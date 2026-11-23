# ESP32 radar display (small screen)

**This folder is only the firmware for the ESP32 and the 2.8" display.**
The Android viewer app comes later and will live in its own folder.

Shows people detected by the HLK-LD2450 radar live on the 2.8" display:

- **Top bar:** radar firmware version, how many people are detected, and the update rate
  (about 10 Hz). Shows **NO DATA** in red if the radar stops sending.
- **Middle:** top-down view. The radar is the white triangle at the bottom. The green fan is
  its ±60° field of view, with rings every metre out to 6 m. Each person is a numbered
  coloured dot (1 cyan, 2 yellow, 3 magenta) with a fading trail.
- **Bottom:** one row per person: distance, angle (0° = straight ahead) and speed.

## Hardware

- ESP32-DevKitC-32E (ESP32-WROOM-32E)
- HLK-LD2450 radar
- 2.8" SPI TFT 240×320, ILI9341 (touch not used)
- Micro-USB **data** cable (some cables only charge)

## Wiring

| From | To ESP32 pin (as labelled on the board) |
|---|---|
| Radar 5V | 5V |
| Radar GND | GND |
| Radar Tx | 16 |
| Radar Rx | 17 |
| Display VCC | 5V |
| Display GND | GND |
| Display CS | 22 |
| Display RESET | 4 |
| Display DC | 21 |
| Display SDI(MOSI) | 23 |
| Display SCK | 18 |
| Display LED | 32 |
| Display SDO(MISO) | not connected |
| Display T_IRQ, T_DO, T_DIN, T_CS, T_CLK | not connected (touch not used) |

Both 5V wires go to the same 5V pin, so join them on a breadboard rail. Any GND pin works.
Leave the J1 pads on the back of the display open.

## Three ways to flash

| | Needs | Best for |
|---|---|---|
| **1. GitHub build + Espressif web flasher** (main method) | Chrome or Edge | Flashing a ready-made build |
| **2. One-click flasher page** (backup) | Chrome or Edge | Same as 1, in one click |
| **3. PlatformIO** (below) | VS Code + PlatformIO | Changing settings or code yourself |

### 1. GitHub build + Espressif web flasher
1. Open the repository's **Actions** tab → **ESP32 radar display firmware** → the latest green run.
2. Under **Artifacts**, download **esp32-radar-display-firmware** and unzip it.
3. In Chrome or Edge open `https://espressif.github.io/esptool-js/`, click **Connect** and
   pick the **CP210x** port.
4. Add the file **`radar-display-merged.bin`** at flash address **`0x0`**, then click **Program**.
5. When it's done, press **EN** on the ESP32.

### 2. One-click flasher page (backup)
The page source is `web-flasher/index.html`. GitHub Actions publishes it, with the latest
firmware, to GitHub Pages at **https://crickplayer.github.io/FieldTimestampCam/**.
Open that page in Chrome or Edge, click **Install firmware** and pick the CP210x port.

One-time setup on GitHub:
1. **Settings → Pages → Build and deployment → Source: GitHub Actions.**
2. The page is published from the `main` branch. Until this work is merged into `main`,
   either run the workflow by hand (**Actions → ESP32 radar display firmware → Run workflow**,
   choose this branch) after allowing the branch under **Settings → Environments →
   github-pages → Deployment branches**, or merge the work into `main`.

### 3. PlatformIO on Windows 10/11

1. **Install VS Code** from code.visualstudio.com.
2. In VS Code, open **Extensions** (Ctrl+Shift+X), search for **PlatformIO IDE**, install it,
   and restart VS Code when asked. The first start takes a few minutes while it downloads tools.
3. **USB driver:** plug in the ESP32 and open **Device Manager → Ports (COM & LPT)**. You
   should see *Silicon Labs CP210x USB to UART Bridge (COMx)*. If not, install the driver
   from `docs/hlk-ld2450/common software/Serial port driver.zip` (folder `CP210x_Windows_Drivers`,
   run `CP210xVCPInstaller_x64.exe`), then replug.
4. **Get the code:** download the repository from GitHub (Code → Download ZIP, on branch
   `ccr-7b0e112a-ekzu5e`) and unzip it.
5. In VS Code: **File → Open Folder…** and choose the **`esp32-radar-display`** folder
   (this folder, not the repository root).
6. Click the **→ (Upload)** arrow in the blue status bar at the bottom. PlatformIO downloads
   the ESP32 tools and the display library the first time, then builds and flashes.
   If it stops at `Connecting....`, hold the **Boot** button on the ESP32 until it continues.
7. Click the **plug icon (Serial Monitor)** in the status bar to see debug output.

## What you should see

1. "LD2450 RADAR / Connecting to radar..." for about a second.
2. The green fan with the top bar showing e.g. `V2.04.23101915` and `0 people 10.0 Hz`.
3. Walk in front of the radar: a dot appears and follows you.

Serial monitor (115200 baud):
```
[boot] LD2450 radar display
[radar] firmware V2.04.23101915
[radar] tracking mode: multi target
T1 x= -782 y= 1713 mm  d=1.88m  a=-24.5deg  v= -16cm/s   T2 --   T3 --
```

## Troubleshooting

| Symptom | Check |
|---|---|
| Screen stays white or black | Display wiring (CS 22, DC 21, RESET 4, SDI 23, SCK 18), VCC to 5V |
| Backlight on but no picture | SDI/SCK swapped, or CS/DC swapped |
| Red **NO DATA** | Radar Tx → **16**, radar 5V and GND. The radar needs >200 mA, so use a good USB port or cable |
| Serial: `no reply to 'enable configuration'` but the dots work | Radar Rx ← **17** (only needed to read firmware/settings) |
| People appear on the wrong side when you walk left/right | Set `kMirrorX = true` in `src/config.h` |
| Picture upside down | Set `kScreenRotation = 3` in `src/config.h` |
| Upload fails / no COM port | Data cable, CP210x driver, hold **Boot** while it says `Connecting` |

## Settings

Change these in `src/config.h`: mirror left/right, screen rotation, trail length, timeout,
serial debug output. Display pins are in `platformio.ini` (`build_flags`).

## Code layout

| Path | What it does |
|---|---|
| `lib/LD2450/` | Radar protocol: decodes radar frames and builds commands. Plain C++ with no ESP32 code, so it is unit-tested on a PC and is the reference for the Android app's decoder later |
| `src/main.cpp` | Reads the radar on UART2 and drives the screen |
| `src/RadarView.*` | Everything drawn on the display |
| `src/config.h` | User settings |
| `web-flasher/` | The one-click browser flasher page (published to GitHub Pages by CI) |
| `test/` | Protocol tests using the vendor's example data: `pio test -e native` |

GitHub Actions (`.github/workflows/esp32-radar-display.yml`) runs the tests and builds the
firmware on every push that touches this folder. The build output includes
`radar-display-merged.bin`, a single image that can be flashed at address 0x0.
