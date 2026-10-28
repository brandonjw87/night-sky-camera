package com.brandon.nightsky.processing;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * What the camera check found the night's camera really does, measured with the lens covered: whether the night's
 * long exposure comes out in full, how many frames it takes in a row before it stops (if it does) and what gets it
 * going again, the pause between frames, and its sensor in the dark. Kept per camera and system build; sessions
 * plan with it, and the check's page and its shared text say it in words.
 */
public final class CameraFacts {
    /** When the check ran (wall clock, ms). */
    public long timeMs;
    /** The app's version, the phone, and what the camera says about itself, for the shared text. */
    public String version = "", phone = "", camera = "";
    /** ISO of the run and the long exposure. */
    public int iso;
    /** Battery temperature at the end (C). */
    public double tempC = Double.NaN;

    /** How bright the last light check was over black, as a share of the range. */
    public double lit = Double.NaN;
    /** The lens was covered, so the dark was measured. */
    public boolean dark;
    /** Why the check ended before it was through ("" if it got through). */
    public String stoppedBy = "";

    /** The exposure the nights use here (trail-free, the planner's longest), and how each long one came out. */
    public long nightNs;
    public final List<Exposure> exposures = new ArrayList<Exposure>();

    /** One long exposure. */
    public static final class Exposure {
        public long askedNs;
        /** What the camera says it gave (0 if it failed). */
        public long gotNs;
        /** From asking to the picture arriving (ms). */
        public long backMs;

        public boolean failed() {
            return gotNs <= 0;
        }

        /** Came out in full (within the 7% a camera's own rounding may take). */
        public boolean full() {
            return gotNs >= askedNs * 0.93;
        }
    }

    /** The run of short frames: their length, how many were asked for, came back and failed. */
    public long runExpNs;
    public int runAsked, runDone, runFailed;
    /** Fewer frames in a row than this before a stop isn't a count the camera keeps to (sessions' watchdog handles it). */
    public static final int MIN_RUN = 8;

    /** Times the camera stopped (no frame for too long). */
    public int stops;
    /** Frames in a row in one capture session before the first stop, and before the next one (0: no stop). */
    public int stopAfter, stopAgainAfter;
    /** What got it going again: "session" (a fresh capture session), "reopen" (closing and opening it), "none". */
    public String cure = "";
    /** Median and longest pause between one exposure's end and the next one's start (ms). */
    public double gapMs = Double.NaN, gapMaxMs = Double.NaN;

    /** In the dark (from the run's frames): each colour's level and what the camera reports for it (ADU). */
    public double[] black = nan4(), blackReported = nan4();
    /** Spread of single pixels, read noise (from a pair of frames), what the noise profile says, row banding (ADU). */
    public double noise = Double.NaN, readNoise = Double.NaN, profileNoise = Double.NaN, rows = Double.NaN;
    /** Hot pixels in the long dark exposure, of how many; that exposure's length (s); the dark signal (ADU per s). */
    public int hot = -1;
    public long pixels;
    public double hotExpS = Double.NaN, darkPerS = Double.NaN;

    private static double[] nan4() {
        return new double[]{Double.NaN, Double.NaN, Double.NaN, Double.NaN};
    }

    /** The night's long exposure (the first try of it), or null if it wasn't taken. */
    public Exposure night() {
        for (Exposure e : exposures) if (e.askedNs == nightNs) return e;
        return null;
    }

    /** The longest exposure that came out in full (ns), 0 if none did. */
    public long longestFull() {
        long best = 0;
        for (Exposure e : exposures) if (!e.failed() && e.full()) best = Math.max(best, e.askedNs);
        return best;
    }

    /** What the camera cut the night's exposure to (ns), 0 if it didn't. */
    public long cutTo() {
        Exposure e = night();
        return e != null && !e.failed() && !e.full() ? e.gotNs : 0;
    }

    /** Every try of the night's exposure failed. */
    public boolean nightFailed() {
        boolean any = false;
        for (Exposure e : exposures) {
            if (e.askedNs != nightNs) continue;
            if (!e.failed()) return false;
            any = true;
        }
        return any;
    }

