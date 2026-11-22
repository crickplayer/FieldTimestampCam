// Super Starter (Leagend JS300 / JS200) battery display
// ESP32-C3 Super Mini + 1.28" round GC9A01 240x240 TFT
//
// Protocol recovered from the "Super Starter" Android app (com.dc.js200):
//   * BLE service FFF0, write FFF3 (write-without-response), notify FFF4.
//   * Every message is AES-128 encrypted, each 16-byte block on its own
//     (app uses AES/CBC/NoPadding with a zero IV per block = ECB).
//     Commands shorter than 16 bytes are zero padded.
//   * App start-up sequence (read-only parts reproduced here):
//       D1 55 01 01  -> version reply  D1 55 01 ...
//       D1 55 02 00 01 -> "genuine" reply, only if version byte 5 == 00
//       D1 55 04     -> starter start-count reply D1 55 04 ...
//       D1 55 07     -> live data: D1 55 07 FF (ack) then D1 55 07 <data>
//   * The booster also advertises 16 encrypted bytes of manufacturer data
//     with the car-battery voltage/percent, readable without connecting.
//
// This firmware never sends start/boost, firmware-update, clock, battery-type
// or history commands.

#include <Arduino.h>
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_GC9A01A.h>
#include <NimBLEDevice.h>
#include "mbedtls/aes.h"

// ----------------------------------------------------------------------------
// User settings
// ----------------------------------------------------------------------------
// Lock onto one booster by MAC (lower case, e.g. "3c:ab:72:4e:7e:3d").
// Leave empty to use the first Super Starter found.
static const char *PINNED_MAC = "";

// true  = connect for full live data (car + starter + temperature).
//         While the ESP32 is connected, the phone app cannot connect.
// false = listen to broadcasts only (car battery only, phone app unaffected).
static const bool CONNECT_FOR_LIVE = true;

// Reply "genuine" when the booster asks (the app always does this).
static const bool SEND_GENUINE_REPLY = true;

// Display wiring (unchanged from the Schematik build)
static const int PIN_SCK = 4;
static const int PIN_MOSI = 6;
static const int PIN_CS = 7;
static const int PIN_DC = 3;
static const int PIN_RST = 10;

// ----------------------------------------------------------------------------
// Protocol constants
// ----------------------------------------------------------------------------
static const uint8_t AES_KEY[16] = {'l', 'e', 'a', 'g', 'e', 'n', 'd', 0xFF,
                                    0xFE, '0', '1', '0', '0', '0', '0', '@'};
static const NimBLEUUID SVC_UUID("fff0");
static const NimBLEUUID WRITE_UUID("fff3");
static const NimBLEUUID NOTIFY_UUID("fff4");

static const uint32_t CONNECT_TIMEOUT_MS = 10000;  // NimBLE 2.x: milliseconds
static const uint32_t STALE_MS = 8000;
static const uint32_t BROADCAST_STALE_MS = 30000;

// ----------------------------------------------------------------------------
// State
// ----------------------------------------------------------------------------
Adafruit_GC9A01A tft(PIN_CS, PIN_DC, PIN_RST);

struct LiveData {
  bool valid = false;
  uint32_t at = 0;
  int carStatus = 0;       // -1 not connected, 0 normal, 1 low, 2 charging, 3 engine
  int carPct = 0;
  uint16_t carCentiV = 0;
  int starterStatus = 0;   // 0 idle, 1 discharging, 2 charging
  int starterPct = 0;
  uint16_t starterCentiV = 0;
  int starterTemp = 0;
  uint8_t flags = 0;       // byte 15 status bits
};

struct AdvData {
  bool valid = false;
  uint32_t at = 0;
  int carStatus = 0;
  int carPct = 0;
  uint16_t carCentiV = 0;
  int rssi = 0;
};

struct RxPacket {
  uint8_t len;
  uint8_t data[64];
};

enum LinkState { SEARCHING, CONNECTING, HANDSHAKE, LIVE, BACKOFF };
enum HsStep { HS_START, HS_VERSION, HS_GENUINE, HS_STARTS, HS_LIVE_REQ, HS_DONE };

