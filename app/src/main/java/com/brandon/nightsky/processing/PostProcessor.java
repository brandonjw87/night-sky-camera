package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything after capture: hot-pixel map, star alignment against a clear frame from the middle of the
 * session, lens-model refinement, recovery of frames the tracker lost (predicted sky rotation, confirmed by
 * photometry), frame grading (clouds, bumps, blur), the transparency and sky background of every frame per
 * region (so a partly cloudy frame still adds its clear part), the choice of outlier rejection, and the
 * final stack. Works from what is on disk, so it can also finish a session after a crash.
 */
public final class PostProcessor {
    public static final class Output {
        public RgbImage image;
        public Renderer.Analysis analysis;
        public CameraModel model;
        public int unitsTotal, unitsUsed;
        /** Of the used frames, those that were used only in part (clouds covered some regions). */
        public int unitsPartly;
        /** Seconds of exposure that went into the stack (partly used frames count in part). */
        public double integrationSeconds;
        public boolean aligned;
        public float maxGain = 1f;
        /** One short line for the user, e.g. "Used 35 of 37 frames (4 min 40 s). Skipped 2 cloudy." */
        public String summary = "";
        /** How outliers were rejected, in a few words. */
        public String rejection = "";
        /** Choices made automatically, each with its reason. */
        public final List<String> decisions = new ArrayList<String>();
        /** Per stored unit, in capture order. */
        public final List<FrameReport> frames = new ArrayList<FrameReport>();
        public int referenceUnit = -1;
    }

    /** What happened to one unit, for the review screen and the log. */
    public static final class FrameReport {
        public int index;
        public double t, exposure;
        public boolean used, predicted;
        public String reason = "";
        public float weight, transparency = Float.NaN;
        public int stars;
        public double rms = Double.NaN;
        /** Final rotation reference -> unit, or null. */
        public double[] R;
        /** Regions used, out of {@link #REGIONS} (-1 when transparency wasn't measured). */
        public int regionsUsed = -1;
        /** Share of the frame's pixels that went into the stack, and samples rejected as outliers (per channel). */
        public float usedFraction = Float.NaN;
        public long rejectedSamples;
        /** Uncertainty of the frame's position on the sky's path, pixels at the centre (NaN if not on it). */
        public double alignSigma = Double.NaN;
        /** The rotation its own stars gave, before smoothing along the path (null if they gave none). */
        public double[] measuredR;
        /** Stars matched in the alignment (0 if none). */
        public int matched;
    }

    /** Regions per frame for transparency, weights and the sky background: 8 x 6. */
    public static final int GX = 8, GY = 6, REGIONS = GX * GY;
    /** Wider regions (2 x 2 of the above) that a region with few stars leans on. */
    private static final int CX = 4, CY = 3;
    /** Stars a region needs before its own measurement counts as much as its wider region's. */
    private static final float SHRINK_STARS = 3f;
    /** A region is used down to this share of its clear transparency. */
    static final float MIN_REGION_T = 0.5f;
    /** A region counts as clear (for the target background) within 2.5 times its noise of the clear level,
     *  and within these bounds. */
    static final float CLEAR_MIN_DROP = 0.01f, CLEAR_MAX_DROP = 0.1f;
    /** Clear regions a frame needs to be used at all, and a frame placed by prediction to confirm its position. */
    static final int MIN_CLEAR_REGIONS = 6, MIN_CONFIRM_REGIONS = 12;
    /** Stars measured for transparency, re-detected on the reference frame (the live lists keep 300). */
    private static final int PHOT_STARS = 2000;
    /** Spread of the frames' sky background ((P90 - P10) / median) above which linear-fit clipping takes over. */
    static final float BACKGROUND_CHANGE = 0.5f;

    private final SessionStore store;
    private final SensorSpec spec;
    private final int W, H;
    /** Outlier rejection asked for; AUTO chooses from the session. */
    public Rejection.Method rejection = Rejection.Method.AUTO;
    /** Bytes the stack may keep per-sample buffers in; 0 sizes it from free memory (tests set it to force one pass). */
    public long stackMemory = 0;

    public PostProcessor(SessionStore store, SensorSpec spec) {
        this.store = store;
        this.spec = spec;
        W = spec.width;
        H = spec.height;
    }

    private static final class Unit {
        UnitRecord rec;
        StarList stars;
        Aligner.Result res;
        double[] R;
        /** Rotation the stack uses (R, or the identity for the unaligned fallback). */
        double[] stackR;
        boolean predicted;
        float transparency = Float.NaN;
        /** Per region, in reference coordinates: transparency relative to that region's clear level. */
        float[] cellT;
        /** The frame's sky background per region, in its own coordinates. */
        BackgroundGrid bg;
        boolean used;
        String reason = "";
        float weight = 1f;
        int regionsUsed = -1;
        float usedFraction = 1f;
        /** Uncertainty of R on the sky's smoothed path, pixels at the centre (NaN if not on it). */
        double sigmaPx = Double.NaN;
    }

