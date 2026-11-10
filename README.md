# SP3000 Remote

Android Bluetooth HID media remote for Astell&Kern SP3000.

Controls: Previous, Play/Pause, Next, Volume Down, Volume Up, Mute.

Built automatically by GitHub Actions. Download the APK from the latest successful Actions run artifact named `SP3000Remote-APK`.

An experimental native iPhone version lives in [`ios/`](ios/README.md). It uses BLE HID rather than Android's classic Bluetooth HID transport. Real iPhone/SP3000 interoperability is not yet verified. The iOS workflow produces an unsigned IPA for signing and sideloading; it does not publish to TestFlight or the App Store.
