  <div align="center">                                                                                                                                                        
    <img src="https://github.com/user-attachments/assets/23005a47-d69c-4d61-b01a-1a88268bfaee" width="200" />
    <h1>PocketStream</h1>                                                                                                                                                     
    <p>UDP/RTSP video streaming for Android</p>             
  </div>

PocketStream is a professional-grade Android video utility for direct IP camera monitoring and relay. It transforms your
  phone into a portable control center, offering low-latency UDP/RTSP playback, high-quality media capture, and a secure,
  authenticated RTSP re-streaming server for sharing local camera feeds over WiFi or Cellular.

## Features

- **Direct Ethernet Discovery** — Automates subnet discovery and point-to-point IP detection for Ethernet-tethered cameras, bypassing the need for a local router.
- **Live View** — Real-time video streaming powered by LibVLC with fullscreen landscape mode.
- **Screenshots & Recording** — Capture stills or record video directly from the live stream.
- **Camera Config** — Launch the camera's web interface for settings from within the app.
- **RTSP Re-streaming** — Re-broadcast the camera feed as a token-protected RTSP stream, accessible to other devices over WiFi or cellular.
- **Tailscale VPN Support** — With Tailscale installed, the app automatically detects the VPN IP and tunnels the RTSP stream through it, providing an additional layer of security.
- **Bandwidth & Uptime Monitoring** — Real-time tracking of server uptime and outbound bandwidth (kbps/Mbps), providing instant feedback on stream health and network performance.
  
## Prerequisites

- Android 8.0+ (API 26)
- **USB-to-Ethernet Adapter:** Required for the physical connection.
- **IP Camera Setup:** Ensure the camera is set to **DHCP** (so the phone can assign it an IP).
- **VLC Media Player:** Recommended for remote viewing on other devices.
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
## General Setup (Common to all modes)
1. **Connect:** Use the USB-Ethernet adapter to connect your phone to the camera.
2. **Enable Tethering:** In Android Settings, go to **Network & Internet > Hotspot & Tethering** and toggle **Ethernet Tethering** ON.
3. **Discover:** Open PocketStream and tap **Connect**. The app will find the camera's IP on the tethered network.
4. **Configure:** (Optional) Tap **Browser** to access the camera’s internal web interface.
   
---

## <img src="https://github.com/user-attachments/assets/8496f45e-fc56-437f-8c12-5d708c0bcb44" height="28" style="vertical-align: middle;" alt="RTSP Icon"> Mode A: RTSP Input (Standard)

*Best for modern cameras (Amcrest, Hikvision, Dahua, etc.)*

1. **Set Credentials:** Open the **Settings Drawer** and enter the camera’s RTSP Port, Path (e.g., `/live/ch0`), and login credentials.
2. **Start Stream:** Tap **Launch Stream** to pull the video from the camera.
   
## <img src="https://github.com/user-attachments/assets/b939923f-f954-452d-969d-fe60e0993f68" height="28" style="vertical-align: middle;" alt="UDP Icon"> Mode B: Raw UDP Input (Legacy/Fallback)

*Best for specialized hardware or cameras that "Push" video.*

1. **Get Phone IP:** Note the **Local IP** displayed in the "Video Stream" section of the app.
2. **Configure Camera:** In the camera’s web interface, set the **UDP Destination IP** to your phone’s IP and port `8600`.
3. **Start Stream:** Tap **Launch Stream** to listen for incoming video.

---

## Remote Viewing & Re-streaming
PocketStream can act as a secure gateway to share your local camera with the world.

1. **Enable Server:** In Settings, toggle **RTSP Server** to ON.
2. **Security:** A unique **Stream Token** is generated automatically. Use the "Regenerate" button to cycle this token at any time.
3. **Tailscale VPN:**
    - Install and sign into **Tailscale** on your phone.
    - PocketStream will detect your VPN IP automatically.
    - Share the **RTSP URL** (found in the RTSP Server card) with anyone on your Tailscale network.
    - They can paste that URL into VLC to watch the live stream remotely.

---

  ## Plug and play

<img width="1524" height="1137" alt="Screenshot from 2026-02-17 23-37-24" src="https://github.com/user-attachments/assets/6c5833ee-c03f-4433-8839-d8171b220cd0" />

  ## Screenshots

  ### Initial Connection
  <table>
    <tr>
      <td><img src="https://github.com/user-attachments/assets/d00af848-cafd-4c48-8478-1a52484ebf99" width="320" /></td>
      <td><img src="https://github.com/user-attachments/assets/3e72269d-9ac7-48a4-b016-b10fdd6d9817" width="320" /></td>
      <td><img src="https://github.com/user-attachments/assets/f694df60-229d-440d-8e11-35a425136273" width="320" /></td>
    </tr>
  </table>

  ### Fullscreen Player
  <table>
    <tr>    
      <td><img src="https://github.com/user-attachments/assets/8ff1794f-3b1b-4c20-a3f1-2ba4d064fa4e" width="500" /></td>
      <td><img src="https://github.com/user-attachments/assets/05fc2f76-1fda-4e43-85a5-ac33644fce55" width="500" /></td>                                                  
    <tr>
      <td><img src="https://github.com/user-attachments/assets/4426c37d-f102-4fd6-9371-6cc46af01377" width="500" /></td>
      <td><img src="https://github.com/user-attachments/assets/82a8ac4a-9516-40bf-865a-850cb3a96ffc" width="500" /></td>  
  </table>

### Re-streaming
  <table>                                                                                                                                                                   
    <tr>                                                                                                                                                                      
      <td><img src="https://github.com/user-attachments/assets/34a4a3d4-096b-4081-bfb8-3cca79ecd8d3" width="320" /></td>
      <td><img src="https://github.com/user-attachments/assets/1d4adfec-c394-4260-bc37-254aa26dfe94" width="320" /></td>                                                  
    </tr> 
  </table>

### Receiving in VLC
  <table>    
    <tr>
      <td><img src="https://github.com/user-attachments/assets/fa127fff-a8c0-46c6-a2b4-292b904327cc" width="320" /></td>
      <td><img src="https://github.com/user-attachments/assets/d8c809a7-2bad-4543-9ffb-bb911ca5fc9d" width="320" /></td>
    </tr>
  </table>
  
### Receiving in ATAK
  <table>                                                                                                                                                                     
    <tr>                                                                                                                                                                      
      <td><img src="https://github.com/user-attachments/assets/34d89e12-6f0c-44c4-ad5e-9314781276b2" width="500" /></td>
      <td><img src="https://github.com/user-attachments/assets/607d292d-f7ce-46d9-bb68-dad769a75252" width="500" /></td>                                                      
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
