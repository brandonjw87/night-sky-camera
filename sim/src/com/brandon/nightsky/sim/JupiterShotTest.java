package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.JupiterGeo;
import com.brandon.nightsky.processing.JupiterMeter;
import com.brandon.nightsky.processing.JupiterStack;
import com.brandon.nightsky.processing.MoonFinder;
import com.brandon.nightsky.processing.MoonMeter;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.PointFinder;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.SensorSpec;

import java.io.File;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Galileo's notebook's photo (3.2), off the phone: a simulated telephoto night ({@link JupiterSim}) with a hand
 * holding the phone, a street light, a bright star and hot pixels in the frame. Jupiter found (not the lamp, the star
 * or a hot pixel), followed, its exposure settled from a first guess far off, the lens's focus scored; then its burst
 * and the moons' brighter one stacked and every moon that shows found where it is and named, two stars behind
 * Jupiter not taken for moons and no hot pixel taken for anything. Four nights: all four out (December 30, 2026),
 * Ganymede in Jupiter's shadow (December 6), Io close to Jupiter (December 23) and Io and Europa in one dot (December
 * 22: one named, the other said to be in its light); and one with the phone's tilt unknown (the picture's turn found
 * from the moons alone).
 * <p>
 * Usage: JupiterShotTest [out dir for pictures]
 */
public final class JupiterShotTest {
    static int failed;
    static final double LAT = 39, LON = -77, SCALE = 17;
    public static final long ALL4 = 1798614000000L, DARK = 1796547600000L, IONEAR = 1798012800000L;
    /** 22 December 2026, 05:00 UTC: Io and Europa half Jupiter's radius apart, six out to the east (one dot). */
    public static final long BLEND = 1797915600000L;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    /** The hand: Jupiter's place on the sensor, the frame's turn (degrees) and blur (px), frame by frame. */
    /** The bursts' lengths, as the camera takes them (3 s of Jupiter, 8 s of its moons at 30 a second). */
    static final int MAIN_FRAMES = 90, MOONS_FRAMES = 240;

    /** For probes: the sensor noise's own seed (-1: from the shot's). */
    public static long NOISE_SEED = -1;

    /** For probes: every frame's hand blur the same (px; -1: as a hand). */
    public static double FIXED_BLUR = -1;

    static final class Hand {
        final Random rnd;
        double X, Y, rot, blur;
        int n;

        Hand(long seed, double x, double y) {
            rnd = new Random(seed);
            X = x;
            Y = y;
        }

        void next() {
            double t = n++ / 30.0;
            X += 0.12 * rnd.nextGaussian();
            Y += 0.12 * rnd.nextGaussian();
            rot += 0.012 * rnd.nextGaussian();
            double u = rnd.nextDouble();
            blur = u < 0.6 ? 0.2 + 0.3 * rnd.nextDouble() : u < 0.9 ? 0.6 + 0.4 * rnd.nextDouble() : 1.2 + 0.6 * rnd.nextDouble();
            if (FIXED_BLUR >= 0) blur = FIXED_BLUR;
            jx = X + 0.35 * Math.sin(2 * Math.PI * 9.1 * t + 0.4) + 0.2 * Math.sin(2 * Math.PI * 11.3 * t + 1.3);
            jy = Y + 0.3 * Math.sin(2 * Math.PI * 8.7 * t + 2.1) + 0.25 * Math.sin(2 * Math.PI * 12.1 * t);
        }

        double jx, jy;
    }

    static final class Shot {
        final List<MoonStack.Frame> main = new ArrayList<MoonStack.Frame>(), moons = new ArrayList<MoonStack.Frame>();
        final List<Double> gains = new ArrayList<Double>();
        /** Where Jupiter's middle truly was in each frame (crop px, pixel i's middle at i). */
        final List<double[]> mainTruth = new ArrayList<double[]>(), moonsTruth = new ArrayList<double[]>();
        MoonMeter.Exposure exp, moonsExp;
        int meterSteps;
        double level;
        boolean settled, foundJupiter;
        double finderErr = Double.NaN;
        String lostTo = "";
    }

