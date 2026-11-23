# Repository layout

| Folder | What it is |
|---|---|
| `app/` (+ Gradle files at the root) | Field Timestamp Cam, the existing Android camera app |
| `esp32-radar-display/` | **HLK-LD2450 radar → ESP32 → 2.8" display** firmware (PlatformIO). See its README |
| `docs/hlk-ld2450/` | Radar vendor files and `ANALYSIS.md` (protocol, Bluetooth, display, wiring) |
| *(later)* | Android radar viewer app (live tracking on the phone) |

# Field Timestamp Cam (Android)

## Build the `.apk` on Your Phone with GitHub Actions (No Laptop Needed)
1. Unzip `FieldTimestampCam-source-v2.zip` on your phone.
2. Create a new repository on [GitHub](https://github.com) and upload all extracted files (including the hidden `.github/workflows/build-apk.yml` file).
3. Open the **Actions** tab in your GitHub repository.
4. Wait ~2 minutes for **Build Android APK** to finish with a green checkmark.
5. Tap the completed workflow run, scroll down to **Artifacts**, and download `FieldTimestampCam-debug-apk.zip`.
6. Unzip it to get `app-debug.apk` and tap it to install on your Android phone.
