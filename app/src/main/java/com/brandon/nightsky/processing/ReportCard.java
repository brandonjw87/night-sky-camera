package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

/**
 * The session's report card: what happened to the frames and why, the problems ranked by what they cost, what
 * the stack really gained over one frame, how surely the frames were lined up, a score for every frame, what
 * the picture shows, one tip and one sentence to say. Built from the post-processor's output, the session's
 * settings (pre-flight results, what the capture saw) and where and when it was taken. All text comes from
 * fixed templates filled in with measured numbers.
 * <p>
 * Every problem's cost is a share of the session's light: what the stack lost, in frames' worth, against the
 * same session without that problem. Clouds cost the frames and regions left out and the starlight they
 * dimmed (signal to noise goes with transparency, so a frame's worth with its square); dew costs the starlight
 * moved out of the stars' cores into halos; focus drift the faint stars' contrast (a star spread over twice the
 * width needs four times the exposure); the Moon the extra sky glow (twice the glow, twice the exposure for the
 * same noise); a bump, the frames it spoiled; overheating, the time not exposed.
 */
public final class ReportCard {
    /** What the report is built from. */
    public static final class Input {
        public PostProcessor.Output out;
        /** Where and when (may be null). */
        public SessionSky sky;
        /** The session's settings: pre-flight results and what the capture saw. */
        public Properties props = new Properties();
        public TimeZone zone = TimeZone.getDefault();
        public BrightStars catalog;
    }

    /** One problem, with what it cost and what to do about it. */
    public static final class Problem {
        /** clouds, dew, smudge, focus, moon, bump, thermal, dropped, alignment, trails. */
        public String key;
        public String title;
        /** Share of the session's light it cost (0..1). */
        public double impact;
        public String evidence;
        public String fix;
        /** A few words for the spoken sentence. */
        public String spoken;

        Problem(String key, String title, double impact) {
            this.key = key;
            this.title = title;
            this.impact = impact;
        }
    }

    /** One frame's score. */
    public static final class Frame {
        public int index;
        /** 0..100: its worth against the session's best frames. */
        public int score;
        /** Why it scored under 50 (or was left out); "" otherwise. */
        public String reason = "";
        /** Its local time ("11:40 PM"), else minutes and seconds into the session. */
        public String time = "";
        public boolean used, partly;
    }

    /** Problems costing at least this share of the light are listed; smaller ones become notes. */
    static final double MIN_IMPACT = 0.02;

    public String headline = "";
    public final List<Problem> problems = new ArrayList<Problem>();
    public final List<String> notes = new ArrayList<String>();
    public double nEff = Double.NaN, noiseDown = Double.NaN, idealDown = Double.NaN, equivalentSec = Double.NaN;
    /** Whether {@link #nEff} was measured on the stack (else estimated from the frames' scores). */
    public boolean gainMeasured;
    public String gain = "";
    public double matchedMean = Double.NaN, residualMean = Double.NaN;
    public int alignedFrames, fallbackFrames;
    public String registration = "", confidence = "";
    public Frame[] frames = new Frame[0];
    public int goodFrames;
    public SkyGuide sky;
    /** Sky brightness (mag per square arcsecond, green), NaN if unknown. */
    public double skyMag = Double.NaN;
    public String conditions = "";
    public String tip = "";
    public String spoken = "";
    /** The streaks found: the satellites named (and when), planes, meteors, and what was done with them; "" if none. */
    public String streaks = "";
    public final List<Streak> streakList = new ArrayList<Streak>();

    /** A satellite named in the picture, or a kind of unnamed streak (name "plane", "meteor" or "unknown"). */
    public static final class Streak {
        public String name = "", time = "";
        /** 0 for the unnamed kinds. */
        public int norad;
        /** Frames it crossed. */
        public int frames;
        /** Brightest predicted magnitude (NaN if unnamed). */
        public double mag = Double.NaN;
        public boolean kept;
    }

    private ReportCard() {}

    // ------------------------------------------------------------------------------------------------
    // per-frame data, in time order

    private static final class F {
        PostProcessor.FrameReport r;
        String cat = "";
        double share, t, tDew = 1, tCloud = 1;
        boolean dewCaused;
        String when = "", whenShort = "";
        double utcMs = Double.NaN;
    }

    private Input in;
    private F[] fs;
    private int n;
    private boolean dewRising, timed;
    private double halo0 = Double.NaN, halo1 = Double.NaN;
    private int dewOnset = -1;
    private SimpleDateFormat clock;

    public static ReportCard build(Input in) {
        ReportCard r = new ReportCard();
        r.in = in;
        r.prepare();
        r.dew();
        r.clouds();
        r.focus();
        r.bump();
        r.moon();
        r.thermal();
        r.alignment();
        r.trails();
        Collections.sort(r.problems, new Comparator<Problem>() {
            @Override
            public int compare(Problem a, Problem b) {
                return Double.compare(b.impact, a.impact);
            }
        });
        r.scores();
        r.headline();
        r.gain();
        r.registration();
        r.skyGuide();
        r.streaks();
        r.tipAndSpeech();
        return r;
    }

    private void prepare() {
        PostProcessor.Output o = in.out;
        List<PostProcessor.FrameReport> list = new ArrayList<PostProcessor.FrameReport>(o.frames);
        Collections.sort(list, new Comparator<PostProcessor.FrameReport>() {
            @Override
            public int compare(PostProcessor.FrameReport a, PostProcessor.FrameReport b) {
                return Double.compare(a.t, b.t);
            }
        });
        n = list.size();
        fs = new F[n];
        timed = in.sky != null && in.sky.clockValid;
        clock = new SimpleDateFormat("h:mm a", Locale.US);
        clock.setTimeZone(in.zone);
        SimpleDateFormat brief = new SimpleDateFormat("h:mm", Locale.US);
        brief.setTimeZone(in.zone);
        for (int i = 0; i < n; i++) {
            F f = new F();
            f.r = list.get(i);
            f.t = f.r.t;
            f.cat = category(f.r);
            f.share = !f.r.used ? 0 : f.r.regionsUsed >= 0 ? f.r.regionsUsed / (double) PostProcessor.REGIONS : 1;
            if (timed && f.r.timestampNs != 0) {
                f.utcMs = in.sky.utcMs(f.r.timestampNs) + 500 * f.r.exposure;
                f.when = clock.format(new Date((long) f.utcMs));
                f.whenShort = brief.format(new Date((long) f.utcMs));
            } else {
                long s = Math.round(f.t);
                f.when = String.format(Locale.US, "%d:%02d into the session", s / 60, s % 60);
                f.whenShort = String.format(Locale.US, "%d:%02d", s / 60, s % 60);
            }
            fs[i] = f;
        }
    }

    /** Why a left-out frame was left out, as a class: bump, clouds, blur, stars, align; "" if used. */
    static String category(PostProcessor.FrameReport f) {
        if (f.used) return "";
        String r = f.reason == null ? "" : f.reason;
        if (f.disturbed || r.startsWith("moved")) return "bump";
        if (r.startsWith("clouds")) return "clouds";
        if (r.startsWith("blurred")) return "blur";
        if (r.startsWith("too few stars")) return "stars";
        return "align";
    }

    private double T(F f) {
        float t = f.r.transparency;
        return Float.isNaN(t) ? 1 : Math.max(0, Math.min(1.2, t));
    }

    // ------------------------------------------------------------------------------------------------
    // dew forming on the lens, or a smudged lens

