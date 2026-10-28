package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.MeteorLog;
import com.brandon.nightsky.processing.MeteorShowers;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SatMatch;
import com.brandon.nightsky.processing.SatSky;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TimeZone;

/**
 * Meteors (1.5) on a synthetic session on the Geminids' peak night, through capture, the stack and the streak
 * finding, scored against the truth. The Geminids come from the shower's radiant as seen from the place (moved
 * towards the zenith by the Earth's gravity), each a little off it as a shower's meteors are, at the speed across
 * the sky their speed in the air gives them where they are; the sporadic meteors fly any way that lines up with no
 * shower active that night, at speeds of their own. Each lights up and fades the way meteors do, brightest late in
 * its flight. A blinking plane crosses too.
 * <p>
 * Scored: each meteor bright and long enough for the stack to reject (as {@link SatSessionTest} counts a
 * satellite's) found in its own frame and called a meteor; the Geminids named and the sporadic ones not; the way
 * each Geminid flew; both ends of each on its true path on the sky; its brightness at its brightest within a
 * magnitude (a sporadic one's within one and a half: its speed is taken, not known); its frame's times around it;
 * nothing else called a meteor. Then the pictures: by default the meteors are taken out of the stack with the other
 * streaks, and with Keep meteors they stay, as a render of the same session without them (the same noise) shows.
 * The session's log, meteors.csv and the report card must say the same.
 * <p>
 * Usage: MeteorSessionTest outDir [scale=2] [frames=37] [seed=1]
 */
public final class MeteorSessionTest {
    static final double LAT = SatSessionTest.LAT, LON = SatSessionTest.LON;
    static final double DEG = Astro.DEG;

