package com.brandon.nightsky;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.brandon.nightsky.processing.CancelledException;
import com.brandon.nightsky.processing.Earthshine;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.MoonLook;
import com.brandon.nightsky.processing.MoonSites;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.Progress;
import com.brandon.nightsky.processing.TransitFinder;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Moonshot (3.0): the Moon from the Pixel's 5x telephoto, sharper and cleaner than any single photo of it, with
 * every place people and their robots have landed marked where it really is tonight. Point the phone at the Moon:
 * it finds it, sets the exposure and focuses on its craters; Shoot the Moon takes 150 RAW frames in five seconds,
 * hand-held; the sharpest half are lined up and stacked ({@link MoonStack}) into the photo, saved twice in the
 * gallery (as it is, and labelled). Tap a landing site for its story. The Space Station's next crossings of the
 * Moon near here are listed below ({@link TransitFinder}). On a crescent (3.1) it shoots 120 brighter frames after
 * the burst for the Earth's glow on the dark part, merged into the photo ({@link Earthshine}).
 */
public final class MoonActivity extends Activity implements MoonCamera.Listener {
    static final int GOLD = 0xFFFFD54F, WHITE = 0xFFFFFFFF, GREY = 0xFFB0BEC5, DIM = 0xFF78909C, CARD = 0xFF101418, LINE = 0xFF37474F;
    private static final int REQ_CAMERA = 31;

    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MoonCamera camera;
    private TextureView texture;
    private Overlay overlay;
    private ImageView closeUp;
    private TextView status, details, moonLine, shutterHelp, storyText;
    private Button shutter, refocus;
    private LinearLayout resultBox, transitBox;
    private ProgressBar bar;
    private TextView barText;
    private ZoomImageView photo;
    private MoonCamera.Live live;
    private volatile Thread job;
    private volatile boolean jobCancelled;

    // the result
    private MoonStack.Result result;
    private MoonGeo.View view;
    private MoonLook.Picture base, before;
    private int[] baseMineral;
    private List<MoonLook.Label> labels = new ArrayList<MoonLook.Label>();
    /** The Moon map's names (3.1): the seas, craters, mountains and Apollo landings on the Moon itself. */
    private List<MoonLook.MapLabel> mapLabels = new ArrayList<MoonLook.MapLabel>();
    private float labelSize = 30;
    private int labelLevel = MoonLook.LABELS_FAMOUS;
    private boolean mineral;
    /** What the photo shows (3.1): one frame, the stack, or the stack with the Earth's glow on its night side. */
    static final int LOOK_ONE = 0, LOOK_STACK = 1, LOOK_GLOW = 2;
    private int look = LOOK_STACK, shownLook = -1;
    /** The Earth's glow (3.1): its merge (null: none shot, or it went wrong), its photo (null: none to show). */
    private Earthshine.Result glowResult;
    private MoonLook.Picture glowPic;
    /**
     * Shoot the Earth's glow too: on unless turned off, offered while tonight's Moon is a crescent at most
     * {@link #GLOW_ILLUM} lit (past that the night side is too small, and the glow too faint against the glare).
     */
    private boolean glowOn = true;
    private double tonightIllum = Double.NaN;
    static final double GLOW_ILLUM = 0.45;
    private Button glowToggle;
    static final String HELP = "Hold the phone with both hands, elbows against your chest or a wall. It takes 150 pictures in five "
            + "seconds and keeps the sharpest.";
    /** The label level the photo on the screen was drawn with. */
    private int shownLevel = -1;
    private Bitmap shown;
    private Uri savedPlain, savedLabelled, savedMap, savedNoGlow;
    /** The burst is a crossing's: the stations' silhouettes are found and laid on the photo. */
    private boolean transitShot;
    /** What crossed the Moon in the photo: "ISS", "Tiangong", or a mystery; and its story. */
    private String crossingLabel = "", crossingStory = "";
    private Locator.Place place;
    private MediaActionSound clicks;

    /** The open Moonshot screen (the watch's shutter and countdown reach it), or null. */
    static volatile MoonActivity current;
    /** What the status line says, and what the photo came to, for the watch. */
    private volatile String watchWords = "", watchDone = "";

