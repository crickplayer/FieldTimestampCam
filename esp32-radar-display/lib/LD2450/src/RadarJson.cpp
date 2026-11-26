#include "RadarJson.h"

#include <stdio.h>

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

}  // namespace ld2450
