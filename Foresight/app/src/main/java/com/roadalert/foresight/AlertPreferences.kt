package com.roadalert.foresight

import android.content.Context
import android.content.SharedPreferences

class AlertPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("foresight_settings", Context.MODE_PRIVATE)

    companion object {
        const val KEY_VRU_ALERTS = "pref_vru_alerts"
        const val KEY_SPEED_ALERTS = "pref_speed_alerts"
        const val KEY_SPEED_CAMERAS = "pref_speed_cameras"
        const val KEY_ROADWORKS_TRAFFIC = "pref_roadworks_traffic"
        const val KEY_WEATHER_ALERTS = "pref_weather_alerts"
        const val KEY_AUDIO_CHIME = "pref_audio_chime"
        const val KEY_VOICE_ALERTS = "pref_voice_alerts"
        const val KEY_SPEED_TOLERANCE_PCT = "pref_speed_tolerance_pct"
        const val KEY_BLUETOOTH_HFP_MODE = "pref_bluetooth_hfp_mode"
    }

    var vruAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_VRU_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_VRU_ALERTS, value).apply()

    var speedAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEED_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_ALERTS, value).apply()

    var speedCamerasEnabled: Boolean
        get() = prefs.getBoolean(KEY_SPEED_CAMERAS, true)
        set(value) = prefs.edit().putBoolean(KEY_SPEED_CAMERAS, value).apply()

    var roadworksTrafficEnabled: Boolean
        get() = prefs.getBoolean(KEY_ROADWORKS_TRAFFIC, true)
        set(value) = prefs.edit().putBoolean(KEY_ROADWORKS_TRAFFIC, value).apply()

    var weatherAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEATHER_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_WEATHER_ALERTS, value).apply()

    var audioChimeEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_CHIME, true)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_CHIME, value).apply()

    var voiceAlertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_VOICE_ALERTS, value).apply()

    var speedTolerancePercent: Int
        get() = prefs.getInt(KEY_SPEED_TOLERANCE_PCT, 10)
        set(value) = prefs.edit().putInt(KEY_SPEED_TOLERANCE_PCT, value).apply()

    var bluetoothHfpMode: Boolean
        get() = prefs.getBoolean(KEY_BLUETOOTH_HFP_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_BLUETOOTH_HFP_MODE, value).apply()
}
