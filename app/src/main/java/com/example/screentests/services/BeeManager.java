package com.example.screentests.services;

import android.content.Context;

import android.graphics.Rect;
import android.os.Build;
import android.util.Log;

import android.view.WindowManager;
import android.view.WindowMetrics;


import java.util.ArrayList;


public class BeeManager {
    public enum Dimension { WIDTH, HEIGHT }

    private static final String TAG = "BeeManager";
    private final Context context;
    private final WindowManager windowManager;
    private final ArrayList<SingleBee> bees = new ArrayList<>();
    private final int maxVisualOverhead = 100; //determines how far off the screen bees can go

    public BeeManager(Context context, WindowManager windowManager) {
        this.context = context;
        this.windowManager = windowManager;//dont need it rn but hey, you have what you have
    }

    public int getWindowSize(Dimension dimension) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Rect bounds = windowManager.getCurrentWindowMetrics().getBounds();
            return dimension == Dimension.WIDTH ? bounds.width() : bounds.height();
        }
        return -1; //fail case because the compiler is a dipshit
    }

    public void initBeeSwarm(int intensity) {
        Log.d(TAG, "Updating bee swarm to level/with intensity: " + intensity);
        for (int i = 0; i < intensity; i++) {
            SingleBee curBee = new SingleBee(getWindowSize(Dimension.WIDTH), getWindowSize(Dimension.HEIGHT), 2 * maxVisualOverhead);//i double MaxVisualOverhead to account for both screen ends
            bees.add(curBee);
        }
        for (SingleBee bee : bees) {
            bee.updateExistingBees(bees);
        }
    }

    public void updateBeeVisuals(SingleBee bee) {
        //TODO:update visuals
    }

    public void removeAllBees() {
        for (SingleBee bee : bees) {
            try {
                bees.remove(bee);
            } catch (Exception e) {
                //Nothing because ts should work
            }
        }
        bees.clear();
    }
}
