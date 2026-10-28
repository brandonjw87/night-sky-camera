package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.LiveStats;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.MergeProcessor;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionLibrary;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TreeSet;

/**
 * The session library on disk (G1, G3): masters, recipes and the frames budget, on simulated sessions.
 * <ul>
 * <li>Rounding the stack to half floats (what a master keeps) changes the photo by at most a level here and there.</li>
 * <li>A master read back is the same picture, bit for bit, and renders the same photo, pixel for pixel, even after
 * the session's frames are deleted.</li>
 * <li>Recipes read back as written (the same stack key), and saving never writes over an earlier one.</li>
 * <li>The frames budget deletes the oldest developed sessions' frames first, logs it, and keeps their photos.</li>
 * <li>Sessions of the same sky from the same place, on two nights, are offered for merging; another patch of sky
 * and star trails are not.</li>
 * </ul>
 * Usage: LibraryTest outDir [scale=2] [frames=12]
 */
public final class LibraryTest {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "library-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 12;
        outDir.mkdirs();
        BrightStars catalog = SimRunner.loadCatalog();
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        long start1 = SimRunner.DEFAULT_TIME_MS, start2 = start1 + MergeTest.SIDEREAL_DAY_MS;
        double lon = SimRunner.DEFAULT_LON;
        File root = new File(outDir, "sessions");
        root.mkdirs();

        SkySim n1 = MergeTest.night(W, H, truth, catalog, start1, lon, scale);
        SensorSpec spec = n1.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        SessionStore s1 = MergeTest.capture(n1, spec, root, frames, 7);
        writeSettings(s1, spec, MergeTest.sky(n1, catalog, start1, lon), start1, false);

        // 1) develop, as the app does: the stack, rounded to half floats, then the photo from that
        PostProcessor p1 = new PostProcessor(s1, spec);
        p1.sky = MergeTest.sky(n1, catalog, start1, lon);
        PostProcessor.Output o1 = p1.run(false, MergeTest.quiet());
        System.out.println("developed: " + o1.summary);
        Recipe r1 = Recipe.single(s1.id(), false);
        Renderer.Params rp = r1.params();
        int[] exact = Master.render(o1.image, o1.analysis, rp);
        Master.quantize(o1, spec, false);
        int[] rounded = Master.render(o1.image, o1.analysis, rp);
        int[] diff = compare(exact, rounded);
        // a level here and there: the stretch is steep in the faint sky, and bright star cores near clipping move most.
        // Since 1.4 the noise reduction sets stars aside by a threshold, so a noise peak right at it can go either way
        // with the smallest change: a few pixels in a hundred thousand (alone in the sky) move by more than 8 levels
        MergeTest.check(diff[0] <= exact.length / 50 && diff[2] <= exact.length / 1000 && diff[3] <= exact.length / 10000,
                String.format(Locale.US, "half floats change the photo little: %.2f%% of pixels by one level, %.3f%% by more, "
                        + "%.4f%% by more than 8 levels (at most %d)", 100.0 * (diff[0] - diff[2]) / exact.length,
                        100.0 * diff[2] / exact.length, 100.0 * diff[3] / exact.length, diff[1]));

        // 2) its master, written and read back
        Master.Meta m1 = master(s1, o1, spec, r1, start1, n1, true);
        Master.Loaded l1 = Master.read(s1.dir, m1.key);
        MergeTest.check(l1 != null && same(l1.image, o1.image), "the master reads back as the same picture, bit for bit ("
                + SessionLibrary.gb(m1.bytes) + ", key " + m1.key + ")");
        MergeTest.check(l1 != null && l1.meta.solutionR != null && l1.meta.starX != null && l1.meta.starX.length > 50
                && l1.meta.model != null && l1.meta.model.fx == o1.model.fx, "it knows where it points (plate solution, lens model, "
                + (l1 != null && l1.meta.starX != null ? l1.meta.starX.length : 0) + " stars)");
        String photoHash = Master.pixelHash(rounded);
        String fromMaster = renderHash(l1, spec, r1);
        MergeTest.check(photoHash.equals(fromMaster), "rendered from the master: the developed photo, pixel for pixel");

