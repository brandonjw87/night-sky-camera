package com.brandon.nightsky.watch;

import android.content.Context;
import android.graphics.Bitmap;

import com.brandon.nightsky.remote.JsonTree;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The Moon on the wrist (3.0, the Moonshot): its phase drawn as it looks tonight (the lit side where the sky has it, its
 * seas turned as they are),
 * how much of it is lit and how high it is; with the phone's Moonshot open, its shutter and Catch; and the next
 * space-station crossing of the Moon near here, counted down to the second with buzzes (a minute, ten seconds,
 * three, two, one, now), so the phone can be held still without looking at anything.
 */
final class MoonFace {
    private MoonFace() {}

    /** The countdown shows from this long before a crossing (ms). */
    static final long COUNT_FROM_MS = 3600_000L;
    /** The buzzes' marks (seconds before). */
    static final int[] MARKS = {60, 10, 3, 2, 1, 0};

    private static Bitmap phaseBmp;
    private static String phaseKey = "";

    /**
     * The seas (maria) of the Moon's near side, for its face on the watch: {latitude, longitude (east positive),
     * radius} in degrees, as rough round patches: Imbrium, Serenitatis, Tranquillitatis, Crisium, Fecunditatis, Nectaris,
     * Nubium, Humorum, the Ocean of Storms (two), Frigoris (three), Vaporum, Insularum, Cognitum.
     */
    static final double[][] SEAS = {
            {33, -16, 17}, {28, 17.5, 10}, {8.5, 31, 11}, {17, 59, 8}, {-7.8, 51, 9}, {-15, 35, 5}, {-21, -17, 10}, {-24, -39, 6},
            {22, -55, 15}, {2, -48, 12}, {56, 0, 6}, {57, 25, 6}, {55, -28, 6}, {13, 3.5, 4}, {7.5, -31, 7}, {-10, -23, 5},
    };

    /** The lit part's brightness on the watch (the words go over it, so it stays dim), and the night side's (earthshine). */
    static final double LIT = 0.42, EARTHSHINE = 0.05;

