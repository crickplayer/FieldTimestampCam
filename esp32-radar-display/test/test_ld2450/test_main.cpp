// Radar-protocol tests. Run on a PC with:  pio test -e native
// Expected values come from the vendor documents (see docs/hlk-ld2450/ANALYSIS.md).
#include <string.h>
#include <unity.h>

#include "LD2450.h"
#include "RadarJson.h"

using namespace ld2450;

namespace {
// Protocol V1.03 section 2.3 example: one target.
const uint8_t kProtocolExample[30] = {0xAA, 0xFF, 0x03, 0x00, 0x0E, 0x03, 0xB1, 0x86, 0x10, 0x00,
                                      0x40, 0x01, 0,    0,    0,    0,    0,    0,    0,    0,
                                      0,    0,    0,    0,    0,    0,    0,    0,    0x55, 0xCC};

Parser::Event feedAll(Parser& p, const uint8_t* data, size_t n) {
  Parser::Event last = Parser::Event::None;
  for (size_t i = 0; i < n; i++) {
    Parser::Event e = p.feed(data[i]);
    if (e != Parser::Event::None) last = e;
  }
  return last;
}
}  // namespace

void test_signed15_encoding() {
  TEST_ASSERT_EQUAL_INT16(-782, decodeSigned15(0x030E));
  TEST_ASSERT_EQUAL_INT16(1713, decodeSigned15(0x86B1));
  TEST_ASSERT_EQUAL_INT16(-16, decodeSigned15(0x0010));
  TEST_ASSERT_EQUAL_INT16(17, decodeSigned15(0x8011));
  TEST_ASSERT_EQUAL_INT16(0, decodeSigned15(0x8000));
}

void test_protocol_example_frame() {
  Parser p;
  TEST_ASSERT_EQUAL(Parser::Event::Frame, feedAll(p, kProtocolExample, 30));
  const Frame& f = p.frame();
  TEST_ASSERT_EQUAL_INT(1, f.count());
  TEST_ASSERT_TRUE(f.targets[0].valid);
  TEST_ASSERT_EQUAL_INT16(-782, f.targets[0].x_mm);
  TEST_ASSERT_EQUAL_INT16(1713, f.targets[0].y_mm);
  TEST_ASSERT_EQUAL_INT16(-16, f.targets[0].speed_cms);
  TEST_ASSERT_EQUAL_UINT16(320, f.targets[0].resolution_mm);
  TEST_ASSERT_FALSE(f.targets[1].valid);
  TEST_ASSERT_FALSE(f.targets[2].valid);
  TEST_ASSERT_FLOAT_WITHIN(1.0f, 1883.1f, f.targets[0].distanceMm());
  TEST_ASSERT_FLOAT_WITHIN(0.1f, -24.54f, f.targets[0].angleDeg());
}

void test_operation_manual_captures() {
  // Operation manual section 6: (-272, 850) mm, 0 cm/s, and (2750, 3655) mm, -17 cm/s.
  const uint8_t a[8] = {0x10, 0x01, 0x52, 0x83, 0x00, 0x00, 0x68, 0x01};
  Target t = decodeTarget(a);
  TEST_ASSERT_TRUE(t.valid);
  TEST_ASSERT_EQUAL_INT16(-272, t.x_mm);
  TEST_ASSERT_EQUAL_INT16(850, t.y_mm);
  TEST_ASSERT_EQUAL_INT16(0, t.speed_cms);
  TEST_ASSERT_EQUAL_UINT16(360, t.resolution_mm);

  const uint8_t b[8] = {0xBE, 0x8A, 0x47, 0x8E, 0x11, 0x00, 0x68, 0x01};
  t = decodeTarget(b);
  TEST_ASSERT_EQUAL_INT16(2750, t.x_mm);
  TEST_ASSERT_EQUAL_INT16(3655, t.y_mm);
  TEST_ASSERT_EQUAL_INT16(-17, t.speed_cms);
}

void test_resyncs_after_garbage_and_partial_frame() {
  Parser p;
  const uint8_t junk[] = {0x00, 0xAA, 0x12, 0xFD, 0xFC, 0x99, 0x55, 0xCC, 0xAA, 0xFF};
  TEST_ASSERT_EQUAL(Parser::Event::None, feedAll(p, junk, sizeof(junk)));
  // A real frame directly after the junk must still be found.
  TEST_ASSERT_EQUAL(Parser::Event::Frame, feedAll(p, kProtocolExample, 30));
  TEST_ASSERT_EQUAL_INT16(-782, p.frame().targets[0].x_mm);
}

void test_rejects_bad_tail() {
  Parser p;
  uint8_t bad[30];
  memcpy(bad, kProtocolExample, 30);
  bad[29] = 0x00;
  TEST_ASSERT_EQUAL(Parser::Event::None, feedAll(p, bad, 30));
  TEST_ASSERT_EQUAL_UINT32(1, p.errorCount());
  TEST_ASSERT_EQUAL(Parser::Event::Frame, feedAll(p, kProtocolExample, 30));
}

