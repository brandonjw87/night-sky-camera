package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.EdgeMask;
import com.brandon.nightsky.processing.Framing;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.Stats;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * Re-editing a session (G3) on a simulated night with a branch over the left edge:
 * <ul>
 * <li>Trimming to a stretch of time, leaving frames out by hand, choosing the reference frame and tapping stars
 * stacks just those frames, as asked.</li>
 * <li>The foreground is found on its own (the branch), painting adds to it and takes from it, its stars stay out of
 * the alignment, and keeping the ground still gives the branch sharp edges where the lined-up stack smears it, without
 * darkening the sky beside it.</li>
 * <li>Stretch, exposure, black point, saturation, look, crop and north up make the photo; north up agrees with the
 * simulated sky.</li>
 * <li>Every render is a saved recipe, none written over, and running a saved recipe again from its file gives the same
 * stacked picture and the same photo, bit for bit; a change of look alone renders from the master without stacking.</li>
 * </ul>
 * Usage: G3Test outDir [scale=2] [frames=16]
 */
public final class G3Test {
    public static void main(String[] args) throws Exception {
        File outDir = new File(args.length > 0 ? args[0] : "g3-out");
        int scale = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int frames = args.length > 2 ? Integer.parseInt(args[2]) : 16;
        outDir.mkdirs();
        BrightStars catalog = SimRunner.loadCatalog();
        int W = 4080 / scale, H = 3072 / scale;
        CameraModel truth = CameraModel.nominal(W, H, 2875.0 / scale);
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        long start = SimRunner.DEFAULT_TIME_MS;
        double lon = SimRunner.DEFAULT_LON;
        File root = new File(outDir, "sessions");
        root.mkdirs();

        SkySim sim = MergeTest.night(W, H, truth, catalog, start, lon, scale);
        sim.branch = true;
        SensorSpec spec = sim.spec(true);
        ColorMath.compute(spec, ColorMath.DEFAULT_CCT);
        SessionStore s = MergeTest.capture(sim, spec, root, frames, 7);
        SessionSky sky = MergeTest.sky(sim, catalog, start, lon);
        LibraryTest.writeSettings(s, spec, sky, start, false);

        // 1) developed as it was set up
        Recipe r1 = Recipe.single(s.id(), false);
        r1.note = "developed";
        Made d = make(s, spec, sky, r1);
        System.out.println("developed: " + d.o.summary);
        MergeTest.check(d.o.groundShare == 0 && !hasDecision(d.o, "foreground"), "developing as set up leaves the ground alone");

        // 2) the foreground found on its own: the branch
        boolean[] auto = EdgeMask.automatic(s, spec);
        float[] bt = sim.branchTransmission();
        int cw = EdgeMask.cells(W), ch = EdgeMask.cells(H);
        int[] cover = branchCover(auto, bt, W, H);
        MergeTest.check(auto != null && cover[0] >= 0.97 * cover[1] && cover[2] <= 0.02 * cw * ch, String.format(Locale.US,
                "the branch is found on its own: %d of its %d cells, %d cells of open sky with it (%s)", cover[0], cover[1], cover[2],
                auto != null ? EdgeMask.where(auto, cw, ch) : "nothing found"));

        // 3) the edit: frames 2 to 13 without 7, reference frame 9, stars tapped in frame 11, the foreground painted
        //    (a patch of sky taken for ground, a stub of the branch taken back as sky) and kept still, and a new look
        Recipe e = r1.edit();
        e.parts.get(0).include = Recipe.parseRanges("2-6,8-13");
        e.parts.get(0).includeNote = "between 0:17 and 1:55, frame 7 left out by hand";
        e.reference = 9;
        float[] taps = taps(sim, 9, 11, 3);
        e.taps.put(11, taps);
        EdgeMask paint = new EdgeMask(W, H);
        float gx = 0.85f * W, gy = 0.85f * H, sx = 0, sy = 0;
        // a stub of the branch the finding took for ground, to take back as sky: the found cell furthest along a twig
        for (int cy = 0; cy < ch && auto != null; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                float px = (cx + 0.5f) * EdgeMask.CELL, py = (cy + 0.5f) * EdgeMask.CELL;
                if (!auto[cy * cw + cx] || py < 0.31f * H || py > 0.40f * H || px > 0.28f * W || bt[(int) py * W + (int) px] >= 0.5f) continue;
                if (px > sx) {
                    sx = px;
                    sy = py;
                }
            }
        }
        paint.paintDisc(gx, gy, 40, EdgeMask.GROUND);
        paint.paintDisc(sx, sy, 30, EdgeMask.SKY);
        e.mask = paint.save(s.dir);
        e.freezeGround = true;
        e.stretch = 6.5f;
        e.ev = 0.3f;
        e.black = 0.2f;
        e.saturation = 1.2f;
        e.look = Recipe.LOOK_PUNCHY;
        e.crop = "3:2";
        e.northUp = true;
        e.note = "edited";
        MergeTest.check(!e.sameStack(r1) && e.mask.startsWith("masks/") && EdgeMask.load(s.dir, e.mask) != null
                && e.mask.equals(paint.save(s.dir)), "the painting is kept once, named by what it holds: " + e.mask);
        Made a = make(s, spec, sky, e);
        System.out.println("edited: " + a.o.summary);
        for (String dd : a.o.decisions) System.out.println("    " + dd);
        int used = 0;
        boolean outside = false;
        for (PostProcessor.FrameReport fr : a.o.frames) {
            if (fr.used) used++;
            if (!e.parts.get(0).include.contains(fr.index)) outside = true;
        }
        MergeTest.check(a.o.unitsTotal == 11 && !outside && used >= 10 && hasDecision(a.o, "frames: 11 of " + frames),
                String.format(Locale.US, "trimmed: %d of the %d frames chosen were stacked, none outside them", used, a.o.unitsTotal));
        MergeTest.check(a.o.referenceUnit == 9 && hasDecision(a.o, "as chosen"), "the reference frame is the one chosen (frame "
                + a.o.referenceUnit + ")");
        MergeTest.check(hasDecision(a.o, "foreground (") && hasDecision(a.o, "kept still"), "its stars stay out of the alignment "
                + "and it is kept still (" + String.format(Locale.US, "%.1f%%", 100 * a.o.groundShare) + " of the frame)");
        boolean[] g = paint.ground(auto);
        MergeTest.check(EdgeMask.in(g, W, gx, gy) && !EdgeMask.in(g, W, sx, sy) && auto != null && EdgeMask.in(auto, W, sx, sy),
                "painting adds ground the finding missed and takes back what it took for ground");
        // kept still, the sky beside the branch stays near the open sky's level and the branch's edge is clearly darker than
        // in the lined-up stack, which smears the moving sky over it (the lens's blur still softens it a pixel or two)
        int[] dist = distances(bt, W, H);
        double[] e1 = edge(d.image, bt, dist, W, H), e2 = edge(a.image, bt, dist, W, H);
        MergeTest.check(e2[0] > 0.8 && e2[0] > e1[0] + 0.1 && e2[1] < 0.75 * e1[1], String.format(Locale.US, "the branch keeps its edge: the sky "
                + "beside it %.2f of the open sky's level above the branch's (lined up %.2f); the branch %.2f of the sky (lined up %.2f)",
                e2[0], e1[0], e2[1], e1[1]));

