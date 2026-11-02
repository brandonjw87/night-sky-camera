package com.brandon.nightsky;

import android.graphics.ImageFormat;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.LensShadingMap;
import android.media.Image;
import android.os.SystemClock;
import android.util.Pair;

import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ShadingMap;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.sim.SkySim;

import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowCaptureResult;
import org.robolectric.shadows.ShadowTotalCaptureResult;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * The camera of the simulated phone: exposures of a simulated night sky ({@link SkySim}, the real stars at the time
 * and place) taken one after the other as the sensor would, each delivered on the rig's handler once the fake clock
 * has passed its end: started, then its RAW image and its result (in either order, as Camera2 may). The sky is
 * rendered at the exposure's own time, with the blur of the focus the rig asked for, scaled for its ISO.
 * <p>
 * What can go wrong on the phone can be made to: the session fails to come up, long exposures are cut short, frames
 * fail or lose their image, the camera stops answering, the phone is knocked, clouds pass.
 */
public final class SimCamera {
    private static volatile SimCamera current;

    static SimCamera get() {
        return current;
    }

    static void set(SimCamera c) {
        current = c;
    }

    /**
     * Electrons a raw count at ISO 700 (fewer at higher ISOs). The simulator's stars (in counts) are what a 6.9 mm
     * f/1.68 lens collects through a clear sky (as ClearSky works it out) at about 2.33 electrons a count, so this camera
     * counts them so and says so in its noise profile: its first clear night then reads clear against its specs (sim's
     * ClearSkyTest works the number out). It changes only the light's shot noise, not its level.
     */
    static final double ELECTRONS_PER_DN = 2.33;
    static final float MIN_FOCUS = 10f;
    /** Blur circle per diopter of defocus (px), as FocusSweep.Lens works it out for this lens and sensor. */
    static final double PX_PER_DIOPTER = 5.9;
    final int W = 2040, H = 1536;
    /** Bytes of padding at the end of each row but the last, as a camera's RAW buffer may have. */
    final int pad = 64;

    volatile boolean present = true;
    /** The next session fails to come up with this camera error (once, and as many times again as failOpenAgain). */
    volatile String failOpen;
    volatile int failOpenAgain;
    /** Longer exposures are cut to this (and the result says so). */
    volatile long maxExposureNs = 30_000_000_000L;
    /** The next n exposures fail (no image), or lose their image (result only). */
    volatile int failNext, loseNext;
    /** Every exposure at least this long fails (no image). */
    volatile long failAtLeastNs = Long.MAX_VALUE;
    /** Exposures start but never come back (a stalled camera); a new session or reopening clears it if unstall. */
    volatile boolean stall, stallSticky;
    /**
     * After this many captures in a row the camera stops (later ones never come back) until a new capture session,
     * or, with stallReopen, until it is opened again (0: never).
     */
    volatile int stallAfter;
    volatile boolean stallReopen;
    /** The lens covered (indoors, against a table): no sky, no stars, only the sensor's own dark. */
    volatile boolean covered;
    /** Light reaching the sensor, a room's (electrons per second per pixel at ISO 700). */
    volatile double lightPerS;
    /** The focus (diopters) at which the stars are sharpest. */
    volatile double bestFocus = 0.35;
    /** Clouds over the whole sky (transmission 0..1, 1 clear). */
    volatile double transparency = 1;
    /** The phone was knocked: from now on the frames show the knocked view. */
    volatile boolean bumped;
    /** Instead of the next frame, the camera is lost with this error (another app took it). */
    volatile String errorNext;

    final SkySim sim;
    /** The fake clock (elapsedRealtimeNanos) at the sky's start. */
    final long t0Ns;
    private final double[] baseSky;
    private final double baseDark, basePsf;
    private final ExecutorService render = Executors.newSingleThreadExecutor();
    private final List<Shot> pending = new ArrayList<Shot>();
    private long busyUntilNs, seed = 11;
    final List<String> events = Collections.synchronizedList(new ArrayList<String>());
    volatile int opens, captures, delivered, failedShots, lostShots, aborted;

    private static final class Shot {
        FakeRig rig;
        boolean stuck;
        int seq, iso, gen;
        long startNs, expNs, reqNs;
        float focus;
        float[] black;
        Future<short[]> pixels;
        Runnable started, done;
    }

