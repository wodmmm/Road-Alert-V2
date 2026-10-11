package com.roadalert.garminbridge;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btn = findViewById(R.id.btnToggle);
        TextView statusText = findViewById(R.id.tvStatus);
        
        btn.setText("Start Bridge");

        // Request permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS
                }, 101);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
             if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                 requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
             }
        }

        btn.setOnClickListener(v -> {
            Intent svc = new Intent(this, GarminBleService.class);
            if (btn.getText().equals("Start Bridge")) {
                startForegroundService(svc);
                btn.setText("Stop Bridge");
                statusText.setText("Bridge running — listening via Garmin Connect BT");
            } else {
                stopService(svc);
                btn.setText("Start Bridge");
                statusText.setText("Bridge stopped.");
            }
        });
    }
}