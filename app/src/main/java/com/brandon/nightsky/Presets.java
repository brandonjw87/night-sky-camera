package com.brandon.nightsky;

import com.brandon.nightsky.processing.Preset;

import java.util.ArrayList;
import java.util.List;

/**
 * Presets on the phone (E6): the four that come with the app and Brandon's own (kept in the settings), the one in
 * place, and a session's settings from them.
 */
final class Presets {
    /** Longest a session may grow to while collecting clear minutes (s). */
    static final int MAX_COLLECT_SEC = 2 * 60 * 60;

    private Presets() {}

    /** The built-in presets, then Brandon's own. */
    static List<Preset> all(Prefs prefs) {
        List<Preset> l = Preset.builtIns();
        l.addAll(own(prefs));
        return l;
    }

    static List<Preset> own(Prefs prefs) {
        return Preset.listFromJson(prefs.userPresetsJson());
    }

    /** The preset with this id, or null. */
    static Preset find(Prefs prefs, String id) {
        return Preset.find(all(prefs), id);
    }

    /** The name of the preset with this id ("" if there's none). */
    static String name(Prefs prefs, String id) {
        Preset p = find(prefs, id);
        return p != null ? p.name : "";
    }

    /** The first preset with these settings (null if none has them). */
    static Preset matching(Prefs prefs, Preset settings) {
        for (Preset p : all(prefs)) if (p.sameSettings(settings)) return p;
        return null;
    }

    /** Puts a preset in place (picked by hand: a saved site's preset doesn't replace it for an hour). */
    static void pick(Prefs prefs, Preset p, boolean byHand) {
        prefs.applyPreset(p);
        if (byHand) prefs.setHandPicked(System.currentTimeMillis());
    }

    /** A setting was changed by hand: the settings are now the preset they match, if one does. */
    static void handChanged(Prefs prefs) {
        Preset m = matching(prefs, prefs.currentPreset());
        prefs.setPreset(m != null ? m.id : "");
        prefs.setHandPicked(System.currentTimeMillis());
    }

    /** Saves the settings in place as a preset of Brandon's own, and puts it in place. */
    static Preset saveOwn(Prefs prefs, String name) {
        Preset p = prefs.currentPreset();
        p.id = "u" + System.currentTimeMillis();
        p.name = name.trim();
        p.builtIn = false;
        List<Preset> l = own(prefs);
        l.add(p);
        prefs.setUserPresetsJson(Preset.toJson(l));
        prefs.setPreset(p.id);
        return p;
    }

    static void rename(Prefs prefs, String id, String name) {
        List<Preset> l = own(prefs);
        for (Preset p : l) if (p.id.equals(id)) p.name = name.trim();
        prefs.setUserPresetsJson(Preset.toJson(l));
    }

    /** Deletes one of Brandon's presets (the settings stay as they are, now his own). */
    static void delete(Prefs prefs, String id) {
        List<Preset> l = own(prefs);
        List<Preset> keep = new ArrayList<Preset>();
        for (Preset p : l) if (!p.id.equals(id)) keep.add(p);
        prefs.setUserPresetsJson(Preset.toJson(keep));
        if (prefs.preset().equals(id)) prefs.setPreset("");
    }

    /** Whether a name is already a preset's (names are what's shown and said). */
    static boolean nameTaken(Prefs prefs, String name, String exceptId) {
        for (Preset p : all(prefs)) if (p.name.equalsIgnoreCase(name.trim()) && !p.id.equals(exceptId)) return true;
        return false;
    }

    /**
     * A session's settings: the saved ones, or {@code only}'s for this session alone. The saved settings may give way
     * to a saved site's preset when the session finds it's at one; a preset or a shortcut asked for doesn't.
     */
    static SessionController.Settings session(Prefs prefs, Preset only) {
        SessionController.Settings s = new SessionController.Settings();
        fill(prefs, s, only != null ? only : prefs.currentPreset());
        s.sounds = prefs.sounds();
        s.preflight = prefs.preflight();
        s.usesSaved = only == null;
        return s;
    }

    /** Puts a preset's settings into a session's. */
    static void fill(Prefs prefs, SessionController.Settings s, Preset p) {
        s.trails = p.trails;
        s.durationSec = p.durationMin * 60;
        s.brightness = p.brightness;
        s.saveDng = p.saveDng;
        s.rejection = p.rejection;
        s.keepSatellites = p.keepSatellites;
        s.keepMeteors = p.keepMeteors;
        s.blend = p.blend;
        s.quick = p.quick;
        // collect N clear minutes (A3): the session's length of clear sky, for at most three times as long
        s.clearTargetSec = p.collectClear ? s.durationSec : 0;
        s.maxDurationSec = p.collectClear ? Math.min(MAX_COLLECT_SEC, 3 * s.durationSec) : 0;
        s.presetId = p.id;
        s.presetName = name(prefs, p.id);
    }
}