    SimCamera(BrightStars catalog, long startUnixMs, double lonDeg, double headingDeg) {
        CameraModel truth = CameraModel.nominal(W, H, 1437.5);
        truth.cx = W / 2.0 + 7;
        truth.cy = H / 2.0 - 4.5;
        truth.k1 = -0.045;
        truth.k2 = 0.018;
        truth.source = "truth";
        sim = new SkySim(W, H, truth, 42, catalog, startUnixMs, lonDeg);
        sim.setAttitude(headingDeg, 0, 0);
        sim.colourByBv();
        sim.psfSigma = 0.6;
        baseSky = sim.skyRate.clone();
        baseDark = sim.darkRate;
        basePsf = sim.psfSigma;
        t0Ns = SystemClock.elapsedRealtimeNanos();
    }

    CameraModel truth() {
        return sim.truth;
    }

    /**
     * A meteor across the picture: lighting up {@code t} seconds into the sky at (fx W, fy H), flying at {@code paDeg}
     * in the picture (clockwise from its right edge), {@code degS} degrees a second for {@code dur} seconds, at
     * magnitude {@code mag} at its brightest. Frames are drawn when the app asks for them, up to two exposures before
     * they end, so a meteor added sooner than that misses the picture.
     */
    void meteor(double t, double fx, double fy, double paDeg, double degS, double dur, double mag) {
        double x = fx * W, y = fy * H, pa = Math.toRadians(paDeg);
        double[] a = new double[3], b = new double[3], ja = new double[3], jb = new double[3];
        sim.truth.unproject(x, y, a);
        sim.truth.unproject(x + 10 * Math.cos(pa), y + 10 * Math.sin(pa), b);
        double[] R = sim.cameraToJ2000At(t);
        com.brandon.nightsky.processing.Rot.apply(R, a[0], a[1], a[2], ja);
        com.brandon.nightsky.processing.Rot.apply(R, b[0], b[1], b[2], jb);
        unit(ja);
        unit(jb);
        double d = ja[0] * jb[0] + ja[1] * jb[1] + ja[2] * jb[2];
        double[] dir = {jb[0] - d * ja[0], jb[1] - d * ja[1], jb[2] - d * ja[2]};
        unit(dir);
        sim.meteors.add(new SkySim.Meteor(t, dur, ja, dir, degS, mag, 2));
        note(String.format(Locale.US, "a meteor at %.1f s, from (%.0f, %.0f), %.0f deg/s for %.1f s, magnitude %.1f", t, x, y, degS, dur, mag));
    }

    private static void unit(double[] v) {
        double n = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        for (int i = 0; i < 3; i++) v[i] /= n;
    }

    void note(String s) {
        events.add(String.format(Locale.US, "%8.1f s  %s", (SystemClock.elapsedRealtimeNanos() - t0Ns) / 1e9, s));
    }

    synchronized void opened(FakeRig rig) {
        opens++;
        note("camera opened (" + opens + ")");
        if (!stallSticky) stall = false;
    }

