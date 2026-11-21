package com.fieldcam.timestamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Renders the watermark off-device so its layout can be checked as a PNG. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class OverlayRenderTest {

    private fun telemetry() = TelemetryState().apply {
        latitude = 40.75356533; longitude = -74.00507036
        altitudeMeters = 12.4; speedMps = 1.3f; azimuthDeg = 290f
    }

    private fun render(name: String, w: Int, h: Int, s: CameraSettings): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(140, 140, 138))
        val logo = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(255, 140, 0)) }
        TelemetryOverlayRenderer(telemetry(), MapTileCache(), { s }, { logo }).draw(c, w, h)
        val out = File(System.getProperty("overlay.out", "build/overlay-renders"), "$name.png")
        out.parentFile!!.mkdirs()
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return bmp
    }

    @Test fun defaultLandscapeLooksLikeReference() {
        val bmp = render("default_landscape", 2000, 1500, CameraSettings(customLocation = "Manhattan"))
        // Bottom-right corner area must contain bright (white) text pixels.
        var white = 0
        for (x in 1300 until 1990 step 2) for (y in 1250 until 1480 step 2) {
            val p = bmp.getPixel(x, y); if (Color.red(p) > 240 && Color.green(p) > 240) white++
        }
        assertTrue("timestamp text missing", white > 200)
    }

    @Test fun everythingEnabledPortrait() {
        render("all_features_portrait", 1500, 2000, CameraSettings(
            timeFormatIndex = 1, showAltitude = true, showSpeed = true, showIndex = true,
            customText = "Good day at the zoo 🦁", customLocation = "Central Park Zoo",
            showMap = true, showLogo = true, backgroundStyle = BackgroundStyle.BOX,
            fontColor = Color.YELLOW, font = "sans_bold", position = StampPosition.BOTTOM_CENTER))
    }

    @Test fun allSevenPositionsAndFormats() {
        for (p in StampPosition.values()) {
            render("pos_${p.name.lowercase()}", 1600, 1200, CameraSettings(position = p,
                backgroundStyle = BackgroundStyle.OUTLINE, showCompass = false, customLocation = "Manhattan"))
        }
        val now = Date()
        TimeFormats.PATTERNS.forEach { SimpleDateFormat(it, Locale.US).format(now) }
        assertTrue(TimeFormats.PATTERNS.size == 61)
    }
}
