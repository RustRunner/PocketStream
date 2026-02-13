  <div align="center">                                                                                                                                                        
    <img src="https://github.com/user-attachments/assets/23005a47-d69c-4d61-b01a-1a88268bfaee" width="128" />
    <h1>PocketStream</h1>                                                                                                                                                     
    <p>UDP/RTSP video streaming for Android</p>             
  </div>

Android app that enables direct ethernet connections to IP cameras. Manages UDP live view, screenshots, recordings, and RTSP re-streaming over WiFi or Cellular — all from the phone.

## Features

- **Ethernet Tethering** — Connects to IP cameras via ethernet tethering with automatic network discovery
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
0. Enable **DHCP** on Ip Camera (Camera needs to recieve an IP assigned by the phone)
1. Use **USB-ethernet adapter** to connect Phone to IP Camera
2. Enable **Ethernet Tethering** in phone's Hotspot & Tethering settings
3. Tap **Connect** — the app discovers the camera on the local network
4. Use **Browser** to access the camera's web config
5. Copy **Phone IP** to camera's web config as UDP stream destination
6. Tap **Stream** to start the live video feed
7. Optionally enable the **RTSP Server** in Settings to re-stream to other devices
8. For remote viewing: install Tailscale on the phone, and share the RTSP URL with users on your Tailscale network — they can open it directly in VLC


  ## Screenshots

  ### Initial Connection
  <table>
    <tr>
      <td><img src="https://github.com/user-attachments/assets/0d8896df-0fb9-40b1-a05c-cc59d9966ac8" width="250" /></td>
      <td><img src="https://github.com/user-attachments/assets/ed30d17f-6a8a-41d5-8e37-f55a0696a129" width="250" /></td>
      <td><img src="https://github.com/user-attachments/assets/8bf72adc-4578-4c43-b551-fca370ef20a7" width="250" /></td>
    </tr>
  </table>

  ### Fullscreen Player
  <table>
    <tr>                                                                                                                                                                      
      <td><img src="https://github.com/user-attachments/assets/ae6d4e21-383d-4cae-b5c3-94121dbdbd4b" width="400" /></td>
      <td><img src="https://github.com/user-attachments/assets/14394c40-4404-4d42-a2f5-e2db30e8481e" width="400" /></td>                                                      
    </tr>                                                                                                                                                                     
    <tr>
      <td><img src="https://github.com/user-attachments/assets/aa550eb7-0f0c-403f-99d4-9804710ed19d" width="400" /></td>
      <td><img src="https://github.com/user-attachments/assets/71d4b6f8-232d-4564-863b-889360546680" width="400" /></td>
    </tr>
  </table>

  ### Settings and Re-streaming
  <table>
    <tr>
      <td><img src="https://github.com/user-attachments/assets/92ec3d5a-7bba-4913-999b-f832d72cc8a8" width="250" /></td>
      <td><img src="https://github.com/user-attachments/assets/6f7ed3f8-0180-45c5-91cb-79d116e20b4b" width="250" /></td>
      <td><img src="https://github.com/user-attachments/assets/fa127fff-a8c0-46c6-a2b4-292b904327cc" width="250" /></td>
    </tr>
    <tr>
      <td><img src="https://github.com/user-attachments/assets/d8c809a7-2bad-4543-9ffb-bb911ca5fc9d" width="250" /></td>
    </tr>
  </table>



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
