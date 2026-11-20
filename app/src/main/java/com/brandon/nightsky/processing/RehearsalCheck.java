package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * A rehearsal's check (2.1): every step of the session against the sky it was given, whose answers are known
 * ({@link RehearsalSky}). What it can't fake (the camera's frames, the lens moving, the phone's memory, its encoders and
 * its gallery) are the real phone's; what it compares (the focus found, the sky read, where the stars say it pointed,
 * how the frames lined up) has a right answer to compare with.
 */
public final class RehearsalCheck {
    public enum Level { PASS, NOTE, FAIL }

    public static final class Item {
        public final String key, title, text;
        public final Level level;

        public Item(String key, Level level, String title, String text) {
            this.key = key;
            this.level = level;
            this.title = title;
            this.text = text;
        }

        public String line() {
            return (level == Level.PASS ? "✓ " : level == Level.NOTE ? "• " : "✗ ") + title + ": " + text;
        }
    }

    /** What the check looks at. */
    public static final class Input {
        /** The session's settings: the sky drawn ("rehearsal.*"), how the capture went ("capture.*", "focus.*", "preflight.*"). */
        public Properties props = new Properties();
        /** What developing made (null if it failed). */
        public PostProcessor.Output out;
        /** The session's clock (camera timestamps to UTC). */
        public SessionSky sky;
        /** What was saved: the photo, its HDR brightness map (if asked for), its video inside (Motion Photo), the DNG, the log. */
        public boolean photoSaved, hdrWanted, hdrSaved, videoWanted, videoSaved, dngWanted, dngSaved, logSaved;
        /** Why the HDR map or the video wasn't saved ("" if it was, or wasn't asked for). */
        public String hdrWhy = "", videoWhy = "";
        /** How long developing took (s), and the most memory it used of what the app may use (bytes; -1 if unknown). */
        public double developSeconds = Double.NaN;
        public long heapPeak = -1, heapMax = -1;
    }

    public final List<Item> items = new ArrayList<Item>();
    public String headline = "";
    public int passed, notes, failed;

    // ------------------------------------------------------------------------------------------------

    public static RehearsalCheck build(Input in) {
        RehearsalCheck c = new RehearsalCheck();
        Properties p = in.props;
        Truth t = Truth.of(p);
        c.camera(p);
        c.dark(p);
        c.focus(p, t);
        c.skyCheck(p);
        PostProcessor.Output o = in.out;
        if (o == null) {
            c.add("develop", Level.FAIL, "Developing", "it didn't finish, so the frames weren't stacked");
        } else {
            c.solve(o, t);
            c.frames(o);
            c.align(o, t, in.sky);
            c.colour(o);
            c.noise(o);
        }
        c.saving(in);
        c.phone(in, p);
        c.finish();
        return c;
    }

    private void add(String key, Level level, String title, String text) {
        items.add(new Item(key, level, title, text));
    }

    private void finish() {
        passed = notes = failed = 0;
        for (Item it : items) {
            if (it.level == Level.PASS) passed++;
            else if (it.level == Level.NOTE) notes++;
            else failed++;
        }
        int n = items.size();
        if (failed > 0) headline = String.format(Locale.US, "Rehearsal check: %d of %d passed, %d failed", passed, n, failed);
        else if (notes > 0) headline = String.format(Locale.US, "Rehearsal check: %d of %d passed, %d to look at", passed, n, notes);
        else headline = String.format(Locale.US, "Rehearsal check: all %d passed", n);
    }

