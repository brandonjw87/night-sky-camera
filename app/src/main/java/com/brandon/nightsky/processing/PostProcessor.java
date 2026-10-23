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
        /** The sensor's pattern as measured (null if it wasn't), and the walking noise before and after taking it out. */
        public SensorPattern pattern;
        public double walkingBefore = Double.NaN, walkingAfter = Double.NaN;
        /** Where the reference frame pointed (null if unknown), and the time it was solved for (UTC Julian date). */
        public SkySolver.Solution solution;
        public double solutionJd = Double.NaN;
        /** The stack's real noise gain over one frame (null if not measured), and the unit that frame was. */
        public NoiseProbe.Result noise;
        public int noiseUnit = -1;
        /** Median star size (half-flux radius, pixels) over the used frames. */
        public double medianHfr = Double.NaN;
        /** The reference frame's stars (positions in the stack's coordinates); null if none. */
        public StarList referenceStars;
        /** The sensor's defective pixels, as found in the session's average and taken out of every frame. */
        public HotPixels hot;
        /**
         * The streaks the frames had (satellites by name where a known one explains them, then planes, meteors and
         * the rest), or null if they weren't looked for; with the satellites' predicted paths through the picture
         * (in the stack's pixels, for drawing; null if none were predicted).
         */
        public SatMatch.Result streaks;
        public List<SatPredict.Track> satTracks;
        /** Whether the named satellites' streaks were kept in the picture rather than taken out. */
        public boolean satellitesKept;
        /**
         * The long-exposure blend at half size (null unless {@link PostProcessor#blend}), how many frames it averages
         * and their exposure (s).
         */
        public RgbImage blend;
        public int blendFrames;
        public double blendSeconds;
        /** Why satellites couldn't be named ("" when they could, or weren't asked for). */
        public String satellitesNote = "";
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
        /** Its transparency per region ({@link #GX} x {@link #GY}, row by row) as the stack scaled it; null if not measured. */
        public float[] regionT;
        /** Share of the frame's pixels that went into the stack, and samples rejected as outliers (per channel). */
        public float usedFraction = Float.NaN;
        public long rejectedSamples;
        /** Uncertainty of the frame's position on the sky's path, pixels at the centre (NaN if not on it). */
        public double alignSigma = Double.NaN;
        /** The rotation its own stars gave, before smoothing along the path (null if they gave none). */
        public double[] measuredR;
        /** Stars matched in the alignment (0 if none). */
        public int matched;
        /** Median half-flux radius of its stars (pixels; NaN if unknown). */
        public double hfr = Double.NaN;
        /** Starlight in a halo around its stars over the light in their cores ({@link HaloMeter}); NaN if unknown. */
        public double halo = Double.NaN;
        /** Its sky background in green, ADU per second above black (NaN if unknown). */
        public double background = Double.NaN;
        /** Largest rotation rate while it was exposed (rad/s), and whether that spoiled it. */
        public float gyroMax;
        public boolean disturbed;
        /** Noise of its sky background in green (ADU; NaN if unknown). */
        public double noise = Double.NaN;
        /** Camera timestamp of the start of its exposure (ns). */
        public long timestampNs;
        /** Samples it had for the stack (rejected ones included). */
        public long samples;
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
    /** Whether to measure the sensor's own pattern and take it out (walking noise, banding); tests switch it off. */
    public boolean patternCleanup = true;
    /** Place, time and the star catalogue, to find where the camera pointed; null to skip that. */
    public SessionSky sky;
    /**
     * The unit to use as the reference frame if it can be, and as the noise measurement's one frame (tests set
     * it to compare runs); -1 to choose.
     */
    public int forceReference = -1;
    /** Outlier rejection limits (sigmas above and below). */
    public float clipHigh = 3f, clipLow = 4f;
    /** Satellites to name the streaks after (null: streaks are still found and taken out, but not named). */
    public SatCatalog satellites;
    /** Why {@link #satellites} is null, for the report (when it is). */
    public String satellitesMissing = "no satellite data on the phone";
    /** Keep the named satellites' streaks (and meteors) in the picture, at full brightness, instead of taking them out. */
    public boolean keepSatellites;
    /** Whether streaks get corridors in the next stack (tests switch it off to compare; they're still named). */
    public boolean streakCorridors = true;
    /** Also make the long-exposure blend (C7): the plain average, where drifting clouds turn into soft streaks. */
    public boolean blend;
    /** Faintest satellite looked for (magnitude), and how far outside the picture a track may start (pixels). */
    static final double SAT_MAG_LIMIT = 7;
    static final float SAT_PAD = 64;
    /** Frames the stack needs before a sample far above the others says something (a streak) about its frame. */
    static final int MIN_STREAK_FRAMES = 3;
    /** The reference frame's stars as detected for photometry (the deepest list of it). */
    private StarList refDetections;
    /**
     * The session's average on the sensor, green, lens shading taken out, per {@link StreakEvidence#CELL}-px cell
     * (row by row, {@link #skyCellsX} wide), and its median: where a branch or a roof sits in every frame. Null
     * if not made.
     */
    private float[] sensorSky;
    private int skyCellsX;
    /** Its median, and its noise from cell to cell. */
    private float sensorSkyLevel, sensorSkyNoise;
    /** On the sensor's average, a cell this far below the sky's median (or above it, times) isn't sky. */
    static final float OBSTRUCTION_DARK = 0.35f, OBSTRUCTION_BRIGHT = 3f;
    /** Share of an unexplained streak's length beside something that isn't sky, at which it's that thing's edge. */
    static final double OBSTRUCTION_SHARE = 0.5;

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
        /** Halo ratio of its stars (NaN if not measured). */
        double halo = Double.NaN;
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
        out.hot = hot;
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
            makeBlend(out, units, fs, hot, progress);
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
            try {
                solveSky(ref, model, al, out);
            } catch (RuntimeException e) {
                // naming the sky is a bonus: never let it cost the photo
                store.log("sky: matching the stars to the catalogue failed: " + e);
            }
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
        st.kHigh = clipHigh;
        st.kLow = clipLow;
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

        // 5) the stack, and again without the sensor's pattern and the streaks' faint edges if they show; the noise
        //    gain measured as it goes
        st.noise = noiseProbe(plans, stacked, out.referenceUnit);
        if (st.noise != null) st.noise.mask(NoiseProbe.starMask(refDetections != null ? refDetections : ref != null ? ref.stars : null, W, H));
        StreakEvidence ev = out.aligned && plans.size() >= MIN_STREAK_FRAMES ? new StreakEvidence(plans.size(), W, H) : null;
        st.evidence = ev;
        SharpStacker.Result sr;
        try {
            sr = st.stack(plans, progress);
        } finally {
            st.evidence = null;
        }
        boolean corridors = false;
        if (ev != null) {
            try {
                corridors = streaks(out, ev, stacked, plans, recs, model);
            } catch (RuntimeException e) {
                // naming streaks is a bonus: never let it cost the photo
                for (SharpStacker.Plan p : plans) p.masks = p.keep = null;
                out.streaks = null;
                store.log("streaks: looking for them failed: " + e);
            }
        }
        SharpStacker.Result again = cleanPattern(out, st, sr, plans, target, fixed, model, fs, hot, progress);
        if (corridors && again == sr) again = stackWithoutStreaks(st, sr, plans, progress);
        sr = again;
        if (st.noise != null) {
            out.noise = st.noise.result();
            out.noiseUnit = plans.get(st.noise.single).unit;
            NoiseProbe.Result nr = out.noise;
            store.log(String.format(Locale.US, "noise gain: one frame (unit %d) %.3f, the stack %.3f ADU per %d px block "
                            + "(%d blocks, halves %.1f + %.1f frames): worth %.1f frames of %d", out.noiseUnit, nr.sigmaFrame,
                    nr.sigmaStack, nr.bin, nr.blocks, nr.nEven, nr.nOdd, nr.nEff, plans.size()));
            st.noise = null;
        }
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
            fr.regionT = u.cellT != null ? u.cellT.clone() : null;
            fr.alignSigma = u.sigmaPx;
            if (u.res != null && u.res.ok) {
                fr.measuredR = u.res.R;
                fr.matched = u.res.nInliers;
            }
            if (u.res != null && u.res.ok && u.res.medianHfr > 0) fr.hfr = u.res.medianHfr;
            else if (u.rec.hfr > 0) fr.hfr = u.rec.hfr;
            fr.halo = u.halo;
            if (u.bg != null && u.rec.exposure > 0) fr.background = u.bg.medianLevel(1) / u.rec.exposure;
            else if (u.rec.background > 0 && u.rec.exposure > 0) fr.background = u.rec.background / u.rec.exposure;
            fr.gyroMax = u.rec.gyroMax;
            fr.disturbed = u.rec.disturbed;
            fr.timestampNs = u.rec.sensorTimestampNs;
            if (u.bg != null) fr.noise = u.bg.medianSigma(1);
            else if (u.rec.noise > 0) fr.noise = u.rec.noise;
            Integer pi = planOf.get(u.rec.index);
            if (pi != null) {
                fr.usedFraction = sr.samples[pi] > 0 ? sr.included[pi] / (float) sr.samples[pi] : 0f;
                fr.samples = sr.samples[pi];
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
        float[] hh = new float[units.size()];
        int nhh = 0;
        for (FrameReport fr : out.frames) if (fr.used && fr.hfr > 0) hh[nhh++] = (float) fr.hfr;
        if (nhh > 0) out.medianHfr = Stats.median(hh, nhh);
        out.referenceStars = refDetections != null ? refDetections : ref != null ? ref.stars : null;
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
        makeBlend(out, units, fs, hot, progress);
        return out;
    }

    /**
     * The long-exposure blend (C7), if asked for: every frame that wasn't moved, averaged as it is. A blend that
     * fails or runs out of memory costs only itself.
     */
    private void makeBlend(Output out, List<Unit> units, FrameStore fs, HotPixels hot, Progress progress) throws IOException {
        if (!blend) return;
        List<BlendStacker.Unit> bu = new ArrayList<BlendStacker.Unit>();
        double sec = 0;
        int moved = 0;
        for (int pass = 0; pass < 2 && bu.isEmpty(); pass++) {
            moved = 0;
            for (Unit u : units) {
                if (pass == 0 && u.rec.disturbed) {
                    moved++;
                    continue;
                }
                BlendStacker.Unit b = new BlendStacker.Unit();
                b.unit = u.rec.index;
                b.invScale = u.rec.invScale;
                System.arraycopy(u.rec.black, 0, b.black, 0, 4);
                bu.add(b);
                sec += u.rec.exposure;
            }
        }
        long t0 = System.currentTimeMillis();
        try {
            out.blend = new BlendStacker(spec, fs, hot).stack(bu, progress);
            out.blendFrames = bu.size();
            out.blendSeconds = sec;
            decide(out, String.format(Locale.US, "cloud blur: also saved as the plain average of %d frames (%s), not lined up and "
                            + "nothing clipped, so clouds show as soft streaks; at half size%s (%d ms)", bu.size(), duration(sec),
                    moved > 0 ? ", " + moved + " moved frame" + (moved == 1 ? "" : "s") + " left out" : "", System.currentTimeMillis() - t0));
        } catch (OutOfMemoryError e) {
            out.blend = null;
            decide(out, "cloud blur: not made, ran out of memory");
        }
    }

    /**
     * The sensor's own pattern (walking noise, banding; see {@link SensorPattern}), measured from the frames
     * against the first stack and, where it stands above the noise, taken out of every frame for a second
     * stack. Needs the sky to have drifted across the sensor; skipped (and said so) when it can't help.
     */
    private SharpStacker.Result cleanPattern(Output out, SharpStacker st, SharpStacker.Result sr, List<SharpStacker.Plan> plans,
                                             BackgroundGrid target, StaticSources fixed, CameraModel model, FrameStore fs,
                                             HotPixels hot, Progress progress) throws IOException {
        if (!patternCleanup || !out.aligned) return sr;
        if (plans.size() < SensorPattern.MIN_FRAMES) {
            store.log(String.format(Locale.US, "sensor pattern: not measured, %d frames are too few (it needs %d)",
                    plans.size(), SensorPattern.MIN_FRAMES));
            return sr;
        }
        double[] dr = drift(plans, model);
        if (dr[0] < SensorPattern.MIN_DRIFT_PX) {
            decide(out, String.format(Locale.US, "sensor pattern: left alone, the sky moved only %.0f px across the sensor, "
                    + "too little to tell the sensor's pattern from the sky (and too little for it to streak)", dr[0]));
            return sr;
        }
        double before = SensorPattern.walkingRatio(sr.image, dr[1], dr[2]);
        store.log(String.format(Locale.US, "sensor pattern: the sky drifted %.0f px across the sensor, at %.0f deg in the stack",
                dr[0], Math.toDegrees(Math.atan2(dr[2], dr[1]))));
        // the estimate's full-frame maps, and room for its per-band samples
        Runtime rt = Runtime.getRuntime();
        long free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        long maps = (long) W * H * 11L;
        long budget = Math.min(64L << 20, (free - maps - (96L << 20)) / 2);
        if (budget < (8L << 20)) {
            decide(out, String.format(Locale.US, "sensor pattern: not measured, not enough memory (streaks along the drift %.2f, "
                    + "1 = none)", before));
            return sr;
        }
        SensorPattern sp;
        try {
            sp = SensorPattern.estimate(spec, model, fs, hot, fixed, plans, target, st.minTransparency, sr.image, dr[0],
                    budget, progress);
        } catch (OutOfMemoryError e) {
            decide(out, "sensor pattern: not measured, ran out of memory");
            return sr;
        }
        store.log(sp.describe());
        out.pattern = sp;
        out.walkingBefore = before;
        out.walkingAfter = before;
        if (!sp.worthRemoving()) {
            decide(out, String.format(Locale.US, "sensor pattern: nothing above the noise to take out (streaks along the "
                    + "drift %.2f, 1 = none)", before));
            return sr;
        }
        sp.attach(plans, true);
        st.into = sr.image;
        st.label = "Stacking again without the sensor's pattern";
        SharpStacker.Result again;
        try {
            again = st.stack(plans, progress);
        } catch (OutOfMemoryError e) {
            // the first stack was being overwritten: make it again as it was
            sp.attach(plans, false);
            out.pattern = null;
            st.label = "Stacking";
            again = st.stack(plans, progress);
            decide(out, "sensor pattern: measured but not taken out, ran out of memory");
            return again;
        } finally {
            st.into = null;
        }
        double after = SensorPattern.walkingRatio(again.image, dr[1], dr[2]);
        out.walkingAfter = after;
        decide(out, String.format(Locale.US, "sensor pattern: taken out of every frame and stacked again (%s); streaks along "
                + "the drift %.2f before, %.2f after (1 = none)", sp.parts(), before, after));
        return again;
    }

    /**
     * The streaks in the first stack's rejections: the known satellites' predicted paths tried against them and
     * named where they fit, the rest found as lines (planes, meteors, satellites the data doesn't have). Each
     * streak's frame gets a corridor along it: left out of the next stack, so the faint edges the rejection
     * missed go too; or, for named satellites and meteors when {@link #keepSatellites}, kept on top of the stack
     * at full brightness. True if a frame has a corridor (the stack must be made again).
     */
    private boolean streaks(Output out, StreakEvidence ev, List<Unit> stacked, List<SharpStacker.Plan> plans,
                            List<UnitRecord> recs, CameraModel model) {
        int fixedCells = clearSensorFixed(ev, plans, model);
        if (fixedCells > 0) {
            store.log(String.format(Locale.US, "streaks: %d cells of rejections stayed put on the sensor (a branch's edge, a lit "
                    + "leaf, a warm patch), so they're not counted as streaks", fixedCells));
        }
        List<SatPredict.Track> tracks = predictSatellites(out, stacked, recs);
        int maxIndex = 0;
        for (SharpStacker.Plan p : plans) maxIndex = Math.max(maxIndex, p.unit);
        int[] planOf = new int[maxIndex + 1];
        java.util.Arrays.fill(planOf, -1);
        for (int i = 0; i < plans.size(); i++) planOf[plans.get(i).unit] = i;
        double degPerPx = Math.toDegrees(1 / (out.solution != null ? out.solution.model.fx : model.fx));
        long t0 = System.currentTimeMillis();
        SatMatch.Result r = SatMatch.match(tracks != null ? tracks : new ArrayList<SatPredict.Track>(), planOf, ev, degPerPx, W);
        int edges = 0;
        for (java.util.Iterator<SatMatch.Streak> it = r.streaks.iterator(); it.hasNext(); ) {
            SatMatch.Streak s = it.next();
            if (s.sat != null || s.plan < 0 || s.plan >= plans.size()) continue;
            if (besideObstruction(s, plans.get(s.plan), model) >= OBSTRUCTION_SHARE) {
                it.remove();
                edges++;
            }
        }
        if (edges > 0) {
            store.log(String.format(Locale.US, "streaks: %d along the edge of something on the sensor that isn't sky (a branch, "
                    + "a roof), so they're not counted", edges));
        }
        out.streaks = r;
        out.satTracks = tracks;
        out.satellitesKept = keepSatellites;
        store.log(String.format(Locale.US, "streaks: %s (%d ms)", SatMatch.describe(r), System.currentTimeMillis() - t0));
        List<List<float[]>> masks = new ArrayList<List<float[]>>(), keeps = new ArrayList<List<float[]>>();
        for (int i = 0; i < plans.size(); i++) {
            masks.add(new ArrayList<float[]>());
            keeps.add(new ArrayList<float[]>());
        }
        int masked = 0, kept = 0;
        for (SatMatch.Streak s : r.streaks) {
            if (Float.isNaN(s.x0) || s.plan < 0 || s.plan >= plans.size()) continue;
            float[] c = {s.x0, s.y0, s.x1, s.y1, s.halfWidth};
            boolean keep = keepSatellites && (s.sat != null || "meteor".equals(s.kind));
            (keep ? keeps : masks).get(s.plan).add(c);
            if (keep) kept++;
            else masked++;
            store.log(String.format(Locale.US, "streak: unit %d %s%s, (%.0f,%.0f)-(%.0f,%.0f) +-%.0f px, %d cells%s%s",
                    s.unit, s.name(), s.kind.isEmpty() ? "" : " (" + s.kind + ")", s.x0, s.y0, s.x1, s.y1, s.halfWidth, s.cells,
                    s.sat != null ? String.format(Locale.US, " of %d, %.1f px across, %+.1f s along, mag %.1f, elements %.1f days old",
                            s.expected, s.across, s.alongSec, s.mag, s.track.ageDays) : "",
                    keep ? ", kept" : ", masked"));
        }
        if (!streakCorridors) {
            store.log("streaks: corridors switched off, the stack keeps what the rejection left");
            return false;
        }
        for (int i = 0; i < plans.size(); i++) {
            plans.get(i).masks = flat(masks.get(i));
            plans.get(i).keep = flat(keeps.get(i));
        }
        if (!r.streaks.isEmpty()) {
            List<String> names = new ArrayList<String>();
            for (SatMatch.Streak s : r.streaks) if (s.sat != null && !names.contains(s.name())) names.add(s.name());
            int planes = 0, meteors = 0, other = 0;
            for (SatMatch.Streak s : r.streaks) {
                if (s.sat != null) continue;
                if ("plane".equals(s.kind)) planes++;
                else if ("meteor".equals(s.kind)) meteors++;
                else other++;
            }
            List<String> what = new ArrayList<String>();
            if (!names.isEmpty()) what.add(names.size() <= 4 ? join(names) : join(names.subList(0, 3)) + " and " + (names.size() - 3) + " more satellites");
            if (planes > 0) what.add(planes + (planes == 1 ? " plane" : " planes"));
            if (meteors > 0) what.add(meteors + (meteors == 1 ? " possible meteor" : " possible meteors"));
            if (other > 0) what.add(other + (other == 1 ? " streak nothing explains" : " streaks nothing explains"));
            decide(out, String.format(Locale.US, "streaks: %d in %d frame%s (%s); %s", r.streaks.size(), framesWith(r),
                    framesWith(r) == 1 ? "" : "s", join(what),
                    kept == 0 ? "a corridor along each left out of its frame, faint edges included"
                            : masked == 0 ? "kept in the picture at full brightness, as asked"
                            : kept + " kept in the picture as asked, the other " + masked + " left out"));
        } else if (tracks != null && !tracks.isEmpty()) {
            store.log("streaks: none; the predicted satellites were too faint to leave one");
        }
        return masked > 0 || kept > 0;
    }

    /**
     * Clears the rejections that stay put on the sensor rather than on the sky: a branch's edges, a lit leaf, a
     * warm patch, which drift through the stack with the frames. Each frame's cells are taken back to where they
     * were on its sensor; a sensor cell (or its neighbour) with rejections in at least half the frames, and three,
     * is fixed, and its cells go from every frame, unless the same cells of the sky had them as often (a train of
     * satellites along one line, or stars: when the sky hardly drifted, the two can't be told apart, and nothing
     * goes). A satellite, a plane or a meteor crosses a cell of the sensor in one frame. Returns the cells cleared.
     */
    private int clearSensorFixed(StreakEvidence ev, List<SharpStacker.Plan> plans, CameraModel model) {
        int nf = plans.size();
        if (nf < 4) return 0;
        int cw = ev.cellsX, chh = ev.cellsY, c = StreakEvidence.CELL;
        if (drift(plans, model)[0] < 3 * c) return 0;
        short[] count = new short[cw * chh], sky = new short[cw * chh];
        for (int f = 0; f < nf; f++) for (int q : ev.cells(f)) if (sky[q] < Short.MAX_VALUE) sky[q]++;
        int[] stamp = new int[cw * chh];
        int[][] where = new int[nf][];
        double[] b = new double[3], r = new double[3], p = new double[2];
        for (int f = 0; f < nf; f++) {
            double[] R = plans.get(f).R;
            int[] cells = ev.cells(f);
            where[f] = new int[cells.length];
            for (int k = 0; k < cells.length; k++) {
                int cx = cells[k] % cw, cy = cells[k] / cw;
                where[f][k] = -1;
                model.unproject((cx + 0.5) * c, (cy + 0.5) * c, b);
                Rot.apply(R, b[0], b[1], b[2], r);
                if (!model.project(r[0], r[1], r[2], p)) continue;
                int sx = (int) Math.floor(p[0] / c), sy = (int) Math.floor(p[1] / c);
                if (sx < 0 || sy < 0 || sx >= cw || sy >= chh) continue;
                int q = sy * cw + sx;
                where[f][k] = q;
                if (stamp[q] != f + 1) {
                    stamp[q] = f + 1;
                    if (count[q] < Short.MAX_VALUE) count[q]++;
                }
            }
        }
        int need = Math.max(3, (nf + 1) / 2), cleared = 0;
        for (int f = 0; f < nf; f++) {
            int[] cells = ev.cells(f);
            for (int k = 0; k < cells.length; k++) {
                int q = where[f][k];
                if (q < 0) continue;
                // positions are good to a cell: the cell or its neighbours, on the sensor and on the sky
                int most = max3x3(count, q % cw, q / cw, cw, chh), onSky = max3x3(sky, cells[k] % cw, cells[k] / cw, cw, chh);
                if (most >= need && onSky < need) {
                    ev.clear(f, cells[k] % cw, cells[k] / cw);
                    cleared++;
                }
            }
        }
        return cleared;
    }

    private static int max3x3(short[] a, int cx, int cy, int cw, int ch) {
        int m = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && y >= 0 && x < cw && y < ch) m = Math.max(m, a[y * cw + x]);
            }
        }
        return m;
    }

    private static int framesWith(SatMatch.Result r) {
        java.util.Set<Integer> f = new java.util.HashSet<Integer>();
        for (SatMatch.Streak s : r.streaks) f.add(s.plan);
        return f.size();
    }

    private static float[] flat(List<float[]> l) {
        if (l.isEmpty()) return null;
        float[] o = new float[l.size() * 5];
        for (int i = 0; i < l.size(); i++) System.arraycopy(l.get(i), 0, o, i * 5, 5);
        return o;
    }

    /**
     * Where the known satellites crossed the picture during each stacked frame: from the plate solution, the
     * place and the frames' times. Null (and why, in the output) when one of those is missing.
     */
    private List<SatPredict.Track> predictSatellites(Output out, List<Unit> stacked, List<UnitRecord> recs) {
        if (satellites == null || satellites.size() == 0) {
            out.satellitesNote = satellitesMissing;
            store.log("satellites: no orbital elements (" + satellitesMissing + "), so streaks aren't named");
            return null;
        }
        if (out.solution == null) {
            out.satellitesNote = "the stars weren't matched to the catalogue";
            store.log("satellites: not predicted, the sky wasn't solved");
            return null;
        }
        if (sky == null || !sky.located() || !sky.clockValid) {
            out.satellitesNote = sky == null || !sky.located() ? "the place wasn't known" : "the time wasn't known";
            store.log("satellites: not predicted, " + out.satellitesNote);
            return null;
        }
        SatSky.Site site = new SatSky.Site(Math.toRadians(sky.latDeg), Math.toRadians(sky.lonDeg),
                Double.isNaN(sky.altM) ? 0 : sky.altM / 1000);
        site.errKm = Double.isNaN(sky.accuracyM) ? 0 : Math.min(5, sky.accuracyM / 1000);
        // the session's clock starts at its first frame's exposure; a unit of several frames spans from its first
        // frame's start to its last's end
        long t0Ns = recs.get(0).sensorTimestampNs;
        List<SatPredict.Exposure> ex = new ArrayList<SatPredict.Exposure>();
        double jdA = Double.MAX_VALUE, jdB = -Double.MAX_VALUE;
        for (Unit u : stacked) {
            UnitRecord r = u.rec;
            if (r.sensorTimestampNs == 0) continue;
            double span = r.exposure;
            if (r.frames > 1 && t0Ns != 0) {
                double toMid = (t0Ns + r.t * 1e9 - r.sensorTimestampNs) * 1e-9;
                span = Math.max(r.exposure, Math.min(2 * toMid, r.exposure * 1.5 + 2));
            }
            double jd0 = sky.jdUtc(r.sensorTimestampNs);
            ex.add(new SatPredict.Exposure(r.index, jd0, span));
            jdA = Math.min(jdA, jd0);
            jdB = Math.max(jdB, jd0 + span / 86400);
        }
        if (ex.isEmpty()) {
            out.satellitesNote = "the frames' times weren't recorded";
            return null;
        }
        long t0 = System.currentTimeMillis();
        double[] f = SatPredict.fieldOf(out.solution, W, H);
        List<SatCatalog.Sat> all = satellites.all();
        List<SatCatalog.Sat> cands = SatPredict.candidates(all, site, jdA, jdB, new double[]{f[0], f[1], f[2]}, f[3], SAT_MAG_LIMIT);
        List<SatPredict.Track> tracks = SatPredict.tracks(cands, site, ex, out.solution, W, H, SAT_MAG_LIMIT, SAT_PAD);
        double oldest = 0;
        for (SatCatalog.Sat s : cands) oldest = Math.max(oldest, Math.abs(s.ageDays(jdA)));
        store.log(String.format(Locale.US, "satellites: %d in the data, %d near the picture, %d tracks through it in %d frames "
                        + "(oldest elements %.1f days; place good to %.1f km; %d ms)", all.size(), cands.size(), tracks.size(), ex.size(),
                oldest, site.errKm, System.currentTimeMillis() - t0));
        return tracks;
    }

    /** The stack made again with the streaks' corridors (the pattern step didn't make it again anyway). */
    private SharpStacker.Result stackWithoutStreaks(SharpStacker st, SharpStacker.Result sr, List<SharpStacker.Plan> plans,
                                                    Progress progress) throws IOException {
        st.into = sr.image;
        st.label = "Stacking again without the streaks";
        try {
            return st.stack(plans, progress);
        } catch (OutOfMemoryError e) {
            // the first stack was being overwritten: make it again as it was, streaks and all
            for (SharpStacker.Plan p : plans) p.masks = p.keep = null;
            st.label = "Stacking";
            store.log("streaks: ran out of memory taking them out; stacked again as before");
            return st.stack(plans, progress);
        } finally {
            st.into = null;
            st.label = "Stacking";
        }
    }

    /**
     * How the sensor moved against the sky over the stacked frames, seen in the stack: {largest distance
     * between the frames' centres (pixels), direction x, direction y (first to last frame)}.
     */
    private double[] drift(List<SharpStacker.Plan> plans, CameraModel model) {
        double[] b = new double[3], r = new double[3], p = new double[2];
        model.unproject(W / 2.0, H / 2.0, b);
        int n = plans.size(), m = 0;
        double[] xs = new double[n], ys = new double[n];
        for (SharpStacker.Plan pl : plans) {
            Rot.apply(Rot.transpose(pl.R), b[0], b[1], b[2], r);
            if (!model.project(r[0], r[1], r[2], p)) continue;
            xs[m] = p[0];
            ys[m] = p[1];
            m++;
        }
        if (m < 2) return new double[]{0, 1, 0};
        double ext = 0;
        for (int i = 0; i < m; i++) for (int j = i + 1; j < m; j++) ext = Math.max(ext, Math.hypot(xs[i] - xs[j], ys[i] - ys[j]));
        double dx = xs[m - 1] - xs[0], dy = ys[m - 1] - ys[0], d = Math.hypot(dx, dy);
        return d > 0 ? new double[]{ext, dx / d, dy / d} : new double[]{ext, 1, 0};
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
        sensorSky(sum);
        if (n < 2) return HotPixels.empty(W, H);
        // with few frames stars barely move on the sensor, so be stricter
        float k = n >= 8 ? 8f : 12f;
        return HotPixels.detect(sum, W, H, spec.cfa, k);
    }

    /** Makes {@link #sensorSky} from the session's average on the sensor (black taken off). */
    private void sensorSky(final float[] avg) {
        final int c = StreakEvidence.CELL, cw = (W + c - 1) / c, ch = (H + c - 1) / c;
        final int gq = Preflight.greenSite(spec);
        final float[] map = new float[cw * ch];
        final ShadingMap sh = spec.shading;
        Parallel.forRange(ch, 8, new Parallel.Range() {
            @Override
            public void run(int r0, int r1) {
                for (int cy = r0; cy < r1; cy++) {
                    for (int cx = 0; cx < cw; cx++) {
                        double sum = 0;
                        int n = 0;
                        for (int y = cy * c; y < Math.min(H, (cy + 1) * c); y++) {
                            for (int x = cx * c; x < Math.min(W, (cx + 1) * c); x++) {
                                if (spec.cfa[((y & 1) << 1) | (x & 1)] != 1) continue;
                                sum += avg[y * W + x];
                                n++;
                            }
                        }
                        float gain = sh != null ? sh.gain(gq, (cx + 0.5) * c, (cy + 0.5) * c) : 1f;
                        map[cy * cw + cx] = n > 0 ? (float) (sum / n) * gain : 0f;
                    }
                }
            }
        });
        // the median from every 7th cell
        float[] some = new float[(map.length + 6) / 7];
        for (int i = 0, k = 0; i < map.length && k < some.length; i += 7) some[k++] = map[i];
        java.util.Arrays.sort(some);
        float level = some[some.length / 2];
        // noise: the median step between neighbouring cells, as a sigma
        float[] steps = new float[some.length];
        int k = 0;
        for (int i = 0; i + 1 < map.length && k < steps.length; i += 7) {
            if ((i + 1) % cw == 0) continue;
            steps[k++] = Math.abs(map[i + 1] - map[i]);
        }
        java.util.Arrays.sort(steps, 0, k);
        float noise = k > 0 ? (float) (1.4826 * steps[k / 2] / Math.sqrt(2)) : 0f;
        if (!(level > 0)) {
            sensorSky = null;
            return;
        }
        sensorSky = map;
        skyCellsX = cw;
        sensorSkyLevel = level;
        sensorSkyNoise = noise;
    }

    /**
     * Share of a streak's length that runs beside something on the sensor that isn't sky: a cell within its
     * corridor (and two cells more) that the session's average shows far darker than the sky (a branch, a
     * roof) or far brighter (a lit wall). An obstruction's edge leaves rejections along it in the frames at
     * either end of a session, as the sky drifts past it; a satellite crosses open sky. Stack coordinates are
     * taken to the streak's frame's sensor through its rotation.
     */
    private double besideObstruction(SatMatch.Streak s, SharpStacker.Plan plan, CameraModel model) {
        if (sensorSky == null || plan.R == null) return 0;
        int c = StreakEvidence.CELL, cw = skyCellsX, ch = sensorSky.length / cw;
        double dx = s.x1 - s.x0, dy = s.y1 - s.y0, len = Math.hypot(dx, dy);
        if (len < c) return 0;
        double ux = dx / len, uy = dy / len, reach = s.halfWidth + 2 * c;
        double[] b = new double[3], r = new double[3], p = new double[2];
        int samples = 0, beside = 0;
        for (double u = 0; u <= len; u += c) {
            boolean seen = false, odd = false;
            for (double o = -reach; o <= reach + 1e-9 && !odd; o += c) {
                double x = s.x0 + ux * u - uy * o, y = s.y0 + uy * u + ux * o;
                model.unproject(x, y, b);
                Rot.apply(plan.R, b[0], b[1], b[2], r);
                if (!model.project(r[0], r[1], r[2], p)) continue;
                int sx = (int) Math.floor(p[0] / c), sy = (int) Math.floor(p[1] / c);
                if (sx < 0 || sy < 0 || sx >= cw || sy >= ch) continue;
                seen = true;
                float v = sensorSky[sy * cw + sx], L = sensorSkyLevel, clear = 6 * sensorSkyNoise;
                odd = (v < OBSTRUCTION_DARK * L && L - v > clear) || (v > OBSTRUCTION_BRIGHT * L && v - L > clear);
            }
            if (!seen) continue;
            samples++;
            if (odd) beside++;
        }
        return samples > 0 ? beside / (double) samples : 0;
    }

    /** A clear frame near the middle of the session: most star light, with a mild preference for the middle. */
    private Unit chooseReference(List<Unit> units) {
        double t0 = Double.MAX_VALUE, t1 = -Double.MAX_VALUE;
        for (Unit u : units) {
            t0 = Math.min(t0, u.rec.t);
            t1 = Math.max(t1, u.rec.t);
        }
        double mid = 0.5 * (t0 + t1), span = Math.max(1e-3, t1 - t0);
        for (Unit u : units) {
            if (u.rec.index == forceReference && u.stars != null && u.stars.count >= Aligner.MIN_REF_STARS) return u;
        }
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
            f.reference = u.rec.index == out.referenceUnit;
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
     * Where the reference frame pointed, from its stars and the bright-star catalogue ({@link SkySolver}):
     * what the photo shows, where satellites cross it, and its photometric zero point.
     */
    private void solveSky(Unit ref, CameraModel model, Aligner al, Output out) {
        if (sky == null || sky.catalog == null || refDetections == null) return;
        StarList confirmed = confirmedStars(al);
        SkySolver.Prior pr = new SkySolver.Prior();
        if (sky.clockValid && ref.rec.sensorTimestampNs != 0) {
            pr.jdUtc = sky.jdUtc(ref.rec.sensorTimestampNs) + ref.rec.exposure / 2 / 86400.0;
        }
        if (sky.located()) {
            pr.lat = sky.latDeg * Astro.DEG;
            pr.lon = sky.lonDeg * Astro.DEG;
        }
        long t0 = System.currentTimeMillis();
        SkySolver.Solution s = SkySolver.solve(confirmed != null ? confirmed : refDetections, refDetections, model,
                sky.catalog, pr);
        long ms = System.currentTimeMillis() - t0;
        if (s == null) {
            decide(out, String.format(Locale.US, "sky: couldn't match the stars to the catalogue (%d stars, %s), so the "
                    + "photo isn't labelled", refDetections.count, pr.located() ? "searched around the zenith" : "no place or time"));
            return;
        }
        out.solution = s;
        out.solutionJd = pr.jdUtc;
        store.log(String.format(Locale.US, "sky: %s (%d ms, %s)", s.describe(), ms, sky.describe()));
    }

    /**
     * The reference frame's stars that other frames matched too, brightest first: what's left is starlight, not
     * a plane, a satellite or a meteor that happened to cross the reference frame. Null with too few frames to tell.
     */
    private static StarList confirmedStars(Aligner al) {
        StarList s = al.reference();
        if (s == null) return null;
        int[] seen = new int[s.count];
        int others = 0;
        for (Aligner.Result r : al.results()) {
            if (!r.ok || r.unit == al.referenceUnit() || r.mRef == null) continue;
            others++;
            for (int i : r.mRef) if (i >= 0 && i < s.count) seen[i]++;
        }
        if (others < 2) return null;
        int need = Math.max(2, Math.min(4, others / 4));
        StarList c = new StarList(s.count);
        for (int i = 0; i < s.count; i++) {
            if (seen[i] >= need) c.add(s.x[i], s.y[i], s.flux[i], s.hfr[i], s.elong[i], s.saturated[i]);
        }
        return c.count >= 8 ? c : null;
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
        refDetections = ps;
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
            // a halo growing around the stars: dew or frost forming on the lens (for the report)
            StarList hs = u == ref ? ps : u.stars;
            if (hs != null && hs.count >= 5) {
                HaloMeter.Result hm = HaloMeter.measure(raw, u.rec.invScale, u.rec.black, spec, hs, Math.max(0.3f, hs.noise));
                u.halo = hm.ratio;
            }
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

    /**
     * The noise probe for the stack. Its "one frame" is the best of the frames used all over (most starlight
     * against its sky's noise), so the gain reads in frames like your best ones; when a test has fixed the
     * reference frame, the reference. The halves are weighed by each frame's weight and share used.
     */
    private NoiseProbe noiseProbe(List<SharpStacker.Plan> plans, List<Unit> stacked, int referenceUnit) {
        if (plans.size() < 2) return null;
        double[] pw = new double[plans.size()];
        int single = -1, ref = -1;
        double best = -1;
        for (int i = 0; i < plans.size(); i++) {
            SharpStacker.Plan p = plans.get(i);
            Unit u = null;
            for (Unit v : stacked) if (v.rec.index == p.unit) u = v;
            pw[i] = p.weight * (u != null ? u.usedFraction : 1f);
            if (p.unit == referenceUnit) ref = i;
            if (u == null || u.bg == null || (u.regionsUsed >= 0 && u.regionsUsed < REGIONS)) continue;
            double t = Float.isNaN(u.transparency) ? 1 : Math.min(1, u.transparency);
            double sig = u.bg.medianSigma(1);
            double snr = (sig > 0 ? t * u.rec.exposure / sig : t) * (p.unit == referenceUnit ? 1.02 : 1);
            if (snr > best) {
                best = snr;
                single = i;
            }
        }
        if (forceReference >= 0 && ref >= 0) single = ref;
        if (single < 0) single = ref >= 0 ? ref : 0;
        return new NoiseProbe(W, H, single, pw);
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