    private void dew() {
        for (F f : fs) f.tCloud = Math.min(1, T(f));
        double[] h = new double[n];
        int[] idx = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(fs[i].r.halo) && !fs[i].cat.equals("bump")) {
                h[k] = fs[i].r.halo;
                idx[k++] = i;
            }
        }
        double limit = num("preflight.haloLimit", 1.5 * Preflight.DEFAULT_CLEAN_HALO + 0.05);
        double clean = (limit - 0.05) / 1.5;
        if (k < 3) return;
        int third = Math.max(2, k / 3);
        double[] first = Arrays.copyOfRange(h, 0, third), last = Arrays.copyOfRange(h, k - third, k);
        halo0 = median(first);
        halo1 = median(last);
        double spread = 1.4826 * mad(first, halo0);
        dewRising = k >= 6 && halo1 - halo0 > Math.max(0.06, 3 * spread) && halo1 > 1.4 * halo0;
        if (dewRising) {
            double mid = halo0 + 0.5 * (halo1 - halo0);
            for (int j = 0; j < k; j++) {
                if (h[j] > mid) {
                    // the first frame from which most later ones stay above the halfway mark
                    int above = 0, total = 0;
                    for (int q = j; q < k; q++) {
                        total++;
                        if (h[q] > mid) above++;
                    }
                    if (above >= 0.75 * total) {
                        dewOnset = idx[j];
                        break;
                    }
                }
            }
            for (int j = 0; j < k; j++) {
                F f = fs[idx[j]];
                f.tDew = Math.min(1, (1 + halo0) / (1 + Math.max(halo0, h[j])));
            }
            double lost = 0;
            for (F f : fs) {
                f.tCloud = Math.min(1, T(f) / f.tDew);
                // a frame left out for "clouds" or too few stars while dew dimmed it, and clear otherwise
                boolean dimmed = f.tDew < 0.8;
                if ((f.cat.equals("clouds") || f.cat.equals("stars")) && dimmed && f.tCloud > 0.8) f.dewCaused = true;
                if (f.r.used && dimmed && f.share < 1 && f.tCloud > 0.8) f.dewCaused = true;
                if (f.dewCaused && !f.r.used) lost += 1;
                else if (f.r.used) lost += (f.dewCaused ? 1 - f.share : 0) + f.share * (1 - f.tDew * f.tDew);
            }
            Problem p = new Problem("dew", "Dew on the lens", lost / n);
            F on = dewOnset >= 0 ? fs[dewOnset] : null;
            double s0 = 100 * Math.max(0, halo0) / (1 + Math.max(0, halo0)), s1 = 100 * halo1 / (1 + halo1);
            p.evidence = String.format(Locale.US, "The glow around the stars grew from %s to %.0f%% of their light%s, "
                            + "so dew (or frost) probably formed on the lens; the stars' cores lost that light.",
                    s0 < 3 ? "almost nothing" : String.format(Locale.US, "%.0f%%", s0), s1,
                    on != null ? (timed ? " after " : " from ") + on.when : "");
            p.fix = "On damp nights keep the lens a little warm: a hand warmer or a dew-heater strip held next to the camera "
                    + "with a band. Wipe the lens before you start, and keep sessions short once dew is forming.";
            p.spoken = "Dew on the lens" + (on != null && timed ? " after " + on.whenShort : "") + ".";
            problems.add(p);
            return;
        }
        // a glow from the start: a smudge (or dew before the session began)
        boolean warned = "true".equals(in.props.getProperty("preflight.lensWarned"));
        if ((halo0 > limit || warned) && halo0 > clean) {
            double cost = 1 - Math.pow((1 + clean) / (1 + halo0), 2);
            Problem p = new Problem("smudge", "Smudged lens", cost);
            p.evidence = String.format(Locale.US, "Every star had a glow around it all session (%.0f%% of its light; a clean "
                    + "lens gives about %.0f%%), so the lens was probably smudged or already damp.", 100 * halo0 / (1 + halo0), 100 * clean / (1 + clean));
            p.fix = "Wipe the camera lens with a soft cloth before you start: a fingerprint spreads the starlight into a glow.";
            p.spoken = "The lens looks smudged.";
            problems.add(p);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // clouds

    private void clouds() {
        double lost = 0;
        int out = 0, partly = 0, dimmed = 0;
        double worst = 1;
        int firstAff = -1, lastAff = -1;
        double bgMed = medianBackground();
        for (int i = 0; i < n; i++) {
            F f = fs[i];
            boolean aff = false;
            if (!f.r.used) {
                boolean cloudy = f.cat.equals("clouds");
                // too few stars to line up, with the sky glowing brighter than usual: cloud too
                if (f.cat.equals("stars") && !Double.isNaN(bgMed) && f.r.background > 1.25 * bgMed) cloudy = true;
                if (f.cat.equals("stars") && neighbourCloudy(i)) cloudy = true;
                if (cloudy && !f.dewCaused) {
                    lost += 1;
                    out++;
                    aff = true;
                    if (!Float.isNaN(f.r.transparency)) worst = Math.min(worst, f.r.transparency);
                    else worst = 0;
                }
            } else {
                if (f.share < 1 && !f.dewCaused) {
                    lost += 1 - f.share;
                    partly++;
                    aff = true;
                }
                double tc = Math.min(1, f.tCloud / 0.95);
                lost += f.share * f.tDew * f.tDew * (1 - tc * tc);
                if (f.tCloud < 0.85) {
                    dimmed++;
                    aff = true;
                    worst = Math.min(worst, f.tCloud);
                }
            }
            if (aff) {
                if (firstAff < 0) firstAff = i;
                lastAff = i;
            }
        }
        if (firstAff < 0) return;
        boolean thin = out == 0 && worst >= 0.5;
        Problem p = new Problem("clouds", thin ? "Thin cloud" : "Clouds", lost / n);
        List<String> parts = new ArrayList<String>();
        if (out > 0) parts.add(count(out, "frame") + " lost to cloud");
        if (partly > 0) parts.add(count(partly, "frame") + " used only in " + (partly == 1 ? "its" : "their") + " clear parts");
        if (dimmed > 0) parts.add(count(dimmed, "frame") + " dimmed");
        String span = span(firstAff, lastAff);
        p.evidence = capital(SkyGuide.list(parts)) + span
                + (worst < 1 ? String.format(Locale.US, "; the starlight fell to %.0f%% at worst.", 100 * Math.max(0, worst)) : ".");
        p.fix = "Clouds can't be fixed afterwards, but the app keeps the clear parts. Check a cloud map before you start, "
                + "and on a patchy night run longer so more clear frames make it in.";
        p.spoken = (thin ? "Thin cloud" : "Clouds") + spanSpoken(firstAff, lastAff) + ".";
        problems.add(p);
    }

    private boolean neighbourCloudy(int i) {
        for (int d = -2; d <= 2; d++) {
            int j = i + d;
            if (d == 0 || j < 0 || j >= n) continue;
            if (fs[j].cat.equals("clouds") || (fs[j].r.used && fs[j].tCloud < 0.7)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // focus drift

    private void focus() {
        double[] h = new double[n];
        double[] tt = new double[n];
        int[] idx = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (fs[i].r.used && fs[i].r.hfr > 0) {
                h[k] = fs[i].r.hfr;
                tt[k] = fs[i].t;
                idx[k++] = i;
            }
        }
        if (k < 6) return;
        int third = Math.max(2, k / 3);
        double f0 = median(Arrays.copyOfRange(h, 0, third)), f1 = median(Arrays.copyOfRange(h, k - third, k));
        double rho = spearman(tt, h, k);
        if (!(f1 > 1.2 * f0) || rho < 0.5) return;
        double lost = 0;
        int onset = -1;
        double mid = f0 + 0.5 * (f1 - f0);
        for (int j = 0; j < k; j++) {
            double r = Math.min(1, f0 / h[j]);
            lost += fs[idx[j]].share * (1 - r * r);
            if (onset < 0 && h[j] > mid) onset = idx[j];
        }
        for (F f : fs) if (f.cat.equals("blur")) lost += 1;
        Problem p = new Problem("focus", "Focus drift", lost / n);
        F on = onset >= 0 ? fs[onset] : null;
        p.evidence = String.format(Locale.US, "The stars grew from %.1f to %.1f pixels across (FWHM)%s, so the focus drifted, "
                        + "probably as the lens cooled (a light fogging does this too).", 2 * f0, 2 * f1,
                on != null ? (timed ? " after " : " from ") + on.when : "");
        p.fix = "Let the phone sit outside for ten minutes before you start, so it is at the air's temperature when the "
                + "focus is set.";
        p.spoken = "The focus drifted" + (on != null && timed ? " after " + on.whenShort : "") + ".";
        problems.add(p);
    }

    // ------------------------------------------------------------------------------------------------
    // a bump or shaking

    private void bump() {
        List<F> knocked = new ArrayList<F>();
        double lost = 0;
        double maxRate = 0;
        float[] hh = new float[n];
        int nh = 0;
        for (F f : fs) if (f.r.used && f.r.hfr > 0) hh[nh++] = (float) f.r.hfr;
        double hMed = nh > 0 ? Stats.median(hh, nh) : Double.NaN;
        int shaken = 0;
        for (F f : fs) {
            if (f.cat.equals("bump")) {
                knocked.add(f);
                lost += 1;
                maxRate = Math.max(maxRate, f.r.gyroMax);
            } else if (f.r.used && f.r.gyroMax > 0.02f && f.r.hfr > 1.25 * hMed) {
                double r = hMed / f.r.hfr;
                lost += f.share * (1 - r * r);
                shaken++;
            } else if (f.cat.equals("blur") && f.r.gyroMax > 0.02f) {
                lost += 1;
                shaken++;
            }
        }
        if (knocked.isEmpty() && shaken == 0) return;
        Problem p = new Problem("bump", knocked.isEmpty() ? "Shaking" : knocked.size() == 1 ? "A knock" : "Knocks", lost / n);
        StringBuilder e = new StringBuilder();
        if (knocked.size() == 1) {
            F f = knocked.get(0);
            e.append(String.format(Locale.US, "Frame %d (%s) was spoiled by a knock", f.r.index, f.when));
            if (f.r.gyroMax > 0) e.append(String.format(Locale.US, ": the phone turned at %.0f degrees a second", Math.toDegrees(f.r.gyroMax)));
            e.append('.');
        } else if (knocked.size() > 1) {
            List<String> w = new ArrayList<String>();
            for (F f : knocked) if (w.size() < 4) w.add(f.whenShort);
            e.append(String.format(Locale.US, "%d frames were spoiled by knocks (%s%s).", knocked.size(), SkyGuide.list(w),
                    knocked.size() > 4 ? " and more" : ""));
        }
        if (shaken > 0) {
            if (e.length() > 0) e.append(' ');
            e.append(count(shaken, "frame")).append(shaken == 1 ? " was" : " were").append(" shaken while exposing (smeared stars).");
        }
        p.evidence = e.toString();
        p.fix = "Put the phone on something firm where nobody walks, and keep away from it until it says Done.";
        p.spoken = knocked.size() == 1 ? "A knock spoiled one frame." : knocked.size() > 1
                ? "Knocks spoiled " + knocked.size() + " frames." : "The phone was shaken.";
        problems.add(p);
    }

    // ------------------------------------------------------------------------------------------------
    // moonlight

    private void moon() {
        if (!timed || in.sky == null || !in.sky.located()) return;
        double lat = in.sky.latDeg * Astro.DEG, lon = in.sky.lonDeg * Astro.DEG;
        int first = -1, last = -1;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(fs[i].utcMs)) continue;
            if (first < 0) first = i;
            last = i;
        }
        if (first < 0) return;
        skyMag = measuredSkyMag();
        double darkNl = nanoLamberts(21.0);
        double shareSum = 0, maxAlt = -90, illum = 0, sepMid = 0, factor = 1;
        int samples = 5;
        for (int s = 0; s < samples; s++) {
            double u = fs[first].utcMs + (fs[last].utcMs - fs[first].utcMs) * s / (samples - 1.0);
            double jd = Astro.jdUtc(u / 1000.0);
            double jdTt = Astro.jdTt(jd);
            double[] m = Astro.moonOfDate(jdTt);
            double[] mEnu = Astro.topocentric(m, m[3], jd, lat, lon);
            double alt = Astro.altitude(mEnu) / Astro.DEG;
            double k = Astro.moonIllumination(jdTt);
            double[] field = fieldEnu(jd, lat, lon);
            double rho = Astro.sep(mEnu, field) / Astro.DEG;
            double z = 90 - Astro.altitude(field) / Astro.DEG;
            maxAlt = Math.max(maxAlt, alt);
            if (s == samples / 2) {
                illum = k;
                sepMid = rho;
            }
            if (alt <= 0) continue;
            double bm = moonSkyNl(k, rho, 90 - alt, z);
            double total = !Double.isNaN(skyMag) ? Math.max(nanoLamberts(skyMag), bm) : darkNl + bm;
            double share = Math.min(0.95, bm / total);
            shareSum += share;
            if (s == samples / 2 || factor == 1) factor = 1 / (1 - share);
        }
        double impact = shareSum / samples;
        if (maxAlt <= 0 || impact < 0.005) return;
        Problem p = new Problem("moon", "Moonlight", impact);
        double midJd = Astro.jdUtc(fs[(first + last) / 2].utcMs / 1000.0);
        p.evidence = String.format(Locale.US, "The Moon (%.0f%% lit) was up, %.0f degrees from where the camera pointed, and "
                + "made the sky about %.1f times brighter than it would have been without it.", 100 * illum, sepMid, factor);
        double set = moonSet(midJd, lat, lon);
        String when = Double.isNaN(set) ? "" : " (tonight it set at " + clock.format(new Date((long) ((set - 2440587.5) * 86400000.0))) + ")";
        p.fix = "Shoot when the Moon is down or a thin crescent" + when + "; the sky is darkest with no Moon.";
        p.spoken = "Moonlight brightened the sky.";
        problems.add(p);
    }

    /** Topocentric direction (ENU) of where the camera pointed: the solved centre, else straight up. */
    private double[] fieldEnu(double jdUtc, double lat, double lon) {
        SkySolver.Solution s = in.out.solution;
        if (s == null) return new double[]{0, 0, 1};
        double[] c = s.direction(s.model.cx, s.model.cy);
        double[] e = new double[3];
        Rot.apply(Astro.j2000ToEnu(jdUtc, lat, lon), c[0], c[1], c[2], e);
        return e;
    }

    /**
     * Sky brightness of the reference frame in magnitudes per square arcsecond (green, close to V): its sky
     * background against the zero point of its catalogue stars. The stars' flux is summed in the detector's
     * half-resolution green image (a cell is 2 x 2 pixels), so a pixel's sky in those units is a quarter of a
     * cell's. NaN without a solution.
     */
    private double measuredSkyMag() {
        PostProcessor.Output o = in.out;
        SkySolver.Solution s = o.solution;
        if (s == null || Double.isNaN(s.zeroPoint) || s.model == null || !(s.model.fx > 0)) return Double.NaN;
        PostProcessor.FrameReport ref = null;
        for (PostProcessor.FrameReport f : o.frames) if (f.index == o.referenceUnit) ref = f;
        if (ref == null || !(ref.background > 0) || !(ref.exposure > 0)) return Double.NaN;
        double pix = 206264.806 / s.model.fx;
        double perArcsec2 = ref.background * ref.exposure / (4 * pix * pix);
        return s.zeroPoint - 2.5 * Math.log10(perArcsec2);
    }

    /** V magnitudes per square arcsecond to nanolamberts (Garstang; as in Krisciunas and Schaefer 1991). */
    static double nanoLamberts(double mag) {
        return 34.08 * Math.exp(20.7233 - 0.92104 * mag);
    }

    /**
     * Moonlight scattered into the sky (nanolamberts, V), Krisciunas and Schaefer (1991): illuminated fraction k,
     * the Moon's distance from the target rho, the Moon's and the target's zenith distances (degrees).
     */
    static double moonSkyNl(double k, double rhoDeg, double zMoonDeg, double zDeg) {
        double alpha = Math.acos(Math.max(-1, Math.min(1, 2 * k - 1))) / Astro.DEG;
        double istar = Math.pow(10, -0.4 * (3.84 + 0.026 * alpha + 4e-9 * Math.pow(alpha, 4)));
        double cr = Math.cos(rhoDeg * Astro.DEG);
        double fr = Math.pow(10, 5.36) * (1.06 + cr * cr) + Math.pow(10, 6.15 - rhoDeg / 40);
        double ext = 0.172;
        double xm = airmass(zMoonDeg), x = airmass(zDeg);
        return fr * istar * Math.pow(10, -0.4 * ext * xm) * (1 - Math.pow(10, -0.4 * ext * x));
    }

    private static double airmass(double zDeg) {
        double s = Math.sin(Math.min(89.5, zDeg) * Astro.DEG);
        return 1 / Math.sqrt(1 - 0.96 * s * s);
    }

    /** When the Moon next sets after a UTC Julian date (UTC Julian date), NaN if not within a day. */
    static double moonSet(double jdUtc, double lat, double lon) {
        double prev = moonAlt(jdUtc, lat, lon);
        for (int step = 1; step <= 24 * 12; step++) {
            double jd = jdUtc + step / (24.0 * 12);
            double a = moonAlt(jd, lat, lon);
            if (prev > -0.3 && a <= -0.3) {
                // halve the step down to a minute
                double lo = jd - 1 / (24.0 * 12), hi = jd;
                for (int it = 0; it < 6; it++) {
                    double m = 0.5 * (lo + hi);
                    if (moonAlt(m, lat, lon) > -0.3) lo = m;
                    else hi = m;
                }
                return 0.5 * (lo + hi);
            }
            prev = a;
        }
        return Double.NaN;
    }

    private static double moonAlt(double jdUtc, double lat, double lon) {
        double[] m = Astro.moonOfDate(Astro.jdTt(jdUtc));
        return Astro.altitude(Astro.topocentric(m, m[3], jdUtc, lat, lon)) / Astro.DEG;
    }

    // ------------------------------------------------------------------------------------------------
    // overheating and dropped frames

    private void thermal() {
        int dropped = (int) num("capture.framesDropped", 0);
        int status = (int) num("capture.thermalMax", 0);
        double hotAt = num("capture.thermalSevereAtMs", Double.NaN);
        // time not exposed: gaps between frames well beyond the usual one
        double[] gaps = new double[Math.max(0, n - 1)];
        for (int i = 0; i + 1 < n; i++) {
            gaps[i] = (fs[i + 1].t - 0.5 * fs[i + 1].r.exposure) - (fs[i].t + 0.5 * fs[i].r.exposure);
        }
        double g0 = gaps.length > 0 ? median(gaps.clone()) : 0;
        double lostSec = 0;
        for (double g : gaps) lostSec += Math.max(0, g - Math.max(1.0, 3 * g0));
        double spanSec = n > 0 ? (fs[n - 1].t + 0.5 * fs[n - 1].r.exposure) - (fs[0].t - 0.5 * fs[0].r.exposure) : 0;
        double impact = spanSec > 0 ? lostSec / spanSec : 0;
        boolean hot = status >= 3;
        if (!hot && impact < 0.005 && dropped == 0) return;
        Problem p = new Problem(hot ? "thermal" : "dropped", hot ? "The phone got hot" : "Frames dropped", impact);
        StringBuilder e = new StringBuilder();
        if (hot) {
            e.append("The phone reported it was ").append(thermalName(status)).append(" hot");
            if (!Double.isNaN(hotAt)) e.append(" from ").append(clock.format(new Date((long) hotAt)));
            e.append(lostSec >= 1 ? String.format(Locale.US, ", and %.0f seconds of the session weren't exposed", lostSec) : "");
        } else {
            e.append(String.format(Locale.US, "%.0f seconds of the session weren't exposed", lostSec));
        }
        if (dropped > 0) e.append(" (").append(count(dropped, "frame")).append(" dropped").append(hot ? "" : " while the phone caught up").append(')');
        e.append('.');
        p.evidence = e.toString();
        p.fix = hot ? "Start with a cool phone: take the case off, keep it out of the sun and warm pockets, and don't charge it during a session."
                : "Close other apps before a session so the phone can keep up.";
        p.spoken = hot ? "The phone got hot." : "Some frames were dropped.";
        problems.add(p);
    }

    static String thermalName(int s) {
        switch (s) {
            case 1:
                return "slightly";
            case 2:
                return "moderately";
            case 3:
                return "severely";
            case 4:
                return "critically";
            default:
                return s >= 5 ? "dangerously" : "not";
        }
    }

    // ------------------------------------------------------------------------------------------------
    // alignment trouble

    private void alignment() {
        int k = 0, first = -1, last = -1;
        double bgMed = medianBackground();
        for (int i = 0; i < n; i++) {
            F f = fs[i];
            boolean bad = f.cat.equals("align")
                    || (f.cat.equals("stars") && !f.dewCaused && !(f.r.background > 1.25 * bgMed) && !neighbourCloudy(i));
            if (!bad) continue;
            k++;
            if (first < 0) first = i;
            last = i;
        }
        if (k == 0) return;
        Problem p = new Problem("alignment", "Frames that couldn't be lined up", k / (double) n);
        p.evidence = capital(count(k, "frame")) + (k == 1 ? " couldn't" : " couldn't") + " be lined up with the others (too few "
                + "stars matched)" + span(first, last) + ".";
        p.fix = "More stars make lining up surer: a clean lens, a darker spot and the full session length all help.";
        p.spoken = k == 1 ? "One frame couldn't be lined up." : k + " frames couldn't be lined up.";
        problems.add(p);
    }

    // ------------------------------------------------------------------------------------------------
    // satellite and plane trails taken out by the rejection

    private void trails() {
        float[] fr = new float[n];
        int k = 0;
        for (F f : fs) if (f.r.used && f.r.samples > 0) fr[k++] = f.r.rejectedSamples / (float) f.r.samples;
        if (k < 4) return;
        float med = Stats.median(fr.clone(), k);
        List<String> which = new ArrayList<String>();
        long extra = 0, total = 0;
        for (F f : fs) {
            if (!f.r.used || f.r.samples <= 0) continue;
            total += f.r.samples;
            float q = f.r.rejectedSamples / (float) f.r.samples;
            if (q > Math.max(5 * med, med + 3e-4f)) {
                which.add(Integer.toString(f.r.index));
                extra += f.r.rejectedSamples - (long) (med * f.r.samples);
            }
        }
        if (which.isEmpty()) return;
        double impact = total > 0 ? extra / (double) total : 0;
        String text = "Trails (satellites, planes or meteors) were taken out of " + (which.size() == 1 ? "frame " : "frames ")
                + SkyGuide.list(which.size() > 6 ? which.subList(0, 6) : which) + (which.size() > 6 ? " and more" : "")
                + ": the stack doesn't show them.";
        if (impact >= MIN_IMPACT) {
            Problem p = new Problem("trails", "Trails", impact);
            p.evidence = text;
            p.fix = "Nothing to do: the stack leaves them out.";
            p.spoken = "Trails were taken out.";
            problems.add(p);
        } else {
            notes.add(text);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // frame scores

    private void scores() {
        frames = new Frame[n];
        double[] snr = new double[n];
        float[] sv = new float[n], hv = new float[n], nv = new float[n];
        int ks = 0, kh = 0, kn = 0;
        for (F f : fs) if (f.r.used && f.r.noise > 0) nv[kn++] = (float) f.r.noise;
        double noiseMed = kn > 0 ? Stats.median(nv, kn) : 1;
        for (int i = 0; i < n; i++) {
            F f = fs[i];
            if (!f.r.used) continue;
            double sig = f.r.noise > 0 ? f.r.noise : noiseMed;
            snr[i] = T(f) * Math.max(1e-6, f.r.exposure) / sig;
            sv[ks++] = (float) snr[i];
            if (f.r.hfr > 0) hv[kh++] = (float) f.r.hfr;
        }
        double snrRef = ks > 0 ? Stats.quantile(sv, ks, 0.8) : 1;
        double hRef = kh > 0 ? Stats.quantile(hv, kh, 0.25) : Double.NaN;
        double tRef = 1;
        int good = 0;
        for (int i = 0; i < n; i++) {
            F f = fs[i];
            Frame fr = new Frame();
            fr.index = f.r.index;
            fr.time = f.whenShort;
            fr.used = f.r.used;
            fr.partly = f.r.used && f.share < 1;
            if (!f.r.used) {
                fr.score = 0;
                fr.reason = leftOutReason(f);
            } else {
                double qs = Math.min(1, snr[i] / snrRef);
                double sharp = f.r.hfr > 0 && hRef > 0 ? Math.min(1, Math.pow(hRef / f.r.hfr, 2)) : 1;
                double q = f.share * qs * qs * sharp;
                fr.score = (int) Math.max(0, Math.min(100, Math.round(100 * q)));
                if (fr.score < 50) {
                    // the factor that cost it most
                    double lShare = 1 - f.share, lSharp = 1 - sharp;
                    double t = T(f) / tRef;
                    double lT = 1 - Math.min(1, t * t);
                    double sig = f.r.noise > 0 ? f.r.noise : noiseMed;
                    double nr = sig / noiseMed;
                    double lN = 1 - Math.min(1, 1 / (nr * nr));
                    double m = Math.max(Math.max(lShare, lSharp), Math.max(lT, lN));
                    if (m == lShare) fr.reason = String.format(Locale.US, "cloud over part of it (%.0f%% used)", 100 * f.share);
                    else if (m == lT) fr.reason = String.format(Locale.US, f.tDew < 0.9 ? "dew on the lens (%.0f%% light)" : "thin cloud (%.0f%% light)", 100 * T(f));
                    else if (m == lSharp) fr.reason = String.format(Locale.US, "soft stars (%.1f vs %.1f px)", 2 * f.r.hfr, 2 * hRef);
                    else fr.reason = String.format(Locale.US, "brighter sky (%.1f times the noise)", nr);
                }
            }
            if (fr.score >= 50) good++;
            frames[i] = fr;
        }
        goodFrames = good;
    }

    private String leftOutReason(F f) {
        if (f.dewCaused) return String.format(Locale.US, "dew on the lens (%.0f%% light)", 100 * T(f));
        switch (f.cat) {
            case "bump":
                return "knocked (the phone moved)";
            case "clouds":
                return Float.isNaN(f.r.transparency) ? "clouds" : String.format(Locale.US, "clouds (%.0f%% light)", 100 * f.r.transparency);
            case "blur":
                return "blurred";
            case "stars":
                return "too few stars";
            default:
                return "couldn't be lined up";
        }
    }

    // ------------------------------------------------------------------------------------------------
    // the summary lines

    private void headline() {
        int full = 0, partly = 0;
        List<String> why = new ArrayList<String>();
        int[] c = new int[6];
        String[] label = {"bumped", "cloud", "blurred", "too few stars", "not lined up", "dew"};
        for (F f : fs) {
            if (f.r.used) {
                if (f.share < 1) partly++;
                else full++;
                continue;
            }
            if (f.dewCaused) c[5]++;
            else if (f.cat.equals("bump")) c[0]++;
            else if (f.cat.equals("clouds")) c[1]++;
            else if (f.cat.equals("blur")) c[2]++;
            else if (f.cat.equals("stars")) c[3]++;
            else c[4]++;
        }
        int rejected = n - full - partly;
        StringBuilder sb = new StringBuilder();
        sb.append(count(n, "frame"));
        if (rejected == 0 && partly == 0) {
            sb.append(n == 1 ? ", used." : ", all used.");
        } else {
            sb.append(": ").append(full).append(" used fully");
            if (partly > 0) sb.append(", ").append(partly).append(" partly (cloudy areas)");
            if (rejected > 0) {
                for (int k = 0; k < c.length; k++) {
                    if (c[k] > 0) why.add((rejected > 1 && distinct(c) > 1 ? c[k] + " " : "") + label[k]);
                }
                sb.append(", ").append(rejected).append(" rejected (").append(SkyGuide.list(why)).append(')');
            }
            sb.append('.');
        }
        headline = sb.toString();
    }

    private static int distinct(int[] c) {
        int k = 0;
        for (int v : c) if (v > 0) k++;
        return k;
    }

    private void gain() {
        PostProcessor.Output o = in.out;
        double exp = Double.NaN;
        for (PostProcessor.FrameReport f : o.frames) if (f.index == (o.noiseUnit >= 0 ? o.noiseUnit : o.referenceUnit)) exp = f.exposure;
        if (Double.isNaN(exp) && n > 0) exp = fs[n / 2].r.exposure;
        if (o.noise != null && o.noise.nEff > 0) {
            nEff = o.noise.nEff;
            gainMeasured = true;
        } else {
            double s = 0;
            for (Frame f : frames) s += f.score / 100.0;
            nEff = s;
        }
        idealDown = Math.sqrt(Math.max(1, n));
        noiseDown = Math.sqrt(Math.max(1e-9, nEff));
        // within the measurement's few per cent of every frame counting in full: say so, not "better than ideal"
        boolean full = nEff >= 0.97 * n;
        equivalentSec = Math.min(nEff, n) * exp;
        if (n <= 1) {
            gain = "One frame: nothing to stack.";
            return;
        }
        if (full) {
            gain = String.format(Locale.US, "Noise down %.1f\u00d7, the most %d frames can give, like a single %s exposure.",
                    idealDown, n, duration(equivalentSec, false));
        } else {
            gain = String.format(Locale.US, "Noise down %s%.1f\u00d7 (ideal %.1f\u00d7), like a single %s exposure%s.", gainMeasured ? "" : "about ",
                    noiseDown, idealDown, duration(equivalentSec, false), gainMeasured ? "" : " (estimated from the frames' scores)");
        }
    }

    private void registration() {
        PostProcessor.Output o = in.out;
        double ms = 0, rs = 0;
        int km = 0, kr = 0, used = 0;
        for (F f : fs) {
            if (!f.r.used) continue;
            used++;
            if (f.r.predicted) fallbackFrames++;
            else if (f.r.matched > 0) {
                ms += f.r.matched;
                km++;
            }
            if (f.r.index != o.referenceUnit && !Double.isNaN(f.r.rms) && !f.r.predicted) {
                rs += f.r.rms;
                kr++;
            }
        }
        alignedFrames = used;
        if (used == 0) {
            registration = "No frame could be lined up, so the picture is a plain average.";
            confidence = "none";
            return;
        }
        matchedMean = km > 0 ? ms / km : Double.NaN;
        residualMean = kr > 0 ? rs / kr : Double.NaN;
        StringBuilder sb = new StringBuilder();
        if (km > 0) {
            sb.append(String.format(Locale.US, "Lined up on %.0f stars a frame on average", matchedMean));
            if (!Double.isNaN(residualMean)) sb.append(String.format(Locale.US, " (%.2f px residual)", residualMean));
        } else {
            sb.append("Lined up by the sky's steady motion");
        }
        if (fallbackFrames > 0) {
            sb.append(String.format(Locale.US, "; %d of %d frames placed by the sky's steady motion (too few stars to match)",
                    fallbackFrames, used));
        }
        sb.append('.');
        if (o.solution != null) {
            sb.append(String.format(Locale.US, " The sky was identified from %d catalogue stars.", o.solution.matched));
        }
        double share = fallbackFrames / (double) used;
        confidence = km > 0 && matchedMean >= 50 && !(residualMean > 0.6) && share <= 0.2 ? "high"
                : km > 0 && matchedMean >= 15 && share <= 0.5 ? "medium" : "low";
        registration = sb.toString();
    }

    private void skyGuide() {
        PostProcessor.Output o = in.out;
        if (Double.isNaN(skyMag)) skyMag = measuredSkyMag();
        if (!Double.isNaN(skyMag)) {
            conditions = String.format(Locale.US, "Sky brightness about %.1f magnitudes per square arcsecond: %s.", skyMag, skyClass(skyMag));
        }
        double jd = o.solutionJd;
        if (Double.isNaN(jd) && timed && n > 0 && !Double.isNaN(fs[n / 2].utcMs)) jd = Astro.jdUtc(fs[n / 2].utcMs / 1000.0);
        Renderer.Analysis a = o.analysis;
        int x0 = a != null ? a.cx0 : 0, y0 = a != null ? a.cy0 : 0;
        int w = a != null ? a.cw : o.image != null ? o.image.width : 0, h = a != null ? a.ch : o.image != null ? o.image.height : 0;
        sky = SkyGuide.describe(o.solution, jd, in.sky, in.catalog != null ? in.catalog : in.sky != null ? in.sky.catalog : null, x0, y0, w, h);
    }

    private void streaks() {
        PostProcessor.Output o = in.out;
        SatMatch.Result r = o.streaks;
        String note = o.satellitesNote != null ? o.satellitesNote : "";
        if (r == null) {
            if (!note.isEmpty()) streaks = "Streaks weren't named: " + note + ".";
            return;
        }
        Map<Integer, Streak> named = new LinkedHashMap<Integer, Streak>();
        Map<Integer, Integer> firstUnit = new HashMap<Integer, Integer>();
        int planes = 0, meteors = 0, other = 0, unlisted = 0;
        for (List<SatMatch.Streak> chain : chains(r.streaks)) {
            boolean plane = false, meteor = false;
            for (SatMatch.Streak s : chain) {
                plane |= "plane".equals(s.kind);
                meteor |= "meteor".equals(s.kind);
            }
            if (plane) planes++;
            else if (chain.size() >= 2) unlisted++;
            else if (meteor) meteors++;
            else other++;
        }
        for (SatMatch.Streak s : r.streaks) {
            if (s.sat == null) continue;
            Streak k = named.get(s.sat.norad);
            if (k == null) {
                k = new Streak();
                k.name = s.sat.displayName();
                k.norad = s.sat.norad;
                k.kept = o.satellitesKept;
                named.put(s.sat.norad, k);
                firstUnit.put(s.sat.norad, s.unit);
            }
            k.frames++;
            if (Double.isNaN(k.mag) || s.mag < k.mag) k.mag = s.mag;
            if (s.unit < firstUnit.get(s.sat.norad)) firstUnit.put(s.sat.norad, s.unit);
        }
        List<Streak> list = new ArrayList<Streak>(named.values());
        for (Streak k : list) k.time = whenOf(firstUnit.get(k.norad));
        Collections.sort(list, new Comparator<Streak>() {
            @Override
            public int compare(Streak a, Streak b) {
                return Double.compare(a.mag, b.mag);
            }
        });
        streakList.addAll(list);
        String[] kinds = {"plane", "meteor", "unlisted satellite", "unknown"};
        int[] counts = {planes, meteors, unlisted, other};
        for (int i = 0; i < kinds.length; i++) {
            if (counts[i] == 0) continue;
            Streak k = new Streak();
            k.name = kinds[i];
            k.frames = counts[i];
            k.kept = o.satellitesKept && "meteor".equals(kinds[i]);
            streakList.add(k);
        }
        if (r.streaks.isEmpty()) {
            streaks = r.predicted > 0 ? "No streaks: the satellites that crossed were too faint to leave one." : "No streaks found.";
            if (!note.isEmpty()) streaks += " (Satellites weren't named: " + note + ".)";
            return;
        }
        List<String> parts = new ArrayList<String>();
        int shown = Math.min(5, list.size());
        for (int i = 0; i < shown; i++) {
            Streak k = list.get(i);
            parts.add(SatPlanner.the(k.name) + (k.time.isEmpty() ? "" : " at " + k.time)
                    + (k.frames > 1 ? " (" + count(k.frames, "frame") + ")" : ""));
        }
        if (list.size() > shown) parts.add(count(list.size() - shown, "more satellite"));
        StringBuilder sb = new StringBuilder();
        if (!list.isEmpty()) sb.append(list.size() == 1 ? "A satellite crossed the picture: " : "Satellites crossed the picture: ")
                .append(SkyGuide.list(parts)).append('.');
        List<String> rest = new ArrayList<String>();
        if (planes > 0) rest.add(count(planes, "plane"));
        if (unlisted > 0) rest.add(unlisted == 1 ? "a satellite the data doesn't have" : unlisted + " satellites the data doesn't have");
        if (meteors > 0) rest.add(count(meteors, "possible meteor"));
        if (other > 0) rest.add(count(other, "streak") + " nothing explains");
        if (!rest.isEmpty()) sb.append(sb.length() > 0 ? " Also " : "").append(sb.length() > 0 ? SkyGuide.list(rest) : capital(SkyGuide.list(rest)))
                .append('.');
        boolean anyKept = false, anyOut = false;
        for (Streak k : streakList) {
            if (k.kept) anyKept = true;
            else anyOut = true;
        }
        sb.append(anyKept && !anyOut ? " Kept in the picture at full brightness, as asked."
                : anyKept ? " The satellites and meteors were kept, as asked; the rest were taken out."
                : " All were taken out of the stack, faint edges included.");
        if (list.isEmpty() && !note.isEmpty()) sb.append(" (Satellites weren't named: ").append(note).append(".)");
        streaks = sb.toString();
    }

    /**
     * The unnamed streaks as objects: a streak in one frame and its continuation in the next (in line with it,
     * within a few degrees and pixels) are one thing crossing, a plane or a satellite the data doesn't have.
     */
    public static List<List<SatMatch.Streak>> chains(List<SatMatch.Streak> streaks) {
        List<SatMatch.Streak> un = new ArrayList<SatMatch.Streak>();
        for (SatMatch.Streak s : streaks) if (s.sat == null && !Float.isNaN(s.x0)) un.add(s);
        Collections.sort(un, new Comparator<SatMatch.Streak>() {
            @Override
            public int compare(SatMatch.Streak a, SatMatch.Streak b) {
                return a.unit - b.unit;
            }
        });
        List<List<SatMatch.Streak>> out = new ArrayList<List<SatMatch.Streak>>();
        for (SatMatch.Streak s : un) {
            List<SatMatch.Streak> into = null;
            for (List<SatMatch.Streak> c : out) {
                SatMatch.Streak last = c.get(c.size() - 1);
                // the next frame or the one after; or the same frame, a dashed plane's light broken into pieces
                if (s.unit - last.unit >= 0 && s.unit - last.unit <= 2 && inLine(c, s)) {
                    into = c;
                    break;
                }
            }
            if (into == null) out.add(into = new ArrayList<SatMatch.Streak>());
            into.add(s);
        }
        return out;
    }

    /**
     * Whether streak {@code b} carries on the line of the {@code chain}: the line through all its pieces' ends,
     * weighted by their lengths. A short piece's direction is uncertain by about two cells over its length, and
     * the line wanders a little over a long way, so both tolerances grow accordingly.
     */
    static boolean inLine(List<SatMatch.Streak> chain, SatMatch.Streak b) {
        double sw = 0, mx = 0, my = 0, hw = 0;
        for (SatMatch.Streak a : chain) {
            double w = Math.max(1, Math.hypot(a.x1 - a.x0, a.y1 - a.y0));
            mx += w * (a.x0 + a.x1);
            my += w * (a.y0 + a.y1);
            sw += 2 * w;
            hw = Math.max(hw, a.halfWidth);
        }
        mx /= sw;
        my /= sw;
        double sxx = 0, sxy = 0, syy = 0, span = 0;
        for (SatMatch.Streak a : chain) {
            double w = Math.max(1, Math.hypot(a.x1 - a.x0, a.y1 - a.y0));
            for (int e = 0; e < 2; e++) {
                double dx = (e == 0 ? a.x0 : a.x1) - mx, dy = (e == 0 ? a.y0 : a.y1) - my;
                sxx += w * dx * dx;
                sxy += w * dx * dy;
                syy += w * dy * dy;
            }
            span = Math.max(span, Math.hypot(a.x1 - a.x0, a.y1 - a.y0));
        }
        double ang = 0.5 * Math.atan2(2 * sxy, sxx - syy), ux = Math.cos(ang), uy = Math.sin(ang);
        double ex = b.x1 - b.x0, ey = b.y1 - b.y0, len2 = Math.hypot(ex, ey);
        if (len2 < 1) return false;
        double angTol = Math.max(Math.toRadians(4), Math.atan2(3 * StreakEvidence.CELL, Math.min(len2, span)));
        if (Math.abs(ux * ex + uy * ey) / len2 < Math.cos(angTol)) return false;
        double nx = -uy, ny = ux;
        double far = Math.hypot((b.x0 + b.x1) / 2 - mx, (b.y0 + b.y1) / 2 - my);
        double tol = Math.max(hw, b.halfWidth) + 2 * StreakEvidence.CELL + 0.01 * far;
        return Math.abs((b.x0 - mx) * nx + (b.y0 - my) * ny) < tol && Math.abs((b.x1 - mx) * nx + (b.y1 - my) * ny) < tol;
    }

    /** The local time of a unit's frame ("11:40 PM"), or its minutes into the session; "" if not found. */
    private String whenOf(int unit) {
        for (F f : fs) if (f.r.index == unit) return f.when;
        return "";
    }

    static String skyClass(double m) {
        if (m >= 21.7) return "an excellent dark sky";
        if (m >= 21.3) return "a dark country sky";
        if (m >= 20.8) return "a country sky";
        if (m >= 20.3) return "a sky between country and suburbs";
        if (m >= 19.3) return "a suburban sky";
        if (m >= 18.5) return "a bright suburban sky";
        return "a city sky";
    }

    private void tipAndSpeech() {
        Problem top = null;
        for (Problem p : problems) {
            if (p.impact >= MIN_IMPACT) {
                top = p;
                break;
            }
        }
        // what counts as a problem: only those costing at least MIN_IMPACT; the rest become notes
        List<Problem> keep = new ArrayList<Problem>();
        for (Problem p : problems) {
            if (p.impact >= MIN_IMPACT) keep.add(p);
            else notes.add(p.evidence);
        }
        problems.clear();
        problems.addAll(keep);
        if (top != null) {
            tip = top.fix;
        } else if (n > 1 && nEff >= 0.8 * n) {
            tip = "Everything worked: next time try a longer session (+5 minutes) for an even smoother picture.";
        } else {
            tip = "Lay the phone flat on something firm and let it run the full session.";
        }
        StringBuilder sb = new StringBuilder("Done. ");
        sb.append(goodFrames == 1 ? "One good frame" : goodFrames + " good frames");
        if (n > 1 && equivalentSec > 0) sb.append(", like a ").append(duration(equivalentSec, true)).append(" exposure");
        sb.append('.');
        if (top != null && top.impact >= 0.05) sb.append(' ').append(top.spoken);
        else if (top == null && n > 1) sb.append(" A clear, steady session.");
        spoken = sb.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // output

    public String toJson() {
        Json j = new Json().obj();
        j.put("headline", headline);
        j.arr("problems");
        for (Problem p : problems) {
            j.obj().put("key", p.key).put("title", p.title).put("impact", round(p.impact, 3)).put("evidence", p.evidence)
                    .put("fix", p.fix).put("spoken", p.spoken).end();
        }
        j.end();
        j.arr("notes");
        for (String s : notes) j.val(s);
        j.end();
        j.obj("gain").put("text", gain).put("nEff", round(nEff, 2)).put("measured", gainMeasured)
                .put("noiseDown", round(noiseDown, 2)).put("idealDown", round(idealDown, 2))
                .put("equivalentSeconds", Math.round(equivalentSec));
        NoiseProbe.Result nr = in.out.noise;
        if (nr != null) {
            j.put("sigmaFrame", round(nr.sigmaFrame, 4)).put("sigmaStack", round(nr.sigmaStack, 4)).put("blocks", nr.blocks)
                    .put("frameUnit", in.out.noiseUnit);
        }
        j.end();
        j.obj("registration").put("text", registration).put("confidence", confidence).put("matchedMean", round(matchedMean, 1))
                .put("residualPx", round(residualMean, 3)).put("aligned", alignedFrames).put("fallback", fallbackFrames);
        if (in.out.solution != null) j.put("catalogStars", in.out.solution.matched);
        j.end();
        j.arr("frames");
        for (Frame f : frames) {
            j.obj().put("index", f.index).put("score", f.score).put("time", f.time).put("used", f.used).put("partly", f.partly);
            if (!f.reason.isEmpty()) j.put("reason", f.reason);
            j.end();
        }
        j.end();
        j.put("goodFrames", goodFrames);
        if (sky != null) {
            j.obj("sky").put("centre", sky.centre).put("placed", sky.placed);
            j.arr("lines");
            for (String s : sky.lines) j.val(s);
            j.end().arr("items");
            for (SkyGuide.Item it : sky.items) {
                j.obj().put("kind", it.kind).put("name", it.name);
                if (!it.where.isEmpty()) j.put("where", it.where);
                if (!Double.isNaN(it.mag)) j.put("mag", round(it.mag, 1));
                if (!Double.isNaN(it.x)) j.put("x", round(it.x, 3)).put("y", round(it.y, 3));
                if (!Double.isNaN(it.share)) j.put("share", round(it.share, 3));
                j.end();
            }
            j.end().end();
        }
        if (!Double.isNaN(skyMag)) j.put("skyMag", round(skyMag, 2));
        if (!streaks.isEmpty()) {
            j.obj("streaks").put("text", streaks).arr("items");
            for (Streak k : streakList) {
                j.obj().put("name", k.name).put("frames", k.frames).put("kept", k.kept);
                if (k.norad != 0) j.put("norad", k.norad);
                if (!k.time.isEmpty()) j.put("time", k.time);
                if (!Double.isNaN(k.mag)) j.put("mag", round(k.mag, 1));
                j.end();
            }
            j.end().end();
        }
        j.put("conditions", conditions);
        j.put("tip", tip).put("spoken", spoken);
        j.end();
        return j.toString();
    }

    /** The report as plain text, for the log and the review screen. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append(headline).append('\n');
        if (!gain.isEmpty()) sb.append(gain).append('\n');
        if (problems.isEmpty()) {
            sb.append("No problems found.\n");
        } else {
            int k = 1;
            for (Problem p : problems) {
                sb.append(String.format(Locale.US, "%d. %s (cost about %.0f%% of the light): %s Fix: %s%n", k++, p.title,
                        100 * p.impact, p.evidence, p.fix));
            }
        }
        for (String s : notes) sb.append("Note: ").append(s).append('\n');
        if (!registration.isEmpty()) sb.append(registration).append(" Confidence: ").append(confidence).append(".\n");
        if (!conditions.isEmpty()) sb.append(conditions).append('\n');
        if (sky != null) for (String s : sky.lines) sb.append(s).append('\n');
        if (!streaks.isEmpty()) sb.append(streaks).append('\n');
        sb.append("Frames:");
        for (Frame f : frames) {
            sb.append(String.format(Locale.US, " %d:%d", f.index, f.score));
            if (!f.reason.isEmpty()) sb.append(" (").append(f.reason).append(')');
        }
        sb.append('\n');
        sb.append("Tip: ").append(tip).append('\n');
        sb.append("Spoken: ").append(spoken);
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // helpers

    private double num(String key, double def) {
        String v = in.props != null ? in.props.getProperty(key) : null;
        if (v == null) return def;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private double medianBackground() {
        float[] v = new float[n];
        int k = 0;
        for (F f : fs) if (f.r.used && f.r.background > 0) v[k++] = (float) f.r.background;
        return k > 0 ? Stats.median(v, k) : Double.NaN;
    }

    /** " between 11:40 and 11:52 PM", " after 11:40 PM", " before 11:52 PM" or "" for the whole session. */
    private String span(int a, int b) {
        boolean fromStart = a <= 0, toEnd = b >= n - 1;
        if (fromStart && toEnd) return " through the session";
        if (toEnd) return (timed ? " after " : " from ") + fs[a].when;
        if (fromStart) return (timed ? " before " : " until ") + fs[b].when;
        if (a == b || fs[a].when.equals(fs[b].when)) return " at " + fs[a].when;
        return " between " + fs[a].whenShort + " and " + fs[b].when;
    }

    private String spanSpoken(int a, int b) {
        boolean fromStart = a <= 0, toEnd = b >= n - 1;
        if (!timed || (fromStart && toEnd)) return "";
        if (toEnd) return " after " + fs[a].whenShort;
        if (fromStart) return " until " + fs[b].whenShort;
        if (fs[a].whenShort.equals(fs[b].whenShort)) return " at " + fs[a].whenShort;
        return " from " + fs[a].whenShort + " to " + fs[b].whenShort;
    }

    /** "1 frame", "3 frames". */
    static String count(int k, String noun) {
        return k + " " + noun + (k == 1 ? "" : "s");
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** "45-second", "4-minute", "3.5-minute" ("three and a half minute" spoken). */
    static String duration(double sec, boolean spoken) {
        if (!(sec > 0)) return "short";
        if (sec < 100) return Math.max(5, Math.round(sec / 5) * 5) + "-second";
        double m = sec / 60;
        if (m < 10) {
            double h = Math.round(m * 2) / 2.0;
            if (h == Math.rint(h)) return (long) h + "-minute";
            return spoken ? (long) Math.floor(h) + " and a half minute" : String.format(Locale.US, "%.1f-minute", h);
        }
        return Math.round(m) + "-minute";
    }

    private static double median(double[] v) {
        double[] c = v.clone();
        Arrays.sort(c);
        int k = c.length;
        return k == 0 ? Double.NaN : k % 2 == 1 ? c[k / 2] : 0.5 * (c[k / 2 - 1] + c[k / 2]);
    }

    private static double mad(double[] v, double m) {
        double[] a = new double[v.length];
        for (int i = 0; i < v.length; i++) a[i] = Math.abs(v[i] - m);
        return median(a);
    }

    /** Spearman's rank correlation of the first k pairs. */
    static double spearman(double[] x, double[] y, int k) {
        double[] rx = ranks(x, k), ry = ranks(y, k);
        double mx = (k - 1) / 2.0, sxy = 0, sxx = 0, syy = 0;
        for (int i = 0; i < k; i++) {
            sxy += (rx[i] - mx) * (ry[i] - mx);
            sxx += (rx[i] - mx) * (rx[i] - mx);
            syy += (ry[i] - mx) * (ry[i] - mx);
        }
        return sxx > 0 && syy > 0 ? sxy / Math.sqrt(sxx * syy) : 0;
    }

    private static double[] ranks(final double[] v, int k) {
        Integer[] o = new Integer[k];
        for (int i = 0; i < k; i++) o[i] = i;
        Arrays.sort(o, new Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                return Double.compare(v[a], v[b]);
            }
        });
        double[] r = new double[k];
        for (int i = 0; i < k; ) {
            int j = i;
            while (j + 1 < k && v[o[j + 1]] == v[o[i]]) j++;
            double avg = (i + j) / 2.0;
            for (int q = i; q <= j; q++) r[o[q]] = avg;
            i = j + 1;
        }
        return r;
    }

    private static double round(double v, int digits) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        double s = Math.pow(10, digits);
        return Math.round(v * s) / s;
    }
}