    /** The whole frame's window (where the search looks) and the window the burst's frames are drawn in. */
    static final int FW = 900, FH = 700, BW = 200;

    static SensorSpec spec() {
        return MoonshotTest.spec();
    }

    /** A night's shot: finding Jupiter, the meter, then both bursts, as the camera does them. */
    static Shot shoot(JupiterSim sim, long seed, boolean tilt, double sensitivity) {
        SensorSpec spec = spec();
        sim.sensitivity = sensitivity;
        Shot shot = new Shot();
        Hand hand = new Hand(seed, 612.3, 287.8);
        MoonMeter.Limits lim = new MoonMeter.Limits();
        lim.minIso = 50;
        lim.maxIso = 6400;
        lim.minNs = 20_000;
        lim.maxNs = 200_000_000L;
        MoonMeter.Exposure e = JupiterMeter.start(sim.view.mag, sim.view.alt, 2.8, SCALE, lim);
        Random rnd = new Random(NOISE_SEED >= 0 ? NOISE_SEED : seed + 1);
        // the whole frame once: Jupiter among a lamp, a bright star and hot pixels
        hand.next();
        short[] whole = sim.frame(FW, FH, 0, 0, hand.jx, hand.jy, hand.rot, hand.blur, e.ns, e.iso, rnd);
        MoonFinder.Spot s = PointFinder.find(ShortBuffer.wrap(whole), FW, FW, FH, spec, null, 0);
        shot.foundJupiter = s.ok && Math.hypot(s.x - hand.jx, s.y - hand.jy) < 1;
        if (s.ok) shot.finderErr = Math.hypot(s.x - hand.jx, s.y - hand.jy);
        if (!shot.foundJupiter) shot.lostTo = s.ok ? String.format(Locale.US, "%.0f,%.0f", s.x, s.y) : "nothing";
        // the meter: the brightest of the last few frames' levels (a blurred frame reads low)
        List<Double> levels = new ArrayList<Double>();
        List<Double> clips = new ArrayList<Double>();
        for (int i = 0; i < 40; i++) {
            Frame f = window(sim, hand, e, rnd, spec);
            if (f.spot == null) continue;
            levels.add(f.spot.level);
            clips.add(f.spot.clipped);
            if (levels.size() < 4) continue;
            double lv = 0, cl = 0;
            for (int k = levels.size() - 4; k < levels.size(); k++) {
                lv = Math.max(lv, levels.get(k));
                cl = Math.max(cl, clips.get(k));
            }
            shot.level = lv;
            if (JupiterMeter.settled(lv, cl)) {
                shot.settled = true;
                break;
            }
            e = JupiterMeter.adjust(e, lv, cl, lim);
            shot.meterSteps++;
            levels.clear();
            clips.clear();
        }
        shot.exp = e;
        // Jupiter's burst, then the moons'
        for (int i = 0; i < MAIN_FRAMES; i++) {
            Frame f = window(sim, hand, e, rnd, spec);
            if (f.frame == null) continue;
            f.frame.upPa = tilt ? sim.upPa - hand.rot : Double.NaN;
            shot.main.add(f.frame);
            shot.mainTruth.add(new double[]{hand.jx - 0.5 - f.frame.x0, hand.jy - 0.5 - f.frame.y0});
        }
        MoonMeter.Exposure m = JupiterMeter.moons(e, lim, 33_333_333L);
        shot.moonsExp = m;
        for (int i = 0; i < MOONS_FRAMES; i++) {
            Frame f = window(sim, hand, m, rnd, spec);
            if (f.frame == null) continue;
            f.frame.upPa = tilt ? sim.upPa - hand.rot : Double.NaN;
            shot.moons.add(f.frame);
            shot.moonsTruth.add(new double[]{hand.jx - 0.5 - f.frame.x0, hand.jy - 0.5 - f.frame.y0});
            shot.gains.add(m.light() / e.light());
        }
        return shot;
    }

    public static double SENS_FOR_PROBE = 1;

