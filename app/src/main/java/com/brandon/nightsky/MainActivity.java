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
import android.graphics.Color;
import android.graphics.Insets;
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
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.Preflight;
import com.brandon.nightsky.processing.Rejection;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatPlanner;
import com.brandon.nightsky.processing.SatSky;
import com.brandon.nightsky.processing.Tonight;
import com.brandon.nightsky.remote.QrCode;

import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

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

    // the idle screen's pages: home, tonight's plan, a session being added, last night's summary
    private static final int PAGE_HOME = 0, PAGE_PLAN = 1, PAGE_EDIT = 2, PAGE_SUMMARY = 3;
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
    /** The pages' back-gesture handler (API 33+). */
    private Object pageBackCb;

    // views of the current screen (whichever exist)
    private TextView headline, detail, stage;
    /** The pre-flight check's findings, on the arming screen. */
    private TextView checks;
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

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((CaptureService.LocalBinder) b).service();
            shownVersion = -1;
            shownPhase = null;
            refresh();
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
    }

    @Override
    protected void onPause() {
        super.onPause();
        visible = false;
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
                case 3: showDeveloping(); break;
                case 4: showResult(s); break;
                default: showFailed(s); break;
            }
        }
        shownPhase = p;
        if (headline != null) headline.setText(s.headline);
        if (detail != null) detail.setText(s.detail);
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
        }
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
        shownChecks = null;
        bar = null;
        live = null;
        zoom = null;
        resultBrightness = null;
        saveVersion = null;
        shownPreview = -1;
        viewerBox = null;
        shownViewer = null;
        satBox = null;
        setQrBright(false);
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
                }
            });
            (k < 3 ? r1 : r2).addView(b, weight());
        }
        box.addView(r1, gap(8));
        box.addView(r2, gap(6));
        box.addView(help, gap(6));

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
            }
        };
        out.setOnClickListener(keepClick);
        keep.setOnClickListener(keepClick);
        LinearLayout kr = row();
        kr.addView(out, weight());
        kr.addView(keep, weight());
        box.addView(kr, gap(6));
        box.addView(keepHelp, gap(6));
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

    /** Starts a session; {@code startAtMs}: the capture begins no earlier than this (0: once the phone is ready). */
    private void startSession(long startAtMs) {
        pendingStartAt = startAtMs;
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
        Intent i = new Intent(this, CaptureService.class).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_TRAILS, prefs.trails())
                .putExtra(CaptureService.EXTRA_DURATION, prefs.durationMin() * 60)
                .putExtra(CaptureService.EXTRA_BRIGHTNESS, prefs.brightness())
                .putExtra(CaptureService.EXTRA_SOUNDS, prefs.sounds())
                .putExtra(CaptureService.EXTRA_DNG, prefs.saveDng())
                .putExtra(CaptureService.EXTRA_PREFLIGHT, prefs.preflight())
                .putExtra(CaptureService.EXTRA_VIEWER, prefs.remoteViewer())
                .putExtra(CaptureService.EXTRA_KEEP_SATELLITES, prefs.keepSatellites())
                .putExtra(CaptureService.EXTRA_START_AT, startAtMs);
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
            startSession(pendingStartAt);
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

    /** Back from a page: the editor goes back to the plan, the rest to the home screen. */
    private boolean pageBack() {
        if (page == PAGE_HOME) return false;
        openPage(page == PAGE_EDIT ? PAGE_PLAN : PAGE_HOME);
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
                        showReport(rc);
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
        col.addView(spacer(), new LinearLayout.LayoutParams(1, 0, 1f));
        col.addView(headline);
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

    private void showDeveloping() {
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
        col.addView(text("You can pick the phone up now. This takes a minute or two.", 13, RED_DIM, false), gap(8));
        Button cancel = button("Cancel (keeps the frames)", false);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.controller().cancel();
            }
        });
        col.addView(cancel, gap(10));
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
                    showReport(rc);
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
        Button again = button("New photo", false);
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (service != null) service.controller().clearResult();
            }
        });
        row2.addView(open, weight());
        row2.addView(again, weight());
        if (r != null && r.startedMs > 0 && !s.scheduled) {
            // the same stars at the same height tomorrow night: 3 min 56 s earlier by the clock
            final Button rep = button("Repeat tomorrow", false);
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
            row2.addView(rep, weight());
        }
        col.addView(row2, gap(6));
        String where = "Saved in Pictures/NightSky" + (r != null && r.dng != null ? " (JPEG and DNG" : " (JPEG")
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
    // Session report
    // ------------------------------------------------------------------------------------------------

    /** The report card over the result screen, until Close or Back. */
    private void showReport(ReportCard rc) {
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
        root.addView(ov, match());
        reportOverlay = ov;
        if (Build.VERSION.SDK_INT >= 33) {
            OnBackInvokedCallback cb = new OnBackInvokedCallback() {
                @Override
                public void onBackInvoked() {
                    closeReport();
                }
            };
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
            reportBack = cb;
        }
    }

    private void closeReport() {
        if (reportOverlay != null) {
            root.removeView(reportOverlay);
            reportOverlay = null;
        }
        if (reportBack != null && Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) reportBack);
        }
        reportBack = null;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // before Android 13 (from 13 on, the callbacks take Back)
        if (reportOverlay != null) {
            closeReport();
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
    // Helpers
    // ------------------------------------------------------------------------------------------------

    /**
     * Lists the sessions whose frames are still on the phone (never developed) with their size, and deletes
     * those frames only if the person says yes.
     */
    private void offerCleanup() {
        SessionController.Phase ph = service != null ? service.controller().status().phase : null;
        if (ph != null && ph != SessionController.Phase.IDLE && ph != SessionController.Phase.DONE && ph != SessionController.Phase.FAILED) {
            toastish("Wait until the session is finished");
            return;
        }
        final List<File> dirs = SessionController.unfinished(this);
        if (dirs.isEmpty()) {
            new AlertDialog.Builder(this).setMessage("No old session frames are left on the phone. To free space, "
                    + "delete photos or videos you no longer need, or use Files > Clean.").setPositiveButton("OK", null).show();
            return;
        }
        StringBuilder sb = new StringBuilder("These sessions were never developed, so their frames are still on the phone:\n\n");
        long total = 0;
        for (File d : dirs) {
            long b = SessionController.sizeOf(d);
            total += b;
            sb.append("• ").append(sessionName(d)).append(": ").append(SessionController.unitCount(d)).append(" frames, ")
                    .append(String.format(Locale.US, "%.1f GB", b / 1e9)).append('\n');
        }
        sb.append(String.format(Locale.US, "\nDelete their frames to free %.1f GB? They can't be developed afterwards.", total / 1e9));
        new AlertDialog.Builder(this).setTitle("Free up space").setMessage(sb.toString())
                .setPositiveButton("Delete the frames", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface di, int w) {
                        for (File d : dirs) SessionController.delete(d);
                        toastish(String.format(Locale.US, "%.1f GB free now", freeBytes() / 1e9));
                        SessionController.Phase p = service != null ? service.controller().status().phase : null;
                        if (p == null || p == SessionController.Phase.IDLE) showHome();
                    }
                }).setNegativeButton("Keep them", null).show();
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