        // 3) recipes: saved, never written over, read back as written
        r1.note = "developed";
        r1.stackKey = m1.key;
        r1.masterHash = m1.hash;
        r1.pixelHash = photoHash;
        r1.summary = o1.summary;
        File f1 = r1.save(s1.dir);
        Recipe r2 = r1.edit();
        r2.brightness = 0.62f;
        r2.parts.get(0).include = Recipe.parseRanges("0-4,6,8-" + (frames - 1));
        r2.parts.get(0).includeNote = "frames 5 and 7 left out by hand";
        r2.taps.put(5, new float[]{100.25f, 200.5f, 101f, 203.75f, 300f, 400f, 302.5f, 401.125f});
        r2.note = "edited";
        File f2 = r2.save(s1.dir);
        List<Recipe> saved = Recipe.list(s1.dir);
        MergeTest.check(saved.size() == 2 && saved.get(0).number == 1 && saved.get(1).number == 2 && f1.getName().equals("r001.json")
                && f2.getName().equals("r002.json"), "two renders saved as r001 and r002, the first untouched");
        Recipe back = saved.get(1);
        MergeTest.check(back.stackJson().equals(r2.stackJson()) && back.stackKey().equals(r2.stackKey()) && !r2.stackKey().equals(r1.stackKey())
                && back.brightness == 0.62f && back.taps.get(5)[7] == 401.125f && "6".equals(rangeOf(back, 5, 7)),
                "a recipe reads back as written: same stack key " + r2.stackKey() + " (the develop one " + r1.stackKey() + ")");
        TreeSet<Integer> ts = Recipe.parseRanges("3,0-2,9,5-7");
        MergeTest.check("0-3,5-7,9".equals(Recipe.ranges(ts)), "frame ranges: " + Recipe.ranges(ts));
        // a third save after one is deleted by hand still never reuses a number below the newest
        Recipe r3 = r1.edit();
        r3.note = "again";
        File f3 = r3.save(s1.dir);
        MergeTest.check(f3.getName().equals("r003.json") && Recipe.fromJson(new String(java.nio.file.Files.readAllBytes(f1.toPath()),
                "UTF-8")).pixelHash.equals(photoHash), "a third render is r003; r001 still holds the developed photo's hash");

        // 4) masters beyond the develop one: the newest two kept
        for (int k = 0; k < 3; k++) {
            Master.Meta mk = Master.meta(o1, spec, false);
            mk.key = "extra" + k;
            mk.createdMs = 1000 + k;
            Master.write(s1.dir, mk, o1.image);
        }
        List<String> gone = Master.prune(s1.dir, SessionLibrary.EXTRA_MASTERS, null);
        List<Master.Meta> left = Master.list(s1.dir);
        MergeTest.check(gone.equals(Collections.singletonList("extra0")) && left.size() == 3, "pruning keeps the develop master and the "
                + "newest two others: deleted " + gone + ", " + left.size() + " left");

