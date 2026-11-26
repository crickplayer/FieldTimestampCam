// Settings you may want to change. Display pins are in platformio.ini (build_flags).
#pragma once

// Radar wiring: radar Tx -> GPIO16 (ESP32 RX2), radar Rx <- GPIO17 (ESP32 TX2).
constexpr int kRadarRxPin = 16;
constexpr int kRadarTxPin = 17;
constexpr unsigned long kRadarBaud = 256000;  // LD2450 factory default

// The radar tracks out to 6 m and +/-60 degrees.
constexpr float kMaxRangeM = 6.0f;
constexpr float kFovDeg = 60.0f;

// If people show up on the wrong side of the screen when you walk left/right,
// change this to true.
constexpr bool kMirrorX = false;

// The radar's own Bluetooth (for Hi-Link's phone app). This project does not use it, and it
// has no password, so it is turned off. The ESP32 applies this once per radar (the radar
// restarts once to apply it) and the radar remembers it. Set to true to turn it back on.
constexpr bool kRadarBluetooth = false;

// Show "no radar data" if no frame arrives for this long (radar sends 10 per second).
constexpr unsigned long kRadarTimeoutMs = 1500;

// Number of past positions drawn as a fading trail behind each person.
constexpr int kTrailLength = 12;

// Print decoded targets to the USB serial monitor (115200 baud) for debugging.
constexpr bool kSerialDebug = true;

// Hold this button (BOOT on the DevKitC) for kSetupHoldMs to open the Wi-Fi setup hotspot.
constexpr int kSetupButtonPin = 0;
constexpr unsigned long kSetupHoldMs = 3000;

// Screen orientation: 1 = landscape. If the picture is upside down, use 3.
constexpr uint8_t kScreenRotation = 1;
