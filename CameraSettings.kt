package com.fieldcam.timestamp

import android.content.Context
import android.graphics.Color
import kotlin.math.abs

enum class StampPosition { BOTTOM_RIGHT, BOTTOM_LEFT, TOP_RIGHT, TOP_LEFT }
enum class VideoQualityPreset { LOW, NORMAL, HIGH }

data class CameraSettings(
    var showCompass: Boolean = true,
    var showMap: Boolean = false,
    var showAltitudeSpeed: Boolean = false,
    var showIndexNumber: Boolean = false,
    var showGrid: Boolean = true,
    var alwaysShowZoom: Boolean = true,
    var mirrorFrontCamera: Boolean = true,
    var keepOriginalPhoto: Boolean = false,
    var touchToTakePhoto: Boolean = false,
    var shutterSound: Boolean = true,
    var useBluetoothMic: Boolean = false,
    var videoQuality: VideoQualityPreset = VideoQualityPreset.HIGH,
    var fontColor: Int = Color.WHITE,
    var textOpacity: Int = 255,
    var backgroundColor: Int = Color.BLACK,
    var backgroundOpacity: Int = 0,
    var textSizeScale: Float = 1.0f,
    var position: StampPosition = StampPosition.BOTTOM_RIGHT,
    var customText: String = "",
    var filePrefix: String = "IMG_",
    var timerSeconds: Int = 0,
    var intervalSeconds: Int = 0,
    var photoIndex: Int = 1
) {
    fun save(context: Context) {
        context.getSharedPreferences("ts_cam_prefs", Context.MODE_PRIVATE).edit().apply {
            putBoolean("showCompass", showCompass)
            putBoolean("showAltitudeSpeed", showAltitudeSpeed)
            putBoolean("showIndexNumber", showIndexNumber)
            putBoolean("showGrid", showGrid)
            putBoolean("alwaysShowZoom", alwaysShowZoom)
            putBoolean("mirrorFrontCamera", mirrorFrontCamera)
            putBoolean("keepOriginalPhoto", keepOriginalPhoto)
            putBoolean("touchToTakePhoto", touchToTakePhoto)
            putBoolean("shutterSound", shutterSound)
            putBoolean("useBluetoothMic", useBluetoothMic)
            putString("videoQuality", videoQuality.name)
            putInt("fontColor", fontColor)
            putInt("textOpacity", textOpacity)
            putInt("backgroundColor", backgroundColor)
            putInt("backgroundOpacity", backgroundOpacity)
            putFloat("textSizeScale", textSizeScale)
            putString("position", position.name)
            putString("customText", customText)
            putString("filePrefix", filePrefix)
            putInt("timerSeconds", timerSeconds)
            putInt("intervalSeconds", intervalSeconds)
            putInt("photoIndex", photoIndex)
            apply()
        }
    }

    companion object {
        fun load(context: Context): CameraSettings {
            val p = context.getSharedPreferences("ts_cam_prefs", Context.MODE_PRIVATE)
            return CameraSettings(
                showCompass = p.getBoolean("showCompass", true),
                showAltitudeSpeed = p.getBoolean("showAltitudeSpeed", false),
                showIndexNumber = p.getBoolean("showIndexNumber", false),
                showGrid = p.getBoolean("showGrid", true),
                alwaysShowZoom = p.getBoolean("alwaysShowZoom", true),
                mirrorFrontCamera = p.getBoolean("mirrorFrontCamera", true),
                keepOriginalPhoto = p.getBoolean("keepOriginalPhoto", false),
                touchToTakePhoto = p.getBoolean("touchToTakePhoto", false),
                shutterSound = p.getBoolean("shutterSound", true),
                useBluetoothMic = p.getBoolean("useBluetoothMic", false),
                videoQuality = VideoQualityPreset.valueOf(p.getString("videoQuality", "HIGH")!!),
                fontColor = p.getInt("fontColor", Color.WHITE),
                textOpacity = p.getInt("textOpacity", 255),
                backgroundColor = p.getInt("backgroundColor", Color.BLACK),
                backgroundOpacity = p.getInt("backgroundOpacity", 0),
                textSizeScale = p.getFloat("textSizeScale", 1.0f),
                position = StampPosition.valueOf(p.getString("position", "BOTTOM_RIGHT")!!),
                customText = p.getString("customText", "") ?: "",
                filePrefix = p.getString("filePrefix", "IMG_") ?: "IMG_",
                timerSeconds = p.getInt("timerSeconds", 0),
                intervalSeconds = p.getInt("intervalSeconds", 0),
                photoIndex = p.getInt("photoIndex", 1)
            )
        }
    }
}

data class TelemetryState(
    @Volatile var latitude: Double? = null,
    @Volatile var longitude: Double? = null,
    @Volatile var altitudeMeters: Double = 0.0,
    @Volatile var speedMps: Float = 0f,
    @Volatile var locationName: String = "Acquiring GPS...",
    @Volatile var azimuthDeg: Int = 0
) {
    fun formatCoordinates(): String {
        val lat = latitude ?: return "Waiting for GPS fix..."
        val lon = longitude ?: return ""
        val latDir = if (lat >= 0) "N" else "S"
        val lonDir = if (lon >= 0) "E" else "W"
        return String.format("%.8f%s %.8f%s", abs(lat), latDir, abs(lon), lonDir)
    }

    fun formatBearing(): String {
        val dirs = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val idx = (((azimuthDeg + 22.5) % 360) / 45.0).toInt()
        return "$azimuthDeg° ${dirs[idx]}"
    }
}