        // 4b) a merge's description and a report kept with a master: read back as written, and deleted with it
        MergeProcessor.Result mr = new MergeProcessor.Result();
        mr.total = "47 minutes over 3 nights";
        mr.sessions = 3;
        mr.nights = 3;
        mr.coverW = 4;
        mr.coverH = 3;
        mr.coverage = new byte[]{0, 1, 2, 3, 3, 3, 3, 3, 12, 2, 1, 0};
        MergeProcessor.Part pa = new MergeProcessor.Part();
        pa.id = s1.id();
        pa.label = "22 Oct";
        pa.framesTotal = 16;
        pa.framesUsed = 15;
        pa.seconds = 300;
        pa.outline = new float[]{0, 0, 10, 0, 10, 5, 0, 5};
        MergeProcessor.Part pb = new MergeProcessor.Part();
        pb.id = "x";
        pb.label = "23 Oct";
        pb.skipped = "too few shared stars";
        pb.signal = 0.9;
        pb.photometryStars = 33;
        pb.matched = 41;
        pb.rms = 0.35;
        mr.parts.add(pa);
        mr.parts.add(pb);
        Master.Meta mm = Master.meta(o1, spec, false);
        mm.key = "merged0";
        mm.createdMs = 999;
        mm.merge = MergeProcessor.describe(mr);
        Master.write(s1.dir, mm, o1.image);
        Master.writeReport(s1.dir, "merged0", "{\"file\":\"report\"}");
        Master.Meta mb = null;
        for (Master.Meta x : Master.list(s1.dir)) if (x.key.equals("merged0")) mb = x;
        MergeProcessor.Result mback = mb != null ? MergeProcessor.readDescription(mb.merge) : null;
        boolean okDesc = mback != null && mback.total.equals(mr.total) && mback.nights == 3 && mback.coverW == 4 && mback.coverH == 3
                && mback.coverage[8] == 9 && mback.coverage[3] == 3 && mback.parts.size() == 2 && mback.parts.get(0).skipped == null
                && "too few shared stars".equals(mback.parts.get(1).skipped) && mback.parts.get(0).outline.length == 8
                && Math.abs(mback.parts.get(1).rms - 0.35) < 1e-9 && Double.isNaN(mback.parts.get(0).rms)
                && Math.abs(mback.parts.get(1).signal - 0.9) < 1e-9 && mback.parts.get(0).framesUsed == 15;
        String rep = Master.readReport(s1.dir, "merged0");
        Master.delete(s1.dir, "merged0");
        MergeTest.check(okDesc && "{\"file\":\"report\"}".equals(rep) && Master.readReport(s1.dir, "merged0") == null
                && !new File(new File(s1.dir, Master.DIR), "merged0.json").exists(), "a merge's parts, coverage (12 kept as 9) and "
                + "total read back from its master, its report too, and both go with it");

        // 5) a second night of the same sky, a third of another patch, and star trails: what may be merged
        SkySim n2 = MergeTest.night(W, H, truth, catalog, start2, lon, scale);
        n2.setAttitude(31, 3.6, 95);
        SessionStore s2 = MergeTest.capture(n2, spec, root, frames, 8);
        writeSettings(s2, spec, MergeTest.sky(n2, catalog, start2, lon), start2, false);
        develop(s2, spec, MergeTest.sky(n2, catalog, start2, lon), start2, n2);
        SkySim n3 = MergeTest.night(W, H, truth, catalog, start2 + 3 * 3600_000L, lon, scale);
        SessionStore s3 = MergeTest.capture(n3, spec, root, frames, 9);
        writeSettings(s3, spec, MergeTest.sky(n3, catalog, start2 + 3 * 3600_000L, lon), start2 + 3 * 3600_000L, false);
        develop(s3, spec, MergeTest.sky(n3, catalog, start2 + 3 * 3600_000L, lon), start2 + 3 * 3600_000L, n3);
        SessionStore s4 = MergeTest.capture(n1, spec, root, Math.max(4, frames / 3), 10);
        writeSettings(s4, spec, MergeTest.sky(n1, catalog, start1, lon), start1, true);
        s4.setState("developed");
        s1.setState("developed");
        // as if s4 were minutes added to night 1 ("Add 5 minutes"): they point at its folder
        Properties p4 = s4.readSettings();
        p4.setProperty("continuesFrom", s1.dir.getName());
        s4.writeSettings(p4);
        List<SessionLibrary.Entry> all = SessionLibrary.list(root);
        SessionLibrary.Entry e1 = find(all, s1.id());
        List<SessionLibrary.Entry> cand = SessionLibrary.mergeCandidates(e1, all);
        MergeTest.check(cand.size() == 1 && cand.get(0).id.equals(s2.id()), "merge candidates for night 1: " + ids(cand)
                + " (night 2 of the same sky; not 3 hours later, nor the star trails)");
        MergeTest.check(e1.hasFrames && e1.renders.size() == 3 && e1.developMaster() != null && e1.centre != null,
                "the library sees night 1's frames, 3 renders and its develop master");
        List<SessionLibrary.Entry> adds = SessionLibrary.continuations(e1, all);
        MergeTest.check(adds.size() == 1 && adds.get(0).id.equals(s4.id()) && SessionLibrary.continuations(find(all, s2.id()), all).isEmpty(),
                "minutes added to night 1 are found by its folder: " + ids(adds));

