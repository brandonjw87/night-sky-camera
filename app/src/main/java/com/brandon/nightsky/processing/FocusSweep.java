package com.brandon.nightsky.processing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Finding the best focus by measuring a few focus settings (A5 at night, F7 in daylight). It says which focus
 * value to measure next and fits the measurements: at night the stars' half-flux radius (HFR), whose square is a
 * parabola in the focus value (a blur disc growing linearly away from focus, added to the stars' own size), so
 * its vertex is the best focus; in daylight the Tenengrad sharpness of a distant scene, whose logarithm is about
 * a parabola near its peak.
 * <p>
 * Ways to sweep: a full sweep (coarse steps from the lens's infinity end, then fine steps around the best coarse
 * one), a fine sweep around a value (the daylight calibration's prediction, F7), and a two-frame check of a known
 * value (one frame each side of it: unequal star sizes say which way, and how far, the best focus has moved).
 * A sweep whose best value lands on its edge is extended that way.
 */
public final class FocusSweep {
    public enum Kind { STARS, DAYLIGHT }

    /** The focus control: how far to sweep, in which steps, and how fast stars blur away from focus. */
    public static final class Lens {
        /** The values are diopters (calibrated or approximate), not the lens's own uncalibrated units. */
        public boolean calibrated;
        /** Nearest focus the lens has (its largest value); 0 for a fixed-focus lens. */
        public double minFocus;
        /** Farthest from infinity worth sweeping, the coarse and fine steps (focus units). */
        public double max = 0.8, coarse = 0.2, fine = 0.05;
        /** Growth of the stars' HFR (RAW px) per focus unit away from the best focus, from the optics (0: unknown). */
        public double slope;
        /** The blur disc's diameter in RAW px per focus unit, from the optics (0: unknown). */
        public double blurPerUnit;

        /**
         * From the camera's description: the fine step is where the blur disc reaches about 0.6 px (stars stay
         * within a few per cent of their size), the coarse step four of those.
         *
         * @param minFocus   LENS_INFO_MINIMUM_FOCUS_DISTANCE
         * @param hyperfocal LENS_INFO_HYPERFOCAL_DISTANCE (diopters, 0 if unknown)
         * @param pixelUm    pixel pitch of the frames (µm)
         */
        public static Lens of(double minFocus, double hyperfocal, boolean calibrated, double focalMm, double fNumber, double pixelUm) {
            Lens l = new Lens();
            l.calibrated = calibrated;
            l.minFocus = minFocus;
            // an uncalibrated lens: its nearest focus taken as about 10 diopters
            double unit = calibrated ? 1 : (minFocus > 0 ? minFocus / 10 : 1);
            double k = 0;
            if (focalMm > 0 && fNumber > 0 && pixelUm > 0) k = (focalMm * 1e-3) * (focalMm * 1e-3) / fNumber / (pixelUm * 1e-6);
            double fine;
            if (k > 0) fine = Stats.clamp(0.6 / k, 0.01, 0.2);
            else if (calibrated && hyperfocal > 0) fine = Stats.clamp(hyperfocal / 2, 0.01, 0.2);
            else fine = 0.05;
            l.fine = fine * unit;
            l.coarse = 4 * l.fine;
            l.max = Math.max(0.6 * unit, 16 * l.fine);
            if (minFocus > 0) l.max = Math.min(l.max, 0.5 * minFocus);
            l.blurPerUnit = k / unit;
            // a uniform disc of diameter d has a half-flux radius (mean radius) of d / 3
            l.slope = k / 3 / unit;
            return l;
        }

        public String describe() {
            return String.format(Locale.US, "%s, steps %.3f and %.3f up to %.2f%s", calibrated ? "diopters" : "the lens's own units",
                    fine, coarse, max, slope > 0 ? String.format(Locale.US, ", stars grow %.1f px per unit", slope) : "");
        }
    }

    /** One measured focus setting. */
    public static final class Point {
        public double focus;
        /** Stars: HFR (px); daylight: sharpness. */
        public double value, noise;
        public boolean ok;
        public int stars;

        double cost(Kind kind) {
            return kind == Kind.STARS ? value : -value;
        }
    }

    /** The two-frame check of a known focus. */
    public static final class Check {
        /** The focus checked and the step each side. */
        public double focus, delta;
        /** How far the best focus seems to be from it (best = focus - shift), and how sure that is. */
        public double shift, sigma;
        /** The best focus estimated from the two frames. */
        public double best;
        /** The known focus still holds (moved less than a fine step, or no more than the noise). */
        public boolean holds;
        public String describe() {
            return String.format(Locale.US, "%.3f checked at %.3f and %.3f: best %.3f (%+.3f, +/-%.3f): %s", focus, focus - delta, focus + delta,
                    best, -shift, sigma, holds ? "still holds" : "moved");
        }
    }

    public static final class Result {
        public boolean ok;
        public double focus = Double.NaN;
        /** Stars: HFR at the chosen focus; daylight: sharpness there. */
        public double value = Double.NaN;
        /** Stars: HFR at focus 0 (the lens's infinity), measured or from the fit, NaN if neither. */
        public double atZero = Double.NaN;
        public boolean zeroMeasured;
        /** Stars: HFR growth per focus unit (from the fit), NaN if not fitted. */
        public double slope = Double.NaN;
        /** Uncertainty of the focus found (focus units), NaN if not fitted. */
        public double sigma = Double.NaN;
        public int frames, usable;
        public boolean fitted, atEdge;
        /** The two-frame check, if one was made. */
        public Check check;
        /** How the focus was found, or why none was. */
        public String how = "";
    }

    private static final int COARSE = 0, FINE = 1, AROUND = 2, CHECK = 3, ZERO = 4, DONE = 5;

    public final Kind kind;
    public final Lens lens;
    /** What kind of sweep, and why it was chosen. */
    public final String mode;
    public final String why;
    /** The daylight calibration's prediction this sweep is centred on (NaN if none). */
    public double predicted = Double.NaN;
    /**
     * Stars: end with one frame at the lens's own infinity (0) if the sweep didn't take one and found its best
     * focus away from it, so the log can compare the two measured sizes (A5).
     */
    public boolean measureZero;
    private final List<Point> pts = new ArrayList<Point>();
    private final ArrayDeque<Double> queue = new ArrayDeque<Double>();
    private final double coarse, fine;
    private int stage;
    private int extensions;
    private double checkFocus = Double.NaN, checkSlope;
    private Check check;
    private String failure = "";

    private FocusSweep(Kind kind, Lens lens, String mode, String why, double coarse, double fine) {
        this.kind = kind;
        this.lens = lens;
        this.mode = mode;
        this.why = why;
        this.coarse = coarse;
        this.fine = fine;
    }

    /** Coarse steps from the infinity end to the lens's max, then fine steps around the best. */
    public static FocusSweep full(Lens lens, Kind kind, String why) {
        double coarse = kind == Kind.DAYLIGHT ? lens.fine : lens.coarse, fine = kind == Kind.DAYLIGHT ? lens.fine / 2 : lens.fine;
        FocusSweep s = new FocusSweep(kind, lens, "full", why, coarse, fine);
        s.stage = COARSE;
        for (int k = 0; k * coarse <= lens.max + 1e-9; k++) s.queue.add(k * coarse);
        return s;
    }

    /** Fine steps from centre - half steps to centre + half steps (within the lens's range). */
    public static FocusSweep around(Lens lens, Kind kind, double centre, int half, String mode, String why) {
        FocusSweep s = new FocusSweep(kind, lens, mode, why, lens.coarse, kind == Kind.DAYLIGHT ? lens.fine / 2 : lens.fine);
        s.stage = AROUND;
        s.queueAround(centre, half);
        return s;
    }

    /** Two frames, two fine steps each side of a known focus; a fine sweep follows if it has moved. */
    public static FocusSweep check(Lens lens, double focus, double slope, String why) {
        FocusSweep s = new FocusSweep(Kind.STARS, lens, "check", why, lens.coarse, lens.fine);
        s.stage = CHECK;
        s.checkFocus = focus;
        s.checkSlope = slope > 0 ? slope : lens.slope;
        double d = checkDelta(lens);
        s.queue.add(Math.max(0, focus - d));
        s.queue.add(focus + d);
        return s;
    }

    /** The step each side of a checked focus: far enough that the stars' sizes differ clearly, near enough to stay sharp. */
    public static double checkDelta(Lens lens) {
        return 2 * lens.fine;
    }

    /**
     * The sweep for tonight (A5 with F7): centred on the daylight calibration's prediction for the phone's
     * temperature when there is a line to predict with, else a check of the last focus found on the stars, else
     * centred on the nearest daylight value, else a full sweep.
     */
    public static FocusSweep night(Lens lens, FocusModel model, double tempC) {
        FocusModel.Line line = model != null ? model.line() : null;
        if (line != null && !Double.isNaN(tempC)) {
            double pred = Stats.clamp(line.predict(tempC), 0, lens.max + 2 * lens.fine);
            boolean inside = tempC >= line.tMin - 5 && tempC <= line.tMax + 5;
            FocusSweep s = around(lens, Kind.STARS, pred, inside ? 3 : 5, "predicted", String.format(Locale.US,
                    "centred on %.3f, what the daylight calibration (%d points, %.0f to %.0f C) predicts at %.0f C%s", pred, line.n, line.tMin,
                    line.tMax, tempC, inside ? "" : ", outside its temperatures, so wider"));
            s.predicted = pred;
            return s;
        }
        FocusModel.Cal last = model != null ? model.lastNight() : null;
        if (last != null) {
            return check(lens, last.focus, last.slope, String.format(Locale.US, "the focus found on the stars last time (%.3f) checked with two frames",
                    last.focus));
        }
        FocusModel.Cal day = model != null ? model.closestDay(tempC) : null;
        if (day != null) {
            int n = model.day.size();
            return around(lens, Kind.STARS, day.focus, 5, "daylight", String.format(Locale.US,
                    "centred on the daylight calibration's %.3f (%d point%s: a line needs %d spanning %.0f C)", day.focus, n, n == 1 ? "" : "s",
                    FocusModel.MIN_POINTS, FocusModel.MIN_SPAN_C));
        }
        return full(lens, Kind.STARS, "no focus known yet: a full sweep from the lens's infinity end");
    }

    private void queueAround(double centre, int half) {
        double hi = lens.max + 4 * lens.fine;
        for (int k = -half; k <= half; k++) {
            double f = centre + k * fine;
            if (f < -1e-9 || f > hi + 1e-9) continue;
            f = Math.max(0, f);
            if (!measuredNear(f) && !queued(f)) queue.add(f);
        }
    }

    private boolean measuredNear(double f) {
        for (Point p : pts) if (Math.abs(p.focus - f) < 0.45 * fine) return true;
        return false;
    }

    private boolean queued(double f) {
        for (double q : queue) if (Math.abs(q - f) < 0.45 * fine) return true;
        return false;
    }

    /** The next focus to measure, NaN when the sweep is over. */
    public double next() {
        if (stage == DONE || queue.isEmpty()) return Double.NaN;
        return queue.peek();
    }

    public boolean done() {
        return stage == DONE;
    }

    /** Frames measured so far. */
    public int frames() {
        return pts.size();
    }

    public List<Point> points() {
        return Collections.unmodifiableList(pts);
    }

    /** Frames still planned (more may follow if the best lands on an edge). */
    public int planned() {
        return stage == DONE ? 0 : queue.size() + (stage == COARSE ? 6 : 0);
    }

    /**
     * The measurement for the focus {@link #next()} gave. {@code focus} is where the lens really was (its
     * reported position), which may differ a little from the value asked for.
     *
     * @param ok false if the frame couldn't be measured (no stars, no detail)
     */
    public void add(double focus, double value, double noise, int stars, boolean ok) {
        if (stage == DONE) return;
        if (!queue.isEmpty()) queue.poll();
        Point p = new Point();
        p.focus = focus;
        p.value = value;
        p.noise = noise;
        p.stars = stars;
        p.ok = ok && !Double.isNaN(value) && value > 0;
        pts.add(p);
        // nothing to see in the first two frames: give up early (clouds, fog, a covered lens)
        if (pts.size() >= 2 && usable() == 0) {
            failure = kind == Kind.STARS ? "no stars to focus on in the first frames" : "no detail to focus on";
            stage = DONE;
            queue.clear();
            return;
        }
        if (stage == COARSE && risingPastBest()) queue.clear();
        if (queue.isEmpty()) advance();
    }

    private int usable() {
        int n = 0;
        for (Point p : pts) if (p.ok) n++;
        return n;
    }

    /** The coarse sweep has gone well past the best value: two frames in a row clearly worse, getting worse. */
    private boolean risingPastBest() {
        int best = -1;
        for (int i = 0; i < pts.size(); i++) {
            Point p = pts.get(i);
            if (p.ok && (best < 0 || p.cost(kind) < pts.get(best).cost(kind))) best = i;
        }
        if (best < 0 || pts.size() - best < 3) return false;
        Point b = pts.get(best), p1 = pts.get(pts.size() - 2), p2 = pts.get(pts.size() - 1);
        if (!p1.ok || !p2.ok) return false;
        if (kind == Kind.STARS) return p1.value > 1.4 * b.value && p2.value > p1.value;
        return p1.value < 0.6 * b.value && p2.value < p1.value;
    }

    private Point best() {
        Point b = null;
        for (Point p : pts) if (p.ok && (b == null || p.cost(kind) < b.cost(kind))) b = p;
        return b;
    }

    private void advance() {
        Point b = best();
        switch (stage) {
            case COARSE:
                if (b == null) {
                    finish(kind == Kind.STARS ? "no stars to focus on" : "no detail to focus on");
                    return;
                }
                stage = FINE;
                for (double f = b.focus - coarse + fine; f < b.focus + coarse - 1e-9; f += fine) {
                    double v = Math.max(0, f);
                    if (v > lens.max + 1e-9) continue;
                    if (!measuredNear(v) && !queued(v)) queue.add(v);
                }
                if (queue.isEmpty()) finish("");
                return;
            case CHECK:
                checked();
                return;
            case FINE:
            case AROUND:
                if (b == null) {
                    finish(kind == Kind.STARS ? "no stars to focus on" : "no detail to focus on");
                    return;
                }
                if (extend(b)) return;
                finish("");
                return;
            default:
                finish("");
        }
    }

    /** The best value on the edge of what was measured: two more fine steps that way (twice at most). */
    private boolean extend(Point b) {
        if (extensions >= 2) return false;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (Point p : pts) {
            if (!p.ok) continue;
            lo = Math.min(lo, p.focus);
            hi = Math.max(hi, p.focus);
        }
        double top = lens.max + 4 * lens.fine;
        if (Math.abs(b.focus - hi) < 0.45 * fine && hi + fine <= top + 1e-9) {
            extensions++;
            for (int k = 1; k <= 2; k++) if (hi + k * fine <= top + 1e-9) queue.add(hi + k * fine);
            return !queue.isEmpty();
        }
        if (Math.abs(b.focus - lo) < 0.45 * fine && lo - fine >= -1e-9) {
            extensions++;
            for (int k = 1; k <= 2; k++) if (lo - k * fine >= -1e-9) queue.add(Math.max(0, lo - k * fine));
            return !queue.isEmpty();
        }
        return false;
    }

    private void checked() {
        Point lo = null, hi = null;
        for (Point p : pts) {
            if (p.focus < checkFocus) lo = p;
            else hi = p;
        }
        if (lo == null || hi == null || !lo.ok || !hi.ok) {
            // can't tell from these two: sweep around the known value
            stage = AROUND;
            queueAround(checkFocus, 3);
            if (queue.isEmpty()) finish("");
            return;
        }
        check = twoPoint(checkFocus, lo.focus, lo.value, lo.noise, hi.focus, hi.value, hi.noise, checkSlope, fine);
        if (check.holds) {
            finish("");
            return;
        }
        stage = AROUND;
        queueAround(Stats.clamp(check.best, 0, lens.max + 4 * lens.fine), 3);
        if (queue.isEmpty()) finish("");
    }

    private void finish(String why) {
        failure = why;
        queue.clear();
        if (measureZero && kind == Kind.STARS && stage != ZERO && why.isEmpty()) {
            Point b = best();
            boolean zero = false;
            for (Point p : pts) if (Math.abs(p.focus) < 0.25 * fine) zero = true;
            if (b != null && !zero && b.focus > 1.5 * fine) {
                stage = ZERO;
                queue.add(0.0);
                return;
            }
        }
        stage = DONE;
    }

    /**
     * The best focus from one frame each side of {@code focus}: with HFR^2 = a^2 + s^2 (f - best)^2, the
     * difference of the two squares is linear in how far {@code focus} is from the best.
     *
     * @param slope stars' HFR growth per focus unit
     */
    public static Check twoPoint(double focus, double fLow, double hLow, double nLow, double fHigh, double hHigh, double nHigh,
                                 double slope, double fine) {
        Check c = new Check();
        c.focus = focus;
        c.delta = 0.5 * (fHigh - fLow);
        double s2 = slope * slope;
        double mid = 0.5 * (fLow + fHigh);
        // hHigh^2 - hLow^2 = s^2 ((fHigh - best)^2 - (fLow - best)^2) = s^2 (fHigh - fLow) (fHigh + fLow - 2 best)
        double d = (hHigh * hHigh - hLow * hLow) / (s2 * (fHigh - fLow));
        double best = mid - 0.5 * d;
        double nl = Math.max(nLow, 0.01 * hLow), nh = Math.max(nHigh, 0.01 * hHigh);
        c.sigma = 0.5 * Math.sqrt(Math.pow(2 * hHigh * nh, 2) + Math.pow(2 * hLow * nl, 2)) / (s2 * (fHigh - fLow));
        // beyond the frames the estimate leans on the slope being right: no more than three steps out
        double lim = 3 * c.delta;
        best = Stats.clamp(best, focus - lim, focus + lim);
        c.best = Math.max(0, best);
        c.shift = focus - c.best;
        c.holds = Math.abs(c.shift) < fine || Math.abs(c.shift) < 2 * c.sigma;
        return c;
    }

    /** The best focus from the frames measured. */
    public Result result() {
        Result r = new Result();
        r.frames = pts.size();
        r.usable = usable();
        r.check = check;
        if (check != null && check.holds) {
            r.ok = true;
            r.focus = check.focus;
            r.how = "two frames each side of it agree it still holds";
            // the stars' size at the focus: between the two frames' (they're equally far out)
            double h2 = 0;
            int n = 0;
            for (Point p : pts) {
                if (!p.ok || Math.abs(p.focus - checkFocus) > 1.5 * checkDelta(lens)) continue;
                h2 += p.value * p.value - checkSlope * checkSlope * (p.focus - check.best) * (p.focus - check.best);
                n++;
            }
            if (n > 0 && h2 > 0) r.value = Math.sqrt(h2 / n);
            r.slope = checkSlope;
            zero(r, null);
            return r;
        }
        Point b = best();
        if (b == null || usable() < 3) {
            r.how = !failure.isEmpty() ? failure : String.format(Locale.US, "only %d of %d frames could be measured", usable(), pts.size());
            if (b != null && usable() > 0 && failure.isEmpty()) r.how = String.format(Locale.US, "too few frames to measure (%d of %d)", usable(), pts.size());
            return r;
        }
        boolean haveFine = false;
        for (Point p : pts) if (p.ok && p != b && Math.abs(p.focus - b.focus) < 1.5 * fine) haveFine = true;
        double w = kind == Kind.STARS ? (haveFine ? 3.2 * fine : 1.6 * coarse) : (haveFine ? 2.2 * fine : 1.1 * coarse);
        List<Point> win = new ArrayList<Point>();
        for (Point p : pts) if (p.ok && Math.abs(p.focus - b.focus) <= w + 1e-9) win.add(p);
        Collections.sort(win, new Comparator<Point>() {
            @Override
            public int compare(Point a, Point c) {
                return Double.compare(a.focus, c.focus);
            }
        });
        double[] fit = fit(win);
        r.ok = true;
        if (fit != null) {
            double a = fit[0], bb = fit[1], c = fit[2];
            double f0 = -bb / (2 * c);
            double lo = win.get(0).focus, hi = win.get(win.size() - 1).focus;
            if (c > 0 && f0 >= lo - fine && f0 <= hi + fine) {
                r.fitted = true;
                r.focus = Math.max(0, f0);
                double y0 = a + bb * r.focus + c * r.focus * r.focus;
                r.value = kind == Kind.STARS ? Math.sqrt(Math.max(1e-4, y0)) : Math.exp(-y0);
                if (kind == Kind.STARS) r.slope = Math.sqrt(c);
                // delta method on f0 = -b / 2c
                double jb = -1 / (2 * c), jc = bb / (2 * c * c);
                double var = jb * jb * fit[4] + jc * jc * fit[5] + 2 * jb * jc * fit[6];
                r.sigma = Math.sqrt(Math.max(0, var));
                r.how = String.format(Locale.US, "fitted through %d of %d frames", win.size(), pts.size());
                r.atEdge = f0 < lo || f0 > hi;
            }
        }
        if (!r.fitted) {
            r.focus = b.focus;
            r.value = b.value;
            r.how = String.format(Locale.US, "the best of %d frames (no curve to fit)", pts.size());
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (Point p : pts) {
                if (!p.ok) continue;
                lo = Math.min(lo, p.focus);
                hi = Math.max(hi, p.focus);
            }
            r.atEdge = Math.abs(b.focus - lo) < 0.45 * fine || Math.abs(b.focus - hi) < 0.45 * fine;
        }
        if (r.atEdge && r.focus < 0.45 * fine) r.how += ", at the lens's infinity end";
        else if (r.atEdge) r.how += ", at the edge of the sweep";
        zero(r, fit);
        return r;
    }

    /** Stars: their size at focus 0, measured if a frame was taken there, else from the fit. */
    private void zero(Result r, double[] fit) {
        if (kind != Kind.STARS) return;
        for (Point p : pts) {
            if (p.ok && Math.abs(p.focus) < 0.25 * fine) {
                r.atZero = p.value;
                r.zeroMeasured = true;
                return;
            }
        }
        if (fit != null && r.fitted && fit[0] > 0) {
            r.atZero = Math.sqrt(fit[0]);
        } else if (!Double.isNaN(r.value) && !Double.isNaN(r.slope)) {
            r.atZero = Math.sqrt(r.value * r.value + r.slope * r.slope * r.focus * r.focus);
        }
    }

    /**
     * Weighted least squares parabola y = a + b f + c f^2 through the points (stars: y = HFR^2; daylight:
     * y = -ln sharpness). Returns {a, b, c, chi2/dof, var b, var c, cov bc}, or null with fewer than three
     * distinct focus values.
     */
    double[] fit(List<Point> win) {
        int n = win.size();
        if (n < 3) return null;
        int distinct = 1;
        for (int i = 1; i < n; i++) if (Math.abs(win.get(i).focus - win.get(i - 1).focus) > 0.3 * fine) distinct++;
        if (distinct < 3) return null;
        // centre the focus values for a well-conditioned solve
        double fm = 0;
        for (Point p : win) fm += p.focus;
        fm /= n;
        double[][] A = new double[3][3];
        double[] B = new double[3];
        double[] y = new double[n], wt = new double[n];
        for (int i = 0; i < n; i++) {
            Point p = win.get(i);
            double sy;
            if (kind == Kind.STARS) {
                y[i] = p.value * p.value;
                sy = 2 * p.value * Math.max(p.noise, 0.01 * p.value);
            } else {
                y[i] = -Math.log(p.value);
                sy = Math.max(p.noise / p.value, 0.01);
            }
            wt[i] = 1 / (sy * sy);
            double x = p.focus - fm;
            double[] row = {1, x, x * x};
            for (int a = 0; a < 3; a++) {
                B[a] += wt[i] * row[a] * y[i];
                for (int c = 0; c < 3; c++) A[a][c] += wt[i] * row[a] * row[c];
            }
        }
        double[][] inv = invert3(A);
        if (inv == null) return null;
        double[] q = new double[3];
        for (int a = 0; a < 3; a++) for (int c = 0; c < 3; c++) q[a] += inv[a][c] * B[c];
        double chi2 = 0;
        for (int i = 0; i < n; i++) {
            double x = win.get(i).focus - fm;
            double e = y[i] - (q[0] + q[1] * x + q[2] * x * x);
            chi2 += wt[i] * e * e;
        }
        double dof = Math.max(1, n - 3);
        // scale the covariance by the scatter when it exceeds the noise (real stars aren't the model)
        double s = Math.max(1, chi2 / dof);
        // back to uncentred: y = q0 + q1 (f - fm) + q2 (f - fm)^2
        double c = q[2], b = q[1] - 2 * q[2] * fm, a = q[0] - q[1] * fm + q[2] * fm * fm;
        double vc = inv[2][2] * s, vq1 = inv[1][1] * s, cq12 = inv[1][2] * s;
        // b = q1 - 2 fm q2
        double vb = vq1 + 4 * fm * fm * vc - 4 * fm * cq12;
        double cbc = cq12 - 2 * fm * vc;
        return new double[]{a, b, c, chi2 / dof, vb, vc, cbc};
    }

    static double[][] invert3(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2], d = m[1][0], e = m[1][1], f = m[1][2], g = m[2][0], h = m[2][1], k = m[2][2];
        double det = a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-300) return null;
        double id = 1 / det;
        return new double[][]{
                {(e * k - f * h) * id, (c * h - b * k) * id, (b * f - c * e) * id},
                {(f * g - d * k) * id, (a * k - c * g) * id, (c * d - a * f) * id},
                {(d * h - e * g) * id, (b * g - a * h) * id, (a * e - b * d) * id}};
    }

    /** One line per frame, for the session's log. */
    public String pointsLine() {
        StringBuilder sb = new StringBuilder();
        for (Point p : pts) {
            if (sb.length() > 0) sb.append("; ");
            if (!p.ok) sb.append(String.format(Locale.US, "%.3f: -", p.focus));
            else if (kind == Kind.STARS) sb.append(String.format(Locale.US, "%.3f: %.2f px (%d)", p.focus, p.value, p.stars));
            else sb.append(String.format(Locale.US, "%.3f: %.4g", p.focus, p.value));
        }
        return sb.toString();
    }
}
