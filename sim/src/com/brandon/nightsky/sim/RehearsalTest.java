package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ClearSky;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.FocusMeter;
import com.brandon.nightsky.processing.FocusSweep;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.RehearsalCheck;
import com.brandon.nightsky.processing.RehearsalSky;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.Stats;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.SplittableRandom;

/**
 * The rehearsal's sky (2.1) against the app's own measurements, on covered-camera frames like the phone's (black
 * level, read noise and hot pixels only). Checks: the stars and the sky glow land as {@link ClearSky} expects of the
 * lens; the sky check reads the drawn sky's brightness as {@link RehearsalSky#skyReading} predicts, sees a clear sky
 * (no haze, clouds, obstructions or smudge), solves the frame where it was drawn and finds the place from the stars
 * and the phone's level; the focus sweep finds the focus the stars were drawn sharpest at; a whole session of frames
 * through the live processor's hook and developing comes out with every line of the rehearsal's check passed; light
 * reaching the camera is caught; the check keeps in a session's settings; a full-size frame draws quickly enough.
 * <p>
 * Usage: RehearsalTest [work-dir]
 */
public final class RehearsalTest {
    static int failures;
    static final int SCALE = 2;
    /** The phone's covered camera: 10-bit, black 64, read noise in electrons, a few hot pixels. */
    static final int BLACK = 64, WHITE = 1023;
    static final double READ_E = 1.6;
    static final double LAT = 40, LON = -75;
    static final double HEADING = 25;
    /** "Up" in the phone's axes, lying screen-down with a slight tilt (m/s^2). */
    static final float[] UP = {0.35f, -0.55f, -9.78f};
    static BrightStars catalog;

