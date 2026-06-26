package com.example.screentests.frontend;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.screentests.R;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.services.OverlayService;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        Button backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        findViewById(R.id.debugTriggerInterventionButton).setOnClickListener(v -> {
            // Start the OverlayService if not already running
            startService(new Intent(SettingsActivity.this, OverlayService.class));
            // Force the engine to level 4 (Full Intervention)
            ProductivityEngine.getInstance().debugTriggerUI(4, true);
        });

        findViewById(R.id.resetCategorizationButton).setOnClickListener(v -> {
            ProductivityEngine.getInstance().resetAllCategorizations();
            Toast.makeText(this, "All categorizations have been reset", Toast.LENGTH_SHORT).show();
        });
    }
}
