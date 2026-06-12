package com.example.screentests.services;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import com.example.screentests.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class BeeManager {
    public enum dim {WIDTH, HEIGHT}

    private static final String TAG = "BeeManager";
    private final Context context;
    private final WindowManager windowManager;
    private final ArrayList<SingleBee> bees = new ArrayList<>();
    private final Map<SingleBee, View> beeViews = new HashMap<>();
    private final int maxVisualOverhead = 100;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private boolean isSimulationRunning = false;

    public BeeManager(Context context, WindowManager windowManager, int score) {
        this.context = context;
        this.windowManager = windowManager;
    }

    public int getWindowSize(dim dimension) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return dimension == dim.WIDTH ? bounds.width() : bounds.height();
        } else {
            return dimension == dim.WIDTH ? windowManager.getDefaultDisplay().getWidth() : windowManager.getDefaultDisplay().getHeight();
        }
    }

    public void initBeeSwarm(int intensity) {
        Log.d(TAG, "Initializing bee swarm with intensity: " + intensity);
        removeAllBees(); 
        
        int width = getWindowSize(dim.WIDTH);
        int height = getWindowSize(dim.HEIGHT);

        for (int i = 0; i < intensity; i++) {
            SingleBee curBee = new SingleBee(width, height, maxVisualOverhead);
            bees.add(curBee);
        }
        
        for (SingleBee bee : bees) {
            bee.updateExistingBees(new ArrayList<>(bees));
        }
    }

    private void updateBeeVisuals(SingleBee bee) {
        mainHandler.post(() -> {
            View view = beeViews.get(bee);
            int x = bee.getPosition(dim.WIDTH);
            int y = bee.getPosition(dim.HEIGHT);

            if (view == null) {
                ImageView imageView = new ImageView(context);
                imageView.setImageResource(R.mipmap.ic_launcher_round);
                
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                        100, 100, 
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = x;
                params.y = y;

                try {
                    windowManager.addView(imageView, params);
                    beeViews.put(bee, imageView);
                } catch (Exception e) {
                    Log.e(TAG, "Error adding bee view", e);
                }
            } else {
                WindowManager.LayoutParams params = (WindowManager.LayoutParams) view.getLayoutParams();
                params.x = x;
                params.y = y;
                try {
                    windowManager.updateViewLayout(view, params);
                } catch (Exception e) {
                    Log.e(TAG, "Error updating bee layout", e);
                }
            }
        });
    }

    public void startSimulation() {
        if (isSimulationRunning) return;
        isSimulationRunning = true;

        new Thread(() -> {
            Log.d(TAG, "Simulation started");
            long startTime = System.currentTimeMillis();
            // Simulation runs for 10 seconds or until stopped
            while (isSimulationRunning && (System.currentTimeMillis() - startTime < 10000)) {
                for (SingleBee bee : bees) {
                    bee.timeStep();
                    updateBeeVisuals(bee);
                }
                try {
                    Thread.sleep(32); // roughly 30 FPS
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            isSimulationRunning = false;
        }).start();
    }

    public void removeAllBees() {
        isSimulationRunning = false;
        mainHandler.post(() -> {
            for (View view : beeViews.values()) {
                try {
                    windowManager.removeView(view);
                } catch (Exception e) {
                    Log.e(TAG, "Error removing bee view", e);
                }
            }
            beeViews.clear();
            bees.clear();
        });
    }
}
