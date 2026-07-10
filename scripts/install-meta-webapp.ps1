[CmdletBinding()]
param(
  [string]$Serial = $env:ADB_SERIAL,
  [string]$AppName = "ambient link",
  [string]$AppUrl = "https://agent.public.computer/",
  [int]$WaitSeconds = 2,
  [int]$WaitForUnlockSeconds = 0,
  [switch]$TapConnect
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-Adb {
  param([string[]]$ArgsList)
  $output = & adb @ArgsList 2>&1
  $code = $LASTEXITCODE
  if ($code -ne 0) {
    $text = ($output | Out-String).Trim()
    throw "adb $($ArgsList -join ' ') failed ($code): $text"
  }
  return $output
}

function Get-DeviceSerial {
  param([string]$Requested)
  if ($Requested) {
    $state = Invoke-Adb @("-s", $Requested, "get-state")
    if (($state | Select-Object -First 1) -ne "device") {
      throw "ADB device $Requested is not in device state"
    }
    return $Requested
  }

  $devices = (& adb devices) |
    Where-Object { $_ -match "^\S+\s+device$" } |
    ForEach-Object { ($_ -split "\s+")[0] }

  if ($devices.Count -eq 0) { throw "No USB/ADB device is connected" }
  if ($devices.Count -gt 1) {
    throw "Multiple ADB devices connected; pass -Serial. Devices: $($devices -join ', ')"
  }
  return $devices[0]
}

function Get-Attr {
  param($Node, [string]$Name)
  $attr = $Node.Attributes.GetNamedItem($Name)
  if ($null -eq $attr) { return "" }
  return [string]$attr.Value
}

function Get-BoundsCenter {
  param([string]$Bounds)
  if ($Bounds -notmatch "\[(\d+),(\d+)\]\[(\d+),(\d+)\]") {
    throw "Cannot parse UI bounds: $Bounds"
  }
  $x = [int](([int]$Matches[1] + [int]$Matches[3]) / 2)
  $y = [int](([int]$Matches[2] + [int]$Matches[4]) / 2)
  return @($x, $y)
}

function Get-WindowState {
  param([string]$Device)
  $window = (Invoke-Adb @("-s", $Device, "shell", "dumpsys", "window") | Out-String)
  $focus = [regex]::Match($window, "mFocusedApp=ActivityRecord\{[^}]+ ([^/\s]+)/([^\s]+)")
  $focusText = if ($focus.Success) { "$($focus.Groups[1].Value)/$($focus.Groups[2].Value)" } else { "unknown" }
  $locked = $window -match "mDreamingLockscreen=true" -or $window -match "mShowingLockscreen=true"
  return [pscustomobject]@{ FocusedApp = $focusText; Locked = $locked }
}

if ($AppUrl -notmatch "^https://") {
  throw "Meta Display web apps must use a public https URL; got $AppUrl"
}

$device = Get-DeviceSerial $Serial
Invoke-Adb @("-s", $device, "shell", "pm", "path", "com.facebook.stella") | Out-Null

$encodedName = [System.Uri]::EscapeDataString($AppName)
$encodedUrl = [System.Uri]::EscapeDataString($AppUrl)
$deeplink = "fb-viewapp://web_app_deep_link?appName=$encodedName&appUrl=$encodedUrl"

Write-Host "Launching Meta AI web-app install deep link on $device"
Write-Host "App name: $AppName"
Write-Host "App URL:  $AppUrl"

Invoke-Adb @(
  "-s", $device,
  "shell", "am", "start", "-W",
  "-a", "android.intent.action.VIEW",
  "-d", $deeplink
) | Write-Host

Start-Sleep -Seconds $WaitSeconds

$state = Get-WindowState $device

Write-Host "Focused app: $($state.FocusedApp)"

if ($state.Locked -and $WaitForUnlockSeconds -gt 0) {
  $deadline = (Get-Date).AddSeconds($WaitForUnlockSeconds)
  Write-Host "Phone is locked; waiting up to $WaitForUnlockSeconds seconds for unlock."
  while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 2
    $state = Get-WindowState $device
    if (-not $state.Locked) { break }
  }
  Write-Host "Focused app: $($state.FocusedApp)"
}

if ($state.Locked) {
  Write-Warning "Phone is locked. Meta AI is launched behind the lockscreen; unlock the phone, then rerun this script with -TapConnect."
  exit 2
}

if ($state.FocusedApp -notmatch "^com\.facebook\.stella/") {
  Write-Warning "Meta AI is not focused. The deep link may have been rejected or another app took focus."
}

if (-not $TapConnect) {
  Write-Host "Deep link delivered. Rerun with -TapConnect after unlocking to tap the exact visible confirmation button."
  exit 0
}

$remoteDump = "/sdcard/ambient-link-webapp-install.xml"
Invoke-Adb @("-s", $device, "shell", "uiautomator", "dump", $remoteDump) | Out-Null
$xmlText = (Invoke-Adb @("-s", $device, "shell", "cat", $remoteDump) | Out-String).Trim()
[xml]$xml = $xmlText

$confirmPattern = "^(Connect|Add|Add app|Install|Continue)$"
$candidates = @($xml.SelectNodes("//*[@clickable='true']")) | Where-Object {
  (Get-Attr $_ "text") -match $confirmPattern -or
  (Get-Attr $_ "content-desc") -match $confirmPattern
}

if ($candidates.Count -eq 0) {
  $visible = @($xml.SelectNodes("//*[@clickable='true']")) |
    ForEach-Object {
      $label = (Get-Attr $_ "text")
      if (-not $label) { $label = Get-Attr $_ "content-desc" }
      $label
    } |
    Where-Object { $_ } |
    Select-Object -Unique
  Write-Warning "No exact confirmation button was visible. Clickable labels: $($visible -join ', ')"
  exit 3
}

$button = $candidates | Select-Object -First 1
$label = Get-Attr $button "text"
if (-not $label) { $label = Get-Attr $button "content-desc" }
$center = Get-BoundsCenter (Get-Attr $button "bounds")

Write-Host "Tapping '$label' at $($center[0]),$($center[1])"
Invoke-Adb @("-s", $device, "shell", "input", "tap", [string]$center[0], [string]$center[1]) | Out-Null

Start-Sleep -Seconds $WaitSeconds
Invoke-Adb @("-s", $device, "shell", "dumpsys", "window") |
  Select-String -Pattern "mCurrentFocus|mFocusedApp|mDreamingLockscreen|mShowingLockscreen" |
  Write-Host
