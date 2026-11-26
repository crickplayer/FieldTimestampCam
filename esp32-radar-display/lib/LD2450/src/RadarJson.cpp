#include "RadarJson.h"

#include <stdio.h>
#include <string.h>

namespace ld2450 {

size_t encodeFrameJson(const Frame& frame, bool mirror_x, uint32_t seq, char* out, size_t out_size) {
  if (out == nullptr || out_size == 0) return 0;
  size_t len = 0;
  auto append = [&](int written) {
    if (written < 0 || (size_t)written >= out_size - len) return false;
    len += (size_t)written;
    return true;
  };

  if (!append(snprintf(out, out_size, "{\"v\":%d,\"seq\":%lu,\"targets\":[", kJsonVersion,
                       (unsigned long)seq))) {
    out[0] = '\0';
    return 0;
  }
  bool first = true;
  for (int i = 0; i < kMaxTargets; i++) {
    const Target& t = frame.targets[i];
    if (!t.valid) continue;
    int x = mirror_x ? -t.x_mm : t.x_mm;
    if (!append(snprintf(out + len, out_size - len, "%s{\"id\":%d,\"x\":%d,\"y\":%d,\"speed\":%d}",
                         first ? "" : ",", i + 1, x, (int)t.y_mm, (int)t.speed_cms))) {
      out[0] = '\0';
      return 0;
    }
    first = false;
  }
  if (!append(snprintf(out + len, out_size - len, "]}"))) {
    out[0] = '\0';
    return 0;
  }
  return len;
}

namespace {

// Finds "key" (with quotes) in [p, end). Returns a pointer just past the key's colon, or null.
const char* findKey(const char* p, const char* end, const char* key) {
  const size_t key_len = strlen(key);
  for (; p + key_len + 2 <= end; p++) {
    if (*p != '"' || memcmp(p + 1, key, key_len) != 0 || p[key_len + 1] != '"') continue;
    const char* q = p + key_len + 2;
    while (q < end && (*q == ' ' || *q == '\t' || *q == '\r' || *q == '\n')) q++;
    if (q < end && *q == ':') return q + 1;
  }
  return nullptr;
}

// Reads an optionally signed integer at p (after optional spaces). False if there is none.
bool readInt(const char* p, const char* end, int64_t* value) {
  if (p == nullptr) return false;
  while (p < end && (*p == ' ' || *p == '\t' || *p == '\r' || *p == '\n')) p++;
  bool negative = false;
  if (p < end && (*p == '-' || *p == '+')) negative = *p++ == '-';
  if (p >= end || *p < '0' || *p > '9') return false;
  int64_t v = 0;
  while (p < end && *p >= '0' && *p <= '9') {
    v = v * 10 + (*p++ - '0');
    if (v > 9999999999LL) return false;
  }
  *value = negative ? -v : v;
  return true;
}

int16_t clamp16(int64_t v) {
  return (int16_t)(v < -32768 ? -32768 : (v > 32767 ? 32767 : v));
}

}  // namespace

bool decodeFrameJson(const char* json, size_t len, Frame* frame, uint32_t* seq) {
  if (json == nullptr || frame == nullptr) return false;
  const char* end = json + len;
  const char* p = findKey(json, end, "targets");
  if (p == nullptr) return false;
  while (p < end && *p != '[') p++;
  if (p >= end) return false;

  Frame out;
  for (p++; p < end && *p != ']';) {
    if (*p != '{') {
      p++;
      continue;
    }
    const char* obj_end = (const char*)memchr(p, '}', end - p);
    if (obj_end == nullptr) return false;
    int64_t id, x, y, speed;
    if (!readInt(findKey(p, obj_end, "id"), obj_end, &id) ||
        !readInt(findKey(p, obj_end, "x"), obj_end, &x) ||
        !readInt(findKey(p, obj_end, "y"), obj_end, &y)) {
      return false;
    }
    if (!readInt(findKey(p, obj_end, "speed"), obj_end, &speed)) speed = 0;
    if (id >= 1 && id <= kMaxTargets) {
      Target& t = out.targets[id - 1];
      t.valid = true;
      t.x_mm = clamp16(x);
      t.y_mm = clamp16(y);
      t.speed_cms = clamp16(speed);
    }
    p = obj_end + 1;
  }
  if (p >= end) return false;  // no closing ']'

  if (seq != nullptr) {
    int64_t s;
    *seq = readInt(findKey(json, end, "seq"), end, &s) ? (uint32_t)s : 0;
  }
  *frame = out;
  return true;
}

}  // namespace ld2450