        // 6) the frames budget: the oldest developed sessions lose their frames first, and say so in their logs
        long b1 = SessionLibrary.frameBytes(s1), b2 = SessionLibrary.frameBytes(s2), b3 = SessionLibrary.frameBytes(s3);
        long b4 = SessionLibrary.frameBytes(s4);
        // room for the newest two only (sessions are listed by their folder names, which are their start times here)
        List<SessionStore> order = SessionStore.list(root);
        long budget = SessionLibrary.frameBytes(order.get(0)) + SessionLibrary.frameBytes(order.get(1)) + 1024;
        SessionLibrary.Trim t = SessionLibrary.trim(root, budget, 0, -1, null);
        for (String line : t.lines) System.out.println("  trim: " + line);
        boolean oldestGone = SessionLibrary.frameBytes(order.get(order.size() - 1)) == 0 && SessionLibrary.frameBytes(order.get(order.size() - 2)) == 0;
        boolean newestKept = SessionLibrary.frameBytes(order.get(0)) > 0 && SessionLibrary.frameBytes(order.get(1)) > 0;
        MergeTest.check(oldestGone && newestKept && t.lines.size() == 2 && t.keptFrameBytes <= budget
                && order.get(order.size() - 1).readLog().contains("decision: deleted the frames"), String.format(Locale.US,
                "a budget of %s kept the newest two sessions' frames and deleted the oldest two's (%s freed), logged in each",
                SessionLibrary.gb(budget), SessionLibrary.gb(t.freedBytes)));
        // a new session that needs more room than is free: the next oldest goes, whatever the budget
        SessionLibrary.Trim t2 = SessionLibrary.trim(root, Long.MAX_VALUE, 10L << 30, (10L << 30) - 1, Collections.singleton(order.get(0).id()));
        MergeTest.check(t2.lines.size() == 1 && SessionLibrary.frameBytes(order.get(1)) == 0 && SessionLibrary.frameBytes(order.get(0)) > 0,
                "short of room for a new session: the oldest session with frames gave them up, the one kept back didn't");
        // night 1 without its frames still renders its photo from the master
        SessionLibrary.Entry e1b = SessionLibrary.entry(s1);
        String again = renderHash(Master.read(s1.dir, m1.key), spec, r1);
        MergeTest.check(!e1b.hasFrames && photoHash.equals(again) && Recipe.list(s1.dir).size() == 3,
                "night 1's frames are gone, its photo still renders the same from its master, its recipes stay");
        System.out.printf(Locale.US, "frames: %s, %s, %s, %s; masters %s%n", SessionLibrary.gb(b1), SessionLibrary.gb(b2), SessionLibrary.gb(b3),
                SessionLibrary.gb(b4), SessionLibrary.gb(Master.bytes(s1.dir)));