    /** A meteor drawn, and what it is. */
    static final class Truth {
        String name;
        /** Its shower; null for a sporadic meteor. */
        MeteorShowers.Shower shower;
        SkySim.Meteor m;
        int frame;
        /** Found, called a meteor, and in the log (else null). */
        SatMatch.Streak streak;
        MeteorLog.Meteor logged;
        double[][] path;
        boolean detectable;
    }

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "meteor-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 37;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 1;
        out.mkdirs();
        BrightStars catalog = SimRunner.loadCatalog();
        long startMs = geminidNight();
        double jd0 = Astro.jdUtc(startMs);
        SatSky.Moment mo = SatSky.moment(jd0, Math.toRadians(LAT), Math.toRadians(LON), 0);
        MeteorShowers.Shower gem = MeteorShowers.byCode("GEM");
        double[] rad = MeteorShowers.apparentRadiant(gem.radiant(MeteorShowers.solarLongitude(jd0)), zenithJ2000(mo), gem.speed);
        double[] e = new double[3];
        mo.toEnu(rad, e);
        System.out.printf(Locale.US, "session starts %s UTC, solar longitude %.2f (Geminids peak at %.2f), Sun at %.1f deg, "
                        + "Geminid radiant at altitude %.1f%n", SatSessionTest.iso(jd0), MeteorShowers.solarLongitude(jd0), gem.peak,
                mo.sunAltDeg, Math.toDegrees(Math.asin(e[2])));
        SkySim sim = SatSessionTest.newSim(scale, startMs, catalog);
        sim.planeFrame = frames / 3;
        List<Truth> truth = plan(sim, frames, new Random(seed), gem);
        // the same noise in every render, so one without the meteors shows what they left behind
        sim.steadyNoise = true;
        SatSessionTest.Result def = SatSessionTest.run(new File(out, "default"), sim, frames, seed, startMs, catalog, null, true, 0, 30, false);
        SatSessionTest.Result keep = SatSessionTest.run(new File(out, "keep"), sim, frames, seed, startMs, catalog, null, true, 0, 30, true);
        List<SkySim.Meteor> drawn = new ArrayList<SkySim.Meteor>(sim.meteors);
        sim.meteors.clear();
        SatSessionTest.Result clean = SatSessionTest.run(new File(out, "clean"), sim, frames, seed, startMs, catalog, null, true, 0, 30, false);
        sim.meteors.addAll(drawn);
        if (def.out.solution == null || keep.out.solution == null || clean.out.image == null) {
            System.out.println("no plate solution");
            System.out.println("FAILED");
            System.exit(1);
        }
        boolean ok = score("default (taken out)", def, truth, false);
        ok &= score("Keep meteors", keep, truth, true);
        ok &= pictures(def, keep, clean, truth);
        ok &= said(def, out, false, truth);
        ok &= said(keep, out, true, truth);
        System.out.println(ok ? "ALL PASS" : "FAILED");
        if (!ok) System.exit(1);
    }

    /** 1:00 AM local time (UTC-5) on the night of the Geminids' peak in 2026, their radiant high in the south. */
    static long geminidNight() {
        Calendar c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US);
        c.clear();
        c.set(2026, Calendar.DECEMBER, 14, 6, 0, 0);
        return c.getTimeInMillis();
    }

    // ------------------------------------------------------------------------------------------------
    // The meteors

    /** Eight Geminids and three sporadic meteors, each in a frame of its own (two apart, clear of the plane and the session's ends). */
    static List<Truth> plan(SkySim sim, int frames, Random rnd, MeteorShowers.Shower shower) {
        List<Integer> free = new ArrayList<Integer>();
        for (int f = 2; f < frames - 2; f += 2) if (f < sim.planeFrame - 1 || f > sim.planeFrame + 2) free.add(f);
        Collections.shuffle(free, rnd);
        int members = 8, sporadic = 3;
        List<Truth> list = new ArrayList<Truth>();
        for (int k = 0; k < members + sporadic && k < free.size(); k++) {
            int f = free.get(k);
            Truth t = k < members ? member(sim, f, shower, rnd, frames) : sporadic(sim, f, rnd, frames);
            if (t == null) {
                System.out.println("  (no meteor placed in frame " + f + ")");
                continue;
            }
            t.name = (t.shower != null ? t.shower.member : "sporadic") + " " + (list.size() + 1);
            list.add(t);
            sim.meteors.add(t.m);
            double[] end = t.m.at(t.m.startSec + t.m.durationSec);
            System.out.printf(Locale.US, "  %-14s frame %2d at %5.2f s for %.2f s, %4.1f deg/s (%4.1f deg), peak mag %+.1f, from RA %.1f Dec %+.1f%n",
                    t.name, f, t.m.startSec - sim.frameStart(f), t.m.durationSec, t.m.speedDegS, t.m.speedDegS * t.m.durationSec,
                    t.m.peakMag, Astro.raDec(t.m.from)[0] / DEG, Astro.raDec(t.m.from)[1] / DEG);
        }
        return list;
    }

    /** A meteor of a shower in frame f: from the shower's radiant as seen (give or take its spread), somewhere in the picture. */
    static Truth member(SkySim sim, int f, MeteorShowers.Shower sh, Random rnd, int frames) {
        return member(sim, f, sh, rnd, frames, Double.NaN);
    }

    /** As above, at its brightest of magnitude {@code mag} (NaN: any from -1.5 to +1.5). */
    static Truth member(SkySim sim, int f, MeteorShowers.Shower sh, Random rnd, int frames, double mag) {
        for (int tries = 0; tries < 500; tries++) {
            double dur = 0.3 + 0.6 * rnd.nextDouble();
            double t0 = sim.frameStart(f) + 0.3 + (sim.frameExp - 0.6 - dur) * rnd.nextDouble(), tm = t0 + dur / 2;
            double jd = Astro.jdUtc(sim.startUnixMs / 1000.0 + tm);
            double[] rad = MeteorShowers.apparentRadiant(sh.radiant(MeteorShowers.solarLongitude(jd)), zenith(sim, tm), sh.speed);
            // its own radiant: a little off the shower's (a Rayleigh-distributed distance, sigma the spread)
            rad = SatSessionTest.offset(rad, sh.spread * DEG * Math.sqrt(-2 * Math.log(1 - rnd.nextDouble())), 2 * Math.PI * rnd.nextDouble());
            double[] mid = inPicture(sim, f, rnd);
            double d = Astro.sep(mid, rad);
            if (d < 8 * DEG || d > 80 * DEG) continue;
            double w = MeteorShowers.angularSpeed(sh.speed, d / DEG, altitude(sim, tm, mid));
            double len = w * dur * DEG, a = d - len / 2;
            if (a < 3 * DEG) continue;
            // along the great circle from its radiant through the middle
            double dot = dot(mid, rad);
            double[] T = SatSessionTest.norm(new double[]{mid[0] - dot * rad[0], mid[1] - dot * rad[1], mid[2] - dot * rad[2]});
            double c = Math.cos(a), s = Math.sin(a);
            double[] from = {rad[0] * c + T[0] * s, rad[1] * c + T[1] * s, rad[2] * c + T[2] * s};
            double[] dir = {T[0] * c - rad[0] * s, T[1] * c - rad[1] * s, T[2] * c - rad[2] * s};
            Truth t = new Truth();
            t.shower = sh;
            t.frame = f;
            double any = -1.5 + 3 * rnd.nextDouble();
            t.m = new SkySim.Meteor(t0, dur, from, dir, w, Double.isNaN(mag) ? any : mag, frames);
            return t;
        }
        return null;
    }

    /** A sporadic meteor in frame f: any way across the sky that lines up with no shower active that night. */
    static Truth sporadic(SkySim sim, int f, Random rnd, int frames) {
        for (int tries = 0; tries < 500; tries++) {
            double dur = 0.2 + 0.6 * rnd.nextDouble();
            double t0 = sim.frameStart(f) + 0.3 + (sim.frameExp - 0.6 - dur) * rnd.nextDouble(), tm = t0 + dur / 2;
            double jd = Astro.jdUtc(sim.startUnixMs / 1000.0 + tm);
            double[] mid = inPicture(sim, f, rnd);
            double[] e1 = SatSessionTest.norm(SatSessionTest.cross(new double[]{0, 0, 1}, mid)), e2 = SatSessionTest.cross(mid, e1);
            double pa = 2 * Math.PI * rnd.nextDouble();
            double[] u = {e1[0] * Math.cos(pa) + e2[0] * Math.sin(pa), e1[1] * Math.cos(pa) + e2[1] * Math.sin(pa),
                    e1[2] * Math.cos(pa) + e2[2] * Math.sin(pa)};
            // its own speed in the air and distance from its own radiant
            double w = MeteorShowers.angularSpeed(30 + 25 * rnd.nextDouble(), 30 + 50 * rnd.nextDouble(), altitude(sim, tm, mid));
            double len = w * dur * DEG, c = Math.cos(len / 2), s = Math.sin(len / 2);
            double[] from = {mid[0] * c - u[0] * s, mid[1] * c - u[1] * s, mid[2] * c - u[2] * s};
            double[] dir = {u[0] * c + mid[0] * s, u[1] * c + mid[1] * s, u[2] * c + mid[2] * s};
            SkySim.Meteor m = new SkySim.Meteor(t0, dur, from, dir, w, -1 + 2.5 * rnd.nextDouble(), frames);
            // its great circle clear of every active shower's radiant (as seen) by more than any tolerance, so that no
            // part of its trail lines up with one either
            double[] n = SatSessionTest.norm(SatSessionTest.cross(from, dir)), zen = zenith(sim, tm);
            double lon = MeteorShowers.solarLongitude(jd), off = Math.PI;
            for (MeteorShowers.Shower sh : MeteorShowers.ALL) {
                if (!sh.active(lon)) continue;
                double[] rad = MeteorShowers.apparentRadiant(sh.radiant(lon), zen, sh.speed);
                off = Math.min(off, Math.asin(Math.min(1, Math.abs(dot(rad, n)))));
            }
            if (off < 12 * DEG) continue;
            Truth t = new Truth();
            t.frame = f;
            t.m = m;
            return t;
        }
        return null;
    }

    /** A direction (J2000) somewhere in frame f's picture, clear of its edges. */
    static double[] inPicture(SkySim sim, int f, Random rnd) {
        double x = sim.W * (0.08 + 0.84 * rnd.nextDouble()), y = sim.H * (0.08 + 0.84 * rnd.nextDouble());
        double[] cam = new double[3], j = new double[3];
        sim.truth.unproject(x, y, cam);
        double[] c = SatSessionTest.norm(cam);
        Rot.apply(sim.cameraToJ2000(f), c[0], c[1], c[2], j);
        return SatSessionTest.norm(j);
    }

    /** Straight up (J2000) t seconds into the session, in the sim's own turning sky. */
    static double[] zenith(SkySim sim, double t) {
        double[] m = sim.j2000ToEnu(t);
        return new double[]{m[6], m[7], m[8]};
    }

    static double[] zenithJ2000(SatSky.Moment mo) {
        double[] z = new double[3];
        mo.toJ2000(new double[]{0, 0, 1}, z);
        return z;
    }

    static double altitude(SkySim sim, double t, double[] j) {
        double[] e = new double[3];
        Rot.apply(sim.j2000ToEnu(t), j[0], j[1], j[2], e);
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, e[2]))));
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    // ------------------------------------------------------------------------------------------------
    // Scoring

    /** Where meteor t crossed the stack (the reference frame's pixels, by the sim's true camera) every 10 ms while it shone; null if off it. */
    static double[][] truthPath(SatSessionTest.Result r, Truth t) {
        if (r.out.referenceUnit < 0) return null;
        SkySim sim = r.sim;
        double[] Rt = Rot.transpose(sim.cameraToJ2000(r.out.referenceUnit));
        List<double[]> pts = new ArrayList<double[]>();
        double[] b = new double[3], px = new double[2];
        for (double dt = 0; dt <= t.m.durationSec + 1e-9; dt += 0.01) {
            double[] j = t.m.at(t.m.startSec + dt);
            Rot.apply(Rt, j[0], j[1], j[2], b);
            if (b[2] < 0.05 || !sim.truth.project(b[0], b[1], b[2], px)) continue;
            if (px[0] < 0 || px[1] < 0 || px[0] >= sim.W || px[1] >= sim.H) continue;
            pts.add(new double[]{px[0], px[1]});
        }
        return pts.size() >= 2 ? pts.toArray(new double[0][]) : null;
    }

    /** The log's record of streak s, or null. */
    static MeteorLog.Meteor logged(SatSessionTest.Result r, SatMatch.Streak s) {
        boolean ev = !Float.isNaN(s.sx0);
        float ax = ev ? s.sx0 : s.x0, ay = ev ? s.sy0 : s.y0, bx = ev ? s.sx1 : s.x1, by = ev ? s.sy1 : s.y1;
        for (MeteorLog.Meteor m : r.out.meteors) {
            if (m.unit != s.unit) continue;
            if (m.x0 == ax && m.y0 == ay && m.x1 == bx && m.y1 == by || m.x0 == bx && m.y0 == by && m.x1 == ax && m.y1 == ay) return m;
        }
        return null;
    }

    /** A streak's light along it, cell by cell, for a failure's message. */
    static String lightProfile(SatMatch.Streak s) {
        if (s.light == null) return "none";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < s.light.length; i++) sb.append(i > 0 ? " " : "").append(Math.round(s.light[i]));
        return sb.append("]").toString();
    }

    /** Where on meteor t's great circle a logged end lies: {degrees along from where it lit up, degrees across}. */
    static double[] onPath(Truth t, double raDeg, double decDeg) {
        double[] p = Astro.vec(raDeg * DEG, decDeg * DEG), n = SatSessionTest.norm(SatSessionTest.cross(t.m.from, t.m.dir));
        return new double[]{Math.toDegrees(Math.atan2(dot(p, t.m.dir), dot(p, t.m.from))), Math.toDegrees(Math.asin(dot(p, n)))};
    }

    static boolean score(String what, SatSessionTest.Result r, List<Truth> truth, boolean keep) {
        System.out.println("-- " + what);
        double degPerPx = Math.toDegrees(1 / r.sim.truth.fx);
        int should = 0, found = 0, called = 0, showerRight = 0, flewRight = 0, members = 0, onTrack = 0, timed = 0, keptRight = 0;
        int magged = 0, magOk = 0;
        double magSum = 0, magSq = 0;
        boolean ok = true;
        for (Truth t : truth) {
            t.path = truthPath(r, t);
            double len = t.path == null ? 0 : SatSessionTest.pathLength(t.path);
            double dens = t.m.peakDensity[t.frame];
            t.detectable = len >= 60 && dens >= SatSessionTest.MIN_DENSITY;
            SatMatch.Streak s = t.path == null ? null : SatSessionTest.streakOn(r.out.streaks, t.frame, t.path);
            t.streak = s;
            t.logged = s == null ? null : logged(r, s);
            MeteorLog.Meteor m = t.logged;
            if (!t.detectable && s == null) {
                System.out.printf(Locale.US, "  %-14s frame %2d %4.0f px, density %6.1f: too faint or short to find%n", t.name, t.frame, len, dens);
                continue;
            }
            List<String> wrong = new ArrayList<String>();
            if (t.detectable) should++;
            if (s != null && t.detectable) found++;
            boolean isMeteor = s != null && "meteor".equals(s.kind) && s.sat == null && m != null;
            if (s == null) wrong.add("NOT FOUND");
            else if (!isMeteor) wrong.add("CALLED " + (s.sat != null ? s.sat.name : s.kind.isEmpty() ? "unknown" : s.kind));
            if (isMeteor) {
                called++;
                boolean right = t.shower == null ? m.attributed && m.shower == null : m.shower == t.shower;
                if (right) showerRight++;
                else wrong.add("SHOWER " + m.what());
                if (t.shower != null) {
                    members++;
                    double a0 = onPath(t, m.ra0, m.dec0)[0], a1 = onPath(t, m.ra1, m.dec1)[0];
                    if (m.flew && a0 < a1) flewRight++;
                    else wrong.add(m.flew ? "BACKWARDS" : "DIRECTION UNKNOWN");
                }
                // both ends on its path: within 4 px across it, and within it give or take 8 px (two of the cells the
                // streak's ends are found to)
                double full = t.m.speedDegS * t.m.durationSec, across = 4 * degPerPx, along = 8 * degPerPx;
                double[] p0 = onPath(t, m.ra0, m.dec0), p1 = onPath(t, m.ra1, m.dec1);
                boolean on = Math.abs(p0[1]) <= across && Math.abs(p1[1]) <= across && Math.min(p0[0], p1[0]) >= -along
                        && Math.max(p0[0], p1[0]) <= full + along;
                if (on) onTrack++;
                else wrong.add(String.format(Locale.US, "OFF PATH (%.2f, %.2f) (%.2f, %.2f) deg; streak (%.0f,%.0f)-(%.0f,%.0f), evidence "
                                + "(%.0f,%.0f)-(%.0f,%.0f), truth (%.0f,%.0f)-(%.0f,%.0f), reference unit %d", p0[0], p0[1], p1[0], p1[1],
                        s.x0, s.y0, s.x1, s.y1, s.sx0, s.sy0, s.sx1, s.sy1, t.path[0][0], t.path[0][1], t.path[t.path.length - 1][0],
                        t.path[t.path.length - 1][1], r.out.referenceUnit) + "; light " + lightProfile(s));
                // its frame's times around it (the phone's clock is 0.3 s ahead)
                double lit = r.sim.startUnixMs + t.m.startSec * 1000, out = lit + t.m.durationSec * 1000;
                double skew = SatSessionTest.CLOCK_ERR_S * 1000;
                if (m.fromMs - skew <= lit + 50 && out <= m.toMs - skew + 50) timed++;
                else wrong.add(String.format(Locale.US, "TIME %.0f-%.0f ms vs %.0f-%.0f", m.fromMs - skew - r.sim.startUnixMs,
                        m.toMs - skew - r.sim.startUnixMs, lit - r.sim.startUnixMs, out - r.sim.startUnixMs));
                if (m.kept == keep) keptRight++;
                else wrong.add(m.kept ? "KEPT" : "NOT KEPT");
                if (!Double.isNaN(m.mag)) {
                    magged++;
                    double err = m.mag - t.m.peakMag;
                    magSum += err;
                    magSq += err * err;
                    if (Math.abs(err) <= (t.shower != null ? 1.0 : 1.5)) magOk++;
                    else wrong.add(String.format(Locale.US, "MAG %+.1f", err));
                } else {
                    wrong.add("NO MAG");
                }
            }
            // a faint one found or not is fine; whatever is found must be right
            boolean bad = t.detectable ? !wrong.isEmpty() : s != null && !wrong.isEmpty() && !wrong.get(0).equals("NOT FOUND");
            ok &= !bad;
            System.out.printf(Locale.US, "  %-14s frame %2d %4.0f px, density %6.1f, mag %+.1f: %s%s%n", t.name, t.frame, len, dens, t.m.peakMag,
                    m == null ? (s == null ? "-" : "streak " + s.kind) : String.format(Locale.US, "%s, %.1f deg, mag %+.1f at %.1f deg/s%s",
                            m.what(), m.lengthDeg, m.mag, m.speedDegS, m.flew ? ", flew " + m.where() : ""),
                    wrong.isEmpty() ? "" : "  " + String.join(", ", wrong));
        }
        // nothing else called a meteor
        int falseMeteors = 0;
        for (MeteorLog.Meteor m : r.out.meteors) {
            boolean ours = false;
            for (Truth t : truth) ours |= t.logged == m;
            if (!ours) {
                falseMeteors++;
                System.out.printf(Locale.US, "  unit %2d: %s at (%.0f,%.0f)-(%.0f,%.0f) is no meteor drawn  FALSE METEOR%n", m.unit, m.what(),
                        m.x0, m.y0, m.x1, m.y1);
            }
        }
        double bias = magged > 0 ? magSum / magged : Double.NaN, rms = magged > 0 ? Math.sqrt(magSq / magged) : Double.NaN;
        System.out.printf(Locale.US, "meteors: %d of %d found, %d called meteors, shower right for %d, %d of %d Geminids' direction, "
                        + "%d on their paths, %d timed, %d kept as asked, magnitudes %d of %d within reach (bias %+.2f, rms %.2f), "
                        + "%d false%n", found, should, called, showerRight, flewRight, members, onTrack, timed, keptRight, magOk, magged,
                bias, rms, falseMeteors);
        System.out.println("streaks: " + SatMatch.describe(r.out.streaks));
        ok &= should >= 6 && found == should && falseMeteors == 0 && Math.abs(bias) < 0.4 && rms < 0.7;
        System.out.println("== meteors, " + what + ": " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** By default each meteor found is gone from the picture; with Keep meteors it stays. */
    static boolean pictures(SatSessionTest.Result def, SatSessionTest.Result keep, SatSessionTest.Result clean, List<Truth> truth) {
        System.out.println("-- pictures");
        if (def.out.referenceUnit != clean.out.referenceUnit || keep.out.referenceUnit != clean.out.referenceUnit
                || def.out.image.width != clean.out.image.width || keep.out.image.width != clean.out.image.width) {
            System.out.println("the three stacks aren't on the same grid");
            return false;
        }
        boolean ok = true;
        int measured = 0;
        for (Truth t : truth) {
            if (t.logged == null || t.path == null) continue;
            if (!t.detectable) {
                System.out.printf(Locale.US, "  %-14s too faint to judge%n", t.name);
                continue;
            }
            double[] rd = SatSessionTest.residual(def.out.image, clean.out.image, t.path);
            double[] rk = SatSessionTest.residual(keep.out.image, clean.out.image, t.path);
            if (rd == null || rk == null) continue;
            measured++;
            // kept: plainly there (a meteor's light changes along its trail, so its stretches differ more than a
            // satellite's: twice their standard error is enough)
            boolean left = SatSessionTest.visible(rd), kept = rk[0] > 2 * rk[1] && SatSessionTest.sight(rk) > 1;
            ok &= !left && kept;
            System.out.printf(Locale.US, "  %-14s by default %+7.2f (%+5.1f se, sight %5.2f)%s; kept %+7.2f (%+5.1f se, sight %5.2f)%s%n",
                    t.name, rd[0], rd[0] / rd[1], SatSessionTest.sight(rd), left ? "  TRAIL LEFT" : "",
                    rk[0], rk[0] / rk[1], SatSessionTest.sight(rk), kept ? "" : "  NOT KEPT");
        }
        ok &= measured >= 5;
        System.out.println("== pictures: " + (ok ? "PASS" : "FAIL") + " (" + measured + " trails measured)");
        return ok;
    }

    /** The log's meteor lines, meteors.csv and the report card. */
    static boolean said(SatSessionTest.Result r, File out, boolean keep, List<Truth> truth) throws Exception {
        String what = keep ? "keep" : "default";
        System.out.println("-- log, list and report, " + what);
        int n = r.out.meteors.size(), members = 0, sporadic = 0;
        for (MeteorLog.Meteor m : r.out.meteors) {
            if (m.shower != null) members++;
            else sporadic++;
        }
        boolean ok = n > 0;
        // the log: a line for each
        String log = new String(java.nio.file.Files.readAllBytes(new File(new File(out, what), "session.log").toPath()), "UTF-8");
        int lines = 0;
        for (String l : log.split("\n")) if (l.contains("meteor: unit ")) lines++;
        if (lines != n) System.out.println("  log has " + lines + " meteor lines for " + n + " meteors  WRONG");
        ok &= lines == n;
        // the list: a header and a row each, its shower's code (SPO for sporadic) and whether it was kept
        String csv = r.meteorsCsv == null ? "" : r.meteorsCsv;
        String[] rows = csv.split("\n");
        boolean listOk = rows.length == n + 1 && rows[0].startsWith("unit,utc_start,utc_end,shower,");
        for (int k = 0; listOk && k < n; k++) {
            MeteorLog.Meteor m = r.out.meteors.get(k);
            String[] c = rows[k + 1].split(",", -1);
            listOk = c.length == 22 && c[0].equals(Integer.toString(m.unit)) && c[3].equals(m.shower != null ? m.shower.code : "SPO")
                    && c[17].equals(m.kept ? "1" : "0") && c[1].endsWith("Z");
            if (!listOk) System.out.println("  meteors.csv row " + (k + 1) + ": " + rows[k + 1] + "  WRONG");
        }
        if (rows.length != n + 1) System.out.println("  meteors.csv has " + (rows.length - 1) + " rows for " + n + " meteors  WRONG");
        ok &= listOk;
        // the report card
        ReportCard.Input in = new ReportCard.Input();
        in.out = r.out;
        in.sky = r.sky;
        in.zone = TimeZone.getTimeZone("America/New_York");
        ReportCard rc = ReportCard.build(in);
        System.out.println("  report: " + rc.meteors);
        for (ReportCard.Meteor k : rc.meteorList) System.out.println("    " + k.line());
        System.out.println("  spoken: " + rc.spoken);
        String counts = members > 0 && sporadic > 0 ? n + " meteors: " + members + " Geminids and " + sporadic + " sporadic." : "";
        boolean says = rc.meteors.startsWith(counts) && rc.meteorList.size() == n
                && rc.meteors.contains(" was about magnitude ")
                && (keep ? rc.meteors.endsWith(" All kept in the picture at full brightness, as asked.")
                : rc.meteors.endsWith(" Taken out with the other streaks; Keep meteors, in the settings, leaves them in."))
                && rc.spoken.contains(" Caught " + n + " meteors, " + members + " of them Geminids.")
                && rc.toJson().contains("\"meteors\"") && rc.describe().contains("Meteors: ");
        for (ReportCard.Meteor k : rc.meteorList) says &= k.line().contains(", about magnitude ") && !k.where.isEmpty();
        ok &= says;
        System.out.println("== log, list and report, " + what + ": " + (ok ? "PASS" : "FAIL"));
        return ok;
    }
}