    /** The frames the camera gave: all asked for, as long as asked, without stopping. */
    private void camera(Properties p) {
        int done = intOf(p, "capture.framesDone"), planned = intOf(p, "capture.framesPlanned");
        int failedFrames = intOf(p, "capture.framesFailed"), dropped = intOf(p, "capture.framesDropped");
        int stalls = intOf(p, "capture.stalls"), renewals = intOf(p, "capture.renewals");
        String stop = p.getProperty("capture.stopReason", ""), stopKey = p.getProperty("capture.stopKey", "");
        double exp = num(p, "frameExposureNs") / 1e9;
        String iso = p.getProperty("iso", "");
        String frames = String.format(Locale.US, "%d of %d frames%s%s", done, planned,
                exp > 0 ? String.format(Locale.US, " of %s", seconds(exp)) : "", iso.isEmpty() ? "" : " at ISO " + iso);
        List<String> extra = new ArrayList<String>();
        if (failedFrames > 0) extra.add(failedFrames + " failed");
        if (dropped > 0) extra.add(dropped + " dropped as processing fell behind");
        if (stalls > 0) extra.add(String.format(Locale.US, "the camera stalled %d time%s and was restarted", stalls, stalls == 1 ? "" : "s"));
        if (renewals > 0) extra.add(String.format(Locale.US, "a fresh capture session %d time%s, as planned for this camera", renewals,
                renewals == 1 ? "" : "s"));
        String cut = p.getProperty("capture.shortWhy", "");
        if (!cut.isEmpty()) extra.add("shorter than asked: " + lower(cut));
        String more = extra.isEmpty() ? "" : " (" + join(extra) + ")";
        if (!stop.isEmpty() && !"you".equals(stopKey)) {
            add("camera", Level.FAIL, "The camera", frames + ", then it stopped: " + stop + more);
        } else if (!stop.isEmpty()) {
            add("camera", Level.NOTE, "The camera", frames + ", stopped early by you" + more);
        } else if (planned > 0 && done >= planned && failedFrames == 0 && dropped == 0 && stalls == 0) {
            add("camera", Level.PASS, "The camera", frames + ", every one in full" + more);
        } else if (done > 0) {
            add("camera", Level.NOTE, "The camera", frames + more);
        } else {
            add("camera", Level.FAIL, "The camera", "no frames came back" + more);
        }
    }

    /** The camera's own light (a covered camera's dark current, or light getting in) against the sky drawn. */
    private void dark(Properties p) {
        double leak = num(p, "rehearsal.leakElectronsPerSec"), sky = num(p, "rehearsal.skyElectronsPerSec");
        if (Double.isNaN(leak) || !(sky > 0)) {
            add("dark", Level.NOTE, "The camera in the dark", "not measured");
            return;
        }
        double share = Math.max(0, leak) / sky;
        String what = String.format(Locale.US, "the frames' own light was %.0f%% of the sky drawn", 100 * share);
        if (share < 0.15) add("dark", Level.PASS, "The camera in the dark", what + ": dark enough");
        else if (share < 1.0) add("dark", Level.NOTE, "The camera in the dark", what + ", which the sky check counted as sky. "
                + "A dimmer room, or the phone flat on a table, keeps it out");
        else add("dark", Level.FAIL, "The camera in the dark", what + ": light reached the camera. Lay the phone flat, screen up, "
                    + "its camera against the table, in a dimmer room");
    }

    /** The focus found against the focus the stars were drawn sharpest at. */
    private void focus(Properties p, Truth t) {
        double found = num(p, "focus.final");
        String how = p.getProperty("rehearsal.focusHow", "");
        if (Double.isNaN(t.bestFocus)) {
            add("focus", Level.NOTE, "Focusing", "this lens has a fixed focus: nothing to find");
            return;
        }
        if (Double.isNaN(found)) {
            add("focus", Level.FAIL, "Focusing", "no focus was recorded");
            return;
        }
        double blur = t.blurPerUnit * Math.abs(found - t.bestFocus);
        String what = String.format(Locale.US, "found %.3f, the stars were drawn sharpest at %.3f: %s", found, t.bestFocus,
                blur < 0.05 ? "spot on" : String.format(Locale.US, "a blur of %.1f px", blur));
        boolean swept = "swept".equals(how) || "checked".equals(how);
        if (!swept) {
            String why = p.getProperty("rehearsal.focusWhy", "");
            add("focus", blur <= 1.0 ? Level.PASS : Level.NOTE, "Focusing", what + " (not swept" + (why.isEmpty() ? "" : ": " + why) + ")");
            return;
        }
        if (blur <= 1.0) add("focus", Level.PASS, "Focusing", what);
        else if (blur <= 2.5) add("focus", Level.NOTE, "Focusing", what + ", a little soft");
        else add("focus", Level.FAIL, "Focusing", what + ", too soft");
    }

