package com.brandon.nightsky.processing;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Live per-frame work during capture, on its own thread so the camera never waits: groups frames into
 * units, stores each unit to disk at once (crash safety), keeps the sensor-fixed sum for the hot-pixel map,
 * finds stars, tracks the sky rotation and feeds the live preview.
 */
public final class UnitProcessor {
    public interface Listener {
        void onUnit(UnitRecord rec, Aligner.Result live, int unitsDone);

        void onError(Throwable t);
    }

    public static final class FrameInfo {
        public long timestampNs, exposureNs;
        public int iso;
        public final float[] black = new float[4];
        public float gyroMax;
        public boolean disturbed;
        public float focus;
    }

    private final SessionStore store;
    private final SensorSpec spec;
    private final FrameStore frames;
    private final StarDetector detector;
    private final Aligner aligner;
    private final PreviewAccumulator preview;
    private final float[] sensorSum;
    private final int W, H, framesPerUnit;
    private final ExecutorService exec;
    private final BlockingQueue<short[]> pool;
    private final Listener listener;

    // unit being assembled (exec thread only)
    private short[] unitBuf;
    private int inUnit;
    private UnitRecord cur;
    private double tSum;
    private int nextIndex;
    private int sumCount;
    private long t0Ns = Long.MIN_VALUE;
    private volatile boolean failed;

