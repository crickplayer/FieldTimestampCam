// JSON messages the ESP32 sends to viewers (phone app, browser), over both the home-network
// WebSocket and MQTT. Plain C++ so it is unit-tested on a PC.
//
// Target frame (about 10 per second):
//   {"v":1,"seq":42,"targets":[{"id":1,"x":-782,"y":1713,"speed":-16}]}
//   x, y in mm (x already mirrored if kMirrorX is set), speed in cm/s. Empty slots are left out.
#pragma once

#include <stddef.h>
#include <stdint.h>

#include "LD2450.h"

namespace ld2450 {

constexpr int kJsonVersion = 1;

// Writes the frame as JSON into out. Returns the length written (excluding the
// terminating zero), or 0 if out is too small.
size_t encodeFrameJson(const Frame& frame, bool mirror_x, uint32_t seq, char* out, size_t out_size);

}  // namespace ld2450
