package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A saved site (E6): a name and a place, the preset to use there, and what the app learns there: the share of
 * the catalogue's stars a clear night shows (for "only if clear", E2), what blocks the sky (the pre-flight
 * check's obstruction, E4) and the sky brightness over time (sessions and sky readings, D4 and H3). A session
 * that starts within {@link #NEAR_KM} of a site picks it, and its preset, by itself.
 */
public final class Site {
    /** A session this close to a site (km) is at that site. */
    public static final double NEAR_KM = 2.0;
    /** The farthest an approximate fix may put a site's own place (km). */
    static final double FAR_KM = 4.0;
    /** Clear nights' star shares kept, and sky brightness measurements kept. */
    static final int CLEAR_KEEP = 10, SKY_KEEP = 100;

    /** One sky brightness measurement at the site. */
    public static final class Sky {
        public long timeMs;
        /** Magnitudes per square arcsecond. */
        public double mag;
        /** Whether it counts for the site's darkness (no Moon, fully dark, clear). */
        public boolean valid;
        /** "reading" (a sky reading) or "session" (a session's report). */
        public String source = "";
    }

    public String id = "";
    public String name = "";
    public double latDeg = Double.NaN, lonDeg = Double.NaN;
    /** The preset to use here ("" for none: the settings stay as they are). */
    public String presetId = "";
    public long createdMs, lastUsedMs;
    public int sessions;
    /** Shares of the catalogue's stars that clear nights here showed (newest last). */
    public final List<Double> clearShares = new ArrayList<Double>();
    /**
     * What blocked the sky at the last check ("" if nothing did), the share of the frame, which way (degrees east of
     * north, NaN if unknown) and when (0: never checked here).
     */
    public String obstruction = "";
    public double obstructionFraction;
    public double obstructionAzimuthDeg = Double.NaN;
    public long obstructionMs;
    public final List<Sky> sky = new ArrayList<Sky>();

    /** Great-circle distance in km. */
    public static double distanceKm(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1), p2 = Math.toRadians(lat2);
        double dp = p2 - p1, dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * 6371.0 * Math.asin(Math.min(1, Math.sqrt(a)));
    }

    public double distanceKm(double lat, double lon) {
        return distanceKm(latDeg, lonDeg, lat, lon);
    }

    /**
     * How close a fix must be to a site to be at it (km): {@link #NEAR_KM}, or more for an approximate fix. Android
     * snaps an approximate location to a grid about 2 km across, so the same garden can come out one square over
     * (2 to 2.8 km away) from one night to the next.
     */
    public static double nearKm(double accuracyM) {
        if (Double.isNaN(accuracyM) || accuracyM < 500) return NEAR_KM;
        return Math.max(NEAR_KM, Math.min(FAR_KM, 1.5 * accuracyM / 1000));
    }

    /** The closest site within {@code maxKm} of the place, or null. */
    public static Site nearest(List<Site> sites, double lat, double lon, double maxKm) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return null;
        Site best = null;
        double bd = maxKm;
        for (Site s : sites) {
            if (Double.isNaN(s.latDeg)) continue;
            double d = s.distanceKm(lat, lon);
            if (d <= bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    public static Site find(List<Site> sites, String id) {
        for (Site s : sites) if (s.id.equals(id)) return s;
        return null;
    }

    // ---- a session at a site ----

    /** A setting changed by hand this recently keeps a site's preset from replacing it (ms). */
    public static final long HAND_MS = 60 * 60_000L;

    /** What to do about a site's preset. */
    public static final class Pick {
        /** The site's preset (null if it has none, or it was deleted). */
        public Preset preset;
        /** Whether it goes in place of the settings. */
        public boolean use;
        /** Why, or why not, for the log. */
        public String why = "";
    }

    /**
     * Whether this site's preset ({@code preset}: null if it has none or it was deleted) takes the place of the
     * settings in use (the preset {@code currentId}, "" for settings no preset has). Not when picking it by itself
     * is off ({@code auto}), the settings were asked for ({@code usesSaved} false: a shortcut, a preset or the night
     * plan), they are its preset already, or a setting was changed by hand {@code handAgoMs} ago, within
     * {@link #HAND_MS}.
     */
    public Pick decide(Preset preset, String currentId, boolean auto, boolean usesSaved, long handAgoMs) {
        Pick k = new Pick();
        k.preset = preset;
        if (preset == null) {
            k.why = presetId.isEmpty() ? "it has no preset of its own" : "its preset was deleted";
        } else if (preset.id.equals(currentId)) {
            k.why = "its preset, " + preset.name + ", is in place already";
        } else if (!auto) {
            k.why = "picking a site's preset by itself is off";
        } else if (!usesSaved) {
            k.why = "this session's settings were asked for (a shortcut, a preset or the night plan)";
        } else if (handAgoMs >= 0 && handAgoMs < HAND_MS) {
            k.why = String.format(Locale.US, "a setting was changed by hand %d minutes ago, so they stay", Math.max(1, handAgoMs / 60_000L));
        } else {
            k.use = true;
            k.why = "its preset, " + preset.name + ", picked";
        }
        return k;
    }

    /** What's said at the start: "Backyard site, Backyard preset." ("Backyard site." with no preset). */
    public static String spoken(String siteName, String presetName) {
        String s = siteName + (siteName.toLowerCase(Locale.US).endsWith("site") ? "" : " site");
        return presetName.isEmpty() ? s + "." : s + ", " + presetName + (presetName.toLowerCase(Locale.US).endsWith("preset") ? "." : " preset.");
    }

    /**
     * The share of the catalogue's stars a clear night shows here (NaN until a clear night was seen): the
     * second best of the clear nights kept once there are three, so one odd night can't set it.
     */
    public double clearShare() {
        int n = clearShares.size();
        if (n == 0) return Double.NaN;
        double[] v = new double[n];
        for (int i = 0; i < n; i++) v[i] = clearShares.get(i);
        Arrays.sort(v);
        return v[n >= 3 ? n - 2 : n - 1];
    }

    /** A clear night showed this share of the stars that should show. */
    public void learnClear(double share) {
        if (Double.isNaN(share) || share <= 0 || share > 1.5) return;
        clearShares.add(Math.min(1, share));
        while (clearShares.size() > CLEAR_KEEP) clearShares.remove(0);
    }

    public void addSky(long timeMs, double mag, boolean valid, String source) {
        if (Double.isNaN(mag) || mag < 10 || mag > 25) return;
        Sky s = new Sky();
        s.timeMs = timeMs;
        s.mag = mag;
        s.valid = valid;
        s.source = source;
        sky.add(s);
        while (sky.size() > SKY_KEEP) sky.remove(0);
    }

    /** Median of the valid sky brightness measurements (NaN if none), and how many there are. */
    public double skyMedian() {
        double[] v = new double[sky.size()];
        int n = 0;
        for (Sky s : sky) if (s.valid) v[n++] = s.mag;
        if (n == 0) return Double.NaN;
        Arrays.sort(v, 0, n);
        return n % 2 == 1 ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
    }

    public int validSkyCount() {
        int n = 0;
        for (Sky s : sky) if (s.valid) n++;
        return n;
    }

    /** "20.4 mag/arcsec² (median of 6), a suburban sky" or "" with no valid measurement. */
    public String skyText() {
        double m = skyMedian();
        if (Double.isNaN(m)) return "";
        int n = validSkyCount();
        return String.format(Locale.US, "%.1f mag/arcsec²%s, %s", m, n > 1 ? " (median of " + n + ")" : "", ReportCard.skyClass(m));
    }

    /** What the check saw blocking the sky: the item's text (empty if nothing did), its share and which way. */
    public void noteObstruction(long timeMs, String text, double fraction, double azimuthDeg) {
        obstruction = text == null ? "" : text;
        obstructionFraction = obstruction.isEmpty() ? 0 : fraction;
        obstructionAzimuthDeg = obstruction.isEmpty() ? Double.NaN : azimuthDeg;
        obstructionMs = timeMs;
    }

    /** What the app has learned here, a line each, for the sites page. */
    public List<String> describe(long nowMs) {
        List<String> l = new ArrayList<String>();
        String sk = skyText();
        int all = sky.size();
        if (!sk.isEmpty()) {
            l.add("Sky: " + sk + ".");
        } else if (all > 0) {
            l.add("Sky: " + all + (all == 1 ? " measurement" : " measurements") + ", none on a dark, clear night without the Moon yet.");
        } else {
            l.add("Sky: not measured yet. A sky reading or a session here measures it.");
        }
        double cs = clearShare();
        if (!Double.isNaN(cs)) {
            l.add(String.format(Locale.US, "A clear night here shows %d%% of the catalogue's stars (from %d %s): \"only if clear\" "
                    + "expects that, not all of them.", Math.round(100 * cs), clearShares.size(), clearShares.size() == 1 ? "night" : "nights"));
        }
        if (obstructionMs > 0) {
            String when = ago(nowMs - obstructionMs);
            if (obstruction.isEmpty()) l.add("Nothing blocked the sky at the last check (" + when + ").");
            else l.add(obstruction + " (" + when + ")");
        }
        if (sessions > 0) l.add(sessions + (sessions == 1 ? " session" : " sessions") + " here.");
        return l;
    }

    /** "today", "yesterday", "3 days ago". */
    static String ago(long ms) {
        long days = Math.max(0, ms) / 86_400_000L;
        return days == 0 ? "today" : days == 1 ? "yesterday" : days + " days ago";
    }

    // ------------------------------------------------------------------------------------------------
    // JSON

    public void toJson(Json j) {
        j.obj();
        j.put("id", id).put("name", name);
        j.put("lat", latDeg, 9).put("lon", lonDeg, 9);
        j.put("preset", presetId);
        j.put("created", createdMs).put("lastUsed", lastUsedMs).put("sessions", sessions);
        j.arr("clearShares");
        for (double v : clearShares) j.val(v, 4);
        j.end();
        j.put("obstruction", obstruction).put("obstructionFraction", obstructionFraction, 3)
                .put("obstructionAzimuth", obstructionAzimuthDeg, 4).put("obstructionAt", obstructionMs);
        j.arr("sky");
        for (Sky s : sky) {
            j.obj().put("t", s.timeMs).put("mag", s.mag, 4).put("valid", s.valid).put("source", s.source).end();
        }
        j.end();
        j.end();
    }

    public static Site fromJson(JsonTree.Obj o) {
        Site s = new Site();
        s.id = o.str("id", "");
        s.name = o.str("name", "");
        s.latDeg = o.num("lat", Double.NaN);
        s.lonDeg = o.num("lon", Double.NaN);
        s.presetId = o.str("preset", "");
        s.createdMs = o.lng("created", 0L);
        s.lastUsedMs = o.lng("lastUsed", 0L);
        s.sessions = o.integer("sessions", 0);
        for (double v : o.nums("clearShares")) if (!Double.isNaN(v)) s.clearShares.add(v);
        s.obstruction = o.str("obstruction", "");
        s.obstructionFraction = o.num("obstructionFraction", 0);
        s.obstructionAzimuthDeg = o.num("obstructionAzimuth", Double.NaN);
        s.obstructionMs = o.lng("obstructionAt", 0L);
        for (JsonTree.Obj k : o.objs("sky")) {
            Sky m = new Sky();
            m.timeMs = k.lng("t", 0L);
            m.mag = k.num("mag", Double.NaN);
            m.valid = k.bool("valid", false);
            m.source = k.str("source", "");
            if (!Double.isNaN(m.mag)) s.sky.add(m);
        }
        return s;
    }

    public static String toJson(List<Site> l) {
        Json j = new Json().arr();
        for (Site s : l) s.toJson(j);
        return j.end().toString();
    }

    /** Sites from JSON (a list); empty if the text isn't one. Entries without an id or a place are dropped. */
    @SuppressWarnings("unchecked")
    public static List<Site> listFromJson(String text) {
        List<Site> out = new ArrayList<Site>();
        if (text == null || text.isEmpty()) return out;
        Object v;
        try {
            v = JsonTree.parse(text);
        } catch (IllegalArgumentException e) {
            return out;
        }
        if (!(v instanceof List)) return out;
        for (Object e : (List<?>) v) {
            if (!(e instanceof Map)) continue;
            Site s = fromJson(new JsonTree.Obj((Map<String, Object>) e));
            if (!s.id.isEmpty() && !Double.isNaN(s.latDeg) && !Double.isNaN(s.lonDeg)) out.add(s);
        }
        return out;
    }
}