static portMUX_TYPE mux = portMUX_INITIALIZER_UNLOCKED;
static QueueHandle_t rxQueue;
static LiveData live;
static AdvData adv;

static volatile bool targetSeen = false;
static NimBLEAddress targetAddr;
static String targetName;

static NimBLEClient *client = nullptr;
static NimBLERemoteCharacteristic *writeChr = nullptr;
static volatile int lastDisconnectReason = 0;

static LinkState linkState = SEARCHING;
static HsStep hs = HS_START;
static uint32_t hsDeadline = 0;
static uint32_t nextConnectAt = 0;
static uint32_t lastLiveRequest = 0;
static int failedConnects = 0;
static float fwVersion = 0;
static String statusLine = "Searching...";

// ----------------------------------------------------------------------------
// Crypto helpers
// ----------------------------------------------------------------------------
static void aesBlock(const uint8_t *in, uint8_t *out, bool encrypt) {
  mbedtls_aes_context ctx;
  mbedtls_aes_init(&ctx);
  if (encrypt)
    mbedtls_aes_setkey_enc(&ctx, AES_KEY, 128);
  else
    mbedtls_aes_setkey_dec(&ctx, AES_KEY, 128);
  mbedtls_aes_crypt_ecb(&ctx, encrypt ? MBEDTLS_AES_ENCRYPT : MBEDTLS_AES_DECRYPT,
                        in, out);
  mbedtls_aes_free(&ctx);
}

static size_t decryptPacket(const uint8_t *in, size_t len, uint8_t *out) {
  size_t blocks = len / 16;
  for (size_t i = 0; i < blocks; i++) aesBlock(in + i * 16, out + i * 16, false);
  return blocks * 16;
}

static String toHex(const uint8_t *d, size_t n) {
  static const char *h = "0123456789ABCDEF";
  String s;
  s.reserve(n * 2);
  for (size_t i = 0; i < n; i++) {
    s += h[d[i] >> 4];
    s += h[d[i] & 15];
  }
  return s;
}

// ----------------------------------------------------------------------------
// BLE: scanning
// ----------------------------------------------------------------------------
static bool nameLooksLikeBooster(const std::string &name) {
  if (name.find("Super Starter") != std::string::npos) return true;
  if (name.rfind("JS", 0) == 0 && name.size() >= 5 && isdigit((uint8_t)name[2]))
    return true;
  return false;
}

// Returns true if the advertisement carried valid encrypted booster data.
static bool decodeAdvert(const NimBLEAdvertisedDevice *dev) {
  const std::vector<uint8_t> &p = dev->getPayload();
  size_t i = 0;
  while (i + 1 < p.size()) {
    uint8_t adLen = p[i];
    if (adLen == 0 || i + 1 + adLen > p.size()) break;
    uint8_t adType = p[i + 1];
    // App looks for "11FF" or "12FF": manufacturer data of 16 or 17 bytes.
    if (adType == 0xFF && (adLen == 0x11 || adLen == 0x12)) {
      uint8_t plain[16];
      aesBlock(&p[i + 2], plain, false);
      char mac[18];
      snprintf(mac, sizeof(mac), "%02x:%02x:%02x:%02x:%02x:%02x", plain[0],
               plain[1], plain[2], plain[3], plain[4], plain[5]);
      if (dev->getAddress().toString() == std::string(mac)) {
        AdvData a;
        a.valid = true;
        a.at = millis();
        a.carPct = plain[8];
        a.carStatus = plain[9];
        a.carCentiV = (plain[11] << 8) | plain[12];
        a.rssi = dev->getRSSI();
        if (a.carPct <= 100 && a.carCentiV < 2000) {
          portENTER_CRITICAL(&mux);
          adv = a;
          portEXIT_CRITICAL(&mux);
          return true;
        }
      }
    }
    i += adLen + 1;
  }
  return false;
}

