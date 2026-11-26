// Settings you may want to change. Display pins are in platformio.ini (build_flags).
#pragma once

#include <WiFi.h>

// The radar's range and field of view (must match the radar: 6 m, +/-60 degrees).
constexpr float kMaxRangeM = 6.0f;
constexpr float kFovDeg = 60.0f;

// Left/right direction is set on the radar ESP32 (kMirrorX in esp32-radar-display/src/config.h);
// this screen follows it, like the browser page and the phone app.

// Screen orientation: 0..3 (each step turns the picture by 90 degrees).
constexpr uint8_t kScreenRotation = 0;

// Show "No radar data" if no frame arrives for this long (the radar sends about 10 per second).
constexpr unsigned long kRadarTimeoutMs = 1500;

// Number of past positions drawn as a fading trail behind each person.
constexpr int kTrailLength = 10;

// Hold this button (BOOT on the SuperMini) for kSetupHoldMs to open the Wi-Fi setup hotspot.
constexpr int kSetupButtonPin = 9;
constexpr unsigned long kSetupHoldMs = 3000;

// Wi-Fi transmit power. Many C3 SuperMini boards have a small antenna that fails to join Wi-Fi
// at full power; 8.5 dBm is the usual fix. If the board works but the signal is weak, try
// WIFI_POWER_11dBm or WIFI_POWER_15dBm.
constexpr wifi_power_t kWifiTxPower = WIFI_POWER_8_5dBm;
