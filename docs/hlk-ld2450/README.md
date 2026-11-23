# HLK-LD2450 vendor files

Reference material for the Hi-Link HLK-LD2450 24 GHz 1T2R human motion tracking radar
(product page: https://www.hlktech.net/index.php?id=1157), mirrored from the vendor's
Google Drive folder "HLK-LD2450 Human body tracking detection (3 person)".

Stored unmodified for reference only; nothing here is built into the app.

| File | What it is |
|---|---|
| `LD2450 serial port communication protocol V1.03.pdf` | UART protocol: commands/ACKs, tracking modes, baud, Bluetooth, zone filtering, data frames |
| `HLK-LD2450 1t2r motion target detection and tracking module manual v1.0.pdf` | Hardware manual: specs, pinout, mounting, detection range |
| `HLK-LD2450 operation manual.pdf` | Operation manual for the PC tool / module |
| `2450 serial port parsing code representation.pdf` | Sample frame-parsing code |
| `HLK-LD2450LD Report Data Analysis Reference Code.zip` | Reference parsing code (PDF + note, Chinese filenames) |
| `HLK-LD2450--3D.zip` | 3D model of the module (STEP) for enclosure design |
| `HLK-LD2450_TOOL_English.zip` | Windows PC configuration/visualisation tool (2024-01-09). The Drive's `application software/HLK-LD2450_TOOL_English` folder is byte-identical, so it is not duplicated here |
| `application software/HLKRadarTool APP download link.pdf` | Link to the HLKRadarTool phone app |
| `application software/Radar Bluetooth demo (Android).zip` | Vendor Android BLE demo source (`hlkradartool-android`), originally `雷达蓝牙demo（安卓）.zip` |
| `application software/Radar Bluetooth demo (iOS).zip` | Vendor iOS BLE demo source, originally `雷达蓝牙demo（iOS）.zip` |
| `common software/Serial port driver.zip` | CP210x and CH341 USB-serial drivers for Windows |
| `common software/sscom5.12.1.rar` | SSCOM serial terminal for Windows |

## SHA-256
```
196767dbb1bf23f8e3f6d28fefa76d6a7a01f80dabb2da51ce08713609e1c3c8  ./2450 serial port parsing code representation.pdf
dd4d44fffa50d74f76c175b9b692e46ffd4822832b800d1569553f8e2f864ec8  ./HLK-LD2450 1t2r motion target detection and tracking module manual v1.0.pdf
b73ba2f61afa3ae3bb03dc9a2138b92cdc6f6fd80b22c17854c0ac52cae468df  ./HLK-LD2450 operation manual.pdf
3e48df115c724636b98d11ad58588d3af77cfe720bf77e5fa52f3532b8f9c2ab  ./HLK-LD2450--3D.zip
3e01b75e8faa9793b3b727696483037ec34e7c7f71d4875342408c5c213d8714  ./HLK-LD2450LD Report Data Analysis Reference Code.zip
401d89c3a29da441dba043546a93a9b185d42b7133931d4324a95bdc0bb9450d  ./HLK-LD2450_TOOL_English.zip
9725e6c2408e7de5b444a21528c902743d1d4777d01871ad8bb1ade086cd7300  ./LD2450 serial port communication protocol V1.03.pdf
f5ae9a8882ed0f2e89536f2c2ef7d2454dc6184478255128ee39c9c2484000ba  ./README.md.tmp
eb195b33e25f7f747976d15fbff657d6a9355e523f74d1e18404b9f6ffbb5d9a  ./application software/HLKRadarTool APP download link.pdf
aa74cd0b58802a463955b90ef4c06739b1d33df95aa416e6c0ba857e3a01cb3d  ./application software/Radar Bluetooth demo (Android).zip
4048503edf1863f352a5e8b6d9f7fc429e707d22b2408d2b75ca005b5281b136  ./application software/Radar Bluetooth demo (iOS).zip
f34e2b7aca85673873f392126a4e541f31e295ed4a7e6a8d3d023dea3a5876a6  ./common software/Serial port driver.zip
075d73382fb4b341f71f78275b84ec050594c7c02ab9e759190c2a3d2beb4390  ./common software/sscom5.12.1.rar
```
