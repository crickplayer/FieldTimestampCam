package com.fieldcam.timestamp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.preference.PreferenceManager
import kotlin.math.abs

/** Seven anchor points for every overlay element. */
enum class StampPosition { TOP_LEFT, TOP_CENTER, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT, CENTER;
    val isTop get() = this == TOP_LEFT || this == TOP_CENTER || this == TOP_RIGHT
    val isBottom get() = this == BOTTOM_LEFT || this == BOTTOM_CENTER || this == BOTTOM_RIGHT
    val isLeft get() = this == TOP_LEFT || this == BOTTOM_LEFT
    val isRight get() = this == TOP_RIGHT || this == BOTTOM_RIGHT
}

enum class BackgroundStyle { NONE, SHADOW, OUTLINE, BOX }

/**
 * Immutable snapshot of every user setting. The renderer reads one snapshot per
 * frame; changing a preference swaps the snapshot, so edits apply live — even
 * while a video is recording.
 */
data class CameraSettings(
    // Timestamp
    val showTime: Boolean = true,
    val timeFormatIndex: Int = 0,
    val font: String = "sans",
    val fontColor: Int = Color.WHITE,
    val textSizePercent: Int = 100,
    val textOpacityPercent: Int = 100,
    val position: StampPosition = StampPosition.BOTTOM_RIGHT,
    val backgroundStyle: BackgroundStyle = BackgroundStyle.SHADOW,
    val backgroundColor: Int = Color.BLACK,
    val backgroundOpacityPercent: Int = 45,
    // Location lines
    val showGps: Boolean = true,
    val coordFormat: String = "decimal",
    val showAddress: Boolean = true,
    val addressFormat: String = "neighborhood",
    val customLocation: String = "",
    val showHeading: Boolean = true,
    val showAltitude: Boolean = false,
    val showSpeed: Boolean = false,
    val imperialUnits: Boolean = false,
    val customText: String = "",
    val showIndex: Boolean = false,
    val photoIndex: Int = 1,
    // Compass
    val showCompass: Boolean = true,
    val compassPosition: StampPosition = StampPosition.TOP_LEFT,
    val compassSizePercent: Int = 100,
    // Map
    val showMap: Boolean = false,
    val mapZoom: Int = 16,
    val mapSizePercent: Int = 30,
    val mapOpacityPercent: Int = 90,
    val mapPosition: StampPosition = StampPosition.BOTTOM_LEFT,
    // Logo
    val showLogo: Boolean = false,
    val logoSizePercent: Int = 20,
    val logoOpacityPercent: Int = 100,
    val logoPosition: StampPosition = StampPosition.TOP_RIGHT,
    // Capture
    val recordAudio: Boolean = true,
    val shutterSound: Boolean = true,
    val videoQuality: String = "FHD",
    val aspectRatio: String = "4:3",
    val orientation: String = "auto",
    val batterySaverBrightness: Int = 10,
    val saveToSdCard: Boolean = false,
    val filePrefix: String = "",
) {
    companion object {
        fun prefs(context: Context): SharedPreferences =
            PreferenceManager.getDefaultSharedPreferences(context)

        private fun SharedPreferences.str(key: String, def: String) = getString(key, def) ?: def
        private fun SharedPreferences.color(key: String, def: String) =
            runCatching { Color.parseColor(str(key, def)) }.getOrDefault(Color.parseColor(def))
        private fun SharedPreferences.pos(key: String, def: StampPosition) =
            runCatching { StampPosition.valueOf(str(key, def.name)) }.getOrDefault(def)

        fun load(context: Context): CameraSettings {
            val p = prefs(context)
            return CameraSettings(
                showTime = p.getBoolean("show_time", true),
                timeFormatIndex = p.str("time_format", "0").toIntOrNull()
                    ?.coerceIn(0, TimeFormats.PATTERNS.lastIndex) ?: 0,
                font = p.str("font", "sans"),
                fontColor = p.color("font_color", "#FFFFFF"),
                textSizePercent = p.getInt("font_size", 100),
                textOpacityPercent = p.getInt("text_opacity", 100),
                position = p.pos("position", StampPosition.BOTTOM_RIGHT),
                backgroundStyle = runCatching { BackgroundStyle.valueOf(p.str("bg_style", "SHADOW")) }
                    .getOrDefault(BackgroundStyle.SHADOW),
                backgroundColor = p.color("bg_color", "#000000"),
                backgroundOpacityPercent = p.getInt("bg_opacity", 45),
                showGps = p.getBoolean("show_gps", true),
                coordFormat = p.str("coord_format", "decimal"),
                showAddress = p.getBoolean("show_address", true),
                addressFormat = p.str("address_format", "neighborhood"),
                customLocation = p.str("custom_location", ""),
                showHeading = p.getBoolean("show_heading", true),
                showAltitude = p.getBoolean("show_altitude", false),
                showSpeed = p.getBoolean("show_speed", false),
                imperialUnits = p.str("units", "metric") == "imperial",
                customText = p.str("custom_text", ""),
                showIndex = p.getBoolean("show_index", false),
                photoIndex = p.getInt("photo_index", 1),
                showCompass = p.getBoolean("show_compass", true),
                compassPosition = p.pos("compass_position", StampPosition.TOP_LEFT),
                compassSizePercent = p.getInt("compass_size", 100),
                showMap = p.getBoolean("show_map", false),
                mapZoom = p.getInt("map_zoom", 16),
                mapSizePercent = p.getInt("map_size", 30),
                mapOpacityPercent = p.getInt("map_opacity", 90),
                mapPosition = p.pos("map_position", StampPosition.BOTTOM_LEFT),
                showLogo = p.getBoolean("show_logo", false),
                logoSizePercent = p.getInt("logo_size", 20),
                logoOpacityPercent = p.getInt("logo_opacity", 100),
                logoPosition = p.pos("logo_position", StampPosition.TOP_RIGHT),
                recordAudio = p.getBoolean("record_audio", true),
                shutterSound = p.getBoolean("shutter_sound", true),
                videoQuality = p.str("video_quality", "FHD"),
                aspectRatio = p.str("aspect_ratio", "4:3"),
                orientation = p.str("orientation", "auto"),
                batterySaverBrightness = p.getInt("battery_saver_brightness", 10),
                saveToSdCard = p.getBoolean("save_to_sd", false),
                filePrefix = p.str("file_prefix", ""),
            )
        }
    }
}

