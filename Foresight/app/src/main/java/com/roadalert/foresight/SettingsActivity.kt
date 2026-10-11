package com.roadalert.foresight

import android.os.Bundle
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: AlertPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = AlertPreferences(this)
        title = "Foresight Settings"

        val scrollView = android.widget.ScrollView(this)
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 48, 48, 64)
        }

        fun addSectionHeader(title: String) {
            val tv = TextView(this).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(0xFF38BDF8.toInt())
                setPadding(0, 32, 0, 12)
            }
            layout.addView(tv)
        }

        fun addCheckbox(label: String, initialValue: Boolean, onChecked: (Boolean) -> Unit): CheckBox {
            val cb = CheckBox(this).apply {
                text = label
                textSize = 16f
                isChecked = initialValue
                setOnCheckedChangeListener { _, isChecked -> onChecked(isChecked) }
                setPadding(16, 16, 16, 16)
            }
            layout.addView(cb)
            return cb
        }

        addSectionHeader("HAZARD SHIELDS")
        addCheckbox("Vulnerable Road Users (VRU)", prefs.vruAlertsEnabled) { prefs.vruAlertsEnabled = it }
        addCheckbox("Speed Camera Warnings", prefs.speedCamerasEnabled) { prefs.speedCamerasEnabled = it }
        addCheckbox("Roadworks & Incident Delays", prefs.roadworksTrafficEnabled) { prefs.roadworksTrafficEnabled = it }
        addCheckbox("Adverse Weather & Road Conditions", prefs.weatherAlertsEnabled) { prefs.weatherAlertsEnabled = it }
        addCheckbox("Speed Limit Exceeded Alerts", prefs.speedAlertsEnabled) { prefs.speedAlertsEnabled = it }

        addSectionHeader("AUDIO & VOICE")
        addCheckbox("Warning Chime (before speech)", prefs.audioChimeEnabled) { prefs.audioChimeEnabled = it }
        addCheckbox("Spoken Voice Alerts (TTS)", prefs.voiceAlertsEnabled) { prefs.voiceAlertsEnabled = it }
        addCheckbox("Route via Car Bluetooth Phone Call (HFP)", prefs.bluetoothHfpMode) { prefs.bluetoothHfpMode = it }

        addSectionHeader("SPEED TOLERANCE")
        val tolTv = TextView(this).apply {
            text = "Alert at: +${prefs.speedTolerancePercent}% over limit"
            textSize = 15f
            setPadding(0, 8, 0, 8)
        }
        layout.addView(tolTv)

        val seekBar = SeekBar(this).apply {
            max = 20
            progress = prefs.speedTolerancePercent
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    val p = if (progress < 0) 0 else progress
                    tolTv.text = "Alert at: +$p% over limit"
                    prefs.speedTolerancePercent = p
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        layout.addView(seekBar)

        scrollView.addView(layout)
        setContentView(scrollView)
    }
}
