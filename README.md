# Timestamp Camera (FieldTimestampCam, Android)

A camera that burns a **live watermark** into the preview, photos and videos:
date/time (to the millisecond), GPS coordinates, heading, address, altitude,
speed, compass, mini map, custom text/emoji and a logo. Everything you see in the
viewfinder is exactly what gets saved, and the watermark updates on every frame.

## Features
- **61 timestamp formats**, including millisecond (`.SSS`) formats; the overlay
  is redrawn on every video frame, so recordings show a running 0.001 s clock
- Font (16 typefaces), font color, font size and text opacity
- **7 positions**: top left/center/right, bottom left/center/right, center (for
  the text block, compass, map and logo independently)
- Background: none / drop shadow / outline / solid box, with color + opacity
- GPS coordinates (decimal, DMS or signed), heading (e.g. `290° W`), automatic
  address (neighborhood, street, city, country or full address)
- **Pick the location**: 📍 button → automatic address, nearby places, or your own text
- Altitude and speed (metric or imperial)
- Custom text and emoji (multi-line), e.g. "Good day at the zoo 🦁"; photo index number
- Live compass (position + size)
- Mini map (OpenStreetMap) with adjustable scale (zoom), size, opacity and position
- Custom logo image (size, opacity, position)
- Record video with or without audio (🎤 button, can mute mid-recording)
- **Battery saver mode**: 🔋 button or **double-tap** the preview; brightness is
  0–100 % of normal (set in settings)
- Shutter sound on/off
- Change effects and **switch cameras while recording** (persistent recording)
- **Capture photos while recording**
- Portrait and landscape (auto-rotate or locked), 4:3 / 16:9, video
  resolution 4K / 1080p / 720p / 480p
- Save straight to the **SD card** (Settings → Advanced, Android 10+)
- Tap to focus, pinch / slider zoom, flash off/auto/on/torch, GPS EXIF tags

Files are saved to `DCIM/TimestampCamera`.

## Build
```
./gradlew assembleDebug      # or: gradle assembleDebug
```
The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions
(`.github/workflows/build-apk.yml`) builds it on every push and uploads it as the
`FieldTimestampCam-debug-apk` artifact.

`./gradlew testDebugUnitTest` renders sample watermarks (Robolectric) to
`app/build/overlay-renders/*.png`.
