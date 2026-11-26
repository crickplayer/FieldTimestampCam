#include "RoundView.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

namespace {
constexpr int kSize = 240;
constexpr int kCenter = kSize / 2;

// Radar position and how many pixels the max range covers. Chosen so the whole
// +/-60 degree fan fits inside the round screen.
constexpr int kOriginX = kCenter;
constexpr int kOriginY = 196;
constexpr int kRadiusPx = 134;
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

RoundView::RoundView() : canvas_(&tft_) {}

bool RoundView::begin() {
  tft_.init();
  tft_.setRotation(kScreenRotation);
  tft_.fillScreen(TFT_BLACK);

  // One full-screen buffer gives flicker-free redraws: 16-bit colour (113 KB) if memory
  // allows, otherwise 8-bit (57 KB).
  canvas_.setColorDepth(16);
  sprite_ok_ = canvas_.createSprite(kSize, kSize) != nullptr;
  if (!sprite_ok_) {
    canvas_.setColorDepth(8);
    sprite_ok_ = canvas_.createSprite(kSize, kSize) != nullptr;
  }
  return sprite_ok_;
}

void RoundView::clearTrails() {
  for (int& n : trail_len_) n = 0;
}

void RoundView::push() {
  canvas_.pushSprite(0, 0);
}

void RoundView::showMessage(const char* title, uint16_t title_color, const char* line1,
                            const char* line2, const char* line3, const char* line4) {
  if (!sprite_ok_) return;
  const char* lines[] = {line1, line2, line3, line4};

  // Only redraw when the text changes.
  char key[sizeof(last_message_)];
  snprintf(key, sizeof(key), "%s|%u|%s|%s|%s|%s", title, title_color, line1 ? line1 : "",
           line2 ? line2 : "", line3 ? line3 : "", line4 ? line4 : "");
  if (strcmp(key, last_message_) == 0) return;
  strncpy(last_message_, key, sizeof(last_message_) - 1);
  last_message_[sizeof(last_message_) - 1] = '\0';

  // Dimmed radar fan behind the text.
  canvas_.fillSprite(TFT_BLACK);
  drawFan(rgb(0, 18, 10), rgb(0, 50, 25));

  int n = 0;
  for (const char* l : lines) n += (l && l[0]) ? 1 : 0;
  const int line_h = 20;
  int y = kCenter - (30 + n * line_h) / 2 + 4;

  canvas_.setTextDatum(MC_DATUM);
  canvas_.setTextColor(title_color);
  canvas_.drawString(title, kCenter, y, 4);
  y += 30;
  canvas_.setTextColor(TFT_WHITE);
  for (const char* l : lines) {
    if (!l || !l[0]) continue;
    canvas_.drawString(l, kCenter, y, 2);
    y += line_h;
  }
  push();
}

void RoundView::render(const ld2450::Frame& frame, const char* status, const char* footer) {
  if (!sprite_ok_) return;
  last_message_[0] = '\0';  // the next message must redraw

  canvas_.fillSprite(TFT_BLACK);
  drawFan(rgb(0, 40, 22), rgb(0, 120, 60));
  drawTargets(frame);
  drawHeader(frame);

  canvas_.setTextDatum(MC_DATUM);
  canvas_.setTextColor(rgb(140, 140, 140));
  canvas_.drawString(status, kCenter, 213, 2);
  canvas_.setTextColor(rgb(90, 90, 90));
  canvas_.drawString(footer, kCenter, 228, 1);
  push();
}

bool RoundView::toScreen(const ld2450::Target& t, Point* p) const {
  // x arrives already mirrored by the radar ESP32 if its kMirrorX is set.
  p->x = kOriginX + (int16_t)lroundf(t.x_mm * kPxPerMm);
  p->y = kOriginY - (int16_t)lroundf(t.y_mm * kPxPerMm);
  const int dx = p->x - kCenter, dy = p->y - kCenter;
  return dx * dx + dy * dy <= 116 * 116;  // inside the round screen
}

void RoundView::drawFan(uint16_t fill, uint16_t grid) {
  const uint16_t label = rgb(130, 130, 130);

  // Filled +/-60 degree sector.
  for (int a = -kFovDeg; a < kFovDeg; a += kArcStepDeg) {
    int16_t x1, y1, x2, y2;
    polar(a, kRadiusPx, &x1, &y1);
    polar(a + kArcStepDeg, kRadiusPx, &x2, &y2);
    canvas_.fillTriangle(kOriginX, kOriginY, x1, y1, x2, y2, fill);
  }

  // Range rings every 2 m (thin ones at the odd metres).
  for (int m = 1; m <= (int)kMaxRangeM; m++) {
    const float r = m * 1000.0f * kPxPerMm;
    const uint16_t c = (m % 2 == 0) ? grid : dim(grid, 0.55f);
    int16_t px, py;
    polar(-kFovDeg, r, &px, &py);
    for (int a = -kFovDeg + kArcStepDeg; a <= kFovDeg; a += kArcStepDeg) {
      int16_t x, y;
      polar(a, r, &x, &y);
      canvas_.drawLine(px, py, x, y, c);
      px = x;
      py = y;
    }
  }

  // Angle spokes at -60, -30, 0, 30, 60 degrees.
  for (int a = -60; a <= 60; a += 30) {
    int16_t x, y;
    polar(a, kRadiusPx, &x, &y);
    canvas_.drawLine(kOriginX, kOriginY, x, y, grid);
  }

  // Distance labels just right of the centre line.
  canvas_.setTextDatum(ML_DATUM);
  canvas_.setTextColor(label);
  for (int m = 2; m <= (int)kMaxRangeM - 2; m += 2) {
    char text[6];
    snprintf(text, sizeof(text), "%dm", m);
    canvas_.drawString(text, kOriginX + 4, kOriginY - (int)(m * 1000.0f * kPxPerMm) + 6, 1);
  }

  // The radar itself.
  canvas_.fillTriangle(kOriginX, kOriginY - 6, kOriginX - 6, kOriginY + 4, kOriginX + 6,
                       kOriginY + 4, TFT_WHITE);

  // Round bezel.
  canvas_.drawCircle(kCenter, kCenter, kCenter - 1, rgb(40, 60, 50));
}

void RoundView::drawTargets(const ld2450::Frame& frame) {
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
      const float f = 0.15f + 0.6f * (k + 1) / trail_len_[i];
      canvas_.fillCircle(trail_[i][k].x, trail_[i][k].y, 2, dim(color, f));
    }

