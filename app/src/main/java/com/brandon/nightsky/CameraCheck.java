package com.brandon.nightsky;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Pair;
import android.util.Range;
import android.util.Size;

import com.brandon.nightsky.processing.CameraFacts;
import com.brandon.nightsky.processing.DarkFrames;
import com.brandon.nightsky.processing.SensorSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The camera check: what the night's camera really does, measured in about three minutes with the lens covered (the
 * phone screen-up on a table, indoors, day or night), through the same {@link CameraRig} and requests the nights use.
 * <ol>
 * <li>A quarter-second frame tells whether the lens is covered; it waits a while for it to be.</li>
 * <li>A run of 70 one-second frames, two queued at a time as sessions do, finds whether the camera stops after so
 * many in a row (some Pixels stop after 63) and what gets it going again: a fresh capture session, or opening the
 * camera again. After a stop the run goes on long enough to see whether it stops again after as many. Its frames
 * also give the pause between frames, the black level and the read noise.</li>
 * <li>The night's long exposure, as the planner would choose it here, shows whether it comes out in full (a camera
 * may cut it short, or fail it); in the dark it also shows the hot pixels.</li>
 * </ol>
 * What it finds is saved for the sessions (the exposure the camera gives, how often to renew the capture session)
 * and as {@link CameraFacts}. Camera callbacks and the measuring run on a thread of their own; the listener hears
 * on the main thread.
 */
final class CameraCheck {
    interface Listener {
        /** What it is doing, and how far through it is (main thread). */
        void onState(String headline, String detail, int percent);

        /** The check is over (main thread); {@link CameraFacts#complete} says whether it got through. */
        void onDone(CameraFacts facts);

        /** The camera couldn't be used (main thread). */
        void onError(String text);
    }

    /** The light check's exposure, and the run's. */
    static final long LIGHT_NS = 250_000_000L, RUN_NS = 1_000_000_000L;
    /** Frames in the run, and the most it runs to after a stop (to see a second stop after as many frames). */
    static final int RUN_FRAMES = 70, RUN_MAX = 150;
    /** How long it waits for the lens to be covered before it goes on without the dark measurements (ms). */
    static final long COVER_MS = 45_000;
    /** Stops it tries to get past before giving up on the run. */
    static final int MAX_STOPS = 4;

    private enum Step {OPENING, LIGHT, RUN, LONG, DONE}

    private final Context ctx;
    private final Prefs prefs;
    private final Handler main;
    private final Listener listener;
    private HandlerThread thread;
    private Handler h;
    private volatile boolean closed;

    private String cameraId;
    private CameraCharacteristics cc;
    private Size rawSize;
    private SensorSpecFactory.Geometry geo;
    private SensorSpec spec;
    private CameraRig rig;
    private Step step = Step.OPENING;
    private final CameraFacts f = new CameraFacts();

    /** A request in flight: what was asked, and what has come back. */
    private static final class Req {
        final int seq;
        /** Its place in the run (1 for the first frame asked), 0 outside the run. */
        int index;
        final long expNs, askedMs;
        long startNs = -1;
        TotalCaptureResult result;
        boolean image, failed;

        Req(int seq, long expNs) {
            this.seq = seq;
            this.expNs = expNs;
            askedMs = SystemClock.elapsedRealtime();
        }
    }

    private final Map<Integer, Req> reqs = new HashMap<Integer, Req>();
    private final Map<Long, Integer> tsToSeq = new HashMap<Long, Integer>();
    private int seqCounter, inFlight;
    private long startMs, coverFromMs, lastProgressMs;

    // the run
    private int runTarget = RUN_FRAMES, sinceCure;
    /** What was last tried to get the camera going after a stop ("session", "reopen"), "" if nothing yet. */
    private String trying = "";
    private long prevStartNs = -1, prevExpNs;
    private final List<Double> gaps = new ArrayList<Double>();
    private short[] bufA, bufB, bufLong;
    /** A pair of the run's frames, once the sensor has settled (from the 4th), is in bufA and bufB. */
    private boolean haveA, haveB;
    private DarkFrames.Frame shortDark;
    private double shortLevel = Double.NaN;

    // the long exposure
    private int longTries;
    private long longAskNs;

