package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;

/**
 * Which satellites cross the picture, when and where.
 * <p>
 * For a finished session: a coarse search in 10-second steps over the session (with a margin, since a low
 * satellite moves over a degree a second near the zenith) finds the satellites that are sunlit, bright enough
 * and near the picture at some step; each of those is then followed through every frame's exposure in
 * half-second steps, a few seconds beyond either end for the elements' timing error, and mapped into the
 * stack by the reference frame's plate solution. Between samples the path is a straight line to well under a
 * pixel.
 * <p>
 * Before a session: the passes through the camera's field of view in the next hour or so, for a warning and
 * for the quietest window to shoot in.
 */
public final class SatPredict {
    private SatPredict() {}

    /** Coarse step (seconds), and the margin around the picture it needs (degrees). */
    static final double COARSE_S = 10, COARSE_MARGIN_DEG = 8;
    /** Step along an exposure (seconds). */
    public static final double FINE_S = 0.5;
    /**
     * How far off along its path a satellite may be, in seconds, plus seconds per day of the elements' age; and
     * across it, in degrees, plus degrees per day.
     */
    public static final double ALONG_S = 2, ALONG_S_PER_DAY = 1, ACROSS_DEG = 0.15, ACROSS_DEG_PER_DAY = 0.05;
    /** Along-track margin used at most (seconds), however old the elements. */
    static final double MAX_MARGIN_S = 10;
    /** Elements older than this (days) aren't used. */
    public static final double MAX_AGE_DAYS = 14;
    /** Most the observer's uncertain place widens the across-track search (degrees). */
    static final double MAX_PLACE_DEG = 0.6;

    /** One stored unit's exposure. */
    public static final class Exposure {
        public final int unit;
        /** Start (UTC Julian date) and length (seconds) of the exposure. */
        public final double jd0, seconds;

        public Exposure(int unit, double jd0, double seconds) {
            this.unit = unit;
            this.jd0 = jd0;
            this.seconds = seconds;
        }
    }

    /** A satellite's predicted path across the picture during one exposure, in the stack's pixels. */
    public static final class Track {
        public final SatCatalog.Sat sat;
        public final int unit;
        /** The exposure (UTC Julian dates). */
        public final double jd0, jd1;
        /** Days since the elements' epoch; seconds sampled beyond either end of the exposure. */
        public final double ageDays, margin;
        /** Samples every {@link #FINE_S} from jd0 - margin: time, position (NaN where it is unlit, below the
         * horizon or behind the camera) and magnitude. */
        public final double[] jd;
        public final float[] x, y, mag;
        /** Samples within the exposure: [in0, in1]. */
        public final int in0, in1;
        /** Nearest the satellite came while seen (km), and how far off the observer's place may be (km). */
        public double minRangeKm = Double.MAX_VALUE, siteErrKm;

        Track(SatCatalog.Sat sat, int unit, double jd0, double jd1, double margin, int n) {
            this.sat = sat;
            this.unit = unit;
            this.jd0 = jd0;
            this.jd1 = jd1;
            this.ageDays = sat.ageDays(jd0);
            this.margin = margin;
            jd = new double[n];
            x = new float[n];
            y = new float[n];
            mag = new float[n];
            in0 = (int) Math.ceil(margin / FINE_S - 1e-9);
            in1 = Math.min(n - 1, (int) Math.floor((margin + (jd1 - jd0) * 86400) / FINE_S + 1e-9));
        }

        /** Brightest magnitude while lit during the exposure (99 if never). */
        public float brightest() {
            float m = 99;
            for (int i = in0; i <= in1; i++) if (!Float.isNaN(x[i])) m = Math.min(m, mag[i]);
            return m;
        }

        /** Mean speed across the picture during the exposure (pixels per second), NaN if unknown. */
        public float speed() {
            double d = 0;
            int n = 0;
            for (int i = in0; i < in1; i++) {
                if (Float.isNaN(x[i]) || Float.isNaN(x[i + 1])) continue;
                d += Math.hypot(x[i + 1] - x[i], y[i + 1] - y[i]);
                n++;
            }
            return n > 0 ? (float) (d / (n * FINE_S)) : Float.NaN;
        }

        /** Along-track timing tolerance (seconds) and across-track tolerance (degrees) for these elements. */
        public double alongTol() {
            return ALONG_S + ALONG_S_PER_DAY * Math.abs(ageDays);
        }

        public double acrossTolDeg() {
            // an observer a kilometre from where the phone thinks it is sees a satellite 500 km away 0.1 degrees off
            double place = siteErrKm > 0 && minRangeKm < Double.MAX_VALUE ? Math.toDegrees(siteErrKm / minRangeKm) : 0;
            return ACROSS_DEG + ACROSS_DEG_PER_DAY * Math.abs(ageDays) + Math.min(MAX_PLACE_DEG, place);
        }
    }

