package com.roadalert.motorist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusTv: TextView
    private lateinit var toggleBtn: Button
    private lateinit var exportBtn: Button
    private lateinit var clearBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val layout = android.widget.LinearLayout(this)
        layout.orientation = android.widget.LinearLayout.VERTICAL
        layout.setPadding(64, 64, 64, 64)
        layout.gravity = android.view.Gravity.CENTER
        
        statusTv = TextView(this)
        statusTv.textSize = 24f
        statusTv.textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER
        
        toggleBtn = Button(this)
        toggleBtn.textSize = 18f
        val params = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 48, 0, 0)
        toggleBtn.layoutParams = params
        
        exportBtn = Button(this)
        exportBtn.text = "Export Logs"
        exportBtn.textSize = 18f
        val exportParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        exportParams.setMargins(0, 32, 0, 0)
        exportBtn.layoutParams = exportParams

        clearBtn = Button(this)
        clearBtn.text = "Clear Logs"
        clearBtn.textSize = 18f
        val clearParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        )
        clearParams.setMargins(0, 32, 0, 0)
        clearBtn.layoutParams = clearParams
        
        layout.addView(statusTv)
        layout.addView(toggleBtn)
        layout.addView(exportBtn)
        layout.addView(clearBtn)
        setContentView(layout)

        val prefs = getSharedPreferences("roadalert_prefs", Context.MODE_PRIVATE)
        var isEnabled = prefs.getBoolean("service_enabled", true)

        toggleBtn.setOnClickListener {
            isEnabled = !isEnabled
            prefs.edit().putBoolean("service_enabled", isEnabled).apply()
            updateUiAndService(isEnabled)
        }

        exportBtn.setOnClickListener {
            exportLogs()
        }

        clearBtn.setOnClickListener {
            clearLogs()
        }

        // Auto-start or request permissions
        if (checkPermissions()) {
            updateUiAndService(isEnabled)
        } else {
            statusTv.text = "Road Alert\n\nPermissions Required"
            toggleBtn.text = "Enable Service"
            requestPermissions()
        }
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
        
        // Background location is separate on API 29+
        if (hasBasics && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
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
            // Request background location separately if basics are already granted
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 101)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            if (requestCode == 100 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // After basics, request background
                requestPermissions()
            } else {
                val prefs = getSharedPreferences("roadalert_prefs", Context.MODE_PRIVATE)
                val isEnabled = prefs.getBoolean("service_enabled", true)
                updateUiAndService(isEnabled)
            }
        }
    }

    private fun updateUiAndService(enabled: Boolean) {
        if (enabled) {
            statusTv.text = "Road Alert\n\nService Active"
            toggleBtn.text = "Stop Service"
            startMotoristService()
        } else {
            statusTv.text = "Road Alert\n\nService Stopped"
            toggleBtn.text = "Start Service"
            stopMotoristService()
        }
    }

    private fun startMotoristService() {
        val intent = Intent(this, MotoristService::class.java)
        intent.action = MotoristService.ACTION_START
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopMotoristService() {
        val intent = Intent(this, MotoristService::class.java)
        stopService(intent)
    }

    private fun exportLogs() {
        val fileDir = getExternalFilesDir(null) ?: return
        val file = File(fileDir, "safety_log.csv")
        if (!file.exists()) {
            android.widget.Toast.makeText(this, "No log file found. Please run the service first.", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        
        try {
            val authority = "$packageName.fileprovider"
            val uri = FileProvider.getUriForFile(this, authority, file)
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Road Alert Telemetry Log")
                putExtra(Intent.EXTRA_TEXT, "Attached is the safety telemetry log from the Road Alert PoC.")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(Intent.createChooser(intent, "Export Telemetry Log"))
        } catch (e: Exception) {
            e.printStackTrace()
            android.widget.Toast.makeText(this, "Failed to export logs: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun clearLogs() {
        val fileDir = getExternalFilesDir(null) ?: return
        val file = File(fileDir, "safety_log.csv")
        if (file.exists()) {
            if (file.delete()) {
                android.widget.Toast.makeText(this, "Logs cleared successfully.", android.widget.Toast.LENGTH_SHORT).show()
            } else {
                android.widget.Toast.makeText(this, "Failed to clear logs.", android.widget.Toast.LENGTH_SHORT).show()
            }
        } else {
            android.widget.Toast.makeText(this, "No logs to clear.", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
}
