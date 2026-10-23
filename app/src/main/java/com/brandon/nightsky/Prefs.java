package com.brandon.nightsky;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.brandon.nightsky.processing.Preset;
import com.brandon.nightsky.processing.SettingsFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Saved choices and what the app has learned about this phone's camera. They carry a version (E6): the first time
 * an updated app reads them, settings from an older version are brought up to date ({@link SettingsFile#migrate}),
 * never reset. Every key is either in {@link SettingsFile}'s lists (and so in the settings file) or there as kept
 * on the phone only.
 */
final class Prefs {
    static final int[] DURATIONS_MIN = {1, 2, 3, 5, 10, 20, 30};

    private final SharedPreferences sp;
    /** Whether this process has checked the settings' version. */
    private static boolean checked;

    Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("nightsky", Context.MODE_PRIVATE);
        synchronized (Prefs.class) {
            if (!checked) {
                checked = true;
                migrate();
            }
        }
    }

    /**
     * Settings from an older version (1.1 and before had no version; nothing stored at all is a new install) are
     * brought up to {@link SettingsFile#VERSION}. A newer version's settings (after going back to an older app)
     * are left as they are.
     */
    private void migrate() {
        int v = sp.getInt(SettingsFile.VERSION_KEY, 0);
        if (v >= SettingsFile.VERSION) return;
        Map<String, ?> all = sp.getAll();
        int from = v > 0 ? v : all.isEmpty() ? SettingsFile.VERSION : 1;
        Map<String, Object> m = new HashMap<String, Object>(all);
        List<String> notes = SettingsFile.migrate(m, from);
        SharedPreferences.Editor e = sp.edit();
        for (Map.Entry<String, Object> x : m.entrySet()) {
            Object old = all.get(x.getKey());
            if (old == null || !old.equals(x.getValue())) put(e, x.getKey(), x.getValue());
        }
        e.putInt(SettingsFile.VERSION_KEY, SettingsFile.VERSION);
        e.putString("settingsMigrated", from == SettingsFile.VERSION ? "" : "from version " + from + " on "
                + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(new java.util.Date())
                + (notes.isEmpty() ? "" : ": " + android.text.TextUtils.join("; ", notes)));
        // at once, before anything reads the new settings
        e.commit();
        if (from != SettingsFile.VERSION) android.util.Log.i("NightSky", "settings brought up from version " + from + ": " + notes);
    }

    /** Stores a value with its own type. */
    static void put(SharedPreferences.Editor e, String k, Object v) {
        if (v instanceof Boolean) e.putBoolean(k, (Boolean) v);
        else if (v instanceof Integer) e.putInt(k, (Integer) v);
        else if (v instanceof Long) e.putLong(k, (Long) v);
        else if (v instanceof Float) e.putFloat(k, (Float) v);
        else if (v instanceof String) e.putString(k, (String) v);
    }

    /** Everything stored, for the settings file. */
    Map<String, ?> all() {
        return sp.getAll();
    }

    SharedPreferences.Editor edit() {
        return sp.edit();
    }

    /** What the last update did to the settings ("" if nothing), for the next session's log. */
    String settingsMigrated() {
        return sp.getString("settingsMigrated", "");
    }

    void clearSettingsMigrated() {
        sp.edit().putString("settingsMigrated", "").apply();
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

    /** Serve the remote viewer during sessions (local network only, with a secret link). */
    boolean remoteViewer() {
        return sp.getBoolean("remoteViewer", true);
    }

    void setRemoteViewer(boolean v) {
        sp.edit().putBoolean("remoteViewer", v).apply();
    }

    /** How the stack removes satellites, planes and other outliers: a Rejection.Method name, AUTO to choose. */
    String rejection() {
        return sp.getString("rejection", "AUTO");
    }

    void setRejection(String v) {
        sp.edit().putString("rejection", v).apply();
    }

    /** Whether the home screen shows its advanced settings. */
    boolean advancedOpen() {
        return sp.getBoolean("advancedOpen", false);
    }

    void setAdvancedOpen(boolean v) {
        sp.edit().putBoolean("advancedOpen", v).apply();
    }

    int countdownSec() {
        return sp.getInt("countdownSec", 5);
    }

    // ---- presets and sites (E6) ----

    /** The preset whose settings are in place ("" when they were set by hand and match none). */
    String preset() {
        return sp.getString("preset", "");
    }

    void setPreset(String id) {
        sp.edit().putString("preset", id).apply();
    }

    /** When a setting was last changed by hand (Unix ms): a saved site's preset doesn't replace it for an hour. */
    long handPickedMs() {
        return sp.getLong("handPickedMs", 0L);
    }

    void setHandPicked(long ms) {
        sp.edit().putLong("handPickedMs", ms).apply();
    }

    /** At a saved site, use its preset by itself (and say so). */
    boolean autoSite() {
        return sp.getBoolean("autoSite", true);
    }

    void setAutoSite(boolean v) {
        sp.edit().putBoolean("autoSite", v).apply();
    }

    /** Keep capturing until the session's length of clear sky is in, for up to three times as long (A3). */
    boolean collectClear() {
        return sp.getBoolean("collectClear", false);
    }

    void setCollectClear(boolean v) {
        sp.edit().putBoolean("collectClear", v).apply();
    }

    /** Also save the cloud-blur version: the plain average, where drifting clouds turn into soft streaks (C7). */
    boolean blend() {
        return sp.getBoolean("blend", false);
    }

    void setBlend(boolean v) {
        sp.edit().putBoolean("blend", v).apply();
    }

    /** Quick developing: one stacking pass and a half-size photo (the Quick look preset). */
    boolean quick() {
        return sp.getBoolean("quick", false);
    }

    void setQuick(boolean v) {
        sp.edit().putBoolean("quick", v).apply();
    }

    /** The settings a preset holds, as they are now; its id is {@link #preset()}. */
    Preset currentPreset() {
        Preset p = new Preset();
        p.id = preset();
        p.trails = trails();
        p.durationMin = durationMin();
        p.brightness = brightness();
        p.saveDng = saveDng();
        p.rejection = rejection();
        p.keepSatellites = keepSatellites();
        p.collectClear = collectClear();
        p.blend = blend();
        p.quick = quick();
        return p;
    }

    /** Puts a preset's settings in place, all at once. */
    void applyPreset(Preset p) {
        sp.edit().putBoolean("trails", p.trails).putInt("durationMin", p.durationMin).putFloat("brightness", p.brightness)
                .putBoolean("saveDng", p.saveDng).putString("rejection", p.rejection).putBoolean("keepSatellites", p.keepSatellites)
                .putBoolean("collectClear", p.collectClear).putBoolean("blend", p.blend).putBoolean("quick", p.quick)
                .putString("preset", p.id).apply();
    }

    /** Brandon's own presets and the saved sites, as JSON lists ("" for none). */
    String userPresetsJson() {
        return sp.getString(SettingsFile.PRESETS_KEY, "");
    }

    String sitesJson() {
        return sp.getString(SettingsFile.SITES_KEY, "");
    }

    void setUserPresetsJson(String json) {
        sp.edit().putString(SettingsFile.PRESETS_KEY, json).apply();
    }

    /** Saved at once: sites are written from the session thread and the screen (see Sites). */
    void setSitesJson(String json) {
        sp.edit().putString(SettingsFile.SITES_KEY, json).commit();
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

    /** Run the pre-flight check (a test frame read for smudges, obstructions, clouds, the Moon) before starting. */
    boolean preflight() {
        return sp.getBoolean("preflight", true);
    }

    void setPreflight(boolean v) {
        sp.edit().putBoolean("preflight", v).apply();
    }

    /** Download satellites' orbits from CelesTrak (to name streaks and warn of passes); nothing is sent but the request. */
    boolean satelliteData() {
        return sp.getBoolean("satelliteData", true);
    }

    void setSatelliteData(boolean v) {
        sp.edit().putBoolean("satelliteData", v).apply();
    }

    /** Keep satellite streaks (and meteors) in the picture instead of taking them out; bright passes are counted down. */
    boolean keepSatellites() {
        return sp.getBoolean("keepSatellites", false);
    }

    void setKeepSatellites(boolean v) {
        sp.edit().putBoolean("keepSatellites", v).apply();
    }

    /** On the result: dashed satellite tracks, and names of stars, planets and constellations. */
    boolean showTracks() {
        return sp.getBoolean("showTracks", true);
    }

    void setShowTracks(boolean v) {
        sp.edit().putBoolean("showTracks", v).apply();
    }

    boolean showLabels() {
        return sp.getBoolean("showLabels", false);
    }

    void setShowLabels(boolean v) {
        sp.edit().putBoolean("showLabels", v).apply();
    }

    /** Tonight's cloud forecast from Open-Meteo (the place rounded to about 10 km is sent with the request). */
    boolean forecast() {
        return sp.getBoolean("forecast", true);
    }

    void setForecast(boolean v) {
        sp.edit().putBoolean("forecast", v).apply();
    }

    /** Beeps and voice during scheduled sessions, for new plans (off: someone may be asleep nearby). */
    boolean planSounds() {
        return sp.getBoolean("planSounds", false);
    }

    void setPlanSounds(boolean v) {
        sp.edit().putBoolean("planSounds", v).apply();
    }

    /** Scheduled sessions stop, and don't start, below this battery level (%), for new plans. */
    int planMinBattery() {
        return sp.getInt("planMinBattery", 20);
    }

    void setPlanMinBattery(int v) {
        sp.edit().putInt("planMinBattery", v).apply();
    }

    /** Whether the app has asked once to be left out of battery optimisation (for night plans). */
    boolean askedBatteryExemption() {
        return sp.getBoolean("askedBatteryExemption", false);
    }

    void setAskedBatteryExemption() {
        sp.edit().putBoolean("askedBatteryExemption", true).apply();
    }

    /** Whether the app has asked for the (approximate) location once already. */
    boolean askedLocation() {
        return sp.getBoolean("askedLocation", false);
    }

    void setAskedLocation() {
        sp.edit().putBoolean("askedLocation", true).apply();
    }

    // ---- what the app learns from good sessions ----

    /** Halo ratios this camera's lens gave in good sessions (newest last), at most this many kept. */
    private static final int HALO_KEEP = 12, ZP_KEEP = 20;

    /**
     * This camera's clean-lens halo ratio: the lower quartile of the good sessions' ratios, so the odd session
     * with a slightly smudged lens doesn't raise it. NaN until there is one.
     */
    double haloBaseline(String cameraId) {
        double[] v = list("halo|" + cameraKey(cameraId));
        if (v.length == 0) return Double.NaN;
        java.util.Arrays.sort(v);
        return v[(v.length - 1) / 4];
    }

    void learnHalo(String cameraId, double ratio) {
        if (Double.isNaN(ratio) || ratio < 0) return;
        append("halo|" + cameraKey(cameraId), ratio, HALO_KEEP);
    }

    /**
     * This camera's zero point on a clear night (per second at ISO 100): the clearest of the sessions measured,
     * the second clearest once there are three, so one odd reading can't make every night look hazy. NaN until
     * there is one.
     */
    double clearZeroPoint(String cameraId) {
        double[] v = list("zp|" + cameraKey(cameraId));
        if (v.length == 0) return Double.NaN;
        java.util.Arrays.sort(v);
        return v[v.length >= 3 ? v.length - 2 : v.length - 1];
    }

    void learnZeroPoint(String cameraId, double zp) {
        if (Double.isNaN(zp)) return;
        append("zp|" + cameraKey(cameraId), zp, ZP_KEEP);
    }

    /** Battery used per hour of capture (%), learned from sessions; NaN until measured. */
    double drainPctPerHour() {
        float v = sp.getFloat("drainPctPerHour", Float.NaN);
        return v;
    }

    void learnDrain(double pctPerHour, double weight) {
        if (Double.isNaN(pctPerHour) || pctPerHour <= 0 || pctPerHour > 200) return;
        double old = drainPctPerHour();
        double w = Math.max(0.05, Math.min(1, weight));
        double v = Double.isNaN(old) ? pctPerHour : old + w * (pctPerHour - old);
        sp.edit().putFloat("drainPctPerHour", (float) v).apply();
    }

    private double[] list(String key) {
        String s = sp.getString(key, "");
        if (s.isEmpty()) return new double[0];
        String[] p = s.split(",");
        double[] v = new double[p.length];
        int n = 0;
        for (String x : p) {
            try {
                v[n++] = Double.parseDouble(x);
            } catch (NumberFormatException ignored) {
            }
        }
        return java.util.Arrays.copyOf(v, n);
    }

    private void append(String key, double value, int keep) {
        double[] v = list(key);
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, v.length - keep + 1); i < v.length; i++) sb.append(v[i]).append(',');
        sb.append(String.format(java.util.Locale.US, "%.5f", value));
        sp.edit().putString(key, sb.toString()).apply();
    }

    // ---- the last place known (for nights without a signal) ----

    Locator.Place savedPlace() {
        if (!sp.contains("placeLat")) return null;
        Locator.Place p = new Locator.Place();
        p.latDeg = sp.getFloat("placeLat", 0f);
        p.lonDeg = sp.getFloat("placeLon", 0f);
        p.altM = sp.getFloat("placeAlt", Float.NaN);
        p.accuracyM = sp.getFloat("placeAccuracy", Float.NaN);
        p.timeMs = sp.getLong("placeTime", 0L);
        p.source = "saved";
        return p;
    }

    void savePlace(Locator.Place p) {
        if (p == null) return;
        sp.edit().putFloat("placeLat", (float) p.latDeg).putFloat("placeLon", (float) p.lonDeg)
                .putFloat("placeAlt", (float) p.altM).putFloat("placeAccuracy", (float) p.accuracyM).putLong("placeTime", p.timeMs).apply();
    }

    /** Last session that was developed, for the home screen. */
    String lastResultUri() {
        return sp.getString("lastResult", null);
    }

    void setLastResultUri(String uri) {
        sp.edit().putString("lastResult", uri).apply();
    }

    /** The last photo's small picture (a file in the thumbnails folder, "" if none) and when it was taken (Unix ms). */
    String lastThumb() {
        return sp.getString("lastThumb", "");
    }

    long lastResultMs() {
        return sp.getLong("lastResultMs", 0L);
    }

    void setLastThumb(String name, long takenMs) {
        sp.edit().putString("lastThumb", name).putLong("lastResultMs", takenMs).apply();
    }

    /**
     * Added to sky readings: a real meter's (SQM-L) reading minus this app's for the same sky, from a check against
     * one (0: never checked).
     */
    double sqmOffset() {
        return sp.getFloat("sqmOffset", 0f);
    }

    void setSqmOffset(double v) {
        sp.edit().putFloat("sqmOffset", (float) v).apply();
    }

    /** The Quick Settings tile asks to unlock the phone first, instead of opening over the lock screen. */
    boolean tileUnlocks() {
        return sp.getBoolean("tileUnlocks", false);
    }

    void setTileUnlocks(boolean v) {
        sp.edit().putBoolean("tileUnlocks", v).apply();
    }
}
