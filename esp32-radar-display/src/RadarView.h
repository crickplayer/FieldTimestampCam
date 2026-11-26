// Draws the live radar view on the 2.8" ILI9341 display (landscape, 320 x 240).
//
//   +------------------------------------------+
//   | LD2450  V2.04...        2 people  10.0 Hz |  status bar   (y 0..19)
//   |            .-''''''''''''-.               |
//   |         .'   \    |    /   '.             |  top-down fan (y 20..179)
//   |        /  6m  \   |   /      \            |  radar at the bottom centre,
//   |           ...  \  |  /  ...               |  +/-60 deg, rings every 1 m
//   |                  \|/                      |
//   |-------------------------------------------|
//   | (1) 1.89 m   -25 deg   -0.16 m/s          |  one row per person
//   | (2) --                                    |  (y 180..239)
//   | (3) --                                    |
//   +------------------------------------------+
#pragma once

#include <TFT_eSPI.h>

#include "LD2450.h"
#include "RadarNetwork.h"
#include "config.h"

class RadarView {
 public:
  RadarView();
  bool begin();
  void showMessage(const char* line1, const char* line2);
  void setFirmwareVersion(const char* version);

  // online = frames are arriving from the radar.
  void render(const ld2450::Frame& frame, bool online, float frames_per_sec, const NetStatus& net);

 private:
  struct Point {
    int16_t x, y;
  };

  bool toScreen(const ld2450::Target& t, Point* p) const;
  void drawFanBackground();
  void drawTargets(const ld2450::Frame& frame);
  void drawInfo(const ld2450::Frame& frame, bool online, const NetStatus& net);
  void drawStatus(int count, bool online, float fps, const NetStatus& net);

  TFT_eSPI tft_;
  TFT_eSprite fan_;
  TFT_eSprite info_;
  bool sprites_ok_ = false;
  char firmware_[24] = "";
  char last_status_[96] = "";

  Point trail_[ld2450::kMaxTargets][kTrailLength];
  int trail_len_[ld2450::kMaxTargets] = {0, 0, 0};
};