    /** The sky check (pre-flight): the sky's brightness read against the sky drawn, and no false alarms in a clear sky. */
    private void skyCheck(Properties p) {
        if (p.getProperty("preflight.summary") == null) {
            add("skycheck", Level.NOTE, "The sky check", "it didn't run (it's off in the settings)");
            return;
        }
        double read = num(p, "rehearsal.pfRead"), expected = num(p, "rehearsal.pfExpected"), drawn = num(p, "rehearsal.pfDrawn");
        String warnings = p.getProperty("preflight.warnings", "").trim();
        List<String> alarms = new ArrayList<String>();
        for (String w : warnings.split("[,;\\s]+")) {
            if (w.isEmpty() || "sun".equals(w) || "satellites".equals(w) || "battery".equals(w) || "storage".equals(w)) continue;
            // the real Moon can be in the frame or near it: that's a true warning
            if ("moon".equals(w)) continue;
            alarms.add(w);
        }
        String alarm = alarms.isEmpty() ? "" : "; it warned of " + join(alarms) + " in a clear sky";
        if (Double.isNaN(read) || Double.isNaN(expected)) {
            add("skycheck", Level.NOTE, "The sky check",
                    "it couldn't read the sky's brightness (too few stars to calibrate it)" + alarm);
            return;
        }
        double off = read - expected;
        String what = String.format(Locale.US, "read the sky at %.2f magnitudes a square arcsecond; %.2f expected (the sky drawn at "
                + "%.2f)%s", read, expected, drawn, alarm);
        if (Math.abs(off) <= 0.35 && alarms.isEmpty()) add("skycheck", Level.PASS, "The sky check", what);
        else if (Math.abs(off) <= 0.7) add("skycheck", Level.NOTE, "The sky check", what);
        else add("skycheck", Level.FAIL, "The sky check", what + String.format(Locale.US, ": %.1f magnitudes off", Math.abs(off)));
    }

    /** Where the stars say the camera pointed, against where it was drawn pointing. */
    private void solve(PostProcessor.Output o, Truth t) {
        if (o.solution == null || Double.isNaN(o.solutionJd)) {
            add("solve", Level.FAIL, "Finding where it pointed", "the stars weren't matched to the catalogue");
            return;
        }
        double[] T = t.atJd(o.solutionJd);
        double err = Rot.angle(Rot.mul(Rot.transpose(T), o.solution.R)) / Astro.DEG;
        double[] c = new double[3];
        Rot.apply(o.solution.R, 0, 0, 1, c);
        double[] rd = Astro.raDec(c);
        String what = String.format(Locale.US, "%d stars matched, pointing at RA %.2f h, Dec %+.1f deg, %s from where it was drawn",
                o.solution.matched, rd[0] / Astro.DEG / 15, rd[1] / Astro.DEG, angle(err));
        if (err < 0.2) add("solve", Level.PASS, "Finding where it pointed", what);
        else if (err < 1) add("solve", Level.NOTE, "Finding where it pointed", what);
        else add("solve", Level.FAIL, "Finding where it pointed", what);
    }

    private void frames(PostProcessor.Output o) {
        String what = String.format(Locale.US, "%d of %d frames stacked", o.unitsUsed, o.unitsTotal);
        List<String> left = new ArrayList<String>();
        for (PostProcessor.FrameReport f : o.frames) if (!f.used && !f.reason.isEmpty()) left.add(f.reason);
        String why = left.isEmpty() ? "" : " (left out: " + join(distinct(left)) + ")";
        double share = o.unitsTotal > 0 ? o.unitsUsed / (double) o.unitsTotal : 0;
        if (share >= 0.9) add("frames", Level.PASS, "Stacking", what + why);
        else if (share >= 0.6) add("frames", Level.NOTE, "Stacking", what + why);
        else add("frames", Level.FAIL, "Stacking", what + why);
    }

    /** How each frame lined up against how the drawn sky turned between it and the reference frame. */
    private void align(PostProcessor.Output o, Truth t, SessionSky sky) {
        if (sky == null || !sky.clockValid || o.frames.isEmpty()) {
            add("align", Level.NOTE, "Lining up", "the session's clock isn't known, so it can't be compared");
            return;
        }
        PostProcessor.FrameReport ref = null;
        for (PostProcessor.FrameReport f : o.frames) if (f.index == o.referenceUnit) ref = f;
        CameraModel m = o.model;
        if (ref == null || ref.R == null || m == null || t.camToEnu == null) {
            add("align", Level.NOTE, "Lining up", "no reference frame to compare with");
            return;
        }
        // each frame's middle, from its own camera timestamp (the turn between two frames depends only on the time between)
        double[] Mref = Rot.transpose(t.atUtcMs(sky.utcMs(ref.timestampNs) + 500 * ref.exposure));
        double[] Rref = ref.R;
        List<Double> errs = new ArrayList<Double>();
        for (PostProcessor.FrameReport f : o.frames) {
            if (!f.used || f.R == null || f == ref) continue;
            double[] Mi = Rot.transpose(t.atUtcMs(sky.utcMs(f.timestampNs) + 500 * f.exposure));
            // the true turn from the reference frame's bearings to this one's, against the one found (relative to the
            // reference frame's own, which is the identity when it is the reference)
            double[] truth = Rot.mul(Mi, Rot.transpose(Mref));
            double[] found = Rot.mul(f.R, Rot.transpose(Rref));
            errs.add(m.maxDisplacement(Rot.mul(found, Rot.transpose(truth))));
        }
        if (errs.isEmpty()) {
            add("align", Level.NOTE, "Lining up", "only the reference frame was stacked");
            return;
        }
        double[] e = new double[errs.size()];
        for (int i = 0; i < e.length; i++) e[i] = errs.get(i);
        Arrays.sort(e);
        double med = e[e.length / 2], max = e[e.length - 1];
        String what = String.format(Locale.US, "%d frames within %.2f px of where the drawn sky had turned (%.2f px typically)", e.length,
                max, med);
        if (max < 1.5) add("align", Level.PASS, "Lining up", what);
        else if (max < 4) add("align", Level.NOTE, "Lining up", what);
        else add("align", Level.FAIL, "Lining up", what);
    }

