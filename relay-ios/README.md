# phone-ios

Native iOS daemon. Same job as `phone-android`: hold a DAT iOS session, listen
to the relay over WS, render peek + expand cards on the HUD, route chip taps
back to the agent.

Implements the protocol in [`../phone-shared/PROTOCOL.md`](../phone-shared/PROTOCOL.md).

## Project layout

Swift sources live in `FaceChat/Sources/`. They're intentionally Xcode-agnostic
so you can drop them into either a SwiftUI App target or a Swift Package.

```
FaceChat/
├── Sources/
│   ├── FaceChatApp.swift     ← @main SwiftUI app entry
│   ├── MainView.swift        ← settings + status UI (one screen)
│   ├── RelayClient.swift     ← URLSessionWebSocketTask wrapper + reconnect
│   ├── HudPresenter.swift    ← DAT session lifecycle + peek/expand renders
│   ├── ChipSet.swift         ← classifier — same patterns as Kotlin twin
│   └── DaemonState.swift     ← @Observable status object, mirrors Android
└── Resources/
    └── Info.plist            ← MWDAT keys, BG modes, opt-out analytics
```

## Setting up the Xcode project

1. Open Xcode → **File** → **New** → **Project** → **iOS** → **App**
   - Product name: `FaceChat`
   - Interface: SwiftUI
   - Language: Swift
2. Delete the auto-generated `ContentView.swift` and `FaceChatApp.swift`.
3. Drag the contents of `FaceChat/Sources/` into the new target (uncheck
   "Copy items if needed", check "Create groups"). Same for
   `FaceChat/Resources/Info.plist` → replace the auto-generated one.
4. **File** → **Add Package Dependencies…** → enter
   `https://github.com/facebook/meta-wearables-dat-ios`, pin to 0.7.0, add
   both `MWDATCore` and `MWDATDisplay` products to the FaceChat target.
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