    public UnitProcessor(SessionStore store, SensorSpec spec, int framesPerUnit, int poolSize, Listener listener) {
        this.store = store;
        this.spec = spec;
        this.listener = listener;
        W = spec.width;
        H = spec.height;
        int kMax = Math.max(1, (int) (65535f / Math.max(1f, spec.white)));
        this.framesPerUnit = Math.max(1, Math.min(framesPerUnit, kMax));
        frames = store.frames(W, H);
        detector = new StarDetector(spec);
        CameraModel model = spec.model != null ? spec.model : CameraModel.nominal(W, H, W / (2 * Math.tan(Math.toRadians(35))));
        aligner = new Aligner(model, W, H);
        preview = new PreviewAccumulator(spec, model);
        sensorSum = new float[W * H];
        pool = new ArrayBlockingQueue<short[]>(poolSize);
        for (int i = 0; i < poolSize; i++) pool.add(new short[W * H]);
        exec = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nightsky-units");
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        });
    }

    public int framesPerUnit() {
        return framesPerUnit;
    }

    public PreviewAccumulator preview() {
        return preview;
    }

    public boolean failed() {
        return failed;
    }

    /** A free frame buffer, waiting up to {@code waitMs}; null if processing has fallen too far behind. */
    public short[] obtainBuffer(long waitMs) {
        try {
            return pool.poll(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    public void releaseBuffer(short[] b) {
        pool.offer(b);
    }

    /** Hands a filled buffer (from {@link #obtainBuffer}) over; it is returned to the pool when done. */
    public void submit(final short[] raw, final FrameInfo info) {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!failed) addFrame(raw, info);
                } catch (Throwable t) {
                    failed = true;
                    store.log("unit processing failed: " + t);
                    if (listener != null) listener.onError(t);
                } finally {
                    if (raw != unitBuf) pool.offer(raw);
                }
            }
        });
    }

    private void addFrame(short[] raw, FrameInfo info) throws IOException {
        long midNs = info.timestampNs + info.exposureNs / 2;
        if (t0Ns == Long.MIN_VALUE) t0Ns = info.timestampNs;
        double t = (midNs - t0Ns) * 1e-9;
        if (framesPerUnit == 1) {
            UnitRecord r = newRecord(info, t);
            r.frames = 1;
            processUnit(raw, r);
            return;
        }
        if (info.disturbed) {
            // keep a disturbed frame out of a clean unit
            flushUnit();
            UnitRecord r = newRecord(info, t);
            processUnit(raw, r);
            return;
        }
        if (unitBuf == null) unitBuf = new short[W * H];
        if (inUnit == 0) {
            cur = newRecord(info, t);
            cur.exposure = 0;
            tSum = 0;
            java.util.Arrays.fill(cur.black, 0f);
            System.arraycopy(raw, 0, unitBuf, 0, W * H);
        } else {
            final short[] u = unitBuf;
            final short[] f = raw;
            Parallel.forRange(H, 32, new Parallel.Range() {
                @Override
                public void run(int y0, int y1) {
                    for (int i = y0 * W, e = y1 * W; i < e; i++) u[i] = (short) ((u[i] & 0xFFFF) + (f[i] & 0xFFFF));
                }
            });
            cur.gyroMax = Math.max(cur.gyroMax, info.gyroMax);
        }
        inUnit++;
        tSum += t;
        cur.exposure += info.exposureNs * 1e-9;
        for (int p = 0; p < 4; p++) cur.black[p] += info.black[p];
        if (inUnit >= framesPerUnit) flushUnit();
    }

    private UnitRecord newRecord(FrameInfo info, double t) {
        UnitRecord r = new UnitRecord();
        r.t = t;
        r.exposure = info.exposureNs * 1e-9;
        r.iso = info.iso;
        r.invScale = 1f;
        System.arraycopy(info.black, 0, r.black, 0, 4);
        r.gyroMax = info.gyroMax;
        r.disturbed = info.disturbed;
        r.focus = info.focus;
        r.sensorTimestampNs = info.timestampNs;
        return r;
    }

    private void flushUnit() throws IOException {
        if (inUnit == 0) return;
        cur.frames = inUnit;
        cur.invScale = 1f / inUnit;
        cur.t = tSum / inUnit;
        for (int p = 0; p < 4; p++) cur.black[p] /= inUnit;
        UnitRecord r = cur;
        inUnit = 0;
        cur = null;
        processUnit(unitBuf, r);
    }

    private void processUnit(final short[] raw, final UnitRecord r) throws IOException {
        r.index = nextIndex++;
        frames.write(r.index, raw);
        final float sc = r.invScale;
        final float[] bl = r.black;
        Parallel.forRange(H, 32, new Parallel.Range() {
            @Override
            public void run(int y0, int y1) {
                for (int y = y0; y < y1; y++) {
                    int o = y * W;
                    int py = (y & 1) << 1;
                    float be = bl[py], bo = bl[py | 1];
                    for (int x = 0; x < W; x += 2) {
                        sensorSum[o + x] += (raw[o + x] & 0xFFFF) * sc - be;
                        sensorSum[o + x + 1] += (raw[o + x + 1] & 0xFFFF) * sc - bo;
                    }
                }
            }
        });
        sumCount++;
        StarList stars = detector.detect(raw, sc, 300);
        store.writeStars(r.index, stars);
        Aligner.Result live = aligner.process(r.index, stars, r.t);
        if (live.ok && !r.disturbed) preview.add(raw, sc, bl, live.R);
        r.stars = stars.count;
        r.liveOk = live.ok;
        r.rms = (float) live.rms;
        r.hfr = live.medianHfr;
        r.background = stars.background;
        r.noise = stars.noise;
        store.appendUnit(r);
        store.log(String.format(java.util.Locale.US,
                "unit %d t=%.1fs exp=%.2fs x%d iso=%d stars=%d %s rms=%.2f hfr=%.2f bg=%.1f noise=%.2f gyro=%.4f%s %s",
                r.index, r.t, r.exposure, r.frames, r.iso, stars.count, live.ok ? "aligned" : "unaligned", live.rms,
                live.medianHfr, stars.background, stars.noise, r.gyroMax, r.disturbed ? " DISTURBED" : "", live.note));
        if (listener != null) listener.onUnit(r, live, nextIndex);
    }

    /** Stores any partial unit and the sensor-fixed sum, then stops the worker thread. */
    public void finish() {
        exec.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!failed) {
                        flushUnit();
                        if (sumCount > 0) store.writeSum(sensorSum, sumCount);
                    }
                } catch (Throwable t) {
                    failed = true;
                    store.log("finishing units failed: " + t);
                    if (listener != null) listener.onError(t);
                }
            }
        });
        exec.shutdown();
        try {
            exec.awaitTermination(2, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops without storing a partial unit. */
    public void abort() {
        failed = true;
        exec.shutdownNow();
    }

    public int unitsStored() {
        return nextIndex;
    }
}
