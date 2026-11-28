package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.MissionControl;
import com.brandon.nightsky.processing.MissionLog;
import com.brandon.nightsky.processing.Patches;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatSky;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.StarSong;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;
import com.brandon.nightsky.remote.JsonTree;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Mission Control's live catches (2.3) on whole sessions, scored against the truth.
 * <p>
 * Catches: the satellite session of {@link SatSessionTest} (real orbits, a Starlink train, the ISS, crowding, two
 * satellites missing from the data, a plane and a meteor), captured with Mission Control watching each unit as it comes
 * in. Of the satellites' trails bright and long enough to see in one frame, at least half must be caught live with the
 * right name, none with a wrong one, and nothing may be reported where nothing crossed. The live sky must solve, the
 * satellites coming must be announced before they cross, and the photo's story must end with patches.
 * <p>
 * Quiet: a sky with nothing crossing it but with what fools a streak finder (the Milky Way, a branch in front, clouds
 * drifting over part of the frame, the sensor's banding, the phone bumped once): no streak may be reported at all.
 * <p>
 * Usage: MissionTest outDir [scale=2] [frames=37] [seed=1]
 */
public final class MissionTest {
    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "mission-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 37;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 1;
        String only = args.length > 4 ? args[4] : "";
        out.mkdirs();
        BrightStars catalog = SimRunner.loadCatalog();
        long startMs = SatSessionTest.darkStart();
        boolean ok = true;
        if (!only.equals("quiet")) ok &= catches(new File(out, "catches"), scale, frames, seed, startMs, catalog);
        if (!only.equals("catches")) ok &= quiet(new File(out, "quiet"), scale, Math.min(frames, 30), seed + 7, startMs, catalog);
        System.out.println(ok ? "ALL PASS" : "FAILED");
        if (!ok) System.exit(1);
    }

    // ------------------------------------------------------------------------------------------------

    /** The catches' session: its sky, its satellites' data and the passes as they really flew. */
    static final class Scenario {
        SkySim sim;
        SatCatalog cat;
        List<SatSessionTest.Pass> passes;
    }

    static Scenario scenario(int scale, int frames, long seed, long startMs, BrightStars catalog) {
        Random rnd = new Random(seed);
        SkySim sim = SatSessionTest.newSim(scale, startMs, catalog);
        double dur = sim.frameStart(frames - 1) + sim.frameExp;
        double jd0 = Astro.jdUtc(startMs);
        SatSky.Site site = new SatSky.Site(Math.toRadians(SatSessionTest.LAT), Math.toRadians(SatSessionTest.LON), 0);
        double[] axis = SatSessionTest.axisEnu(sim);
        List<SatSessionTest.Pass> passes = new ArrayList<SatSessionTest.Pass>();
        SatSessionTest.Shape[] real = {SatSessionTest.DELTA, SatSessionTest.CBERS, SatSessionTest.SL12};
        int norad = 70001;
        int singles = 14;
        for (int k = 0; k < singles; k++) {
            SatSessionTest.Shape sh = k == 5 ? SatSessionTest.ISS : real[k % real.length];
            double t = dur * (k + 0.3 + 0.4 * rnd.nextDouble()) / singles;
            SatSessionTest.Pass p = SatSessionTest.plan(sh, sh == SatSessionTest.ISS ? "ISS (ZARYA)" : sh.name + " " + (char) ('A' + k),
                    sh == SatSessionTest.ISS ? 25544 : norad++, jd0 + t / 86400, axis, 24, rnd, site);
            if (p == null) continue;
            p.trueStdMag = sh == SatSessionTest.ISS ? -1.3 : 3.0 + 2.5 * rnd.nextDouble();
            passes.add(p);
        }
        SatSessionTest.Pass lead = SatSessionTest.plan(SatSessionTest.STARLINK, "STARLINK-1001", 61001, jd0 + dur * 0.45 / 86400, axis, 15, rnd, site);
        if (lead != null) {
            lead.trueStdMag = 4.2;
            passes.add(lead);
            for (int k = 1; k < 4; k++) {
                SatSessionTest.Pass f = SatSessionTest.copyPass(lead, "STARLINK-100" + (k + 1), 61001 + k);
                f.m = lead.m - 12.0 * k * SatSessionTest.STARLINK.n * 360 / 86400;
                f.trueStdMag = 4.0 + 0.3 * k;
                passes.add(f);
            }
        }
        for (int k = 0; k < 2; k++) {
            SatSessionTest.Pass x = SatSessionTest.plan(real[k], "UNLISTED " + k, 75001 + k, jd0 + dur * (0.25 + 0.5 * k) / 86400, axis, 22, rnd, site);
            if (x == null) continue;
            x.inData = false;
            x.trueStdMag = 3.2;
            passes.add(x);
        }
        SatSessionTest.finish(passes, rnd, frames, sim);
        sim.planeFrame = frames / 3;
        sim.meteorFrame = (frames * 2) / 3;
        SatCatalog cat = SatSessionTest.catalogue(passes, jd0);
        Scenario sc = new Scenario();
        sc.sim = sim;
        sc.cat = cat;
        sc.passes = passes;
        return sc;
    }

    static boolean catches(File dir, int scale, int frames, long seed, long startMs, BrightStars catalog) throws Exception {
        Scenario sc = scenario(scale, frames, seed, startMs, catalog);
        SkySim sim = sc.sim;
        SatCatalog cat = sc.cat;
        List<SatSessionTest.Pass> passes = sc.passes;
        Run r = run(dir, sim, frames, seed, startMs, catalog, cat, true);
        // score the satellite catches against the truth, frame by frame, in each frame's own pixels
        System.out.println("-- live catches");
        int should = 0, right = 0, wrong = 0, unlistedNamed = 0, extra = 0;
        List<MissionLog.Event> caughtEv = new ArrayList<MissionLog.Event>();
        for (MissionLog.Event e : r.events) {
            if (MissionLog.SAT_CAUGHT.equals(e.kind) || MissionLog.STREAK.equals(e.kind) || MissionLog.METEOR.equals(e.kind)) caughtEv.add(e);
        }
        boolean[] explained = new boolean[caughtEv.size()];
        for (SatSessionTest.Pass p : passes) {
            if (!p.drawn) continue;
            for (int f = 0; f < frames; f++) {
                double[][] path = unitPath(sim, p, f);
                if (path == null) continue;
                double len = SatSessionTest.pathLength(path);
                double dens = p.drawnSat.peakDensity[f];
                boolean detectable = len >= 120.0 / scale * 2 && dens >= SatSessionTest.MIN_DENSITY * 1.5;
                int hit = -1;
                for (int k = 0; k < caughtEv.size(); k++) {
                    MissionLog.Event e = caughtEv.get(k);
                    if (e.unit != f || e.unitLine == null) continue;
                    if (on(e.unitLine, path)) {
                        // where two trails cross, an event can lie on both: the one that names this satellite counts
                        if (hit < 0 || caughtEv.get(hit).norad != p.norad) hit = k;
                        explained[k] = true;
                    }
                }
                MissionLog.Event e = hit >= 0 ? caughtEv.get(hit) : null;
                boolean isRight = e != null && e.norad == p.norad;
                boolean isWrong = e != null && e.norad != 0 && e.norad != p.norad;
                if (!p.inData) {
                    if (isWrong) unlistedNamed++;
                } else if (detectable) {
                    should++;
                    if (isRight) right++;
                    if (isWrong) wrong++;
                } else if (isWrong) wrong++;
                if (detectable || e != null) {
                    System.out.printf(Locale.US, "  frame %2d %-16s %s %4.0f px, density %6.1f: %s%s%n", f, p.name, p.inData ? "      " : "(none)",
                            len, dens, e == null ? "-" : e.kind + " " + (e.name.isEmpty() ? "" : e.name),
                            !p.inData ? (isWrong ? "  WRONG" : "") : isRight ? "" : isWrong ? "  WRONG" : detectable ? "  missed" : "");
                }
            }
        }
        // the plane and the meteor explain what's on their lines
        for (int k = 0; k < caughtEv.size(); k++) {
            MissionLog.Event e = caughtEv.get(k);
            if (explained[k] || e.unitLine == null) continue;
            double[] seg = sim.planeSegment(e.unit);
            if (seg != null && on(e.unitLine, new double[][]{{seg[0], seg[1]}, {seg[2], seg[3]}})) explained[k] = true;
            if (e.unit == sim.meteorFrame && on(e.unitLine, new double[][]{{sim.W * 0.55, sim.H * 0.3}, {sim.W * 0.62, sim.H * 0.42}})) explained[k] = true;
            if (!explained[k]) {
                extra++;
                System.out.printf(Locale.US, "  frame %2d: %s %s at (%.0f,%.0f)-(%.0f,%.0f) where nothing crossed  FALSE%n", e.unit, e.kind, e.name,
                        e.unitLine[0], e.unitLine[1], e.unitLine[2], e.unitLine[3]);
            }
        }
        double share = should > 0 ? right / (double) should : 0;
        System.out.printf(Locale.US, "caught live: %d of %d trails with the right name (%.0f%%), %d wrong, %d unlisted given a name, %d false%n",
                right, should, 100 * share, wrong, unlistedNamed, extra);
        boolean ok = should >= 8 && share >= 0.5 && wrong == 0 && unlistedNamed == 0 && extra == 0;
        // the plane's frames
        boolean plane = false;
        for (MissionLog.Event e : caughtEv) if (e.unit == sim.planeFrame || e.unit == sim.planeFrame + 1) plane |= e.norad == 0;
        System.out.println("plane reported: " + plane);
        ok &= plane;
        ok &= story(r, true);
        System.out.println("== live catches: " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    /** Whether a found line (x0, y0, x1, y1) lies along a path: its middle within 16 px, its direction within 8 degrees. */
    static boolean on(float[] l, double[][] path) {
        double mx = (l[0] + l[2]) / 2, my = (l[1] + l[3]) / 2;
        double best = Double.MAX_VALUE, cos = 0;
        for (int k = 0; k + 1 < path.length; k++) {
            double ax = path[k][0], ay = path[k][1], bx = path[k + 1][0], by = path[k + 1][1];
            double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
            double u = l2 > 0 ? Math.max(0, Math.min(1, ((mx - ax) * dx + (my - ay) * dy) / l2)) : 0;
            double e = Math.hypot(mx - ax - u * dx, my - ay - u * dy);
            if (e < best) {
                best = e;
                double sx = l[2] - l[0], sy = l[3] - l[1];
                cos = l2 > 0 ? Math.abs(sx * dx + sy * dy) / Math.max(1e-9, Math.hypot(sx, sy) * Math.sqrt(l2)) : 1;
            }
        }
        // a two-point path (the plane's, the meteor's) is the whole line; a short line's ends are known to a cell (8 px)
        if (path.length == 2) cos = Math.max(cos, 0);
        double len = Math.hypot(l[2] - l[0], l[3] - l[1]);
        return best < 16 && cos > Math.cos(Math.toRadians(8 + Math.toDegrees(Math.atan2(8, Math.max(1, len)))));
    }

    /** The satellite's true path during frame f, in that frame's own pixels; null if it didn't cross it. */
    static double[][] unitPath(SkySim sim, SatSessionTest.Pass p, int f) {
        if (p.truth == null) return null;
        double[] Rt = Rot.transpose(sim.cameraToJ2000(f));
        SatSky.Site site = new SatSky.Site(Math.toRadians(SatSessionTest.LAT), Math.toRadians(SatSessionTest.LON), 0);
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

    // ------------------------------------------------------------------------------------------------

    static boolean quiet(File dir, int scale, int frames, long seed, long startMs, BrightStars catalog) throws Exception {
        SkySim sim = SatSessionTest.newSim(scale, startMs, catalog);
        sim.milkyWay = 1;
        sim.branch = true;
        sim.partialFrom = frames / 4;
        sim.partialTo = frames / 2;
        sim.rowBandSigma = 0.6;
        sim.bumpFrame = (frames * 2) / 3;
        Run r = run(dir, sim, frames, seed, startMs, catalog, null, false);
        int streaks = 0;
        for (MissionLog.Event e : r.events) {
            if (MissionLog.SAT_CAUGHT.equals(e.kind) || MissionLog.STREAK.equals(e.kind) || MissionLog.METEOR.equals(e.kind)) {
                streaks++;
                System.out.printf(Locale.US, "  frame %2d: %s %s  FALSE%n", e.unit, e.kind, e.name);
            }
        }
        System.out.printf(Locale.US, "quiet sky: %d streaks reported (none crossed)%n", streaks);
        boolean ok = streaks == 0 && story(r, false);
        System.out.println("== quiet sky: " + (ok ? "PASS" : "FAIL"));
        return ok;
    }

    // ------------------------------------------------------------------------------------------------

    static final class Run {
        PostProcessor.Output out;
        MissionControl mc;
        List<MissionLog.Event> events = new ArrayList<MissionLog.Event>();
        String sky, sats, song, patches, eventsJson;
        int comingBefore;
    }

    /** A pretend status: what the session says it's doing, as the phone's would. */
    static final class FakeStatus implements MissionControl.Status {
        volatile String state = "arming";
        volatile long elapsed, remaining;
        volatile Renderer.Analysis analysis;

        public String state() {
            return state;
        }

        public String detail() {
            return "";
        }

        public long elapsedMs() {
            return elapsed;
        }

        public long remainingMs() {
            return remaining;
        }

        public double countdownLeftS() {
            return 10;
        }

        public Renderer.Analysis analysis() {
            return analysis;
        }

        public float brightness() {
            return 0.5f;
        }
    }

    static Run run(File dir, final SkySim sim, final int frames, long seed, long startMs, BrightStars catalog, SatCatalog cat,
                   boolean satellites) throws Exception {
        dir.mkdirs();
        final SensorSpec spec = sim.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        File root = new File(dir, "sessions");
        root.mkdirs();
        SessionStore store = SessionStore.create(root);
        final SessionSky sky = new SessionSky();
        sky.latDeg = SatSessionTest.LAT;
        sky.lonDeg = SatSessionTest.LON;
        sky.altM = 0;
        sky.accuracyM = 30;
        sky.locationSource = "simulation";
        sky.clockWallMs = startMs + Math.round(SatSessionTest.CLOCK_ERR_S * 1000);
        sky.clockTimestampNs = 1_000_000_000L;
        sky.clockValid = true;
        sky.catalog = catalog;
        final Run run = new Run();
        final List<String> saved = new ArrayList<String>();
        final MissionControl mc = new MissionControl("", "", new MissionDemo.ImageIoJpeg(), new MissionControl.Keep() {
            public void namedStars(String json) {
            }

            public void patches(String json) {
                saved.add(json);
            }
        });
        run.mc = mc;
        final StringBuilder logText = new StringBuilder();
        mc.setLogger(new MissionControl.Logger() {
            public void log(String line) {
                synchronized (logText) {
                    logText.append(line).append('\n');
                }
                System.out.println("  " + line);
            }
        });
        final FakeStatus st = new FakeStatus();
        mc.setStatus(st);
        // the phone's clock is the session's own: the satellites coming are the sim's
        final long[] simNow = {startMs - 30_000};
        mc.setClock(new MissionControl.Clock() {
            public long now() {
                return simNow[0];
            }
        });
        mc.setCatalogue(catalog);
        mc.newSession(sky, catalog, cat, sim.W, sim.H, frames, true);
        mc.tickNow(simNow[0]);
        st.state = "capturing";
        simNow[0] = startMs;
        mc.captureStarted(simNow[0]);
        mc.tickNow(simNow[0]);
        final int[] version = {0};
        UnitProcessor up = new UnitProcessor(store, spec, 1, 3, new UnitProcessor.Listener() {
            @Override
            public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {
                mc.unit(rec, live, unitsDone);
                RgbImage img = src.preview().image();
                if (img != null) {
                    st.analysis = Renderer.analyze(img, spec);
                    int f = com.brandon.nightsky.processing.PreviewAccumulator.FACTOR;
                    mc.livePicture(img, new int[]{img.x0 * f, img.y0 * f, img.cropWidth() * f, img.cropHeight() * f}, ++version[0]);
                }
                st.elapsed = (long) ((sim.frameStart(unitsDone - 1) + sim.frameExp) * 1000);
                st.remaining = (long) ((sim.frameStart(frames - 1) + sim.frameExp) * 1000) - st.elapsed;
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
            }
        });
        up.setWatcher(mc.watcher(up));
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < frames; i++) {
            short[] buf = up.obtainBuffer(60000);
            UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
            sim.render(i, buf, info, seed);
            // the phone is at the start of this frame: what crosses in the next minute and a half is coming
            mc.settle();
            simNow[0] = startMs + Math.round(sim.frameStart(i) * 1000);
            mc.tickNow(simNow[0]);
            up.submit(buf, info);
        }
        up.finish();
        mc.settle();
        simNow[0] = startMs + Math.round((sim.frameStart(frames - 1) + sim.frameExp) * 1000);
        System.out.printf(Locale.US, "capture with Mission Control watching: %d ms%n", System.currentTimeMillis() - t0);
        st.state = "developing";
        mc.tickNow(simNow[0]);
        run.sky = mc.skyJson();
        PostProcessor pp = new PostProcessor(store, spec);
        pp.sky = sky;
        pp.satellites = cat;
        PostProcessor.Output o = pp.run(false, new Progress() {
            @Override
            public void update(String stage, float fraction) {
            }

            @Override
            public boolean cancelled() {
                return false;
            }
        });
        run.out = o;
        System.out.println("developed: " + o.summary);
        MissionControl.Photo p = new MissionControl.Photo();
        p.jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9};
        p.width = 640;
        p.height = 480;
        p.sol = o.solution;
        p.jdUtc = o.solutionJd;
        if (o.analysis != null) p.crop = new int[]{o.analysis.cx0, o.analysis.cy0, o.analysis.cw, o.analysis.ch};
        p.streaks = o.streaks;
        p.meteors = o.meteors.size();
        p.startMs = startMs;
        p.endMs = startMs + Math.round((sim.frameStart(frames - 1) + sim.frameExp) * 1000);
        p.seconds = sim.frameStart(frames - 1) + sim.frameExp;
        p.fresh = true;
        p.constellations.add("Cygnus");
        mc.photo(p);
        mc.settle();
        st.state = "done";
        mc.tickNow(simNow[0]);
        run.events = mc.log.since(0);
        run.eventsJson = mc.eventsJson(0);
        run.song = mc.songJson();
        run.patches = mc.patchesJson();
        String photoSky = mc.skyJson();
        java.nio.file.Files.write(new File(dir, "events.json").toPath(), run.eventsJson.getBytes("UTF-8"));
        if (run.sky != null) java.nio.file.Files.write(new File(dir, "sky-live.json").toPath(), run.sky.getBytes("UTF-8"));
        if (photoSky != null) java.nio.file.Files.write(new File(dir, "sky-photo.json").toPath(), photoSky.getBytes("UTF-8"));
        if (run.song != null) java.nio.file.Files.write(new File(dir, "song.json").toPath(), run.song.getBytes("UTF-8"));
        java.nio.file.Files.write(new File(dir, "patches.json").toPath(), run.patches.getBytes("UTF-8"));
        for (MissionLog.Event e : run.events) {
            System.out.printf(Locale.US, "  [%s] %s%s%n", e.kind, e.text, e.say.isEmpty() ? "" : "  (says: " + e.say + ")");
            if (e.snap >= 0) {
                byte[] j = mc.snap(e.snap);
                if (j != null) java.nio.file.Files.write(new File(dir, "snap-" + e.snap + ".jpg").toPath(), j);
            }
        }
        java.nio.file.Files.copy(store.logFile().toPath(), new File(dir, "session.log").toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        store.deleteBulk();
        mc.shutdown();
        return run;
    }

    /** The story's shape: the sky solved live, the photo's end, patches; the JSON the page reads parses. */
    static boolean story(Run r, boolean satellites) {
        boolean solved = false, done = false, patch = false, firstLight = false;
        int coming = 0;
        for (MissionLog.Event e : r.events) {
            if (MissionLog.SKY.equals(e.kind)) solved = true;
            if (MissionLog.PHASE.equals(e.kind) && "done".equals(e.name)) done = true;
            if (MissionLog.PATCH.equals(e.kind)) {
                patch = true;
                firstLight |= "first-light".equals(e.name);
            }
            if (MissionLog.SAT_COMING.equals(e.kind)) coming++;
        }
        boolean parsed = true;
        try {
            Object ev = JsonTree.parse(r.eventsJson);
            parsed &= ev instanceof Map && ((Map<?, ?>) ev).containsKey("state");
            parsed &= r.sky != null && JsonTree.parse(r.sky) instanceof Map;
            parsed &= r.song != null && JsonTree.parse(r.song) instanceof Map;
            parsed &= JsonTree.parse(r.patches) instanceof Map;
        } catch (RuntimeException e) {
            System.out.println("JSON: " + e);
            parsed = false;
        }
        int songNotes = 0;
        if (r.song != null) {
            JsonTree.Obj o = JsonTree.object(r.song);
            songNotes = o.list("notes").size();
        }
        System.out.printf(Locale.US, "story: sky solved live %s, %d satellites announced as coming, photo's end %s, patches %s (first light %s), "
                + "song of %d notes, JSON %s%n", solved, coming, done, patch, firstLight, songNotes, parsed ? "parses" : "BROKEN");
        return solved && done && patch && firstLight && parsed && songNotes >= 20 && (!satellites || coming >= 3);
    }
}
