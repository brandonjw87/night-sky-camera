package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatMatch;
import com.brandon.nightsky.processing.SatOverlay;
import com.brandon.nightsky.processing.SatPredict;
import com.brandon.nightsky.processing.SatSky;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.Sgp4;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * Satellite naming and masks (H1) on a synthetic session whose satellites come from real orbital elements,
 * through capture, the stack and the streak naming, scored against the truth.
 * <p>
 * The elements: three objects of the SGP4 verification set (Vallado et al., AIAA 2006-6753) as published, and,
 * for the crowded cases, elements of the ISS's and a Starlink shell's shape. Each pass keeps its object's
 * orbit (size, shape, inclination, drag) and moves only its node and its place along the orbit, so that it
 * crosses the camera's field at a chosen moment of the session, with an epoch a few hours to two days before
 * (as downloaded elements would be). The satellites drawn aren't quite where those elements say: each is
 * moved along and across its orbit by errors typical of elements that age, and the phone's clock is 0.3 s off.
 * A Starlink train, the ISS with a capsule docked (both in the data), a satellite crossing another's path and
 * one running alongside another, two satellites missing from the data, a plane and a meteor make it harder; and
 * it is run twice, with a precise place and with an approximate one 1.4 km off.
 * <p>
 * Scored: of the streaks the satellites leave (bright enough for the stack to reject, long enough in the
 * picture), the share named after the right satellite, at least 80%, and none named after the wrong one.
 * Then six frames with bright passes and a plane are stacked with and without the corridors: with them, no
 * trail may stand out along any truth path (the stack's median along the path within 3 sigma of parallel lines
 * nearby); without them, the same measure shows the trails it would otherwise leave.
 * <p>
 * Usage: SatSessionTest outDir [scale=2] [frames=37] [seed=1]
 */
public final class SatSessionTest {
    /** One orbit's shape: everything but the node, the place along the orbit and the epoch. */
    static final class Shape {
        final String name, source;
        final double n, e, i, argp, bstar, ndot, nddot;

        Shape(String name, String source, double n, double e, double i, double argp, double bstar, double ndot, double nddot) {
            this.name = name;
            this.source = source;
            this.n = n;
            this.e = e;
            this.i = i;
            this.argp = argp;
            this.bstar = bstar;
            this.ndot = ndot;
            this.nddot = nddot;
        }

        static Shape fromTle(String name, String source, String l1, String l2) {
            Sgp4 s = Sgp4.fromTle(name, l1, l2);
            return new Shape(name, source, s.noKozai * Sgp4.XPDOTP, s.ecco, Math.toDegrees(s.inclo), Math.toDegrees(s.argpo),
                    s.bstar, s.ndot * Sgp4.XPDOTP * 1440, s.nddot * Sgp4.XPDOTP * 1440 * 1440);
        }
    }

    // from SGP4-VER.TLE (python-sgp4, MIT licence): objects in low, near-circular orbits
    static final Shape DELTA = Shape.fromTle("DELTA 1 DEB", "visual",
            "1 06251U 62025E   06176.82412014  .00008885  00000-0  12808-3 0  3985",
            "2 06251  58.0579  54.0425 0030035 139.1568 221.1854 15.56387291  6774");
    static final Shape CBERS = Shape.fromTle("CBERS 2", "visual",
            "1 28057U 03049A   06177.78615833  .00000060  00000-0  35940-4 0  1836",
            "2 28057  98.4283 247.6961 0000884  88.1964 271.9322 14.35478080140550");
    static final Shape SL12 = Shape.fromTle("SL-12 DEB", "visual",
            "1 29238U 06022G   06177.28732010  .00766286  10823-4  13334-2 0   101",
            "2 29238  51.5595 213.7903 0202579  95.2503 267.9010 15.73823839  1061");
    // the shapes of the ISS (about 420 km) and of a Starlink shell (550 km, 53 degrees)
    static final Shape ISS = new Shape("ISS (ZARYA)", "stations", 15.50, 0.0004, 51.64, 30, 2.5e-4, 1.5e-4, 0);
    static final Shape STARLINK = new Shape("STARLINK", "starlink", 15.06, 0.00012, 53.05, 90, 1.0e-4, 1.0e-5, 0);

    /** One pass: the elements the phone has, the satellite as it really flew, and how bright it really is. */
    static final class Pass {
        String name;
        int norad;
        Shape shape;
        double epochJd, raan, m;
        /** In the phone's data (else only drawn); drawn (else only in the data: a docked capsule). */
        boolean inData = true, drawn = true;
        double trueStdMag, alongErrSec, crossErrKm;
        Sgp4 data, truth;
        SkySim.OrbitSat drawnSat;
        double jdTarget;
        double[] targetEnu;
    }

