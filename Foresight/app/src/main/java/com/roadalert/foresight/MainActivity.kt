package com.roadalert.foresight

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: AlertPreferences
    private lateinit var statusBadgeTv: TextView
    private lateinit var summaryTv: TextView
    private lateinit var startStopBtn: Button
    private lateinit var settingsBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        prefs = AlertPreferences(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F172A")) // dark_slate
            setPadding(56, 72, 56, 72)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // App Title
        val titleTv = TextView(this).apply {
            text = "FORESIGHT"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        root.addView(titleTv)

        val subtitleTv = TextView(this).apply {
            text = "Headless Driver Safety Assistant"
            textSize = 14f
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 48)
        }
        root.addView(subtitleTv)

        // Status Card
        val statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#1E293B")) // panel_bg
            setPadding(40, 40, 40, 40)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        statusBadgeTv = TextView(this).apply {
            text = "STANDBY"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#94A3B8"))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        statusCard.addView(statusBadgeTv)

        // Summary of Active Shields
        summaryTv = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            setLineSpacing(12f, 1f)
        }
        statusCard.addView(summaryTv)
        root.addView(statusCard)

        // Spacer
        val spacer = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        root.addView(spacer)

        // Main Action Button (Start / Stop)
        startStopBtn = Button(this).apply {
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#0284C7")) // primary blue
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                150
            ).apply {
                setMargins(0, 24, 0, 16)
            }
            setOnClickListener {
                val servicePref = getSharedPreferences("foresight_prefs", Context.MODE_PRIVATE)
                val isRunning = servicePref.getBoolean("service_running", false)
                if (isRunning) {
                    stopForesightService()
                } else {
                    showStartJourneyDialog()
                }
            }
        }
        root.addView(startStopBtn)

        // Settings Button
        settingsBtn = Button(this).apply {
            text = "Settings"
            textSize = 16f
            setTextColor(Color.parseColor("#E2E8F0"))
            setBackgroundColor(Color.parseColor("#334155"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                130
            )
            setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        root.addView(settingsBtn)

        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun updateUiState() {
        val servicePref = getSharedPreferences("foresight_prefs", Context.MODE_PRIVATE)
        val isRunning = servicePref.getBoolean("service_running", false)

        val vru = if (prefs.vruAlertsEnabled) "✓ ON" else "✗ OFF"
        val cameras = if (prefs.speedCamerasEnabled) "✓ ON" else "✗ OFF"
        val speed = if (prefs.speedAlertsEnabled) "✓ ON (+${prefs.speedTolerancePercent}%)" else "✗ OFF"
        val traffic = if (prefs.roadworksTrafficEnabled) "✓ ON" else "✗ OFF"
        val weather = if (prefs.weatherAlertsEnabled) "✓ ON" else "✗ OFF"
        val audioMode = if (prefs.bluetoothHfpMode) "Bluetooth Call (HFP)" else "Bluetooth / Media (A2DP)"

        summaryTv.text = """
            SHIELD STATUS:
            • VRU Detection: $vru
            • Speed Cameras: $cameras
            • Speed Limit Alert: $speed
            • Roadworks & Incidents: $traffic
            • Adverse Weather: $weather
            
            AUDIO OUTPUT:
            • $audioMode
        """.trimIndent()

        if (isRunning) {
            statusBadgeTv.text = "● FORESIGHT ACTIVE"
            statusBadgeTv.setTextColor(Color.parseColor("#4ADE80")) // accent_green
            startStopBtn.text = "STOP JOURNEY"
            startStopBtn.setBackgroundColor(Color.parseColor("#EF4444")) // alert_red
        } else {
            statusBadgeTv.text = "STANDBY"
            statusBadgeTv.setTextColor(Color.parseColor("#94A3B8"))
            startStopBtn.text = "START JOURNEY"
            startStopBtn.setBackgroundColor(Color.parseColor("#0284C7"))
        }
    }

    private fun showStartJourneyDialog() {
        if (!checkPermissions()) {
            requestPermissions()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Start Foresight")
            .setMessage("Foresight runs completely in the background.\n\nYou can turn off the screen or put your phone away. Audio alerts and chimes will play automatically through your car's Bluetooth or phone speaker.")
            .setPositiveButton("Confirm & Drive") { _, _ ->
                startForesightService()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startForesightService() {
        val servicePref = getSharedPreferences("foresight_prefs", Context.MODE_PRIVATE)
        servicePref.edit().putBoolean("service_running", true).apply()

        val intent = Intent(this, ForesightService::class.java).apply {
            action = ForesightService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUiState()
    }

    private fun stopForesightService() {
        val servicePref = getSharedPreferences("foresight_prefs", Context.MODE_PRIVATE)
        servicePref.edit().putBoolean("service_running", false).apply()

        val intent = Intent(this, ForesightService::class.java).apply {
            action = ForesightService.ACTION_STOP
        }
        stopService(intent)
        updateUiState()
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val hasBasics = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (hasBasics && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }

        return hasBasics
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val hasBasics = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!hasBasics) {
            ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
                101
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (requestCode == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestPermissions()
            } else {
                showStartJourneyDialog()
            }
        }
    }
}