class ScanCallbacks : public NimBLEScanCallbacks {
  void onResult(const NimBLEAdvertisedDevice *dev) override {
    std::string addr = dev->getAddress().toString();
    if (strlen(PINNED_MAC) && addr != PINNED_MAC) return;

    bool hasData = decodeAdvert(dev);
    bool looks = nameLooksLikeBooster(dev->getName()) ||
                 dev->isAdvertisingService(SVC_UUID);
    if (!hasData && !looks) return;

    if (!targetSeen) {
      Serial.printf("[scan] booster found: %s name='%s' rssi=%d data=%s\n",
                    addr.c_str(), dev->getName().c_str(), dev->getRSSI(),
                    hasData ? "yes" : "no");
    }
    portENTER_CRITICAL(&mux);
    targetAddr = dev->getAddress();
    portEXIT_CRITICAL(&mux);
    if (dev->getName().size()) targetName = dev->getName().c_str();
    targetSeen = true;
  }
} scanCallbacks;

static void startScan() {
  NimBLEScan *scan = NimBLEDevice::getScan();
  if (scan->isScanning()) return;
  scan->setScanCallbacks(&scanCallbacks, true);  // want duplicates: live adverts
  scan->setActiveScan(true);                     // name is in the scan response
  scan->setInterval(100);
  scan->setWindow(80);
  scan->start(0, false, true);                   // scan until stopped
}

// ----------------------------------------------------------------------------
// BLE: connection
// ----------------------------------------------------------------------------
static void onNotify(NimBLERemoteCharacteristic *, uint8_t *data, size_t len, bool) {
  RxPacket pkt;
  pkt.len = len > sizeof(pkt.data) ? sizeof(pkt.data) : len;
  memcpy(pkt.data, data, pkt.len);
  xQueueSend(rxQueue, &pkt, 0);
}

class ClientCallbacks : public NimBLEClientCallbacks {
  void onConnect(NimBLEClient *c) override {
    Serial.printf("[ble] connected to %s\n", c->getPeerAddress().toString().c_str());
  }
  void onDisconnect(NimBLEClient *, int reason) override {
    lastDisconnectReason = reason;
    Serial.printf("[ble] disconnected, reason %d (0x%X)\n", reason, reason);
  }
} clientCallbacks;

static bool sendCmd(const uint8_t *cmd, size_t len) {
  if (!writeChr || !client || !client->isConnected() || len > 16) return false;
  uint8_t padded[16] = {0}, enc[16];
  memcpy(padded, cmd, len);
  aesBlock(padded, enc, true);
  bool ok = writeChr->writeValue(enc, 16, false);
  Serial.printf("[tx] %s -> %s\n", toHex(cmd, len).c_str(), ok ? "ok" : "FAILED");
  return ok;
}

static void setStatus(const String &s) {
  if (s != statusLine) {
    statusLine = s;
    Serial.println("[status] " + s);
  }
}

static bool connectBooster() {
  NimBLEAddress addr;
  portENTER_CRITICAL(&mux);
  addr = targetAddr;
  portEXIT_CRITICAL(&mux);

  NimBLEDevice::getScan()->stop();
  delay(100);
  linkState = CONNECTING;
  setStatus("Connecting...");

  if (!client) {
    client = NimBLEDevice::createClient();
    client->setClientCallbacks(&clientCallbacks, false);
    client->setConnectTimeout(CONNECT_TIMEOUT_MS);
    client->setConnectionParams(12, 24, 0, 400);
  }

  Serial.printf("[ble] connecting to %s (type %d)\n", addr.toString().c_str(),
                addr.getType());
  if (!client->connect(addr, true, false, true)) {
    Serial.printf("[ble] connect failed, error %d\n", client->getLastError());
    return false;
  }

  NimBLERemoteService *svc = client->getService(SVC_UUID);
  if (!svc) {
    Serial.println("[ble] service FFF0 not found");
    client->disconnect();
    return false;
  }
  NimBLERemoteCharacteristic *notifyChr = svc->getCharacteristic(NOTIFY_UUID);
  writeChr = svc->getCharacteristic(WRITE_UUID);
  if (!notifyChr || !writeChr) {
    Serial.println("[ble] FFF3/FFF4 not found");
    client->disconnect();
    writeChr = nullptr;
    return false;
  }
  if (!notifyChr->subscribe(true, onNotify, true)) {
    Serial.println("[ble] enabling FFF4 notifications failed");
    client->disconnect();
    writeChr = nullptr;
    return false;
  }
  Serial.printf("[ble] ready, MTU %u\n", client->getMTU());
  xQueueReset(rxQueue);
  linkState = HANDSHAKE;
  hs = HS_START;
  hsDeadline = millis() + 500;  // app waits 500 ms before the first command
  setStatus("Handshake...");
  return true;
}