    /** The check got through: the run and the night's exposure were tried. */
    public boolean complete() {
        return runDone > 0 && night() != null;
    }

    /**
     * Frames to take in one capture session before renewing it (0: no need), as sessions use it: a few short of the
     * fewest frames in a row it stopped after, of the counts long enough to be one the camera keeps to.
     */
    public int renewAfter() {
        if ("none".equals(cure)) return 0;
        int k = Integer.MAX_VALUE;
        if (stopAfter >= MIN_RUN) k = stopAfter;
        if (stopAgainAfter >= MIN_RUN) k = Math.min(k, stopAgainAfter);
        return k == Integer.MAX_VALUE ? 0 : k - 3;
    }

    /** Something a session can't work around: long exposures fail, or the camera stops and nothing restarts it. */
    public boolean trouble() {
        return (nightFailed() && longestFull() == 0) || (stops > 0 && "none".equals(cure));
    }

    /** One line at the top of the result. */
    public String headline() {
        if (!complete()) return "The check didn't finish";
        if (trouble()) return "The camera needs a look";
        if (cutTo() > 0 || nightFailed() || renewAfter() > 0) return "Ready: sessions work around what it found";
        return dark ? "Ready for the night" : "Ready for the night (the dark wasn't measured)";
    }

    /** The findings in words, one sentence or two each: long exposures, frame after frame, the dark. */
    public List<String> lines() {
        List<String> out = new ArrayList<String>();
        if (!stoppedBy.isEmpty()) out.add("The check stopped early: " + lowerFirst(stoppedBy));
        // long exposures
        Exposure n = night();
        String len = seconds(nightNs);
        if (n == null) {
            out.add("Long exposures: not tried (the check stopped before).");
        } else if (nightFailed()) {
            long half = longestFull();
            if (half > 0) {
                out.add(String.format(Locale.US, "Long exposures: %s exposures failed, %s ones came out in full. Sessions try %s "
                        + "first and switch to shorter ones by themselves when they fail.", hyphen(nightNs), hyphen(half), len));
            } else {
                out.add(String.format(Locale.US, "Long exposures: every long exposure failed, %s and shorter. Restart the phone "
                        + "and check again.", len));
            }
        } else if (n.full()) {
            out.add(String.format(Locale.US, "Long exposures: %s, the length nights use here, came out in full.", len));
        } else {
            int k = (int) Math.max(1, Math.round(nightNs / (double) n.gotNs));
            out.add(String.format(Locale.US, "Long exposures: asked for %s, the camera gave %s. Sessions now plan with that "
                            + "from the start: %s %s exposures added together make each frame.", len, seconds(n.gotNs), small(k),
                    hyphen(n.gotNs)));
        }
        // frame after frame
        String run = String.format(Locale.US, "%d %s frames", runDone, hyphen(runExpNs));
        if (runDone == 0) {
            out.add("Frame after frame: no frame came back.");
        } else if (stops == 0) {
            out.add(String.format(Locale.US, "Frame after frame: %s in a row without a stop%s.", run,
                    Double.isNaN(gapMs) ? "" : String.format(Locale.US, ", %s between them", ms(gapMs))));
        } else {
            String how = "reopen".equals(cure) ? "closing and opening the camera" : "a fresh capture session";
            String stopped = stopAgainAfter > 0
                    ? String.format(Locale.US, "the camera stopped after %d frames in a row, and again after %d more", stopAfter, stopAgainAfter)
                    : stopAfter > 0 ? String.format(Locale.US, "the camera stopped once, after %d frames in a row", stopAfter)
                    : "the camera stopped once, at the start";
            if ("none".equals(cure)) {
                out.add(String.format(Locale.US, "Frame after frame: %s, and nothing got it going again (a fresh capture session, "
                        + "then opening it again). A session would stop there too: restart the phone and check again.", stopped));
            } else if (renewAfter() > 0) {
                out.add(String.format(Locale.US, "Frame after frame: %s; %s got it going. Sessions now do that every %d frames, "
                                + "a pause of about %s instead of the minute it takes to notice a stop.", stopped, how, renewAfter(),
                        "reopen".equals(cure) ? "2 seconds" : "a second"));
            } else {
                // too soon to be a count it keeps to: a stall, which sessions get past by themselves
                out.add(String.format(Locale.US, "Frame after frame: %s; %s got it going, then %s came in all. A session does "
                        + "that by itself when the camera stalls.", stopped, how, run));
            }
        }
        // the dark
        if (!dark) {
            out.add(Double.isNaN(lit) ? "In the dark: not measured."
                    : String.format(Locale.US, "In the dark: not measured, as light reached the sensor (%.0f%% of full). To "
                    + "measure it, check again in a dim room, with the camera against the table or in a drawer.", 100 * lit));
        } else {
            StringBuilder b = new StringBuilder("In the dark");
            b.append(String.format(Locale.US, " (ISO %d%s): ", iso, Double.isNaN(tempC) ? "" : String.format(Locale.US, ", %.0f °C", tempC)));
            if (hot >= 0 && pixels > 0) {
                b.append(String.format(Locale.US, "%,d hot pixels in a %s exposure (%s of the sensor), which every photo takes "
                        + "out; ", hot, hyphen(Math.round(hotExpS * 1e9)), share(hot / (double) pixels)));
            }
            b.append(String.format(Locale.US, "noise %.1f levels.", Double.isNaN(readNoise) ? noise : readNoise));
            double bm = mean(black), br = mean(blackReported);
            if (!Double.isNaN(bm) && !Double.isNaN(br) && Math.abs(bm - br) > Math.max(1.0, 0.5 * (Double.isNaN(noise) ? 0 : noise))) {
                b.append(String.format(Locale.US, " The black level measured %.1f against the %.1f the camera reports.", bm, br));
            }
            out.add(b.toString());
        }
        return out;
    }

