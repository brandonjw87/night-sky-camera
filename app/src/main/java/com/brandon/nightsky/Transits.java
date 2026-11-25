package com.brandon.nightsky;

import android.content.Context;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatPredict;
import com.brandon.nightsky.processing.TransitFinder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The Transit Hunter's list (3.0): the space stations crossing the Moon within an hour's drive in the next ten days
 * ({@link TransitFinder}), from the orbits the app already keeps ({@link Satellites}), worked out at most every
 * quarter of an hour and kept for the Moonshot screen and the watch.
 */
final class Transits {
    /** How far to look (km) and how long ahead (days). */
    static final double NEAR_KM = 60;
    static final int DAYS = 10;
    private static final long KEEP_MS = 15 * 60_000L;

    private static List<TransitFinder.Transit> cached;
    private static long cachedAt;
    private static double cachedLat = Double.NaN, cachedLon = Double.NaN;
    private static String cachedWhy = "";

    private Transits() {}

    /** The crossings near {@code p} from now on (null, and the reason in {@code why}, if they can't be worked out). Not on the main thread. */
    static synchronized List<TransitFinder.Transit> find(Context c, Locator.Place p, StringBuilder why) {
        long now = System.currentTimeMillis();
        if (p == null) {
            why.append("Where you are isn't known yet: allow the location on the main screen, then come back.");
            return null;
        }
        if (cached != null && now - cachedAt < KEEP_MS && Math.abs(p.latDeg - cachedLat) < 0.003 && Math.abs(p.lonDeg - cachedLon) < 0.003) {
            return future(cached, now);
        }
        if (cached == null && !cachedWhy.isEmpty() && now - cachedAt < 60_000L) {
            why.append(cachedWhy);
            return null;
        }
        Context app = c.getApplicationContext();
        if (!new Prefs(app).satelliteData()) {
            remember(null, now, p, "Satellite data is off in Settings. Turn it on and the space stations' orbits come from CelesTrak, "
                    + "so the Moonshot can tell you when one crosses the Moon near you.");
            why.append(cachedWhy);
            return null;
        }
        Satellites.refreshNow(app, false);
        SatCatalog cat = Satellites.catalogNow(app);
        double jd = Astro.jdUtc(now);
        List<TransitFinder.Station> st = new ArrayList<TransitFinder.Station>();
        if (cat != null) {
            SatCatalog.Sat iss = cat.get(TransitFinder.ISS), css = cat.get(TransitFinder.TIANGONG);
            if (iss != null && iss.ageDays(jd) < SatPredict.MAX_AGE_DAYS) {
                st.add(new TransitFinder.Station(iss.orbit, TransitFinder.ISS, "the ISS", TransitFinder.ISS_SPAN_M));
            }
            if (css != null && css.ageDays(jd) < SatPredict.MAX_AGE_DAYS) {
                st.add(new TransitFinder.Station(css.orbit, TransitFinder.TIANGONG, "Tiangong", 0));
            }
        }
        if (st.isEmpty()) {
            remember(null, now, p, "No fresh orbit for the space stations yet: it comes from CelesTrak when the phone is online. Try again in a minute.");
            why.append(cachedWhy);
            return null;
        }
        double hKm = Double.isNaN(p.altM) ? 0 : p.altM / 1000;
        List<TransitFinder.Transit> l = TransitFinder.all(st, p.latDeg, p.lonDeg, hKm, now - 60_000L, now + DAYS * 86_400_000L, NEAR_KM);
        remember(l, now, p, "");
        return future(l, now);
    }

    private static void remember(List<TransitFinder.Transit> l, long now, Locator.Place p, String why) {
        cached = l;
        cachedAt = now;
        cachedLat = p.latDeg;
        cachedLon = p.lonDeg;
        cachedWhy = why;
    }

    /** The ones not yet over (with a few seconds' grace). */
    private static List<TransitFinder.Transit> future(List<TransitFinder.Transit> l, long now) {
        List<TransitFinder.Transit> out = new ArrayList<TransitFinder.Transit>();
        for (TransitFinder.Transit t : l) if (when(t) > now - 5000) out.add(t);
        return out;
    }