        // 7) automatic length: what the frames so far are worth, in clear frames
        List<LiveStats.Unit> us = new ArrayList<LiveStats.Unit>();
        for (int i = 0; i < 10; i++) us.add(unit(1.0f, 10f, null));
        for (int i = 0; i < 4; i++) us.add(unit(0.7f, 10f, null));
        for (int i = 0; i < 3; i++) us.add(unit(1.0f, 14f, null));
        us.add(unit(0.3f, 10f, "clouds (30% light)"));
        us.add(unit(1.0f, 10f, "a bump"));
        double w = LiveStats.worth(us), expectW = 10 + 4 * 0.49 + 3 * (10 / 14.0) * (10 / 14.0);
        MergeTest.check(Math.abs(w - expectW) < 1e-6, String.format(Locale.US, "21 frames worth %.2f clear ones: 10 clear, 4 hazy at 70%% "
                + "count 0.49 each, 3 under a brighter sky count 0.51 each, clouds and a bump count nothing (expected %.2f)", w, expectW));
        MergeTest.finish();
    }

    static LiveStats.Unit unit(float t, float noise, String reason) {
        LiveStats.Unit u = new LiveStats.Unit();
        u.ok = true;
        u.transparency = t;
        u.noise = noise;
        u.reason = reason;
        return u;
    }

    static void writeSettings(SessionStore s, SensorSpec spec, SessionSky sky, long startMs, boolean trails) throws Exception {
        Properties p = spec.toProperties();
        p.setProperty("mode", trails ? "trails" : "deep");
        p.setProperty("durationSec", "300");
        p.setProperty("state", "captured");
        p.setProperty("capture.startWallMs", Long.toString(startMs));
        sky.toProperties(p);
        s.writeSettings(p);
    }

    static void develop(SessionStore s, SensorSpec spec, SessionSky sky, long startMs, SkySim sim) throws Exception {
        PostProcessor pp = new PostProcessor(s, spec);
        pp.sky = sky;
        PostProcessor.Output o = pp.run(false, MergeTest.quiet());
        Master.quantize(o, spec, false);
        Recipe r = Recipe.single(s.id(), false);
        Master.Meta m = master(s, o, spec, r, startMs, sim, true);
        r.stackKey = m.key;
        r.note = "developed";
        r.save(s.dir);
        s.setState("developed");
        System.out.println("developed " + s.id() + ": " + o.summary);
    }

    static Master.Meta master(SessionStore s, PostProcessor.Output o, SensorSpec spec, Recipe r, long startMs, SkySim sim, boolean develop)
            throws Exception {
        Master.Meta m = Master.meta(o, spec, false);
        m.key = r.stackKey();
        m.stack = r.stackJson();
        m.develop = develop;
        m.sessions.add(s.id());
        m.startMs = startMs;
        m.lat = sim.latitudeDeg;
        m.lon = SimRunner.DEFAULT_LON;
        Master.write(s.dir, m, o.image);
        return m;
    }

    static String renderHash(Master.Loaded l, SensorSpec spec, Recipe r) {
        if (l == null) return "none";
        Renderer.Analysis a = Master.analyze(l.image, Master.colourSpec(spec, l.meta), l.meta.trails);
        return Master.pixelHash(Master.render(l.image, a, r.params()));
    }

    /** Pixels that differ, the largest difference of a channel (levels), and pixels that differ by more than one level. */
    /** Pixels that differ, the most they differ by, how many by more than a level, and by more than 8. */
    static int[] compare(int[] a, int[] b) {
        int n = 0, mx = 0, more = 0, far = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] == b[i]) continue;
            n++;
            int d = 0;
            for (int s = 0; s < 24; s += 8) d = Math.max(d, Math.abs(((a[i] >> s) & 0xFF) - ((b[i] >> s) & 0xFF)));
            mx = Math.max(mx, d);
            if (d > 1) more++;
            if (d > 8) far++;
        }
        return new int[]{n, mx, more, far};
    }

    static boolean same(RgbImage a, RgbImage b) {
        return a.width == b.width && a.height == b.height && a.x0 == b.x0 && a.y0 == b.y0 && a.x1 == b.x1 && a.y1 == b.y1
                && Arrays.equals(a.r, b.r) && Arrays.equals(a.g, b.g) && Arrays.equals(a.b, b.b) && Arrays.equals(a.clip, b.clip);
    }

    static SessionLibrary.Entry find(List<SessionLibrary.Entry> all, String id) {
        for (SessionLibrary.Entry e : all) if (e.id.equals(id)) return e;
        throw new IllegalStateException("no session " + id);
    }

    static String ids(List<SessionLibrary.Entry> l) {
        StringBuilder sb = new StringBuilder("[");
        for (SessionLibrary.Entry e : l) sb.append(sb.length() > 1 ? ", " : "").append(e.id);
        return sb.append(']').toString();
    }

    /** The frames between a and b (exclusive) that a recipe's base part includes, as ranges. */
    static String rangeOf(Recipe r, int a, int b) {
        TreeSet<Integer> s = new TreeSet<Integer>();
        for (Integer v : r.parts.get(0).include) if (v > a && v < b) s.add(v);
        return Recipe.ranges(s);
    }
}