    /** A pass through a circle of the sky (the camera's field of view). */
    public static final class Pass {
        public final SatCatalog.Sat sat;
        /** When it enters and leaves the circle lit, and when it is brightest (UTC Julian dates). */
        public double jdIn, jdOut, jdBest;
        public float magBest = 99;
        public double altBest;

        Pass(SatCatalog.Sat sat) {
            this.sat = sat;
        }
    }

    /**
     * The satellites lit, brighter than {@code magLimit} and within {@code radiusDeg} of the J2000 direction
     * {@code axis} at some coarse step between {@code jd0} and {@code jd1} (with the margin a step needs).
     */
    public static List<SatCatalog.Sat> candidates(List<SatCatalog.Sat> sats, SatSky.Site site, double jd0, double jd1,
                                                  double[] axis, double radiusDeg, double magLimit) {
        List<SatCatalog.Sat> out = new ArrayList<SatCatalog.Sat>();
        double cosR = Math.cos(Math.toRadians(Math.min(180, radiusDeg + COARSE_MARGIN_DEG)));
        int steps = (int) Math.ceil((jd1 - jd0) * 86400 / COARSE_S) + 2;
        SatSky.Moment[] ms = new SatSky.Moment[steps + 1];
        for (int k = 0; k <= steps; k++) ms[k] = site.moment(jd0 + (k - 1) * COARSE_S / 86400);
        SatSky.Look look = new SatSky.Look();
        for (SatCatalog.Sat s : sats) {
            if (Math.abs(s.ageDays(jd0)) > MAX_AGE_DAYS) continue;
            for (SatSky.Moment m : ms) {
                if (!SatSky.look(s.orbit, s.stdMag, m, look)) break;
                if (!look.sunlit || look.altDeg < 0 || look.mag > magLimit) continue;
                if (dot(look.j2000, axis) >= cosR) {
                    out.add(s);
                    break;
                }
            }
        }
        return out;
    }

    /**
     * Every candidate's path through every exposure, in the pixels of the plate solution {@code sol} (the
     * stack's), kept where some sample within the exposure or its margin is lit, brighter than
     * {@code magLimit} and within {@code pad} pixels of the W x H picture.
     */
    public static List<Track> tracks(List<SatCatalog.Sat> cands, SatSky.Site site, List<Exposure> exposures,
                                     SkySolver.Solution sol, int W, int H, double magLimit, float pad) {
        List<Track> out = new ArrayList<Track>();
        if (cands.isEmpty()) return out;
        SatSky.Look look = new SatSky.Look();
        for (Exposure e : exposures) {
            double maxAge = 0;
            for (SatCatalog.Sat s : cands) maxAge = Math.max(maxAge, Math.abs(s.ageDays(e.jd0)));
            double margin = Math.min(MAX_MARGIN_S, ALONG_S + ALONG_S_PER_DAY * maxAge);
            margin = Math.ceil(margin / FINE_S) * FINE_S;
            int n = (int) Math.floor((2 * margin + e.seconds) / FINE_S + 1e-9) + 1;
            SatSky.Moment[] ms = new SatSky.Moment[n];
            for (int i = 0; i < n; i++) ms[i] = site.moment(e.jd0 + (i * FINE_S - margin) / 86400);
            for (SatCatalog.Sat s : cands) {
                Track t = new Track(s, e.unit, e.jd0, e.jd0 + e.seconds / 86400, margin, n);
                t.siteErrKm = site.errKm;
                boolean any = false, ok = true;
                for (int i = 0; i < n; i++) {
                    t.jd[i] = ms[i].jdUtc;
                    t.x[i] = t.y[i] = Float.NaN;
                    t.mag[i] = 99;
                    if (!SatSky.look(s.orbit, s.stdMag, ms[i], look)) {
                        ok = false;
                        break;
                    }
                    if (!look.sunlit || look.altDeg < 0) continue;
                    double[] p = sol.pixel(look.j2000);
                    if (p == null) continue;
                    t.x[i] = (float) p[0];
                    t.y[i] = (float) p[1];
                    t.mag[i] = (float) look.mag;
                    t.minRangeKm = Math.min(t.minRangeKm, look.rangeKm);
                    if (look.mag <= magLimit && p[0] > -pad && p[1] > -pad && p[0] < W + pad && p[1] < H + pad) any = true;
                }
                if (ok && any) out.add(t);
            }
        }
        return out;
    }

