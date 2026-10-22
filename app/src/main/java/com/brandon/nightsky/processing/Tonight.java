package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.JsonTree;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Tonight at a glance (the first pack's D1, smallest version): when the sky is fully dark (the Sun 18° down),
 * the Moon (how much is lit, when it rises or sets, how much it brightens the sky straight up), and, when a
 * forecast has been downloaded, cloud by layer and dew. Every 10 minutes of the night gets a score from 0 to
 * 100; the best stretch is "the best window", which scheduled sessions can start at.
 */
public final class Tonight {
    /** The Sun's altitude at which the sky is fully dark, and at the end of nautical twilight (degrees). */
    public static final double DARK_DEG = -18, NAUTICAL_DEG = -12, CIVIL_DEG = -6;
    /** The Moon's centre at rising and setting, seen from the ground (refraction and its radius, degrees). */
    public static final double MOON_HORIZON_DEG = -0.8;
    /** Slots the night is scored in. */
    public static final long SLOT_MS = 10 * 60_000L;
    /** A sky this bright (V mag per square arcsecond) is assumed when the site's own isn't known: a suburban backyard. */
    public static final double DEFAULT_SKY_MAG = 21.0;
    /** No window is called good below this score. */
    public static final int MIN_GOOD = 25;

    public static final class Slot {
        public long ms;
        public double sunAlt, moonAlt;
        /** Cloud cover from the forecast (%, NaN if none), and whether dew is likely. */
        public double cloud = Double.NaN;
        public boolean dewRisk;
        public double darkFactor, moonFactor, cloudFactor = 1, dewFactor = 1;
        public int score;
    }

    /** An hourly forecast (Open-Meteo's fields), times in Unix ms; values NaN where missing. */
    public static final class Forecast {
        public long[] ms = new long[0];
        public double[] cloud, low, mid, high, rh, dew, temp, wind, vis;
        public long fetchedMs;
        public double latDeg = Double.NaN, lonDeg = Double.NaN;

        /** Reads Open-Meteo's answer (asked for with timeformat=unixtime); throws IllegalArgumentException if it can't. */
        public static Forecast fromOpenMeteo(String json, long fetchedMs) {
            JsonTree.Obj o = JsonTree.object(json);
            JsonTree.Obj h = o.obj("hourly");
            if (h == null) throw new IllegalArgumentException("no hourly forecast in the answer");
            double[] t = h.nums("time");
            if (t.length == 0) throw new IllegalArgumentException("an empty forecast");
            Forecast f = new Forecast();
            f.ms = new long[t.length];
            for (int i = 0; i < t.length; i++) f.ms[i] = Math.round(t[i] * 1000);
            f.cloud = field(h, "cloud_cover", t.length);
            f.low = field(h, "cloud_cover_low", t.length);
            f.mid = field(h, "cloud_cover_mid", t.length);
            f.high = field(h, "cloud_cover_high", t.length);
            f.rh = field(h, "relative_humidity_2m", t.length);
            f.dew = field(h, "dew_point_2m", t.length);
            f.temp = field(h, "temperature_2m", t.length);
            f.wind = field(h, "wind_speed_10m", t.length);
            f.vis = field(h, "visibility", t.length);
            f.latDeg = o.num("latitude", Double.NaN);
            f.lonDeg = o.num("longitude", Double.NaN);
            f.fetchedMs = fetchedMs;
            return f;
        }

        private static double[] field(JsonTree.Obj h, String k, int n) {
            double[] v = h.nums(k);
            double[] out = new double[n];
            for (int i = 0; i < n; i++) out[i] = i < v.length ? v[i] : Double.NaN;
            return out;
        }

        /** The hour holding {@code t} (within 45 minutes of its start or the next), or -1. */
        public int index(long t) {
            int best = -1;
            long bd = Long.MAX_VALUE;
            for (int i = 0; i < ms.length; i++) {
                long d = Math.abs(ms[i] + 30 * 60_000L - t);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            return bd <= 45 * 60_000L ? best : -1;
        }

        /** Whether it covers any of the night from {@code from} to {@code to}. */
        public boolean covers(long from, long to) {
            return ms.length > 0 && ms[0] <= to && ms[ms.length - 1] + 3600_000L >= from;
        }
    }

    /** The span looked at: from now (or the evening) to the morning. */
    public long fromMs, toMs;
    /** Full darkness (0 if it doesn't begin or end in the span), sunset and sunrise, the darkest moment. */
    public long duskMs, dawnMs, sunsetMs, sunriseMs, civilDawnMs, nauticalDuskMs, nauticalDawnMs;
    public long darkestMs;
    public double darkestSunAlt = Double.NaN;
    /** It's already fully dark at {@link #fromMs}. */
    public boolean darkNow;
    /** Moonrise and moonset within the night (0 if none), whether it's up at nightfall, and how much is lit. */
    public long moonRiseMs, moonSetMs;
    public boolean moonUpAtDusk;
    public double moonIllum = Double.NaN;
    public final List<Slot> slots = new ArrayList<Slot>();
    /** The best window (0 if no stretch of the night is good), its average score, and the night's best slot. */
    public long bestStartMs, bestEndMs;
    public int bestScore, maxScore;
    /** Tonight's score: the best window's average (0 if none). */
    public int score;
    public boolean hasForecast;
    public long forecastAgeMs = -1;
    /** One line, for the widget and the home screen: "Tonight 82/100 · best 11:10 PM to 4:40 AM". */
    public String headline = "";
    /** A few lines: darkness, the Moon, clouds and dew, the forecast's source. */
    public final List<String> lines = new ArrayList<String>();
    public double latDeg, lonDeg;
    public TimeZone zone;

    private Tonight() {}

    /**
     * Works out the night after {@code nowMs} (the one under way if it's dark already) at a place, with a
     * forecast if there is one (null otherwise) and the site's sky brightness if known (NaN for a suburban sky).
     */
    public static Tonight compute(double latDeg, double lonDeg, long nowMs, TimeZone zone, Forecast fc, double skyMag) {
        Tonight t = new Tonight();
        t.latDeg = latDeg;
        t.lonDeg = lonDeg;
        t.zone = zone;
        double lat = latDeg * Astro.DEG, lon = lonDeg * Astro.DEG;
        double sunNow = sunAlt(nowMs, lat, lon);
        t.darkNow = sunNow <= DARK_DEG;
        // the night: from now if it's under way (sunset to sunrise), else from the next sunset
        long from;
        if (sunNow < -0.833) {
            from = nowMs;
            t.sunsetMs = crossing(nowMs, -16 * 3600_000L, -0.833, lat, lon, false, true);
        } else {
            t.sunsetMs = crossing(nowMs, 26 * 3600_000L, -0.833, lat, lon, false, true);
            from = t.sunsetMs > 0 ? t.sunsetMs : nowMs;
        }
        t.sunriseMs = crossing(from, 26 * 3600_000L, -0.833, lat, lon, true, true);
        long to = t.sunriseMs > 0 ? t.sunriseMs : from + 16 * 3600_000L;
        t.fromMs = from;
        t.toMs = to;
        // darkness: the -18° crossings around this night (searching back from now if it's dark already)
        if (t.darkNow) {
            t.duskMs = crossing(nowMs, -16 * 3600_000L, DARK_DEG, lat, lon, false, true);
            if (t.duskMs == 0) t.duskMs = nowMs;
        } else {
            t.duskMs = crossing(Math.max(nowMs, from - 3600_000L), to - Math.max(nowMs, from - 3600_000L), DARK_DEG, lat, lon, false, true);
        }
        t.dawnMs = crossing(Math.max(nowMs, t.duskMs), to - Math.max(nowMs, t.duskMs) + 3600_000L, DARK_DEG, lat, lon, true, true);
        t.nauticalDuskMs = crossing(Math.max(from - 3600_000L, nowMs - 16 * 3600_000L), to - from + 3600_000L, NAUTICAL_DEG, lat, lon, false, true);
        t.nauticalDawnMs = crossing(from, to - from + 3600_000L, NAUTICAL_DEG, lat, lon, true, true);
        t.civilDawnMs = crossing(from, to - from + 3600_000L, CIVIL_DEG, lat, lon, true, true);
        // the darkest moment
        double minAlt = 90;
        for (long m = from; m <= to; m += SLOT_MS) {
            double a = sunAlt(m, lat, lon);
            if (a < minAlt) {
                minAlt = a;
                t.darkestMs = m;
            }
        }
        t.darkestSunAlt = minAlt;
        // the Moon
        long nightStart = t.duskMs > 0 ? t.duskMs : from;
        long nightEnd = t.dawnMs > 0 ? t.dawnMs : to;
        t.moonUpAtDusk = moonAlt(nightStart, lat, lon) > MOON_HORIZON_DEG;
        t.moonRiseMs = moonCrossing(Math.min(nightStart, Math.max(nowMs, from)), nightEnd, lat, lon, true);
        t.moonSetMs = moonCrossing(Math.min(nightStart, Math.max(nowMs, from)), nightEnd, lat, lon, false);
        long mid = (nightStart + nightEnd) / 2;
        t.moonIllum = Astro.moonIllumination(Astro.jdTt(Astro.jdUtc(mid)));
        // forecast
        t.hasForecast = fc != null && fc.covers(Math.max(nowMs, from), to);
        if (fc != null) t.forecastAgeMs = nowMs - fc.fetchedMs;
        // the slots
        double darkMag = Double.isNaN(skyMag) ? DEFAULT_SKY_MAG : skyMag;
        double bDark = nanoLamberts(darkMag);
        for (long m = Math.max(nowMs, from) / SLOT_MS * SLOT_MS; m < to; m += SLOT_MS) {
            if (m < nowMs - SLOT_MS) continue;
            Slot s = new Slot();
            s.ms = m;
            long c = m + SLOT_MS / 2;
            s.sunAlt = sunAlt(c, lat, lon);
            s.moonAlt = moonAlt(c, lat, lon);
            s.darkFactor = s.sunAlt <= DARK_DEG ? 1 : s.sunAlt >= NAUTICAL_DEG ? 0 : 0.25 + 0.75 * (NAUTICAL_DEG - s.sunAlt) / (NAUTICAL_DEG - DARK_DEG);
            if (s.moonAlt <= MOON_HORIZON_DEG) {
                s.moonFactor = 1;
            } else {
                double z = 90 - Math.max(0, s.moonAlt);
                double b = ReportCard.moonSkyNl(t.moonIllum, z, z, 0);
                s.moonFactor = bDark / (bDark + b);
            }
            if (t.hasForecast) {
                int i = fc.index(c);
                if (i >= 0) {
                    double eff = effectiveCloud(fc, i);
                    if (!Double.isNaN(eff)) {
                        s.cloud = eff;
                        s.cloudFactor = 1 - 0.95 * eff / 100;
                    }
                    double dew = fc.dew[i], temp = fc.temp[i], rh = fc.rh[i];
                    s.dewRisk = (!Double.isNaN(dew) && !Double.isNaN(temp) && temp - dew <= 2.5) || (!Double.isNaN(rh) && rh >= 92);
                    if (s.dewRisk) s.dewFactor = 0.92;
                }
            }
            s.score = (int) Math.round(100 * s.darkFactor * s.moonFactor * s.cloudFactor * s.dewFactor);
            t.slots.add(s);
        }
        t.bestWindow();
        t.describe(nowMs);
        return t;
    }

    /** Low and middle cloud block the stars; high cloud (cirrus) dims them: the worst of the layers counts. */
    static double effectiveCloud(Forecast fc, int i) {
        double low = fc.low[i], mid = fc.mid[i], high = fc.high[i];
        if (!Double.isNaN(low) || !Double.isNaN(mid) || !Double.isNaN(high)) {
            double e = 0;
            if (!Double.isNaN(low)) e = Math.max(e, low);
            if (!Double.isNaN(mid)) e = Math.max(e, mid);
            if (!Double.isNaN(high)) e = Math.max(e, 0.6 * high);
            return Math.min(100, e);
        }
        return fc.cloud[i];
    }

    /** Sky brightness in nanolamberts from V magnitudes per square arcsecond (Krisciunas and Schaefer 1991). */
    static double nanoLamberts(double vMag) {
        return 34.08 * Math.exp(20.7233 - 0.92104 * vMag);
    }

    /**
     * The best window: the dark stretch scoring within 12 of the night's best (and at least {@link #MIN_GOOD})
     * with the most score in it.
     */
    private void bestWindow() {
        int max = 0;
        for (Slot s : slots) if (s.sunAlt <= DARK_DEG) max = Math.max(max, s.score);
        maxScore = max;
        if (max < MIN_GOOD) return;
        int thr = Math.max(MIN_GOOD, max - 12);
        int bestA = -1, bestB = -1;
        long bestSum = -1;
        for (int i = 0; i < slots.size(); ) {
            if (slots.get(i).sunAlt > DARK_DEG || slots.get(i).score < thr) {
                i++;
                continue;
            }
            int j = i;
            long sum = 0;
            while (j < slots.size() && slots.get(j).sunAlt <= DARK_DEG && slots.get(j).score >= thr) sum += slots.get(j++).score;
            if (sum > bestSum) {
                bestSum = sum;
                bestA = i;
                bestB = j;
            }
            i = j;
        }
        if (bestA < 0) return;
        bestStartMs = slots.get(bestA).ms;
        bestEndMs = slots.get(bestB - 1).ms + SLOT_MS;
        if (dawnMs > 0) bestEndMs = Math.min(bestEndMs, dawnMs);
        if (bestStartMs < duskMs) bestStartMs = duskMs;
        bestScore = (int) Math.round(bestSum / (double) (bestB - bestA));
        score = bestScore;
    }

    private void describe(long nowMs) {
        String ws = bestStartMs > 0 ? clock(bestStartMs) + " to " + clock(bestEndMs) : "";
        if (duskMs == 0 && dawnMs == 0 && !darkNow) {
            headline = "No full darkness tonight";
        } else if (bestStartMs > 0) {
            headline = String.format(Locale.US, "Tonight %d/100 · best %s", score, ws);
        } else {
            headline = String.format(Locale.US, "Tonight %d/100 · %s", maxScore, poorReason());
        }
        // darkness
        if (darkNow && dawnMs > 0) {
            lines.add("Fully dark now, until " + clock(dawnMs) + ".");
        } else if (duskMs > 0 && dawnMs > 0) {
            lines.add("Fully dark from " + clock(duskMs) + " to " + clock(dawnMs) + ".");
        } else if (duskMs > 0) {
            lines.add("Fully dark from " + clock(duskMs) + ".");
        } else {
            lines.add(String.format(Locale.US, "The Sun doesn't get 18° below the horizon tonight: the darkest is at %s (Sun %.0f° down).",
                    clock(darkestMs), -darkestSunAlt));
        }
        // the Moon
        int pct = (int) Math.round(100 * moonIllum);
        String moon;
        if (pct <= 2) {
            moon = "New Moon: no moonlight.";
        } else if (moonUpAtDusk && moonSetMs > 0) {
            moon = String.format(Locale.US, "Moon %d%% lit, sets at %s.", pct, clock(moonSetMs));
        } else if (moonUpAtDusk) {
            moon = String.format(Locale.US, "Moon %d%% lit, up all night.", pct);
        } else if (moonRiseMs > 0) {
            moon = String.format(Locale.US, "Moon %d%% lit, rises at %s.", pct, clock(moonRiseMs));
        } else {
            moon = String.format(Locale.US, "Moon %d%% lit, below the horizon all night.", pct);
        }
        lines.add(moon);
        // clouds and dew
        if (hasForecast) {
            String c = cloudStory();
            if (!c.isEmpty()) lines.add(c);
            long dewFrom = 0;
            for (Slot s : slots) {
                if (s.dewRisk && s.sunAlt <= NAUTICAL_DEG) {
                    dewFrom = s.ms;
                    break;
                }
            }
            if (dewFrom > 0) lines.add("Dew likely from " + clock(dewFrom) + ": the lens may fog, so check it before a long session.");
            long h = forecastAgeMs / 3600_000L;
            lines.add("Forecast from Open-Meteo" + (h >= 1 ? ", " + h + " h old." : "."));
        } else {
            lines.add("No cloud forecast yet (it downloads when the phone is online), so this counts only the Sun and the Moon.");
        }
        if (bestStartMs > 0) {
            lines.add(0, "Best window " + ws + String.format(Locale.US, " (score %d/100).", bestScore));
        } else if (duskMs > 0 || darkNow) {
            lines.add(0, "No good window tonight: " + poorReason() + ".");
        }
    }

    private String poorReason() {
        double cloud = 0, moon = 0;
        int n = 0;
        for (Slot s : slots) {
            if (s.sunAlt > DARK_DEG) continue;
            cloud += s.cloudFactor;
            moon += s.moonFactor;
            n++;
        }
        if (n == 0) return "it doesn't get fully dark";
        return cloud <= moon ? "cloudy" : "a bright Moon";
    }

    /** "Clear until 1 AM, then cloudy." from the forecast's effective cover over the dark hours. */
    private String cloudStory() {
        List<int[]> runs = new ArrayList<int[]>(); // {category, first slot, last slot}
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            if (s.sunAlt > DARK_DEG || Double.isNaN(s.cloud)) continue;
            int cat = s.cloud < 20 ? 0 : s.cloud < 60 ? 1 : 2;
            int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last[0] == cat && last[2] == i - 1) last[2] = i;
            else runs.add(new int[]{cat, i, i});
        }
        // fold runs shorter than an hour into their neighbours
        for (int k = 0; k < runs.size() && runs.size() > 1; ) {
            int[] r = runs.get(k);
            if (r[2] - r[1] + 1 >= 6) {
                k++;
                continue;
            }
            if (k > 0) runs.get(k - 1)[2] = r[2];
            else runs.get(1)[1] = r[1];
            runs.remove(k);
            for (int m = 1; m < runs.size(); ) {
                if (runs.get(m)[0] == runs.get(m - 1)[0]) {
                    runs.get(m - 1)[2] = runs.get(m)[2];
                    runs.remove(m);
                } else {
                    m++;
                }
            }
            k = 0;
        }
        if (runs.isEmpty()) return "";
        String[] word = {"clear", "partly cloudy", "cloudy"};
        if (runs.size() == 1) return capital(word[runs.get(0)[0]]) + " all night.";
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < runs.size() && k < 3; k++) {
            int[] r = runs.get(k);
            if (k == 0) sb.append(capital(word[r[0]])).append(" until ").append(clock(slots.get(r[2]).ms + SLOT_MS));
            else if (k == runs.size() - 1 || k == 2) sb.append(", then ").append(word[r[0]]);
            else sb.append(", then ").append(word[r[0]]).append(" until ").append(clock(slots.get(r[2]).ms + SLOT_MS));
        }
        return sb.append('.').toString();
    }

    /** The slot holding {@code t}, or null. */
    public Slot slotAt(long t) {
        for (Slot s : slots) if (t >= s.ms && t < s.ms + SLOT_MS) return s;
        return null;
    }

    /** The Sun's altitude (degrees) at a time. */
    public static double sunAlt(long ms, double lat, double lon) {
        return Astro.altitude(Astro.sunEnu(ms, lat, lon)) / Astro.DEG;
    }

    /** The Moon's altitude (degrees, its centre, parallax included) at a time. */
    public static double moonAlt(long ms, double lat, double lon) {
        return Astro.altitude(Astro.moonEnu(ms, lat, lon)) / Astro.DEG;
    }

    /**
     * When the Sun crosses {@code deg} (rising if {@code up}) between {@code from} and {@code from + span}
     * (a negative span searches back from {@code from}, latest first); 0 if it doesn't. To the minute when
     * {@code fine}.
     */
    static long crossing(long from, long span, double deg, double lat, double lon, boolean up, boolean fine) {
        long step = 10 * 60_000L;
        if (span < 0) {
            long t1 = from;
            double a1 = sunAlt(t1, lat, lon);
            for (long t0 = from - step; t0 >= from + span; t0 -= step) {
                double a0 = sunAlt(t0, lat, lon);
                if (up ? a0 < deg && a1 >= deg : a0 > deg && a1 <= deg) return fine ? refine(t0, t1, deg, lat, lon, up) : t1;
                t1 = t0;
                a1 = a0;
            }
            return 0;
        }
        long t0 = from;
        double a0 = sunAlt(t0, lat, lon);
        for (long t1 = from + step; t1 <= from + span; t1 += step) {
            double a1 = sunAlt(t1, lat, lon);
            if (up ? a0 < deg && a1 >= deg : a0 > deg && a1 <= deg) return fine ? refine(t0, t1, deg, lat, lon, up) : t1;
            t0 = t1;
            a0 = a1;
        }
        return 0;
    }

    private static long refine(long lo, long hi, double deg, double lat, double lon, boolean up) {
        while (hi - lo > 30_000L) {
            long m = (lo + hi) / 2;
            double a = sunAlt(m, lat, lon);
            if (up ? a < deg : a > deg) lo = m;
            else hi = m;
        }
        return (lo + hi) / 2;
    }

    /** The first moonrise (or moonset) between two times, to the minute; 0 if none. */
    static long moonCrossing(long from, long to, double lat, double lon, boolean rise) {
        long step = 10 * 60_000L;
        long t0 = from;
        double a0 = moonAlt(t0, lat, lon);
        for (long t1 = from + step; t1 <= to + step; t1 += step) {
            double a1 = moonAlt(t1, lat, lon);
            boolean hit = rise ? a0 <= MOON_HORIZON_DEG && a1 > MOON_HORIZON_DEG : a0 > MOON_HORIZON_DEG && a1 <= MOON_HORIZON_DEG;
            if (hit) {
                long lo = t0, hi = t1;
                while (hi - lo > 30_000L) {
                    long m = (lo + hi) / 2;
                    boolean above = moonAlt(m, lat, lon) > MOON_HORIZON_DEG;
                    if (above != rise) lo = m;
                    else hi = m;
                }
                long t = (lo + hi) / 2;
                return t <= to ? t : 0;
            }
            t0 = t1;
            a0 = a1;
        }
        return 0;
    }

    String clock(long ms) {
        return clock(ms, zone);
    }

    /** "10:14 PM" in a time zone. */
    public static String clock(long ms, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.US);
        f.setTimeZone(zone != null ? zone : TimeZone.getDefault());
        return f.format(new Date(ms));
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
