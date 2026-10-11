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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import java.io.File

class MainActivity : AppCompatActivity() {

    private var selectedType = "HOR"
    private var isRunning = false
    private lateinit var startStopBtn: MaterialButton
    private lateinit var gpsStatus: android.widget.TextView
    private lateinit var connStatus: android.widget.TextView

    private lateinit var btnHorse: Button
    private lateinit var btnWalker: Button
    private lateinit var btnCyclist: Button
    private lateinit var btnRunner: Button
    private lateinit var btnExportLogs: Button
    private lateinit var btnClearLogs: Button

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
        
        btnExportLogs = findViewById(R.id.btnExportLogs)
        btnClearLogs = findViewById(R.id.btnClearLogs)
        
        btnExportLogs.setOnClickListener { exportLogs() }
        btnClearLogs.setOnClickListener { clearLogs() }
        
        setupSelectionButtons()
        
        startStopBtn.setOnClickListener {
            if (isRunning) {
                showStopConfirmationDialog()
            } else {
                showStartConfirmationDialog()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun updateUiState() {
        isRunning = SafetyService.isRunning
        if (isRunning) {
            startStopBtn.text = "STOP"
            startStopBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.alert_red))
            selectedType = SafetyService.activeUserType
            updateSelectionColors()
            gpsStatus.text = "GPS: ${SafetyService.gpsStatusStr}"
            gpsStatus.alpha = 1.0f
            connStatus.text = "MQTT: ${SafetyService.mqttStatusStr}"
            connStatus.alpha = 1.0f
        } else {
            startStopBtn.text = "START"
            startStopBtn.setBackgroundColor(ContextCompat.getColor(this, R.color.safety_blue))
            gpsStatus.text = "GPS: Waiting"
            gpsStatus.alpha = 0.6f
            connStatus.text = "MQTT: Off"
            connStatus.alpha = 0.6f
        }
    }

    private fun showStartConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Start Road Alert?")
            .setMessage("Are you sure you want to start broadcasting your location?")
            .setPositiveButton("Start") { _, _ ->
                if (checkPermissions()) {
                    startSafetyService()
                } else {
                    requestPermissions()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showStopConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Stop Road Alert?")
            .setMessage("Are you sure you want to stop broadcasting your location?")
            .setPositiveButton("Stop") { _, _ ->
                stopSafetyService()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupSelectionButtons() {
        btnHorse = findViewById(R.id.btnHorse)
        btnWalker = findViewById(R.id.btnWalker)
        btnCyclist = findViewById(R.id.btnCyclist)
        btnRunner = findViewById(R.id.btnRunner)

        val buttons = listOf(btnHorse, btnWalker, btnCyclist, btnRunner)
        val types = listOf("HOR", "WAL", "CYC", "RUN")

        buttons.forEachIndexed { index, button ->
            button.setOnClickListener {
                selectedType = types[index]
                updateSelectionColors()
                Toast.makeText(this, "Selected: ${button.text}", Toast.LENGTH_SHORT).show()
            }
        }
        
        updateSelectionColors()
    }

    private fun updateSelectionColors() {
        val buttons = listOf(btnHorse, btnWalker, btnCyclist, btnRunner)
        val types = listOf("HOR", "WAL", "CYC", "RUN")
        buttons.forEachIndexed { index, button ->
            if (types[index] == selectedType) {
                button.setBackgroundColor(ContextCompat.getColor(this, R.color.safety_blue))
            } else {
                button.setBackgroundColor(ContextCompat.getColor(this, R.color.panel_bg))
            }
        }
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

    private fun exportLogs() {
        val fileDir = getExternalFilesDir(null) ?: return
        val file = File(fileDir, "vru_log.csv")
        if (!file.exists()) {
            Toast.makeText(this, "No log file found. Please run the service first.", Toast.LENGTH_LONG).show()
            return
        }
        
        try {
            val authority = "$packageName.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Road Alert VRU Telemetry Log")
                putExtra(Intent.EXTRA_TEXT, "Attached is the VRU telemetry log from the Road Alert PoC.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "Export Telemetry Log"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to export logs: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun clearLogs() {
        val fileDir = getExternalFilesDir(null) ?: return
        val file = File(fileDir, "vru_log.csv")
        if (file.exists()) {
            if (file.delete()) {
                Toast.makeText(this, "Logs cleared successfully.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Failed to clear logs.", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No logs to clear.", Toast.LENGTH_SHORT).show()
        }
    }
}