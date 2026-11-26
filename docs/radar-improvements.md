# Radar project: future improvements

Ideas to do later. Nothing here has been changed yet.

## 1. Faster refresh rate

**Requested:** 26 Nov 2026, after remote viewing (app over EMQX, phone off Wi-Fi) was confirmed
working. **Status:** noted only; refresh rate deliberately left as is for now.

What sets the rate today:

| Stage | Current behaviour | Where |
|---|---|---|
| Radar | Sends about 10 frames/s (spec says 10 Hz; about 11 Hz seen on the display) | LD2450 firmware |
| ESP32 → home network | Forwards every frame over the WebSocket | `esp32-radar-display/src/RadarNetwork.cpp` (`publishPending`) |
| ESP32 → EMQX | Forwards every frame while someone watches, but **at most 1 frame/s when nobody is detected** (to save cloud traffic) | same, `publishPending` |
| Network task | Loops every 2 ms; frames that arrive while it is busy (e.g. MQTT/TLS) replace each other, so only the newest is sent | same, `networkTask` |
| App | Redraws on each frame received; dots jump from one position to the next | `radar-viewer-android/.../RadarViewModel.kt`, `ui/RadarScreen.kt` |
| Remote path | Each frame goes ESP32 → EMQX (us-east-1) → phone, adding internet latency and jitter | — |

Options to look at:
- **Smooth motion in the app** (interpolate or animate between positions). This makes it feel faster
  without sending more data. Probably the biggest visible gain.
- **Check the actual rate end to end.** The app shows Hz; compare home vs. remote, and check
  whether frames are dropped in the ESP32's network task.
- **Bundle frames for remote:** send, say, 2–3 frames per MQTT message so the app can replay them
  evenly (less overhead per frame, smoother at the same data volume).
- **Lower latency on the remote path:** the EMQX region versus where the phone is; MQTT keep-alive and
  TCP settings.
- The radar itself is limited to about 10 Hz; going beyond that needs interpolation, not more data.
- Keep an eye on EMQX traffic: more frames per second while watching means more data. See the
  traffic notes in `esp32-radar-display/README.md`.

## Open checks from hardware testing

- **Left/right direction:** walk to your left while facing the radar and confirm the dot moves the
  same way on the screen and in the app (`kMirrorX` in `esp32-radar-display/src/config.h`).
- **Speed sign:** confirm whether walking towards the radar shows + or − speed (the vendor files
  don't say).
