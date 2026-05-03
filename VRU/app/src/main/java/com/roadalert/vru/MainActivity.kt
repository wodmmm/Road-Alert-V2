package com.roadalert.vru

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity() {

    private var selectedType = "HOR"
    private var isRunning = false
    private lateinit var startStopBtn: MaterialButton
    private lateinit var gpsStatus: android.widget.TextView
    private lateinit var connStatus: android.widget.TextView

    private val statusReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            intent?.getStringExtra("gps")?.let { 
                gpsStatus.text = "GPS: $it"
                gpsStatus.alpha = 1.0f 
            }
            intent?.getStringExtra("mqtt")?.let { 
                connStatus.text = "MQTT: $it"
                connStatus.alpha = 1.0f 
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter("com.roadalert.vru.STATUS_UPDATE")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(statusReceiver)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        startStopBtn = findViewById(R.id.startStopBtn)
        gpsStatus = findViewById(R.id.gpsStatus)
        connStatus = findViewById(R.id.connStatus)
        
        setupSelectionButtons()
        
        startStopBtn.setOnClickListener {
            if (isRunning) {
                stopSafetyService()
            } else {
                if (checkPermissions()) {
                    startSafetyService()
                } else {
                    requestPermissions()
                }
            }
        }
    }

    private fun setupSelectionButtons() {
        val btnHorse = findViewById<Button>(R.id.btnHorse)
        val btnWalker = findViewById<Button>(R.id.btnWalker)
        val btnCyclist = findViewById<Button>(R.id.btnCyclist)
        val btnRunner = findViewById<Button>(R.id.btnRunner)

        val buttons = listOf(btnHorse, btnWalker, btnCyclist, btnRunner)
        val types = listOf("HOR", "WAL", "CYC", "RUN")

        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                selectedType = types[index]
                // Reset all button colors
                buttons.forEach { it.setBackgroundColor(ContextCompat.getColor(this, R.color.panel_bg)) }
                // Highlight selected
                button.setBackgroundColor(ContextCompat.getColor(this, R.color.safety_blue))
                Toast.makeText(this, "Selected: ${button.text}", Toast.LENGTH_SHORT).show()
            }
        }
        
        // Default selection
        btnHorse.setBackgroundColor(ContextCompat.getColor(this, R.color.safety_blue))
    }

    private fun startSafetyService() {
        val intent = Intent(this, SafetyService::class.java)
        intent.putExtra("USER_TYPE", selectedType)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        isRunning = true
        startStopBtn.text = "STOP"
        startStopBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.alert_red))
    }

    private fun stopSafetyService() {
        stopService(Intent(this, SafetyService::class.java))
        isRunning = false
        startStopBtn.text = "START"
        startStopBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.safety_blue))
    }

    private fun checkPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
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

        ActivityCompat.requestPermissions(this, permissions.toTypedArray(), 100)
    }
}