    public Output run(boolean trails, Progress progress) throws IOException {
        List<UnitRecord> recs = store.readUnits();
        FrameStore fs = store.frames(W, H);
        List<Unit> units = new ArrayList<Unit>();
        for (UnitRecord r : recs) {
            if (!fs.exists(r.index)) continue;
            Unit u = new Unit();
            u.rec = r;
            units.add(u);
        }
        if (units.isEmpty()) throw new IOException("No frames were captured");
        store.log("post-processing " + units.size() + " units, mode " + (trails ? "trails" : "deep sky"));
        Output out = new Output();
        out.unitsTotal = units.size();
        out.maxGain = spec.shading != null ? spec.shading.maxGain() : 1f;
        CameraModel model = spec.model != null ? spec.model
                : CameraModel.nominal(W, H, W / (2 * Math.tan(Math.toRadians(35))));
        out.model = model;

        // 1) hot pixels from the sensor-fixed average
        HotPixels hot = hotPixels(units, fs, progress);
        store.log("hot pixels: " + hot.count());

        if (trails) {
            List<TrailsStacker.Unit> tu = new ArrayList<TrailsStacker.Unit>();
            double integ = 0;
            int moved = 0;
            for (Unit u : units) {
                if (u.rec.disturbed) {
                    moved++;
                    continue;
                }
                TrailsStacker.Unit t = new TrailsStacker.Unit();
                t.unit = u.rec.index;
                t.invScale = u.rec.invScale;
                System.arraycopy(u.rec.black, 0, t.black, 0, 4);
                tu.add(t);
                integ += u.rec.exposure;
            }
            if (tu.isEmpty()) {
                for (Unit u : units) {
                    TrailsStacker.Unit t = new TrailsStacker.Unit();
                    t.unit = u.rec.index;
                    t.invScale = u.rec.invScale;
                    System.arraycopy(u.rec.black, 0, t.black, 0, 4);
                    tu.add(t);
                    integ += u.rec.exposure;
                }
                moved = 0;
            }
            RgbImage img = new TrailsStacker(spec, fs, hot).stack(tu, progress);
            img.x0 = 4;
            img.y0 = 4;
            img.x1 = W - 4;
            img.y1 = H - 4;
            out.image = img;
            out.unitsUsed = tu.size();
            out.integrationSeconds = integ;
            out.summary = String.format(Locale.US, "Star trails from %d frames (%s).%s", tu.size(), duration(integ),
                    moved > 0 ? " Skipped " + moved + " moved." : "");
            out.analysis = Renderer.analyze(img, spec);
            out.analysis.colorSigma = 3f;
            store.log(out.summary + " " + out.analysis.describe());
            return out;
        }

        // 2) star lists without fixed points (warm pixels, a branch), and the reference frame
        for (Unit u : units) u.stars = store.readStars(u.rec.index);
        StaticSources fixed = findFixed(units, model);
        if (fixed.count > 0) {
            for (Unit u : units) u.stars = fixed.filter(u.stars);
            if (fixed.maskedCells() > 0) {
                decide(out, "alignment: ignored " + fixed.describe() + ", so they can't pull the alignment");
            } else {
                store.log("alignment: ignored " + fixed.describe());
            }
        }
        Unit ref = chooseReference(units);
        Map<Integer, Unit> byIndex = new HashMap<Integer, Unit>();
        for (Unit u : units) byIndex.put(u.rec.index, u);

        if (ref != null) {
            out.referenceUnit = ref.rec.index;
            decide(out, String.format(Locale.US, "reference: frame %d (at %d s, %d stars), the clearest frame near the middle",
                    ref.rec.index, Math.round(ref.rec.t), ref.stars.count));
            progress.update("Matching stars", 0f);
            Aligner al = align(model, ref, units, null);
            ModelRefiner.Outcome oc = ModelRefiner.refine(model, al.reference(), al.results(), ref.rec.index);
            store.log(oc.note);
            if (oc.changed) {
                model = oc.model;
                al = align(model, ref, units, null);
            }
            al = sparseRescue(al, model, ref, units, fs, hot, fixed, progress, out);
            out.model = model;
            for (Aligner.Result r : al.results()) {
                Unit u = byIndex.get(r.unit);
                if (u == null) continue;
                u.res = r;
                if (r.ok) u.R = r.R;
            }
            smoothMotion(units, model, out);
            progress.update("Matching stars", 1f);
            measureFrames(units, ref, al.reference(), model, fs, hot, progress);
            grade(units);
            int placed = 0;
            for (Unit u : units) if (u.predicted && u.used) placed++;
            if (placed > 0) {
                decide(out, String.format(Locale.US, "alignment: %d frame%s placed on the sky's steady path (too few stars "
                        + "to match, or a match that didn't fit), confirmed by their starlight", placed, placed == 1 ? "" : "s"));
            }
        }

        // 3) which frames, and how: per region scale and weight against a common sky background
        List<Unit> used = new ArrayList<Unit>();
        int cloudy = 0, moved = 0, blurred = 0, lost = 0;
        for (Unit u : units) {
            if (u.used) used.add(u);
            else if (u.reason.startsWith("moved")) moved++;
            else if (u.reason.startsWith("cloud")) cloudy++;
            else if (u.reason.startsWith("blur")) blurred++;
            else lost++;
        }
        out.aligned = !used.isEmpty();
        List<Unit> stacked = used;
        if (!out.aligned) {
            // Nothing could be aligned (no stars: cloud cover, lens covered, too short): plain average instead.
            store.log("no aligned frames, falling back to an unaligned average");
            stacked = new ArrayList<Unit>();
            for (Unit u : units) if (!u.rec.disturbed || units.size() == 1) stacked.add(u);
            if (stacked.isEmpty()) stacked.add(units.get(0));
            short[] raw = null;
            int done = 0;
            for (Unit u : stacked) {
                u.stackR = Rot.identity();
                if (u.bg == null) {
                    if (raw == null) raw = new short[W * H];
                    fs.read(u.rec.index, raw);
                    u.bg = BackgroundGrid.measure(raw, u.rec.invScale, u.rec.black, spec.cfa, hot, GX, GY, W, H);
                }
                done++;
                if (progress.cancelled()) throw new CancelledException();
                progress.update("Measuring the sky", done / (float) stacked.size());
            }
        } else {
            for (Unit u : used) u.stackR = u.R;
        }
        BackgroundGrid target = targetBackground(stacked, model);
        List<SharpStacker.Plan> plans = new ArrayList<SharpStacker.Plan>();
        double integ = 0;
        int partly = 0;
        for (Unit u : stacked) {
            SharpStacker.Plan p = new SharpStacker.Plan();
            p.unit = u.rec.index;
            p.R = u.stackR;
            p.weight = u.weight;
            p.invScale = u.rec.invScale;
            System.arraycopy(u.rec.black, 0, p.black, 0, 4);
            p.t = u.rec.t;
            p.background = u.bg;
            regionFactors(u, p);
            plans.add(p);
            integ += u.rec.exposure * u.usedFraction;
            if (u.regionsUsed >= 0 && u.regionsUsed < REGIONS) partly++;
        }
        decide(out, String.format(Locale.US, "normalisation: every frame's sky background and starlight matched to the "
                + "clearest frames in %d regions; %d frame%s used only where clear", REGIONS, partly, partly == 1 ? "" : "s"));
        out.unitsUsed = plans.size();
        out.unitsPartly = partly;
        out.integrationSeconds = integ;

        // 4) outlier rejection
        SharpStacker st = new SharpStacker(spec, model, fs, hot);
        long freeMem = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        st.memoryBudget = stackMemory > 0 ? stackMemory : Math.max(16L << 20, Math.min(64L << 20, (freeMem - (160L << 20)) / 2));
        String[] why = new String[1];
        st.method = chooseRejection(stacked, why);
        st.target = target;
        st.minTransparency = MIN_REGION_T;
        if (st.method != Rejection.Method.SIGMA && !st.keepsSamples(plans.size())) {
            decide(out, String.format(Locale.US, "rejection: %s in one pass, because %d frames are too many to keep in "
                    + "memory together", Rejection.Method.SIGMA.label, plans.size()));
            out.rejection = Rejection.Method.SIGMA.label;
        } else {
            decide(out, "rejection: " + why[0]);
            out.rejection = (st.method == Rejection.Method.AUTO ? Rejection.forFrames(plans.size()) : st.method).label;
        }

        // 5) the stack
        SharpStacker.Result sr = st.stack(plans, progress);
        long rej = 0, smp = 0;
        Map<Integer, Integer> planOf = new HashMap<Integer, Integer>();
        for (int i = 0; i < plans.size(); i++) {
            rej += sr.rejected[i];
            smp += sr.samples[i];
            planOf.put(plans.get(i).unit, i);
        }
        store.log(String.format(Locale.US, "stacked %d units in bands of %d rows, rejected %.3f%% of samples, crop %d,%d-%d,%d",
                plans.size(), sr.bandRows, smp > 0 ? 100.0 * rej / (3.0 * smp) : 0, sr.image.x0, sr.image.y0, sr.image.x1, sr.image.y1));
        if (sr.edgeSamples > 0) {
            decide(out, String.format(Locale.US, "cloud edges: %.1f%% of the samples came from next to a cloud's edge and were "
                    + "checked against the frames clear there; %.0f%% of those were left out", 100.0 * sr.edgeSamples / (3.0 * Math.max(1, smp)),
                    100.0 * sr.edgeRejected / sr.edgeSamples));
        }
        for (Unit u : units) {
            FrameReport fr = new FrameReport();
            fr.index = u.rec.index;
            fr.t = u.rec.t;
            fr.exposure = u.rec.exposure;
            fr.used = u.used;
            fr.predicted = u.predicted;
            fr.reason = u.reason;
            fr.weight = u.weight;
            fr.transparency = u.transparency;
            fr.stars = u.stars == null ? 0 : u.stars.count;
            if (u.res != null && u.res.ok) fr.rms = u.res.rms;
            fr.R = u.R;
            fr.regionsUsed = u.regionsUsed;
            fr.alignSigma = u.sigmaPx;
            if (u.res != null && u.res.ok) {
                fr.measuredR = u.res.R;
                fr.matched = u.res.nInliers;
            }
            Integer pi = planOf.get(u.rec.index);
            if (pi != null) {
                fr.usedFraction = sr.samples[pi] > 0 ? sr.included[pi] / (float) sr.samples[pi] : 0f;
                fr.rejectedSamples = sr.rejected[pi];
            }
            out.frames.add(fr);
            store.log(String.format(Locale.US, "unit %d: %s T=%s regions=%s%s used=%s stars=%d rms=%s %s", u.rec.index,
                    u.used ? "USED" : "skipped",
                    Float.isNaN(u.transparency) ? "-" : String.format(Locale.US, "%.2f", u.transparency),
                    u.regionsUsed < 0 ? "-" : u.regionsUsed + "/" + REGIONS, regionRange(u.cellT),
                    Float.isNaN(fr.usedFraction) ? "-" : String.format(Locale.US, "%.0f%%", 100 * fr.usedFraction),
                    fr.stars,
                    u.res != null && u.res.ok ? String.format(Locale.US, "%.2f", u.res.rms) : "-",
                    u.reason + (u.predicted ? " (predicted)" : "")));
        }
        out.image = sr.image;
        out.analysis = Renderer.analyze(sr.image, spec);
        StringBuilder sb = new StringBuilder();
        if (out.aligned) {
            sb.append(String.format(Locale.US, "Used %d of %d frames (%s)", plans.size(), units.size(), duration(integ)));
            if (partly > 0) sb.append(String.format(Locale.US, ", %d of them partly because of clouds", partly));
            sb.append('.');
            List<String> sk = new ArrayList<String>();
            if (cloudy > 0) sk.add(cloudy + " cloudy");
            if (moved > 0) sk.add(moved + " moved");
            if (blurred > 0) sk.add(blurred + " blurred");
            if (lost > 0) sk.add(lost + " without enough stars");
            if (!sk.isEmpty()) sb.append(" Skipped ").append(join(sk)).append('.');
        } else {
            sb.append(String.format(Locale.US, "Not enough stars to line frames up, so this is a plain average of %d frames (%s).",
                    plans.size(), duration(integ)));
        }
        out.summary = sb.toString();
        store.log(out.summary + " " + out.analysis.describe());
        return out;
    }

