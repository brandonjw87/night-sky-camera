package com.brandon.nightsky;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

/**
 * Watches gravity and the gyroscope: is the phone lying screen-down (back camera facing up), how long has
 * it been perfectly still, and how much did it move during a given exposure.
 * <p>
 * Times are on the {@link SystemClock#elapsedRealtimeNanos()} clock, the same one camera timestamps use
 * when the camera reports a realtime timestamp source.
 */
final class MotionMonitor implements SensorEventListener {
    /** Rotation rate (rad/s) above which the phone counts as moving. A phone on the ground reads ~0.002. */
    static final float QUIET_RAD_S = 0.02f;
    /** Gravity's z component (m/s^2) below which the phone is screen-down: within ~30 degrees of flat. */
    static final float FACE_DOWN_Z = -8.5f;

    private static final int RING = 16384;

    private final SensorManager sm;
    private final Sensor gyro, gravity, accel;
    private HandlerThread thread;

    private final long[] gt = new long[RING];
    private final float[] gm = new float[RING];
    private int gHead, gCount;

    private final float[] g = new float[3];
    private volatile boolean haveGravity;
    private volatile long lastDisturbNs = Long.MAX_VALUE;
    /** Last rotation or knock, whichever way the phone faces. */
    private volatile long lastMotionNs = Long.MAX_VALUE;
    private volatile float lastRate;

    MotionMonitor(Context c) {
        sm = (SensorManager) c.getSystemService(Context.SENSOR_SERVICE);
        gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        gravity = sm.getDefaultSensor(Sensor.TYPE_GRAVITY);
        accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    boolean hasGyro() {
        return gyro != null;
    }

    void start() {
        if (thread != null) return;
        thread = new HandlerThread("motion");
        thread.start();
        Handler h = new Handler(thread.getLooper());
        lastDisturbNs = SystemClock.elapsedRealtimeNanos();
        lastMotionNs = lastDisturbNs;
        if (gyro != null) sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME, h);
        if (gravity != null) sm.registerListener(this, gravity, SensorManager.SENSOR_DELAY_GAME, h);
        // the accelerometer also catches knocks the gyroscope can miss
        if (accel != null) sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, h);
    }

    void stop() {
        sm.unregisterListener(this);
        if (thread != null) {
            thread.quitSafely();
            thread = null;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        long t = e.timestamp;
        if (type == Sensor.TYPE_GYROSCOPE) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            float m = (float) Math.sqrt(x * x + y * y + z * z);
            synchronized (gt) {
                gt[gHead] = t;
                gm[gHead] = m;
                gHead = (gHead + 1) % RING;
                if (gCount < RING) gCount++;
            }
            lastRate = m;
            if (m > QUIET_RAD_S) {
                lastDisturbNs = t;
                lastMotionNs = t;
            }
        } else if (type == Sensor.TYPE_GRAVITY) {
            g[0] = e.values[0];
            g[1] = e.values[1];
            g[2] = e.values[2];
            haveGravity = true;
            if (g[2] > FACE_DOWN_Z) lastDisturbNs = t;
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            float x = e.values[0], y = e.values[1], z = e.values[2];
            if (gravity == null) {
                // no fused gravity sensor: low-pass the accelerometer
                float a = haveGravity ? 0.1f : 1f;
                g[0] += a * (x - g[0]);
                g[1] += a * (y - g[1]);
                g[2] += a * (z - g[2]);
                haveGravity = true;
                if (g[2] > FACE_DOWN_Z) lastDisturbNs = t;
            }
            if (haveGravity) {
                float dx = x - g[0], dy = y - g[1], dz = z - g[2];
                // a knock or a footstep on a wooden deck
                if (dx * dx + dy * dy + dz * dz > 0.35f * 0.35f) {
                    lastDisturbNs = t;
                    lastMotionNs = t;
                }
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    boolean known() {
        return haveGravity;
    }

    /** Screen facing the ground, back camera facing the sky. */
    boolean faceDown() {
        return haveGravity && g[2] < FACE_DOWN_Z;
    }

    /** How long the phone has been screen-down and motionless, in ns (0 if it is not screen-down). */
    long stillNs() {
        if (!faceDown()) return 0;
        long now = SystemClock.elapsedRealtimeNanos();
        long d = lastDisturbNs;
        return d == Long.MAX_VALUE ? 0 : Math.max(0, now - d);
    }

    /** How long the phone has been motionless, in ns, whichever way it faces (for a start from the viewer). */
    long quietNs() {
        long d = lastMotionNs;
        return d == Long.MAX_VALUE ? 0 : Math.max(0, SystemClock.elapsedRealtimeNanos() - d);
    }

    /** Latest rotation rate, rad/s. */
    float rate() {
        return lastRate;
    }

    /** Angle between the back camera's axis and straight up, degrees. */
    float tiltDeg() {
        if (!haveGravity) return Float.NaN;
        double n = Math.sqrt(g[0] * g[0] + g[1] * g[1] + g[2] * g[2]);
        if (n < 1e-3) return Float.NaN;
        return (float) Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, -g[2] / n))));
    }

    /** Largest rotation rate between t0 and t1 (ns, elapsed-realtime clock); -1 if no samples cover it. */
    float maxRate(long t0, long t1) {
        float m = -1f;
        synchronized (gt) {
            int i = (gHead - 1 + RING) % RING;
            for (int k = 0; k < gCount; k++) {
                long t = gt[i];
                if (t < t0) break;
                if (t <= t1 && gm[i] > m) m = gm[i];
                i = (i - 1 + RING) % RING;
            }
        }
        return m;
    }
}
