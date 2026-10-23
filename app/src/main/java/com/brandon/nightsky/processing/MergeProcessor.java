package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * Several sessions of the same patch of sky stacked as one (G1): nights repeated at the same sidereal time ("Repeat
 * tomorrow"), or a session and the minutes added to it after it ended.
 * <p>
 * Each session is read, lined up and graded on its own exactly as when it was developed (its own reference frame,
 * its own defective pixels, its own sky's path), but with the base session's lens model so that every frame maps
 * the same way. Each session's reference frame is then placed on the base's: first from the two plate solutions
 * (both give J2000 directions, so the days between them don't matter), then refined on the stars the two reference
 * frames share. A frame of another session maps to the base through its own rotation and that one.
 * <p>
 * Its starlight is matched to the base's by aperture photometry of the shared stars (another night may be clearer,
 * or its frames longer), its sky background is swapped for the base's, and every frame is weighed by its noise
 * after that, so a night under a brighter sky counts for less. One stack of every frame follows, with the outliers
 * rejected over all of them.
 */
public final class MergeProcessor {
    /** One session to merge, as it was set up when it was captured. */
    public static final class Session {
        public final SessionStore store;
        public final SensorSpec spec;
        /** Place, time and the star catalogue, for its plate solution. */
        public SessionSky sky;
        /** When it started (Unix ms; 0 if unknown): for the nights it spans, and the frames' times. */
        public long startMs;
        /** Only these of its units (null: all), as a recipe chose them (G3), and how. */
        public Set<Integer> include;
        public String includeNote = "";
        /** A few words for the report, e.g. "23 Oct" or "the 5 minutes added"; "" for its id. */
        public String label = "";

        public Session(SessionStore store, SensorSpec spec) {
            this.store = store;
            this.spec = spec;
        }

        String name() {
            return label.isEmpty() ? "session " + store.id() : label;
        }
    }

    /** What one session added, for the report and the coverage map. */
    public static final class Part {
        public String id = "", label = "";
        public long startMs;
        public int framesTotal, framesUsed;
        /** Seconds of exposure that went in. */
        public double seconds;
        /** Its starlight at full transparency relative to the base's (photometry of the stars both share). */
        public double signal = 1;
        /** Shared stars measured for that, 0 if too few (then taken as 1). */
        public int photometryStars;
        /** Rotation from the base's reference bearings to its reference frame's; identity for the base. */
        public double[] fromBase;
        /** Stars of its reference frame matched to the base's, and their scatter (px). */
        public int matched;
        public double rms = Double.NaN;
        /** How far its plate solution put it from where the shared stars did (px at the centre). */
        public double solutionOffsetPx = Double.NaN;
        /** Its reference frame's outline in the base's pixels: x, y pairs, clockwise. */
        public float[] outline;
        /** Why it was left out (null when it was merged). */
        public String skipped;
    }

    public static final class Result {
        public PostProcessor.Output out;
        public final List<Part> parts = new ArrayList<Part>();
        /** Sessions covering each {@link #COVER_STEP}-px cell of the base frame (row by row), and the cells across and down. */
        public byte[] coverage;
        public int coverW, coverH;
        /** Sessions merged and nights they span. */
        public int sessions, nights;
        /** "47 minutes over 3 nights", "15 minutes in 2 sessions". */
        public String total = "";
    }

    /** Cell size (pixels) of the coverage map. */
    public static final int COVER_STEP = 64;
    /** Sessions are offered for merging when their centres are this close (degrees) ... */
    public static final double MAX_CENTRE_DEG = 5;
    /** ... and their places this close (km). */
    public static final double MAX_SITE_KM = 5;
    /** Shared stars needed to confirm where a session goes, and the scatter they may have (px). */
    static final int MIN_SHARED = 12;
    static final double MAX_SHARED_RMS = 1.2;
    /** Radius (px) the shared stars are matched within, starting from the plate solutions. */
    static final double SOLUTION_RADIUS = 10;
    /** Stars needed for the photometry between two sessions; fewer and their starlight counts as equal. */
    static final int MIN_PHOT_STARS = 8;

    private final List<Session> sessions;
    public float clipHigh = 3f, clipLow = 4f;
    /** Bytes the stack may keep per-sample buffers in; 0 sizes it from free memory (as {@link PostProcessor#stackMemory}). */
    public long stackMemory;
    public boolean colorCalibration = true;
    /** Outlier rejection as the base session chose it; AUTO picks per pixel by the frames there. */
    public Rejection.Method rejection = Rejection.Method.AUTO;

    public MergeProcessor(List<Session> sessions) {
        if (sessions.isEmpty()) throw new IllegalArgumentException("nothing to merge");
        this.sessions = new ArrayList<Session>(sessions);
    }

    public Result run(Progress progress) throws IOException {
        final Session base = sessions.get(0);
        final SensorSpec spec = base.spec;
        final int W = spec.width, H = spec.height, ns = sessions.size();
        Result res = new Result();
        PostProcessor.Output out = new PostProcessor.Output();
        res.out = out;
        SessionStore log = base.store;
        log.log(String.format(Locale.US, "merge: %d sessions into %s", ns, base.store.id()));
        // the stack reads every frame twice: it gets as much of the progress as all the reading before it
        float slice = 1f / (2 * ns);

        // 1) the base, read and lined up as when it was developed
        PostProcessor bp = processor(base, null);
        PostProcessor.Prep pb = bp.prepareOnly(part(progress, 0, slice));
        if (pb.ref == null || pb.plans.isEmpty() || !pb.out.aligned) {
            throw new IOException("The first session has no frames lined up on the stars, so nothing can be merged into it");
        }
        if (pb.out.solution == null) {
            throw new IOException("The first session's stars couldn't be matched to the catalogue, so other sessions can't be placed on it");
        }
        CameraModel model = pb.model;
        Part p0 = new Part();
        p0.id = base.store.id();
        p0.label = base.name();
        p0.startMs = base.startMs;
        p0.framesTotal = pb.units.size();
        p0.framesUsed = pb.plans.size();
        p0.seconds = pb.integ;
        p0.fromBase = Rot.identity();
        p0.matched = pb.ref.stars != null ? pb.ref.stars.count : 0;
        p0.outline = outline(model, Rot.identity(), W, H);
        res.parts.add(p0);
        StarList refStars = bp.referenceDetections() != null ? bp.referenceDetections() : pb.ref.stars;

        List<SharpStacker.Plan> plans = new ArrayList<SharpStacker.Plan>(pb.plans);
        // per plan, its unit and session; per session, its prepared state
        List<PostProcessor.Unit> planUnits = new ArrayList<PostProcessor.Unit>();
        List<Integer> planSession = new ArrayList<Integer>();
        Map<Integer, PostProcessor.Unit> baseByIndex = new HashMap<Integer, PostProcessor.Unit>();
        for (PostProcessor.Unit u : pb.units) baseByIndex.put(u.rec.index, u);
        for (SharpStacker.Plan p : pb.plans) {
            planUnits.add(baseByIndex.get(p.unit));
            planSession.add(0);
        }
        List<PostProcessor.Prep> preps = new ArrayList<PostProcessor.Prep>();
        preps.add(pb);
        short[] rawB = null;

        for (int k = 1; k < ns; k++) {
            Session s = sessions.get(k);
            Part pk = new Part();
            pk.id = s.store.id();
            pk.label = s.name();
            pk.startMs = s.startMs;
            res.parts.add(pk);
            preps.add(null);
            if (s.spec.width != W || s.spec.height != H || !java.util.Arrays.equals(s.spec.cfa, spec.cfa)) {
                skip(out, log, pk, "it was taken at another size or with another camera");
                continue;
            }
            PostProcessor kp = processor(s, model);
            PostProcessor.Prep pr;
            try {
                pr = kp.prepareOnly(part(progress, k * slice, slice));
            } catch (CancelledException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                skip(out, log, pk, "it couldn't be read (" + e.getMessage() + ")");
                continue;
            }
            pk.framesTotal = pr.units.size();
            if (pr.ref == null || pr.plans.isEmpty() || !pr.out.aligned) {
                skip(out, log, pk, "none of its frames lined up on the stars");
                continue;
            }
            if (pr.out.solution == null) {
                skip(out, log, pk, "its stars couldn't be matched to the catalogue");
                continue;
            }
            // 2) its reference frame on the base's: the plate solutions (base bearings to J2000 to its bearings), then
            //    the stars both reference frames have
            double[] guess = Rot.mul(Rot.transpose(pr.out.solution.R), pb.out.solution.R);
            double apart = Math.toDegrees(Rot.angle(guess));
            double[] cb = pb.out.solution.direction(model.cx, model.cy), ck = pr.out.solution.direction(model.cx, model.cy);
            double centres = Math.toDegrees(Math.acos(Stats.clamp(cb[0] * ck[0] + cb[1] * ck[1] + cb[2] * ck[2], -1, 1)));
            if (centres > 2 * MAX_CENTRE_DEG) {
                skip(out, log, pk, String.format(Locale.US, "it points %.0f degrees away from the first session", centres));
                continue;
            }
            Aligner al = new Aligner(model, W, H);
            al.process(-1, pb.ref.stars, 0);
            Aligner.Result m = al.rematch(-2, pr.ref.stars, guess, SOLUTION_RADIUS);
            if (!m.ok || m.nInliers < MIN_SHARED || m.rms > MAX_SHARED_RMS) {
                skip(out, log, pk, m.ok ? String.format(Locale.US, "its stars didn't line up well enough with the first session's "
                        + "(%d matched, %.2f px)", m.nInliers, m.rms) : "its stars didn't line up with the first session's (" + m.note + ")");
                continue;
            }
            pk.fromBase = m.R;
            pk.matched = m.nInliers;
            pk.rms = m.rms;
            pk.solutionOffsetPx = Rot.angle(Rot.mul(m.R, Rot.transpose(guess))) * model.fx;
            // 3) its starlight against the base's
            if (rawB == null) {
                rawB = new short[W * H];
                pb.fs.read(pb.ref.rec.index, rawB);
            }
            int[] nph = new int[1];
            pk.signal = signal(spec, model, pb, refStars, rawB, pr, m.R, nph);
            pk.photometryStars = nph[0];
            // 4) its frames as plans of the merged stack
            double dt = base.startMs > 0 && s.startMs > 0 ? (s.startMs - base.startMs) / 1000.0 : k * 1e5;
            Map<Integer, PostProcessor.Unit> byIndex = new HashMap<Integer, PostProcessor.Unit>();
            for (PostProcessor.Unit u : pr.units) byIndex.put(u.rec.index, u);
            for (SharpStacker.Plan q : pr.plans) {
                SharpStacker.Plan c = new SharpStacker.Plan();
                c.unit = q.unit;
                c.R = Rot.mul(q.R, m.R);
                c.weight = q.weight;
                c.invScale = q.invScale;
                System.arraycopy(q.black, 0, c.black, 0, 4);
                c.t = q.t + dt;
                c.background = q.background;
                c.transparency = q.transparency;
                c.frames = pr.fs;
                c.hot = pr.hot;
                c.signal = (float) pk.signal;
                plans.add(c);
                planUnits.add(byIndex.get(q.unit));
                planSession.add(k);
            }
            preps.set(k, pr);
            pk.framesUsed = pr.plans.size();
            pk.seconds = pr.integ;
            pk.outline = outline(model, Rot.transpose(m.R), W, H);
            decide(out, log, String.format(Locale.US, "merge: %s placed from its plate solution (%.1f degrees turned, centres %.1f "
                            + "degrees apart), then on %d shared stars (%.2f px; the solution was %.1f px off); its starlight %.2f times "
                            + "the first session's%s; %d of %d frames", pk.label, apart, centres, pk.matched, pk.rms, pk.solutionOffsetPx,
                    pk.signal, pk.photometryStars > 0 ? " (" + pk.photometryStars + " stars measured)" : " (too few stars to measure, so taken as equal)",
                    pk.framesUsed, pk.framesTotal));
        }
        int merged = 0;
        for (Part p : res.parts) if (p.skipped == null) merged++;
        if (merged < 2) {
            StringBuilder why = new StringBuilder();
            for (Part p : res.parts) if (p.skipped != null) why.append(why.length() > 0 ? "; " : "").append(p.label).append(": ").append(p.skipped);
            throw new IOException("Nothing could be merged into the first session" + (why.length() > 0 ? " (" + why + ")" : ""));
        }

        // 5) one stack of every frame: the base's sky background, each frame's starlight scaled to the base's
        SharpStacker st = new SharpStacker(spec, model, pb.fs, pb.hot);
        st.kHigh = clipHigh;
        st.kLow = clipLow;
        long freeMem = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        st.memoryBudget = stackMemory > 0 ? stackMemory : Math.max(16L << 20, Math.min(64L << 20, (freeMem - (160L << 20)) / 2));
        st.method = rejection;
        st.target = pb.target;
        st.minTransparency = PostProcessor.MIN_REGION_T;
        st.label = "Stacking " + merged + " sessions";
        Rejection.Method used = st.method != Rejection.Method.SIGMA && !st.keepsSamples(plans.size()) ? Rejection.Method.SIGMA
                : st.method == Rejection.Method.AUTO ? Rejection.forFrames(plans.size()) : st.method;
        out.rejection = used.label;
        decide(out, log, String.format(Locale.US, "merge: one stack of %d frames from %d sessions with %s; every frame's sky "
                + "background swapped for the first session's and weighed by its noise", plans.size(), merged, used.label));
        // the noise gain, in frames like the base's best
        double[] pw = new double[plans.size()];
        int single = -1;
        double best = -1;
        for (int i = 0; i < plans.size(); i++) {
            PostProcessor.Unit u = planUnits.get(i);
            pw[i] = plans.get(i).weight * (u != null ? u.usedFraction : 1f);
            if (planSession.get(i) != 0 || u == null || u.bg == null || (u.regionsUsed >= 0 && u.regionsUsed < PostProcessor.REGIONS)) continue;
            double t = Float.isNaN(u.transparency) ? 1 : Math.min(1, u.transparency);
            double sig = u.bg.medianSigma(1);
            double snr = sig > 0 ? t * u.rec.exposure / sig : t;
            if (snr > best) {
                best = snr;
                single = i;
            }
        }
        if (single < 0) single = 0;
        st.noise = new NoiseProbe(W, H, single, pw);
        st.noise.mask(NoiseProbe.starMask(refStars, W, H));
        SharpStacker.Result sr = st.stack(plans, part(progress, 0.5f, 0.5f));
        NoiseProbe.Result nr = st.noise.result();
        st.noise = null;
        out.noise = nr;
        out.noiseUnit = plans.get(single).unit;

        // 6) the reports, the picture's analysis and its colour
        long rej = 0, smp = 0;
        Map<String, Integer> planOf = new HashMap<String, Integer>();
        for (int i = 0; i < plans.size(); i++) {
            rej += sr.rejected[i];
            smp += sr.samples[i];
            planOf.put(planSession.get(i) + ":" + plans.get(i).unit, i);
        }
        log.log(String.format(Locale.US, "merge: stacked %d units in bands of %d rows, rejected %.3f%% of samples, crop %d,%d-%d,%d",
                plans.size(), sr.bandRows, smp > 0 ? 100.0 * rej / (3.0 * smp) : 0, sr.image.x0, sr.image.y0, sr.image.x1, sr.image.y1));
        if (!Double.isNaN(nr.nEff)) {
            decide(out, log, String.format(Locale.US, "merge: worth %.1f frames like the first session's best (%d stacked)", nr.nEff,
                    plans.size()));
        }
        int total = 0;
        double seconds = 0;
        Set<String> nights = new HashSet<String>();
        int unknownNights = 0;
        for (int k = 0; k < ns; k++) {
            Part p = res.parts.get(k);
            PostProcessor.Prep pr = preps.get(k);
            if (p.skipped != null || pr == null) continue;
            total += p.framesTotal;
            seconds += p.seconds;
            if (p.startMs > 0) nights.add(NightPlan.nightOf(p.startMs, TimeZone.getDefault()));
            else unknownNights++;
            double[] toBase = p.fromBase;
            for (PostProcessor.Unit u : pr.units) {
                PostProcessor.FrameReport fr = PostProcessor.frameReport(u, sr, planOf.get(k + ":" + u.rec.index));
                fr.session = p.id;
                if (k > 0) {
                    if (fr.R != null) fr.R = Rot.mul(fr.R, toBase);
                    if (fr.measuredR != null) fr.measuredR = Rot.mul(fr.measuredR, toBase);
                }
                out.frames.add(fr);
            }
        }
        float[] hh = new float[out.frames.size()];
        int nhh = 0;
        for (PostProcessor.FrameReport fr : out.frames) if (fr.used && fr.hfr > 0) hh[nhh++] = (float) fr.hfr;
        if (nhh > 0) out.medianHfr = Stats.median(hh, nhh);
        out.unitsTotal = total;
        out.unitsUsed = plans.size();
        out.integrationSeconds = seconds;
        out.aligned = true;
        out.model = model;
        out.hot = pb.hot;
        out.maxGain = pb.out.maxGain;
        out.referenceUnit = pb.ref.rec.index;
        out.referenceStars = refStars;
        out.solution = pb.out.solution;
        out.solutionJd = pb.out.solutionJd;
        out.image = sr.image;
        out.analysis = Renderer.analyze(sr.image, spec);
        if (colorCalibration) bp.calibrateColour(out);
        res.sessions = merged;
        res.nights = nights.size() + unknownNights;
        int minutes = (int) Math.round(seconds / 60);
        res.total = res.nights >= 2 ? String.format(Locale.US, "%d minute%s over %d nights", minutes, minutes == 1 ? "" : "s", res.nights)
                : String.format(Locale.US, "%d minute%s in %d sessions", minutes, minutes == 1 ? "" : "s", merged);
        out.summary = String.format(Locale.US, "Merged %d sessions: used %d of %d frames (%s, %s).", merged, plans.size(), total,
                PostProcessor.duration(seconds), res.total);
        coverage(res, model, W, H);
        log.log(out.summary + " " + out.analysis.describe());
        return res;
    }

    /** A session's processor set up for its part of a merge: no colour, streak or pattern passes of its own. */
    private PostProcessor processor(Session s, CameraModel fixed) {
        PostProcessor pp = new PostProcessor(s.store, s.spec);
        pp.sky = s.sky;
        pp.include = s.include;
        pp.includeNote = s.includeNote;
        pp.fixedModel = fixed;
        pp.colorCalibration = colorCalibration;
        pp.patternCleanup = false;
        pp.streakCorridors = false;
        pp.blend = false;
        pp.clipHigh = clipHigh;
        pp.clipLow = clipLow;
        return pp;
    }

    /**
     * Another session's starlight at full transparency relative to the base's: aperture photometry of the base
     * reference frame's stars in both reference frames (where the shared stars put them), the flux-weighted median
     * ratio over the brighter ones, with each reference frame's own transparency taken out. 1 with too few stars.
     */
    private static double signal(SensorSpec spec, CameraModel model, PostProcessor.Prep pb, StarList stars, short[] rawB,
                                 PostProcessor.Prep pk, double[] fromBase, int[] used) throws IOException {
        used[0] = 0;
        if (stars == null || stars.count < MIN_PHOT_STARS) return 1;
        int W = spec.width, H = spec.height;
        short[] rawK = new short[W * H];
        pk.fs.read(pk.ref.rec.index, rawK);
        float[] xb = new float[stars.count], yb = new float[stars.count], xk = new float[stars.count], yk = new float[stars.count];
        double[] b = new double[3], r = new double[3], p = new double[2];
        int n = 0;
        for (int i = 0; i < stars.count; i++) {
            if (stars.saturated[i]) continue;
            model.unproject(stars.x[i], stars.y[i], b);
            Rot.apply(fromBase, b[0], b[1], b[2], r);
            if (!model.project(r[0], r[1], r[2], p)) continue;
            xb[n] = stars.x[i];
            yb[n] = stars.y[i];
            xk[n] = (float) p[0];
            yk[n] = (float) p[1];
            n++;
        }
        if (n < MIN_PHOT_STARS) return 1;
        // an aperture wide enough for either night's star size, so a softer night doesn't lose light outside it
        float hb = pb.ref.stars != null ? pb.ref.stars.medianHfr(80) : 1.5f, hk = pk.ref.stars != null ? pk.ref.stars.medianHfr(80) : 1.5f;
        Photometry ph = new Photometry(W, H);
        ph.rAp = Stats.clamp(3f * Math.max(hb, hk), 3.5f, 9f);
        ph.rIn = ph.rAp + 3;
        ph.rOut = ph.rAp + 7;
        UnitRecord ub = pb.ref.rec, uk = pk.ref.rec;
        float satB = 0.92f * (spec.white - Math.max(Math.max(ub.black[0], ub.black[1]), Math.max(ub.black[2], ub.black[3])));
        float satK = 0.92f * (spec.white - Math.max(Math.max(uk.black[0], uk.black[1]), Math.max(uk.black[2], uk.black[3])));
        float[] fb = ph.measure(rawB, ub.invScale, ub.black, xb, yb, n, pb.hot, satB);
        float[] fk = ph.measure(rawK, uk.invScale, uk.black, xk, yk, n, pk.hot, satK);
        float[] tmp = new float[n];
        int m = 0;
        for (int i = 0; i < n; i++) if (!Float.isNaN(fb[i]) && fb[i] > 0 && !Float.isNaN(fk[i])) tmp[m++] = fb[i];
        if (m < MIN_PHOT_STARS) return 1;
        float minFlux = Stats.quantile(tmp, m, 0.3), capFlux = Stats.quantile(tmp, m, 0.95);
        float[] ratio = new float[n], w = new float[n];
        int q = 0;
        for (int i = 0; i < n; i++) {
            if (Float.isNaN(fb[i]) || Float.isNaN(fk[i]) || fb[i] < minFlux) continue;
            ratio[q] = fk[i] / fb[i];
            w[q++] = Math.min(fb[i], capFlux);
        }
        if (q < MIN_PHOT_STARS) return 1;
        double rho = Stats.weightedMedian(ratio, w, q, new long[q]);
        used[0] = q;
        double tb = Float.isNaN(pb.ref.transparency) || pb.ref.transparency <= 0 ? 1 : pb.ref.transparency;
        double tk = Float.isNaN(pk.ref.transparency) || pk.ref.transparency <= 0 ? 1 : pk.ref.transparency;
        return Math.max(0.02, rho * tb / tk);
    }

    /** A frame's outline mapped into the base's pixels by {@code toBase} (its bearings to the base's): x, y pairs. */
    static float[] outline(CameraModel model, double[] toBase, int W, int H) {
        final int per = 8;
        float[] o = new float[2 * 4 * per];
        double[] b = new double[3], r = new double[3], p = new double[2];
        int n = 0;
        for (int side = 0; side < 4; side++) {
            for (int i = 0; i < per; i++) {
                double f = i / (double) per, x, y;
                if (side == 0) {
                    x = f * W;
                    y = 0;
                } else if (side == 1) {
                    x = W;
                    y = f * H;
                } else if (side == 2) {
                    x = (1 - f) * W;
                    y = H;
                } else {
                    x = 0;
                    y = (1 - f) * H;
                }
                model.unproject(x, y, b);
                Rot.apply(toBase, b[0], b[1], b[2], r);
                if (!model.project(r[0], r[1], r[2], p)) continue;
                o[n++] = (float) p[0];
                o[n++] = (float) p[1];
            }
        }
        return java.util.Arrays.copyOf(o, n);
    }

    /** How many of the merged sessions cover each cell of the base frame (by their reference frames). */
    private static void coverage(Result res, CameraModel model, int W, int H) {
        int cw = (W + COVER_STEP - 1) / COVER_STEP, ch = (H + COVER_STEP - 1) / COVER_STEP;
        byte[] c = new byte[cw * ch];
        double[] b = new double[3], r = new double[3], p = new double[2];
        for (int j = 0; j < ch; j++) {
            for (int i = 0; i < cw; i++) {
                model.unproject(Math.min(W - 1, (i + 0.5) * COVER_STEP), Math.min(H - 1, (j + 0.5) * COVER_STEP), b);
                int n = 0;
                for (Part pt : res.parts) {
                    if (pt.skipped != null || pt.fromBase == null) continue;
                    Rot.apply(pt.fromBase, b[0], b[1], b[2], r);
                    if (model.project(r[0], r[1], r[2], p) && p[0] >= 0 && p[1] >= 0 && p[0] < W && p[1] < H) n++;
                }
                c[j * cw + i] = (byte) Math.min(127, n);
            }
        }
        res.coverage = c;
        res.coverW = cw;
        res.coverH = ch;
    }

    private static void skip(PostProcessor.Output out, SessionStore log, Part p, String why) {
        p.skipped = why;
        decide(out, log, "merge: " + p.label + " left out: " + why);
    }

    private static void decide(PostProcessor.Output out, SessionStore log, String s) {
        out.decisions.add(s);
        log.log("decision: " + s);
    }

    /** Progress over [from, from + share) of the whole. */
    private static Progress part(final Progress p, final float from, final float share) {
        return new Progress() {
            @Override
            public void update(String stage, float fraction) {
                p.update(stage, Math.min(1f, from + share * Stats.clamp(fraction, 0f, 1f)));
            }

            @Override
            public boolean cancelled() {
                return p.cancelled();
            }
        };
    }

    /**
     * Whether two sessions are close enough to merge: centres (J2000 unit vectors) within {@link #MAX_CENTRE_DEG}
     * and places within {@link #MAX_SITE_KM} (NaN places count as close: a place from the stars may be missing).
     */
    public static boolean close(double[] centreA, double latA, double lonA, double[] centreB, double latB, double lonB) {
        if (centreA == null || centreB == null) return false;
        double c = centreA[0] * centreB[0] + centreA[1] * centreB[1] + centreA[2] * centreB[2];
        if (Math.toDegrees(Math.acos(Stats.clamp(c, -1, 1))) > MAX_CENTRE_DEG) return false;
        if (Double.isNaN(latA) || Double.isNaN(latB) || Double.isNaN(lonA) || Double.isNaN(lonB)) return true;
        return distanceKm(latA, lonA, latB, lonB) <= MAX_SITE_KM;
    }

    /** Great-circle distance (km) between two places (degrees). */
    public static double distanceKm(double latA, double lonA, double latB, double lonB) {
        double p1 = Math.toRadians(latA), p2 = Math.toRadians(latB), dl = Math.toRadians(lonB - lonA);
        double a = Math.sin((p2 - p1) / 2), o = Math.sin(dl / 2);
        double h = a * a + Math.cos(p1) * Math.cos(p2) * o * o;
        return 2 * 6371.0 * Math.asin(Math.min(1, Math.sqrt(h)));
    }
}
