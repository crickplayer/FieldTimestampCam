package com.fieldcam.timestamp

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Geocoder
import android.os.Build
import android.os.Looper
import com.google.android.gms.location.*
import java.util.Locale
import kotlin.math.roundToInt

class SensorLocationManager(
    private val context: Context,
    private val state: TelemetryState
) : SensorEventListener {

    private val fusedClient = LocationServices.getFusedLocationProviderClient(context)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val geocoder = Geocoder(context, Locale.getDefault())
    private var lastGeocodeTime = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val loc = result.lastLocation ?: return
            state.latitude = loc.latitude
            state.longitude = loc.longitude
            state.altitudeMeters = loc.altitude
            state.speedMps = loc.speed

            val now = System.currentTimeMillis()
            if (now - lastGeocodeTime > 5000L) {
                lastGeocodeTime = now
                reverseGeocode(loc.latitude, loc.longitude)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(500L)
            .build()
        fusedClient.requestLocationUpdates(req, locationCallback, Looper.getMainLooper())
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        fusedClient.removeLocationUpdates(locationCallback)
        sensorManager.unregisterListener(this)
    }

    private fun reverseGeocode(lat: Double, lon: Double) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            geocoder.getFromLocation(lat, lon, 1) { addresses ->
                addresses.firstOrNull()?.let { addr ->
                    state.locationName = addr.subLocality ?: addr.locality ?: addr.adminArea ?: ""
                }
            }
        } else {
            @Suppress("DEPRECATION")
            Thread {
                try {
                    val addr = geocoder.getFromLocation(lat, lon, 1)?.firstOrNull()
                    if (addr != null) {
                        state.locationName = addr.subLocality ?: addr.locality ?: addr.adminArea ?: ""
                    }
                } catch (_: Exception) {}
            }.start()
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotMatrix = FloatArray(9)
            val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
            SensorManager.getOrientation(rotMatrix, orientation)
            val deg = Math.toDegrees(orientation[0].toDouble()).roundToInt()
            state.azimuthDeg = (deg + 360) % 360
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
