// ESP32-C3 SuperMini + 1.28" GC9A01 round display: a wireless second screen for the
// HLK-LD2450 radar.
//
// It joins the home Wi-Fi, finds the radar ESP32 (esp32-radar-display/) by its mDNS service
// "_ld2450._tcp" (or uses an address typed into the setup page), and reads the same live JSON
// the browser page uses: ws://<radar ip>:81/. Nothing on the radar ESP32 has to change.
#include <Arduino.h>
#include <ESPmDNS.h>
#include <Preferences.h>
#include <WebSocketsClient.h>
#include <WiFi.h>
#include <WiFiManager.h>

#include "LD2450.h"
#include "RadarJson.h"
#include "RoundView.h"
#include "WebUpdate.h"
#include "config.h"

namespace {

constexpr unsigned long kSetupTimeoutSec = 300;      // re-opened setup closes after 5 min
constexpr unsigned long kWifiRetryMs = 20000;
constexpr unsigned long kSearchRetryMs = 8000;       // between radar searches
constexpr unsigned long kConnectGiveUpMs = 15000;    // then search again (radar IP may change)
constexpr uint16_t kDefaultRadarPort = 81;

RoundView view;
WiFiManager wm;
WiFiManagerParameter* p_radar = nullptr;
WebSocketsClient ws;

char device_id[24];
char setup_ssid[24];
char setup_password[12];
bool portal_was_active = false;
unsigned long last_wifi_attempt = 0;
bool mdns_started = false;

// ---- Radar link ----
enum class Link { Idle, Connecting, Connected };
Link radar_link = Link::Idle;
String manual_radar;   // from the setup page; blank = find automatically
String cached_radar;   // last radar found (tried first after a restart)
bool tried_cache = false;
unsigned long last_search_ms = 0;
bool searched_once = false;
unsigned long link_since_ms = 0;  // when Connecting started / when the link was lost
String radar_host;
char radar_name[24] = "";

// ---- Radar data ----
ld2450::Frame latest;
bool have_frame = false;
unsigned long last_frame_ms = 0;
unsigned long fps_window_ms = 0;
uint32_t fps_count = 0;
float fps = 0;
bool frame_dirty = false;

// ---- Settings ----

void loadSettings() {
  Preferences prefs;
  prefs.begin("round", true);
  manual_radar = prefs.getString("radar", "");
  cached_radar = prefs.getString("last_radar", "");
  prefs.end();
}

void saveCachedRadar(const String& host) {
  if (host == cached_radar) return;
  cached_radar = host;
  Preferences prefs;
  prefs.begin("round", false);
  prefs.putString("last_radar", host);
  prefs.end();
}

void saveSettingsFromPortal() {
  String radar = p_radar->getValue();
  radar.trim();
  Preferences prefs;
  prefs.begin("round", false);
  prefs.putString("radar", radar);
  prefs.end();
  manual_radar = radar;
  Serial.printf("[net] radar address set to \"%s\"\n", radar.c_str());
}

// ---- Wi-Fi ----

void applyTxPower() {
  WiFi.setTxPower(kWifiTxPower);
}

void makeSetupPassword() {
  static const char kChars[] = "abcdefghjkmnpqrstuvwxyz23456789";  // no 0/o, 1/l/i
  for (int i = 0; i < 8; i++) setup_password[i] = kChars[esp_random() % (sizeof(kChars) - 1)];
  setup_password[8] = '\0';
}

void startPortal(bool wifi_saved) {
  ws.disconnect();
  radar_link = Link::Idle;
  web_update::stop();  // the setup page needs port 80
  makeSetupPassword();
  p_radar->setValue(manual_radar.c_str(), 64);
  wm.setConfigPortalTimeout(wifi_saved ? kSetupTimeoutSec : 0);
  wm.startConfigPortal(setup_ssid, setup_password);
  applyTxPower();
  Serial.printf("[net] setup hotspot \"%s\" password \"%s\"\n", setup_ssid, setup_password);
}

// ---- Finding the radar ----

// Resolves "name" or "name.local" through mDNS; anything else is returned unchanged
// (an IP address, or a normal DNS name).
String resolveHost(const String& host) {
  IPAddress ip;
  if (ip.fromString(host)) return host;
  String name = host;
  if (name.endsWith(".local")) name.remove(name.length() - 6);
  if (name.indexOf('.') < 0) {
    IPAddress found = MDNS.queryHost(name.c_str(), 2000);
    if (found != IPAddress()) return found.toString();
  }
  return host;
}

// Picks the radar to connect to: the address from the setup page, else the radar found last
// time (once after each restart), else an mDNS search. Returns false if none was found.
bool pickRadar(String* host, uint16_t* port) {
  *port = kDefaultRadarPort;
  if (manual_radar.length()) {
    String h = manual_radar;
    const int colon = h.lastIndexOf(':');
    if (colon > 0) {
      *port = (uint16_t)h.substring(colon + 1).toInt();
      h = h.substring(0, colon);
      if (*port == 0) *port = kDefaultRadarPort;
    }
    *host = resolveHost(h);
    return true;
  }
  if (!tried_cache && cached_radar.length()) {
    tried_cache = true;
    *host = cached_radar;
    return true;
  }
  const int n = MDNS.queryService("ld2450", "tcp");
  if (n <= 0) return false;
  *host = MDNS.IP(0).toString();
  *port = MDNS.port(0);
  Serial.printf("[radar] found %s at %s:%u (%d radar%s on the network)\n",
                MDNS.hostname(0).c_str(), host->c_str(), *port, n, n == 1 ? "" : "s");
  saveCachedRadar(*host);
  return true;
}

void onWebSocketEvent(WStype_t type, uint8_t* payload, size_t length) {
  switch (type) {
    case WStype_CONNECTED:
      Serial.printf("[radar] connected to %s\n", radar_host.c_str());
      radar_link = Link::Connected;
      saveCachedRadar(radar_host);
      web_update::setRadar(radar_host.c_str());
      break;
    case WStype_DISCONNECTED:
      if (radar_link == Link::Connected) {
        Serial.println("[radar] connection lost");
        radar_link = Link::Connecting;  // the library keeps retrying the same address
        web_update::setRadar("");
        link_since_ms = millis();
      }
      break;
    case WStype_TEXT: {
      ld2450::Frame f;
      if (ld2450::decodeFrameJson((const char*)payload, length, &f, nullptr)) {
        latest = f;
        have_frame = true;
        last_frame_ms = millis();
        fps_count++;
        frame_dirty = true;
      } else {
        // Status message: remember the radar's name, e.g. "device":"ld2450-ab12".
        const char* p = strstr((const char*)payload, "\"device\":\"");
        if (p) {
          p += 10;
          size_t n = 0;
          while (p[n] && p[n] != '"' && n < sizeof(radar_name) - 1) n++;
          memcpy(radar_name, p, n);
          radar_name[n] = '\0';
        }
      }
      break;
    }
    default:
      break;
  }
}

void handleRadarLink(unsigned long now) {
  if (radar_link == Link::Idle) {
    if (searched_once && now - last_search_ms < kSearchRetryMs) return;
    searched_once = true;
    String host;
    uint16_t port;
    const bool found = pickRadar(&host, &port);
    last_search_ms = millis();
    if (!found) return;
    radar_host = host;
    radar_name[0] = '\0';
    Serial.printf("[radar] connecting to %s:%u\n", host.c_str(), port);
    ws.begin(host.c_str(), port, "/", "");
    radar_link = Link::Connecting;
    link_since_ms = millis();
    return;
  }
  if (radar_link == Link::Connecting && now - link_since_ms > kConnectGiveUpMs) {
    Serial.println("[radar] no answer; searching again");
    ws.disconnect();
    radar_link = Link::Idle;
    last_search_ms = 0;
    searched_once = false;  // search right away
  }
}

// ---- Screen ----

void showUpdateProgress(int percent) {
  char line[16];
  snprintf(line, sizeof(line), "%d%%", percent);
  view.showMessage("Updating", TFT_CYAN, line, "Keep it plugged in.", "It restarts when done.");
}

void draw(unsigned long now, bool wifi) {
  static unsigned long last_draw_ms = 0;
  const bool portal = wm.getConfigPortalActive();

  if (portal) {
    char join[40], pass[32];
    snprintf(join, sizeof(join), "%s", setup_ssid);
    snprintf(pass, sizeof(pass), "Password: %s", setup_password);
    view.showMessage("Wi-Fi setup", TFT_YELLOW, "Join hotspot:", join, pass,
                     "then open 192.168.4.1");
    return;
  }
  if (web_update::progress() >= 0) {
    showUpdateProgress(web_update::progress());
    return;
  }
  if (web_update::code()[0]) {
    view.showMessage(web_update::code(), TFT_YELLOW, "Firmware update code",
                     "Type it in the browser.", "Valid 10 minutes.");
    return;
  }
  if (!wifi) {
    view.clearTrails();
    view.showMessage("Wi-Fi", TFT_YELLOW, "Connecting...", WiFi.SSID().c_str(),
                     "Hold BOOT 3 s for setup");
    return;
  }
  char my_ip[32];
  snprintf(my_ip, sizeof(my_ip), "This display: %s", WiFi.localIP().toString().c_str());
  if (radar_link != Link::Connected) {
    view.clearTrails();
    if (radar_link == Link::Idle && searched_once) {
      view.showMessage("Radar", TFT_YELLOW, "Searching...", "Is the radar ESP32 on",
                       "the same Wi-Fi?", my_ip);
    } else {
      view.showMessage("Radar", TFT_CYAN, "Connecting to", radar_host.c_str(), "", my_ip);
    }
    return;
  }

  const bool online = have_frame && now - last_frame_ms < kRadarTimeoutMs;
  if (!online) {
    view.clearTrails();
    view.showMessage("No radar data", TFT_RED, "Connected to",
                     radar_name[0] ? radar_name : radar_host.c_str(), "Is the radar sending?",
                     my_ip);
    return;
  }

  // Redraw on each new frame (and at least twice a second).
  if (frame_dirty || now - last_draw_ms >= 500) {
    frame_dirty = false;
    last_draw_ms = now;
    char status[32];
    snprintf(status, sizeof(status), "%.0f Hz", fps);
    view.render(latest, status, WiFi.localIP().toString().c_str());
  }
}

void handleButton(unsigned long now) {
  static unsigned long pressed_since = 0;
  if (digitalRead(kSetupButtonPin) == LOW) {
    if (pressed_since == 0) pressed_since = now;
    if (pressed_since != 1 && now - pressed_since >= kSetupHoldMs) {
      if (!wm.getConfigPortalActive()) startPortal(wm.getWiFiIsSaved());
      pressed_since = 1;  // fire once per press
    }
  } else {
    pressed_since = 0;
  }
}

}  // namespace