    /** For probes: {main frames, their truths, moons frames, their truths, the exposures}. */
    public static Object[] shootForProbe(JupiterSim sim, long seed) {
        Shot s = shoot(sim, seed, true, SENS_FOR_PROBE);
        return new Object[]{s.main, s.mainTruth, s.moons, s.moonsTruth, s.exp, s.moonsExp};
    }

    @SuppressWarnings("unchecked")
    public static JupiterStack.Result stackOfPublic(JupiterSim sim, Object[] probe, boolean tilt) {
        Shot s = new Shot();
        s.main.addAll((List<MoonStack.Frame>) probe[0]);
        s.moons.addAll((List<MoonStack.Frame>) probe[2]);
        s.exp = (MoonMeter.Exposure) probe[4];
        s.moonsExp = (MoonMeter.Exposure) probe[5];
        for (int i = 0; i < s.moons.size(); i++) s.gains.add(s.moonsExp.light() / s.exp.light());
        return stackOf(sim, s, tilt);
    }

    public static SensorSpec specForProbe() {
        return spec();
    }

    static final class Frame {
        MoonFinder.Spot spot;
        MoonStack.Frame frame;
    }

    /** One frame drawn round Jupiter, Jupiter followed in it, and the burst's crop round it (as the camera crops). */
    static Frame window(JupiterSim sim, Hand hand, MoonMeter.Exposure e, Random rnd, SensorSpec spec) {
        hand.next();
        int wx = ((int) Math.round(hand.X) - BW / 2) & ~1, wy = ((int) Math.round(hand.Y) - BW / 2) & ~1;
        short[] win = sim.frame(BW, BW, wx, wy, hand.jx, hand.jy, hand.rot, hand.blur, e.ns, e.iso, rnd);
        MoonFinder.Spot near = new MoonFinder.Spot();
        near.x = hand.X - wx;
        near.y = hand.Y - wy;
        near.ok = true;
        MoonFinder.Spot s = PointFinder.find(ShortBuffer.wrap(win), BW, BW, BW, spec, near, 40);
        Frame f = new Frame();
        if (!s.ok) return f;
        f.spot = s;
        int side = PointFinder.side(sim.rPx);
        int[] c = MoonFinder.corner(s.x, s.y, side, BW, BW);
        short[] crop = MoonFinder.crop(ShortBuffer.wrap(win), BW, BW, BW, c[0], c[1], side, side);
        f.frame = new MoonStack.Frame(crop, side, side, wx + c[0], wy + c[1], 0);
        return f;
    }

    /** The night's sim: the lens, a street light far off, a bright star, two faint stars near Jupiter, hot pixels. */
    public static JupiterSim night(long t, double turn, double[][] stars) {
        JupiterGeo.View v = JupiterGeo.view(t, LAT, LON);
        JupiterSim sim = new JupiterSim(v, SCALE, v.q + turn, 1.6, 0.03);
        sim.air = Math.pow(10, -0.4 * 0.2 * (MoonMeter.airmass(v.alt) - 1));
        sim.lamp = new double[]{150, 560, 4e9};
        sim.addHot(160, FW, FH, 5, 2500);
        // a star of magnitude 1.3 elsewhere in the frame
        sim.addStar(-4200, 2600, 1.3);
        if (stars != null) for (double[] st : stars) sim.addStar(st[0], st[1], st[2]);
        return sim;
    }

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        SensorSpec spec = spec();