    /** Queues an exposure: it starts once the sensor is free, and comes back after it (fake clock). */
    synchronized void capture(final FakeRig rig, int seq, long reqNs, int iso, float focus, int gen, int nthInSession, int nthSinceOpen) {
        captures++;
        long now = SystemClock.elapsedRealtimeNanos();
        final Shot s = new Shot();
        s.rig = rig;
        s.seq = seq;
        s.reqNs = reqNs;
        s.expNs = Math.min(reqNs, maxExposureNs);
        s.iso = iso;
        s.focus = focus;
        s.gen = gen;
        // the sensor takes one exposure at a time, with a moment for reading out between them
        s.startNs = Math.max(now + 8_000_000L, busyUntilNs + 33_000_000L);
        busyUntilNs = s.startNs + s.expNs;
        final double tSec = (s.startNs - t0Ns) / 1e9, expS = s.expNs / 1e9;
        final double psf = Math.hypot(basePsf, PX_PER_DIOPTER * Math.abs(focus - bestFocus) / 4);
        final double gain = iso / 700.0, clouds = transparency, light = lightPerS;
        final boolean knocked = bumped, dark = covered;
        int nth = stallReopen ? nthSinceOpen : nthInSession;
        s.stuck = stall || (stallAfter > 0 && nth > stallAfter);
        if (stallAfter > 0 && nth == stallAfter + 1) {
            note(String.format(Locale.US, "capture %d: the camera stops, %d in a row %s (as asked)", seq, stallAfter,
                    stallReopen ? "since it was opened" : "in this capture session"));
        }
        s.pixels = render.submit(new Callable<short[]>() {
            @Override
            public short[] call() {
                short[] raw = new short[W * H];
                UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
                sim.frameExp = expS;
                sim.gap = tSec - expS;
                sim.starGain = dark ? 0 : gain;
                for (int c = 0; c < 3; c++) sim.skyRate[c] = (dark ? 0 : baseSky[c] * gain) + light * gain;
                sim.darkRate = baseDark * gain;
                // the shot noise in electrons: fewer of them a count at a higher ISO
                sim.electronsPerDn = ELECTRONS_PER_DN / gain;
                sim.psfSigma = psf;
                if (clouds < 1) {
                    sim.cloudFrom = 1;
                    sim.cloudTo = 1;
                    sim.cloudT = clouds;
                } else {
                    sim.cloudFrom = sim.cloudTo = -1;
                }
                sim.bumpFrame = knocked ? 0 : -1;
                sim.render(1, raw, info, ++seed);
                s.black = info.black.clone();
                return raw;
            }
        });
        s.started = new Runnable() {
            @Override
            public void run() {
                if (live(s)) s.rig.listener.onStarted(s.seq, s.startNs);
            }
        };
        s.done = new Runnable() {
            @Override
            public void run() {
                finish(s);
            }
        };
        rig.handler.postDelayed(s.started, Math.max(0, (s.startNs - now) / 1_000_000L));
        if (!s.stuck) rig.handler.postDelayed(s.done, Math.max(0, (s.startNs + s.expNs - now) / 1_000_000L) + 60);
        pending.add(s);
        if (s.expNs < reqNs) note(String.format(Locale.US, "capture %d: %.2f s asked, %.2f s given", seq, reqNs / 1e9, s.expNs / 1e9));
    }

    private static boolean live(Shot s) {
        return !s.rig.closed && s.rig.generation == s.gen;
    }

    /** Whether an exposure that is due back is still being drawn (the clock waits for it). */
    synchronized boolean overdue(long nowNs) {
        for (Shot s : pending) {
            if (!s.stuck && s.startNs + s.expNs <= nowNs && !s.pixels.isDone()) return true;
        }
        return false;
    }

    private void finish(Shot s) {
        synchronized (this) {
            if (!pending.remove(s)) return;
        }
        if (!live(s)) return;
        short[] px;
        try {
            px = s.pixels.get();
        } catch (Exception e) {
            throw new RuntimeException("rendering failed", e);
        }
        CameraRig.Listener l = s.rig.listener;
        if (errorNext != null) {
            String w = errorNext;
            errorNext = null;
            note("camera lost: " + w + " (as asked)");
            l.onCameraError(w);
            return;
        }
        if (failNext > 0 || s.expNs >= failAtLeastNs) {
            if (failNext > 0) failNext--;
            failedShots++;
            note("capture " + s.seq + " failed (as asked, " + s.expNs / 1e9 + " s)");
            l.onFailed(s.seq, false);
            return;
        }
        TotalCaptureResult r = result(s);
        s.rig.sessionResults++;
        if (loseNext > 0) {
            loseNext--;
            lostShots++;
            note("capture " + s.seq + " lost its image (as asked)");
            l.onBufferLost(s.seq);
            l.onResult(s.seq, r);
            return;
        }
        delivered++;
        FakeImage img = new FakeImage(px, W, H, pad, s.startNs);
        if ((s.seq & 1) == 0) {
            l.onImage(img);
            l.onResult(s.seq, r);
        } else {
            l.onResult(s.seq, r);
            l.onImage(img);
        }
        img.close();
    }

    /** Drops what's queued or running: each one fails (as Camera2's abortCaptures does). */
    void abort(FakeRig rig) {
        final List<Shot> gone = drop(rig);
        aborted += gone.size();
        if (!gone.isEmpty()) note("aborted " + gone.size());
        for (final Shot s : gone) {
            rig.handler.post(new Runnable() {
                @Override
                public void run() {
                    if (live(s)) s.rig.listener.onFailed(s.seq, false);
                }
            });
        }
    }

    /** The session closed or made again: what's queued or running is gone without a word. */
    void dropAll(FakeRig rig) {
        List<Shot> gone = drop(rig);
        if (!gone.isEmpty()) note("dropped " + gone.size() + " with the session");
        if (!stallSticky) stall = false;
    }

