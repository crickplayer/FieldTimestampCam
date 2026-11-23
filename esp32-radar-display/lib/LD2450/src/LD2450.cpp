#include "LD2450.h"

#include <math.h>
#include <stdio.h>
#include <string.h>

namespace ld2450 {

namespace {
const uint8_t kDataHeader[4] = {0xAA, 0xFF, 0x03, 0x00};
const uint8_t kDataTail[2] = {0x55, 0xCC};
const uint8_t kCmdHeader[4] = {0xFD, 0xFC, 0xFB, 0xFA};
const uint8_t kCmdTail[4] = {0x04, 0x03, 0x02, 0x01};

uint16_t le16(const uint8_t* p) { return (uint16_t)(p[0] | (p[1] << 8)); }
}  // namespace

float Target::distanceMm() const {
  return sqrtf((float)x_mm * x_mm + (float)y_mm * y_mm);
}

float Target::angleDeg() const {
  return atan2f((float)x_mm, (float)y_mm) * 57.2957795f;
}

int Frame::count() const {
  int n = 0;
  for (const Target& t : targets) n += t.valid ? 1 : 0;
  return n;
}

int16_t decodeSigned15(uint16_t raw) {
  int16_t magnitude = (int16_t)(raw & 0x7FFF);
  return (raw & 0x8000) ? magnitude : (int16_t)-magnitude;
}

Target decodeTarget(const uint8_t* slot) {
  Target t;
  uint16_t x = le16(slot), y = le16(slot + 2);
  t.valid = (x != 0 || y != 0);
  if (!t.valid) return t;
  t.x_mm = decodeSigned15(x);
  t.y_mm = decodeSigned15(y);
  t.speed_cms = decodeSigned15(le16(slot + 4));
  t.resolution_mm = le16(slot + 6);
  return t;
}

size_t buildCommand(uint16_t command, const uint8_t* value, size_t value_len,
                    uint8_t* out, size_t out_size) {
  size_t inner = 2 + value_len;
  size_t total = 4 + 2 + inner + 4;
  if (out == nullptr || out_size < total || inner > 0xFFFF) return 0;
  memcpy(out, kCmdHeader, 4);
  out[4] = inner & 0xFF;
  out[5] = inner >> 8;
  out[6] = command & 0xFF;
  out[7] = command >> 8;
  if (value_len) memcpy(out + 8, value, value_len);
  memcpy(out + 8 + value_len, kCmdTail, 4);
  return total;
}

bool formatFirmwareVersion(const Ack& ack, char* out, size_t out_size) {
  // Payload after status: type (2) + major (2) + minor (4).
  if (ack.payload_len < 8 || out == nullptr || out_size == 0) return false;
  uint16_t major = le16(ack.payload + 2);
  uint32_t minor = (uint32_t)ack.payload[4] | ((uint32_t)ack.payload[5] << 8) |
                   ((uint32_t)ack.payload[6] << 16) | ((uint32_t)ack.payload[7] << 24);
  snprintf(out, out_size, "V%x.%02x.%08lx", (major >> 8) & 0xFF, major & 0xFF,
           (unsigned long)minor);
  return true;
}

void Parser::reset() {
  kind_ = Kind::None;
  len_ = 0;
  expected_ = 0;
}

void Parser::fail() {
  errors_++;
  reset();
}

Parser::Event Parser::feed(uint8_t b) {
  if (len_ == 0) {
    if (b == kDataHeader[0]) {
      kind_ = Kind::Data;
    } else if (b == kCmdHeader[0]) {
      kind_ = Kind::Ack;
    } else {
      return Event::None;  // not a frame start; skip
    }
    buf_[len_++] = b;
    return Event::None;
  }

  if (len_ < 4) {
    const uint8_t* header = (kind_ == Kind::Data) ? kDataHeader : kCmdHeader;
    if (b != header[len_]) {
      // False start: drop it and see whether this byte begins a real frame.
      reset();
      return (b == kDataHeader[0] || b == kCmdHeader[0]) ? feed(b) : Event::None;
    }
    buf_[len_++] = b;
    if (len_ == 4 && kind_ == Kind::Data) expected_ = kDataFrameLen;
    return Event::None;
  }

  buf_[len_++] = b;

  if (kind_ == Kind::Ack && len_ == 6) {
    size_t inner = le16(buf_ + 4);  // command word + status + payload
    if (inner < 2 || inner > kMaxAckPayload + 4) {
      fail();
      return Event::None;
    }
    expected_ = 6 + inner + 4;
  }

  if (expected_ != 0 && len_ == expected_) {
    return (kind_ == Kind::Data) ? finishData() : finishAck();
  }
  return Event::None;
}

Parser::Event Parser::finishData() {
  if (buf_[28] != kDataTail[0] || buf_[29] != kDataTail[1]) {
    fail();
    return Event::None;
  }
  for (int i = 0; i < kMaxTargets; i++) {
    frame_.targets[i] = decodeTarget(buf_ + 4 + 8 * i);
  }
  reset();
  return Event::Frame;
}

Parser::Event Parser::finishAck() {
  if (memcmp(buf_ + len_ - 4, kCmdTail, 4) != 0) {
    fail();
    return Event::None;
  }
  size_t inner = len_ - 10;
  // The radar replies with the command word | 0x0100.
  ack_.command = le16(buf_ + 6) & 0xFEFF;
  ack_.status = (inner >= 4) ? le16(buf_ + 8) : 0xFFFF;
  ack_.payload_len = (inner > 4) ? inner - 4 : 0;
  if (ack_.payload_len) memcpy(ack_.payload, buf_ + 10, ack_.payload_len);
  reset();
  return Event::Ack;
}

}  // namespace ld2450