    CameraCheck(Context c, Prefs prefs, Handler main, Listener l) {
        ctx = c.getApplicationContext();
        this.prefs = prefs;
        this.main = main;
        listener = l;
    }

    /** The camera sessions use (the one checked), or null. */
    String cameraId() {
        return cameraId;
    }

    /** Opens the camera and starts. Null if it is starting, else why it can't. */
    String start() {
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = CameraRig.pickCamera(cm);
            if (cameraId == null) return "This phone has no back camera with RAW support.";
            cc = cm.getCameraCharacteristics(cameraId);
            rawSize = CameraRig.rawSize(cc);
            geo = SensorSpecFactory.geometry(cc, rawSize);
            spec = SensorSpecFactory.build(cc, cameraId, rawSize, geo);
        } catch (CameraAccessException | RuntimeException e) {
            return "The camera can't be read: " + e.getMessage();
        }
        Range<Integer> isoRange = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        Integer analog = cc.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
        int isoMin = isoRange != null ? isoRange.getLower() : 50, isoMax = isoRange != null ? isoRange.getUpper() : 3200;
        int iso = analog != null && analog > 0 ? analog : 1600;
        iso = Math.max(isoMin, Math.min(Math.min(isoMax, 3200), iso));
        Range<Long> expRange = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        long advMax = expRange != null ? expRange.getUpper() : 0;
        // the exposure a night here would plan, as if nothing had been learned yet
        Locator.Place place = Locator.best(ctx, prefs);
        ExposurePlan plan = ExposurePlan.make(spec.model.fx, place != null ? place.latDeg : ExposurePlan.DEFAULT_LATITUDE, 300, advMax,
                0, 0, iso, isoMin, spec.white);
        f.timeMs = System.currentTimeMillis();
        f.version = BuildInfo.VERSION;
        f.phone = Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + " (" + Build.DISPLAY + ")";
        f.camera = describe(iso, isoRange, analog, expRange);
        f.iso = plan.iso;
        f.nightNs = plan.frameExpNs;
        f.runExpNs = RUN_NS;
        for (int p = 0; p < 4; p++) f.blackReported[p] = spec.black[p];
        thread = new HandlerThread("camera-check");
        thread.start();
        h = new Handler(thread.getLooper());
        startMs = SystemClock.elapsedRealtime();
        h.post(new Runnable() {
            @Override
            public void run() {
                state("Opening the camera", "", 1);
                openRig();
                tick();
            }
        });
        return null;
    }

    /** Stops wherever it is and lets the camera go (any thread). */
    void stop() {
        closed = true;
        final Handler hh = h;
        if (hh == null) return;
        hh.post(new Runnable() {
            @Override
            public void run() {
                release();
            }
        });
    }

    private String describe(int iso, Range<Integer> isoRange, Integer analog, Range<Long> expRange) {
        Integer hw = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        Integer ts = cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        Float mf = cc.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        Integer cal = cc.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        return String.format(Locale.US, "camera %s: RAW %dx%d, black %.0f/%.0f/%.0f/%.0f, white %.0f, ISO %s (analog up to %s), "
                        + "exposures %s, hardware level %s, timestamps %s, focus %s, f=%.2f mm f/%.2f",
                cameraId, rawSize.getWidth(), rawSize.getHeight(), spec.black[0], spec.black[1], spec.black[2], spec.black[3], spec.white,
                isoRange == null ? "?" : isoRange.getLower() + "-" + isoRange.getUpper(), analog == null ? "?" : analog.toString(),
                expRange == null ? "?" : String.format(Locale.US, "%.6f-%.1f s", expRange.getLower() / 1e9, expRange.getUpper() / 1e9),
                hw == null ? "?" : hw.toString(), ts == null ? "?" : ts == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME ? "realtime" : "unknown",
                mf == null || mf <= 0 ? "fixed" : String.format(Locale.US, "up to %.1f, %s", mf, cal == null ? "?"
                        : cal == CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED ? "uncalibrated" : "calibrated"),
                spec.focalLengthMm, spec.fNumber);
    }

    // ---- the camera ----

    private void openRig() {
        if (closed) return;
        rig = new CameraRig(ctx, h, rigListener);
        rig.setFocus(0f);
        try {
            rig.open(cameraId, cc, rawSize);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            fail("The camera couldn't be opened (" + e.getMessage() + ").");
        }
    }

    private final CameraRig.Listener rigListener = new CameraRig.Listener() {
        @Override
        public void onReady() {
            if (closed) return;
            lastProgressMs = SystemClock.elapsedRealtime();
            if (step == Step.OPENING) {
                coverFromMs = SystemClock.elapsedRealtime();
                light();
            } else if (step == Step.RUN) {
                queueRun();
            } else if (step == Step.LONG && inFlight == 0) {
                queueLong();
            }
        }

        @Override
        public void onStarted(int seq, long timestampNs) {
            Req r = reqs.get(seq);
            if (r == null) return;
            r.startNs = timestampNs;
            tsToSeq.put(timestampNs, seq);
        }

        @Override
        public void onImage(Image img) {
            Integer seq = tsToSeq.get(img.getTimestamp());
            Req r = seq != null ? reqs.get(seq) : null;
            if (r == null || r.image || r.failed) return;
            r.image = true;
            measure(r, img);
            arrived(r);
        }

        @Override
        public void onResult(int seq, TotalCaptureResult result) {
            Req r = reqs.get(seq);
            if (r == null || r.failed) return;
            r.result = result;
            arrived(r);
        }

        @Override
        public void onFailed(int seq, boolean imageCaptured) {
            Req r = reqs.get(seq);
            if (r == null || r.failed) return;
            r.failed = true;
            done(r);
            if (step == Step.LIGHT) {
                light();
            } else if (step == Step.RUN) {
                f.runFailed++;
                lastProgressMs = SystemClock.elapsedRealtime();
                if (f.runFailed > 10) {
                    endRun();
                } else {
                    queueRun();
                }
            } else if (step == Step.LONG) {
                longCame(r, 0);
            }
        }

        @Override
        public void onBufferLost(int seq) {
            Req r = reqs.get(seq);
            if (r == null || r.failed) return;
            r.image = true;
            arrived(r);
        }

        @Override
        public void onCameraError(String what) {
            if (closed) return;
            if (step == Step.RUN && sinceCure == 0 && "session".equals(trying) && !CameraRig.TAKEN.equals(what)) {
                // a fresh capture session wouldn't set up: open the camera again instead
                trying = "reopen";
                reopen();
            } else if (step == Step.RUN && sinceCure == 0 && "reopen".equals(trying)) {
                // opening it again failed too: the run is over
                f.cure = "none";
                endRun();
            } else {
                fail("The camera stopped (" + what + ").");
            }
        }
    };

    private Req ask(long expNs) {
        int seq = ++seqCounter;
        Req r = new Req(seq, expNs);
        if (rig == null || !rig.capture(seq, expNs, f.iso)) return null;
        reqs.put(seq, r);
        inFlight++;
        return r;
    }

    private void done(Req r) {
        if (reqs.remove(r.seq) != null) inFlight--;
        if (r.startNs >= 0) tsToSeq.remove(r.startNs);
    }

    /** Both the picture (or word that it's lost) and the result are in. */
    private void arrived(Req r) {
        if (!r.image || r.result == null || !reqs.containsKey(r.seq)) return;
        done(r);
        lastProgressMs = SystemClock.elapsedRealtime();
        if (step == Step.LIGHT) {
            lightCame();
        } else if (step == Step.RUN) {
            runCame(r);
        } else if (step == Step.LONG) {
            Long e = r.result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            longCame(r, e != null ? e : r.expNs);
        }
    }

    /** What the picture itself says, while it is still open: copied where the dark is measured. */
    private void measure(Req r, Image img) {
        if (step == Step.LIGHT) {
            short[] b = buffer(0);
            SessionController.copyRaw(img, b);
            DarkFrames.Frame d = DarkFrames.measure(b, rawSize.getWidth(), rawSize.getHeight(), spec.white, mean(spec.black));
            f.lit = DarkFrames.litShare(d, spec.white, mean(spec.black));
        } else if (step == Step.RUN && f.dark && shortDark == null) {
            // two frames once the sensor has settled: the black level, the spread and, from the pair, the read noise
            if (!haveA && r.index >= 4) {
                SessionController.copyRaw(img, buffer(0));
                haveA = true;
            } else if (haveA && !haveB) {
                SessionController.copyRaw(img, buffer(1));
                haveB = true;
            }
        } else if (step == Step.LONG && f.dark && bufLong == null && r.expNs == f.nightNs) {
            bufLong = new short[rawSize.getWidth() * rawSize.getHeight()];
            SessionController.copyRaw(img, bufLong);
        }
    }

    private short[] buffer(int which) {
        int n = rawSize.getWidth() * rawSize.getHeight();
        if (which == 0) {
            if (bufA == null) bufA = new short[n];
            return bufA;
        }
        if (bufB == null) bufB = new short[n];
        return bufB;
    }

    // ---- 1. is the lens covered ----

    private void light() {
        if (closed) return;
        step = Step.LIGHT;
        if (ask(LIGHT_NS) == null) fail("The camera didn't take the first frame.");
    }

    private void lightCame() {
        long waited = SystemClock.elapsedRealtime() - coverFromMs;
        if (!Double.isNaN(f.lit) && f.lit <= DarkFrames.LIT_SHARE) {
            f.dark = true;
            startRun();
        } else if (waited < COVER_MS) {
            state("Cover the camera", String.format(Locale.US, "Lay the phone screen-up on a table so the camera is against it, in "
                    + "a dim room (or put it in a drawer). Light now: %.0f%% of full; going on without the dark part in %d s.",
                    100 * (Double.isNaN(f.lit) ? 1 : f.lit), (COVER_MS - waited + 999) / 1000), 3);
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (step == Step.LIGHT) light();
                }
            }, 700);
        } else {
            f.dark = false;
            startRun();
        }
    }

    // ---- 2. frame after frame ----

    private void startRun() {
        step = Step.RUN;
        lastProgressMs = SystemClock.elapsedRealtime();
        queueRun();
    }

    private void queueRun() {
        if (closed || step != Step.RUN || rig == null || !rig.ready()) return;
        while (inFlight < 2 && f.runDone + inFlight < runTarget) {
            Req r = ask(RUN_NS);
            if (r == null) break;
            r.index = ++f.runAsked;
        }
        state(f.dark ? "Checking frame after frame" : "Checking frame after frame (the lens isn't covered)",
                String.format(Locale.US, "Frame %d of %d: one-second frames, two queued at a time, as at night%s", f.runDone,
                        runTarget, f.stops > 0 ? String.format(Locale.US, " (the camera stopped after %d in a row)", f.stopAfter) : ""),
                5 + 65 * f.runDone / Math.max(1, runTarget));
    }

    private void runCame(Req r) {
        f.runDone++;
        sinceCure++;
        if (!trying.isEmpty() && f.cure.isEmpty()) f.cure = trying;
        // the pause between this exposure's start and the last one's end (the sensor's clock)
        if (r.startNs > 0 && prevStartNs > 0 && r.startNs > prevStartNs) {
            double gap = (r.startNs - prevStartNs - prevExpNs) / 1e6;
            if (gap >= 0 && gap < 10_000) gaps.add(gap);
        }
        if (r.startNs > 0) {
            prevStartNs = r.startNs;
            Long e = r.result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
            prevExpNs = e != null ? e : r.expNs;
        }
        if (f.runDone == 4) {
            float[] black = SensorSpecFactory.dynamicBlack(r.result, geo);
            if (black != null) for (int p = 0; p < 4; p++) f.blackReported[p] = black[p];
            Pair<Double, Double>[] np = r.result.get(CaptureResult.SENSOR_NOISE_PROFILE);
            if (np != null && np.length >= 4) {
                double o = 0;
                for (int c = 0; c < 4; c++) o += np[c].second;
                f.profileNoise = Math.sqrt(Math.max(0, o / 4)) * (spec.white - mean(spec.black));
            }
        }
        if (f.dark && haveB && shortDark == null) darkPair();
        if (f.runDone >= runTarget) {
            endRun();
        } else {
            queueRun();
        }
    }

    /** The settled pair of dark frames: levels, spread, banding, and the read noise from their difference. */
    private void darkPair() {
        int W = rawSize.getWidth(), H = rawSize.getHeight();
        DarkFrames.Frame d = DarkFrames.measure(bufA, W, H, spec.white, mean(spec.black));
        double lit = DarkFrames.litShare(d, spec.white, mean(spec.black));
        if (lit > DarkFrames.LIT_SHARE) {
            // light got in after all (the phone was moved, or a second's frame shows what a quarter's didn't)
            f.dark = false;
            f.lit = lit;
        } else {
            shortDark = d;
            shortLevel = d.mean();
            System.arraycopy(d.level, 0, f.black, 0, 4);
            f.noise = d.noise;
            f.rows = d.rows;
            f.readNoise = DarkFrames.pairNoise(bufA, bufB, W, H);
        }
        bufA = bufB = null;
    }

    /** No frame for too long: what was learned, then the next thing to get it going. */
    private void stopped() {
        int k = rig != null ? rig.sessionResults() : 0;
        f.stops++;
        abortAll();
        // the pause across a stop isn't the camera's pause between frames
        prevStartNs = -1;
        if (f.stops == 1) {
            f.stopAfter = k;
            // after a stop the run goes on until it could see another after as many frames
            runTarget = Math.min(RUN_MAX, Math.max(runTarget, f.runDone + k + 5));
        } else if (sinceCure > 0 && f.stopAgainAfter == 0) {
            f.stopAgainAfter = k;
        }
        if (f.stops > MAX_STOPS) {
            endRun();
            return;
        }
        if (sinceCure == 0 && "reopen".equals(trying)) {
            // nothing came after opening it again either
            f.cure = "none";
            endRun();
            return;
        }
        // a fresh capture session first; if nothing comes after one, open the camera again
        trying = sinceCure == 0 && "session".equals(trying) ? "reopen" : trying.isEmpty() ? "session" : trying;
        sinceCure = 0;
        lastProgressMs = SystemClock.elapsedRealtime();
        state("The camera stopped", String.format(Locale.US, "After %d frames in a row. Trying %s.", k,
                "reopen".equals(trying) ? "closing and opening it" : "a fresh capture session"), 5 + 65 * f.runDone / Math.max(1, runTarget));
        if ("reopen".equals(trying)) {
            reopen();
        } else {
            rig.restartSession();
        }
    }

    /** Closes the camera and opens it again; onReady follows. */
    private void reopen() {
        if (rig != null) rig.close();
        rig = null;
        lastProgressMs = SystemClock.elapsedRealtime();
        openRig();
    }

    private void abortAll() {
        for (Req r : new ArrayList<Req>(reqs.values())) done(r);
        if (rig != null) rig.abort();
    }

    private void endRun() {
        if (step != Step.RUN) return;
        abortAll();
        if (gaps.size() >= 3) {
            List<Double> g = new ArrayList<Double>(gaps);
            Collections.sort(g);
            f.gapMs = g.get(g.size() / 2);
            f.gapMaxMs = g.get(g.size() - 1);
        }
        if (f.stopAfter > 0 && f.cure.isEmpty()) f.cure = "none";
        longExposure();
    }

    // ---- 3. the night's long exposure ----

    private void longExposure() {
        step = Step.LONG;
        longTries = 0;
        longAskNs = f.nightNs;
        if (rig == null || "none".equals(f.cure)) {
            // the camera won't answer: reopen it for the last part
            reopen();
            return;
        }
        if (rig.ready() && inFlight == 0) queueLong();
    }

    private void queueLong() {
        if (closed || step != Step.LONG || inFlight > 0) return;
        Req r = ask(longAskNs);
        if (r == null) {
            finish();
            return;
        }
        longTries++;
        final int seq = r.seq;
        final long wait = longAskNs / 1_000_000L + 15_000L;
        final long t0 = SystemClock.elapsedRealtime();
        h.post(new Runnable() {
            @Override
            public void run() {
                Req q = reqs.get(seq);
                if (q == null || closed) return;
                long left = (wait - (SystemClock.elapsedRealtime() - t0)) / 1000;
                state("The night's long exposure", String.format(Locale.US, "%s at ISO %d, as a night here would take it%s. %d s left.",
                        CameraFacts.seconds(longAskNs), f.iso, f.dark ? "; in the dark it shows the hot pixels" : "", Math.max(0, left - 15)),
                        70 + (int) (28 * Math.min(1.0, (SystemClock.elapsedRealtime() - t0) / (double) (longAskNs / 1_000_000L))));
                if (SystemClock.elapsedRealtime() - t0 > wait) {
                    // it never came back
                    q.failed = true;
                    done(q);
                    rig.abort();
                    longCame(q, 0);
                } else {
                    h.postDelayed(this, 1000);
                }
            }
        });
    }

    private void longCame(Req r, long gotNs) {
        CameraFacts.Exposure e = new CameraFacts.Exposure();
        e.askedNs = r.expNs;
        e.gotNs = gotNs;
        e.backMs = SystemClock.elapsedRealtime() - r.askedMs;
        f.exposures.add(e);
        if (gotNs > 0) {
            if (e.full()) prefs.noteExposureDelivered(cameraId, r.expNs);
            else prefs.noteExposureClamped(cameraId, r.expNs, gotNs);
            if (r.expNs == f.nightNs) hotPixels(gotNs);
            finish();
        } else if (longTries < 2) {
            queueLong();
        } else if (longAskNs == f.nightNs && f.nightNs >= 2_000_000_000L) {
            // failed twice: does half as long come out?
            longAskNs = f.nightNs / 2;
            longTries = 0;
            queueLong();
        } else {
            finish();
        }
    }

    /** The long dark exposure against the short ones: hot pixels, and the signal the dark adds per second. */
    private void hotPixels(long gotNs) {
        if (!f.dark || bufLong == null) return;
        int W = rawSize.getWidth(), H = rawSize.getHeight();
        DarkFrames.Frame d = DarkFrames.measure(bufLong, W, H, spec.white, mean(spec.black));
        bufLong = null;
        if (DarkFrames.litShare(d, spec.white, mean(spec.black)) > DarkFrames.LIT_SHARE) return;
        f.hot = d.hot;
        f.pixels = d.pixels;
        f.hotExpS = gotNs / 1e9;
        if (shortDark != null && gotNs > RUN_NS) {
            f.darkPerS = (d.mean() + d.excess - shortLevel - shortDark.excess) / ((gotNs - RUN_NS) / 1e9);
        }
    }

    // ---- the end ----

    private void finish() {
        if (step == Step.DONE) return;
        step = Step.DONE;
        BatteryInfo b = BatteryInfo.read(ctx);
        f.tempC = b.tempC;
        // the sessions' part: how often to renew the capture session (a clean run longer than a learned count clears it)
        if (f.renewAfter() > 0) {
            prefs.noteRenew(cameraId, f.renewAfter(), f.cure);
        } else if (f.stops == 0 && prefs.renewAfter(cameraId) > 0 && prefs.renewAfter(cameraId) < f.runDone) {
            prefs.noteRenew(cameraId, 0, "");
        }
        if (f.complete()) prefs.setCameraFacts(cameraId, f.encode());
        release();
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onDone(f);
            }
        });
    }

    private void fail(final String text) {
        if (step == Step.DONE) return;
        if (f.runDone > 0) {
            // what the run found is worth keeping
            f.stoppedBy = text;
            finish();
            return;
        }
        step = Step.DONE;
        release();
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(text);
            }
        });
    }

    private void release() {
        if (rig != null) {
            rig.close();
            rig = null;
        }
        bufA = bufB = bufLong = null;
        if (thread != null) thread.quitSafely();
    }

    /** Twice a second: a stopped camera in the run; while opening, a camera that never comes up. */
    private void tick() {
        if (closed || step == Step.DONE) return;
        long now = SystemClock.elapsedRealtime();
        if (step == Step.RUN && now - lastProgressMs > Math.max(6000L, 2 * RUN_NS / 1_000_000L + 4000L)) {
            stopped();
        } else if (step == Step.OPENING && now - startMs > 20_000L) {
            fail("The camera didn't open.");
            return;
        } else if (step == Step.LIGHT && now - lastProgressMs > 8000L) {
            fail("The camera didn't send back its first frame.");
            return;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 500);
    }

    private void state(final String head, final String detail, final int pct) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (!closed) listener.onState(head, detail, pct);
            }
        });
    }

    private static float mean(float[] a) {
        return (a[0] + a[1] + a[2] + a[3]) / 4;
    }
}
