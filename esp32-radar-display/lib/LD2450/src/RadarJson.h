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

// Reads a target frame written by encodeFrameJson (used by viewers such as the round display).
// json need not be zero-terminated. Returns true and fills frame (target i goes to slot id - 1;
// x is used as sent, so it is already mirrored if the radar ESP32 has kMirrorX set) when the
// message is a target frame. Returns false for other messages (e.g. "status") or bad input.
// seq may be null.
bool decodeFrameJson(const char* json, size_t len, Frame* frame, uint32_t* seq);

}  // namespace ld2450
