# HLK-LD2450 — Step 1 analysis (reference spec for the ESP32 tracker and Android viewer)

Sources: the vendor files in this folder. Where the PC tool was examined, it was done with
rea (Ghidra) on `HLK-LD2450_TOOL_English.zip` → `ICLM_DataProcess.dll`
(sha256 `c688a20b…6c76`), without running it.

## 1. Module hardware

| Item | Value |
|---|---|
| Radar | 24–24.25 GHz FMCW, 1 TX / 2 RX, 250 MHz sweep bandwidth |
| Targets | Up to 3 at the same time (multi-target mode is the default) |
| Range / field of view | 6 m max; azimuth ±60°, elevation ±35° |
| Update rate | 10 frames/s |
| Power | **5 V**, supply must deliver > 200 mA (120 mA average) |
| Logic level | **3.3 V** UART (connects straight to the ESP32) |
| UART | **256000 baud**, 8N1 (factory default) |
| Pins | 5V, GND, Tx, Rx (pin header and socket variants) |
| Mounting | Wall mount at 1.5–2 m high, antenna facing the area |
| Size | 15 × 44 mm |
| Bluetooth | Built in, **on by default** (name contains `HLK-LD2450`) |

Placement notes from the manual: keep it rigid (vibration shows up as motion), no metal
in front of it, avoid fans, curtains or plants moving in view, and the back lobe can see
movement behind it (a metal plate behind the module helps). For an enclosure, use a flat
plastic cover, ideally about 3.9 mm thick in ABS, with 12.4 or 18.6 mm between the
antenna and the cover.

## 2. Data frame (radar → host), 30 bytes, 10 Hz

```
AA FF 03 00 | T1 (8 bytes) | T2 (8 bytes) | T3 (8 bytes) | 55 CC
```
Each target is 4 little-endian uint16 values:

| Offset | Field | Encoding |
|---|---|---|
| 0 | X (mm) | bit15 = 1 → positive, 0 → negative; magnitude = low 15 bits |
| 2 | Y (mm) | same encoding (in practice always positive) |
| 4 | Speed (cm/s) | same encoding (negative = moving toward the radar) |
| 6 | Distance-gate resolution (mm) | plain uint16 |

An all-zero 8-byte slot means "no target".

```c
static int16_t ld_s15(uint16_t v) { return (v & 0x8000) ? (int16_t)(v & 0x7FFF) : -(int16_t)(v & 0x7FFF); }
```
Check against the documented example, verified with this decoder:
`AA FF 03 00 0E 03 B1 86 10 00 40 01 00…00 55 CC` → T1 X = −782 mm, Y = 1713 mm,
v = −16 cm/s, gate = 320 mm. (The module manual says "360" here, which is a typo; 0x0140 = 320.)

Derived values: distance = √(x² + y²); angle = atan2(x, y) (0° = straight ahead).

**Bug in the vendor C reference** (`2450 serial port parsing code representation.pdf`):
for negative X it prints the raw value instead of negating it, and it checks `i+6 < ret`
but reads up to `i+7`. Do not copy it. Use the decoder above.

## 3. Command / ACK frames (host ↔ radar)

```
FD FC FB FA | len (uint16 LE) | cmd word (uint16 LE) | value… | 04 03 02 01
```
`len` counts the command word plus the value. The ACK uses the same framing; its command
word is **the sent command | 0x0100**, followed by a uint16 status (0 = OK, 1 = fail).

rea on the PC tool (`RadarProtocol_ComposeCmdFrame`, `FUN_1800953d0`) confirms that the
tool builds exactly this frame and validates the reply in the same way: 6-byte header and
length, then `len + 4` bytes, tail `04 03 02 01`, ACK cmd == cmd + 0x100.

Every command must be wrapped in **Enable config (0x00FF, value 0x0001)** …
**End config (0x00FE)**. Per the protocol, the radar returns to normal working mode (data frames) after End config.

| Cmd | Purpose | Value → reply |
|---|---|---|
| 0x00FF | Enable config | `01 00` → status, protocol ver, buffer size |
| 0x00FE | End config | — |
| 0x0080 / 0x0090 | Single / multi-target tracking | — |
| 0x0091 | Query tracking mode | → 1 = single, 2 = multi |
| 0x00A0 | Firmware version | → type, major, minor (e.g. V1.02.22062416) |
| 0x00A1 | Set baud (index 1–8: 9600 … 256000 = 7, 460800 = 8) | applied after restart |
| 0x00A2 | Factory reset | applied after restart |
| 0x00A3 | Restart | |
| 0x00A4 | Bluetooth on (`01 00`) / off (`00 00`) | applied after restart |
| 0x00A5 | Get MAC (value `01 00`) | → 3-byte MAC (big-endian) |
| 0x00C1 | Query zone filter | → type + 3 rectangles |
| 0x00C2 | Set zone filter (26 bytes) | applied immediately, saved across power loss |

Zone filter value: int16 type (0 = off, 1 = detect only inside the zones, 2 = ignore the
zones), then 3 rectangles, each given as 2 diagonal corners (x1, y1, x2, y2) as int16 mm.
All zeros = zone unused. Coordinates here are **plain two's-complement** int16, not the
sign-bit encoding used in data frames.

## 4. Bluetooth LE (from the vendor Android demo source)

| | UUID |
|---|---|
| Service | `0000ae00-0000-1000-8000-00805f9b34fb` |
| Notify (radar → phone) | `0000ae02-…` |
| Write (phone → radar) | `0000ae01-…` |

- Advertised name contains `HLK-LD2450`. The demo requests an MTU of 512.
- Notifications carry the **same 30-byte `AA FF 03 00 … 55 CC` data frames** and the
  same command/ACK frames as the UART (the vendor parser starts after the 4-byte header).
- The demo's password check (cmd 0xA8, default password `HiLink`) is applied only to
  LD2410-family devices, not the LD2450.
- The demo gates the LD2450 UI on firmware ≥ 2.02.23090616.
- Its LD2450 plotting code (`analysisAreaCoordinate`) is not used by the demo, so it is
  only a reference.

So the Android viewer can either connect to the **radar's own BLE** directly, or to the
**ESP32** (which could forward the same frames). See the open decision in §6.

## 5. Display: 2.8" SPI TFT, 240×320, "TFT SPI 240X320 V1.2"

- Controller **ILI9341**, panel HSD028309; touch controller **XPT2046** (resistive, with stylus).
- microSD slot (SD_CS / SD_MOSI / SD_MISO / SD_SCK pads on the back).
- 14-pin header: VCC, GND, CS, RESET, DC, SDI(MOSI), SCK, LED, SDO(MISO), T_CLK, T_CS,
  T_DIN, T_DO, T_IRQ.
- VCC accepts 5 V or 3.3 V (on-board 3.3 V regulator U1, with jumper J1 to bypass it for a
  3.3 V supply). **Logic is 3.3 V**, which suits the ESP32.
- LED is the backlight, switched by transistor Q1, so it can be driven from a GPIO (PWM dimming).
- The display and touch can share one SPI bus (separate CS pins).
- Supported by TFT_eSPI / LovyanGFX (ILI9341 driver, XPT2046 touch).

## 6. Open decisions for the next steps
1. Pin mapping for the radar UART and TFT on the ESP32 DevKitC (Step 2).
2. Phone link: ESP32 as a BLE/Wi-Fi bridge, or the phone connects to the radar's own BLE.
   A BLE peripheral generally accepts only one central at a time, so check whether the
   phone and the ESP32 can share the radar.