    /** " (0.12-1.03)": the range of a frame's regional transparency, for the log. */
    private static String regionRange(float[] t) {
        if (t == null) return "";
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float v : t) {
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        return String.format(Locale.US, " (%.2f-%.2f)", lo, hi);
    }

    private void decide(Output out, String s) {
        out.decisions.add(s);
        store.log("decision: " + s);
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? " and " : ", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    public static String duration(double s) {
        int t = (int) Math.round(s);
        if (t < 60) return t + " s";
        int m = t / 60, r = t % 60;
        return r == 0 ? m + " min" : m + " min " + r + " s";
    }

    // ------------------------------------------------------------------------------------------------

    private HotPixels hotPixels(List<Unit> units, FrameStore fs, Progress progress) throws IOException {
        int[] cnt = new int[1];
        float[] sum = store.readSum(W * H, cnt);
        int n = cnt[0];
        if (sum == null || n < units.size()) {
            // rebuild (crash recovery): average of every stored unit
            sum = new float[W * H];
            short[] raw = new short[W * H];
            n = 0;
            for (Unit u : units) {
                fs.read(u.rec.index, raw);
                final short[] rr = raw;
                final float[] s = sum;
                final float sc = u.rec.invScale;
                final float[] bl = u.rec.black;
                Parallel.forRange(H, 32, new Parallel.Range() {
                    @Override
                    public void run(int y0, int y1) {
                        for (int y = y0; y < y1; y++) {
                            int o = y * W;
                            int py = (y & 1) << 1;
                            for (int x = 0; x < W; x++) s[o + x] += (rr[o + x] & 0xFFFF) * sc - bl[py | (x & 1)];
                        }
                    }
                });
                n++;
                if (progress.cancelled()) throw new CancelledException();
                progress.update("Reading frames", n / (float) units.size());
            }
            store.log("rebuilt the sensor sum from " + n + " units");
        }
        float inv = 1f / Math.max(1, n);
        for (int i = 0; i < sum.length; i++) sum[i] *= inv;
        if (n < 2) return HotPixels.empty(W, H);
        // with few frames stars barely move on the sensor, so be stricter
        float k = n >= 8 ? 8f : 12f;
        return HotPixels.detect(sum, W, H, spec.cfa, k);
    }

