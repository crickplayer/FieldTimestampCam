package com.radarviewer.ld2450

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

data class AppSettings(
    val mqttHost: String = "",
    val mqttPort: Int = 8883,
    val mqttUser: String = "",
    val mqttPassword: String = "",
    /** e.g. "ld2450-35f4". Learned automatically on the home network or from the broker. */
    val deviceId: String = "",
    /** Optional fixed home-network address of the ESP32, e.g. "192.168.1.243". */
    val homeAddress: String = "",
    /** Last address that worked on the home network (tried first next time). */
    val lastHomeAddress: String = "",
) {
    val cloudConfigured: Boolean get() = mqttHost.isNotBlank()
}

/** Settings are kept in encrypted storage on the phone (the MQTT password never leaves it). */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        "radar_settings",
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load() = AppSettings(
        mqttHost = prefs.getString("mqtt_host", "") ?: "",
        mqttPort = prefs.getInt("mqtt_port", 8883),
        mqttUser = prefs.getString("mqtt_user", "") ?: "",
        mqttPassword = prefs.getString("mqtt_pass", "") ?: "",
        deviceId = prefs.getString("device_id", "") ?: "",
        homeAddress = prefs.getString("home_address", "") ?: "",
        lastHomeAddress = prefs.getString("last_home_address", "") ?: "",
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("mqtt_host", s.mqttHost.trim())
            .putInt("mqtt_port", s.mqttPort)
            .putString("mqtt_user", s.mqttUser.trim())
            .putString("mqtt_pass", s.mqttPassword)
            .putString("device_id", s.deviceId.trim())
            .putString("home_address", s.homeAddress.trim())
            .putString("last_home_address", s.lastHomeAddress)
            .apply()
    }
}
