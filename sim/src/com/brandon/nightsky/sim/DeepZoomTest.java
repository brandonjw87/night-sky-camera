package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.DeepZoom;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deep Space Zoom (2.4, {@link DeepZoom}) on a simulated night of the real sky over the US east coast in October, with
 * the Andromeda Galaxy overhead drawn as a glow (the Heart Nebula faint, the Triangulum Galaxy not drawn at all):
 * developed as the app does, then the close-ups planned and made.
 * <ul>
 * <li>Andromeda gets a close-up, where the plate solution puts it (against the simulation's truth), drizzled.
 * <li>The drizzle's stars are smaller than the stack's at the same place and scale (the resolution it wins).
 * <li>Around the galaxy the stars come out smaller still, and the galaxy keeps its light.
 * <li>Every close-up is 1080 x 1080 and its middle stands out of its sky; words and the Moon's scale are filled in.
 * <li>A nebula too faint to see gets no close-up, and says so; no plate solution, no close-ups.
 * </ul>
 * Arguments: output folder, scale (2: 2040 x 1536), frames.
 */
public final class DeepZoomTest {
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "sim-out/deepzoom");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 24;
        out.mkdirs();
        BrightStars catalog = SimRunner.loadCatalog();
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.cx = W / 2.0 + 14.0 / scale;
        truth.cy = H / 2.0 - 9.0 / scale;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        SkySim sim = new SkySim(W, H, truth, 42, catalog, SimRunner.DEFAULT_TIME_MS, SimRunner.DEFAULT_LON);
        sim.psfSigma = 0.85 / Math.sqrt(scale);
        sim.colourByBv();
        // the Andromeda Galaxy: a yellowish oval with a bright middle, 3 degrees long
        sim.glows.add(new SkySim.Glow(10.685, 41.269, 0.9, 0.28, 35, 2.2, new double[]{1.25, 1, 0.7}, 0.08, 0.87));
        // M32 beside it, a small bright spot
        sim.glows.add(new SkySim.Glow(10.674, 40.865, 0.04, 0.03, 0, 0.35, new double[]{1.3, 1, 0.65}, 0.02, 0.5));
        // the Heart Nebula, red and faint (the Triangulum Galaxy isn't drawn: too faint to see)
        sim.glows.add(new SkySim.Glow(38.3, 61.45, 0.55, 0.45, 0, 0.15, new double[]{2.4, 1, 0.9}, 0.1, 0.1));
        SensorSpec spec = sim.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        File root = new File(out, "sessions");
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
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            sim.render(i, buf, info, 7);
            up.submit(buf, info);
        }
        up.finish();
        up = null;
        PostProcessor pp = new PostProcessor(store, spec);
        SessionSky sky = new SessionSky();
        sky.latDeg = sim.latitudeDeg;
        sky.lonDeg = SimRunner.DEFAULT_LON;
        sky.locationSource = "simulation";
        sky.clockWallMs = SimRunner.DEFAULT_TIME_MS;
        sky.clockTimestampNs = 1_000_000_000L;
        sky.clockValid = true;
        sky.catalog = catalog;
        pp.sky = sky;
        PostProcessor.Output o = pp.run(false, PhotoVideoTest.QUIET);
        System.out.printf(Locale.US, "night: %d frames in %.0f s: %s%n", frames, (System.currentTimeMillis() - t0) / 1000.0, o.summary);
        check("the photo is placed on the sky", o.solution != null, o.solution != null ? o.solution.describe() : "no solution");
        if (o.solution == null) {
            finish();
            return;
        }
        // as the app does after developing: the stack as its master keeps it, analysed with the plate solution
        Master.quantize(o, spec, false);
        Renderer.Params params = new Renderer.Params();
        int[] photo = Master.render(o.image, o.analysis, params);
        PhotoVideoTest.writePng(photo, o.analysis.cw, o.analysis.ch, new File(out, "photo.png"));

        List<String> notes = new ArrayList<String>();
        List<DeepZoom.Target> plan = DeepZoom.plan(o.solution, o.solutionJd, o.image, o.analysis, null, false, notes);
        for (DeepZoom.Target t : plan) {
            System.out.printf(Locale.US, "  target %s (%c) at %.1f, %.1f, %.1f px/deg, snr %.1f, box %d px%s%n", t.name, t.kind, t.px, t.py,
                    t.pxPerDeg, t.snr, t.side(), t.drizzled() ? " drizzled" : "");
        }
        for (String s : notes) System.out.println("  not: " + s);
        DeepZoom.Target m31 = null;
        for (DeepZoom.Target t : plan) if (t.name.contains("M31")) m31 = t;
        check("Andromeda gets a close-up", m31 != null, plan.size() + " close-ups");
        if (m31 != null) {
            // where the truth has it in the reference frame
            double[] C = sim.cameraToJ2000(o.referenceUnit);
            double[] dir = com.brandon.nightsky.processing.Astro.vec(Math.toRadians(10.685), Math.toRadians(41.269)), b = new double[3], p = new double[2];
            Rot.apply(Rot.transpose(C), dir[0], dir[1], dir[2], b);
            boolean on = truth.project(b[0], b[1], b[2], p);
            double err = on ? Math.hypot(p[0] - m31.px, p[1] - m31.py) : Double.NaN;
            check("Andromeda is where the sky has it", on && err < 2.0, String.format(Locale.US, "%.2f px off", err));
            check("Andromeda's close-up is drizzled", m31.drizzled(), m31.side() + " px box");
        }
        // every candidate, past the three the photo gets
        List<String> all = new ArrayList<String>();
        List<DeepZoom.Target> every = DeepZoom.plan(o.solution, o.solutionJd, o.image, o.analysis, null, false, 20, all);
        boolean m33 = false, heartFaint = false, heart = false;
        for (String s : all) {
            m33 |= s.contains("M33") && s.contains("faint");
            heartFaint |= s.contains("Heart") && s.contains("faint");
        }
        boolean m33Planned = false;
        for (DeepZoom.Target t : every) {
            m33Planned |= t.name.contains("M33");
            heart |= t.name.contains("Heart");
            System.out.printf(Locale.US, "  candidate %s, snr %.1f%n", t.name, t.snr);
        }
        for (String s : all) System.out.println("  candidate not: " + s);
        check("the Triangulum Galaxy, not drawn here, gets none", !m33Planned && m33, m33 ? "noted as too faint" : "not noted");
        check("the Heart Nebula, faint but there, isn't called too faint", !heartFaint, heart ? "planned" : "left out for another reason");

        int k = 0;
        for (DeepZoom.Target t : plan) {
            DeepZoom.CloseUp cu = DeepZoom.make(t, o.video, o.image, o.analysis, params, PhotoVideoTest.QUIET);
            System.out.println("  " + cu.note);
            check(t.name + ": 1080 x 1080", cu.argb != null && cu.size == DeepZoom.OUT && cu.argb.length == cu.size * cu.size, "");
            double mid = luma(cu.argb, cu.size, 0.4, 0.6), edge = luma(cu.argb, cu.size, 0.0, 0.12);
            if (t.kind != 'o' && t.kind != 'c') {
                check(t.name + ": its middle stands out of its sky", mid > edge + 4, String.format(Locale.US, "%.1f against %.1f", mid, edge));
            }
            check(t.name + ": its words are filled in", !cu.title.isEmpty() && !cu.distance.isEmpty() && !cu.width.isEmpty() && !cu.fact.isEmpty()
                    && !cu.how.isEmpty(), cu.title + " / " + cu.distance + " / " + cu.width);
            if (t.drizzled()) {
                check(t.name + ": the drizzle's stars are smaller than the stack's", cu.starSigma < 0.97 * cu.stackStarSigma,
                        String.format(Locale.US, "sigma %.3f against %.3f stack px", cu.starSigma, cu.stackStarSigma));
            }
            if (t.kind == 'g' || t.kind == 'n') {
                check(t.name + ": its stars made smaller", cu.smallerStarSigma < 0.88 * cu.starSigma,
                        String.format(Locale.US, "sigma %.3f, before %.3f", cu.smallerStarSigma, cu.starSigma));
                check(t.name + ": it keeps its own light", cu.objectKept > 0.85, String.format(Locale.US, "%.0f%% kept", 100 * cu.objectKept));
            }
            PhotoVideoTest.writePng(cu.argb, cu.size, cu.size, new File(out, String.format(Locale.US, "closeup%d.png", k)));
            AwtPainter pa = new AwtPainter(cu.argb, cu.size, cu.size);
            DeepZoom.card(cu, pa);
            PhotoVideoTest.writePng(pa.pixels(), cu.size, cu.size, new File(out, String.format(Locale.US, "card%d.png", k)));
            k++;
        }
        List<String> none = new ArrayList<String>();
        check("no plate solution, no close-ups", DeepZoom.plan(null, o.solutionJd, o.image, o.analysis, null, false, none).isEmpty(),
                none.isEmpty() ? "" : none.get(0));
        finish();
    }

    /** The mean of R, G and B over the square from {@code a} to {@code b} of the way across, both ways. */
    static double luma(int[] argb, int n, double a, double b) {
        double s = 0;
        int c = 0;
        for (int y = (int) (a * n); y < (int) (b * n); y++) {
            for (int x = (int) (a * n); x < (int) (b * n); x++) {
                int p = argb[y * n + x];
                s += ((p >> 16) & 0xFF) + ((p >> 8) & 0xFF) + (p & 0xFF);
                c++;
            }
        }
        return c > 0 ? s / (3.0 * c) : 0;
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    static void finish() {
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
