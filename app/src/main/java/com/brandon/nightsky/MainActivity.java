package com.brandon.nightsky;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.brandon.nightsky.processing.EdgeMask;
import com.brandon.nightsky.processing.FocusModel;
import com.brandon.nightsky.processing.FramePreview;
import com.brandon.nightsky.processing.FrameStore;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.MergeProcessor;
import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.Preset;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Rejection;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatPlanner;
import com.brandon.nightsky.processing.SatSky;
import com.brandon.nightsky.processing.SessionLibrary;
import com.brandon.nightsky.processing.SessionStore;
import com.brandon.nightsky.processing.SettingsFile;
import com.brandon.nightsky.processing.Site;
import com.brandon.nightsky.processing.SkyReading;
import com.brandon.nightsky.processing.Tonight;
import com.brandon.nightsky.processing.UnitRecord;
import com.brandon.nightsky.remote.QrCode;

import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * The whole app on one screen that changes with the session: set up, place the phone, capturing,
 * developing, result. Red on black so it doesn't ruin night vision.
 */
public final class MainActivity extends Activity {
    static final int BG = 0xFF000000;
    static final int RED = 0xFFE53935;
    static final int RED_DIM = 0xFF9A2A25;
    static final int RED_DARK = 0xFF2A0807;
    static final int RED_SEL = 0xFF6E1512;
    /** Light modules of the QR codes: pale enough for any phone camera to read, still reddish. */
    static final int QR_LIGHT = 0xFFFFCDD2;
    /** The screen is in front (the capture service keeps a scheduled session's result on screen while it is). */
    static volatile boolean visible;
    /**
     * Opened from the tile, the widget or a shortcut (E3): the data is nightsky://launch/what, where what is home,
     * arm (start with the saved settings), stack5, trails30, quick, reading, plan, armplan or summary.
     */
    static final String ACTION_LAUNCH = "com.brandon.nightsky.LAUNCH";
    /** With {@link #ACTION_LAUNCH}: the phone was locked (the tile): show over the lock screen. */
    static final String EXTRA_OVER_LOCK = "overLock";

