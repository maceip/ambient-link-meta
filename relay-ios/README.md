# relay-ios

Native iOS daemon. Same job as `relay-android`: hold a DAT iOS session, listen
to the relay over WS, render peek + expand cards on the HUD, route chip taps
back to the agent.

Implements the protocol in [`../protocol/PROTOCOL.md`](../protocol/PROTOCOL.md).

## Project layout

Swift sources live in `AmbientLink/Sources/`. They're intentionally Xcode-agnostic
so you can drop them into either a SwiftUI App target or a Swift Package.

```
AmbientLink/
├── Sources/
│   ├── AmbientLinkApp.swift     ← @main SwiftUI app entry
│   ├── MainView.swift        ← settings + status UI (one screen)
│   ├── RelayClient.swift     ← URLSessionWebSocketTask wrapper + reconnect
│   ├── HudPresenter.swift    ← DAT session lifecycle + peek/expand renders
│   ├── ChipSet.swift         ← classifier — same patterns as Kotlin twin
│   ├── DaemonState.swift     ← @Observable status object, mirrors Android
│   └── DebugFireWidget.swift ← DEBUG-only direct sendContent round-trip
└── Resources/
    └── Info.plist            ← MWDAT keys, BG modes, opt-out analytics
```

## Setting up the Xcode project

1. Open Xcode → **File** → **New** → **Project** → **iOS** → **App**
   - Product name: `AmbientLink`
   - Interface: SwiftUI
   - Language: Swift
2. Delete the auto-generated `ContentView.swift` and `AmbientLinkApp.swift`.
3. Drag the contents of `AmbientLink/Sources/` into the new target (uncheck
   "Copy items if needed", check "Create groups"). Same for
   `AmbientLink/Resources/Info.plist` → replace the auto-generated one.
4. **File** → **Add Package Dependencies…** → enter
   `https://github.com/facebook/meta-wearables-dat-ios`, pin to 0.7.0, add
   both `MWDATCore` and `MWDATDisplay` products to the AmbientLink target.
   Also add the shared core via **Add Local…** → `../../ambient-link-core/core-apple`
   and link `AmbientLinkCore` (this is where the `GlassLink`/`Session` contract now
   lives — the in-repo copy was removed). `RelayClient.swift` here stays the
   vendor-specific WS client.
5. In target settings:
   - **Signing & Capabilities** → add **Background Modes** →
     check **Background processing** (so the WS stays alive while the user
     wears the glasses)
   - **Info** → add `NSBluetoothAlwaysUsageDescription` ("Needed to talk to
     your Meta Display glasses")
6. Build & run on a real iPhone (the DAT SDK requires Bluetooth, won't work
   on the simulator).

## DAT app ID / client token

Add a `local.xcconfig` (gitignored) with:

```
MWDAT_APPLICATION_ID = <your-app-id>
MWDAT_CLIENT_TOKEN   = <your-client-token>
```

Reference it from your build settings (Project → Info → Configurations).
Then read in `Info.plist`:

```xml
<key>MWDAT</key>
<dict>
  <key>ApplicationID</key>
  <string>$(MWDAT_APPLICATION_ID)</string>
  <key>ClientToken</key>
  <string>$(MWDAT_CLIENT_TOKEN)</string>
</dict>
```

## Default relay URL

Edit `RelayClient.defaultURL` in `Sources/RelayClient.swift` or pass via
`UserDefaults` from `MainView`.
