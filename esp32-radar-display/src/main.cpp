// HLK-LD2450 radar -> ESP32-DevKitC-32E -> 2.8" ILI9341 display.
//
// Reads the radar over UART2, decodes up to 3 people and draws them live on the screen.
// Decoded data is also printed to the USB serial monitor (115200 baud) for debugging.
#include <Arduino.h>

#include "LD2450.h"
#include "RadarNetwork.h"
#include "RadarView.h"
#include "config.h"

namespace {
HardwareSerial& radar = Serial2;
ld2450::Parser parser;
RadarView view;

ld2450::Frame latest;
unsigned long last_frame_ms = 0;
unsigned long last_render_ms = 0;
unsigned long fps_window_ms = 0;
uint32_t fps_count = 0;
float fps = 0.0f;
bool have_frame = false;

// ---- Radar commands -------------------------------------------------------

void sendCommand(uint16_t cmd, const uint8_t* value = nullptr, size_t len = 0) {
  uint8_t out[32];
  size_t n = ld2450::buildCommand(cmd, value, len, out, sizeof(out));
  if (n) radar.write(out, n);
}

// Waits for the ACK of cmd, ignoring data frames. Returns true on a successful ACK.
bool waitAck(uint16_t cmd, unsigned long timeout_ms = 500) {
  unsigned long start = millis();
  while (millis() - start < timeout_ms) {
    while (radar.available()) {
      if (parser.feed(radar.read()) == ld2450::Parser::Event::Ack &&
          parser.ack().command == cmd) {
        return parser.ack().ok();
      }
    }
    delay(1);
  }
  return false;
}

bool command(uint16_t cmd, const uint8_t* value = nullptr, size_t len = 0) {
  sendCommand(cmd, value, len);
  return waitAck(cmd);
}

// Reads the firmware version and makes sure multi-target (3 people) tracking is on.
// Failures here are not fatal: the radar streams data with its saved settings anyway.
void configureRadar() {
  const uint8_t enable_value[] = {0x01, 0x00};
  bool in_config = command(ld2450::CMD_ENABLE_CONFIG, enable_value, 2) ||
                   command(ld2450::CMD_ENABLE_CONFIG, enable_value, 2);
  if (!in_config) {
    Serial.println("[radar] no reply to 'enable configuration' (check radar Rx <- GPIO17)");
    return;
  }

  if (command(ld2450::CMD_READ_FIRMWARE)) {
    char version[24];
    if (ld2450::formatFirmwareVersion(parser.ack(), version, sizeof(version))) {
      Serial.printf("[radar] firmware %s\n", version);
      view.setFirmwareVersion(version);
      radar_net::setFirmware(version);
    }
  }

  if (command(ld2450::CMD_QUERY_TRACKING_MODE)) {
    const ld2450::Ack& ack = parser.ack();
    bool single = ack.payload_len >= 2 && ack.payload[0] == 0x01;
    Serial.printf("[radar] tracking mode: %s\n", single ? "single target" : "multi target");
    if (single && command(ld2450::CMD_MULTI_TARGET)) {
      Serial.println("[radar] switched to multi-target tracking");
    }
  }

  // Data reporting stops while in configuration mode, so always leave it.
  if (!command(ld2450::CMD_END_CONFIG)) command(ld2450::CMD_END_CONFIG);
}

// ---- Debug output ---------------------------------------------------------

void printFrame(const ld2450::Frame& f) {
  for (int i = 0; i < ld2450::kMaxTargets; i++) {
    const ld2450::Target& t = f.targets[i];
    if (t.valid) {
      Serial.printf("T%d x=%5d y=%5d mm  d=%.2fm  a=%+5.1fdeg  v=%+4dcm/s   ", i + 1, t.x_mm,
                    t.y_mm, t.distanceMm() / 1000.0f, t.angleDeg(), t.speed_cms);
    } else {
      Serial.printf("T%d --   ", i + 1);
    }
  }
  Serial.println();
}
}  // namespace

void setup() {
  Serial.begin(115200);
  Serial.println("\n[boot] LD2450 radar display");

  radar.setRxBufferSize(1024);
  radar.begin(kRadarBaud, SERIAL_8N1, kRadarRxPin, kRadarTxPin);

  if (!view.begin()) Serial.println("[display] not enough memory for screen buffers");
  view.showMessage("LD2450 RADAR", "Connecting to radar...");

  configureRadar();
  last_frame_ms = millis();

  pinMode(kSetupButtonPin, INPUT_PULLUP);
  radar_net::begin();
}

void loop() {
  const unsigned long now = millis();

  while (radar.available()) {
    if (parser.feed(radar.read()) == ld2450::Parser::Event::Frame) {
      latest = parser.frame();
      last_frame_ms = now;
      have_frame = true;
      fps_count++;
      radar_net::publishFrame(latest);
    }
  }

  // Hold BOOT for 3 s to open the Wi-Fi setup hotspot.
  static unsigned long pressed_since = 0;
  if (digitalRead(kSetupButtonPin) == LOW) {
    if (pressed_since == 0) pressed_since = now;
    if (pressed_since != 1 && now - pressed_since >= kSetupHoldMs) {
      radar_net::requestSetup();
      pressed_since = 1;  // fire once per press
    }
  } else {
    pressed_since = 0;
  }

  if (now - fps_window_ms >= 1000) {
    fps = fps_count * 1000.0f / (now - fps_window_ms);
    fps_count = 0;
    fps_window_ms = now;
  }

  const bool online = have_frame && (now - last_frame_ms) < kRadarTimeoutMs;

  // Redraw when a new frame arrives, or twice a second while waiting for the radar.
  static unsigned long drawn_frame_ms = 0;
  if (online && last_frame_ms != drawn_frame_ms) {
    drawn_frame_ms = last_frame_ms;
    view.render(latest, true, fps, radar_net::status());
    last_render_ms = now;
    if (kSerialDebug) printFrame(latest);
  } else if (!online && now - last_frame_ms >= kRadarTimeoutMs && now - last_render_ms >= 500) {
    view.render(latest, false, 0, radar_net::status());
    last_render_ms = now;
  }

  delay(1);
}
