package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A preset (E6): a named set of session settings picked with one tap. Four come with the app (Backyard, Dark
 * site, Partly cloudy, Quick look) and Brandon can save his own. What a preset holds is what changes from one
 * kind of night to another; sounds, the viewer and the like stay as they are.
 */
public final class Preset {
    public static final String BACKYARD = "backyard", DARK = "dark", CLOUDY = "cloudy", QUICK = "quick";

    /** "backyard", "dark", "cloudy", "quick", or "u" and a number for Brandon's own. */
    public String id = "";
    public String name = "";
    public boolean builtIn;
    public boolean trails;
    public int durationMin = 5;
    /** Picture brightness, 0..1 (0.5 = natural dark sky). */
    public float brightness = 0.5f;
    public boolean saveDng = true;
    /** Outlier rejection (a Rejection.Method name; AUTO chooses from the session). */
    public String rejection = "AUTO";
    public boolean keepSatellites;
    /** Keep meteors in the picture while satellites and planes are taken out ({@link #keepSatellites} keeps both). */
    public boolean keepMeteors;
    /** Keep capturing until the session's length of clear sky is in, for at most three times as long (A3). */
    public boolean collectClear;
    /** Also save the long-exposure blend: the plain average, where drifting clouds turn into soft streaks (C7). */
    public boolean blend;
    /** Quick developing: one stacking pass and a half-size photo. */
    public boolean quick;

    public Preset() {}

    public Preset copy() {
        Preset p = new Preset();
        p.id = id;
        p.name = name;
        p.builtIn = builtIn;
        p.trails = trails;
        p.durationMin = durationMin;
        p.brightness = brightness;
        p.saveDng = saveDng;
        p.rejection = rejection;
        p.keepSatellites = keepSatellites;
        p.keepMeteors = keepMeteors;
        p.collectClear = collectClear;
        p.blend = blend;
        p.quick = quick;
        return p;
    }

    /** The presets that come with the app, in the order shown. */
    public static List<Preset> builtIns() {
        List<Preset> l = new ArrayList<Preset>();
        Preset b = new Preset();
        b.id = BACKYARD;
        b.name = "Backyard";
        // light-polluted: the glow kept darker, so the stars stand out from it
        b.brightness = 0.40f;
        l.add(b);
        Preset d = new Preset();
        d.id = DARK;
        d.name = "Dark site";
        // a dark sky holds more: longer, and brighter to bring out the faint stars and the Milky Way
        d.durationMin = 10;
        d.brightness = 0.60f;
        l.add(d);
        // frames are used only where they're clear in any case (F1)
        Preset c = new Preset();
        c.id = CLOUDY;
        c.name = "Partly cloudy";
        c.collectClear = true;
        c.blend = true;
        l.add(c);
        Preset q = new Preset();
        q.id = QUICK;
        q.name = "Quick look";
        q.durationMin = 2;
        q.saveDng = false;
        q.quick = true;
        l.add(q);
        for (Preset p : l) p.builtIn = true;
        return l;
    }

    /** The one with this id among {@code all}, or null. */
    public static Preset find(List<Preset> all, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Preset p : all) if (p.id.equals(id)) return p;
        return null;
    }

    /** Whether meteors stay in the picture (on their own, or with the satellites). */
    public boolean keptMeteors() {
        return keepMeteors || keepSatellites;
    }

    /** Whether the two hold the same settings (names aside). */
    public boolean sameSettings(Preset o) {
        return o != null && trails == o.trails && durationMin == o.durationMin && Math.abs(brightness - o.brightness) < 0.005f
                && saveDng == o.saveDng && rejection.equalsIgnoreCase(o.rejection) && keepSatellites == o.keepSatellites
                && keptMeteors() == o.keptMeteors() && collectClear == o.collectClear && blend == o.blend && quick == o.quick;
    }

    /** "Deep sky, 10 min, brightness 55%, RAW too" and what's special, for the screen. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(trails ? "Star trails" : "Deep sky").append(", ").append(durationMin).append(" min");
        sb.append(String.format(Locale.US, ", brightness %d%%", Math.round(brightness * 100)));
        sb.append(saveDng ? ", RAW too" : ", JPEG only");
        if (collectClear) sb.append(", keeps going until ").append(durationMin).append(" clear minutes are in (up to ")
                .append(3 * durationMin).append(")");
        if (blend) sb.append(", also a cloud-blur version");
        if (quick) sb.append(", quick developing and a half-size photo");
        if (keepSatellites) sb.append(", satellites and meteors kept");
        else if (keepMeteors) sb.append(", meteors kept");
        if (!"AUTO".equalsIgnoreCase(rejection)) sb.append(", rejection ").append(rejection.toLowerCase(Locale.US).replace('_', ' '));
        return sb.toString();
    }

    public void toJson(Json j) {
        j.obj();
        j.put("id", id).put("name", name);
        j.put("trails", trails).put("durationMin", durationMin).put("brightness", (double) brightness, 3);
        j.put("saveDng", saveDng).put("rejection", rejection).put("keepSatellites", keepSatellites);
        if (keepMeteors) j.put("keepMeteors", true);
        j.put("collectClear", collectClear).put("blend", blend).put("quick", quick);
        j.end();
    }

    public static Preset fromJson(JsonTree.Obj o) {
        Preset p = new Preset();
        p.id = o.str("id", "");
        p.name = o.str("name", "");
        p.trails = o.bool("trails", false);
        p.durationMin = Math.max(1, Math.min(120, o.integer("durationMin", 5)));
        p.brightness = (float) Math.max(0, Math.min(1, o.num("brightness", 0.5)));
        p.saveDng = o.bool("saveDng", true);
        p.rejection = o.str("rejection", "AUTO");
        p.keepSatellites = o.bool("keepSatellites", false);
        p.keepMeteors = o.bool("keepMeteors", false);
        p.collectClear = o.bool("collectClear", false);
        p.blend = o.bool("blend", false);
        p.quick = o.bool("quick", false);
        return p;
    }

    /** A list of presets as JSON. */
    public static String toJson(List<Preset> l) {
        Json j = new Json().arr();
        for (Preset p : l) p.toJson(j);
        return j.end().toString();
    }

    /** Presets from JSON (a list); empty if the text isn't one. Entries without an id or a name are dropped. */
    public static List<Preset> listFromJson(String s) {
        List<Preset> out = new ArrayList<Preset>();
        if (s == null || s.isEmpty()) return out;
        Object v;
        try {
            v = JsonTree.parse(s);
        } catch (IllegalArgumentException e) {
            return out;
        }
        if (!(v instanceof List)) return out;
        for (Object e : (List<?>) v) {
            if (!(e instanceof java.util.Map)) continue;
            @SuppressWarnings("unchecked")
            Preset p = fromJson(new JsonTree.Obj((java.util.Map<String, Object>) e));
            if (!p.id.isEmpty() && !p.name.isEmpty()) out.add(p);
        }
        return out;
    }
}