    public static void main(String[] args) throws Exception {
        File work = new File(args.length > 0 ? args[0] : "build/rehearsal-test");
        work.mkdirs();
        catalog = SimRunner.loadCatalog();
        long start = moonless(SimRunner.DEFAULT_TIME_MS);
        SensorSpec spec = spec(SCALE);
        RehearsalSky rs = sky(spec, start, 0.25, FocusSweep.Lens.of(10, 0.1, true, 6.9, 1.68, 2.4 * SCALE).blurPerUnit);
        System.out.println("  " + rs.describe());
        photometry(spec, rs);
        Preflight.Report pf = preflight(spec, rs);
        double found = focus(spec, rs);
        light(spec, rs);
        session(work, spec, rs, pf, found);
        timing();
        attitude();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    // ------------------------------------------------------------------------------------------------
    // The camera and the sky

    /** A Pixel-like main camera (4080 x 3072 at full size, 6.9 mm f/1.68), as SimRunner has it. */
    static SensorSpec spec(int scale) {
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.cx = W / 2.0 + 14.0 / scale;
        truth.cy = H / 2.0 - 9.0 / scale;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        SkySim sim = new SkySim(W, H, truth, 42);
        SensorSpec s = sim.spec(true);
        for (int q = 0; q < 4; q++) s.black[q] = BLACK;
        s.white = WHITE;
        ColorMath.compute(s, ColorMath.DEFAULT_CCT);
        return s;
    }

    static RehearsalSky sky(SensorSpec spec, long startMs, double bestFocus, double blurPerUnit) {
        RehearsalSky.Setup s = new RehearsalSky.Setup();
        s.spec = spec;
        s.model = spec.model;
        s.catalog = catalog;
        s.latDeg = LAT;
        s.lonDeg = LON;
        s.altM = 100;
        s.placeSource = "test";
        s.camToEnu = RehearsalSky.attitude(HEADING, UP);
        s.startUtcMs = startMs;
        s.seed = 2026;
        s.bestFocus = bestFocus;
        s.blurPerUnit = blurPerUnit;
        s.headingDeg = HEADING;
        return RehearsalSky.build(s);
    }

    /** The first half hour from {@code t} when the Moon is well down, so that its glow doesn't decide what's measured. */
    static long moonless(long t) {
        for (int k = 0; k < 96; k++) {
            double alt = Astro.altitude(Astro.moonEnu(t, LAT * Astro.DEG, LON * Astro.DEG)) / Astro.DEG;
            if (alt < -10) return t;
            t += 1800_000L;
        }
        return t;
    }

    /** Electrons a raw level at an ISO: a 2.4 um phone pixel's 6 at ISO 100 on a 10-bit scale. */
    static double epl(int iso) {
        return 6.0 * 100 / iso;
    }

    /** A covered camera's frame: black level, read noise, a few hot pixels. */
    static void dark(short[] raw, int W, int H, int iso, long seed) {
        SplittableRandom r = new SplittableRandom(seed);
        double sig = READ_E / epl(iso);
        for (int i = 0; i < W * H; i++) {
            double v = BLACK + sig * gaussian(r);
            raw[i] = (short) Math.max(0, Math.min(WHITE, Math.round(v)));
        }
        SplittableRandom hot = new SplittableRandom(99);
        for (int k = 0; k < W * H / 20000; k++) raw[hot.nextInt(W * H)] = (short) (BLACK + 150 + hot.nextInt(700));
    }

    /** Android's noise profile for a frame at an ISO: shot noise of {@link #epl} and the read noise, as shares of the range. */
    static void withNoiseProfile(SensorSpec s, int iso) {
        double range = s.range();
        for (int q = 0; q < 4; q++) {
            s.noiseS[q] = (float) (1 / (epl(iso) * range));
            s.noiseO[q] = (float) Math.pow(READ_E / epl(iso) / range, 2);
        }
    }

    static double gaussian(SplittableRandom r) {
        double u = Math.max(1e-300, r.nextDouble()), v = r.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    static RehearsalSky.Frame frame(double utcStartMs, double exposureS, int iso, double focus, long seed) {
        RehearsalSky.Frame f = new RehearsalSky.Frame();
        f.utcStartMs = utcStartMs;
        f.exposureS = exposureS;
        f.iso = iso;
        f.focus = focus;
        Arrays.fill(f.black, BLACK);
        f.electronsPerLevel = epl(iso);
        f.noiseSeed = seed;
        return f;
    }

    // ------------------------------------------------------------------------------------------------

    /** The sky glow and the catalogue's stars land with the light ClearSky expects of this lens. */
    static void photometry(SensorSpec spec, RehearsalSky rs) {
        int W = spec.width, H = spec.height, iso = 800;
        double t = 16;
        short[] raw = new short[W * H];
        dark(raw, W, H, iso, 1);
        RehearsalSky.Frame f = frame(rs.startUtcMs, t, iso, rs.bestFocus, 11);
        RehearsalSky.Drawn d = rs.draw(raw, f);
        System.out.printf(Locale.US, "  drew %d stars in %d ms; sky %.1f levels at the middle, %.2f e- a level%n", d.stars, d.millis, d.skyLevels,
                d.electronsPerLevel);
        CameraModel m = spec.model;
        int gq = 1;
        // the sky: the green photosites' median in a box at the middle, against what was drawn there (vignetting in)
        float[] v = new float[64 * 64];
        int n = 0;
        for (int y = (int) m.cy - 64; y < (int) m.cy + 64; y++) {
            for (int x = (int) m.cx - 64; x < (int) m.cx + 64; x++) {
                int q = ((y & 1) << 1) | (x & 1);
                if (spec.cfa[q] != 1 || n >= v.length) continue;
                v[n++] = (raw[y * W + x] & 0xFFFF) - BLACK;
            }
        }
        double med = Stats.median(v, n);
        double want = d.skyLevels / spec.shading.gain(gq, m.cx, m.cy);
        check(Math.abs(med / want - 1) < 0.04, String.format(Locale.US, "the sky glow at the middle: %.1f levels, %.1f drawn", med, want));
        check(d.leakLevels < 1, String.format(Locale.US, "the covered camera's own light: %.2f levels (none)", d.leakLevels));

        // a short frame at ISO 100, so that stars down to magnitude 3.5 don't clip
        iso = 100;
        t = 4;
        dark(raw, W, H, iso, 4);
        d = rs.draw(raw, frame(rs.startUtcMs, t, iso, rs.bestFocus, 44));

        // the stars: the light in a box around each catalogue star of magnitude 3.5 to 5, against e0 10^-0.4(V + k X)
        double mid = rs.startUtcMs + 500 * t;
        double[] C = Rot.transpose(rs.camToJ2000(mid));
        double[] enu = Astro.j2000ToEnu(Astro.jdUtc(mid / 1000.0), LAT * Astro.DEG, LON * Astro.DEG);
        double[] b = new double[3], p = new double[2], e = new double[3];
        List<Double> ratios = new ArrayList<Double>();
        int R = 7;
        // a clean lens's glow puts 3% of a bright star's light 6 px wide, of which the box holds this much
        double inBox = Math.pow(erf((R + 0.5) / (Math.sqrt(2) * 6)), 2);
        for (int i = 0; i < catalog.n; i++) {
            float vm = catalog.vmag[i];
            if (!(vm >= 3.5 && vm <= 5)) continue;
            Rot.apply(C, catalog.dir[3 * i], catalog.dir[3 * i + 1], catalog.dir[3 * i + 2], b);
            if (b[2] < 0.3 || !m.project(b[0], b[1], b[2], p)) continue;
            if (p[0] < 40 || p[1] < 40 || p[0] > W - 40 || p[1] > H - 40) continue;
            if (crowded(i, C, m, p, vm)) continue;
            Rot.apply(enu, catalog.dir[3 * i], catalog.dir[3 * i + 1], catalog.dir[3 * i + 2], e);
            double alt = Math.asin(e[2]) / Astro.DEG;
            int cx = (int) Math.floor(p[0]), cy = (int) Math.floor(p[1]);
            // the box's green light above the sky (from a ring around it), in electrons
            float[] ring = new float[400];
            int nr = 0;
            double sum = 0;
            int ng = 0;
            for (int y = cy - R - 6; y <= cy + R + 6; y++) {
                for (int x = cx - R - 6; x <= cx + R + 6; x++) {
                    int q = ((y & 1) << 1) | (x & 1);
                    if (spec.cfa[q] != 1) continue;
                    int val = (raw[y * W + x] & 0xFFFF) - BLACK;
                    boolean in = Math.abs(x - cx) <= R && Math.abs(y - cy) <= R;
                    if (in) {
                        sum += val;
                        ng++;
                    } else if (nr < ring.length) {
                        ring[nr++] = val;
                    }
                }
            }
            double skyPer = Stats.median(ring, nr);
            double electrons = (sum - skyPer * ng) * d.electronsPerLevel;
            boolean halo = vm < 8;
            double share = halo ? 1 - 0.03 + 0.03 * inBox : 1;
            double vig = 1 / spec.shading.gain(gq, p[0], p[1]);
            // green photosites take half the light of a star spread over many of them
            double expected = 0.5 * rs.e0 * Math.pow(10, -0.4 * (vm + rs.extinction * ClearSky.airmass(alt))) * t * vig * share;
            ratios.add(electrons / expected);
        }
        double[] r = new double[ratios.size()];
        for (int i = 0; i < r.length; i++) r[i] = ratios.get(i);
        Arrays.sort(r);
        double medR = r.length > 0 ? r[r.length / 2] : Double.NaN;
        check(r.length >= 10 && Math.abs(medR - 1) < 0.05, String.format(Locale.US,
                "%d catalogue stars of magnitude 3.5 to 5: their light %.3f times what ClearSky expects (middle half %.3f to %.3f)", r.length,
                medR, r.length > 0 ? r[r.length / 4] : Double.NaN, r.length > 0 ? r[3 * r.length / 4] : Double.NaN));
    }

    /** Another catalogue star within 12 px (its light would be counted with this one's). */
    static boolean crowded(int self, double[] C, CameraModel m, double[] at, float vm) {
        double[] b = new double[3], p = new double[2];
        for (int j = 0; j < catalog.n; j++) {
            if (j == self || catalog.vmag[j] > vm + 3.5) continue;
            Rot.apply(C, catalog.dir[3 * j], catalog.dir[3 * j + 1], catalog.dir[3 * j + 2], b);
            if (b[2] < 0.2 || !m.project(b[0], b[1], b[2], p)) continue;
            if (Math.abs(p[0] - at[0]) < 14 && Math.abs(p[1] - at[1]) < 14) return true;
        }
        return false;
    }

    static double erf(double x) {
        double s = x < 0 ? -1 : 1;
        x = Math.abs(x);
        double t = 1 / (1 + 0.3275911 * x);
        double y = 1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * Math.exp(-x * x);
        return s * y;
    }

    // ------------------------------------------------------------------------------------------------

    static SessionSky sessionSky(long startMs) {
        SessionSky sky = new SessionSky();
        sky.latDeg = LAT;
        sky.lonDeg = LON;
        sky.altM = 100;
        sky.locationSource = "test";
        sky.locationAgeMin = 1;
        sky.clockWallMs = startMs;
        sky.clockTimestampNs = 1_000_000_000L;
        sky.clockValid = true;
        sky.headingTopDeg = HEADING;
        sky.catalog = catalog;
        return sky;
    }

    /** The sky check on a drawn test frame: the session's ISO and frame length at most 8 s, as the app takes it. */
    static Preflight.Report preflight(SensorSpec camera, RehearsalSky rs) {
        int W = camera.width, H = camera.height, iso = 800;
        double t = 8;
        // the frame's noise profile, as the camera reports it with each frame (shot noise and read noise at this ISO)
        SensorSpec spec = camera.copy();
        spec.iso = iso;
        withNoiseProfile(spec, iso);
        short[] raw = new short[W * H];
        dark(raw, W, H, iso, 2);
        double startMs = rs.startUtcMs;
        RehearsalSky.Drawn d = rs.draw(raw, frame(startMs, t, iso, rs.bestFocus, 22));
        Preflight.Input in = new Preflight.Input();
        in.raw = raw;
        in.spec = spec;
        in.exposureS = t;
        in.iso = iso;
        in.planExposureS = 16;
        in.planIso = iso;
        in.sky = sessionSky(rs.startUtcMs);
        in.timestampNs = 1_000_000_000L;
        in.up = UP;
        in.catalog = catalog;
        in.rehearsal = true;
        Preflight.Report rep = Preflight.run(in);
        System.out.println("  sky check: " + rep.spoken());
        for (Preflight.Item it : rep.items) System.out.println("    " + it.level + " " + it.key + ": " + it.text);
        double[] want = rs.skyReading(startMs + 500 * t, d.leakPerSec, d.electronsPerLevel);
        check(!Double.isNaN(rep.skyMag) && Math.abs(rep.skyMag - want[1]) <= 0.2, String.format(Locale.US,
                "the sky check read the sky at %.2f magnitudes a square arcsecond; %.2f expected (drawn %.2f)", rep.skyMag, want[1], want[0]));
        List<String> alarms = new ArrayList<String>();
        for (Preflight.Item it : rep.items) {
            if ((it.level == Preflight.Level.WARN || it.level == Preflight.Level.STOP) && !"moon".equals(it.key)) alarms.add(it.key);
        }
        check(alarms.isEmpty(), "a clear sky: no clouds, obstructions, smudge or haze warned of" + (alarms.isEmpty() ? "" : " (warned: " + alarms + ")"));
        Preflight.Item sun = rep.item("sun");
        check(sun != null && sun.level == Preflight.Level.OK && sun.text.startsWith("A rehearsal"), "the Sun's height isn't checked in a rehearsal");
        check(!Double.isNaN(rep.hazeMag) && Math.abs(rep.hazeMag) < 0.3, String.format(Locale.US,
                "the stars as bright as the camera's specs say a clear sky gives them: %+.2f magnitudes", rep.hazeMag));
        if (rep.solution != null) {
            double[] truth = rs.camToJ2000(startMs + 500 * t);
            double err = Rot.angle(Rot.mul(Rot.transpose(truth), rep.solution.R)) / Astro.DEG;
            check(err < 0.2, String.format(Locale.US, "the test frame solved %.3f degrees from where it was drawn (%d stars matched)", err,
                    rep.solution.matched));
        } else {
            check(false, "the test frame solved");
        }
        double dLat = rep.starLatDeg - LAT, dLon = (rep.starLonDeg - LON) * Math.cos(LAT * Astro.DEG);
        check(Math.hypot(dLat, dLon) < 0.5, String.format(Locale.US,
                "the place from the stars and the phone's level: %.2f, %.2f (drawn from %.2f, %.2f)", rep.starLatDeg, rep.starLonDeg, LAT, LON));
        return rep;
    }

    /** The app's focus sweep on drawn frames (a quarter of the frame length, at most 2 s, at a high ISO) finds the best focus. */
    static double focus(SensorSpec spec, RehearsalSky rs) {
        int W = spec.width, H = spec.height, iso = 3200;
        double t = 2;
        FocusSweep.Lens lens = FocusSweep.Lens.of(10, 0.1, true, 6.9, 1.68, 2.4 * SCALE);
        short[] raw = new short[W * H];
        FocusSweep s = FocusSweep.full(lens, FocusSweep.Kind.STARS, "test");
        double at = rs.startUtcMs + 30_000;
        int k = 0;
        long ms = 0;
        StringBuilder sb = new StringBuilder();
        while (!s.done()) {
            double f = s.next();
            if (Double.isNaN(f)) break;
            dark(raw, W, H, iso, 100 + k);
            RehearsalSky.Drawn d = rs.draw(raw, frame(at, t, iso, f, 300 + k));
            ms = Math.max(ms, d.millis);
            FocusMeter.Reading r = FocusMeter.measure(raw, 1f, spec, new float[]{BLACK, BLACK, BLACK, BLACK});
            if (k < 24) sb.append(String.format(Locale.US, " %.2f:%.2f", f, r.hfr));
            s.add(f, r.hfr, r.noise, r.stars, r.ok());
            at += 1000 * t + 100;
            k++;
        }
        FocusSweep.Result res = s.result();
        System.out.println("  focus sweep (focus:HFR):" + sb + String.format(Locale.US, "; at most %d ms a frame to draw", ms));
        double err = Math.abs(res.focus - rs.bestFocus);
        check(res.ok && err <= lens.fine, String.format(Locale.US, "the focus sweep found %.3f in %d frames; the stars were drawn sharpest at "
                + "%.3f (one fine step: %.3f)", res.focus, res.frames, rs.bestFocus, lens.fine));
        double hfr = rs.hfrAt(rs.bestFocus);
        // the meter takes the median over the middle and the corners, where the drawn stars are a little softer
        check(res.value > 0.95 * hfr && res.value < 1.25 * hfr, String.format(Locale.US,
                "the stars measured %.2f px (half-flux radius) at the best focus; drawn %.2f px at the middle", res.value, hfr));
        return res.ok ? res.focus : rs.bestFocus;
    }

    /** Light getting to the camera (a lit room, the phone propped up) is measured and the check says so. */
    static void light(SensorSpec spec, RehearsalSky rs) {
        int W = spec.width, H = spec.height, iso = 800;
        short[] raw = new short[W * H];
        dark(raw, W, H, iso, 3);
        for (int i = 0; i < raw.length; i++) raw[i] = (short) Math.min(WHITE, (raw[i] & 0xFFFF) + 120);
        RehearsalSky.Drawn d = rs.draw(raw, frame(rs.startUtcMs, 16, iso, rs.bestFocus, 33));
        check(Math.abs(d.leakLevels - 120) < 2, String.format(Locale.US, "light in the frame before the sky was drawn: %.1f levels (120 let in)",
                d.leakLevels));
        Properties p = new Properties();
        p.setProperty("rehearsal.leakElectronsPerSec", Double.toString(d.leakPerSec * d.electronsPerLevel));
        p.setProperty("rehearsal.skyElectronsPerSec", Double.toString(d.skyLevels * d.electronsPerLevel / 16));
        RehearsalCheck.Input in = new RehearsalCheck.Input();
        in.props = p;
        RehearsalCheck c = RehearsalCheck.build(in);
        RehearsalCheck.Item dark = item(c, "dark");
        check(dark != null && dark.level != RehearsalCheck.Level.PASS, "the check says light reached the camera: " + (dark != null ? dark.line() : "none"));
    }

    static RehearsalCheck.Item item(RehearsalCheck c, String key) {
        for (RehearsalCheck.Item it : c.items) if (it.key.equals(key)) return it;
        return null;
    }

    // ------------------------------------------------------------------------------------------------

    /**
     * A whole session as the phone runs it: covered-camera frames through the live processor, the sky drawn by its
     * hook, then developing, then the rehearsal's check, which should pass every line.
     */
    static void session(File work, SensorSpec spec, final RehearsalSky rs, Preflight.Report pf, final double focus) throws Exception {
        final int W = spec.width, H = spec.height, iso = 800, frames = 19;
        final double t = 16, gap = 0.06;
        SensorSpec sp = spec.copy();
        sp.iso = iso;
        sp.frameExposureNs = (long) (t * 1e9);
        File root = new File(work, "sessions");
        root.mkdirs();
        SessionStore store = SessionStore.create(root);
        final long t0Wall = rs.startUtcMs + 60_000;
        final List<RehearsalSky.Drawn> drawn = new ArrayList<RehearsalSky.Drawn>();
        final int[] hookFailures = {0};
        UnitProcessor up = new UnitProcessor(store, sp, 1, 3, new UnitProcessor.Listener() {
            @Override
            public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {
            }

            @Override
            public void onError(Throwable e) {
                e.printStackTrace();
            }
        });
        up.setHook(new UnitProcessor.FrameHook() {
            @Override
            public void before(short[] raw, UnitProcessor.FrameInfo info) {
                RehearsalSky.Frame f = new RehearsalSky.Frame();
                f.utcStartMs = info.utcStartMs;
                f.exposureS = info.exposureNs / 1e9;
                f.iso = info.iso;
                f.focus = info.focus;
                System.arraycopy(info.black, 0, f.black, 0, 4);
                f.electronsPerLevel = info.electronsPerLevel;
                f.noiseSeed = info.timestampNs;
                RehearsalSky.Drawn d = rs.draw(raw, f);
                synchronized (drawn) {
                    drawn.add(d);
                }
            }

            @Override
            public void failed(UnitProcessor.FrameInfo info, Throwable e) {
                hookFailures[0]++;
                e.printStackTrace();
            }
        });
        long c0 = System.currentTimeMillis();
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            dark(buf, W, H, iso, 1000 + i);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            info.timestampNs = 1_000_000_000L + (long) (i * (t + gap) * 1e9);
            info.exposureNs = (long) (t * 1e9);
            info.iso = iso;
            Arrays.fill(info.black, BLACK);
            info.focus = (float) focus;
            info.gyroMax = 0.002f;
            info.utcStartMs = t0Wall + (info.timestampNs - 1_000_000_000L) / 1e6;
            info.electronsPerLevel = epl(iso);
            up.submit(buf, info);
        }
        up.finish();
        long c1 = System.currentTimeMillis();
        long drawMs = 0;
        for (RehearsalSky.Drawn d : drawn) drawMs = Math.max(drawMs, d.millis);
        System.out.printf(Locale.US, "  capture of %d frames: %d ms (each drawn in at most %d ms)%n", frames, c1 - c0, drawMs);
        check(drawn.size() == frames && hookFailures[0] == 0, String.format(Locale.US, "the sky was drawn on all %d frames (%d drawn, %d failed)",
                frames, drawn.size(), hookFailures[0]));
        up = null;

        PostProcessor pp = new PostProcessor(store, sp);
        SessionSky sky = sessionSky(t0Wall);
        pp.sky = sky;
        long d0 = System.currentTimeMillis();
        PostProcessor.Output o = pp.run(false, new Progress() {
            @Override
            public void update(String stage, float fraction) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        });
        double developS = (System.currentTimeMillis() - d0) / 1000.0;
        System.out.println("  developed: " + o.summary);

        // the session's settings as the app writes them
        Properties p = sp.toProperties();
        rs.toProperties(p);
        p.setProperty("capture.framesDone", Integer.toString(frames));
        p.setProperty("capture.framesPlanned", Integer.toString(frames));
        p.setProperty("capture.framesFailed", "0");
        p.setProperty("capture.framesDropped", "0");
        p.setProperty("capture.stalls", "0");
        p.setProperty("capture.renewals", "0");
        p.setProperty("capture.thermalMax", "0");
        p.setProperty("focus.final", Double.toString(focus));
        p.setProperty("rehearsal.focusHow", "swept");
        p.setProperty("preflight.summary", pf.spoken());
        StringBuilder w = new StringBuilder();
        for (Preflight.Item it : pf.items) {
            if (it.level != Preflight.Level.WARN && it.level != Preflight.Level.STOP) continue;
            if (w.length() > 0) w.append(',');
            w.append(it.key);
        }
        p.setProperty("preflight.warnings", w.toString());
        double[] reading = rs.skyReading(rs.startUtcMs + 4000, 0, epl(800));
        p.setProperty("rehearsal.pfRead", Double.toString(pf.skyMag));
        p.setProperty("rehearsal.pfExpected", Double.toString(reading[1]));
        p.setProperty("rehearsal.pfDrawn", Double.toString(reading[0]));
        double[] leak = new double[drawn.size()];
        double skyE = 0;
        for (int i = 0; i < leak.length; i++) {
            RehearsalSky.Drawn d = drawn.get(i);
            leak[i] = d.leakPerSec * d.electronsPerLevel;
            skyE += d.skyLevels * d.electronsPerLevel / t / leak.length;
        }
        Arrays.sort(leak);
        p.setProperty("rehearsal.leakElectronsPerSec", Double.toString(leak[leak.length / 2]));
        p.setProperty("rehearsal.skyElectronsPerSec", Double.toString(skyE));

        RehearsalCheck.Input in = new RehearsalCheck.Input();
        in.props = p;
        in.out = o;
        in.sky = sky;
        in.photoSaved = true;
        in.logSaved = true;
        in.developSeconds = developS;
        Runtime rt = Runtime.getRuntime();
        in.heapPeak = rt.totalMemory() - rt.freeMemory();
        in.heapMax = rt.maxMemory();
        RehearsalCheck c = RehearsalCheck.build(in);
        System.out.println("  " + c.describe().replace("\n", "\n    "));
        check(c.failed == 0 && c.notes == 0 && c.passed == c.items.size(), "the rehearsal's check: " + c.headline);

        // kept in the session's settings and read back the same
        Properties kept = new Properties();
        c.toProperties(kept);
        RehearsalCheck back = RehearsalCheck.fromProperties(kept);
        check(back != null && back.describe().equals(c.describe()), "the check reads back from the session's settings unchanged");
    }

    /** A full-size frame (4080 x 3072) of 16 s draws in a few seconds at most, well inside the frame's own time. */
    static void timing() {
        SensorSpec spec = spec(1);
        RehearsalSky rs = sky(spec, moonless(SimRunner.DEFAULT_TIME_MS), 0.25, 11.8);
        short[] raw = new short[spec.width * spec.height];
        long best = Long.MAX_VALUE;
        int stars = 0;
        for (int k = 0; k < 3; k++) {
            Arrays.fill(raw, (short) BLACK);
            RehearsalSky.Drawn d = rs.draw(raw, frame(rs.startUtcMs + 16060.0 * k, 16, 800, 0.25, k));
            best = Math.min(best, d.millis);
            stars = d.stars;
        }
        System.out.printf(Locale.US, "  full size: %d stars of %d drawn in %d ms (%d threads)%n", stars, rs.stars, best,
                Runtime.getRuntime().availableProcessors());
        check(best < 5000, String.format(Locale.US, "a full-size frame draws in %d ms", best));
    }

    /** The phone's attitude: its camera to the zenith when level, the zenith where "up" says when tilted, the compass kept. */
    static void attitude() {
        double[] level = RehearsalSky.attitude(HEADING, null);
        double[] a = new double[3];
        Rot.apply(level, 0, 0, 1, a);
        check(a[2] > 0.999999, "a level phone's camera looks straight up");
        double[] tilted = RehearsalSky.attitude(HEADING, UP);
        double n = Math.sqrt(UP[0] * UP[0] + UP[1] * UP[1] + UP[2] * UP[2]);
        Rot.apply(tilted, -UP[1] / n, -UP[0] / n, -UP[2] / n, a);
        check(a[2] > 0.999999, "tilted, the zenith is where the phone's \"up\" says");
        // the phone's top edge (camera -x) still towards the compass heading
        Rot.apply(tilted, -1, 0, 0, a);
        double az = Math.toDegrees(Math.atan2(a[0], a[1]));
        check(Math.abs(az - HEADING) < 2, String.format(Locale.US, "its top edge towards %.1f degrees (the compass said %.1f)", az, HEADING));
        // screen-up, it is taken as turned over about its long side
        float[] faceUp = {-UP[0], UP[1], -UP[2]};
        double[] flipped = RehearsalSky.attitude(HEADING, new float[]{-faceUp[0], faceUp[1], -faceUp[2]});
        check(Rot.angle(Rot.mul(Rot.transpose(flipped), tilted)) < 1e-6, "screen-up is drawn as if turned over, screen-down");
    }
}
