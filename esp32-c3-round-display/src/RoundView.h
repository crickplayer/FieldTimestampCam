// Draws the radar on the 1.28" round GC9A01 display (240 x 240).
//
//                .-''''''-.
//             .'  2 people  '.       count of people (top)
//            /  nearest 1.9 m \      distance of the nearest person
//           |\     \  |  /    /|
//           | \  (1)\ | /    / |     top-down 120 degree fan, rings every 2 m
//           |  \     \|/ (2)/  |     radar at the bottom centre
//            \  '----- ^ ---'  /
//             '.   10 Hz     .'      link status / frame rate (bottom)
//                '-......-'
#pragma once

#include <TFT_eSPI.h>

#include "LD2450.h"
#include "config.h"

class RoundView {
 public:
  RoundView();
  bool begin();

  // Live radar picture. status is the short text under the radar (e.g. "10 Hz").
  void render(const ld2450::Frame& frame, const char* status);

  // Full-screen message: a title and up to four lines (null or "" = unused).
  void showMessage(const char* title, uint16_t title_color, const char* line1,
                   const char* line2 = nullptr, const char* line3 = nullptr,
                   const char* line4 = nullptr);

  void clearTrails();

 private:
  struct Point {
    int16_t x, y;
  };

  bool toScreen(const ld2450::Target& t, Point* p) const;
  void drawFan(uint16_t fill, uint16_t grid);
  void drawTargets(const ld2450::Frame& frame);
  void drawHeader(const ld2450::Frame& frame);
  void push();

  TFT_eSPI tft_;
  TFT_eSprite canvas_;
  bool sprite_ok_ = false;
  char last_message_[200] = "";

  Point trail_[ld2450::kMaxTargets][kTrailLength];
  int trail_len_[ld2450::kMaxTargets] = {0, 0, 0};
};
