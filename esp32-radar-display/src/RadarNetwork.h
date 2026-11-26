// Wi-Fi, home-network streaming and MQTT (EMQX) for viewers such as the Android app.
//
// Runs in its own FreeRTOS task on core 0, so slow network work (Wi-Fi reconnects,
// TLS handshakes) never stalls the radar or the display, which run on core 1.
//
//  * First start (no Wi-Fi saved): opens a setup hotspot "LD2450-XXXX" with a password shown
//    on the display. Join it with a phone to enter Wi-Fi and (optionally) MQTT settings.
//    Hold the ESP32's BOOT button for 3 s at any time to open the setup hotspot again.
//  * Home network: http://<ip>/ shows a live view in any browser; ws://<ip>:81/ streams the
//    same JSON the app uses. Advertised over mDNS as _ld2450._tcp.
//  * Remote: publishes to MQTT topics under ld2450/<device-id>/ (see README):
//      status   retained, {"state":"online"|"offline",...}
//      targets  live frames, sent only while a viewer has sent to .../watch in the last 60 s
//      watch    viewers publish anything here every ~30 s while they are watching
#pragma once

#include <stdint.h>

#include "LD2450.h"

struct NetStatus {
  bool wifi = false;           // connected to the home network
  bool cloud_configured = false;
  bool cloud = false;          // connected to the MQTT broker
  bool cloud_streaming = false;  // a remote viewer is watching
  bool setup = false;          // setup hotspot is open
  uint8_t lan_viewers = 0;     // WebSocket clients on the home network
  char ip[16] = "";
  char device_id[16] = "";
  char setup_ssid[24] = "";
  char setup_password[12] = "";
};

namespace radar_net {

// Starts the network task. firmware may be updated later with setFirmware().
void begin();

// Hands the latest radar frame to the network task (cheap; safe to call every frame).
void publishFrame(const ld2450::Frame& frame);

// Radar firmware version, reported in the status message.
void setFirmware(const char* version);

// Opens the setup hotspot (e.g. after a long press on BOOT).
void requestSetup();

// Snapshot of the current network state for the display.
NetStatus status();

}  // namespace radar_net