    /**
     * The passes, between {@code jd0} and {@code jd1}, of satellites lit and brighter than {@code magLimit}
     * through the circle of {@code radiusDeg} around the direction {@code axisEnu} (east, north, up; the zenith
     * for a phone lying flat), found in coarse steps and timed to the second.
     */
    public static List<Pass> passes(List<SatCatalog.Sat> sats, SatSky.Site site, double jd0, double jd1, double[] axisEnu,
                                    double radiusDeg, double magLimit) {
        List<Pass> out = new ArrayList<Pass>();
        double cosR = Math.cos(Math.toRadians(radiusDeg));
        double cosC = Math.cos(Math.toRadians(Math.min(180, radiusDeg + COARSE_MARGIN_DEG)));
        int steps = (int) Math.ceil((jd1 - jd0) * 86400 / COARSE_S);
        SatSky.Moment[] ms = new SatSky.Moment[steps + 1];
        for (int k = 0; k <= steps; k++) ms[k] = site.moment(jd0 + k * COARSE_S / 86400);
        SatSky.Look look = new SatSky.Look();
        for (SatCatalog.Sat s : sats) {
            if (Math.abs(s.ageDays(jd0)) > MAX_AGE_DAYS) continue;
            double skipUntil = -1;
            boolean prevNear = false;
            for (int k = 0; k <= steps; k++) {
                if (ms[k].jdUtc <= skipUntil) {
                    prevNear = true;
                    continue;
                }
                if (!SatSky.look(s.orbit, s.stdMag, ms[k], look)) break;
                boolean near = look.sunlit && look.altDeg > 0 && look.mag <= magLimit + 1 && dot(look.enu, axisEnu) >= cosC;
                if (near && !prevNear) {
                    // a new stretch near the circle: time it second by second from the step before
                    Pass p = refine(s, site, ms[k].jdUtc - COARSE_S / 86400, cosR, cosC, axisEnu, magLimit, look);
                    if (p != null) {
                        out.add(p);
                        skipUntil = p.jdOut;
                    }
                }
                prevNear = near;
            }
        }
        return out;
    }

    /**
     * A pass timed to the second, searching from jdStart while the satellite stays near the circle, or null if
     * it never enters it lit and bright enough. A few seconds outside count as still in, for a satellite whose
     * brightness hovers at the limit.
     */
    private static Pass refine(SatCatalog.Sat s, SatSky.Site site, double jdStart, double cosR, double cosC, double[] axisEnu,
                               double magLimit, SatSky.Look look) {
        Pass p = null;
        int away = 0;
        // at most fifteen minutes: no low satellite stays near a camera's field longer
        for (int i = 0; i < 900; i++) {
            SatSky.Moment m = site.moment(jdStart + i / 86400.0);
            if (!SatSky.look(s.orbit, s.stdMag, m, look)) break;
            boolean lit = look.sunlit && look.altDeg > 0;
            double d = dot(look.enu, axisEnu);
            boolean in = lit && look.mag <= magLimit && d >= cosR;
            if (in) {
                if (p == null) {
                    p = new Pass(s);
                    p.jdIn = m.jdUtc;
                }
                p.jdOut = m.jdUtc;
                if (look.mag < p.magBest) {
                    p.magBest = (float) look.mag;
                    p.jdBest = m.jdUtc;
                    p.altBest = look.altDeg;
                }
                away = 0;
            } else if (p != null) {
                if (++away > 5) break;
            } else if (!(lit && d >= cosC) && i > COARSE_S) {
                if (++away > 2 * COARSE_S) break;
            }
        }
        return p;
    }

    /**
     * The start (UTC Julian date) of the {@code seconds}-long window between {@code jdFrom} and {@code jdTo}
     * (latest start) with the least satellite light: each pass inside it counts as its brightness relative to
     * a magnitude-3 satellite. The earliest of equally quiet windows; [start, cost].
     */
    public static double[] quietestWindow(List<Pass> passes, double jdFrom, double jdTo, double seconds, double stepSec) {
        double best = Double.MAX_VALUE, bestJd = jdFrom;
        for (double jd = jdFrom; jd <= jdTo + 1e-9; jd += stepSec / 86400) {
            double end = jd + seconds / 86400, cost = 0;
            for (Pass p : passes) {
                if (p.jdOut < jd || p.jdIn > end) continue;
                cost += Math.pow(10, -0.4 * (p.magBest - 3));
            }
            if (cost < best - 1e-6) {
                best = cost;
                bestJd = jd;
            }
        }
        return new double[]{bestJd, best};
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /** The plate solution's centre direction (J2000) and the angle (degrees) to the farthest corner of a W x H picture. */
    public static double[] fieldOf(SkySolver.Solution sol, int W, int H) {
        double[] c = sol.direction(W / 2.0, H / 2.0);
        double r = 0;
        double[][] corners = {{0, 0}, {W, 0}, {0, H}, {W, H}};
        for (double[] k : corners) r = Math.max(r, Astro.sep(c, sol.direction(k[0], k[1])));
        return new double[]{c[0], c[1], c[2], Math.toDegrees(r)};
    }
}