// ----------------------------------------------------------------------------
// Protocol handling
// ----------------------------------------------------------------------------
static void requestLive() {
  static const uint8_t cmd[] = {0xD1, 0x55, 0x07};
  sendCmd(cmd, sizeof(cmd));
  lastLiveRequest = millis();
  hs = HS_LIVE_REQ;
  hsDeadline = millis() + 3000;
}

static void advanceHandshake() {
  switch (hs) {
    case HS_START: {
      static const uint8_t cmd[] = {0xD1, 0x55, 0x01, 0x01};  // version
      sendCmd(cmd, sizeof(cmd));
      hs = HS_VERSION;
      hsDeadline = millis() + 2500;
      break;
    }
    case HS_VERSION:  // no version reply: carry on anyway
    case HS_GENUINE: {
      static const uint8_t cmd[] = {0xD1, 0x55, 0x04};  // starter start count
      sendCmd(cmd, sizeof(cmd));
      hs = HS_STARTS;
      hsDeadline = millis() + 2000;
      break;
    }
    case HS_STARTS:
    case HS_LIVE_REQ:
      requestLive();
      break;
    case HS_DONE:
      break;
  }
}

static void parseLive(const uint8_t *b) {
  LiveData d;
  d.valid = true;
  d.at = millis();
  d.carStatus = b[5];
  d.carPct = b[6];
  d.carCentiV = (b[7] << 8) | b[8];
  d.starterTemp = b[9] == 1 ? -(int)b[10] : (int)b[10];
  d.starterStatus = b[11];
  d.starterPct = b[12];
  d.starterCentiV = (b[13] << 8) | b[14];
  d.flags = b[15];
  if (d.flags & 0x40) d.carStatus = -1;               // battery not connected
  else if (b[24] == 0x01) d.carStatus = 3;            // engine running flag
  if (d.carPct > 100 || d.starterPct > 100) return;   // corrupt frame
  portENTER_CRITICAL(&mux);
  live = d;
  portEXIT_CRITICAL(&mux);
}

static void handlePacket(const RxPacket &pkt) {
  uint8_t plain[64] = {0};
  size_t n = decryptPacket(pkt.data, pkt.len, plain);
  if (n < 16) {
    Serial.printf("[rx] short packet (%u bytes): %s\n", pkt.len,
                  toHex(pkt.data, pkt.len).c_str());
    return;
  }
  if (plain[0] != 0xD1 || plain[1] != 0x55) {
    Serial.printf("[rx] unknown: %s\n", toHex(plain, n).c_str());
    return;
  }
  uint8_t type = plain[2];
  if (type != 0x07 || plain[3] == 0xFF)
    Serial.printf("[rx] %s\n", toHex(plain, n).c_str());

  switch (type) {
    case 0x01:  // version
      fwVersion = plain[3] + plain[4] / 10.0f;
      Serial.printf("[proto] firmware %.1f, verified flag %02X, battery type %u\n",
                    fwVersion, plain[5], plain[6]);
      if (hs == HS_VERSION) {
        if (plain[5] == 0x00 && SEND_GENUINE_REPLY) {
          // Same as the app: D1 55 02 00 (genuine) 01 (non-Chinese UI)
          static const uint8_t cmd[] = {0xD1, 0x55, 0x02, 0x00, 0x01};
          sendCmd(cmd, sizeof(cmd));
          hs = HS_GENUINE;
          hsDeadline = millis() + 2000;
        } else {
          advanceHandshake();
        }
      }
      break;
    case 0x02:
      Serial.printf("[proto] genuine reply result %02X\n", plain[3]);
      if (hs == HS_GENUINE) advanceHandshake();
      break;
    case 0x04:
      Serial.printf("[proto] starter start count %lu\n",
                    (unsigned long)((plain[3] << 24) | (plain[4] << 16) |
                                    (plain[5] << 8) | plain[6]));
      if (hs == HS_STARTS) advanceHandshake();
      break;
    case 0x07:
      if (plain[3] == 0xFF) {
        Serial.println("[proto] live data acknowledged");
        hs = HS_DONE;
      } else if (n >= 32) {
        parseLive(plain);
        hs = HS_DONE;
        linkState = LIVE;
      }
      break;
    default:
      break;  // alarms, history, CCA results: logged above, ignored
  }
}