    canvas_.fillCircle(p.x, p.y, 7, color);
    canvas_.drawCircle(p.x, p.y, 8, TFT_WHITE);
    char num[2] = {(char)('1' + i), '\0'};
    canvas_.setTextDatum(MC_DATUM);
    canvas_.setTextColor(TFT_BLACK);
    canvas_.drawString(num, p.x, p.y + 1, 1);

    // Distance next to the dot (on the side towards the screen centre).
    char dist[8];
    snprintf(dist, sizeof(dist), "%.1fm", t.distanceMm() / 1000.0f);
    const bool right = p.x < kCenter;
    canvas_.setTextDatum(right ? ML_DATUM : MR_DATUM);
    canvas_.setTextColor(color);
    canvas_.drawString(dist, right ? p.x + 11 : p.x - 11, p.y, 1);
  }
}

void RoundView::drawHeader(const ld2450::Frame& frame) {
  const int count = frame.count();
  char title[16];
  if (count == 0) {
    snprintf(title, sizeof(title), "Nobody");
  } else {
    snprintf(title, sizeof(title), "%d %s", count, count == 1 ? "person" : "people");
  }
  canvas_.setTextDatum(MC_DATUM);
  canvas_.setTextColor(count ? TFT_WHITE : rgb(150, 150, 150));
  canvas_.drawString(title, kCenter, 26, 4);

  float nearest = 0;
  for (const ld2450::Target& t : frame.targets) {
    if (!t.valid) continue;
    const float d = t.distanceMm() / 1000.0f;
    if (nearest == 0 || d < nearest) nearest = d;
  }
  if (count) {
    char line[24];
    snprintf(line, sizeof(line), "nearest %.2f m", nearest);
    canvas_.setTextColor(TFT_GREEN);
    canvas_.drawString(line, kCenter, 50, 2);
  }
}
