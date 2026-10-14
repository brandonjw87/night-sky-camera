package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Everything after capture: hot-pixel map, star alignment against a clear frame from the middle of the
 * session, lens-model refinement, recovery of frames the tracker lost (predicted sky rotation, confirmed by
 * photometry), frame grading (clouds, bumps, blur) with per-region weights for partly cloudy frames, and
 * the final stack. Works from what is on disk, so it can also finish a session after a crash.
 */
public final class PostProcessor {
    public static final class Output {
        public RgbImage image;
        public Renderer.Analysis analysis;
        public CameraModel model;
        public int unitsTotal, unitsUsed;
        public double integrationSeconds;
        public boolean aligned;
        public float maxGain = 1f;
        /** One short line for the user, e.g. "Used 35 of 37 frames (4 min 40 s). Skipped: 2 cloudy." */
        public String summary = "";
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
    }

    private final SessionStore store;
    private final SensorSpec spec;
    private final int W, H;

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
        boolean predicted;
        float transparency = Float.NaN;
        float[] cellT;
        boolean used;
        String reason = "";
        float weight = 1f;
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

        // 2) star lists and the reference frame
        for (Unit u : units) u.stars = store.readStars(u.rec.index);
        Unit ref = chooseReference(units);
        Map<Integer, Unit> byIndex = new HashMap<Integer, Unit>();
        for (Unit u : units) byIndex.put(u.rec.index, u);

        if (ref != null) {
            out.referenceUnit = ref.rec.index;
            store.log("reference unit " + ref.rec.index + " at t=" + Math.round(ref.rec.t) + "s with " + ref.stars.count + " stars");
            progress.update("Matching stars", 0f);
            Aligner al = align(model, ref, units);
            ModelRefiner.Outcome oc = ModelRefiner.refine(model, al.reference(), al.results(), ref.rec.index);
            store.log(oc.note);
            if (oc.changed) {
                model = oc.model;
                al = align(model, ref, units);
            }
            out.model = model;
            for (Aligner.Result r : al.results()) {
                Unit u = byIndex.get(r.unit);
                if (u == null) continue;
                u.res = r;
                if (r.ok) u.R = r.R;
            }
            fillPredicted(units, model);
            progress.update("Matching stars", 1f);
            measureTransparency(units, ref, al.reference(), model, fs, hot, progress);
            grade(units);
        }

        List<SharpStacker.Plan> plans = new ArrayList<SharpStacker.Plan>();
        double integ = 0;
        int cloudy = 0, moved = 0, blurred = 0, lost = 0;
        for (Unit u : units) {
            if (u.used) {
                SharpStacker.Plan p = new SharpStacker.Plan();
                p.unit = u.rec.index;
                p.R = u.R;
                p.weight = u.weight;
                p.invScale = u.rec.invScale;
                System.arraycopy(u.rec.black, 0, p.black, 0, 4);
                p.local = localWeights(u);
                plans.add(p);
                integ += u.rec.exposure;
            } else if (u.reason.startsWith("moved")) moved++;
            else if (u.reason.startsWith("cloud")) cloudy++;
            else if (u.reason.startsWith("blur")) blurred++;
            else lost++;
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
            out.frames.add(fr);
            store.log(String.format(Locale.US, "unit %d: %s w=%.2f T=%s stars=%d rms=%s %s", u.rec.index,
                    u.used ? "USED" : "skipped", u.weight,
                    Float.isNaN(u.transparency) ? "-" : String.format(Locale.US, "%.2f", u.transparency),
                    u.stars == null ? 0 : u.stars.count,
                    u.res != null && u.res.ok ? String.format(Locale.US, "%.2f", u.res.rms) : "-",
                    u.reason + (u.predicted ? " (predicted)" : "")));
        }
        out.aligned = !plans.isEmpty();
        if (plans.isEmpty()) {
            // Nothing could be aligned (no stars: cloud cover, lens covered, too short): plain average instead.
            store.log("no aligned frames, falling back to an unaligned average");
            for (Unit u : units) {
                if (u.rec.disturbed && units.size() > 1) continue;
                SharpStacker.Plan p = new SharpStacker.Plan();
                p.unit = u.rec.index;
                p.R = Rot.identity();
                p.invScale = u.rec.invScale;
                System.arraycopy(u.rec.black, 0, p.black, 0, 4);
                plans.add(p);
                integ += u.rec.exposure;
            }
            if (plans.isEmpty()) {
                Unit u = units.get(0);
                SharpStacker.Plan p = new SharpStacker.Plan();
                p.unit = u.rec.index;
                p.R = Rot.identity();
                p.invScale = u.rec.invScale;
                System.arraycopy(u.rec.black, 0, p.black, 0, 4);
                plans.add(p);
                integ += u.rec.exposure;
            }
        }
        out.unitsUsed = plans.size();
        out.integrationSeconds = integ;