        // 4) the photo: its crop and north up, which agrees with the simulated sky
        MergeTest.check(Math.abs(a.framing.outW / (double) a.framing.outH - 1.5) < 0.01 && a.north != null,
                String.format(Locale.US, "cropped to 3:2 and turned north up: %s", a.framing.describe()));
        double[] np = sim.poleInCamera();
        double[] tn = Framing.north(a.o.model, toPole(np), a.o.analysis.cx0 + a.o.analysis.cw / 2.0, a.o.analysis.cy0 + a.o.analysis.ch / 2.0);
        double off = tn != null && a.north != null ? Math.toDegrees(Math.acos(Math.min(1, tn[0] * a.north[0] + tn[1] * a.north[1]))) : 99;
        MergeTest.check(off < 1.0, String.format(Locale.US, "north from the plate solution is %.2f degrees from the simulated sky's", off));
        SimRunner.writePng(a.photo, a.framing.outW, a.framing.outH, new File(outDir, "edited.png"));
        SimRunner.writePng(d.photo, d.framing.outW, d.framing.outH, new File(outDir, "developed.png"));
        // the branch in both stacks, as the develop step renders them, side by side to look at
        int[] pd = Master.render(d.image, d.o.analysis, new Renderer.Params()), pa = Master.render(a.image, a.o.analysis,
                new Renderer.Params());
        int bx0 = 0, by0 = (int) (0.22 * H), bw = (int) (0.30 * W), bh = (int) (0.28 * H);
        int[] side = new int[2 * bw * bh];
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) {
                side[y * 2 * bw + x] = pixelAt(pd, d.o.analysis, bx0 + x, by0 + y);
                side[y * 2 * bw + bw + x] = pixelAt(pa, a.o.analysis, bx0 + x, by0 + y);
            }
        }
        SimRunner.writePng(side, 2 * bw, bh, new File(outDir, "branch_lined_up_vs_still.png"));

        // 5) the saved recipe, run again from its file: the same stacked picture and the same photo
        List<Recipe> saved = Recipe.list(s.dir);
        Recipe back = Recipe.fromJson(new String(Files.readAllBytes(Recipe.file(s.dir, 2).toPath()), "UTF-8"));
        MergeTest.check(saved.size() == 2 && back.number == 2 && back.stackKey.equals(a.recipe.stackKey) && back.pixelHash.equals(a.recipe.pixelHash),
                "renders saved as r001 and r002, with their stack key and pixel hash");
        Recipe again = back.edit();
        again.note = "the same recipe, run again";
        Made b = make(s, spec, sky, again);
        MergeTest.check(b.masterHash.equals(back.masterHash) && b.recipe.stackKey.equals(back.stackKey),
                "run again from its file: the same stacked picture, bit for bit (" + back.masterHash.substring(0, 12) + ")");
        MergeTest.check(b.recipe.pixelHash.equals(back.pixelHash), "and the same photo, pixel for pixel (" + back.pixelHash.substring(0, 12) + ")");
        MergeTest.check(Recipe.list(s.dir).size() == 3 && Recipe.list(s.dir).get(1).pixelHash.equals(back.pixelHash),
                "the earlier renders are kept as they were (r001, r002, r003)");

        // 6) a new look alone: rendered from the master, no stacking
        Recipe look = back.edit();
        look.look = Recipe.LOOK_LINEAR;
        look.crop = "16:9";
        look.northUp = false;
        Master.Loaded l = Master.read(s.dir, back.stackKey);
        SensorSpec cs = Master.colourSpec(spec, l.meta);
        Renderer.Analysis la = Master.analyze(l.image, cs, l.meta.trails);
        Framing lf = look.framing(la.cw, la.ch, look.north(la, l.meta.model, l.meta.solutionR));
        int[] lp = Master.photo(l.image, la, look.params(), lf);
        Framing bf = back.framing(la.cw, la.ch, back.north(la, l.meta.model, l.meta.solutionR));
        String fromMaster = Master.pixelHash(Master.photo(l.image, la, back.params(), bf));
        MergeTest.check(look.sameStack(back) && fromMaster.equals(back.pixelHash) && Math.abs(lf.outW / (double) lf.outH - 16 / 9.0) < 0.01
                && !Master.pixelHash(lp).equals(back.pixelHash), "a new look renders from the master without stacking; the edited "
                + "photo from its master is the same, pixel for pixel");

        // 7) an older processing version is offered again
        Recipe old = back.edit();
        old.pipeline = PostProcessor.PIPELINE - 1;
        MergeTest.check(old.outdated() && !back.outdated(), "a render by an older processing version is marked to make again");

        // 8) the whole night with the ground kept still: the branch sharp, and the sky beside it as bright as further out
        //    (the lined-up stack takes the stars there only from the frames that saw them, not darkened by the branch)
        Recipe k = r1.edit();
        k.freezeGround = true;
        k.note = "kept still";
        Made fz = make(s, spec, sky, k);
        for (String dd : fz.o.decisions) if (dd.startsWith("foreground")) System.out.println("    " + dd);
        double[] e3 = edge(fz.image, bt, dist, W, H);
        double hd = halo(d.image, bt, dist, W, H), hf = halo(fz.image, bt, dist, W, H);
        MergeTest.check(e3[0] > 0.85 && e3[1] < 0.75 * e1[1], String.format(Locale.US, "kept still all night, the branch keeps its "
                + "edge: the sky beside it %.2f (lined up %.2f); the branch %.2f of the sky (lined up %.2f)", e3[0], e1[0], e3[1], e1[1]));
        MergeTest.check(hf > 0.97 && hf > hd + 0.03, String.format(Locale.US, "and the sky 4 to 12 px from the branch is %.3f of the "
                + "sky's level 40 to 56 px from it (lined up %.3f)", hf, hd));
        int[] pf = Master.render(fz.image, fz.o.analysis, new Renderer.Params());
        for (int y = 0; y < bh; y++) {
            for (int x = 0; x < bw; x++) side[y * 2 * bw + bw + x] = pixelAt(pf, fz.o.analysis, bx0 + x, by0 + y);
        }
        SimRunner.writePng(side, 2 * bw, bh, new File(outDir, "branch_lined_up_vs_still_all_night.png"));
        MergeTest.finish();
    }

    /** What making a recipe gave. */
    static final class Made {
        PostProcessor.Output o;
        RgbImage image;
        Recipe recipe;
        String masterHash;
        int[] photo;
        Framing framing;
        double[] north;
    }

    /**
     * Makes a recipe as the app does (G3): the stack as it says, rounded as its master keeps it, the master written,
     * the photo rendered, cut and turned, and the recipe saved with what it made.
     */
    static Made make(SessionStore s, SensorSpec spec, SessionSky sky, Recipe r) throws Exception {
        PostProcessor pp = new PostProcessor(s, spec);
        pp.sky = sky;
        String missing = r.configure(pp, s.dir);
        if (!missing.isEmpty()) System.out.println("  not used: " + missing);
        PostProcessor.Output o = pp.run(r.trails, MergeTest.quiet());
        Master.quantize(o, spec, r.trails);
        Master.Meta m = Master.meta(o, spec, r.trails);
        m.key = r.stackKey();
        m.stack = r.stackJson();
        m.develop = "developed".equals(r.note);
        m.sessions.add(s.id());
        Master.write(s.dir, m, o.image);
        Made made = new Made();
        made.o = o;
        made.image = o.image;
        made.masterHash = m.hash;
        made.north = r.north(o.analysis, o.model, o.solution != null ? o.solution.R : null);
        made.framing = r.framing(o.analysis.cw, o.analysis.ch, made.north);
        made.photo = Master.photo(o.image, o.analysis, r.params(), made.framing);
        r.stackKey = m.key;
        r.masterHash = m.hash;
        r.pixelHash = Master.pixelHash(made.photo);
        r.summary = o.summary;
        r.app = "test";
        r.save(s.dir);
        made.recipe = r;
        return made;
    }

    static boolean hasDecision(PostProcessor.Output o, String s) {
        for (String d : o.decisions) if (d.contains(s)) return true;
        return false;
    }

    /** Stars seen in frames a and b (the simulation's truth at mid exposure), as taps: the n brightest in both. */
    static float[] taps(SkySim sim, int a, int b, int n) {
        double[][] ta = sim.starTable(a), tb = sim.starTable(b);
        float[] out = new float[4 * n];
        boolean[] usedA = new boolean[ta.length];
        for (int k = 0; k < n; k++) {
            int best = -1;
            for (int i = 0; i < ta.length; i++) {
                if (usedA[i] || ta[i][0] < 100 || ta[i][1] < 100 || ta[i][0] > sim.W - 100 || ta[i][1] > sim.H - 100) continue;
                if (best < 0 || ta[i][2] > ta[best][2]) best = i;
            }
            usedA[best] = true;
            // the same star in frame b: the same catalogue flux, nearest
            int j = -1;
            double dj = 1e9;
            for (int i = 0; i < tb.length; i++) {
                if (tb[i][2] != ta[best][2]) continue;
                double dd = Math.hypot(tb[i][0] - ta[best][0], tb[i][1] - ta[best][1]);
                if (dd < dj) {
                    dj = dd;
                    j = i;
                }
            }
            out[4 * k] = (float) ta[best][0];
            out[4 * k + 1] = (float) ta[best][1];
            out[4 * k + 2] = (float) tb[j][0];
            out[4 * k + 3] = (float) tb[j][1];
        }
        return out;
    }

    /** Branch cells found (of those mostly branch), and open-sky cells taken with it, of the automatic finding. */
    static int[] branchCover(boolean[] g, float[] bt, int W, int H) {
        int cw = EdgeMask.cells(W), ch = EdgeMask.cells(H), found = 0, branch = 0, sky = 0;
        for (int cy = 0; cy < ch; cy++) {
            for (int cx = 0; cx < cw; cx++) {
                int dark = 0, n = 0;
                boolean near = false;
                for (int y = cy * EdgeMask.CELL; y < Math.min(H, (cy + 1) * EdgeMask.CELL); y++) {
                    for (int x = cx * EdgeMask.CELL; x < Math.min(W, (cx + 1) * EdgeMask.CELL); x++) {
                        n++;
                        if (bt[y * W + x] < 0.5f) dark++;
                    }
                }
                // open sky: no branch within 4 cells
                for (int y = Math.max(0, (cy - 4) * EdgeMask.CELL); y < Math.min(H, (cy + 5) * EdgeMask.CELL) && !near; y += 2) {
                    for (int x = Math.max(0, (cx - 4) * EdgeMask.CELL); x < Math.min(W, (cx + 5) * EdgeMask.CELL); x += 2) {
                        if (bt[y * W + x] < 0.5f) {
                            near = true;
                            break;
                        }
                    }
                }
                boolean in = g != null && g[cy * cw + cx];
                if (dark * 2 > n) {
                    branch++;
                    if (in) found++;
                } else if (!near && in) {
                    sky++;
                }
            }
        }
        return new int[]{found, branch, sky};
    }

    /**
     * The sky's background (green, the median, so the stars don't count) 4 to 12 px from the branch over its
     * background 40 to 56 px from it.
     */
    static double halo(RgbImage im, float[] bt, int[] dist, int W, int H) {
        float[] near = new float[W * H / 8], far = new float[W * H / 8];
        int nn = 0, nf = 0;
        int x0 = Math.max(im.x0, 8), y0 = Math.max(im.y0, 8), x1 = Math.min(im.x1, W - 8), y1 = Math.min(im.y1, H - 8);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int i = y * W + x;
                if (bt[i] < 0.5f) continue;
                if (dist[i] >= 4 && dist[i] <= 12 && nn < near.length) near[nn++] = im.g[i];
                else if (dist[i] >= 40 && dist[i] <= 56 && nf < far.length) far[nf++] = im.g[i];
            }
        }
        if (nn == 0 || nf == 0) return 0;
        float f = Stats.median(far, nf);
        return f > 0 ? Stats.median(near, nn) / f : 0;
    }

    /** The rendered photo's pixel at sensor (x, y); black outside it. */
    static int pixelAt(int[] photo, Renderer.Analysis a, int x, int y) {
        int px = x - a.cx0, py = y - a.cy0;
        return px >= 0 && py >= 0 && px < a.cw && py < a.ch ? photo[py * a.cw + px] : 0xFF000000;
    }

    /** Per pixel, the distance (px, 4-connected, up to 64; 99 beyond) to the nearest pixel of the other kind (branch or sky). */
    static int[] distances(float[] bt, int W, int H) {
        int[] dist = new int[W * H];
        java.util.Arrays.fill(dist, 99);
        int[] q = new int[W * H];
        int qh = 0, qt = 0;
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int i = y * W + x;
                boolean br = bt[i] < 0.5f;
                if ((x > 0 && (bt[i - 1] < 0.5f) != br) || (x + 1 < W && (bt[i + 1] < 0.5f) != br) || (y > 0 && (bt[i - W] < 0.5f) != br)
                        || (y + 1 < H && (bt[i + W] < 0.5f) != br)) {
                    dist[i] = 1;
                    q[qt++] = i;
                }
            }
        }
        while (qh < qt) {
            int i = q[qh++];
            if (dist[i] >= 64) continue;
            int x = i % W, y = i / W;
            boolean br = bt[i] < 0.5f;
            int[] nb = {x > 0 ? i - 1 : -1, x + 1 < W ? i + 1 : -1, y > 0 ? i - W : -1, y + 1 < H ? i + W : -1};
            for (int j : nb) {
                if (j < 0 || dist[j] != 99 || (bt[j] < 0.5f) != br) continue;
                dist[j] = dist[i] + 1;
                q[qt++] = j;
            }
        }
        return dist;
    }

    /**
     * The branch's edge in a stacked picture (green): the sky within 4 px of the branch above the branch's own pixels
     * within 4 px of the sky, over the nearby open sky's level (16 to 24 px from the branch, so the sky's gradient
     * doesn't count) above the branch's; and the branch's level over that sky's.
     */
    static double[] edge(RgbImage im, float[] bt, int[] dist, int W, int H) {
        double sIn = 0, sOut = 0, sOpen = 0;
        int nIn = 0, nOut = 0, nOpen = 0;
        int x0 = Math.max(im.x0, 8), y0 = Math.max(im.y0, 8), x1 = Math.min(im.x1, W - 8), y1 = Math.min(im.y1, H - 8);
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int i = y * W + x;
                boolean br = bt[i] < 0.5f;
                float v = im.g[i];
                if (br && dist[i] <= 4) {
                    sIn += v;
                    nIn++;
                } else if (!br && dist[i] <= 4) {
                    sOut += v;
                    nOut++;
                } else if (!br && dist[i] >= 16 && dist[i] <= 24) {
                    sOpen += v;
                    nOpen++;
                }
            }
        }
        double in = sIn / Math.max(1, nIn), out = sOut / Math.max(1, nOut), open = sOpen / Math.max(1, nOpen);
        return new double[]{(out - in) / Math.max(1e-6, open - in), in / Math.max(1e-6, open)};
    }

    /** A rotation taking camera bearings to a frame whose third axis is the pole (as J2000's is). */
    static double[] toPole(double[] p) {
        double n = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        double[] z = {p[0] / n, p[1] / n, p[2] / n};
        double[] a = Math.abs(z[0]) < 0.9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0};
        double d = a[0] * z[0] + a[1] * z[1] + a[2] * z[2];
        double[] x = {a[0] - d * z[0], a[1] - d * z[1], a[2] - d * z[2]};
        double xn = Math.sqrt(x[0] * x[0] + x[1] * x[1] + x[2] * x[2]);
        x[0] /= xn;
        x[1] /= xn;
        x[2] /= xn;
        double[] y = {z[1] * x[2] - z[2] * x[1], z[2] * x[0] - z[0] * x[2], z[0] * x[1] - z[1] * x[0]};
        return new double[]{x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2]};
    }
}
