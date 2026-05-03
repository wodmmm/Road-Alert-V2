package com.example.garminmqttbridge;

import android.content.Intent;
import android.os.*;
import android.widget.Button;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btn = findViewById(R.id.btnToggle);
        btn.setText("Start Bridge");

        btn.setOnClickListener(v -> {
            Intent svc = new Intent(this, BridgeService.class);
            if (btn.getText().equals("Start Bridge")) {
                startForegroundService(svc);
                btn.setText("Stop Bridge");
            } else {
                stopService(svc);
                btn.setText("Start Bridge");
            }
        });
    }
}