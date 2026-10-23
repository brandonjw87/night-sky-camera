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
import android.os.Looper;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.util.Range;
import android.util.Size;

import com.brandon.nightsky.processing.Aligner;
import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CancelledException;
import com.brandon.nightsky.processing.DngWriter;
import com.brandon.nightsky.processing.EdgeMask;
import com.brandon.nightsky.processing.FocusMeter;
import com.brandon.nightsky.processing.FocusModel;
import com.brandon.nightsky.processing.FocusSweep;
import com.brandon.nightsky.processing.FocusWatch;
import com.brandon.nightsky.processing.Framing;
import com.brandon.nightsky.processing.LiveStats;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.MergeProcessor;
import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.Preset;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Rejection;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatOverlay;
import com.brandon.nightsky.processing.SatPlanner;
import com.brandon.nightsky.processing.SatPredict;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionLibrary;
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
        /**
         * "Add 5 minutes" after a session (G1): the folder of the session these minutes add to ("" if none), and the
         * focus it ended with (NaN if unknown). No check of the sky and no focusing: the phone was just there.
         */
        String continuesFrom = "";
        float continueFocus = Float.NaN;
        /** "Repeat tomorrow" (E2): the folder of the session this one repeats ("" if none), offered for merging after. */
        String repeatOf = "";
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
        /** How the photo shown was made (G3; null if its recipe couldn't be saved), and its stack's master (null if none). */
        Recipe recipe;
        Master.Meta master;
        /** North's direction at the photo's centre (x right, y down), for turning it north up; null if the sky wasn't solved. */
        double[] north;
        /** When developing finished (Unix ms): "Add 5 minutes" is offered for a while after. */
        long endedMs;
        /** A merge's parts, coverage and total (G1); null for one session. */
        MergeProcessor.Result merge;
        /** The session a "Repeat tomorrow" session repeats (its folder's name; "" if none): offered for merging. */
        String repeatOf = "";
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
    /** The result's preview without the tracks and names drawn on it, before its crop and turn ({@link #previewFraming}). */
    private Bitmap previewBase;
    /** How the preview is cut and turned (G3), or null for as it is. */
    private Framing previewFraming;
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
    /** Why the capture stopped before its planned end, for the report (empty if it didn't), and the kind of reason:
     *  you, pickedUp, moved, camera, taken, battery, storage, hot, end, dawn, slow or processing. */
    private String stopReason = "", stopKey = "";
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
    /** Times the capture paused because the phone moved; frames the camera failed or lost (framesDropped counts
     *  only those processing had no room for); times the camera went silent and was restarted, and for how long
     *  (for the report). */
    private int pauses, framesFailed, stalls;
    private long stalledMs;
    /** Times the camera was opened again after an error as it opened (once: such errors often pass). */
    private int openRetries;
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
                    finishCapture("you", "stopped by you");
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

    /** How long after a session "Add 5 minutes" is offered (the phone is likely still where it was). */
    static final long ADD_AFTER_MS = 30 * 60_000L;

    /**
     * Settings for "Add 5 minutes" after a session (G1): that session's own settings (rejection, satellites, DNG,
     * site), {@code seconds} more, no check of the sky and the focus it ended with. When they're developed, they're
     * stacked with it (and any minutes added to it before). Throws if the session can't be read.
     */
    static Settings continuation(Prefs prefs, File root, String baseFolder, int seconds) throws IOException {
        SessionStore base = new SessionStore(new File(root, baseFolder));
        Properties p = base.readSettings();
        if (p.isEmpty()) throw new IOException("the session is gone");
        // minutes added to minutes added: all of them add to the first session
        String first = p.getProperty("continuesFrom", "");
        if (!first.isEmpty() && new File(new File(root, first), "session.properties").isFile()) {
            Properties fp = new SessionStore(new File(root, first)).readSettings();
            if (!fp.isEmpty()) {
                baseFolder = first;
                p = fp;
            }
        }
        Settings s = new Settings();
        s.trails = false;
        s.durationSec = seconds;
        s.brightness = parseFloat(p.getProperty("brightness"), prefs.brightness());
        s.sounds = prefs.sounds();
        s.saveDng = !"false".equals(p.getProperty("saveDng"));
        s.preflight = false;
        s.rejection = p.getProperty("rejection", "AUTO");
        s.keepSatellites = "true".equals(p.getProperty("keepSatellites"));
        s.quick = "true".equals(p.getProperty("quick"));
        s.siteId = p.getProperty("site.id", "");
        s.siteName = p.getProperty("site.name", "");
        s.presetId = p.getProperty("preset.id", "");
        s.presetName = p.getProperty("preset.name", "");
        s.continuesFrom = baseFolder;
        // the latest focus: the minutes added last, else the session's
        float focus = parseFloat(base.readSettings().getProperty("focus.final"), Float.NaN);
        if (Float.isNaN(focus)) focus = parseFloat(p.getProperty("focus.final"), Float.NaN);
        s.continueFocus = focus;
        s.origin = "Add " + Math.round(seconds / 60f) + " minutes to session " + baseFolder.replace("session_", "");
        return s;
    }

    /**
     * Whether the photo shown can have minutes added to it now (G1): a deep-sky photo of a session (or minutes added
     * to one) that ended in the last half hour, whose frames are still kept.
     */
    boolean canAddAfter() {
        Status s = status;
        Result r = s.result;
        if (s.phase != Phase.DONE || r == null || r.trails || r.endedMs <= 0 || (r.merge != null && r.recipe != null
                && "merged".equals(r.recipe.note))) {
            return false;
        }
        if (System.currentTimeMillis() - r.endedMs > ADD_AFTER_MS) return false;
        File frames = new File(new File(root, r.session), "frames");
        String[] l = frames.list();
        return l != null && l.length > 0;
    }

    /**
     * Stacks sessions chosen in the library as one photo (G1), the first as the base, each with the minutes added to
     * it. Runs like developing, with the result shown when it's done.
     */
    void merge(final List<File> chosen) {
        final List<File> dirs = withAdded(chosen);
        h.post(new Runnable() {
            @Override
            public void run() {
                if (phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.FAILED) return;
                if (dirs.size() < 2) return;
                clearForJob();
                phase = Phase.FINISHING;
                publish("Merging", "Reading the sessions", -1);
                final SessionStore base = new SessionStore(dirs.get(0));
                final float brightness = parseFloat(readProperty(base, "brightness"), prefs.brightness());
                final boolean dng = !"false".equals(readProperty(base, "saveDng"));
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        runDevelop(base, brightness, dng, null, JOB_MERGE, 0, "Reading the sessions", new Maker() {
                            @Override
                            public Made make(Progress progress) throws IOException {
                                List<SessionStore> st = new ArrayList<SessionStore>();
                                for (File d : dirs) st.add(new SessionStore(d));
                                return developMerge(st, false, brightness, progress);
                            }
                        });
                    }
                }, "merge").start();
            }
        });
    }

    /**
     * Shows a kept session's latest photo again from its master (G1, G3): no stacking, so it's quick, and the
     * brightness can change as after developing.
     */
    void openSession(File dir) {
        openRender(dir, 0);
    }

    /**
     * Shows one of a kept session's renders (G3; 0 for the latest kept): from its stack's master, or made again from
     * its recipe when that master was deleted to make room and the frames are still kept.
     */
    void openRender(final File dir, final int number) {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (phase != Phase.IDLE && phase != Phase.DONE && phase != Phase.FAILED) return;
                clearForJob();
                phase = Phase.FINISHING;
                publish("Opening", "Reading the session's photo", -1);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        doOpen(dir, number, null, null);
                    }
                }, "open").start();
            }
        });
    }

    /** The sessions chosen, each followed by the minutes added to it (developed, frames kept), oldest first. */
    private List<File> withAdded(List<File> chosen) {
        List<File> out = new ArrayList<File>();
        List<SessionStore> all = SessionStore.list(root);
        java.util.Collections.reverse(all);
        for (File d : chosen) {
            out.add(d);
            for (SessionStore s : all) {
                if (s.dir.equals(d) || chosen.contains(s.dir)) continue;
                try {
                    Properties p = s.readSettings();
                    String[] fl = s.framesDir.list();
                    if (d.getName().equals(p.getProperty("continuesFrom")) && "developed".equals(p.getProperty("state")) && fl != null
                            && fl.length > 0) {
                        out.add(s.dir);
                    }
                } catch (IOException ignored) {
                }
            }
        }
        return out;
    }

    /** Frees the last result and capture state before a job on a kept session. */
    private void clearForJob() {
        output = null;
        small = null;
        result = null;
        preview = null;
        previewBase = null;
        previewFraming = null;
        cancelDevelop = false;
        resetCapture();
        settings = new Settings();
        settings.sounds = false;
        token++;
    }

    private static String readProperty(SessionStore st, String key) {
        try {
            return st.readSettings().getProperty(key);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * On a thread of its own: render {@code number} of the session (0: the latest whose stack is kept) shown as a
     * result, from its stack's master; a render whose master was deleted to make room is made again from its recipe.
     * {@code headline} and {@code detail} say why it's shown (null for the usual words).
     */
    private void doOpen(File dir, int number, String headline, String detail) {
        final SessionStore st = new SessionStore(dir);
        try {
            Properties props = st.readSettings();
            SensorSpec sp = SensorSpec.fromProperties(props);
            List<Recipe> all = Recipe.list(dir);
            Recipe rc = null;
            Master.Loaded l = null;
            if (number > 0) {
                for (Recipe x : all) if (x.number == number) rc = x;
                if (rc == null) throw new IOException("render " + number + " isn't in the session's folder");
                if (!rc.stackKey.isEmpty()) l = Master.read(dir, rc.stackKey);
                if (l == null) {
                    // its stack was deleted to make room: made again from its recipe while the frames are kept
                    makeAgain(st, rc);
                    return;
                }
            } else {
                for (int i = all.size() - 1; i >= 0 && l == null; i--) {
                    Recipe x = all.get(i);
                    if (!x.stackKey.isEmpty()) l = Master.read(dir, x.stackKey);
                    if (l != null) rc = x;
                }
                if (l == null) {
                    for (Master.Meta mm : Master.list(dir)) {
                        l = Master.read(dir, mm.key);
                        if (l != null) break;
                    }
                }
            }
            if (l == null) throw new IOException("it has no stacked picture kept (it was developed before version 1.2)");
            Opened op = open(st, props, sp, rc, l);
            st.log("opened from master " + l.meta.key + (rc != null ? String.format(Locale.US, " (render r%03d)", rc.number) : ""));
            show(op, headline, detail);
        } catch (final Throwable t) {
            st.log("opening failed: " + t);
            h.post(new Runnable() {
                @Override
                public void run() {
                    fail("Couldn't open that session: " + (t instanceof OutOfMemoryError ? "out of memory" : t.getMessage()) + ".");
                }
            });
        }
    }

    /** A stack and how it's shown, ready to be the result (made off the session thread). */
    private static final class Opened {
        PostProcessor.Output o;
        SensorSpec spec;
        RgbImage small;
        int factor;
        Bitmap bmp;
        Framing framing;
        Result r;
        String headline = "";
    }

    /** A master read back, with the session's report for it, rendered as {@code rc} says (null: the develop step's settings). */
    private Opened open(SessionStore st, Properties props, SensorSpec sp, Recipe rc, Master.Loaded l) {
        PostProcessor.Output o = outputOf(l, sp);
        float brightness = rc != null ? rc.brightness : parseFloat(props.getProperty("brightness"), prefs.brightness());
        int f = Math.max(1, (int) Math.ceil(o.analysis.cw / 1600.0));
        RgbImage sm = o.image.binned(f);
        double[] north = northOf(o, l.meta.solutionR);
        Result r = new Result();
        r.summary = o.summary;
        r.name = rc != null && !rc.name.isEmpty() ? rc.name : "NightSky_" + st.id();
        r.session = st.dir.getName();
        r.trails = l.meta.trails;
        r.startedMs = parseLong(props.getProperty("capture.startWallMs"));
        r.durationSec = (int) Math.round(l.meta.integrationSeconds);
        if (rc != null && !rc.uri.isEmpty()) r.jpeg = Uri.parse(rc.uri);
        r.jpegFactor = rc != null && rc.quick ? 2 : 1;
        r.canAdjust = true;
        r.brightness = brightness;
        // the report made with this stack; the develop step's from before reports were kept with masters
        r.merge = MergeProcessor.readDescription(l.meta.merge);
        String rj = Master.readReport(st.dir, l.meta.key);
        if (rj == null && l.meta.develop && l.meta.sessions.size() <= 1) rj = st.readText("report.json");
        if (rj != null) {
            try {
                r.report = ReportCard.fromJson(rj);
                r.reportJson = rj;
            } catch (RuntimeException ignored) {
            }
        }
        // the sky's names are where the stack put them; the satellites' tracks aren't kept
        r.canLabel = r.report != null && r.report.sky != null && !r.report.sky.items.isEmpty();
        r.recipe = rc;
        r.master = l.meta;
        r.north = north;
        r.repeatOf = props.getProperty("repeatOf", "");
        Opened op = new Opened();
        op.o = o;
        op.spec = sp;
        op.small = sm;
        op.factor = f;
        op.r = r;
        op.bmp = previewBitmap(sm, o.analysis, paramsOf(rc, brightness), f);
        op.framing = frameFor(rc, north, sm.cropWidth(), sm.cropHeight());
        op.headline = "Session of " + (r.startedMs > 0 ? nightLabel(r.startedMs) : st.id());
        return op;
    }

    /** A master as developing's output: the stack, its colour and analysis, its lens model and reference frame. */
    private static PostProcessor.Output outputOf(Master.Loaded l, SensorSpec sp) {
        PostProcessor.Output o = new PostProcessor.Output();
        o.image = l.image;
        SensorSpec cs = Master.colourSpec(sp, l.meta);
        if (l.meta.colourFromStars) o.colourSpec = cs;
        o.analysis = Master.analyze(l.image, cs, l.meta.trails);
        o.summary = l.meta.summary;
        o.integrationSeconds = l.meta.integrationSeconds;
        o.unitsUsed = l.meta.unitsUsed;
        o.unitsTotal = l.meta.unitsTotal;
        o.maxGain = l.meta.maxGain;
        o.model = l.meta.model;
        o.referenceUnit = l.meta.referenceUnit;
        o.aligned = true;
        o.trails = l.meta.trails;
        return o;
    }

    /** Makes {@code op} the result on screen ({@code headline} and {@code detail} null: the usual words). */
    private void show(final Opened op, final String headline, final String detail) {
        h.post(new Runnable() {
            @Override
            public void run() {
                output = op.o;
                outSpec = op.spec;
                small = op.small;
                smallFactor = op.factor;
                result = op.r;
                previewFraming = op.framing;
                previewBase = op.bmp;
                preview = decorate(op.bmp);
                previewVersion++;
                phase = Phase.DONE;
                publish(headline != null ? headline : op.headline, detail != null ? detail : op.r.summary, -1);
                host.onIdle();
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
                previewBase = previewBitmap(small, output.analysis, paramsOf(result != null ? result.recipe : null, brightness), smallFactor);
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
                            String[] hash = new String[1];
                            // a new render of the same stack: its own recipe, the earlier ones untouched
                            Recipe nr = r.recipe != null ? r.recipe.edit() : null;
                            if (nr != null) {
                                nr.brightness = brightness;
                                nr.note = "brightness changed";
                            }
                            r.jpeg = saveJpeg(o, sp, paramsOf(nr, brightness), nr, r.north, name, null, r.jpegFactor, hash);
                            r.brightness = brightness;
                            prefs.setLastResultUri(r.jpeg.toString());
                            if (nr != null) {
                                SessionStore st = new SessionStore(new File(root, r.session));
                                Recipe saved = saveRecipe(st, nr, r.master, o, name, r.jpeg, hash[0]);
                                if (saved != null) r.recipe = saved;
                            }
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
                            r.labelled = saveJpeg(o, sp, paramsOf(r.recipe, brightness), r.recipe, r.north, r.name + "_labelled", d,
                                    r.jpegFactor, null);
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

    /**
     * The preview with the satellites' tracks and the sky's names, as the result screen's switches say, then cut and
     * turned as its render says.
     */
    private Bitmap decorate(Bitmap base) {
        Result r = result;
        PostProcessor.Output o = output;
        if (base == null || r == null || o == null) return base;
        Bitmap b = base;
        try {
            b = TrackPainter.paint(base, o.analysis.cx0, o.analysis.cy0, smallFactor, r.tracks,
                    r.report != null ? r.report.sky : null, prefs.showTracks(), prefs.showLabels());
        } catch (RuntimeException | OutOfMemoryError e) {
            android.util.Log.w("NightSky", "could not draw the tracks and names: " + e);
        }
        return frame(b, previewFraming);
    }

    /** The bitmap cut and turned as {@code f} says (the same one when f leaves it as it is, or doesn't fit it). */
    private static Bitmap frame(Bitmap b, Framing f) {
        if (b == null || f == null || f.identity() || b.getWidth() != f.w || b.getHeight() != f.h) return b;
        int[] px = new int[f.w * f.h];
        b.getPixels(px, 0, f.w, 0, 0, f.w, f.h);
        return Bitmap.createBitmap(f.apply(px), f.outW, f.outH, Bitmap.Config.ARGB_8888);
    }

    /** The on-screen copy of the small stack rendered as {@code rc} says (before its framing). */
    private static Bitmap previewBitmap(RgbImage sm, Renderer.Analysis a, Renderer.Params p, int factor) {
        int[] px = Renderer.renderSmall(sm, a, p, factor);
        return Bitmap.createBitmap(px, sm.cropWidth(), sm.cropHeight(), Bitmap.Config.ARGB_8888);
    }

    /** How a recipe cuts and turns a w x h copy of the photo, {@code north} its north (null: as it is). */
    private static Framing frameFor(Recipe rc, double[] north, int w, int h) {
        if (rc == null) return null;
        Framing f = rc.framing(w, h, rc.northUp ? north : null);
        return f.identity() ? null : f;
    }

    /** North's direction at the centre of the output's photo, from where the stack pointed ({@code R}); null if unknown. */
    private static double[] northOf(PostProcessor.Output o, double[] R) {
        Renderer.Analysis a = o.analysis;
        return a == null ? null : Framing.north(o.model, R, a.cx0 + a.cw / 2.0, a.cy0 + a.ch / 2.0);
    }

    /** A render's settings: the recipe's (with {@code brightness} when it isn't NaN), else the develop step's. */
    private static Renderer.Params paramsOf(Recipe rc, float brightness) {
        Renderer.Params p = rc != null ? rc.params() : new Renderer.Params();
        if (!Float.isNaN(brightness)) p.brightness = brightness;
        return p;
    }

    // ------------------------------------------------------------------------------------------------
    // Editing (G3): every change is a new render with its own recipe; nothing saved is written over
    // ------------------------------------------------------------------------------------------------

    /** What the edit screen shows and changes, read off the main thread ({@link #editInfo}). */
    static final class EditInfo {
        /** The photo's recipe, and a copy of it to change. */
        Recipe was, recipe;
        /** The session's frames as developing graded them, in time order (empty when the stack can't change). */
        ReportCard.Frame[] frames = new ReportCard.Frame[0];
        /** The frame the stack was lined up on (-1 if unknown). */
        int referenceUnit = -1;
        /** Why the stack can't change, so only how it's rendered can ("" when it can). */
        String noRestack = "";
        /** Why it can't be turned north up ("" when it can). */
        String noNorth = "";
        /** The automatic foreground, in cells of the sensor (null if none was found or it couldn't be looked for). */
        boolean[] autoGround;
        /** The painting to start from (blank when the recipe has none; null when the stack can't change). */
        EdgeMask painting;
        /** The photo's crop of the sensor, and sensor pixels per pixel of the preview. */
        int cx0, cy0, cw, ch, factor;
        /** The sensor's size. */
        int width, height;
        /** The session's renders, oldest first, and its folder. */
        List<Recipe> renders = new ArrayList<Recipe>();
        File dir;
    }

    interface EditCallback {
        /** On the main thread: what editing needs, or why it can't be edited (info null). */
        void ready(EditInfo info, String why);
    }

    /** Reads what the edit screen needs (slow: the session's average, for the foreground), then calls back on the main thread. */
    void editInfo(final EditCallback cb) {
        final Handler ui = new Handler(Looper.getMainLooper());
        h.post(new Runnable() {
            @Override
            public void run() {
                final Result r = result;
                final PostProcessor.Output o = output;
                final SensorSpec sp = outSpec;
                final int f = smallFactor;
                final String why = r == null || o == null || phase != Phase.DONE ? "There's no photo to edit."
                        : r.recipe == null ? "This photo's recipe wasn't kept, so it can't be edited." : null;
                if (why != null) {
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.ready(null, why);
                        }
                    });
                    return;
                }
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final EditInfo in = editInfo(r, o, sp, f);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.ready(in, null);
                            }
                        });
                    }
                }, "edit-info").start();
            }
        });
    }

    private EditInfo editInfo(Result r, PostProcessor.Output o, SensorSpec sp, int factor) {
        EditInfo in = new EditInfo();
        in.was = r.recipe;
        in.recipe = r.recipe.edit();
        in.dir = new File(root, r.session);
        SessionStore st = new SessionStore(in.dir);
        Renderer.Analysis a = o.analysis;
        in.cx0 = a.cx0;
        in.cy0 = a.cy0;
        in.cw = a.cw;
        in.ch = a.ch;
        in.factor = factor;
        in.width = sp.width;
        in.height = sp.height;
        in.referenceUnit = r.master != null && r.master.referenceUnit >= 0 ? r.master.referenceUnit : o.referenceUnit;
        in.renders = Recipe.list(in.dir);
        in.noNorth = r.north == null ? "The sky wasn't recognised in this photo, so where north is isn't known." : "";
        if (r.recipe.isMerge()) {
            in.noRestack = "It's sessions stacked together: here only how it's rendered can change.";
        } else if (!framesKept(st)) {
            in.noRestack = "Its frames were deleted to make room (Settings: frames kept), so only how it's rendered can change.";
        }
        if (!in.noRestack.isEmpty()) return in;
        in.frames = sessionFrames(st);
        if (!r.trails) {
            try {
                in.autoGround = EdgeMask.automatic(st, sp);
            } catch (RuntimeException | OutOfMemoryError e) {
                st.log("edit: the foreground couldn't be looked for: " + e);
            }
            EdgeMask m = r.recipe.mask.isEmpty() ? null : EdgeMask.load(in.dir, r.recipe.mask);
            in.painting = m != null ? m : new EdgeMask(sp.width, sp.height);
        }
        return in;
    }

    /** The session's frames as developing graded them (its report); from its frames' records if the report is gone. */
    private static ReportCard.Frame[] sessionFrames(SessionStore st) {
        String rj = st.readText("report.json");
        if (rj != null) {
            try {
                ReportCard rc = ReportCard.fromJson(rj);
                if (rc.frames.length > 0) return rc.frames;
            } catch (RuntimeException ignored) {
            }
        }
        List<ReportCard.Frame> out = new ArrayList<ReportCard.Frame>();
        try {
            for (UnitRecord u : st.readUnits()) {
                ReportCard.Frame f = new ReportCard.Frame();
                f.index = u.index;
                int sec = (int) Math.round(u.t);
                f.time = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60);
                f.used = true;
                f.score = 100;
                out.add(f);
            }
        } catch (IOException ignored) {
        }
        return out.toArray(new ReportCard.Frame[0]);
    }

    /** Whether a session's frames are still on the phone (its stack can be made again). */
    static boolean framesKept(SessionStore st) {
        String[] l = st.framesDir.list();
        if (l == null) return false;
        for (String n : l) if (n.endsWith(".raw")) return true;
        return false;
    }

    /**
     * Shows the photo as an edit renders it (quick, from the small copy): its settings, crop and turn, or with
     * {@code whole} neither cut nor turned (for painting the foreground). Null shows the photo as it is again.
     */
    void previewEdit(final Recipe e, final boolean whole) {
        final Recipe c = e != null ? e.edit() : null;
        final int tk = ++rerenderToken;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (tk != rerenderToken) return;
                previewAs(c, whole);
                Status cur = status;
                publish(cur.headline, cur.detail, -1);
            }
        });
    }

    /** The preview as {@code c} renders it ({@link #previewEdit}), or the photo as it is (null); on the session thread. */
    private void previewAs(Recipe c, boolean whole) {
        if (output == null || small == null || result == null) return;
        Recipe rc = c != null ? c : result.recipe;
        previewFraming = whole ? null : frameFor(rc, result.north, small.cropWidth(), small.cropHeight());
        previewBase = previewBitmap(small, output.analysis, paramsOf(rc, c != null ? Float.NaN : result.brightness), smallFactor);
        preview = decorate(previewBase);
        previewVersion++;
    }

    /**
     * Saves an edit of the photo shown as a new render, never over one: from the stack shown when only how it's
     * rendered changed, from the kept master of the stack it asks for, or else stacked again from the frames.
     * {@code painting}: the foreground as painted by hand, kept in the session folder for the recipe (null: as the
     * recipe says).
     */
    void saveEdit(Recipe edit, EdgeMask painting) {
        final Recipe e = edit.edit();
        final EdgeMask pm = painting != null ? painting.copy() : null;
        h.post(new Runnable() {
            @Override
            public void run() {
                if (output == null || result == null || phase != Phase.DONE) return;
                Result r = result;
                final Recipe cur = r.recipe;
                if (cur == null) {
                    previewAs(null, false);
                    publish("Not saved", "This photo's recipe wasn't kept, so it can't be edited.", -1);
                    return;
                }
                final SessionStore st = new SessionStore(new File(root, r.session));
                if (pm != null) {
                    try {
                        e.mask = pm.isBlank() ? "" : pm.save(st.dir);
                    } catch (IOException ex) {
                        previewAs(null, false);
                        publish("Not saved", "The painting couldn't be kept: " + ex.getMessage(), -1);
                        return;
                    }
                }
                String what = e.changes(cur);
                if (what.isEmpty()) {
                    previewAs(null, false);
                    publish("Nothing to save", "The edit is the photo as it is.", -1);
                    return;
                }
                if (e.sameStack(cur)) {
                    e.note = "edited: " + what;
                    st.log("edit: " + what + ", rendered from the same stack");
                    final Opened op = current();
                    phase = Phase.DEVELOPING;
                    publish("Saving", "The edit, as a new render", -1);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            renderEdit(st, e, op, true, 0);
                        }
                    }, "edit").start();
                    return;
                }
                if (cur.isMerge()) {
                    previewAs(null, false);
                    publish("Not saved", "It's sessions stacked together: only how it's rendered can change.", -1);
                    return;
                }
                // a new stack: made by this version's processing
                e.restacked();
                e.note = "edited: " + e.changes(cur);
                st.log("edit: " + e.changes(cur));
                final int back = cur.number;
                clearForJob(); // the new stack needs the memory
                phase = Phase.FINISHING;
                publish("Stacking the edit", "Looking for its stack", -1);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Master.Loaded l = Master.read(st.dir, e.stackKey());
                            if (l != null) {
                                // an earlier edit made that stack: rendered from its master, not stacked again
                                st.log("edit: its stack " + l.meta.key + " is kept, so it isn't stacked again");
                                Properties props = st.readSettings();
                                renderEdit(st, e, open(st, props, SensorSpec.fromProperties(props), e, l), false, back);
                                return;
                            }
                        } catch (IOException | RuntimeException | OutOfMemoryError ex) {
                            st.log("edit: the kept stack couldn't be read: " + ex);
                        }
                        if (!framesKept(st)) {
                            doOpen(st.dir, back, "Not saved", "The frames were deleted to make room, so the stack can't change now; only "
                                    + "how it's rendered can.");
                            return;
                        }
                        restack(st, e, null, back);
                    }
                }, "edit").start();
            }
        });
    }

    /** The photo shown, as {@link #renderEdit} takes it (on the session thread). */
    private Opened current() {
        Opened op = new Opened();
        op.o = output;
        op.spec = outSpec;
        op.small = small;
        op.factor = smallFactor;
        op.r = result;
        return op;
    }

    /**
     * Saves an edit rendered from a stack already made, with its recipe, and shows it (on a thread of its own). If it
     * fails, the photo stays ({@code shown}) or render {@code backTo} is opened again.
     */
    private void renderEdit(SessionStore st, Recipe e, Opened op, boolean shown, int backTo) {
        try {
            String name = editName(st, e.trails);
            String[] hash = new String[1];
            Renderer.Params p = paramsOf(e, Float.NaN);
            int factor = e.quick ? 2 : 1;
            Uri jpeg = saveJpeg(op.o, op.spec, p, e, op.r.north, name, null, factor, hash);
            Recipe saved = saveRecipe(st, e, op.r.master, op.o, name, jpeg, hash[0]);
            Bitmap bmp = previewBitmap(op.small, op.o.analysis, p, op.factor);
            Framing pf = frameFor(e, op.r.north, op.small.cropWidth(), op.small.cropHeight());
            saveThumb(st, frame(bmp, pf));
            Result r = edited(op.r);
            r.name = name;
            r.jpeg = jpeg;
            r.jpegFactor = factor;
            r.recipe = saved != null ? saved : e;
            r.brightness = e.brightness;
            prefs.setLastResultUri(jpeg.toString());
            Opened done = new Opened();
            done.o = op.o;
            done.spec = op.spec;
            done.small = op.small;
            done.factor = op.factor;
            done.r = r;
            done.bmp = bmp;
            done.framing = pf;
            show(done, saved != null ? String.format(Locale.US, "Saved as render %d", saved.number) : "Saved", r.summary);
        } catch (Throwable t) {
            st.log("edit: not saved: " + t);
            final String why = t instanceof OutOfMemoryError ? "out of memory" : t.getMessage();
            if (!shown) {
                doOpen(st.dir, backTo, "Edit not saved", "Could not save it: " + why + ". The photo is as it was.");
                return;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    phase = Phase.DONE;
                    previewAs(null, false);
                    publish("Not saved", "Could not save the edit: " + why, -1);
                }
            });
        }
    }

    /** The result after an edit rendered from {@code was}'s stack: the same stack, report and place on the sky. */
    private static Result edited(Result was) {
        Result r = new Result();
        r.summary = was.summary;
        r.session = was.session;
        r.startedMs = was.startedMs;
        r.durationSec = was.durationSec;
        r.trails = was.trails;
        r.canAdjust = was.canAdjust;
        r.report = was.report;
        r.reportJson = was.reportJson;
        r.tracks = was.tracks;
        r.canLabel = was.canLabel;
        r.master = was.master;
        r.north = was.north;
        r.merge = was.merge;
        return r;
    }

    /**
     * A render whose stack was deleted to make room, made again from its recipe while the frames are kept (on the
     * calling thread, a job's own): the same picture, unless the processing changed since.
     */
    private void makeAgain(SessionStore st, Recipe rc) throws IOException {
        if (!framesKept(st)) {
            throw new IOException(String.format(Locale.US, "render %d's stacked picture was deleted to make room, and so were the frames",
                    rc.number));
        }
        Recipe e = rc.edit();
        e.note = String.format(Locale.US, "render r%03d made again", rc.number);
        if (rc.outdated()) {
            e.restacked();
            e.note += " with the new processing";
        }
        st.log("decision: " + e.note + " from its recipe: its stacked picture was deleted to make room");
        restack(st, e, rc, 0);
    }

    /** Stacks the session again as {@code e} says and shows it (on the calling thread, a job's own); {@code again}: the render it remakes. */
    private void restack(final SessionStore st, final Recipe e, final Recipe again, int backTo) {
        boolean dng = !"false".equals(readProperty(st, "saveDng"));
        runDevelop(st, e.brightness, dng, null, again != null ? JOB_AGAIN : JOB_EDIT, backTo, e.trails ? "Building the star trails"
                : "Lining up the stars", new Maker() {
            @Override
            public Made make(Progress progress) throws IOException {
                Made m = developEdit(st, e, progress);
                m.again = again;
                return m;
            }
        });
    }

    /** The session stacked again as an edit's recipe says. */
    private Made developEdit(SessionStore st, Recipe e, Progress progress) throws IOException {
        Properties props = st.readSettings();
        SensorSpec sp = SensorSpec.fromProperties(props);
        PostProcessor pp = new PostProcessor(st, sp);
        if (!e.mask.isEmpty() && EdgeMask.load(st.dir, e.mask) == null) {
            st.log("decision: edit: the foreground painting " + e.mask + " is missing, so it's stacked without it");
            e.mask = "";
        }
        String missing = e.configure(pp, st.dir);
        if (!missing.isEmpty()) st.log("decision: edit: " + missing);
        SessionSky sk = skyFor(pp, props, st, e.trails);
        Made m = new Made();
        m.o = pp.run(e.trails, progress);
        m.spec = sp;
        m.home = st;
        m.session = st;
        m.props = props;
        m.sky = sk;
        m.trails = e.trails;
        m.quick = e.quick;
        m.recipe = e;
        m.edit = true;
        m.name = editName(st, e.trails);
        return m;
    }

    /** An edit's photo's name: the session's, then the render's number ("NightSky_20261022_2315_r4"). */
    private static String editName(SessionStore st, boolean trails) {
        int n = 1;
        for (Recipe x : Recipe.list(st.dir)) n = Math.max(n, x.number + 1);
        return "NightSky_" + st.id() + (trails ? "_trails" : "") + "_r" + n;
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
                    previewFraming = null;
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
                    finishCapture("you", "stopped from the viewer");
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
                return remoteAddTimeNow(seconds, "the viewer");
            }
        });
    }

    /** Adds time to the session capturing now (session thread); returns what to tell whoever asked. */
    private String remoteAddTimeNow(int seconds, String who) {
        if (phase != Phase.CAPTURING && phase != Phase.PAUSED) return "Time can only be added while capturing";
        if (settings.durationSec + seconds > MAX_DURATION_SEC) {
            return "It is already as long as a session can be (" + MAX_DURATION_SEC / 60 + " minutes)";
        }
        // whole units, as planned
        int extra = (int) Math.max(1, Math.round(seconds * 1e9 / plan.unitExpNs())) * plan.framesPerUnit;
        long unitBytes = 2L * spec.width * spec.height;
        int unitsLeft = (plan.frames + extra - framesDone + plan.framesPerUnit - 1) / plan.framesPerUnit;
        long need = unitBytes * (unitsLeft + 2) + (400L << 20);
        try {
            if (new StatFs(root.getAbsolutePath()).getAvailableBytes() < need) return "Not enough free storage for more time";
        } catch (IllegalArgumentException ignored) {
        }
        plan.frames += extra;
        settings.durationSec += seconds;
        if (settings.clearTargetSec > 0) settings.clearTargetSec += seconds;
        log(String.format(Locale.US, "%s added %d s: %d frames planned", who, seconds, plan.frames));
        publishCapture();
        return String.format(Locale.US, "Added %d minutes: %d minutes in all", Math.round(seconds / 60f),
                Math.round(settings.durationSec / 60f));
    }

    /**
     * The volume-up key (or a Bluetooth remote's button) while capturing (G1): 5 more minutes, said aloud so it's
     * heard with the phone face-down. Any thread.
     */
    void addTimeFromKey() {
        h.post(new Runnable() {
            @Override
            public void run() {
                if (phase != Phase.CAPTURING && phase != Phase.PAUSED) return;
                long now = SystemClock.elapsedRealtime();
                // a remote's button can repeat: one press, one 5 minutes
                if (now - lastKeyAddMs < 1500) return;
                lastKeyAddMs = now;
                final String msg = remoteAddTimeNow(5 * 60, "the volume key");
                if (sounds != null) {
                    sounds.chime();
                    sounds.say(msg.startsWith("Added") ? msg.replace("Added 5 minutes: ", "5 minutes added. ") + "." : msg, null);
                }
            }
        });
    }

    private long lastKeyAddMs;

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
        previewFraming = null;
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
            // older sessions' frames within their budget, and room for this one's (a reading keeps no frames)
            cleanupOldSessions(s.readingOnly ? 0 : needBytes());
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
        if (!Float.isNaN(settings.continueFocus)) {
            focusHow = "continued";
            return settings.continueFocus;
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

    /**
     * Before a session: leftovers without frames are removed, and the developed sessions' frames are kept within the
     * budget set in the settings, the oldest going first (G1), with room made for this session's {@code need} bytes
     * when storage is short. What went goes in this session's log; undeveloped sessions keep their frames.
     */
    private void cleanupOldSessions(long need) {
        for (SessionStore s : SessionStore.list(root)) {
            try {
                if (!"developed".equals(s.state()) && s.readUnits().isEmpty()) s.deleteAll();
            } catch (IOException ignored) {
            }
        }
        SessionLibrary.Trim t = SessionLibrary.trim(root, prefs.keepFramesBytes(), need, need > 0 ? freeBytes() : -1, null);
        for (String line : t.lines) planLog += (planLog.isEmpty() ? "" : "\n") + "decision: " + line;
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
        startLog.setLength(0);
        focusCheckDue = false;
        focusCheckWhy = "";
        focusHaze = "";
        focusChecks = "";
        focusFollowUps = 0;
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
        stopKey = "";
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
        pauses = 0;
        framesFailed = 0;
        stalls = 0;
        stalledMs = 0;
        openRetries = 0;
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
                    finishCapture("slow", "it was taking far longer than planned");
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
                else if (now - pausedAtMs > MAX_PAUSE_MS) finishCapture("moved", "the phone was not put back down");
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
            stopEarly("end", "it reached its end time, " + clockTime(settings.endAtMs));
            return;
        }
        if (settings.stopAtDawn && sky.located() && now - sunCheckMs >= 60_000L) {
            sunCheckMs = now;
            double alt = Tonight.sunAlt(wall, sky.latDeg * Astro.DEG, sky.lonDeg * Astro.DEG);
            if (alt > Tonight.DARK_DEG) {
                stopEarly("dawn", String.format(Locale.US, "dawn: the Sun rose to %.0f degrees below the horizon and the sky began to brighten", -alt));
                return;
            }
        }
        if (settings.minBatteryPct > 0 && now - batteryCheckMs >= 30_000L) {
            batteryCheckMs = now;
            BatteryInfo b = BatteryInfo.read(ctx);
            if (!b.charging && b.pct >= 0 && b.pct < settings.minBatteryPct) {
                stopEarly("battery", String.format(Locale.US, "the battery fell to %d%% (sessions stop below %d%%)", b.pct, settings.minBatteryPct));
                return;
            }
        }
        long free = freeBytes();
        if (free >= 0 && spec != null && free < 2L * spec.width * spec.height * 3 + (300L << 20)) {
            stopEarly("storage", String.format(Locale.US, "storage ran low (%.1f GB free)", free / 1e9));
            return;
        }
        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.getCurrentThermalStatus() >= PowerManager.THERMAL_STATUS_CRITICAL) {
            stopEarly("hot", "the phone got critically hot");
        }
    }

    private void stopEarly(String key, String why) {
        log("decision: stopped early: " + why);
        finishCapture(key, why);
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
        android.util.Log.i("NightSky", waitLog);
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
            plan.limitTo(exp, settings.durationSec, spec.white, "The camera cut the test exposure to " + ExposurePlan.seconds(exp));
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
        in.haloSpread = prefs.haloSpread(cameraId);
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
        else if (!settings.continuesFrom.isEmpty()) why = "these minutes add to the session just taken, at its focus";
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
            case "continued":
                return "the focus of the session these minutes add to";
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
        // a move cut short at two fine steps: the best focus lies further on, so check again from there at once (a few
        // times at most); the stars only shrink after such a move, so their growth wouldn't call for another check
        boolean partWay = !c.holds && Math.abs(c.best - focusValue) > 0.5 * lens.fine && focusFollowUps < 3;
        rig.setFocus(focusValue);
        double minutes = (now - captureStartMs - pausedTotalMs) / 60000.0;
        log(String.format(Locale.US, "decision: focus check after %.0f minutes (%s): %s (stars %.2f and %.2f px; %.1f px per unit, from %s): %s",
                minutes, focusCheckWhy, c.describe(), checkLowH, rd.hfr, slope, slopeFrom, c.holds
                        ? String.format(Locale.US, "focus kept at %.3f", before) : String.format(Locale.US, "focus moved from %.3f to %.3f", before, focusValue)));
        focusChecks += (focusChecks.isEmpty() ? "" : ";") + String.format(Locale.US, "%.0f,%.3f,%.3f", minutes, before, focusValue);
        finishFocusCheck();
        if (partWay) {
            focusFollowUps++;
            focusCheckDue = true;
            focusCheckWhy = String.format(Locale.US, "the last check moved the focus part of the way to its best (%.3f)", c.best);
            focusCheckDueMs = now;
            log("focus: a check is due: " + focusCheckWhy);
        } else {
            focusFollowUps = 0;
        }
        submitNext();
    }

    /** Checks in a row that followed a move cut short. */
    private int focusFollowUps;

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
            // the site's sky without the Moon, so the report can tell moonlight from the site's own light
            Site site = Site.find(Sites.load(prefs), settings.siteId);
            double dark = site == null ? Double.NaN : site.skyMedian();
            if (!Double.isNaN(dark)) p.setProperty("site.skyMag", String.format(Locale.US, "%.2f", dark));
        }
        if (!settings.continuesFrom.isEmpty()) p.setProperty("continuesFrom", settings.continuesFrom);
        if (!settings.repeatOf.isEmpty()) p.setProperty("repeatOf", settings.repeatOf);
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
            if (!Double.isNaN(pf.haloClean)) p.setProperty("preflight.haloClean", Double.toString(pf.haloClean));
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
        for (String l : startLog.toString().split("\n")) if (!l.isEmpty()) store.log(l);
        startLog.setLength(0);
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
            if (r.dropped) framesDropped++;
            else framesFailed++;
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
                replanAfterClamp(exp, "The camera cut the " + ExposurePlan.seconds(r.expNs).replace(" seconds", "-second")
                        + " exposures to " + ExposurePlan.seconds(exp));
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
        if (framesDone >= plan.frames && !moreForClearSky()) finishCapture(null, null);
        else submitNext();
    }

    /**
     * "Collect N clear minutes" (the Partly cloudy preset; G1's automatic length): at the planned end, if what the
     * frames add to the stack so far (E5's N_eff: haze, thin cloud and a brighter sky count in part, frames not judged
     * yet in full) is worth less than the target, capture on, up to the longest allowed or the end time. True if it
     * goes on.
     */
    private boolean moreForClearSky() {
        if (settings.clearTargetSec <= 0) return false;
        double unitSec = plan.unitExpNs() / 1e9;
        List<LiveStats.Unit> us = liveStats.from(0);
        double clear = LiveStats.worth(us) * unitSec;
        int pending = Math.max(0, framesDone / plan.framesPerUnit - us.size());
        clear += pending * unitSec;
        long now = SystemClock.elapsedRealtime();
        long ran = now - captureStartMs - pausedTotalMs;
        if (clear >= settings.clearTargetSec) {
            if (settings.durationSec > 0 && ran > settings.durationSec * 1000L + 30_000L) {
                log(String.format(Locale.US, "decision: the frames are worth %.1f clear minutes: done", clear / 60));
            }
            return false;
        }
        long maxMs = Math.max(settings.durationSec, settings.maxDurationSec) * 1000L;
        if (ran >= maxMs - plan.frameExpNs / 1_000_000L) {
            log(String.format(Locale.US, "decision: worth only %.1f of %d clear minutes by the longest it may run (%d minutes): developing what there is",
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
        log(String.format(Locale.US, "decision: the frames so far are worth %.1f of the %d clear minutes asked for (clouds, haze or a "
                        + "brighter sky count in part): %d more frames, %d minutes in all", clear / 60, Math.round(settings.clearTargetSec / 60f),
                extra, Math.round(settings.durationSec / 60f)));
        publishCapture();
        return true;
    }

    /**
     * Plans the session again with frames no longer than {@code deliveredNs}, starting over ({@code why}: the start of a
     * sentence, for the log and the report).
     */
    private void replanAfterClamp(long deliveredNs, String why) {
        abortInFlight();
        plan.limitTo(deliveredNs, settings.durationSec, spec.white, why);
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
        // logged in the new folder: the old one, and its log, were just deleted
        log("decision: " + Character.toLowerCase(why.charAt(0)) + why.substring(1) + "; new plan: " + plan.describe());
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
            finishCapture("pickedUp", "picked up near the end");
            return;
        }
        abortInFlight();
        phase = Phase.PAUSED;
        pausedAtMs = SystemClock.elapsedRealtime();
        pauses++;
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
        stalls++;
        stalledMs += SystemClock.elapsedRealtime() - lastProgressMs;
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
                finishCapture("camera", "the camera could not be reopened");
            }
        } else {
            finishCapture("camera", "the camera stopped responding");
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
            framesFailed++;
            log("frame " + seq + " failed");
            ++consecutiveFailures;
            // the camera answered, so it hasn't stalled: no restart (that would cut short the frames already exposing)
            lastProgressMs = SystemClock.elapsedRealtime();
            long fallback = Math.max(250_000_000L, advMaxNs);
            if (framesDone == 0 && consecutiveFailures >= 2 && plan.frameExpNs > fallback) {
                // long exposures refused outright: use the longest the camera admits to
                log("long exposures fail; falling back to " + fallback / 1e9 + " s frames");
                prefs.noteExposureClamped(cameraId, plan.frameExpNs, fallback);
                consecutiveFailures = 0;
                firstFrameChecked = false;
                replanAfterClamp(fallback, "Exposures of " + ExposurePlan.seconds(plan.frameExpNs) + " kept failing");
            } else if (framesDone == 0 && consecutiveFailures >= 3 && plan.frameExpNs >= 2_000_000_000L) {
                // even the longest the camera admits to keeps failing: half as long, for this session only (the next
                // tries the full length again, so a passing fault doesn't shorten every session after it)
                long half = plan.frameExpNs / 2;
                consecutiveFailures = 0;
                firstFrameChecked = false;
                replanAfterClamp(half, "Exposures of " + ExposurePlan.seconds(plan.frameExpNs) + " kept failing, even the longest the "
                        + "camera admits to");
            } else if (consecutiveFailures > 8) {
                finishCapture("camera", "the camera kept failing");
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
                if (framesDone > 0) finishCapture(CameraRig.TAKEN.equals(what) ? "taken" : "camera", what);
                else fail("The camera stopped: " + what);
            } else if (phase == Phase.STARTING && openRetries++ == 0 && !CameraRig.TAKEN.equals(what)) {
                // an error as it opens often passes (the camera still closing after another app): once more, shortly
                log("decision: opening the camera again in 2 s");
                rig.close();
                final int tk = token;
                h.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (tk != token || phase != Phase.STARTING) return;
                        try {
                            rig = new CameraRig(ctx, h, rigListener);
                            rig.setFocus(focusValue);
                            rig.open(cameraId, cc, rawSize);
                        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
                            fail("Could not open the camera: " + e.getMessage());
                        }
                    }
                }, 2000);
            } else if (phase.ordinal() < Phase.CAPTURING.ordinal()) {
                fail(phase == Phase.STARTING ? "Couldn't start the camera: " + what + ". If it happens again, restart the phone."
                        : "The camera stopped: " + what);
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
                        finishCapture("processing", "processing failed");
                    }
                }
            });
        }
    };

    // ------------------------------------------------------------------------------------------------
    // Finish and develop
    // ------------------------------------------------------------------------------------------------

    /** Ends the capture and develops it; {@code key} and {@code why} say why it ended before its planned end (null if it didn't). */
    private void finishCapture(String key, String why) {
        if (phase.ordinal() >= Phase.FINISHING.ordinal()) return;
        if (why != null) {
            log("capture ended early: " + why);
            stopKey = key;
            stopReason = why;
        }
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
            p.setProperty("capture.framesFailed", Integer.toString(framesFailed));
            p.setProperty("capture.pauses", Integer.toString(pauses));
            p.setProperty("capture.pausedMs", Long.toString(pausedTotalMs));
            p.setProperty("capture.stalls", Integer.toString(stalls));
            p.setProperty("capture.stalledMs", Long.toString(stalledMs));
            p.setProperty("capture.thermalMax", Integer.toString(thermalMax));
            if (thermalSevereAtMs > 0) p.setProperty("capture.thermalSevereAtMs", Long.toString(thermalSevereAtMs));
            p.setProperty("capture.framesPlanned", Integer.toString(plan.frames));
            p.setProperty("capture.framesPerUnit", Integer.toString(plan.framesPerUnit));
            if (!plan.shortWhy.isEmpty()) p.setProperty("capture.shortWhy", plan.shortWhy);
            if (!stopReason.isEmpty()) {
                p.setProperty("capture.stopKey", stopKey);
                p.setProperty("capture.stopReason", stopReason);
            }
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
        previewFraming = null;
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

    /** What developing made, before it is saved: the stack, where it belongs and how it was made. */
    private static final class Made {
        PostProcessor.Output o;
        /** The (base) session's sensor description, and the folder the master and the recipe go in. */
        SensorSpec spec;
        SessionStore home;
        /** The session just captured or developed: its state and frames (the same as home unless minutes were added). */
        SessionStore session;
        /** The home session's settings, place and time, for the report. */
        Properties props;
        SessionSky sky;
        Recipe recipe;
        boolean trails, quick;
        String name;
        /** For a merge: what each session added, the coverage and the total (null otherwise). */
        MergeProcessor.Result merge;
        /** A merge of nights chosen in the library (no report card, that is a session's), not minutes added to one. */
        boolean nights;
        /** Stacked again as an edit says (G3): the session's own report, frames and learning are left as they were. */
        boolean edit;
        /** The saved render this one makes again (its stack was deleted), to compare with; null otherwise. */
        Recipe again;
    }

    /** How a develop job makes its stack (on the develop thread). */
    private interface Maker {
        Made make(Progress progress) throws IOException;
    }

    /** Runs on the develop thread. */
    private void develop(final SessionStore st, final SensorSpec sp, final boolean trails, final float brightness,
                         final boolean saveDng, final Sounds snd) {
        runDevelop(st, brightness, saveDng, snd, JOB_DEVELOP, 0, trails ? "Building the star trails" : "Lining up the stars", new Maker() {
            @Override
            public Made make(Progress progress) throws IOException {
                Properties props = new Properties();
                try {
                    props = st.readSettings();
                } catch (IOException e) {
                    st.log("could not read the session settings: " + e.getMessage());
                }
                // minutes added after a session (G1): stacked with it, or on their own if they can't be
                String base = trails ? "" : props.getProperty("continuesFrom", "");
                if (!base.isEmpty()) {
                    try {
                        return developMerge(addedTo(base, st), true, brightness, progress);
                    } catch (CancelledException e) {
                        throw e;
                    } catch (IOException | RuntimeException e) {
                        st.log("decision: the minutes added couldn't be stacked with the session they add to (" + e.getMessage()
                                + "), so they are developed on their own");
                    }
                }
                return developSolo(st, sp, props, trails, brightness, progress);
            }
        });
    }

    /** One session as it was set up when it started. */
    private Made developSolo(SessionStore st, SensorSpec sp, Properties props, boolean trails, float brightness, Progress progress)
            throws IOException {
        PostProcessor pp = new PostProcessor(st, sp);
        // the rejection chosen when the session started, so redeveloping a session gives the same result
        pp.rejection = Rejection.Method.parse(props.getProperty("rejection"));
        SessionSky sk = skyFor(pp, props, st, trails);
        // the satellites: named where they crossed, and taken out (or kept, as set when it started)
        pp.keepSatellites = "true".equals(props.getProperty("keepSatellites"));
        // the cloud-blur version (C7), and quick developing (Quick look), as set when it started
        pp.blend = "true".equals(props.getProperty("blend"));
        boolean quick = "true".equals(props.getProperty("quick"));
        if (quick) {
            pp.patternCleanup = false;
            pp.streakCorridors = false;
        }
        Made m = new Made();
        m.o = pp.run(trails, progress);
        if (quick) {
            String q = "quick developing (Quick look): one stacking pass, the sensor's pattern not measured and the streaks' "
                    + "faint edges not stacked out again, and a half-size photo";
            m.o.decisions.add(q);
            st.log("decision: " + q);
        }
        m.spec = sp;
        m.home = st;
        m.session = st;
        m.props = props;
        m.sky = sk;
        m.trails = trails;
        m.quick = quick;
        m.recipe = developRecipe(st, props, trails, brightness);
        m.name = "NightSky_" + st.id() + (trails ? "_trails" : "");
        return m;
    }

    /**
     * Where and when the session was (for naming the stars and placing the frames on the sky) and the satellites that
     * crossed it (named where they did), as developing sets them up.
     */
    private SessionSky skyFor(PostProcessor pp, Properties props, SessionStore st, boolean trails) {
        SessionSky sk = SessionSky.fromProperties(props);
        sk.catalog = SkyCatalog.get(ctx);
        pp.sky = sk;
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
        return sk;
    }

    /**
     * Sessions stacked as one (G1), the first the base: minutes added after a session ({@code added}), or nights of the
     * same sky chosen in the library. Each session is read as it was set up (its place and time, the frames its
     * latest edit kept); the photo's master and recipe go in the base's folder.
     */
    private Made developMerge(List<SessionStore> stores, boolean added, float brightness, Progress progress) throws IOException {
        SessionStore base = stores.get(0);
        Properties bp = base.readSettings();
        BrightStars cat = SkyCatalog.get(ctx);
        List<MergeProcessor.Session> ss = new ArrayList<MergeProcessor.Session>();
        Recipe recipe = new Recipe();
        for (int k = 0; k < stores.size(); k++) {
            SessionStore s = stores.get(k);
            Properties p = k == 0 ? bp : s.readSettings();
            MergeProcessor.Session ms = new MergeProcessor.Session(s, SensorSpec.fromProperties(p));
            SessionSky sk = SessionSky.fromProperties(p);
            sk.catalog = cat;
            ms.sky = sk;
            ms.startMs = parseLong(p.getProperty("capture.startWallMs"));
            String night = ms.startMs > 0 ? nightLabel(ms.startMs) : "session " + s.id();
            ms.label = added ? (k == 0 ? "the session" : "the minutes added" + (ms.startMs > 0 ? " at " + clockTime(ms.startMs) : ""))
                    : p.getProperty("continuesFrom", "").isEmpty() ? night
                    : night + ", minutes added" + (ms.startMs > 0 ? " at " + clockTime(ms.startMs) : "");
            // the frames its latest edit kept (G3), else all of them
            Recipe last = Recipe.latest(s.dir);
            Recipe.Part part = new Recipe.Part();
            part.session = s.id();
            if (last != null && !last.isMerge() && last.base().include != null) {
                ms.include = last.base().include;
                ms.includeNote = last.base().includeNote;
                part.include = last.base().include;
                part.includeNote = last.base().includeNote;
            }
            recipe.parts.add(part);
            ss.add(ms);
        }
        MergeProcessor mp = new MergeProcessor(ss);
        mp.rejection = Rejection.Method.parse(bp.getProperty("rejection"));
        MergeProcessor.Result r = mp.run(progress);
        r.added = added;
        Made m = new Made();
        m.o = r.out;
        m.merge = r;
        m.nights = !added;
        m.spec = ss.get(0).spec;
        m.home = base;
        m.session = added ? stores.get(stores.size() - 1) : base;
        m.props = bp;
        m.sky = ss.get(0).sky;
        m.trails = false;
        m.quick = false;
        recipe.rejection = mp.rejection.name();
        recipe.brightness = brightness;
        recipe.app = BuildInfo.VERSION;
        recipe.note = added ? "minutes added" : "merged";
        recipe.total = r.total;
        m.recipe = recipe;
        int minutes = (int) Math.round(r.out.integrationSeconds / 60);
        m.name = "NightSky_" + base.id() + (added ? "_" + minutes + "min" : r.nights > 1 ? "_" + r.nights + "nights" : "_" + r.sessions + "sessions");
        return m;
    }

    /** The session a continuation adds to, then every earlier continuation of it, then this one: oldest first. */
    private List<SessionStore> addedTo(String baseName, SessionStore st) throws IOException {
        File bd = new File(root, baseName);
        if (!new File(bd, "session.properties").isFile()) throw new IOException("the session it adds to is gone");
        List<SessionStore> out = new ArrayList<SessionStore>();
        out.add(new SessionStore(bd));
        List<SessionStore> all = SessionStore.list(root);
        java.util.Collections.reverse(all);
        for (SessionStore s : all) {
            if (s.dir.equals(st.dir) || s.dir.equals(bd)) continue;
            try {
                Properties p = s.readSettings();
                if (baseName.equals(p.getProperty("continuesFrom")) && "developed".equals(p.getProperty("state"))) out.add(s);
            } catch (IOException ignored) {
            }
        }
        out.add(st);
        return out;
    }

    /** "22 Oct": the night a session started on (the evening's date). */
    static String nightLabel(long ms) {
        return new java.text.SimpleDateFormat("d MMM", Locale.US).format(new java.util.Date(ms - 12 * 3600_000L));
    }

    /** What a develop job is: a session (or minutes added to one), sessions merged, or an edit stacked again (G3). */
    /** Develop jobs: a session, sessions merged, an edit stacked again, a saved render whose stack was deleted made again. */
    private static final int JOB_DEVELOP = 0, JOB_MERGE = 1, JOB_EDIT = 2, JOB_AGAIN = 3;

    /**
     * The develop thread's frame: progress on the screen, the stack from {@code maker}, then everything saved and shown
     * ({@link #finishDevelop}); a cancel or a failure leaves the frames as they were (an edit's goes back to render
     * {@code backTo}, 0 for the latest).
     */
    private void runDevelop(final SessionStore st, final float brightness, final boolean saveDng, final Sounds snd, final int job,
                            int backTo, String firstStage, Maker maker) {
        final boolean merging = job == JOB_MERGE, edit = job == JOB_EDIT || job == JOB_AGAIN;
        final String verb = merging ? "Merging" : job == JOB_EDIT ? "Stacking the edit" : job == JOB_AGAIN ? "Making it again" : "Developing";
        final String what = merging ? "merging" : job == JOB_EDIT ? "stacking the edit" : job == JOB_AGAIN ? "making a render again" : "developing";
        final long t0 = SystemClock.elapsedRealtime();
        post(Phase.DEVELOPING, verb, firstStage, 0f);
        Progress progress = new Progress() {
            long last;

            @Override
            public void update(String stage, float fraction) {
                long now = SystemClock.elapsedRealtime();
                if (now - last < 300 && fraction < 1f) return;
                last = now;
                post(Phase.DEVELOPING, verb, stage, fraction);
            }

            @Override
            public boolean cancelled() {
                return cancelDevelop;
            }
        };
        try {
            Made m = maker.make(progress);
            finishDevelop(m, brightness, saveDng, snd, t0);
        } catch (final CancelledException e) {
            st.log(what + " cancelled");
            if (edit) {
                doOpen(st.dir, backTo, "Cancelled", job == JOB_AGAIN ? "It wasn't made again; this is the session's latest photo."
                        : "The edit wasn't saved; the photo is as it was.");
                return;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    phase = Phase.IDLE;
                    outcome = NightPlan.Outcome.CANCELLED;
                    outcomeDetail = merging ? "Merging was cancelled" : "Developing was cancelled; the frames are kept";
                    publish("Cancelled", merging ? "The sessions are as they were." : "The frames are kept: you can develop them later "
                            + "from the start screen.", -1);
                    if (snd != null) snd.release();
                    host.onIdle();
                }
            });
        } catch (final Throwable t) {
            st.log(what + " failed: " + t);
            for (StackTraceElement el : t.getStackTrace()) st.log("  at " + el);
            if (edit) {
                String why = t instanceof OutOfMemoryError ? "out of memory" : t.getMessage();
                if (job == JOB_AGAIN) {
                    doOpen(st.dir, backTo, "Not made again", "Stacking it again failed: " + why + ". This is the session's latest photo.");
                } else {
                    doOpen(st.dir, backTo, "Edit not saved", "Stacking it again failed: " + why + ". The photo is as it was.");
                }
                return;
            }
            h.post(new Runnable() {
                @Override
                public void run() {
                    if (snd != null) snd.release();
                    String why = t instanceof OutOfMemoryError ? "out of memory" : t.getMessage();
                    fail(merging ? "Merging failed: " + why + ". The sessions are as they were."
                            : "Developing failed: " + why + ". The frames are kept; try again from the start screen.");
                }
            });
        }
    }

    /**
     * Saves what developing made and shows it: the stack rounded as its master keeps it, the master and the recipe,
     * the report card, the photo (and the cloud blur, the DNG and the log), the frames kept or not, and the result.
     */
    private void finishDevelop(final Made m, final float brightness, boolean saveDng, final Sounds snd, long t0) throws IOException {
        final PostProcessor.Output o = m.o;
        final SensorSpec sp = m.spec;
        SessionStore st = m.session, home = m.home;
        // the stack as its master keeps it (half floats), so the photo saved below is the one the master gives again
        Master.quantize(o, sp, m.trails);
        Master.Meta master = saveMaster(home, o, sp, m.recipe, m.props, !m.nights && !m.edit, m.merge);
        if (m.edit && master != null) pruneMasters(home, master.key);
        if (m.merge == null && !m.edit) learnFromSession(m.props, o, sp, st);
        // a merge of nights isn't one session: its own summary and coverage map stand for the report card; an edit's is
        // kept with its stack, the session's own stays the develop step's
        ReportCard card = m.nights ? null : report(o, m.sky, m.props, st, !m.edit);
        if (card != null && master != null) {
            // with the stack it describes, for opening the photo again from the sessions page
            try {
                Master.writeReport(home.dir, master.key, card.toJson());
            } catch (IOException e) {
                home.log("report: not kept with the master: " + e.getMessage());
            }
        }
        List<SatOverlay.Line> tracks = SatOverlay.lines(o.satTracks, o.streaks, o.analysis.cx0, o.analysis.cy0, o.analysis.cw,
                o.analysis.ch);
        final String name = m.name;
        int factor = m.quick ? 2 : 1;
        post(Phase.DEVELOPING, "Saving", m.quick ? "Half-size photo" : "Full-size photo", -1f);
        String[] hash = new String[1];
        // rendered as its recipe says: the develop step's settings, or an edit's (G3)
        Renderer.Params params = paramsOf(m.recipe, brightness);
        final double[] north = northOf(o, master != null ? master.solutionR : o.solution != null ? o.solution.R : null);
        // a render made again that came out as it was isn't saved twice: it's the one already in the gallery (G3)
        final boolean same = m.again != null && !m.again.pixelHash.isEmpty()
                && m.again.pixelHash.equals(photoHash(o, params, m.recipe, north, factor));
        Uri jpeg;
        Recipe recipe;
        if (same) {
            jpeg = m.again.uri.isEmpty() ? null : Uri.parse(m.again.uri);
            recipe = m.again;
            hash[0] = m.again.pixelHash;
        } else {
            jpeg = saveJpeg(o, sp, params, m.recipe, north, name, null, factor, hash);
            recipe = saveRecipe(home, m.recipe, master, o, name, jpeg, hash[0]);
        }
        if (m.again != null) {
            home.log(String.format(Locale.US, "decision: render r%03d made again from its recipe: %s%s", m.again.number,
                    sameAs(m.again, master, hash[0]), same ? ", so it isn't saved twice" : recipe != null
                            ? String.format(Locale.US, ", so it's saved as render r%03d", recipe.number) : ""));
        }
        Uri blendUri = null;
        if (o.blend != null && !same) {
            post(Phase.DEVELOPING, "Saving", "The cloud-blur version", -1f);
            try {
                blendUri = saveBlend(o, sp, brightness, name + "_blend");
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                st.log("cloud blur: not saved: " + e);
            }
        }
        o.blend = null; // its memory, for the result screen
        Uri dng = null;
        if (saveDng && !same) {
            post(Phase.DEVELOPING, "Saving", "RAW (DNG) copy", -1f);
            dng = saveDng(o, sp, name);
        }
        if (!"developed".equals(st.state())) st.setState("developed");
        st.log(String.format(Locale.US, "%s in %.1f s: %s", m.again != null ? "render made again" : m.edit ? "edit stacked"
                : m.merge == null ? "developed" : m.nights ? "merged" : "stacked with the session it adds to",
                (SystemClock.elapsedRealtime() - t0) / 1000.0, o.summary));
        if (home != st) home.log(String.format(Locale.US, "%s: %s", m.recipe.note, o.summary));
        Uri log = null;
        if (!m.edit) {
            try {
                String text = st.readLog();
                if (home != st) text = home.readLog() + "\n---- " + st.dir.getName() + " ----\n" + text;
                log = MediaSaver.saveText(ctx, name + "_log.txt", text);
            } catch (IOException | RuntimeException ignored) {
            }
        }
        // the photo is saved: the frames stay for editing and merging while the budget allows (an edit leaves them be)
        if (!m.edit && (m.merge == null || home != st)) keepOrDeleteFrames(st, home);
        // on-screen copy, about 1600 pixels wide, kept with the stack for brightness changes and edits
        int f = Math.max(1, (int) Math.ceil(o.analysis.cw / 1600.0));
        RgbImage sm = o.image.binned(f);
        final Bitmap bmp = previewBitmap(sm, o.analysis, params, f);
        final Framing pf = frameFor(m.recipe, north, sm.cropWidth(), sm.cropHeight());
        saveThumb(home, frame(bmp, pf));
        final Result r = new Result();
        r.summary = o.summary;
        r.name = same && !m.again.name.isEmpty() ? m.again.name : name;
        r.session = home.dir.getName();
        r.trails = m.trails;
        r.startedMs = parseLong(m.props.getProperty("capture.startWallMs"));
        r.durationSec = m.merge != null ? (int) Math.round(o.integrationSeconds)
                : (int) parseLong(m.props.getProperty("capture.durationSec", m.props.getProperty("durationSec")));
        // an edit isn't a new capture: no "Add 5 minutes" after it
        r.endedMs = m.edit ? 0 : System.currentTimeMillis();
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
        r.recipe = recipe != null ? recipe : m.recipe;
        r.master = master;
        r.merge = m.merge;
        r.north = north;
        r.repeatOf = m.merge == null && !m.edit ? m.props.getProperty("repeatOf", "") : "";
        if (jpeg != null) prefs.setLastResultUri(jpeg.toString());
        // what's said: an edit by its render's number, a render made again by how it came out
        final String headline, detail;
        if (m.again != null) {
            headline = String.format(Locale.US, "Render %d made again", m.again.number);
            detail = same ? "Its stacked picture had been deleted to make room, so it was stacked again from the frames: the same picture, "
                    + "pixel for pixel."
                    : recipe == null ? "It differs from the first, and couldn't be saved."
                    : m.again.outdated() ? String.format(Locale.US, "Made with this version's processing and saved as render %d; render %d stays "
                    + "as it was.", recipe.number, m.again.number)
                    : String.format(Locale.US, "Saved as render %d: it differs from render %d (%s).", recipe.number, m.again.number,
                    sameAs(m.again, master, hash[0]));
        } else if (m.edit) {
            headline = recipe != null ? String.format(Locale.US, "Saved as render %d", recipe.number) : "Your edit is ready";
            detail = r.summary;
        } else {
            headline = m.merge != null ? (m.nights ? "The merged photo is ready" : "The longer photo is ready")
                    : m.trails ? "Your star trails are ready" : "Your photo is ready";
            detail = r.summary;
        }
        final RgbImage smf = sm;
        final int ff = f;
        h.post(new Runnable() {
            @Override
            public void run() {
                output = o;
                outSpec = sp;
                small = smf;
                smallFactor = ff;
                result = r;
                previewFraming = pf;
                previewBase = bmp;
                preview = decorate(bmp);
                previewVersion++;
                phase = Phase.DONE;
                outcome = NightPlan.Outcome.DONE;
                outcomeDetail = doneWords(r, o);
                publish(headline, detail, -1);
                if (snd != null) {
                    snd.say(m.merge != null ? (m.nights ? "The merged photo is ready: " : "The longer photo is ready: ") + m.merge.total
                            : r.report != null ? r.report.spoken : spoken(o), new Runnable() {
                        @Override
                        public void run() {
                            snd.release();
                        }
                    });
                }
                host.onIdle();
            }
        });
    }

    /** Masters of edits kept beside the develop step's and the newest: older ones go (their recipes can make them again). */
    private static final int EDIT_MASTERS = 2;

    private static void pruneMasters(SessionStore st, String newest) {
        java.util.Set<String> keep = new java.util.HashSet<String>();
        keep.add(newest);
        long before = Master.bytes(st.dir);
        List<String> gone = Master.prune(st.dir, EDIT_MASTERS, keep);
        if (gone.isEmpty()) return;
        st.log(String.format(Locale.US, "decision: %d older stacked picture%s of edits deleted (%s), keeping the develop step's and the "
                        + "newest %d; their recipes can make them again while the frames are kept", gone.size(), gone.size() == 1 ? "" : "s",
                SessionLibrary.gb(before - Master.bytes(st.dir)), EDIT_MASTERS + 1));
    }

    /** Whether a render made again came out as the first time: its stack (the master's hash) and its pixels. */
    private static String sameAs(Recipe was, Master.Meta master, String pixelHash) {
        boolean stack = master != null && !was.masterHash.isEmpty() && was.masterHash.equals(master.hash);
        boolean pixels = pixelHash != null && pixelHash.equals(was.pixelHash);
        if (pixels) return stack ? "the same stack and the same picture, pixel for pixel"
                : "the same picture, pixel for pixel (the stack differs below what the photo shows)";
        if (was.outdated()) return "with the new processing, so it differs from the first";
        if (stack) return "the same stack, but the picture differs (the rendering changed since)";
        return "a different stack (the satellite data or the app changed since it was made)";
    }

    /** A small copy of the photo in the session folder, for the sessions page. */
    private static void saveThumb(SessionStore st, Bitmap bmp) {
        try {
            int w = 360, hh = Math.max(1, Math.round(w * bmp.getHeight() / (float) bmp.getWidth()));
            Bitmap t = Bitmap.createScaledBitmap(bmp, w, hh, true);
            java.io.FileOutputStream os = new java.io.FileOutputStream(new File(st.dir, "thumb.jpg"));
            try {
                t.compress(Bitmap.CompressFormat.JPEG, 85, os);
            } finally {
                os.close();
                if (t != bmp) t.recycle();
            }
        } catch (IOException | RuntimeException e) {
            st.log("thumbnail: not saved: " + e);
        }
    }

    /**
     * The session's report card, in its log and (with {@code own}) saved as the session's report; null if it couldn't
     * be made.
     */
    private ReportCard report(PostProcessor.Output o, SessionSky sk, Properties props, SessionStore st, boolean own) {
        try {
            ReportCard.Input in = new ReportCard.Input();
            in.out = o;
            in.sky = sk;
            in.props = props;
            in.zone = TimeZone.getDefault();
            in.catalog = sk.catalog;
            ReportCard card = ReportCard.build(in);
            st.log("report:\n" + card.describe());
            if (!own) return card;
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
            double spread = prefs.haloSpread(sp.cameraId);
            st.log(String.format(Locale.US, "learned for the next check: clean lens halo %.3f (%s), clear night zero point %.2f",
                    prefs.haloBaseline(sp.cameraId), Double.isNaN(spread) ? "how much it wanders is known after 4 good sessions"
                            : String.format(Locale.US, "readings wander %.3f", spread), prefs.clearZeroPoint(sp.cameraId)));
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
        return saveJpeg(o, sp, paramsOf(null, brightness), null, null, name, d, factor, null);
    }

    /**
     * Saves the photo rendered with {@code p}, cut and turned as {@code rc} says ({@code north} its north; rc null: as
     * it is); with {@code hash}, the photo's pixels' SHA-256 goes in hash[0] (before anything is drawn on it, which is
     * drawn before the framing).
     */
    private Uri saveJpeg(PostProcessor.Output o, SensorSpec sp, Renderer.Params p, Recipe rc, double[] north, String name, Decor d,
                         int factor, String[] hash) throws IOException {
        Framing f = frameFor(rc, north, o.analysis.cw / factor, o.analysis.ch / factor);
        if (f != null) return saveFramed(o, sp, p, f, name, d, factor, hash);
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
            if (hash != null) hash[0] = pixelHash(bmp);
            if (d != null) TrackPainter.draw(bmp, o.analysis.cx0, o.analysis.cy0, factor, d.tracks, d.sky, d.showTracks, d.showLabels);
            return MediaSaver.saveJpeg(ctx, bmp, name, exif(o, sp, cw, ch));
        } finally {
            bmp.recycle();
        }
    }

    /** {@link #saveJpeg} with a crop or a turn: rendered whole (and drawn on), then framed. */
    private Uri saveFramed(PostProcessor.Output o, SensorSpec sp, Renderer.Params p, Framing f, String name, Decor d, int factor,
                           String[] hash) throws IOException {
        RgbImage sm = factor > 1 ? o.image.binned(factor) : null;
        int[] px = sm != null ? Renderer.renderSmall(sm, o.analysis, p, factor) : Master.render(o.image, o.analysis, p);
        int pw = sm != null ? sm.cropWidth() : o.analysis.cw, ph = sm != null ? sm.cropHeight() : o.analysis.ch;
        sm = null;
        if (pw != f.w || ph != f.h) throw new IOException("the framing is for a " + f.w + "x" + f.h + " photo, not " + pw + "x" + ph);
        int[] out = f.apply(px);
        if (hash != null) hash[0] = Master.pixelHash(out);
        if (d != null) {
            Bitmap whole = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888);
            try {
                whole.setPixels(px, 0, pw, 0, 0, pw, ph);
                TrackPainter.draw(whole, o.analysis.cx0, o.analysis.cy0, factor, d.tracks, d.sky, d.showTracks, d.showLabels);
                whole.getPixels(px, 0, pw, 0, 0, pw, ph);
            } finally {
                whole.recycle();
            }
            out = f.apply(px);
        }
        px = null;
        Bitmap bmp = Bitmap.createBitmap(f.outW, f.outH, Bitmap.Config.ARGB_8888);
        try {
            bmp.setPixels(out, 0, f.outW, 0, 0, f.outW, f.outH);
            out = null;
            return MediaSaver.saveJpeg(ctx, bmp, name, exif(o, sp, f.outW, f.outH));
        } finally {
            bmp.recycle();
        }
    }

    private static MediaSaver.Exif exif(PostProcessor.Output o, SensorSpec sp, int w, int h) {
        MediaSaver.Exif ex = new MediaSaver.Exif();
        ex.make = sp.deviceMake;
        ex.model = sp.deviceModel;
        ex.description = o.summary;
        ex.exposureSeconds = o.integrationSeconds;
        ex.iso = sp.iso;
        ex.fNumber = sp.fNumber;
        ex.focalLengthMm = sp.focalLengthMm;
        ex.width = w;
        ex.height = h;
        return ex;
    }

    /** The SHA-256 of the pixels of the photo {@link #saveJpeg} would save with these settings, without saving it. */
    private static String photoHash(PostProcessor.Output o, Renderer.Params p, Recipe rc, double[] north, int factor) {
        RgbImage sm = factor > 1 ? o.image.binned(factor) : null;
        int[] px = sm != null ? Renderer.renderSmall(sm, o.analysis, p, factor) : Master.render(o.image, o.analysis, p);
        Framing f = frameFor(rc, north, o.analysis.cw / factor, o.analysis.ch / factor);
        return Master.pixelHash(f != null ? f.apply(px) : px);
    }

    /** SHA-256 of a rendered photo's pixels, row after row, as {@link Master#pixelHash} makes it. */
    private static String pixelHash(Bitmap bmp) {
        int w = bmp.getWidth(), hgt = bmp.getHeight();
        int[] row = new int[w];
        java.security.MessageDigest md = Master.digest();
        for (int y = 0; y < hgt; y++) {
            bmp.getPixels(row, 0, w, 0, y, w, 1);
            Master.update(md, row, 0, w);
        }
        return Master.finish(md);
    }

    /** The develop step's recipe: the whole session, as set when it started. */
    private static Recipe developRecipe(SessionStore st, Properties props, boolean trails, float brightness) {
        Recipe r = Recipe.single(st.id(), trails);
        r.rejection = Rejection.Method.parse(props.getProperty("rejection")).name();
        r.keepSatellites = "true".equals(props.getProperty("keepSatellites"));
        r.quick = "true".equals(props.getProperty("quick"));
        r.brightness = brightness;
        r.app = BuildInfo.VERSION;
        r.note = "developed";
        return r;
    }

    /**
     * Keeps a stack as a master in the session folder (G1, G3), to render again or merge without its frames; null if
     * it couldn't be written (the photo still is).
     */
    private static Master.Meta saveMaster(SessionStore st, PostProcessor.Output o, SensorSpec sp, Recipe r, Properties props,
                                          boolean develop, MergeProcessor.Result merge) {
        try {
            Master.Meta m = Master.meta(o, sp, r.trails);
            m.key = r.stackKey();
            m.stack = r.stackJson();
            m.develop = develop;
            m.merge = merge != null ? MergeProcessor.describe(merge) : "";
            m.app = BuildInfo.VERSION;
            for (Recipe.Part p : r.parts) m.sessions.add(p.session);
            m.startMs = parseLong(props.getProperty("capture.startWallMs"));
            m.lat = parseDouble(props.getProperty("lat"));
            m.lon = parseDouble(props.getProperty("lon"));
            m.siteId = props.getProperty("site.id", "");
            Master.write(st.dir, m, o.image);
            st.log(String.format(Locale.US, "master %s saved (%s): the stack, to render again or merge without the frames", m.key,
                    SessionLibrary.gb(m.bytes)));
            return m;
        } catch (IOException | RuntimeException e) {
            st.log("master: not saved: " + e);
            return null;
        }
    }

    /** Saves a render's recipe (never over an earlier one); returns it, or null if it couldn't be saved. */
    private static Recipe saveRecipe(SessionStore st, Recipe r, Master.Meta m, PostProcessor.Output o, String name, Uri jpeg, String hash) {
        try {
            r.name = name;
            r.uri = jpeg != null ? jpeg.toString() : "";
            r.stackKey = m != null ? m.key : r.stackKey();
            r.masterHash = m != null ? m.hash : "";
            r.pixelHash = hash != null ? hash : "";
            r.summary = o.summary;
            r.app = BuildInfo.VERSION;
            r.save(st.dir);
            st.log(String.format(Locale.US, "render r%03d saved: %s (%s)", r.number, name, r.note));
            return r;
        } catch (IOException | RuntimeException e) {
            st.log("render recipe: not saved: " + e);
            return null;
        }
    }

    /**
     * After developing: the session's frames stay so it can be edited and merged later (G1, G3), the oldest sessions'
     * going first beyond the budget set in the settings; with keeping frames off, they go now the photo is saved.
     */
    private void keepOrDeleteFrames(SessionStore st, SessionStore home) {
        long budget = prefs.keepFramesBytes();
        if (budget <= 0) {
            st.deleteBulk();
            st.log("decision: the frames deleted now the photo is saved (keeping frames is off in the settings)");
            return;
        }
        // minutes added keep the session they add to whole too, for the next "Add 5 minutes"
        java.util.Set<String> keep = new java.util.HashSet<String>();
        keep.add(st.id());
        keep.add(home.id());
        SessionLibrary.Trim t = SessionLibrary.trim(root, budget, 0, -1, keep);
        for (String line : t.lines) st.log("decision: " + line);
        st.log(String.format(Locale.US, "decision: the frames kept for editing and merging (%s; all sessions' frames %s of the %s kept)",
                SessionLibrary.gb(SessionLibrary.frameBytes(st)), SessionLibrary.gb(t.keptFrameBytes), SessionLibrary.gb(budget)));
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
        else if (phase.ordinal() >= Phase.STARTING.ordinal() && phase.ordinal() < Phase.CAPTURING.ordinal() && startLog.length() < 16_000) {
            startLog.append(s).append('\n');
        }
        android.util.Log.i("NightSky", s);
    }

    /** What was logged before the session's folder existed (the camera's hiccups as it opened, a skipped check), for its log. */
    private final StringBuilder startLog = new StringBuilder();

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
        // no frame since the camera went quiet and was restarted (it says so until one comes)
        if (phase == Phase.CAPTURING && restarts > 0) detail = "The camera went quiet: restarting it  ·  " + detail;
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