    /**
     * The phase as a picture ({@code size} px square, the Moon half as wide, so the face's fade leaves it whole): the
     * lit part towards {@code limbDeg} (counterclockwise from up), the night side faint as earthshine, and the dark
     * seas where they are with the Moon's north at {@code axisDeg} (counterclockwise from up; NaN: no seas drawn).
     */
    static Bitmap phase(int size, double illum, double limbDeg, double axisDeg) {
        String key = size + "|" + Math.round(illum * 200) + "|" + Math.round(limbDeg) + "|" + (Double.isNaN(axisDeg) ? "-" : Math.round(axisDeg));
        if (phaseBmp != null && key.equals(phaseKey)) return phaseBmp;
        int[] px = new int[size * size];
        double c = (size - 1) / 2.0, R = size * 0.27;
        double t = Math.toRadians(limbDeg), lx = -Math.sin(t), ly = -Math.cos(t);
        double k = Math.max(0, Math.min(1, illum));
        boolean seas = !Double.isNaN(axisDeg);
        double th = seas ? Math.toRadians(axisDeg) : 0, ct = Math.cos(th), st = Math.sin(th);
        double[][] sea = new double[SEAS.length][];
        for (int i = 0; i < SEAS.length; i++) {
            double la = Math.toRadians(SEAS[i][0]), lo = Math.toRadians(SEAS[i][1]);
            sea[i] = new double[]{Math.cos(la) * Math.sin(lo), Math.sin(la), Math.cos(la) * Math.cos(lo), Math.cos(Math.toRadians(SEAS[i][2]))};
        }
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double lit = 0, disc = 0;
                // 3 x 3 samples a pixel: a smooth edge and terminator
                for (int sy = 0; sy < 3; sy++) {
                    for (int sx = 0; sx < 3; sx++) {
                        double dx = x + (sx - 1) / 3.0 - c, dy = y + (sy - 1) / 3.0 - c;
                        double rr = dx * dx + dy * dy;
                        if (rr > R * R) continue;
                        disc++;
                        double u = dx * lx + dy * ly, v = -dx * ly + dy * lx;
                        double half = Math.sqrt(Math.max(0, R * R - v * v));
                        if (u >= (1 - 2 * k) * half) lit++;
                    }
                }
                double a = disc / 9, l = disc > 0 ? lit / disc : 0;
                double tone = 1;
                if (seas && disc > 0) {
                    // the point on the Moon's face (its north up, east to the right as the sky has it), turned as tonight
                    double X = (x - c) / R, Y = (y - c) / R;
                    double mx = X * ct - Y * st, my = X * st + Y * ct;
                    double q = mx * mx + my * my;
                    if (q < 1) {
                        double ex = mx, ny = -my, ez = Math.sqrt(1 - q);
                        double dark = 0;
                        for (double[] m : sea) {
                            double cosd = ex * m[0] + ny * m[1] + ez * m[2];
                            // inside the patch, and softly a little way out of it
                            double edge = (cosd - m[3]) / (1 - m[3] + 1e-9);
                            dark = Math.max(dark, Math.max(0, Math.min(1, 0.5 + 2.5 * edge)));
                        }
                        tone = 1 - 0.42 * dark;
                    }
                }
                int g = (int) Math.round(255 * a * (EARTHSHINE + (LIT - EARTHSHINE) * l * tone));
                px[y * size + x] = 0xFF000000 | (g << 16) | (g << 8) | g;
            }
        }
        Bitmap b = Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888);
        phaseBmp = b;
        phaseKey = key;
        return b;
    }

    /** The time to count down to: the crossing the phone waits for, else one seen from here within the hour; 0 if none. */
    static long target(JsonTree.Obj moon, long now) {
        if (moon == null) return 0;
        long armed = moon.lng("armedAt", 0);
        if (armed > 0 && armed > now - 5000) return armed;
        JsonTree.Obj next = moon.obj("next");
        if (next != null && next.bool("here", false)) {
            long at = next.lng("at", 0);
            if (at > now - 5000 && at - now < COUNT_FROM_MS) return at;
        }
        return 0;
    }

    /** The Moon screen. */
    static void face(DialView.Face f, JsonTree.Obj s, String via, boolean fresh, long now, long statusAgeMs) {
        JsonTree.Obj moon = s != null ? s.obj("moon") : null;
        int battery = s != null ? s.integer("battery", -1) : -1;
        f.top = fresh ? "Moonshot · " + via + (battery >= 0 ? " · " + battery + "%" : "") : "Last heard " + Math.max(1, statusAgeMs / 60_000L) + " min ago";
        f.linked = fresh;
        f.buttons.clear();
        if (moon == null) {
            f.title = "The Moon";
            f.line1 = "Update Night Sky on the phone";
            f.line2 = "for the Moonshot";
            f.buttons.add(new DialView.Button("moon-back", "Back", true));
            return;
        }
        double illum = moon.num("illum", 0.5);
        f.picture = phase(200, illum, moon.num("limb", moon.bool("waxing", true) ? 270 : 90), moon.has("axis") ? moon.num("axis", 0) : Double.NaN);
        f.title = moon.str("phase", "The Moon");
        boolean open = moon.bool("open", false);
        long at = target(moon, now);
        JsonTree.Obj next = moon.obj("next");
        String name = moon.lng("armedAt", 0) > 0 ? moon.str("armedName", "the ISS") : next != null ? next.str("name", "the ISS") : "the ISS";
        if (at > 0) {
            long left = at - now;
            f.big = left > 0 ? WatchActivity.clock(left) : "Now!";
            f.bigScale = left >= 3600_000L ? 0.8f : 1f;
            f.line1 = capital(name) + " crosses the Moon";
            f.line2 = moon.lng("armedAt", 0) > 0 ? "The phone shoots by itself" : open ? "Tap Catch to shoot it" : "Seen from right here";
            if (left <= 60_000L && left > 0) f.progress = 1f - left / 60_000f;
        } else {
            f.big = Math.round(illum * 100) + "%";
            f.bigScale = 0.8f;
            f.line1 = moon.has("alt") ? (moon.num("alt", 0) > 0 ? Math.round(moon.num("alt", 0)) + "° up, " + moon.str("dir", "")
                    : "Below the horizon") : "lit";
            f.line2 = nextWords(next);
        }
        if (open) {
            String done = moon.str("done", ""), words = moon.str("words", "");
            if (at == 0) f.line2 = !done.isEmpty() ? done : !words.isEmpty() ? words : f.line2;
            DialView.Button shoot = new DialView.Button("moonshot", "Shoot", true);
            shoot.enabled = moon.bool("ready", false);
            f.buttons.add(shoot);
            if (moon.bool("canCatch", false)) f.buttons.add(new DialView.Button("moon-catch", "Catch", false));
        } else if (at == 0 && f.line2.isEmpty()) {
            f.line2 = "Open the Moonshot on the phone";
        }
        f.buttons.add(new DialView.Button("moon-back", "Back", f.buttons.isEmpty()));
    }

    /** "ISS here, Sat 9:41 PM", "ISS 14 km NE, Sat 9:41 PM". */
    static String nextWords(JsonTree.Obj next) {
        if (next == null) return "";
        long at = next.lng("at", 0);
        if (at <= 0) return "";
        String who = shortName(next.str("name", "ISS"));
        String when = new SimpleDateFormat("EEE h:mm a", Locale.US).format(new Date(at));
        if (next.bool("here", false)) return who + " crosses it here, " + when;
        return String.format(Locale.US, "%s %.0f km %s, %s", who, next.num("km", 0), abbrev(next.str("dir", "")), when);
    }

    static String shortName(String n) {
        return n.startsWith("the ") ? n.substring(4) : n;
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "north-east" as "NE". */
    static String abbrev(String dir) {
        StringBuilder b = new StringBuilder();
        for (String p : dir.split("-")) if (!p.isEmpty()) b.append(Character.toUpperCase(p.charAt(0)));
        return b.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // the countdown's buzzes

    private static long buzzedFor;
    private static final Set<Integer> buzzed = new HashSet<Integer>();

    /**
     * Once a second (from the screen, or the service with the screen off): the buzz for each mark as it passes, once a
     * crossing. Whether a countdown is running (the service then keeps going).
     */
    static synchronized boolean tick(Context c, JsonTree.Obj moon, long now) {
        long at = target(moon, now);
        if (at == 0) return false;
        if (at != buzzedFor) {
            buzzedFor = at;
            buzzed.clear();
        }
        long left = at - now;
        for (int m : MARKS) {
            // within a second and a half after the mark passes: a late status doesn't buzz old marks
            if (left <= m * 1000L && left > m * 1000L - 1500 && buzzed.add(m)) {
                Buzz.transit(c, m);
                break;
            }
        }
        return left > -5000;
    }
}