void test_back_to_back_frames() {
  Parser p;
  int frames = 0;
  for (int n = 0; n < 5; n++) {
    for (int i = 0; i < 30; i++) {
      if (p.feed(kProtocolExample[i]) == Parser::Event::Frame) frames++;
    }
  }
  TEST_ASSERT_EQUAL_INT(5, frames);
}

void test_build_enable_config_command() {
  // Protocol 2.2.1: FD FC FB FA 04 00 FF 00 01 00 04 03 02 01
  const uint8_t expected[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x04, 0x00, 0xFF,
                              0x00, 0x01, 0x00, 0x04, 0x03, 0x02, 0x01};
  const uint8_t value[] = {0x01, 0x00};
  uint8_t out[32];
  TEST_ASSERT_EQUAL_size_t(sizeof(expected),
                           buildCommand(CMD_ENABLE_CONFIG, value, 2, out, sizeof(out)));
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, out, sizeof(expected));
}

void test_build_end_config_command() {
  // Protocol 2.2.2: FD FC FB FA 02 00 FE 00 04 03 02 01
  const uint8_t expected[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x02, 0x00,
                              0xFE, 0x00, 0x04, 0x03, 0x02, 0x01};
  uint8_t out[32];
  TEST_ASSERT_EQUAL_size_t(sizeof(expected),
                           buildCommand(CMD_END_CONFIG, nullptr, 0, out, sizeof(out)));
  TEST_ASSERT_EQUAL_UINT8_ARRAY(expected, out, sizeof(expected));
  TEST_ASSERT_EQUAL_size_t(0, buildCommand(CMD_END_CONFIG, nullptr, 0, out, 4));
}

void test_parse_enable_config_ack() {
  // Protocol 2.2.1 ACK: FD FC FB FA 08 00 FF 01 00 00 01 00 40 00 04 03 02 01
  const uint8_t ack[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x08, 0x00, 0xFF, 0x01, 0x00,
                         0x00, 0x01, 0x00, 0x40, 0x00, 0x04, 0x03, 0x02, 0x01};
  Parser p;
  TEST_ASSERT_EQUAL(Parser::Event::Ack, feedAll(p, ack, sizeof(ack)));
  TEST_ASSERT_EQUAL_HEX16(CMD_ENABLE_CONFIG, p.ack().command);
  TEST_ASSERT_TRUE(p.ack().ok());
  TEST_ASSERT_EQUAL_size_t(4, p.ack().payload_len);  // protocol version + buffer size
}

void test_parse_firmware_ack_and_version() {
  // Protocol 2.2.6 ACK -> V1.02.22062416
  const uint8_t ack[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x0C, 0x00, 0xA0, 0x01, 0x00, 0x00, 0x00,
                         0x00, 0x02, 0x01, 0x16, 0x24, 0x06, 0x22, 0x04, 0x03, 0x02, 0x01};
  Parser p;
  TEST_ASSERT_EQUAL(Parser::Event::Ack, feedAll(p, ack, sizeof(ack)));
  TEST_ASSERT_EQUAL_HEX16(CMD_READ_FIRMWARE, p.ack().command);
  char version[24];
  TEST_ASSERT_TRUE(formatFirmwareVersion(p.ack(), version, sizeof(version)));
  TEST_ASSERT_EQUAL_STRING("V1.02.22062416", version);
}

void test_ack_between_data_frames() {
  const uint8_t ack[] = {0xFD, 0xFC, 0xFB, 0xFA, 0x04, 0x00, 0xFE,
                         0x01, 0x00, 0x00, 0x04, 0x03, 0x02, 0x01};
  Parser p;
  TEST_ASSERT_EQUAL(Parser::Event::Frame, feedAll(p, kProtocolExample, 30));
  TEST_ASSERT_EQUAL(Parser::Event::Ack, feedAll(p, ack, sizeof(ack)));
  TEST_ASSERT_EQUAL_HEX16(CMD_END_CONFIG, p.ack().command);
  TEST_ASSERT_EQUAL(Parser::Event::Frame, feedAll(p, kProtocolExample, 30));
}

void test_rejects_oversized_ack_length() {
  const uint8_t bogus[] = {0xFD, 0xFC, 0xFB, 0xFA, 0xFF, 0x7F};
  Parser p;
  TEST_ASSERT_EQUAL(Parser::Event::None, feedAll(p, bogus, sizeof(bogus)));
  TEST_ASSERT_EQUAL_UINT32(1, p.errorCount());
}

