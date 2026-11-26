# LD2450 Radar Viewer (Android)

**Standalone Android app** that shows the HLK-LD2450 radar live on your phone, at home and away.
It is a separate app from Field Timestamp Cam (own package `com.radarviewer.ld2450`, own build).

It needs the ESP32 radar display (`../esp32-radar-display/`) connected to your Wi-Fi.

## How it connects

| Where you are | How | Notes |
|---|---|---|
| **On the same Wi-Fi as the radar** | Directly to the ESP32 (`ws://<ip>:81/`) | Found automatically (mDNS `_ld2450._tcp`). Fast; works without internet |
| **Anywhere else** | Through your EMQX broker (MQTT over TLS, port 8883) | Needs the broker details in Settings |

It picks automatically. On Wi-Fi it looks for the radar for 4 seconds, then falls back to EMQX.
While remote, it checks every 30 seconds whether the radar is reachable directly and switches
back when it is.

To save data, the app is connected **only while it is on screen**. While remote it tells the
ESP32 every 30 s that someone is watching (`ld2450/<id>/watch`), and the ESP32 streams to EMQX
only while that keeps arriving.

## Install

1. Open the repository's **Actions** tab → **LD2450 Radar Viewer (Android)** → the latest green run.
2. Download **LD2450-Radar-Viewer-apk** (on your phone, or on a PC and copy it over), unzip it
   and open `app-debug.apk`.
3. Android asks to allow installing from that app (browser or Files). Allow it, then install.
   It is a debug build signed by GitHub's build, so Play Protect may warn about an unknown
   developer. Choose **Install anyway**.

## Set up

1. Open the app at home on your Wi-Fi. It should find the radar and show **Home Wi-Fi · 192.168.x.x**.
   This also teaches the app your radar's device ID.
2. Tap **⚙ Settings** and enter your EMQX details. Use the **phone** user you created in EMQX
   (e.g. `radar-phone-app`), not the ESP32's user:
   - Broker address: as shown in the EMQX console, e.g. `xxxxxxxx.ala.us-east-1.emqxsl.com`
   - Port: `8883`
   - Username and password of the phone user
3. Tap **Save**. To try remote viewing at home, turn off Wi-Fi on the phone. The app should
   switch to **Remote · EMQX** within a few seconds.

The password is stored encrypted on the phone (Android Keystore) and is never sent anywhere
except to your broker over TLS. The app trusts only the root certificates bundled in
`app/src/main/res/raw/radar_root_cas.pem` (the same set as the ESP32).

## Screen

- **Top:** how it's connected (green Home Wi-Fi / cyan Remote / yellow Connecting / red Not
  connected), the radar's device ID and firmware.
- **Middle:** the same top-down view as the ESP32's screen, with up to 3 people and trails.
- **Bottom:** distance, angle and speed for each person.
- **Status messages:** "Waiting for the radar…" (remote, before the first frame), "Radar offline"
  (the ESP32 dropped off the broker), "No radar data" (connected, but the radar is silent).

The screen stays on while the app is open.

## Build it yourself

GitHub Actions (`.github/workflows/radar-viewer-android.yml`) runs the unit tests and builds the
APK on every change in this folder. Locally (JDK 17+, Android SDK 35):

```
./gradlew testDebugUnitTest assembleDebug
```

## Code

| File | What it does |
|---|---|
| `RadarViewModel.kt` | Chooses home vs. remote, reconnects, keeps the live state |
| `LanClient.kt`, `LanDiscovery.kt` | Home network: WebSocket to the ESP32, mDNS discovery |
| `CloudClient.kt`, `Tls.kt` | Remote: MQTT (Eclipse Paho) over TLS with the bundled root certificates |
| `RadarMessages.kt` | Parses the ESP32's JSON messages; MQTT topic names |
| `SettingsStore.kt` | Encrypted settings |
| `ui/` | Jetpack Compose screens: radar view and settings |
