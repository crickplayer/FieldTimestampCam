#include "RadarView.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

namespace {
constexpr int kScreenW = 320;
constexpr int kStatusH = 20;
constexpr int kFanY = kStatusH;
constexpr int kFanH = 160;
constexpr int kInfoY = kFanY + kFanH;
constexpr int kInfoH = 240 - kInfoY;

// Radar position inside the fan sprite and how many pixels the max range covers.
constexpr int kOriginX = kScreenW / 2;
constexpr int kOriginY = kFanH - 6;
constexpr int kRadiusPx = 148;
constexpr float kPxPerMm = kRadiusPx / (kMaxRangeM * 1000.0f);
constexpr int kArcStepDeg = 4;

constexpr float kDegToRad = 0.01745329252f;

const uint16_t kTargetColors[ld2450::kMaxTargets] = {TFT_CYAN, TFT_YELLOW, TFT_MAGENTA};

uint16_t rgb(uint8_t r, uint8_t g, uint8_t b) {
  return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
}

uint16_t dim(uint16_t c, float f) {
  uint8_t r = ((c >> 11) & 0x1F) << 3, g = ((c >> 5) & 0x3F) << 2, b = (c & 0x1F) << 3;
  return rgb(r * f, g * f, b * f);
}

// Point on the fan at a given angle (0 = straight ahead) and radius in pixels.
void polar(float deg, float r, int16_t* x, int16_t* y) {
  *x = kOriginX + (int16_t)lroundf(r * sinf(deg * kDegToRad));
  *y = kOriginY - (int16_t)lroundf(r * cosf(deg * kDegToRad));
}
}  // namespace

RadarView::RadarView() : fan_(&tft_), info_(&tft_) {}

bool RadarView::begin() {
  tft_.init();
  tft_.setRotation(kScreenRotation);
  tft_.fillScreen(TFT_BLACK);

  // 8-bit sprites (51 KB + 19 KB) give flicker-free redraws without running out of RAM.
  fan_.setColorDepth(8);
  info_.setColorDepth(8);
  sprites_ok_ = fan_.createSprite(kScreenW, kFanH) != nullptr &&
                info_.createSprite(kScreenW, kInfoH) != nullptr;
  return sprites_ok_;
}

void RadarView::setFirmwareVersion(const char* version) {
  strncpy(firmware_, version ? version : "", sizeof(firmware_) - 1);
  firmware_[sizeof(firmware_) - 1] = '\0';
  last_status_[0] = '\0';  // force a status-bar redraw
}

void RadarView::showMessage(const char* line1, const char* line2) {
  if (!sprites_ok_) return;
  drawFanBackground();
  fan_.setTextDatum(MC_DATUM);
  fan_.setTextColor(TFT_WHITE);
  fan_.drawString(line1, kOriginX, kFanH / 2 - 12, 4);
  fan_.setTextColor(TFT_LIGHTGREY);
  fan_.drawString(line2, kOriginX, kFanH / 2 + 14, 2);
  fan_.pushSprite(0, kFanY);
}

void RadarView::render(const ld2450::Frame& frame, bool online, float fps) {
  if (!sprites_ok_) return;

  if (online) {
    drawFanBackground();
    drawTargets(frame);
    fan_.pushSprite(0, kFanY);
  } else {
    for (int& n : trail_len_) n = 0;
    showMessage("NO RADAR DATA", "Check radar Tx -> GPIO16, 5V and GND");
  }

  drawInfo(frame, online);
  info_.pushSprite(0, kInfoY);
  drawStatus(online ? frame.count() : 0, online, fps);
}

bool RadarView::toScreen(const ld2450::Target& t, Point* p) const {
  float x = kMirrorX ? -t.x_mm : t.x_mm;
  p->x = kOriginX + (int16_t)lroundf(x * kPxPerMm);
  p->y = kOriginY - (int16_t)lroundf(t.y_mm * kPxPerMm);
  return p->x >= 0 && p->x < kScreenW && p->y >= 0 && p->y < kFanH;
}

void RadarView::drawFanBackground() {
  const uint16_t fill = rgb(0, 40, 22);
  const uint16_t grid = rgb(0, 120, 60);
  const uint16_t label = rgb(130, 130, 130);

  fan_.fillSprite(TFT_BLACK);

  // Filled +/-60 degree sector.
  for (int a = -kFovDeg; a < kFovDeg; a += kArcStepDeg) {
    int16_t x1, y1, x2, y2;
    polar(a, kRadiusPx, &x1, &y1);
    polar(a + kArcStepDeg, kRadiusPx, &x2, &y2);
    fan_.fillTriangle(kOriginX, kOriginY, x1, y1, x2, y2, fill);
  }

  // Range rings every metre.
  for (int m = 1; m <= (int)kMaxRangeM; m++) {
    float r = m * 1000.0f * kPxPerMm;
    int16_t px, py;
    polar(-kFovDeg, r, &px, &py);
    for (int a = -kFovDeg + kArcStepDeg; a <= kFovDeg; a += kArcStepDeg) {
      int16_t x, y;
      polar(a, r, &x, &y);
      fan_.drawLine(px, py, x, y, grid);
      px = x;
      py = y;
    }
  }

  // Angle spokes at -60, -30, 0, 30, 60 degrees.
  for (int a = -60; a <= 60; a += 30) {
    int16_t x, y;
    polar(a, kRadiusPx, &x, &y);
    fan_.drawLine(kOriginX, kOriginY, x, y, grid);
  }

  // Distance labels along the centre line.
  fan_.setTextDatum(ML_DATUM);
  fan_.setTextColor(label);
  for (int m = 2; m <= (int)kMaxRangeM; m += 2) {
    char text[6];
    snprintf(text, sizeof(text), "%dm", m);
    fan_.drawString(text, kOriginX + 4, kOriginY - (int)(m * 1000.0f * kPxPerMm) + 6, 1);
  }

  // The radar itself.
  fan_.fillTriangle(kOriginX, kOriginY - 6, kOriginX - 6, kOriginY + 4, kOriginX + 6,
                    kOriginY + 4, TFT_WHITE);
}