// ----------------------------------------------------------------------------
// Display
// ----------------------------------------------------------------------------
static const uint16_t C_BG = GC9A01A_BLACK;
static const uint16_t C_DIM = 0x4208;
static const uint16_t C_LABEL = 0x9CF3;
static const uint16_t C_GREEN = 0x07E8;
static const uint16_t C_ORANGE = 0xFD20;
static const uint16_t C_RED = 0xF8A2;
static const uint16_t C_CYAN = 0x2E7F;
static const uint16_t C_PURPLE = 0xCB3F;

static uint16_t pctColor(int pct) {
  if (pct < 25) return C_RED;
  if (pct < 55) return C_ORANGE;
  return C_GREEN;
}

static void textCentered(const String &s, int cx, int y, int size, uint16_t fg,
                         int clearW) {
  tft.setTextSize(size);
  if (clearW > 0) tft.fillRect(cx - clearW / 2, y, clearW, 8 * size, C_BG);
  tft.setTextColor(fg);
  int w = s.length() * 6 * size;
  tft.setCursor(cx - w / 2, y);
  tft.print(s);
}

// Angles: 0 = top, clockwise, degrees.
static void fillArc(int r0, int r1, float a0, float a1, uint16_t col) {
  if (a1 < a0) std::swap(a0, a1);
  const int cx = 120, cy = 120;
  for (float a = a0; a < a1; a += 2) {
    float b = min(a + 2, a1);
    float ra = radians(a), rb = radians(b);
    int x0 = cx + r0 * sin(ra), y0 = cy - r0 * cos(ra);
    int x1 = cx + r1 * sin(ra), y1 = cy - r1 * cos(ra);
    int x2 = cx + r0 * sin(rb), y2 = cy - r0 * cos(rb);
    int x3 = cx + r1 * sin(rb), y3 = cy - r1 * cos(rb);
    tft.fillTriangle(x0, y0, x1, y1, x2, y2, col);
    tft.fillTriangle(x1, y1, x2, y2, x3, y3, col);
  }
}

// Car gauge on the left (200..340 deg), starter on the right (160..20 deg),
// both filling from the bottom upwards.
static void drawGauge(bool left, int pct, uint16_t col) {
  const int r0 = 108, r1 = 118;
  const float span = 140;
  pct = constrain(pct, 0, 100);
  float fill = span * pct / 100.0f;
  if (left) {
    fillArc(r0, r1, 200, 200 + fill, col);
    fillArc(r0, r1, 200 + fill, 340, C_DIM);
  } else {
    fillArc(r0, r1, 160 - fill, 160, col);
    fillArc(r0, r1, 20, 160 - fill, C_DIM);
  }
}

static String volts(uint16_t centi) {
  char buf[8];
  snprintf(buf, sizeof(buf), "%u.%02u", centi / 100, centi % 100);
  return buf;
}

static const char *carStatusText(int s) {
  switch (s) {
    case -1: return "NOT CONNECTED";
    case 0: return "NORMAL";
    case 1: return "LOW BATTERY";
    case 2: return "CHARGING";
    case 3: return "ENGINE ON";
    default: return "";
  }
}
static uint16_t carStatusColor(int s) {
  return s == 0 ? C_GREEN : s == 2 || s == 3 ? C_CYAN : C_RED;
}
static const char *starterStatusText(int s) {
  switch (s) {
    case 0: return "IDLE";
    case 1: return "DISCHARGING";
    case 2: return "CHARGING";
    default: return "";
  }
}

