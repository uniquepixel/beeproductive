package com.example.screentests.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;

import com.example.screentests.R;
import com.example.screentests.engine.ProductivityEngine;
import com.example.screentests.engine.ProductivityState;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class OverlayService extends Service {
    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "OverlayServiceChannel";
    private static final int NOTIFICATION_ID = 1;

    private WindowManager windowManager;
    private View interventionOverlay;
    private List<View> bees = new ArrayList<>();
    private int lastLevel = -1;
    private boolean isInterventionShowing = false;
    private boolean isObserverRegistered = false;

    private final Observer<ProductivityState> stateObserver = state -> {
        Log.d(TAG, "Received state update: level=" + state.getLevel() + ", showIntervention=" + state.isShowInterventionOverlay());
        updateBees(state.getLevel());
        updateIntervention(state.isShowInterventionOverlay());
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service onCreate");
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
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
        // TODO: update logic when swarm logic is implemented in frontend
        if (level == lastLevel) return;
        Log.d(TAG, "Updating bees to level: " + level);
        
        // Remove old bees
        for (View bee : bees) {
            try {
                windowManager.removeView(bee);
            } catch (Exception e) {
                Log.e(TAG, "Error removing bee view", e);
            }
        }
        bees.clear();

        if (level > 0) {
            int beeCount = level * 3;
            Random random = new Random();
            
            Rect bounds = getScreenBounds();
            int screenWidth = bounds.width();
            int screenHeight = bounds.height();

            for (int i = 0; i < beeCount; i++) {
                ImageView bee = new ImageView(this);
                bee.setImageResource(R.drawable.ic_launcher_foreground);
                bee.setAlpha(0.6f);

                int size = 100 + random.nextInt(100);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        size, size,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);

                params.gravity = Gravity.TOP | Gravity.START;
                params.x = random.nextInt(Math.max(1, screenWidth - size));
                params.y = random.nextInt(Math.max(1, screenHeight - size));

                try {
                    windowManager.addView(bee, params);
                    bees.add(bee);
                } catch (Exception e) {
                    Log.e(TAG, "Error adding bee view", e);
                }
            }
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

    private Rect getScreenBounds() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowMetrics windowMetrics = windowManager.getCurrentWindowMetrics();
            return windowMetrics.getBounds();
        } else {
            // fallback
            return new Rect(0, 0, windowManager.getDefaultDisplay().getWidth(), windowManager.getDefaultDisplay().getHeight());
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
        for (View bee : bees) {
            try {
                windowManager.removeView(bee);
            } catch (Exception e) {
                // Ignore
            }
        }
        if (interventionOverlay != null) {
            try {
                windowManager.removeView(interventionOverlay);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}