    /** A clear frame near the middle of the session: most star light, with a mild preference for the middle. */
    private Unit chooseReference(List<Unit> units) {
        double t0 = Double.MAX_VALUE, t1 = -Double.MAX_VALUE;
        for (Unit u : units) {
            t0 = Math.min(t0, u.rec.t);
            t1 = Math.max(t1, u.rec.t);
        }
        double mid = 0.5 * (t0 + t1), span = Math.max(1e-3, t1 - t0);
        Unit best = null;
        double bestScore = 0;
        for (Unit u : units) {
            if (u.stars == null || u.stars.count < Aligner.MIN_REF_STARS || u.rec.disturbed) continue;
            int n = Math.min(u.stars.count, 150);
            double f = 0;
            for (int i = 0; i < n; i++) f += u.stars.saturated[i] ? 0 : u.stars.flux[i];
            f += 0.2 * n * u.stars.count;
            double score = f / (1 + 1.2 * Math.abs(u.rec.t - mid) / span);
            if (score > bestScore) {
                bestScore = score;
                best = u;
            }
        }
        return best;
    }

    /** Aligns every frame to the reference frame, or to {@code catalog} (in the reference frame's coordinates). */
    private Aligner align(CameraModel model, Unit ref, List<Unit> units, StarList catalog) {
        Aligner al = new Aligner(model, W, H);
        if (catalog != null) al.useCatalog(ref.rec.index, catalog, ref.rec.t);
        al.process(ref.rec.index, ref.stars, ref.rec.t);
        StarList empty = new StarList(4);
        int ri = units.indexOf(ref);
        for (int i = ri + 1; i < units.size(); i++) {
            Unit u = units.get(i);
            al.process(u.rec.index, u.stars != null ? u.stars : empty, u.rec.t);
        }
        al.resetPrediction();
        for (int i = ri - 1; i >= 0; i--) {
            Unit u = units.get(i);
            al.process(u.rec.index, u.stars != null ? u.stars : empty, u.rec.t);
        }
        return al;
    }

    /** Fixed points from every frame's star list: what stays put on the sensor while the sky drifts. */
    private StaticSources findFixed(List<Unit> units, CameraModel model) {
        List<StarList> lists = new ArrayList<StarList>();
        double[] t = new double[units.size()];
        for (int i = 0; i < units.size(); i++) {
            Unit u = units.get(i);
            lists.add(u.rec.disturbed ? null : u.stars);
            t[i] = u.rec.t;
        }
        return StaticSources.find(lists, t, model.fx, W, H);
    }

    /** A frame matching fewer stars than this counts as sparse and gets the deeper search. */
    static final int WEAK_MATCHES = 25;
    /** A reference frame with fewer stars than this gets the deeper search too. */
    static final int SPARSE_REFERENCE = 60;

    /**
     * Few stars (thin cloud, haze, a bright town, a short exposure): searches the weak frames and the reference
     * deeper, at 3.5 and then 2.5 sigma with stricter shape checks, and matches every frame against the stars
     * seen across the whole session ({@link StarCatalog}) rather than the reference frame alone. Does nothing
     * when every frame matched plenty of stars.
     */
    private Aligner sparseRescue(Aligner al, CameraModel model, Unit ref, List<Unit> units, FrameStore fs,
                                 HotPixels hot, StaticSources fixed, Progress progress, Output out) throws IOException {
        Map<Integer, Aligner.Result> res = resultsByUnit(al);
        List<Unit> weak = weakFrames(units, res);
        boolean sparseRef = ref.stars == null || ref.stars.count < SPARSE_REFERENCE;
        if (weak.isEmpty() && !sparseRef) return al;
        int weakBefore = weak.size(), refStars = ref.stars == null ? 0 : ref.stars.count;
        // the star size from the reference frame's brightest detections (fainter ones are mostly noise here)
        float medHfr = ref.stars != null ? ref.stars.medianHfr(20) : 0f;
        short[] raw = new short[W * H];
        Aligner best = al;
        int searched = 0;
        StarList cat = null;
        float[] ladder = {3.5f, 2.5f};
        for (int step = 0; step < ladder.length; step++) {
            List<Unit> todo = new ArrayList<Unit>(weak);
            if (step == 0 && !todo.contains(ref)) todo.add(ref);
            if (todo.isEmpty()) break;
            int done = 0;
            for (Unit u : todo) {
                u.stars = fixed.filter(deepDetect(fs, u, raw, ladder[step], hot, medHfr));
                searched++;
                done++;
                if (progress.cancelled()) throw new CancelledException();
                progress.update("Looking for faint stars", done / (float) todo.size());
            }
            // match against the session's stars: two rounds, the second from the better alignment
            for (int round = 0; round < 2; round++) {
                StarList c = catalog(model, units, resultsByUnit(best));
                if (c.count < Aligner.MIN_PAIRS) break;
                Aligner a = align(model, ref, units, c);
                store.log(String.format(Locale.US, "sparse field, %.1f sigma, round %d: %d stars seen across the session; "
                                + "%d frames aligned with %d matches (was %d with %d)", ladder[step], round + 1, c.count, aligned(a),
                        inliers(a), aligned(best), inliers(best)));
                if (aligned(a) < aligned(best) || (aligned(a) == aligned(best) && inliers(a) <= inliers(best))) break;
                best = a;
                cat = c;
            }
            weak = weakFrames(units, resultsByUnit(best));
        }
        int alignedBefore = aligned(al), alignedAfter = aligned(best);
        decide(out, String.format(Locale.US, "alignment: few stars (%d frames matched under %d, the reference had %d), so %d "
                        + "searches went deeper%s; %d of %d frames aligned before, %d after", weakBefore, WEAK_MATCHES, refStars,
                searched, cat != null ? String.format(Locale.US, " and frames were matched against %d stars seen across the "
                        + "session", cat.count) : "", alignedBefore, units.size(), alignedAfter));
        return best;
    }

