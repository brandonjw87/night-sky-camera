package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * The settings' version and the settings file (E6). Every setting, Brandon's own presets and his saved sites go in
 * one JSON file, to keep a copy or to carry them over to a new phone; and the settings carry a version number, so
 * an update that changes what a setting means moves the old value over ({@link #migrate}) instead of resetting it.
 * What the app has learned about this phone's camera goes in the file too: it only applies on the same phone and
 * system build, where it saves relearning after a reinstall.
 */
public final class SettingsFile {
    /** The settings' version: raised whenever a setting changes meaning, with a step in {@link #migrate}. */
    public static final int VERSION = 2;
    public static final String APP = "Night Sky";
    /** Keys whose values are presets and sites (JSON in the settings), written as lists of their own. */
    public static final String PRESETS_KEY = "userPresets", SITES_KEY = "sites", VERSION_KEY = "settingsVersion";

    /** The settings Brandon chooses, and their types: b(oolean), i(nt), l(ong), f(loat), s(tring). */
    static final String[][] SETTINGS = {
            {"trails", "b"}, {"durationMin", "i"}, {"brightness", "f"}, {"sounds", "b"}, {"saveDng", "b"},
            {"remoteViewer", "b"}, {"rejection", "s"}, {"advancedOpen", "b"}, {"countdownSec", "i"}, {"preflight", "b"},
            {"satelliteData", "b"}, {"keepSatellites", "b"}, {"showTracks", "b"}, {"showLabels", "b"}, {"forecast", "b"},
            {"planSounds", "b"}, {"planMinBattery", "i"}, {"tileUnlocks", "b"}, {"sqmOffset", "f"}, {"preset", "s"},
            {"autoSite", "b"}, {"collectClear", "b"}, {"blend", "b"}, {"quick", "b"}, {"focusAssist", "b"},
            {"keepFramesGb", "i"}, {"remoteKey", "b"},
    };
    /** What the app learned about the camera: keys starting with these (camera and system build follow), or equal. */
    static final String[][] LEARNED = {
            {"verifiedExp|", "l"}, {"clampedExp|", "l"}, {"clampDelivered|", "l"}, {"halo|", "s"}, {"zp|", "s"},
            {"drainPctPerHour", "f"}, {"focusModel|", "s"},
    };
    /** Kept on the phone only: what it's doing or last did, not a setting. */
    static final String[] TRANSIENT = {
            "askedBatteryExemption", "askedLocation", "lastResult", "lastResultMs", "lastThumb", "placeLat", "placeLon",
            "placeAlt", "placeAccuracy", "placeTime", "handPickedMs", "settingsMigrated", VERSION_KEY, PRESETS_KEY, SITES_KEY,
    };

    private SettingsFile() {}

    /** The type letter of a setting (or learned value) key, or null if it isn't one. */
    public static String typeOf(String key) {
        for (String[] s : SETTINGS) if (s[0].equals(key)) return s[1];
        return learnedType(key);
    }

    static String learnedType(String key) {
        for (String[] s : LEARNED) {
            if (s[0].endsWith("|") ? key.startsWith(s[0]) && key.length() > s[0].length() : s[0].equals(key)) return s[1];
        }
        return null;
    }

    static boolean isTransient(String key) {
        for (String t : TRANSIENT) if (t.equals(key)) return true;
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // Versions

    /**
     * Brings settings saved by an older version ({@code from}) up to {@link #VERSION}, in place; returns what changed,
     * a line each. It only adds and converts: no setting is ever dropped or put back to its default.
     */
    public static List<String> migrate(Map<String, Object> m, int from) {
        List<String> notes = new ArrayList<String>();
        if (from < 2) {
            // 1.2 brings presets: the settings in place are the preset they match, if one does
            if (!m.containsKey("preset")) {
                Preset cur = fromSettings(m);
                Preset match = null;
                for (Preset p : Preset.builtIns()) if (p.sameSettings(cur)) match = p;
                m.put("preset", match != null ? match.id : "");
                notes.add(match != null ? "the settings are the " + match.name + " preset's"
                        : "the settings are kept as they are (no preset has them, so they show as your own)");
            }
        }
        return notes;
    }

    /** The settings a preset holds, as they are in {@code m} (the defaults where missing). */
    public static Preset fromSettings(Map<String, ?> m) {
        Preset p = new Preset();
        p.trails = bool(m, "trails", false);
        p.durationMin = integer(m, "durationMin", 5);
        Object b = m.get("brightness");
        p.brightness = b instanceof Number ? ((Number) b).floatValue() : 0.5f;
        p.saveDng = bool(m, "saveDng", true);
        Object r = m.get("rejection");
        p.rejection = r instanceof String ? (String) r : "AUTO";
        p.keepSatellites = bool(m, "keepSatellites", false);
        p.collectClear = bool(m, "collectClear", false);
        p.blend = bool(m, "blend", false);
        p.quick = bool(m, "quick", false);
        return p;
    }

    private static boolean bool(Map<String, ?> m, String k, boolean def) {
        Object v = m.get(k);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    private static int integer(Map<String, ?> m, String k, int def) {
        Object v = m.get(k);
        return v instanceof Number ? ((Number) v).intValue() : def;
    }

    // ------------------------------------------------------------------------------------------------
    // The file

    /**
     * The settings file for everything in {@code all} (the settings as stored: Boolean, Integer, Long, Float or String
     * values), with the presets and sites stored under {@link #PRESETS_KEY} and {@link #SITES_KEY} as lists.
     */
    public static String write(Map<String, ?> all, String appVersion, long nowMs, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        f.setTimeZone(zone);
        Json j = new Json().obj();
        j.put("app", APP).put("file", "settings").put(VERSION_KEY, VERSION).put("appVersion", appVersion);
        j.put("exported", f.format(new Date(nowMs))).put("exportedMs", nowMs);
        j.obj("settings");
        for (String[] s : SETTINGS) {
            Object v = all.get(s[0]);
            if (v != null && fits(v, s[1])) value(j, s[0], v, s[1]);
        }
        j.end();
        j.obj("learned");
        for (Map.Entry<String, ?> e : new java.util.TreeMap<String, Object>(all).entrySet()) {
            String t = learnedType(e.getKey());
            if (t != null && e.getValue() != null && fits(e.getValue(), t)) value(j, e.getKey(), e.getValue(), t);
        }
        j.end();
        Object ps = all.get(PRESETS_KEY), ss = all.get(SITES_KEY);
        j.raw("presets", Preset.toJson(Preset.listFromJson(ps instanceof String ? (String) ps : "")));
        j.raw("sites", Site.toJson(Site.listFromJson(ss instanceof String ? (String) ss : "")));
        return j.end().toString();
    }

    private static boolean fits(Object v, String t) {
        switch (t) {
            case "b": return v instanceof Boolean;
            case "i": return v instanceof Integer;
            case "l": return v instanceof Long || v instanceof Integer;
            case "f": return v instanceof Float || v instanceof Double;
            default: return v instanceof String;
        }
    }

    private static void value(Json j, String k, Object v, String t) {
        switch (t) {
            case "b": j.put(k, (Boolean) v); break;
            case "i":
            case "l": j.put(k, ((Number) v).longValue()); break;
            case "f": {
                // as the phone stores it: 0.6, not 0.6000000238
                float f = ((Number) v).floatValue();
                if (!Float.isNaN(f) && !Float.isInfinite(f)) j.raw(k, Float.toString(f));
                break;
            }
            default: j.put(k, (String) v); break;
        }
    }

    /** A settings file, read: the values with their types (Boolean, Integer, Long, Float, String), presets and sites. */
    public static final class Parsed {
        /** The version of the settings in the file (after {@link #migrate}, they are this version's). */
        public int version;
        public String appVersion = "", exported = "";
        public final Map<String, Object> values = new LinkedHashMap<String, Object>();
        public final List<Preset> presets = new ArrayList<Preset>();
        public final List<Site> sites = new ArrayList<Site>();
        /** Entries left out, and why ("brightness (not a number)"). */
        public final List<String> skipped = new ArrayList<String>();
        /** What {@link #migrate} changed. */
        public final List<String> notes = new ArrayList<String>();

        /** How many settings (not learned values). */
        public int settingCount() {
            int n = 0;
            for (String k : values.keySet()) if (learnedType(k) == null) n++;
            return n;
        }
    }

    /** Reads a settings file; throws IllegalArgumentException, with words for the screen, if it isn't one. */
    public static Parsed read(String text) {
        JsonTree.Obj o;
        try {
            o = JsonTree.object(text.startsWith("﻿") ? text.substring(1) : text);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("That file isn't a Night Sky settings file (it isn't JSON).");
        }
        if (!APP.equals(o.str("app", "")) || !"settings".equals(o.str("file", ""))) {
            throw new IllegalArgumentException("That file isn't a Night Sky settings file.");
        }
        Parsed p = new Parsed();
        p.version = o.integer(VERSION_KEY, 1);
        p.appVersion = o.str("appVersion", "");
        p.exported = o.str("exported", "");
        if (p.version > VERSION) {
            p.notes.add("made by a newer version of the app (" + p.appVersion + "): settings this one doesn't know are left out");
        }
        JsonTree.Obj s = o.obj("settings");
        if (s != null) {
            for (Map.Entry<String, Object> e : s.map.entrySet()) {
                String t = null;
                for (String[] d : SETTINGS) if (d[0].equals(e.getKey())) t = d[1];
                take(p, e.getKey(), e.getValue(), t);
            }
        }
        JsonTree.Obj l = o.obj("learned");
        if (l != null) {
            for (Map.Entry<String, Object> e : l.map.entrySet()) take(p, e.getKey(), e.getValue(), learnedType(e.getKey()));
        }
        for (JsonTree.Obj po : o.objs("presets")) {
            Preset pr = Preset.fromJson(po);
            if (pr.id.isEmpty() || pr.name.isEmpty()) p.skipped.add("a preset without a name");
            else if (Preset.find(Preset.builtIns(), pr.id) != null) p.skipped.add("preset " + pr.name + " (the built-in ones can't be replaced)");
            else if (Preset.find(p.presets, pr.id) == null) p.presets.add(pr);
        }
        for (JsonTree.Obj so : o.objs("sites")) {
            Site si = Site.fromJson(so);
            if (si.id.isEmpty() || Double.isNaN(si.latDeg) || Double.isNaN(si.lonDeg)) p.skipped.add("a site without a place");
            else if (Site.find(p.sites, si.id) == null) p.sites.add(si);
        }
        if (p.version < VERSION) p.notes.addAll(migrate(p.values, p.version));
        p.version = Math.max(p.version, VERSION);
        return p;
    }

    private static void take(Parsed p, String k, Object v, String t) {
        if (t == null) {
            p.skipped.add(k + " (not a setting this version knows)");
            return;
        }
        Object out = null;
        String why = "";
        switch (t) {
            case "b":
                if (v instanceof Boolean) out = v;
                else why = "not a yes/no";
                break;
            case "i":
            case "l":
                if (v instanceof Double && (Double) v == Math.rint((Double) v) && Math.abs((Double) v) < 9e15) {
                    long n = (long) (double) (Double) v;
                    if (t.equals("l")) out = n;
                    else if (n >= Integer.MIN_VALUE && n <= Integer.MAX_VALUE) out = (int) n;
                    else why = "too large";
                } else {
                    why = "not a whole number";
                }
                break;
            case "f":
                if (v instanceof Double && !((Double) v).isNaN() && !((Double) v).isInfinite()) out = (float) (double) (Double) v;
                else why = "not a number";
                break;
            default:
                if (v instanceof String) out = v;
                else why = "not text";
                break;
        }
        if (out != null && !sane(k, out)) {
            out = null;
            why = "out of range";
        }
        if (out == null) p.skipped.add(k + " (" + why + ")");
        else p.values.put(k, out);
    }

    /** Values a hand-edited file could get wrong enough to break a session. */
    static boolean sane(String k, Object v) {
        switch (k) {
            case "durationMin": return (Integer) v >= 1 && (Integer) v <= 120;
            case "brightness": return (Float) v >= 0f && (Float) v <= 1f;
            case "countdownSec": return (Integer) v >= 0 && (Integer) v <= 60;
            case "planMinBattery": return (Integer) v >= 0 && (Integer) v <= 100;
            case "sqmOffset": return Math.abs((Float) v) <= 2f;
            default: return true;
        }
    }
}
