package com.brandon.nightsky;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.SizeF;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.Figures;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SkyPointer;
import com.brandon.nightsky.processing.SkyWindow;
import com.brandon.nightsky.processing.StarLock;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Sky Window (3.3): hold the phone up and the screen is a window on the sky. The main camera's picture, with
 * everything in it named and drawn where it is: the stars and the constellations' figures, the planets, the Moon in its
 * phase, the galaxies and clusters, the black holes nobody can see, the spacecraft leaving the Solar System, the radiant
 * of a meteor shower, the space stations as they fly over. Point it at the ground and the sky on the other side of the
 * Earth shows through it. Tap anything for its story, when it rises and sets, how far its light has come.
 * <p>
 * Where the phone points comes from its rotation sensors ({@link SkyWindow}): the gyro, gravity and the compass. The
 * compass is the weak one (a car, a railing, the phone's own magnets pull it off by degrees), so at night, whenever the
 * phone is held still, the camera's own frame is plate-solved ({@link SkyCamera}, {@link StarLock}) and the labels move
 * onto the stars: from then the gyro carries the lock, and the compass no longer moves it. By night the picture is the
 * camera's RAW frame stretched to show the stars an eighth of a second catches, put on the screen where the phone was
 * while it was exposed, so it never lags behind the drawing.
 * <p>
 * Find steers the phone to anything (an arrow, then a ring, then a buzz); the Moon and Jupiter, once in the 5x camera's
 * frame, go straight on to the Moonshot or Jupiter's moons. Time travel slides the sky twelve hours either way; red keeps
 * your eyes used to the dark.
 */
public final class SkyWindowActivity extends Activity implements SkyCamera.Listener, SkyCamera.Pose, SensorEventListener {
    /** What to find when opened ("the Moon", "Jupiter"), and the screen it was opened from ("moon", "jupiter"). */
    static final String EXTRA_FIND = "find", EXTRA_FROM = "from";
    static final int GOLD = 0xFFFFD54F, WHITE = 0xFFFFFFFF, GREY = 0xFFB0BEC5, DIM = 0xFF78909C, CARD = 0xF0101418, LINE = 0xFF37474F,
            GREEN = 0xFF69F0AE, FIGURE = 0xFF90CAF9, NORTH = 0xFFFF8A65;
    private static final int REQ_PERMS = 33;
    /** On it: within this of the middle (degrees); for the 5x, well inside its frame. */
    static final double GOT_DEG = 2.5;
    /** How often the guide's ring and arrow pulse when nothing else redraws the window (ms). */
    static final long PULSE_MS = 40;
    static final float MIN_ZOOM = 0.5f, MAX_ZOOM = 8f;
    /** The picture's mesh: how finely the lens's distortion is followed. */
    static final int MESH_W = 16, MESH_H = 12;
    private static final double[] I3 = {1, 0, 0, 0, 1, 0, 0, 0, 1};

    private Prefs prefs;
    private SharedPreferences opts;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread work;
    private Handler wh;

    // the sky
    Locator.Place place;
    double decl;
    volatile SkyWindow.Catalogue catalogue;
    volatile SkyWindow.Sky sky;
    volatile List<SkyWindow.Item> sats = new ArrayList<SkyWindow.Item>();
    volatile long satsMs;
    volatile List<SatCatalog.Sat> brightSats;
    volatile List<SkyWindow.Pass> passes = new ArrayList<SkyWindow.Pass>();
    /** The 5x camera's field (degrees: across the screen and up it), NaN unknown. */
    volatile double teleW = Double.NaN, teleH = Double.NaN;

    // where the phone points
    private SensorManager sm;
    private Sensor gameSensor, rvSensor;
    volatile SkyWindow.Readings readings = new SkyWindow.Readings();
    private final float[] rvM = new float[9], gM = new float[9];
    volatile boolean haveRv, gameMode;
    /** The compass's offset from the gyro's own north (degrees), and whether a lock has fixed it. */
    volatile double yawDeg = Double.NaN;
    volatile boolean yawFrozen;
    /** The lock's correction (east-north-up), the lock, when, and the tries. */
    volatile double[] C = I3;
    StarLock.Fix fix;
    long fixMs;
    int lockTries, lockFails;

    // the camera
    SkyCamera camera;
    TextureView texture;
    volatile SkyCamera.Frame picture;
    SkyCamera.Frame lastFrame;
    CameraModel camModel;
    int rawW, rawH;
    volatile int sensorOrientation = 90;
    boolean cameraFailed;
    int cameraRetries;
    String cameraWhy = "";

    // the screen
    FrameLayout root, overlay;
    WindowView window;
    TextView pill, caption, sub, tipView, timeLabel;
    boolean tipGoing;
    /** The bars at the top and the bottom, whose space the sky's labels leave clear. */
    LinearLayout topBar, bottomBar;
    Button bFind, bTime, bCam, bXray, bRed, bShoot;
    LinearLayout timeBox, cardBox, findBox, findList;
    ScrollView findScroll;
    TextView cardTitle, cardLine, cardAbout, cardStory, cardRise;
    Button cardFind, cardShoot;
    SeekBar slider;

    // what's chosen
    float zoom = 1f;
    boolean camOn = true, red, xray;
    volatile long offsetMs;
    SkyWindow.Item target, selected;
    int selectedStar = -1;
    String from = "", findName = "";
    boolean gotTarget, askedPerms;
    long gotAtMs;
    /** What the caption shows, so it changes only when it must. */
    String captionText = "", subText = "";
    long captionMs;
    /** The window as last drawn (for taps and the robots). */
    volatile SkyWindow.View lastView;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        opts = getSharedPreferences("sky_window", MODE_PRIVATE);
        red = opts.getBoolean("red", false);
        xray = opts.getBoolean("xray", false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        Intent in = getIntent();
        if (in != null) {
            String f = in.getStringExtra(EXTRA_FIND);
            findName = f != null ? f : "";
            String fr = in.getStringExtra(EXTRA_FROM);
            from = fr != null ? fr : "";
        }
        place = Locator.best(this, prefs);
        decl = Locator.declination(place);
        work = new HandlerThread("sky-window");
        work.start();
        wh = new Handler(work.getLooper());
        build();
        loadSky();
    }

    // ------------------------------------------------------------------------------------------------
    // the screen

    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);
        texture = new TextureView(this);
        root.addView(texture, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));
        window = new WindowView(this);
        root.addView(window, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        window.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || bt - t != ob - ot) layoutTexture();
            }
        });
        overlay = new FrameLayout(this);
        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                Insets i = in.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
                return WindowInsets.CONSUMED;
            }
        });

        // the top: done, and how sure the labels are
        LinearLayout top = topBar = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(10), dp(8), dp(10), dp(4));
        Button done = chip("Done", false);
        done.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(done, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        pill = text("", 13, GREY, false);
        pill.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable pg = new GradientDrawable();
        pg.setColor(0xB0000000);
        pg.setCornerRadius(dp(16));
        pill.setBackground(pg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        plp.leftMargin = dp(8);
        top.addView(pill, plp);
        overlay.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // the first-time tip, in the middle
        tipView = text("Hold the phone up like a window on the sky.\nTap anything for its story. Pinch to zoom.", 16, WHITE, true);
        tipView.setGravity(Gravity.CENTER);
        tipView.setPadding(dp(16), dp(12), dp(16), dp(12));
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(0xC0000000);
        tg.setCornerRadius(dp(14));
        tipView.setBackground(tg);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        tlp.topMargin = dp(120);
        overlay.addView(tipView, tlp);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                hideTip();
            }
        }, 6500);

        // the bottom: what's in the middle, the 5x, the time slider, the card, the chips
        LinearLayout bottom = bottomBar = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setPadding(dp(12), dp(4), dp(12), dp(10));
        caption = text("", 20, WHITE, true);
        caption.setGravity(Gravity.CENTER_HORIZONTAL);
        sub = text("", 14, GREY, false);
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.addView(caption, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bottom.addView(sub, gap(2));
        bShoot = button("Shoot it with the 5x", true);
        bShoot.setVisibility(View.GONE);
        bShoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                SkyWindow.Item t = target != null ? target : selected;
                if (t != null) shoot(t);
            }
        });
        bottom.addView(bShoot, gap(8));

        timeBox = card();
        timeLabel = text("Now", 15, GOLD, true);
        timeBox.addView(timeLabel);
        LinearLayout trow = new LinearLayout(this);
        trow.setOrientation(LinearLayout.HORIZONTAL);
        trow.setGravity(Gravity.CENTER_VERTICAL);
        slider = new SeekBar(this);
        slider.setMax(1440);
        slider.setProgress(720);
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                setOffset((p - 720) * 60_000L);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {}

            @Override
            public void onStopTrackingTouch(SeekBar s) {}
        });
        trow.addView(slider, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button now = chip("Now", false);
        now.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                slider.setProgress(720);
                setOffset(0);
            }
        });
        trow.addView(now);
        timeBox.addView(trow, gap(4));
        timeBox.setVisibility(View.GONE);
        bottom.addView(timeBox, gap(8));

        cardBox = card();
        cardTitle = text("", 20, GOLD, true);
        cardLine = text("", 14, GREY, false);
        cardAbout = text("", 15, WHITE, false);
        cardStory = text("", 15, WHITE, false);
        cardRise = text("", 14, GREY, false);
        cardBox.addView(cardTitle);
        cardBox.addView(cardLine, gap(2));
        cardBox.addView(cardAbout, gap(8));
        cardBox.addView(cardStory, gap(6));
        cardBox.addView(cardRise, gap(6));
        LinearLayout crow = new LinearLayout(this);
        crow.setOrientation(LinearLayout.HORIZONTAL);
        cardFind = button("Find it", false);
        cardShoot = button("Shoot it with the 5x", true);
        Button cardClose = button("Close", false);
        crow.addView(cardFind, weight());
        crow.addView(cardShoot, weight());
        crow.addView(cardClose, weight());
        cardBox.addView(crow, gap(10));
        cardFind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected != null) setTarget(selected);
                closeCard();
            }
        });
        cardShoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (selected != null) shoot(selected);
            }
        });
        cardClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeCard();
            }
        });
        cardBox.setVisibility(View.GONE);
        bottom.addView(cardBox, gap(8));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bFind = chip("Find", false);
        bTime = chip("Time", false);
        bCam = chip("Camera", true);
        bXray = chip("X-ray", xray);
        bRed = chip("Red", red);
        bar.addView(bFind, weight());
        bar.addView(bTime, weight());
        bar.addView(bCam, weight());
        bar.addView(bXray, weight());
        bar.addView(bRed, weight());
        bottom.addView(bar, gap(10));
        bFind.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (findBox.getVisibility() == View.VISIBLE) findBox.setVisibility(View.GONE);
                else showFind();
            }
        });
        bTime.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean show = timeBox.getVisibility() != View.VISIBLE;
                timeBox.setVisibility(show ? View.VISIBLE : View.GONE);
                chipStyle(bTime, show || offsetMs != 0);
            }
        });
        bCam.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setCamera(!camOn);
            }
        });
        bXray.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                xray = !xray;
                opts.edit().putBoolean("xray", xray).apply();
                chipStyle(bXray, xray);
                toast(xray ? "X-ray: the pulsars, the exploded stars, the stars with planets and the messages we've sent, all marked"
                        : "X-ray off");
                placeSky();
            }
        });
        bRed.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                red = !red;
                opts.edit().putBoolean("red", red).apply();
                chipStyle(bRed, red);
                applyRed();
            }
        });
        overlay.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Find's list, over the top half
        findBox = card();
        LinearLayout fhead = new LinearLayout(this);
        fhead.setOrientation(LinearLayout.HORIZONTAL);
        fhead.setGravity(Gravity.CENTER_VERTICAL);
        TextView ft = text("Find", 20, GOLD, true);
        fhead.addView(ft, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button fclose = chip("Close", false);
        fclose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                findBox.setVisibility(View.GONE);
            }
        });
        fhead.addView(fclose);
        findBox.addView(fhead);
        findScroll = new ScrollView(this);
        findList = new LinearLayout(this);
        findList.setOrientation(LinearLayout.VERTICAL);
        findScroll.addView(findList);
        findBox.addView(findScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        findBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        flp.setMargins(dp(12), dp(64), dp(12), dp(150));
        overlay.addView(findBox, flp);

        applyRed();
        hideBars();
        updatePill();
    }

    /** The status and navigation bars out of the way: a window on the sky has no frame. A swipe brings them back. */
    private void hideBars() {
        try {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } catch (RuntimeException ignored) {
            // a window without a controller yet: the bars stay
        }
    }

    /** Red at night: everything on the screen in red, and dimmer, so your eyes stay used to the dark. */
    private void applyRed() {
        if (red) {
            Paint p = new Paint();
            p.setColorFilter(new ColorMatrixColorFilter(new float[]{
                    0.30f, 0.59f, 0.11f, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0,
                    0, 0, 0, 1, 0}));
            root.setLayerType(View.LAYER_TYPE_HARDWARE, p);
        } else {
            root.setLayerType(View.LAYER_TYPE_NONE, null);
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = red ? 0.15f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    /** The camera's picture sized to fill the screen (cropped at the sides), and zoomed with the drawing. */
    void layoutTexture() {
        int w = root.getWidth(), h = root.getHeight();
        if (w <= 0 || h <= 0 || rawW <= 0 || rawH <= 0) return;
        double k = scale(w, h);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) Math.round(rawH * k), (int) Math.round(rawW * k), Gravity.CENTER);
        texture.setLayoutParams(lp);
        texture.setScaleX(zoom);
        texture.setScaleY(zoom);
    }

    /** Screen pixels a RAW pixel spans at 1x: the camera's picture (upright, its long side up the screen) covering the screen. */
    private double scale(int w, int h) {
        return Math.max((double) h / rawW, (double) w / rawH);
    }

    /** The window's focal length (screen pixels) at 1x: the camera's, as the picture fills the screen; else a 66° view. */
    double f1() {
        int w = window.getWidth(), h = window.getHeight();
        if (camModel != null && rawW > 0 && rawH > 0) return camModel.fx * scale(w, h);
        return (h / 2.0) / Math.tan(Math.toRadians(33));
    }

    // ------------------------------------------------------------------------------------------------
    // the sky (worker thread)

    /** The stars, the sky now, the satellites and the 5x camera's field, on the worker. */
    private void loadSky() {
        final Context ctx = getApplicationContext();
        wh.post(new Runnable() {
            @Override
            public void run() {
                BrightStars stars = SkyCatalog.get(ctx);
                catalogue = new SkyWindow.Catalogue(stars);
                Locator.Place p = place;
                SkyCamera cam = camera;
                if (cam != null && p != null) cam.setSky(stars, p.latDeg, p.lonDeg);
                placeSkyNow();
                tele(ctx);
                satellitesNow(ctx);
            }
        });
    }

    /** The 5x camera's field of view, for its frame on the window. */
    private void tele(Context ctx) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            MoonCamera.Lens l = cm != null ? MoonCamera.choose(cm) : null;
            if (l == null || l.eqMm < 60) return;
            SizeF phys = l.cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            double f = l.focalMm;
            if (l.zoomed) {
                // the main camera zoomed: its own field, five times narrower
                f = l.focalMm * 5;
            }
            if (phys == null || f <= 0) return;
            double longMm = Math.max(phys.getWidth(), phys.getHeight()), shortMm = Math.min(phys.getWidth(), phys.getHeight());
            teleW = 2 * Math.toDegrees(Math.atan(shortMm / 2 / f));
            teleH = 2 * Math.toDegrees(Math.atan(longMm / 2 / f));
        } catch (Exception | LinkageError e) {
            android.util.Log.w("NightSky", "sky window: no 5x field", e);
        }
    }

    /** The satellites' catalogue (cached; read here), the bright ones to place every second, and the stations' passes. */
    private void satellitesNow(Context ctx) {
        Locator.Place p = place;
        if (p == null) return;
        try {
            SatCatalog cat = Satellites.catalogNow(ctx);
            if (cat == null) return;
            long now = System.currentTimeMillis();
            brightSats = SkyWindow.brightSats(cat.all(), Astro.jdUtc(now));
            passes = SkyWindow.stationPasses(cat.all(), p.latDeg, p.lonDeg, now, 24);
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (findBox.getVisibility() == View.VISIBLE) showFind();
                }
            });
        } catch (RuntimeException | OutOfMemoryError e) {
            android.util.Log.w("NightSky", "sky window satellites", e);
        }
    }

    /** Places the sky again, at the window's time (any thread). */
    void placeSky() {
        wh.removeCallbacks(placeTask);
        wh.post(placeTask);
    }

    private final Runnable placeTask = new Runnable() {
        @Override
        public void run() {
            placeSkyNow();
        }
    };

    private void placeSkyNow() {
        Locator.Place p = place;
        SkyWindow.Catalogue c = catalogue;
        if (p == null || c == null) return;
        try {
            final SkyWindow.Sky s = SkyWindow.sky(c, System.currentTimeMillis() + offsetMs, p.latDeg, p.lonDeg, xray);
            main.post(new Runnable() {
                @Override
                public void run() {
                    adopt(s);
                }
            });
        } catch (RuntimeException e) {
            android.util.Log.w("NightSky", "sky window sky", e);
        }
    }

    /** A new placing of the sky (main thread): the target and the card carried over by name. */
    private void adopt(SkyWindow.Sky s) {
        sky = s;
        if (!findName.isEmpty()) {
            SkyWindow.Item t = findItem(s, findName);
            if (t != null) {
                findName = "";
                setTarget(t);
            }
        }
        if (target != null && target.kind != SkyWindow.Kind.SAT && target.dir == null) {
            SkyWindow.Item t = s.find(target.name);
            if (t != null) target = t;
        }
        if (selected != null && selected.kind != SkyWindow.Kind.SAT) {
            SkyWindow.Item t = s.find(selected.name);
            if (t != null) selected = t;
        }
        updatePill();
        window.invalidate();
    }

    static SkyWindow.Item findItem(SkyWindow.Sky s, String name) {
        if (s == null || name == null) return null;
        for (SkyWindow.Item t : s.items) if (t.name.equalsIgnoreCase(name)) return t;
        String n = name.toLowerCase(Locale.US).replace("the ", "");
        for (SkyWindow.Item t : s.items) if (t.name.toLowerCase(Locale.US).replace("the ", "").equals(n)) return t;
        return null;
    }

    /** Every second: the satellites where they are. Every 20 seconds: the sky placed again. */
    private final Runnable satTick = new Runnable() {
        @Override
        public void run() {
            Locator.Place p = place;
            List<SatCatalog.Sat> b = brightSats;
            if (p != null && b != null && offsetMs == 0) {
                try {
                    long now = System.currentTimeMillis();
                    List<SkyWindow.Item> s = SkyWindow.satellites(b, p.latDeg, p.lonDeg, now);
                    sats = s;
                    satsMs = now;
                } catch (RuntimeException e) {
                    android.util.Log.w("NightSky", "sky window satellites", e);
                }
            } else if (offsetMs != 0) {
                sats = new ArrayList<SkyWindow.Item>();
            }
            wh.postDelayed(this, 1000);
        }
    };

    private final Runnable skyTick = new Runnable() {
        @Override
        public void run() {
            placeSkyNow();
            wh.postDelayed(this, 20_000);
        }
    };

    // ------------------------------------------------------------------------------------------------
    // the lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        // the gyro's own north may start afresh: a new lock is needed
        readings = new SkyWindow.Readings();
        yawDeg = Double.NaN;
        yawFrozen = false;
        C = I3;
        fix = null;
        lockFails = 0;
        cameraRetries = 0;
        startSensors();
        boolean camOk = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        if ((!camOk || !Locator.allowed(this)) && !askedPerms) {
            askedPerms = true;
            List<String> want = new ArrayList<String>();
            if (!camOk) want.add(Manifest.permission.CAMERA);
            if (!Locator.allowed(this)) want.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            requestPermissions(want.toArray(new String[0]), REQ_PERMS);
        }
        if (camOk && camOn) startCamera();
        freshPlace();
        wh.removeCallbacks(satTick);
        wh.removeCallbacks(skyTick);
        wh.postDelayed(satTick, 300);
        wh.postDelayed(skyTick, 20_000);
        updatePill();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code != REQ_PERMS) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            if (camOn) startCamera();
        } else {
            cameraWhy = "No camera: the drawn sky only (allow the camera in Settings > Apps > Night Sky)";
        }
        freshPlace();
        updatePill();
    }

    private boolean askedFix;

    /** A fresh fix: the sky again if the place moved. */
    private void freshPlace() {
        if (askedFix || !Locator.allowed(this)) return;
        askedFix = true;
        Locator.refresh(this, prefs, main, 20_000L, new Locator.Listener() {
            @Override
            public void onPlace(Locator.Place p) {
                if (p == null || isFinishing()) return;
                Locator.Place old = place;
                place = p;
                decl = Locator.declination(p);
                SkyWindow.Catalogue c = catalogue;
                if (camera != null && c != null) camera.setSky(c.stars, p.latDeg, p.lonDeg);
                double moved = old == null ? 1e9 : 111.2 * Math.hypot(p.latDeg - old.latDeg, (p.lonDeg - old.lonDeg) * Math.cos(Math.toRadians(p.latDeg)));
                if (moved > 1) {
                    placeSky();
                    wh.post(new Runnable() {
                        @Override
                        public void run() {
                            satellitesNow(getApplicationContext());
                        }
                    });
                }
                updatePill();
            }
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSensors();
        closeCamera();
        wh.removeCallbacks(satTick);
        wh.removeCallbacks(skyTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (work != null) work.quitSafely();
    }

    // ------------------------------------------------------------------------------------------------
    // the sensors

    private void startSensors() {
        sm = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sm == null) return;
        gameSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        rvSensor = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        gameMode = gameSensor != null;
        haveRv = false;
        if (rvSensor != null) sm.registerListener(this, rvSensor, SensorManager.SENSOR_DELAY_GAME);
        if (gameSensor != null) sm.registerListener(this, gameSensor, SensorManager.SENSOR_DELAY_GAME);
        if (gameSensor != null && rvSensor == null) yawDeg = 0;
    }

    private void stopSensors() {
        if (sm != null) sm.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        int type = e.sensor.getType();
        if (type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(rvM, e.values);
            haveRv = true;
            if (!gameMode) {
                yawDeg = 0;
                readings.add(e.timestamp, toD(rvM));
                window.postInvalidateOnAnimation();
            }
        } else if (type == Sensor.TYPE_GAME_ROTATION_VECTOR) {
            SensorManager.getRotationMatrixFromVector(gM, e.values);
            double[] g = toD(gM);
            if (haveRv && !yawFrozen) {
                // the compass's north against the gyro's own: the twist about the vertical between the two, smoothed
                double[] y = Rot.mul(toD(rvM), Rot.transpose(g));
                double a = Math.toDegrees(Math.atan2(y[3] - y[1], y[0] + y[4]));
                double cur = yawDeg;
                yawDeg = Double.isNaN(cur) ? a : wrap(cur + 0.05 * wrap(a - cur));
            }
            readings.add(e.timestamp, g);
            window.postInvalidateOnAnimation();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor s, int a) {}

    static double[] toD(float[] m) {
        double[] d = new double[9];
        for (int i = 0; i < 9; i++) d[i] = m[i];
        return d;
    }

    static double wrap(double deg) {
        double d = ((deg + 180) % 360 + 360) % 360 - 180;
        return d;
    }

    /** A turn about the vertical, anticlockwise from above (degrees). */
    static double[] rz(double deg) {
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, -s, 0, s, c, 0, 0, 0, 1};
    }

    /** The phone's axes into east-north-up (true north), from a reading and the compass offset. */
    double[] world(double[] g, double yaw) {
        return Rot.mul(SkyWindow.trueNorth(decl), Rot.mul(rz(yaw), g));
    }

    @Override
    public double[] cameraToEnu(long realtimeNs, double[] yawOut) {
        double y = yawDeg;
        if (Double.isNaN(y)) return null;
        double[] g = readings.at(realtimeNs);
        if (g == null) return null;
        if (yawOut != null) yawOut[0] = y;
        return SkyWindow.cameraToEnu(world(g, y), sensorOrientation);
    }

    @Override
    public double turnDeg(long t0Ns, long t1Ns) {
        return readings.turnDeg(t0Ns, t1Ns);
    }

    @Override
    public long utcMs(long realtimeNs) {
        return System.currentTimeMillis() - (SystemClock.elapsedRealtimeNanos() - realtimeNs) / 1_000_000L;
    }

    // ------------------------------------------------------------------------------------------------
    // the camera

    private void setCamera(boolean on) {
        camOn = on;
        chipStyle(bCam, on);
        if (on) {
            texture.setVisibility(View.VISIBLE);
            cameraFailed = false;
            cameraRetries = 0;
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        } else {
            closeCamera();
            picture = null;
            texture.setVisibility(View.INVISIBLE);
        }
        updatePill();
        window.invalidate();
    }

    private void startCamera() {
        if (camera != null || cameraFailed) return;
        if (texture.isAvailable()) open(texture.getSurfaceTexture());
        else {
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    if (camOn && !isFinishing()) open(st);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {}

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture st) {}
            });
        }
    }

    private void open(SurfaceTexture st) {
        if (camera != null || isFinishing()) return;
        camera = new SkyCamera(this, this, this);
        Locator.Place p = place;
        if (p != null && Astro.altitude(Astro.sunEnu(System.currentTimeMillis(), p.latDeg * Astro.DEG, p.lonDeg * Astro.DEG)) / Astro.DEG < -6) {
            camera.startAtNight();
        }
        SkyWindow.Catalogue c = catalogue;
        if (p != null && c != null) camera.setSky(c.stars, p.latDeg, p.lonDeg);
        String why = camera.open(st);
        if (why != null) {
            camera = null;
            cameraFailed = true;
            cameraWhy = why;
            updatePill();
            return;
        }
        if (camera.model != null && camera.raw != null) {
            camModel = camera.model;
            rawW = camera.raw.getWidth();
            rawH = camera.raw.getHeight();
            sensorOrientation = camera.sensorOrientation;
            layoutTexture();
        }
        cameraWhy = "";
    }

    private void closeCamera() {
        if (camera != null) {
            camera.close();
            camera = null;
        }
        picture = null;
    }

    @Override
    public void onFrame(SkyCamera.Frame f) {
        lastFrame = f;
        if (f.picture != null) {
            picture = f;
            if (camera != null) camera.showing(f.picture);
        } else if (f.night < SkyCamera.PICTURE_OFF) {
            picture = null;
        }
        updatePill();
        window.invalidate();
    }

    @Override
    public void onLock(StarLock.Fix got, SkyCamera.Frame f) {
        lockTries++;
        if (got != null) {
            boolean first = fix == null;
            // the correction holds for the compass offset it was found with: from now the gyro carries it, not the compass
            if (gameMode) {
                yawDeg = f.yawDeg;
                yawFrozen = true;
            }
            C = got.C;
            fix = got;
            fixMs = System.currentTimeMillis();
            lockFails = 0;
            if (first) {
                hideTip();
                window.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                toast(String.format(Locale.US, "Locked onto %d stars: the compass was %.0f° off. The labels are on the stars now.", got.stars,
                        Math.abs(got.headingDeg)));
            }
        } else {
            lockFails++;
        }
        updatePill();
        window.invalidate();
    }

    @Override
    public void onProblem(String why) {
        closeCamera();
        if (cameraRetries < 2 && camOn && !isFinishing()) {
            // another app (the Moonshot, a moment ago) may not have let the camera go yet
            cameraRetries++;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (camOn && !isFinishing() && camera == null) startCamera();
                }
            }, 800);
            return;
        }
        cameraFailed = true;
        cameraWhy = why + " The drawn sky goes on without it.";
        updatePill();
    }

    /** How sure the labels are, at the top. */
    void updatePill() {
        String s;
        int c = GREY;
        Locator.Place p = place;
        boolean night = lastFrame != null && lastFrame.night >= SkyCamera.LOCK_NIGHT;
        if (p == null) {
            s = "Where are you? Allow location so the sky can be drawn for here.";
            c = GOLD;
        } else if (readings.size() == 0) {
            s = "Finding which way the phone points…";
        } else if (fix != null) {
            long age = (System.currentTimeMillis() - fixMs) / 60000L;
            s = String.format(Locale.US, "★ Star lock: %d stars · the compass was %.0f° off%s", fix.stars, Math.abs(fix.headingDeg),
                    age >= 2 ? " · " + age + " min ago" : "");
            c = GREEN;
        } else if (!cameraWhy.isEmpty()) {
            s = cameraWhy;
            c = GOLD;
        } else if (!camOn) {
            s = "By the compass: labels may be a few degrees off (the camera is off)";
        } else if (night && lockFails >= 4) {
            s = "No star lock yet: too few stars (cloud, city glow?). By the compass for now.";
        } else if (night) {
            s = "Hold still a moment to lock onto the stars…";
        } else {
            s = "By the compass: labels may be a few degrees off. After dark the stars fix that.";
        }
        if (!s.equals(pill.getText().toString())) pill.setText(s);
        pill.setTextColor(c);
    }

    // ------------------------------------------------------------------------------------------------
    // choosing

    void setTarget(SkyWindow.Item t) {
        hideTip();
        target = t;
        gotTarget = false;
        if (t != null) toast("Follow the arrow to " + t.name);
        window.invalidate();
    }

    void setOffset(long ms) {
        offsetMs = ms;
        timeLabel.setText(timeWords(ms));
        chipStyle(bTime, ms != 0 || timeBox.getVisibility() == View.VISIBLE);
        placeSky();
        window.invalidate();
    }

    /** "Tonight 11:40 PM · in 3 h 10 min". */
    static String timeWords(long offsetMs) {
        if (offsetMs == 0) return "Now";
        long now = System.currentTimeMillis(), t = now + offsetMs;
        TimeZone tz = TimeZone.getDefault();
        Calendar a = Calendar.getInstance(tz), b = Calendar.getInstance(tz);
        a.setTimeInMillis(now);
        b.setTimeInMillis(t);
        int days = (b.get(Calendar.YEAR) - a.get(Calendar.YEAR)) * 366 + b.get(Calendar.DAY_OF_YEAR) - a.get(Calendar.DAY_OF_YEAR);
        int hour = b.get(Calendar.HOUR_OF_DAY);
        boolean evening = hour >= 18 || hour < 5;
        String day;
        if (days == 0) day = evening ? "Tonight" : "Today";
        else if (days == 1) day = hour < 5 ? "Tonight" : "Tomorrow";
        else if (days == -1) day = hour >= 18 ? "Last night" : "Yesterday";
        else day = b.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, Locale.US);
        return day + " " + SkyWindow.clock(t, tz) + " · " + SkyWindow.fromNow(offsetMs);
    }

    /** Off to the 5x: the Moonshot or Jupiter's moons (back to it, when that's where the window was opened from). */
    void shoot(SkyWindow.Item t) {
        if ("moon".equals(t.shoot)) {
            if ("moon".equals(from)) finish();
            else startActivity(new Intent(this, MoonActivity.class));
        } else if ("jupiter".equals(t.shoot)) {
            if ("jupiter".equals(from)) finish();
            else startActivity(new Intent(this, JupiterActivity.class));
        }
    }

    /** Find's list: the stations up now and their passes, then the sky's best. */
    void showFind() {
        hideTip();
        findList.removeAllViews();
        SkyWindow.Sky s = sky;
        TimeZone tz = TimeZone.getDefault();
        for (final SkyWindow.Item it : sats) {
            if (it.norad != 25544 && it.norad != 48274 && it.norad != 20580) continue;
            findRow(it.name + ": up now!", it.where() + ", moving across the sky", it, GREEN);
        }
        List<SkyWindow.Pass> ps = passes;
        int shown = 0;
        for (SkyWindow.Pass p : ps) {
            if (p.endMs < System.currentTimeMillis() || shown >= 4) continue;
            shown++;
            final SkyWindow.Pass pass = p;
            TextView t = findRow(p.name + ": next pass " + SkyWindow.fromNow(p.startMs - System.currentTimeMillis()), p.words(tz), null, WHITE);
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    findBox.setVisibility(View.GONE);
                    showPass(pass);
                }
            });
        }
        if (s == null) {
            findList.addView(text("Working out the sky…", 15, GREY, false));
        } else {
            for (SkyWindow.Item it : SkyWindow.targets(s)) {
                String w = it.up() ? it.where() : "below the horizon now (" + it.where() + ")";
                findRow(cap(it.name), w + (it.about.isEmpty() ? "" : " · " + it.about), it, it.up() ? WHITE : DIM);
            }
        }
        findBox.setVisibility(View.VISIBLE);
        closeCard();
    }

    private TextView findRow(String title, String line, final SkyWindow.Item it, int colour) {
        TextView t = text(title + "\n" + line, 15, colour, false);
        t.setPadding(dp(4), dp(10), dp(4), dp(10));
        if (it != null) {
            t.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    findBox.setVisibility(View.GONE);
                    setTarget(it);
                }
            });
        }
        findList.addView(t);
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        findList.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1) / 2)));
        return t;
    }

    void showPass(SkyWindow.Pass p) {
        selected = null;
        selectedStar = -1;
        TimeZone tz = TimeZone.getDefault();
        cardTitle.setText(p.name);
        cardLine.setText("Next pass " + SkyWindow.fromNow(p.startMs - System.currentTimeMillis()));
        cardAbout.setText(p.words(tz) + String.format(Locale.US, ", as bright as magnitude %.1f.", p.mag));
        cardStory.setText(p.norad == 25544 ? "Be out a few minutes early, face the " + Probes0.compass(p.startAzDeg)
                + " and look for a bright star that moves, without blinking. Open the window then and it's labelled as it flies."
                : "Look for a steady light gliding across the stars. Open the window then and it's labelled as it flies.");
        cardRise.setText("");
        cardFind.setVisibility(View.GONE);
        cardShoot.setVisibility(View.GONE);
        cardBox.setVisibility(View.VISIBLE);
    }

    /** The first-time tip fades: after a few seconds, or as soon as the window is used. */
    void hideTip() {
        if (tipView == null || tipView.getVisibility() != View.VISIBLE || tipGoing) return;
        tipGoing = true;
        tipView.animate().alpha(0f).setDuration(400).withEndAction(new Runnable() {
            @Override
            public void run() {
                tipView.setVisibility(View.GONE);
            }
        }).start();
    }

    /** The card for something tapped: its story, when it rises and sets, Find it and, for the Moon and Jupiter, the 5x. */
    void showCard(final SkyWindow.Item it, double[] enu) {
        hideTip();
        selected = it;
        selectedStar = -1;
        findBox.setVisibility(View.GONE);
        double alt = enu != null ? Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, enu[2])))) : it.altDeg;
        double az = enu != null ? (Math.toDegrees(Math.atan2(enu[0], enu[1])) + 360) % 360 : it.azDeg;
        cardTitle.setText(cap(it.name));
        String kind = it.kind == SkyWindow.Kind.MOON || it.kind == SkyWindow.Kind.SUN ? "" : cap(it.kind.word) + " · ";
        String where = SkyPointer.where(alt, az) + (alt < -2 ? ": you're looking through the Earth" : "");
        cardLine.setText(kind + where);
        cardAbout.setText(it.about.isEmpty() ? "" : cap(it.about) + ".");
        cardAbout.setVisibility(it.about.isEmpty() ? View.GONE : View.VISIBLE);
        cardStory.setText(it.story);
        cardStory.setVisibility(it.story.isEmpty() ? View.GONE : View.VISIBLE);
        cardRise.setText(it.kind == SkyWindow.Kind.SAT ? satRise(it) : "Working out when it rises and sets…");
        cardFind.setVisibility(View.VISIBLE);
        cardShoot.setVisibility(!it.shoot.isEmpty() && alt > 0 ? View.VISIBLE : View.GONE);
        cardBox.setVisibility(View.VISIBLE);
        window.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (it.kind != SkyWindow.Kind.SAT) {
            final Locator.Place p = place;
            if (p == null) return;
            final long from = System.currentTimeMillis() + offsetMs;
            wh.post(new Runnable() {
                @Override
                public void run() {
                    final String w = SkyWindow.riseSetWords(SkyWindow.riseSet(it, from, p.latDeg, p.lonDeg), TimeZone.getDefault());
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (selected == it) cardRise.setText(w);
                        }
                    });
                }
            });
        }
    }

    private String satRise(SkyWindow.Item it) {
        for (SkyWindow.Pass p : passes) {
            if (p.norad == it.norad && p.startMs > System.currentTimeMillis()) return "Its next pass: " + p.words(TimeZone.getDefault());
        }
        return "";
    }

    /** A star with no story of its own: what it is, how far, how old its light. */
    void showStar(int i, double[] enu) {
        SkyWindow.Catalogue c = catalogue;
        if (c == null) return;
        BrightStars st = c.stars;
        String label = st.label(i);
        String abbr = SkyWindow.constellationOf(st, i), con = BrightStars.constellation(abbr);
        SkyWindow.Item it = new SkyWindow.Item(label.isEmpty() ? "A star in " + con : label, SkyWindow.Kind.STAR);
        it.dir = st.vec(i);
        it.mag = st.vmag[i];
        it.lightYears = st.ly[i] > 0 ? st.ly[i] : Double.NaN;
        it.constellation = abbr;
        String colour = colourWords(st.kelvin[i]);
        it.about = String.format(Locale.US, "%s in %s, magnitude %.1f%s", colour, con, st.vmag[i],
                Double.isNaN(it.lightYears) ? "" : ", " + lightYears(it.lightYears) + " away");
        it.story = Double.isNaN(it.lightYears) ? "" : SkyWindow.lightLeft(it.lightYears);
        showCard(it, enu);
        selectedStar = i;
    }

    static String colourWords(int kelvin) {
        if (kelvin <= 0) return "A star";
        if (kelvin < 4000) return "A cool orange-red star";
        if (kelvin < 5300) return "An orange star";
        if (kelvin < 6200) return "A yellow star like the Sun";
        if (kelvin < 7800) return "A white star";
        return "A hot blue-white star";
    }

    static String lightYears(double ly) {
        if (ly < 10) return String.format(Locale.US, "%.1f light years", ly);
        if (ly < 10_000) return String.format(Locale.US, "%,d light years", Math.round(ly));
        return String.format(Locale.US, "%,d light years", Math.round(ly / 1000) * 1000);
    }

    void closeCard() {
        cardBox.setVisibility(View.GONE);
        selected = null;
        selectedStar = -1;
        window.invalidate();
    }

    static String cap(String s) {
        if (s == null || s.isEmpty()) return "";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------------------------------------
    // the caption: what's in the middle, or the way to what's being found

    void caption(String c, String s) {
        long now = SystemClock.uptimeMillis();
        if (c.equals(captionText) && s.equals(subText)) return;
        if (now - captionMs < 120 && !captionText.isEmpty()) return;
        captionMs = now;
        captionText = c;
        subText = s;
        caption.setText(c);
        sub.setText(s);
        sub.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ------------------------------------------------------------------------------------------------
    // little helpers

    int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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
        t.setShadowLayer(dp(3), 0, dp(1), 0xFF000000);
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setStroke(dp(1), LINE);
        g.setCornerRadius(dp(14));
        l.setBackground(g);
        return l;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(15);
        b.setTextColor(primary ? Color.BLACK : GOLD);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        if (primary) {
            g.setColor(GOLD);
        } else {
            g.setColor(0xFF101418);
            g.setStroke(dp(1), LINE);
        }
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setPadding(dp(8), dp(10), dp(8), dp(10));
        return b;
    }

    private Button chip(String s, boolean on) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setStateListAnimator(null);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        chipStyle(b, on);
        return b;
    }

    private void chipStyle(Button b, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(on ? 0xE03E2F00 : 0xB0000000);
        g.setStroke(dp(on ? 2 : 1), on ? GOLD : LINE);
        b.setBackground(g);
        b.setTextColor(on ? GOLD : GREY);
        b.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_LONG).show();
    }

    /** Compass words (a small stand-in, so the pass card needn't reach into the processing package's own). */
    static final class Probes0 {
        static String compass(double az) {
            return com.brandon.nightsky.processing.Probes.compass(az);
        }
    }

    // ================================================================================================
    // the window itself: the camera's picture and the sky drawn over it

    final class WindowView extends View {
        final Paint picPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), textP = new Paint(Paint.ANTI_ALIAS_FLAG),
                small = new Paint(Paint.ANTI_ALIAS_FLAG), figP = new Paint(Paint.ANTI_ALIAS_FLAG), figDim = new Paint(Paint.ANTI_ALIAS_FLAG),
                figName = new Paint(Paint.ANTI_ALIAS_FLAG), dash = new Paint(Paint.ANTI_ALIAS_FLAG);
        final ScaleGestureDetector pinch;
        final GestureDetector taps;
        final float[] pt = new float[2];
        final double[] p2 = new double[2];
        private float[] verts, figLines, figLinesDim;
        private double[] bearings;
        private CameraModel bearingsFor;
        // what was drawn where (for taps and the caption): its place, its kind's rank, the item or the star
        int nHits;
        float[] hx = new float[4096], hy = new float[4096];
        int[] hRank = new int[4096], hStar = new int[4096];
        SkyWindow.Item[] hItem = new SkyWindow.Item[4096];
        double[][] hEnu = new double[4096][];
        // the labels to place: their text, where their thing is, its size, rank, colour
        final List<Label> labels = new ArrayList<Label>();
        final List<RectF> placed = new ArrayList<RectF>();
        double[] targetEnu;

        WindowView(Context c) {
            super(c);
            textP.setColor(WHITE);
            textP.setTextSize(dp(13));
            textP.setShadowLayer(dp(2), 0, dp(1), 0xFF000000);
            small.setColor(GREY);
            small.setTextSize(dp(11));
            small.setShadowLayer(dp(2), 0, dp(1), 0xFF000000);
            figP.setColor(0x7090CAF9);
            figP.setStrokeWidth(dp(1.2f));
            figDim.setColor(0x3890CAF9);
            figDim.setStrokeWidth(dp(1));
            figName.setColor(0xA090CAF9);
            figName.setTextSize(dp(12));
            figName.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.ITALIC));
            figName.setShadowLayer(dp(2), 0, dp(1), 0xFF000000);
            stroke.setStyle(Paint.Style.STROKE);
            dash.setStyle(Paint.Style.STROKE);
            dash.setPathEffect(new DashPathEffect(new float[]{dp(8), dp(6)}, 0));
            pinch = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector d) {
                    zoom = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, zoom * d.getScaleFactor()));
                    texture.setScaleX(zoom);
                    texture.setScaleY(zoom);
                    invalidate();
                    return true;
                }
            });
            taps = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public boolean onSingleTapUp(MotionEvent e) {
                    tap(e.getX(), e.getY());
                    return true;
                }

                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    zoom = 1f;
                    texture.setScaleX(1f);
                    texture.setScaleY(1f);
                    invalidate();
                    return true;
                }
            });
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) hideTip();
            pinch.onTouchEvent(e);
            if (!pinch.isInProgress()) taps.onTouchEvent(e);
            return true;
        }

        /** What's under a tap: the nearest thing drawn there, things before plain stars. */
        void tap(float x, float y) {
            int best = -1;
            double bestScore = Double.MAX_VALUE;
            for (int k = 0; k < nHits; k++) {
                double dx = hx[k] - x, dy = hy[k] - y, d2 = dx * dx + dy * dy;
                boolean star = hItem[k] == null;
                double r = star ? dp(22) : dp(36);
                if (d2 > r * r) continue;
                double score = d2 * (star ? 2.5 : 1) + hRank[k] * dp(4) * dp(4);
                if (score < bestScore) {
                    bestScore = score;
                    best = k;
                }
            }
            if (best < 0) {
                if (cardBox.getVisibility() == View.VISIBLE) closeCard();
                else if (findBox.getVisibility() == View.VISIBLE) findBox.setVisibility(View.GONE);
                return;
            }
            if (hItem[best] != null) showCard(hItem[best], hEnu[best]);
            else showStar(hStar[best], hEnu[best]);
            invalidate();
        }

        private void hit(float x, float y, int rank, SkyWindow.Item it, int star, double[] enu) {
            if (nHits >= hx.length) return;
            hx[nHits] = x;
            hy[nHits] = y;
            hRank[nHits] = rank;
            hItem[nHits] = it;
            hStar[nHits] = star;
            hEnu[nHits] = enu;
            nHits++;
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            nHits = 0;
            labels.clear();
            placed.clear();
            targetEnu = null;
            SkyWindow.Sky s = sky;
            SkyWindow.Catalogue cat = catalogue;
            Locator.Place p = place;
            double[] g = readings.latest();
            double y = yawDeg;
            long nowMs = System.currentTimeMillis(), utc = nowMs + offsetMs;
            boolean cam = camOn && camera != null && !cameraFailed;
            if (!cam) c.drawColor(skyColour(s));
            if (g == null || Double.isNaN(y) || p == null) {
                lastView = null;
                caption(p == null ? "Where are you?" : "Hold the phone up to the sky", p == null ? "Night Sky needs your rough location to draw the sky for here." : "");
                return;
            }
            double[] sensorView = SkyWindow.viewToEnu(world(g, y));
            double[] viewToEnu = StarLock.orthonormal(Rot.mul(C, sensorView));
            double f = f1() * zoom;
            SkyWindow.View v = new SkyWindow.View(w, h, f, viewToEnu);
            lastView = v;
            SkyCamera.Frame pic = picture;
            boolean picShown = cam && pic != null && pic.picture != null && pic.camToEnu != null;
            if (picShown) picShown = drawPicture(c, v, sensorView, pic);
            if (cam && offsetMs != 0) c.drawColor(0xA0000000);
            double lat = p.latDeg * Astro.DEG, lon = p.lonDeg * Astro.DEG;
            double[] toEnu = Astro.j2000ToEnu(Astro.jdUtc(utc), lat, lon);
            double[] M = v.fromJ2000(toEnu);
            ground(c, v, cam);
            figures(c, v, M, toEnu);
            if (cat != null) stars(c, v, M, toEnu, cat, picShown);
            if (s != null) items(c, v, M, toEnu, s, utc, nowMs, lat, lon);
            placeLabels(c, w, h);
            horizon(c, v);
            frame5x(c, v);
            guide(c, v, nowMs);
            reticle(c, w, h);
            middle(v, w, h);
        }

        /** The sky's own colour, for the drawing without the camera: blue by day, through twilight to black. */
        int skyColour(SkyWindow.Sky s) {
            double sun = s != null ? s.sunAltDeg : -20;
            if (sun > 0) return 0xFF2E5C8A;
            if (sun > -6) return 0xFF1A2B4C;
            if (sun > -12) return 0xFF0B1426;
            return 0xFF03050B;
        }

        /** The night picture, put where the phone was while it was exposed (its lens's distortion followed): false if it can't be. */
        boolean drawPicture(Canvas c, SkyWindow.View v, double[] sensorView, SkyCamera.Frame pic) {
            CameraModel m = pic.model;
            int n = (MESH_W + 1) * (MESH_H + 1);
            if (bearings == null || bearingsFor != m) {
                bearings = new double[3 * n];
                double[] d = new double[3];
                int k = 0;
                double rw = SkyCamera.SCALE * pic.pw, rh = SkyCamera.SCALE * pic.ph;
                for (int j = 0; j <= MESH_H; j++) {
                    for (int i = 0; i <= MESH_W; i++) {
                        m.unproject(rw * i / MESH_W, rh * j / MESH_H, d);
                        bearings[k++] = d[0];
                        bearings[k++] = d[1];
                        bearings[k++] = d[2];
                    }
                }
                bearingsFor = m;
            }
            if (verts == null || verts.length != 2 * n) verts = new float[2 * n];
            // the camera's bearings into the screen's, both by the sensors (a lock corrects them alike)
            double[] A = Rot.mul(Rot.transpose(sensorView), pic.camToEnu);
            for (int k = 0; k < n; k++) {
                double bx = bearings[3 * k], by = bearings[3 * k + 1], bz = bearings[3 * k + 2];
                double x = A[0] * bx + A[1] * by + A[2] * bz, yy = A[3] * bx + A[4] * by + A[5] * bz, z = A[6] * bx + A[7] * by + A[8] * bz;
                if (!v.projectView(x, yy, z, p2)) return false;
                verts[2 * k] = (float) p2[0];
                verts[2 * k + 1] = (float) p2[1];
            }
            picPaint.setAlpha(offsetMs != 0 ? 110 : 255);
            c.drawBitmapMesh(pic.picture, MESH_W, MESH_H, verts, 0, null, 0, picPaint);
            return true;
        }

        /** Below the horizon: the ground, tinted, with the sky on the other side of the Earth showing through it. */
        void ground(Canvas c, SkyWindow.View v, boolean cam) {
            double[] R = v.viewToEnu;
            float w = v.width, h = v.height;
            float[][] corners = {{0, 0}, {w, 0}, {w, h}, {0, h}};
            Path path = new Path();
            int n = 0;
            for (int k = 0; k < 4; k++) {
                float[] a = corners[k], b = corners[(k + 1) % 4];
                double ua = up(R, v, a[0], a[1]), ub = up(R, v, b[0], b[1]);
                if (ua < 0) {
                    if (n++ == 0) path.moveTo(a[0], a[1]);
                    else path.lineTo(a[0], a[1]);
                }
                if ((ua < 0) != (ub < 0)) {
                    double t = ua / (ua - ub);
                    float x = (float) (a[0] + t * (b[0] - a[0])), yy = (float) (a[1] + t * (b[1] - a[1]));
                    if (n++ == 0) path.moveTo(x, yy);
                    else path.lineTo(x, yy);
                }
            }
            if (n < 3) return;
            path.close();
            fill.setColor(cam ? 0x8C0E1A10 : 0xE0101A12);
            c.drawPath(path, fill);
        }

        /** How far up a point of the screen looks (the sine of its height). */
        double up(double[] R, SkyWindow.View v, double sx, double sy) {
            double x = (sx - v.cx) / v.f, yy = (sy - v.cy) / v.f;
            return R[6] * x + R[7] * yy + R[8];
        }

        /** The horizon's line and its compass points. */
        void horizon(Canvas c, SkyWindow.View v) {
            String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
            stroke.setStrokeWidth(dp(1.5f));
            stroke.setColor(0xB08FBF8F);
            // the line: a great circle, so a straight line on the screen; drawn from points along it
            float px = Float.NaN, py = Float.NaN;
            for (int a = 0; a <= 360; a += 3) {
                double r = Math.toRadians(a);
                if (v.project(new double[]{Math.sin(r), Math.cos(r), 0}, p2)) {
                    float x = (float) p2[0], yy = (float) p2[1];
                    if (!Float.isNaN(px) && Math.abs(x - px) + Math.abs(yy - py) < 4 * (v.width + v.height)) c.drawLine(px, py, x, yy, stroke);
                    px = x;
                    py = yy;
                } else {
                    px = Float.NaN;
                }
            }
            for (int k = 0; k < 8; k++) {
                double r = Math.toRadians(45 * k);
                if (!v.project(new double[]{Math.sin(r), Math.cos(r), 0}, p2) || !v.onScreen(p2[0], p2[1], -dp(8))) continue;
                float x = (float) p2[0], yy = (float) p2[1];
                textP.setColor(k == 0 ? NORTH : 0xFFCFE8CF);
                textP.setTextSize(dp(k % 2 == 0 ? 16 : 13));
                float tw = textP.measureText(names[k]);
                c.drawLine(x, yy - dp(6), x, yy + dp(6), stroke);
                c.drawText(names[k], x - tw / 2, yy - dp(10), textP);
            }
            textP.setColor(WHITE);
            textP.setTextSize(dp(13));
        }

        /** The constellations' figures, and their names. */
        void figures(Canvas c, SkyWindow.View v, double[] M, double[] toEnu) {
            List<Figures.Figure> all = Figures.all();
            int nl = 0, nd = 0;
            if (figLines == null) {
                figLines = new float[4 * 1400];
                figLinesDim = new float[4 * 1400];
            }
            double f = v.f, cx = v.cx, cy = v.cy;
            float margin = Math.max(v.width, v.height);
            for (Figures.Figure fg : all) {
                for (double[][] run : fg.runs) {
                    for (int k = 0; k + 1 < run.length; k++) {
                        double[] a = run[k], b = run[k + 1];
                        double za = M[6] * a[0] + M[7] * a[1] + M[8] * a[2], zb = M[6] * b[0] + M[7] * b[1] + M[8] * b[2];
                        if (za < 0.05 || zb < 0.05) continue;
                        float xa = (float) (cx + f * (M[0] * a[0] + M[1] * a[1] + M[2] * a[2]) / za);
                        float ya = (float) (cy + f * (M[3] * a[0] + M[4] * a[1] + M[5] * a[2]) / za);
                        float xb = (float) (cx + f * (M[0] * b[0] + M[1] * b[1] + M[2] * b[2]) / zb);
                        float yb = (float) (cy + f * (M[3] * b[0] + M[4] * b[1] + M[5] * b[2]) / zb);
                        if (Math.max(xa, xb) < -margin || Math.min(xa, xb) > v.width + margin || Math.max(ya, yb) < -margin
                                || Math.min(ya, yb) > v.height + margin) continue;
                        double upA = toEnu[6] * a[0] + toEnu[7] * a[1] + toEnu[8] * a[2], upB = toEnu[6] * b[0] + toEnu[7] * b[1] + toEnu[8] * b[2];
                        boolean below = upA + upB < 0;
                        float[] arr = below ? figLinesDim : figLines;
                        int o = below ? nd : nl;
                        if (o + 4 > arr.length) continue;
                        arr[o] = xa;
                        arr[o + 1] = ya;
                        arr[o + 2] = xb;
                        arr[o + 3] = yb;
                        if (below) nd += 4;
                        else nl += 4;
                    }
                }
                if (zoom <= 3.5f) {
                    double[] l = fg.label;
                    double z = M[6] * l[0] + M[7] * l[1] + M[8] * l[2];
                    if (z > 0.3) {
                        float x = (float) (cx + f * (M[0] * l[0] + M[1] * l[1] + M[2] * l[2]) / z);
                        float yy = (float) (cy + f * (M[3] * l[0] + M[4] * l[1] + M[5] * l[2]) / z);
                        if (v.onScreen(x, yy, -dp(10))) {
                            boolean below = toEnu[6] * l[0] + toEnu[7] * l[1] + toEnu[8] * l[2] < 0;
                            labels.add(new Label(BrightStars.constellation(fg.abbr), x, yy, 0, 90, figName, below, true));
                        }
                    }
                }
            }
            if (nl > 0) c.drawLines(figLines, 0, nl, figP);
            if (nd > 0) c.drawLines(figLinesDim, 0, nd, figDim);
        }

        /** The faintest star drawn at this zoom. */
        float starLimit() {
            return (float) Math.min(SkyWindow.DOT_MAG + 0.5, 5.0 + 2.5 * Math.log10(Math.max(0.5, zoom)));
        }

        /** The stars, as dots of their colour and brightness (fainter over the camera's own stars at night). */
        void stars(Canvas c, SkyWindow.View v, double[] M, double[] toEnu, SkyWindow.Catalogue cat, boolean picShown) {
            BrightStars st = cat.stars;
            if (st == null) return;
            float lim = starLimit();
            double f = v.f, cx = v.cx, cy = v.cy;
            double[] d = st.dir;
            float base = dp(1);
            for (int k = 0; k < cat.dots.length; k++) {
                int i = cat.dots[k];
                float mag = st.vmag[i];
                if (mag > lim) break;
                double dx = d[3 * i], dy = d[3 * i + 1], dz = d[3 * i + 2];
                double z = M[6] * dx + M[7] * dy + M[8] * dz;
                if (z < 0.05) continue;
                float x = (float) (cx + f * (M[0] * dx + M[1] * dy + M[2] * dz) / z);
                float yy = (float) (cy + f * (M[3] * dx + M[4] * dy + M[5] * dz) / z);
                if (x < -10 || yy < -10 || x > v.width + 10 || yy > v.height + 10) continue;
                boolean below = toEnu[6] * dx + toEnu[7] * dy + toEnu[8] * dz < 0;
                float r = base * Math.max(0.7f, (lim + 1.2f - mag) * 0.55f);
                int col = cat.colours[i];
                int alpha = picShown ? 120 : 255;
                if (below) alpha = alpha * 45 / 100;
                fill.setColor((col & 0x00FFFFFF) | (alpha << 24));
                c.drawCircle(x, yy, r, fill);
                if (i == selectedStar) {
                    stroke.setColor(GOLD);
                    stroke.setStrokeWidth(dp(2));
                    c.drawCircle(x, yy, r + dp(9), stroke);
                }
                hit(x, yy, 9, null, i, new double[]{toEnu[0] * dx + toEnu[1] * dy + toEnu[2] * dz, toEnu[3] * dx + toEnu[4] * dy + toEnu[5] * dz,
                        toEnu[6] * dx + toEnu[7] * dy + toEnu[8] * dz});
            }
        }

        /** Where an item is at the window's moment (east-north-up). */
        double[] enuOf(SkyWindow.Item it, long utc, long nowMs, double[] toEnu, double lat, double lon) {
            switch (it.kind) {
                case MOON:
                    return Astro.moonEnu(utc, lat, lon);
                case SUN:
                    return Astro.sunEnu(utc, lat, lon);
                case SAT:
                    return SkyWindow.satAt(it, satsMs, nowMs);
                default:
                    if (it.dir != null) {
                        double[] e = new double[3];
                        Rot.apply(toEnu, it.dir[0], it.dir[1], it.dir[2], e);
                        return e;
                    }
                    return it.enu;
            }
        }

        /** The Moon, the Sun, the planets, the deep sky, the unseen, the spacecraft, the showers' radiants and the satellites. */
        void items(Canvas c, SkyWindow.View v, double[] M, double[] toEnu, SkyWindow.Sky s, long utc, long nowMs, double lat, double lon) {
            double starLab = 2.0 + 1.6 * Math.log(Math.max(0.5, zoom)) / Math.log(2);
            double deepLim = 5.0 + 2.0 * Math.log(Math.max(0.5, zoom)) / Math.log(2);
            List<SkyWindow.Item> all = new ArrayList<SkyWindow.Item>(s.items);
            if (offsetMs == 0) all.addAll(sats);
            double[] sunEnu = Astro.sunEnu(utc, lat, lon);
            for (SkyWindow.Item it : all) {
                boolean chosen = it == selected || it == target || (target != null && it.kind == SkyWindow.Kind.SAT && it.norad == target.norad);
                if (it.kind == SkyWindow.Kind.XRAY && !xray && !chosen) continue;
                if (it.kind == SkyWindow.Kind.DEEP && !chosen && !Double.isNaN(it.mag) && it.mag > deepLim) continue;
                double[] e = enuOf(it, utc, nowMs, toEnu, lat, lon);
                if (e == null) continue;
                if (target != null && (it == target || (target.kind == SkyWindow.Kind.SAT && it.kind == SkyWindow.Kind.SAT && it.norad == target.norad))) {
                    targetEnu = e;
                    if (it != target) target = it;
                }
                if (!v.project(e, p2)) continue;
                float x = (float) p2[0], yy = (float) p2[1];
                if (!v.onScreen(x, yy, dp(30))) continue;
                boolean below = e[2] < 0;
                int a = below ? 130 : 255;
                float r = marker(c, it, x, yy, v, e, sunEnu, a);
                if (chosen) {
                    stroke.setColor(it == target ? GREEN : GOLD);
                    stroke.setStrokeWidth(dp(2));
                    c.drawCircle(x, yy, r + dp(10), stroke);
                }
                int rank = rank(it);
                hit(x, yy, rank, it, -1, e);
                boolean label = it.kind != SkyWindow.Kind.STAR || chosen || it.mag <= starLab;
                if (label) labels.add(new Label(it.name.startsWith("the ") ? cap(it.name.substring(4)) : it.name, x, yy, r, rank, textP, below, false));
            }
            // the target, if it's out of the sky's list (a star tapped, or the window's time moved on)
            if (target != null && targetEnu == null) targetEnu = enuOf(target, utc, nowMs, toEnu, lat, lon);
        }

        int rank(SkyWindow.Item it) {
            switch (it.kind) {
                case SUN:
                case MOON:
                    return 0;
                case PLANET:
                case SAT:
                    return 1;
                case PROBE:
                case POINT:
                case SHOWER:
                    return 2;
                case BLACK_HOLE:
                    return 3;
                case STAR:
                    return 4 + (int) Math.max(0, Math.min(4, it.mag));
                case DEEP:
                    return 6;
                default:
                    return 7;
            }
        }

        /** Draws an item's mark; its radius. */
        float marker(Canvas c, SkyWindow.Item it, float x, float y, SkyWindow.View v, double[] e, double[] sunEnu, int alpha) {
            int col = (it.colour & 0x00FFFFFF) | (alpha << 24);
            fill.setColor(col);
            stroke.setColor(col);
            stroke.setStrokeWidth(dp(1.5f));
            switch (it.kind) {
                case MOON: {
                    float r = (float) Math.max(dp(7), v.f * Math.tan(Math.toRadians(0.26)));
                    moon(c, x, y, r, v, e, sunEnu, alpha);
                    return r;
                }
                case SUN: {
                    float r = (float) Math.max(dp(9), v.f * Math.tan(Math.toRadians(0.27)));
                    c.drawCircle(x, y, r, fill);
                    for (int k = 0; k < 8; k++) {
                        double a = Math.PI * k / 4;
                        c.drawLine(x + (float) Math.cos(a) * (r + dp(3)), y + (float) Math.sin(a) * (r + dp(3)), x + (float) Math.cos(a) * (r + dp(9)),
                                y + (float) Math.sin(a) * (r + dp(9)), stroke);
                    }
                    return r + dp(9);
                }
                case PLANET: {
                    float r = dp(Math.max(3f, Math.min(7f, (float) (4.5 - 0.8 * (Double.isNaN(it.mag) ? 1 : it.mag)))));
                    c.drawCircle(x, y, r, fill);
                    if (it.planet == 4) {
                        // Saturn's rings
                        RectF ring = new RectF(x - 2.3f * r, y - 0.8f * r, x + 2.3f * r, y + 0.8f * r);
                        c.drawOval(ring, stroke);
                    }
                    return r;
                }
                case DWARF:
                    c.drawCircle(x, y, dp(2.5f), fill);
                    return dp(2.5f);
                case STAR:
                    return dp(3);
                case DEEP: {
                    RectF o = new RectF(x - dp(7), y - dp(4.5f), x + dp(7), y + dp(4.5f));
                    c.drawOval(o, stroke);
                    return dp(7);
                }
                case BLACK_HOLE: {
                    fill.setColor(alpha << 24);
                    c.drawCircle(x, y, dp(5), fill);
                    stroke.setStrokeWidth(dp(2));
                    c.drawCircle(x, y, dp(8), stroke);
                    dash.setColor(col);
                    dash.setStrokeWidth(dp(1));
                    c.drawCircle(x, y, dp(13), dash);
                    return dp(13);
                }
                case XRAY: {
                    c.drawCircle(x, y, dp(6), stroke);
                    c.drawLine(x - dp(9), y, x - dp(3), y, stroke);
                    c.drawLine(x + dp(3), y, x + dp(9), y, stroke);
                    return dp(9);
                }
                case PROBE: {
                    Path d = new Path();
                    d.moveTo(x, y - dp(7));
                    d.lineTo(x + dp(6), y);
                    d.lineTo(x, y + dp(7));
                    d.lineTo(x - dp(6), y);
                    d.close();
                    c.drawPath(d, stroke);
                    return dp(7);
                }
                case POINT:
                    c.drawLine(x - dp(7), y, x + dp(7), y, stroke);
                    c.drawLine(x, y - dp(7), x, y + dp(7), stroke);
                    return dp(7);
                case SHOWER:
                    for (int k = 0; k < 8; k++) {
                        double a = Math.PI * k / 4 + 0.2;
                        c.drawLine(x + (float) Math.cos(a) * dp(5), y + (float) Math.sin(a) * dp(5), x + (float) Math.cos(a) * dp(15),
                                y + (float) Math.sin(a) * dp(15), stroke);
                    }
                    return dp(15);
                case SAT: {
                    c.drawCircle(x, y, dp(3.5f), fill);
                    // which way it's going: a short tail behind it
                    if (it.enuNext != null && v.project(SkyWindow.satAt(it, satsMs, System.currentTimeMillis() - 4000), p2)) {
                        stroke.setColor((alpha / 2) << 24 | 0xFFFFFF);
                        c.drawLine((float) p2[0], (float) p2[1], x, y, stroke);
                    }
                    return dp(4);
                }
                default:
                    c.drawCircle(x, y, dp(3), fill);
                    return dp(3);
            }
        }

        /** The Moon in its phase: the side toward the Sun lit, the terminator an ellipse. */
        void moon(Canvas c, float x, float y, float r, SkyWindow.View v, double[] e, double[] sunEnu, int alpha) {
            double cosE = e[0] * sunEnu[0] + e[1] * sunEnu[1] + e[2] * sunEnu[2];
            double k = (1 - cosE) / 2;
            fill.setColor((alpha * 70 / 100) << 24 | 0x2E3338);
            c.drawCircle(x, y, r, fill);
            // the Sun's way on the screen, from the Moon
            double[] t = {sunEnu[0] - cosE * e[0], sunEnu[1] - cosE * e[1], sunEnu[2] - cosE * e[2]};
            double tn = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
            double ang = 0;
            if (tn > 1e-9) {
                double[] q = {e[0] + 0.01 * t[0] / tn, e[1] + 0.01 * t[1] / tn, e[2] + 0.01 * t[2] / tn};
                if (v.project(q, p2)) ang = Math.toDegrees(Math.atan2(p2[1] - y, p2[0] - x));
            }
            Path half = new Path();
            half.addArc(new RectF(-r, -r, r, r), -90, 180);
            half.close();
            float b = (float) (r * Math.abs(1 - 2 * k));
            Path ell = new Path();
            ell.addOval(new RectF(-b, -r, b, r), Path.Direction.CW);
            Path lit = new Path();
            lit.op(half, ell, k < 0.5 ? Path.Op.DIFFERENCE : Path.Op.UNION);
            c.save();
            c.translate(x, y);
            c.rotate((float) ang);
            fill.setColor((alpha << 24) | 0xFFF8E1);
            c.drawPath(lit, fill);
            c.restore();
        }

        /** The labels, most important first, each where it doesn't cover another; figures' names last. */
        void placeLabels(Canvas c, int w, int h) {
            Collections.sort(labels, new Comparator<Label>() {
                @Override
                public int compare(Label a, Label b) {
                    return Integer.compare(a.rank, b.rank);
                }
            });
            // the middle, the bars at the top and bottom (with the caption) and the tip are kept clear
            RectF mid = new RectF(w / 2f - dp(22), h / 2f - dp(22), w / 2f + dp(22), h / 2f + dp(22));
            placed.add(mid);
            if (topBar != null && topBar.getHeight() > 0) placed.add(new RectF(0, 0, w, topBar.getBottom()));
            if (bottomBar != null && bottomBar.getHeight() > 0) {
                float top = bottomBar.getBottom();
                for (int k = 0; k < bottomBar.getChildCount(); k++) {
                    View ch = bottomBar.getChildAt(k);
                    boolean shows = ch.getVisibility() == View.VISIBLE && !(ch instanceof TextView && !(ch instanceof Button)
                            && ((TextView) ch).getText().length() == 0);
                    if (shows) top = Math.min(top, bottomBar.getTop() + ch.getTop());
                }
                placed.add(new RectF(0, top - dp(4), w, h));
            }
            if (tipView != null && tipView.getVisibility() == View.VISIBLE && tipView.getAlpha() > 0.3f) {
                placed.add(new RectF(tipView.getLeft(), tipView.getTop(), tipView.getRight(), tipView.getBottom()));
            }
            for (Label l : labels) {
                Paint pp = l.paint;
                float tw = pp.measureText(l.text), th = pp.getTextSize();
                float gapPx = dp(4);
                RectF r;
                if (l.centred) {
                    r = new RectF(l.x - tw / 2, l.y - th / 2, l.x + tw / 2, l.y + th / 2);
                    if (overlaps(r)) continue;
                } else {
                    r = new RectF(l.x + l.r + gapPx, l.y - th * 0.7f, l.x + l.r + gapPx + tw, l.y + th * 0.35f);
                    if (overlaps(r) || r.right > w) {
                        r = new RectF(l.x - l.r - gapPx - tw, l.y - th * 0.7f, l.x - l.r - gapPx, l.y + th * 0.35f);
                        if (overlaps(r) || r.left < 0) continue;
                    }
                }
                placed.add(r);
                int old = pp.getAlpha();
                if (l.below) pp.setAlpha(old * 55 / 100);
                c.drawText(l.text, r.left, l.centred ? r.bottom - th * 0.2f : l.y + th * 0.35f, pp);
                pp.setAlpha(old);
            }
        }

        boolean overlaps(RectF r) {
            for (RectF o : placed) if (RectF.intersects(o, r)) return true;
            return false;
        }

        /** The 5x camera's frame, when the Moon or Jupiter is being found or looked at. */
        void frame5x(Canvas c, SkyWindow.View v) {
            SkyWindow.Item t = target != null ? target : selected;
            boolean show = t != null && !t.shoot.isEmpty() && !Double.isNaN(teleW);
            boolean inFrame = false;
            if (show) {
                float fw = (float) (v.f * Math.tan(Math.toRadians(teleW / 2))), fh = (float) (v.f * Math.tan(Math.toRadians(teleH / 2)));
                RectF r = new RectF(v.width / 2f - fw, v.height / 2f - fh, v.width / 2f + fw, v.height / 2f + fh);
                double[] e = t == target ? targetEnu : null;
                if (e == null) {
                    for (int k = 0; k < nHits; k++) if (hItem[k] == t) e = hEnu[k];
                }
                if (e != null && v.project(e, p2)) inFrame = r.contains((float) p2[0], (float) p2[1]) && e[2] > 0;
                dash.setColor(inFrame ? GREEN : GOLD);
                dash.setStrokeWidth(dp(2));
                c.drawRect(r, dash);
                small.setColor(inFrame ? GREEN : GOLD);
                c.drawText(inFrame ? "In the 5x camera's frame" : "The 5x camera's frame", r.left + dp(4), r.top - dp(5), small);
                small.setColor(GREY);
            }
            boolean want = show && inFrame && offsetMs == 0;
            if (want != (bShoot.getVisibility() == View.VISIBLE)) {
                bShoot.setVisibility(want ? View.VISIBLE : View.GONE);
                if (want) bShoot.setText("moon".equals(t.shoot) ? "Shoot the Moon with the 5x" : "Shoot Jupiter's moons with the 5x");
            }
        }

        /** The way to what's being found: an arrow at the edge, then a ring on it, then a buzz. */
        void guide(Canvas c, SkyWindow.View v, long nowMs) {
            SkyWindow.Item t = target;
            if (t == null || targetEnu == null) return;
            double got = t.shoot.isEmpty() || Double.isNaN(teleW) ? GOT_DEG : Math.max(1.0, 0.35 * Math.min(teleW, teleH));
            SkyWindow.Guide g = SkyWindow.guide(v, t, targetEnu, got);
            float pulse = (float) (0.5 + 0.5 * Math.sin(nowMs / 180.0));
            if (g.onScreen) {
                stroke.setColor(GREEN);
                stroke.setStrokeWidth(dp(3));
                c.drawCircle((float) g.x, (float) g.y, dp(20) + dp(8) * pulse, stroke);
            } else {
                double a = Math.toRadians(g.arrowDeg);
                float cx = v.width / 2f, cy = v.height / 2f, R = Math.min(v.width, v.height) * 0.36f;
                float ax = cx + (float) Math.sin(a) * R, ay = cy - (float) Math.cos(a) * R;
                // a slim head on a short stem, nudging outward and back
                float sa = (float) Math.sin(a), ca = (float) Math.cos(a), push = dp(6) * pulse;
                float bx = ax + sa * push, by = ay - ca * push;
                float L = dp(30), W2 = dp(12), stem = dp(22);
                Path arrow = new Path();
                arrow.moveTo(bx + sa * L, by - ca * L);
                arrow.lineTo(bx + ca * W2, by + sa * W2);
                arrow.lineTo(bx - ca * W2, by - sa * W2);
                arrow.close();
                fill.setColor(GREEN);
                c.drawPath(arrow, fill);
                stroke.setColor(GREEN);
                stroke.setStrokeWidth(dp(7));
                stroke.setStrokeCap(Paint.Cap.ROUND);
                c.drawLine(bx + sa * dp(2), by - ca * dp(2), bx - sa * stem, by + ca * stem, stroke);
                stroke.setStrokeCap(Paint.Cap.BUTT);
            }
            if (g.got && !gotTarget) {
                gotTarget = true;
                gotAtMs = nowMs;
                performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            } else if (!g.got && g.offDeg > 2.5 * got) {
                gotTarget = false;
            }
            String name = cap(t.name);
            String words = g.got ? "Got it! " + name + (t.shoot.isEmpty() ? "" : " is in the 5x camera's frame")
                    : name + ": " + g.words + String.format(Locale.US, " · %.0f° away", g.offDeg);
            caption(words, g.got ? t.where() + (t.about.isEmpty() ? "" : " · " + t.about) : "Tap Find to choose something else");
            // the pulse, about 25 times a second (the sensors redraw the window more often than that while it's held)
            postInvalidateDelayed(PULSE_MS);
        }

        /** The middle of the window. */
        void reticle(Canvas c, int w, int h) {
            float cx = w / 2f, cy = h / 2f, r = dp(16);
            stroke.setColor(0x90FFFFFF);
            stroke.setStrokeWidth(dp(1.5f));
            c.drawCircle(cx, cy, r, stroke);
            c.drawLine(cx - r - dp(6), cy, cx - r + dp(4), cy, stroke);
            c.drawLine(cx + r - dp(4), cy, cx + r + dp(6), cy, stroke);
            c.drawLine(cx, cy - r - dp(6), cx, cy - r + dp(4), stroke);
            c.drawLine(cx, cy + r - dp(4), cx, cy + r + dp(6), stroke);
        }

        /** The caption, when nothing's being found: what's in the middle, and where the window looks. */
        void middle(SkyWindow.View v, int w, int h) {
            if (target != null && targetEnu != null) return;
            double[] axis = v.axis();
            double alt = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, axis[2])))), az = (Math.toDegrees(Math.atan2(axis[0], axis[1])) + 360) % 360;
            int best = -1;
            double bestScore = Double.MAX_VALUE, reach = Math.min(w, h) * 0.09;
            for (int k = 0; k < nHits; k++) {
                double dx = hx[k] - w / 2.0, dy = hy[k] - h / 2.0, d2 = dx * dx + dy * dy;
                boolean star = hItem[k] == null;
                if (d2 > reach * reach * (star ? 0.4 : 1)) continue;
                double score = d2 * (star ? 3 : 1) + hRank[k] * reach * reach * 0.05;
                if (score < bestScore) {
                    bestScore = score;
                    best = k;
                }
            }
            String where = (alt < -2 ? "Through the Earth: " : "Looking ") + SkyPointer.where(alt, az);
            if (best < 0) {
                caption("", where + (alt < -2 ? ". This is the sky on the other side of the planet." : ""));
                return;
            }
            String name, about;
            if (hItem[best] != null) {
                SkyWindow.Item it = hItem[best];
                name = cap(it.name);
                about = it.about;
            } else {
                SkyWindow.Catalogue cat = catalogue;
                int i = hStar[best];
                String l = cat != null ? cat.stars.label(i) : "";
                name = l.isEmpty() ? "A star in " + (cat != null ? BrightStars.constellation(SkyWindow.constellationOf(cat.stars, i)) : "the sky") : l;
                about = cat != null && cat.stars.ly[i] > 0 ? lightYears(cat.stars.ly[i]) + " away" : "";
            }
            caption(name, (about.isEmpty() ? "" : cap(about) + " · ") + where);
        }
    }

    /** A label waiting to be placed. */
    static final class Label {
        final String text;
        final float x, y, r;
        final int rank;
        final Paint paint;
        final boolean below, centred;

        Label(String text, float x, float y, float r, int rank, Paint paint, boolean below, boolean centred) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.r = r;
            this.rank = rank;
            this.paint = paint;
            this.below = below;
            this.centred = centred;
        }
    }
}