    private static Map<Integer, Aligner.Result> resultsByUnit(Aligner al) {
        Map<Integer, Aligner.Result> m = new HashMap<Integer, Aligner.Result>();
        for (Aligner.Result r : al.results()) m.put(r.unit, r);
        return m;
    }

    private static List<Unit> weakFrames(List<Unit> units, Map<Integer, Aligner.Result> res) {
        List<Unit> weak = new ArrayList<Unit>();
        for (Unit u : units) {
            if (u.rec.disturbed) continue;
            Aligner.Result r = res.get(u.rec.index);
            if (r == null || !r.ok || r.nInliers < WEAK_MATCHES) weak.add(u);
        }
        return weak;
    }

    private static int aligned(Aligner al) {
        int n = 0;
        for (Aligner.Result r : al.results()) if (r.ok) n++;
        return n;
    }

    private static long inliers(Aligner al) {
        long n = 0;
        for (Aligner.Result r : al.results()) if (r.ok) n += r.nInliers;
        return n;
    }

    /** The session's stars from every aligned frame, in reference coordinates. */
    private StarList catalog(CameraModel model, List<Unit> units, Map<Integer, Aligner.Result> res) {
        List<StarList> lists = new ArrayList<StarList>();
        List<double[]> rots = new ArrayList<double[]>();
        float[] rms = new float[units.size()];
        int aligned = 0;
        for (Unit u : units) {
            Aligner.Result r = res.get(u.rec.index);
            boolean ok = r != null && r.ok && !u.rec.disturbed && u.stars != null;
            lists.add(ok ? u.stars : null);
            rots.add(ok ? r.R : null);
            if (ok && r.rms > 0) rms[aligned] = (float) r.rms;
            if (ok) aligned++;
        }
        int minFrames = aligned <= 2 ? 1 : Math.max(2, (int) Math.ceil(0.3 * aligned));
        // the same star lands within a few times the alignment's scatter
        float join = Stats.clamp(2.5f * Stats.median(rms, aligned), 1.5f, 4f);
        return StarCatalog.build(model, lists, rots, W, H, minFrames, join);
    }

    /** Stars of one frame at a lower threshold, with shape checks tight enough to keep noise out. */
    private StarList deepDetect(FrameStore fs, Unit u, short[] raw, float kSigma, HotPixels hot, float medHfr) throws IOException {
        fs.read(u.rec.index, raw);
        StarDetector d = new StarDetector(spec);
        d.kSigma = kSigma;
        d.hot = hot;
        d.maxElong = 1.8f;
        if (medHfr > 0) {
            d.minHfr = 0.6f * medHfr;
            d.maxHfr = Math.min(5.2f, 1.6f * medHfr);
        }
        return d.detect(raw, u.rec.invScale, 600);
    }

    /** A frame whose position on the sky's path is this uncertain (pixels) isn't trusted to be sharp. */
    static final double MAX_PATH_SIGMA = 0.3;

    /**
     * The frames' rotations as one steady path ({@link SkyMotion}): every alignment counts as much as its
     * stars say it should, so a frame with few stars leans on its neighbours; frames the tracker lost, or
     * whose match doesn't fit the path, are placed on it (photometry then confirms them); a bump starts a
     * new stretch.
     */
    private void smoothMotion(List<Unit> units, CameraModel model, Output out) {
        List<Unit> order = new ArrayList<Unit>(units);
        java.util.Collections.sort(order, new java.util.Comparator<Unit>() {
            @Override
            public int compare(Unit a, Unit b) {
                return Double.compare(a.rec.t, b.rec.t);
            }
        });
        List<SkyMotion.Frame> frames = new ArrayList<SkyMotion.Frame>();
        List<double[]> covs = new ArrayList<double[]>();
        for (Unit u : order) {
            SkyMotion.Frame f = new SkyMotion.Frame();
            f.t = u.rec.t;
            f.disturbed = u.rec.disturbed;
            if (u.res != null && u.res.ok) {
                f.R = u.res.R;
                f.cov = u.res.cov;
                if (f.cov != null && trace(f.cov) > 0) covs.add(f.cov);
            }
            frames.add(f);
        }
        // The reference frame is the identity only by definition. Every other frame was measured against its
        // star positions, so they share its centroid errors, and pinning the path to it would bend the path
        // toward a point they can't agree with. It gets an uncertainty like theirs instead (half, as only its
        // own stars' errors are in it).
        if (!covs.isEmpty()) {
            java.util.Collections.sort(covs, new java.util.Comparator<double[]>() {
                @Override
                public int compare(double[] a, double[] b) {
                    return Double.compare(trace(a), trace(b));
                }
            });
            double[] typical = covs.get(covs.size() / 2).clone();
            for (int k = 0; k < 9; k++) typical[k] *= 0.5;
            for (SkyMotion.Frame f : frames) if (f.R != null && f.cov != null && !(trace(f.cov) > 0)) f.cov = typical;
        }
        SkyMotion.Result r = SkyMotion.smooth(frames, model.fx);
        double[] sig = new double[order.size()];
        int ns = 0;
        for (int i = 0; i < order.size(); i++) {
            Unit u = order.get(i);
            SkyMotion.Frame f = frames.get(i);
            u.R = f.smoothed;
            u.predicted = f.smoothed != null && f.predicted;
            u.sigmaPx = f.smoothed != null ? f.sigmaPx : Double.NaN;
            if (f.smoothed != null) sig[ns++] = f.sigmaPx;
        }
        String detail = String.format(Locale.US, "%d measured, %d set aside, %d placed, %d stretch%s, path within %.3f px "
                        + "(median %.3f), alignments moved by up to %.2f px (median %.3f)%s", r.measured, r.outliers, r.placed,
                r.segments, r.segments == 1 ? "" : "es", ns > 0 ? max(sig, ns) : 0, ns > 0 ? Stats.median(toFloat(sig, ns), ns) : 0,
                r.maxShift, r.medianShift, r.loosened > 0 ? ", loosened " + r.loosened + "x for a drifting phone" : "");
        if (r.outliers > 0 || r.placed > 0 || r.maxShift > 0.25) decide(out, "alignment: smoothed along the sky's steady path: " + detail);
        else store.log("sky path: " + detail);
    }