    // the Transit Hunter: the crossings near here, and the one the camera is waiting for
    private List<TransitFinder.Transit> transits;
    private TransitFinder.Transit armed;
    private Button catchIt, mapIt;
    private TextView transitBody;
    private final Runnable tickTransit = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };
    private boolean firedArmed;
    private boolean askedPrecise, askedFix;
    private String countdownWords;
    /** The last whole second the countdown beeped at (one beep a second, ten to five). */
    private long beepedAt = -1;
    /** Frames for a crossing: nine seconds, from four before it, for the orbit's timing to be a little off. */
    static final int TRANSIT_FRAMES = 270;
    static final long TRANSIT_LEAD_MS = 4000;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        place = Locator.best(this, prefs);
        clicks = new MediaActionSound();
        clicks.load(MediaActionSound.SHUTTER_CLICK);
        clicks.load(MediaActionSound.STOP_VIDEO_RECORDING);
        clicks.load(MediaActionSound.FOCUS_COMPLETE);
        clicks.load(MediaActionSound.START_VIDEO_RECORDING);
        build();
    }

    // ------------------------------------------------------------------------------------------------
    // the screen

    private void build() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.BLACK);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(14), dp(16), dp(24));
        sv.addView(col);
        setContentView(sv);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("MOONSHOT", 28, GOLD, true);
        title.setLetterSpacing(0.15f);
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = button("Done", false);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        top.addView(close);
        col.addView(top);
        moonLine = text("", 15, WHITE, false);
        col.addView(moonLine, gap(4));
        moonLine.setText(tonight());

        // the telephoto's picture, the Moon ringed, and its close-up in the corner
        FrameLayout liveBox = new FrameLayout(this);
        texture = new TextureView(this);
        liveBox.addView(texture, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new Overlay(this);
        liveBox.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        closeUp = new ImageView(this);
        closeUp.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(Color.BLACK);
        cb.setStroke(dp(1), GOLD);
        closeUp.setBackground(cb);
        closeUp.setPadding(dp(1), dp(1), dp(1), dp(1));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(dp(150), dp(150), Gravity.TOP | Gravity.END);
        cl.setMargins(0, dp(8), dp(8), 0);
        liveBox.addView(closeUp, cl);
        status = text("Opening the telephoto…", 15, WHITE, true);
        status.setShadowLayer(dp(3), 0, 0, Color.BLACK);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        sl.setMargins(dp(10), 0, dp(10), dp(8));
        liveBox.addView(status, sl);
        col.addView(liveBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0));
        final FrameLayout lb = liveBox;
        liveBox.post(new Runnable() {
            @Override
            public void run() {
                // the picture is 4:3 on its side: three wide, four tall
                ViewGroup.LayoutParams p = lb.getLayoutParams();
                p.height = lb.getWidth() * 4 / 3;
                lb.setLayoutParams(p);
            }
        });
        details = text("", 12, DIM, false);
        col.addView(details, gap(6));

        shutter = button("Shoot the Moon", true);
        shutter.setTextSize(22);
        shutter.setMinHeight(dp(64));
        shutter.setEnabled(false);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (camera != null) {
                    clicks.play(MediaActionSound.SHUTTER_CLICK);
                    transitShot = false;
                    camera.glowWant = glowOn && crescent() ? MoonCamera.GLOW : 0;
                    camera.shoot(MoonCamera.BURST);
                }
            }
        });
        col.addView(shutter, gap(12));
        shutterHelp = text(HELP, 13, GREY, false);
        col.addView(shutterHelp, gap(6));
        // the Earth's glow (3.1): on a crescent, brighter frames after the burst for the dark part
        glowToggle = chip("", true);
        glowToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                glowOn = !glowOn;
                glowStyle();
            }
        });
        col.addView(glowToggle, gap(8));
        refocus = button("Focus again", false);
        refocus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (camera != null) camera.refocus();
            }
        });
        col.addView(refocus, gap(8));

        // the stacking, then the photo
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(ColorStateList.valueOf(GOLD));
        bar.setVisibility(View.GONE);
        col.addView(bar, gap(14));
        barText = text("", 14, WHITE, false);
        barText.setVisibility(View.GONE);
        col.addView(barText, gap(4));
        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        resultBox.setVisibility(View.GONE);
        col.addView(resultBox, gap(14));

        // the Space Station's crossings of the Moon near here
        transitBox = card();
        col.addView(transitBox, gap(18));
        showTransits();

        TextView rice = text(MoonSites.RICE + "\n— President John F. Kennedy, Rice University, 12 September 1962", 13, GREY, false);
        rice.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        col.addView(rice, gap(22));
        glowStyle();
    }

    /** Tonight's Moon a crescent: the Earth's glow on its dark part worth shooting (3.1). */
    private boolean crescent() {
        return !Double.isNaN(tonightIllum) && tonightIllum <= GLOW_ILLUM;
    }

    /** The Earth's glow's switch (3.1): there on a crescent, on or off, and the shutter's help to match. */
    private void glowStyle() {
        if (glowToggle == null) return;
        boolean offered = crescent();
        glowToggle.setVisibility(offered ? View.VISIBLE : View.GONE);
        glowToggle.setText(glowOn ? "Earth's glow too: on (4 more seconds)" : "Earth's glow too: off");
        chipStyle(glowToggle, glowOn);
        shutterHelp.setText(HELP + (offered && glowOn ? " Then 120 brighter ones for the Earth's glow on the dark part: keep holding "
                + "until the second click." : ""));
    }

    /** "Waxing gibbous, 57% lit, 31 degrees up in the south-east" (or when it rises). */
    private String tonight() {
        long now = System.currentTimeMillis();
        double lat = place != null ? place.latDeg : Double.NaN, lon = place != null ? place.lonDeg : Double.NaN;
        MoonGeo.View v = MoonGeo.view(now, Double.isNaN(lat) ? 0 : lat, Double.isNaN(lon) ? 0 : lon);
        tonightIllum = v.illum;
        String phase = MoonLook.phaseName(v) + String.format(Locale.US, ", %.0f%% lit", v.illum * 100);
        if (place == null) return phase + ". Where you are isn't known yet: allow the location in the main screen for the Moon's height.";
        if (v.alt > 0) return phase + String.format(Locale.US, ", %.0f° up in the %s", v.alt, compass(v.az));
        long rise = next(now, lat, lon, true);
        return phase + (rise > 0 ? ". Below the horizon: it rises at " + clock(rise) + " in the " + compass(MoonGeo.view(rise, lat, lon).az)
                : ". Below the horizon for the next day");
    }

    /** The next moment (within a day) the Moon rises ({@code up}) or sets, to two minutes; -1 if none. */
    static long next(long from, double lat, double lon, boolean up) {
        boolean was = MoonGeo.view(from, lat, lon).alt > 0;
        for (long t = from + 120_000L; t < from + 86_400_000L; t += 120_000L) {
            boolean is = MoonGeo.view(t, lat, lon).alt > 0;
            if (is != was && is == up) return t;
            was = is;
        }
        return -1;
    }

    static String compass(double az) {
        String[] n = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return n[(int) Math.round(((az % 360) + 360) % 360 / 45) % 8];
    }

    private String clock(long t) {
        return new SimpleDateFormat("h:mm a", Locale.US).format(new Date(t));
    }

    // ------------------------------------------------------------------------------------------------
    // the camera

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        boolean cam = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if (!cam || (!fine && !askedPrecise)) {
            // the precise place for the Transit Hunter, asked once a visit with the camera (Android wants both location kinds asked together)
            askedPrecise = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_CAMERA);
            return;
        }
        startCamera();
        freshPlace();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code != REQ_CAMERA) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera();
        else say("The Moonshot needs the camera: allow it in Settings > Apps > Night Sky > Permissions.");
        freshPlace();
    }

    /** A fresh fix (precise if allowed): the Moon's line and the crossings again if the place changed. */
    private void freshPlace() {
        if (askedFix || !Locator.allowed(this)) return;
        askedFix = true;
        Locator.refresh(this, prefs, main, 20_000L, new Locator.Listener() {
            @Override
            public void onPlace(Locator.Place p) {
                if (p == null || isFinishing()) return;
                Locator.Place old = place;
                place = p;
                moonLine.setText(tonight());
                glowStyle();
                if (camera != null) camera.setMoon(MoonGeo.view(System.currentTimeMillis(), p.latDeg, p.lonDeg));
                double moved = old == null ? 1e9 : 111.2 * Math.hypot(p.latDeg - old.latDeg, (p.lonDeg - old.lonDeg) * Math.cos(Math.toRadians(p.latDeg)));
                if (moved > 0.3 && armed == null) showTransits();
            }
        });
    }

    private void startCamera() {
        if (camera != null) return;
        if (texture.isAvailable()) open(texture.getSurfaceTexture());
        else {
            texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                    open(st);
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
        camera = new MoonCamera(this, this);
        if (place != null) camera.setMoon(MoonGeo.view(System.currentTimeMillis(), place.latDeg, place.lonDeg));
        String why = camera.open(st);
        if (why != null) {
            camera = null;
            say(why);
        }
    }

    /** The status line, also for the watch. */
    private void say(String w) {
        status.setText(w);
        watchWords = w;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (current == this) current = null;
        if (camera != null) {
            camera.close();
            camera = null;
        }
        shutter.setEnabled(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        jobCancelled = true;
        main.removeCallbacks(tickTransit);
        clicks.release();
    }

    @Override
    public void onLive(MoonCamera.Live l) {
        live = l;
        String w = l.stage == MoonCamera.Stage.SHOOTING ? String.format(Locale.US, "Shooting: %d of %d, hold steady", l.got, l.want)
                : l.stage == MoonCamera.Stage.GLOW ? String.format(Locale.US, "The Earth's glow: %d of %d, keep holding steady", l.got, l.want)
                : l.words;
        // waiting for a crossing: the countdown first, and what the camera needs if it isn't ready
        if (armed != null && !firedArmed && countdownWords != null) w = countdownWords + (l.stage == MoonCamera.Stage.READY ? "" : "\n" + l.words);
        say(w);
        details.setText(l.lens + (l.exposure.isEmpty() ? "" : " · " + l.exposure) + (l.focus.isEmpty() ? "" : " · " + l.focus)
                + (l.fps > 0 ? String.format(Locale.US, " · %.0f frames a second", l.fps) : ""));
        boolean ready = l.stage == MoonCamera.Stage.READY && job == null;
        shutter.setEnabled(ready);
        shutter.setText(l.stage == MoonCamera.Stage.SHOOTING ? String.format(Locale.US, "Shooting %d of %d", l.got, l.want)
                : l.stage == MoonCamera.Stage.GLOW ? String.format(Locale.US, "Earth's glow %d of %d", l.got, l.want) : "Shoot the Moon");
        glowToggle.setEnabled(l.stage != MoonCamera.Stage.SHOOTING && l.stage != MoonCamera.Stage.GLOW);
        refocus.setEnabled(l.stage == MoonCamera.Stage.READY);
        Bitmap b = MoonCamera.bitmap(l);
        if (b != null) closeUp.setImageBitmap(b);
        overlay.invalidate();
    }

    @Override
    public void onProblem(String why) {
        say(why);
        shutter.setEnabled(false);
    }

    @Override
    public void onBurst(final MoonCamera.Burst b) {
        clicks.play(MediaActionSound.STOP_VIDEO_RECORDING);
        if (transitShot && armed != null) {
            catchIt.setText("Caught " + armed.name + "'s crossing: stacking");
            armed = null;
        }
        if (b.frames.size() < 12) {
            say("The Moon slipped out of the picture: only " + b.frames.size() + " frames. Try again, braced against something.");
            return;
        }
        stack(b);
    }

    /** The Moon ringed on the telephoto's picture (the frame turned as the screen shows it). */
    private final class Overlay extends View {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        Overlay(android.content.Context c) {
            super(c);
            ring.setStyle(Paint.Style.STROKE);
            ring.setColor(GOLD);
            ring.setStrokeWidth(dp(2));
        }

        @Override
        protected void onDraw(Canvas c) {
            MoonCamera.Live l = live;
            if (l == null || l.spot == null || !l.spot.ok || l.frameW <= 0 || l.stage == MoonCamera.Stage.SEARCHING) return;
            int so = camera != null && camera.spec != null ? camera.spec.sensorOrientation : 90;
            double nx = l.spot.x / l.frameW, ny = l.spot.y / l.frameH, vx, vy;
            switch (((so % 360) + 360) % 360) {
                case 90: vx = 1 - ny; vy = nx; break;
                case 180: vx = 1 - nx; vy = 1 - ny; break;
                case 270: vx = ny; vy = 1 - nx; break;
                default: vx = nx; vy = ny; break;
            }
            float r = (float) Math.max(dp(14), l.spot.r / Math.max(l.frameW, l.frameH) * getHeight() * 1.3);
            c.drawCircle((float) (vx * getWidth()), (float) (vy * getHeight()), r, ring);
        }
    }

    // ------------------------------------------------------------------------------------------------
    // the stack

    private void stack(final MoonCamera.Burst b) {
        if (job != null) return;
        shutter.setEnabled(false);
        bar.setVisibility(View.VISIBLE);
        barText.setVisibility(View.VISIBLE);
        bar.setProgress(0);
        barText.setText("Stacking " + b.frames.size() + " frames…");
        jobCancelled = false;
        final long t = b.startMs;
        final Locator.Place pl = place;
        // the Earth's glow's frames (3.1), if enough came: the stack's share of the bar, then theirs
        final boolean glow = b.glow.size() >= Earthshine.MIN_FRAMES;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                String fail = null;
                MoonStack.Result r = null;
                MoonGeo.View v = null;
                MoonLook.Picture p = null, p1 = null, pg = null;
                Earthshine.Result es = null;
                try {
                    v = MoonGeo.view(t, pl != null ? pl.latDeg : 0, pl != null ? pl.lonDeg : 0);
                    MoonStack.Options o = new MoonStack.Options();
                    o.expectedRadius = b.rExp;
                    // always looked for: a crossing nobody waited for (a plane, a bird, a satellite) is a catch too
                    o.transit = true;
                    o.brightLimbPa = v.chi;
                    // the sky's up at the top where it is known; else north up, as astronomers have it
                    o.outUpPa = pl != null ? v.q : 0;
                    if (pl == null) {
                        for (MoonStack.Frame f : b.frames) f.upPa = Double.NaN;
                        for (MoonStack.Frame f : b.glow) f.upPa = Double.NaN;
                    }
                    r = MoonStack.stack(b.frames, b.spec, o, progress(0, glow ? 0.8f : 0.9f));
                    android.util.Log.i("NightSky", r != null ? r.log : "moon stack: nothing");
                    if (r == null || r.r == null) fail = "No Moon in the frames: point the phone at it and try again.";
                    else {
                        if (glow) {
                            try {
                                es = Earthshine.merge(b.glow, b.glowGains, b.glowGain, r, b.spec, progress(0.8f, 0.9f));
                                android.util.Log.i("NightSky", es.log);
                            } catch (RuntimeException | OutOfMemoryError e) {
                                // the glow is a bonus: the stacked photo stands without it
                                android.util.Log.w("NightSky", "earthshine", e);
                                es = null;
                            }
                        }
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                barText.setText("Finishing the photo…");
                            }
                        });
                        p = MoonLook.enlarge(r.r, r.g, r.b, r.w, r.h, r.cx, r.cy, r.radius, r.upPa);
                        p1 = MoonLook.enlarge(r.r1, r.g1, r.b1, r.w, r.h, r.cx, r.cy, r.radius, r.upPa);
                        if (es != null && es.ok) {
                            try {
                                pg = MoonLook.enlarge(es.r, es.g, es.b, es.w, es.h, r.cx, r.cy, r.radius, r.upPa, es.level, es.black, es.moonCx,
                                        es.moonCy, es.moonR);
                            } catch (RuntimeException | OutOfMemoryError e) {
                                android.util.Log.w("NightSky", "earthshine photo", e);
                                pg = null;
                            }
                        }
                    }
                } catch (CancelledException e) {
                    fail = "";
                } catch (RuntimeException | OutOfMemoryError e) {
                    android.util.Log.w("NightSky", "moon stack", e);
                    fail = "The stack went wrong: " + e;
                }
                final String f = fail;
                final MoonStack.Result rr = r;
                final MoonGeo.View vv = v;
                final MoonLook.Picture pp = p, pp1 = p1, ppg = pg;
                final Earthshine.Result ee = es;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        job = null;
                        bar.setVisibility(View.GONE);
                        barText.setVisibility(View.GONE);
                        if (isFinishing()) return;
                        if (f != null) {
                            if (!f.isEmpty()) say(f);
                            return;
                        }
                        shown(rr, vv, pp, pp1, ee, ppg, b);
                    }
                });
            }
        }, "moonshot-stack");
        job = th;
        th.start();
    }

    /** The bar and the watch told how the work goes, its stage's fraction over {@code from} to {@code to} of the bar. */
    private Progress progress(final float from, final float to) {
        return new Progress() {
            @Override
            public void update(final String stage, final float fraction) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        bar.setProgress(Math.round((from + (to - from) * fraction) * 1000));
                        barText.setText(stage + "…");
                        watchDone = String.format(Locale.US, "%s, %.0f%%", stage, fraction * 100);
                    }
                });
            }

            @Override
            public boolean cancelled() {
                return jobCancelled;
            }
        };
    }

    /**
     * The photo is made: shown, and saved three ways (as it is, labelled, as a Moon map), from the photo with the
     * Earth's glow when there is one (3.1; {@code es}, {@code pg}), and then once more without it.
     */
    private void shown(MoonStack.Result r, MoonGeo.View v, MoonLook.Picture p, MoonLook.Picture p1, Earthshine.Result es, MoonLook.Picture pg,
                       MoonCamera.Burst b) {
        result = r;
        view = v;
        base = p;
        before = p1;
        glowResult = es;
        glowPic = es != null && es.ok ? pg : null;
        baseMineral = null;
        look = glowPic != null ? LOOK_GLOW : LOOK_STACK;
        mineral = false;
        resultBox.removeAllViews();
        resultBox.setVisibility(View.VISIBLE);
        nameCrossing(r, b);
        // the crossing just shot is over: the list again without it (no Catch for it on the screen or the watch)
        if (transitShot) showTransits();
        String what = r.crossing != null ? "Your Moon, with " + crossingLabel + " crossing it" : glowPic != null ? "Your Moon, with the Earth's glow"
                : "Your Moon";
        watchDone = r.crossing == null && glowPic == null ? "Your Moon is ready" : what + ", is ready";
        resultBox.addView(text(what, 20, GOLD, true));
        String howText = String.format(Locale.US, "The %d sharpest of %d frames, lined up and stacked: the edge's blur %.1f px in one frame, %.1f "
                + "stacked and sharpened.", r.used, r.total, r.edgeSingle / 2, r.edgeSharp / 2);
        if (r.crossing != null) {
            howText += String.format(Locale.US, " %s crossed the lit Moon in %.2f seconds and was caught %d times, %.0f ms apart: every silhouette is on "
                    + "the photo.", crossingLabel, r.crossing.lastS - r.crossing.firstS, r.crossing.frames.length,
                    r.crossing.frames.length > 1 ? (r.crossing.lastS - r.crossing.firstS) / (r.crossing.frames.length - 1) * 1000 : 33);
        }
        if (glowPic != null) {
            howText += String.format(Locale.US, " Then %d frames %.0f times brighter for the Earth's glow: %d of them lined up on the Moon's dark edge "
                    + "and merged into the night side, where the Sun's light doesn't burn them white.", es.total, b.glowGain, es.used);
        } else if (!b.glow.isEmpty()) {
            howText += " The Earth's glow on the dark part was too faint to show this time (a thinner crescent, a darker sky and a steadier "
                    + "hold all help).";
        }
        TextView how = text(howText, 13, GREY, false);
        resultBox.addView(how, gap(4));
        photo = new ZoomImageView(this);
        photo.setBackgroundColor(Color.BLACK);
        resultBox.addView(photo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, getResources().getDisplayMetrics().widthPixels - dp(32)));
        photo.setTapListener(new ZoomImageView.TapListener() {
            @Override
            public void onTap(float x, float y) {
                tapped(x, y);
            }
        });
        LinearLayout row1 = new LinearLayout(this);
        final Button bBefore = chip("One frame", false), bAfter = chip("Stacked", look == LOOK_STACK),
                bGlow = glowPic != null ? chip("Earth's glow", true) : null;
        row1.addView(bBefore, weight());
        row1.addView(bAfter, weight());
        if (bGlow != null) row1.addView(bGlow, weight());
        resultBox.addView(row1, gap(8));
        LinearLayout row2 = new LinearLayout(this);
        final Button lNone = chip("No labels", false), lFamous = chip("Landings", true), lAll = chip("Every one", false),
                lMap = chip("Moon map", false);
        row2.addView(lNone, weight());
        row2.addView(lFamous, weight());
        row2.addView(lAll, weight());
        row2.addView(lMap, weight());
        resultBox.addView(row2, gap(6));
        final Button min = chip("Mineral colours", false);
        resultBox.addView(min, gap(6));
        View.OnClickListener pick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // the mineral colours are the stack's: picking them shows it, and picking another look turns them off
                if (v == bBefore) {
                    look = LOOK_ONE;
                    mineral = false;
                } else if (v == bAfter) look = LOOK_STACK;
                else if (v == bGlow) {
                    look = LOOK_GLOW;
                    mineral = false;
                } else if (v == lNone) labelLevel = MoonLook.LABELS_NONE;
                else if (v == lFamous) labelLevel = MoonLook.LABELS_FAMOUS;
                else if (v == lAll) labelLevel = MoonLook.LABELS_ALL;
                else if (v == lMap) labelLevel = MoonLook.LABELS_MAP;
                else if (v == min) {
                    mineral = !mineral;
                    if (mineral) look = LOOK_STACK;
                }
                chipStyle(bBefore, look == LOOK_ONE);
                chipStyle(bAfter, look == LOOK_STACK);
                if (bGlow != null) chipStyle(bGlow, look == LOOK_GLOW);
                chipStyle(lNone, labelLevel == MoonLook.LABELS_NONE);
                chipStyle(lFamous, labelLevel == MoonLook.LABELS_FAMOUS);
                chipStyle(lAll, labelLevel == MoonLook.LABELS_ALL);
                chipStyle(lMap, labelLevel == MoonLook.LABELS_MAP);
                chipStyle(min, mineral);
                int was = shownLevel;
                boolean wasGlow = shownLook == LOOK_GLOW;
                redraw();
                if (labelLevel != was || (look == LOOK_GLOW) != wasGlow) storyText.setText(hint());
            }
        };
        for (Button x : new Button[]{bBefore, bAfter, bGlow, lNone, lFamous, lAll, lMap, min}) if (x != null) x.setOnClickListener(pick);
        storyText = text("", 14, WHITE, false);
        LinearLayout story = card();
        story.addView(storyText);
        resultBox.addView(story, gap(10));
        chipStyle(lNone, labelLevel == MoonLook.LABELS_NONE);
        chipStyle(lFamous, labelLevel == MoonLook.LABELS_FAMOUS);
        chipStyle(lAll, labelLevel == MoonLook.LABELS_ALL);
        chipStyle(lMap, labelLevel == MoonLook.LABELS_MAP);
        LinearLayout row3 = new LinearLayout(this);
        Button share = button("Share", true), again = button("Shoot again", false);
        row3.addView(share, weight());
        row3.addView(again, weight());
        resultBox.addView(row3, gap(10));
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Uri u = labelLevel == MoonLook.LABELS_NONE ? savedPlain : labelLevel == MoonLook.LABELS_MAP ? savedMap : savedLabelled;
                // the stack without the Earth's glow, plain (the labelled ones are saved with the glow)
                if (look == LOOK_STACK && savedNoGlow != null && labelLevel == MoonLook.LABELS_NONE) u = savedNoGlow;
                if (u == null) u = savedPlain;
                if (u == null) return;
                Intent s = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(Intent.createChooser(s, "Share your Moon"));
            }
        });
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resultBox.setVisibility(View.GONE);
                if (live != null) onLive(live);
            }
        });
        redraw();
        storyText.setText(hint());
        save(b);
        if (live != null) onLive(live);
    }

    /**
     * What the card under the photo says before anything is tapped: the crossing's story, the Earth's glow's (3.1, when
     * it shows), how to get a place's story.
     */
    private String hint() {
        String lead = result != null && result.crossing != null ? crossingStory + "\n\n" : "";
        if (look == LOOK_GLOW && glowResult != null && view != null) lead += MoonLook.glowStory(glowResult.level, view) + "\n\n";
        if (labelLevel == MoonLook.LABELS_MAP) {
            String best = view != null ? MoonLook.bestTonight(mapLabels, view) : "";
            return lead + "Tap a sea, a crater or a mountain range for its story." + (best.isEmpty() ? "" : "\n\n" + best);
        }
        if (labelLevel == MoonLook.LABELS_NONE) return lead + "Choose Landings or Moon map to see what's where.";
        return lead + "Tap a landing site for its story.";
    }

    /** What crossed: the station that was due then (from the Transit Hunter's list), else a mystery flyer. */
    private void nameCrossing(MoonStack.Result r, MoonCamera.Burst b) {
        crossingLabel = "";
        crossingStory = "";
        if (r.crossing == null) return;
        long mid = b.startMs + Math.round((r.crossing.firstS + r.crossing.lastS) / 2 * 1000);
        TransitFinder.Transit due = null;
        if (transits != null) {
            for (TransitFinder.Transit t : transits) if (Math.abs(Transits.when(t) - mid) < 20_000) due = t;
        }
        if (due == null && armed != null && Math.abs(Transits.when(armed) - mid) < 20_000) due = armed;
        if (due != null && due.norad == TransitFinder.ISS) {
            crossingLabel = "ISS";
            crossingStory = "The International Space Station: 109 metres across, about 400 km up, going 7.7 km every second, with people "
                    + "living aboard since November 2000. " + (due.rangeKm > 0 ? String.format(Locale.US, "It was %.0f km from you.", due.rangeKm) : "");
        } else if (due != null) {
            crossingLabel = "Tiangong";
            crossingStory = "Tiangong, China's space station, about 400 km up and crewed since 2021. "
                    + (due.rangeKm > 0 ? String.format(Locale.US, "It was %.0f km from you.", due.rangeKm) : "");
        } else {
            crossingLabel = "Mystery flyer";
            crossingStory = "Something flew straight across the Moon while the phone was shooting: a plane, a bird, a bat or a "
                    + "satellite. No space station was due, so it's a mystery!";
        }
    }

    /** The picture the chips choose: one frame, the stack, or the stack with the Earth's glow. */
    private MoonLook.Picture picture() {
        return look == LOOK_ONE ? before : look == LOOK_GLOW && glowPic != null ? glowPic : base;
    }

    /** The photo as the chips say: one frame, stacked or with the Earth's glow, mineral colours or not, labelled or not. */
    private void redraw() {
        if (base == null) return;
        MoonLook.Picture p = picture();
        boolean one = look == LOOK_ONE;
        int[] px = p.argb;
        if (mineral && p == base) {
            if (baseMineral == null) {
                float[][] m = MoonLook.mineral(result.r, result.g, result.b, result.w, result.h, 4);
                baseMineral = MoonLook.enlarge(m[0], m[1], m[2], result.w, result.h, result.cx, result.cy, result.radius, result.upPa).argb;
            }
            px = baseMineral;
        }
        Bitmap bm = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888);
        bm.setPixels(px, 0, p.w, 0, 0, p.w, p.h);
        CanvasPainter cp = new CanvasPainter(bm);
        labels = new ArrayList<MoonLook.Label>();
        mapLabels = new ArrayList<MoonLook.MapLabel>();
        if (labelLevel == MoonLook.LABELS_MAP) {
            // with the Earth's glow the night side shows too: its seas and brightest craters named
            mapLabels = MoonLook.mapLayout(p, view, cp, 30, p == glowPic);
            MoonLook.paintMap(cp, p, mapLabels, 30);
            if (!one) MoonLook.track(cp, p, result, crossingLabel, 30);
        } else if (labelLevel != MoonLook.LABELS_NONE) {
            labels = MoonLook.layout(p, view, labelLevel, cp, 30);
            labelSize = MoonLook.fit(cp, p, labels, 30);
            labels = MoonLook.layout(p, view, labelLevel, cp, labelSize);
            MoonLook.paint(cp, p, labels, labelSize);
            if (!one) MoonLook.track(cp, p, result, crossingLabel, labelSize);
        }
        shownLevel = labelLevel;
        shownLook = look;
        MoonLook.captions(cp, p, one ? "ONE FRAME" : labelLevel == MoonLook.LABELS_MAP ? "MOON MAP" : "MOONSHOT", caption(p == glowPic), 30);
        Bitmap old = shown;
        shown = bm;
        photo.show(bm, true);
        if (old != null && old != bm) old.recycle();
    }

    /** The caption under the photo; {@code glow}: with the frames merged for the Earth's glow. */
    private String caption(boolean glow) {
        String when = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(new Date(System.currentTimeMillis()));
        String where = place != null ? String.format(Locale.US, "%.1f°%s, %.1f°%s", Math.abs(place.latDeg), place.latDeg >= 0 ? "N" : "S",
                Math.abs(place.lonDeg), place.lonDeg >= 0 ? "E" : "W") : "";
        return MoonLook.caption(view, result, when, where, glow && glowResult != null ? glowResult.used : 0);
    }

    private void tapped(float x, float y) {
        if (shown == null) return;
        if (labelLevel == MoonLook.LABELS_MAP) {
            MoonLook.MapLabel m = MoonLook.mapAt(mapLabels, x, y, 50);
            if (m != null) storyText.setText(MoonLook.mapStory(m));
            return;
        }
        if (labels.isEmpty()) return;
        CanvasPainter cp = new CanvasPainter(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        MoonLook.Label l = MoonLook.at(labels, x, y, 60, cp, picture(), labelSize);
        if (l == null) return;
        storyText.setText(MoonLook.siteStory(l.site, l.sunAlt));
    }

    /**
     * Saves the photo three ways in Pictures/NightSky: as it is, labelled (famous landings), and as a Moon map; with
     * the Earth's glow when there is one (3.1), and then the stack once more without it.
     */
    private void save(final MoonCamera.Burst b) {
        // the last shot's photos aren't this one's: Share waits for these
        savedPlain = savedLabelled = savedMap = savedNoGlow = null;
        final boolean glow = glowPic != null;
        final MoonLook.Picture p = glow ? glowPic : base, stacked = base;
        final MoonGeo.View v = view;
        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(b.startMs));
        final String cap = caption(glow), capStacked = caption(false);
        final MoonStack.Result rr = result;
        final String label = crossingLabel;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap plain = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888);
                    plain.setPixels(p.argb, 0, p.w, 0, 0, p.w, p.h);
                    CanvasPainter cp = new CanvasPainter(plain);
                    MoonLook.captions(cp, p, "MOONSHOT", cap, 30);
                    MediaSaver.Exif ex = exif(b, p);
                    final Uri u1 = MediaSaver.saveJpeg(MoonActivity.this, plain, "Moonshot_" + stamp, ex);
                    Bitmap lab = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888);
                    lab.setPixels(p.argb, 0, p.w, 0, 0, p.w, p.h);
                    CanvasPainter lp = new CanvasPainter(lab);
                    List<MoonLook.Label> ls = MoonLook.layout(p, v, MoonLook.LABELS_FAMOUS, lp, 30);
                    float fs = MoonLook.fit(lp, p, ls, 30);
                    ls = MoonLook.layout(p, v, MoonLook.LABELS_FAMOUS, lp, fs);
                    MoonLook.paint(lp, p, ls, fs);
                    MoonLook.track(lp, p, rr, label, fs);
                    MoonLook.captions(lp, p, "MOONSHOT", cap, 30);
                    final Uri u2 = MediaSaver.saveJpeg(MoonActivity.this, lab, "Moonshot_" + stamp + "_landings", exif(b, p));
                    lab.recycle();
                    // the Moon map (3.1): the seas, craters and mountains named on it (with the glow, the night side's too)
                    Bitmap map = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888);
                    map.setPixels(p.argb, 0, p.w, 0, 0, p.w, p.h);
                    CanvasPainter mp = new CanvasPainter(map);
                    MoonLook.paintMap(mp, p, MoonLook.mapLayout(p, v, mp, 30, glow), 30);
                    MoonLook.track(mp, p, rr, label, 30);
                    MoonLook.captions(mp, p, "MOON MAP", cap, 30);
                    final Uri u3 = MediaSaver.saveJpeg(MoonActivity.this, map, "Moonshot_" + stamp + "_map", exif(b, p));
                    map.recycle();
                    plain.recycle();
                    // the stack without the glow (3.1): the sunlit Moon alone, as before
                    Uri u4 = null;
                    if (glow) {
                        Bitmap st = Bitmap.createBitmap(stacked.w, stacked.h, Bitmap.Config.ARGB_8888);
                        st.setPixels(stacked.argb, 0, stacked.w, 0, 0, stacked.w, stacked.h);
                        CanvasPainter sp = new CanvasPainter(st);
                        MoonLook.captions(sp, stacked, "MOONSHOT", capStacked, 30);
                        u4 = MediaSaver.saveJpeg(MoonActivity.this, st, "Moonshot_" + stamp + "_noglow", exif(b, stacked));
                        st.recycle();
                    }
                    final Uri u4f = u4;
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            savedPlain = u1;
                            savedLabelled = u2;
                            savedMap = u3;
                            savedNoGlow = u4f;
                            if (u1 != null) {
                                prefs.setLastResultUri(u1.toString());
                                if (!isFinishing()) toast(glow ? "Saved in Pictures/NightSky: with the Earth's glow, with the landings, as a Moon map, and "
                                        + "without the glow" : "Saved in Pictures/NightSky: plain, with the landings, and as a Moon map");
                            }
                        }
                    });
                } catch (java.io.IOException | RuntimeException | OutOfMemoryError e) {
                    android.util.Log.w("NightSky", "moonshot save", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) toast("Couldn't save the photo: " + e.getMessage());
                        }
                    });
                }
            }
        }, "moonshot-save").start();
    }

    private MediaSaver.Exif exif(MoonCamera.Burst b, MoonLook.Picture p) {
        MediaSaver.Exif e = new MediaSaver.Exif();
        e.make = android.os.Build.MANUFACTURER;
        e.model = android.os.Build.MODEL;
        e.description = "Night Sky Moonshot: " + result.used + " of " + result.total + " frames stacked"
                + (p == glowPic && glowResult != null ? ", " + glowResult.used + " more for the Earth's glow" : "") + ", " + b.lens;
        e.exposureSeconds = b.exposureNs * 1e-9;
        e.iso = b.iso;
        e.fNumber = b.spec.fNumber;
        e.focalLengthMm = b.spec.focalLengthMm;
        e.time = new Date(b.startMs);
        e.width = p.w;
        e.height = p.h;
        return e;
    }

    // ------------------------------------------------------------------------------------------------
    // the Space Station over the Moon

    private void showTransits() {
        transitBox.removeAllViews();
        transitBox.addView(text("Transit Hunter", 18, GOLD, true));
        transitBox.addView(text("The Space Station crossing the Moon near you", 13, GREY, false), gap(2));
        transitBody = text("Working out the space stations' paths…", 14, WHITE, false);
        transitBox.addView(transitBody, gap(8));
        catchIt = button("", true);
        catchIt.setVisibility(View.GONE);
        transitBox.addView(catchIt, gap(10));
        mapIt = button("", false);
        mapIt.setVisibility(View.GONE);
        transitBox.addView(mapIt, gap(8));
        final Locator.Place pl = place;
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder why = new StringBuilder();
                List<TransitFinder.Transit> l = null;
                try {
                    l = Transits.find(MoonActivity.this, pl, why);
                } catch (RuntimeException e) {
                    android.util.Log.w("NightSky", "transits", e);
                    why.append("The crossings couldn't be worked out: ").append(e.getMessage());
                }
                final List<TransitFinder.Transit> ll = l;
                String ws = l != null ? Transits.words(l) : why.toString();
                if (l != null && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ws += "\n\nYour place is only approximate (within a couple of km): turn on Precise location for Night Sky in "
                            + "Settings to know whether you're inside a strip.";
                }
                final String w = ws;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        transits = ll;
                        transitBody.setText(w);
                        offerTransit();
                    }
                });
            }
        }, "moonshot-transits").start();
    }

    /** "Catch it" for the next crossing seen from here (within six hours), else the way to the nearest strip. */
    private void offerTransit() {
        if (transits == null || transits.isEmpty()) return;
        final TransitFinder.Transit h = Transits.nextHere(transits);
        long now = System.currentTimeMillis();
        if (h != null && Transits.when(h) - now < 6 * 3600_000L) {
            catchIt.setVisibility(View.VISIBLE);
            catchIt.setText("Catch " + h.name + " at " + new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(Transits.when(h))));
            catchIt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (armed == h) {
                        armed = null;
                        catchIt.setText("Catch " + h.name + " at " + new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(Transits.when(h))));
                        if (live != null) onLive(live);
                        return;
                    }
                    armed = h;
                    firedArmed = false;
                    beepedAt = -1;
                    catchIt.setText("Waiting for " + h.name + " (tap to stop)");
                    tick();
                }
            });
        }
        final TransitFinder.Transit first = transits.get(0);
        if (!first.here()) {
            mapIt.setVisibility(View.VISIBLE);
            mapIt.setText("Show the nearest strip on the map (" + Transits.km(first.distKm) + " " + compass(first.bearingDeg) + ")");
            mapIt.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String q = String.format(Locale.US, "%.5f,%.5f", first.lat, first.lon);
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:" + q + "?q=" + q + "(" + Uri.encode(Transits.capital(first.name)
                            + " crosses the Moon here, " + Transits.day(Transits.when(first))) + ")"));
                    try {
                        startActivity(i);
                    } catch (android.content.ActivityNotFoundException e) {
                        toast("No maps app: the strip's middle is at " + q);
                    }
                }
            });
        }
    }

    /** The countdown to the armed crossing: the burst starts {@link #TRANSIT_LEAD_MS} before it. */
    private void tick() {
        main.removeCallbacks(tickTransit);
        TransitFinder.Transit a = armed;
        if (a == null || isFinishing()) return;
        long left = Transits.when(a) - System.currentTimeMillis();
        if (left < -10_000) {
            armed = null;
            catchIt.setVisibility(View.GONE);
            return;
        }
        if (!firedArmed && left <= TRANSIT_LEAD_MS) {
            if (camera != null && camera.stage == MoonCamera.Stage.READY && job == null) {
                firedArmed = true;
                transitShot = true;
                // a crossing's frames are all at the lit Moon's exposure: no glow after them
                camera.glowWant = 0;
                camera.shoot(TRANSIT_FRAMES);
                clicks.play(MediaActionSound.START_VIDEO_RECORDING);
                catchIt.setText("Shooting " + a.name + "'s crossing…");
            } else if (left < -2000) {
                armed = null;
                catchIt.setText("Missed it: the camera wasn't on the Moon");
                return;
            }
        }
        if (!firedArmed) {
            countdownWords = Transits.capital(a.name) + " crosses the Moon " + Transits.countdown(left) + ". Keep the Moon in the picture: the phone shoots by itself.";
            if (live != null) onLive(live);
            else say(countdownWords);
            // a beep a second from ten to five, as at a launch: then the phone shoots, and it has to be held still
            long sec = (left + 999) / 1000;
            if (sec <= 10 && sec > 4 && sec != beepedAt) {
                beepedAt = sec;
                clicks.play(MediaActionSound.FOCUS_COMPLETE);
            }
        }
        main.postDelayed(tickTransit, left > 120_000 ? 1000 : 250);
    }

    // ------------------------------------------------------------------------------------------------
    // the watch (3.0): its Moon screen's shutter, Catch and countdown

    /**
     * The Moonshot's state for the watch's status: open, ready to shoot, what it says, the crossing it waits for. Returns
     * what of it counts as a change.
     */
    static String watchState(com.brandon.nightsky.remote.Json j) {
        MoonActivity a = current;
        j.put("open", a != null);
        if (a == null) return "closed";
        MoonCamera.Live l = a.live;
        boolean ready = l != null && l.stage == MoonCamera.Stage.READY && a.job == null && a.camera != null;
        String stage = l != null ? l.stage.name() : "OPENING";
        // the countdown's words change every second: the watch counts down itself
        String words = a.armed != null && !a.firedArmed && a.countdownWords != null ? "" : a.watchWords;
        if (words.length() > 120) words = words.substring(0, 119) + "…";
        j.put("ready", ready).put("stage", stage).put("words", words);
        String done = a.job != null || !a.watchDone.isEmpty() ? a.watchDone : "";
        if (!done.isEmpty()) j.put("done", done);
        TransitFinder.Transit t = a.armed;
        long armedAt = t != null && !a.firedArmed ? Transits.when(t) : 0;
        if (armedAt > 0) j.put("armedAt", armedAt).put("armedName", t.name);
        TransitFinder.Transit h = Transits.nextHere(a.transits);
        boolean canCatch = t == null && h != null && Transits.when(h) - System.currentTimeMillis() < 6 * 3600_000L;
        j.put("canCatch", canCatch);
        return "open|" + ready + "|" + stage + "|" + words + "|" + done + "|" + armedAt + "|" + canCatch;
    }

    /** A command from the watch ("shoot", "catch"), run on the screen's thread: what to tell the watch. */
    static String fromWatch(final String what) {
        final MoonActivity a = current;
        if (a == null) return "Open the Moonshot on the phone first";
        final String[] out = {"The phone didn't answer"};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        a.main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    out[0] = a.watchCommand(what);
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    private String watchCommand(String what) {
        if (isFinishing() || camera == null) return "The Moonshot's camera isn't on";
        if ("shoot".equals(what)) {
            if (job != null) return "Still stacking the last one";
            if (live == null || live.stage != MoonCamera.Stage.READY) return live != null ? live.words : "The camera isn't ready";
            shutter.performClick();
            return "Shooting the Moon: hold the phone steady";
        }
        if ("catch".equals(what)) {
            TransitFinder.Transit h = Transits.nextHere(transits);
            if (h == null) return "No crossing seen from here soon";
            if (armed == h) return "Already waiting for " + h.name;
            if (catchIt.getVisibility() != View.VISIBLE) return "It's more than six hours away";
            catchIt.performClick();
            return "The phone shoots " + h.name + " at " + new SimpleDateFormat("h:mm:ss a", Locale.US).format(new Date(Transits.when(h)));
        }
        return "The phone doesn't know that";
    }

    // ------------------------------------------------------------------------------------------------
    // the look

    private int dp(int v) {
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
        return t;
    }

    private LinearLayout card() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setStroke(dp(1), LINE);
        g.setCornerRadius(dp(12));
        l.setBackground(g);
        return l;
    }

    private Button button(String s, boolean primary) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xFF546E7A, primary ? Color.BLACK : GOLD}));
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        if (primary) {
            g.setColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {android.R.attr.state_pressed}, {}},
                    new int[]{0xFF263238, 0xFFFFB300, GOLD}));
        } else {
            g.setColor(CARD);
            g.setStroke(dp(1), LINE);
        }
        b.setBackground(g);
        b.setStateListAnimator(null);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        return b;
    }

    private Button chip(String s, boolean on) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setStateListAnimator(null);
        b.setPadding(dp(4), dp(8), dp(4), dp(8));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        chipStyle(b, on);
        return b;
    }

    private void chipStyle(Button b, boolean on) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(12));
        g.setColor(on ? 0xFF3E2F00 : Color.BLACK);
        g.setStroke(dp(on ? 2 : 1), on ? GOLD : LINE);
        b.setBackground(g);
        b.setTextColor(on ? GOLD : GREY);
        b.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    static TimeZone zone() {
        return TimeZone.getDefault();
    }
}