    /** One line for a session's log: what the check found, and when. */
    public String logLine(long nowMs) {
        StringBuilder b = new StringBuilder("camera check ");
        b.append(ago(nowMs - timeMs)).append(": ");
        Exposure n = night();
        if (n == null) b.append("exposure not tried");
        else if (nightFailed()) b.append(String.format(Locale.US, "%.1f s failed", nightNs / 1e9));
        else if (n.full()) b.append(String.format(Locale.US, "%.1f s in full", nightNs / 1e9));
        else b.append(String.format(Locale.US, "%.1f s cut to %.2f s", nightNs / 1e9, n.gotNs / 1e9));
        if (stops > 0) {
            b.append(String.format(Locale.US, "; stopped after %d in a row%s (cure: %s)", stopAfter,
                    stopAgainAfter > 0 ? " and " + stopAgainAfter : "", cure));
        } else if (runDone > 0) {
            b.append(String.format(Locale.US, "; %d in a row without a stop", runDone));
        }
        if (!Double.isNaN(gapMs)) b.append(String.format(Locale.US, "; %.0f ms between frames", gapMs));
        if (dark && hot >= 0) b.append(String.format(Locale.US, "; %d hot pixels", hot));
        return b.toString();
    }

    /** Everything measured, for sharing. */
    public String text() {
        StringBuilder b = new StringBuilder();
        b.append("Night Sky Camera ").append(version).append(" camera check, ")
                .append(new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new java.util.Date(timeMs))).append('\n');
        if (!phone.isEmpty()) b.append(phone).append('\n');
        if (!camera.isEmpty()) b.append(camera).append('\n');
        b.append('\n').append(headline()).append('\n');
        for (String l : lines()) b.append("- ").append(l).append('\n');
        b.append("\nMeasured:\n");
        b.append(String.format(Locale.US, "light check: %s%n", Double.isNaN(lit) ? "none" : String.format(Locale.US, "%.1f%% of the range over black (%s)",
                100 * lit, dark ? "covered" : "lit")));
        for (Exposure e : exposures) {
            b.append(String.format(Locale.US, "exposure %.2f s at ISO %d: %s, back after %.1f s%n", e.askedNs / 1e9, iso,
                    e.failed() ? "failed" : String.format(Locale.US, "gave %.3f s", e.gotNs / 1e9), e.backMs / 1000.0));
        }
        b.append(String.format(Locale.US, "run: %d of %.2f s asked, %d back, %d failed, %d stops (after %d, then %d; cure %s); pause between "
                        + "frames %s median, %s longest%n", runAsked, runExpNs / 1e9, runDone, runFailed, stops, stopAfter, stopAgainAfter,
                cure.isEmpty() ? "-" : cure, Double.isNaN(gapMs) ? "-" : String.format(Locale.US, "%.0f ms", gapMs),
                Double.isNaN(gapMaxMs) ? "-" : String.format(Locale.US, "%.0f ms", gapMaxMs)));
        if (dark) {
            b.append(String.format(Locale.US, "dark: black %.1f/%.1f/%.1f/%.1f (reported %.1f/%.1f/%.1f/%.1f), spread %.2f, read noise %.2f "
                            + "(noise profile %.2f), rows %.2f; %d hot of %d in %.1f s; dark signal %.4f per s%n",
                    black[0], black[1], black[2], black[3], blackReported[0], blackReported[1], blackReported[2], blackReported[3],
                    noise, readNoise, profileNoise, rows, hot, pixels, hotExpS, darkPerS));
        }
        if (!Double.isNaN(tempC)) b.append(String.format(Locale.US, "battery %.1f C%n", tempC));
        b.append("sessions: ").append(renewAfter() > 0 ? "a fresh capture session every " + renewAfter() + " frames" : "no capture renewal");
        if (cutTo() > 0) b.append(String.format(Locale.US, "; frames of %.2f s", cutTo() / 1e9));
        b.append('\n');
        return b.toString();
    }

    // ---- saved as text (Properties) ----

    public String encode() {
        Properties p = new Properties();
        p.setProperty("time", Long.toString(timeMs));
        p.setProperty("version", version);
        p.setProperty("phone", phone);
        p.setProperty("camera", camera);
        p.setProperty("iso", Integer.toString(iso));
        put(p, "temp", tempC);
        put(p, "lit", lit);
        p.setProperty("dark", Boolean.toString(dark));
        p.setProperty("night", Long.toString(nightNs));
        StringBuilder ex = new StringBuilder();
        for (Exposure e : exposures) {
            if (ex.length() > 0) ex.append(';');
            ex.append(e.askedNs).append(',').append(e.gotNs).append(',').append(e.backMs);
        }
        p.setProperty("exposures", ex.toString());
        p.setProperty("run", runExpNs + "," + runAsked + "," + runDone + "," + runFailed);
        p.setProperty("stops", stops + "," + stopAfter + "," + stopAgainAfter);
        p.setProperty("cure", cure);
        put(p, "gap", gapMs);
        put(p, "gapMax", gapMaxMs);
        p.setProperty("black", join(black));
        p.setProperty("blackReported", join(blackReported));
        put(p, "noise", noise);
        put(p, "readNoise", readNoise);
        put(p, "profileNoise", profileNoise);
        put(p, "rows", rows);
        p.setProperty("hot", hot + "," + pixels);
        put(p, "hotExp", hotExpS);
        put(p, "darkPerS", darkPerS);
        StringWriter w = new StringWriter();
        try {
            p.store(w, null);
        } catch (IOException e) {
            return "";
        }
        return w.toString();
    }

    /** Null if there's nothing saved (or it can't be read). */
    public static CameraFacts parse(String s) {
        if (s == null || s.isEmpty()) return null;
        Properties p = new Properties();
        try {
            p.load(new StringReader(s));
            CameraFacts f = new CameraFacts();
            f.timeMs = Long.parseLong(p.getProperty("time", "0"));
            if (f.timeMs <= 0) return null;
            f.version = p.getProperty("version", "");
            f.phone = p.getProperty("phone", "");
            f.camera = p.getProperty("camera", "");
            f.iso = Integer.parseInt(p.getProperty("iso", "0"));
            f.tempC = num(p, "temp");
            f.lit = num(p, "lit");
            f.dark = Boolean.parseBoolean(p.getProperty("dark", "false"));
            f.nightNs = Long.parseLong(p.getProperty("night", "0"));
            String ex = p.getProperty("exposures", "");
            if (!ex.isEmpty()) {
                for (String one : ex.split(";")) {
                    String[] q = one.split(",");
                    Exposure e = new Exposure();
                    e.askedNs = Long.parseLong(q[0]);
                    e.gotNs = Long.parseLong(q[1]);
                    e.backMs = Long.parseLong(q[2]);
                    f.exposures.add(e);
                }
            }
            String[] r = p.getProperty("run", "0,0,0,0").split(",");
            f.runExpNs = Long.parseLong(r[0]);
            f.runAsked = Integer.parseInt(r[1]);
            f.runDone = Integer.parseInt(r[2]);
            f.runFailed = Integer.parseInt(r[3]);
            String[] st = p.getProperty("stops", "0,0,0").split(",");
            f.stops = Integer.parseInt(st[0]);
            f.stopAfter = Integer.parseInt(st[1]);
            f.stopAgainAfter = Integer.parseInt(st[2]);
            f.cure = p.getProperty("cure", "");
            f.gapMs = num(p, "gap");
            f.gapMaxMs = num(p, "gapMax");
            f.black = split4(p.getProperty("black", ""));
            f.blackReported = split4(p.getProperty("blackReported", ""));
            f.noise = num(p, "noise");
            f.readNoise = num(p, "readNoise");
            f.profileNoise = num(p, "profileNoise");
            f.rows = num(p, "rows");
            String[] h = p.getProperty("hot", "-1,0").split(",");
            f.hot = Integer.parseInt(h[0]);
            f.pixels = Long.parseLong(h[1]);
            f.hotExpS = num(p, "hotExp");
            f.darkPerS = num(p, "darkPerS");
            return f;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---- words and numbers ----

    /** "16 seconds", "2.5 seconds", "1 second". */
    public static String seconds(long ns) {
        double s = ns / 1e9;
        if (Math.abs(s - Math.rint(s)) < 0.05) {
            long k = Math.round(s);
            return k + (k == 1 ? " second" : " seconds");
        }
        return String.format(Locale.US, "%.1f seconds", s);
    }

    /** "16-second", "2.5-second", "1-second". */
    static String hyphen(long ns) {
        return seconds(ns).replace(" seconds", "-second").replace(" second", "-second");
    }

    /** Zero to ten in words, larger numbers in figures. */
    static String small(int k) {
        String[] w = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
        return k >= 0 && k < w.length ? w[k] : Integer.toString(k);
    }

    static String ms(double v) {
        return v >= 995 ? String.format(Locale.US, "%.1f s", v / 1000) : String.format(Locale.US, "%.0f ms", v);
    }

    /** A small share as a percentage that shows its size ("0.01%", "0.3%", "2%"). */
    static String share(double s) {
        double pc = 100 * s;
        if (pc >= 1) return String.format(Locale.US, "%.0f%%", pc);
        if (pc >= 0.1) return String.format(Locale.US, "%.1f%%", pc);
        if (pc >= 0.01) return String.format(Locale.US, "%.2f%%", pc);
        return pc > 0 ? "under 0.01%" : "none";
    }

    static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** "just now", "5 minutes ago", "3 hours ago", "2 days ago". */
    public static String ago(long ms) {
        long min = Math.max(0, ms) / 60_000L;
        if (min < 2) return "just now";
        if (min < 120) return min + " minutes ago";
        long h = min / 60;
        if (h < 48) return h + " hours ago";
        return h / 24 + " days ago";
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) {
            if (Double.isNaN(v)) return Double.NaN;
            s += v;
        }
        return s / a.length;
    }

    private static void put(Properties p, String k, double v) {
        if (!Double.isNaN(v)) p.setProperty(k, Double.toString(v));
    }

    private static double num(Properties p, String k) {
        String v = p.getProperty(k);
        return v == null ? Double.NaN : Double.parseDouble(v);
    }

    private static String join(double[] a) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) b.append(',');
            b.append(a[i]);
        }
        return b.toString();
    }

    private static double[] split4(String s) {
        double[] out = nan4();
        if (s.isEmpty()) return out;
        String[] q = s.split(",");
        for (int i = 0; i < 4 && i < q.length; i++) out[i] = Double.parseDouble(q[i]);
        return out;
    }
}
