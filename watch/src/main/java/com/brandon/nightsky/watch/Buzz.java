package com.brandon.nightsky.watch;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** The watch's buzzes (2.5): what the phone's session did, felt on the wrist without looking. */
final class Buzz {
    private Buzz() {}

    /** Off in the tests (no motor there): only counted. */
    static volatile boolean off;
    static volatile int count;
    static volatile String last = "";

    /** The session went from {@code from} to {@code to} (the phone's states: arming, capturing, developing, done...). */
    static void on(Context c, String from, String to) {
        if (from == null || from.isEmpty() || to.equals(from)) return;
        if ("capturing".equals(to) && !"paused".equals(from)) {
            // the first frame: one short tap
            buzz(c, "capturing", new long[]{0, 60});
        } else if ("done".equals(to)) {
            // the photo is ready: long, short, long
            buzz(c, "done", new long[]{0, 400, 150, 120, 150, 400});
        } else if ("failed".equals(to)) {
            buzz(c, "failed", new long[]{0, 150, 120, 150});
        }
    }

    /** Paired with the phone. */
    static void paired(Context c) {
        buzz(c, "paired", new long[]{0, 80, 100, 80});
    }

    /** A tap answered (a command sent). */
    static void tick(Context c) {
        buzz(c, "tick", new long[]{0, 25});
    }

    /** The Sky Pointer closing in on what it's finding: a Geiger counter's click. */
    static void click(Context c) {
        buzz(c, "click", new long[]{0, 12});
    }

    /** The Sky Pointer is on it. */
    static void found(Context c) {
        buzz(c, "found", new long[]{0, 180, 90, 180});
    }

    /**
     * A station's crossing of the Moon counting down (3.0): a minute to go (long), ten seconds (two), three, two, one
     * (taps) and now (long), felt without looking so the phone stays still.
     */
    static void transit(Context c, int secondsLeft) {
        if (secondsLeft >= 60) buzz(c, "transit-60", new long[]{0, 350});
        else if (secondsLeft >= 10) buzz(c, "transit-10", new long[]{0, 150, 120, 150});
        else if (secondsLeft > 0) buzz(c, "transit-" + secondsLeft, new long[]{0, 45});
        else buzz(c, "transit-now", new long[]{0, 600});
    }

    private static void buzz(Context c, String what, long[] pattern) {
        count++;
        last = what;
        if (off) return;
        try {
            Vibrator v;
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                v = vm != null ? vm.getDefaultVibrator() : null;
            } else {
                v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
            }
            if (v != null && v.hasVibrator()) v.vibrate(VibrationEffect.createWaveform(pattern, -1));
        } catch (RuntimeException ignored) {
            // no motor, or not allowed: never mind
        }
    }
}