void RadarView::drawTargets(const ld2450::Frame& frame) {
  for (int i = 0; i < ld2450::kMaxTargets; i++) {
    const ld2450::Target& t = frame.targets[i];
    Point p;
    if (!t.valid || !toScreen(t, &p)) {
      trail_len_[i] = 0;
      continue;
    }

    // Add to the trail (oldest first).
    if (trail_len_[i] == kTrailLength) {
      memmove(trail_[i], trail_[i] + 1, sizeof(Point) * (kTrailLength - 1));
      trail_len_[i]--;
    }
    trail_[i][trail_len_[i]++] = p;

    const uint16_t color = kTargetColors[i];
    for (int k = 0; k < trail_len_[i] - 1; k++) {
      float f = 0.15f + 0.6f * (k + 1) / trail_len_[i];
      fan_.fillCircle(trail_[i][k].x, trail_[i][k].y, 2, dim(color, f));
    }

    fan_.fillCircle(p.x, p.y, 7, color);
    fan_.drawCircle(p.x, p.y, 8, TFT_WHITE);
    char num[2] = {(char)('1' + i), '\0'};
    fan_.setTextDatum(MC_DATUM);
    fan_.setTextColor(TFT_BLACK);
    fan_.drawString(num, p.x, p.y + 1, 1);
  }
}

void RadarView::drawInfo(const ld2450::Frame& frame, bool online) {
  const uint16_t grid = rgb(0, 120, 60);
  info_.fillSprite(TFT_BLACK);
  info_.drawFastHLine(0, 0, kScreenW, grid);

  for (int i = 0; i < ld2450::kMaxTargets; i++) {
    const ld2450::Target& t = frame.targets[i];
    const int y = 3 + i * 19;
    const bool show = online && t.valid;

    uint16_t color = show ? kTargetColors[i] : rgb(70, 70, 70);
    info_.fillCircle(12, y + 9, 7, color);
    char num[2] = {(char)('1' + i), '\0'};
    info_.setTextDatum(MC_DATUM);
    info_.setTextColor(TFT_BLACK);
    info_.drawString(num, 12, y + 10, 1);

    char text[64];
    if (show) {
      float angle = kMirrorX ? -t.angleDeg() : t.angleDeg();
      snprintf(text, sizeof(text), "%.2f m     %+.0f deg     %+.2f m/s",
               t.distanceMm() / 1000.0f, angle, t.speed_cms / 100.0f);
      info_.setTextColor(TFT_WHITE);
    } else {
      snprintf(text, sizeof(text), "--");
      info_.setTextColor(rgb(90, 90, 90));
    }
    info_.setTextDatum(ML_DATUM);
    info_.drawString(text, 26, y + 9, 2);
  }
}

void RadarView::drawStatus(int count, bool online, float fps) {
  char status[64];
  if (online) {
    snprintf(status, sizeof(status), "%d %s   %.1f Hz", count, count == 1 ? "person" : "people",
             fps);
  } else {
    snprintf(status, sizeof(status), "NO DATA");
  }
  char key[sizeof(status) + sizeof(firmware_)];
  snprintf(key, sizeof(key), "%s|%s", status, firmware_);
  if (strcmp(key, last_status_) == 0) return;  // unchanged: skip redraw
  strncpy(last_status_, key, sizeof(last_status_) - 1);
  last_status_[sizeof(last_status_) - 1] = '\0';

  const uint16_t bar = rgb(20, 30, 45);
  tft_.fillRect(0, 0, kScreenW, kStatusH, bar);
  tft_.setTextDatum(ML_DATUM);
  tft_.setTextColor(TFT_WHITE, bar);
  tft_.drawString("LD2450", 4, kStatusH / 2, 2);
  tft_.setTextColor(TFT_LIGHTGREY, bar);
  tft_.drawString(firmware_, 60, kStatusH / 2, 1);
  tft_.setTextDatum(MR_DATUM);
  tft_.setTextColor(online ? TFT_GREEN : TFT_RED, bar);
  tft_.drawString(status, kScreenW - 4, kStatusH / 2, 2);
}