    private synchronized List<Shot> drop(FakeRig rig) {
        List<Shot> gone = new ArrayList<Shot>();
        for (Shot s : pending) if (s.rig == rig) gone.add(s);
        pending.removeAll(gone);
        for (Shot s : gone) {
            rig.handler.removeCallbacks(s.started);
            rig.handler.removeCallbacks(s.done);
            s.pixels.cancel(false);
        }
        busyUntilNs = Math.min(busyUntilNs, SystemClock.elapsedRealtimeNanos());
        return gone;
    }

    private TotalCaptureResult result(Shot s) {
        TotalCaptureResult r = ShadowTotalCaptureResult.newTotalCaptureResult();
        ShadowCaptureResult sr = Shadow.extract(r);
        sr.set(CaptureResult.SENSOR_TIMESTAMP, s.startNs);
        sr.set(CaptureResult.SENSOR_EXPOSURE_TIME, s.expNs);
        sr.set(CaptureResult.SENSOR_SENSITIVITY, s.iso);
        sr.set(CaptureResult.SENSOR_FRAME_DURATION, s.expNs + 33_000_000L);
        sr.set(CaptureResult.LENS_FOCUS_DISTANCE, s.focus);
        sr.set(CaptureResult.LENS_STATE, CaptureResult.LENS_STATE_STATIONARY);
        sr.set(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL, s.black);
        sr.set(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL, sim.white);
        // shot and read noise, on the [0, 1] scale Camera2 uses: ELECTRONS_PER_DN electrons a count at ISO 700, 1.6
        // counts of read noise
        double range = sim.white - sim.black, g = s.iso / 700.0;
        @SuppressWarnings("unchecked")
        Pair<Double, Double>[] np = new Pair[4];
        for (int c = 0; c < 4; c++) {
            np[c] = new Pair<Double, Double>(g / (ELECTRONS_PER_DN * range), sim.readNoise * sim.readNoise / (range * range));
        }
        sr.set(CaptureResult.SENSOR_NOISE_PROFILE, np);
        sr.set(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP, shading());
        return r;
    }

    private LensShadingMap shadingMap;

    /** The vignetting's correction, as the camera reports it (red, green on even rows, green on odd rows, blue per point). */
    private synchronized LensShadingMap shading() {
        if (shadingMap != null) return shadingMap;
        ShadingMap m = sim.shadingMap();
        int cols = m.cols, rows = m.rows;
        float[] e = new float[4 * cols * rows];
        for (int p = 0; p < 4; p++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) e[4 * (y * cols + x) + p] = m.gains[(p * rows + y) * cols + x];
            }
        }
        shadingMap = new LensShadingMap(e, rows, cols);
        return shadingMap;
    }

    /** A RAW16 image as an ImageReader hands it over: one plane, little-endian, rows padded but the last. */
    static final class FakeImage extends Image {
        private final int w, h, rowStride;
        private final long ts;
        private final ByteBuffer buf;
        private final Plane plane;

        FakeImage(short[] px, int w, int h, int pad, long ts) {
            this.w = w;
            this.h = h;
            this.ts = ts;
            rowStride = w * 2 + pad;
            buf = ByteBuffer.allocateDirect(rowStride * (h - 1) + w * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (int y = 0; y < h; y++) {
                buf.position(y * rowStride);
                for (int x = 0; x < w; x++) buf.putShort(px[y * w + x]);
            }
            buf.rewind();
            plane = new Plane() {
                @Override
                public int getRowStride() {
                    return rowStride;
                }

                @Override
                public int getPixelStride() {
                    return 2;
                }

                @Override
                public ByteBuffer getBuffer() {
                    return buf;
                }
            };
            mIsImageValid = true;
        }

        @Override
        public int getFormat() {
            return ImageFormat.RAW_SENSOR;
        }

        @Override
        public int getWidth() {
            return w;
        }

        @Override
        public int getHeight() {
            return h;
        }

        @Override
        public long getTimestamp() {
            return ts;
        }

        @Override
        public int getTransform() {
            return 0;
        }

        @Override
        public int getScalingMode() {
            return 0;
        }

        @Override
        public Plane[] getPlanes() {
            if (!mIsImageValid) throw new IllegalStateException("Image is already closed");
            return new Plane[]{plane};
        }

        @Override
        public void close() {
            mIsImageValid = false;
        }
    }
}
