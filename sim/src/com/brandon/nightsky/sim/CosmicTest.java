package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.CosmicRays;
import com.brandon.nightsky.processing.FrameStore;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * Cosmic Ray Hunter (2.6): particles put into a simulated night's frames (spots, straight tracks at angles, curly worms,
 * from barely over the sky's noise to bright, and three tracks passing right by hot pixels), on a night that also has
 * what must not be taken for one (a satellite, a plane's blinking lights, a meteor, clouds, a knock, hot pixels,
 * thousands of stars drifting). Found again through the
 * same classes the app uses after developing: how many of them (those clearly over the noise), what was taken for one
 * that wasn't (none, on the same night without particles too), their shapes and light, and the picture (a PNG when an
 * output folder is given).
 * <p>
 * Usage: CosmicTest [outDir]; -Dcosmic.seed=N picks other particles (default 2026), -Dcosmic.sim=N another night (42).
 */
public final class CosmicTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    /** What was put in: where, what kind, how long, how much light, and how far over the noise its brightest pixel was. */
    static final class Truth {
        int frame;
        double x, y, length, levels, peak;
        CosmicRays.Kind kind;
        /** On a star at least a third as bright as itself in that frame (its noise can hide it). */
        boolean onStar;
        /** The brightest star's light under it (levels), for the report. */
        double starUnder;
        /** Put right by a hot pixel (that pixel's index; -1: not). */
        int byHot = -1;
        /** Its path's ends. */
        double[] from, to;
    }

    public static void main(String[] args) throws Exception {
        File out = args.length > 0 ? new File(args[0]) : null;
        if (out != null) out.mkdirs();
        File work = new File(out != null ? out : new File(System.getProperty("java.io.tmpdir")), "cosmic-work-" + System.nanoTime());
        System.out.println("== Cosmic Ray Hunter");
        int scale = 2, frames = 16;
        List<Truth> truth = new ArrayList<Truth>();
        Night with = night(new File(work, "with"), scale, frames, truth);
        CosmicRays.Result r = with.result;
        System.out.println("  " + r.summary());
        // ---- found again: those clearly over the noise (12 sigma), not in the knocked frame or on a streak
        int clear = 0, found = 0, kindsRight = 0, tracks = 0, tracksRight = 0;
        double sigma = with.noise;
        List<CosmicRays.Hit> matched = new ArrayList<CosmicRays.Hit>();
        for (Truth t : truth) {
            // not in the knocked frame, on a star as bright, or by a streak (all left out on purpose)
            if (t.frame == with.bump || t.peak < 12 * sigma || t.onStar || nearStreak(with, t)) continue;
            clear++;
            CosmicRays.Hit h = match(r.hits, t);
            if (h == null) {
                System.out.printf(Locale.US, "    missed: frame %d (%.0f, %.0f) %s %.0f px, peak %.1f sigma, %.0f levels, star under it %.0f%n", t.frame,
                        t.x, t.y, t.kind.one, t.length, t.peak / sigma, t.levels, t.starUnder);
                continue;
            }
            matched.add(h);
            found++;
            if (h.kind == t.kind || (t.kind == CosmicRays.Kind.WORM && h.kind == CosmicRays.Kind.TRACK && t.length < 10)) kindsRight++;
            if (t.kind == CosmicRays.Kind.TRACK && t.length >= 8) {
                tracks++;
                if (h.kind == CosmicRays.Kind.TRACK) tracksRight++;
            }
        }
        check("the particles clearly over the noise found again", found >= 0.9 * clear, found + " of " + clear);
        // a hot pixel lights the same photosite in every frame: beside a particle it looked like a star in the frames
        // either side, and the particle was left out (seen on a robo night, 2.6); now the map's defects are mended first
        int byHot = 0, byHotFound = 0, byHotMapped = 0;
        StringBuilder byHotMissed = new StringBuilder();
        for (Truth t : truth) {
            if (t.byHot < 0) continue;
            byHot++;
            if (with.hot != null && with.hot.isBad(t.byHot % with.W, t.byHot / with.W)) byHotMapped++;
            CosmicRays.Hit bh = match(r.hits, t);
            System.out.printf(Locale.US, "    by a hot pixel at (%d, %d): frame %d (%.0f, %.0f), %.0f levels put in, %s%n", t.byHot % with.W, t.byHot / with.W,
                    t.frame, t.x, t.y, t.levels, bh == null ? "missed" : String.format(Locale.US, "found %.0f (%s, %d px)", bh.levels, bh.kind.one, bh.pixels));
            if (bh != null) byHotFound++;
            else byHotMissed.append(String.format(Locale.US, "; missed frame %d (%.0f, %.0f) by (%d, %d)", t.frame, t.x, t.y,
                    t.byHot % with.W, t.byHot / with.W));
        }
        check("tracks by and across hot pixels found (the pixels mended, not taken for stars)", byHot == 4 && byHotMapped == 4 && byHotFound == 4,
                byHotFound + " of " + byHot + " found, their hot pixels " + byHotMapped + " of " + byHot + " in the map" + byHotMissed);
        int fakes = 0;
        for (CosmicRays.Hit h : r.hits) {
            if (matched.contains(h)) continue;
            boolean faint = false;
            for (Truth t : truth) if (t.frame == indexOf(h.unit) && Math.hypot(t.x - h.x, t.y - h.y) < t.length / 2 + 4) faint = true;
            if (!faint) {
                fakes++;
                System.out.printf(Locale.US, "    not a particle: unit %d (%.0f, %.0f) %s %d px, %.1f sigma%n", h.unit, h.x, h.y, h.kind.one, h.pixels, h.sigma);
            }
        }
        check("nothing else taken for one (stars, the satellite, the plane, the meteor, clouds, hot pixels)", fakes == 0, fakes + " not particles");
        // one particle found as two pieces (a gap along its track under the noise) was counted twice, and two pieces side
        // by side looked like "three in a line" with any hit past them (seen on a test night, 2.6); now they're joined
        int pieces = 0;
        for (int i = 0; i < r.hits.size(); i++) {
            for (int j = i + 1; j < r.hits.size(); j++) {
                CosmicRays.Hit a = r.hits.get(i), b = r.hits.get(j);
                if (a.unit == b.unit && Math.hypot(a.x - b.x, a.y - b.y) < (a.length + b.length) / 2 + 3) {
                    // one particle, or two put close together?
                    StringBuilder near = new StringBuilder();
                    int put = 0;
                    for (Truth t : truth) {
                        if (t.frame != indexOf(a.unit)) continue;
                        if (Math.min(Math.hypot(t.x - a.x, t.y - a.y), Math.hypot(t.x - b.x, t.y - b.y)) < t.length / 2 + 6) {
                            put++;
                            near.append(String.format(Locale.US, " (%.0f, %.0f) %s %.0f px", t.x, t.y, t.kind.one, t.length));
                        }
                    }
                    if (put < 2) pieces++;
                    System.out.printf(Locale.US, "    in pieces?: unit %d (%.0f, %.0f) %s %d px and (%.0f, %.0f) %s %d px; put in there:%s%n", a.unit, a.x, a.y,
                            a.kind.one, a.pixels, b.x, b.y, b.kind.one, b.pixels, near);
                }
            }
        }
        check("no particle counted twice (found in two pieces)", pieces == 0, pieces + " found in two pieces");
        check("long tracks told from spots and worms", tracks == 0 || tracksRight >= 0.8 * tracks, tracksRight + " of " + tracks);
        check("their shapes mostly right", kindsRight >= 0.75 * found, kindsRight + " of " + found);
        // their light: what was put in, within the noise of the pixels round it
        double worst = 0;
        String worstOne = "";
        for (Truth t : truth) {
            CosmicRays.Hit h = match(r.hits, t);
            if (h == null || t.peak < 20 * sigma || t.onStar) continue;
            double off = Math.abs(h.levels - t.levels) / t.levels;
            if (off > worst) {
                worst = off;
                worstOne = String.format(Locale.US, ": frame %d (%.0f, %.0f) %s, %.0f levels put in, %.0f found", t.frame, t.x, t.y, t.kind.one, t.levels, h.levels);
            }
        }
        check("their light measured", worst < 0.35, String.format(Locale.US, "%.0f%% off at most%s", worst * 100, worst >= 0.35 ? worstOne : ""));
        check("the electrons and energy from the noise profile", !r.hits.isEmpty() && !Double.isNaN(r.hits.get(0).keV)
                && Math.abs(r.hits.get(0).electrons - r.hits.get(0).levels * with.epl) < 1e-6, "");
        check("muons expected for the chip and the time", Math.abs(r.expected - r.cm2 * r.seconds / 60) < 1e-9 && r.units == frames - 1,
                String.format(Locale.US, "%.1f in %.0f s over %d frames", r.expected, r.seconds, r.units));

        // ---- the same night, no particles: nothing found
        Night none = night(new File(work, "none"), scale, frames, null);
        System.out.println("  " + none.result.summary());
        check("on a night with no particles, none found", none.result.hits.isEmpty(), none.result.hits.size() + " found");

        // ---- the picture
        if (out != null) {
            int w = 1600, h = 1600;
            CosmicRays.Layout l = CosmicRays.layout(w, h, r, with.W, with.H);
            int[] px = CosmicRays.background(l, r, with.W, with.H);
            AwtPainter p = new AwtPainter(px, w, h);
            CosmicRays.paint(p, l, r, with.W, with.H, 1792036800000L, java.util.TimeZone.getTimeZone("America/New_York"));
            PhotoVideoTest.writePng(p.pixels(), w, h, new File(out, "cosmic.png"));
            System.out.println("  picture: " + new File(out, "cosmic.png"));
        }
        deleteTree(work);
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    /** Unit indices are the frame numbers here (one frame a unit). */
    static int indexOf(int unit) {
        return unit;
    }

    /** By one of the streaks the stack found in its frame (the hunter leaves those out: a satellite's glint looks the same). */
    static boolean nearStreak(Night n, Truth t) {
        for (CosmicRays.Unit u : n.units) {
            if (u.index != t.frame) continue;
            for (float[] st : u.streaks) {
                double dx = st[2] - st[0], dy = st[3] - st[1], l2 = dx * dx + dy * dy, l = Math.sqrt(l2);
                double k = l2 > 0 ? ((t.x - st[0]) * dx + (t.y - st[1]) * dy) / l2 : 0, pad = l > 0 ? 30 / l : 0;
                k = Math.max(-pad, Math.min(1 + pad, k));
                if (Math.hypot(st[0] + k * dx - t.x, st[1] + k * dy - t.y) < st[4] + 8 + t.length / 2) return true;
            }
        }
        return false;
    }

    static CosmicRays.Hit match(List<CosmicRays.Hit> hits, Truth t) {
        CosmicRays.Hit best = null;
        double bd = Double.MAX_VALUE;
        for (CosmicRays.Hit h : hits) {
            if (indexOf(h.unit) != t.frame) continue;
            double d = Math.hypot(h.x - t.x, h.y - t.y);
            if (d < Math.max(3, t.length / 2 + 2) && d < bd) {
                bd = d;
                best = h;
            }
        }
        return best;
    }

    /** A night simulated, developed and searched. */
    static final class Night {
        CosmicRays.Result result;
        List<CosmicRays.Unit> units;
        com.brandon.nightsky.processing.HotPixels hot;
        int W, H, bump;
        double noise, epl;
    }

    static Night night(File dir, int scale, int frames, List<Truth> truth) throws Exception {
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel lens = CameraModel.nominal(W, H, 2875.0 / scale);
        lens.cx = W / 2.0 + 14.0 / scale;
        lens.cy = H / 2.0 - 9.0 / scale;
        lens.k1 = -0.045;
        lens.k2 = 0.018;
        lens.source = "truth";
        SkySim sim = new SkySim(W, H, lens, Long.getLong("cosmic.sim", 42), null, SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        sim.psfSigma = 0.85 / Math.sqrt(scale);
        // what must not be taken for a particle
        sim.satelliteFrame = frames / 4;
        sim.planeFrame = frames / 2;
        sim.meteorFrame = (frames * 2) / 3;
        sim.cloudFrom = frames / 3;
        sim.cloudTo = frames / 3 + 1;
        sim.bumpFrame = frames - 4;
        Night n = new Night();
        n.W = W;
        n.H = H;
        n.bump = sim.bumpFrame;
        // the sky's noise in a frame (levels): its glow and dark current, and the read noise
        n.noise = Math.sqrt(sim.readNoise * sim.readNoise + (0.75 * 1.0 + sim.darkRate) * sim.frameExp);
        if (truth != null) particles(sim, frames, W, H, n.noise, truth);
        SensorSpec spec = sim.spec(false);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        // the noise profile, as the camera gives one: variance = S x + O on levels over the range (electrons a level: 1/(S range))
        n.epl = sim.electronsPerDn;
        for (int q = 0; q < 4; q++) {
            spec.noiseS[q] = (float) (1 / (n.epl * spec.range()));
            spec.noiseO[q] = (float) (sim.readNoise * sim.readNoise / (spec.range() * spec.range()));
        }
        spec.pixelPitchUm = 2.4f * scale;
        dir.mkdirs();
        SessionStore store = SessionStore.create(dir);
        UnitProcessor up = new UnitProcessor(store, spec, 1, 3, new UnitProcessor.Listener() {
            @Override
            public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {}

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
        });
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            sim.render(i, buf, info, 7);
            up.submit(buf, info);
        }
        up.finish();
        PostProcessor pp = new PostProcessor(store, spec);
        PostProcessor.Output o = pp.run(false, Progress.NONE);
        final FrameStore fs = store.frames(W, H);
        List<CosmicRays.Unit> units = CosmicRays.units(store, store.readUnits(), o);
        n.units = units;
        n.hot = o.hot;
        for (CosmicRays.Unit u : units) {
            for (float[] s : u.streaks) {
                System.out.printf(Locale.US, "    unit %d streak (%.0f, %.0f)-(%.0f, %.0f) +-%.0f%n", u.index, s[0], s[1], s[2], s[3], s[4]);
            }
        }
        long t0 = System.currentTimeMillis();
        n.result = CosmicRays.find(new CosmicRays.Frames() {
            @Override
            public void readBand(int index, int y0, int y1, short[] dst) throws IOException {
                fs.readBand(index, y0, y1, dst);
            }
        }, W, H, spec.cfa, units, o.hot, CosmicRays.Stack.of(o), n.epl, CosmicRays.area(W, H, spec.pixelPitchUm), Progress.NONE);
        System.out.printf(Locale.US, "  searched %d frames of %dx%d in %d ms%n", n.result.units, W, H, System.currentTimeMillis() - t0);
        return n;
    }

    /**
     * Particles in every frame: two or three each, a spot (straight down), a track (at an angle), or a worm (an electron
     * wandering); the light spread a little as the chip's charge spreads; from 10 to 150 times the noise in all.
     */
    static void particles(SkySim sim, int frames, int W, int H, double noise, List<Truth> truth) {
        double[][][] stars = new double[frames][][];
        for (int f = 0; f < frames; f++) stars[f] = sim.starTable(f);
        SplittableRandom rnd = new SplittableRandom(Long.getLong("cosmic.seed", 2026));
        for (int f = 0; f < frames; f++) {
            int count = 2 + rnd.nextInt(2);
            for (int c = 0; c < count; c++) {
                double kindRoll = rnd.nextDouble();
                CosmicRays.Kind kind = kindRoll < 0.4 ? CosmicRays.Kind.SPOT : kindRoll < 0.8 ? CosmicRays.Kind.TRACK : CosmicRays.Kind.WORM;
                double total = noise * Math.exp(Math.log(10) + rnd.nextDouble() * (Math.log(150) - Math.log(10))) * (kind == CosmicRays.Kind.SPOT ? 1 : 3);
                double x = 30 + rnd.nextDouble() * (W - 60), y = 30 + rnd.nextDouble() * (H - 60);
                List<double[]> path = new ArrayList<double[]>();
                if (kind == CosmicRays.Kind.SPOT) {
                    path.add(new double[]{x, y, 1});
                } else if (kind == CosmicRays.Kind.TRACK) {
                    double len = 6 + rnd.nextDouble() * 24, a = rnd.nextDouble() * Math.PI;
                    for (double s = -len / 2; s <= len / 2; s += 0.25) path.add(new double[]{x + s * Math.cos(a), y + s * Math.sin(a), 1});
                } else {
                    double a = rnd.nextDouble() * 2 * Math.PI, px = x, py = y;
                    int steps = 24 + rnd.nextInt(24);
                    for (int k = 0; k < steps; k++) {
                        a += 0.45 * gauss(rnd);
                        px += 0.5 * Math.cos(a);
                        py += 0.5 * Math.sin(a);
                        // an electron gives up more as it slows
                        path.add(new double[]{px, py, 1 + 2.0 * k / steps});
                    }
                }
                put(sim, f, kind, total, path, W, stars, truth);
            }
        }
        byHotPixels(sim, frames, W, H, noise, stars, truth);
    }

    /**
     * Four bright short tracks by hot pixels (lit in every frame, so in the hot pixel map), in ordinary frames (none of
     * the satellite's, plane's, meteor's, clouds' or knock's): three passing a pixel and a half from one of the night's
     * hottest pixels, every pixel of them within reach of its light in the frames beside, and one straight across a warm
     * pixel (one that doesn't clip, so the particle's light on it can be measured).
     */
    static void byHotPixels(SkySim sim, int frames, int W, int H, double noise, double[][][] stars, List<Truth> truth) {
        int[] idx = sim.hotPixelIndices();
        float[] rate = sim.hotPixelRates();
        int[] when = {2, 7, frames - 3, frames - 2};
        double[] angle = {70, 115, 25, 160}, off = {1.5, 1.5, 0, 1.5}, minRate = {150, 150, 25, 150}, maxRate = {1e9, 1e9, 60, 1e9};
        boolean[] used = new boolean[idx.length];
        for (int k = 0; k < when.length; k++) {
            for (int h = 0; h < idx.length; h++) {
                int hx = idx[h] % W, hy = idx[h] / W;
                if (used[h] || rate[h] < minRate[k] || rate[h] > maxRate[k] || hx < 60 || hy < 60 || hx >= W - 60 || hy >= H - 60) continue;
                // clear of stars a frame shows and of the other hot pixels, so the hot pixel is the only thing beside the track
                boolean clear = true;
                for (double[] st : stars[when[k]]) {
                    if (Math.hypot(st[0] - hx, st[1] - hy) < 10 && st[5] / sim.electronsPerDn > 8 * noise) clear = false;
                }
                for (int j : idx) if (j != idx[h] && Math.abs(j % W - hx) <= 8 && Math.abs(j / W - hy) <= 8) clear = false;
                // nor in a line with two of that frame's other particles (the hunter leaves three in a line out: a plane's
                // blinking lights), which this test's many particles a frame make happen by chance
                List<Truth> same = new ArrayList<Truth>();
                for (Truth t : truth) if (t.frame == when[k]) same.add(t);
                // (pieces of one long track count as hits in a line too)
                for (Truth t : same) if (t.length >= 4 && offLine(t.from, t.to, new double[]{hx, hy}) < 8) clear = false;
                for (int i = 0; i < same.size() && clear; i++) {
                    for (int j = i + 1; j < same.size() && clear; j++) {
                        double[][] p3 = {{hx, hy}, {same.get(i).x, same.get(i).y}, {same.get(j).x, same.get(j).y}};
                        for (int c = 0; c < 3; c++) if (offLine(p3[(c + 1) % 3], p3[(c + 2) % 3], p3[c]) < 8) clear = false;
                    }
                }
                if (!clear) continue;
                // 7 pixels long, its middle off the hot pixel across the track's direction
                double a = Math.toRadians(angle[k]), mx = hx + 0.5 - off[k] * Math.sin(a), my = hy + 0.5 + off[k] * Math.cos(a);
                List<double[]> path = new ArrayList<double[]>();
                for (double t = -3; t <= 3; t += 0.25) path.add(new double[]{mx + t * Math.cos(a), my + t * Math.sin(a), 1});
                Truth tr = put(sim, when[k], CosmicRays.Kind.TRACK, 60 * noise * 3, path, W, stars, truth);
                tr.byHot = idx[h];
                used[h] = true;
                break;
            }
        }
    }

    /** How far c is from the line through a and b (infinite when a and b are the same place). */
    static double offLine(double[] a, double[] b, double[] c) {
        double dx = b[0] - a[0], dy = b[1] - a[1], l = Math.hypot(dx, dy);
        return l < 1 ? Double.POSITIVE_INFINITY : Math.abs((c[0] - a[0]) * dy - (c[1] - a[1]) * dx) / l;
    }

    /** A particle's path (x, y, weight) of {@code total} levels into frame {@code f}: its light spread as the chip's charge spreads. */
    static Truth put(SkySim sim, int f, CosmicRays.Kind kind, double total, List<double[]> path, int W, double[][][] stars, List<Truth> truth) {
        // spread each bit of light over the pixels round it (the chip's charge spreads)
        java.util.Map<Integer, Double> lit = new java.util.HashMap<Integer, Double>();
        double wsum = 0;
        for (double[] q : path) wsum += q[2];
        double sx = 0, sy = 0, minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        for (double[] q : path) {
            double share = total * q[2] / wsum;
            sx += q[0] * q[2] / wsum;
            sy += q[1] * q[2] / wsum;
            minX = Math.min(minX, q[0]);
            maxX = Math.max(maxX, q[0]);
            minY = Math.min(minY, q[1]);
            maxY = Math.max(maxY, q[1]);
            double s2 = 2 * 0.5 * 0.5, norm = 0;
            int cx = (int) Math.floor(q[0]), cy = (int) Math.floor(q[1]);
            double[] wts = new double[25];
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    double ex = cx + dx + 0.5 - q[0], ey = cy + dy + 0.5 - q[1];
                    double wv = Math.exp(-(ex * ex + ey * ey) / s2);
                    wts[(dy + 2) * 5 + dx + 2] = wv;
                    norm += wv;
                }
            }
            for (int dy = -2; dy <= 2; dy++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int k = (cy + dy) * W + cx + dx;
                    double add = share * wts[(dy + 2) * 5 + dx + 2] / norm;
                    Double was = lit.get(k);
                    lit.put(k, (was == null ? 0 : was) + add);
                }
            }
        }
        int[] pix = new int[lit.size()];
        float[] lev = new float[lit.size()];
        int k = 0;
        double peak = 0;
        for (java.util.Map.Entry<Integer, Double> e : lit.entrySet()) {
            pix[k] = e.getKey();
            lev[k] = (float) (double) e.getValue();
            peak = Math.max(peak, e.getValue());
            k++;
        }
        sim.particles.add(new SkySim.Particle(f, pix, lev));
        Truth t = new Truth();
        t.frame = f;
        t.x = sx;
        t.y = sy;
        t.length = Math.max(1, Math.hypot(maxX - minX, maxY - minY) + 1);
        t.kind = kind == CosmicRays.Kind.WORM && t.length < 4 ? CosmicRays.Kind.SPOT : kind;
        t.levels = total;
        t.peak = peak;
        t.from = new double[]{path.get(0)[0], path.get(0)[1]};
        t.to = new double[]{path.get(path.size() - 1)[0], path.get(path.size() - 1)[1]};
        for (double[] st : stars[f]) {
            if (Math.hypot(st[0] - sx, st[1] - sy) < t.length / 2 + 2.5) t.starUnder = Math.max(t.starUnder, st[5] / sim.electronsPerDn);
        }
        t.onStar = t.starUnder > total / 3;
        truth.add(t);
        return t;
    }

    static double gauss(SplittableRandom r) {
        double u = Math.max(1e-12, r.nextDouble()), v = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteTree(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