        System.out.println("== the finder, the meter, the focus");
        JupiterSim s1 = night(ALL4, 25, new double[][]{{-150, 240, 7.0}, {330, -40, 7.5}});
        long t0 = System.nanoTime();
        Shot a = shoot(s1, 3, true, 2.3);
        double ms = (System.nanoTime() - t0) / 1e6;
        check("Jupiter found in the whole frame, not the street light, the bright star or a hot pixel", a.foundJupiter,
                a.foundJupiter ? String.format(Locale.US, "%.2f px off", a.finderErr) : "took " + a.lostTo);
        check("the exposure settled from a first guess 2.3 times too bright (a sensor 2.3x the textbook)", a.settled && a.meterSteps <= 6,
                String.format(Locale.US, "%d steps, %s, brightest pixel %.2f", a.meterSteps, a.exp, a.level));
        Shot dim = shoot(night(ALL4, 25, null), 4, true, 0.45);
        check("and from 0.45x (a sensor half as sensitive)", dim.settled && dim.meterSteps <= 6,
                String.format(Locale.US, "%d steps, %s, brightest pixel %.2f", dim.meterSteps, dim.exp, dim.level));
        check("the moons' frames 16 times the light, as long as a frame allows", Math.abs(a.moonsExp.light() / a.exp.light() - JupiterMeter.MOONS_GAIN) < 0.5
                && a.moonsExp.ns <= JupiterMeter.LONG_NS, a.moonsExp.toString());
        // focus: sharper with less blur
        double[] blurs = {2.4, 1.6, 1.2, 0.8, 0.4, 0};
        double prev = 0;
        boolean rising = true;
        StringBuilder fs = new StringBuilder();
        for (double b : blurs) {
            Random r = new Random(9);
            int side = PointFinder.side(s1.rPx);
            short[] crop = s1.frame(side, side, 1000, 1000, 1000 + side / 2.0 + 0.3, 1000 + side / 2.0 - 0.2, 0, b, a.exp.ns, a.exp.iso, r);
            double sc = PointFinder.sharpness(crop, side, side, 1000, 1000, spec);
            fs.append(String.format(Locale.US, " %.1f:%.1f", b, sc));
            if (sc <= prev) rising = false;
            prev = sc;
        }
        check("the focus score rises as the blur goes", rising, fs.toString().trim());

        System.out.println("== December 30, 2026: all four out, two faint stars near Jupiter");
        JupiterStack.Result r = stackOf(s1, a, true);
        report(r, s1, a);
        check("Jupiter's burst and the moons' used", r.used >= MAIN_FRAMES * 2 / 3 && r.moonsUsed >= MOONS_FRAMES * 9 / 10, r.used + " and " + r.moonsUsed);
        moonsChecks(r, s1, 4);
        int stars = 0, extra = 0;
        for (JupiterStack.Light l : r.lights) {
            if (l.moon >= 0) continue;
            boolean star = false;
            for (double[] st : s1.stars) {
                double[] p = resultPx(r, s1, st[0], st[1]);
                if (Math.hypot(p[0] - l.x, p[1] - l.y) < 2) star = true;
            }
            if (star) stars++;
            else extra++;
        }
        check("the two faint stars found and not called moons", stars == 2, stars + " found");
        check("nothing else taken for a light (no hot pixel, no glow)", extra == 0, extra + " more");
        check("the fit's turn and scale: what the tilt said", Math.abs(r.turn) < 1 && Math.abs(r.scale - 1) < 0.02,
                String.format(Locale.US, "%+.2f degrees, %.3f", r.turn, r.scale));
        check("stacked quickly", ms < 60_000, String.format(Locale.US, "%.0f ms for the whole shot", ms));
        if (out != null) png(new File(out, "jupiter_all4.png"), r);

        System.out.println("== December 6: Ganymede in Jupiter's shadow");
        JupiterSim s2 = night(DARK, -40, null);
        Shot b = shoot(s2, 5, true, 1);
        JupiterStack.Result r2 = stackOf(s2, b, true);
        report(r2, s2, b);
        check("Ganymede: in Jupiter's shadow", r2.status[2] == JupiterStack.DARK, JupiterStack.statusWord(r2.status[2]));
        moonsChecks(r2, s2, 3);

        System.out.println("== December 23: Io three of Jupiter's radii out");
        JupiterSim s3 = night(IONEAR, 70, null);
        Shot c = shoot(s3, 6, true, 1);
        JupiterStack.Result r3 = stackOf(s3, c, true);
        report(r3, s3, c);
        // this close the simulated lens (deliberately dimmer than the phone's) leaves Io under its glare's noise:
        // found where it is, or put down to the glare; never a light elsewhere called Io, never just "not seen"
        boolean ioOk = r3.status[0] == JupiterStack.GLARE
                || (r3.status[0] == JupiterStack.FOUND && Math.hypot(r3.moon[0].jx - s3.view.moons[0].x, r3.moon[0].jy - s3.view.moons[0].y) < 0.5);
        check("Io, close to Jupiter: found where it is, or lost in its glare", ioOk, JupiterStack.statusWord(r3.status[0])
                + (Double.isNaN(r3.expectSnr[0]) ? "" : String.format(Locale.US, ", it would show %.1f times the noise", r3.expectSnr[0])));
        moonsChecks(r3, s3, 4);