/** Live sensor + GPS readings, written by [SensorLocationManager], read per frame. */
class TelemetryState {
    @Volatile var latitude: Double? = null
    @Volatile var longitude: Double? = null
    @Volatile var altitudeMeters: Double? = null
    @Volatile var speedMps: Float? = null
    @Volatile var accuracyMeters: Float? = null
    @Volatile var azimuthDeg: Float = 0f
    @Volatile var address: android.location.Address? = null

    fun formatCoordinates(format: String): String {
        val lat = latitude ?: return "Waiting for GPS…"
        val lon = longitude ?: return "Waiting for GPS…"
        return when (format) {
            "dms" -> "${dms(lat, "N", "S")} ${dms(lon, "E", "W")}"
            "signed" -> String.format(java.util.Locale.US, "%.6f, %.6f", lat, lon)
            else -> String.format(
                java.util.Locale.US, "%.8f%s %.8f%s",
                abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W"
            )
        }
    }

    private fun dms(value: Double, pos: String, neg: String): String {
        val a = abs(value)
        val d = a.toInt()
        val mFull = (a - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        return String.format(java.util.Locale.US, "%d°%02d'%05.2f\"%s", d, m, s, if (value >= 0) pos else neg)
    }

    fun formatBearing(): String {
        val deg = ((azimuthDeg.toInt() % 360) + 360) % 360
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return "$deg° ${dirs[((deg + 22.5) / 45.0).toInt() % 8]}"
    }

    fun formatAddress(format: String): String? {
        val a = address ?: return null
        return when (format) {
            "full" -> a.getAddressLine(0)
            "street" -> listOfNotNull(
                listOfNotNull(a.subThoroughfare, a.thoroughfare).joinToString(" ").ifBlank { null },
                a.locality
            ).joinToString(", ")
            "city" -> listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea).joinToString(", ")
            "country" -> listOfNotNull(a.locality, a.countryName).joinToString(", ")
            else -> a.subLocality ?: a.locality ?: a.subAdminArea ?: a.adminArea
        }?.ifBlank { null }
    }
}