        SharpStacker st = new SharpStacker(spec, model, fs, hot);
        long freeMem = Runtime.getRuntime().maxMemory() - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
        st.memoryBudget = Math.max(16L << 20, Math.min(64L << 20, (freeMem - (160L << 20)) / 2));
        SharpStacker.Result sr = st.stack(plans, progress);
        long rej = 0, smp = 0;
        for (int i = 0; i < plans.size(); i++) {
            rej += sr.rejected[i];
            smp += sr.samples[i];
        }
        store.log(String.format(Locale.US, "stacked %d units, rejected %.3f%% of samples, crop %d,%d-%d,%d",
                plans.size(), smp > 0 ? 100.0 * rej / (3.0 * smp) : 0, sr.image.x0, sr.image.y0, sr.image.x1, sr.image.y1));
        out.image = sr.image;
        out.analysis = Renderer.analyze(sr.image, spec);
        StringBuilder sb = new StringBuilder();
        if (out.aligned) {
            sb.append(String.format(Locale.US, "Used %d of %d frames (%s).", plans.size(), units.size(), duration(integ)));
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

    private Aligner align(CameraModel model, Unit ref, List<Unit> units) {
        Aligner al = new Aligner(model, W, H);
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

    /**
     * Frames the tracker lost (thin cloud, few stars) but where the phone didn't move: take the rotation from
     * neighbouring good frames and the sky's steady rotation rate. Photometry later confirms or rejects them.
     */
    private void fillPredicted(List<Unit> units, CameraModel model) {
        List<Unit> ok = new ArrayList<Unit>();
        for (Unit u : units) if (u.R != null) ok.add(u);
        if (ok.size() < 3) return;
        // robust rotation rate from consecutive good pairs
        List<double[]> rates = new ArrayList<double[]>();
        for (int i = 0; i + 1 < ok.size(); i++) {
            Unit a = ok.get(i), b = ok.get(i + 1);
            double dt = b.rec.t - a.rec.t;
            if (dt <= 0) continue;
            double[] rv = Rot.toRotVec(Rot.mul(b.R, Rot.transpose(a.R)));
            rates.add(new double[]{rv[0] / dt, rv[1] / dt, rv[2] / dt});
        }
        if (rates.size() < 2) return;
        double[] w = new double[3];
        double[] tmp = new double[rates.size()];
        for (int c = 0; c < 3; c++) {
            for (int i = 0; i < rates.size(); i++) tmp[i] = rates.get(i)[c];
            w[c] = Stats.median(tmp.clone(), tmp.length);
        }
        double fpx = model.fx;
        for (Unit u : units) {
            if (u.R != null || u.rec.disturbed) continue;
            Unit prev = null, next = null;
            for (Unit o : ok) {
                if (o.rec.t < u.rec.t) prev = o;
                else if (next == null && o.rec.t > u.rec.t) next = o;
            }
            double[] R = null;
            if (prev != null && next != null) {
                double dt = next.rec.t - prev.rec.t;
                double[] predNext = Rot.mul(Rot.fromRotVec(w[0] * dt, w[1] * dt, w[2] * dt), prev.R);
                double err = Rot.angle(Rot.mul(next.R, Rot.transpose(predNext))) * fpx;
                if (err < 2.0) {
                    double d = u.rec.t - prev.rec.t;
                    R = Rot.mul(Rot.fromRotVec(w[0] * d, w[1] * d, w[2] * d), prev.R);
                }
            } else if (prev != null) {
                double d = u.rec.t - prev.rec.t;
                R = Rot.mul(Rot.fromRotVec(w[0] * d, w[1] * d, w[2] * d), prev.R);
            } else if (next != null) {
                double d = u.rec.t - next.rec.t;
                R = Rot.mul(Rot.fromRotVec(w[0] * d, w[1] * d, w[2] * d), next.R);
            }
            if (R != null) {
                u.R = R;
                u.predicted = true;
            }
        }
    }

    private static final int GX = 4, GY = 3;

    /** Global and per-region transparency of every frame from aperture photometry of the reference stars. */
    private void measureTransparency(List<Unit> units, Unit ref, StarList refStars, CameraModel model, FrameStore fs,
                                     HotPixels hot, Progress progress) throws IOException {
        int n = 0;
        int max = Math.min(refStars.count, 250);
        float[] rx = new float[max], ry = new float[max];
        for (int i = 0; i < refStars.count && n < max; i++) {
            if (refStars.saturated[i]) continue;
            rx[n] = refStars.x[i];
            ry[n] = refStars.y[i];
            n++;
        }
        if (n < 8) return;
        double[] rb = new double[3 * n];
        double[] b = new double[3], p = new double[2];
        for (int i = 0; i < n; i++) {
            model.unproject(rx[i], ry[i], b);
            rb[3 * i] = b[0];
            rb[3 * i + 1] = b[1];
            rb[3 * i + 2] = b[2];
        }
        int[] cell = new int[n];
        LocalWeights grid = new LocalWeights(GX, GY, W, H);
        for (int i = 0; i < n; i++) cell[i] = grid.cellOf(rx[i], ry[i]);
        Photometry ph = new Photometry(W, H);
        short[] raw = new short[W * H];
        float[] xs = new float[n], ys = new float[n];
        Map<Unit, float[]> flux = new HashMap<Unit, float[]>();
        int done = 0, todo = 0;
        for (Unit u : units) if (u.R != null) todo++;
        for (Unit u : units) {
            if (u.R == null) continue;
            fs.read(u.rec.index, raw);
            for (int i = 0; i < n; i++) {
                Rot.apply(u.R, rb[3 * i], rb[3 * i + 1], rb[3 * i + 2], b);
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
            done++;
            if (progress.cancelled()) throw new CancelledException();
            progress.update("Checking for clouds", done / (float) todo);
        }
        float[] fr = flux.get(ref);
        if (fr == null) return;
        // stars bright enough to measure well: upper 70% of the reference fluxes
        float[] tmp = new float[n];
        int m = 0;
        for (int i = 0; i < n; i++) if (!Float.isNaN(fr[i]) && fr[i] > 0) tmp[m++] = fr[i];
        if (m < 8) return;
        float minFlux = Stats.quantile(tmp, m, 0.3);
        float[] ratios = new float[n];
        float[][] cellR = new float[GX * GY][n];
        int[] cellN = new int[GX * GY];
        for (Unit u : units) {
            float[] fu = flux.get(u);
            if (fu == null) continue;
            int k = 0;
            Arrays.fill(cellN, 0);
            for (int i = 0; i < n; i++) {
                if (Float.isNaN(fr[i]) || Float.isNaN(fu[i]) || fr[i] < minFlux) continue;
                float r = fu[i] / fr[i];
                ratios[k++] = r;
                int c = cell[i];
                cellR[c][cellN[c]++] = r;
            }
            if (k < 5) {
                u.transparency = 0f;
                continue;
            }
            u.transparency = Stats.median(ratios, k);
            u.cellT = new float[GX * GY];
            for (int c = 0; c < GX * GY; c++) {
                u.cellT[c] = cellN[c] >= 5 ? Stats.median(cellR[c], cellN[c]) : u.transparency;
            }
        }
        // normalise by the clear-frame level (80th percentile) so a slightly hazy reference doesn't matter
        float[] all = new float[units.size()];
        int na = 0;
        for (Unit u : units) if (!Float.isNaN(u.transparency) && u.cellT != null) all[na++] = u.transparency;
        if (na == 0) return;
        float norm = Math.max(1e-3f, Stats.quantile(all, na, 0.8));
        float[] cn = new float[GX * GY];
        for (int c = 0; c < GX * GY; c++) {
            int q = 0;
            for (Unit u : units) if (u.cellT != null) all[q++] = u.cellT[c];
            cn[c] = q > 0 ? Math.max(1e-3f, Stats.quantile(all, q, 0.8)) : 1f;
        }
        for (Unit u : units) {
            if (Float.isNaN(u.transparency)) continue;
            u.transparency /= norm;
            if (u.cellT != null) for (int c = 0; c < GX * GY; c++) u.cellT[c] /= cn[c];
        }
    }

    private void grade(List<Unit> units) {
        float[] h = new float[units.size()], nz = new float[units.size()];
        int nh = 0, nn = 0;
        for (Unit u : units) {
            if (u.res != null && u.res.ok && u.res.medianHfr > 0) h[nh++] = u.res.medianHfr;
            if (u.stars != null && u.stars.noise > 0) nz[nn++] = u.stars.noise;
        }
        float medH = nh > 0 ? Stats.median(h, nh) : 0f;
        float medN = nn > 0 ? Stats.median(nz, nn) : 1f;
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
            if (!Float.isNaN(u.transparency) && u.transparency < 0.4f) {
                u.reason = String.format(Locale.US, "clouds (%.0f%% light)", 100 * u.transparency);
                continue;
            }
            if (u.predicted && (Float.isNaN(u.transparency) || u.transparency < 0.5f)) {
                u.reason = "could not confirm position";
                continue;
            }
            if (u.res != null && u.res.ok && medH > 0 && u.res.medianHfr > 1.6f * medH) {
                u.reason = String.format(Locale.US, "blurred (%.1f vs %.1f px)", u.res.medianHfr, medH);
                continue;
            }
            if (u.res != null && u.res.ok && u.res.rms > 1.2) {
                u.reason = "poor alignment";
                continue;
            }
            u.used = true;
            float noise = u.stars != null && u.stars.noise > 0 ? u.stars.noise : medN;
            float w = (medN / noise) * (medN / noise);
            u.weight = Stats.clamp(w, 0.25f, 4f);
            u.reason = "";
        }
    }

    private LocalWeights localWeights(Unit u) {
        if (u.cellT == null) return null;
        LocalWeights lw = new LocalWeights(GX, GY, W, H);
        boolean any = false;
        for (int c = 0; c < GX * GY; c++) {
            float t = Stats.clamp(u.cellT[c], 0f, 1.2f);
            float v = t < 0.25f ? 0f : t * t;
            lw.w[c] = v;
            if (Math.abs(v - 1f) > 0.05f) any = true;
        }
        return any ? lw : null;
    }
}
