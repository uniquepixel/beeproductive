package com.example.screentests.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.Display;
import androidx.annotation.NonNull;

import androidx.core.content.ContextCompat;

import com.example.screentests.engine.ProductivityEngine;

import java.io.ByteArrayOutputStream;
import android.util.Base64;

public class TrackerAccessibilityService extends AccessibilityService {

    private static final String TAG = "TrackerService";
    private static TrackerAccessibilityService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Service connected");
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = 0; // AccessibilityServiceInfo.FLAG_DEFAULT does not exist. Using 0 for now.
        this.setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) {
                String packageName = event.getPackageName().toString();
                Log.d(TAG, "App changed: " + packageName);
                
                // Exclude system UI or self
                if (!packageName.equals("com.android.systemui") && !packageName.equals(getPackageName())) {
                    ProductivityEngine.getInstance().onAppChanged(packageName);
                    startOverlayService();
                }
            }
        }
    }

    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayManager.class);
        ContextCompat.startForegroundService(this, intent);
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
    }

    public static TrackerAccessibilityService getInstance() {
        return instance;
    }

    /**
     * Attempts to take a screenshot and returns Base64 encoded JPEG.
     * Only works on Android 11 (API 30+).
     */
    public void takeScreenshotBase64(ScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(@NonNull ScreenshotResult screenshotResult) {
                    Bitmap bitmap = Bitmap.wrapHardwareBuffer(screenshotResult.getHardwareBuffer(), screenshotResult.getColorSpace());
                    if (bitmap != null) {
                        ByteArrayOutputStream baos = new ByteArrayOutputStream();
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                        byte[] imageBytes = baos.toByteArray();
                        String base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                        callback.onSuccess(base64);
                    } else {
                        callback.onFailure("Failed to wrap hardware buffer to Bitmap");
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    callback.onFailure("Screenshot failed with code: " + errorCode);
                }
            });
        } else {
            callback.onFailure("Screenshots via Accessibility Service require Android 11+");
        }
    }

    // Forcefully kicks the user out of the current app by simulating the HOME button
    public void enforceLockout() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public interface ScreenshotCallback {
        void onSuccess(String base64Image);
        void onFailure(String error);
    }
}