    private void colour(PostProcessor.Output o) {
        ColorCalibration.Result r = o.colour;
        if (r == null) {
            add("colour", Level.NOTE, "Colours", "the colour calibration didn't run");
            return;
        }
        if (!r.applied) {
            add("colour", Level.NOTE, "Colours", "the fixed white balance was kept: " + r.reason);
            return;
        }
        double red = r.gainR / r.gainG, blue = r.gainB / r.gainG;
        String what = String.format(Locale.US, "white balance from %d stars' colours: red x%.2f, blue x%.2f over the fixed balance", r.used,
                red, blue);
        boolean near = Math.abs(Math.log(red)) < Math.log(1.2) && Math.abs(Math.log(blue)) < Math.log(1.2);
        add("colour", near ? Level.PASS : Level.NOTE, "Colours", what + (near ? "" : ", more than the camera's own colours would need"));
    }

    private void noise(PostProcessor.Output o) {
        NoiseProbe.Result n = o.noise;
        if (n == null || Double.isNaN(n.nEff)) {
            add("noise", Level.NOTE, "Noise", "the stack's noise wasn't measured");
            return;
        }
        double gain = Math.sqrt(Math.max(0, n.nEff));
        String what = String.format(Locale.US, "the stack is %.1f times smoother than one frame (worth %.1f frames of the %d)", gain, n.nEff,
                o.unitsUsed);
        add("noise", n.nEff >= 0.6 * o.unitsUsed ? Level.PASS : Level.NOTE, "Noise", what);
    }

    private void saving(Input in) {
        if (!in.photoSaved) {
            add("saving", Level.FAIL, "Saving", "the photo wasn't saved to the gallery");
            return;
        }
        List<String> ok = new ArrayList<String>(), missing = new ArrayList<String>();
        ok.add("the photo");
        if (in.hdrWanted) {
            if (in.hdrSaved) ok.add("its HDR brightness map");
            else missing.add("no HDR map" + (in.hdrWhy.isEmpty() ? "" : " (" + in.hdrWhy + ")"));
        }
        if (in.videoWanted) {
            if (in.videoSaved) ok.add("its video inside (a Motion Photo)");
            else missing.add("no video inside" + (in.videoWhy.isEmpty() ? "" : " (" + in.videoWhy + ")"));
        }
        if (in.dngWanted) {
            if (in.dngSaved) ok.add("the RAW (DNG) copy");
            else missing.add("no DNG");
        }
        if (in.logSaved) ok.add("the log");
        else missing.add("no log");
        String what = "saved " + join(ok) + (missing.isEmpty() ? "" : "; " + join(missing));
        add("saving", missing.isEmpty() ? Level.PASS : Level.NOTE, "Saving", what);
    }

    private void phone(Input in, Properties p) {
        List<String> parts = new ArrayList<String>();
        boolean ok = true;
        if (!Double.isNaN(in.developSeconds)) parts.add(String.format(Locale.US, "developed in %s", seconds(in.developSeconds)));
        if (in.heapPeak > 0 && in.heapMax > 0) {
            double share = in.heapPeak / (double) in.heapMax;
            parts.add(String.format(Locale.US, "memory at most %d MB of the %d MB it may use", in.heapPeak >> 20, in.heapMax >> 20));
            if (share > 0.85) ok = false;
        }
        int thermal = intOf(p, "capture.thermalMax");
        String[] heat = {"cool", "warm (light)", "warm (moderate)", "hot (severe)", "very hot (critical)", "too hot (emergency)", "too hot"};
        if (thermal >= 0) {
            parts.add("the phone stayed " + heat[Math.min(heat.length - 1, thermal)]);
            if (thermal >= 3) ok = false;
        }
        if (parts.isEmpty()) {
            add("phone", Level.NOTE, "The phone", "not measured");
            return;
        }
        add("phone", ok ? Level.PASS : Level.NOTE, "The phone", join(parts));
    }

