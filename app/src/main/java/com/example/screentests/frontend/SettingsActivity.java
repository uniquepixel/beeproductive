package com.example.screentests.frontend;

import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.example.screentests.R;
import com.example.screentests.engine.ProductivityEngine;
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

        // AI-changed: was addOnChangeListener, which committed on EVERY drag event — each pixel
        // of movement rewrote SharedPreferences and re-armed the score scheduler (and every
        // re-arm resets the delay until the next tick, so dragging kept postponing scoring).
        // Commit once when the finger lifts instead.
        aggressionSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) { }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                long newIntervalMs = (long) ((61f - slider.getValue()) * 1000);
                engine.setScoreIntervalMillis(newIntervalMs);
            }
        });

        Slider sizeSlider = findViewById(R.id.sizeSlider);//Size: max #bees
        sizeSlider.setValue((float) engine.getMaxBees());
        // AI-changed: same commit-on-release treatment as the aggression slider above.
        sizeSlider.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(@NonNull Slider slider) { }

            @Override
            public void onStopTrackingTouch(@NonNull Slider slider) {
                engine.setMaxBees((int) slider.getValue());
            }
        });

        Button backButton = findViewById(R.id.backButton);
        backButton.setOnClickListener(v -> finish());

        findViewById(R.id.resetCategorizationButton).setOnClickListener(v -> {
            ProductivityEngine.getInstance().resetAllCategorizations();
            Toast.makeText(this, "All categorizations have been reset", Toast.LENGTH_SHORT).show();
        });
    }
}
