package com.roadalert.garminbridge;

import android.content.Intent;
import android.os.*;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

/**
 * MainActivity — minimal one-button UI to start/stop the bridge service.
 * All the real work happens in BridgeService running in the background.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btn = findViewById(R.id.btnToggle);
        TextView statusText = findViewById(R.id.tvStatus);

        btn.setOnClickListener(v -> {
            Intent svc = new Intent(this, BridgeService.class);
            if (btn.getText().equals("Start Bridge")) {
                startForegroundService(svc);
                btn.setText("Stop Bridge");
                statusText.setText("Bridge running — listening for Garmin data on port 8080");
            } else {
                stopService(svc);
                btn.setText("Start Bridge");
                statusText.setText("Bridge stopped.");
            }
        });
    }
}