        System.out.println("== December 30 again, the tilt unknown");
        Shot d = shoot(night(ALL4, 115, null), 7, false, 1);
        JupiterSim s4 = night(ALL4, 115, null);
        JupiterStack.Result r4 = stackOf(s4, d, false);
        report(r4, s4, d);
        // unturned, the result's up is the frames' own: the fit's turn must be the frames' turn from Jupiter's pole
        double want = MoonGeoNorm(s4.view.poleDeg - s4.upPa);
        check("the turn found from the moons alone", Math.abs(MoonGeoNorm(r4.turn - want)) < 2, String.format(Locale.US, "%+.1f, the truth %+.1f", r4.turn, want));
        int named = 0;
        for (int k = 0; k < 4; k++) if (r4.status[k] == JupiterStack.FOUND) named++;
        check("and all four named", named == 4, named + "");

        System.out.println("== December 22: Io and Europa in one dot");
        JupiterSim s5 = night(BLEND, -15, null);
        Shot e = shoot(s5, 8, true, 1);
        JupiterStack.Result r5 = stackOf(s5, e, true);
        report(r5, s5, e);
        // half a radius apart, under the blur's width: one light, named for one of them, the other said to be in it
        int io = r5.status[0], eu = r5.status[1];
        boolean pair = (io == JupiterStack.FOUND && eu == JupiterStack.BLENDED && r5.with[1] == 0)
                || (eu == JupiterStack.FOUND && io == JupiterStack.BLENDED && r5.with[0] == 1);
        check("Io and Europa: one dot, named for one, the other said to be in it", pair,
                JupiterStack.statusWord(io) + ", " + JupiterStack.statusWord(eu));
        moonsChecks(r5, s5, 4);
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    static double MoonGeoNorm(double d) {
        return com.brandon.nightsky.processing.MoonGeo.norm180(d);
    }

    static JupiterStack.Result stackOf(JupiterSim sim, Shot s, boolean tilt) {
        JupiterStack.Options o = new JupiterStack.Options();
        o.arcsecPerPx = SCALE;
        o.view = sim.view;
        o.upPa = tilt ? sim.upPa : Double.NaN;
        long t0 = System.nanoTime();
        JupiterStack.Result r = JupiterStack.stack(s.main, s.moons, s.gains, s.moonsExp.light() / s.exp.light(), spec(), o, Progress.NONE);
        System.out.println(String.format(Locale.US, "  stacked in %.0f ms", (System.nanoTime() - t0) / 1e6));
        return r;
    }

    static void report(JupiterStack.Result r, JupiterSim sim, Shot s) {
        System.out.println("  " + r.log.replace("\n", "\n  "));
        System.out.println(String.format(Locale.US, "  exposure %s, moons %s; truth:", s.exp, s.moonsExp));
        for (int k = 0; k < 4; k++) {
            JupiterGeo.Moon m = sim.view.moons[k];
            System.out.println(String.format(Locale.US, "    %s x %+.2f y %+.2f mag %.2f %s", m.name, m.x, m.y, m.mag, m.showing() ? "" : "(not showing)"));
        }
    }

