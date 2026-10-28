package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

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
        /** clouds, dew, smudge, focus, moon, bump, moved, camera, thermal, dropped, alignment, trails. */
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
    /** The meteors caught: how many, from which showers, the brightest, and what was done with them; "" if none. */
    public String meteors = "";
    public final List<Meteor> meteorList = new ArrayList<Meteor>();

    /** A meteor caught. */
    public static final class Meteor {
        /** Its frame's local time ("9:43 PM"), what it was ("a Geminid"), where it was ("from high in the east to the south"). */
        public String time = "", what = "", where = "";
        /** Its estimated magnitude at its brightest and its length (degrees); NaN where unknown. */
        public double mag = Double.NaN, lengthDeg = Double.NaN;
        public boolean kept;

        /** "9:43 PM: a Geminid, about magnitude -2, 12 degrees long, from high in the east to the south". */
        public String line() {
            StringBuilder sb = new StringBuilder();
            if (!time.isEmpty()) sb.append(time).append(": ");
            sb.append(what);
            if (!Double.isNaN(mag)) sb.append(String.format(Locale.US, ", about magnitude %s", signed(Math.round(mag))));
            if (!Double.isNaN(lengthDeg)) sb.append(String.format(Locale.US, ", %d degree%s long", Math.round(lengthDeg),
                    Math.round(lengthDeg) == 1 ? "" : "s"));
            if (!where.isEmpty()) sb.append(", ").append(where);
            return sb.toString();
        }
    }

    /** A whole number with a real minus sign ("-2" as "\u22122"). */
    static String signed(long v) {
        return v < 0 ? "\u2212" + (-v) : Long.toString(v);
    }

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
    /** Star trails: nothing is lined up or averaged, so there's no noise gain, alignment or rejection to report. */
    private boolean starTrails;
    /**
     * Frames (units) the capture planned and didn't get, not yet put down to a cause: the gaps take their share
     * first ({@link #thermal}), an early stop the rest ({@link #endedEarly}).
     */
    private double missingUnits;
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
        r.focusNote();
        r.bump();
        r.moon();
        r.thermal();
        r.endedEarly();
        r.combined();
        if (!r.starTrails) {
            r.alignment();
            r.trails();
            r.colour();
        }
        Collections.sort(r.problems, new Comparator<Problem>() {
            @Override
            public int compare(Problem a, Problem b) {
                return Double.compare(b.impact, a.impact);
            }
        });
        r.scores();
        r.headline();
        if (!r.starTrails) {
            r.gain();
            r.registration();
        }
        r.skyGuide();
        r.streaks();
        r.meteors();
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
        starTrails = o.trails || (in.props != null && "trails".equals(in.props.getProperty("mode")));
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
        // the clean lens's ratio the limit came from (before 1.2.1 the limit was always 1.5 times it plus 0.05)
        double clean = num("preflight.haloClean", (limit - 0.05) / 1.5);
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
        // a glow from the start: a smudge (or dew before the session began). The check's warning counts when the
        // session's frames bear it out, well clear of a clean lens (not just above it: the readings wander)
        boolean warned = "true".equals(in.props.getProperty("preflight.lensWarned"));
        double c = Math.max(0, clean);
        if ((halo0 > limit || warned && halo0 > 0.5 * (c + limit)) && halo0 > c) {
            double cost = 1 - Math.pow((1 + c) / (1 + halo0), 2);
            Problem p = new Problem("smudge", "Smudged lens", cost);
            p.evidence = String.format(Locale.US, "Every star had a glow around it all session (%.0f%% of its light; a clean "
                    + "lens gives about %.0f%%), so the lens was probably smudged or already damp.", 100 * halo0 / (1 + halo0), 100 * c / (1 + c));
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
        String how = in.props.getProperty("focus.how", "");
        if (!how.isEmpty() && !how.equals("fixed") && !"true".equals(in.props.getProperty("focus.calibrated"))) {
            p.fix += " A few daylight focus calibrations at different temperatures (Advanced, Focus) let it set the focus for the "
                    + "night's temperature.";
        }
        p.spoken = "The focus drifted" + (on != null && timed ? " after " + on.whenShort : "") + ".";
        problems.add(p);
    }

    /** How the focus was set (A5, F7), and the checks made during the session, as a note. */
    private void focusNote() {
        String how = in.props.getProperty("focus.how", "");
        if (how.isEmpty() || how.equals("fixed")) return;
        double value = num("focus.value", Double.NaN), hfr = num("focus.hfr", Double.NaN), zero = num("focus.hfrAtZero", Double.NaN);
        double pred = num("focus.predicted", Double.NaN), temp = num("focus.tempC", Double.NaN);
        boolean measured = "true".equals(in.props.getProperty("focus.zeroMeasured"));
        String at = Double.isNaN(temp) ? "" : String.format(Locale.US, " for %.0f C", temp);
        StringBuilder sb = new StringBuilder();
        if (how.equals("swept") || how.equals("checked")) {
            sb.append(how.equals("swept") ? String.format(Locale.US, "Focus found on the stars: %.3f", value)
                    : String.format(Locale.US, "Focus: last time's %.3f, checked on the stars with two frames", value));
            if (hfr > 0 && zero > 0) {
                sb.append(String.format(Locale.US, ", stars %.1f pixels across against %.1f at the lens's own infinity%s", 2 * hfr, 2 * zero,
                        measured ? "" : " (from the curve)"));
            } else if (hfr > 0) {
                sb.append(String.format(Locale.US, ", stars %.1f pixels across", 2 * hfr));
            }
            if (!Double.isNaN(pred)) sb.append(String.format(Locale.US, "; the daylight calibration predicted %.3f%s", pred, at));
            sb.append('.');
        } else if (how.equals("predicted")) {
            sb.append(String.format(Locale.US, "Focus: %.3f, what the daylight calibration predicts%s (not checked on the stars).", value, at));
        } else if (how.equals("saved")) {
            sb.append(String.format(Locale.US, "Focus: %.3f, found on the stars before (not checked tonight).", value));
        } else {
            sb.append("Focus: the lens's own infinity (not checked on the stars).");
        }
        String checks = in.props.getProperty("focus.checks", "");
        if (!checks.isEmpty()) {
            // a check that follows straight on from a move cut short belongs to the same check: {minutes, from, to, steps}
            List<double[]> eps = new ArrayList<double[]>();
            for (String c : checks.split(";")) {
                String[] q = c.split(",");
                if (q.length < 3) continue;
                try {
                    double m = Double.parseDouble(q[0]), a = Double.parseDouble(q[1]), b = Double.parseDouble(q[2]);
                    double[] last = eps.isEmpty() ? null : eps.get(eps.size() - 1);
                    if (last != null && m - last[0] < 3 && Math.abs(a - last[2]) < 1e-4 && Math.abs(last[2] - last[1]) >= 1e-4) {
                        last[2] = b;
                        last[3]++;
                    } else {
                        eps.add(new double[]{m, a, b, 1});
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            StringBuilder moves = new StringBuilder();
            for (double[] e : eps) {
                if (Math.abs(e[2] - e[1]) < 1e-4) continue;
                moves.append(moves.length() == 0 ? "" : ", ").append(String.format(Locale.US, "from %.3f to %.3f%s after %.0f minutes", e[1], e[2],
                        e[3] > 1 ? ", a step at a time," : "", e[0]));
            }
            if (!eps.isEmpty()) {
                sb.append(String.format(Locale.US, " The stars grew during the session, so the focus was checked %s: %s.",
                        eps.size() == 1 ? "once" : eps.size() + " times", moves.length() == 0 ? "it still held" : "it moved " + moves));
            }
        }
        notes.add(sb.toString());
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
        // the sky without the Moon: this site's own when sessions or sky readings there measured it, else a dark one
        double natural = num("site.skyMag", Double.NaN);
        double naturalNl = nanoLamberts(Double.isNaN(natural) ? 21.0 : natural);
        double measuredNl = Double.isNaN(skyMag) ? Double.NaN : nanoLamberts(skyMag);
        double shareSum = 0, modelSum = 0, maxAlt = -90, illum = 0, sepMid = 0, factor = 1;
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
            double modelShare = Math.min(0.95, bm / (naturalNl + bm));
            // the sky as measured holds the moonlight on top of the sky's own: a Moon behind a house, trees or a
            // hill is up but lights little here, and the measurement shows it
            double share = Double.isNaN(measuredNl) ? modelShare
                    : Math.min(0.95, Math.min(bm, Math.max(0, measuredNl - naturalNl)) / measuredNl);
            modelSum += modelShare;
            shareSum += share;
            if (s == samples / 2 || factor == 1) factor = 1 / (1 - share);
        }
        double impact = shareSum / samples;
        if (maxAlt <= 0) return;
        if (impact < 0.005) {
            if (modelSum / samples >= 0.2) {
                notes.add(String.format(Locale.US, "The Moon (%.0f%% lit) was up but didn't brighten the sky here, so something hid it "
                        + "(a house, trees, a hill or a cloud).", 100 * illum));
            }
            return;
        }
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
        double spanSec = span();
        double[] ex = new double[n];
        for (int i = 0; i < n; i++) ex[i] = fs[i].r.exposure;
        double exp = n > 0 ? median(ex) : 0;
        // what a gap cost: the capture runs on until it has all its planned frames, so one that got them made the gaps
        // up (they cost time, not light); one that stopped first lost what the gaps would have held. Sessions before
        // 1.2.1 didn't say what they planned: the gaps' share of the session, as then.
        int planned = plannedUnits();
        missingUnits = Math.max(0, planned - n);
        boolean madeUp = planned > 0 && n >= planned;
        // the capture pausing while the phone was picked up or moved leaves gaps of its own: the pause, the exposure
        // it cut short and the settling after it
        int pauses = (int) num("capture.pauses", 0);
        if (pauses > 0 && lostSec > 0) {
            double movedSec = Math.min(lostSec, num("capture.pausedMs", 0) / 1000.0 + pauses * (exp + 3));
            lostSec -= movedSec;
            if (movedSec >= 1 && spanSec > 0) {
                Problem p = new Problem("moved", "The phone was moved", gapCost(movedSec, spanSec, exp, planned));
                p.evidence = "The capture paused " + times(pauses) + " when the phone was picked up or moved" + gapWords(movedSec, madeUp);
                p.fix = FIX_MOVED;
                p.spoken = "The capture paused when the phone was moved.";
                problems.add(p);
            }
        }
        // renewing the capture session before a camera's known stop leaves a short gap each time (planned, made up);
        // renewals learned from this session's own stop are told with that stop
        int renewals = (int) num("capture.renewals", 0);
        int learned = (int) num("capture.renewLearned", 0);
        boolean reopened = in.props != null && "reopen".equals(in.props.getProperty("capture.renewHow"));
        String renewed = null;
        if (renewals > 0) {
            lostSec = Math.max(0, lostSec - renewals * (reopened ? 3.0 : 2.0));
            int every = (int) num("capture.renewAfter", 0);
            String pause = reopened ? "a pause of about 2 seconds each time." : "a pause of about a second each time.";
            if (learned > 0) {
                renewed = String.format(Locale.US, "had stopped after %d frames in a row, so from then on it %s every %d frames (%s), %s",
                        learned, reopened ? "was opened again" : "got a fresh capture session", every, times(renewals), pause);
            } else {
                notes.add(String.format(Locale.US, "The camera %s %s (every %d frames), as it stops after a few more in a row: %s",
                        reopened ? "was opened again" : "got a fresh capture session", times(renewals), every, pause));
            }
        }
        // so does the camera losing frames, or going silent until the app restarts it, which closing apps won't cure
        int failed = (int) num("capture.framesFailed", 0);
        int stalls = (int) num("capture.stalls", 0);
        if ((failed > 0 || stalls > 0) && lostSec > 0) {
            double camSec = Math.min(lostSec, num("capture.stalledMs", 0) / 1000.0 + stalls * 3 + failed * (exp + 1));
            lostSec -= camSec;
            if (camSec >= 1 && spanSec > 0) {
                Problem p = new Problem("camera", "The camera had trouble", gapCost(camSec, spanSec, exp, planned));
                StringBuilder e = new StringBuilder("The camera ");
                if (failed > 0) e.append("lost ").append(count(failed, "frame"));
                if (failed > 0 && stalls > 0) e.append(" and ");
                if (stalls > 0) e.append("stopped sending frames ").append(times(stalls)).append(" until the app restarted it");
                e.append(gapWords(camSec, madeUp));
                if (renewed != null) {
                    e.append(" It ").append(renewed);
                    renewed = null;
                }
                p.evidence = e.toString();
                p.fix = "Nothing to do if it's rare: the app carries on by itself. If it happens every session, restart the phone before the next one.";
                p.spoken = "The camera had trouble, but the session carried on.";
                problems.add(p);
            }
        }
        if (renewed != null) notes.add("The camera " + renewed);
        boolean hot = status >= 3;
        if (!hot && dropped == 0 && (lostSec < 1 || spanSec <= 0 || lostSec / spanSec < 0.005)) return;
        Problem p = new Problem(hot ? "thermal" : "dropped", hot ? "The phone got hot" : "Frames dropped", gapCost(lostSec, spanSec, exp, planned));
        StringBuilder e = new StringBuilder();
        if (hot) {
            e.append("The phone reported it was ").append(thermalName(status)).append(" hot");
            if (!Double.isNaN(hotAt)) e.append(" from ").append(clock.format(new Date((long) hotAt)));
        }
        if (dropped > 0) {
            String d = count(dropped, "frame") + " dropped" + (hot ? "" : " while the phone caught up");
            if (e.length() == 0) e.append(d);
            else e.append(" (").append(d).append(')');
        }
        if (lostSec >= 1) {
            if (e.length() == 0) e.append(String.format(Locale.US, "%.0f seconds of the session weren't exposed", lostSec))
                    .append(madeUp ? "; the session ran that much longer to get all its frames" : "");
            else if (madeUp) e.append(gapWords(lostSec, true));
            else e.append(String.format(Locale.US, ", and %.0f seconds of the session weren't exposed", lostSec));
        }
        if (e.length() == 0 || e.charAt(e.length() - 1) != '.') e.append('.');
        p.evidence = e.toString();
        p.fix = hot ? FIX_HOT : "Close other apps before a session so the phone can keep up.";
        p.spoken = hot ? "The phone got hot." : "Some frames were dropped.";
        problems.add(p);
    }

    private static final String FIX_MOVED = "Lay the phone where nothing will knock it and leave it until it says Done (picking it up pauses "
            + "the capture; it carries on once it's back down).";
    private static final String FIX_HOT = "Start with a cool phone: take the case off, keep it out of the sun and warm pockets, and don't "
            + "charge it during a session.";

    /**
     * The share of the session's light {@code sec} seconds without exposures cost: none if the capture made them up
     * (it runs on until it has its planned frames), else the frames they would have held, out of those planned and
     * not got; without a plan (sessions before 1.2.1), their share of the session.
     */
    private double gapCost(double sec, double spanSec, double exp, int planned) {
        if (planned <= 0) return spanSec > 0 ? sec / spanSec : 0;
        if (exp <= 0) return 0;
        double units = Math.min(missingUnits, sec / exp);
        missingUnits -= units;
        return units / planned;
    }

    /** How a gap of {@code sec} seconds ends its sentence: made up by capturing longer, or time not exposed. */
    private static String gapWords(double sec, boolean madeUp) {
        return madeUp ? String.format(Locale.US, "; the session ran %.0f seconds longer to get all its frames.", sec)
                : String.format(Locale.US, ", so %.0f seconds of the session weren't exposed.", sec);
    }

    /** Units (frames as stacked) the capture planned, with any time added, or 0 if the session didn't say (before 1.2.1). */
    private int plannedUnits() {
        int frames = (int) num("capture.framesPlanned", 0);
        int per = Math.max(1, (int) num("capture.framesPerUnit", 1));
        return (frames + per - 1) / per;
    }

    /** Seconds from the start of the first frame to the end of the last. */
    private double span() {
        return n > 0 ? (fs[n - 1].t + 0.5 * fs[n - 1].r.exposure) - (fs[0].t - 0.5 * fs[0].r.exposure) : 0;
    }

    // ------------------------------------------------------------------------------------------------
    // a capture that ended before its planned end: why, and the share of the session it cost

    private void endedEarly() {
        String key = in.props != null ? in.props.getProperty("capture.stopKey", "") : "";
        String why = in.props != null ? in.props.getProperty("capture.stopReason", "") : "";
        if (key.isEmpty() || why.isEmpty() || n == 0) return;
        int planned = plannedUnits();
        double plannedSec = num("capture.durationSec", 0), spanSec = span();
        String frames = planned > n ? String.format(Locale.US, "%d of %d frames", n, planned) : count(n, "frame");
        if (key.equals("you") || key.equals("pickedUp") || key.equals("end") || key.equals("dawn")) {
            // planned, or done by hand: worth a line, not a problem
            String at = String.format(Locale.US, " after %s (%s)", PostProcessor.duration(spanSec), frames);
            notes.add(key.equals("you") ? capital(why) + at + "."
                    : key.equals("pickedUp") ? "The session ended when the phone was picked up near the end," + at + "."
                    : key.equals("end") ? "Stopped as planned: " + why + "."
                    : "Stopped as planned at " + why + ".");
            return;
        }
        // what the early end cost: the planned frames it didn't get, less those the gaps before it already account for
        double missed = planned > 0 ? missingUnits / planned : 0;
        String target, title, fix, spoken;
        switch (key) {
            case "camera":
                target = "camera";
                title = "The camera stopped";
                fix = "Restart the phone before the next session.";
                spoken = "The camera stopped, so the session ended early.";
                break;
            case "taken":
                target = "camera";
                title = "Another app took the camera";
                fix = "Don't open the Camera app or start a video call during a session: they take the camera over.";
                spoken = "Another app took the camera, so the session ended early.";
                break;
            case "battery":
                target = "battery";
                title = "The battery ran low";
                fix = "Charge the phone before a long session, or run it from a power bank.";
                spoken = "The battery ran low, so the session ended early.";
                break;
            case "storage":
                target = "storage";
                title = "Storage ran low";
                fix = "Free up space on the phone before the next session, or keep fewer sessions' frames (Settings, Sessions and storage).";
                spoken = "Storage ran low, so the session ended early.";
                break;
            case "hot":
                target = "thermal";
                title = "The phone got hot";
                fix = FIX_HOT;
                spoken = "The phone got too hot, so the session ended early.";
                break;
            case "moved":
                target = "moved";
                title = "The phone was moved";
                fix = FIX_MOVED;
                spoken = "The phone was picked up, so the session ended early.";
                break;
            default:
                target = "early";
                title = "The session ended early";
                fix = "If it happens again, restart the phone before the next session.";
                spoken = "The session ended early.";
                break;
        }
        // what it had by then, in exposure (the session may have run longer: a camera that went quiet is waited for)
        double captured = 0;
        for (F f : fs) captured += f.r.exposure;
        String after = String.format(Locale.US, "with %s%s captured (%s)", PostProcessor.duration(captured),
                plannedSec > captured + 1 ? " of the planned " + PostProcessor.duration(plannedSec) : "", frames);
        String evidence = capital(why) + ", so the session stopped " + after + ".";
        // the same trouble may already be a problem (the camera faltering before it stopped, the heat): one line for both
        Problem p = null;
        for (Problem q : problems) if (q.key.equals(target)) p = q;
        if (p == null) {
            p = new Problem(target, title, missed);
            p.evidence = evidence;
            problems.add(p);
        } else {
            p.title = title;
            p.impact += missed;
            // the heat's own line already says how hot it got
            p.evidence += " " + (key.equals("hot") ? "That ended the session " + after + "." : evidence);
        }
        p.fix = fix;
        p.spoken = spoken;
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
    // colour (F4)

    /** Where the photo's colours came from: the catalogue stars' own, or the camera's fixed balance and why. */
    private void colour() {
        ColorCalibration.Result c = in.out.colour;
        if (c == null) return;
        if (c.applied) {
            notes.add(String.format(Locale.US, "Colours come from %d stars of known colour: a Sun-like star is white, hot stars like "
                    + "Vega blue-white and cool ones like Arcturus orange (red x%.2f, blue x%.2f over the camera's own balance).",
                    c.used, c.gainR / c.gainG, c.gainB / c.gainG));
        } else {
            notes.add("Colours use the camera's own balance: " + c.reason + ".");
        }
    }

    // ------------------------------------------------------------------------------------------------
    // frame scores

    private void scores() {
        frames = new Frame[n];
        double[] snr = new double[n];
        float[] sv = new float[n], hv = new float[n], nv = new float[n];
        int ks = 0, kh = 0, kn = 0;
        float[] ev = new float[n];
        int ke = 0;
        for (F f : fs) {
            if (f.r.used && f.r.noise > 0) nv[kn++] = (float) f.r.noise;
            if (f.r.used && f.r.exposure > 0) ev[ke++] = (float) f.r.exposure;
        }
        double noiseMed = kn > 0 ? Stats.median(nv, kn) : 1;
        double expMed = ke > 0 ? Stats.median(ev, ke) : 0;
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
                    // a unit short of its frames (the session stopped part way through it) holds less light, and is
                    // noisier for that alone: not a brighter sky
                    double ex = expMed > 0 && f.r.exposure > 0 ? Math.min(1, f.r.exposure / expMed) : 1;
                    double lE = 1 - ex;
                    double nr = sig / noiseMed * Math.sqrt(ex);
                    double lN = 1 - Math.min(1, 1 / (nr * nr));
                    double m = Math.max(Math.max(Math.max(lShare, lSharp), Math.max(lT, lN)), lE);
                    if (m == lShare) fr.reason = String.format(Locale.US, "cloud over part of it (%.0f%% used)", 100 * f.share);
                    else if (m == lE) fr.reason = String.format(Locale.US, "shorter (%.0f s, the others %.0f s)", f.r.exposure, expMed);
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
            k.kept = o.meteorsKept && "meteor".equals(kinds[i]);
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
                : anyKept ? (o.satellitesKept ? " The satellites and meteors were kept, as asked; the rest were taken out."
                : " The meteors were kept, as asked; the rest were taken out.")
                : " All were taken out of the stack, faint edges included.");
        if (list.isEmpty() && !note.isEmpty()) sb.append(" (Satellites weren't named: ").append(note).append(".)");
        streaks = sb.toString();
    }

    /**
     * The meteors ({@link MeteorLog}): how many, by shower, the brightest, and whether they stayed in the picture;
     * then each one, in time order.
     */
    private void meteors() {
        PostProcessor.Output o = in.out;
        List<MeteorLog.Meteor> list = o.meteors;
        if (list == null || list.isEmpty()) return;
        Map<String, Integer> byShower = new LinkedHashMap<String, Integer>();
        Map<String, String[]> words = new HashMap<String, String[]>();
        int kept = 0;
        MeteorLog.Meteor brightest = null;
        boolean attributed = false;
        for (MeteorLog.Meteor m : list) {
            String key = m.shower != null ? m.shower.code : "";
            Integer c = byShower.get(key);
            byShower.put(key, c == null ? 1 : c + 1);
            if (m.shower != null) words.put(key, new String[]{m.shower.member, m.shower.name});
            attributed |= m.attributed;
            if (m.kept) kept++;
            if (!Double.isNaN(m.mag) && (brightest == null || m.mag < brightest.mag)) brightest = m;
            Meteor k = new Meteor();
            k.time = whenOf(m.unit);
            k.what = m.what();
            k.where = m.where();
            k.mag = m.mag;
            k.lengthDeg = m.lengthDeg;
            k.kept = m.kept;
            meteorList.add(k);
        }
        int n = list.size();
        StringBuilder sb = new StringBuilder();
        if (!attributed) {
            sb.append(n == 1 ? "One meteor." : n + " meteors.");
        } else {
            List<String> parts = new ArrayList<String>();
            for (Map.Entry<String, Integer> e : byShower.entrySet()) {
                if (e.getKey().isEmpty()) continue;
                String[] w = words.get(e.getKey());
                parts.add(e.getValue() == 1 ? "1 " + w[0] : e.getValue() + " " + w[1]);
            }
            Integer spo = byShower.get("");
            if (spo != null) parts.add(spo + " sporadic");
            sb.append(n == 1 ? "One meteor: " : n + " meteors: ").append(n == 1 ? list.get(0).what() : SkyGuide.list(parts)).append('.');
        }
        if (brightest != null) {
            String t = whenOf(brightest.unit);
            sb.append(n == 1 ? " About magnitude " : " The brightest" + (t.isEmpty() ? "" : ", at " + t + ",") + " was about magnitude ")
                    .append(signed(Math.round(brightest.mag))).append('.');
        }
        sb.append(kept == n ? (n == 1 ? " Kept in the picture at full brightness, as asked." : " All kept in the picture at full brightness, as asked.")
                : kept > 0 ? " " + kept + " kept in the picture, as asked."
                : o.meteorsKept ? " Quick developing couldn't keep " + (n == 1 ? "it" : "them") + "; developing again in full (Edit) keeps "
                + (n == 1 ? "it." : "them.")
                : " Taken out with the other streaks; Keep meteors, in the settings, leaves them in.");
        meteors = sb.toString();
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
        } else if (starTrails) {
            tip = "Everything worked: trails grow with time, so a longer session (30 minutes or more) draws longer arcs.";
        } else if (n > 1 && nEff >= 0.8 * n) {
            tip = "Everything worked: next time try a longer session (+5 minutes) for an even smoother picture.";
        } else {
            tip = "Lay the phone flat on something firm and let it run the full session.";
        }
        StringBuilder sb = new StringBuilder("Done. ");
        if (starTrails) sb.append("Star trails from ").append(goodFrames == 1 ? "one frame" : goodFrames + " frames");
        else sb.append(goodFrames == 1 ? "One good frame" : goodFrames + " good frames");
        if (!starTrails && n > 1 && equivalentSec > 0) {
            String d = duration(equivalentSec, true);
            sb.append(", like ").append(article(d)).append(' ').append(d).append(" exposure");
        }
        sb.append('.');
        if (top != null && top.impact >= 0.05) sb.append(' ').append(top.spoken);
        else if (top == null && n > 1 && !starTrails) sb.append(" A clear, steady session.");
        List<MeteorLog.Meteor> ms = in.out.meteors;
        if (ms != null && !ms.isEmpty()) {
            MeteorShowers.Shower main = null;
            int members = 0;
            for (MeteorLog.Meteor m : ms) {
                if (m.shower == null) continue;
                if (main == null) main = m.shower;
                if (m.shower == main) members++;
            }
            if (ms.size() == 1) sb.append(" Caught ").append(ms.get(0).what()).append('.');
            else sb.append(" Caught ").append(ms.size()).append(" meteors").append(main == null ? "" : members == ms.size()
                    ? ", all " + main.name : members == 1 ? ", one of them " + MeteorLog.article(main.member) + " " + main.member
                    : ", " + members + " of them " + main.name).append('.');
        }
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
        NoiseProbe.Result nr = in != null ? in.out.noise : null;
        if (nr != null) {
            j.put("sigmaFrame", round(nr.sigmaFrame, 4)).put("sigmaStack", round(nr.sigmaStack, 4)).put("blocks", nr.blocks)
                    .put("frameUnit", in.out.noiseUnit);
        }
        j.end();
        j.obj("registration").put("text", registration).put("confidence", confidence).put("matchedMean", round(matchedMean, 1))
                .put("residualPx", round(residualMean, 3)).put("aligned", alignedFrames).put("fallback", fallbackFrames);
        if (in != null && in.out.solution != null) j.put("catalogStars", in.out.solution.matched);
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
        if (!meteors.isEmpty()) {
            j.obj("meteors").put("text", meteors).arr("items");
            for (Meteor k : meteorList) {
                j.obj().put("time", k.time).put("what", k.what).put("where", k.where).put("kept", k.kept);
                if (!Double.isNaN(k.mag)) j.put("mag", round(k.mag, 1));
                if (!Double.isNaN(k.lengthDeg)) j.put("lengthDeg", round(k.lengthDeg, 1));
                j.end();
            }
            j.end().end();
        }
        j.put("conditions", conditions);
        j.put("tip", tip).put("spoken", spoken);
        j.end();
        return j.toString();
    }

    /**
     * A saved report read back (from a session's report.json), for the morning summary and old sessions: what
     * the report screen shows. Throws IllegalArgumentException if it isn't a report.
     */
    public static ReportCard fromJson(String json) {
        JsonTree.Obj o = JsonTree.object(json);
        ReportCard r = new ReportCard();
        r.headline = o.str("headline", "");
        for (JsonTree.Obj x : o.objs("problems")) {
            Problem p = new Problem(x.str("key", ""), x.str("title", ""), x.num("impact", 0));
            p.evidence = x.str("evidence", "");
            p.fix = x.str("fix", "");
            p.spoken = x.str("spoken", "");
            r.problems.add(p);
        }
        r.notes.addAll(o.strs("notes"));
        JsonTree.Obj g = o.obj("gain");
        if (g != null) {
            r.gain = g.str("text", "");
            r.nEff = g.num("nEff", Double.NaN);
            r.gainMeasured = g.bool("measured", false);
            r.noiseDown = g.num("noiseDown", Double.NaN);
            r.idealDown = g.num("idealDown", Double.NaN);
            r.equivalentSec = g.num("equivalentSeconds", Double.NaN);
        }
        JsonTree.Obj reg = o.obj("registration");
        if (reg != null) {
            r.registration = reg.str("text", "");
            r.confidence = reg.str("confidence", "");
            r.matchedMean = reg.num("matchedMean", Double.NaN);
            r.residualMean = reg.num("residualPx", Double.NaN);
            r.alignedFrames = reg.integer("aligned", 0);
            r.fallbackFrames = reg.integer("fallback", 0);
        }
        List<JsonTree.Obj> fl = o.objs("frames");
        r.frames = new Frame[fl.size()];
        for (int k = 0; k < fl.size(); k++) {
            JsonTree.Obj x = fl.get(k);
            Frame f = new Frame();
            f.index = x.integer("index", k + 1);
            f.score = x.integer("score", 0);
            f.time = x.str("time", "");
            f.used = x.bool("used", true);
            f.partly = x.bool("partly", false);
            f.reason = x.str("reason", "");
            r.frames[k] = f;
        }
        r.goodFrames = o.integer("goodFrames", 0);
        JsonTree.Obj sky = o.obj("sky");
        if (sky != null) r.sky = SkyGuide.fromJson(sky);
        r.skyMag = o.num("skyMag", Double.NaN);
        JsonTree.Obj st = o.obj("streaks");
        if (st != null) {
            r.streaks = st.str("text", "");
            for (JsonTree.Obj x : st.objs("items")) {
                Streak k = new Streak();
                k.name = x.str("name", "");
                k.frames = x.integer("frames", 0);
                k.kept = x.bool("kept", false);
                k.norad = x.integer("norad", 0);
                k.time = x.str("time", "");
                k.mag = x.num("mag", Double.NaN);
                r.streakList.add(k);
            }
        }
        JsonTree.Obj me = o.obj("meteors");
        if (me != null) {
            r.meteors = me.str("text", "");
            for (JsonTree.Obj x : me.objs("items")) {
                Meteor k = new Meteor();
                k.time = x.str("time", "");
                k.what = x.str("what", "");
                k.where = x.str("where", "");
                k.mag = x.num("mag", Double.NaN);
                k.lengthDeg = x.num("lengthDeg", Double.NaN);
                k.kept = x.bool("kept", false);
                r.meteorList.add(k);
            }
        }
        r.conditions = o.str("conditions", "");
        r.tip = o.str("tip", "");
        r.spoken = o.str("spoken", "");
        return r;
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
        if (!meteors.isEmpty()) sb.append("Meteors: ").append(meteors).append('\n');
        for (Meteor k : meteorList) sb.append("  ").append(k.line()).append('\n');
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

    /**
     * Frames added up from shorter exposures: said, as the capture counted exposures ("frame 44 of 44") and the report
     * counts frames, and so you know what the camera does.
     */
    private void combined() {
        int per = (int) num("capture.framesPerUnit", 1);
        double sec = num("frameExposureNs", 0) / 1e9;
        if (per <= 1 || !(sec > 0)) return;
        String why = in.props != null ? in.props.getProperty("capture.shortWhy", "") : "";
        if (why.isEmpty()) why = "The exposures were shorter than planned";
        int done = (int) num("capture.framesDone", 0);
        String len = Math.abs(sec - Math.rint(sec)) < 0.05 ? Long.toString(Math.round(sec)) : String.format(Locale.US, "%.1f", sec);
        notes.add(String.format(Locale.US, "%s, so each frame here is %s %s-second exposures added together%s.", why, small(per), len,
                done > per ? " (" + done + " in all)" : ""));
    }

    // ------------------------------------------------------------------------------------------------
    // helpers

    /** "two" to "ten", then digits. */
    static String small(int k) {
        String[] w = {"zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten"};
        return k >= 0 && k < w.length ? w[k] : Integer.toString(k);
    }

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

    /** "a" or "an" before a number in digits, as it's said: an 8-minute, an 80-second, an 11-minute, a 10-minute. */
    static String article(String s) {
        return s.matches("(8|11|18)(\\D.*)?|8\\d.*") ? "an" : "a";
    }

    static String times(int k) {
        return k == 1 ? "once" : k == 2 ? "twice" : k + " times";
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
