package com.brandon.nightsky;

import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.CancelledException;
import com.brandon.nightsky.processing.DngWriter;
import com.brandon.nightsky.processing.LiveStats;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Rejection;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.UnitProcessor;
import com.brandon.nightsky.processing.UnitRecord;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One night-sky session from start to finish:
 * <ol>
 * <li>open the camera and wait until the phone lies screen-down and perfectly still,</li>
 * <li>count down with beeps while a short test frame measures the sky brightness and reads the lens
 * shading map, then settle,</li>
 * <li>capture RAW exposures back to back (two queued), each handed to the {@link UnitProcessor} which stores
 * it on disk at once; pause if the phone is picked up, restart the camera if it stalls,</li>
 * <li>develop: align and stack ({@link PostProcessor}), render, save JPEG, DNG and log.</li>
 * </ol>
 * Everything except developing runs on one thread, the camera's callbacks included.
 */
final class SessionController {
    enum Phase { IDLE, STARTING, PLACE, STEADY, COUNTDOWN, CAPTURING, PAUSED, FINISHING, DEVELOPING, DONE, FAILED }

    static final class Settings {
        boolean trails;
        int durationSec = 300;
        float brightness = 0.5f;
        boolean sounds = true;
        boolean saveDng = true;
        int countdownSec = 5;
        /** Started from the remote viewer: keep the viewer's mute setting. */
        boolean fromViewer;
    }

    /** What the screen and the notification show. Immutable once published. */
    static final class Status {
        Phase phase = Phase.IDLE;
        String headline = "", detail = "";
        int done, planned;
        long elapsedMs, remainingMs = -1;
        float progress = -1f;
        boolean trails;
        int previewVersion;
        Result result;
        long version;
        /** Plan of the current session (0 before there is one). */
        long frameExpNs;
        int iso;
        /** Started from the viewer: no need to lie face-down. */
        boolean forced;
        boolean muted, soundsOn;
        /** Live-frame picture for the viewer: bumped with each processed unit. */
        int latestVersion = -1;
    }

    static final class Result {
        String summary = "", name = "";
        Uri jpeg, dng, log;
        boolean canAdjust;
        float brightness;
    }

    interface Host {
        void onStatus(Status s);

        /** Capture is over (the camera is closed); the phone may be picked up. */
        void onCaptureEnded();

        /** Developing finished or failed; nothing is running any more. */
        void onIdle();
    }

    private static final int PROBE = 1, FRAME = 2;
    private static final long STILL_NS = 3_000_000_000L;
    /** Stillness needed after "Start now" from the viewer (the phone need not be face-down). */
    private static final long FORCED_STILL_NS = 1_000_000_000L;
    /** Longest session that "+5 minutes" can build up to. */
    private static final int MAX_DURATION_SEC = 2 * 60 * 60;
    private static final long SETTLE_MS = 1500;
    /** A capture paused this long (phone not put back down) is finished with what it has. */
    private static final long MAX_PAUSE_MS = 5 * 60_000L;
    /** A rotation rate above this during an exposure spoils the frame (rad/s). */
    private static final float DISTURBED_RAD_S = 0.05f;

    private static final class Req {
        final int seq, kind;
        final long expNs;
        long startNs = -1;
        short[] buf;
        TotalCaptureResult result;
        boolean lost, dropped;
        long arrivalNs;

        Req(int seq, int kind, long expNs) {
            this.seq = seq;
            this.kind = kind;
            this.expNs = expNs;
        }
    }

    private final Context ctx;
    private final Prefs prefs;
    private final Host host;
    private final Handler h;
    private final HandlerThread thread;
    private final File root;

    private volatile Status status = new Status();
    private volatile Bitmap preview;
    private int previewVersion;
    private long statusVersion;

    // session
    private Settings settings;
    private MotionMonitor motion;
    private Sounds sounds;
    private CameraRig rig;
    private String cameraId;
    private CameraCharacteristics cc;
    private Size rawSize;
    private SensorSpecFactory.Geometry geo;
    private SensorSpec spec;
    private ExposurePlan plan;
    private int isoMin, isoStart;
    private long advMaxNs;
    private boolean realtimeClock;
    private SessionStore store;
    private UnitProcessor up;
    private Phase phase = Phase.IDLE;
    /** Bumped to stop the tick loop. */
    private int token;
    /** Bumped to cancel a pending delayed start or resume. */
    private int startToken;

    // countdown and test frame
    private int countdownLeft;
    private long nextBeepMs;
    private boolean probeSent, probeDone;
    private int probeRetries;
    private long probeSentMs;
    private Req probe;
    private double probeBg = Double.NaN;

    // capture loop
    private int seqCounter;
    private final Map<Integer, Req> reqs = new HashMap<Integer, Req>();
    private final Map<Long, Integer> tsToSeq = new HashMap<Long, Integer>();
    private int inFlight, framesDone, framesDropped, consecutiveFailures, restarts, awayTicks;
    private boolean firstFrameChecked;
    private long captureStartMs, lastProgressMs, pausedAtMs, pausedTotalMs;
    private volatile int liveStars;
    private volatile boolean liveAligned;

    // remote viewer
    private volatile boolean forced, muted, remoteWanted;
    private final LiveStats liveStats = new LiveStats();
    private volatile UnitProcessor.Latest latest;
    private volatile Renderer.Analysis liveAnalysis;
    private volatile SensorSpec liveSpec;
    private volatile int latestVersion = -1;

    // developed result, kept for brightness changes
    private PostProcessor.Output output;
    private SensorSpec outSpec;
    private RgbImage small;
    private int smallFactor;
    private Result result;
    private volatile boolean cancelDevelop;
    private int rerenderToken;

    SessionController(Context c, Prefs prefs, Host host) {
        ctx = c.getApplicationContext();
        this.prefs = prefs;
        this.host = host;
        thread = new HandlerThread("session");
        thread.start();
        h = new Handler(thread.getLooper());
        root = sessionsRoot(ctx);
    }

    static File sessionsRoot(Context c) {
        File base = c.getExternalFilesDir(null);
        if (base == null) base = c.getFilesDir();
        File r = new File(base, "sessions");
        //noinspection ResultOfMethodCallIgnored
        r.mkdirs();
        return r;
    }

    Status status() {
        return status;
    }

    Bitmap preview() {
        return preview;
    }

    // ------------------------------------------------------------------------------------------------
    // Commands (any thread)
    // ------------------------------------------------------------------------------------------------

