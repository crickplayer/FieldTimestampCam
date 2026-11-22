# Super Starter battery display (ESP32-C3 Super Mini + 1.28" round GC9A01)

Shows the live readings of a Leagend **JS300 / JS200 "Super Starter"** jump-start
module on a small round screen:

* **Left:** car battery voltage, percent, status (Normal / Low / Charging / Engine on /
  Not connected) and a percent gauge.
* **Right:** starter pack voltage, percent, status (Idle / Charging / Discharging,
  plus over-temperature, too-cold and fault warnings) and a gauge.
* **Bottom:** starter temperature and link state (`LIVE`, `BROADCAST`, `Searching...`).

The firmware is **read-only**. It never sends boost/start, firmware-update, clock,
battery-type or history commands.

## Wiring (same as the Schematik build)

| Screen pin | ESP32-C3 Super Mini |
|---|---|
| VCC | 3V3 |
| GND | GND |
| SCL / SCK | GPIO 4 |
| SDA / MOSI | GPIO 6 |
| CS | GPIO 7 |
| DC | GPIO 3 |
| RST / RES | GPIO 10 |
| BL / BLK (if present) | 3V3 |

Power the Super Mini from a 5 V USB car adapter on its USB-C port.

## Flashing

### Option A: from a browser (Windows PC, or Android with Chrome and USB-OTG)
1. Open <https://espressif.github.io/esptool-js/> in Chrome or Edge.
2. Plug the Super Mini in with a **data** USB-C cable. Click **Connect** and choose the
   USB JTAG/serial port.
   If it doesn't show up, hold **BOOT**, tap **RESET**, then release **BOOT**.
3. Set Flash Address **0x0** and select `firmware/booster-display-esp32c3-merged.bin`.
4. Click **Program**. When it finishes, press **RESET**.

### Option B: PlatformIO (VS Code)
Open this folder and click **Upload**. To watch the log, click **Monitor** (115200 baud).

## First run
1. Close the Super Starter phone app, or turn the phone's Bluetooth off.
   The booster accepts only one Bluetooth connection at a time.
2. Power the ESP32 near the car. Status goes `Searching...` → `Connecting...` →
   `Handshake...` → `LIVE`.
3. While it is still connecting, the car-battery side may already show values with
   `BROADCAST`. Those come from the booster's advertisements, before any connection.

The serial monitor shows every step (`[scan]`, `[ble]`, `[tx]`, `[rx]`, `[proto]`).
If something fails, those lines tell you where.

## Settings (top of `src/main.cpp`)
* `PINNED_MAC`: lock onto one booster, e.g. `"3c:ab:72:4e:7e:3d"`.
* `CONNECT_FOR_LIVE = false`: broadcast-only mode. The ESP32 never connects, so the
  phone app keeps working, but only car-battery data is shown.
* `SEND_GENUINE_REPLY`: reply to the booster's "genuine?" question the way the app does.

## Protocol notes (recovered from the Android app `com.dc.js200`)
* Service `FFF0`. Write to `FFF3` (write without response). Notifications on `FFF4`.
  The app requests MTU 247.
* AES-128. Key = `"leagend"` + `FF FE` + `"010000@"`. Each 16-byte block is
  encrypted on its own (CBC with zero IV per block). Commands are zero padded to 16 bytes.
* Connect sequence used by the app: `D1550101` (version) → if reply byte 5 is `00`,
  send `D155020001` ("genuine", non-Chinese UI) → `D15509<time>` → `D15508<battery type>`
  → history `D15505…`/`D1550A…` → `D15504` (start count) → `D15507` (live data).
  This firmware sends only `D1550101`, `D155020001`, `D15504` and `D15507`.
* Live frame `D1 55 07` (34 bytes, 3 blocks). Byte numbers:
  `5` car status, `6` car %, `7-8` car V×100, `9` temp sign (1 = negative), `10` temp °C,
  `11` starter status, `12` starter %, `13-14` starter V×100, `15` flags
  (`0x40` battery not connected, `0x20` low-temp protection, `0x10` over-temp,
  `0x04` starter on, `0x01` starter fault), `24` = `01` means engine on,
  `30-31` starter full V×100.
* Advertisement: manufacturer data AD (length `0x11`/`0x12`). The 16 bytes decrypt to
  `0-5` MAC, `8` car %, `9` car status, `11-12` car V×100, `13` crank state.
