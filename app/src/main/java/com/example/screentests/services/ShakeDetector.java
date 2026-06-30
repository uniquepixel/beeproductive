//This Class is mostly AI generated.
package com.example.screentests.services;


import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Lightweight accelerometer-based shake detector. Used by {@link BeeManager} so a device
 * shake can scatter the bee swarm at any severity stage (no extra manifest permission is
 * required for the accelerometer).
 *
 * Self-contained: {@link #start()} registers the sensor listener, {@link #stop()} releases it.
 * Fires {@link Listener#onShake()} (gated by a small cooldown) whenever the measured g-force
 * crosses {@link #SHAKE_THRESHOLD_G}.
 */
public class ShakeDetector implements SensorEventListener {

    public interface Listener {
        void onShake();
    }

    // Force (in g) above which we count a sample as "shaking".
    private static final float SHAKE_THRESHOLD_G = 2.3f;
    // Ignore repeat shakes that arrive faster than this so one shake fires once.
    private static final long SHAKE_COOLDOWN_MS = 600;

    private final SensorManager sensorManager;
    private final Sensor accelerometer;
    private final Listener listener;

    private long lastShakeTime = 0;
    private boolean registered = false;

    public ShakeDetector(Context context, Listener listener) {
        this.listener = listener;
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.accelerometer = sensorManager != null
                ? sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
    }

    public void start() {
        if (registered || sensorManager == null || accelerometer == null) return;
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
        registered = true;
    }

    public void stop() {
        if (!registered || sensorManager == null) return;
        sensorManager.unregisterListener(this);
        registered = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float gX = event.values[0] / SensorManager.GRAVITY_EARTH;
        float gY = event.values[1] / SensorManager.GRAVITY_EARTH;
        float gZ = event.values[2] / SensorManager.GRAVITY_EARTH;
        //how strong is the acceleration
        double gForce = Math.sqrt(gX * gX + gY * gY + gZ * gZ);

        if (gForce > SHAKE_THRESHOLD_G) {
            long now = System.currentTimeMillis();
            if (now - lastShakeTime < SHAKE_COOLDOWN_MS) return;
            lastShakeTime = now;
            if (listener != null) listener.onShake();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // No-op.
    }
}