void setup() {
  Serial.begin(115200);
  Serial.setTxTimeoutMs(0);  // never wait for the USB serial monitor
  Serial.println("\n[boot] LD2450 round display");

  if (!view.begin()) Serial.println("[display] not enough memory for the screen buffer");
  Serial.printf("[boot] free memory %u bytes\n", (unsigned)ESP.getFreeHeap());

  const uint64_t mac = ESP.getEfuseMac();
  const uint8_t b4 = (mac >> 32) & 0xFF, b5 = (mac >> 40) & 0xFF;
  snprintf(device_id, sizeof(device_id), "ld2450-round-%02x%02x", b4, b5);
  snprintf(setup_ssid, sizeof(setup_ssid), "LD2450-Round-%02X%02X", b4, b5);

  pinMode(kSetupButtonPin, INPUT_PULLUP);
  loadSettings();
  web_update::begin(device_id);
  web_update::onProgress(showUpdateProgress);

  p_radar = new WiFiManagerParameter(
      "radar", "Radar address (leave blank to find it automatically)", "", 64);
  wm.addParameter(p_radar);
  wm.setSaveParamsCallback(saveSettingsFromPortal);
  wm.setConfigPortalBlocking(false);
  wm.setTitle("LD2450 round display setup");
  std::vector<const char*> menu = {"wifi", "exit"};
  wm.setMenu(menu);
  wm.setShowInfoUpdate(false);

  WiFi.mode(WIFI_STA);
  WiFi.setHostname(device_id);
  WiFi.setSleep(false);  // lower latency (the board is USB powered)
  WiFi.setAutoReconnect(true);
  applyTxPower();

  ws.onEvent(onWebSocketEvent);
  ws.setReconnectInterval(3000);
  ws.enableHeartbeat(10000, 4000, 2);  // notice a vanished radar within about 20 s

  if (wm.getWiFiIsSaved()) {
    WiFi.begin();
    applyTxPower();
    last_wifi_attempt = millis();
  } else {
    startPortal(false);
  }
}