    /**
     * Every moon that shows (and isn't in the glare) found where it is and named, its place to a fifth of Jupiter's
     * radius or as well as its brightness allows.
     */
    static void moonsChecks(JupiterStack.Result r, JupiterSim sim, int expect) {
        int found = 0, glare = 0;
        double worst = 0;
        boolean names = true;
        for (int k = 0; k < 4; k++) {
            JupiterGeo.Moon m = sim.view.moons[k];
            if (!m.showing() || m.dist() < JupiterStack.GLARE_RJ + 0.3) continue;
            // close to Jupiter a faint moon may honestly be lost in its glare (never "not seen", never misplaced)
            if (r.status[k] == JupiterStack.GLARE && m.dist() < 5) {
                glare++;
                continue;
            }
            // right beside another (closer than the blur can split): in that one's light
            if (r.status[k] == JupiterStack.BLENDED) {
                JupiterGeo.Moon o = sim.view.moons[r.with[k]];
                if (Math.hypot(m.x - o.x, m.y - o.y) * r.rJ < JupiterStack.BLEND_SIGMAS * (r.psf > 0 ? r.psf : 2.2) + 0.5) found++;
                else names = false;
                continue;
            }
            if (r.status[k] != JupiterStack.FOUND) {
                names = false;
                continue;
            }
            found++;
            JupiterStack.Light l = r.moon[k];
            // its place to a fifth of Jupiter's radius, or as well as its brightness allows (the blur's width
            // over its signal to noise, two and a half times)
            double allow = Math.max(0.2, 2.5 * 1.4 * r.psf / Math.max(1, l.snr) / r.rJ);
            // close to Jupiter what's left of its glare leans on a faint moon's light (a third of a radius more)
            if (m.dist() < 6) allow += 0.35;
            // a dot with another moon in it: anywhere between the two
            for (int b = 0; b < 4; b++) {
                if (r.status[b] != JupiterStack.BLENDED || r.with[b] != k) continue;
                JupiterGeo.Moon o = sim.view.moons[b];
                allow = Math.max(allow, Math.hypot(m.x - o.x, m.y - o.y) + 0.2);
            }
            worst = Math.max(worst, Math.hypot(l.jx - m.x, l.jy - m.y) / allow * 0.2);
            if (l.moon != k) names = false;
        }
        check(expect + " moons found and named (or close in, lost in Jupiter's glare)", found + glare == expect && names,
                found + " found" + (glare > 0 ? ", " + glare + " lost in the glare" : ""));
        check("each where it is", worst < 0.2, String.format(Locale.US, "%.3f of Jupiter's radius off at worst, for its brightness", worst));
    }

    /** A sky offset (arcseconds east, north) in the result's pixels, for the result turned Jupiter's pole up. */
    public static double[] resultPx(JupiterStack.Result r, JupiterSim sim, double dxPic, double dyPic) {
        // the star's picture offset (at the sim's up) to east and north, then to the result (pole up), times S
        double a = Math.toRadians(sim.upPa);
        double east = dxPic * sim.arcsecPerPx * -Math.cos(a) + -dyPic * sim.arcsecPerPx * Math.sin(a);
        double north = dxPic * sim.arcsecPerPx * Math.sin(a) + -dyPic * sim.arcsecPerPx * Math.cos(a);
        double[] p = JupiterGeo.inPicture(east, north, sim.view.poleDeg, sim.arcsecPerPx / JupiterStack.S);
        return new double[]{r.cx + p[0], r.cy + p[1]};
    }

    /** ARGB pixels to a PNG. */
    static void write(File f, int[] argb, int w, int h) throws Exception {
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        im.setRGB(0, 0, w, h, argb, 0, w);
        javax.imageio.ImageIO.write(im, "png", f);
    }

    static void png(File f, JupiterStack.Result r) throws Exception {
        int W = r.w;
        java.awt.image.BufferedImage im = new java.awt.image.BufferedImage(W * 4, W * 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        double k = 1 / Math.max(1e-6, r.peak);
        for (int j = 0; j < W * 4; j++) {
            for (int i = 0; i < W * 4; i++) {
                int q = (j / 4) * W + i / 4;
                int R = tone(r.r[q] * k), G = tone(r.g[q] * k), B = tone(r.b[q] * k);
                im.setRGB(i, j, (R << 16) | (G << 8) | B);
            }
        }
        javax.imageio.ImageIO.write(im, "png", f);
        System.out.println("  saved " + f);
    }

    static int tone(double v) {
        double a = 0.0015;
        double y = Math.log(1 + Math.max(0, v) / a) / Math.log(1 + 1 / a);
        return (int) Math.max(0, Math.min(255, Math.round(255 * y)));
    }
}
