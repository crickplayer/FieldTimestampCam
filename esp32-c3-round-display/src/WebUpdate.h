// Firmware update over Wi-Fi: http://<ip>/update
//
// Opening the page shows a one-time 6-digit code on the round display (valid 10 minutes, one
// use, 5 wrong tries). The browser uploads firmware.bin together with the code; the C3 checks
// the file is an ESP32-C3 app, writes it to the spare app slot and restarts into it. If anything
// fails, it keeps running the current firmware.
#pragma once

#include <stdint.h>

namespace web_update {

// Call once at start. device_id is shown on the page.
void begin(const char* device_id);

// Start/stop the web server (port 80 is needed by the Wi-Fi setup page while that is open).
void start();
void stop();

// Call often from loop(): serves pages and restarts after a successful update.
void loop();

// The code to show on the screen, or "" when there is none.
const char* code();

// Upload progress 0..100 while an update is being written, otherwise -1.
int progress();

// Called (at most every few percent) while an update is being written, so the screen can
// show progress: loop() does not run during an upload.
void onProgress(void (*callback)(int percent));

// Radar this display is connected to (shown on the status page).
void setRadar(const char* radar);

}  // namespace web_update
