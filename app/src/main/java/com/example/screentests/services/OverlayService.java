package com.example.screentests.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;

import com.example.screentests.R;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.engine.ProductivityState;

public class OverlayService extends Service {
    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "OverlayServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private View interventionOverlay;
    private View categorizationOverlay;
    private int lastLevel = -1;
    private boolean isInterventionShowing = false;
    private boolean isCategorizationShowing = false;
    private String lastCategorizationPackage = "";
    private boolean isObserverRegistered = false;
    private BeeManager beeManager;

    private final Observer<ProductivityState> stateObserver = state -> {
        Log.d(TAG, "Received state update: score=" + state.getScore() + ", level=" + state.getLevel() + ", showIntervention=" + state.isShowInterventionOverlay());
        // FIX: Pass the actual level from state
        updateBees(state.getLevel());
        updateIntervention(state.isShowInterventionOverlay());
        updateCategorizationOverlay(state.isCheckRequiredForUnknownApp(), state.getCurrentPackageName());
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        beeManager = new BeeManager(this, windowManager, 0);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "Service onStartCommand");
        
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bee Productive")
                .setContentText("Tracker is active")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        if (!isObserverRegistered) {
            ProductivityEngine.getInstance().getState().observeForever(stateObserver);
            isObserverRegistered = true;
        }

        return START_STICKY;
    }

    private void updateBees(int level) {
        if (level == lastLevel) return;
        Log.d(TAG, "Updating bees for level: " + level);
        
        beeManager.initBeeSwarm(level);
        if (level > 0) {
            beeManager.startSimulation();
        } else {
            beeManager.removeAllBees();
        }
        
        lastLevel = level;
    }

    private void updateIntervention(boolean show) {
        if (show == isInterventionShowing) return;
        Log.d(TAG, "Updating intervention show: " + show);

        if (show) {
            interventionOverlay = LayoutInflater.from(this).inflate(R.layout.overlay_intervention, null);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);

            interventionOverlay.findViewById(R.id.closeOverlayButton).setOnClickListener(v -> {
                Log.d(TAG, "Overlay close button clicked");
                ProductivityEngine.getInstance().resetScore();
            });

            try {
                windowManager.addView(interventionOverlay, params);
                isInterventionShowing = true;
            } catch (Exception e) {
                Log.e(TAG, "Error adding intervention overlay", e);
            }
        } else {
            if (interventionOverlay != null) {
                try {
                    windowManager.removeView(interventionOverlay);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing intervention overlay", e);
                }
                interventionOverlay = null;
            }
            isInterventionShowing = false;
        }
    }

    private void updateCategorizationOverlay(boolean show, String packageName) {
        if (show == isCategorizationShowing && packageName.equals(lastCategorizationPackage)) return;
        Log.d(TAG, "Updating categorization overlay: show=" + show + ", pkg=" + packageName);

        // Remove old if exists
        if (categorizationOverlay != null) {
            try {
                windowManager.removeView(categorizationOverlay);
            } catch (Exception e) {
                Log.e(TAG, "Error removing categorization overlay", e);
            }
            categorizationOverlay = null;
        }

        if (show) {
            categorizationOverlay = LayoutInflater.from(this).inflate(R.layout.dialog_app_categorization, null);
            
            // Background to dim the underlying app
            categorizationOverlay.setBackgroundColor(0xAA000000);

            TextView packageLabel = categorizationOverlay.findViewById(R.id.packageNameLabel);
            packageLabel.setText("Package: " + packageName);

            RadioGroup statusGroup = categorizationOverlay.findViewById(R.id.statusRadioGroup);
            SeekBar severitySeekBar = categorizationOverlay.findViewById(R.id.severitySeekBar);
            Button saveButton = categorizationOverlay.findViewById(R.id.saveButton);

            saveButton.setOnClickListener(v -> {
                String status = statusGroup.getCheckedRadioButtonId() == R.id.radioProductive ? "PRODUCTIVE" : "UNPRODUCTIVE";
                int severity = severitySeekBar.getProgress() + 1;
                ProductivityEngine.getInstance().updateAppPolicy(packageName, status, severity);
            });

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);

            try {
                windowManager.addView(categorizationOverlay, params);
                isCategorizationShowing = true;
                lastCategorizationPackage = packageName;
            } catch (Exception e) {
                Log.e(TAG, "Error adding categorization overlay", e);
            }
        } else {
            isCategorizationShowing = false;
            lastCategorizationPackage = "";
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Overlay Service Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "Service onDestroy");
        super.onDestroy();
        if (isObserverRegistered) {
            ProductivityEngine.getInstance().getState().removeObserver(stateObserver);
        }
        if (beeManager != null) {
            beeManager.removeAllBees();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
