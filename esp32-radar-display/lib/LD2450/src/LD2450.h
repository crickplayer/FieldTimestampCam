// HLK-LD2450 protocol: streaming parser and command builder.
//
// Plain C++ with no Arduino dependency, so it runs in unit tests on a PC and can be
// ported to the Android app later. Protocol reference: docs/hlk-ld2450/ANALYSIS.md
#pragma once

#include <stddef.h>
#include <stdint.h>

namespace ld2450 {

constexpr int kMaxTargets = 3;
constexpr size_t kDataFrameLen = 30;   // AA FF 03 00 + 3 x 8 bytes + 55 CC
constexpr size_t kMaxAckPayload = 64;  // radar's advertised command buffer size

// Command words (protocol V1.03, section 2.2).
enum Command : uint16_t {
  CMD_ENABLE_CONFIG = 0x00FF,
  CMD_END_CONFIG = 0x00FE,
  CMD_SINGLE_TARGET = 0x0080,
  CMD_MULTI_TARGET = 0x0090,
  CMD_QUERY_TRACKING_MODE = 0x0091,
  CMD_READ_FIRMWARE = 0x00A0,
  CMD_SET_BAUD = 0x00A1,
  CMD_FACTORY_RESET = 0x00A2,
  CMD_RESTART = 0x00A3,
  CMD_BLUETOOTH = 0x00A4,
  CMD_GET_MAC = 0x00A5,
  CMD_QUERY_ZONES = 0x00C1,
  CMD_SET_ZONES = 0x00C2,
};

struct Target {
  bool valid = false;
  int16_t x_mm = 0;          // left/right of the radar's centre line
  int16_t y_mm = 0;          // distance straight out from the radar
  int16_t speed_cms = 0;     // signed speed, cm/s
  uint16_t resolution_mm = 0;

  float distanceMm() const;  // straight-line distance from the radar
  float angleDeg() const;    // 0 = straight ahead, sign follows x
};

struct Frame {
  Target targets[kMaxTargets];
  int count() const;         // number of valid targets
};

struct Ack {
  uint16_t command = 0;      // the command being acknowledged (reply word without 0x0100)
  uint16_t status = 0xFFFF;  // 0 = success, 1 = failure
  uint8_t payload[kMaxAckPayload];  // bytes after the status word
  size_t payload_len = 0;
  bool ok() const { return status == 0; }
};

// Decodes the sign-bit encoding used in data frames: bit15 set = positive,
// clear = negative, low 15 bits = magnitude.
int16_t decodeSigned15(uint16_t raw);

// Parses a target slot (8 bytes). An all-zero x/y slot is "no target".
Target decodeTarget(const uint8_t* slot);

// Builds a command frame into out (needs 10 + 2 + value_len bytes).
// Returns the number of bytes written, or 0 if out is too small.
size_t buildCommand(uint16_t command, const uint8_t* value, size_t value_len,
                    uint8_t* out, size_t out_size);

// Formats a firmware-version ACK payload as e.g. "V1.02.22062416".
// Returns false if the payload is too short.
bool formatFirmwareVersion(const Ack& ack, char* out, size_t out_size);

class Parser {
 public:
  enum class Event { None, Frame, Ack };

  // Feed one received byte. Returns Frame or Ack when a complete, valid frame ends here.
  Event feed(uint8_t byte);

  const Frame& frame() const { return frame_; }
  const Ack& ack() const { return ack_; }

  // Diagnostics: frames that started but had a bad length or tail.
  uint32_t errorCount() const { return errors_; }

 private:
  enum class Kind { None, Data, Ack };

  void reset();
  void fail();
  Event finishData();
  Event finishAck();

  Kind kind_ = Kind::None;
  uint8_t buf_[6 + kMaxAckPayload + 4 + 4];
  size_t len_ = 0;
  size_t expected_ = 0;
  Frame frame_;
  Ack ack_;
  uint32_t errors_ = 0;
};

}  // namespace ld2450
