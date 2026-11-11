# iOS host

Create the Xcode host on macOS and link the `DecentralertShared` framework produced by:

```bash
gradle -PenableApple=true :shared:linkDebugFrameworkIosSimulatorArm64
```

The SwiftUI host must implement `EmergencyContextCollector` equivalents with Core Location, Core Motion, AVFoundation, Keychain, and `UIApplication.open(tel://112)` using explicit platform permission prompts. Automatic background camera capture is intentionally unsupported.