void test_json_one_target() {
  Parser p;
  feedAll(p, kProtocolExample, 30);
  char out[256];
  size_t n = encodeFrameJson(p.frame(), false, 7, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("{\"v\":1,\"seq\":7,\"targets\":[{\"id\":1,\"x\":-782,\"y\":1713,\"speed\":-16}]}", out);
  TEST_ASSERT_EQUAL_size_t(strlen(out), n);
}

void test_json_mirror_and_empty() {
  Parser p;
  feedAll(p, kProtocolExample, 30);
  char out[256];
  encodeFrameJson(p.frame(), true, 1, out, sizeof(out));
  TEST_ASSERT_NOT_NULL(strstr(out, "\"x\":782,"));
  Frame empty;
  encodeFrameJson(empty, false, 2, out, sizeof(out));
  TEST_ASSERT_EQUAL_STRING("{\"v\":1,\"seq\":2,\"targets\":[]}", out);
}

void test_json_three_targets_and_small_buffer() {
  Frame f;
  for (int i = 0; i < kMaxTargets; i++) {
    f.targets[i].valid = true;
    f.targets[i].x_mm = -1234;
    f.targets[i].y_mm = 5678;
    f.targets[i].speed_cms = -100;
  }
  char out[256];
  size_t n = encodeFrameJson(f, false, 4294967295u, out, sizeof(out));
  TEST_ASSERT_TRUE(n > 0 && n < 200);  // fits the MQTT/WebSocket buffers with room to spare
  TEST_ASSERT_NOT_NULL(strstr(out, "\"id\":3"));
  char tiny[20];
  TEST_ASSERT_EQUAL_size_t(0, encodeFrameJson(f, false, 1, tiny, sizeof(tiny)));
  TEST_ASSERT_EQUAL_STRING("", tiny);
}

void test_json_decode_round_trip() {
  Frame f;
  f.targets[0] = {true, -782, 1713, -16, 0};
  f.targets[2] = {true, 1500, 4200, 35, 0};
  char out[256];
  size_t n = encodeFrameJson(f, false, 4294967295u, out, sizeof(out));
  Frame back;
  back.targets[1].valid = true;  // must be cleared by the decoder
  uint32_t seq = 0;
  TEST_ASSERT_TRUE(decodeFrameJson(out, n, &back, &seq));
  TEST_ASSERT_EQUAL_UINT32(4294967295u, seq);
  TEST_ASSERT_TRUE(back.targets[0].valid);
  TEST_ASSERT_EQUAL_INT16(-782, back.targets[0].x_mm);
  TEST_ASSERT_EQUAL_INT16(1713, back.targets[0].y_mm);
  TEST_ASSERT_EQUAL_INT16(-16, back.targets[0].speed_cms);
  TEST_ASSERT_FALSE(back.targets[1].valid);
  TEST_ASSERT_TRUE(back.targets[2].valid);
  TEST_ASSERT_EQUAL_INT16(4200, back.targets[2].y_mm);
  TEST_ASSERT_EQUAL_INT(2, back.count());
}

void test_json_decode_empty_status_and_bad_input() {
  Frame f;
  const char empty[] = "{\"v\":1,\"seq\":3,\"targets\":[]}";
  TEST_ASSERT_TRUE(decodeFrameJson(empty, strlen(empty), &f, nullptr));
  TEST_ASSERT_EQUAL_INT(0, f.count());

  const char status[] = "{\"v\":1,\"type\":\"status\",\"device\":\"ld2450-ab12\"}";
  TEST_ASSERT_FALSE(decodeFrameJson(status, strlen(status), &f, nullptr));

  const char cut[] = "{\"v\":1,\"seq\":3,\"targets\":[{\"id\":1,\"x\":5,\"y\":9";
  TEST_ASSERT_FALSE(decodeFrameJson(cut, strlen(cut), &f, nullptr));
  const char no_close[] = "{\"targets\":[{\"id\":1,\"x\":5,\"y\":9}";
  TEST_ASSERT_FALSE(decodeFrameJson(no_close, strlen(no_close), &f, nullptr));

  // Spaces and an out-of-range id are tolerated (the id is ignored).
  const char spaced[] = "{ \"targets\" : [ { \"id\" : 2 , \"x\" : -10 , \"y\" : 20 }, "
                        "{\"id\":9,\"x\":1,\"y\":1} ] }";
  TEST_ASSERT_TRUE(decodeFrameJson(spaced, strlen(spaced), &f, nullptr));
  TEST_ASSERT_EQUAL_INT(1, f.count());
  TEST_ASSERT_EQUAL_INT16(-10, f.targets[1].x_mm);
  TEST_ASSERT_EQUAL_INT16(0, f.targets[1].speed_cms);
}

int main(int, char**) {
  UNITY_BEGIN();
  RUN_TEST(test_signed15_encoding);
  RUN_TEST(test_protocol_example_frame);
  RUN_TEST(test_operation_manual_captures);
  RUN_TEST(test_resyncs_after_garbage_and_partial_frame);
  RUN_TEST(test_rejects_bad_tail);
  RUN_TEST(test_back_to_back_frames);
  RUN_TEST(test_build_enable_config_command);
  RUN_TEST(test_build_end_config_command);
  RUN_TEST(test_parse_enable_config_ack);
  RUN_TEST(test_parse_firmware_ack_and_version);
  RUN_TEST(test_ack_between_data_frames);
  RUN_TEST(test_rejects_oversized_ack_length);
  RUN_TEST(test_json_one_target);
  RUN_TEST(test_json_mirror_and_empty);
  RUN_TEST(test_json_three_targets_and_small_buffer);
  RUN_TEST(test_json_decode_round_trip);
  RUN_TEST(test_json_decode_empty_status_and_bad_input);
  return UNITY_END();
}