    static final double LAT = 40, LON = SimRunner.DEFAULT_LON;
    static final double CLOCK_ERR_S = 0.3;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "sat-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 37;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 1;
        out.mkdirs();
        BrightStars catalog = SimRunner.loadCatalog();
        long startMs = darkStart();
        System.out.printf(Locale.US, "session starts %s UTC, Sun at %.1f deg%n", iso(Astro.jdUtc(startMs)),
                SatSky.moment(Astro.jdUtc(startMs), Math.toRadians(LAT), Math.toRadians(LON), 0).sunAltDeg);
        String only = args.length > 4 ? args[4] : "";
        boolean ok = true;
        if (!only.equals("six")) ok &= named(new File(out, "named"), scale, frames, seed, startMs, catalog);
        if (!only.equals("named")) ok &= masked(new File(out, "six"), scale, seed + 100, startMs, catalog);
        System.out.println(ok ? "ALL PASS" : "FAILED");
        if (!ok) System.exit(1);
    }

    /** A start soon after the end of twilight (the Sun 17 degrees down): low satellites overhead are still lit. */
    static long darkStart() {
        long t = SimRunner.DEFAULT_TIME_MS - 6 * 3600_000L;
        for (int k = 0; k < 24 * 60; k++, t += 60_000L) {
            double alt = SatSky.moment(Astro.jdUtc(t), Math.toRadians(LAT), Math.toRadians(LON), 0).sunAltDeg;
            if (alt < -17) return t;
        }
        throw new IllegalStateException("no dark start");
    }

    // ------------------------------------------------------------------------------------------------
    // Part 1: naming

    static boolean named(File dir, int scale, int frames, long seed, long startMs, BrightStars catalog) throws Exception {
        Random rnd = new Random(seed);
        SkySim sim = newSim(scale, startMs, catalog);
        double dur = sim.frameStart(frames - 1) + sim.frameExp;
        double jd0 = Astro.jdUtc(startMs);
        SatSky.Site site = new SatSky.Site(Math.toRadians(LAT), Math.toRadians(LON), 0);
        double[] axis = axisEnu(sim);
        List<Pass> passes = new ArrayList<Pass>();
        Shape[] real = {DELTA, CBERS, SL12};
        int norad = 70001;
        // single passes of the real shapes and the ISS, spread over the session, bright to barely seen
        int singles = 14;
        for (int k = 0; k < singles; k++) {
            Shape sh = k == 5 ? ISS : real[k % real.length];
            double t = dur * (k + 0.3 + 0.4 * rnd.nextDouble()) / singles;
            Pass p = plan(sh, sh == ISS ? "ISS (ZARYA)" : sh.name + " " + (char) ('A' + k), sh == ISS ? 25544 : norad++,
                    jd0 + t / 86400, axis, 24, rnd, site);
            if (p == null) continue;
            p.trueStdMag = sh == ISS ? -1.3 : 3.0 + 2.5 * rnd.nextDouble();
            passes.add(p);
            if (sh == ISS) {
                // a capsule docked: the same elements, in the data, not drawn (its light is the station's)
                Pass c = copyPass(p, "SOYUZ MS-28", 66001);
                c.drawn = false;
                passes.add(c);
            }
        }
        // a Starlink train: four in a row, 12 s apart
        Pass lead = plan(STARLINK, "STARLINK-1001", 61001, jd0 + dur * 0.45 / 86400, axis, 15, rnd, site);
        if (lead != null) {
            lead.trueStdMag = 4.2;
            passes.add(lead);
            for (int k = 1; k < 4; k++) {
                Pass f = copyPass(lead, "STARLINK-100" + (k + 1), 61001 + k);
                f.m = lead.m - 12.0 * k * STARLINK.n * 360 / 86400;
                f.trueStdMag = 4.0 + 0.3 * k;
                passes.add(f);
            }
        }
        // crowding: one crossing another's path half a degree away a moment later, and one running alongside
        // another half a degree over
        Pass a = plan(CBERS, "CBERS 2 X", norad++, jd0 + dur * 0.62 / 86400, axis, 15, rnd, site);
        if (a != null) {
            a.trueStdMag = 3.5;
            passes.add(a);
            Pass b = planAt(DELTA, "DELTA 1 DEB X", norad++, a.jdTarget + 0.5 / 86400, offset(a.targetEnu, Math.toRadians(0.5),
                    2 * Math.PI * rnd.nextDouble()), rnd, site);
            if (b != null) {
                b.trueStdMag = 3.8;
                passes.add(b);
            }
            Pass c = copyPass(a, "CBERS 2 Y", norad++);
            c.raan = norm360(a.raan + 0.5);
            c.trueStdMag = 4.0;
            passes.add(c);
        }
        // two satellites the data doesn't have
        for (int k = 0; k < 2; k++) {
            Pass x = plan(real[k], "UNLISTED " + k, 75001 + k, jd0 + dur * (0.25 + 0.5 * k) / 86400, axis, 22, rnd, site);
            if (x == null) continue;
            x.inData = false;
            x.trueStdMag = 3.2;
            passes.add(x);
        }
        finish(passes, rnd, frames, sim);
        sim.planeFrame = frames / 3;
        sim.meteorFrame = (frames * 2) / 3;
        SatCatalog cat = catalogue(passes, jd0);
        System.out.printf(Locale.US, "%d passes, %d satellites in the data (%d rejected)%n", passes.size(), cat.size(), cat.rejected);
        Result precise = run(new File(dir, "precise"), sim, frames, seed, startMs, catalog, cat, true, 0, 30);
        boolean ok = score("precise place", precise, passes, frames);
        ok &= drawn(precise, passes, frames, catalog);
        // approximate location: the phone thinks it is 1.4 km away, and knows it may be 2 km off
        ok &= score("approximate place", run(new File(dir, "approximate"), sim, frames, seed, startMs, catalog, cat, true, 1.4, 2000),
                passes, frames);
        return ok;
    }

    static boolean score(String what, Result r, List<Pass> passes, int frames) {
        System.out.println("-- " + what);
        if (r.out.solution == null) {
            System.out.println("no plate solution: can't predict");
            return false;
        }
        // score against the truth
        int should = 0, right = 0, wrong = 0, faintNamed = 0, unlistedNamed = 0;
        for (Pass p : passes) {
            if (!p.drawn) continue;
            for (int f = 0; f < frames; f++) {
                double[][] path = truthPath(r, p, f);
                if (path == null) continue;
                double len = pathLength(path);
                double dens = p.drawnSat.peakDensity[f];
                boolean detectable = len >= 60 && dens >= MIN_DENSITY;
                SatMatch.Streak s = streakOn(r.out.streaks, f, path);
                String got = s == null ? "-" : s.sat != null ? s.sat.name + " (" + s.sat.norad + ")" : "unidentified" + (s.kind.isEmpty() ? "" : " " + s.kind);
                boolean isRight = s != null && s.sat != null && s.sat.norad == p.norad;
                boolean isWrong = s != null && s.sat != null && s.sat.norad != p.norad;
                if (!p.inData) {
                    if (isWrong) unlistedNamed++;
                } else if (detectable) {
                    should++;
                    if (isRight) right++;
                    if (isWrong) wrong++;
                } else if (isWrong) {
                    wrong++;
                } else if (isRight) {
                    faintNamed++;
                }
                if (detectable || s != null) {
                    System.out.printf(Locale.US, "  frame %2d %-16s %s %4.0f px, density %6.1f: %s%s%n", f, p.name,
                            p.inData ? "      " : "(none)", len, dens, got,
                            !p.inData ? (isWrong ? "  WRONG" : "") : isRight ? "" : isWrong ? "  WRONG" : detectable ? "  MISSED" : "");
                }
            }
        }
        // every name given must be on that satellite's own path in that frame
        int falseNames = 0;
        if (r.out.streaks != null) {
            for (SatMatch.Streak s : r.out.streaks.streaks) {
                if (s.sat == null) continue;
                boolean onIt = false;
                for (Pass p : passes) {
                    if (!p.drawn || p.norad != s.sat.norad) continue;
                    double[][] path = truthPath(r, p, s.unit);
                    if (path != null && streakOn(r.out.streaks, s.unit, path) == s) onIt = true;
                }
                if (!onIt) {
                    falseNames++;
                    System.out.printf(Locale.US, "  frame %2d: %s named on (%.0f,%.0f)-(%.0f,%.0f), not its path  FALSE NAME%n", s.unit,
                            s.sat.name, s.x0, s.y0, s.x1, s.y1);
                }
            }
        }
        double share = should > 0 ? right / (double) should : 0;
        System.out.printf(Locale.US, "named: %d of %d streaks right (%.0f%%), %d wrong, %d unlisted satellites given a name, %d names "
                + "off their satellite's path, %d faint ones named too%n", right, should, 100 * share, wrong, unlistedNamed, falseNames, faintNamed);
        System.out.println("matcher: " + SatMatch.describe(r.out.streaks));
        boolean ok = should >= 10 && share >= 0.8 && wrong == 0 && unlistedNamed == 0 && falseNames == 0;
        System.out.println("== naming, " + what + ": " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /**
     * The dashed tracks to draw (each named satellite's, on its true path, labelled inside the picture; none for
     * satellites the data doesn't have) and the report card's streak list.
     */
    static boolean drawn(Result r, List<Pass> passes, int frames, BrightStars catalog) {
        System.out.println("-- tracks and report");
        Renderer.Analysis a = r.out.analysis;
        List<SatOverlay.Line> lines = SatOverlay.lines(r.out.satTracks, r.out.streaks, a.cx0, a.cy0, a.cw, a.ch);
        boolean ok = true;
        int seenLines = 0, unseen = 0;
        for (SatOverlay.Line l : lines) {
            if (l.seen) seenLines++;
            else unseen++;
            boolean inside = l.labelX >= a.cx0 && l.labelY >= a.cy0 && l.labelX < a.cx0 + a.cw && l.labelY < a.cy0 + a.ch;
            // distance of each drawn point from the satellite's true path (all its frames' paths together)
            List<double[][]> truth = new ArrayList<double[][]>();
            boolean listed = false;
            for (Pass p : passes) {
                if (p.norad != l.norad) continue;
                listed |= p.inData;
                if (!p.drawn) continue;
                for (int f = 0; f < frames; f++) {
                    double[][] path = truthPath(r, p, f);
                    if (path != null) truth.add(path);
                }
            }
            List<Double> d = new ArrayList<Double>();
            for (float[] part : l.parts) {
                for (int i = 0; i + 1 < part.length; i += 2) {
                    double e = Double.MAX_VALUE;
                    for (double[][] path : truth) e = Math.min(e, distance(path, part[i], part[i + 1]));
                    if (e < 40) d.add(e);
                }
            }
            double[] dv = new double[d.size()];
            for (int i = 0; i < dv.length; i++) dv[i] = d.get(i);
            double med = dv.length > 0 ? median(dv) : Double.NaN;
            boolean good = inside && listed && (!l.seen || med < 3);
            ok &= good;
            if (!good && System.getenv("SATDBG") != null) debugTrack(r, l, passes);
            if (!good || l.seen && seenLines <= 6) {
                System.out.printf(Locale.US, "  %-16s %s mag %5.1f, %d parts, label (%.0f, %.0f)%s, %.1f px from its true path%s%n", l.name,
                        l.seen ? "seen  " : "unseen", l.mag, l.parts.size(), l.labelX, l.labelY, inside ? "" : " OUTSIDE", med,
                        good ? "" : "  WRONG");
            }
        }
        java.util.Set<Integer> named = new java.util.HashSet<Integer>();
        for (SatMatch.Streak s : r.out.streaks.streaks) if (s.sat != null) named.add(s.sat.norad);
        for (int id : named) {
            boolean has = false;
            for (SatOverlay.Line l : lines) has |= l.norad == id && l.seen;
            if (!has) System.out.println("  no seen track for " + id + "  MISSING");
            ok &= has;
        }
        System.out.printf(Locale.US, "tracks: %d seen, %d predicted only, for %d named satellites%n", seenLines, unseen, named.size());
        ReportCard.Input in = new ReportCard.Input();
        in.out = r.out;
        in.sky = r.sky;
        in.zone = TimeZone.getTimeZone("America/New_York");
        in.catalog = catalog;
        ReportCard rc = ReportCard.build(in);
        System.out.println("report: " + rc.streaks);
        for (List<SatMatch.Streak> chain : ReportCard.chains(r.out.streaks.streaks)) {
            StringBuilder b = new StringBuilder("  unnamed:");
            for (SatMatch.Streak s : chain) {
                b.append(String.format(Locale.US, " [u%d %s (%.0f,%.0f)-(%.0f,%.0f) hw %.1f]", s.unit, s.kind, s.x0, s.y0, s.x1, s.y1,
                        s.halfWidth));
            }
            System.out.println(b);
        }
        boolean says = rc.streaks.startsWith("Satellites crossed the picture: the ISS at ") && rc.streaks.contains("1 plane,")
                && rc.streaks.contains("2 satellites the data doesn't have")
                && rc.streaks.contains("possible meteor") && rc.streaks.contains("taken out of the stack") && rc.toJson().contains("\"streaks\"");
        System.out.println("== tracks and report: " + (ok && says ? "PASS" : "FAIL"));
        return ok && says;
    }

    /** Per frame: where the prediction ran, where the fit put the streak, and where the satellite really was (signed, across). */
    static void debugTrack(Result r, SatOverlay.Line l, List<Pass> passes) {
        for (SatPredict.Track t : r.out.satTracks) {
            if (t.sat.norad != l.norad) continue;
            SatMatch.Streak fit = null;
            for (SatMatch.Streak s : r.out.streaks.streaks) if (s.sat != null && s.sat.norad == l.norad && s.unit == t.unit) fit = s;
            double[][] truth = null;
            for (Pass p : passes) if (p.norad == l.norad && p.drawn && truth == null) truth = truthPath(r, p, t.unit);
            if (truth == null) continue;
            // the predicted path's middle sample, and its direction
            int m = (t.in0 + t.in1) / 2;
            if (Float.isNaN(t.x[m]) || Float.isNaN(t.x[m + 1])) continue;
            double dx = t.x[m + 1] - t.x[m], dy = t.y[m + 1] - t.y[m], len = Math.hypot(dx, dy);
            double nx = -dy / len, ny = dx / len;
            // the truth's signed offset across, near the middle: the truth point nearest the predicted middle
            double best = Double.MAX_VALUE, signed = 0;
            for (double[] q : truth) {
                double d = Math.hypot(q[0] - t.x[m], q[1] - t.y[m]);
                if (d < best) {
                    best = d;
                    signed = (q[0] - t.x[m]) * nx + (q[1] - t.y[m]) * ny;
                }
            }
            System.out.printf(Locale.US, "    unit %2d: truth %+6.1f px across the prediction; fit %s; speed %.0f px/s%n", t.unit, signed,
                    fit == null ? "none" : String.format(Locale.US, "%+6.1f px across, %+.1f s along, half width %.1f", fit.across, fit.alongSec,
                            fit.halfWidth), len / SatPredict.FINE_S);
        }
    }

    /** Distance (px) from a point to a polyline. */
    static double distance(double[][] path, double x, double y) {
        double best = Double.MAX_VALUE;
        for (int k = 0; k + 1 < path.length; k++) {
            double ax = path[k][0], ay = path[k][1], dx = path[k + 1][0] - ax, dy = path[k + 1][1] - ay, l2 = dx * dx + dy * dy;
            double u = l2 > 0 ? Math.max(0, Math.min(1, ((x - ax) * dx + (y - ay) * dy) / l2)) : 0;
            best = Math.min(best, Math.hypot(x - ax - u * dx, y - ay - u * dy));
        }
        return best;
    }

    /** Line density (signal per pixel of length in one frame) a streak needs to leave rejections the stack can see. */
    static final double MIN_DENSITY = 25;

    // ------------------------------------------------------------------------------------------------
    // Part 2: six frames, with and without the corridors

    static boolean masked(File dir, int scale, long seed, long startMs, BrightStars catalog) throws Exception {
        int frames = 6;
        Random rnd = new Random(seed);
        SkySim sim = newSim(scale, startMs, catalog);
        double dur = sim.frameStart(frames - 1) + sim.frameExp;
        double jd0 = Astro.jdUtc(startMs);
        SatSky.Site site = new SatSky.Site(Math.toRadians(LAT), Math.toRadians(LON), 0);
        double[] axis = axisEnu(sim);
        List<Pass> passes = new ArrayList<Pass>();
        Shape[] shapes = {CBERS, DELTA, ISS, SL12, CBERS};
        for (int k = 0; k < shapes.length; k++) {
            Pass p = plan(shapes[k], shapes[k] == ISS ? "ISS (ZARYA)" : shapes[k].name + " " + (char) ('A' + k),
                    shapes[k] == ISS ? 25544 : 72001 + k, jd0 + dur * (k + 0.5) / shapes.length / 86400, axis, 20, rnd, site);
            if (p == null) continue;
            p.trueStdMag = shapes[k] == ISS ? -1.3 : 1.8 + 0.5 * k;
            passes.add(p);
        }
        finish(passes, rnd, frames, sim);
        sim.planeFrame = 2;
        // a phone lens's glow: a tenth of every star's and satellite's light spread a few pixels wide, the faint
        // edge of a trail that outlier rejection can't see
        sim.haloFrac = 0.1;
        sim.haloSigma = 8.0 / scale;
        SatCatalog cat = catalogue(passes, jd0);
        // the same noise in every render, so a render without the satellites and the plane shows what they left behind
        sim.steadyNoise = true;
        Result with = run(new File(dir, "with"), sim, frames, seed, startMs, catalog, cat, true, 0, 30);
        Result without = run(new File(dir, "without"), sim, frames, seed, startMs, catalog, cat, false, 0, 30);
        List<SkySim.OrbitSat> drawn = new ArrayList<SkySim.OrbitSat>(sim.orbitSats);
        int planeFrame = sim.planeFrame;
        sim.orbitSats.clear();
        sim.planeFrame = -1;
        Result clean = run(new File(dir, "clean"), sim, frames, seed, startMs, catalog, cat, true, 0, 30);
        sim.orbitSats.addAll(drawn);
        sim.planeFrame = planeFrame;
        sim.steadyNoise = false;
        if (with.out.solution == null || without.out.solution == null || clean.out.image == null) {
            System.out.println("no plate solution");
            return false;
        }
        RgbImage a = with.out.image, b = without.out.image, c = clean.out.image;
        if (with.out.referenceUnit != clean.out.referenceUnit || without.out.referenceUnit != clean.out.referenceUnit
                || a.width != c.width || a.height != c.height || b.width != c.width || b.height != c.height) {
            System.out.println("the three stacks aren't on the same grid");
            return false;
        }
        boolean ok = true;
        int seenWithout = 0, measured = 0, leftWith = 0;
        List<String> names = new ArrayList<String>();
        List<double[][]> paths = new ArrayList<double[][]>();
        List<Double> densities = new ArrayList<Double>();
        for (Pass p : passes) {
            for (int f = 0; f < frames; f++) {
                double[][] path = truthPath(with, p, f);
                if (path == null || pathLength(path) < 60 || p.drawnSat.peakDensity[f] < MIN_DENSITY) continue;
                names.add("frame " + f + " " + p.name);
                paths.add(path);
                densities.add(p.drawnSat.peakDensity[f]);
            }
        }
        for (int f = 0; f < frames; f++) {
            double[][] path = planePath(sim, with.out.referenceUnit, f);
            if (path == null) continue;
            names.add("frame " + f + " plane");
            paths.add(path);
            densities.add(Double.NaN);
        }
        for (int k = 0; k < paths.size(); k++) {
            double[][] path = paths.get(k);
            double[] rw = residual(a, c, path), ro = residual(b, c, path);
            if (rw == null || ro == null) continue;
            measured++;
            boolean left = visible(rw), seen = visible(ro);
            if (seen) seenWithout++;
            if (left) leftWith++;
            ok &= !left;
            System.out.printf(Locale.US, "  %-24s %4.0f px, density %7.1f: with corridors %+6.2f over %2.0f px (%+5.1f se, sight %.2f), "
                            + "without %+6.2f over %2.0f px (%+5.1f se, sight %.2f)%s%s%n", names.get(k), pathLength(path), densities.get(k),
                    rw[0], rw[2], rw[0] / rw[1], sight(rw), ro[0], ro[2], ro[0] / ro[1], sight(ro),
                    seen ? "  seen without" : "", left ? "  TRAIL LEFT" : "");
        }
        System.out.printf(Locale.US, "six frames: %d trails measured, %d of them visible without the corridors, %d with; streaks: %s%n",
                measured, seenWithout, leftWith, SatMatch.describe(with.out.streaks));
        ok &= measured >= 4;
        System.out.println("== six frames with masks: " + (ok ? "PASS" : "FAIL") + (seenWithout == 0 ? " (but the test saw no trail without them either)" : ""));
        return ok;
    }

    /** Half the width of the band across a path that is measured (px). */
    static final int BAND = 24;

    /** Widths of the lines looked for in a trail's remains (px). */
    static final int[] WIDTHS = {3, 6, 12, 2 * BAND + 1};

    /**
     * What a trail left in a stack: the stack minus the same session's stack rendered without satellites or plane
     * (same noise), across +-BAND px of the path, less the same difference's level 30 to 60 px out, as a profile
     * (the median along each of 8 stretches of the path, averaged). The most visible line in that profile, of
     * any of {@link #WIDTHS} and anywhere across the band, is returned as {its summed excess per px of length,
     * that sum's standard error from the 8 stretches, its width, the clean stack's pixel noise there}; null if
     * too little of the path is on the picture.
     */
    static double[] residual(RgbImage img, RgbImage clean, double[][] path) {
        int nb = 2 * BAND + 1;
        List<double[]> rows = new ArrayList<double[]>();
        List<Double> noise = new ArrayList<Double>();
        for (int k = 0; k + 1 < path.length; k++) {
            double x0 = path[k][0], y0 = path[k][1], x1 = path[k + 1][0], y1 = path[k + 1][1];
            double len = Math.hypot(x1 - x0, y1 - y0);
            if (len < 1e-6) continue;
            double ux = (x1 - x0) / len, uy = (y1 - y0) / len, nx = -uy, ny = ux;
            for (double u = 0; u < len; u += 1) {
                double px = x0 + ux * u, py = y0 + uy * u;
                double[] row = new double[nb];
                boolean in = true;
                for (int d = -BAND; d <= BAND && in; d++) {
                    row[d + BAND] = diff(img, clean, px + nx * d, py + ny * d);
                    if (Double.isNaN(row[d + BAND])) in = false;
                }
                double[] ring = new double[44], bg = new double[44];
                int n = 0;
                for (int d = 30; d <= 60 && in; d += 3) {
                    for (int side = -1; side <= 1; side += 2) {
                        double v = diff(img, clean, px + nx * d * side, py + ny * d * side);
                        double q = value(clean, px + nx * d * side, py + ny * d * side);
                        if (Double.isNaN(v) || Double.isNaN(q)) continue;
                        ring[n] = v;
                        bg[n++] = q;
                    }
                }
                if (!in || n < 10) continue;
                double level = median(Arrays.copyOf(ring, n));
                for (int d = 0; d < nb; d++) row[d] -= level;
                rows.add(row);
                noise.add(mad(Arrays.copyOf(bg, n)));
            }
        }
        int K = 8;
        if (rows.size() < 5 * K) return null;
        double[][] prof = new double[K][nb];
        for (int k = 0; k < K; k++) {
            int from = k * rows.size() / K, to = (k + 1) * rows.size() / K;
            double[] col = new double[to - from];
            for (int d = 0; d < nb; d++) {
                for (int i = from; i < to; i++) col[i - from] = rows.get(i)[d];
                prof[k][d] = median(col);
            }
        }
        double[] nz = new double[noise.size()];
        for (int i = 0; i < nz.length; i++) nz[i] = noise.get(i);
        double sigma = median(nz);
        double[] best = null;
        double bestScore = -1;
        for (int w : WIDTHS) {
            for (int a = 0; a + w <= nb; a++) {
                double[] sums = new double[K];
                double mean = 0;
                for (int k = 0; k < K; k++) {
                    for (int d = a; d < a + w; d++) sums[k] += prof[k][d];
                    mean += sums[k] / K;
                }
                double var = 0;
                for (double v : sums) var += (v - mean) * (v - mean) / (K - 1);
                double se = Math.max(1e-6, Math.sqrt(var / K));
                double[] r = {mean, se, w, sigma};
                double score = Math.min(Math.abs(mean) / (4 * se), sight(r));
                if (score > bestScore) {
                    bestScore = score;
                    best = r;
                }
            }
        }
        return best;
    }

    /**
     * How plainly a person would see a line {sum, se, width, pixel noise}: its excess per pixel, over 50 px of it
     * and at most 6 px across (a faint wide band isn't a trail), against the pixel noise, in units of 3 sigma.
     */
    static double sight(double[] r) {
        double perPx = Math.abs(r[0]) / r[2];
        return perPx * Math.sqrt(50 * Math.min(r[2], 6)) / r[3] / 3;
    }

    /** A trail a person would see: clear of the difference's own noise (4 standard errors) and of the stack's. */
    static boolean visible(double[] r) {
        return Math.abs(r[0]) > 4 * r[1] && sight(r) > 1;
    }

    static double diff(RgbImage a, RgbImage b, double x, double y) {
        double va = value(a, x, y), vb = value(b, x, y);
        return Double.isNaN(va) || Double.isNaN(vb) ? Double.NaN : va - vb;
    }

    static double value(RgbImage img, double x, double y) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        if (ix < img.x0 || iy < img.y0 || ix >= img.x1 || iy >= img.y1) return Double.NaN;
        return img.g[iy * img.width + ix];
    }

    static double median(double[] a) {
        double[] s = a.clone();
        Arrays.sort(s);
        return s.length == 0 ? Double.NaN : (s.length % 2 == 1 ? s[s.length / 2] : 0.5 * (s[s.length / 2 - 1] + s[s.length / 2]));
    }

    /** Robust spread: 1.4826 times the median absolute deviation. */
    static double mad(double[] a) {
        double m = median(a);
        double[] d = new double[a.length];
        for (int i = 0; i < a.length; i++) d[i] = Math.abs(a[i] - m);
        return 1.4826 * median(d);
    }

    /** The plane's line in frame f, in the reference frame's pixels (the stack's grid); null if it isn't in frame f. */
    static double[][] planePath(SkySim sim, int ref, int f) {
        double[] seg = sim.planeSegment(f);
        if (seg == null || ref < 0) return null;
        double[] Rf = sim.cameraToJ2000(f), Rt = Rot.transpose(sim.cameraToJ2000(ref));
        double len = Math.hypot(seg[2] - seg[0], seg[3] - seg[1]);
        List<double[]> pts = new ArrayList<double[]>();
        double[] cam = new double[3], j = new double[3], b = new double[3], px = new double[2];
        for (double u = 0; u <= len; u += 10) {
            double x = seg[0] + (seg[2] - seg[0]) * u / len, y = seg[1] + (seg[3] - seg[1]) * u / len;
            sim.truth.unproject(x, y, cam);
            Rot.apply(Rf, cam[0], cam[1], cam[2], j);
            Rot.apply(Rt, j[0], j[1], j[2], b);
            if (b[2] < 0.05 || !sim.truth.project(b[0], b[1], b[2], px)) continue;
            pts.add(new double[]{px[0], px[1]});
        }
        return pts.size() >= 2 ? pts.toArray(new double[0][]) : null;
    }

    // ------------------------------------------------------------------------------------------------
    // Passes

    /** The camera's axis as east, north, up at the start. */
    static double[] axisEnu(SkySim sim) {
        // bearing (0, 0, 1) of frame 0, through J2000, in ENU at the middle of that frame
        double[] R = sim.cameraToJ2000(0), M = sim.j2000ToEnu(0.5 * sim.frameExp);
        double[] j = new double[3], e = new double[3];
        Rot.apply(R, 0, 0, 1, j);
        Rot.apply(M, j[0], j[1], j[2], e);
        return e;
    }

    /**
     * A pass of shape {@code sh} through a direction within {@code radiusDeg} of the axis at jdT, lit; null if
     * none could be found.
     */
    static Pass plan(Shape sh, String name, int norad, double jdT, double[] axis, double radiusDeg, Random rnd, SatSky.Site site) {
        for (int tries = 0; tries < 40; tries++) {
            double r = radiusDeg * Math.sqrt(rnd.nextDouble()), az = 2 * Math.PI * rnd.nextDouble();
            double[] tgt = offset(axis, Math.toRadians(r), az);
            double age = 0.2 + 3.8 * rnd.nextDouble();
            Pass p = new Pass();
            p.name = name;
            p.norad = norad;
            p.shape = sh;
            p.epochJd = jdT - age;
            p.jdTarget = jdT;
            p.targetEnu = tgt;
            if (!solve(p, rnd.nextBoolean(), site)) continue;
            Sgp4 s = elements(p, p.epochJd, 0, 0);
            SatSky.Look look = new SatSky.Look();
            if (!SatSky.look(s, 3, site.moment(jdT), look) || !look.sunlit || look.altDeg < 20) continue;
            return p;
        }
        System.out.println("  (no lit pass found for " + name + ")");
        return null;
    }

    /** A pass through exactly this direction at jdT, lit; null if none. */
    static Pass planAt(Shape sh, String name, int norad, double jdT, double[] tgt, Random rnd, SatSky.Site site) {
        for (int tries = 0; tries < 8; tries++) {
            Pass p = new Pass();
            p.name = name;
            p.norad = norad;
            p.shape = sh;
            p.epochJd = jdT - (0.2 + 3.8 * rnd.nextDouble());
            p.jdTarget = jdT;
            p.targetEnu = tgt;
            if (!solve(p, rnd.nextBoolean(), site)) continue;
            SatSky.Look look = new SatSky.Look();
            if (!SatSky.look(elements(p, p.epochJd, 0, 0), 3, site.moment(jdT), look) || !look.sunlit) continue;
            return p;
        }
        return null;
    }

    static Pass copyPass(Pass p, String name, int norad) {
        Pass c = new Pass();
        c.name = name;
        c.norad = norad;
        c.shape = p.shape;
        c.epochJd = p.epochJd;
        c.raan = p.raan;
        c.m = p.m;
        c.jdTarget = p.jdTarget;
        c.targetEnu = p.targetEnu;
        c.trueStdMag = p.trueStdMag;
        return c;
    }

    /** A unit vector {@code ang} radians from {@code a}, toward position angle {@code pa}. */
    static double[] offset(double[] a, double ang, double pa) {
        double[] u = Math.abs(a[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{0, 1, 0};
        double[] e1 = norm(cross(u, a)), e2 = cross(a, e1);
        double c = Math.cos(ang), s = Math.sin(ang);
        return norm(new double[]{c * a[0] + s * (Math.cos(pa) * e1[0] + Math.sin(pa) * e2[0]),
                c * a[1] + s * (Math.cos(pa) * e1[1] + Math.sin(pa) * e2[1]),
                c * a[2] + s * (Math.cos(pa) * e1[2] + Math.sin(pa) * e2[2])});
    }

    /**
     * Node and mean anomaly (degrees) that put the satellite at the target direction at jdTarget: a geometric
     * first guess (a circular orbit through the point at the shape's height above the target), then Newton's
     * method on SGP4 itself.
     */
    static boolean solve(Pass p, boolean ascending, SatSky.Site site) {
        Shape sh = p.shape;
        double nRad = sh.n * 2 * Math.PI / 86400;
        double a = Math.cbrt(398600.4418 / (nRad * nRad));
        double[] obs = Astro.observerEcef(site.latRad, site.lonRad, site.hKm);
        double[] m = Astro.ecefToEnu(site.latRad, site.lonRad);
        double[] t = p.targetEnu;
        // ENU -> ECEF: the transpose
        double[] d = {m[0] * t[0] + m[3] * t[1] + m[6] * t[2], m[1] * t[0] + m[4] * t[1] + m[7] * t[2], m[2] * t[0] + m[5] * t[1] + m[8] * t[2]};
        double od = obs[0] * d[0] + obs[1] * d[1] + obs[2] * d[2], oo = obs[0] * obs[0] + obs[1] * obs[1] + obs[2] * obs[2];
        double rho = -od + Math.sqrt(od * od - oo + a * a);
        double[] pe = {obs[0] + rho * d[0], obs[1] + rho * d[1], obs[2] + rho * d[2]};
        double g = Sgp4.gstime(p.jdTarget), cg = Math.cos(g), sg = Math.sin(g);
        double[] pt = {cg * pe[0] - sg * pe[1], sg * pe[0] + cg * pe[1], pe[2]};
        double r = Math.sqrt(pt[0] * pt[0] + pt[1] * pt[1] + pt[2] * pt[2]);
        double dec = Math.asin(pt[2] / r), ra = Math.atan2(pt[1], pt[0]);
        double inc = Math.toRadians(sh.i);
        double su = Math.sin(dec) / Math.sin(inc);
        if (Math.abs(su) > 1) return false;
        double u = ascending ? Math.asin(su) : Math.PI - Math.asin(su);
        double node = ra - Math.atan2(Math.cos(inc) * Math.sin(u), Math.cos(u));
        double revs = sh.n * (p.jdTarget - p.epochJd);
        p.raan = norm360(Math.toDegrees(node));
        p.m = norm360(Math.toDegrees(u) - sh.argp - 360 * (revs - Math.floor(revs)));
        // Newton on (node, mean anomaly) against the direction SGP4 gives
        double[] e1 = norm(cross(Math.abs(t[2]) < 0.9 ? new double[]{0, 0, 1} : new double[]{0, 1, 0}, t)), e2 = cross(t, e1);
        SatSky.Moment mo = site.moment(p.jdTarget);
        SatSky.Look look = new SatSky.Look();
        for (int it = 0; it < 30; it++) {
            double[] f0 = residual(p, p.raan, p.m, mo, look, t, e1, e2);
            if (f0 == null) return false;
            if (Math.hypot(f0[0], f0[1]) < 1e-6) return true;
            double h = 0.01;
            double[] fa = residual(p, p.raan + h, p.m, mo, look, t, e1, e2), fb = residual(p, p.raan, p.m + h, mo, look, t, e1, e2);
            if (fa == null || fb == null) return false;
            double j00 = (fa[0] - f0[0]) / h, j10 = (fa[1] - f0[1]) / h, j01 = (fb[0] - f0[0]) / h, j11 = (fb[1] - f0[1]) / h;
            double det = j00 * j11 - j01 * j10;
            if (Math.abs(det) < 1e-12) return false;
            double dn = (j11 * f0[0] - j01 * f0[1]) / det, dm = (-j10 * f0[0] + j00 * f0[1]) / det;
            double step = Math.hypot(dn, dm);
            if (step > 20) {
                dn *= 20 / step;
                dm *= 20 / step;
            }
            p.raan = norm360(p.raan - dn);
            p.m = norm360(p.m - dm);
        }
        double[] f = residual(p, p.raan, p.m, mo, look, t, e1, e2);
        return f != null && Math.hypot(f[0], f[1]) < 1e-4;
    }

    private static double[] residual(Pass p, double raan, double m, SatSky.Moment mo, SatSky.Look look, double[] t, double[] e1, double[] e2) {
        double r0 = p.raan, m0 = p.m;
        p.raan = raan;
        p.m = m;
        Sgp4 s = elements(p, p.epochJd, 0, 0);
        p.raan = r0;
        p.m = m0;
        if (s.error != 0 || !SatSky.look(s, 3, mo, look)) return null;
        // behind the observer's horizon the direction still counts: Newton needs it smooth
        double[] d = {look.enu[0] - t[0], look.enu[1] - t[1], look.enu[2] - t[2]};
        return new double[]{d[0] * e1[0] + d[1] * e1[1] + d[2] * e1[2], d[0] * e2[0] + d[1] * e2[1] + d[2] * e2[2]};
    }

    /** The pass's elements at epoch {@code epochJd}, the orbit tilted by {@code dIncDeg} and turned by {@code dNodeDeg}. */
    static Sgp4 elements(Pass p, double epochJd, double dIncDeg, double dNodeDeg) {
        Shape sh = p.shape;
        return Sgp4.fromOmm(p.name, String.valueOf(p.norad), iso(epochJd), sh.n, sh.e, sh.i + dIncDeg, norm360(p.raan + dNodeDeg),
                sh.argp, p.m, sh.bstar, sh.ndot, sh.nddot);
    }

    /**
     * The elements in the phone's data, and the satellites as drawn: moved along their orbit and across it by
     * errors typical of elements of their age (along: 0.3 s, plus 0.4 s a day, plus 0.15 s a day squared for the
     * drag nobody predicted; across: 0.2 km plus 0.25 km a day).
     */
    static void finish(List<Pass> passes, Random rnd, int frames, SkySim sim) {
        for (Pass p : passes) {
            p.data = elements(p, p.epochJd, 0.0, 0.0);
            if (!p.drawn) continue;
            double age = p.jdTarget - p.epochJd;
            p.alongErrSec = rnd.nextGaussian() * (0.3 + 0.4 * age + 0.15 * age * age);
            p.crossErrKm = rnd.nextGaussian() * (0.2 + 0.25 * age);
            // across: the tilt of inclination and node that moves the satellite that far along the orbit's normal at
            // the target time, from SGP4's own response to each
            double[] r = new double[3], v = new double[3], ri = new double[3], rn = new double[3], w = new double[3];
            double ahead = p.epochJd - p.alongErrSec / 86400, h = 0.001;
            elements(p, ahead, 0, 0).at(p.jdTarget, r, v);
            elements(p, ahead, h, 0).at(p.jdTarget, ri, w);
            elements(p, ahead, 0, h).at(p.jdTarget, rn, w);
            double[] nrm = norm(cross(r, v));
            double gi = ((ri[0] - r[0]) * nrm[0] + (ri[1] - r[1]) * nrm[1] + (ri[2] - r[2]) * nrm[2]) / h;
            double gn = ((rn[0] - r[0]) * nrm[0] + (rn[1] - r[1]) * nrm[1] + (rn[2] - r[2]) * nrm[2]) / h;
            double g2 = gi * gi + gn * gn;
            p.truth = elements(p, ahead, p.crossErrKm * gi / g2, p.crossErrKm * gn / g2);
            p.drawnSat = new SkySim.OrbitSat(p.truth, p.trueStdMag, frames);
            sim.orbitSats.add(p.drawnSat);
        }
    }

    /** The phone's data as CelesTrak would send it: OMM in JSON, one request per group. */
    static SatCatalog catalogue(List<Pass> passes, double jd0) {
        SatCatalog cat = new SatCatalog();
        String[] groups = {"visual", "stations", "starlink"};
        for (String g : groups) {
            StringBuilder b = new StringBuilder("[");
            for (Pass p : passes) {
                if (!p.inData || !p.shape.source.equals(g)) continue;
                if (b.length() > 1) b.append(",\n");
                Shape sh = p.shape;
                b.append(String.format(Locale.US, "{\"OBJECT_NAME\":\"%s\",\"OBJECT_ID\":\"2026-%03dA\",\"EPOCH\":\"%s\",\"MEAN_MOTION\":%.8f,"
                                + "\"ECCENTRICITY\":%.7f,\"INCLINATION\":%.4f,\"RA_OF_ASC_NODE\":%.4f,\"ARG_OF_PERICENTER\":%.4f,"
                                + "\"MEAN_ANOMALY\":%.4f,\"EPHEMERIS_TYPE\":0,\"CLASSIFICATION_TYPE\":\"U\",\"NORAD_CAT_ID\":%d,"
                                + "\"ELEMENT_SET_NO\":999,\"REV_AT_EPOCH\":12345,\"BSTAR\":%.5e,\"MEAN_MOTION_DOT\":%.8f,\"MEAN_MOTION_DDOT\":%.1f}",
                        p.name, p.norad % 1000, iso(p.epochJd), sh.n, sh.e, sh.i, p.raan, sh.argp, p.m, p.norad, sh.bstar,
                        sh.ndot / 2, sh.nddot));
            }
            b.append("]");
            cat.add(b.toString(), g, jd0);
        }
        return cat;
    }

    // ------------------------------------------------------------------------------------------------
    // Running a session and reading the truth

    static final class Result {
        PostProcessor.Output out;
        SkySim sim;
        SessionSky sky;
    }

    static SkySim newSim(int scale, long startMs, BrightStars catalog) {
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.cx = W / 2.0 + 14.0 / scale;
        truth.cy = H / 2.0 - 9.0 / scale;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        SkySim sim = new SkySim(W, H, truth, 42, catalog, startMs, LON);
        sim.psfSigma = 0.85 / Math.sqrt(scale);
        return sim;
    }

    /**
     * @param placeOffKm how far from the truth the phone thinks it is (north-east)
     * @param accuracyM  how far off it says it may be
     */
    static Result run(File dir, SkySim sim, int frames, long seed, long startMs, BrightStars catalog, SatCatalog cat,
                      boolean corridors, double placeOffKm, double accuracyM) throws Exception {
        dir.mkdirs();
        SensorSpec spec = sim.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        File root = new File(dir, "sessions");
        root.mkdirs();
        SessionStore store = SessionStore.create(root);
        UnitProcessor up = new UnitProcessor(store, spec, 1, 3, new UnitProcessor.Listener() {
            @Override
            public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
        });
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            sim.render(i, buf, info, seed);
            up.submit(buf, info);
        }
        up.finish();
        PostProcessor pp = new PostProcessor(store, spec);
        SessionSky sky = new SessionSky();
        double dKm = placeOffKm / Math.sqrt(2);
        sky.latDeg = LAT + Math.toDegrees(dKm / 6371.0);
        sky.lonDeg = LON + Math.toDegrees(dKm / (6371.0 * Math.cos(Math.toRadians(LAT))));
        sky.altM = 0;
        sky.accuracyM = accuracyM;
        sky.locationSource = "simulation";
        // the phone's clock is a little ahead
        sky.clockWallMs = startMs + Math.round(CLOCK_ERR_S * 1000);
        sky.clockTimestampNs = 1_000_000_000L;
        sky.clockValid = true;
        sky.catalog = catalog;
        pp.sky = sky;
        pp.satellites = cat;
        pp.streakCorridors = corridors;
        long t0 = System.currentTimeMillis();
        PostProcessor.Output o = pp.run(false, new Progress() {
            @Override
            public void update(String stage, float fraction) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        });
        System.out.printf(Locale.US, "%s: %s (%d ms)%n", dir.getName(), o.summary, System.currentTimeMillis() - t0);
        for (String d : o.decisions) if (d.startsWith("streaks")) System.out.println("  decision: " + d);
        Result r = new Result();
        r.out = o;
        r.sim = sim;
        r.sky = sky;
        java.nio.file.Files.copy(store.logFile().toPath(), new File(dir, "session.log").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        store.deleteBulk();
        return r;
    }

    /**
     * Where the satellite as drawn crossed the stack during frame f (the reference frame's pixels, by the
     * sim's true camera), every 0.1 s while lit and in the picture; null if it wasn't.
     */
    static double[][] truthPath(Result r, Pass p, int f) {
        if (p.truth == null || r.out.referenceUnit < 0) return null;
        SkySim sim = r.sim;
        double[] R = sim.cameraToJ2000(r.out.referenceUnit);
        double[] Rt = Rot.transpose(R);
        SatSky.Site site = new SatSky.Site(Math.toRadians(LAT), Math.toRadians(LON), 0);
        SatSky.Look look = new SatSky.Look();
        List<double[]> pts = new ArrayList<double[]>();
        double[] b = new double[3], px = new double[2];
        for (double t = 0; t <= sim.frameExp + 1e-9; t += 0.1) {
            double jd = Astro.jdUtc(sim.startUnixMs / 1000.0 + sim.frameStart(f) + t);
            if (!SatSky.look(p.truth, 3, site.moment(jd), look) || !look.sunlit || look.altDeg < 0) continue;
            Rot.apply(Rt, look.j2000[0], look.j2000[1], look.j2000[2], b);
            if (b[2] < 0.05 || !sim.truth.project(b[0], b[1], b[2], px)) continue;
            if (px[0] < 0 || px[1] < 0 || px[0] >= sim.W || px[1] >= sim.H) continue;
            pts.add(new double[]{px[0], px[1]});
        }
        return pts.size() >= 2 ? pts.toArray(new double[0][]) : null;
    }

    static double pathLength(double[][] p) {
        double l = 0;
        for (int k = 0; k + 1 < p.length; k++) l += Math.hypot(p[k + 1][0] - p[k][0], p[k + 1][1] - p[k][1]);
        return l;
    }

    /** The found streak in frame f that lies along the path (its middle within 12 px of it, its direction within 6 degrees). */
    static SatMatch.Streak streakOn(SatMatch.Result res, int f, double[][] path) {
        if (res == null) return null;
        SatMatch.Streak best = null;
        double bestD = 12;
        for (SatMatch.Streak s : res.streaks) {
            if (s.unit != f) continue;
            double mx = (s.x0 + s.x1) / 2, my = (s.y0 + s.y1) / 2;
            double d = Double.MAX_VALUE, dirOk = 0;
            for (int k = 0; k + 1 < path.length; k++) {
                double ax = path[k][0], ay = path[k][1], bx = path[k + 1][0], by = path[k + 1][1];
                double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
                double u = l2 > 0 ? Math.max(0, Math.min(1, ((mx - ax) * dx + (my - ay) * dy) / l2)) : 0;
                double e = Math.hypot(mx - ax - u * dx, my - ay - u * dy);
                if (e < d) {
                    d = e;
                    double sx = s.x1 - s.x0, sy = s.y1 - s.y0;
                    dirOk = Math.abs(sx * dx + sy * dy) / Math.max(1e-9, Math.hypot(sx, sy) * Math.sqrt(l2));
                }
            }
            if (d < bestD && dirOk > Math.cos(Math.toRadians(6))) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------------------------------------

    static String iso(double jd) {
        double ms = (jd - 2440587.5) * 86400000.0;
        long whole = (long) Math.floor(ms);
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.setTimeInMillis(whole);
        double sec = c.get(Calendar.SECOND) + (c.get(Calendar.MILLISECOND) + (ms - whole)) / 1000.0;
        return String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:%09.6f", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), sec);
    }

    static double norm360(double a) {
        a %= 360;
        return a < 0 ? a + 360 : a;
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] norm(double[] a) {
        double n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / n, a[1] / n, a[2] / n};
    }
}