    private static double trace(double[] m) {
        return m[0] + m[4] + m[8];
    }

    private static double max(double[] a, int n) {
        double m = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) m = Math.max(m, a[i]);
        return m;
    }

    private static float[] toFloat(double[] a, int n) {
        float[] f = new float[n];
        for (int i = 0; i < n; i++) f[i] = (float) a[i];
        return f;
    }

    /**
     * Reads every aligned frame once for two measurements. Its sky background and noise per region
     * ({@link BackgroundGrid}). Its transparency from aperture photometry of up to {@link #PHOT_STARS} stars
     * of the reference frame, as flux-weighted medians of each star's flux ratio to the reference: overall,
     * and per region (leaning on the wider region where a region has few stars), each relative to the clear
     * level of that region over the frames ({@link #clearLevel}), so a slightly hazy reference doesn't matter.
     */
    private void measureFrames(List<Unit> units, Unit ref, StarList refStars, CameraModel model, FrameStore fs,
                               HotPixels hot, Progress progress) throws IOException {
        short[] raw = new short[W * H];
        fs.read(ref.rec.index, raw);
        StarList ps = refStars;
        try {
            StarList det = new StarDetector(spec).detect(raw, ref.rec.invScale, PHOT_STARS);
            if (det.count > ps.count) ps = det;
        } catch (OutOfMemoryError e) {
            store.log("not enough memory to look for more stars; photometry uses the live star list");
        }
        int n = 0;
        float[] rx = new float[ps.count], ry = new float[ps.count];
        for (int i = 0; i < ps.count; i++) {
            if (ps.saturated[i]) continue;
            rx[n] = ps.x[i];
            ry[n] = ps.y[i];
            n++;
        }
        store.log("photometry on " + n + " stars of the reference frame");
        double[] rb = new double[3 * n];
        double[] b = new double[3], p = new double[2];
        int[] fine = new int[n], coarse = new int[n];
        for (int i = 0; i < n; i++) {
            model.unproject(rx[i], ry[i], b);
            rb[3 * i] = b[0];
            rb[3 * i + 1] = b[1];
            rb[3 * i + 2] = b[2];
            int fx = Stats.clamp((int) (rx[i] * GX / W), 0, GX - 1), fy = Stats.clamp((int) (ry[i] * GY / H), 0, GY - 1);
            fine[i] = fy * GX + fx;
            coarse[i] = parent(fine[i]);
        }
        Photometry ph = new Photometry(W, H);
        float[] xs = new float[n], ys = new float[n];
        Map<Unit, float[]> flux = new HashMap<Unit, float[]>();
        List<Unit> todo = new ArrayList<Unit>();
        todo.add(ref);
        for (Unit u : units) if (u.R != null && u != ref) todo.add(u);
        int done = 0;
        for (Unit u : todo) {
            if (u != ref) fs.read(u.rec.index, raw);
            u.bg = BackgroundGrid.measure(raw, u.rec.invScale, u.rec.black, spec.cfa, hot, GX, GY, W, H);
            if (n >= 8) {
                double[] R = u.R != null ? u.R : Rot.identity();
                for (int i = 0; i < n; i++) {
                    Rot.apply(R, rb[3 * i], rb[3 * i + 1], rb[3 * i + 2], b);
                    if (model.project(b[0], b[1], b[2], p)) {
                        xs[i] = (float) p[0];
                        ys[i] = (float) p[1];
                    } else {
                        xs[i] = -1000;
                        ys[i] = -1000;
                    }
                }
                float sat = 0.92f * (spec.white - Math.max(Math.max(u.rec.black[0], u.rec.black[1]), Math.max(u.rec.black[2], u.rec.black[3])));
                flux.put(u, ph.measure(raw, u.rec.invScale, u.rec.black, xs, ys, n, hot, sat));
            }
            done++;
            if (progress.cancelled()) throw new CancelledException();
            progress.update("Checking for clouds", done / (float) todo.size());
        }
        float[] fr = flux.get(ref);
        if (fr == null) return;
        // stars bright enough to measure well: upper 70% of the reference fluxes
        float[] tmp = new float[n];
        int m = 0;
        for (int i = 0; i < n; i++) if (!Float.isNaN(fr[i]) && fr[i] > 0) tmp[m++] = fr[i];
        if (m < 8) return;
        float minFlux = Stats.quantile(tmp, m, 0.3);
        // brighter stars measure the ratio better, so they count more, up to a cap so no single star decides
        float capFlux = Stats.quantile(tmp, m, 0.95);
        float[] wt = new float[n];
        for (int i = 0; i < n; i++) wt[i] = Math.min(fr[i], capFlux);
        float[] ratios = new float[n], rw = new float[n];
        float[][] fineR = new float[REGIONS][n], fineW = new float[REGIONS][n];
        float[][] coarseR = new float[CX * CY][n], coarseW = new float[CX * CY][n];
        int[] fineN = new int[REGIONS], coarseN = new int[CX * CY];
        float[] tc = new float[CX * CY];
        long[] scratch = new long[n];
        for (Unit u : units) {
            float[] fu = flux.get(u);
            if (fu == null) continue;
            int k = 0;
            java.util.Arrays.fill(fineN, 0);
            java.util.Arrays.fill(coarseN, 0);
            for (int i = 0; i < n; i++) {
                if (Float.isNaN(fr[i]) || Float.isNaN(fu[i]) || fr[i] < minFlux) continue;
                float r = fu[i] / fr[i];
                ratios[k] = r;
                rw[k++] = wt[i];
                int f = fine[i], c = coarse[i];
                fineR[f][fineN[f]] = r;
                fineW[f][fineN[f]++] = wt[i];
                coarseR[c][coarseN[c]] = r;
                coarseW[c][coarseN[c]++] = wt[i];
            }
            if (k < 5) {
                u.transparency = 0f;
                continue;
            }
            float tg = Stats.weightedMedian(ratios, rw, k, scratch);
            for (int c = 0; c < CX * CY; c++) tc[c] = shrink(coarseR[c], coarseW[c], coarseN[c], tg, scratch);
            u.cellT = new float[REGIONS];
            for (int c = 0; c < REGIONS; c++) u.cellT[c] = shrink(fineR[c], fineW[c], fineN[c], tc[parent(c)], scratch);
            u.transparency = tg;
        }
        // relative to the clear level: overall, and per region
        float[] all = new float[units.size()];
        int na = 0;
        for (Unit u : units) if (u.cellT != null) all[na++] = u.transparency;
        if (na == 0) return;
        float norm = clearLevel(all, na);
        float[] cn = new float[REGIONS];
        for (int c = 0; c < REGIONS; c++) {
            int q = 0;
            for (Unit u : units) if (u.cellT != null) all[q++] = u.cellT[c];
            cn[c] = clearLevel(all, q);
        }
        for (Unit u : units) {
            if (Float.isNaN(u.transparency)) continue;
            u.transparency /= norm;
            if (u.cellT != null) for (int c = 0; c < REGIONS; c++) u.cellT[c] /= cn[c];
        }
    }

    /** The wider region a region belongs to. */
    private static int parent(int cell) {
        int fx = cell % GX, fy = cell / GX;
        return (fy * CY / GY) * CX + fx * CX / GX;
    }

    /**
     * Weighted median of a region's star ratios, pulled toward {@code prior} as far as the region has few
     * stars (counted by their weights, so a region of faint stars leans more on its wider region).
     */
    private static float shrink(float[] r, float[] w, int n, float prior, long[] scratch) {
        if (n == 0) return prior;
        double sw = 0, sw2 = 0;
        for (int i = 0; i < n; i++) {
            sw += w[i];
            sw2 += (double) w[i] * w[i];
        }
        double ne = sw2 > 0 ? sw * sw / sw2 : n;
        float med = Stats.weightedMedian(r, w, n, scratch);
        return (float) ((ne * med + SHRINK_STARS * prior) / (ne + SHRINK_STARS));
    }

    /**
     * The clear level of a transparency over the frames (one value per frame, in time order): the median of
     * the values within three times their noise of the 80th percentile. The noise comes from the differences
     * between consecutive frames (cloud cover changes more slowly than that), at least 0.5% and at most 5%.
     * So a majority of slightly hazy frames doesn't pass for clear, while equally clear frames all count.
     * Reorders the array.
     */
    static float clearLevel(float[] t, int n) {
        if (n <= 0) return 1f;
        float[] d = new float[Math.max(1, n - 1)];
        for (int i = 0; i + 1 < n; i++) d[i] = Math.abs(t[i + 1] - t[i]);
        float p80 = Stats.quantile(t, n, 0.8);
        // the median absolute difference of two values with noise sigma is 0.954 sigma
        float sigma = n >= 3 ? Stats.median(d, n - 1) / 0.954f : 0.02f * p80;
        sigma = Stats.clamp(sigma, 0.005f * p80, 0.05f * p80);
        float lo = p80 - 3 * sigma;
        int k = 0;
        for (int i = 0; i < n; i++) if (t[i] >= lo) t[k++] = t[i];
        return Math.max(1e-3f, Stats.median(t, k));
    }

    private void grade(List<Unit> units) {
        float[] h = new float[units.size()];
        int nh = 0;
        for (Unit u : units) if (u.res != null && u.res.ok && u.res.medianHfr > 0) h[nh++] = u.res.medianHfr;
        float medH = nh > 0 ? Stats.median(h, nh) : 0f;
        for (Unit u : units) {
            u.used = false;
            if (u.rec.disturbed) {
                u.reason = "moved";
                continue;
            }
            if (u.R == null) {
                u.reason = u.stars == null || u.stars.count < Aligner.MIN_REF_STARS ? "too few stars" : "could not align";
                continue;
            }
            if (u.cellT != null) {
                // clouds over a part of the frame leave that part out; clouded nearly everywhere, the whole frame
                int clear = 0;
                for (float t : u.cellT) if (t >= MIN_REGION_T) clear++;
                if (clear < MIN_CLEAR_REGIONS) {
                    u.reason = String.format(Locale.US, "clouds (%.0f%% light)", 100 * u.transparency);
                    continue;
                }
                if (u.predicted && clear < MIN_CONFIRM_REGIONS) {
                    u.reason = "could not confirm position";
                    continue;
                }
            } else if (!Float.isNaN(u.transparency)) {
                u.reason = String.format(Locale.US, "clouds (%.0f%% light)", 100 * u.transparency);
                continue;
            } else if (u.predicted) {
                u.reason = "could not confirm position";
                continue;
            }
            if (u.res != null && u.res.ok && medH > 0 && u.res.medianHfr > 1.6f * medH) {
                u.reason = String.format(Locale.US, "blurred (%.1f vs %.1f px)", u.res.medianHfr, medH);
                continue;
            }
            // a loose star fit is fine as long as the sky's path pins the frame down
            if (u.res != null && u.res.ok && u.res.rms > 1.2 && !(u.sigmaPx <= MAX_PATH_SIGMA)) {
                u.reason = "poor alignment";
                continue;
            }
            u.used = true;
            u.weight = 1f;
            u.reason = "";
        }
    }

    /** Where the centre of region {@code cell} (reference coordinates) falls in frame u: into p, or the centre itself. */
    private void regionCentreIn(Unit u, CameraModel model, int cell, double[] b, double[] p) {
        double X = (cell % GX + 0.5) * W / GX, Y = (cell / GX + 0.5) * H / GY;
        p[0] = X;
        p[1] = Y;
        if (u.stackR == null) return;
        double[] q = new double[2];
        model.unproject(X, Y, b);
        Rot.apply(u.stackR, b[0], b[1], b[2], b);
        if (model.project(b[0], b[1], b[2], q)) {
            p[0] = q[0];
            p[1] = q[1];
        }
    }

    /**
     * The sky background every frame is matched to, per region in reference coordinates: the median over the
     * frames that use the region of their background there (and of their noise, for the weights).
     */
    private BackgroundGrid targetBackground(List<Unit> frames, CameraModel model) {
        BackgroundGrid t = new BackgroundGrid(GX, GY, W, H);
        int nf = frames.size();
        float[][] lv = new float[3][nf], sg = new float[3][nf];
        double[] b = new double[3], p = new double[2];
        // per region from the frames that are clear there, else hazy, else all: a cloud's glow in most frames
        // mustn't become everyone's sky
        float[] need = {0f, MIN_REGION_T, -Float.MAX_VALUE};
        for (int c = 0; c < REGIONS; c++) {
            need[0] = clearThreshold(frames, c);
            for (int pass = 0; pass < need.length; pass++) {
                int k = 0;
                for (Unit u : frames) {
                    if (u.bg == null) continue;
                    if (u.cellT != null && u.cellT[c] < need[pass]) continue;
                    regionCentreIn(u, model, c, b, p);
                    for (int ch = 0; ch < 3; ch++) {
                        lv[ch][k] = u.bg.level(ch, p[0], p[1]);
                        sg[ch][k] = u.bg.sigma(ch, p[0], p[1]);
                    }
                    k++;
                }
                if (k == 0) continue;
                for (int ch = 0; ch < 3; ch++) {
                    t.level[ch][c] = Stats.median(lv[ch], k);
                    t.sigma[ch][c] = Stats.median(sg[ch], k);
                }
                break;
            }
        }
        return t;
    }

    /**
     * A frame's transparency per region, for the stacker: it scales the starlight up to the clear level, cuts
     * the frame where the cloud is thicker than {@link #MIN_REGION_T} and weighs the rest by its noise
     * after scaling (see {@link SharpStacker#minTransparency}).
     */
    private void regionFactors(Unit u, SharpStacker.Plan p) {
        if (u.cellT == null) {
            p.transparency = null;
            u.regionsUsed = -1;
            u.usedFraction = 1f;
            return;
        }
        LocalWeights tr = new LocalWeights(GX, GY, W, H);
        int used = 0;
        for (int c = 0; c < REGIONS; c++) {
            tr.w[c] = u.cellT[c];
            if (u.cellT[c] >= MIN_REGION_T) used++;
        }
        p.transparency = tr;
        u.regionsUsed = used;
        u.usedFraction = used / (float) REGIONS;
    }

    /**
     * The rejection method: what was asked for, else linear-fit clipping when the sky background changed a
     * lot through the session, else chosen per pixel from its number of frames. {@code why[0]} gets its name
     * and the reason, for the decision log.
     */
    private Rejection.Method chooseRejection(List<Unit> frames, String[] why) {
        int n = frames.size();
        float spread = backgroundSpread(frames);
        if (rejection != Rejection.Method.AUTO) {
            why[0] = rejection.label + ", as chosen in the settings";
            return rejection;
        }
        if (n >= 12 && spread > BACKGROUND_CHANGE) {
            why[0] = String.format(Locale.US, "%s, because the sky background changed by %.0f%% during the session "
                    + "(twilight or moonlight)", Rejection.Method.LINEAR_FIT.label, 100 * spread);
            return Rejection.Method.LINEAR_FIT;
        }
        why[0] = String.format(Locale.US, "%s, which suits %d frames (sky background steady within %.0f%%)",
                Rejection.forFrames(n).label, n, 100 * spread);
        return Rejection.Method.AUTO;
    }

    /**
     * Transparency (relative to the clear level) above which region c of a frame counts as clear: 2.5 times
     * the frame-to-frame noise of its transparency below 1, from consecutive frames (in time order).
     */
    private static float clearThreshold(List<Unit> frames, int c) {
        float[] d = new float[frames.size()];
        int n = 0;
        float prev = Float.NaN;
        for (Unit u : frames) {
            if (u.cellT == null) continue;
            if (!Float.isNaN(prev)) d[n++] = Math.abs(u.cellT[c] - prev);
            prev = u.cellT[c];
        }
        float sigma = n >= 2 ? Stats.median(d, n) / 0.954f : 0.04f;
        return 1f - Stats.clamp(2.5f * sigma, CLEAR_MIN_DROP, CLEAR_MAX_DROP);
    }

    /** How much the frames' sky background varied: (P90 - P10) / median of their median green levels. */
    private static float backgroundSpread(List<Unit> frames) {
        float[] v = new float[frames.size()];
        int n = 0;
        for (Unit u : frames) if (u.bg != null) v[n++] = u.bg.medianLevel(1);
        if (n < 3) return 0f;
        float p10 = Stats.quantile(v, n, 0.1), p50 = Stats.quantile(v, n, 0.5), p90 = Stats.quantile(v, n, 0.9);
        return p50 > 0 ? (p90 - p10) / p50 : 0f;
    }
}
