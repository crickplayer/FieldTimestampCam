#include "WebUpdate.h"

#include <Arduino.h>
#include <Update.h>
#include <WebServer.h>

#include "WebPage.h"

// Firmware build id, set by CI (date + git commit); "dev" for local builds.
#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif

// Chip id stored in every ESP app image header (bytes 12-13): 0x0005 = ESP32-C3.
#ifndef CONFIG_IDF_FIRMWARE_CHIP_ID
#define CONFIG_IDF_FIRMWARE_CHIP_ID 0x0005
#endif

namespace web_update {
namespace {

constexpr unsigned long kCodeValidMs = 10 * 60 * 1000;
constexpr uint8_t kMaxBadCodes = 5;

WebServer http(80);
bool running = false;
const char* device = "";
String radar_host;

char update_code[8] = "";
unsigned long code_until = 0;
uint8_t bad_codes = 0;
bool authorized = false;
bool failed = false;
bool first_chunk = false;
size_t total = 0;
String error;
int progress_pct = -1;
int last_reported = -1;
void (*progress_cb)(int) = nullptr;
bool restart_pending = false;
unsigned long restart_at = 0;

bool codeValid() {
  return update_code[0] != '\0' && (long)(code_until - millis()) > 0;
}

void newCode() {
  snprintf(update_code, sizeof(update_code), "%06lu", (unsigned long)(esp_random() % 1000000));
  code_until = millis() + kCodeValidMs;
  bad_codes = 0;
  Serial.printf("[update] code %s shown on the display\n", update_code);
}

void report(int pct) {
  progress_pct = pct;
  if (progress_cb && (last_reported < 0 || pct - last_reported >= 5 || pct == 100)) {
    last_reported = pct;
    progress_cb(pct);
  }
}

String statusJson() {
  char buf[200];
  snprintf(buf, sizeof(buf),
           "{\"type\":\"round-display\",\"device\":\"%s\",\"build\":\"%s\",\"radar\":\"%s\"}",
           device, FW_VERSION, radar_host.c_str());
  return String(buf);
}

void handleUpload() {
  HTTPUpload& up = http.upload();
  switch (up.status) {
    case UPLOAD_FILE_START:
      authorized = false;
      failed = false;
      error = "";
      if (!codeValid()) {
        error = "The code has expired. Reload this page to get a new one.";
        update_code[0] = '\0';
        return;
      }
      if (http.arg("code") != update_code) {
        if (++bad_codes >= kMaxBadCodes) update_code[0] = '\0';
        error = "Wrong code. Check the code on the round display.";
        return;
      }
      update_code[0] = '\0';  // single use
      total = (size_t)http.arg("size").toInt();
      if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
        error = Update.errorString();
        return;
      }
      authorized = true;
      first_chunk = true;
      last_reported = -1;
      report(0);
      Serial.printf("[update] receiving %s (%u bytes)\n", up.filename.c_str(), (unsigned)total);
      break;
    case UPLOAD_FILE_WRITE:
      if (!authorized || failed) return;
      if (first_chunk) {
        first_chunk = false;
        // An app image starts with 0xE9 and names its chip in bytes 12-13. This catches the
        // merged image, the big radar display's firmware.bin, zips and other files.
        if (up.currentSize < 16 || up.buf[0] != 0xE9) {
          failed = true;
          error = "That is not the right file. Upload firmware.bin (not round-display-merged.bin).";
          Update.abort();
          return;
        }
        const uint16_t chip = up.buf[12] | (up.buf[13] << 8);
        if (chip != CONFIG_IDF_FIRMWARE_CHIP_ID) {
          failed = true;
          error = "That firmware is for a different board (probably the big radar display). "
                  "Use firmware.bin from esp32-c3-round-display-firmware.";
          Update.abort();
          return;
        }
      }
      if (Update.write(up.buf, up.currentSize) != up.currentSize) {
        failed = true;
        error = Update.errorString();
        Update.abort();
        return;
      }
      if (total > 0) report((int)min<size_t>(99, Update.progress() * 100 / total));
      break;
    case UPLOAD_FILE_END:
      if (!authorized || failed) return;
      // end(true) checks the image and only then makes it the one to boot.
      if (Update.end(true)) {
        report(100);
        Serial.printf("[update] %u bytes written, restarting\n", (unsigned)up.totalSize);
      } else {
        failed = true;
        error = Update.errorString();
      }
      break;
    case UPLOAD_FILE_ABORTED:
      if (authorized) Update.abort();
      failed = true;
      error = "The upload was interrupted.";
      break;
  }
}

void handleDone() {
  const bool ok = authorized && !failed && progress_pct == 100;
  http.sendHeader("Connection", "close");
  if (ok) {
    http.send(200, "text/plain", "Update complete. The round display is restarting.");
    restart_pending = true;
    restart_at = millis() + 1500;
  } else {
    http.send(authorized ? 500 : 403, "text/plain",
              error.length() ? error : String("Update failed."));
    progress_pct = -1;
    Serial.printf("[update] failed: %s\n", error.c_str());
  }
  authorized = false;
}

}  // namespace

void begin(const char* device_id) {
  device = device_id;
  http.on("/", []() { http.send_P(200, "text/html", kHomePage); });
  http.on("/status", []() { http.send(200, "application/json", statusJson()); });
  http.on("/update", HTTP_GET, []() {
    newCode();
    http.send_P(200, "text/html", kUpdatePage);
  });
  http.on("/update", HTTP_POST, handleDone, handleUpload);
}

void start() {
  if (running) return;
  http.begin();
  running = true;
}

void stop() {
  if (!running) return;
  http.stop();
  running = false;
}

void loop() {
  if (running) http.handleClient();
  if (update_code[0] && !codeValid()) update_code[0] = '\0';
  if (restart_pending && (long)(millis() - restart_at) >= 0) {
    Serial.println("[update] restarting into the new firmware");
    delay(100);
    ESP.restart();
  }
}

const char* code() {
  return codeValid() ? update_code : "";
}

int progress() {
  return progress_pct;
}

void onProgress(void (*callback)(int)) {
  progress_cb = callback;
}

void setRadar(const char* radar) {
  radar_host = radar ? radar : "";
}

}  // namespace web_update
