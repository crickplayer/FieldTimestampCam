#include "RadarNetwork.h"

#include <Arduino.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <PubSubClient.h>
#include <WebServer.h>
#include <WebSocketsServer.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <Update.h>
#include <WiFiManager.h>
#include <time.h>

#include "RadarJson.h"
#include "RootCAs.h"
#include "WebPage.h"
#include "config.h"

// Firmware build id, set by CI (date + git commit); "dev" for local builds.
#ifndef FW_VERSION
#define FW_VERSION "dev"
#endif

namespace radar_net {
namespace {

constexpr uint16_t kHttpPort = 80;
constexpr uint16_t kWebSocketPort = 81;
constexpr unsigned long kWatchWindowMs = 60000;    // stream to MQTT this long after a "watch"
constexpr unsigned long kMqttRetryMs = 15000;
constexpr unsigned long kWifiRetryMs = 20000;
constexpr unsigned long kSetupTimeoutSec = 300;     // re-opened setup closes after 5 min

// ---- Shared between the network task and the main loop (guarded by mux) ----
portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
ld2450::Frame pending_frame;
bool has_pending = false;
NetStatus shared;
char firmware[24] = "";
volatile bool setup_requested = false;

// ---- Network-task state ----
struct MqttSettings {
  String host;
  uint16_t port = 8883;
  String user;
  String pass;
  bool tls = true;
  bool configured() const { return host.length() > 0; }
} mqtt_cfg;

WiFiManager wm;
WiFiManagerParameter* p_host = nullptr;
WiFiManagerParameter* p_port = nullptr;
WiFiManagerParameter* p_user = nullptr;
WiFiManagerParameter* p_pass = nullptr;
WiFiManagerParameter* p_tls = nullptr;

WebServer http(kHttpPort);
WebSocketsServer ws(kWebSocketPort);
WiFiClient plain_client;
WiFiClientSecure tls_client;
PubSubClient mqtt;

char device_id[16];
char topic_status[48];
char topic_targets[48];
char topic_watch[48];

uint32_t seq = 0;
unsigned long watch_until = 0;
bool watching = false;
unsigned long last_mqtt_attempt = 0;
bool mqtt_ever_tried = false;
unsigned long last_wifi_attempt = 0;
unsigned long last_cloud_publish = 0;
bool last_cloud_empty = false;
bool mdns_started = false;
bool http_running = false;
bool portal_was_active = false;
char setup_ssid[24];
char setup_password[12];

// ---- Firmware update over Wi-Fi ----
constexpr unsigned long kUpdateCodeValidMs = 10 * 60 * 1000;
constexpr uint8_t kUpdateMaxBadCodes = 5;
char update_code[8] = "";
unsigned long update_code_until = 0;
uint8_t update_bad_codes = 0;
bool update_authorized = false;
bool update_failed = false;
bool update_first_chunk = false;
size_t update_total = 0;
String update_error;
int8_t update_progress = -1;
bool restart_pending = false;
unsigned long restart_at = 0;

// ---- Settings ----

void loadSettings() {
  Preferences prefs;
  prefs.begin("ld2450", true);
  mqtt_cfg.host = prefs.getString("mqtt_host", "");
  mqtt_cfg.port = prefs.getUShort("mqtt_port", 8883);
  mqtt_cfg.user = prefs.getString("mqtt_user", "");
  mqtt_cfg.pass = prefs.getString("mqtt_pass", "");
  mqtt_cfg.tls = prefs.getBool("mqtt_tls", true);
  prefs.end();
}

void saveSettingsFromPortal() {
  Preferences prefs;
  prefs.begin("ld2450", false);
  String host = p_host->getValue();
  host.trim();
  prefs.putString("mqtt_host", host);
  long port = String(p_port->getValue()).toInt();
  prefs.putUShort("mqtt_port", (port > 0 && port < 65536) ? (uint16_t)port : 8883);
  prefs.putString("mqtt_user", p_user->getValue());
  // A blank password field keeps the saved password (it is never shown in the page).
  if (strlen(p_pass->getValue()) > 0) prefs.putString("mqtt_pass", p_pass->getValue());
  if (host.length() == 0) prefs.putString("mqtt_pass", "");
  prefs.putBool("mqtt_tls", String(p_tls->getValue()) != "0");
  prefs.end();
  Serial.println("[net] settings saved");
}

// ---- Messages ----

String statusJson(bool online) {
  char ip[16];
  portENTER_CRITICAL(&mux);
  strncpy(ip, shared.ip, sizeof(ip));
  portEXIT_CRITICAL(&mux);
  char fw[24];
  portENTER_CRITICAL(&mux);
  strncpy(fw, firmware, sizeof(fw));
  portEXIT_CRITICAL(&mux);
  char buf[256];
  snprintf(buf, sizeof(buf),
           "{\"v\":%d,\"type\":\"status\",\"device\":\"%s\",\"state\":\"%s\",\"fw\":\"%s\","
           "\"ip\":\"%s\",\"mirror_x\":%s,\"build\":\"%s\"}",
           ld2450::kJsonVersion, device_id, online ? "online" : "offline", fw, ip,
           kMirrorX ? "true" : "false", FW_VERSION);
  return String(buf);
}

// ---- Setup hotspot ----

void makeSetupPassword() {
  static const char kChars[] = "abcdefghjkmnpqrstuvwxyz23456789";  // no 0/o, 1/l/i
  for (int i = 0; i < 8; i++) setup_password[i] = kChars[esp_random() % (sizeof(kChars) - 1)];
  setup_password[8] = '\0';
}

void startPortal(bool wifi_saved) {
  makeSetupPassword();
  if (http_running) {
    http.stop();  // the setup page needs port 80
    http_running = false;
  }
  p_host->setValue(mqtt_cfg.host.c_str(), 80);
  char port[8];
  snprintf(port, sizeof(port), "%u", mqtt_cfg.port);
  p_port->setValue(port, 6);
  p_user->setValue(mqtt_cfg.user.c_str(), 64);
  p_tls->setValue(mqtt_cfg.tls ? "1" : "0", 2);
  wm.setConfigPortalTimeout(wifi_saved ? kSetupTimeoutSec : 0);
  wm.startConfigPortal(setup_ssid, setup_password);
  Serial.printf("[net] setup hotspot \"%s\" password \"%s\"\n", setup_ssid, setup_password);
}

// ---- Home-network viewers ----

void onWebSocketEvent(uint8_t client, WStype_t type, uint8_t*, size_t) {
  if (type == WStype_CONNECTED) {
    String s = statusJson(true);
    ws.sendTXT(client, s.c_str(), s.length());
  }
}

void startHttp() {
  if (http_running) return;
  http.begin();
  http_running = true;
}

// ---- Firmware update over Wi-Fi ----

bool updateCodeValid() {
  return update_code[0] != '\0' && (long)(update_code_until - millis()) > 0;
}

void newUpdateCode() {
  snprintf(update_code, sizeof(update_code), "%06lu", (unsigned long)(esp_random() % 1000000));
  update_code_until = millis() + kUpdateCodeValidMs;
  update_bad_codes = 0;
  Serial.printf("[update] code %s shown on the display\n", update_code);
}

void handleUpdateUpload() {
  HTTPUpload& up = http.upload();
  switch (up.status) {
    case UPLOAD_FILE_START: {
      update_authorized = false;
      update_failed = false;
      update_error = "";
      if (!updateCodeValid()) {
        update_error = "The code has expired. Reload this page to get a new one.";
        update_code[0] = '\0';
        return;
      }
      if (http.arg("code") != update_code) {
        if (++update_bad_codes >= kUpdateMaxBadCodes) update_code[0] = '\0';
        update_error = "Wrong code. Check the code on the radar's screen.";
        return;
      }
      update_code[0] = '\0';  // single use
      update_total = (size_t)http.arg("size").toInt();
      if (!Update.begin(UPDATE_SIZE_UNKNOWN, U_FLASH)) {
        update_error = Update.errorString();
        return;
      }
      update_authorized = true;
      update_first_chunk = true;
      update_progress = 0;
      Serial.printf("[update] receiving %s (%u bytes)\n", up.filename.c_str(), (unsigned)update_total);
      break;
    }
    case UPLOAD_FILE_WRITE:
      if (!update_authorized || update_failed) return;
      if (update_first_chunk) {
        update_first_chunk = false;
        // An ESP32 app image starts with 0xE9. Catches the merged image, zips and other files.
        if (up.currentSize == 0 || up.buf[0] != 0xE9) {
          update_failed = true;
          update_error = "That is not the right file. Upload firmware.bin (not radar-display-merged.bin).";
          Update.abort();
          return;
        }
      }
      if (Update.write(up.buf, up.currentSize) != up.currentSize) {
        update_failed = true;
        update_error = Update.errorString();
        Update.abort();
        return;
      }
      if (update_total > 0) {
        update_progress = (int8_t)min<size_t>(99, Update.progress() * 100 / update_total);
      }
      break;
    case UPLOAD_FILE_END:
      if (!update_authorized || update_failed) return;
      // end(true) checks the image and only then makes it the one to boot.
      if (Update.end(true)) {
        update_progress = 100;
        Serial.printf("[update] %u bytes written, restarting\n", (unsigned)up.totalSize);
      } else {
        update_failed = true;
        update_error = Update.errorString();
      }
      break;
    case UPLOAD_FILE_ABORTED:
      if (update_authorized) Update.abort();
      update_failed = true;
      update_error = "The upload was interrupted.";
      break;
  }
}

void handleUpdateDone() {
  const bool ok = update_authorized && !update_failed && update_progress == 100;
  http.sendHeader("Connection", "close");
  if (ok) {
    http.send(200, "text/plain", "Update complete. The radar display is restarting.");
    restart_pending = true;
    restart_at = millis() + 1500;
  } else {
    http.send(update_authorized ? 500 : 403, "text/plain",
              update_error.length() ? update_error : String("Update failed."));
    update_progress = -1;
    Serial.printf("[update] failed: %s\n", update_error.c_str());
  }
  update_authorized = false;
}

void setupServers() {
  http.on("/", []() { http.send_P(200, "text/html", kWebPage); });
  http.on("/update", HTTP_GET, []() {
    newUpdateCode();
    http.send_P(200, "text/html", kUpdatePage);
  });
  http.on("/update", HTTP_POST, handleUpdateDone, handleUpdateUpload);
  http.on("/status", []() { http.send(200, "application/json", statusJson(true)); });
  ws.begin();
  ws.onEvent(onWebSocketEvent);
}

// ---- MQTT ----

void onMqttMessage(char* topic, uint8_t*, unsigned int) {
  if (strcmp(topic, topic_watch) == 0) watch_until = millis() + kWatchWindowMs;
}

void mqttConnect() {
  last_mqtt_attempt = millis();
  mqtt_ever_tried = true;
  if (mqtt_cfg.tls) {
    tls_client.setCACert(kMqttRootCAs);
    tls_client.setTimeout(10);
    mqtt.setClient(tls_client);
  } else {
    mqtt.setClient(plain_client);
  }
  mqtt.setServer(mqtt_cfg.host.c_str(), mqtt_cfg.port);
  mqtt.setBufferSize(512);
  mqtt.setKeepAlive(30);
  mqtt.setSocketTimeout(10);
  mqtt.setCallback(onMqttMessage);

  String will = statusJson(false);
  const char* user = mqtt_cfg.user.length() ? mqtt_cfg.user.c_str() : nullptr;
  const char* pass = mqtt_cfg.pass.length() ? mqtt_cfg.pass.c_str() : nullptr;
  Serial.printf("[mqtt] connecting to %s:%u (%s)\n", mqtt_cfg.host.c_str(), mqtt_cfg.port,
                mqtt_cfg.tls ? "TLS" : "no TLS");
  if (mqtt.connect(device_id, user, pass, topic_status, 1, true, will.c_str())) {
    String online = statusJson(true);
    mqtt.publish(topic_status, online.c_str(), true);
    mqtt.subscribe(topic_watch);
    Serial.printf("[mqtt] connected; free heap %u, largest block %u\n", ESP.getFreeHeap(),
                  ESP.getMaxAllocHeap());
  } else {
    // PubSubClient states: -4 timeout, -2 network/TLS failed, 4 bad user/password, 5 not authorised.
    Serial.printf("[mqtt] connect failed, state %d\n", mqtt.state());
  }
}

// ---- Task ----

void publishPending() {
  ld2450::Frame frame;
  bool have = false;
  portENTER_CRITICAL(&mux);
  if (has_pending) {
    frame = pending_frame;
    has_pending = false;
    have = true;
  }
  portEXIT_CRITICAL(&mux);
  if (!have) return;

  char json[256];
  size_t n = ld2450::encodeFrameJson(frame, kMirrorX, ++seq, json, sizeof(json));
  if (n == 0) return;

  if (ws.connectedClients() > 0) ws.broadcastTXT(json, n);

  if (watching && mqtt.connected()) {
    // Save cloud traffic: when nobody is detected, send at most one frame per second.
    const unsigned long now = millis();
    const bool empty = frame.count() == 0;
    if (!(empty && last_cloud_empty && now - last_cloud_publish < 1000)) {
      mqtt.publish(topic_targets, json);
      last_cloud_publish = now;
      last_cloud_empty = empty;
    }
  }
}

void networkTask(void*) {
  uint64_t mac = ESP.getEfuseMac();
  uint8_t b4 = (mac >> 32) & 0xFF, b5 = (mac >> 40) & 0xFF;
  snprintf(device_id, sizeof(device_id), "ld2450-%02x%02x", b4, b5);
  snprintf(setup_ssid, sizeof(setup_ssid), "LD2450-%02X%02X", b4, b5);
  snprintf(topic_status, sizeof(topic_status), "ld2450/%s/status", device_id);
  snprintf(topic_targets, sizeof(topic_targets), "ld2450/%s/targets", device_id);
  snprintf(topic_watch, sizeof(topic_watch), "ld2450/%s/watch", device_id);
  portENTER_CRITICAL(&mux);
  strncpy(shared.device_id, device_id, sizeof(shared.device_id));
  portEXIT_CRITICAL(&mux);

  loadSettings();

  // Setup page fields (WiFiManager shows them under the Wi-Fi fields).
  p_host = new WiFiManagerParameter("mqtt_host", "MQTT broker address (leave blank for home network only)", "", 80);
  p_port = new WiFiManagerParameter("mqtt_port", "MQTT port (8883 = encrypted)", "8883", 6);
  p_user = new WiFiManagerParameter("mqtt_user", "MQTT username", "", 64);
  p_pass = new WiFiManagerParameter("mqtt_pass", "MQTT password (blank = keep current)", "", 64,
                                    "type='password'");
  p_tls = new WiFiManagerParameter("mqtt_tls", "Encrypted connection (1 = yes, 0 = no)", "1", 2);
  wm.addParameter(p_host);
  wm.addParameter(p_port);
  wm.addParameter(p_user);
  wm.addParameter(p_pass);
  wm.addParameter(p_tls);
  wm.setSaveParamsCallback(saveSettingsFromPortal);
  wm.setConfigPortalBlocking(false);
  wm.setTitle("LD2450 radar setup");
  std::vector<const char*> menu = {"wifi", "exit"};  // no firmware-upload page
  wm.setMenu(menu);
  wm.setShowInfoUpdate(false);

  WiFi.mode(WIFI_STA);
  WiFi.setHostname(device_id);
  WiFi.setSleep(false);  // lower latency for live viewing (the board is USB powered)
  WiFi.setAutoReconnect(true);
  setupServers();

  const bool wifi_saved = wm.getWiFiIsSaved();
  if (wifi_saved) {
    WiFi.begin();
    last_wifi_attempt = millis();
  } else {
    startPortal(false);
  }

  for (;;) {
    const unsigned long now = millis();

    if (setup_requested) {
      setup_requested = false;
      if (!wm.getConfigPortalActive()) startPortal(wm.getWiFiIsSaved());
    }

    const bool portal = wm.getConfigPortalActive();
    if (portal) wm.process();
    if (portal_was_active && !wm.getConfigPortalActive()) {
      // Setup finished or timed out: apply any new MQTT settings.
      loadSettings();
      mqtt.disconnect();
      mqtt_ever_tried = false;
      if (WiFi.status() != WL_CONNECTED && wm.getWiFiIsSaved()) WiFi.begin();
    }
    portal_was_active = wm.getConfigPortalActive();

    const bool wifi = WiFi.status() == WL_CONNECTED;
    if (wifi) {
      if (!mdns_started && MDNS.begin(device_id)) {
        MDNS.addService("http", "tcp", kHttpPort);
        MDNS.addService("ld2450", "tcp", kWebSocketPort);
        configTime(0, 0, "pool.ntp.org", "time.google.com");  // TLS certificate dates
        mdns_started = true;
        Serial.printf("[net] Wi-Fi connected, IP %s, http://%s.local/\n",
                      WiFi.localIP().toString().c_str(), device_id);
      }
      if (!wm.getConfigPortalActive()) startHttp();
    } else if (!portal && wm.getWiFiIsSaved() && now - last_wifi_attempt > kWifiRetryMs) {
      WiFi.begin();
      last_wifi_attempt = now;
    }

    if (http_running) http.handleClient();
    ws.loop();

    if (wifi && mqtt_cfg.configured()) {
      if (!mqtt.connected()) {
        if (!mqtt_ever_tried || now - last_mqtt_attempt > kMqttRetryMs) mqttConnect();
      } else {
        mqtt.loop();
      }
    }
    watching = (long)(watch_until - millis()) > 0;

    publishPending();

    if (restart_pending && (long)(millis() - restart_at) >= 0) {
      mqtt.disconnect();
      ESP.restart();
    }
    if (update_code[0] && !updateCodeValid()) update_code[0] = '\0';

    portENTER_CRITICAL(&mux);
    strncpy(shared.update_code, update_code, sizeof(shared.update_code));
    shared.update_progress = update_progress;
    shared.wifi = wifi;
    shared.cloud_configured = mqtt_cfg.configured();
    shared.cloud = mqtt.connected();
    shared.cloud_streaming = watching && shared.cloud;
    shared.setup = wm.getConfigPortalActive();
    shared.lan_viewers = ws.connectedClients();
    portEXIT_CRITICAL(&mux);
    if (wifi) {
      String ip = WiFi.localIP().toString();
      portENTER_CRITICAL(&mux);
      strncpy(shared.ip, ip.c_str(), sizeof(shared.ip) - 1);
      portEXIT_CRITICAL(&mux);
    }
    portENTER_CRITICAL(&mux);
    strncpy(shared.setup_ssid, setup_ssid, sizeof(shared.setup_ssid) - 1);
    strncpy(shared.setup_password, setup_password, sizeof(shared.setup_password) - 1);
    portEXIT_CRITICAL(&mux);

    vTaskDelay(pdMS_TO_TICKS(2));
  }
}

}  // namespace

void begin() {
  // TLS needs a large stack; pinned to core 0 (the Arduino loop runs on core 1).
  xTaskCreatePinnedToCore(networkTask, "net", 16384, nullptr, 1, nullptr, 0);
}

void publishFrame(const ld2450::Frame& frame) {
  portENTER_CRITICAL(&mux);
  pending_frame = frame;
  has_pending = true;
  portEXIT_CRITICAL(&mux);
}

void setFirmware(const char* version) {
  portENTER_CRITICAL(&mux);
  strncpy(firmware, version ? version : "", sizeof(firmware) - 1);
  portEXIT_CRITICAL(&mux);
}

void requestSetup() { setup_requested = true; }

NetStatus status() {
  NetStatus s;
  portENTER_CRITICAL(&mux);
  s = shared;
  portEXIT_CRITICAL(&mux);
  return s;
}

}  // namespace radar_net
