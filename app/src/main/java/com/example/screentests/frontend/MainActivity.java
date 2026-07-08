package com.example.screentests.frontend;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.example.screentests.R;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.engine.ProductivityState;
import com.example.screentests.services.OverlayManager;

public class MainActivity extends AppCompatActivity {

    private int lastLevel = -1;

    private final ActivityResultLauncher<Intent> overlayPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Settings.canDrawOverlays(this)) {
                    startOverlayService();
                }
            }
    );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        findViewById(R.id.settingsFab).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        ProductivityEngine.getInstance().init(getApplicationContext());

        com.google.android.material.materialswitch.MaterialSwitch productivitySwitch = findViewById(R.id.productivitySwitch);
        productivitySwitch.setChecked(ProductivityEngine.getInstance().isEnabled());
        productivitySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ProductivityEngine.getInstance().setEnabled(isChecked);
            // AI-changed: the master switch now also controls the overlay foreground service.
            // Switching off used to leave an idle service (and its permanent "Tracker is active"
            // notification) running; switching on now brings it back immediately instead of
            // waiting for the next app change.
            if (isChecked) {
                checkOverlayPermission();
            } else {
                stopService(new Intent(MainActivity.this, OverlayManager.class));
            }
        });

        ProductivityEngine.getInstance().getState().observe(this, this::updateUI);

        checkOverlayPermission();
        checkAccessibilityPermission();
    }

    private void checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            overlayPermissionLauncher.launch(intent);
        } else {
            startOverlayService();
        }
    }

    private void startOverlayService() {
        // AI-changed: respect the master switch — don't spin up the foreground service (and its
        // permanent notification) when the whole app functionality is turned off.
        if (!ProductivityEngine.getInstance().isEnabled()) return;
        Intent intent = new Intent(this, OverlayManager.class);
        androidx.core.content.ContextCompat.startForegroundService(this, intent);
    }

    private boolean isAccessibilityServiceEnabled() {
        String expectedComponentName = getPackageName() + "/" + com.example.screentests.services.TrackerAccessibilityService.class.getName();
        String enabledServicesSetting = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );

        if (enabledServicesSetting == null) return false;

        TextUtils.SimpleStringSplitter colonSplitter = new TextUtils.SimpleStringSplitter(':');
        colonSplitter.setString(enabledServicesSetting);

        while (colonSplitter.hasNext()) {
            String componentName = colonSplitter.next();
            if (componentName.equalsIgnoreCase(expectedComponentName)) {
                return true;
            }
        }
        return false;
    }

    private void checkAccessibilityPermission() {
        if (!isAccessibilityServiceEnabled()) {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        }
    }

    private void updateUI(ProductivityState state) {
        int currentLevel = state.getLevel();
        if (currentLevel != lastLevel) {
            lastLevel = currentLevel;
        }
    }
}
