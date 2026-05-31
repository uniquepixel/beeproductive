package com.example.screentests.frontend;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.example.screentests.R;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.engine.ProductivityState;
import com.example.screentests.services.OverlayService;

import java.util.Random;

public class MainActivity extends AppCompatActivity {

    private ConstraintLayout mainLayout;
    private int lastLevel = -1;
    private boolean isDialogShowing = false;

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

        mainLayout = findViewById(R.id.main);

        findViewById(R.id.settingsFab).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
        });

        ProductivityEngine.getInstance().init(getApplicationContext());

        ProductivityEngine.getInstance().getState().observe(this, this::updateUI);

        checkOverlayPermission();
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
        Intent intent = new Intent(this, OverlayService.class);
        androidx.core.content.ContextCompat.startForegroundService(this, intent); //funktioniert nur mit ForegroundService
    }

    private void updateUI(ProductivityState state) {
        // Unproductivity Level (0 - 4)
        int currentLevel = state.getLevel();
        if (currentLevel != lastLevel) {
            updateBeeCount(currentLevel);
            lastLevel = currentLevel;
        }
        // Intervention is handled globally by OverlayService.

        // Unknown App Detection
        if (state.isCheckRequiredForUnknownApp() && !isDialogShowing) {
            showCategorizationDialog(state.getCurrentPackageName());
        }
    }

    private void updateBeeCount(int level) {
        // TODO: currently adds bees based on lvl 0-4. Change to swarm logic -> also change updateUI & OverlayService

        // delete existing bees
        for (int i = 0; i < mainLayout.getChildCount(); i++) {
            if ("bee".equals(mainLayout.getChildAt(i).getTag())) {
                mainLayout.removeViewAt(i);
                i--;
            }
        }

        int beeCount = level * 3;
        Random random = new Random();
        for (int i = 0; i < beeCount; i++) {
            ImageView bee = new ImageView(this);
            bee.setImageResource(R.drawable.ic_launcher_foreground); // Placeholder: bee texture
            bee.setTag("bee");
            bee.setAlpha(0.6f);
            
            int size = 100 + random.nextInt(100);
            ConstraintLayout.LayoutParams lp = new ConstraintLayout.LayoutParams(size, size);
            bee.setLayoutParams(lp);
            
            // random pos
            bee.setX(random.nextInt(Math.max(1, mainLayout.getWidth() - size)));
            bee.setY(random.nextInt(Math.max(1, mainLayout.getHeight() - size)));
            
            mainLayout.addView(bee);
        }
    }

    private void showCategorizationDialog(String packageName) {
        isDialogShowing = true;
        AppCategorizationDialog dialog = AppCategorizationDialog.newInstance(packageName);
        dialog.show(getSupportFragmentManager(), "CategorizationDialog");
        // We might need a listener to reset isDialogShowing when dismissed
        getSupportFragmentManager().registerFragmentLifecycleCallbacks(new androidx.fragment.app.FragmentManager.FragmentLifecycleCallbacks() {
            @Override
            public void onFragmentDestroyed(androidx.fragment.app.FragmentManager fm, androidx.fragment.app.Fragment f) {
                super.onFragmentDestroyed(fm, f);
                if (f instanceof AppCategorizationDialog) {
                    isDialogShowing = false;
                    getSupportFragmentManager().unregisterFragmentLifecycleCallbacks(this);
                }
            }
        }, false);
    }
}