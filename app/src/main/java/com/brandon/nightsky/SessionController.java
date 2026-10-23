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
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.CancelledException;
import com.brandon.nightsky.processing.DngWriter;
import com.brandon.nightsky.processing.FocusMeter;
import com.brandon.nightsky.processing.FocusModel;
import com.brandon.nightsky.processing.FocusSweep;
import com.brandon.nightsky.processing.FocusWatch;
import com.brandon.nightsky.processing.LiveStats;
import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.Preset;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Rejection;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatOverlay;
import com.brandon.nightsky.processing.SatPlanner;
import com.brandon.nightsky.processing.SatPredict;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.Site;
import com.brandon.nightsky.processing.SkyGuide;
import com.brandon.nightsky.processing.SkyReading;
import com.brandon.nightsky.processing.Stats;
import com.brandon.nightsky.processing.Tonight;
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
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One night-sky session from start to finish:
 * <ol>
 * <li>open the camera and wait until the phone lies screen-down and perfectly still,</li>
 * <li>focus on the stars (A5): a few short frames at focus settings near infinity, the stars' size measured in
 * each and the sharpest setting fitted, centred on what the daylight calibration predicts for the phone's
 * temperature (F7), or a two-frame check of the focus found last time,</li>
 * <li>check the sky ({@link Preflight}): a short probe measures the sky brightness and reads the lens shading
 * map, a test frame at the session's settings is read for a smudged lens, obstructions, clouds, the Sun and
 * the Moon, and one spoken sentence says what it found; after a warning there is time to pick the phone up
 * and fix it (putting it back down checks again),</li>
 * <li>count down with beeps, then settle,</li>
 * <li>capture RAW exposures back to back (two queued), each handed to the {@link UnitProcessor} which stores
 * it on disk at once; pause if the phone is picked up, restart the camera if it stalls,</li>
 * <li>develop: align and stack ({@link PostProcessor}), render, save JPEG, DNG and log.</li>
 * </ol>
 * A sky reading ({@link Settings#readingOnly}) takes three test frames and stops: how dark the sky is, said aloud
 * and added to the readings' log, and no photo.
 * Everything except developing runs on one thread, the camera's callbacks included.
 */
final class SessionController {
    enum Phase { IDLE, STARTING, PLACE, STEADY, FOCUS, PREFLIGHT, COUNTDOWN, CAPTURING, PAUSED, FINISHING, DEVELOPING, DONE, FAILED }

    static final class Settings {
        boolean trails;
        int durationSec = 300;
        float brightness = 0.5f;
        boolean sounds = true;
        boolean saveDng = true;
        int countdownSec = 5;
        /** Check the sky with a test frame before the countdown. */
        boolean preflight = true;
        /** Started from the remote viewer: keep the viewer's mute setting. */
        boolean fromViewer;
        /** Keep satellite streaks (and meteors) in the picture, and count bright passes down aloud. */
        boolean keepSatellites;
        /** Start no earlier than this (Unix ms; 0 for as soon as it's ready): the quietest stretch, chosen on the home screen. */
        long startAtMs;
        /** Why it starts at {@link #startAtMs}, for the screen and the log; empty for the quietest stretch for satellites. */
        String startWhy = "";
        /**
         * Part of a night's plan (E2): nobody is watching, so it doesn't wait for anyone after a warning, it gives up
         * if the phone isn't lying still, and how it ended goes back to the plan ({@link Status#outcome}).
         */
        boolean scheduled;
        /** Start only if the test frame shows enough stars; otherwise end with {@link NightPlan.Outcome#CLOUDY}. */
        boolean onlyIfClear;
        /** Share of the catalogue's stars this site shows on a clear night (NaN: not known yet), for "only if clear". */
        double clearBaseline = Double.NaN;
        /** Stop capturing at this time (Unix ms; 0: no end time). */
        long endAtMs;
        /** Stop when the Sun climbs above 18 degrees below the horizon. */
        boolean stopAtDawn;
        /** Stop capturing below this battery level (%), and don't start below it when scheduled. */
        int minBatteryPct = 5;
        /** Give up waiting for the phone to lie still and screen-down after this long (ms; 0: wait for ever). */
        long placeTimeoutMs;
        /** Keep capturing until this many seconds of clear frames are in (0: off), for at most maxDurationSec. */
        int clearTargetSec, maxDurationSec;
        /** The preset or plan entry it came from, for the log ("" if none). */
        String origin = "";
        /** A sky reading (H3): pre-flight test frames only, read for the sky's brightness; no photo. */
        boolean readingOnly;
        /** Outlier rejection (a Rejection.Method name; AUTO chooses from the session). */
        String rejection = "AUTO";
        /** Also save the cloud-blur version: the plain average, where drifting clouds turn into soft streaks (C7). */
        boolean blend;
        /** Quick developing (Quick look): one stacking pass and a half-size photo. */
        boolean quick;
        /** The preset these settings are ("" if none), and its name. */
        String presetId = "", presetName = "";
        /**
         * The saved settings, not ones asked for (a shortcut's, a preset's or the night plan's): a saved site's preset
         * may take their place.
         */
        boolean usesSaved;
        /** The saved site the session is at ("" if none, or not known yet), and its name (E6). */
        String siteId = "", siteName = "";
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
        /** The pre-flight check's findings for this session (null before it has run). */
        Preflight.Report preflight;
        /** Failed for want of storage: the screen offers to free some. */
        boolean storageShort;
        /** How the last session or attempt ended (null while one runs, or before the first), and the details. */
        NightPlan.Outcome outcome;
        String outcomeDetail = "";
        /** Part of a night's plan. */
        boolean scheduled;
        /** A sky reading's result (null unless one just finished). */
        SkyReading reading;
        /** The saved site and preset, as said at the start ("Backyard site, Backyard preset."; "" if not at one). */
        String siteLine = "";
    }

    static final class Result {
        /** The photo's file name (without extension), and the session folder's name. */
        String summary = "", name = "", session = "";
        /** When the capture began (Unix ms, 0 if unknown), how long it ran (s) and whether it was star trails. */
        long startedMs;
        int durationSec;
        boolean trails;
        Uri jpeg, dng, log;
        boolean canAdjust;
        float brightness;
        /** The session's report card (null if it couldn't be made), and the same as JSON for the viewer. */
        ReportCard report;
        String reportJson;
        /** Satellite tracks over the picture (empty if none), and whether the sky's names can be drawn. */
        List<SatOverlay.Line> tracks = new ArrayList<SatOverlay.Line>();
        boolean canLabel;
        /** The full-size copy with the tracks and names, once saved. */
        volatile Uri labelled;
        /** The cloud-blur version (C7), if one was saved. */
        Uri blend;
        /** Photo pixels per JPEG pixel (2 for Quick look's half-size photo). */
        int jpegFactor = 1;
    }

    /** What to draw over a saved photo. */
    private static final class Decor {
        List<SatOverlay.Line> tracks;
        SkyGuide sky;
        boolean showTracks, showLabels;
    }

    interface Host {
        void onStatus(Status s);

        /** Capture is over (the camera is closed); the phone may be picked up. */
        void onCaptureEnded();

        /** Developing finished or failed; nothing is running any more. */
        void onIdle();
    }

    private static final int PROBE = 1, FRAME = 2, TEST = 3, FOCUS = 4, FOCUS_CHECK = 5;
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
    /** The pre-flight test frame is the session's frame length, at most this long. */
    private static final long TEST_MAX_NS = 8_000_000_000L;
    /** After a pre-flight warning: time to pick the phone up before the countdown starts. */
    private static final long GRACE_MS = 10_000L;
    /** Pre-flight steps: waiting for the probe, the test frame, reading it, speaking, waiting after a warning. */
    private static final int PF_PROBE = 1, PF_TEST = 2, PF_READ = 3, PF_SPEAK = 4, PF_GRACE = 5, PF_WAIT = 6;
    /** Longest the focus step may take before the session goes on with the best so far (ms). */
    private static final long FOCUS_MAX_MS = 60_000L;

    private static final class Req {
        final int seq, kind;
        final long expNs;
        /** The focus asked for (focus frames). */
        float focus = Float.NaN;
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
    /** The result's preview without the tracks and names drawn on it. */
    private Bitmap previewBase;
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

    // pre-flight check
    private int pfStep, preflightToken, testRetries;
    private Req testReq;
    private short[] testBuf;
    private long testSentMs, graceUntilMs;
    private Preflight.Report preflightReport;
    private boolean startedAfterWarnings, storageShort;
    /** A sky reading's frames so far, and the reading made from them (null if none). */
    private final List<Preflight.Report> readingReports = new ArrayList<Preflight.Report>();

    // focus on the stars (A5) and what the daylight calibration (F7) knows
    /** The lens's focus control (null: a fixed-focus lens), where it has focused before, and tonight's sweep. */
    private FocusSweep.Lens lens;
    private FocusModel focusModel;
    private FocusSweep sweep;
    private Req focusReq;
    private short[] focusBuf;
    private int focusToken, focusRetries, focusPointTries;
    private long focusStartMs, focusSentMs;
    private boolean focusMeasuring, focusDone;
    /** The focus the capture uses, how it was chosen (for the report), and the battery's temperature then. */
    private float focusValue;
    private String focusHow = "";
    private double focusTempC = Double.NaN, focusPredicted = Double.NaN;
    private FocusSweep.Result focusResult;
    /** The focus decisions made before the session folder exists, for its log. */
    private String focusLog = "";
    /** Long sessions (F7): the stars' size watched, a two-frame check between frames when they swell steadily. */
    private final FocusWatch focusWatch = new FocusWatch();
    private boolean focusCheckDue;
    private String focusCheckWhy = "", focusHaze = "", focusChecks = "";
    /** 0: no check running; 1: the frame below the focus is out; 2: the one above. */
    private int focusCheckStep;
    private Req checkReq;
    private short[] checkBuf;
    private long checkSentMs, focusCheckDueMs;
    private double checkLowF, checkLowH, checkLowN;
    private SkyReading reading;
    /** How the last session or attempt ended, for the plan (null while one runs). */
    private NightPlan.Outcome outcome;
    private String outcomeDetail = "";
    /** Scheduled: when the wait for the phone to lie still began (0: not waiting), and whether it was said. */
    private long placeSinceMs;
    private boolean movedSaid;
    /** Scheduled starts and stop conditions, for the session's log. */
    private String planLog = "";
    /** Why the capture stopped before its planned end, for the report (empty if it didn't). */
    private String stopReason = "";
    private long stopCheckMs, sunCheckMs, batteryCheckMs;
    /**
     * Waiting to start (a bright satellite to let pass, or the stretch chosen on the home screen): the countdown
     * begins at this time (Unix ms), so that the capture starts when planned; why, for the screen.
     */
    private long waitUntilWallMs;
    private String waitWhy = "";
    /** For the session's log: the wait, and the satellite data's state when the session started. */
    private String waitLog = "", satDataLog = "";
    /** The satellite data for this session (null until read, or none), and the passes to count down when kept. */
    private SatCatalog satCatalog;
    private final List<SatPredict.Pass> countdownPasses = new ArrayList<SatPredict.Pass>();
    private int countdownNext, countdownSaid = Integer.MAX_VALUE;
    /** Place, clock and compass of this session. */
    private SessionSky sky = new SessionSky();
    private Locator.Place place;
    private CancellationSignal locating;
    /** Whether the saved site has been looked for (once a session), what was decided (for the log) and said. */
    private boolean siteChecked;
    private String siteLog = "", siteLine = "";
    /** A place fix this fresh (minutes) is good enough to look for the saved site at the start. */
    private static final double SITE_FRESH_MIN = 30;
    /** Battery at the start of capture (-1: unknown or charging), for learning how fast a session drains it. */
    private int batteryAtStart = -1;
    private long batteryAtStartMs;

    // capture loop
    private int seqCounter;
    private final Map<Integer, Req> reqs = new HashMap<Integer, Req>();
    private final Map<Long, Integer> tsToSeq = new HashMap<Long, Integer>();
    private int inFlight, framesDone, framesDropped, consecutiveFailures, restarts, awayTicks;
    private boolean firstFrameChecked;
    private long captureStartMs, lastProgressMs, pausedAtMs, pausedTotalMs;
    /** When the capture began by the wall clock (for Repeat tomorrow), 0 before it has. */
    private long captureStartWallMs;
    /** The hottest the phone said it got during the capture (PowerManager thermal status), and when it first
     *  got severely hot (Unix ms, 0 if never). */
    private int thermalMax;
    private long thermalSevereAtMs, thermalCheckedMs;
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
                previewBase = Bitmap.createBitmap(px, small.cropWidth(), small.cropHeight(), Bitmap.Config.ARGB_8888);
                preview = decorate(previewBase);
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
                            r.jpeg = saveJpeg(o, sp, brightness, name, null, r.jpegFactor);
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

    /** Draws the preview again with the tracks and names as the switches now say. */
    void redraw() {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (previewBase == null || result == null) return;
                preview = decorate(previewBase);
                previewVersion++;
                Status cur = status;
                publish(cur.headline, cur.detail, -1);
            }
        });
    }

    /**
     * Saves a full-size copy with the satellites' tracks and the sky's names drawn on it (as the switches say),
     * beside the photo, at the brightness shown.
     */
    void saveLabelled() {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (output == null || result == null || phase != Phase.DONE) return;
                final Result r = result;
                final Decor d = new Decor();
                d.tracks = r.tracks;
                d.sky = r.report != null ? r.report.sky : null;
                d.showTracks = prefs.showTracks();
                d.showLabels = prefs.showLabels();
                if (!TrackPainter.anything(d.tracks, d.sky, d.showTracks, d.showLabels)) return;
                phase = Phase.DEVELOPING;
                publish("Saving", "Full-size copy with the names", -1);
                final PostProcessor.Output o = output;
                final SensorSpec sp = outSpec;
                final float brightness = r.brightness;
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String err = null;
                        try {
                            r.labelled = saveJpeg(o, sp, brightness, r.name + "_labelled", d, r.jpegFactor);
                        } catch (Throwable t) {
                            err = "Could not save: " + (t instanceof OutOfMemoryError ? "out of memory" : t.getMessage());
                        }
                        final String e = err;
                        h.post(new Runnable() {
                            @Override
                            public void run() {
                                phase = Phase.DONE;
                                publish(e == null ? "Saved the copy with names" : "Not saved", e == null ? r.summary : e, -1);
                            }
                        });
                    }
                }, "save").start();
            }
        });
    }

    /** The preview with the satellites' tracks and the sky's names, as the result screen's switches say. */
    private Bitmap decorate(Bitmap base) {
        Result r = result;
        PostProcessor.Output o = output;
        if (base == null || r == null || o == null) return base;
        try {
            return TrackPainter.paint(base, o.analysis.cx0, o.analysis.cy0, smallFactor, r.tracks,
                    r.report != null ? r.report.sky : null, prefs.showTracks(), prefs.showLabels());
        } catch (RuntimeException | OutOfMemoryError e) {
            android.util.Log.w("NightSky", "could not draw the tracks and names: " + e);
            return base;
        }
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
                    reading = null;
                    preview = null;
                    previewBase = null;
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
                    case FOCUS:
                    case PREFLIGHT:
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
        previewBase = null;
        if (up != null) up.abort();
        up = null;
        store = null;
        settings = s;
        if (!s.fromViewer) muted = false;
        token++;
        resetCapture();
        outcome = null;
        outcomeDetail = "";
        phase = Phase.STARTING;
        publish(s.scheduled ? "Scheduled session: getting the camera ready" : s.readingOnly ? "Sky reading: getting the camera ready"
                : "Getting the camera ready", "", -1);
        if (s.scheduled) {
            BatteryInfo b = BatteryInfo.read(ctx);
            if (!b.charging && b.pct >= 0 && b.pct < s.minBatteryPct) {
                endAttempt(NightPlan.Outcome.LOW_BATTERY, String.format(Locale.US, "the battery was at %d%%, below the %d%% set for scheduled sessions",
                        b.pct, s.minBatteryPct));
                return;
            }
        }
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
            // where we are: the frame length depends on the latitude (the zenith's declination)
            place = Locator.best(ctx, prefs);
            sky = new SessionSky();
            setPlace(place);
            if (realtimeClock) setClock(System.currentTimeMillis(), SystemClock.elapsedRealtimeNanos());
            plan = ExposurePlan.make(spec.model.fx, place != null ? place.latDeg : ExposurePlan.DEFAULT_LATITUDE, s.durationSec, advMax,
                    prefs.verifiedExposureNs(cameraId), prefs.clampDeliveredNs(cameraId), isoStart, isoMin, spec.white);
            // the focus to start from: tonight's sweep, if there is one, refines it
            lens = focusLens(cc, spec);
            focusModel = FocusModel.parse(prefs.focusModel(cameraId));
            focusTempC = BatteryInfo.read(ctx).tempC;
            focusValue = initialFocus();
            // a reading keeps no frames
            String space = s.readingOnly ? null : checkSpace();
            if (space != null && s.scheduled) {
                // nobody is there to free space: a shorter session that fits beats none
                int fits = secondsThatFit();
                if (fits >= 300) {
                    planLog += (planLog.isEmpty() ? "" : "\n") + String.format(Locale.US,
                            "decision: shortened from %d to %d minutes to fit the free storage (%.1f GB)", s.durationSec / 60, fits / 60,
                            freeBytes() / 1e9);
                    s.durationSec = fits;
                    plan.setDuration(fits);
                    space = null;
                }
            }
            if (space != null) {
                storageShort = true;
                fail(space);
                return;
            }
            // a fresh fix while the phone is put down (it is only for this session's records and checks)
            final int tk = token;
            locating = Locator.refresh(ctx, prefs, h, 30_000L, new Locator.Listener() {
                @Override
                public void onPlace(Locator.Place p) {
                    locating = null;
                    if (tk != token || p == null || phase.ordinal() >= Phase.CAPTURING.ordinal()) return;
                    place = p;
                    setPlace(p);
                    if (phase.ordinal() < Phase.PREFLIGHT.ordinal()) checkSite("with a fresh fix", true);
                }
            });
            cleanupOldSessions();
            satCatalog = null;
            satDataLog = "";
            if (!s.trails && !s.readingOnly) loadSatellites(tk);
            motion = new MotionMonitor(ctx);
            motion.start();
            sounds = new Sounds(ctx, s.sounds);
            sounds.setMuted(muted);
            // a recent fix that finds a saved site settles it straight away (else the fresh fix will)
            if (place != null && place.ageMinutes() <= SITE_FRESH_MIN) checkSite("at the start", false);
            rig = new CameraRig(ctx, h, rigListener);
            rig.setFocus(focusValue);
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

    /** The lens's focus control, or null for a lens with a fixed focus (also the daylight calibration's). */
    static FocusSweep.Lens focusLens(CameraCharacteristics c, SensorSpec sp) {
        Float mf = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
        if (mf == null || mf <= 0) return null;
        Float hyper = c.get(CameraCharacteristics.LENS_INFO_HYPERFOCAL_DISTANCE);
        Integer cal = c.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        boolean calibrated = cal != null && cal != CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED;
        return FocusSweep.Lens.of(mf, hyper != null ? hyper : 0, calibrated, sp.focalLengthMm, sp.fNumber, sp.pixelPitchUm);
    }

    /**
     * The focus to start with, before or without a sweep: what the daylight calibration predicts for the
     * phone's temperature, else the focus found on the stars last time, else the lens's own infinity (0).
     */
    private float initialFocus() {
        if (lens == null) {
            focusHow = "fixed";
            return 0f;
        }
        FocusModel.Line l = focusModel.line();
        if (l != null && !Double.isNaN(focusTempC)) {
            focusHow = "predicted";
            return (float) Stats.clamp(l.predict(focusTempC), 0, lens.max + 2 * lens.fine);
        }
        FocusModel.Cal last = focusModel.lastNight();
        if (last != null) {
            focusHow = "saved";
            return (float) last.focus;
        }
        focusHow = "infinity";
        return 0f;
    }

    /**
     * Reads the satellite data for the check before the session (thousands of Starlinks take a moment, so on
     * a thread of its own), then fetches newer data if it is due and reads that.
     */
    private void loadSatellites(final int tk) {
        if (!prefs.satelliteData()) {
            satDataLog = "satellite data: off in the settings";
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                long t0 = SystemClock.elapsedRealtime();
                SatCatalog cat = null;
                String line;
                try {
                    cat = Satellites.catalogNow(ctx);
                    line = Satellites.data(ctx).status(System.currentTimeMillis());
                } catch (RuntimeException e) {
                    line = "could not be read (" + e + ")";
                }
                handOver(tk, cat, String.format(Locale.US, "satellite data: %s (read in %d ms)", line, SystemClock.elapsedRealtime() - t0));
                String fetched = Satellites.refreshNow(ctx, false);
                if (fetched.isEmpty()) return;
                try {
                    cat = Satellites.catalogNow(ctx);
                } catch (RuntimeException e) {
                    fetched += "; could not be read (" + e + ")";
                }
                handOver(tk, cat, fetched);
            }
        }, "satellites").start();
    }

    private void handOver(final int tk, final SatCatalog cat, final String line) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (tk != token) return;
                if (cat != null) satCatalog = cat;
                satDataLog = satDataLog.isEmpty() ? line : satDataLog + "\n" + line;
                android.util.Log.i("NightSky", line);
            }
        });
    }

    /** Storage the session's frames need (bytes): its units, two spare, and room to develop. */
    private long needBytes() {
        long unitBytes = 2L * spec.width * spec.height;
        int units = (plan.frames + plan.framesPerUnit - 1) / plan.framesPerUnit;
        return unitBytes * (units + 2) + (400L << 20);
    }

    /** The longest session the free storage holds (seconds), for a scheduled session that doesn't fit. */
    private int secondsThatFit() {
        long free = freeBytes();
        if (free < 0) return settings.durationSec;
        long unitBytes = 2L * spec.width * spec.height;
        long units = (free - (400L << 20)) / unitBytes - 2;
        if (units <= 0) return 0;
        return (int) Math.min(settings.durationSec, units * plan.framesPerUnit * plan.frameExpNs / 1_000_000_000L);
    }

    private long freeBytes() {
        try {
            return new StatFs(root.getAbsolutePath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** Null if there is room for the session's frames, else a message. */
    private String checkSpace() {
        long need = needBytes();
        long free = freeBytes();
        if (free < 0) return null;
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
        captureStartWallMs = 0;
        inFlight = 0;
        framesDone = 0;
        framesDropped = 0;
        thermalMax = 0;
        thermalSevereAtMs = 0;
        thermalCheckedMs = 0;
        consecutiveFailures = 0;
        restarts = 0;
        awayTicks = 0;
        firstFrameChecked = false;
        probeSent = false;
        probeDone = false;
        probeRetries = 0;
        probe = null;
        probeBg = Double.NaN;
        pfStep = 0;
        preflightToken++;
        testReq = null;
        testBuf = null;
        testRetries = 0;
        focusToken++;
        sweep = null;
        focusReq = null;
        focusBuf = null;
        focusRetries = 0;
        focusPointTries = 0;
        focusMeasuring = false;
        focusDone = false;
        focusResult = null;
        focusPredicted = Double.NaN;
        focusLog = "";
        focusCheckDue = false;
        focusCheckWhy = "";
        focusHaze = "";
        focusChecks = "";
        focusCheckStep = 0;
        checkReq = null;
        checkBuf = null;
        preflightReport = null;
        readingReports.clear();
        reading = null;
        startedAfterWarnings = false;
        storageShort = false;
        siteChecked = false;
        siteLog = "";
        siteLine = "";
        placeSinceMs = 0;
        movedSaid = false;
        planLog = "";
        stopReason = "";
        stopCheckMs = 0;
        sunCheckMs = 0;
        batteryCheckMs = 0;
        waitUntilWallMs = 0;
        waitWhy = "";
        waitLog = "";
        countdownPasses.clear();
        countdownNext = 0;
        countdownSaid = Integer.MAX_VALUE;
        batteryAtStart = -1;
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
                } else if (settings.scheduled) {
                    placeWait(now);
                }
                break;
            case STEADY:
                if (!forced && !motion.faceDown()) {
                    phase = Phase.PLACE;
                    publishPlace();
                } else if (forced ? motion.quietNs() >= FORCED_STILL_NS : motion.stillNs() >= STILL_NS) {
                    placeSinceMs = 0;
                    // the settings are used from here on: the saved site, with whatever place there is
                    checkSite("when the phone was put down", true);
                    if (wantsFocus()) startFocus(now);
                    else afterSteady(now);
                } else if (settings.scheduled) {
                    placeWait(now);
                }
                break;
            case FOCUS:
                if (forced) {
                    stopFocus(now, "start now from the viewer");
                    break;
                }
                if (!motion.faceDown() || motion.rate() > 0.15f) {
                    // picked up: focus again once it is back down
                    abandonFocus();
                    phase = motion.faceDown() ? Phase.STEADY : Phase.PLACE;
                    if (phase == Phase.PLACE) publishPlace();
                    else publish("Hold still", "Focusing once the phone is still", -1);
                    return;
                }
                if (!probeDone && probeSent && now - probeSentMs > 12000) {
                    log("test frame timed out");
                    forgetProbe();
                    probeDone = true;
                }
                if (now - focusStartMs > FOCUS_MAX_MS) {
                    stopFocus(now, "it was taking too long");
                } else if (focusReq != null && now - focusSentMs > focusReq.expNs / 1_000_000L + 10_000L) {
                    forgetFocus();
                    if (++focusRetries <= 2) sendFocus(now);
                    else stopFocus(now, "the focus frames stopped coming");
                } else if (probeDone && focusReq == null && !focusMeasuring) {
                    sendFocus(now);
                }
                break;
            case PREFLIGHT:
                if (forced && !settings.readingOnly) {
                    // "Start now" from the viewer: no more checking or waiting
                    skipPreflight(now, pfStep == PF_WAIT ? "start now from the viewer, without waiting"
                            : "skipped the rest of the check: start now from the viewer");
                    break;
                }
                if (!motion.faceDown() || motion.rate() > 0.15f) {
                    // picked up (to wipe the lens, or move away from a branch): check again once it is back down
                    abandonPreflight();
                    phase = motion.faceDown() ? Phase.STEADY : Phase.PLACE;
                    if (phase == Phase.PLACE) publishPlace();
                    else publish("Hold still", "Checking the sky once the phone is still", -1);
                    return;
                }
                if (!probeDone && probeSent && now - probeSentMs > 12000) {
                    log("test frame timed out");
                    forgetProbe();
                    probeDone = true;
                }
                if (pfStep == PF_PROBE && probeDone) {
                    sendTest(now);
                } else if (pfStep == PF_TEST && testReq != null && now - testSentMs > testReq.expNs / 1_000_000L + 15000) {
                    forgetTest();
                    skipPreflight(now, "the test frame never came back, so the sky wasn't checked");
                } else if (pfStep == PF_GRACE && now >= graceUntilMs) {
                    startedAfterWarnings = true;
                    log("decision: started after the pre-flight warnings (the phone was left down)");
                    proceed(now);
                } else if (pfStep == PF_WAIT) {
                    if (System.currentTimeMillis() >= waitUntilWallMs) {
                        pfStep = 0;
                        startCountdown(now);
                    } else if (now - lastPublishMs >= 1000) {
                        publishWait();
                    }
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
                if (phase == Phase.CAPTURING && focusCheckStep != 0 && checkReq != null
                        && now - checkSentMs > checkReq.expNs / 1_000_000L + 10_000L) {
                    cancelFocusCheck("a check frame never came back");
                    submitNext();
                } else if (phase == Phase.CAPTURING && focusCheckDue && focusCheckStep == 0 && inFlight == 0) {
                    // waiting for a free buffer to check the focus with
                    submitNext();
                }
                if (phase == Phase.CAPTURING && now - lastPublishMs >= 1000) publishCapture();
                noteThermal(now);
                if (phase == Phase.CAPTURING) satCountdown();
                if (phase == Phase.CAPTURING) checkStops(now);
                break;
            case PAUSED:
                if (forced ? motion.quietNs() >= STILL_NS : motion.stillNs() >= STILL_NS) resume();
                else if (now - pausedAtMs > MAX_PAUSE_MS) finishCapture("the phone was not put back down");
                else if (now - lastPublishMs >= 1000) publishCapture();
                if (phase == Phase.PAUSED) checkStops(now);
                break;
            default:
                break;
        }
    }

    /**
     * Which saved site the session is at (E6), once the place is known well enough and before anything has used
     * the settings: the site's clear-night share of stars goes to "only if clear", and a session on the saved
     * settings takes the site's preset (as {@link Sites#decide} says). Said aloud: "Backyard site, Backyard preset."
     * An earlier fix ({@code last} false) settles it only when it finds a site: finding none, a fresh fix may yet.
     */
    private void checkSite(String when, boolean last) {
        if (siteChecked || settings == null) return;
        List<Site> sites = Sites.load(prefs);
        Site site = Sites.at(sites, place);
        if (site == null && !last) return;
        siteChecked = true;
        if (sites.isEmpty()) return;
        if (site == null) {
            siteLog = place == null ? "site: the place isn't known, so no saved site was looked for"
                    : String.format(Locale.US, "site: none of the %d saved within %.1f km of %s", sites.size(), Site.nearKm(place.accuracyM),
                    place.describe());
            android.util.Log.i("NightSky", siteLog);
            return;
        }
        settings.siteId = site.id;
        settings.siteName = site.name;
        double share = site.clearShare();
        if (!Double.isNaN(share)) settings.clearBaseline = share;
        Site.Pick k;
        if (settings.readingOnly) {
            k = new Site.Pick();
            k.why = "a sky reading takes no photo, so the preset doesn't matter";
        } else {
            k = Sites.decide(prefs, site, settings.presetId, settings.usesSaved, System.currentTimeMillis());
        }
        String changed = k.use ? usePreset(k.preset) : "";
        siteLog = String.format(Locale.US, "%s: at the %s site, %.1f km away (%s, %s): %s%s%s", k.use ? "decision" : "site", site.name,
                site.distanceKm(place.latDeg, place.lonDeg), place.describe(), when, k.why, changed,
                Double.isNaN(share) ? "" : String.format(Locale.US, "; a clear night there shows %.0f%% of the catalogue's stars", 100 * share));
        siteLine = Site.spoken(site.name, settings.readingOnly ? "" : settings.presetName);
        android.util.Log.i("NightSky", siteLog);
        if (sounds != null) sounds.say(siteLine, null);
        republish();
    }

    /**
     * Puts a saved site's preset in place of the session's settings (nothing has used them yet), and in place of
     * the saved ones, as the home screen will show. Returns what changed, for the log.
     */
    private String usePreset(Preset p) {
        int oldDur = settings.durationSec;
        boolean oldTrails = settings.trails;
        String before = settings.presetName.isEmpty() ? "the settings as they were" : "the " + settings.presetName + " preset";
        Presets.fill(prefs, settings, p);
        Presets.pick(prefs, p, false);
        String note = "";
        if (settings.durationSec != oldDur && plan != null) {
            plan.setDuration(settings.durationSec);
            if (settings.durationSec > oldDur && checkSpace() != null) {
                settings.durationSec = oldDur;
                if (settings.clearTargetSec > 0) {
                    settings.clearTargetSec = oldDur;
                    settings.maxDurationSec = Math.min(Presets.MAX_COLLECT_SEC, 3 * oldDur);
                }
                plan.setDuration(oldDur);
                note = String.format(Locale.US, "; kept to %d minutes, as %d don't fit the free storage", oldDur / 60, p.durationMin);
            }
        }
        if (oldTrails && !settings.trails && !settings.readingOnly) loadSatellites(token);
        return " (instead of " + before + "): " + p.describe() + note;
    }

    private void publishPlace() {
        if (settings.scheduled) {
            publish("Scheduled session: lay the phone screen-down", "The back camera must face the sky. It starts once the phone is "
                    + "still" + (settings.placeTimeoutMs > 0 ? ", or tries again later." : "."), -1);
            return;
        }
        if (settings.readingOnly) {
            publish("Lay the phone screen-down", "The back camera must face the sky. Once the phone is still, three test frames "
                    + "measure how dark the sky is (about 30 seconds).", -1);
            return;
        }
        publish("Lay the phone screen-down", settings.preflight && !forced
                ? "The back camera must face the sky. Once the phone is still it checks the sky, then starts by itself."
                : "The back camera must face the sky. It starts by itself once the phone is still.", -1);
    }

    /**
     * A scheduled start finds the phone moved (not lying still, screen-down): it says so once, waits, and after
     * {@link Settings#placeTimeoutMs} hands back to the plan, which tries again later.
     */
    private void placeWait(long now) {
        if (placeSinceMs == 0) placeSinceMs = now;
        if (!movedSaid && now - placeSinceMs > 4000) {
            movedSaid = true;
            planLog += (planLog.isEmpty() ? "" : "\n") + "scheduled start: the phone wasn't lying still, screen-down; waiting for it";
            android.util.Log.i("NightSky", "scheduled start: the phone has been moved");
            if (sounds != null) sounds.say("The phone has been moved. Lay it screen-down, camera to the sky, to start.", null);
        }
        if (settings.placeTimeoutMs > 0 && now - placeSinceMs > settings.placeTimeoutMs) {
            endAttempt(NightPlan.Outcome.NOT_PLACED, String.format(Locale.US, "The phone wasn't lying still and screen-down for %d minutes",
                    Math.round(settings.placeTimeoutMs / 60_000.0)));
        }
    }

    /**
     * Every few seconds while capturing: the session's stop conditions. Each develops what was captured: the
     * end time, the sky brightening at dawn, a low battery, storage running out, the phone overheating.
     */
    private void checkStops(long now) {
        if (now - stopCheckMs < 5000) return;
        stopCheckMs = now;
        long wall = System.currentTimeMillis();
        if (settings.endAtMs > 0 && wall >= settings.endAtMs) {
            stopEarly("it reached its end time, " + clockTime(settings.endAtMs));
            return;
        }
        if (settings.stopAtDawn && sky.located() && now - sunCheckMs >= 60_000L) {
            sunCheckMs = now;
            double alt = Tonight.sunAlt(wall, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG);
            if (alt > Tonight.DARK_DEG) {
                stopEarly(String.format(Locale.US, "dawn: the Sun rose to %.0f degrees below the horizon and the sky began to brighten", -alt));
                return;
            }
        }
        if (settings.minBatteryPct > 0 && now - batteryCheckMs >= 30_000L) {
            batteryCheckMs = now;
            BatteryInfo b = BatteryInfo.read(ctx);
            if (!b.charging && b.pct >= 0 && b.pct < settings.minBatteryPct) {
                stopEarly(String.format(Locale.US, "the battery fell to %d%% (sessions stop below %d%%)", b.pct, settings.minBatteryPct));
                return;
            }
        }
        long free = freeBytes();
        if (free >= 0 && spec != null && free < 2L * spec.width * spec.height * 3 + (300L << 20)) {
            stopEarly(String.format(Locale.US, "storage ran low (%.1f GB free)", free / 1e9));
            return;
        }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_CRITICAL) {
            stopEarly("the phone got critically hot");
        }
    }

    private void stopEarly(String why) {
        stopReason = why;
        log("decision: stopped early: " + why);
        finishCapture(why);
    }

    private void startCountdown(long now) {
        phase = Phase.COUNTDOWN;
        countdownLeft = forced ? 0 : Math.max(1, settings.countdownSec);
        nextBeepMs = now;
        if (!probeSent) sendProbe();
    }

    /** From the start of the countdown to the first exposure (ms). */
    private long countdownLeadMs() {
        return Math.max(1, settings.countdownSec) * 1000L + SETTLE_MS;
    }

    /**
     * After the check (or once the phone is still, without one): the countdown now, or first a wait, so that the
     * capture starts after a bright satellite has crossed (its streak would cost the stack) or at the time picked
     * on the home screen.
     */
    private void proceed(long now) {
        long wall = System.currentTimeMillis(), at = 0;
        String why = "", reason = "";
        SatPlanner.Plan p = preflightReport != null ? preflightReport.satellites : null;
        if (p != null && p.waits()) {
            at = p.startMs;
            String name = SatPlanner.the(p.waitFor.sat.displayName()), when = clockTime(SatPlanner.ms(p.waitFor.jdIn));
            why = SatPlanner.capital(name) + " crosses the frame at " + when + ", so it starts after it";
            reason = String.format(Locale.US, "%s (magnitude %.1f) crossing the frame at %s would have left a streak", name,
                    p.waitFor.magBest, when);
        }
        if (settings.startAtMs > at) {
            at = settings.startAtMs;
            if (settings.startWhy.isEmpty()) {
                why = "Starts at " + clockTime(at) + ", the quietest stretch for satellites";
                reason = "the quietest stretch for satellites, picked on the home screen";
            } else {
                why = "Starts at " + clockTime(at) + ", " + settings.startWhy;
                reason = settings.startWhy;
            }
        }
        long lead = countdownLeadMs();
        if (forced || at - lead - wall < 2000) {
            startCountdown(now);
            return;
        }
        phase = Phase.PREFLIGHT;
        pfStep = PF_WAIT;
        waitUntilWallMs = at - lead;
        waitWhy = why;
        waitLog = String.format(Locale.US, "decision: waited %s to start at %s: %s", clock(waitUntilWallMs - wall), clockTime(at), reason);
        log(waitLog);
        publishWait();
    }

    private void publishWait() {
        long left = Math.max(0, waitUntilWallMs - System.currentTimeMillis());
        publish("Waiting to start", waitWhy + ". The countdown begins in " + clock(left) + ".", -1);
        lastPublishMs = SystemClock.elapsedRealtime();
    }

    /** "10:14 PM" in the phone's time zone. */
    static String clockTime(long wallMs) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("h:mm a", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(wallMs));
    }

    /** "10:14", for speaking. */
    private static String sayTime(long wallMs) {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("h:mm", Locale.US);
        f.setTimeZone(TimeZone.getDefault());
        return f.format(new Date(wallMs));
    }

    /**
     * What to say about when the capture starts, if not right after the countdown, and about a bright pass to
     * count down; "" if nothing.
     */
    private String startWords(Preflight.Report rep) {
        SatPlanner.Plan p = rep.satellites;
        StringBuilder w = new StringBuilder();
        boolean satWait = p != null && p.waits();
        if (!satWait && settings.startAtMs - System.currentTimeMillis() > countdownLeadMs() + 15_000L) {
            w.append("I'll start at ").append(sayTime(settings.startAtMs))
                    .append(settings.startWhy.isEmpty() ? ", the quietest few minutes for satellites." : ", as planned.");
        }
        if (p != null && !p.spoken.isEmpty()) w.append(w.length() > 0 ? " " : "").append(p.spoken);
        return w.toString();
    }

    private static final String[] COUNT_WORDS = {"Now", "One", "Two", "Three", "Four", "Five"};

    /**
     * Keeping satellites: a bright pass about to cross is counted down aloud ("The ISS in ten seconds", five to
     * one, "now"), so it can be watched going over. Speech is far too weak to shake the phone.
     */
    private void satCountdown() {
        if (countdownNext >= countdownPasses.size() || sounds == null) return;
        SatPredict.Pass p = countdownPasses.get(countdownNext);
        long left = SatPlanner.ms(p.jdIn) - System.currentTimeMillis();
        if (left < -3000) {
            // missed (the capture was paused, or started late)
            countdownNext++;
            countdownSaid = Integer.MAX_VALUE;
            return;
        }
        int step = left > 10_500 ? -1 : left > 5_500 ? 10 : (int) Math.max(0, Math.ceil((left - 500) / 1000.0));
        if (step < 0 || step >= countdownSaid) return;
        boolean first = countdownSaid == Integer.MAX_VALUE;
        countdownSaid = step;
        String name = SatPlanner.capital(SatPlanner.the(p.sat.displayName()));
        String text;
        if (step == 10) text = String.format(Locale.US, "%s in %d seconds.", name, Math.round(left / 1000.0));
        else if (first) text = name + (step == 0 ? " now." : ", " + COUNT_WORDS[step].toLowerCase(Locale.US) + ".");
        else text = COUNT_WORDS[step] + ".";
        sounds.say(text, null);
        if (first) {
            log(String.format(Locale.US, "counting down %s: in the picture from %s, magnitude %.1f", SatPlanner.the(p.sat.displayName()),
                    clockTime(SatPlanner.ms(p.jdIn)), p.magBest));
        }
        if (step == 0) {
            countdownNext++;
            countdownSaid = Integer.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Pre-flight check
    // ------------------------------------------------------------------------------------------------

    private void startPreflight(long now) {
        phase = Phase.PREFLIGHT;
        preflightToken++;
        preflightReport = null;
        readingReports.clear();
        testRetries = 0;
        testReq = null;
        pfStep = PF_PROBE;
        sounds.tick();
        if (settings.readingOnly) publish("Reading the sky", "Three test frames measure how dark the sky is", -1);
        else publish("Checking the sky", "A test frame looks for a smudged lens, obstructions, clouds and the Moon", -1);
        if (!probeSent) sendProbe();
        if (probeDone) sendTest(now);
    }

    /** The phone was picked up or the check was cut short: whatever is still coming is ignored. */
    private void abandonPreflight() {
        preflightToken++;
        forgetTest();
        pfStep = 0;
    }

    private void skipPreflight(long now, String why) {
        log("pre-flight: " + why);
        abandonPreflight();
        if (settings.readingOnly) {
            finishReading(why);
            return;
        }
        startCountdown(now);
    }

    /**
     * A sky reading's frame was read: another frame, or the reading. The first frame not measured (no stars to
     * measure against) ends it at once; after that, a frame not measured (a cloud passing) just doesn't count.
     */
    private void readingFrame(Preflight.Report rep) {
        readingReports.add(rep);
        int n = readingReports.size();
        android.util.Log.i("NightSky", String.format(Locale.US, "sky reading: frame %d: %.2f with the stars, %.2f between them", n,
                rep.skyMagWithStars, rep.skyMag));
        if (n < SkyReading.FRAMES && SkyReading.measured(readingReports.get(0))) {
            testRetries = 0;
            sendTest(SystemClock.elapsedRealtime());
            return;
        }
        finishReading("");
    }

    /**
     * A sky reading is over (H3): the frames' sky brightness (or why there is none) goes on the screen, is said
     * aloud and is added to the readings' log; the camera closes. No photo, no session folder.
     */
    private void finishReading(String why) {
        long wall = System.currentTimeMillis();
        SkyReading r = SkyReading.of(new ArrayList<Preflight.Report>(readingReports), wall, prefs.sqmOffset(), why);
        if (Double.isNaN(r.latDeg) && place != null) {
            r.latDeg = place.latDeg;
            r.lonDeg = place.lonDeg;
        }
        android.util.Log.i("NightSky", "sky reading: " + r.headline + (r.valid ? "" : " (doesn't count: " + r.why + ")"));
        token++;
        startToken++;
        abandonPreflight();
        stopLocating();
        releaseCamera();
        final Sounds sp = sounds;
        sounds = null;
        reading = r;
        phase = Phase.DONE;
        outcome = NightPlan.Outcome.DONE;
        outcomeDetail = r.headline;
        r.site = settings.siteName;
        if (!settings.siteId.isEmpty() && r.frames > 0) {
            Sites.learnReading(prefs, settings.siteId, r);
            r.lines.add("Added to the " + settings.siteName + " site's sky history" + (r.valid ? "." : ", marked as not counting."));
        }
        String saved = SkyReadings.append(ctx, r);
        if (saved != null) android.util.Log.w("NightSky", "sky reading not logged: " + saved);
        publish("Sky reading", r.headline, -1);
        if (sp != null) {
            sp.say(r.spoken, new Runnable() {
                @Override
                public void run() {
                    sp.release();
                }
            });
        }
        host.onCaptureEnded();
        host.onIdle();
    }

    private void forgetTest() {
        Req r = testReq;
        if (r == null) return;
        reqs.remove(r.seq);
        if (r.startNs >= 0) tsToSeq.remove(r.startNs);
        testReq = null;
    }

    /** The test frame: the session's ISO and frame length (at most 8 s), so it sees what the session will. */
    private void sendTest(long now) {
        long exp = Math.min(plan.frameExpNs, TEST_MAX_NS);
        if (testBuf == null && focusBuf != null) {
            // the focus step is over: its frame's memory does for the test frame
            testBuf = focusBuf;
            focusBuf = null;
        }
        if (testBuf == null) {
            try {
                testBuf = new short[spec.width * spec.height];
            } catch (OutOfMemoryError e) {
                skipPreflight(now, "not enough memory for the test frame, so the sky wasn't checked");
                return;
            }
        }
        int seq = ++seqCounter;
        Req r = new Req(seq, TEST, exp);
        if (!rig.capture(seq, exp, plan.iso)) {
            skipPreflight(now, "the test frame could not be taken, so the sky wasn't checked");
            return;
        }
        reqs.put(seq, r);
        testReq = r;
        testSentMs = now;
        pfStep = PF_TEST;
        if (settings.readingOnly) {
            publish("Reading the sky", String.format(Locale.US, "Frame %d of %d: %.0f seconds at ISO %d", readingReports.size() + 1,
                    SkyReading.FRAMES, exp / 1e9, plan.iso), -1);
        } else {
            publish("Checking the sky", String.format(Locale.US, "Test frame: %.0f seconds at ISO %d", exp / 1e9, plan.iso), -1);
        }
    }

    private void testArrived(Req r) {
        if (r != testReq || r.result == null || (r.buf == null && !r.lost)) return;
        forgetTest();
        if (phase != Phase.PREFLIGHT || pfStep != PF_TEST) return;
        long now = SystemClock.elapsedRealtime();
        if (r.lost || r.buf == null) {
            if (++testRetries <= 1) sendTest(now);
            else skipPreflight(now, "the test frame was lost, so the sky wasn't checked");
            return;
        }
        TotalCaptureResult res = r.result;
        SensorSpecFactory.applyResult(spec, res, geo);
        Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        long exp = e != null ? e : r.expNs;
        Integer isoR = res.get(CaptureResult.SENSOR_SENSITIVITY);
        int iso = isoR != null ? isoR : plan.iso;
        if (exp < r.expNs * 0.93) {
            // the camera cut it short: the session's frames will be cut too, so plan with what it delivers
            prefs.noteExposureClamped(cameraId, r.expNs, exp);
            plan.limitTo(exp, settings.durationSec, spec.white);
            log(String.format(Locale.US, "decision: the camera cut the test frame to %.2f s; new plan: %s", exp / 1e9, plan.describe()));
        } else {
            prefs.noteExposureDelivered(cameraId, r.expNs);
        }
        Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
        long tsn = ts != null ? ts : r.startNs;
        long t0 = realtimeClock && tsn > 0 ? tsn : r.arrivalNs - exp;
        float rate = motion.maxRate(t0, t0 + exp);
        if (rate > DISTURBED_RAD_S && ++testRetries <= 1) {
            log(String.format(Locale.US, "pre-flight: the phone moved during the test frame (%.2f rad/s); taking another", rate));
            sendTest(now);
            return;
        }
        // the compass, now that the phone lies where it will stay
        double head = motion.headingTopDeg();
        if (!Double.isNaN(head)) {
            double d = place != null ? Locator.declination(place) : 0;
            sky.headingTopDeg = ((head + d) % 360 + 360) % 360;
        }
        final Preflight.Input in = new Preflight.Input();
        in.raw = r.buf;
        in.invScale = 1f;
        float[] black = SensorSpecFactory.dynamicBlack(res, geo);
        in.black = black != null ? black : spec.black.clone();
        in.spec = spec;
        in.exposureS = exp / 1e9;
        in.iso = iso;
        in.planExposureS = plan.frameExpNs / 1e9;
        in.planIso = plan.iso;
        in.sessionSeconds = settings.durationSec;
        in.trails = settings.trails;
        in.sky = sky.copy();
        in.timestampNs = tsn;
        in.up = motion.up();
        in.haloBaseline = prefs.haloBaseline(cameraId);
        in.clearZeroPoint = prefs.clearZeroPoint(cameraId);
        in.freeBytes = freeBytes();
        in.needBytes = needBytes();
        BatteryInfo b = BatteryInfo.read(ctx);
        in.batteryPct = b.pct;
        in.batteryTempC = b.tempC;
        in.charging = b.charging;
        in.drainPctPerHour = prefs.drainPctPerHour();
        in.satellites = satCatalog;
        in.keepSatellites = settings.keepSatellites;
        in.zone = TimeZone.getDefault();
        // from the middle of the test frame to the first exposure: reading it, the summary, the countdown
        in.startDelayMs = exp / 2_000_000L + 8000L + countdownLeadMs();
        long middle = System.currentTimeMillis() - exp / 2_000_000L;
        if (settings.startAtMs > 0) in.startDelayMs = Math.max(in.startDelayMs, settings.startAtMs - middle);
        pfStep = PF_READ;
        publish(settings.readingOnly ? "Reading the sky" : "Checking the sky", settings.readingOnly
                ? String.format(Locale.US, "Measuring frame %d of %d", readingReports.size() + 1, SkyReading.FRAMES) : "Reading the test frame", -1);
        final int tk = preflightToken;
        new Thread(new Runnable() {
            @Override
            public void run() {
                Preflight.Report rep = null;
                Throwable err = null;
                try {
                    in.catalog = SkyCatalog.get(ctx);
                    rep = Preflight.run(in);
                } catch (Throwable t) {
                    err = t;
                }
                final Preflight.Report fr = rep;
                final Throwable fe = err;
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        preflightRead(tk, fr, fe);
                    }
                });
            }
        }, "preflight").start();
    }

    private void preflightRead(final int tk, final Preflight.Report rep, Throwable err) {
        if (tk != preflightToken || phase != Phase.PREFLIGHT || pfStep != PF_READ) return;
        long now = SystemClock.elapsedRealtime();
        if (rep == null) {
            skipPreflight(now, "the check failed (" + err + "), so the sky wasn't checked");
            return;
        }
        preflightReport = rep;
        android.util.Log.i("NightSky", "pre-flight (" + rep.millis + " ms): " + rep.spoken() + "\n" + rep.describe());
        if (settings.readingOnly) {
            readingFrame(rep);
            return;
        }
        // the saved site learns what blocks its sky and how bright the sky is there
        if (!settings.siteId.isEmpty()) Sites.learnCheck(prefs, settings.siteId, rep, prefs.sqmOffset(), System.currentTimeMillis());
        if (settings.onlyIfClear) {
            Preflight.Clear c = Preflight.clearEnough(rep, settings.clearBaseline);
            String line = "only if clear: " + c.seen + (c.clear ? ": clear enough, starting" : ": not clear enough");
            android.util.Log.i("NightSky", line);
            if (!c.clear) {
                endAttempt(NightPlan.Outcome.CLOUDY, capital(c.seen));
                return;
            }
            planLog += (planLog.isEmpty() ? "" : "\n") + "decision: " + line;
        }
        final Preflight.Item stop = rep.stop();
        final boolean warn = rep.hasWarnings() && !settings.scheduled;
        if (rep.hasWarnings() && settings.scheduled) {
            planLog += (planLog.isEmpty() ? "" : "\n") + "decision: started despite the pre-flight warnings: a scheduled session has nobody there to fix them";
        }
        pfStep = PF_SPEAK;
        String say = rep.spoken();
        String later = stop != null ? "" : startWords(rep);
        if (stop != null) {
            publish("Can't start", stop.text, -1);
        } else if (warn) {
            say += " Pick up the phone to fix it, or it starts by itself in ten seconds.";
            publish("Check before starting", "Pick up the phone to fix it, or it starts by itself", -1);
        } else {
            publish("Ready", later.isEmpty() ? "Starting soon" : later, -1);
        }
        if (!later.isEmpty()) say += " " + later;
        sounds.say(say, new Runnable() {
            @Override
            public void run() {
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tk != preflightToken || phase != Phase.PREFLIGHT || pfStep != PF_SPEAK) return;
                        long t = SystemClock.elapsedRealtime();
                        if (stop != null) {
                            storageShort = "storage".equals(stop.key);
                            fail(stop.text);
                        } else if (warn) {
                            pfStep = PF_GRACE;
                            graceUntilMs = t + GRACE_MS;
                            publish("Check before starting", "Pick up the phone to fix it, or it starts by itself in a few seconds", -1);
                        } else {
                            proceed(t);
                        }
                    }
                });
            }
        });
    }

    /** Records the place in this session's sky. */
    private void setPlace(Locator.Place p) {
        if (p == null) return;
        sky.latDeg = p.latDeg;
        sky.lonDeg = p.lonDeg;
        sky.altM = p.altM;
        sky.accuracyM = p.accuracyM;
        sky.locationSource = p.source;
        sky.locationAgeMin = p.ageMinutes();
    }

    /** A wall-clock time and the camera timestamp of the same instant (the first one given is kept). */
    private void setClock(long wallMs, long timestampNs) {
        if (sky.clockValid) return;
        sky.clockWallMs = wallMs;
        sky.clockTimestampNs = timestampNs;
        sky.clockValid = true;
    }

    // ------------------------------------------------------------------------------------------------
    // Focus on the stars (A5), centred by the daylight calibration (F7), and checked in long sessions
    // ------------------------------------------------------------------------------------------------

    /** Once the phone is still (and focused): the check of the sky, or straight on to the countdown. */
    private void afterSteady(long now) {
        if (settings.readingOnly || (settings.preflight || settings.onlyIfClear) && !forced) startPreflight(now);
        else proceed(now);
    }

    /**
     * Whether to focus on the stars now: once a session, and not for a sky reading (the sky's brightness doesn't
     * need sharp stars), a lens with a fixed focus, with it turned off in the settings, or after "Start now" from
     * the viewer. Without a sweep the session keeps the focus it opened with (predicted, saved or the lens's own
     * infinity), and the log says why.
     */
    private boolean wantsFocus() {
        if (focusDone || settings.readingOnly) return false;
        String why = null;
        if (lens == null) why = "the lens has a fixed focus";
        else if (!prefs.focusAssist()) why = "focusing on the stars is off in the settings";
        else if (forced) why = "start now from the viewer";
        if (why == null) return true;
        focusDone = true;
        noteFocus(String.format(Locale.US, "decision: focus %.3f (%s), not swept: %s", focusValue, howWords(focusHow), why));
        return false;
    }

    /** A focus decision: for the session's log (written once its folder exists) and the system log now. */
    private void noteFocus(String line) {
        focusLog += line + "\n";
        android.util.Log.i("NightSky", line);
    }

    private void startFocus(long now) {
        phase = Phase.FOCUS;
        focusToken++;
        focusReq = null;
        focusRetries = 0;
        focusPointTries = 0;
        focusMeasuring = false;
        focusStartMs = now;
        // the phone has lain outside a while by now: its temperature now is the one the focus is for
        float t = BatteryInfo.read(ctx).tempC;
        if (!Float.isNaN(t)) focusTempC = t;
        sweep = FocusSweep.night(lens, focusModel, focusTempC);
        sweep.measureZero = true;
        if (focusBuf == null) {
            try {
                focusBuf = new short[spec.width * spec.height];
            } catch (OutOfMemoryError e) {
                stopFocus(now, "not enough memory for the focus frames");
                return;
            }
        }
        publish("Focusing on the stars", focusWords(), -1);
        if (!probeSent) sendProbe();
    }

    /** The next focus frame, or the result once the sweep has measured all it wants. */
    private void sendFocus(long now) {
        if (phase != Phase.FOCUS || sweep == null) return;
        double f = sweep.next();
        if (Double.isNaN(f)) {
            finishFocus(now);
            return;
        }
        long exp = focusExpNs();
        int seq = ++seqCounter;
        Req r = new Req(seq, FOCUS, exp);
        r.focus = (float) f;
        rig.setFocus(r.focus);
        if (!rig.capture(seq, exp, focusIso(exp))) {
            stopFocus(now, "the focus frames could not be taken");
            return;
        }
        reqs.put(seq, r);
        focusReq = r;
        focusSentMs = now;
        publish("Focusing on the stars", focusWords(), -1);
    }

    /** Short frames, so the sweep is quick: a quarter of the session's frame, from half a second to two seconds. */
    private long focusExpNs() {
        long exp = Math.max(500_000_000L, Math.min(2_000_000_000L, plan.frameExpNs / 4));
        if (advMaxNs > 0) exp = Math.min(exp, Math.max(100_000_000L, advMaxNs));
        return exp;
    }

    /**
     * The focus frames' ISO: high, so that short frames show enough stars, but no more exposure than a session
     * frame's (which the plan keeps from saturating in the sky's glow), and at most the camera's analog maximum.
     */
    private int focusIso(long exp) {
        double same = (double) plan.iso * plan.frameExpNs / Math.max(1L, exp);
        return (int) Math.max(plan.iso, Math.min(isoStart, same));
    }

    private void forgetFocus() {
        Req r = focusReq;
        if (r == null) return;
        reqs.remove(r.seq);
        if (r.startNs >= 0) tsToSeq.remove(r.startNs);
        focusReq = null;
    }

    /** The phone was picked up while focusing: whatever is still coming is ignored, and it starts again. */
    private void abandonFocus() {
        focusToken++;
        forgetFocus();
        focusMeasuring = false;
        sweep = null;
        rig.setFocus(focusValue);
    }

    /** A focus frame is in (image and result): taken again if the phone or the lens moved, else measured. */
    private void focusArrived(Req r) {
        if (r != focusReq || r.result == null || (r.buf == null && !r.lost)) return;
        forgetFocus();
        if (phase != Phase.FOCUS || sweep == null) return;
        long now = SystemClock.elapsedRealtime();
        if (r.lost || r.buf == null) {
            if (++focusRetries <= 2) sendFocus(now);
            else stopFocus(now, "the focus frames were lost");
            return;
        }
        TotalCaptureResult res = r.result;
        Float fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
        Integer lensState = res.get(CaptureResult.LENS_STATE);
        float at = fd != null ? fd : r.focus;
        Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        long exp = e != null ? e : r.expNs;
        Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
        long tsn = ts != null ? ts : r.startNs;
        long t0 = realtimeClock && tsn > 0 ? tsn : r.arrivalNs - exp;
        float rate = motion.maxRate(t0, t0 + exp);
        String again = null;
        if (rate > DISTURBED_RAD_S) again = String.format(Locale.US, "the phone moved (%.2f rad/s)", rate);
        else if (lensState != null && lensState == CaptureResult.LENS_STATE_MOVING) again = "the lens was still moving";
        else if (Math.abs(at - r.focus) > 0.5 * lens.fine) again = String.format(Locale.US, "the lens was at %.3f", at);
        if (again != null && ++focusPointTries <= 2) {
            android.util.Log.i("NightSky", String.format(Locale.US, "focus: the frame at %.3f taken again: %s", r.focus, again));
            sendFocus(now);
            return;
        }
        focusPointTries = 0;
        float[] black = SensorSpecFactory.dynamicBlack(res, geo);
        final float[] bl = black != null ? black : spec.black.clone();
        final short[] buf = r.buf;
        final SensorSpec sp = spec;
        final int tk = focusToken;
        final float where = at;
        focusMeasuring = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final FocusMeter.Reading rd = measureFocus(buf, sp, bl);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        focusMeasured(tk, where, rd);
                    }
                });
            }
        }, "focus").start();
    }

    /** The stars' size in a focus or check frame (off the session thread). */
    private static FocusMeter.Reading measureFocus(short[] buf, SensorSpec sp, float[] black) {
        try {
            return FocusMeter.measure(buf, 1f, sp, black);
        } catch (Throwable t) {
            FocusMeter.Reading rd = new FocusMeter.Reading();
            rd.why = "could not be measured: " + t;
            return rd;
        }
    }

    private void focusMeasured(int tk, float at, FocusMeter.Reading rd) {
        if (tk != focusToken || phase != Phase.FOCUS || sweep == null) return;
        focusMeasuring = false;
        sweep.add(at, rd.hfr, rd.noise, rd.stars, rd.ok());
        android.util.Log.i("NightSky", String.format(Locale.US, "focus: %.3f: %s (%d ms)", at, rd.describe(), rd.millis));
        long now = SystemClock.elapsedRealtime();
        if (sweep.done()) finishFocus(now);
        else sendFocus(now);
    }

    /**
     * The sweep is over: the focus it found for the rest of the session (else the one the session opened with),
     * with the stars' size there and at the lens's own infinity in the log (A5), how far it landed from the
     * daylight calibration's prediction (F7), and saved for next time as this camera's focus on the stars.
     */
    private void finishFocus(long now) {
        FocusSweep s = sweep;
        FocusSweep.Result res = s.result();
        focusResult = res;
        focusPredicted = s.predicted;
        noteSweep(s);
        if (res.ok) {
            focusValue = (float) res.focus;
            focusHow = res.check != null && res.check.holds ? "checked" : "swept";
            noteFocus(String.format(Locale.US, "decision: focus %.3f%s (%s sweep, %d frames in %.0f s, %s): stars %.2f px (half-flux radius) "
                            + "against %s at the lens's own infinity (0.000%s)", focusValue,
                    Double.isNaN(res.sigma) ? "" : String.format(Locale.US, " +/-%.3f", res.sigma), s.mode, res.frames, (now - focusStartMs) / 1000.0,
                    res.how, res.value, Double.isNaN(res.atZero) ? "unknown" : String.format(Locale.US, "%.2f px", res.atZero),
                    Double.isNaN(res.atZero) ? "" : res.zeroMeasured ? ", measured" : ", from the fit"));
            if (res.check != null) noteFocus("focus: two-frame check: " + res.check.describe());
            if (!Double.isNaN(s.predicted)) {
                double off = res.focus - s.predicted;
                noteFocus(String.format(Locale.US, "focus: %.3f against the daylight calibration's %.3f for %.0f C: %s (%+.3f; steps of %.3f)",
                        res.focus, s.predicted, focusTempC, Math.abs(off) <= lens.fine + 1e-6 ? "within one sweep step of the prediction"
                                : "more than one sweep step from the prediction", off, lens.fine));
            }
            if (res.fitted || res.check != null && res.check.holds) {
                // A5's "custom infinity" for this camera, and a night point for F7
                FocusModel.Cal c = new FocusModel.Cal();
                c.focus = res.focus;
                c.tempC = focusTempC;
                c.timeMs = System.currentTimeMillis();
                c.hfr = res.value;
                c.slope = res.slope;
                focusModel.addNight(c);
                prefs.setFocusModel(cameraId, focusModel.encode());
            } else {
                noteFocus("focus: not saved for next time, as no curve could be fitted");
            }
        } else {
            noteFocus(String.format(Locale.US, "decision: focus kept at %.3f (%s): %s", focusValue, howWords(focusHow), res.how));
        }
        rig.setFocus(focusValue);
        endFocus(now);
    }

    /** What the sweep measured, for the log. */
    private void noteSweep(FocusSweep s) {
        noteFocus("focus: " + s.why + "; " + lens.describe());
        if (s.frames() > 0) noteFocus("focus frames: " + s.pointsLine());
    }

    /** The focus step cut short: the curve fitted so far if there is one, else the focus the session opened with. */
    private void stopFocus(long now, String why) {
        if (phase != Phase.FOCUS) return;
        focusToken++;
        forgetFocus();
        focusMeasuring = false;
        FocusSweep s = sweep;
        FocusSweep.Result res = s != null && s.frames() > 0 ? s.result() : null;
        if (s != null) {
            focusPredicted = s.predicted;
            noteSweep(s);
        }
        if (res != null && res.ok && res.fitted) {
            focusResult = res;
            focusValue = (float) res.focus;
            focusHow = "swept";
            noteFocus(String.format(Locale.US, "decision: focus %.3f from the %d frames so far (%s), cut short: %s", focusValue, res.frames,
                    res.how, why));
        } else {
            noteFocus(String.format(Locale.US, "decision: focus kept at %.3f (%s), the sweep cut short: %s", focusValue, howWords(focusHow), why));
        }
        rig.setFocus(focusValue);
        endFocus(now);
    }

    private void endFocus(long now) {
        focusDone = true;
        sweep = null;
        if (phase == Phase.FOCUS) afterSteady(now);
    }

    /** How the session's focus was chosen, in words for the log. */
    private static String howWords(String how) {
        switch (how) {
            case "fixed":
                return "the lens's fixed focus";
            case "predicted":
                return "what the daylight calibration predicts for the phone's temperature";
            case "saved":
                return "the focus found on the stars last time";
            case "checked":
                return "last time's focus, checked with two frames";
            case "swept":
                return "found on the stars";
            default:
                return "the lens's own infinity";
        }
    }

    /** The detail line on the screen while focusing. */
    private String focusWords() {
        FocusSweep s = sweep;
        if (s == null) return "Short frames find where the stars are sharpest";
        int n = s.frames();
        return String.format(Locale.US, "Frame %d of about %d: short frames find where the stars are sharpest", n + 1, n + Math.max(1, s.planned()));
    }

    /**
     * Each processed unit, on the session thread: the stars' size watched in sessions of half an hour or more
     * (F7). Once they have grown steadily with the sky as clear, a two-frame focus check runs between frames.
     */
    private void watchFocus(double t, float hfr, float transparency, boolean ok) {
        if (phase != Phase.CAPTURING && phase != Phase.PAUSED) return;
        if (lens == null || !prefs.focusAssist()) return;
        if (ok) focusWatch.add(t, hfr, transparency);
        if (focusCheckDue || focusCheckStep != 0) return;
        String why = focusWatch.due(t, settings.durationSec);
        String haze = focusWatch.haze();
        if (!haze.isEmpty() && focusHaze.isEmpty()) {
            // said once: thin cloud swells the stars too, and refocusing wouldn't help
            focusHaze = haze;
            log("focus: no check: " + haze);
        }
        if (why == null) return;
        focusCheckDue = true;
        focusCheckWhy = why;
        focusCheckDueMs = SystemClock.elapsedRealtime();
        log("focus: a check is due: " + why);
    }

    /** The frames in flight are in: the check's first frame, below the focus, once a buffer is free. */
    private void startFocusCheck() {
        if (up == null || rig == null) return;
        long now = SystemClock.elapsedRealtime();
        short[] b = up.obtainBuffer(0);
        if (b == null) {
            if (now - focusCheckDueMs > 15_000L) cancelFocusCheck("no memory was free for the check frames");
            return;
        }
        checkBuf = b;
        focusCheckStep = 1;
        sendCheck(now);
    }

    /** One check frame: two fine steps below the focus (step 1), then above it (step 2). False if it couldn't be taken. */
    private boolean sendCheck(long now) {
        double d = FocusSweep.checkDelta(lens);
        float f = (float) (focusCheckStep == 1 ? Math.max(0, focusValue - d) : focusValue + d);
        long exp = focusExpNs();
        int seq = ++seqCounter;
        Req r = new Req(seq, FOCUS_CHECK, exp);
        r.focus = f;
        rig.setFocus(f);
        if (!rig.capture(seq, exp, focusIso(exp))) {
            cancelFocusCheck("a check frame could not be taken");
            return false;
        }
        reqs.put(seq, r);
        checkReq = r;
        checkSentMs = now;
        lastProgressMs = now;
        return true;
    }

    /** A check frame is in: measured off the session thread, unless the phone moved. */
    private void checkArrived(Req r) {
        if (r != checkReq || r.result == null || (r.buf == null && !r.lost)) return;
        reqs.remove(r.seq);
        if (r.startNs >= 0) tsToSeq.remove(r.startNs);
        checkReq = null;
        long now = SystemClock.elapsedRealtime();
        lastProgressMs = now;
        if (phase != Phase.CAPTURING || focusCheckStep == 0) return;
        if (r.lost || r.buf == null) {
            cancelFocusCheck("a check frame was lost");
            submitNext();
            return;
        }
        TotalCaptureResult res = r.result;
        Long e = res.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        long exp = e != null ? e : r.expNs;
        Long ts = res.get(CaptureResult.SENSOR_TIMESTAMP);
        long tsn = ts != null ? ts : r.startNs;
        long t0 = realtimeClock && tsn > 0 ? tsn : r.arrivalNs - exp;
        float rate = motion.maxRate(t0, t0 + exp);
        if (rate > DISTURBED_RAD_S) {
            cancelFocusCheck(String.format(Locale.US, "the phone moved during a check frame (%.2f rad/s)", rate));
            submitNext();
            return;
        }
        Float fd = res.get(CaptureResult.LENS_FOCUS_DISTANCE);
        final float at = fd != null ? fd : r.focus;
        float[] black = SensorSpecFactory.dynamicBlack(res, geo);
        final float[] bl = black != null ? black : spec.black.clone();
        final short[] buf = r.buf;
        final SensorSpec sp = spec;
        final int tk = focusToken, step = focusCheckStep;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final FocusMeter.Reading rd = measureFocus(buf, sp, bl);
                h.post(new Runnable() {
                    @Override
                    public void run() {
                        checkMeasured(tk, step, at, rd);
                    }
                });
            }
        }, "focus-check").start();
    }

    /**
     * A check frame measured: after the one below the focus, the one above; after both, where the best focus is
     * now (from the difference of the two sizes and how fast stars grow away from focus). The focus moves there,
     * at most two fine steps at a time, unless it still holds.
     */
    private void checkMeasured(int tk, int step, float at, FocusMeter.Reading rd) {
        if (tk != focusToken || step != focusCheckStep || phase != Phase.CAPTURING) return;
        long now = SystemClock.elapsedRealtime();
        lastProgressMs = now;
        if (!rd.ok()) {
            cancelFocusCheck("too few stars to measure in a check frame (" + rd.why + ")");
            submitNext();
            return;
        }
        if (step == 1) {
            checkLowF = at;
            checkLowH = rd.hfr;
            checkLowN = rd.noise;
            focusCheckStep = 2;
            if (!sendCheck(now)) submitNext();
            return;
        }
        double slope = lens.slope;
        String slopeFrom = "the optics";
        FocusModel.Cal last = focusModel.lastNight();
        if (focusResult != null && focusResult.slope > 0) {
            slope = focusResult.slope;
            slopeFrom = "tonight's sweep";
        } else if (last != null && last.slope > 0) {
            slope = last.slope;
            slopeFrom = "the last sweep";
        }
        FocusSweep.Check c = FocusSweep.twoPoint(focusValue, checkLowF, checkLowH, checkLowN, at, rd.hfr, rd.noise, slope, lens.fine);
        float before = focusValue;
        if (!c.holds) focusValue = (float) Math.max(0, before + Stats.clamp(c.best - before, -2 * lens.fine, 2 * lens.fine));
        rig.setFocus(focusValue);
        double minutes = (now - captureStartMs - pausedTotalMs) / 60000.0;
        log(String.format(Locale.US, "decision: focus check after %.0f minutes (%s): %s (stars %.2f and %.2f px; %.1f px per unit, from %s): %s",
                minutes, focusCheckWhy, c.describe(), checkLowH, rd.hfr, slope, slopeFrom, c.holds
                        ? String.format(Locale.US, "focus kept at %.3f", before) : String.format(Locale.US, "focus moved from %.3f to %.3f", before, focusValue)));
        focusChecks += (focusChecks.isEmpty() ? "" : ";") + String.format(Locale.US, "%.0f,%.3f,%.3f", minutes, before, focusValue);
        finishFocusCheck();
        submitNext();
    }

    /** The check is over: its buffer back to the pool, and the stars' size from now on the new baseline. */
    private void finishFocusCheck() {
        if (checkBuf != null && up != null) up.releaseBuffer(checkBuf);
        checkBuf = null;
        focusCheckStep = 0;
        focusCheckDue = false;
        focusCheckWhy = "";
        focusWatch.reset((SystemClock.elapsedRealtime() - captureStartMs) / 1000.0);
    }

    /**
     * Stops a check: its frame forgotten, its buffer back, the lens back at the session's focus. With a reason
     * (logged) the check is off until the stars grow again; without one (a pause, the capture aborted) it runs
     * once frames are taken again.
     */
    private void cancelFocusCheck(String why) {
        Req r = checkReq;
        if (r != null) {
            reqs.remove(r.seq);
            if (r.startNs >= 0) tsToSeq.remove(r.startNs);
            checkReq = null;
        }
        if (checkBuf != null && up != null) up.releaseBuffer(checkBuf);
        checkBuf = null;
        if (focusCheckStep != 0) {
            focusCheckStep = 0;
            focusToken++;
            if (rig != null) rig.setFocus(focusValue);
        }
        if (why == null) return;
        log("focus: check abandoned: " + why);
        focusCheckDue = false;
        focusCheckWhy = "";
        focusWatch.reset((SystemClock.elapsedRealtime() - captureStartMs) / 1000.0);
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
        testBuf = null; // the test frame has been read: its memory goes to the capture
        focusBuf = null;
        focusWatch.reset(0);
        if (locating != null) {
            locating.cancel();
            locating = null;
        }
        try {
            openStore();
        } catch (IOException e) {
            fail("Could not create the session folder: " + e.getMessage());
            return;
        }
        if (settings.endAtMs > 0) {
            int left = (int) ((settings.endAtMs - System.currentTimeMillis()) / 1000);
            if (left < settings.durationSec) {
                settings.durationSec = Math.max(60, left);
                plan.setDuration(settings.durationSec);
                log(String.format(Locale.US, "decision: %d minutes left until the end time (%s): %d frames", Math.round(left / 60f),
                        clockTime(settings.endAtMs), plan.frames));
            }
        }
        BatteryInfo b = BatteryInfo.read(ctx);
        batteryAtStart = b.charging ? -1 : b.pct;
        batteryAtStartMs = SystemClock.elapsedRealtime();
        countdownPasses.clear();
        countdownNext = 0;
        countdownSaid = Integer.MAX_VALUE;
        SatPlanner.Plan sp = preflightReport != null ? preflightReport.satellites : null;
        if (settings.keepSatellites && sp != null) countdownPasses.addAll(sp.countdown);
        phase = Phase.CAPTURING;
        captureStartMs = SystemClock.elapsedRealtime();
        captureStartWallMs = System.currentTimeMillis();
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
        p.setProperty("rejection", Rejection.Method.parse(settings.rejection).name());
        p.setProperty("keepSatellites", Boolean.toString(settings.keepSatellites));
        p.setProperty("blend", Boolean.toString(settings.blend));
        p.setProperty("quick", Boolean.toString(settings.quick));
        if (!settings.presetId.isEmpty()) {
            p.setProperty("preset.id", settings.presetId);
            p.setProperty("preset.name", settings.presetName);
        }
        if (!settings.siteId.isEmpty()) {
            p.setProperty("site.id", settings.siteId);
            p.setProperty("site.name", settings.siteName);
        }
        p.setProperty("state", "capturing");
        sky.toProperties(p);
        p.setProperty("focus.value", Float.toString(focusValue));
        p.setProperty("focus.how", focusHow);
        if (!Double.isNaN(focusTempC)) p.setProperty("focus.tempC", Double.toString(focusTempC));
        if (!Double.isNaN(focusPredicted)) p.setProperty("focus.predicted", Double.toString(focusPredicted));
        FocusSweep.Result fres = focusResult;
        if (fres != null && fres.ok) {
            p.setProperty("focus.frames", Integer.toString(fres.frames));
            if (!Double.isNaN(fres.value)) p.setProperty("focus.hfr", Double.toString(fres.value));
            if (!Double.isNaN(fres.atZero)) p.setProperty("focus.hfrAtZero", Double.toString(fres.atZero));
            p.setProperty("focus.zeroMeasured", Boolean.toString(fres.zeroMeasured));
        }
        p.setProperty("focus.calibrated", Boolean.toString(focusModel != null && focusModel.line() != null));
        Preflight.Report pf = preflightReport;
        if (pf != null) {
            p.setProperty("preflight.summary", pf.spoken());
            if (!Double.isNaN(pf.haloRatio)) p.setProperty("preflight.haloRatio", Double.toString(pf.haloRatio));
            if (!Double.isNaN(pf.haloLimit)) p.setProperty("preflight.haloLimit", Double.toString(pf.haloLimit));
            if (!Double.isNaN(pf.zeroPointNorm)) p.setProperty("preflight.zeroPointNorm", Double.toString(pf.zeroPointNorm));
            Preflight.Item lens = pf.item("lens");
            p.setProperty("preflight.lensWarned", Boolean.toString(lens != null && lens.level == Preflight.Level.WARN));
            // for the site's clear-night share of stars, learned if the session goes well
            Preflight.Item clouds = pf.item("clouds");
            p.setProperty("preflight.cloudWarned", Boolean.toString(clouds != null && clouds.level != Preflight.Level.OK));
            if (pf.expected >= 12 && !Double.isNaN(pf.seenFraction)) p.setProperty("preflight.seenFraction", Double.toString(pf.seenFraction));
            if (!Double.isNaN(pf.sunAltDeg)) p.setProperty("preflight.sunAlt", Double.toString(pf.sunAltDeg));
            StringBuilder w = new StringBuilder();
            for (Preflight.Item it : pf.items) {
                if (it.level != Preflight.Level.WARN && it.level != Preflight.Level.STOP) continue;
                if (w.length() > 0) w.append(',');
                w.append(it.key);
            }
            p.setProperty("preflight.warnings", w.toString());
            p.setProperty("preflight.startedAfterWarnings", Boolean.toString(startedAfterWarnings));
        }
        store.writeSettings(p);
        store.log("Night Sky Camera " + BuildInfo.VERSION + " on " + spec.deviceMake + " " + spec.deviceModel
                + " (" + android.os.Build.FINGERPRINT + ")");
        store.log("camera " + cameraId + ": " + spec.describe());
        store.log("plan: " + plan.describe());
        float tilt = motion.tiltDeg();
        if (!Float.isNaN(tilt)) store.log(String.format(Locale.US, "tilt from vertical %.1f deg", tilt));
        store.log("sky: " + sky.describe() + (place != null ? "; place " + place.describe() : "; no place known"));
        if (pf != null) {
            store.log(String.format(Locale.US, "pre-flight (%d ms): %s", pf.millis, pf.spoken()));
            for (Preflight.Item it : pf.items) store.log("  " + it.level.name().toLowerCase(Locale.US) + " " + it.key + ": " + it.text);
            if (pf.placeSource.startsWith("stars")) {
                store.log(String.format(Locale.US, "decision: place from the stars and the phone's level: %.1f, %.1f (%s)",
                        pf.latDeg, pf.lonDeg, pf.placeSource));
            }
            try {
                store.writeText("preflight.json", pf.toJson());
            } catch (IOException e) {
                store.log("could not save the pre-flight report: " + e.getMessage());
            }
        } else if (!settings.preflight) {
            store.log("pre-flight: off in the settings");
        }
        if (!settings.trails) {
            for (String l : satDataLog.split("\n")) if (!l.isEmpty()) store.log(l);
            store.log(settings.keepSatellites ? "satellites: kept in the picture (as set), bright passes counted down"
                    : "satellites: their streaks are taken out of the stack");
        }
        if (!waitLog.isEmpty()) store.log(waitLog);
        for (String l : focusLog.split("\n")) if (!l.isEmpty()) store.log(l);
        if (!settings.origin.isEmpty()) store.log(settings.origin);
        store.log("settings: " + (settings.presetName.isEmpty() ? "set by hand" : "the " + settings.presetName + " preset")
                + (settings.usesSaved ? "" : ", asked for this session"));
        if (!siteLog.isEmpty()) store.log(siteLog);
        if (!settings.siteId.isEmpty()) Sites.noteSession(prefs, settings.siteId, System.currentTimeMillis());
        String migrated = prefs.settingsMigrated();
        if (!migrated.isEmpty()) {
            store.log("settings: brought up to date " + migrated + " (none reset)");
            prefs.clearSettingsMigrated();
        }
        if (settings.scheduled) {
            store.log(String.format(Locale.US, "scheduled session: %s; stops below %d%% battery%s%s", settings.sounds ? "with sounds" : "silent",
                    settings.minBatteryPct, settings.endAtMs > 0 ? ", at " + clockTime(settings.endAtMs) : "",
                    settings.stopAtDawn ? ", at dawn" : ""));
        }
        for (String l : planLog.split("\n")) if (!l.isEmpty()) store.log(l);
        if (settings.clearTargetSec > 0) {
            store.log(String.format(Locale.US, "collecting %d clear minutes (for at most %d minutes)", Math.round(settings.clearTargetSec / 60f),
                    Math.round(settings.maxDurationSec / 60f)));
        }
        p.setProperty("scheduled", Boolean.toString(settings.scheduled));
        store.writeSettings(p);
        up = new UnitProcessor(store, spec, plan.framesPerUnit, 3, unitListener);
        up.setKeepLatest(remoteWanted);
        liveSpec = spec;
    }

    private void submitNext() {
        if (phase != Phase.CAPTURING || rig == null || !rig.ready()) return;
        if (focusCheckDue || focusCheckStep != 0) {
            // a focus check between frames (F7), once the frames in flight are in
            if (focusCheckStep == 0 && inFlight == 0) startFocusCheck();
            if (focusCheckDue || focusCheckStep != 0) return;
        }
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
        if (framesDone >= plan.frames && !moreForClearSky()) finishCapture(null);
        else submitNext();
    }

    /**
     * "Collect N clear minutes" (the Partly cloudy preset): at the planned end, if the frames judged clear so far
     * (those not judged yet counted as clear) add up to less than the target, capture on, up to the longest
     * allowed. True if it goes on.
     */
    private boolean moreForClearSky() {
        if (settings.clearTargetSec <= 0) return false;
        double unitSec = plan.unitExpNs() / 1e9;
        List<LiveStats.Unit> us = liveStats.from(0);
        double clear = 0;
        for (LiveStats.Unit u : us) if (u.ok && u.reason == null) clear += unitSec;
        int pending = Math.max(0, framesDone / plan.framesPerUnit - us.size());
        clear += pending * unitSec;
        long now = SystemClock.elapsedRealtime();
        long ran = now - captureStartMs - pausedTotalMs;
        if (clear >= settings.clearTargetSec) {
            if (settings.durationSec > 0 && ran > settings.durationSec * 1000L + 30_000L) {
                log(String.format(Locale.US, "decision: %.1f clear minutes collected: done", clear / 60));
            }
            return false;
        }
        long maxMs = Math.max(settings.durationSec, settings.maxDurationSec) * 1000L;
        if (ran >= maxMs - plan.frameExpNs / 1_000_000L) {
            log(String.format(Locale.US, "decision: only %.1f of %d clear minutes by the longest it may run (%d minutes): developing what there is",
                    clear / 60, Math.round(settings.clearTargetSec / 60f), Math.round(maxMs / 60_000f)));
            return false;
        }
        if (settings.endAtMs > 0 && System.currentTimeMillis() >= settings.endAtMs) return false;
        double needSec = settings.clearTargetSec - clear;
        int extra = (int) Math.ceil(needSec * 1e9 / plan.frameExpNs);
        extra = (int) Math.max(plan.framesPerUnit, Math.min(extra, (maxMs - ran) * 1_000_000L / plan.frameExpNs));
        extra = (extra + plan.framesPerUnit - 1) / plan.framesPerUnit * plan.framesPerUnit;
        long unitBytes = 2L * spec.width * spec.height;
        long need = unitBytes * (extra / plan.framesPerUnit + 2) + (400L << 20);
        long free = freeBytes();
        if (free >= 0 && free < need) {
            log("decision: not enough storage to capture longer for clear sky");
            return false;
        }
        plan.frames += extra;
        settings.durationSec += (int) Math.round(extra * plan.frameExpNs / 1e9);
        log(String.format(Locale.US, "decision: %.1f of %d clear minutes so far (clouds): %d more frames, %d minutes in all",
                clear / 60, Math.round(settings.clearTargetSec / 60f), extra, Math.round(settings.durationSec / 60f)));
        publishCapture();
        return true;
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
        cancelFocusCheck(null);
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
            rig.setFocus(focusValue);
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
            // a camera clock of its own: pair it with the wall clock at the start of an exposure (to within the
            // callback's few milliseconds)
            if (!realtimeClock) setClock(System.currentTimeMillis(), timestampNs);
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
            if (r.kind == TEST) {
                if (r != testReq || testBuf == null) return;
                copyRaw(img, testBuf);
                r.buf = testBuf;
                testArrived(r);
                return;
            }
            if (r.kind == FOCUS) {
                if (r != focusReq || focusBuf == null) return;
                copyRaw(img, focusBuf);
                r.buf = focusBuf;
                focusArrived(r);
                return;
            }
            if (r.kind == FOCUS_CHECK) {
                if (r != checkReq || checkBuf == null) return;
                copyRaw(img, checkBuf);
                r.buf = checkBuf;
                checkArrived(r);
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
            else if (r.kind == TEST) testArrived(r);
            else if (r.kind == FOCUS) focusArrived(r);
            else if (r.kind == FOCUS_CHECK) checkArrived(r);
            else frameArrived(r);
        }

        @Override
        public void onFailed(int seq, boolean imageCaptured) {
            Req r = reqs.get(seq);
            if (r == null) return;
            if (r.kind == FOCUS) {
                boolean current = r == focusReq;
                reqs.remove(seq);
                if (r.startNs >= 0) tsToSeq.remove(r.startNs);
                if (!current) return;
                focusReq = null;
                if (phase != Phase.FOCUS) return;
                long now = SystemClock.elapsedRealtime();
                if (++focusRetries <= 2) sendFocus(now);
                else stopFocus(now, "the focus frames failed");
                return;
            }
            if (r.kind == FOCUS_CHECK) {
                if (r == checkReq) {
                    cancelFocusCheck("a check frame failed");
                    submitNext();
                } else {
                    reqs.remove(seq);
                    if (r.startNs >= 0) tsToSeq.remove(r.startNs);
                }
                return;
            }
            if (r.kind == TEST) {
                boolean current = r == testReq;
                forgetTest();
                reqs.remove(seq);
                if (!current || phase != Phase.PREFLIGHT || pfStep != PF_TEST) return;
                long now = SystemClock.elapsedRealtime();
                if (++testRetries <= 1) sendTest(now);
                else skipPreflight(now, "the test frame failed, so the sky wasn't checked");
                return;
            }
            if (r.kind == PROBE) {
                reqs.remove(seq);
                probe = null;
                if (++probeRetries <= 1 && (phase == Phase.COUNTDOWN || phase == Phase.PREFLIGHT || phase == Phase.FOCUS)) {
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
            else if (r.kind == TEST) testArrived(r);
            else if (r.kind == FOCUS) focusArrived(r);
            else if (r.kind == FOCUS_CHECK) checkArrived(r);
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
            LiveStats.Unit lu = liveStats.add(rec, live, System.currentTimeMillis());
            final double ut = rec.t;
            final float uh = lu.hfr, utr = lu.transparency;
            final boolean uok = lu.ok && !lu.disturbed;
            h.post(new Runnable() {
                @Override
                public void run() {
                    watchFocus(ut, uh, utr, uok);
                }
            });
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
        noteBatteryUse();
        if (frames > 0 && store != null) recordCapture(store);
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

    /** Every two seconds while capturing: how hot the phone says it is (for the report). */
    private void noteThermal(long now) {
        if (now - thermalCheckedMs < 2000) return;
        thermalCheckedMs = now;
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm == null) return;
        int st = pm.getCurrentThermalStatus();
        if (st > thermalMax) {
            thermalMax = st;
            log("thermal status " + st);
        }
        if (st >= PowerManager.THERMAL_STATUS_SEVERE && thermalSevereAtMs == 0) thermalSevereAtMs = System.currentTimeMillis();
    }

    /** What the capture saw that the frames don't show, into the session's settings for the report. */
    private void recordCapture(SessionStore st) {
        try {
            Properties p = st.readSettings();
            p.setProperty("capture.framesDone", Integer.toString(framesDone));
            p.setProperty("capture.framesDropped", Integer.toString(framesDropped));
            p.setProperty("capture.thermalMax", Integer.toString(thermalMax));
            if (thermalSevereAtMs > 0) p.setProperty("capture.thermalSevereAtMs", Long.toString(thermalSevereAtMs));
            if (!stopReason.isEmpty()) p.setProperty("capture.stopReason", stopReason);
            if (captureStartWallMs > 0) p.setProperty("capture.startWallMs", Long.toString(captureStartWallMs));
            p.setProperty("capture.durationSec", Integer.toString(settings.durationSec));
            p.setProperty("focus.final", Float.toString(focusValue));
            if (!focusChecks.isEmpty()) p.setProperty("focus.checks", focusChecks);
            st.writeSettings(p);
        } catch (IOException e) {
            st.log("could not note the capture's details: " + e.getMessage());
        }
    }

    /** How fast this session used the battery, for the next pre-flight's estimate (only when not charging). */
    private void noteBatteryUse() {
        if (batteryAtStart < 0) return;
        BatteryInfo b = BatteryInfo.read(ctx);
        double hours = (SystemClock.elapsedRealtime() - batteryAtStartMs) / 3.6e6;
        int drop = batteryAtStart - b.pct;
        batteryAtStart = -1;
        if (b.charging || b.pct < 0 || hours < 0.05) return;
        if (drop >= 2) {
            double rate = drop / hours;
            prefs.learnDrain(rate, hours);
            log(String.format(Locale.US, "battery: %d%% used in %.0f min (%.0f%% an hour)", drop, hours * 60, rate));
        }
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
        previewBase = null;
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
            Properties props = new Properties();
            try {
                props = st.readSettings();
                // the rejection chosen when the session started, so redeveloping a session gives the same result
                pp.rejection = Rejection.Method.parse(props.getProperty("rejection"));
            } catch (IOException e) {
                st.log("could not read the session settings: " + e.getMessage());
            }
            // where and when: lets the stars be named and the frames placed on the sky
            SessionSky sk = SessionSky.fromProperties(props);
            sk.catalog = SkyCatalog.get(ctx);
            pp.sky = sk;
            // the satellites: named where they crossed, and taken out (or kept, as set when it started)
            pp.keepSatellites = "true".equals(props.getProperty("keepSatellites"));
            // the cloud-blur version (C7), and quick developing (Quick look), as set when it started
            pp.blend = "true".equals(props.getProperty("blend"));
            boolean quick = "true".equals(props.getProperty("quick"));
            if (quick) {
                pp.patternCleanup = false;
                pp.streakCorridors = false;
            }
            if (!trails) {
                if (!prefs.satelliteData()) {
                    pp.satellitesMissing = "satellite data is off in the settings";
                } else {
                    try {
                        long ms = sk.clockValid ? sk.clockWallMs : System.currentTimeMillis();
                        pp.satellites = Satellites.catalog(ctx, Astro.jdUtc(ms / 1000.0));
                    } catch (RuntimeException e) {
                        pp.satellitesMissing = "the satellite data couldn't be read";
                        st.log("satellites: the data couldn't be read: " + e);
                    }
                }
            }
            PostProcessor.Output o = pp.run(trails, progress);
            if (quick) {
                String q = "quick developing (Quick look): one stacking pass, the sensor's pattern not measured and the streaks' "
                        + "faint edges not stacked out again, and a half-size photo";
                o.decisions.add(q);
                st.log("decision: " + q);
            }
            learnFromSession(props, o, sp, st);
            ReportCard card = report(o, sk, props, st);
            List<SatOverlay.Line> tracks = SatOverlay.lines(o.satTracks, o.streaks, o.analysis.cx0, o.analysis.cy0, o.analysis.cw,
                    o.analysis.ch);
            String name = "NightSky_" + st.id() + (trails ? "_trails" : "");
            int factor = quick ? 2 : 1;
            post(Phase.DEVELOPING, "Saving", quick ? "Half-size photo" : "Full-size photo", -1f);
            Uri jpeg = saveJpeg(o, sp, brightness, name, null, factor);
            Uri blendUri = null;
            if (o.blend != null) {
                post(Phase.DEVELOPING, "Saving", "The cloud-blur version", -1f);
                try {
                    blendUri = saveBlend(o, sp, brightness, name + "_blend");
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    st.log("cloud blur: not saved: " + e);
                }
                o.blend = null; // its memory, for the result screen
            }
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
            r.session = st.dir.getName();
            r.trails = trails;
            r.startedMs = parseLong(props.getProperty("capture.startWallMs"));
            r.durationSec = (int) parseLong(props.getProperty("capture.durationSec", props.getProperty("durationSec")));
            r.jpeg = jpeg;
            r.jpegFactor = factor;
            r.blend = blendUri;
            r.dng = dng;
            r.log = log;
            r.canAdjust = true;
            r.brightness = brightness;
            r.report = card;
            r.reportJson = card != null ? card.toJson() : null;
            r.tracks = tracks;
            r.canLabel = card != null && card.sky != null && !card.sky.items.isEmpty();
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
                    previewBase = bmp;
                    preview = decorate(bmp);
                    previewVersion++;
                    phase = Phase.DONE;
                    outcome = NightPlan.Outcome.DONE;
                    outcomeDetail = doneWords(r, out);
                    publish(trails ? "Your star trails are ready" : "Your photo is ready", r.summary, -1);
                    if (snd != null) {
                        snd.say(r.report != null ? r.report.spoken : spoken(out), new Runnable() {
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
                    outcome = NightPlan.Outcome.CANCELLED;
                    outcomeDetail = "Developing was cancelled; the frames are kept";
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

    /** The session's report card, saved with the session and in its log; null if it couldn't be made. */
    private ReportCard report(PostProcessor.Output o, SessionSky sk, Properties props, SessionStore st) {
        try {
            ReportCard.Input in = new ReportCard.Input();
            in.out = o;
            in.sky = sk;
            in.props = props;
            in.zone = TimeZone.getDefault();
            in.catalog = sk.catalog;
            ReportCard card = ReportCard.build(in);
            st.log("report:\n" + card.describe());
            try {
                st.writeText("report.json", card.toJson());
            } catch (IOException e) {
                st.log("could not save the report: " + e.getMessage());
            }
            return card;
        } catch (RuntimeException e) {
            st.log("report failed: " + e);
            for (StackTraceElement el : e.getStackTrace()) st.log("  at " + el);
            return null;
        }
    }

    /**
     * A good session (most frames kept) teaches the next pre-flight check what this camera's clean lens and a
     * clear night look like.
     */
    private void learnFromSession(Properties props, PostProcessor.Output o, SensorSpec sp, SessionStore st) {
        if (o.unitsTotal <= 0 || o.unitsUsed < 0.6 * o.unitsTotal || sp.cameraId.isEmpty()) return;
        double halo = parseDouble(props.getProperty("preflight.haloRatio"));
        boolean lensWarned = "true".equals(props.getProperty("preflight.lensWarned"));
        if (!Double.isNaN(halo) && !lensWarned) prefs.learnHalo(sp.cameraId, halo);
        double zp = parseDouble(props.getProperty("preflight.zeroPointNorm"));
        if (!Double.isNaN(zp)) prefs.learnZeroPoint(sp.cameraId, zp);
        if (!Double.isNaN(halo) || !Double.isNaN(zp)) {
            st.log(String.format(Locale.US, "learned for the next check: clean lens halo %.3f, clear night zero point %.2f",
                    prefs.haloBaseline(sp.cameraId), prefs.clearZeroPoint(sp.cameraId)));
        }
        // a clear, dark night at a saved site: the share of the stars its check saw is what a clear night there shows
        String siteId = props.getProperty("site.id", "");
        double seen = parseDouble(props.getProperty("preflight.seenFraction"));
        double sunAlt = parseDouble(props.getProperty("preflight.sunAlt"));
        if (!siteId.isEmpty() && !Double.isNaN(seen) && !"true".equals(props.getProperty("preflight.cloudWarned"))
                && o.unitsUsed >= 0.8 * o.unitsTotal && o.unitsPartly <= 0.2 * o.unitsTotal && !(sunAlt > -12)) {
            Sites.learnClear(prefs, siteId, seen);
            st.log(String.format(Locale.US, "learned for the %s site: a clear night there shows %.0f%% of the catalogue's stars",
                    props.getProperty("site.name", ""), 100 * seen));
        }
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return s == null ? Double.NaN : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** "18 good frames, like a 4-minute exposure. Thin cloud after 11:40." for the plan's summary. */
    private static String doneWords(Result r, PostProcessor.Output o) {
        String s = r.report != null ? r.report.spoken : spoken(o);
        if (s.startsWith("Done. ")) s = s.substring(6);
        return s.isEmpty() ? r.summary : s;
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String spoken(PostProcessor.Output o) {
        if (o.unitsTotal > 0 && o.unitsUsed < o.unitsTotal) {
            return String.format(Locale.US, "Done. %d of %d frames kept.", o.unitsUsed, o.unitsTotal);
        }
        return "Done. Your photo is ready.";
    }

    /** The photo as a JPEG, {@code factor} photo pixels to a JPEG pixel each way (1: full size), with {@code d} drawn on. */
    private Uri saveJpeg(PostProcessor.Output o, SensorSpec sp, float brightness, String name, Decor d, int factor) throws IOException {
        final Renderer.Params p = new Renderer.Params();
        p.brightness = brightness;
        RgbImage sm = factor > 1 ? o.image.binned(factor) : null;
        final int cw = sm != null ? sm.cropWidth() : o.analysis.cw, ch = sm != null ? sm.cropHeight() : o.analysis.ch;
        final Bitmap bmp = Bitmap.createBitmap(cw, ch, Bitmap.Config.ARGB_8888);
        try {
            if (sm != null) {
                // smaller (Quick look): the full photo's stretch, on its binned copy
                int[] px = Renderer.renderSmall(sm, o.analysis, p, factor);
                sm = null;
                bmp.setPixels(px, 0, cw, 0, 0, cw, ch);
            } else {
                Renderer.render(o.image, o.analysis, p, new Renderer.RowSink() {
                    @Override
                    public void rows(int y, int count, int[] argb, int width) {
                        synchronized (bmp) {
                            bmp.setPixels(argb, 0, width, 0, y, width, count);
                        }
                    }
                }, null);
            }
            if (d != null) TrackPainter.draw(bmp, o.analysis.cx0, o.analysis.cy0, factor, d.tracks, d.sky, d.showTracks, d.showLabels);
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

    /** The cloud-blur version (C7), with a stretch of its own: clouds and trails make it a different picture. */
    private Uri saveBlend(PostProcessor.Output o, SensorSpec sp, float brightness, String name) throws IOException {
        PostProcessor.Output b = new PostProcessor.Output();
        b.image = o.blend;
        b.analysis = Renderer.analyze(o.blend, sp);
        b.integrationSeconds = o.blendSeconds;
        b.summary = String.format(Locale.US, "Cloud blur: the plain average of %d frames (%s), not lined up", o.blendFrames,
                PostProcessor.duration(o.blendSeconds));
        return saveJpeg(b, sp, brightness, name, null, 1);
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
                // the stars' white balance as the DNG's as-shot one, when it was measured (F4)
                DngWriter.write(os, o.image, o.colourSpec != null ? o.colourSpec : sp, meta, o.maxGain);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Cancel, fail, release
    // ------------------------------------------------------------------------------------------------

    private void cancelAll() {
        token++;
        startToken++;
        stopLocating();
        abortInFlight();
        releaseCapture();
        if (up != null) up.abort();
        if (store != null) store.deleteAll();
        up = null;
        store = null;
        phase = Phase.IDLE;
        outcome = NightPlan.Outcome.CANCELLED;
        outcomeDetail = "Cancelled";
        publish("", "", -1);
        host.onCaptureEnded();
        host.onIdle();
    }

    /**
     * A scheduled attempt that didn't start: too cloudy, the phone not lying still, the battery too low. The
     * camera closes and the plan decides what next (another try later, or the next session).
     */
    private void endAttempt(NightPlan.Outcome o, String detail) {
        android.util.Log.i("NightSky", "scheduled attempt ended: " + o + ": " + detail);
        token++;
        startToken++;
        stopLocating();
        abortInFlight();
        releaseCapture();
        if (up != null) up.abort();
        if (store != null) store.deleteAll();
        up = null;
        store = null;
        phase = Phase.IDLE;
        outcome = o;
        outcomeDetail = detail;
        String head = o == NightPlan.Outcome.CLOUDY ? "Not clear enough to start"
                : o == NightPlan.Outcome.NOT_PLACED ? "The phone wasn't lying still" : "Not started";
        publish(head, detail, -1);
        host.onCaptureEnded();
        host.onIdle();
    }

    private void fail(String msg) {
        token++;
        startToken++;
        stopLocating();
        if (store != null) store.log("failed: " + msg);
        releaseCapture();
        if (up != null) {
            up.abort();
            up = null;
        }
        store = null;
        phase = Phase.FAILED;
        outcome = NightPlan.Outcome.FAILED;
        outcomeDetail = msg;
        publish("Something went wrong", msg, -1);
        host.onCaptureEnded();
        host.onIdle();
    }

    private void stopLocating() {
        if (locating != null) {
            locating.cancel();
            locating = null;
        }
        testBuf = null;
        focusBuf = null;
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
        s.preflight = preflightReport;
        s.storageShort = storageShort;
        s.outcome = outcome;
        s.outcomeDetail = outcomeDetail;
        s.scheduled = settings != null && settings.scheduled;
        s.reading = reading;
        s.siteLine = siteLine;
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

    static long sizeOf(File dir) {
        return new SessionStore(dir).sizeBytes();
    }
}
