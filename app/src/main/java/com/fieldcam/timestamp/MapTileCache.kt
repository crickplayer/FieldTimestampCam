package com.fieldcam.timestamp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.tan

/**
 * Small OpenStreetMap tile cache for the on-camera mini map. Tiles are fetched
 * on a background pool; until a tile arrives the renderer draws a placeholder.
 */
class MapTileCache {
    private val cache = LruCache<String, Bitmap>(48)
    private val pending = Collections.synchronizedSet(HashSet<String>())
    private val failed = Collections.synchronizedMap(HashMap<String, Long>())
    private val pool = Executors.newFixedThreadPool(2)

    fun tile(z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        val wx = ((x % n) + n) % n
        if (y < 0 || y >= n) return null
        val key = "$z/$wx/$y"
        cache.get(key)?.let { return it }
        val failedAt = failed[key]
        if (failedAt != null && System.currentTimeMillis() - failedAt < 15_000) return null
        if (pending.add(key)) {
            pool.execute {
                try {
                    val conn = URL("https://tile.openstreetmap.org/$key.png").openConnection() as HttpURLConnection
                    conn.setRequestProperty("User-Agent", "FieldTimestampCam/2.0 (Android camera overlay)")
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }?.let { cache.put(key, it) }
                        ?: failed.put(key, System.currentTimeMillis())
                    conn.disconnect()
                } catch (_: Exception) {
                    failed[key] = System.currentTimeMillis()
                } finally {
                    pending.remove(key)
                }
            }
        }
        return null
    }

    fun shutdown() = pool.shutdownNow()

    companion object {
        /** Fractional tile coordinates (Web Mercator) of a lat/lon at zoom z. */
        fun tileXY(lat: Double, lon: Double, z: Int): Pair<Double, Double> {
            val n = (1 shl z).toDouble()
            val x = (lon + 180.0) / 360.0 * n
            val latRad = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
            val y = (1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n
            return x to y
        }
    }
}
