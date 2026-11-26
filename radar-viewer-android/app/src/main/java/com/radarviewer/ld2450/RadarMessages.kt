package com.radarviewer.ld2450

import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.hypot

/** One person tracked by the radar. x/y in mm (x is left/right, y straight out), speed in cm/s. */
data class RadarTarget(val id: Int, val xMm: Int, val yMm: Int, val speedCms: Int) {
    val distanceM: Double get() = hypot(xMm.toDouble(), yMm.toDouble()) / 1000.0
    /** 0 = straight ahead of the radar; the sign follows x. */
    val angleDeg: Double get() = Math.toDegrees(atan2(xMm.toDouble(), yMm.toDouble()))
    val speedMs: Double get() = speedCms / 100.0
}

data class RadarFrame(val seq: Long, val targets: List<RadarTarget>)

data class DeviceStatus(val deviceId: String, val online: Boolean, val firmware: String, val ip: String)

sealed interface RadarMessage {
    data class Frame(val frame: RadarFrame) : RadarMessage
    data class Status(val status: DeviceStatus) : RadarMessage
}

/**
 * Messages sent by the ESP32 (esp32-radar-display/src/RadarNetwork.cpp), identical over the
 * home-network WebSocket and MQTT:
 *   {"v":1,"seq":42,"targets":[{"id":1,"x":-782,"y":1713,"speed":-16}]}
 *   {"v":1,"type":"status","device":"ld2450-35f4","state":"online","fw":"V2.04...","ip":"..."}
 */
object RadarMessages {
    const val MAX_TARGETS = 3

    fun parse(text: String): RadarMessage? = try {
        val o = JSONObject(text)
        when {
            o.optString("type") == "status" -> RadarMessage.Status(
                DeviceStatus(
                    deviceId = o.optString("device"),
                    online = o.optString("state") == "online",
                    firmware = o.optString("fw"),
                    ip = o.optString("ip"),
                )
            )
            o.has("targets") -> {
                val arr = o.getJSONArray("targets")
                val targets = (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    RadarTarget(t.getInt("id"), t.getInt("x"), t.getInt("y"), t.optInt("speed"))
                }.filter { it.id in 1..MAX_TARGETS }
                RadarMessage.Frame(RadarFrame(o.optLong("seq"), targets))
            }
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** MQTT topics used by the ESP32 (see esp32-radar-display/README.md). */
object Topics {
    const val ALL_STATUS = "ld2450/+/status"
    fun status(deviceId: String) = "ld2450/$deviceId/status"
    fun targets(deviceId: String) = "ld2450/$deviceId/targets"
    fun watch(deviceId: String) = "ld2450/$deviceId/watch"

    /** "ld2450/ld2450-35f4/status" -> "ld2450-35f4" */
    fun deviceIdOf(topic: String): String? {
        val parts = topic.split('/')
        return if (parts.size == 3 && parts[0] == "ld2450" && parts[1].isNotEmpty()) parts[1] else null
    }
}
