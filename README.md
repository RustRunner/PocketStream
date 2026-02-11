# PocketStream

Android app that facilitates direct ethernet connections to IP cameras. Manages live view, screenshots, recordings, and RTSP re-streaming over WiFi or Cellular — all from the phone.

## Features

- **Ethernet Tethering** — Connects to IP cameras via USB/ethernet tethering with automatic network discovery
- **Live View** — Real-time UDP video streaming powered by LibVLC with fullscreen landscape mode
- **Screenshots & Recording** — Capture stills or record video directly from the live stream
- **Camera Config** — Launch the camera's web interface for settings from within the app
- **RTSP Re-streaming** — Re-broadcast the camera feed as a token-protected RTSP stream, accessible to other devices over WiFi or cellular
- **Tailscale VPN Support** — With Tailscale installed, the app automatically detects the VPN IP and tunnels the RTSP stream through it. Any user on the same Tailscale network can paste the RTSP URL (with auth token) into VLC to watch the live stream remotely.
- **Bandwidth & Uptime Monitoring** — Real-time stats for the RTSP server

## Requirements

- Android 8.0+ (API 26)
- USB-to-Ethernet adapter (or compatible tethering setup)
- IP camera with UDP stream output
- [Tailscale](https://tailscale.com/) (optional, for remote RTSP access over VPN)

## Build

> **Note:** This app was developed on an NVIDIA DGX Spark running ARM64 (aarch64) Linux. The build toolchain — including a custom ARM64-native AAPT2 binary — is configured for that architecture. If you're building on a standard x86_64 machine, you may need to remove or adjust the `android.aapt2FromMavenOverride` line in `gradle.properties`. Your mileage may vary.

```bash
./gradlew assembleDebug
```

The APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

For a signed release build, create a `keystore.properties` file in the project root:

```properties
storeFile=path/to/keystore.jks
storePassword=your_store_password
keyAlias=your_key_alias
keyPassword=your_key_password
```

Then run:

```bash
./gradlew assembleRelease
```

## Usage

1. Connect an IP camera to your phone via USB-ethernet adapter
2. Enable **Ethernet Tethering** in your phone's Hotspot & Tethering settings
3. Tap **Connect** — the app discovers the camera on the local network
4. Use **Browser** to access the camera's web config if needed
5. Tap **Stream** to start the live video feed
6. Optionally enable the **RTSP Server** in Settings to re-stream to other devices
7. For remote viewing: install Tailscale on the phone, and share the RTSP URL with users on your Tailscale network — they can open it directly in VLC

## License

This project is licensed under the [GNU Affero General Public License v3.0](LICENSE).

### Third-Party Libraries

| Library | License |
|---|---|
| [LibVLC](https://www.videolan.org/vlc/libvlc.html) | LGPL-2.1+ |
| AndroidX | Apache 2.0 |
| Material Components | Apache 2.0 |
| Kotlin Coroutines | Apache 2.0 |
| OkHttp | Apache 2.0 |