    /** The moment to be ready for: as seen from here if it crosses here, else on the centre line. */
    static long when(TransitFinder.Transit t) {
        return t.here() ? t.hereMs : t.timeMs;
    }

    /** The next one seen from right here, or null. */
    static TransitFinder.Transit nextHere(List<TransitFinder.Transit> l) {
        if (l == null) return null;
        for (TransitFinder.Transit t : l) if (t.here()) return t;
        return null;
    }

    /** The list in words, for the Moonshot's card. */
    static String words(Context c, Locator.Place p) {
        StringBuilder why = new StringBuilder();
        List<TransitFinder.Transit> l = find(c, p, why);
        if (l == null) return why.toString();
        return words(l);
    }

    static String words(List<TransitFinder.Transit> l) {
        if (l.isEmpty()) {
            return "No crossings of the Moon within " + Math.round(NEAR_KM) + " km in the next " + DAYS + " days. Each one is seen only from a "
                    + "strip a few kilometres wide, so they're rare at any one place: look again in a few days, as the orbits are updated.";
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (TransitFinder.Transit t : l) {
            if (shown == 5) break;
            if (shown > 0) sb.append("\n\n");
            sb.append(line(t));
            shown++;
        }
        sb.append("\n\nThe strips are only a few kilometres wide, so a few hundred metres matter: the further ahead, the more the "
                + "station's orbit can change, so look again the day before.");
        return sb.toString();
    }

    /** One crossing: when, where to be, what it looks like. */
    static String line(TransitFinder.Transit t) {
        StringBuilder sb = new StringBuilder();
        sb.append(day(when(t))).append(": ").append(capital(t.name));
        if (t.here()) {
            sb.append(String.format(Locale.US, " crosses the Moon right here, in %.1f seconds", t.hereS));
        } else {
            sb.append(String.format(Locale.US, " crosses the Moon %s %s of here (seen from a strip %s wide that runs %s)", km(t.distKm),
                    MoonActivity.compass(t.bearingDeg), km(2 * t.halfWidthKm), MoonActivity.compass(t.pathDeg) + "–" + MoonActivity.compass(t.pathDeg + 180)));
        }
        sb.append(String.format(Locale.US, ". The Moon %.0f° up in the %s, %.0f%% lit", t.moonAlt, MoonActivity.compass(t.moonAz), t.illum * 100));
        sb.append(t.sunAlt > -0.8 ? ", in daylight (the station shows as a dark dot)." : t.sunAlt > -12 ? ", at dusk." : ", at night.");
        if (t.sizeArcsec > 0) sb.append(String.format(Locale.US, " It's %.0f arcseconds across, %.0f km away.", t.sizeArcsec, t.rangeKm));
        return sb.toString();
    }

    static String day(long t) {
        long now = System.currentTimeMillis();
        SimpleDateFormat d = new SimpleDateFormat("EEE d MMM", Locale.US), c = new SimpleDateFormat("h:mm:ss a", Locale.US);
        String when = c.format(new Date(t));
        String today = d.format(new Date(now)), that = d.format(new Date(t)), tomorrow = d.format(new Date(now + 86_400_000L));
        if (that.equals(today)) return "Today at " + when;
        if (that.equals(tomorrow)) return "Tomorrow at " + when;
        return that + " at " + when;
    }

    static String km(double v) {
        return v < 10 ? String.format(Locale.US, "%.1f km", v) : String.format(Locale.US, "%.0f km", v);
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** The countdown in words: "in 3 h 12 min", "in 4 min 05 s", "in 9 s". */
    static String countdown(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        if (s >= 3600) return String.format(Locale.US, "in %d h %02d min", s / 3600, (s % 3600) / 60);
        if (s >= 60) return String.format(Locale.US, "in %d min %02d s", s / 60, s % 60);
        return "in " + s + " s";
    }
}
