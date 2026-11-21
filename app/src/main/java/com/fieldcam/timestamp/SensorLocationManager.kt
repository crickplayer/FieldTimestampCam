package com.fieldcam.timestamp

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Address
import android.location.Geocoder
import android.os.Looper
import com.google.android.gms.location.*
import java.util.Locale
import java.util.concurrent.Executors

class SensorLocationManager(
    private val context: Context,
    private val state: TelemetryState
) : SensorEventListener {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val geocoder = Geocoder(context, Locale.getDefault())
    private val geoExecutor = Executors.newSingleThreadExecutor()
    private var lastGeocodeTime = 0L
    private var started = false

    /** Display rotation in degrees (0/90/180/270); updated by the activity. */
    @Volatile var displayRotationDeg: Int = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            state.latitude = loc.latitude
            state.longitude = loc.longitude
            if (loc.hasAltitude()) state.altitudeMeters = loc.altitude
            state.speedMps = if (loc.hasSpeed()) loc.speed else 0f
            if (loc.hasAccuracy()) state.accuracyMeters = loc.accuracy

            val now = System.currentTimeMillis()
            if (now - lastGeocodeTime > 5000L) {
                lastGeocodeTime = now
                geoExecutor.execute {
                    lookup(loc.latitude, loc.longitude, 1).firstOrNull()?.let { state.address = it }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        runCatching {
            val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateIntervalMillis(250L)
                .build()
            fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
            fusedClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && state.latitude == null) {
                    state.latitude = loc.latitude; state.longitude = loc.longitude
                }
            }
        }
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() {
        if (!started) return
        started = false
        fusedClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
    }

    /** Blocking geocode; call off the main thread. */
    @Suppress("DEPRECATION")
    fun lookup(lat: Double, lon: Double, max: Int): List<Address> =
        runCatching { geocoder.getFromLocation(lat, lon, max) ?: emptyList() }.getOrDefault(emptyList())

    /** Addresses around the current fix (several small offsets) for the location picker. */
    fun nearbyAddresses(callback: (List<Address>) -> Unit) {
        val lat = state.latitude
        val lon = state.longitude
        if (lat == null || lon == null || !Geocoder.isPresent()) { callback(emptyList()); return }
        geoExecutor.execute {
            val out = LinkedHashMap<String, Address>()
            val d = 0.0009 // ~100 m
            val offsets = listOf(0.0 to 0.0, d to 0.0, -d to 0.0, 0.0 to d, 0.0 to -d, 2 * d to 2 * d, -2 * d to -2 * d)
            for ((dy, dx) in offsets) {
                for (a in lookup(lat + dy, lon + dx, 5)) {
                    val line = a.getAddressLine(0) ?: continue
                    out.putIfAbsent(line, a)
                }
                if (out.size >= 15) break
            }
            callback(out.values.toList())
        }
    }

    private val rotMatrix = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)
    private var smoothed = Float.NaN

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        // Heading of the camera's optical axis (back camera points along -Z of the device).
        SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        var deg = Math.toDegrees(orientation[0].toDouble()).toFloat()
        deg = (deg + displayRotationDeg + 360f) % 360f
        smoothed = if (smoothed.isNaN()) deg else {
            var diff = deg - smoothed
            if (diff > 180) diff -= 360
            if (diff < -180) diff += 360
            (smoothed + diff * 0.2f + 360f) % 360f
        }
        state.azimuthDeg = smoothed
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun shutdown() {
        stop()
        geoExecutor.shutdown()
    }
}