    static Uri launchUri(String what) {
        return Uri.parse("nightsky://launch/" + what);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    /** The session report, shown over the result screen; and its back-gesture handler (API 33+). */
    private FrameLayout reportOverlay;
    private Object reportBack;
    private CaptureService service;
    private SessionController.Phase shownPhase;
    private long shownVersion = -1;
    private int shownPreview = -1;
    private boolean pendingStart, askedNotifications;
    /** When the session waiting on a permission should start (Unix ms; 0 for at once). */
    private long pendingStartAt;
    /** The home screen's satellite card; a counter that drops stale results; the last outlook, reused for a while. */
    private LinearLayout satBox;
    private int satToken;
    private static SatPlanner.Outlook lastOutlook;
    private static String lastOutlookKey = "";
    private static long lastOutlookMs;
    private Boolean windowActive;

    // the idle screen's pages: home, tonight's plan, a session being added, last night's summary, the saved sites,
    // the daylight focus calibration, the sessions kept, and merging some of them
    private static final int PAGE_HOME = 0, PAGE_PLAN = 1, PAGE_EDIT = 2, PAGE_SUMMARY = 3, PAGE_SITES = 4, PAGE_FOCUS = 5,
            PAGE_SESSIONS = 6, PAGE_MERGE = 7;
    /** The merge page's session to merge into, and the one ticked alone (folder names; null for all). */
    private String mergeBase, mergePick;
    /** Drops the sessions read for a page Brandon has left. */
    private int libraryToken;
    /** Asking for a settings file to load, for the location on the sites page, and for the camera to calibrate. */
    private static final int REQ_IMPORT = 7, REQ_SITES_LOCATION = 8, REQ_FOCUS_CAMERA = 9;
    /** The daylight focus calibration's camera while its page shows (null otherwise); the camera sessions use. */
    private FocusCalibrator calibrator;
    private static String focusCamera;
    /** The home screen's scroll view, and its presets card (filled again when a setting changes). */
    private ScrollView homeScroll;
    private LinearLayout presetBox;
    /** What the home screen said about the saved site whose preset it put in place ("" if nothing). */
    private String sitePicked = "";
    private int page = PAGE_HOME;
    /** The plan being edited (not armed), the session being added, and the night being summarised. */
    private NightPlan editPlan, summaryPlan;
    private NightPlan.Item editItem;
    /** The home screen's card about tonight. */
    private LinearLayout nightBox;
    private int shownPlanVersion = -1, forecastToken;
    private long shownForecastMs, shownPlanErrorMs;
    /** Arm once the permissions asked for are answered; re-arm once the app is on screen. */
    private boolean pendingArm, pendingRearm;
    /** What the tile, widget or shortcut asked for, done once the screen is up (null: nothing). */
    private String pendingLaunch;
    /** The settings the session waiting on a permission starts with (null: the saved ones). */
    private Launch pendingLaunchSettings;
    /** A launch waiting for the capture service to connect (it may be running a session already). */
    private String launchAfterBind;
    /** The pages' back-gesture handler (API 33+). */
    private Object pageBackCb;

    // views of the current screen (whichever exist)
    private TextView headline, detail, stage;
    /** The pre-flight check's findings, and the saved site and preset, on the arming screen. */
    private TextView checks, siteView;
    private Preflight.Report shownChecks;
    private ProgressBar bar;
    private ImageView live;
    private ZoomImageView zoom;
    private SeekBar resultBrightness;
    private Button saveVersion;
    private float shownBrightness = -1f;
    /** Holds the remote viewer's card on the session screens; rebuilt when its content changes. */
    private LinearLayout viewerBox;
    private String shownViewer;
    private boolean viewerCompact;
    /** The result screen: the viewer outlives the session there, so it gets a stop button. */
    private boolean viewerStoppable;
    private long viewerCheckedMs;
    /** The arm screen is temporarily brighter so another phone can read the QR code. */
    private boolean qrBright;
    /** The edit screen (G3) while it's open over the result screen, and its picture. */
    private EditState edit;
    private ZoomImageView editZoom;
    /** The developing screen's note and cancel button, which follow what's running. */
    private TextView devNote;
    private Button devCancel;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((CaptureService.LocalBinder) b).service();
            shownVersion = -1;
            shownPhase = null;
            refresh();
            if (launchAfterBind != null) {
                String what = launchAfterBind;
                launchAfterBind = null;
                launch(what);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            refreshViewer(false);
            main.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
        });
        setContentView(root);
        Plans.channel(this);
        handleIntent(getIntent());
        showHome();
        bindService(new Intent(this, CaptureService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
        if (shownPhase == null || shownPhase == SessionController.Phase.IDLE) showHome();
    }

    /** From a notification or the alarm: open the plan or last night's summary, or re-arm the plan. */
    private void handleIntent(Intent i) {
        if (i == null) return;
        if (ACTION_LAUNCH.equals(i.getAction()) && i.getData() != null) {
            // the tile, the widget or a shortcut: done once, in onResume
            pendingLaunch = i.getData().getLastPathSegment();
            boolean overLock = i.getBooleanExtra(EXTRA_OVER_LOCK, false);
            setShowWhenLocked(overLock);
            setTurnScreenOn(overLock);
            // done with; the extra stays, so a recreated screen still shows over the lock screen
            i.setAction(Intent.ACTION_MAIN);
            i.setData(null);
        } else {
            // opened any other way: over the lock screen only if this is still the tile's opening
            setShowWhenLocked(i.getBooleanExtra(EXTRA_OVER_LOCK, false));
        }
        String screen = i.getStringExtra(Plans.EXTRA_SCREEN);
        if ("plan".equals(screen)) {
            page = PAGE_PLAN;
            editPlan = null;
        } else if ("summary".equals(screen)) {
            page = PAGE_SUMMARY;
            summaryPlan = null;
        }
        if (i.getBooleanExtra(Plans.EXTRA_REARM, false)) pendingRearm = true;
        i.removeExtra(Plans.EXTRA_SCREEN);
        i.removeExtra(Plans.EXTRA_REARM);
    }

    @Override
    protected void onResume() {
        super.onResume();
        visible = true;
        main.post(poll);
        if (shownPhase == null || shownPhase == SessionController.Phase.IDLE) showHome();
        if (pendingRearm) {
            pendingRearm = false;
            // the app is on screen now, so the camera service may start
            main.post(new Runnable() {
                @Override
                public void run() {
                    armPlan();
                }
            });
        }
        if (pendingLaunch != null) {
            final String what = pendingLaunch;
            pendingLaunch = null;
            main.post(new Runnable() {
                @Override
                public void run() {
                    launch(what);
                }
            });
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
        stopCalibrator();
        // the screen was turned on for the tile once; not again
        setTurnScreenOn(false);
        // settings or the plan may have changed: the widget and the shortcuts follow
        Glance.refresh(this);
        main.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(poll);
        try {
            unbindService(conn);
        } catch (IllegalArgumentException ignored) {
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------------------------------------
    // State -> screen
    // ------------------------------------------------------------------------------------------------

    private void refresh() {
        if (service == null) return;
        SessionController.Status s = service.controller().status();
        String pe = service.planError();
        if (pe != null && service.planErrorMs() != shownPlanErrorMs) {
            shownPlanErrorMs = service.planErrorMs();
            new AlertDialog.Builder(this).setMessage(pe).setPositiveButton("OK", null).show();
        }
        if (screenOf(shownPhase) == 0 && service.plans().version() != shownPlanVersion) {
            // the plan moved on (a start, a result, a retry): show it as it is now
            if (page == PAGE_PLAN) showPlan();
            else if (page == PAGE_HOME) fillNightBox();
            shownPlanVersion = service.plans().version();
        }
        if (s.version == shownVersion) return;
        shownVersion = s.version;
        SessionController.Phase p = s.phase;
        boolean active = p != SessionController.Phase.IDLE && p != SessionController.Phase.DONE && p != SessionController.Phase.FAILED;
        setActiveWindow(active);
        if (screenOf(p) != screenOf(shownPhase)) {
            switch (screenOf(p)) {
                case 0: showHome(); break;
                case 1: showArming(); break;
                case 2: showCapturing(); break;
                case 3: showDeveloping(s); break;
                case 4:
                    if (s.reading != null && s.result == null) showReading(s);
                    else showResult(s);
                    break;
                default: showFailed(s); break;
            }
        }
        shownPhase = p;
        if (headline != null) headline.setText(s.headline);
        if (detail != null) detail.setText(s.detail);
        if (siteView != null) {
            siteView.setText(s.siteLine);
            siteView.setVisibility(s.siteLine.isEmpty() ? View.GONE : View.VISIBLE);
        }
        if (checks != null && s.preflight != shownChecks) {
            shownChecks = s.preflight;
            checks.setText(s.preflight != null ? s.preflight.describe() : "");
            checks.setVisibility(s.preflight != null ? View.VISIBLE : View.GONE);
        }
        if (bar != null) {
            if (s.progress >= 0) {
                bar.setIndeterminate(false);
                bar.setProgress(Math.round(s.progress * 1000));
            } else {
                bar.setIndeterminate(true);
            }
        }
        Bitmap pv = service.controller().preview();
        if (s.previewVersion != shownPreview && pv != null) {
            shownPreview = s.previewVersion;
            if (live != null) live.setImageBitmap(pv);
            if (zoom != null) zoom.show(pv, true);
            if (editZoom != null && (edit == null || !edit.comparing)) editZoom.show(pv, true);
        }
        if (devNote != null) styleDeveloping(s);
        if (saveVersion != null && s.result != null && resultBrightness != null) {
            float cur = resultBrightness.getProgress() / 100f;
            saveVersion.setEnabled(p == SessionController.Phase.DONE && Math.abs(cur - shownBrightness) > 0.005f);
        }
    }

    /** 0 home, 1 placing, 2 capturing, 3 developing, 4 result, 5 failed */
    private static int screenOf(SessionController.Phase p) {
        if (p == null) return -1;
        switch (p) {
            case IDLE: return 0;
            case STARTING:
            case PLACE:
            case STEADY:
            case FOCUS:
            case PREFLIGHT:
            case COUNTDOWN: return 1;
            case CAPTURING:
            case PAUSED: return 2;
            case FINISHING:
            case DEVELOPING: return 3;
            case DONE: return 4;
            default: return 5;
        }
    }

    private void setActiveWindow(boolean active) {
        if (windowActive != null && windowActive == active) return;
        windowActive = active;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        if (active) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            lp.screenBrightness = qrBright ? 0.6f : 0.12f;
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        getWindow().setAttributes(lp);
    }

    private void clearRefs() {
        closeReport();
        setPageBack(false);
        nightBox = null;
        headline = detail = stage = null;
        checks = null;
        siteView = null;
        presetBox = null;
        homeScroll = null;
        shownChecks = null;
        bar = null;
        live = null;
        zoom = null;
        editZoom = null;
        devNote = null;
        devCancel = null;
        resultBrightness = null;
        saveVersion = null;
        shownPreview = -1;
        viewerBox = null;
        shownViewer = null;
        satBox = null;
        setQrBright(false);
        stopCalibrator();
    }

    // ------------------------------------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------------------------------------

    /** The idle screen: the home page, or the plan, editor or summary page Brandon is on. */
    private void showHome() {
        switch (page) {
            case PAGE_PLAN:
                showPlan();
                break;
            case PAGE_EDIT:
                showEditor();
                break;
            case PAGE_SUMMARY:
                showSummary();
                break;
            case PAGE_SITES:
                showSites();
                break;
            case PAGE_FOCUS:
                showFocus();
                break;
            case PAGE_SESSIONS:
                showSessions();
                break;
            case PAGE_MERGE:
                showMergePage();
                break;
            default:
                showHomePage();
                break;
        }
    }

    private void showHomePage() {
        page = PAGE_HOME;
        clearRefs();
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = column();
        sv.addView(col);
        root.addView(sv, match());
        homeScroll = sv;

        TextView title = text("Night Sky", 30, RED, true);
        col.addView(title);
        col.addView(text("Long exposures of the stars from your Pixel's main camera", 14, RED_DIM, false), gap(4));

        // tonight: the armed plan, a plan to re-arm, last night's summary, or the sky and a way to plan it
        nightBox = new LinearLayout(this);
        nightBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(nightBox, gap(16));
        fillNightBox();

        // unfinished session from a crash or a flat battery
        List<File> unfinished = SessionController.unfinished(this);
        if (!unfinished.isEmpty()) {
            final File dir = unfinished.get(0);
            int n = SessionController.unitCount(dir);
            LinearLayout card = card();
            card.addView(text("A session wasn't finished (" + n + " frames saved).", 16, RED, true));
            LinearLayout row = row();
            Button dev = button("Develop it", true);
            dev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, CaptureService.class).setAction(CaptureService.ACTION_RECOVER)
                            .putExtra(CaptureService.EXTRA_DIR, dir.getAbsolutePath());
                    startForegroundService(i);
                }
            });
            Button del = button("Delete", false);
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirm("Delete the saved frames of that session?", new Runnable() {
                        @Override
                        public void run() {
                            SessionController.delete(dir);
                            showHome();
                        }
                    });
                }
            });
            row.addView(dev, weight());
            row.addView(del, weight());
            card.addView(row, gap(10));
            col.addView(card, gap(16));
        }

        // storage running out: offer to delete old sessions' frames (listing them, asking first)
        long free = freeBytes();
        long need = roughNeedBytes(prefs.durationMin() * 60);
        if (free >= 0 && free < 2 * need) {
            LinearLayout card = card();
            card.addView(text(String.format(Locale.US, "Storage is low: %.1f GB free. A %d-minute session needs about %.1f GB.",
                    free / 1e9, prefs.durationMin(), need / 1e9), 15, RED, true));
            Button clean = button("Free up space", false);
            clean.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    offerCleanup();
                }
            });
            card.addView(clean, gap(8));
            col.addView(card, gap(16));
        }

        // presets (E6): one tap for the mode, the length, the brightness and the rest; at a saved site, its own
        String picked = autoPickSite();
        if (picked != null) sitePicked = picked;
        col.addView(label("Preset"), gap(22));
        presetBox = new LinearLayout(this);
        presetBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(presetBox, gap(8));
        fillPresets();

        // mode
        col.addView(label("Mode"), gap(22));
        final Button deep = chip("Deep sky", !prefs.trails());
        final Button trails = chip("Star trails", prefs.trails());
        final TextView modeHelp = text(modeHelp(prefs.trails()), 13, RED_DIM, false);
        View.OnClickListener modeClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean t = v == trails;
                prefs.setTrails(t);
                styleChip(deep, !t);
                styleChip(trails, t);
                modeHelp.setText(modeHelp(t));
                showSatellites();
                handChanged();
            }
        };
        deep.setOnClickListener(modeClick);
        trails.setOnClickListener(modeClick);
        LinearLayout modes = row();
        modes.addView(deep, weight());
        modes.addView(trails, weight());
        col.addView(modes, gap(8));
        col.addView(modeHelp, gap(6));

        // satellites in the camera's view: the passes, and the quietest stretch to start in (deep sky only)
        satBox = card();
        col.addView(satBox, gap(14));
        showSatellites();
        Satellites.refresh(this, false, new Satellites.Callback() {
            @Override
            public void done(String log) {
                if (!log.isEmpty()) showSatellites();
            }
        });

        // length
        col.addView(label("How long"), gap(22));
        LinearLayout lens = row();
        final Button[] lenChips = new Button[Prefs.DURATIONS_MIN.length];
        for (int k = 0; k < Prefs.DURATIONS_MIN.length; k++) {
            final int min = Prefs.DURATIONS_MIN[k];
            Button b = chip(min + "m", prefs.durationMin() == min);
            b.setTextSize(15);
            lenChips[k] = b;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setDurationMin(min);
                    for (int j = 0; j < lenChips.length; j++) styleChip(lenChips[j], Prefs.DURATIONS_MIN[j] == min);
                    showSatellites();
                    handChanged();
                }
            });
            lens.addView(b, weight());
        }
        col.addView(lens, gap(8));

        // brightness
        col.addView(label("Brightness"), gap(22));
        final TextView bval = text(brightnessLabel(prefs.brightness()), 13, RED_DIM, false);
        SeekBar sb = seek(Math.round(prefs.brightness() * 100));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int v, boolean user) {
                if (!user) return;
                prefs.setBrightness(v / 100f);
                bval.setText(brightnessLabel(v / 100f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                handChanged();
            }
        });
        col.addView(sb, gap(4));
        col.addView(bval);

        // options
        CheckBox snd = check("Beeps and voice", prefs.sounds());
        snd.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setSounds(on);
            }
        });
        CheckBox dng = check("Also save a RAW (DNG) file", prefs.saveDng());
        dng.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setSaveDng(on);
                handChanged();
            }
        });
        CheckBox viewer = check("Viewer: watch and stop it from a laptop or another phone on the same Wi-Fi", prefs.remoteViewer());
        viewer.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setRemoteViewer(on);
            }
        });
        CheckBox pre = check("Check the sky first: smudged lens, trees, clouds, the Moon (about 15 seconds)", prefs.preflight());
        pre.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setPreflight(on);
            }
        });
        col.addView(snd, gap(18));
        col.addView(pre);
        col.addView(dng);
        col.addView(viewer);
        addAdvanced(col);

        Button start = button("Start", true);
        start.setTextSize(22);
        start.setMinHeight(dp(64));
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSession(0);
            }
        });
        col.addView(start, gap(24));
        col.addView(text("Tap Start, then lay the phone screen-down on the ground or a table with the back camera "
                + "facing the sky. Once it's still, it checks the sky and says what it found, then starts by itself. "
                + "It beeps when it's done.", 14, RED_DIM, false), gap(10));

        final String last = prefs.lastResultUri();
        if (last != null) {
            Button open = button("Open the last photo", false);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    view(Uri.parse(last));
                }
            });
            col.addView(open, gap(18));
        }
        Button sessions = button("Sessions: open, merge nights, free space", false);
        sessions.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPage(PAGE_SESSIONS);
            }
        });
        col.addView(sessions, gap(last != null ? 10 : 18));
        Button reading = button("Sky reading: how dark is it here?", false);
        reading.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSession(0, Launch.of("reading"));
            }
        });
        col.addView(reading, gap(10));
        col.addView(text("Version " + BuildInfo.VERSION + " · photos are saved in Pictures/NightSky", 12, RED_DIM, false), gap(24));
    }

    /**
     * Fills the home screen's satellite card: the passes through the camera's view for a session started now,
     * the quietest stretch in the next hour (with a button to start then), and the bright passes of the night.
     * Worked out off the main thread (thousands of orbits), and kept for two minutes.
     */
    private void showSatellites() {
        final LinearLayout box = satBox;
        if (box == null) return;
        box.removeAllViews();
        boolean on = prefs.satelliteData() && !prefs.trails();
        box.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) return;
        box.addView(text("Satellites", 16, RED, true));
        final TextView body = text("Working out the passes\u2026", 14, RED_DIM, false);
        box.addView(body, gap(4));
        final int tk = ++satToken;
        final Context app = getApplicationContext();
        final Locator.Place place = Locator.best(this, prefs);
        final int sec = prefs.durationMin() * 60;
        final boolean keep = prefs.keepSatellites();
        final long stamp = Satellites.data(this).newestFetchMs();
        final String key = sec + "|" + keep + "|" + stamp + "|" + (place != null ? Math.round(place.latDeg * 100) + "," + Math.round(place.lonDeg * 100) : "-");
        synchronized (MainActivity.class) {
            if (lastOutlook != null && key.equals(lastOutlookKey) && System.currentTimeMillis() - lastOutlookMs < 120_000L) {
                fillSatellites(box, body, lastOutlook, true, true, null);
                return;
            }
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                SatPlanner.Outlook o = null;
                String err = null;
                SatCatalog cat = null;
                try {
                    cat = Satellites.catalogNow(app);
                    if (cat != null && place != null) {
                        double hKm = Double.isNaN(place.altM) ? 0 : place.altM / 1000;
                        SatSky.Site site = new SatSky.Site(Math.toRadians(place.latDeg), Math.toRadians(place.lonDeg), hKm);
                        o = SatPlanner.outlook(cat, site, System.currentTimeMillis(), sec, keep, TimeZone.getDefault());
                        synchronized (MainActivity.class) {
                            lastOutlook = o;
                            lastOutlookKey = key;
                            lastOutlookMs = System.currentTimeMillis();
                        }
                    }
                } catch (RuntimeException e) {
                    err = e.toString();
                }
                final SatPlanner.Outlook fo = o;
                final boolean hasData = cat != null;
                final String fe = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tk == satToken && satBox == box) fillSatellites(box, body, fo, hasData, place != null, fe);
                    }
                });
            }
        }, "satellite-outlook").start();
    }

    private void fillSatellites(LinearLayout box, TextView body, SatPlanner.Outlook o, boolean hasData, boolean located, String err) {
        if (err != null) {
            body.setText("The passes couldn't be worked out (" + err + ").");
        } else if (!hasData) {
            body.setText("No satellite data yet. It downloads from CelesTrak when the phone is online (the Starlink lists on Wi-Fi "
                    + "only), then streaks are named and bright passes waited out.");
        } else if (!located || o == null) {
            body.setText("The passes need the phone's place: allow location when you start a session.");
        } else {
            String t = "If you start now: " + o.next.text;
            if (!o.text.isEmpty()) t += "\n\n" + o.text;
            body.setText(t);
            if (o.next.offerQuiet) {
                final long at = o.next.quietStartMs;
                Button b = button("Start at " + SessionController.clockTime(at) + " (quietest)", false);
                b.setTextSize(15);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (at - System.currentTimeMillis() < 60_000L) {
                            toastish("That stretch has started: tap Start");
                            return;
                        }
                        startSession(at);
                    }
                });
                box.addView(b, gap(8));
            }
        }
        box.addView(text(Satellites.data(this).status(System.currentTimeMillis()), 12, RED_DIM, false), gap(6));
    }

    private static String keepHelp(boolean keep) {
        return keep
                ? "Satellites and meteors stay in the picture at full brightness. A bright pass is counted down aloud "
                + "so you can watch it go over."
                : "Their streaks are taken out of the stack, faint edges included, and the session waits for a bright one "
                + "that's about to cross (the ISS, Tiangong) before starting. The report and a labelled copy name them.";
    }

    /** Settings most nights don't need, folded away under one line. */
    private void addAdvanced(LinearLayout col) {
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView head = text(advancedHead(prefs.advancedOpen()), 15, RED, true);
        head.setPadding(0, dp(10), 0, dp(10));
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean open = !prefs.advancedOpen();
                prefs.setAdvancedOpen(open);
                head.setText(advancedHead(open));
                box.setVisibility(open ? View.VISIBLE : View.GONE);
            }
        });
        box.setVisibility(prefs.advancedOpen() ? View.VISIBLE : View.GONE);
        col.addView(head, gap(10));
        col.addView(box);

        box.addView(label("Removing satellites and planes"), gap(4));
        final Rejection.Method[] ms = {Rejection.Method.AUTO, Rejection.Method.PERCENTILE, Rejection.Method.WINSORIZED,
                Rejection.Method.GESD, Rejection.Method.LINEAR_FIT, Rejection.Method.SIGMA};
        final String[] names = {"Automatic", "Percentile", "Winsorized", "GESD", "Linear fit", "Sigma"};
        final Button[] chips = new Button[ms.length];
        final Rejection.Method current = Rejection.Method.parse(prefs.rejection());
        final TextView help = text(rejectionHelp(current), 13, RED_DIM, false);
        LinearLayout r1 = row(), r2 = row();
        for (int k = 0; k < ms.length; k++) {
            final Rejection.Method m = ms[k];
            Button b = chip(names[k], m == current);
            b.setTextSize(14);
            chips[k] = b;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setRejection(m.name());
                    for (int j = 0; j < ms.length; j++) styleChip(chips[j], ms[j] == m);
                    help.setText(rejectionHelp(m));
                    handChanged();
                }
            });
            (k < 3 ? r1 : r2).addView(b, weight());
        }
        box.addView(r1, gap(8));
        box.addView(r2, gap(6));
        box.addView(help, gap(6));

        // focusing on the stars (A5) and the daylight calibration (F7)
        box.addView(label("Focus"), gap(20));
        CheckBox fa = check("Focus on the stars before each session (about 30 seconds of short frames), and check the focus "
                + "during sessions of half an hour or more", prefs.focusAssist());
        fa.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setFocusAssist(on);
            }
        });
        box.addView(fa, gap(6));
        box.addView(text(focusModelWords(), 13, RED_DIM, false), gap(2));
        Button cal = button("Daylight focus calibration", false);
        cal.setTextSize(15);
        cal.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPage(PAGE_FOCUS);
            }
        });
        box.addView(cal, gap(6));

        // satellites: the orbital data, and whether their streaks stay
        box.addView(label("Satellites"), gap(20));
        final TextView status = text(Satellites.data(this).status(System.currentTimeMillis()), 13, RED_DIM, false);
        final Button update = button("Update the satellite data", false);
        update.setTextSize(15);
        update.setEnabled(prefs.satelliteData());
        CheckBox data = check("Use satellite data: download orbits from CelesTrak (public, nothing is sent) to name streaks "
                + "and plan around bright passes", prefs.satelliteData());
        data.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setSatelliteData(on);
                update.setEnabled(on);
                showSatellites();
            }
        });
        update.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                update.setEnabled(false);
                update.setText("Updating\u2026");
                Satellites.refresh(MainActivity.this, true, new Satellites.Callback() {
                    @Override
                    public void done(String log) {
                        update.setEnabled(prefs.satelliteData());
                        update.setText("Update the satellite data");
                        status.setText(Satellites.data(MainActivity.this).status(System.currentTimeMillis()));
                        if (log.isEmpty()) {
                            toastish("Nothing newer yet: CelesTrak updates the orbits every 2 hours"
                                    + (Satellites.metered(MainActivity.this) ? " (Starlink comes over Wi-Fi only)" : ""));
                        } else {
                            toastish(log.contains("failed") || log.contains("refused") ? log : "Satellite data updated");
                        }
                        showSatellites();
                    }
                });
            }
        });
        box.addView(data, gap(6));
        box.addView(status, gap(2));
        box.addView(update, gap(6));
        box.addView(text("Satellite streaks", 14, RED, true), gap(14));
        final Button out = chip("Take them out", !prefs.keepSatellites());
        final Button keep = chip("Keep them", prefs.keepSatellites());
        out.setTextSize(14);
        keep.setTextSize(14);
        final TextView keepHelp = text(keepHelp(prefs.keepSatellites()), 13, RED_DIM, false);
        View.OnClickListener keepClick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean k = v == keep;
                prefs.setKeepSatellites(k);
                styleChip(out, !k);
                styleChip(keep, k);
                keepHelp.setText(keepHelp(k));
                showSatellites();
                handChanged();
            }
        };
        out.setOnClickListener(keepClick);
        keep.setOnClickListener(keepClick);
        LinearLayout kr = row();
        kr.addView(out, weight());
        kr.addView(keep, weight());
        box.addView(kr, gap(6));
        box.addView(keepHelp, gap(6));

        // what the Partly cloudy and Quick look presets turn on (E6)
        box.addView(label("Clouds and quick looks"), gap(20));
        CheckBox collect = check("Keep going until the chosen minutes of clear sky are in, for up to three times as long (hazy "
                + "frames count in part)", prefs.collectClear());
        collect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setCollectClear(on);
                handChanged();
            }
        });
        CheckBox blend = check("Also save a cloud-blur version: the plain average, where passing clouds turn into soft streaks "
                + "(the stars trail a little)", prefs.blend());
        blend.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setBlend(on);
                handChanged();
            }
        });
        CheckBox quick = check("Quick developing: one stacking pass, about twice as fast, and a half-size photo", prefs.quick());
        quick.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setQuick(on);
                handChanged();
            }
        });
        box.addView(collect, gap(6));
        box.addView(blend);
        box.addView(quick);

        // the sessions' frames kept for merging and adding minutes, and the volume key (G1)
        box.addView(label("Sessions and storage"), gap(20));
        box.addView(text("After developing, a session's frames stay on the phone so it can be merged with other nights of the "
                + "same sky, or have minutes added. Beyond this much, the oldest sessions' frames go first (their photos stay), "
                + "when the next session starts or is developed:", 13, RED_DIM, false), gap(4));
        LinearLayout kr1 = row(), kr2 = row();
        final Button[] kchips = new Button[Prefs.KEEP_FRAMES_GB.length];
        for (int k = 0; k < Prefs.KEEP_FRAMES_GB.length; k++) {
            final int g = Prefs.KEEP_FRAMES_GB[k];
            Button b = chip(g == 0 ? "Off" : g + " GB", prefs.keepFramesGb() == g);
            b.setTextSize(14);
            kchips[k] = b;
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setKeepFramesGb(g);
                    for (int j = 0; j < kchips.length; j++) styleChip(kchips[j], Prefs.KEEP_FRAMES_GB[j] == g);
                    if (g == 0) toastish("Off: each session's frames are deleted once its photo is saved, as before version 1.2");
                }
            });
            (k < 3 ? kr1 : kr2).addView(b, weight());
        }
        box.addView(kr1, gap(8));
        box.addView(kr2, gap(6));
        final TextView keptNow = text("", 13, RED_DIM, false);
        box.addView(keptNow, gap(6));
        final File sroot = SessionController.sessionsRoot(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                long n = 0;
                int with = 0;
                for (SessionStore st : SessionStore.list(sroot)) {
                    long b = SessionLibrary.frameBytes(st);
                    n += b;
                    if (b > 0) with++;
                }
                final String t = "Kept now: " + SessionLibrary.gb(n) + " of frames in " + with + (with == 1 ? " session." : " sessions.");
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        keptNow.setText(t);
                    }
                });
            }
        }, "kept").start();
        CheckBox key = check("Volume up, or a Bluetooth remote's button, adds 5 minutes while capturing", prefs.remoteKey());
        key.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setRemoteKey(on);
            }
        });
        box.addView(key, gap(8));
        Button ses = button("Sessions\u2026", false);
        ses.setTextSize(15);
        ses.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPage(PAGE_SESSIONS);
            }
        });
        box.addView(ses, gap(6));

        // the settings file (E6)
        box.addView(label("Settings file"), gap(20));
        box.addView(text("All the settings, your presets and your sites in one file in Documents/NightSky: keep a copy, or "
                + "load it after reinstalling or on a new phone. Nothing is sent anywhere.", 13, RED_DIM, false), gap(4));
        LinearLayout io = row();
        Button exp = button("Save to a file", false);
        exp.setTextSize(15);
        exp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportSettings();
            }
        });
        Button imp = button("Load a file…", false);
        imp.setTextSize(15);
        imp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importSettings();
            }
        });
        io.addView(exp, weight());
        io.addView(imp, weight());
        box.addView(io, gap(6));

        // the Quick Settings tile (E3)
        box.addView(label("Quick Settings tile"), gap(20));
        CheckBox unlock = check("Ask to unlock the phone first (otherwise the tile opens the app over the lock screen, ready to "
                + "start)", prefs.tileUnlocks());
        unlock.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setTileUnlocks(on);
            }
        });
        box.addView(unlock, gap(6));
        box.addView(text("To add the tile: swipe down twice from the top, tap the pencil, and drag \"Night sky\" into your "
                + "tiles. For the widget, touch and hold the home screen, then Widgets > Night Sky.", 13, RED_DIM, false), gap(2));

        // sky readings (H3)
        box.addView(label("Sky readings"), gap(20));
        int n = SkyReadings.count(this);
        double off = prefs.sqmOffset();
        box.addView(text(n + (n == 1 ? " reading" : " readings") + " in the log"
                + (off != 0 ? String.format(Locale.US, ". Matched to a meter: %+.2f added.", off) : "."), 13, RED_DIM, false), gap(4));
        Button export = button("Save the readings (CSV for Excel)", false);
        export.setTextSize(15);
        export.setEnabled(n > 0);
        export.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportReadings();
            }
        });
        box.addView(export, gap(6));
    }

    private static String advancedHead(boolean open) {
        return open ? "Advanced \u25BE" : "Advanced \u25B8";
    }

    private static String rejectionHelp(Rejection.Method m) {
        switch (m) {
            case PERCENTILE:
                return "Drops values far from each pixel's middle value. Best with 6 frames or fewer.";
            case WINSORIZED:
                return "Sigma clipping that first pulls in the extremes, so one bright trail can't hide another. "
                        + "Best from 7 to about 50 frames.";
            case GESD:
                return "A statistical test that removes outliers one at a time. Best with more than 50 frames.";
            case LINEAR_FIT:
                return "Follows a sky that brightens or darkens during the session (twilight, moonrise) before clipping.";
            case SIGMA:
                return "Plain sigma clipping in one pass. Uses the least memory.";
            default:
                return "Picks for you from the number of frames, and switches to linear fit when the sky brightens "
                        + "during the session. The log says which it used and why.";
        }
    }

    private static String modeHelp(boolean trails) {
        return trails
                ? "Each star draws its arc across the sky. Longer is better: try 20 or 30 minutes."
                : "Lines up the stars between frames and stacks them into one deep, clean photo.";
    }

    private static String brightnessLabel(float b) {
        if (b < 0.3f) return Math.round(b * 100) + "% · dark, natural night";
        if (b > 0.7f) return Math.round(b * 100) + "% · bright, pulls out faint stars";
        return Math.round(b * 100) + "%";
    }

    /**
     * A session started from the tile, the widget or a shortcut (E3): what it changes from the saved settings, for
     * this session only (null: as saved).
     */
    static final class Launch {
        /** A preset's settings, for this session (its id; null for none). */
        String preset;
        Boolean trails, saveDng;
        Integer durationMin;
        /** A sky reading: the check only, no photo. */
        boolean reading;
        /** For the session's log: "shortcut: Star trails 30 min". */
        String origin = "";
        /** "Add 5 minutes" after a session (G1): the folder of the session they add to (null: a session of its own). */
        String continueFrom;

        /** Five more minutes of the session in {@code folder}, stacked with it when they're developed. */
        static Launch addTo(String folder) {
            Launch l = new Launch();
            l.continueFrom = folder;
            l.durationMin = 5;
            return l;
        }

        static Launch of(String what) {
            Launch l = new Launch();
            switch (what) {
                case "stack5":
                    l.trails = false;
                    l.durationMin = 5;
                    l.origin = "shortcut: Arm 5-minute stack";
                    return l;
                case "trails30":
                    l.trails = true;
                    l.durationMin = 30;
                    l.origin = "shortcut: Star trails 30 min";
                    return l;
                case "quick":
                    // the Quick look preset: two minutes, JPEG only, quick developing
                    l.preset = Preset.QUICK;
                    l.origin = "shortcut: Quick look";
                    return l;
                case "reading":
                    l.reading = true;
                    l.trails = false;
                    l.durationMin = 1;
                    l.saveDng = false;
                    l.origin = "shortcut: Sky reading";
                    return l;
                default:
                    return null;
            }
        }
    }

    /** Does what the tile, the widget or a shortcut asked for (the screen is up). */
    private void launch(String what) {
        if (what == null) return;
        if (service == null) {
            // not connected yet: whether a session is running isn't known
            launchAfterBind = what;
            return;
        }
        Shortcuts.used(this, what);
        SessionController.Status s = service != null ? service.controller().status() : null;
        boolean busy = s != null && screenOf(s.phase) >= 1 && screenOf(s.phase) <= 3;
        switch (what) {
            case "plan":
                if (!busy) openPage(PAGE_PLAN);
                return;
            case "armplan":
                if (busy) break;
                editPlan = null;
                openPage(PAGE_PLAN);
                armPlan();
                return;
            case "summary":
                if (!busy) openPage(PAGE_SUMMARY);
                return;
            case "arm":
            case "stack5":
            case "trails30":
            case "quick":
            case "reading":
                if (busy) break;
                if (screenOf(shownPhase) != 0 && shownPhase != null && service != null) service.controller().clearResult();
                page = PAGE_HOME;
                showHome();
                startSession(0, Launch.of(what));
                return;
            default:
                if (!busy && page != PAGE_HOME) openPage(PAGE_HOME);
                return;
        }
        toastish("A session is running: it's on screen");
    }

    /** Starts a session; {@code startAtMs}: the capture begins no earlier than this (0: once the phone is ready). */
    private void startSession(long startAtMs) {
        startSession(startAtMs, null);
    }

    /** The same, with some settings changed for this session only ({@code l} null: the saved settings). */
    private void startSession(long startAtMs, Launch l) {
        pendingStartAt = startAtMs;
        pendingLaunchSettings = l;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            askedNotifications = true; // asked together with the camera: don't ask again if it is refused
            prefs.setAskedLocation();
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
            return;
        }
        if (!prefs.askedLocation() && !Locator.allowed(this)) {
            // optional: the approximate place, for the Sun, the Moon and the compass (asked once)
            prefs.setAskedLocation();
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 4);
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !askedNotifications) {
            // optional: only for the progress notification
            askedNotifications = true;
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
            return;
        }
        pendingLaunchSettings = null;
        boolean reading = l != null && l.reading;
        // the service starts from the saved settings; a shortcut changes some for this session only
        Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_VIEWER, prefs.remoteViewer() && !reading)
                .putExtra(CaptureService.EXTRA_START_AT, startAtMs)
                .putExtra(CaptureService.EXTRA_READING, reading)
                .putExtra(CaptureService.EXTRA_ORIGIN, l != null ? l.origin : "");
        if (l != null) {
            if (l.preset != null) i.putExtra(CaptureService.EXTRA_PRESET, l.preset);
            if (l.trails != null) i.putExtra(CaptureService.EXTRA_TRAILS, (boolean) l.trails);
            if (l.durationMin != null) i.putExtra(CaptureService.EXTRA_DURATION, l.durationMin * 60);
            if (l.saveDng != null) i.putExtra(CaptureService.EXTRA_DNG, (boolean) l.saveDng);
            if (l.continueFrom != null) i.putExtra(CaptureService.EXTRA_CONTINUE, l.continueFrom);
        }
        startForegroundService(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 3) {
            if (service != null && android.os.Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
                service.startHotspot();
            } else {
                toastish("The hotspot needs the Nearby devices permission");
            }
            return;
        }
        if (code == 5) {
            // location for tonight's sky
            if (screenOf(shownPhase) == 0 || shownPhase == null) showHome();
            return;
        }
        if (code == REQ_FOCUS_CAMERA) {
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                toastish("The calibration needs the camera: allow it in Settings > Apps > Night Sky > Permissions");
            }
            if (page == PAGE_FOCUS && screenOf(shownPhase) <= 0) showFocus();
            return;
        }
        if (code == REQ_SITES_LOCATION) {
            if (!Locator.allowed(this)) toastish("Location is off for the app: allow it in Settings > Apps > Night Sky > Permissions");
            if (page == PAGE_SITES && screenOf(shownPhase) <= 0) showSites();
            return;
        }
        if (pendingArm) {
            pendingArm = false;
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                armPlan();
            } else {
                new AlertDialog.Builder(this).setMessage("A night plan needs the camera to take its photos. You can allow it in "
                        + "Settings > Apps > Night Sky > Permissions.").setPositiveButton("OK", null).show();
            }
            return;
        }
        if (!pendingStart) return;
        pendingStart = false;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startSession(pendingStartAt, pendingLaunchSettings);
        } else {
            new AlertDialog.Builder(this).setMessage("The app needs the camera to take the photo. You can allow it in "
                    + "Settings > Apps > Night Sky > Permissions.").setPositiveButton("OK", null).show();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Tonight and the night plan (E2)
    // ------------------------------------------------------------------------------------------------

    /** Tonight at a place: darkness, the Moon, the forecast's clouds, and the best window. Null without a place. */
    private Tonight tonight(Locator.Place place) {
        if (place == null) return null;
        Tonight.Forecast fc = Forecasts.cached(this, place.latDeg, place.lonDeg);
        return Tonight.compute(place.latDeg, place.lonDeg, System.currentTimeMillis(), TimeZone.getDefault(), fc, Double.NaN);
    }

    /** Asks for a new forecast if one is due, and redraws what shows tonight when it comes. */
    private void refreshForecast(Locator.Place place) {
        if (place == null || !prefs.forecast()) return;
        final int tk = ++forecastToken;
        Forecasts.refresh(this, place.latDeg, place.lonDeg, false, new Forecasts.Callback() {
            @Override
            public void done(Tonight.Forecast f, String error) {
                if (tk != forecastToken || f == null || f.fetchedMs == shownForecastMs) return;
                Glance.refresh(MainActivity.this);
                if (screenOf(shownPhase) != 0 && shownPhase != null) return;
                if (page == PAGE_HOME && nightBox != null) fillNightBox();
                else if (page == PAGE_PLAN) showPlan();
            }
        });
    }

    /** Last night's plan if its summary hasn't been looked at (the plan just run, or the newest past one). */
    private NightPlan nightToSummarise(NightPlan saved) {
        NightPlan p = saved != null && !saved.armed && saved.finished() ? saved : Plans.lastArchived(this);
        if (p == null || p.summaryDone || p.armed) return null;
        long ended = 0;
        for (NightPlan.Item it : p.items) ended = Math.max(ended, it.endedMs);
        if (ended == 0 || System.currentTimeMillis() - ended > 36 * 3600_000L) return null;
        return p;
    }

    /** The home screen's card about tonight: the armed plan, a plan to re-arm, last night's summary, the sky. */
    private void fillNightBox() {
        LinearLayout box = nightBox;
        if (box == null) return;
        box.removeAllViews();
        long now = System.currentTimeMillis();
        PlanRunner runner = service != null ? service.plans() : null;
        shownPlanVersion = runner != null ? runner.version() : -1;
        NightPlan saved = Plans.load(this);
        if (runner != null && runner.armed()) {
            LinearLayout c = card();
            c.addView(text("Night plan armed", 17, RED, true));
            c.addView(text("Next: " + runner.nextText() + ".", 14, RED, false), gap(4));
            c.addView(text("Leave the phone screen-down, camera to the sky. It starts by itself.", 13, RED_DIM, false), gap(4));
            Button open = button("Open the plan", false);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPage(PAGE_PLAN);
                }
            });
            c.addView(open, gap(10));
            box.addView(c);
            return;
        }
        if (saved != null && saved.armed && !saved.finished()) {
            LinearLayout c = card();
            c.addView(text("Tonight's plan needs re-arming", 17, RED, true));
            c.addView(text("The phone or the app restarted while it was armed, and Android doesn't let the camera start by itself "
                    + "after that. One tap arms it again; the phone can stay where it is.", 14, RED_DIM, false), gap(4));
            LinearLayout r = row();
            Button re = button("Re-arm", true);
            re.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    armPlan();
                }
            });
            Button open = button("Open the plan", false);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPage(PAGE_PLAN);
                }
            });
            r.addView(re, weight());
            r.addView(open, weight());
            c.addView(r, gap(10));
            box.addView(c);
            return;
        }
        final NightPlan last = nightToSummarise(saved);
        if (last != null) {
            LinearLayout c = card();
            c.addView(text(last.summaryTitle(), 17, RED, true));
            LinearLayout pics = row();
            int shown = 0;
            for (NightPlan.Item it : last.items) {
                if (shown >= 4) break;
                Bitmap b = Plans.thumb(this, it.thumb);
                if (b == null) continue;
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(b);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(64), 1f);
                lp.rightMargin = dp(4);
                pics.addView(iv, lp);
                shown++;
            }
            if (shown > 0) c.addView(pics, gap(8));
            Button see = button("See last night", true);
            see.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    summaryPlan = last;
                    openPage(PAGE_SUMMARY);
                }
            });
            c.addView(see, gap(10));
            box.addView(c);
        }
        // tonight's sky
        Locator.Place place = Locator.best(this, prefs);
        Tonight t = tonight(place);
        LinearLayout c = card();
        if (t == null) {
            c.addView(text("Tonight", 17, RED, true));
            c.addView(text("Allow location to see tonight's darkness, the Moon and the best window, and to plan the night.",
                    14, RED_DIM, false), gap(4));
            Button loc = button("Allow location", false);
            loc.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setAskedLocation();
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 5);
                }
            });
            c.addView(loc, gap(10));
        } else {
            shownForecastMs = t.hasForecast ? now - t.forecastAgeMs : 0;
            c.addView(text(t.headline, 17, RED, true));
            for (int i = 0; i < Math.min(3, t.lines.size()); i++) c.addView(text(t.lines.get(i), 14, RED_DIM, false), gap(3));
        }
        String night = Plans.planningNight(now, t, TimeZone.getDefault());
        int planned = 0;
        if (saved != null && !saved.armed && saved.night.equals(night)) {
            for (NightPlan.Item it : saved.items) if (!it.finished()) planned++;
        }
        if (planned > 0) {
            c.addView(text("Tonight's plan: " + planned + (planned == 1 ? " session" : " sessions") + ", not armed yet.", 14, RED, false), gap(8));
        }
        Button plan = button(planned > 0 ? "Open tonight's plan" : "Plan the night", false);
        plan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editPlan = null;
                openPage(PAGE_PLAN);
            }
        });
        c.addView(plan, gap(10));
        box.addView(c, last != null ? gap(12) : gap(0));
        refreshForecast(place);
    }

    private void openPage(int p) {
        page = p;
        showHome();
    }

    /** Back from a page: the editor goes back to the plan, merging to the sessions, the rest to the home screen. */
    private boolean pageBack() {
        if (page == PAGE_HOME) return false;
        openPage(page == PAGE_EDIT ? PAGE_PLAN : page == PAGE_MERGE ? PAGE_SESSIONS : PAGE_HOME);
        return true;
    }

    private void setPageBack(boolean on) {
        if (Build.VERSION.SDK_INT < 33) return;
        if (on && pageBackCb == null) {
            OnBackInvokedCallback cb = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    pageBack();
                }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
            pageBackCb = cb;
        } else if (!on && pageBackCb != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) pageBackCb);
            pageBackCb = null;
        }
    }

    /** The page's frame: a scroll view with a column, and a back line at the top. */
    private LinearLayout pageColumn(String back) {
        clearRefs();
        root.removeAllViews();
        setPageBack(true);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = column();
        sv.addView(col);
        root.addView(sv, match());
        TextView b = text("‹ " + back, 15, RED_DIM, true);
        b.setPadding(0, dp(4), 0, dp(10));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pageBack();
            }
        });
        col.addView(b);
        return col;
    }

    /** Tonight's plan: its sessions, its options, and Arm (or, armed, what comes next and Cancel). */
    private void showPlan() {
        final long now = System.currentTimeMillis();
        final TimeZone zone = TimeZone.getDefault();
        final Locator.Place place = Locator.best(this, prefs);
        final Tonight t = tonight(place);
        PlanRunner runner = service != null ? service.plans() : null;
        shownPlanVersion = runner != null ? runner.version() : -1;
        final boolean armed = runner != null && runner.armed();
        NightPlan saved = Plans.load(this);
        final boolean needsRearm = !armed && saved != null && saved.armed && !saved.finished();
        final NightPlan p;
        if (armed || needsRearm) {
            p = saved;
            editPlan = null;
        } else {
            if (editPlan == null) {
                editPlan = Plans.forNight(this, Plans.planningNight(now, t, zone), place, now);
                if (editPlan.items.isEmpty()) {
                    editPlan.sounds = prefs.planSounds();
                    editPlan.minBatteryPct = prefs.planMinBattery();
                }
            }
            p = editPlan;
            p.resolve(t, now);
        }
        if (p == null) {
            openPage(PAGE_HOME);
            return;
        }
        LinearLayout col = pageColumn("Home");
        col.addView(text(armed ? "Tonight's plan, armed" : "Tonight's plan", 26, RED, true));
        col.addView(text(nightWords(p.night) + (t != null ? " · " + t.headline : ""), 14, RED_DIM, false), gap(4));
        if (t != null && !armed) {
            for (int i = 1; i < Math.min(3, t.lines.size()); i++) col.addView(text(t.lines.get(i), 13, RED_DIM, false), gap(2));
        }

        if (armed) {
            LinearLayout c = card();
            c.addView(text("Next: " + runner.nextText() + ".", 16, RED, true));
            long wake = runner.nextAtMs();
            c.addView(text((wake > 0 ? "The camera opens at " + Tonight.clock(wake, zone) + ". " : "")
                    + "Lay the phone screen-down, camera to the sky, before then; the screen can go off. On a long night, "
                    + "keep it on a charger or a power bank.", 14, RED_DIM, false), gap(6));
            col.addView(c, gap(14));
        } else if (needsRearm) {
            LinearLayout c = card();
            c.addView(text("This plan needs re-arming", 16, RED, true));
            c.addView(text("The phone or the app restarted while it was armed. Re-arming carries on where it was.", 14, RED_DIM, false), gap(4));
            col.addView(c, gap(14));
        }

        col.addView(label(p.items.isEmpty() ? "No sessions yet" : "Sessions"), gap(20));
        for (final NightPlan.Item it : p.items) {
            LinearLayout c = card();
            c.addView(text(itemTitle(p, it), 16, RED, true));
            String body = it.outcome.isEmpty() ? p.describe(it) : it.outcome;
            c.addView(text(body, 14, it.finished() ? RED_DIM : RED, false), gap(3));
            if (!armed && !needsRearm && !it.finished() && it.state != NightPlan.State.RUNNING) {
                Button rm = button("Remove", false);
                rm.setTextSize(14);
                rm.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        p.remove(it.id);
                        savePlan(p, t);
                        showPlan();
                    }
                });
                c.addView(rm, gap(8));
            }
            col.addView(c, gap(8));
        }

        if (!armed && !needsRearm) {
            Button add = button("+ Add a session", false);
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    editItem = defaultItem(p, t);
                    openPage(PAGE_EDIT);
                }
            });
            col.addView(add, gap(10));

            col.addView(label("During these sessions"), gap(22));
            CheckBox snd = check("Beeps and voice (off: someone may be asleep nearby)", p.sounds);
            snd.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton b, boolean on) {
                    p.sounds = on;
                    prefs.setPlanSounds(on);
                    if (!p.items.isEmpty()) savePlan(p, t);
                }
            });
            col.addView(snd, gap(4));
            col.addView(text("Stop, and don't start, below this battery level", 14, RED, false), gap(10));
            final int[] levels = {10, 20, 30, 40};
            final Button[] bs = new Button[levels.length];
            LinearLayout br = row();
            for (int k = 0; k < levels.length; k++) {
                final int lv = levels[k];
                Button b = chip(lv + "%", p.minBatteryPct == lv);
                b.setTextSize(15);
                bs[k] = b;
                b.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        p.minBatteryPct = lv;
                        prefs.setPlanMinBattery(lv);
                        for (int j = 0; j < levels.length; j++) styleChip(bs[j], levels[j] == lv);
                        if (!p.items.isEmpty()) savePlan(p, t);
                    }
                });
                br.addView(b, weight());
            }
            col.addView(br, gap(6));
            col.addView(text("Every session also stops at dawn, when storage runs low or if the phone gets too hot, and keeps "
                    + "what it captured.", 13, RED_DIM, false), gap(8));
        }

        if (armed) {
            Button cancel = button("Cancel the plan", false);
            cancel.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirm("Cancel tonight's plan? A session capturing now is developed with what it has.", new Runnable() {
                        @Override
                        public void run() {
                            startService(new Intent(MainActivity.this, CaptureService.class).setAction(CaptureService.ACTION_CANCEL_PLAN));
                        }
                    });
                }
            });
            col.addView(cancel, gap(20));
            viewerBox = new LinearLayout(this);
            viewerBox.setOrientation(LinearLayout.VERTICAL);
            viewerCompact = false;
            viewerStoppable = false;
            col.addView(viewerBox, gap(16));
            refreshViewer(true);
        } else {
            boolean left = false;
            for (NightPlan.Item it : p.items) left |= !it.finished();
            Button arm = button(needsRearm ? "Re-arm the plan" : "Arm the plan", true);
            arm.setTextSize(20);
            arm.setMinHeight(dp(60));
            arm.setEnabled(left);
            arm.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!needsRearm) savePlan(p, t);
                    armPlan();
                }
            });
            col.addView(arm, gap(24));
            if (needsRearm) {
                Button cancel = button("Cancel the plan", false);
                cancel.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startService(new Intent(MainActivity.this, CaptureService.class).setAction(CaptureService.ACTION_CANCEL_PLAN));
                        editPlan = null;
                        openPage(PAGE_HOME);
                    }
                });
                col.addView(cancel, gap(8));
            } else if (!left && !p.items.isEmpty()) {
                col.addView(text("Everything in tonight's plan has run. Add a session to arm it again.", 13, RED_DIM, false), gap(8));
            } else {
                col.addView(text("Arm it while the app is open, then lay the phone screen-down outside, camera to the sky. Each "
                        + "session opens the camera by itself; in between the phone sleeps.", 13, RED_DIM, false), gap(8));
            }
        }

        if (!p.log.isEmpty()) {
            col.addView(label("What happened"), gap(22));
            int from = Math.max(0, p.log.size() - 10);
            for (int i = from; i < p.log.size(); i++) col.addView(text(p.log.get(i), 12, RED_DIM, false), gap(2));
        }
    }

    /** "10:10 PM · 5-minute stack" (or without the time if it isn't known). */
    private static String itemTitle(NightPlan p, NightPlan.Item it) {
        long t = it.startedMs > 0 ? it.startedMs : it.startMs;
        String state = it.state == NightPlan.State.RUNNING ? " (running)" : it.state == NightPlan.State.DONE ? " ✓" : "";
        return (t > 0 ? Tonight.clock(t, p.zone) + " · " : "") + it.what() + state;
    }

    /** "Night of Thursday 22 October". */
    private static String nightWords(String night) {
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Date d = in.parse(night);
            return "Night of " + new SimpleDateFormat("EEEE d MMMM", Locale.getDefault()).format(d);
        } catch (ParseException e) {
            return "Night of " + night;
        }
    }

    private void savePlan(NightPlan p, Tonight t) {
        Plans.save(this, p);
        Plans.remindToArm(this, p, t, System.currentTimeMillis());
    }

    /** A new session for the editor: a 5-minute stack at full darkness, only if clear, or what was last added. */
    private NightPlan.Item defaultItem(NightPlan p, Tonight t) {
        NightPlan.Item it = new NightPlan.Item();
        it.trails = prefs.trails();
        it.durationSec = it.trails ? Math.max(20, prefs.durationMin()) * 60 : prefs.durationMin() * 60;
        it.rule = NightPlan.Rule.DARK;
        if (!p.items.isEmpty()) {
            // after the last session: an hour later by the clock
            NightPlan.Item last = p.items.get(p.items.size() - 1);
            long end = last.endMs > 0 ? last.endMs : last.startMs + last.durationSec * 1000L;
            if (last.startMs > 0) {
                it.rule = NightPlan.Rule.CLOCK;
                it.clockMs = (end + 3600_000L) / 600_000L * 600_000L;
            }
        }
        if (it.clockMs == 0) {
            long dusk = t != null && t.duskMs > 0 ? t.duskMs : p.clockOnNight(22, 0);
            it.clockMs = (dusk + 599_999L) / 600_000L * 600_000L;
        }
        it.endMs = 0;
        return it;
    }

    /** One session's settings: what, when it starts, how long, and whether it waits for a clear sky. */
    private void showEditor() {
        final NightPlan p = editPlan;
        final NightPlan.Item it = editItem;
        if (p == null || it == null) {
            openPage(PAGE_PLAN);
            return;
        }
        final Locator.Place place = Locator.best(this, prefs);
        final Tonight t = tonight(place);
        final TimeZone zone = TimeZone.getDefault();
        LinearLayout col = pageColumn("Tonight's plan");
        col.addView(text("Add a session", 26, RED, true));
        final TextView preview = text("", 15, RED, false);

        // what
        col.addView(label("Mode"), gap(18));
        final Button deep = chip("Deep sky", !it.trails);
        final Button trails = chip("Star trails", it.trails);
        LinearLayout mr = row();
        mr.addView(deep, weight());
        mr.addView(trails, weight());
        col.addView(mr, gap(6));

        // when
        col.addView(label("Starts"), gap(18));
        final NightPlan.Rule[] rules = {NightPlan.Rule.DARK, NightPlan.Rule.MOONSET, NightPlan.Rule.BEST, NightPlan.Rule.CLOCK};
        final String[] ruleNames = {"Full darkness", "Moonset", "Best window", "A set time"};
        final Button[] ruleChips = new Button[rules.length];
        LinearLayout r1 = row(), r2 = row();
        for (int k = 0; k < rules.length; k++) {
            Button b = chip(ruleNames[k], it.rule == rules[k]);
            b.setTextSize(15);
            ruleChips[k] = b;
            (k < 2 ? r1 : r2).addView(b, weight());
        }
        col.addView(r1, gap(6));
        col.addView(r2, gap(6));
        final LinearLayout offsetRow = row();
        final int[] offsets = {0, 30, 60, 120};
        final String[] offsetNames = {"On time", "+30 min", "+1 h", "+2 h"};
        final Button[] offsetChips = new Button[offsets.length];
        for (int k = 0; k < offsets.length; k++) {
            Button b = chip(offsetNames[k], it.offsetMin == offsets[k]);
            b.setTextSize(14);
            offsetChips[k] = b;
            offsetRow.addView(b, weight());
        }
        col.addView(offsetRow, gap(6));
        final TextView clockText = text("", 22, RED, true);
        clockText.setGravity(Gravity.CENTER);
        final LinearLayout clockRow = timeStepper(clockText, new long[]{-3600_000L, -600_000L, 600_000L, 3600_000L});
        col.addView(clockRow, gap(6));

        // how long
        col.addView(label("How long"), gap(18));
        final int[] deepMin = {1, 3, 5, 10, 20, 30, 60};
        final int[] trailMin = {10, 20, 30, 60, 120, -1};
        final LinearLayout lenBox = new LinearLayout(this);
        lenBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(lenBox, gap(6));
        final TextView endText = text("", 22, RED, true);
        endText.setGravity(Gravity.CENTER);
        final LinearLayout endRow = timeStepper(endText, new long[]{-3600_000L, -600_000L, 600_000L, 3600_000L});
        col.addView(endRow, gap(6));

        // only if clear
        final CheckBox clear = check("Only if the sky is clear: a test frame counts the stars first, and if there are under a third "
                + "of what a clear sky shows here, it tries again every 10 minutes", it.onlyIfClear);
        col.addView(clear, gap(16));
        final LinearLayout waitRow = row();
        final int[] waits = {60, 120, 180, -1};
        final String[] waitNames = {"1 h", "2 h", "3 h", "Till dawn"};
        final Button[] waitChips = new Button[waits.length];
        for (int k = 0; k < waits.length; k++) {
            Button b = chip(waitNames[k], (it.waitMin == 0 ? 120 : it.waitMin) == waits[k]);
            b.setTextSize(14);
            waitChips[k] = b;
            waitRow.addView(b, weight());
        }
        final TextView waitLabel = text("Keep trying for", 14, RED, false);
        col.addView(waitLabel, gap(4));
        col.addView(waitRow, gap(4));

        col.addView(preview, gap(18));

        // keeps the controls in step with the item
        final Runnable update = new Runnable() {
            @Override
            public void run() {
                styleChip(deep, !it.trails);
                styleChip(trails, it.trails);
                for (int k = 0; k < rules.length; k++) styleChip(ruleChips[k], it.rule == rules[k]);
                for (int k = 0; k < offsets.length; k++) styleChip(offsetChips[k], it.offsetMin == offsets[k]);
                offsetRow.setVisibility(it.rule == NightPlan.Rule.CLOCK ? View.GONE : View.VISIBLE);
                clockRow.setVisibility(it.rule == NightPlan.Rule.CLOCK ? View.VISIBLE : View.GONE);
                clockText.setText(Tonight.clock(it.clockMs, zone));
                endRow.setVisibility(it.trails && it.endMs > 0 ? View.VISIBLE : View.GONE);
                if (it.endMs > 0) endText.setText("until " + Tonight.clock(it.endMs, zone));
                for (int k = 0; k < waits.length; k++) styleChip(waitChips[k], (it.waitMin == 0 ? 120 : it.waitMin) == waits[k]);
                waitRow.setVisibility(it.onlyIfClear ? View.VISIBLE : View.GONE);
                waitLabel.setVisibility(it.onlyIfClear ? View.VISIBLE : View.GONE);
                preview.setText(p.preview(it, t));
            }
        };
        final Runnable[] fillLengths = new Runnable[1];
        fillLengths[0] = new Runnable() {
            @Override
            public void run() {
                lenBox.removeAllViews();
                final int[] mins = it.trails ? trailMin : deepMin;
                final Button[] bs = new Button[mins.length];
                LinearLayout row = row(), row2 = null;
                for (int k = 0; k < mins.length; k++) {
                    final int m = mins[k];
                    boolean on = m < 0 ? it.endMs > 0 : it.endMs == 0 && it.durationSec == m * 60;
                    Button b = chip(m < 0 ? "Until…" : m >= 60 ? (m / 60) + " h" : m + "m", on);
                    b.setTextSize(14);
                    bs[k] = b;
                    b.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (m < 0) {
                                if (it.endMs == 0) it.endMs = p.clockOnNight(1, 0);
                            } else {
                                it.endMs = 0;
                                it.durationSec = m * 60;
                            }
                            fillLengths[0].run();
                            update.run();
                        }
                    });
                    if (k >= 4 && row2 == null) {
                        row2 = row();
                    }
                    (row2 != null ? row2 : row).addView(b, weight());
                }
                lenBox.addView(row);
                if (row2 != null) lenBox.addView(row2, gap(6));
            }
        };
        fillLengths[0].run();

        deep.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                it.trails = false;
                it.endMs = 0;
                if (it.durationSec > 3600) it.durationSec = 300;
                fillLengths[0].run();
                update.run();
            }
        });
        trails.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                it.trails = true;
                if (it.durationSec < 600) it.durationSec = 1800;
                fillLengths[0].run();
                update.run();
            }
        });
        for (int k = 0; k < rules.length; k++) {
            final NightPlan.Rule rule = rules[k];
            ruleChips[k].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (rule == NightPlan.Rule.CLOCK && it.rule != NightPlan.Rule.CLOCK) {
                        long s = p.previewStart(it, t);
                        if (s > 0) it.clockMs = (s + 599_999L) / 600_000L * 600_000L;
                    }
                    it.rule = rule;
                    update.run();
                }
            });
        }
        for (int k = 0; k < offsets.length; k++) {
            final int o = offsets[k];
            offsetChips[k].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    it.offsetMin = o;
                    update.run();
                }
            });
        }
        clockText.setTag(new TimeTarget() {
            @Override
            public void shift(long ms) {
                it.clockMs = clampToNight(p, it.clockMs + ms);
                update.run();
            }
        });
        endText.setTag(new TimeTarget() {
            @Override
            public void shift(long ms) {
                it.endMs = clampToNight(p, it.endMs + ms);
                update.run();
            }
        });
        clear.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                it.onlyIfClear = on;
                update.run();
            }
        });
        for (int k = 0; k < waits.length; k++) {
            final int w = waits[k];
            waitChips[k].setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    it.waitMin = w;
                    update.run();
                }
            });
        }
        update.run();

        LinearLayout br = row();
        Button add = button("Add to the plan", true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                long now = System.currentTimeMillis();
                long start = p.previewStart(it, t);
                if (start == 0) {
                    toastish(p.preview(it, t));
                    return;
                }
                if (start < now - 5 * 60_000L && !(it.endMs > now)) {
                    toastish("That time has passed tonight");
                    return;
                }
                p.add(it);
                p.resolve(t, now);
                savePlan(p, t);
                editItem = null;
                openPage(PAGE_PLAN);
            }
        });
        Button cancel = button("Cancel", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                editItem = null;
                openPage(PAGE_PLAN);
            }
        });
        br.addView(add, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        br.addView(cancel, weight());
        col.addView(br, gap(18));
    }

    /** Moves a clock time by a step; the buttons find what to move through the label's tag. */
    private interface TimeTarget {
        void shift(long ms);
    }

    /** [-1 h] [-10] time [+10] [+1 h]: steps a time shown in {@code label} (its tag says what to change). */
    private LinearLayout timeStepper(final TextView label, long[] steps) {
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        String[] names = {"−1 h", "−10", "+10", "+1 h"};
        for (int k = 0; k < steps.length; k++) {
            final long st = steps[k];
            Button b = chip(names[k], false);
            b.setTextSize(14);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Object tg = label.getTag();
                    if (tg instanceof TimeTarget) ((TimeTarget) tg).shift(st);
                }
            });
            if (k == 2) r.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2.2f));
            r.addView(b, weight());
        }
        return r;
    }

    /** Keeps a time within the plan's night (noon to noon). */
    private static long clampToNight(NightPlan p, long ms) {
        long from = p.clockOnNight(12, 0), to = p.clockOnNight(11, 50);
        return Math.max(from, Math.min(to, ms));
    }

    /** Arms the saved plan: the camera service starts now, while the app is on screen, and waits for the alarms. */
    private void armPlan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingArm = true;
            askedNotifications = true;
            prefs.setAskedLocation();
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, 6);
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !askedNotifications) {
            // the plan's notification, the re-arm reminder and the morning summary
            askedNotifications = true;
            pendingArm = true;
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 6);
            return;
        }
        long now = System.currentTimeMillis();
        NightPlan p = Plans.load(this);
        if (p == null || p.items.isEmpty()) {
            toastish("Add a session first");
            return;
        }
        // what would happen: refuse a plan where nothing can start
        Tonight t = tonight(Locator.best(this, prefs));
        NightPlan test = NightPlan.fromJson(p.toJson());
        test.armed = false;
        for (NightPlan.Item it : test.items) if (it.state == NightPlan.State.RUNNING) it.state = NightPlan.State.FAILED;
        test.resolve(t, now);
        if (test.next(now) == null) {
            StringBuilder why = new StringBuilder("Nothing in the plan can start tonight:\n");
            for (NightPlan.Item it : test.items) if (!it.outcome.isEmpty()) why.append("\n• ").append(it.what()).append(": ").append(it.outcome);
            new AlertDialog.Builder(this).setMessage(why.toString()).setPositiveButton("OK", null).show();
            return;
        }
        startForegroundService(new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_ARM_PLAN));
        editPlan = null;
        page = PAGE_PLAN;
        showHome();
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (!prefs.askedBatteryExemption() && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            prefs.setAskedBatteryExemption();
            new AlertDialog.Builder(this).setTitle("Let the plan run at night")
                    .setMessage("The plan is armed. It opens the camera by itself, hours from now, while the phone sleeps, and "
                            + "Android may hold that back to save battery. Let Night Sky run in the background? It uses the "
                            + "battery only during the sessions. (You can change this later in Settings > Apps > Night Sky > "
                            + "Battery.)")
                    .setPositiveButton("Allow", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                        .setData(Uri.parse("package:" + getPackageName())));
                            } catch (RuntimeException e) {
                                toastish("Open Settings > Apps > Night Sky > Battery and choose Unrestricted");
                            }
                        }
                    }).setNegativeButton("Not now", null).show();
        }
    }

    /** Last night's summary: every session's picture, how it went, its report, and Repeat tomorrow. */
    private void showSummary() {
        final NightPlan p = summaryPlan != null ? summaryPlan : nightToSummarise(Plans.load(this));
        if (p == null) {
            openPage(PAGE_HOME);
            return;
        }
        summaryPlan = p;
        Plans.cancelNotification(this, Plans.NOTIF_MORNING);
        LinearLayout col = pageColumn("Home");
        col.addView(text(p.summaryTitle(), 24, RED, true));
        col.addView(text(nightWords(p.night), 14, RED_DIM, false), gap(4));
        for (final NightPlan.Item it : p.items) {
            if (it.state == NightPlan.State.CANCELLED && it.tries == 0) continue;
            LinearLayout c = card();
            Bitmap b = Plans.thumb(this, it.thumb);
            if (b != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(b);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                c.addView(iv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)));
            }
            c.addView(text(itemTitle(p, it), 16, RED, true), gap(b != null ? 8 : 0));
            c.addView(text(it.outcome.isEmpty() ? it.state.name().toLowerCase(Locale.US) : it.outcome, 14, RED_DIM, false), gap(3));
            LinearLayout r = row();
            final ReportCard rc = it.session.isEmpty() ? null : savedReport(it.session);
            if (rc != null) {
                Button rb = button("Report", false);
                rb.setTextSize(14);
                rb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showReport(rc, null);
                    }
                });
                r.addView(rb, weight());
            }
            if (!it.photo.isEmpty()) {
                Button ob = button("Photo", false);
                ob.setTextSize(14);
                ob.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        view(Uri.parse(it.photo));
                    }
                });
                r.addView(ob, weight());
            }
            if (it.state == NightPlan.State.DONE) {
                final Button rep = button("Repeat tomorrow", false);
                rep.setTextSize(14);
                rep.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (addRepeat(it, p.night)) {
                            rep.setEnabled(false);
                            rep.setText("Added ✓");
                        }
                    }
                });
                r.addView(rep, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f));
            }
            if (r.getChildCount() > 0) c.addView(r, gap(8));
            col.addView(c, gap(10));
        }
        if (!p.log.isEmpty()) {
            col.addView(label("What happened, step by step"), gap(22));
            for (String l : p.log) col.addView(text(l, 12, RED_DIM, false), gap(2));
        }
        Button done = button("Done", true);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                markSummaryDone(p);
                summaryPlan = null;
                openPage(PAGE_HOME);
            }
        });
        col.addView(done, gap(20));
    }

    /** A session's saved report (null if it has none). */
    private ReportCard savedReport(String session) {
        try {
            String json = new com.brandon.nightsky.processing.SessionStore(new File(SessionController.sessionsRoot(this), session))
                    .readText("report.json");
            return json == null || json.isEmpty() ? null : ReportCard.fromJson(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void markSummaryDone(NightPlan p) {
        NightPlan cur = Plans.load(this);
        if (cur != null && cur.night.equals(p.night) && cur.createdMs == p.createdMs) {
            cur.summaryDone = true;
            Plans.save(this, cur);
        } else {
            Plans.archivedSummaryDone(this);
        }
        p.summaryDone = true;
        Plans.cancelNotification(this, Plans.NOTIF_MORNING);
    }

    /**
     * Puts the same session on the next night's plan, a sidereal day later per night (the same stars overhead).
     * Returns false if it couldn't (that night's plan is armed).
     */
    private boolean addRepeat(NightPlan.Item done, String doneNight) {
        long now = System.currentTimeMillis();
        Locator.Place place = Locator.best(this, prefs);
        Tonight t = tonight(place);
        String target = NightPlan.nightAfter(doneNight);
        String planning = Plans.planningNight(now, t, TimeZone.getDefault());
        if (target.compareTo(planning) < 0) target = planning;
        NightPlan cur = Plans.load(this);
        if (cur != null && cur.armed && !cur.finished()) {
            toastish("A plan is armed: cancel it before changing the plans");
            return false;
        }
        NightPlan.Item r = NightPlan.repeatAfter(done, NightPlan.nightsBetween(doneNight, target));
        NightPlan p = Plans.forNight(this, target, place, now);
        if (p.items.isEmpty()) {
            p.sounds = prefs.planSounds();
            p.minBatteryPct = prefs.planMinBattery();
        }
        p.add(r);
        p.resolve(target.equals(planning) ? t : null, now);
        savePlan(p, target.equals(planning) ? t : null);
        editPlan = null;
        toastish("On " + (target.equals(planning) ? "tonight's" : "tomorrow night's") + " plan: " + r.what() + " at "
                + Tonight.clock(r.clockMs, TimeZone.getDefault()) + ". Arm it in the evening.");
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Session screens
    // ------------------------------------------------------------------------------------------------

    private void showArming() {
        clearRefs();
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        LinearLayout col = column();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        sv.addView(col);
        root.addView(sv, match());
        headline = text("", 30, RED, true);
        headline.setGravity(Gravity.CENTER);
        detail = text("", 16, RED_DIM, false);
        detail.setGravity(Gravity.CENTER);
        checks = text("", 15, RED, false);
        checks.setVisibility(View.GONE);
        checks.setLineSpacing(dp(3), 1f);
        siteView = text("", 15, RED_DIM, true);
        siteView.setGravity(Gravity.CENTER);
        siteView.setVisibility(View.GONE);
        col.addView(spacer(), new LinearLayout.LayoutParams(1, 0, 1f));
        col.addView(siteView);
        col.addView(headline, gap(6));
        col.addView(detail, gap(14));
        col.addView(checks, gap(16));
        col.addView(spacer(), new LinearLayout.LayoutParams(1, 0, 1f));
        viewerBox = new LinearLayout(this);
        viewerBox.setOrientation(LinearLayout.VERTICAL);
        viewerCompact = false;
        viewerStoppable = false;
        col.addView(viewerBox, gap(12));
        refreshViewer(true);
        boolean scheduled = service != null && service.controller().status().scheduled;
        Button cancel = button(scheduled ? "Skip this session (the plan goes on)" : "Cancel", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.controller().cancel();
            }
        });
        col.addView(cancel, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void showCapturing() {
        clearRefs();
        root.removeAllViews();
        LinearLayout col = column();
        root.addView(col, match());
        headline = text("", 24, RED, true);
        detail = text("", 15, RED_DIM, false);
        bar = progress();
        live = new ImageView(this);
        live.setScaleType(ImageView.ScaleType.FIT_CENTER);
        live.setBackgroundColor(BG);
        col.addView(headline);
        col.addView(detail, gap(6));
        col.addView(bar, gap(10));
        col.addView(live, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        col.addView(text("Live view of the stack so far. Leave the phone face-down; lifting it pauses the capture.", 13, RED_DIM, false), gap(8));
        viewerBox = new LinearLayout(this);
        viewerBox.setOrientation(LinearLayout.VERTICAL);
        viewerCompact = true;
        viewerStoppable = false;
        col.addView(viewerBox, gap(4));
        refreshViewer(true);
        LinearLayout row = row();
        Button stop = button("Hold to stop and develop", true);
        stop.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (service != null) service.controller().finishNow();
                return true;
            }
        });
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toastish("Hold the button to stop");
            }
        });
        Button discard = button("Discard", false);
        discard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirm("Throw this session away?", new Runnable() {
                    @Override
                    public void run() {
                        if (service != null) service.controller().cancel();
                    }
                });
            }
        });
        row.addView(stop, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        row.addView(discard, weight());
        col.addView(row, gap(10));
    }

    private void showDeveloping(SessionController.Status s) {
        clearRefs();
        root.removeAllViews();
        LinearLayout col = column();
        root.addView(col, match());
        headline = text("Developing", 24, RED, true);
        detail = text("", 15, RED_DIM, false);
        bar = progress();
        live = new ImageView(this);
        live.setScaleType(ImageView.ScaleType.FIT_CENTER);
        col.addView(headline);
        col.addView(detail, gap(6));
        col.addView(bar, gap(10));
        col.addView(live, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        devNote = text("", 13, RED_DIM, false);
        col.addView(devNote, gap(8));
        devCancel = button("Cancel", false);
        devCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.controller().cancel();
            }
        });
        col.addView(devCancel, gap(10));
        styleDeveloping(s);
    }

    /** The developing screen's note and cancel button for what's running: developing, merging, an edit, a save. */
    private void styleDeveloping(SessionController.Status s) {
        String hl = s != null ? s.headline : "";
        boolean edit = hl.startsWith("Stacking the edit"), merge = hl.startsWith("Merging"), again = hl.startsWith("Making it again");
        boolean quick = hl.equals("Saving") || hl.equals("Opening");
        String note = edit ? "The photo you edited stays as it was: the edit is saved as a new render. This takes a minute or two."
                : again ? "Its stacked picture was deleted to make room, so it's stacked again from the frames as its recipe says. This "
                + "takes a minute or two."
                : merge ? "This takes a few minutes. The sessions stay as they were." : quick ? ""
                : "You can pick the phone up now. This takes a minute or two.";
        devNote.setText(note);
        devNote.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
        devCancel.setText(edit ? "Cancel (the photo stays as it was)" : merge || again ? "Cancel" : "Cancel (keeps the frames)");
        devCancel.setVisibility(quick ? View.GONE : View.VISIBLE);
    }

    private void showResult(final SessionController.Status s) {
        clearRefs();
        root.removeAllViews();
        LinearLayout col = column();
        col.setPadding(dp(8), dp(8), dp(8), dp(12));
        root.addView(col, match());
        zoom = new ZoomImageView(this);
        zoom.setBackgroundColor(BG);
        col.addView(zoom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        headline = text("", 18, RED, true);
        detail = text("", 13, RED_DIM, false);
        col.addView(headline, gap(8));
        col.addView(detail, gap(2));
        final SessionController.Result r = s.result;
        if (r != null && r.report != null) {
            final ReportCard rc = r.report;
            LinearLayout link = card();
            link.setPadding(dp(12), dp(8), dp(12), dp(8));
            link.addView(text(rc.headline, 14, RED, false));
            link.addView(text(rc.problems.isEmpty() ? "Session report \u203a" : "Session report: " + rc.problems.get(0).title.toLowerCase(Locale.US)
                    + (rc.problems.size() > 1 ? " and more" : "") + " \u203a", 13, RED_DIM, true));
            link.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showReport(rc, r.merge);
                }
            });
            col.addView(link, gap(6));
        } else if (r != null && r.merge != null) {
            // nights merged (G1): their total and coverage map stand for the report card
            final MergeProcessor.Result mr = r.merge;
            LinearLayout link = card();
            link.setPadding(dp(12), dp(8), dp(12), dp(8));
            link.addView(text(mr.total, 14, RED, false));
            link.addView(text("The nights and where they overlap \u203a", 13, RED_DIM, true));
            link.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMergeInfo(mr);
                }
            });
            col.addView(link, gap(6));
        }
        if (r != null && (!r.tracks.isEmpty() || r.canLabel)) {
            // satellites' tracks and the sky's names over the picture, and a full-size copy with them
            LinearLayout tg = row();
            if (!r.tracks.isEmpty()) {
                final Button tb = chip("Satellites", prefs.showTracks());
                tb.setTextSize(14);
                tb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean on = !prefs.showTracks();
                        prefs.setShowTracks(on);
                        styleChip(tb, on);
                        if (service != null) service.controller().redraw();
                    }
                });
                tg.addView(tb, weight());
            }
            if (r.canLabel) {
                final Button lb = chip("Names", prefs.showLabels());
                lb.setTextSize(14);
                lb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        boolean on = !prefs.showLabels();
                        prefs.setShowLabels(on);
                        styleChip(lb, on);
                        if (service != null) service.controller().redraw();
                    }
                });
                tg.addView(lb, weight());
            }
            Button copy = button(r.labelled != null ? "Saved \u2713" : "Save this copy", false);
            copy.setTextSize(14);
            copy.setPadding(dp(6), dp(8), dp(6), dp(8));
            copy.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (service == null) return;
                    if (!(prefs.showTracks() && !r.tracks.isEmpty()) && !(prefs.showLabels() && r.canLabel)) {
                        toastish("Turn on Satellites or Names first");
                        return;
                    }
                    service.controller().saveLabelled();
                }
            });
            tg.addView(copy, weight());
            col.addView(tg, gap(6));
        }
        shownBrightness = r != null ? r.brightness : prefs.brightness();
        final TextView bl = text("Brightness " + Math.round(shownBrightness * 100) + "%  ·  pinch to zoom", 13, RED_DIM, false);
        resultBrightness = seek(Math.round(shownBrightness * 100));
        resultBrightness.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int v, boolean user) {
                if (!user || service == null) return;
                bl.setText("Brightness " + v + "%  ·  pinch to zoom");
                service.controller().adjust(v / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.setBrightness(sb.getProgress() / 100f);
            }
        });
        col.addView(resultBrightness, gap(6));
        col.addView(bl);
        LinearLayout row1 = row();
        saveVersion = button("Save this brightness", true);
        saveVersion.setEnabled(false);
        saveVersion.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service == null || resultBrightness == null) return;
                float b = resultBrightness.getProgress() / 100f;
                shownBrightness = b;
                saveVersion.setEnabled(false);
                service.controller().saveVersion(b);
            }
        });
        Button share = button("Share", false);
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SessionController.Result rr = service != null ? service.controller().status().result : r;
                if (rr == null || rr.jpeg == null) return;
                Intent i = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, rr.jpeg)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(i, "Share photo"));
            }
        });
        row1.addView(saveVersion, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
        row1.addView(share, weight());
        col.addView(row1, gap(8));
        LinearLayout row2 = row();
        Button open = button("Open in Photos", false);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SessionController.Result rr = service != null ? service.controller().status().result : r;
                if (rr != null && rr.jpeg != null) view(rr.jpeg);
            }
        });
        // a photo opened or merged on the sessions page goes back there; a new one goes to the start screen
        final boolean fromLibrary = r != null && (r.endedMs <= 0 || (r.merge != null && !r.merge.added));
        Button again = button(fromLibrary ? "Sessions" : "New photo", false);
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (fromLibrary) page = PAGE_SESSIONS;
                else if (page == PAGE_SESSIONS || page == PAGE_MERGE) page = PAGE_HOME;
                if (service != null) service.controller().clearResult();
            }
        });
        row2.addView(open, weight());
        row2.addView(again, weight());
        col.addView(row2, gap(6));
        // the rest, smaller: editing, minutes added, tomorrow night, other nights, the cloud blur, the sessions
        List<Button> more = new ArrayList<Button>();
        if (r != null && r.recipe != null) {
            final Button eb = smallButton("Edit", false);
            eb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openEdit(eb);
                }
            });
            more.add(eb);
            if (r.recipe.outdated() && r.merge == null) {
                // made by an older version: offered again with this one's processing (G3)
                Button nb = smallButton("New processing", false);
                nb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirm("Make this photo again with this version's processing? It's saved as a new render; this one stays.",
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        if (service == null || r.recipe == null) return;
                                        Recipe e = r.recipe.edit();
                                        e.restacked();
                                        service.controller().saveEdit(e, null);
                                    }
                                });
                    }
                });
                more.add(nb);
            }
        }
        if (r != null && r.startedMs > 0 && r.endedMs > 0 && !s.scheduled && !fromLibrary) {
            // the same stars at the same height tomorrow night: 3 min 56 s earlier by the clock
            final Button rep = smallButton("Repeat tomorrow", false);
            rep.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    NightPlan.Item done = NightPlan.ofSession(r.trails, r.startedMs, r.durationSec, r.session);
                    if (addRepeat(done, NightPlan.nightOf(r.startedMs, TimeZone.getDefault()))) {
                        rep.setEnabled(false);
                        rep.setText("On tomorrow's plan ✓");
                    }
                }
            });
            more.add(rep);
        }
        if (r != null && r.blend != null) {
            // the cloud-blur version (C7), saved beside the photo
            Button bl2 = smallButton("Cloud-blur version", false);
            bl2.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    view(r.blend);
                }
            });
            more.add(bl2);
        }
        addMoreRow(col, r, fromLibrary, more);
        String where = "Saved in Pictures/NightSky" + (r != null && r.dng != null ? " (JPEG and DNG" : " (JPEG")
                + (r != null && r.jpegFactor > 1 ? ", half size" : "")
                + (r != null && r.blend != null ? ", the cloud-blur version" : "")
                + (r != null && r.labelled != null ? ", and a copy with names)" : ")");
        col.addView(text(where, 12, RED_DIM, false), gap(4));
        viewerBox = new LinearLayout(this);
        viewerBox.setOrientation(LinearLayout.VERTICAL);
        viewerCompact = true;
        viewerStoppable = true;
        col.addView(viewerBox, gap(2));
        refreshViewer(true);
    }

    // ------------------------------------------------------------------------------------------------
    // Edit screen (G3): how it's rendered, cropped and turned, which frames go in, the reference frame, frames
    // lined up by hand and the foreground; saved as a new render, nothing written over
    // ------------------------------------------------------------------------------------------------

    private static final String TAB_LOOK = "Look", TAB_CROP = "Crop", TAB_FRAMES = "Frames", TAB_GROUND = "Ground", TAB_RENDERS = "Renders";

    /** The edit screen's state while it's open. */
    private static final class EditState {
        SessionController.EditInfo info;
        /** The edit as it stands (its frames come from the trim and {@link #leftOut}). */
        Recipe r;
        /** The time trim (positions in the frames) and the frames (units) left out by hand. */
        int from, to;
        final TreeSet<Integer> leftOut = new TreeSet<Integer>();
        /** The frame touched in the strip (a position; -1 for none). */
        int picked = -1;
        /** The foreground painting (null when the stack can't change), and its cells over the picture (a pixel each). */
        EdgeMask painting;
        Bitmap cells;
        /** The painting before each stroke or clearing, newest last, for Undo; and the stroke's own, until it paints. */
        final ArrayList<EdgeMask> undo = new ArrayList<EdgeMask>();
        EdgeMask strokeBefore;
        boolean strokeKept;
        /** What one finger does on the Ground tab: paints ground, sky, rubs out ({@link EdgeMask} values), or -1 moves. */
        int brush = -1;
        int brushDp = 36;
        /** The photo as it was, for Compare, and whether it's shown now. */
        Bitmap before;
        boolean comparing;
        /** Saved: the photo isn't put back as it was when the screen closes. */
        boolean saved;
        String tab = TAB_LOOK;
        FrameLayout ov;
        LinearLayout tabBox;
        TextView hint;
        /** Lining a frame up by hand, over the edit screen (null when not shown). */
        View tapView;
        /** The Ground tab's Undo (null when another tab is shown). */
        Button undoButton;
    }

    /** Reads what editing the photo needs (the session's frames and foreground), then opens the edit screen. */
    private void openEdit(final Button b) {
        if (service == null || reportOverlay != null) return;
        final Bitmap before = service.controller().preview();
        b.setEnabled(false);
        b.setText("Reading…");
        service.controller().editInfo(new SessionController.EditCallback() {
            @Override
            public void ready(SessionController.EditInfo in, String why) {
                b.setEnabled(true);
                b.setText("Edit");
                if (zoom == null || reportOverlay != null) return; // the result screen has gone
                if (in == null) {
                    toastish(why);
                    return;
                }
                showEdit(in, before);
            }
        });
    }

    private void showEdit(SessionController.EditInfo in, Bitmap before) {
        final EditState es = new EditState();
        es.info = in;
        es.r = in.recipe;
        es.before = before;
        int n = in.frames.length;
        es.from = 0;
        es.to = Math.max(0, n - 1);
        Recipe.Part b = es.r.base();
        if (n > 0 && b != null && b.include != null) {
            int first = -1, last = -1;
            for (int i = 0; i < n; i++) {
                if (!b.include.contains(in.frames[i].index)) continue;
                if (first < 0) first = i;
                last = i;
            }
            if (first >= 0) {
                es.from = first;
                es.to = last;
                for (int i = first; i <= last; i++) if (!b.include.contains(in.frames[i].index)) es.leftOut.add(in.frames[i].index);
            }
        }
        es.painting = in.painting != null ? in.painting.copy() : null;
        edit = es;

        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(BG);
        ov.setClickable(true);
        es.ov = ov;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), dp(8), dp(8), dp(10));
        ov.addView(col, match());
        editZoom = new ZoomImageView(this);
        editZoom.setBackgroundColor(BG);
        col.addView(editZoom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.1f));
        Bitmap pv = service != null ? service.controller().preview() : null;
        if (pv != null) editZoom.show(pv, false);
        es.hint = text("", 13, RED_DIM, false);
        col.addView(es.hint, gap(4));
        LinearLayout tabs = row();
        final List<String> names = new ArrayList<String>();
        names.add(TAB_LOOK);
        names.add(TAB_CROP);
        if (in.noRestack.isEmpty() && n > 0) names.add(TAB_FRAMES);
        if (in.noRestack.isEmpty() && es.painting != null) names.add(TAB_GROUND);
        names.add(TAB_RENDERS);
        final List<Button> tabButtons = new ArrayList<Button>();
        for (final String t : names) {
            Button tb = chip(t, t.equals(es.tab));
            tb.setTextSize(13);
            tb.setMaxLines(1);
            tb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (t.equals(es.tab)) return;
                    es.tab = t;
                    for (int k = 0; k < names.size(); k++) styleChip(tabButtons.get(k), names.get(k).equals(t));
                    fillEditTab(es);
                }
            });
            tabButtons.add(tb);
            LinearLayout.LayoutParams lp = weight();
            lp.leftMargin = lp.rightMargin = dp(2);
            tabs.addView(tb, lp);
        }
        col.addView(tabs, gap(6));
        ScrollView sv = new ScrollView(this);
        es.tabBox = new LinearLayout(this);
        es.tabBox.setOrientation(LinearLayout.VERTICAL);
        es.tabBox.setPadding(dp(4), dp(4), dp(4), dp(8));
        sv.addView(es.tabBox);
        col.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout bar = row();
        Button cmp = button("Compare", false);
        cmp.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    // held down: the photo as it was
                    v.setPressed(true);
                    es.comparing = true;
                    if (es.before != null && editZoom != null) editZoom.show(es.before, true);
                } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    es.comparing = false;
                    Bitmap now = service != null ? service.controller().preview() : null;
                    if (now != null && editZoom != null) editZoom.show(now, true);
                }
                return true;
            }
        });
        Button cancel = button("Cancel", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeReport();
            }
        });
        Button save = button("Save", true);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveEditNow(es);
            }
        });
        bar.addView(cmp, weight());
        bar.addView(cancel, weight());
        bar.addView(save, weight());
        col.addView(bar, gap(6));
        openOverlay(ov);
        fillEditTab(es);
    }

    /** The tab's controls; the Ground tab shows the whole picture (neither cut nor turned) with the foreground over it. */
    private void fillEditTab(EditState es) {
        LinearLayout box = es.tabBox;
        box.removeAllViews();
        es.undoButton = null;
        boolean ground = TAB_GROUND.equals(es.tab);
        if (editZoom != null) {
            editZoom.setOverlay(ground ? groundOverlay(es) : null);
            editZoom.setPainter(ground && es.brush >= 0 ? painter(es) : null, dp(es.brushDp));
        }
        if (TAB_LOOK.equals(es.tab)) lookTab(es, box);
        else if (TAB_CROP.equals(es.tab)) cropTab(es, box);
        else if (TAB_FRAMES.equals(es.tab)) framesTab(es, box);
        else if (ground) groundTab(es, box);
        else rendersTab(es, box);
        editPreview(es);
    }

    /** The picture as the edit renders it (quick, from the small copy), and the hint under it. */
    private void editPreview(EditState es) {
        if (service != null) service.controller().previewEdit(editRecipe(es), TAB_GROUND.equals(es.tab));
        editHint(es);
    }

    private void editHint(EditState es) {
        if (es.hint == null) return;
        Recipe e = editRecipe(es);
        StringBuilder sb = new StringBuilder();
        if (TAB_FRAMES.equals(es.tab)) sb.append(framesIn(es)).append(" of ").append(es.info.frames.length).append(" frames go in. ");
        if (!e.sameStack(es.info.was)) {
            sb.append("Frame and ground changes are stacked when you save (a minute or two). ");
        }
        if (TAB_GROUND.equals(es.tab)) sb.append(es.brush >= 0 ? "One finger paints; two fingers zoom and move." : "Pick Ground or Sky to paint.");
        else sb.append("Hold Compare to see it as it was.");
        es.hint.setText(sb.toString());
    }

    /** The edit as it stands, as a recipe: the frames from the trim and those left out by hand, the painting's name. */
    private Recipe editRecipe(EditState es) {
        Recipe r = es.r.edit();
        ReportCard.Frame[] fr = es.info.frames;
        Recipe.Part b = r.base();
        if (fr.length > 0 && b != null) {
            if (es.from == 0 && es.to == fr.length - 1 && es.leftOut.isEmpty()) {
                b.include = null;
                b.includeNote = "";
            } else {
                TreeSet<Integer> inc = new TreeSet<Integer>();
                for (int i = es.from; i <= es.to; i++) if (!es.leftOut.contains(fr[i].index)) inc.add(fr[i].index);
                b.include = inc;
                b.includeNote = includeWords(es);
            }
        }
        if (es.painting != null) r.mask = es.painting.isBlank() ? "" : es.painting.name();
        return r;
    }

    /** "between 11:02 PM and 11:31 PM, 2 left out by hand" ("" for all the frames). */
    private static String includeWords(EditState es) {
        ReportCard.Frame[] fr = es.info.frames;
        StringBuilder sb = new StringBuilder();
        if (es.from > 0 || es.to < fr.length - 1) sb.append("between ").append(fr[es.from].time).append(" and ").append(fr[es.to].time);
        int hand = 0;
        for (int i = es.from; i <= es.to; i++) if (es.leftOut.contains(fr[i].index)) hand++;
        if (hand > 0) sb.append(sb.length() > 0 ? ", " : "").append(hand).append(" left out by hand");
        return sb.toString();
    }

    private static int framesIn(EditState es) {
        int n = 0;
        for (int i = es.from; i <= es.to && i < es.info.frames.length; i++) if (!es.leftOut.contains(es.info.frames[i].index)) n++;
        return n;
    }

    /**
     * The reference frame (unit) the edit lines up on: chosen by hand, else the one developing chose while the edit
     * keeps it (-1 if unknown, or left out: the stack then chooses another).
     */
    private static int refUnit(EditState es) {
        if (es.r.reference >= 0) return es.r.reference;
        int ru = es.info.referenceUnit;
        return ru >= 0 && keeps(es, ru) ? ru : -1;
    }

    /** Whether the edit keeps frame (unit) {@code unit}: inside the trim and not left out by hand. */
    private static boolean keeps(EditState es, int unit) {
        if (es.leftOut.contains(unit)) return false;
        ReportCard.Frame[] fr = es.info.frames;
        for (int i = es.from; i <= es.to && i < fr.length; i++) if (fr[i].index == unit) return true;
        return fr.length == 0;
    }

    private void saveEditNow(EditState es) {
        if (service == null) return;
        Recipe e = editRecipe(es);
        if (e.changes(es.info.was).isEmpty()) {
            toastish("Nothing has changed");
            return;
        }
        if (es.info.frames.length > 0 && framesIn(es) == 0) {
            toastish("Keep at least one frame");
            return;
        }
        es.saved = true;
        service.controller().saveEdit(e, es.painting);
        closeReport();
    }

    private interface Slider {
        String label(int v);

        void set(int v);
    }

    /** A setting's label and slider, 0 to {@code max}; moving it renders the picture again. */
    private void addSlider(final EditState es, LinearLayout box, int max, int value, final Slider s) {
        final TextView l = text(s.label(value), 14, RED, false);
        SeekBar sb = seek(0);
        sb.setMax(max);
        sb.setProgress(Math.max(0, Math.min(max, value)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int v, boolean user) {
                if (!user) return;
                l.setText(s.label(v));
                s.set(v);
                editPreview(es);
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        box.addView(l, gap(8));
        box.addView(sb);
    }

    /** "7", "6.5", "-0.3". */
    private static String num(float v) {
        float r = Math.round(v * 10) / 10f;
        return r == Math.round(r) ? Integer.toString(Math.round(r)) : String.format(Locale.US, "%.1f", r);
    }

    private void lookTab(final EditState es, LinearLayout box) {
        final Recipe r = es.r;
        addSlider(es, box, 18, Math.round((r.stretch - 1) * 2), new Slider() {
            @Override
            public String label(int v) {
                float st = 1 + v / 2f;
                return "Stretch " + num(st) + (st == 5f ? " (as developed)" : st < 5f ? " (gentler)" : " (stronger: more faint light)");
            }

            @Override
            public void set(int v) {
                r.stretch = 1 + v / 2f;
            }
        });
        addSlider(es, box, 40, Math.round(r.ev * 10) + 20, new Slider() {
            @Override
            public String label(int v) {
                float ev = (v - 20) / 10f;
                return "Exposure " + (ev > 0 ? "+" : "") + num(ev) + " EV" + (v == 20 ? " (as developed)" : "");
            }

            @Override
            public void set(int v) {
                r.ev = (v - 20) / 10f;
            }
        });
        addSlider(es, box, 20, Math.round(r.black * 20), new Slider() {
            @Override
            public String label(int v) {
                return "Black point " + 5 * v + "%" + (v == 0 ? " (as developed)" : ": the sky darker");
            }

            @Override
            public void set(int v) {
                r.black = v / 20f;
            }
        });
        addSlider(es, box, 40, Math.round(r.saturation * 20), new Slider() {
            @Override
            public String label(int v) {
                return "Colour " + 5 * v + "%" + (v == 20 ? " (as developed)" : "");
            }

            @Override
            public void set(int v) {
                r.saturation = v / 20f;
            }
        });
        addSlider(es, box, 100, Math.round(r.brightness * 100), new Slider() {
            @Override
            public String label(int v) {
                return "Sky brightness " + v + "%";
            }

            @Override
            public void set(int v) {
                r.brightness = v / 100f;
            }
        });
        box.addView(label("Look"), gap(12));
        LinearLayout looks = row();
        final String[][] ls = {{Recipe.LOOK_NATURAL, "Natural"}, {Recipe.LOOK_PUNCHY, "Punchy"}, {Recipe.LOOK_LINEAR, "Linear"}};
        final List<Button> lb = new ArrayList<Button>();
        for (final String[] l : ls) {
            Button c = chip(l[1], l[0].equals(r.look));
            c.setTextSize(14);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    r.look = l[0];
                    for (int k = 0; k < ls.length; k++) styleChip(lb.get(k), ls[k][0].equals(r.look));
                    editPreview(es);
                }
            });
            lb.add(c);
            looks.addView(c, weight());
        }
        box.addView(looks, gap(4));
        box.addView(text("Natural is the develop step's gentle curve. Punchy lifts the faint light with more contrast and colour. Linear is "
                + "the light as it fell, where bright stars clip.", 12, RED_DIM, false), gap(4));
        Button reset = smallButton("As developed", false);
        reset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.stretch = 5f;
                r.ev = 0f;
                r.black = 0f;
                r.saturation = 1f;
                r.look = Recipe.LOOK_NATURAL;
                List<Recipe> all = es.info.renders;
                r.brightness = all.isEmpty() ? 0.5f : all.get(0).brightness;
                fillEditTab(es);
            }
        });
        box.addView(reset, gap(10));
    }

    private void cropTab(final EditState es, LinearLayout box) {
        final Recipe r = es.r;
        box.addView(label("Crop"), gap(4));
        LinearLayout crops = row();
        final List<Button> cb = new ArrayList<Button>();
        for (final String c : Recipe.CROPS) {
            Button b = chip(Recipe.CROP_FULL.equals(c) ? "Full" : c, c.equals(r.crop));
            b.setTextSize(14);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    r.crop = c;
                    for (int k = 0; k < Recipe.CROPS.length; k++) styleChip(cb.get(k), Recipe.CROPS[k].equals(r.crop));
                    editPreview(es);
                }
            });
            cb.add(b);
            crops.addView(b, weight());
        }
        box.addView(crops, gap(4));
        box.addView(text("Cut from the middle of the photo (turned first when north is up), as large as fits.", 12, RED_DIM, false), gap(4));
        box.addView(label("Turn"), gap(14));
        LinearLayout turn = row();
        final Button nb = chip("North up", r.northUp);
        nb.setTextSize(14);
        nb.setEnabled(es.info.noNorth.isEmpty());
        nb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.northUp = !r.northUp;
                styleChip(nb, r.northUp);
                editPreview(es);
            }
        });
        turn.addView(nb, weight());
        turn.addView(spacer(), weight());
        box.addView(turn, gap(4));
        box.addView(text(es.info.noNorth.isEmpty() ? "Turns the photo so north is up, from where its stars say the camera pointed."
                : es.info.noNorth, 12, RED_DIM, false), gap(4));
    }

    private void framesTab(final EditState es, LinearLayout box) {
        final ReportCard.Frame[] fr = es.info.frames;
        final int n = fr.length;
        final TextView cap = text("", 13, RED, false);
        final LinearLayout actions = row();
        final TextView refL = text("", 14, RED, false);
        final Button refAuto = smallButton("Automatic", false);
        final FrameStrip strip = new FrameStrip(this);
        final Runnable update = new Runnable() {
            @Override
            public void run() {
                if (es.r.reference >= 0 && !keeps(es, es.r.reference)) es.r.reference = -1;
                markStrip(es, strip);
                frameActions(es, actions, cap, this);
                int ru = refUnit(es);
                refL.setText(ru < 0 ? (es.info.referenceUnit >= 0 ? "The stack chooses the reference again: the one it had is left out."
                        : "Lined up on the frame the stack chooses.") : es.r.reference >= 0
                        ? "Lined up on frame " + ru + ", chosen by hand." : "Lined up on frame " + ru + ", as developing chose.");
                refAuto.setVisibility(es.r.reference >= 0 ? View.VISIBLE : View.GONE);
                editHint(es);
            }
        };
        strip.setFrames(fr, new FrameStrip.Listener() {
            @Override
            public void onFrame(ReportCard.Frame f) {
                for (int i = 0; i < n; i++) if (fr[i] == f) es.picked = i;
                update.run();
            }
        });
        LinearLayout.LayoutParams slp = gap(4);
        slp.height = dp(72);
        box.addView(strip, slp);
        final TextView fromL = text("From " + frameWords(fr[es.from]), 14, RED, false);
        final TextView toL = text("To " + frameWords(fr[es.to]), 14, RED, false);
        final SeekBar fromS = seek(0), toS = seek(0);
        fromS.setMax(Math.max(1, n - 1));
        fromS.setProgress(es.from);
        toS.setMax(Math.max(1, n - 1));
        toS.setProgress(es.to);
        fromS.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int v, boolean user) {
                if (!user) return;
                v = Math.min(v, Math.min(es.to, n - 1));
                if (v != b.getProgress()) b.setProgress(v);
                es.from = v;
                fromL.setText("From " + frameWords(fr[v]));
                update.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        toS.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int v, boolean user) {
                if (!user) return;
                v = Math.min(n - 1, Math.max(v, es.from));
                if (v != b.getProgress()) b.setProgress(v);
                es.to = v;
                toL.setText("To " + frameWords(fr[v]));
                update.run();
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        box.addView(fromL, gap(8));
        box.addView(fromS);
        box.addView(toL, gap(2));
        box.addView(toS);
        box.addView(cap, gap(8));
        box.addView(actions, gap(4));
        if (!es.r.trails) {
            box.addView(label("Reference frame"), gap(14));
            LinearLayout rr = row();
            rr.setGravity(Gravity.CENTER_VERTICAL);
            rr.addView(refL, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f));
            refAuto.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    es.r.reference = -1;
                    update.run();
                }
            });
            rr.addView(refAuto, weight());
            box.addView(rr, gap(4));
            box.addView(text("The other frames are lined up on its stars. Pick a sharp frame with plenty of stars.", 12, RED_DIM, false), gap(2));
        }
        if (es.info.was.quick) {
            box.addView(label("Quality"), gap(14));
            LinearLayout qr = row();
            final Button q = chip("Full quality", !es.r.quick);
            q.setTextSize(14);
            q.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    es.r.quick = !es.r.quick;
                    styleChip(q, !es.r.quick);
                    editHint(es);
                }
            });
            qr.addView(q, weight());
            qr.addView(spacer(), weight());
            box.addView(qr, gap(4));
            box.addView(text("It was a Quick look: full quality measures the sensor's pattern, stacks twice and saves the photo full size.",
                    12, RED_DIM, false), gap(2));
        }
        update.run();
    }

    /** "frame 12 (11:14 PM)". */
    private static String frameWords(ReportCard.Frame f) {
        return "frame " + f.index + " (" + f.time + ")";
    }

    private static void markStrip(EditState es, FrameStrip strip) {
        strip.setEdit(es.from, es.to, es.leftOut, refUnit(es), es.r.taps.keySet());
        if (es.picked >= 0) strip.select(es.picked);
    }

    /** What can be done with the frame touched: leave it out or put it back, line the others up on it, line it up by hand. */
    private void frameActions(final EditState es, LinearLayout actions, TextView cap, final Runnable update) {
        actions.removeAllViews();
        if (es.picked < 0 || es.picked >= es.info.frames.length) {
            cap.setText("Touch a frame to leave it out" + (es.r.trails ? "" : ", line the others up on it, or line it up by hand") + ".");
            return;
        }
        final ReportCard.Frame f = es.info.frames[es.picked];
        final boolean out = es.leftOut.contains(f.index), inTrim = es.picked >= es.from && es.picked <= es.to;
        String state = !inTrim ? "outside the trim" : out ? "left out by hand" : !f.used ? "left out when developed: " + f.reason
                : "score " + f.score + (f.reason.isEmpty() ? "" : ": " + f.reason);
        float[] t = es.r.taps.get(f.index);
        if (t != null) state += "; lined up by hand on " + t.length / 4 + " stars";
        cap.setText("Frame " + f.index + " (" + f.time + "), " + state + ".");
        Button lo = smallButton(out ? "Put back" : "Leave out", false);
        lo.setEnabled(inTrim);
        lo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (out) {
                    es.leftOut.remove(f.index);
                } else {
                    if (framesIn(es) <= 1) {
                        toastish("Keep at least one frame");
                        return;
                    }
                    es.leftOut.add(f.index);
                }
                update.run();
            }
        });
        actions.addView(lo, weight());
        if (es.r.trails) return;
        final int ru = refUnit(es);
        Button ref = smallButton(ru == f.index ? "Reference ✓" : "Reference", false);
        ref.setEnabled(ru != f.index);
        ref.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                es.r.reference = f.index;
                es.leftOut.remove(f.index);
                update.run();
            }
        });
        actions.addView(ref, weight());
        Button tap = smallButton("Line up by hand", false);
        tap.setEnabled(ru >= 0 && ru != f.index);
        tap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openTaps(es, f.index, update);
            }
        });
        actions.addView(tap, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));
    }

    private void groundTab(final EditState es, LinearLayout box) {
        final Recipe r = es.r;
        final SessionController.EditInfo in = es.info;
        LinearLayout fr = row();
        final Button fz = chip("Keep the ground still", r.freezeGround);
        fz.setTextSize(14);
        fz.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                r.freezeGround = !r.freezeGround;
                styleChip(fz, r.freezeGround);
                editHint(es);
            }
        });
        fr.addView(fz, weight());
        box.addView(fr, gap(4));
        box.addView(text("Trees and roof lines at the edges stay sharp: they come from the frames' plain average, not lined up with "
                + "the stars, and the stars beside them only from frames that saw them.", 12, RED_DIM, false), gap(4));
        String where = EdgeMask.where(in.autoGround, es.painting.cw, es.painting.ch);
        box.addView(text(in.autoGround == null || where.isEmpty() ? "Nothing was found at the edges automatically: paint any ground there."
                : String.format(Locale.US, "Found automatically (shaded): %s, %.0f%% of the frame.", where,
                100.0 * EdgeMask.count(in.autoGround) / in.autoGround.length), 13, RED, false), gap(10));
        box.addView(label("One finger"), gap(12));
        LinearLayout bs = row();
        final String[] names = {"Moves", "Ground", "Sky", "Rubs out"};
        final int[] vals = {-1, EdgeMask.GROUND, EdgeMask.SKY, EdgeMask.NONE};
        final List<Button> bb = new ArrayList<Button>();
        for (int k = 0; k < names.length; k++) {
            final int val = vals[k];
            Button c = chip(names[k], es.brush == val);
            c.setTextSize(13);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    es.brush = val;
                    for (int j = 0; j < vals.length; j++) styleChip(bb.get(j), es.brush == vals[j]);
                    if (editZoom != null) editZoom.setPainter(es.brush >= 0 ? painter(es) : null, dp(es.brushDp));
                    editHint(es);
                }
            });
            bb.add(c);
            bs.addView(c, weight());
        }
        box.addView(bs, gap(4));
        final TextView sl = text("Brush " + es.brushDp + " dp", 14, RED, false);
        SeekBar size = seek(0);
        size.setMax(84);
        size.setProgress(es.brushDp - 12);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar b, int v, boolean user) {
                if (!user) return;
                es.brushDp = 12 + v;
                sl.setText("Brush " + es.brushDp + " dp");
                if (editZoom != null && es.brush >= 0) editZoom.setPainter(painter(es), dp(es.brushDp));
            }

            @Override
            public void onStartTrackingTouch(SeekBar b) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar b) {
            }
        });
        box.addView(sl, gap(8));
        box.addView(size);
        LinearLayout ur = row();
        final Button undo = smallButton("Undo", false);
        undo.setEnabled(!es.undo.isEmpty());
        es.undoButton = undo;
        undo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                undoPainting(es);
            }
        });
        ur.addView(undo, weight());
        Button clear = smallButton("Clear the painting", false);
        clear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (es.painting.isBlank()) return;
                pushUndo(es, es.painting);
                es.painting = new EdgeMask(es.painting.width, es.painting.height);
                es.cells = null;
                if (editZoom != null) editZoom.setOverlay(groundOverlay(es));
                editHint(es);
                undo.setEnabled(true);
            }
        });
        ur.addView(clear, weight());
        box.addView(ur, gap(10));
        box.addView(text("Ground: something in the way that wasn't found. Sky: sky that was taken for ground. Stars on the ground are "
                + "left out of lining up the frames.", 12, RED_DIM, false), gap(6));
    }

    /** The foreground over the whole picture: shaded where it was found, stronger where painted as ground. */
    private ZoomImageView.Overlay groundOverlay(final EditState es) {
        if (es.painting == null) return null;
        if (es.cells == null) groundCells(es, 0, 0, es.painting.cw - 1, es.painting.ch - 1);
        final SessionController.EditInfo in = es.info;
        final Matrix mx = new Matrix();
        float sc = EdgeMask.CELL / (float) in.factor;
        mx.setScale(sc, sc);
        mx.postTranslate(-in.cx0 / (float) in.factor, -in.cy0 / (float) in.factor);
        // no filtering: the cells stay square
        final Paint p = new Paint();
        return new ZoomImageView.Overlay() {
            @Override
            public void draw(Canvas c, float scale) {
                if (es.cells != null) c.drawBitmap(es.cells, mx, p);
            }
        };
    }

    /** Colours the cells x0..x1, y0..y1 of the overlay as the painting over the automatic finding has them. */
    private static void groundCells(EditState es, int x0, int y0, int x1, int y1) {
        EdgeMask m = es.painting;
        boolean[] auto = es.info.autoGround;
        if (es.cells == null) es.cells = Bitmap.createBitmap(m.cw, m.ch, Bitmap.Config.ARGB_8888);
        x0 = Math.max(0, x0);
        y0 = Math.max(0, y0);
        x1 = Math.min(m.cw - 1, x1);
        y1 = Math.min(m.ch - 1, y1);
        if (x1 < x0 || y1 < y0) return;
        int w = x1 - x0 + 1, h = y1 - y0 + 1;
        int[] px = new int[w * h];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int k = (y0 + j) * m.cw + x0 + i;
                byte v = m.paint[k];
                boolean a = auto != null && k < auto.length && auto[k];
                px[j * w + i] = v == EdgeMask.GROUND ? 0xA0E53935 : v == EdgeMask.SKY ? (a ? 0x30FFCDD2 : 0) : a ? 0x60E53935 : 0;
            }
        }
        es.cells.setPixels(px, 0, w, x0, y0, w, h);
    }

    /** One finger on the Ground tab: paints the brush's value into the painting, in the sensor's cells. */
    private ZoomImageView.PaintListener painter(final EditState es) {
        return new ZoomImageView.PaintListener() {
            @Override
            public void onPaint(float x, float y, float radius, boolean start) {
                SessionController.EditInfo in = es.info;
                if (es.painting == null || es.brush < 0) return;
                if (start) {
                    es.strokeBefore = es.painting.copy();
                    es.strokeKept = false;
                }
                float sx = in.cx0 + x * in.factor, sy = in.cy0 + y * in.factor, sr = radius * in.factor;
                if (!es.painting.paintDisc(sx, sy, sr, (byte) es.brush)) return;
                if (!es.strokeKept && es.strokeBefore != null) {
                    // the first change of the stroke: what it changes can be undone
                    pushUndo(es, es.strokeBefore);
                    es.strokeKept = true;
                    if (es.undoButton != null) es.undoButton.setEnabled(true);
                }
                int c = EdgeMask.CELL;
                groundCells(es, (int) ((sx - sr) / c) - 1, (int) ((sy - sr) / c) - 1, (int) ((sx + sr) / c) + 1, (int) ((sy + sr) / c) + 1);
                if (editZoom != null) editZoom.invalidate();
                if (start) editHint(es);
            }

            @Override
            public void onStrokeCancelled() {
                if (!es.strokeKept || es.undo.isEmpty()) return;
                es.strokeKept = false;
                undoPainting(es);
            }
        };
    }

    /** At most this many steps back on the Ground tab. */
    private static final int UNDO_STEPS = 20;

    private static void pushUndo(EditState es, EdgeMask before) {
        es.undo.add(before);
        while (es.undo.size() > UNDO_STEPS) es.undo.remove(0);
    }

    /** The painting as it was before the last stroke or clearing. */
    private void undoPainting(EditState es) {
        if (es.undo.isEmpty()) return;
        es.painting = es.undo.remove(es.undo.size() - 1);
        es.cells = null;
        if (editZoom != null) editZoom.setOverlay(groundOverlay(es));
        editHint(es);
        if (es.undoButton != null) es.undoButton.setEnabled(!es.undo.isEmpty());
    }

    private void rendersTab(final EditState es, LinearLayout box) {
        List<Recipe> rs = es.info.renders;
        box.addView(text("Every save is a new render with its own recipe; none is written over. Open one to see it again or to edit "
                + "from it.", 12, RED_DIM, false), gap(2));
        SimpleDateFormat df = new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault());
        for (int i = rs.size() - 1; i >= 0; i--) {
            final Recipe x = rs.get(i);
            boolean shown = x.number == es.info.was.number;
            LinearLayout c = card();
            c.addView(text("Render " + x.number + (shown ? " (shown)" : ""), 15, RED, true));
            c.addView(text((x.createdMs > 0 ? df.format(new Date(x.createdMs)) + " · " : "") + (x.note.isEmpty() ? "made" : x.note),
                    13, RED_DIM, false), gap(2));
            if (x.outdated()) c.addView(text("Made by an older version's processing.", 12, RED_DIM, false), gap(2));
            if (!shown) {
                c.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (service == null) return;
                        File dir = es.info.dir;
                        closeReport();
                        service.controller().openRender(dir, x.number);
                    }
                });
            }
            box.addView(c, gap(8));
        }
        if (es.info.was.outdated() && es.info.noRestack.isEmpty()) {
            Button again = smallButton("Make it again with the new processing", false);
            again.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (service == null) return;
                    Recipe e = es.info.was.edit();
                    e.restacked();
                    es.saved = true;
                    service.controller().saveEdit(e, null);
                    closeReport();
                }
            });
            box.addView(again, gap(10));
        }
        if (!es.info.noRestack.isEmpty()) box.addView(text(es.info.noRestack, 12, RED_DIM, false), gap(10));
    }

    // ---- lining a frame up by hand (F3's rescue, in G3)

    /**
     * Its own screen over the edit screen: a star tapped in the reference frame, then the same star in frame
     * {@code unit}, two pairs or more, each pulled onto the star under the finger; {@code done} runs when it closes.
     */
    private void openTaps(final EditState es, final int unit, final Runnable done) {
        if (es.tapView != null) return;
        final SessionController.EditInfo in = es.info;
        final int ref = refUnit(es);
        final FrameLayout tv = new FrameLayout(this);
        tv.setBackgroundColor(BG);
        tv.setClickable(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(8), dp(8), dp(8), dp(10));
        tv.addView(col, match());
        final TextView title = text("Line up frame " + unit + " by hand", 18, RED, true);
        final TextView say = text("Reading the two frames…", 14, RED_DIM, false);
        final ZoomImageView z = new ZoomImageView(this);
        z.setBackgroundColor(BG);
        col.addView(title);
        col.addView(say, gap(4));
        col.addView(z, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout bar = row();
        final Button undo = smallButton("Undo", false), flip = smallButton("Other frame", false), cancel = smallButton("Cancel", false),
                ok = smallButton("Done", true);
        bar.addView(undo, weight());
        bar.addView(flip, weight());
        bar.addView(cancel, weight());
        bar.addView(ok, weight());
        col.addView(bar, gap(6));
        es.ov.addView(tv, match());
        es.tapView = tv;

        // pairs: a star in the reference (x, y) and in the frame (x, y), sensor pixels
        final List<float[]> pairs = new ArrayList<float[]>();
        float[] old = es.r.taps.get(unit);
        if (old != null) for (int k = 0; k + 3 < old.length; k += 4) pairs.add(new float[]{old[k], old[k + 1], old[k + 2], old[k + 3]});
        final float[] pending = {Float.NaN, Float.NaN};
        final FramePreview[] fp = new FramePreview[2];
        final Bitmap[] bm = new Bitmap[2];
        final int[] showing = {0};
        final Runnable prompt = new Runnable() {
            @Override
            public void run() {
                int np = pairs.size();
                if (fp[0] == null) return;
                if (showing[0] == 1 && !Float.isNaN(pending[0])) {
                    say.setText("Now tap the same star in frame " + unit + ". Use Other frame to look back.");
                } else if (showing[0] == 1) {
                    say.setText("Frame " + unit + ". Use Other frame to go back to the reference and tap a star there first.");
                } else if (np == 0) {
                    say.setText("Tap a star in the reference (frame " + ref + ") that you can find again. Two fingers zoom.");
                } else {
                    say.setText(np + (np == 1 ? " star" : " stars") + " matched. Tap another star in the reference, far from the others"
                            + (np >= 2 ? ", or press Done." : "."));
                }
                title.setText((showing[0] == 0 ? "The reference, frame " + ref : "Frame " + unit) + ": line up by hand");
                ok.setEnabled(np >= 2 || (np == 0 && es.r.taps.containsKey(unit)));
                ok.setText(np == 0 && es.r.taps.containsKey(unit) ? "Remove" : "Done");
                undo.setEnabled(np > 0 || !Float.isNaN(pending[0]));
                z.invalidate();
            }
        };
        z.setOverlay(new ZoomImageView.Overlay() {
            final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), num = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas c, float scale) {
                ring.setStyle(Paint.Style.STROKE);
                ring.setColor(RED);
                ring.setStrokeWidth(dp(2) / scale);
                num.setColor(RED);
                num.setTextSize(dp(14) / scale);
                float r = dp(12) / scale;
                int o = showing[0] == 0 ? 0 : 2;
                for (int k = 0; k < pairs.size(); k++) {
                    float x = pairs.get(k)[o] / 2, y = pairs.get(k)[o + 1] / 2;
                    c.drawCircle(x, y, r, ring);
                    c.drawText(Integer.toString(k + 1), x + r, y - r, num);
                }
                if (showing[0] == 0 && !Float.isNaN(pending[0])) {
                    c.drawCircle(pending[0] / 2, pending[1] / 2, r, ring);
                    c.drawCircle(pending[0] / 2, pending[1] / 2, r * 0.3f, ring);
                }
            }
        });
        z.setTapListener(new ZoomImageView.TapListener() {
            @Override
            public void onTap(float x, float y) {
                int sh = showing[0];
                if (fp[sh] == null) return;
                float[] p = fp[sh].snap(x, y, 14);
                if (p == null) {
                    toastish("No star there: tap right on one");
                    return;
                }
                if (sh == 0) {
                    pending[0] = p[0];
                    pending[1] = p[1];
                    showing[0] = 1;
                    z.show(bm[1], true);
                } else if (!Float.isNaN(pending[0])) {
                    pairs.add(new float[]{pending[0], pending[1], p[0], p[1]});
                    pending[0] = pending[1] = Float.NaN;
                    showing[0] = 0;
                    z.show(bm[0], true);
                } else {
                    toastish("First tap the star in the reference");
                }
                prompt.run();
            }
        });
        undo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!Float.isNaN(pending[0])) pending[0] = pending[1] = Float.NaN;
                else if (!pairs.isEmpty()) pairs.remove(pairs.size() - 1);
                if (showing[0] != 0 && bm[0] != null) {
                    showing[0] = 0;
                    z.show(bm[0], true);
                }
                prompt.run();
            }
        });
        flip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bm[0] == null) return;
                showing[0] = 1 - showing[0];
                z.show(bm[showing[0]], true);
                prompt.run();
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeTaps(es);
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pairs.isEmpty()) {
                    es.r.taps.remove(unit);
                } else {
                    float[] t = new float[4 * pairs.size()];
                    for (int k = 0; k < pairs.size(); k++) System.arraycopy(pairs.get(k), 0, t, 4 * k, 4);
                    es.r.taps.put(unit, t);
                    es.leftOut.remove(unit);
                }
                closeTaps(es);
                done.run();
            }
        });
        ok.setEnabled(false);
        undo.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SessionStore st = new SessionStore(in.dir);
                    UnitRecord ra = null, rb = null;
                    for (UnitRecord u : st.readUnits()) {
                        if (u.index == ref) ra = u;
                        if (u.index == unit) rb = u;
                    }
                    FrameStore fs = st.frames(in.width, in.height);
                    short[] raw = new short[in.width * in.height];
                    final FramePreview a = framePreview(fs, ra, ref, raw, in.width, in.height);
                    final FramePreview b = framePreview(fs, rb, unit, raw, in.width, in.height);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (es.tapView != tv) return;
                            fp[0] = a;
                            fp[1] = b;
                            bm[0] = Bitmap.createBitmap(a.argb, a.w, a.h, Bitmap.Config.ARGB_8888);
                            bm[1] = Bitmap.createBitmap(b.argb, b.w, b.h, Bitmap.Config.ARGB_8888);
                            z.show(bm[0], false);
                            prompt.run();
                        }
                    });
                } catch (final IOException | RuntimeException | OutOfMemoryError e) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (es.tapView == tv) say.setText("The frames couldn't be read: " + e.getMessage());
                        }
                    });
                }
            }
        }, "tap-frames").start();
    }

    private static FramePreview framePreview(FrameStore fs, UnitRecord rec, int unit, short[] raw, int w, int h) throws IOException {
        if (rec == null || !fs.exists(unit)) throw new IOException("frame " + unit + " isn't on the phone");
        fs.read(unit, raw);
        return FramePreview.of(raw, w, h, rec.invScale, rec.black);
    }

    private void closeTaps(EditState es) {
        if (es.tapView == null) return;
        es.ov.removeView(es.tapView);
        es.tapView = null;
    }


    // ------------------------------------------------------------------------------------------------
    // Session report
    // ------------------------------------------------------------------------------------------------

    /** The report card over the result screen, until Close or Back; with the sessions stacked when it's a merge. */
    private void showReport(ReportCard rc, MergeProcessor.Result merge) {
        if (rc == null || reportOverlay != null) return;
        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(BG);
        ov.setClickable(true);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = column();
        sv.addView(col);
        ov.addView(sv, match());
        col.addView(label("Session report"));
        col.addView(text(rc.headline, 18, RED, true), gap(6));
        if (!rc.gain.isEmpty()) col.addView(text(rc.gain, 15, RED, false), gap(6));

        col.addView(label(rc.problems.isEmpty() ? "No problems found" : "What cost light, biggest first"), gap(18));
        if (rc.problems.isEmpty()) col.addView(text("A clear, steady session.", 14, RED_DIM, false), gap(4));
        for (ReportCard.Problem p : rc.problems) {
            LinearLayout c = card();
            c.addView(text(p.title, 16, RED, true));
            c.addView(text(String.format(Locale.US, "Cost about %.0f%% of the light", 100 * p.impact), 12, RED_DIM, false), gap(2));
            c.addView(text(p.evidence, 14, RED, false), gap(6));
            c.addView(text("Fix: " + p.fix, 14, RED_DIM, false), gap(6));
            col.addView(c, gap(8));
        }
        for (String note : rc.notes) col.addView(text(note, 13, RED_DIM, false), gap(6));

        col.addView(label("Frames"), gap(18));
        final TextView fcap = text(rc.goodFrames + " of " + rc.frames.length + " frames scored 50 or more. Touch a bar to see "
                + "a frame's score.", 13, RED_DIM, false);
        FrameStrip strip = new FrameStrip(this);
        strip.setFrames(rc.frames, new FrameStrip.Listener() {
            @Override
            public void onFrame(ReportCard.Frame f) {
                String what = !f.used ? "left out: " + f.reason
                        : "score " + f.score + (f.partly ? ", used partly" : "") + (f.reason.isEmpty() ? "" : ": " + f.reason);
                fcap.setText(String.format(Locale.US, "Frame %d (%s), %s", f.index, f.time, what));
            }
        });
        LinearLayout.LayoutParams slp = gap(8);
        slp.height = dp(64);
        col.addView(strip, slp);
        col.addView(fcap, gap(4));

        if (merge != null) addMergeSection(col, merge);

        if (!rc.registration.isEmpty()) {
            col.addView(label("Lining up"), gap(18));
            col.addView(text(rc.registration + " Confidence: " + rc.confidence + ".", 14, RED, false), gap(4));
        }
        if (rc.sky != null && !rc.sky.lines.isEmpty()) {
            col.addView(label("What you're looking at"), gap(18));
            for (String l : rc.sky.lines) col.addView(text(l, 14, RED, false), gap(4));
        }
        if (!rc.streaks.isEmpty()) {
            col.addView(label("Satellites and streaks"), gap(18));
            col.addView(text(rc.streaks, 14, RED, false), gap(4));
            List<String> named = new java.util.ArrayList<String>();
            for (ReportCard.Streak k : rc.streakList) {
                if (k.norad == 0) continue;
                named.add(String.format(Locale.US, "%s%s, %d frame%s%s", SatPlanner.the(k.name), k.time.isEmpty() ? "" : " at " + k.time,
                        k.frames, k.frames == 1 ? "" : "s", Double.isNaN(k.mag) ? "" : String.format(Locale.US, ", magnitude %.1f", k.mag)));
            }
            if (named.size() > 5) col.addView(text("All of them: " + TextUtils.join("; ", named) + ".", 13, RED_DIM, false), gap(6));
        }
        if (!rc.conditions.isEmpty()) col.addView(text(rc.conditions, 13, RED_DIM, false), gap(6));
        col.addView(label("Next time"), gap(18));
        col.addView(text(rc.tip, 14, RED, false), gap(4));
        Button close = button("Close", true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeReport();
            }
        });
        col.addView(close, gap(20));
        openOverlay(ov);
    }

    /** Shows a page over the result screen until Close or Back (the report card, a merge's details). */
    private void openOverlay(FrameLayout ov) {
        root.addView(ov, match());
        reportOverlay = ov;
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedCallback cb = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    overlayBack();
                }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
            reportBack = cb;
        }
    }

    /** Back over the result screen: lining a frame up by hand closes first, then the page over it. */
    private void overlayBack() {
        if (edit != null && edit.tapView != null) {
            closeTaps(edit);
            return;
        }
        closeReport();
    }

    private void closeReport() {
        if (reportOverlay != null) {
            root.removeView(reportOverlay);
            reportOverlay = null;
        }
        if (edit != null) {
            EditState es = edit;
            edit = null;
            editZoom = null;
            // closed without saving: the photo as it was
            if (!es.saved && service != null) service.controller().previewEdit(null, false);
        }
        if (reportBack != null && Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) reportBack);
        }
        reportBack = null;
    }

    /** While capturing, volume up (or a Bluetooth remote's button) adds 5 minutes (G1), unless that's off in the settings. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && service != null && prefs.remoteKey()) {
            SessionController.Phase p = service.controller().status().phase;
            if (p == SessionController.Phase.CAPTURING || p == SessionController.Phase.PAUSED) {
                if (event.getRepeatCount() == 0) service.controller().addTimeFromKey();
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP && service != null && prefs.remoteKey()) {
            SessionController.Phase p = service.controller().status().phase;
            if (p == SessionController.Phase.CAPTURING || p == SessionController.Phase.PAUSED) return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // before Android 13 (from 13 on, the callbacks take Back)
        if (reportOverlay != null) {
            overlayBack();
            return;
        }
        if ((shownPhase == null || screenOf(shownPhase) == 0) && pageBack()) return;
        super.onBackPressed();
    }

    // ------------------------------------------------------------------------------------------------
    // Remote viewer card
    // ------------------------------------------------------------------------------------------------

    /** Rebuilds the viewer card if what it shows has changed (addresses are re-read every 2 seconds). */
    private void refreshViewer(boolean force) {
        if (viewerBox == null || service == null) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && now - viewerCheckedMs < 2000) return;
        viewerCheckedMs = now;
        RemoteHost r = service.remote();
        List<RemoteHost.Address> as = r != null ? RemoteHost.addresses(this) : null;
        StringBuilder sig = new StringBuilder();
        sig.append(service.viewerVersion()).append('|').append(viewerCompact).append('|').append(viewerStoppable);
        if (r != null) {
            sig.append('|').append(r.port());
            for (RemoteHost.Address a : as) sig.append('|').append(a.ip);
        }
        String key = sig.toString();
        if (key.equals(shownViewer)) return;
        shownViewer = key;
        viewerBox.removeAllViews();
        if (r == null) return;
        if (viewerCompact) buildViewerLine(r, as);
        else buildViewerCard(r, as);
    }

    private static RemoteHost.Address pick(List<RemoteHost.Address> as, boolean hotspotOn) {
        if (as.isEmpty()) return null;
        if (hotspotOn) {
            for (RemoteHost.Address a : as) if (a.hotspot) return a;
        }
        for (RemoteHost.Address a : as) if (a.wifi) return a;
        return as.get(0);
    }

    /** One line on the capture and result screens; on the result screen with a button to stop the viewer. */
    private void buildViewerLine(RemoteHost r, List<RemoteHost.Address> as) {
        RemoteHost.Address a = pick(as, service.hotspotInfo() != null);
        if (a != null) {
            TextView t = text("Viewer: " + r.typedFor(a.ip) + " · code " + spaced(r.pairCode()), 13, RED_DIM, false);
            t.setTextIsSelectable(true);
            viewerBox.addView(t);
        }
        if (!viewerStoppable) return;
        Button stop = button("Stop the viewer", false);
        stop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.stopViewer();
                refreshViewer(true);
            }
        });
        viewerBox.addView(stop, gap(6));
    }

    /** The arm screen's card: QR codes, the address to type, the pairing code and the hotspot. */
    private void buildViewerCard(final RemoteHost r, List<RemoteHost.Address> as) {
        LinearLayout card = card();
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.addView(text("Watch it from another device", 16, RED, true));
        final String[] hs = service.hotspotInfo();
        RemoteHost.Address a = pick(as, hs != null);
        int qrPx = Math.min(dp(230), getResources().getDisplayMetrics().widthPixels - dp(90));
        if (hs != null) {
            card.addView(text("1. On the other device, join the phone's Wi-Fi: scan this code", 14, RED_DIM, false), gap(8));
            card.addView(qrView(QrCode.wifiPayload(hs[0], hs[1], "sae".equals(hs[2])), qrPx), gapWrap(6));
            TextView creds = text("Network " + hs[0] + "\nPassword " + hs[1], 14, RED, false);
            creds.setTextIsSelectable(true);
            creds.setGravity(Gravity.CENTER);
            card.addView(creds, gap(4));
            if (a != null) card.addView(text("2. Then open the viewer: scan this code", 14, RED_DIM, false), gap(12));
        }
        if (a == null) {
            card.addView(text("The phone is not on a Wi-Fi network. Connect it to Wi-Fi, or start a hotspot for the "
                    + "other device to join.", 14, RED_DIM, false), gap(8));
        } else {
            if (hs == null) card.addView(text("Scan with a phone or tablet camera on the same Wi-Fi:", 14, RED_DIM, false), gap(8));
            card.addView(qrView(r.linkFor(a.ip), qrPx), gapWrap(6));
            TextView typed = text("On a laptop, open " + r.typedFor(a.ip) + " and type the code " + spaced(r.pairCode()), 14, RED, false);
            typed.setTextIsSelectable(true);
            typed.setGravity(Gravity.CENTER);
            card.addView(typed, gap(6));
            if (as.size() > 1) {
                StringBuilder more = new StringBuilder("Other addresses: ");
                boolean first = true;
                for (RemoteHost.Address o : as) {
                    if (o == a) continue;
                    if (!first) more.append(", ");
                    more.append(r.typedFor(o.ip)).append(" (").append(o.label).append(')');
                    first = false;
                }
                TextView m = text(more.toString(), 12, RED_DIM, false);
                m.setTextIsSelectable(true);
                card.addView(m, gap(4));
            }
            String warn = RemoteHost.wifiWarning(this);
            if (warn != null && hs == null) card.addView(text(warn, 13, 0xFFFFCDD2, false), gap(6));
            card.addView(text("Tap a code if the other camera can't read it (the screen gets brighter).", 12, RED_DIM, false), gap(4));
        }
        String err = service.hotspotError();
        if (err != null) card.addView(text(err, 13, 0xFFFFCDD2, false), gap(6));
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            Button hb;
            if (hs != null) {
                hb = button("Turn off the viewer hotspot", false);
                hb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (service != null) service.stopHotspot();
                        refreshViewer(true);
                    }
                });
            } else {
                hb = button(service.hotspotStarting() ? "Starting the hotspot…" : "No Wi-Fi here? Start a hotspot for it", a == null);
                hb.setEnabled(!service.hotspotStarting());
                hb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                            requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 3);
                        } else if (service != null) {
                            service.startHotspot();
                        }
                        refreshViewer(true);
                    }
                });
            }
            card.addView(hb, gap(10));
        }
        viewerBox.addView(card);
    }

    private static String spaced(String code) {
        return code.length() == 6 ? code.substring(0, 3) + " " + code.substring(3) : code;
    }

    /** A QR code with dark modules on a pale square, pixel-exact so it stays sharp. */
    private ImageView qrView(String text, int targetPx) {
        QrCode q = QrCode.encodeText(text, QrCode.MEDIUM);
        int n = q.size + 8; // four light modules around the code
        int scale = Math.max(2, targetPx / n);
        int dim = n * scale;
        int[] px = new int[dim * dim];
        for (int y = 0; y < dim; y++) {
            int my = y / scale - 4;
            for (int x = 0; x < dim; x++) px[y * dim + x] = q.get(x / scale - 4, my) ? 0xFF000000 : QR_LIGHT;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(Bitmap.createBitmap(px, dim, dim, Bitmap.Config.ARGB_8888));
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setContentDescription("QR code");
        iv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setQrBright(!qrBright);
            }
        });
        return iv;
    }

    private void setQrBright(boolean on) {
        if (qrBright == on) return;
        qrBright = on;
        if (windowActive != null && windowActive) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = on ? 0.6f : 0.12f;
            getWindow().setAttributes(lp);
        }
    }

    private LinearLayout.LayoutParams gapWrap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        return lp;
    }

    /**
     * A sky reading (H3): the value and what it means, then read again, save the log, match a meter or done.
     * Shown over the lock screen too when the tile opened the app there.
     */
    private void showReading(SessionController.Status s) {
        clearRefs();
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = column();
        sv.addView(col);
        root.addView(sv, match());
        final SkyReading r = s.reading;
        col.addView(label("Sky reading"));
        col.addView(text(r.headline, 26, RED, true), gap(6));
        if (r.frames > 0) {
            col.addView(text(String.format(Locale.US, "Naked-eye stars to about magnitude %.1f%s", r.nelm, r.valid ? "" : " (doesn't count)"),
                    17, RED, false), gap(4));
        }
        for (String line : r.lines) col.addView(text(line, 14, RED_DIM, false), gap(8));
        int n = SkyReadings.count(this);
        col.addView(text(n + (n == 1 ? " reading" : " readings") + " in the log.", 13, RED_DIM, false), gap(14));

        Button again = button("Read again", true);
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSession(0, Launch.of("reading"));
            }
        });
        col.addView(again, gap(18));
        Button save = button("Save the readings (CSV for Excel)", false);
        save.setEnabled(n > 0);
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportReadings();
            }
        });
        col.addView(save, gap(10));
        if (r.frames > 0) {
            Button meter = button(prefs.sqmOffset() != 0 ? String.format(Locale.US, "Matched to a meter (%+.2f): change", prefs.sqmOffset())
                    : "Match it to a handheld meter (SQM-L)", false);
            meter.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    matchMeter(r);
                }
            });
            col.addView(meter, gap(10));
        }
        Button done = button("Done", false);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.controller().clearResult();
                page = PAGE_HOME;
                showHome();
            }
        });
        col.addView(done, gap(10));
    }

    /** Saves the readings' log as a CSV file in Documents/NightSky (off the main thread). */
    private void exportReadings() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    SkyReadings.export(app);
                    msg = "Saved in Documents/NightSky: open it in Excel or Sheets";
                } catch (Exception e) {
                    msg = "Not saved: " + e.getMessage();
                }
                final String m = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toastish(m);
                    }
                });
            }
        }, "export").start();
    }

    /**
     * A handheld meter read the same sky: the difference is kept and added to later readings (H3). Asks for the
     * meter's value; takes it only within 2 of this reading.
     */
    private void matchMeter(final SkyReading r) {
        final double own = r.meterMag - r.offset;
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        in.setHint(String.format(Locale.US, "%.2f", r.meterMag));
        in.setTextColor(RED);
        in.setHintTextColor(RED_DIM);
        FrameLayout pad = new FrameLayout(this);
        pad.setPadding(dp(20), dp(8), dp(20), 0);
        pad.addView(in);
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle("Match a handheld meter")
                .setMessage("Point your SQM-L straight up at the same sky now and type what it reads. Later readings are shifted "
                        + "by the difference.")
                .setView(pad)
                .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        double v;
                        try {
                            v = Double.parseDouble(in.getText().toString().trim().replace(',', '.'));
                        } catch (NumberFormatException e) {
                            toastish("That isn't a number");
                            return;
                        }
                        double off = v - own;
                        if (!(Math.abs(off) <= 2)) {
                            toastish(String.format(Locale.US, "%.2f is more than 2 away from this reading: check the number", v));
                            return;
                        }
                        prefs.setSqmOffset(off);
                        toastish(String.format(Locale.US, "Saved: readings from now on add %+.2f", off));
                    }
                })
                .setNegativeButton("Cancel", null);
        if (prefs.sqmOffset() != 0) {
            b.setNeutralButton("Remove the offset", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    prefs.setSqmOffset(0);
                    toastish("Offset removed");
                }
            });
        }
        b.show();
    }

    // ------------------------------------------------------------------------------------------------
    // Presets, saved sites and the settings file (E6)
    // ------------------------------------------------------------------------------------------------

    /**
     * At a saved site (by a fix from the last half hour), its preset goes in place before the home screen shows,
     * unless a setting was changed by hand in the last hour. What to say about it, or null if it did nothing.
     */
    private String autoPickSite() {
        Locator.Place pl = Locator.best(this, prefs);
        if (pl == null || pl.ageMinutes() > 30) return null;
        Site here = Sites.at(Sites.load(prefs), pl);
        if (here == null) return null;
        Site.Pick k = Sites.decide(prefs, here, prefs.preset(), true, System.currentTimeMillis());
        if (!k.use) return null;
        Presets.pick(prefs, k.preset, false);
        return "At the " + here.name + " site: its preset, " + k.preset.name + ", is in place.";
    }

    /** The presets card: a chip for each, what the one in place does, and the saved site here. */
    private void fillPresets() {
        final LinearLayout box = presetBox;
        if (box == null) return;
        box.removeAllViews();
        List<Preset> all = Presets.all(prefs);
        String cur = prefs.preset();
        LinearLayout r = null;
        for (int k = 0; k < all.size(); k++) {
            if (k % 2 == 0) {
                r = row();
                box.addView(r, gap(k == 0 ? 0 : 6));
            }
            final Preset p = all.get(k);
            Button b = chip(p.name, p.id.equals(cur));
            b.setTextSize(15);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Presets.pick(prefs, p, true);
                    sitePicked = "";
                    rebuildHome();
                }
            });
            if (!p.builtIn) {
                b.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        ownPreset(p);
                        return true;
                    }
                });
            }
            r.addView(b, weight());
        }
        if (r != null && all.size() % 2 == 1) r.addView(new View(this), weight());
        Preset in = Presets.find(prefs, cur);
        box.addView(text(in != null ? in.describe() + "." : "Your own settings: no preset has them.", 13, RED_DIM, false), gap(6));
        if (in == null) {
            Button save = button("Save them as a preset", false);
            save.setTextSize(15);
            save.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    askText("Name this preset", null, "", new TextAnswer() {
                        @Override
                        public void answer(String name) {
                            if (Presets.nameTaken(prefs, name, "")) {
                                toastish("There's a preset called " + name + " already");
                                return;
                            }
                            Presets.saveOwn(prefs, name);
                            fillPresets();
                            toastish("Saved the " + name + " preset");
                        }
                    });
                }
            });
            box.addView(save, gap(6));
        } else if (!in.builtIn) {
            box.addView(text("Touch and hold it to rename or delete it.", 12, RED_DIM, false), gap(2));
        }
        LinearLayout sr = row();
        sr.setGravity(Gravity.CENTER_VERTICAL);
        Button sb = button("Sites", false);
        sb.setTextSize(15);
        sb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPage(PAGE_SITES);
            }
        });
        sr.addView(text(siteWords(), 13, RED_DIM, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(8);
        sr.addView(sb, lp);
        box.addView(sr, gap(8));
    }

    /** The presets card's line about the saved sites. */
    private String siteWords() {
        List<Site> sites = Sites.load(prefs);
        if (sites.isEmpty()) return "Save the places you shoot from as sites: a session at one uses its preset by itself.";
        Locator.Place pl = Locator.best(this, prefs);
        if (pl == null) return sites.size() + (sites.size() == 1 ? " saved site" : " saved sites") + ": a session finds the one it's at.";
        Site here = Sites.at(sites, pl);
        if (here == null) return sites.size() == 1 ? "1 saved site, not here." : sites.size() + " saved sites, none here.";
        if (!sitePicked.isEmpty()) return sitePicked;
        String pn = Presets.name(prefs, here.presetId);
        return "At the " + here.name + " site" + (pn.isEmpty() ? "." : " (its preset: " + pn + ").");
    }

    /** The home screen again, scrolled where it was (a preset changes most of it). */
    private void rebuildHome() {
        final int y = homeScroll != null ? homeScroll.getScrollY() : 0;
        showHomePage();
        final ScrollView sv = homeScroll;
        if (sv != null) {
            sv.post(new Runnable() {
                @Override
                public void run() {
                    sv.scrollTo(0, y);
                }
            });
        }
    }

    /** A setting was changed by hand: the presets card shows which preset that makes it, if any. */
    private void handChanged() {
        Presets.handChanged(prefs);
        sitePicked = "";
        fillPresets();
    }

    /** One of Brandon's presets, touched and held: rename or delete it. */
    private void ownPreset(final Preset p) {
        new AlertDialog.Builder(this).setTitle(p.name).setItems(new String[]{"Rename", "Delete"}, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                if (w == 0) {
                    askText("Rename the preset", null, p.name, new TextAnswer() {
                        @Override
                        public void answer(String name) {
                            if (Presets.nameTaken(prefs, name, p.id)) {
                                toastish("There's a preset called " + name + " already");
                                return;
                            }
                            Presets.rename(prefs, p.id, name);
                            fillPresets();
                        }
                    });
                } else {
                    confirm("Delete the " + p.name + " preset? The settings in place stay as they are.", new Runnable() {
                        @Override
                        public void run() {
                            Presets.delete(prefs, p.id);
                            fillPresets();
                        }
                    });
                }
            }
        }).show();
    }

    interface TextAnswer {
        void answer(String text);
    }

    /** Asks for a name (at most 40 characters); an empty answer does nothing. */
    private void askText(String title, String message, String initial, final TextAnswer a) {
        final EditText in = new EditText(this);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        in.setSingleLine(true);
        in.setText(initial);
        in.setSelection(initial.length());
        in.setTextColor(RED);
        FrameLayout pad = new FrameLayout(this);
        pad.setPadding(dp(20), dp(8), dp(20), 0);
        pad.addView(in);
        AlertDialog.Builder b = new AlertDialog.Builder(this).setTitle(title).setView(pad)
                .setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String t = in.getText().toString().trim();
                        if (t.length() > 40) t = t.substring(0, 40).trim();
                        if (!t.isEmpty()) a.answer(t);
                    }
                })
                .setNegativeButton("Cancel", null);
        if (message != null) b.setMessage(message);
        b.show();
    }

    /** The camera sessions use (and the calibration calibrates), looked up once; null if there is none. */
    private String focusCameraId() {
        if (focusCamera == null) focusCamera = FocusCalibrator.cameraOf(this);
        return focusCamera;
    }

    /** What's known about the focus, for the Advanced settings. */
    private String focusModelWords() {
        String id = focusCameraId();
        if (id == null) return "";
        return FocusModel.parse(prefs.focusModel(id)).describe(BatteryInfo.read(this).tempC);
    }

    private void stopCalibrator() {
        if (calibrator == null) return;
        calibrator.stop();
        calibrator = null;
    }

    /**
     * The daylight focus calibration (F7): the camera's picture with a box for the far target, Calibrate, and the
     * calibrations so far (each removable).
     */
    private void showFocus() {
        page = PAGE_FOCUS;
        LinearLayout col = pageColumn("Home");
        col.addView(text("Daylight focus calibration", 26, RED, true));
        col.addView(text("At night the app finds the focus on the stars by itself. A lens's focus moves a little as it warms and "
                + "cools, so a few calibrations in daylight, at different temperatures, let it predict the night's focus and find "
                + "it in fewer frames.", 14, RED_DIM, false), gap(6));
        col.addView(text("1. Leave the phone outside in the shade for ten minutes, so it is at the air's temperature.\n"
                + "2. Prop it so the back camera looks at something at least 500 m away with sharp edges (a far building, a "
                + "tower, a ridge, trees on a distant hill), inside the box.\n"
                + "3. Tap Calibrate and keep the phone still for about 15 seconds.\n"
                + "Three calibrations at temperatures at least 5 \u00B0C apart, such as a cool morning and a warm afternoon, make "
                + "the line that predicts the focus.", 14, RED, false), gap(10));
        final TextView state = text("", 15, RED, true);
        final TextView detail = text("", 13, RED_DIM, false);
        final TextView outcome = text("", 14, RED, false);
        final Button go = button("Calibrate", true);
        final LinearLayout points = new LinearLayout(this);
        points.setOrientation(LinearLayout.VERTICAL);
        go.setEnabled(false);
        boolean busy = service != null && screenOf(service.controller().status().phase) > 0
                && screenOf(service.controller().status().phase) < 4;
        if (busy) {
            state.setText("Not while a session is running");
        } else if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            state.setText("The calibration needs the camera");
            Button allow = button("Allow the camera", false);
            allow.setTextSize(15);
            allow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_FOCUS_CAMERA);
                }
            });
            col.addView(allow, gap(10));
        } else {
            // the picture, 3:4 as the phone holds it, with the middle third (the part scored) boxed
            int w = getResources().getDisplayMetrics().widthPixels - dp(40);
            int hgt = w * 4 / 3, maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55);
            if (hgt > maxH) {
                hgt = maxH;
                w = hgt * 3 / 4;
            }
            FrameLayout frame = new FrameLayout(this);
            TextureView tv = new TextureView(this);
            frame.addView(tv, match());
            final Paint paint = new Paint();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2));
            paint.setColor(RED);
            View box = new View(this) {
                @Override
                protected void onDraw(Canvas c) {
                    float bw = getWidth() / 3f, bh = getHeight() / 3f;
                    c.drawRect(bw, bh, 2 * bw, 2 * bh, paint);
                }
            };
            frame.addView(box, match());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, hgt);
            lp.topMargin = dp(12);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            col.addView(frame, lp);
            tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                /** The calibrator showing on this view (a view on its way out mustn't stop the next one's). */
                FocusCalibrator mine;

                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int vw, int vh) {
                    stopCalibrator();
                    mine = calibrator = new FocusCalibrator(MainActivity.this, prefs, main, new FocusCalibrator.Listener() {
                        @Override
                        public void onState(String head, String text) {
                            state.setText(head);
                            detail.setText(text);
                            go.setEnabled(!head.startsWith("Calibrating"));
                        }

                        @Override
                        public void onDone(boolean ok, String text) {
                            state.setText(ok ? "Calibrated" : "Not calibrated");
                            detail.setText("");
                            outcome.setText(text);
                            go.setEnabled(true);
                            go.setText("Calibrate again");
                            if (ok) fillFocusPoints(points);
                        }

                        @Override
                        public void onError(String text) {
                            state.setText("The camera isn't available");
                            detail.setText(text);
                            go.setEnabled(false);
                        }
                    });
                    String err = calibrator.start(st);
                    if (err != null) {
                        stopCalibrator();
                        state.setText("The camera isn't available");
                        detail.setText(err);
                    }
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int vw, int vh) {
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    if (mine != null && mine == calibrator) stopCalibrator();
                    else if (mine != null) mine.stop();
                    mine = null;
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {
                }
            });
            go.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (calibrator == null) return;
                    go.setEnabled(false);
                    outcome.setText("");
                    calibrator.calibrate();
                }
            });
            col.addView(go, gap(10));
        }
        col.addView(state, gap(10));
        col.addView(detail, gap(2));
        col.addView(outcome, gap(8));
        col.addView(points, gap(16));
        fillFocusPoints(points);
    }

    /** What the calibrations and the nights have found, newest first; a daylight one can be removed. */
    private void fillFocusPoints(final LinearLayout box) {
        box.removeAllViews();
        final String id = focusCameraId();
        if (id == null) return;
        final FocusModel m = FocusModel.parse(prefs.focusModel(id));
        box.addView(text(m.describe(BatteryInfo.read(this).tempC), 13, RED_DIM, false));
        if (!m.day.isEmpty()) {
            box.addView(label("Daylight calibrations"), gap(14));
            for (int i = m.day.size() - 1; i >= 0; i--) {
                final FocusModel.Cal c = m.day.get(i);
                LinearLayout r = row();
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.addView(text(String.format(Locale.US, "%.3f at %s, %s%s", c.focus, Double.isNaN(c.tempC) ? "an unknown temperature"
                                : String.format(Locale.US, "%.0f \u00B0C", c.tempC), FocusModel.ago(c.timeMs),
                        Double.isNaN(c.contrast) ? "" : String.format(Locale.US, " (sharpness x%.1f)", c.contrast)), 14, RED, false),
                        new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                Button rm = button("Remove", false);
                rm.setTextSize(13);
                rm.setPadding(dp(10), dp(6), dp(10), dp(6));
                rm.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        confirm("Remove this calibration? Use this for one made with the target too close or the phone moving.",
                                new Runnable() {
                                    @Override
                                    public void run() {
                                        FocusModel now = FocusModel.parse(prefs.focusModel(id));
                                        for (int k = 0; k < now.day.size(); k++) {
                                            FocusModel.Cal x = now.day.get(k);
                                            if (x.timeMs == c.timeMs && Math.abs(x.focus - c.focus) < 1e-6) {
                                                now.day.remove(k);
                                                break;
                                            }
                                        }
                                        prefs.setFocusModel(id, now.encode());
                                        fillFocusPoints(box);
                                    }
                                });
                    }
                });
                r.addView(rm);
                box.addView(r, gap(6));
            }
        }
        if (!m.night.isEmpty()) {
            box.addView(label("Found on the stars"), gap(14));
            for (int i = m.night.size() - 1, k = 0; i >= 0 && k < 6; i--, k++) {
                FocusModel.Cal c = m.night.get(i);
                box.addView(text(String.format(Locale.US, "%.3f at %s, %s%s", c.focus, Double.isNaN(c.tempC) ? "an unknown temperature"
                                : String.format(Locale.US, "%.0f \u00B0C", c.tempC), FocusModel.ago(c.timeMs),
                        Double.isNaN(c.hfr) ? "" : String.format(Locale.US, ", stars %.1f px across", 2 * c.hfr)), 14, RED, false), gap(4));
            }
        }
    }

    /** The saved sites: save this place, and each site's preset, name and what it has learned. */
    private void showSites() {
        page = PAGE_SITES;
        LinearLayout col = pageColumn("Home");
        col.addView(text("Saved sites", 26, RED, true));
        col.addView(text("A site is a place you shoot from. A session that starts within about 2 km of one uses the site's preset "
                + "and says so (\"Backyard site, Backyard preset.\"), and the site keeps what the app learns there: how dark its "
                + "sky is, how many stars a clear night shows, and what blocks the view.", 14, RED_DIM, false), gap(6));
        CheckBox auto = check("Use a site's preset by itself when a session starts there", prefs.autoSite());
        auto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                prefs.setAutoSite(on);
            }
        });
        col.addView(auto, gap(10));
        final long now = System.currentTimeMillis();
        final Locator.Place pl = Locator.best(this, prefs);
        List<Site> sites = Sites.load(prefs);
        final Site here = Sites.at(sites, pl);
        Button add = button(here == null ? "Save this place as a site" : "Save this place as another site", true);
        add.setEnabled(pl != null);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addSite(pl);
            }
        });
        col.addView(add, gap(14));
        col.addView(text(pl == null ? "Where the phone is isn't known: allow location (approximate is enough), then find it."
                : "Here: " + pl.describe(), 12, RED_DIM, false), gap(4));
        if (!Locator.allowed(this)) {
            Button allow = button("Allow location", false);
            allow.setTextSize(15);
            allow.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setAskedLocation();
                    requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_SITES_LOCATION);
                }
            });
            col.addView(allow, gap(6));
        } else if (pl == null || pl.ageMinutes() > 30) {
            final Button find = button("Find where the phone is now", false);
            find.setTextSize(15);
            find.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    find.setEnabled(false);
                    find.setText("Finding…");
                    Locator.refresh(MainActivity.this, prefs, main, 30_000L, new Locator.Listener() {
                        @Override
                        public void onPlace(Locator.Place p) {
                            if (p == null) toastish("No fix came: try outside, or check that location is on");
                            if (page == PAGE_SITES && screenOf(shownPhase) <= 0) showSites();
                        }
                    });
                }
            });
            col.addView(find, gap(6));
        }
        if (sites.isEmpty()) {
            col.addView(text("No sites yet.", 15, RED_DIM, false), gap(18));
            return;
        }
        if (pl != null) {
            Collections.sort(sites, new Comparator<Site>() {
                @Override
                public int compare(Site a, Site b) {
                    return Double.compare(a.distanceKm(pl.latDeg, pl.lonDeg), b.distanceKm(pl.latDeg, pl.lonDeg));
                }
            });
        }
        for (final Site s : sites) {
            LinearLayout card = card();
            card.addView(text(s.name + (s == here ? "  · here" : ""), 18, RED, true));
            String pn = s.presetId.isEmpty() ? "none, the settings stay as they are" : Presets.name(prefs, s.presetId);
            if (pn.isEmpty()) pn = "deleted, so none";
            String dist = pl != null ? String.format(Locale.US, " · %.1f km away", s.distanceKm(pl.latDeg, pl.lonDeg)) : "";
            card.addView(text("Preset: " + pn + dist, 14, RED, false), gap(4));
            for (String line : s.describe(now)) card.addView(text(line, 13, RED_DIM, false), gap(4));
            LinearLayout br = row();
            Button pb = button("Preset", false);
            Button rn = button("Rename", false);
            Button del = button("Delete", false);
            for (Button b : new Button[]{pb, rn, del}) {
                b.setTextSize(14);
                b.setPadding(dp(6), dp(8), dp(6), dp(8));
                br.addView(b, weight());
            }
            pb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickSitePreset(s);
                }
            });
            rn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    askText("Rename the site", null, s.name, new TextAnswer() {
                        @Override
                        public void answer(final String name) {
                            Sites.changeOne(prefs, s.id, new Sites.Change() {
                                @Override
                                public void apply(List<Site> one) {
                                    one.get(0).name = name;
                                }
                            });
                            showSites();
                        }
                    });
                }
            });
            del.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    confirm("Delete the " + s.name + " site and what the app learned there?", new Runnable() {
                        @Override
                        public void run() {
                            Sites.delete(prefs, s.id);
                            showSites();
                        }
                    });
                }
            });
            card.addView(br, gap(8));
            col.addView(card, gap(12));
        }
    }

    /** Saves the place as a site, with the preset in place as its own (asks for the name). */
    private void addSite(final Locator.Place pl) {
        if (pl == null) return;
        List<Site> sites = Sites.load(prefs);
        final String presetId = prefs.preset();
        String pn = Presets.name(prefs, presetId);
        String msg = "Its preset: " + (pn.isEmpty() ? "none (your own settings are in place)" : pn + ", the one in place now")
                + ". You can change it on the site's card.";
        if (pl.ageMinutes() > 30) {
            msg += String.format(Locale.US, " The place is from a fix %d minutes old: if the phone has moved since, find where it is first.",
                    Math.round(pl.ageMinutes()));
        }
        askText("Name this site", msg, sites.isEmpty() ? "Backyard" : "Site " + (sites.size() + 1), new TextAnswer() {
            @Override
            public void answer(String name) {
                Sites.add(prefs, name, pl, presetId);
                toastish("Saved the " + name + " site");
                showSites();
            }
        });
    }

    /** Picks a site's preset (or none). */
    private void pickSitePreset(final Site s) {
        final List<Preset> all = Presets.all(prefs);
        String[] names = new String[all.size() + 1];
        names[0] = "None: the settings stay as they are";
        int checked = 0;
        for (int i = 0; i < all.size(); i++) {
            names[i + 1] = all.get(i).name;
            if (all.get(i).id.equals(s.presetId)) checked = i + 1;
        }
        new AlertDialog.Builder(this).setTitle("Preset for " + s.name).setSingleChoiceItems(names, checked, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                final String id = w == 0 ? "" : all.get(w - 1).id;
                Sites.changeOne(prefs, s.id, new Sites.Change() {
                    @Override
                    public void apply(List<Site> one) {
                        one.get(0).presetId = id;
                    }
                });
                d.dismiss();
                showSites();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    /** Saves all the settings, presets and sites as a file in Documents/NightSky (off the main thread). */
    private void exportSettings() {
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                String msg;
                try {
                    String name = SettingsIO.fileName("settings", System.currentTimeMillis());
                    SettingsIO.export(app, name);
                    msg = "Saved in Documents/NightSky as " + name;
                } catch (Exception e) {
                    msg = "Not saved: " + e.getMessage();
                }
                final String m = msg;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        toastish(m);
                    }
                });
            }
        }, "export").start();
    }

    /** Asks for a settings file to load (the system's file picker). */
    private void importSettings() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_IMPORT);
        } catch (android.content.ActivityNotFoundException e) {
            toastish("This phone has no file picker");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                SettingsFile.Parsed p = null;
                String err = null;
                try {
                    p = SettingsIO.read(app, uri);
                } catch (IllegalArgumentException e) {
                    err = e.getMessage();
                } catch (Exception e) {
                    err = "The file couldn't be read: " + e.getMessage();
                }
                final SettingsFile.Parsed pp = p;
                final String ee = err;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (ee != null) new AlertDialog.Builder(MainActivity.this).setMessage(ee).setPositiveButton("OK", null).show();
                        else confirmImport(pp);
                    }
                });
            }
        }, "import").start();
    }

    /** Says what a settings file holds and loads it if Brandon says so. */
    private void confirmImport(final SettingsFile.Parsed p) {
        String msg = String.format(Locale.US, "This file%s has %d settings, %d %s of your own and %d %s%s. Load them? The settings as "
                        + "they are now are saved to a file first.",
                p.exported.isEmpty() ? "" : " (saved " + p.exported + (p.appVersion.isEmpty() ? "" : " by version " + p.appVersion) + ")",
                p.settingCount(), p.presets.size(), p.presets.size() == 1 ? "preset" : "presets", p.sites.size(),
                p.sites.size() == 1 ? "site" : "sites",
                p.skipped.isEmpty() ? "" : "; " + p.skipped.size() + " entries this version can't use will be left out");
        new AlertDialog.Builder(this).setTitle("Load settings").setMessage(msg).setPositiveButton("Load", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                final Context app = getApplicationContext();
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String m;
                        try {
                            m = SettingsIO.apply(app, p);
                        } catch (Exception e) {
                            m = "Not loaded: " + e.getMessage();
                        }
                        final String mm = m;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                new AlertDialog.Builder(MainActivity.this).setMessage(mm).setPositiveButton("OK", null).show();
                                if (page == PAGE_HOME && screenOf(shownPhase) <= 0) rebuildHome();
                            }
                        });
                    }
                }, "import").start();
            }
        }).setNegativeButton("Cancel", null).show();
    }

    private void showFailed(SessionController.Status s) {
        clearRefs();
        root.removeAllViews();
        LinearLayout col = column();
        root.addView(col, match());
        headline = text("", 24, RED, true);
        detail = text("", 16, RED_DIM, false);
        col.addView(headline);
        col.addView(detail, gap(12));
        if (s.storageShort) {
            Button clean = button("Free up space", false);
            clean.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    offerCleanup();
                }
            });
            col.addView(clean, gap(18));
        }
        Button ok = button("OK", true);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.controller().clearResult();
                showHome();
            }
        });
        col.addView(ok, gap(24));
    }

    // ------------------------------------------------------------------------------------------------
    // Sessions kept on the phone, and merging them (G1)
    // ------------------------------------------------------------------------------------------------

    /** The sessions as the sessions and merge pages show them, read off the main thread. */
    private static final class Library {
        List<SessionLibrary.Entry> all = new ArrayList<SessionLibrary.Entry>();
        final Map<String, Bitmap> thumbs = new HashMap<String, Bitmap>();
        final Map<String, List<SessionLibrary.Entry>> candidates = new HashMap<String, List<SessionLibrary.Entry>>();
        long frameBytes;

        static Library read(File root) {
            Library l = new Library();
            l.all = SessionLibrary.list(root);
            for (SessionLibrary.Entry e : l.all) {
                l.frameBytes += e.frameBytes;
                File t = new File(e.store.dir, "thumb.jpg");
                if (t.isFile()) {
                    Bitmap b = BitmapFactory.decodeFile(t.getAbsolutePath());
                    if (b != null) l.thumbs.put(e.folder(), b);
                }
                l.candidates.put(e.folder(), SessionLibrary.mergeCandidates(e, l.all));
            }
            return l;
        }

        /** The session in this folder, or null. */
        SessionLibrary.Entry get(String folder) {
            for (SessionLibrary.Entry e : all) if (e.folder().equals(folder)) return e;
            return null;
        }

        /** Sessions that could be merged with this one, less those its latest photo already has. */
        List<SessionLibrary.Entry> mergeable(String folder, Collection<String> already) {
            List<SessionLibrary.Entry> out = new ArrayList<SessionLibrary.Entry>();
            List<SessionLibrary.Entry> c = candidates.get(folder);
            if (c == null) return out;
            for (SessionLibrary.Entry e : c) if (already == null || !already.contains(e.id)) out.add(e);
            return out;
        }
    }

    /** Reads the sessions on a thread, then hands them over on the main thread unless the screen moved on. */
    private void readLibrary(final int pageNow, final LibraryReady ready) {
        final int tk = ++libraryToken;
        final File sroot = SessionController.sessionsRoot(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Library lib = Library.read(sroot);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tk != libraryToken || page != pageNow || screenOf(shownPhase) > 0) return;
                        ready.ready(lib);
                    }
                });
            }
        }, "sessions").start();
    }

    private interface LibraryReady {
        void ready(Library lib);
    }

    /** The sessions page: every session kept, newest first, to open, merge, develop, or free space. */
    private void showSessions() {
        page = PAGE_SESSIONS;
        LinearLayout col = pageColumn("Home");
        col.addView(text("Sessions", 26, RED, true));
        col.addView(text("Every session kept on the phone, newest first. While its frames are kept, a session can be merged "
                + "with other nights of the same sky. Without them, its photo still opens and its brightness can still change.",
                14, RED_DIM, false), gap(6));
        final TextView storage = text("Reading the sessions…", 13, RED_DIM, false);
        col.addView(storage, gap(10));
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        col.addView(list, gap(4));
        readLibrary(PAGE_SESSIONS, new LibraryReady() {
            @Override
            public void ready(Library lib) {
                long budget = prefs.keepFramesBytes();
                long free = freeBytes();
                storage.setText("Frames kept: " + SessionLibrary.gb(lib.frameBytes)
                        + (budget > 0 ? " of the " + SessionLibrary.gb(budget) + " set in Advanced settings" : " (keeping frames is off in Advanced settings)")
                        + (free >= 0 ? ". " + SessionLibrary.gb(free) + " free on the phone." : "."));
                int shown = 0;
                for (SessionLibrary.Entry e : lib.all) {
                    // minutes added show with the session they add to
                    if (!e.continuesFrom.isEmpty() && lib.get(e.continuesFrom) != null) continue;
                    list.addView(sessionCard(e, lib), gap(shown == 0 ? 6 : 10));
                    shown++;
                }
                if (shown == 0) list.addView(text("No sessions kept yet.", 15, RED_DIM, false), gap(12));
            }
        });
    }

    /**
     * "Night of Wed 22 Oct · 01:30": the night a session belongs to (the evening's date, as merging counts nights) and
     * when it started; the folder's id when the time isn't known.
     */
    private static String when(SessionLibrary.Entry e) {
        if (e.startMs <= 0) return e.id;
        return "Night of " + new SimpleDateFormat("EEE d MMM", Locale.US).format(new Date(e.startMs - 12 * 3600_000L)) + " \u00b7 "
                + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(e.startMs));
    }

    /** The text with a full stop at its end ("" stays ""). */
    private static String sentence(String s) {
        s = s.trim();
        return s.isEmpty() || s.endsWith(".") || s.endsWith("!") || s.endsWith("?") ? s : s + ".";
    }

    /** One session on the sessions page. */
    private LinearLayout sessionCard(final SessionLibrary.Entry e, final Library lib) {
        LinearLayout c = card();
        LinearLayout top = row();
        Bitmap thumb = lib.thumbs.get(e.folder());
        if (thumb != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(thumb);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(72));
            lp.rightMargin = dp(12);
            top.addView(iv, lp);
        }
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        top.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        List<SessionLibrary.Entry> added = SessionLibrary.continuations(e, lib.all);
        int minutes = Math.round(e.durationSec / 60f);
        for (SessionLibrary.Entry a : added) minutes += Math.round(a.durationSec / 60f);
        words.addView(text(when(e), 16, RED, true));
        words.addView(text((e.trails ? "Star trails" : "Deep sky") + (minutes > 0 ? " · " + minutes + " min" : "")
                + (added.isEmpty() ? "" : " · " + (added.size() == 1 ? "5 minutes added" : added.size() + " times 5 minutes added"))
                + (e.siteName.isEmpty() ? "" : " · " + e.siteName), 13, RED_DIM, false), gap(2));
        c.addView(top);

        Master.Meta newest = e.newestMaster();
        MergeProcessor.Result merged = newest != null ? MergeProcessor.readDescription(newest.merge) : null;
        StringBuilder sb = new StringBuilder();
        if (!e.developed()) {
            sb.append(e.units > 0 ? "Not developed yet: " + e.units + (e.units == 1 ? " frame" : " frames") + " saved."
                    : "Not developed, and no frames were saved.");
        } else {
            if (merged != null && !merged.added) sb.append("Latest photo: ").append(sentence(merged.total)).append(' ');
            else if (!e.summary.isEmpty()) sb.append(sentence(e.summary)).append(' ');
            sb.append(e.hasFrames ? "Frames kept (" + SessionLibrary.gb(e.frameBytes) + ")." : "Photo only: its frames were deleted.");
            if (e.renders.size() > 1) sb.append(" ").append(e.renders.size()).append(" versions saved.");
            if (newest == null) sb.append(" Developed before version 1.2, so it can't be opened here.");
        }
        c.addView(text(sb.toString(), 13, RED_DIM, false), gap(8));

        final List<SessionLibrary.Entry> cands = lib.mergeable(e.folder(), newest != null ? newest.sessions : null);
        LinearLayout r1 = row();
        if (newest != null) {
            Button open = smallButton("Open", true);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    jobOnSession(CaptureService.ACTION_OPEN, e.folder());
                }
            });
            r1.addView(open, weight());
        }
        if (!e.developed() && e.units > 0) {
            Button dev = smallButton("Develop", true);
            dev.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, CaptureService.class).setAction(CaptureService.ACTION_RECOVER)
                            .putExtra(CaptureService.EXTRA_DIR, e.store.dir.getAbsolutePath());
                    startForegroundService(i);
                }
            });
            r1.addView(dev, weight());
        }
        if (!cands.isEmpty()) {
            Button m = smallButton(cands.size() == 1 ? "Merge with " + SessionController.nightLabel(cands.get(0).startMs) : "Merge…", false);
            m.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    mergeBase = e.folder();
                    mergePick = null;
                    openPage(PAGE_MERGE);
                }
            });
            r1.addView(m, weight());
        }
        if (r1.getChildCount() > 0) c.addView(r1, gap(10));
        LinearLayout r2 = row();
        if (e.developed() && e.hasFrames) {
            Button df = smallButton("Delete frames", false);
            df.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    long b = e.frameBytes;
                    for (SessionLibrary.Entry a : SessionLibrary.continuations(e, lib.all)) b += a.frameBytes;
                    confirm("Delete this session's frames (" + SessionLibrary.gb(b) + ")? Its photos stay and it still opens, but it "
                            + "can't be merged or have minutes added any more.", new Runnable() {
                        @Override
                        public void run() {
                            deleteFrames(e, lib);
                            showSessions();
                        }
                    });
                }
            });
            r2.addView(df, weight());
        }
        Button del = smallButton("Delete", false);
        del.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                List<SessionLibrary.Entry> adds = SessionLibrary.continuations(e, lib.all);
                long b = e.bytes;
                for (SessionLibrary.Entry a : adds) b += a.bytes;
                confirm("Delete this session" + (adds.isEmpty() ? "" : " and the minutes added to it") + " from the phone ("
                        + SessionLibrary.gb(b) + ")? The photos saved in Pictures/NightSky stay.", new Runnable() {
                    @Override
                    public void run() {
                        for (SessionLibrary.Entry a : SessionLibrary.continuations(e, lib.all)) SessionController.delete(a.store.dir);
                        SessionController.delete(e.store.dir);
                        showSessions();
                    }
                });
            }
        });
        r2.addView(del, weight());
        c.addView(r2, gap(6));
        return c;
    }

    /** Deletes a session's frames (and those of the minutes added to it) when Brandon asks; its photos and masters stay. */
    private static void deleteFrames(SessionLibrary.Entry e, Library lib) {
        List<SessionLibrary.Entry> all = new ArrayList<SessionLibrary.Entry>(SessionLibrary.continuations(e, lib.all));
        all.add(0, e);
        for (SessionLibrary.Entry a : all) {
            long b = a.frameBytes;
            a.store.deleteBulk();
            a.store.log("frames deleted by you from the sessions page (" + SessionLibrary.gb(b) + "); the photos and masters stay");
        }
    }

    /** Opens or merges kept sessions in the capture service (the app is on screen, so a plain start is allowed). */
    private void jobOnSession(String action, String folders) {
        if (service != null) {
            SessionController.Phase p = service.controller().status().phase;
            if (screenOf(p) >= 1 && screenOf(p) <= 3) {
                toastish("A session is running: wait until it's finished");
                return;
            }
        }
        startService(new Intent(this, CaptureService.class).setAction(action).putExtra(CaptureService.EXTRA_DIR, folders));
    }

    /** The merge page: a session, the others of the same sky it can be merged with, and Merge. */
    private void showMergePage() {
        page = PAGE_MERGE;
        final LinearLayout col = pageColumn("Sessions");
        col.addView(text("Merge nights", 26, RED, true));
        col.addView(text("Sessions of the same patch of sky from the same place (centres within 5°, sites within 5 km), stacked "
                + "as one photo: more minutes, less noise. Each night is lined up on the stars and keeps its own darks and "
                + "sky; a night under a brighter or hazier sky counts for less. It takes about as long as developing them all.",
                14, RED_DIM, false), gap(6));
        final TextView wait = text("Reading the sessions…", 13, RED_DIM, false);
        col.addView(wait, gap(10));
        final String baseFolder = mergeBase;
        final String pick = mergePick;
        readLibrary(PAGE_MERGE, new LibraryReady() {
            @Override
            public void ready(Library lib) {
                col.removeView(wait);
                final SessionLibrary.Entry base = baseFolder != null ? lib.get(baseFolder) : null;
                if (base == null) {
                    col.addView(text("That session isn't on the phone any more.", 15, RED_DIM, false), gap(12));
                    return;
                }
                Master.Meta newest = base.newestMaster();
                final List<SessionLibrary.Entry> cands = lib.mergeable(base.folder(), newest != null ? newest.sessions : null);
                col.addView(label("Into"), gap(16));
                col.addView(mergeRow(base, lib, null), gap(6));
                if (cands.isEmpty()) {
                    col.addView(text("No other session of this sky is kept with its frames. Tap Repeat tomorrow on the photo's "
                            + "screen: the same stars come back to the same place 3 min 56 s earlier each night, and the app offers "
                            + "to merge the two.", 14, RED_DIM, false), gap(14));
                    return;
                }
                col.addView(label("With"), gap(18));
                final List<CheckBox> boxes = new ArrayList<CheckBox>();
                final Button go = button("", true);
                final Runnable count = new Runnable() {
                    @Override
                    public void run() {
                        int n = 1;
                        for (CheckBox b : boxes) if (b.isChecked()) n++;
                        go.setText(n < 2 ? "Pick a session to merge" : "Merge " + n + " sessions");
                        go.setEnabled(n >= 2);
                    }
                };
                for (SessionLibrary.Entry e : cands) {
                    CheckBox cb = new CheckBox(MainActivity.this);
                    cb.setButtonTintList(ColorStateList.valueOf(RED));
                    cb.setChecked(pick == null || pick.equals(e.folder()));
                    cb.setTag(e.folder());
                    cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton b, boolean on) {
                            count.run();
                        }
                    });
                    boxes.add(cb);
                    col.addView(mergeRow(e, lib, cb), gap(6));
                }
                go.setTextSize(18);
                go.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        List<String> folders = new ArrayList<String>();
                        folders.add(base.folder());
                        for (CheckBox b : boxes) if (b.isChecked()) folders.add((String) b.getTag());
                        if (folders.size() < 2) return;
                        // back on the sessions page once the merged photo is closed
                        page = PAGE_SESSIONS;
                        jobOnSession(CaptureService.ACTION_MERGE, TextUtils.join("|", folders));
                    }
                });
                count.run();
                col.addView(go, gap(18));
                col.addView(text("The merged photo is saved as a new photo; the sessions and their own photos stay as they are. "
                        + "Minutes added to a session come along with it.", 13, RED_DIM, false), gap(8));
            }
        });
    }

    /** A session on the merge page: its picture, when, how long and how far from the base; a tick box to include it. */
    private LinearLayout mergeRow(SessionLibrary.Entry e, Library lib, CheckBox box) {
        LinearLayout c = card();
        c.setPadding(dp(10), dp(8), dp(12), dp(8));
        LinearLayout r = row();
        r.setGravity(Gravity.CENTER_VERTICAL);
        if (box != null) r.addView(box);
        Bitmap thumb = lib.thumbs.get(e.folder());
        if (thumb != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(thumb);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(72), dp(54));
            lp.rightMargin = dp(10);
            r.addView(iv, lp);
        }
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        int minutes = Math.round(e.durationSec / 60f);
        for (SessionLibrary.Entry a : SessionLibrary.continuations(e, lib.all)) minutes += Math.round(a.durationSec / 60f);
        words.addView(text(when(e), 15, RED, true));
        String apart = "";
        SessionLibrary.Entry base = mergeBase != null ? lib.get(mergeBase) : null;
        if (box != null && base != null && base.centre != null && e.centre != null) {
            double d = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, base.centre[0] * e.centre[0] + base.centre[1] * e.centre[1]
                    + base.centre[2] * e.centre[2]))));
            apart = String.format(Locale.US, " · %.1f° from it", d);
        }
        words.addView(text(minutes + " min" + apart + (e.summary.isEmpty() ? "" : " · " + e.summary), 12, RED_DIM, false), gap(2));
        r.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        c.addView(r);
        if (box != null) {
            final CheckBox b = box;
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    b.setChecked(!b.isChecked());
                }
            });
        }
        return c;
    }

    /** The sessions merged into a photo: their minutes, how each was lined up and matched, and where they overlap. */
    private void addMergeSection(LinearLayout col, MergeProcessor.Result m) {
        col.addView(label(m.added ? "The session and the minutes added" : "Nights merged"), gap(18));
        if (!m.total.isEmpty()) col.addView(text(m.total, 16, RED, true), gap(6));
        Bitmap cover = coverageBitmap(m, getResources().getDisplayMetrics().widthPixels - dp(40));
        if (cover != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(cover);
            iv.setAdjustViewBounds(true);
            iv.setMaxHeight(dp(220));
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iv.setContentDescription("Coverage map");
            col.addView(iv, gap(8));
            col.addView(text(coverageWords(m), 13, RED_DIM, false), gap(4));
        }
        int k = 0;
        for (MergeProcessor.Part p : m.parts) {
            LinearLayout c = card();
            String name = p.label.isEmpty() ? "Session " + p.id : Character.toUpperCase(p.label.charAt(0)) + p.label.substring(1);
            c.addView(text(name, 15, RED, true));
            if (p.skipped != null) {
                c.addView(text("Left out: " + p.skipped, 13, RED_DIM, false), gap(2));
            } else {
                c.addView(text(String.format(Locale.US, "%d of %d frames, %s", p.framesUsed, p.framesTotal, minutesWords(p.seconds)),
                        13, RED_DIM, false), gap(2));
                if (k == 0) {
                    c.addView(text("The others are lined up on this one.", 13, RED_DIM, false), gap(2));
                } else {
                    if (p.matched > 0) {
                        c.addView(text(String.format(Locale.US, "Lined up on %d stars both share (%.1f px apart on average)", p.matched,
                                Double.isNaN(p.rms) ? 0 : p.rms), 13, RED_DIM, false), gap(2));
                    }
                    c.addView(text(p.photometryStars > 0
                            ? String.format(Locale.US, "Its starlight: %.0f%% of the first's, from %d stars", 100 * p.signal, p.photometryStars)
                            : "Too few shared stars to compare the starlight: taken as equal", 13, RED_DIM, false), gap(2));
                }
            }
            col.addView(c, gap(8));
            k++;
        }
    }

    private static String minutesWords(double seconds) {
        long m = Math.round(seconds / 60);
        return m >= 1 ? m + " min" : Math.round(seconds) + " s";
    }

    /** "All 3 nights cover 86% of the frame; the edges have fewer." */
    private static String coverageWords(MergeProcessor.Result m) {
        int merged = 0;
        for (MergeProcessor.Part p : m.parts) if (p.skipped == null) merged++;
        int all = 0;
        for (byte b : m.coverage) if ((b & 0xFF) >= Math.min(9, merged)) all++;
        int pct = (int) Math.round(100.0 * all / Math.max(1, m.coverage.length));
        String who = m.added ? "all " + merged + " parts" : m.nights > 1 && m.nights == merged ? "all " + merged + " nights" : "all " + merged + " sessions";
        return "Brighter where more of them overlap: " + who + " cover " + pct + "% of the frame"
                + (pct < 100 ? ", the edges have fewer, so they're a little noisier." : ".");
    }

    /** The coverage map, cells scaled up to about {@code targetW} pixels across: dark red for one session, brighter for more. */
    private static Bitmap coverageBitmap(MergeProcessor.Result m, int targetW) {
        if (m.coverage == null || m.coverW <= 0 || m.coverH <= 0) return null;
        int max = 1;
        for (byte b : m.coverage) max = Math.max(max, b & 0xFF);
        int s = Math.max(1, targetW / m.coverW), w = m.coverW * s, h = m.coverH * s;
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            int row = (y / s) * m.coverW;
            for (int x = 0; x < w; x++) {
                int v = m.coverage[row + x / s] & 0xFF;
                int c;
                if (v == 0) {
                    c = 0xFF0C0303;
                } else {
                    // dark red for one session up to a dim red for all: no glare at night
                    float t = max <= 1 ? 1f : (v - 1) / (float) (max - 1);
                    c = 0xFF000000 | Math.round(0x3A + t * (0xB3 - 0x3A)) << 16 | Math.round(0x0B + t * (0x33 - 0x0B)) << 8
                            | Math.round(0x09 + t * (0x2C - 0x09));
                }
                px[y * w + x] = c;
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    }

    /** A merge's details over the result screen (nights merged have no report card). */
    private void showMergeInfo(MergeProcessor.Result m) {
        if (m == null || reportOverlay != null) return;
        FrameLayout ov = new FrameLayout(this);
        ov.setBackgroundColor(BG);
        ov.setClickable(true);
        ScrollView sv = new ScrollView(this);
        LinearLayout col = column();
        sv.addView(col);
        ov.addView(sv, match());
        addMergeSection(col, m);
        Button close = button("Close", true);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeReport();
            }
        });
        col.addView(close, gap(20));
        openOverlay(ov);
    }

    /** A smaller button for rows of two or three: one line, its text shrinking to fit if it must. */
    private Button smallButton(String s, boolean primary) {
        Button b = button(s, primary);
        b.setTextSize(14);
        b.setPadding(dp(6), dp(8), dp(6), dp(8));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMaxLines(1);
        b.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, TypedValue.COMPLEX_UNIT_SP);
        return b;
    }

    /** Small buttons in rows: one row for up to three, else rows of two or three. */
    private void layoutButtons(LinearLayout grid, List<Button> buttons) {
        for (Button b : buttons) {
            if (b.getParent() instanceof ViewGroup) ((ViewGroup) b.getParent()).removeView(b);
        }
        grid.removeAllViews();
        int n = buttons.size(), per = n <= 3 ? n : n == 4 ? 2 : 3;
        for (int i = 0; i < n; i += per) {
            LinearLayout row = row();
            for (int k = i; k < Math.min(n, i + per); k++) row.addView(buttons.get(k), weight());
            grid.addView(row, gap(i == 0 ? 0 : 6));
        }
        grid.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    /**
     * The result screen's smaller buttons (G1): five more minutes of the session (for half an hour after, while its
     * frames are kept), {@code others} (tomorrow night, the cloud blur), merging it with other nights of the same sky
     * (offered once found, by the night a "Repeat tomorrow" session repeats when that's one), and the sessions page.
     */
    private void addMoreRow(LinearLayout col, final SessionController.Result r, boolean fromLibrary, List<Button> others) {
        final LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        final List<Button> buttons = new ArrayList<Button>();
        if (r != null && service != null && service.controller().canAddAfter()) {
            Button add = smallButton("Add 5 minutes", false);
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (service == null || !service.controller().canAddAfter()) {
                        toastish("Minutes can be added only within half an hour, while the frames are kept");
                        return;
                    }
                    toastish("Put the phone back as it was, screen-down: the 5 minutes are stacked with this photo");
                    startSession(0, Launch.addTo(r.session));
                }
            });
            buttons.add(add);
        }
        buttons.addAll(others);
        if (!fromLibrary) {
            Button lib = smallButton("Sessions", false);
            lib.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    page = PAGE_SESSIONS;
                    if (service != null) service.controller().clearResult();
                }
            });
            buttons.add(lib);
        }
        col.addView(grid, gap(6));
        layoutButtons(grid, buttons);
        if (r == null || r.trails || r.session.isEmpty()) return;
        // other nights of this sky, found off the main thread
        final String folder = r.session;
        final List<String> already = r.master != null ? new ArrayList<String>(r.master.sessions) : null;
        final File sroot = SessionController.sessionsRoot(this);
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<SessionLibrary.Entry> all = SessionLibrary.list(sroot);
                SessionLibrary.Entry base = null;
                for (SessionLibrary.Entry e : all) if (e.folder().equals(folder)) base = e;
                if (base == null) return;
                final List<SessionLibrary.Entry> c = new ArrayList<SessionLibrary.Entry>();
                for (SessionLibrary.Entry e : SessionLibrary.mergeCandidates(base, all)) {
                    if (already == null || !already.contains(e.id)) c.add(e);
                }
                if (c.isEmpty()) return;
                SessionLibrary.Entry p = null;
                for (SessionLibrary.Entry e : c) if (e.folder().equals(r.repeatOf)) p = e;
                final SessionLibrary.Entry pick = p;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!grid.isAttachedToWindow()) return;
                        SessionLibrary.Entry one = pick != null ? pick : c.size() == 1 ? c.get(0) : null;
                        Button mb = smallButton(one != null && one.startMs > 0 ? "Merge with " + SessionController.nightLabel(one.startMs)
                                : "Merge\u2026", false);
                        mb.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                mergeBase = folder;
                                mergePick = pick != null ? pick.folder() : null;
                                page = PAGE_MERGE;
                                if (service != null) service.controller().clearResult();
                            }
                        });
                        // before the sessions button, after the rest
                        int at = buttons.size() > 0 && !buttons.get(buttons.size() - 1).getText().toString().equals("Sessions")
                                ? buttons.size() : Math.max(0, buttons.size() - 1);
                        buttons.add(at, mb);
                        layoutButtons(grid, buttons);
                    }
                });
            }
        }, "mergeable").start();
    }

    // ------------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------------

    /**
     * Lists the sessions whose frames are on the phone, never developed or kept after developing, with their size,
     * and deletes those frames only if Brandon says yes (or opens the sessions page to choose).
     */
    private void offerCleanup() {
        SessionController.Phase ph = service != null ? service.controller().status().phase : null;
        if (ph != null && ph != SessionController.Phase.IDLE && ph != SessionController.Phase.DONE && ph != SessionController.Phase.FAILED) {
            toastish("Wait until the session is finished");
            return;
        }
        final List<File> dirs = SessionController.unfinished(this);
        final List<SessionLibrary.Entry> kept = new ArrayList<SessionLibrary.Entry>();
        for (SessionLibrary.Entry e : SessionLibrary.list(SessionController.sessionsRoot(this))) {
            if (e.developed() && e.hasFrames) kept.add(e);
        }
        if (dirs.isEmpty() && kept.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("No session frames are left on the phone. To free space, "
                    + "delete photos or videos you no longer need, or use Files > Clean.").setPositiveButton("OK", null).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        long total = 0;
        if (!dirs.isEmpty()) {
            sb.append("Never developed, so their frames are still on the phone:\n");
            for (File d : dirs) {
                long b = SessionController.sizeOf(d);
                total += b;
                sb.append("• ").append(sessionName(d)).append(": ").append(SessionController.unitCount(d)).append(" frames, ")
                        .append(SessionLibrary.gb(b)).append('\n');
            }
        }
        if (!kept.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append("Developed, with their frames kept for merging and adding minutes:\n");
            for (SessionLibrary.Entry e : kept) {
                total += e.frameBytes;
                sb.append("• ").append(when(e)).append(": ").append(SessionLibrary.gb(e.frameBytes)).append('\n');
            }
        }
        sb.append("\nDelete all these frames to free ").append(SessionLibrary.gb(total)).append('?')
                .append(dirs.isEmpty() ? "" : " Sessions never developed can't be developed afterwards.")
                .append(kept.isEmpty() ? "" : " Developed sessions keep their photos.");
        new AlertDialog.Builder(this).setTitle("Free up space").setMessage(sb.toString())
                .setPositiveButton("Delete the frames", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        for (File d : dirs) SessionController.delete(d);
                        for (SessionLibrary.Entry e : kept) {
                            long b = e.frameBytes;
                            e.store.deleteBulk();
                            e.store.log("frames deleted by you to free space (" + SessionLibrary.gb(b) + "); the photos and masters stay");
                        }
                        toastish(SessionLibrary.gb(Math.max(0, freeBytes())) + " free now");
                        SessionController.Phase p = service != null ? service.controller().status().phase : null;
                        if (p == null || p == SessionController.Phase.IDLE) showHome();
                    }
                })
                .setNeutralButton("Choose…", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        SessionController.Phase p = service != null ? service.controller().status().phase : null;
                        if (p == null || p == SessionController.Phase.IDLE) openPage(PAGE_SESSIONS);
                    }
                })
                .setNegativeButton("Keep them", null).show();
    }

    /** "15 Oct, 21:30" from a session folder's name. */
    private static String sessionName(File dir) {
        String n = dir.getName();
        String id = n.startsWith("session_") ? n.substring(8) : n;
        if (id.length() > 15) id = id.substring(0, 15);
        try {
            Date d = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(id);
            return new SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(d);
        } catch (ParseException e) {
            return n;
        }
    }

    private long freeBytes() {
        try {
            return new android.os.StatFs(SessionController.sessionsRoot(this).getAbsolutePath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }

    /** About what a session of this length needs before the camera is known: 25 MB frames of 6 s, and room to develop. */
    private static long roughNeedBytes(int durationSec) {
        return 25_000_000L * (durationSec / 6 + 2) + (400L << 20);
    }

    private void view(Uri uri) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (RuntimeException e) {
            toastish("No app can open the photo");
        }
    }

    private void confirm(String msg, final Runnable yes) {
        new AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface d, int w) {
                yes.run();
            }
        }).setNegativeButton("No", null).show();
    }

    private void toastish(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(16), dp(20), dp(20));
        return l;
    }

    private LinearLayout row() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout card() {
        LinearLayout l = column();
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(RED_DARK);
        g.setStroke(dp(1), RED_DIM);
        g.setCornerRadius(dp(12));
        l.setBackground(g);
        return l;
    }

    private View spacer() {
        return new View(this);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private LinearLayout.LayoutParams gap(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        return lp;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(3);
        lp.rightMargin = dp(3);
        return lp;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView label(String s) {
        TextView t = text(s.toUpperCase(Locale.US), 12, RED_DIM, true);
        t.setLetterSpacing(0.12f);
        return t;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{RED_SEL, primary ? Color.BLACK : RED}));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        if (primary) {
            g.setColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_pressed}, {}},
                    new int[]{RED_DARK, 0xFFB71C1C, RED}));
        } else {
            g.setColor(RED_DARK);
            g.setStroke(dp(1), RED_DIM);
        }
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        return b;
    }

    private Button chip(String s, boolean selected) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setStateListAnimator(null);
        b.setPadding(dp(4), dp(10), dp(4), dp(10));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        styleChip(b, selected);
        return b;
    }

    private void styleChip(Button b, boolean selected) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(selected ? RED_SEL : BG);
        g.setStroke(dp(selected ? 2 : 1), selected ? RED : RED_DIM);
        b.setBackground(g);
        b.setTextColor(selected ? 0xFFFFCDD2 : RED_DIM);
        b.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private SeekBar seek(int value) {
        SeekBar s = new SeekBar(this);
        s.setMax(100);
        s.setProgress(value);
        s.setProgressTintList(ColorStateList.valueOf(RED));
        s.setThumbTintList(ColorStateList.valueOf(RED));
        s.setProgressBackgroundTintList(ColorStateList.valueOf(RED_DIM));
        s.setPadding(dp(8), dp(12), dp(8), dp(12));
        return s;
    }

    private CheckBox check(String s, boolean on) {
        CheckBox c = new CheckBox(this);
        c.setText(s);
        c.setTextColor(RED);
        c.setTextSize(15);
        c.setChecked(on);
        c.setButtonTintList(ColorStateList.valueOf(RED));
        return c;
    }

    private ProgressBar progress() {
        ProgressBar p = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        p.setMax(1000);
        p.setProgressTintList(ColorStateList.valueOf(RED));
        p.setIndeterminateTintList(ColorStateList.valueOf(RED));
        p.setProgressBackgroundTintList(ColorStateList.valueOf(RED_DARK));
        return p;
    }

    @SuppressWarnings("unused")
    private static boolean empty(String s) {
        return TextUtils.isEmpty(s);
    }
}