    // ------------------------------------------------------------------------------------------------
    // What was drawn, read back from the session's settings

    /** The parts of a rehearsal's sky the check compares with. */
    public static final class Truth {
        public double latDeg = Double.NaN, lonDeg = Double.NaN;
        public double[] camToEnu;
        public double bestFocus = Double.NaN, blurPerUnit;

        static Truth of(Properties p) {
            Truth t = new Truth();
            t.latDeg = num(p, "rehearsal.lat");
            t.lonDeg = num(p, "rehearsal.lon");
            t.bestFocus = num(p, "rehearsal.bestFocus");
            t.blurPerUnit = num(p, "rehearsal.blurPerUnit");
            String m = p.getProperty("rehearsal.camToEnu");
            if (m != null) {
                String[] s = m.split(",");
                if (s.length == 9) {
                    t.camToEnu = new double[9];
                    for (int i = 0; i < 9; i++) t.camToEnu[i] = Double.parseDouble(s[i].trim());
                }
            }
            return t;
        }

        /** Camera bearings to J2000 at a UTC Julian date (the identity if the drawn pointing isn't known). */
        double[] atJd(double jdUtc) {
            if (camToEnu == null) return Rot.identity();
            return Rot.mul(Rot.transpose(Astro.j2000ToEnu(jdUtc, latDeg * Astro.DEG, lonDeg * Astro.DEG)), camToEnu);
        }

        /** Camera bearings to J2000 at a time (UTC, ms). */
        double[] atUtcMs(double utcMs) {
            return atJd(Astro.jdUtc(utcMs / 1000.0));
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Keeping it

    /** Into the session's settings, so the photo opened again later shows it too. */
    public void toProperties(Properties p) {
        p.setProperty("rehearsal.check.headline", headline);
        p.setProperty("rehearsal.check.items", Integer.toString(items.size()));
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            p.setProperty("rehearsal.check." + i, it.level.name() + "|" + it.key + "|" + it.title + "|" + it.text);
        }
    }

    /** As kept in a session's settings; null if it has none. */
    public static RehearsalCheck fromProperties(Properties p) {
        int n = intOf(p, "rehearsal.check.items");
        if (n <= 0) return null;
        RehearsalCheck c = new RehearsalCheck();
        for (int i = 0; i < n; i++) {
            String v = p.getProperty("rehearsal.check." + i);
            if (v == null) continue;
            String[] s = v.split("\\|", 4);
            if (s.length < 4) continue;
            Level l;
            try {
                l = Level.valueOf(s[0]);
            } catch (IllegalArgumentException e) {
                continue;
            }
            c.items.add(new Item(s[1], l, s[2], s[3]));
        }
        c.finish();
        return c;
    }

    /** Every check, one a line, for the log and for sharing. */
    public String describe() {
        StringBuilder sb = new StringBuilder(headline);
        for (Item it : items) sb.append('\n').append(it.line()).append('.');
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------

    static String seconds(double s) {
        if (s >= 100) return String.format(Locale.US, "%d min %d s", (int) (s / 60), (int) Math.round(s % 60));
        return s >= 10 || Math.abs(s - Math.round(s)) < 0.05 ? String.format(Locale.US, "%.0f s", s) : String.format(Locale.US, "%.1f s", s);
    }

    static String angle(double deg) {
        if (deg >= 1) return String.format(Locale.US, "%.1f degrees", deg);
        double arcmin = deg * 60;
        return arcmin >= 1 ? String.format(Locale.US, "%.1f arcminutes", arcmin) : String.format(Locale.US, "%.0f arcseconds", arcmin * 60);
    }

    private static String lower(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static List<String> distinct(List<String> l) {
        List<String> out = new ArrayList<String>();
        for (String s : l) if (!out.contains(s)) out.add(s);
        return out;
    }

    static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(i == parts.size() - 1 ? (parts.size() > 2 ? ", and " : " and ") : ", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    static double num(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null) return Double.NaN;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static int intOf(Properties p, String k) {
        double v = num(p, k);
        return Double.isNaN(v) ? -1 : (int) Math.round(v);
    }
}