static void drawStatic() {
  tft.fillScreen(C_BG);
  tft.cp437(true);
  textCentered("SUPER STARTER", 120, 26, 1, C_LABEL, 0);
  textCentered("CAR", 66, 50, 2, C_CYAN, 0);
  textCentered("STARTER", 174, 50, 2, C_PURPLE, 0);
  tft.drawFastVLine(120, 48, 128, C_DIM);
  textCentered("VOLTS", 66, 104, 1, C_LABEL, 0);
  textCentered("VOLTS", 174, 104, 1, C_LABEL, 0);
  drawGauge(true, 0, C_DIM);
  drawGauge(false, 0, C_DIM);
}

struct Shown {
  String carV, carP, carS, stV, stP, stS, temp, status;
  int carGauge = -1, stGauge = -1;
  uint16_t carGaugeCol = 0, stGaugeCol = 0;
} shown;

static void field(String &cache, const String &val, int cx, int y, int size,
                  uint16_t col, int clearW) {
  String key = val + "#" + String(col);
  if (cache == key) return;
  cache = key;
  textCentered(val, cx, y, size, col, clearW);
}

static void render() {
  LiveData d;
  AdvData a;
  portENTER_CRITICAL(&mux);
  d = live;
  a = adv;
  portEXIT_CRITICAL(&mux);

  uint32_t now = millis();
  bool liveFresh = d.valid && linkState == LIVE && now - d.at < STALE_MS;
  bool advFresh = a.valid && now - a.at < BROADCAST_STALE_MS;

  // Car side: live data first, broadcast data as fallback.
  bool carOk = liveFresh || advFresh;
  int carPct = liveFresh ? d.carPct : a.carPct;
  uint16_t carCV = liveFresh ? d.carCentiV : a.carCentiV;
  int carSt = liveFresh ? d.carStatus : a.carStatus;
  uint16_t carCol = carOk ? (carCV < 1200 ? C_RED : carCV < 1240 ? C_ORANGE : C_GREEN)
                          : C_DIM;

  field(shown.carV, carOk ? volts(carCV) : String("--.--"), 66, 76, 3, carCol, 100);
  field(shown.carP, carOk ? String(carPct) + "%" : String("--%"), 66, 120, 3,
        carOk ? pctColor(carPct) : C_DIM, 96);
  field(shown.carS, carOk ? String(carStatusText(carSt)) : String(""), 66, 156, 1,
        carStatusColor(carSt), 100);

  int cg = carOk ? carPct : 0;
  uint16_t cgc = carOk ? pctColor(carPct) : C_DIM;
  if (cg != shown.carGauge || cgc != shown.carGaugeCol) {
    shown.carGauge = cg;
    shown.carGaugeCol = cgc;
    drawGauge(true, cg, cgc);
  }

  // Starter side: only available over the live connection.
  field(shown.stV, liveFresh ? volts(d.starterCentiV) : String("--.--"), 174, 76, 3,
        liveFresh ? C_PURPLE : C_DIM, 100);
  field(shown.stP, liveFresh ? String(d.starterPct) + "%" : String("--%"), 174, 120,
        3, liveFresh ? pctColor(d.starterPct) : C_DIM, 96);
  String stState = "";
  uint16_t stCol = C_LABEL;
  if (liveFresh) {
    if (d.flags & 0x10) { stState = "OVER TEMP"; stCol = C_RED; }
    else if (d.flags & 0x20) { stState = "TOO COLD"; stCol = C_ORANGE; }
    else if (d.flags & 0x01) { stState = "STARTER FAULT"; stCol = C_RED; }
    else stState = starterStatusText(d.starterStatus);
  }
  field(shown.stS, stState, 174, 156, 1, stCol, 100);

  int sg = liveFresh ? d.starterPct : 0;
  uint16_t sgc = liveFresh ? pctColor(d.starterPct) : C_DIM;
  if (sg != shown.stGauge || sgc != shown.stGaugeCol) {
    shown.stGauge = sg;
    shown.stGaugeCol = sgc;
    drawGauge(false, sg, sgc);
  }

  String temp = liveFresh ? String(d.starterTemp) + (char)247 + "C" : String("");
  field(shown.temp, temp, 120, 176, 2, C_LABEL, 90);

  String st;
  uint16_t stc = C_ORANGE;
  if (liveFresh) { st = "LIVE"; stc = C_GREEN; }
  else if (advFresh && linkState != LIVE) {
    st = CONNECT_FOR_LIVE ? "BROADCAST - " + statusLine : String("BROADCAST");
    stc = C_CYAN;
  } else st = statusLine;
  field(shown.status, st, 120, 202, 1, stc, 150);
}