    void start(final Settings s) {
        h.post(new Runnable() {
            @Override
            public void run() {
                doStart(s);
            }
        });
    }

    /** Stop capturing and develop what was captured. */
    void finishNow() {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (phase == Phase.CAPTURING || phase == Phase.PAUSED) {
                    finishCapture("stopped by you");
                } else if (phase.ordinal() < Phase.CAPTURING.ordinal() && phase != Phase.IDLE) {
                    cancelAll();
                }
            }
        });
    }

    /** Throw the session away. */
    void cancel() {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (phase == Phase.FINISHING || phase == Phase.DEVELOPING) {
                    // stop developing but keep the frames
                    cancelDevelop = true;
                    return;
                }
                cancelAll();
            }
        });
    }

    /** Develop a session left unfinished by a crash or a dead battery. */
    void recover(final File dir) {
        h.post(new Runnable() {
            @Override
            public void run() {
                doRecover(dir);
            }
        });
    }

    /** New on-screen preview at another brightness (quick, binned). */
    void adjust(final float brightness) {
        final int tk = ++rerenderToken;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (tk != rerenderToken || output == null || small == null) return;
                Renderer.Params p = new Renderer.Params();
                p.brightness = brightness;
                int[] px = Renderer.renderSmall(small, output.analysis, p, smallFactor);
                preview = Bitmap.createBitmap(px, small.cropWidth(), small.cropHeight(), Bitmap.Config.ARGB_8888);
                previewVersion++;
                if (result != null) result.brightness = brightness;
                Status cur = status;
                publish(cur.headline, cur.detail, -1);
            }
        });
    }

    /** Saves a full-size JPEG at another brightness. */
    void saveVersion(final float brightness) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (output == null || result == null || phase != Phase.DONE) return;
                phase = Phase.DEVELOPING;
                publish("Saving", "Full-size photo at the new brightness", -1);
                final PostProcessor.Output o = output;
                final SensorSpec sp = outSpec;
                final Result r = result;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String err = null;
                        try {
                            String name = r.name + "_b" + Math.round(brightness * 100);
                            r.jpeg = saveJpeg(o, sp, brightness, name);
                            r.brightness = brightness;
                            prefs.setLastResultUri(r.jpeg.toString());
                        } catch (Throwable t) {
                            err = "Could not save: " + t.getMessage();
                        }
                        final String e = err;
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                phase = Phase.DONE;
                                publish(e == null ? "Saved" : "Not saved", e == null ? r.summary : e, -1);
                            }
                        });
                    }
                }, "save").start();
            }
        });
    }

    /** Forget the developed result (frees memory). */
    void clearResult() {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (phase == Phase.DONE || phase == Phase.FAILED) {
                    output = null;
                    small = null;
                    result = null;
                    preview = null;
                    phase = Phase.IDLE;
                    publish("", "", -1);
                }
            }
        });
    }

    void shutdown() {
        h.post(new Runnable() {
            @Override
            public void run() {
                releaseCapture();
                if (up != null) {
                    up.abort(); // the units saved so far stay on disk for "Develop it"
                    up = null;
                }
                thread.quitSafely();
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Remote viewer (called from the viewer's threads, never from the session thread)
    // ------------------------------------------------------------------------------------------------

    /** Whether a viewer may be watching: the live processor then keeps a picture of each new frame. */
    void setRemoteWanted(final boolean on) {
        remoteWanted = on;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (up != null) up.setKeepLatest(on);
                if (!on) latest = null;
            }
        });
    }

    /** Copies of the per-unit statistics from position {@code from} on. */
    List<LiveStats.Unit> liveUnits(int from) {
        return liveStats.from(from);
    }

    UnitProcessor.Latest latestFrame() {
        return latest;
    }

    /** How the live stack is stretched (the same is used for the latest frame), or null before the first. */
    Renderer.Analysis liveAnalysis() {
        return liveAnalysis;
    }

    SensorSpec liveSpec() {
        return liveSpec;
    }

    float sessionBrightness() {
        Settings s = settings;
        return s != null ? s.brightness : prefs.brightness();
    }

    /** Skip waiting for the phone to lie screen-down and the countdown. */
    String remoteStartNow() {
        return onSession(new Callable<String>() {
            @Override
            public String call() {
                switch (phase) {
                    case STARTING:
                    case PLACE:
                    case STEADY:
                    case COUNTDOWN:
                        forced = true;
                        log("start now: asked from the viewer");
                        if (phase == Phase.COUNTDOWN && countdownLeft > 0) {
                            countdownLeft = 0;
                            nextBeepMs = SystemClock.elapsedRealtime();
                        }
                        return "Starting as soon as the phone is still";
                    default:
                        return "It is not waiting to start";
                }
            }
        });
    }

    String remoteStop() {
        return onSession(new Callable<String>() {
            @Override
            public String call() {
                if (phase == Phase.CAPTURING || phase == Phase.PAUSED) {
                    finishCapture("stopped from the viewer");
                    return "Stopped. Developing the frames taken so far.";
                }
                return "It is not capturing";
            }
        });
    }

    /** Cancels a session that has not started capturing yet. */
    String remoteCancel() {
        return onSession(new Callable<String>() {
            @Override
            public String call() {
                if (phase.ordinal() >= Phase.STARTING.ordinal() && phase.ordinal() <= Phase.COUNTDOWN.ordinal()) {
                    cancelAll();
                    return "Cancelled";
                }
                return "Only a session that has not started can be cancelled from here";
            }
        });
    }

    String remoteAddTime(final int seconds) {
        return onSession(new Callable<String>() {
            @Override
            public String call() {
                if (phase != Phase.CAPTURING && phase != Phase.PAUSED) return "Time can only be added while capturing";
                if (settings.durationSec + seconds > MAX_DURATION_SEC) {
                    return "It is already as long as a session can be (" + MAX_DURATION_SEC / 60 + " minutes)";
                }
                int extra = (int) Math.max(1, Math.round(seconds * 1e9 / plan.frameExpNs));
                long unitBytes = 2L * spec.width * spec.height;
                int unitsLeft = (plan.frames + extra - framesDone + plan.framesPerUnit - 1) / plan.framesPerUnit;
                long need = unitBytes * (unitsLeft + 2) + (400L << 20);
                try {
                    if (new StatFs(root.getAbsolutePath()).getAvailableBytes() < need) return "Not enough free storage for more time";
                } catch (IllegalArgumentException ignored) {
                }
                plan.frames += extra;
                settings.durationSec += seconds;
                log(String.format(Locale.US, "viewer added %d s: %d frames planned", seconds, plan.frames));
                publishCapture();
                return String.format(Locale.US, "Added %d minutes: %d minutes in all", Math.round(seconds / 60f),
                        Math.round(settings.durationSec / 60f));
            }
        });
    }

    String remoteMute(final boolean on) {
        return onSession(new Callable<String>() {
            @Override
            public String call() {
                muted = on;
                if (sounds != null) sounds.setMuted(on);
                republish();
                return on ? "The phone is muted" : "The phone will beep and speak again";
            }
        });
    }

    /**
     * Runs {@code c} on the session thread and waits for its answer (a short message for the viewer). If the
     * session thread is too busy to answer in time, the command is dropped rather than run late, so pressing
     * the button again doesn't do it twice.
     */
    private String onSession(final Callable<String> c) {
        final String[] out = {null};
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean claimed = new AtomicBoolean();
        boolean posted = h.post(new Runnable() {
            @Override
            public void run() {
                if (!claimed.compareAndSet(false, true)) return;
                try {
                    out[0] = c.call();
                } catch (Exception e) {
                    out[0] = "That did not work: " + e.getMessage();
                } finally {
                    done.countDown();
                }
            }
        });
        if (!posted) return "The app is closing";
        try {
            if (!done.await(4, TimeUnit.SECONDS)) {
                if (claimed.compareAndSet(false, true)) return "The phone is busy, try again";
                done.await(10, TimeUnit.SECONDS); // it has just started: wait for its answer
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            claimed.set(true);
            return "Interrupted";
        }
        return out[0] != null ? out[0] : "The phone is busy, try again";
    }

    // ------------------------------------------------------------------------------------------------
    // Start
    // ------------------------------------------------------------------------------------------------

    private void doStart(Settings s) {
        if (phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.FAILED) return;
        output = null;
        small = null;
        result = null;
        preview = null;
        if (up != null) up.abort();
        up = null;
        store = null;
        settings = s;
        if (!s.fromViewer) muted = false;
        token++;
        resetCapture();
        phase = Phase.STARTING;
        publish("Getting the camera ready", "", -1);
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraId = CameraRig.pickCamera(cm);
            if (cameraId == null) {
                fail("This phone has no back camera with RAW support.");
                return;
            }
            cc = cm.getCameraCharacteristics(cameraId);
            rawSize = CameraRig.rawSize(cc);
            geo = SensorSpecFactory.geometry(cc, rawSize);
            spec = SensorSpecFactory.build(cc, cameraId, rawSize, geo);
            Integer ts = cc.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
            realtimeClock = ts != null && ts == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME;
            Range<Integer> isoRange = cc.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            Integer analog = cc.get(CameraCharacteristics.SENSOR_MAX_ANALOG_SENSITIVITY);
            isoMin = isoRange != null ? isoRange.getLower() : 50;
            int isoMax = isoRange != null ? isoRange.getUpper() : 3200;
            int start = analog != null && analog > 0 ? analog : 1600;
            isoStart = Math.max(isoMin, Math.min(Math.min(isoMax, 3200), start));
            Range<Long> expRange = cc.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            long advMax = expRange != null ? expRange.getUpper() : 0;
            advMaxNs = advMax;
            plan = ExposurePlan.make(spec.model.fx, ExposurePlan.DEFAULT_LATITUDE, s.durationSec, advMax,
                    prefs.verifiedExposureNs(cameraId), prefs.clampDeliveredNs(cameraId), isoStart, isoMin, spec.white);
            String space = checkSpace();
            if (space != null) {
                fail(space);
                return;
            }
            cleanupOldSessions();
            motion = new MotionMonitor(ctx);
            motion.start();
            sounds = new Sounds(ctx, s.sounds);
            sounds.setMuted(muted);
            rig = new CameraRig(ctx, h, rigListener);
            rig.open(cameraId, cc, rawSize);
        } catch (CameraAccessException | SecurityException e) {
            fail("Could not open the camera: " + e.getMessage());
            return;
        } catch (RuntimeException e) {
            fail("Could not start: " + e);
            return;
        }
        scheduleTick();
    }

    /** Null if there is room for the session's frames, else a message. */
    private String checkSpace() {
        long unitBytes = 2L * spec.width * spec.height;
        int units = (plan.frames + plan.framesPerUnit - 1) / plan.framesPerUnit;
        long need = unitBytes * (units + 2) + (400L << 20);
        long free;
        try {
            free = new StatFs(root.getAbsolutePath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (free >= need) return null;
        return String.format(Locale.US, "Not enough free storage: this session needs about %.1f GB, %.1f GB is free. "
                + "Choose a shorter time or free some space.", need / 1e9, free / 1e9);
    }

    /** Frames of developed sessions are deleted (logs kept); empty leftovers are removed. */
    private void cleanupOldSessions() {
        for (SessionStore s : SessionStore.list(root)) {
            String st = s.state();
            try {
                if ("developed".equals(st)) {
                    s.deleteBulk();
                } else if (s.readUnits().isEmpty()) {
                    s.deleteAll();
                }
            } catch (IOException ignored) {
            }
        }
    }

    private void resetCapture() {
        reqs.clear();
        tsToSeq.clear();
        inFlight = 0;
        framesDone = 0;
        framesDropped = 0;
        consecutiveFailures = 0;
        restarts = 0;
        awayTicks = 0;
        firstFrameChecked = false;
        probeSent = false;
        probeDone = false;
        probeRetries = 0;
        probe = null;
        probeBg = Double.NaN;
        pausedTotalMs = 0;
        liveStars = 0;
        liveAligned = false;
        cancelDevelop = false;
        forced = false;
        latest = null;
        latestVersion = -1;
        liveAnalysis = null;
        liveStats.clear();
    }

    // ------------------------------------------------------------------------------------------------
    // The 10 Hz tick: placement, stillness, countdown, pause, watchdog
    // ------------------------------------------------------------------------------------------------

    private void scheduleTick() {
        final int tk = token;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tk != token) return;
                tick();
                if (phase.ordinal() >= Phase.STARTING.ordinal() && phase.ordinal() <= Phase.PAUSED.ordinal()) scheduleTick();
            }
        }, 100);
    }

    private long lastPublishMs;

    private void tick() {
        long now = SystemClock.elapsedRealtime();
        switch (phase) {
            case PLACE:
                if (forced || motion.faceDown()) {
                    phase = Phase.STEADY;
                    publish("Hold still", "Starting once the phone is still", -1);
                }
                break;
            case STEADY:
                if (!forced && !motion.faceDown()) {
                    phase = Phase.PLACE;
                    publishPlace();
                } else if (forced ? motion.quietNs() >= FORCED_STILL_NS : motion.stillNs() >= STILL_NS) {
                    startCountdown(now);
                }
                break;
            case COUNTDOWN:
                if ((!forced && !motion.faceDown()) || motion.rate() > 0.15f) {
                    // picked up again: wait for stillness again
                    startToken++;
                    phase = forced || motion.faceDown() ? Phase.STEADY : Phase.PLACE;
                    if (phase == Phase.PLACE) publishPlace();
                    else publish("Hold still", "Starting once the phone is still", -1);
                    return;
                }
                if (!probeDone && probeSent && now - probeSentMs > 12000) {
                    // the test frame never came back: go with the default plan, and ignore it if it turns up
                    log("test frame timed out");
                    forgetProbe();
                    probeDone = true;
                }
                if (countdownLeft > 0 && now >= nextBeepMs) {
                    sounds.tick();
                    publish("Starting in " + countdownLeft, "Keep the phone still", -1);
                    countdownLeft--;
                    nextBeepMs = now + 1000;
                } else if (countdownLeft == 0 && now >= nextBeepMs && probeDone) {
                    countdownLeft = -1;
                    sounds.chime();
                    publish("Starting", plan.describe(), -1);
                    final int tk = ++startToken;
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (tk == startToken && phase == Phase.COUNTDOWN) startCapture();
                        }
                    }, SETTLE_MS);
                } else if (countdownLeft == 0 && now >= nextBeepMs) {
                    publish("Testing the sky", "Measuring how bright it is", -1);
                }
                break;
            case CAPTURING:
                if ((!forced && !motion.faceDown()) || motion.rate() > 0.3f) {
                    if (++awayTicks >= 3) {
                        pause();
                        break;
                    }
                } else {
                    awayTicks = 0;
                }
                if (now - lastProgressMs > plan.frameExpNs / 1_000_000L * 2 + 15000) {
                    // nothing for too long, also when nothing could be queued: restart, reopen, give up
                    watchdog();
                } else if (now - captureStartMs - pausedTotalMs > settings.durationSec * 2000L + 120000L) {
                    finishCapture("it was taking far longer than planned");
                }
                if (phase == Phase.CAPTURING && now - lastPublishMs >= 1000) publishCapture();
                break;
            case PAUSED:
                if (forced ? motion.quietNs() >= STILL_NS : motion.stillNs() >= STILL_NS) resume();
                else if (now - pausedAtMs > MAX_PAUSE_MS) finishCapture("the phone was not put back down");
                else if (now - lastPublishMs >= 1000) publishCapture();
                break;
            default:
                break;
        }
    }

    private void publishPlace() {
        publish("Lay the phone screen-down", "The back camera must face the sky. It starts by itself once the phone is still.", -1);
    }

    private void startCountdown(long now) {
        phase = Phase.COUNTDOWN;
        countdownLeft = forced ? 0 : Math.max(1, settings.countdownSec);
        nextBeepMs = now;
        if (!probeSent) sendProbe();
    }

    // ------------------------------------------------------------------------------------------------
    // Test frame
    // ------------------------------------------------------------------------------------------------

    private void sendProbe() {
        int seq = ++seqCounter;
        // one second, or the longest the camera admits to if that is shorter (the first real frame tests more)
        long exp = advMaxNs > 0 ? Math.min(1_000_000_000L, Math.max(100_000_000L, advMaxNs)) : 1_000_000_000L;
        Req r = new Req(seq, PROBE, exp);
        if (!rig.capture(seq, exp, isoStart)) {
            log("test frame could not be queued");
            probeDone = true;
            return;
        }
        reqs.put(seq, r);
        probe = r;
        probeSent = true;
        probeSentMs = SystemClock.elapsedRealtime();
    }

    /** Median green level of the test frame (DN, before black subtraction), from a sparse grid. */
    private double sampleGreen(Image img) {
        Image.Plane pl = img.getPlanes()[0];
        ByteBuffer b = pl.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
        int rs = pl.getRowStride();
        int W = img.getWidth(), H = img.getHeight();
        int gp = 1;
        for (int p = 0; p < 4; p++) {
            if (spec.cfa[p] == 1) {
                gp = p;
                break;
            }
        }
        int px = gp & 1, py = gp >> 1;
        int step = 16;
        float[] v = new float[((W / step) + 1) * ((H / step) + 1)];
        int n = 0;
        for (int y = H / 10 + py; y < H - H / 10; y += step) {
            int yy = (y & ~1) | py;
            for (int x = W / 10 + px; x < W - W / 10; x += step) {
                int xx = (x & ~1) | px;
                v[n++] = b.getShort(yy * rs + xx * 2) & 0xFFFF;
            }
        }
        if (n == 0) return Double.NaN;
        Arrays.sort(v, 0, n);
        return v[n / 2];
    }

    private void forgetProbe() {
        if (probe == null) return;
        reqs.remove(probe.seq);
        if (probe.startNs >= 0) tsToSeq.remove(probe.startNs);
        probe = null;
    }

    private void probeArrived() {
        Req r = probe;
        if (r == null || r.result == null || (Double.isNaN(probeBg) && !r.lost)) return;
        if (phase.ordinal() >= Phase.CAPTURING.ordinal()) {
            // too late: the capture has its plan and must not change under it
            forgetProbe();
            return;
        }
        probe = null;
        reqs.remove(r.seq);
        TotalCaptureResult res = r.result;
        SensorSpecFactory.applyResult(spec, res, geo);
        Long delivered = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
        long del = delivered != null ? delivered : r.expNs;
        int greenPlane = 1;
        for (int p = 0; p < 4; p++) {
            if (spec.cfa[p] == 1) {
                greenPlane = p;
                break;
            }
        }
        double bgPerSec = Double.NaN;
        if (!Double.isNaN(probeBg) && del > 0) bgPerSec = Math.max(0, probeBg - spec.black[greenPlane]) / (del / 1e9);
        if (del < r.expNs * 0.93) prefs.noteExposureClamped(cameraId, r.expNs, del);
        plan.adjust(Double.isNaN(bgPerSec) ? -1 : bgPerSec, iso != null ? iso : isoStart, spec.range(), r.expNs, del,
                isoMin, settings.durationSec, spec.white);
        probeDone = true;
    }

    // ------------------------------------------------------------------------------------------------
    // Capture loop
    // ------------------------------------------------------------------------------------------------

    private void startCapture() {
        try {
            openStore();
        } catch (IOException e) {
            fail("Could not create the session folder: " + e.getMessage());
            return;
        }
        phase = Phase.CAPTURING;
        captureStartMs = SystemClock.elapsedRealtime();
        lastProgressMs = captureStartMs;
        publishCapture();
        submitNext();
    }

    private void openStore() throws IOException {
        if (store != null && up != null) return;
        spec.iso = plan.iso;
        spec.frameExposureNs = plan.frameExpNs;
        store = SessionStore.create(root);
        Properties p = spec.toProperties();
        p.setProperty("mode", settings.trails ? "trails" : "deep");
        p.setProperty("brightness", Float.toString(settings.brightness));
        p.setProperty("saveDng", Boolean.toString(settings.saveDng));
        p.setProperty("durationSec", Integer.toString(settings.durationSec));
        p.setProperty("plan", plan.describe());
        p.setProperty("rejection", Rejection.Method.parse(prefs.rejection()).name());
        p.setProperty("state", "capturing");
        store.writeSettings(p);
        store.log("Night Sky Camera " + BuildInfo.VERSION + " on " + spec.deviceMake + " " + spec.deviceModel
                + " (" + android.os.Build.FINGERPRINT + ")");
        store.log("camera " + cameraId + ": " + spec.describe());
        store.log("plan: " + plan.describe());
        float tilt = motion.tiltDeg();
        if (!Float.isNaN(tilt)) store.log(String.format(Locale.US, "tilt from vertical %.1f deg", tilt));
        up = new UnitProcessor(store, spec, plan.framesPerUnit, 3, unitListener);
        up.setKeepLatest(remoteWanted);
        liveSpec = spec;
    }

    private void submitNext() {
        if (phase != Phase.CAPTURING || rig == null || !rig.ready()) return;
        while (inFlight < 2 && framesDone + inFlight < plan.frames) {
            int seq = ++seqCounter;
            Req r = new Req(seq, FRAME, plan.frameExpNs);
            if (!rig.capture(seq, plan.frameExpNs, plan.iso)) break;
            reqs.put(seq, r);
            inFlight++;
        }
    }

    private void frameArrived(Req r) {
        if (r.result == null || (r.buf == null && !r.lost && !r.dropped)) return;
        reqs.remove(r.seq);
        if (r.startNs >= 0) tsToSeq.remove(r.startNs);
        inFlight--;
        lastProgressMs = SystemClock.elapsedRealtime();
        consecutiveFailures = 0;
        restarts = 0;
        if (r.buf == null) {
            framesDropped++;
            submitNext();
            return;
        }
        TotalCaptureResult res = r.result;
        Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        long exp = e != null ? e : r.expNs;
        if (!firstFrameChecked) {
            firstFrameChecked = true;
            if (exp < r.expNs * 0.93) {
                // the camera cut the exposure short: plan again around what it can do
                prefs.noteExposureClamped(cameraId, r.expNs, exp);
                up.releaseBuffer(r.buf);
                replanAfterClamp(exp);
                return;
            }
            prefs.noteExposureDelivered(cameraId, r.expNs);
        }
        UnitProcessor.FrameInfo info = new UnitProcessor.FrameInfo();
        Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
        info.timestampNs = ts != null ? ts : r.startNs;
        info.exposureNs = exp;
        Integer iso = res.get(CaptureResult.SENSOR_SENSITIVITY);
        info.iso = iso != null ? iso : plan.iso;
        float[] black = SensorSpecFactory.dynamicBlack(res, geo);
        System.arraycopy(black != null ? black : spec.black, 0, info.black, 0, 4);
        long t0 = realtimeClock && info.timestampNs > 0 ? info.timestampNs : r.arrivalNs - exp;
        float rate = motion.maxRate(t0, t0 + exp);
        info.gyroMax = Math.max(0f, rate);
        info.disturbed = rate > DISTURBED_RAD_S;
        Float fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
        info.focus = fd != null ? fd : 0f;
        up.submit(r.buf, info);
        framesDone++;
        if (framesDone >= plan.frames) finishCapture(null);
        else submitNext();
    }

    private void replanAfterClamp(long deliveredNs) {
        abortInFlight();
        plan.limitTo(deliveredNs, settings.durationSec, spec.white);
        try {
            if (up != null) up.abort();
            if (store != null) store.deleteAll();
        } catch (RuntimeException ignored) {
        }
        up = null;
        store = null;
        framesDone = 0;
        try {
            openStore();
        } catch (IOException e) {
            fail("Could not create the session folder: " + e.getMessage());
            return;
        }
        log("camera cut the exposure to " + deliveredNs / 1e9 + " s; new plan: " + plan.describe());
        submitNext();
    }

    private void abortInFlight() {
        Iterator<Map.Entry<Integer, Req>> it = reqs.entrySet().iterator();
        while (it.hasNext()) {
            Req r = it.next().getValue();
            if (r.kind != FRAME) continue;
            if (r.buf != null && up != null) up.releaseBuffer(r.buf);
            r.buf = null;
            it.remove();
        }
        tsToSeq.clear();
        inFlight = 0;
        if (rig != null) rig.abort();
    }

    private void pause() {
        awayTicks = 0;
        startToken++;
        if (framesDone >= plan.frames * 0.85) {
            finishCapture("picked up near the end");
            return;
        }
        abortInFlight();
        phase = Phase.PAUSED;
        pausedAtMs = SystemClock.elapsedRealtime();
        log("paused: phone moved");
        sounds.warn();
        publishCapture();
    }

    private void resume() {
        pausedTotalMs += SystemClock.elapsedRealtime() - pausedAtMs;
        phase = Phase.CAPTURING;
        lastProgressMs = SystemClock.elapsedRealtime();
        log("resumed");
        sounds.tick();
        publishCapture();
        final int tk = ++startToken;
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (tk == startToken) submitNext();
            }
        }, SETTLE_MS);
    }

    private void watchdog() {
        restarts++;
        log("no frame for " + (SystemClock.elapsedRealtime() - lastProgressMs) / 1000 + " s: restarting the camera (" + restarts + ")");
        abortInFlight();
        lastProgressMs = SystemClock.elapsedRealtime();
        if (restarts <= 2) {
            rig.restartSession();
        } else if (restarts <= 4) {
            rig.close();
            rig = new CameraRig(ctx, h, rigListener);
            try {
                rig.open(cameraId, cc, rawSize);
            } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                finishCapture("the camera could not be reopened");
            }
        } else {
            finishCapture("the camera stopped responding");
        }
    }

    private final CameraRig.Listener rigListener = new CameraRig.Listener() {
        @Override
        public void onReady() {
            if (phase == Phase.STARTING) {
                phase = Phase.PLACE;
                publishPlace();
            } else if (phase == Phase.CAPTURING) {
                submitNext();
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
            // Camera2 reports the start of every capture before its image, so the timestamp names the request.
            // Anything else is an image from an aborted or forgotten request.
            Integer seq = tsToSeq.get(img.getTimestamp());
            Req r = seq != null ? reqs.get(seq) : null;
            if (r == null || r.buf != null || r.dropped || r.lost) return;
            if (r.kind == PROBE) {
                if (!Double.isNaN(probeBg)) return;
                probeBg = sampleGreen(img);
                probeArrived();
                return;
            }
            if (up == null) return;
            short[] buf = up.obtainBuffer(4000);
            if (buf == null) {
                log("processing fell behind: frame dropped");
                r.dropped = true;
            } else {
                copyRaw(img, buf);
                r.buf = buf;
            }
            frameArrived(r);
        }

        @Override
        public void onResult(int seq, TotalCaptureResult result) {
            Req r = reqs.get(seq);
            if (r == null) return;
            r.result = result;
            r.arrivalNs = SystemClock.elapsedRealtimeNanos();
            if (r.kind == PROBE) probeArrived();
            else frameArrived(r);
        }

        @Override
        public void onFailed(int seq, boolean imageCaptured) {
            Req r = reqs.get(seq);
            if (r == null) return;
            if (r.kind == PROBE) {
                reqs.remove(seq);
                probe = null;
                if (++probeRetries <= 1 && phase == Phase.COUNTDOWN) {
                    probeSent = false;
                    sendProbe();
                } else {
                    log("test frame failed; using the default plan");
                    probeDone = true;
                }
                return;
            }
            // no result will follow, even if an image was captured: let the request go (a late image is ignored)
            reqs.remove(seq);
            if (r.startNs >= 0) tsToSeq.remove(r.startNs);
            if (r.buf != null && up != null) up.releaseBuffer(r.buf);
            r.buf = null;
            inFlight--;
            framesDropped++;
            log("frame " + seq + " failed");
            ++consecutiveFailures;
            long fallback = Math.max(250_000_000L, advMaxNs);
            if (framesDone == 0 && consecutiveFailures >= 2 && plan.frameExpNs > fallback) {
                // long exposures refused outright: use the longest the camera admits to
                log("long exposures fail; falling back to " + fallback / 1e9 + " s frames");
                prefs.noteExposureClamped(cameraId, plan.frameExpNs, fallback);
                consecutiveFailures = 0;
                firstFrameChecked = false;
                replanAfterClamp(fallback);
            } else if (consecutiveFailures > 8) {
                finishCapture("the camera kept failing");
            } else {
                submitNext();
            }
        }

        @Override
        public void onBufferLost(int seq) {
            Req r = reqs.get(seq);
            if (r == null) return;
            r.lost = true;
            if (r.kind == PROBE) probeArrived();
            else frameArrived(r);
        }

        @Override
        public void onCameraError(String what) {
            log("camera: " + what);
            if (phase == Phase.CAPTURING || phase == Phase.PAUSED) {
                if (framesDone > 0) finishCapture(what);
                else fail("The camera stopped: " + what);
            } else if (phase.ordinal() < Phase.CAPTURING.ordinal()) {
                fail("The camera stopped: " + what);
            }
        }
    };

    /** Copies a RAW16 image into a frame buffer (rows may be padded). */
    static void copyRaw(Image img, short[] dst) {
        Image.Plane pl = img.getPlanes()[0];
        ByteBuffer b = pl.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
        int W = img.getWidth(), H = img.getHeight();
        int rs = pl.getRowStride();
        b.rewind();
        ShortBuffer sb = b.asShortBuffer();
        if (rs == W * 2) {
            sb.get(dst, 0, W * H);
        } else {
            for (int y = 0; y < H; y++) {
                sb.position(y * (rs / 2));
                sb.get(dst, y * W, W);
            }
        }
    }

    private final UnitProcessor.Listener unitListener = new UnitProcessor.Listener() {
        private int n;

        @Override
        public void onUnit(UnitProcessor src, UnitRecord rec, Aligner.Result live, int unitsDone) {
            liveStars = rec.stars;
            liveAligned = live.ok;
            liveStats.add(rec, live, System.currentTimeMillis());
            UnitProcessor.Latest l = src.latest();
            if (l != null && l.index == rec.index) latest = l;
            // refresh the live picture now and then (it is the 1/4 size running stack); every unit for a viewer
            if (++n % 2 == 1 || unitsDone <= 3 || remoteWanted) {
                try {
                    RgbImage img = src.preview().image();
                    if (img != null) {
                        Renderer.Analysis a = Renderer.analyze(img, spec);
                        Renderer.Params p = new Renderer.Params();
                        p.brightness = settings.brightness;
                        int[] px = Renderer.renderSmall(img.binned(1), a, p, 1);
                        preview = Bitmap.createBitmap(px, img.cropWidth(), img.cropHeight(), Bitmap.Config.ARGB_8888);
                        liveAnalysis = a;
                        previewVersion++;
                    }
                } catch (Throwable ignored) {
                }
            }
            latestVersion = rec.index;
        }

        @Override
        public void onError(Throwable t) {
            log("unit processing error: " + t);
            h.post(new Runnable() {
                @Override
                public void run() {
                    // an aborted processor (after a re-plan) also reports here; only the current one counts
                    if ((phase == Phase.CAPTURING || phase == Phase.PAUSED) && up != null && up.failed()) {
                        finishCapture("processing failed");
                    }
                }
            });
        }
    };

    // ------------------------------------------------------------------------------------------------
    // Finish and develop
    // ------------------------------------------------------------------------------------------------

    private void finishCapture(String why) {
        if (phase.ordinal() >= Phase.FINISHING.ordinal()) return;
        if (why != null) log("capture ended early: " + why);
        abortInFlight();
        phase = Phase.FINISHING;
        token++;
        startToken++;
        final int frames = framesDone;
        releaseCamera();
        host.onCaptureEnded();
        if (frames == 0 || up == null || store == null) {
            if (up != null) up.abort();
            if (store != null) store.deleteAll();
            up = null;
            store = null;
            fail("No frames were captured" + (why != null ? " (" + why + ")." : "."));
            return;
        }
        publish("Developing", "Saving the last frames", -1);
        final Sounds snd = sounds;
        snd.chime();
        snd.say(settings.trails ? "Capture done. Making your star trails." : "Capture done. Developing your photo.", null);
        final UnitProcessor[] u = {up};
        final SessionStore st = store;
        final SensorSpec sp = spec;
        final Settings se = settings;
        up = null;
        store = null; // the develop thread owns it now; a later cancel must not delete it
        new Thread(new Runnable() {
            @Override
            public void run() {
                u[0].finish();
                u[0] = null; // its buffers are big: let them go before developing
                latest = null; // the viewer keeps its last picture; developing needs the memory
                try {
                    st.setState("captured");
                } catch (IOException ignored) {
                }
                develop(st, sp, se.trails, se.brightness, se.saveDng, snd);
            }
        }, "develop").start();
    }

    private void doRecover(final File dir) {
        if (phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.FAILED) return;
        final SessionStore st = new SessionStore(dir);
        final Properties p;
        final SensorSpec sp;
        try {
            p = st.readSettings();
            sp = SensorSpec.fromProperties(p);
        } catch (Exception e) {
            fail("That session cannot be read: " + e.getMessage());
            return;
        }
        output = null; // free the last result's memory before developing another
        small = null;
        result = null;
        preview = null;
        resetCapture();
        settings = new Settings();
        settings.trails = "trails".equals(p.getProperty("mode"));
        settings.brightness = parseFloat(p.getProperty("brightness"), prefs.brightness());
        settings.saveDng = !"false".equals(p.getProperty("saveDng"));
        settings.sounds = false;
        token++;
        phase = Phase.FINISHING;
        publish("Developing", "Picking up the unfinished session", -1);
        final Settings se = settings;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    st.setState("captured");
                } catch (IOException ignored) {
                }
                develop(st, sp, se.trails, se.brightness, se.saveDng, null);
            }
        }, "develop").start();
    }

    private static float parseFloat(String s, float def) {
        try {
            return s == null ? def : Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Runs on the develop thread. */
    private void develop(final SessionStore st, final SensorSpec sp, final boolean trails, final float brightness,
                         final boolean saveDng, final Sounds snd) {
        final long t0 = SystemClock.elapsedRealtime();
        post(Phase.DEVELOPING, "Developing", trails ? "Building the star trails" : "Lining up the stars", 0f);
        Progress progress = new Progress() {
            long last;

            @Override
            public void update(String stage, float fraction) {
                long now = SystemClock.elapsedRealtime();
                if (now - last < 300 && fraction < 1f) return;
                last = now;
                post(Phase.DEVELOPING, "Developing", stage, fraction);
            }

            @Override
            public boolean cancelled() {
                return cancelDevelop;
            }
        };
        try {
            PostProcessor pp = new PostProcessor(st, sp);
            try {
                // the rejection chosen when the session started, so redeveloping a session gives the same result
                pp.rejection = Rejection.Method.parse(st.readSettings().getProperty("rejection"));
            } catch (IOException e) {
                st.log("could not read the session settings: " + e.getMessage());
            }
            PostProcessor.Output o = pp.run(trails, progress);
            String name = "NightSky_" + st.id() + (trails ? "_trails" : "");
            post(Phase.DEVELOPING, "Saving", "Full-size photo", -1f);
            Uri jpeg = saveJpeg(o, sp, brightness, name);
            Uri dng = null;
            if (saveDng) {
                post(Phase.DEVELOPING, "Saving", "RAW (DNG) copy", -1f);
                dng = saveDng(o, sp, name);
            }
            st.setState("developed");
            st.log(String.format(Locale.US, "developed in %.1f s: %s", (SystemClock.elapsedRealtime() - t0) / 1000.0, o.summary));
            Uri log = null;
            try {
                log = MediaSaver.saveText(ctx, name + "_log.txt", st.readLog());
            } catch (IOException | RuntimeException ignored) {
            }
            // the photo is saved, so the frames (gigabytes for long sessions) can go
            st.deleteBulk();
            // on-screen copy, about 1600 pixels wide, kept with the stack for brightness changes
            int f = Math.max(1, (int) Math.ceil(o.analysis.cw / 1600.0));
            RgbImage sm = o.image.binned(f);
            Renderer.Params rp = new Renderer.Params();
            rp.brightness = brightness;
            int[] px = Renderer.renderSmall(sm, o.analysis, rp, f);
            final Bitmap bmp = Bitmap.createBitmap(px, sm.cropWidth(), sm.cropHeight(), Bitmap.Config.ARGB_8888);
            final Result r = new Result();
            r.summary = o.summary;
            r.name = name;
            r.jpeg = jpeg;
            r.dng = dng;
            r.log = log;
            r.canAdjust = true;
            r.brightness = brightness;
            prefs.setLastResultUri(jpeg.toString());
            final PostProcessor.Output out = o;
            final RgbImage smf = sm;
            final int ff = f;
            h.post(new Runnable() {
                @Override
                public void run() {
                    output = out;
                    outSpec = sp;
                    small = smf;
                    smallFactor = ff;
                    result = r;
                    preview = bmp;
                    previewVersion++;
                    phase = Phase.DONE;
                    publish(trails ? "Your star trails are ready" : "Your photo is ready", r.summary, -1);
                    if (snd != null) {
                        snd.say(spoken(out), new Runnable() {
                            @Override
                            public void run() {
                                snd.release();
                            }
                        });
                    }
                    host.onIdle();
                }
            });
        } catch (final CancelledException e) {
            st.log("developing cancelled");
            h.post(new Runnable() {
                @Override
                public void run() {
                    phase = Phase.IDLE;
                    publish("Cancelled", "The frames are kept: you can develop them later from the start screen.", -1);
                    if (snd != null) snd.release();
                    host.onIdle();
                }
            });
        } catch (final Throwable t) {
            st.log("developing failed: " + t);
            for (StackTraceElement el : t.getStackTrace()) st.log("  at " + el);
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (snd != null) snd.release();
                    fail("Developing failed: " + (t instanceof OutOfMemoryError ? "out of memory" : t.getMessage())
                            + ". The frames are kept; try again from the start screen.");
                }
            });
        }
    }

    private static String spoken(PostProcessor.Output o) {
        if (o.unitsTotal > 0 && o.unitsUsed < o.unitsTotal) {
            return String.format(Locale.US, "Done. %d of %d frames kept.", o.unitsUsed, o.unitsTotal);
        }
        return "Done. Your photo is ready.";
    }

    private Uri saveJpeg(PostProcessor.Output o, SensorSpec sp, float brightness, String name) throws IOException {
        final int cw = o.analysis.cw, ch = o.analysis.ch;
        final Bitmap bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        try {
            Renderer.Params p = new Renderer.Params();
            p.brightness = brightness;
            Renderer.render(o.image, o.analysis, p, new Renderer.RowSink() {
                @Override
                public void rows(int y, int count, int[] argb, int width) {
                    synchronized (bmp) {
                        bmp.setPixels(argb, 0, width, 0, y, width, count);
                    }
                }
            }, null);
            MediaSaver.Exif ex = new MediaSaver.Exif();
            ex.make = sp.deviceMake;
            ex.model = sp.deviceModel;
            ex.description = o.summary;
            ex.exposureSeconds = o.integrationSeconds;
            ex.iso = sp.iso;
            ex.fNumber = sp.fNumber;
            ex.focalLengthMm = sp.focalLengthMm;
            ex.width = cw;
            ex.height = ch;
            return MediaSaver.saveJpeg(ctx, bmp, name, ex);
        } finally {
            bmp.recycle();
        }
    }

    private Uri saveDng(final PostProcessor.Output o, final SensorSpec sp, String name) throws IOException {
        final DngWriter.Meta meta = new DngWriter.Meta();
        meta.exposureSeconds = o.integrationSeconds;
        meta.iso = sp.iso;
        meta.description = o.summary;
        meta.time = new Date();
        return MediaSaver.saveDng(ctx, name, meta.time, new MediaSaver.Writer() {
            @Override
            public void write(OutputStream os) throws IOException {
                DngWriter.write(os, o.image, sp, meta, o.maxGain);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Cancel, fail, release
    // ------------------------------------------------------------------------------------------------

    private void cancelAll() {
        token++;
        startToken++;
        abortInFlight();
        releaseCapture();
        if (up != null) up.abort();
        if (store != null) store.deleteAll();
        up = null;
        store = null;
        phase = Phase.IDLE;
        publish("", "", -1);
        host.onCaptureEnded();
        host.onIdle();
    }

    private void fail(String msg) {
        token++;
        startToken++;
        if (store != null) store.log("failed: " + msg);
        releaseCapture();
        if (up != null) {
            up.abort();
            up = null;
        }
        store = null;
        phase = Phase.FAILED;
        publish("Something went wrong", msg, -1);
        host.onCaptureEnded();
        host.onIdle();
    }

    private void releaseCamera() {
        if (rig != null) {
            rig.close();
            rig = null;
        }
        if (motion != null) {
            motion.stop();
            motion = null;
        }
    }

    private void releaseCapture() {
        releaseCamera();
        if (sounds != null && phase != Phase.DEVELOPING) {
            sounds.release();
            sounds = null;
        }
    }

    private void log(String s) {
        if (store != null) store.log(s);
        android.util.Log.i("NightSky", s);
    }

    // ------------------------------------------------------------------------------------------------
    // Status
    // ------------------------------------------------------------------------------------------------

    private void publishCapture() {
        long now = SystemClock.elapsedRealtime();
        long paused = pausedTotalMs + (phase == Phase.PAUSED ? now - pausedAtMs : 0);
        long elapsed = Math.max(0, now - captureStartMs - paused);
        long remaining = Math.max(0, (plan.frames - framesDone) * plan.frameExpNs / 1_000_000L);
        String head = phase == Phase.PAUSED ? "Paused: lay the phone back down" : (settings.trails ? "Capturing star trails" : "Capturing");
        String detail = String.format(Locale.US, "Frame %d of %d  ·  %s left", Math.min(plan.frames, framesDone + (phase == Phase.CAPTURING ? 1 : 0)),
                plan.frames, clock(remaining));
        if (liveStars > 0) detail += String.format(Locale.US, "  ·  %d stars%s", liveStars, liveAligned ? "" : " (not lined up)");
        Status s = base();
        s.headline = head;
        s.detail = detail;
        s.done = framesDone;
        s.planned = plan.frames;
        s.elapsedMs = elapsed;
        s.remainingMs = remaining;
        s.progress = plan.frames > 0 ? framesDone / (float) plan.frames : 0f;
        commit(s);
        lastPublishMs = now;
    }

    static String clock(long ms) {
        long s = (ms + 999) / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void publish(String headline, String detail, float progress) {
        Status s = base();
        s.headline = headline;
        s.detail = detail;
        s.progress = progress;
        commit(s);
    }

    /** Publishes the current state again (after a setting changed). */
    private void republish() {
        if (phase == Phase.CAPTURING || phase == Phase.PAUSED) {
            publishCapture();
        } else {
            Status cur = status;
            publish(cur.headline, cur.detail, cur.progress);
        }
    }

    /** From the develop thread. */
    private void post(final Phase ph, final String headline, final String detail, final float progress) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (phase != Phase.FINISHING && phase != Phase.DEVELOPING) return;
                phase = ph;
                publish(headline, detail, progress);
            }
        });
    }

    private Status base() {
        Status s = new Status();
        s.phase = phase;
        s.trails = settings != null && settings.trails;
        s.previewVersion = previewVersion;
        s.result = result;
        if (plan != null) {
            s.planned = plan.frames;
            s.done = framesDone;
            s.frameExpNs = plan.frameExpNs;
            s.iso = plan.iso;
        }
        s.forced = forced;
        s.muted = muted;
        s.soundsOn = settings != null && settings.sounds;
        s.latestVersion = latestVersion;
        return s;
    }

    private void commit(Status s) {
        s.version = ++statusVersion;
        status = s;
        host.onStatus(s);
    }

    /** Unfinished sessions (frames on disk, never developed), newest first. */
    static List<File> unfinished(Context c) {
        List<File> out = new ArrayList<File>();
        for (SessionStore s : SessionStore.list(sessionsRoot(c))) {
            String st = s.state();
            if (!"capturing".equals(st) && !"captured".equals(st)) continue;
            try {
                if (!s.readUnits().isEmpty()) out.add(s.dir);
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    static int unitCount(File dir) {
        try {
            return new SessionStore(dir).readUnits().size();
        } catch (IOException e) {
            return 0;
        }
    }

    static void delete(File dir) {
        new SessionStore(dir).deleteAll();
    }
}
