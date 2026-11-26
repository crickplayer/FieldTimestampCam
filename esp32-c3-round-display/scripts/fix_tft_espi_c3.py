# Pre-build fix for TFT_eSPI 2.5.43 on the ESP32-C3 (Arduino core 2.0.x / ESP-IDF 4.4).
#
# TFT_eSPI writes to the SPI registers at REG_SPI_BASE(SPI_PORT) with SPI_PORT = SPI2_HOST (= 1).
# This IDF defines REG_SPI_BASE(i) as "SPI2 if i == 2, else 0", so every display write goes to
# address 0: the C3 crashes as soon as it draws (backlight on, picture black). Using 2 selects
# the C3's only general SPI controller, SPI2 at 0x60024000. SPI_PORT is only used for those
# register addresses on the C3. The change is idempotent and re-applied after library updates.
import os

Import("env")  # noqa: F821  (provided by PlatformIO)

header = os.path.join(env.subst("$PROJECT_LIBDEPS_DIR"), env.subst("$PIOENV"),  # noqa: F821
                      "TFT_eSPI", "Processors", "TFT_eSPI_ESP32_C3.h")
OLD = "#define SPI_PORT SPI2_HOST"
NEW = "#define SPI_PORT 2  // patched by scripts/fix_tft_espi_c3.py (was SPI2_HOST)"

if os.path.exists(header):
    with open(header, encoding="utf-8") as f:
        text = f.read()
    if OLD in text:
        with open(header, "w", encoding="utf-8") as f:
            f.write(text.replace(OLD, NEW))
        print("fix_tft_espi_c3: patched SPI_PORT in", header)
    elif NEW not in text:
        raise SystemExit("fix_tft_espi_c3: TFT_eSPI_ESP32_C3.h has changed; check SPI_PORT by hand")
else:
    raise SystemExit("fix_tft_espi_c3: TFT_eSPI not found at " + header)
