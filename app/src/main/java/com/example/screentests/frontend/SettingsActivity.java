package com.example.screentests.frontend;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;

import com.example.screentests.R;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.services.OverlayManager;
import com.google.android.material.slider.Slider;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_settings);

        ProductivityEngine engine = ProductivityEngine.getInstance();


        Slider aggressionSlider = findViewById(R.id.aggressionSlider);//Agression: how fast the score increases, is interval length in s/tick -> lower value = higher increase
        float currentIntervalSec = engine.getScoreIntervalMillis() / 1000f;
        float aggressionValue = 61f - currentIntervalSec;
        aggressionSlider.setValue(Math.max(1f, Math.min(60f, aggressionValue)));
        
        aggressionSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                long newIntervalMs = (long)((61f - value) * 1000);
                engine.setScoreIntervalMillis(newIntervalMs);
            }
        });

        Slider sizeSlider = findViewById(R.id.sizeSlider);//Size: max #bees
        sizeSlider.setValue((float) engine.getMaxBees());
        sizeSlider.addOnChangeListener((slider, value, fromUser) -> {
            if (fromUser) {
                engine.setMaxBees((int) value);
            }
        });

        Button backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        findViewById(R.id.debugTriggerInterventionButton).setOnClickListener(v -> {
            startService(new Intent(SettingsActivity.this, OverlayManager.class));
            ProductivityEngine.getInstance().debugTriggerUI(4, true);
        });

        findViewById(R.id.resetCategorizationButton).setOnClickListener(v -> {
            ProductivityEngine.getInstance().resetAllCategorizations();
            Toast.makeText(this, "All categorizations have been reset", Toast.LENGTH_SHORT).show();
        });
    }
}
