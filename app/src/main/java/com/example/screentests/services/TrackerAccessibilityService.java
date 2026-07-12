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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import android.hardware.HardwareBuffer;
import android.util.Base64;

public class TrackerAccessibilityService extends AccessibilityService {

    private static final String TAG = "TrackerService";
    private static TrackerAccessibilityService instance;

    // AI-changed: screenshot post-processing (JPEG compress + Base64 of a full screen) used to
    // run on the main thread via getMainExecutor() — a guaranteed jank spike of tens to hundreds
    // of milliseconds every time a tracking screenshot was taken. It now runs here instead.
    private final ExecutorService screenshotExecutor = Executors.newSingleThreadExecutor();

    // Initializes service, telling android exactly what we want to track
    // (for example - TYPE_WINDOW_ST-usw.: service listens to changed states/apps)
    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "Service connected");

        // AI-changed: init the engine here too. After process death the system restarts this
        // service without MainActivity ever running; the engine then had no context, so score
        // ticks and app tracking silently did nothing until the user opened the app manually.
        ProductivityEngine.getInstance().init(getApplicationContext());

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags = 0; // before: AccessibilityServiceInfo.FLAG_DEFAULT. Now using 0.
        this.setServiceInfo(info);

        // AI-added: warm the soft-keyboard package cache so IME window events are cheap to filter.
        ImeRegistry.refresh(this);
    }

    // triggers tracking logic
    // starts the OverlayManager, if app has changed and is not systemUI or BeeProductive.
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (event.getPackageName() != null) {
                String packageName = event.getPackageName().toString();
                Log.d(TAG, "App changed: " + packageName);
                
                // Exclude system UI, self, and soft keyboards.
                // AI-changed: opening the soft keyboard (e.g. Samsung's honeyboard on Galaxy
                // devices) fires a window-state event with the IME's package name. Treating it
                // as an app switch popped the categorization overlay over the intervention chat
                // and stole focus, closing the text box as soon as the user started typing.
                if (!packageName.equals("com.android.systemui") && !packageName.equals(getPackageName())
                        && !ImeRegistry.isImePackage(this, packageName)) {
                    ProductivityEngine engine = ProductivityEngine.getInstance();
                    engine.onAppChanged(packageName);
                    // AI-changed: this used to call startForegroundService() on EVERY window
                    // change — a system-service round trip plus a notification rebuild in
                    // onStartCommand on each app switch. Only kick the service when it isn't
                    // already running, and never while the master switch is off.
                    if (engine.isEnabled() && !OverlayManager.isRunning()) {
                        startOverlayService();
                    }
                }
            }
        }
    }

    // starts the OverlayManager if called
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
        screenshotExecutor.shutdown(); // AI-added: don't leave the worker thread behind
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
            // AI-changed: callback now runs on screenshotExecutor instead of the main thread —
            // the JPEG compress + Base64 encode below is far too heavy for the UI thread.
            try {
            takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(@NonNull ScreenshotResult screenshotResult) { // convert buffer to Bitmap, compress to JPEG, encode to base64 Str
                    HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
                    try {
                        Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, screenshotResult.getColorSpace());
                        if (bitmap != null) {
                            ByteArrayOutputStream baos = new ByteArrayOutputStream();
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
                            byte[] imageBytes = baos.toByteArray();
                            String base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                            callback.onSuccess(base64);
                        } else {
                            callback.onFailure("Failed to wrap hardware buffer to Bitmap");
                        }
                    } finally {
                        // AI-changed: the receiver of a ScreenshotResult must close the
                        // HardwareBuffer; previously every screenshot leaked one graphics
                        // buffer (native memory the GC never sees). The wrapped Bitmap keeps
                        // its own reference, so closing here is safe.
                        buffer.close();
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    callback.onFailure("Screenshot failed with code: " + errorCode);
                }
            });
            } catch (Exception e) {
                // AI-added: takeScreenshot() throws (SecurityException & co.) instead of calling
                // onFailure when the service lacks the capability or is being torn down. An
                // uncaught throw here killed the caller silently — the Queen then waited on
                // evidence that never arrived. Route it into the normal failure path so the
                // stored-screenshot fallback still runs.
                Log.e(TAG, "takeScreenshot threw", e);
                callback.onFailure("Screenshot call failed: " + e.getMessage());
            }
        } else {
            callback.onFailure("Screenshots via Accessibility Service require Android 11+");
        }
    }

    // kicks the user out of an app by simulating the HOME button
    public void enforceLockout() {
        performGlobalAction(GLOBAL_ACTION_HOME);
    }

    public interface ScreenshotCallback {
        void onSuccess(String base64Image);
        void onFailure(String error);
    }
}