void loop() {
  const unsigned long now = millis();
  handleButton(now);

  const bool portal = wm.getConfigPortalActive();
  if (portal) wm.process();
  if (portal_was_active && !portal) {
    // Setup finished or timed out: search for the radar again with any new settings.
    loadSettings();
    tried_cache = false;
    searched_once = false;
    if (WiFi.status() != WL_CONNECTED && wm.getWiFiIsSaved()) {
      WiFi.begin();
      applyTxPower();
      last_wifi_attempt = now;
    }
  }
  portal_was_active = portal;

  const bool wifi = WiFi.status() == WL_CONNECTED;
  if (wifi) {
    if (!mdns_started && MDNS.begin(device_id)) {
      mdns_started = true;
      MDNS.addService("http", "tcp", 80);
      Serial.printf("[net] Wi-Fi connected, IP %s\n", WiFi.localIP().toString().c_str());
    }
    if (!portal) {
      web_update::start();
      handleRadarLink(now);
    }
    if (radar_link != Link::Idle) ws.loop();
  } else {
    if (radar_link != Link::Idle) {
      ws.disconnect();
      radar_link = Link::Idle;
      searched_once = false;
    }
    if (!portal && wm.getWiFiIsSaved() && now - last_wifi_attempt >= kWifiRetryMs) {
      WiFi.begin();
      applyTxPower();
      last_wifi_attempt = now;
    }
  }

  if (now - fps_window_ms >= 1000) {
    fps = fps_count * 1000.0f / (now - fps_window_ms);
    fps_count = 0;
    fps_window_ms = now;
  }

  web_update::loop();
  draw(now, wifi);
  delay(1);
}
