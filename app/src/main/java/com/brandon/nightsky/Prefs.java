package com.brandon.nightsky;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

/** Saved choices and what the app has learned about this phone's camera. */
final class Prefs {
    static final int[] DURATIONS_MIN = {1, 3, 5, 10, 20, 30};

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("nightsky", Context.MODE_PRIVATE);
    }

    boolean trails() {
        return sp.getBoolean("trails", false);
    }

    void setTrails(boolean v) {
        sp.edit().putBoolean("trails", v).apply();
    }

    int durationMin() {
        return sp.getInt("durationMin", 5);
    }

    void setDurationMin(int v) {
        sp.edit().putInt("durationMin", v).apply();
    }

    /** Picture brightness, 0..1 (0.5 = natural dark sky). */
    float brightness() {
        return sp.getFloat("brightness", 0.5f);
    }

    void setBrightness(float v) {
        sp.edit().putFloat("brightness", v).apply();
    }

    boolean sounds() {
        return sp.getBoolean("sounds", true);
    }

    void setSounds(boolean v) {
        sp.edit().putBoolean("sounds", v).apply();
    }

    boolean saveDng() {
        return sp.getBoolean("saveDng", true);
    }

    void setSaveDng(boolean v) {
        sp.edit().putBoolean("saveDng", v).apply();
    }

    int countdownSec() {
        return sp.getInt("countdownSec", 5);
    }

    /** Key for facts about one camera under the current system build (a system update can change limits). */
    static String cameraKey(String cameraId) {
        return cameraId + "|" + Build.FINGERPRINT;
    }

    /** Longest exposure the camera has been seen to deliver in full, or 0 if never checked. */
    long verifiedExposureNs(String cameraId) {
        return sp.getLong("verifiedExp|" + cameraKey(cameraId), 0L);
    }

    /** Longest exposure the camera has been seen to cut short (it delivers less than this), or 0. */
    long clampedExposureNs(String cameraId) {
        return sp.getLong("clampedExp|" + cameraKey(cameraId), 0L);
    }

    void noteExposureDelivered(String cameraId, long requestedNs) {
        String k = "verifiedExp|" + cameraKey(cameraId);
        if (requestedNs > sp.getLong(k, 0L)) sp.edit().putLong(k, requestedNs).apply();
    }

    void noteExposureClamped(String cameraId, long requestedNs, long deliveredNs) {
        sp.edit().putLong("clampedExp|" + cameraKey(cameraId), requestedNs)
                .putLong("clampDelivered|" + cameraKey(cameraId), deliveredNs).apply();
    }

    long clampDeliveredNs(String cameraId) {
        return sp.getLong("clampDelivered|" + cameraKey(cameraId), 0L);
    }

    /** Last session that was developed, for the home screen. */
    String lastResultUri() {
        return sp.getString("lastResult", null);
    }

    void setLastResultUri(String uri) {
        sp.edit().putString("lastResult", uri).apply();
    }
}