// ----------------------------------------------------------------------------
// Main
// ----------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\nSuper Starter display starting");

  rxQueue = xQueueCreate(16, sizeof(RxPacket));

  SPI.begin(PIN_SCK, -1, PIN_MOSI, PIN_CS);
  tft.begin(40000000);
  tft.setRotation(0);
  drawStatic();
  render();

  // Self-test the decoder with a frame captured from a real JS300.
  const uint8_t sample[16] = {0x6b, 0x8a, 0xdc, 0x7c, 0xb0, 0xa4, 0x0e, 0xb5,
                              0xe2, 0xe8, 0x36, 0x29, 0x25, 0x8d, 0x0e, 0x2c};
  uint8_t out[16];
  aesBlock(sample, out, false);
  bool ok = out[0] == 0xD1 && out[1] == 0x55 && out[2] == 0x07 && out[6] == 81;
  Serial.printf("Decoder self-test: %s (%s)\n", ok ? "PASS" : "FAIL",
                toHex(out, 16).c_str());

  NimBLEDevice::init("BoosterDisplay");
  NimBLEDevice::setMTU(247);
  startScan();
}

void loop() {
  uint32_t now = millis();

  RxPacket pkt;
  while (xQueueReceive(rxQueue, &pkt, 0) == pdTRUE) handlePacket(pkt);

  bool connected = client && client->isConnected();

  switch (linkState) {
    case SEARCHING:
    case BACKOFF:
      if (!NimBLEDevice::getScan()->isScanning()) startScan();
      if (!targetSeen) {
        setStatus("Searching...");
      } else if (!CONNECT_FOR_LIVE) {
        setStatus("Listening");
      } else if ((int32_t)(now - nextConnectAt) >= 0) {
        if (connectBooster()) {
          failedConnects = 0;
        } else {
          failedConnects++;
          if (client && client->isConnected()) client->disconnect();
          writeChr = nullptr;
          // Back off: 5 s, then 15 s, then 60 s (lets the phone app connect).
          uint32_t wait = failedConnects < 3 ? 5000 : failedConnects < 6 ? 15000 : 60000;
          nextConnectAt = millis() + wait;
          linkState = BACKOFF;
          setStatus("Retry in " + String(wait / 1000) + "s");
          startScan();
        }
      }
      break;

    case CONNECTING:
      break;

    case HANDSHAKE:
      if (!connected) break;
      if ((int32_t)(now - hsDeadline) >= 0) advanceHandshake();
      if (hs == HS_DONE && now - lastLiveRequest > 6000) requestLive();
      break;

    case LIVE: {
      LiveData d;
      portENTER_CRITICAL(&mux);
      d = live;
      portEXIT_CRITICAL(&mux);
      if (connected && now - d.at > 5000 && now - lastLiveRequest > 5000) {
        Serial.println("[proto] live data paused, asking again");
        requestLive();
      }
      if (connected && now - d.at > 45000) {
        Serial.println("[proto] no data for 45 s, reconnecting");
        client->disconnect();
      }
      break;
    }
  }

  if (linkState == HANDSHAKE || linkState == LIVE) {
    if (!connected) {
      Serial.printf("[ble] link lost (reason %d)\n", lastDisconnectReason);
      writeChr = nullptr;
      linkState = BACKOFF;
      nextConnectAt = now + 5000;
      setStatus("Link lost, retry 5s");
      startScan();
    } else if (linkState == HANDSHAKE) {
      setStatus("Waiting for data");
    }
  }

  static uint32_t lastDraw = 0;
  if (now - lastDraw > 250) {
    lastDraw = now;
    render();
  }
  delay(10);
}
