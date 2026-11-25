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
 * Moon near here are listed below ({@link TransitFinder}).
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
    private float labelSize = 30;
    private int labelLevel = MoonLook.LABELS_FAMOUS;
    private boolean showBefore, mineral;
    private Bitmap shown;
    private Uri savedPlain, savedLabelled;
    /** The burst is a crossing's: the stations' silhouettes are found and laid on the photo. */
    private boolean transitShot;
    /** What crossed the Moon in the photo: "ISS", "Tiangong", or a mystery; and its story. */
    private String crossingLabel = "", crossingStory = "";
    private Locator.Place place;
    private MediaActionSound clicks;

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
                    camera.shoot(MoonCamera.BURST);
                }
            }
        });
        col.addView(shutter, gap(12));
        shutterHelp = text("Hold the phone with both hands, elbows against your chest or a wall. It takes 150 pictures in five "
                + "seconds and keeps the sharpest.", 13, GREY, false);
        col.addView(shutterHelp, gap(6));
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
    }

    /** "Waxing gibbous, 57% lit, 31 degrees up in the south-east" (or when it rises). */
    private String tonight() {
        long now = System.currentTimeMillis();
        double lat = place != null ? place.latDeg : Double.NaN, lon = place != null ? place.lonDeg : Double.NaN;
        MoonGeo.View v = MoonGeo.view(now, Double.isNaN(lat) ? 0 : lat, Double.isNaN(lon) ? 0 : lon);
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
        else status.setText("The Moonshot needs the camera: allow it in Settings > Apps > Night Sky > Permissions.");
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
            status.setText(why);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
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
        String w = l.stage == MoonCamera.Stage.SHOOTING ? String.format(Locale.US, "Shooting: %d of %d, hold steady", l.got, l.want) : l.words;
        // waiting for a crossing: the countdown first, and what the camera needs if it isn't ready
        if (armed != null && !firedArmed && countdownWords != null) w = countdownWords + (l.stage == MoonCamera.Stage.READY ? "" : "\n" + l.words);
        status.setText(w);
        details.setText(l.lens + (l.exposure.isEmpty() ? "" : " · " + l.exposure) + (l.focus.isEmpty() ? "" : " · " + l.focus)
                + (l.fps > 0 ? String.format(Locale.US, " · %.0f frames a second", l.fps) : ""));
        boolean ready = l.stage == MoonCamera.Stage.READY && job == null;
        shutter.setEnabled(ready);
        shutter.setText(l.stage == MoonCamera.Stage.SHOOTING ? String.format(Locale.US, "Shooting %d of %d", l.got, l.want) : "Shoot the Moon");
        refocus.setEnabled(l.stage == MoonCamera.Stage.READY);
        Bitmap b = MoonCamera.bitmap(l);
        if (b != null) closeUp.setImageBitmap(b);
        overlay.invalidate();
    }

    @Override
    public void onProblem(String why) {
        status.setText(why);
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
            status.setText("The Moon slipped out of the picture: only " + b.frames.size() + " frames. Try again, braced against something.");
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
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                String fail = null;
                MoonStack.Result r = null;
                MoonGeo.View v = null;
                MoonLook.Picture p = null, p1 = null;
                try {
                    v = MoonGeo.view(t, pl != null ? pl.latDeg : 0, pl != null ? pl.lonDeg : 0);
                    MoonStack.Options o = new MoonStack.Options();
                    o.expectedRadius = b.rExp;
                    // always looked for: a crossing nobody waited for (a plane, a bird, a satellite) is a catch too
                    o.transit = true;
                    o.brightLimbPa = v.chi;
                    // the sky's up at the top where it is known; else north up, as astronomers have it
                    o.outUpPa = pl != null ? v.q : 0;
                    if (pl == null) for (MoonStack.Frame f : b.frames) f.upPa = Double.NaN;
                    r = MoonStack.stack(b.frames, b.spec, o, new Progress() {
                        @Override
                        public void update(final String stage, final float fraction) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    bar.setProgress(Math.round(fraction * 900));
                                    barText.setText(stage + "…");
                                }
                            });
                        }

                        @Override
                        public boolean cancelled() {
                            return jobCancelled;
                        }
                    });
                    android.util.Log.i("NightSky", r != null ? r.log : "moon stack: nothing");
                    if (r == null || r.r == null) fail = "No Moon in the frames: point the phone at it and try again.";
                    else {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                barText.setText("Finishing the photo…");
                            }
                        });
                        p = MoonLook.enlarge(r.r, r.g, r.b, r.w, r.h, r.cx, r.cy, r.radius, r.upPa);
                        p1 = MoonLook.enlarge(r.r1, r.g1, r.b1, r.w, r.h, r.cx, r.cy, r.radius, r.upPa);
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
                final MoonLook.Picture pp = p, pp1 = p1;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        job = null;
                        bar.setVisibility(View.GONE);
                        barText.setVisibility(View.GONE);
                        if (isFinishing()) return;
                        if (f != null) {
                            if (!f.isEmpty()) status.setText(f);
                            return;
                        }
                        shown(rr, vv, pp, pp1, b);
                    }
                });
            }
        }, "moonshot-stack");
        job = th;
        th.start();
    }

    /** The photo is made: shown, and saved twice (as it is, and labelled). */
    private void shown(MoonStack.Result r, MoonGeo.View v, MoonLook.Picture p, MoonLook.Picture p1, MoonCamera.Burst b) {
        result = r;
        view = v;
        base = p;
        before = p1;
        baseMineral = null;
        showBefore = false;
        mineral = false;
        resultBox.removeAllViews();
        resultBox.setVisibility(View.VISIBLE);
        nameCrossing(r, b);
        resultBox.addView(text(r.crossing == null ? "Your Moon" : "Your Moon, with " + crossingLabel + " crossing it", 20, GOLD, true));
        String howText = String.format(Locale.US, "The %d sharpest of %d frames, lined up and stacked: the edge's blur %.1f px in one frame, %.1f "
                + "stacked and sharpened.", r.used, r.total, r.edgeSingle / 2, r.edgeSharp / 2);
        if (r.crossing != null) {
            howText += String.format(Locale.US, " %s crossed the lit Moon in %.2f seconds and was caught %d times, %.0f ms apart: every silhouette is on "
                    + "the photo.", crossingLabel, r.crossing.lastS - r.crossing.firstS, r.crossing.frames.length,
                    r.crossing.frames.length > 1 ? (r.crossing.lastS - r.crossing.firstS) / (r.crossing.frames.length - 1) * 1000 : 33);
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
        final Button bBefore = chip("One frame", false), bAfter = chip("Stacked", true);
        row1.addView(bBefore, weight());
        row1.addView(bAfter, weight());
        resultBox.addView(row1, gap(8));
        LinearLayout row2 = new LinearLayout(this);
        final Button lNone = chip("No labels", false), lFamous = chip("Landings", true), lAll = chip("Every one", false);
        row2.addView(lNone, weight());
        row2.addView(lFamous, weight());
        row2.addView(lAll, weight());
        resultBox.addView(row2, gap(6));
        final Button min = chip("Mineral colours", false);
        resultBox.addView(min, gap(6));
        View.OnClickListener pick = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (v == bBefore || v == bAfter) showBefore = v == bBefore;
                else if (v == lNone) labelLevel = MoonLook.LABELS_NONE;
                else if (v == lFamous) labelLevel = MoonLook.LABELS_FAMOUS;
                else if (v == lAll) labelLevel = MoonLook.LABELS_ALL;
                else if (v == min) mineral = !mineral;
                chipStyle(bBefore, showBefore);
                chipStyle(bAfter, !showBefore);
                chipStyle(lNone, labelLevel == MoonLook.LABELS_NONE);
                chipStyle(lFamous, labelLevel == MoonLook.LABELS_FAMOUS);
                chipStyle(lAll, labelLevel == MoonLook.LABELS_ALL);
                chipStyle(min, mineral);
                redraw();
            }
        };
        for (Button x : new Button[]{bBefore, bAfter, lNone, lFamous, lAll, min}) x.setOnClickListener(pick);
        storyText = text(r.crossing != null ? crossingStory + "\n\nTap a landing site for its story." : "Tap a landing site for its story.", 14, WHITE, false);
        LinearLayout story = card();
        story.addView(storyText);
        resultBox.addView(story, gap(10));
        LinearLayout row3 = new LinearLayout(this);
        Button share = button("Share", true), again = button("Shoot again", false);
        row3.addView(share, weight());
        row3.addView(again, weight());
        resultBox.addView(row3, gap(10));
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Uri u = labelLevel == MoonLook.LABELS_NONE ? savedPlain : savedLabelled;
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
        save(b);
        if (live != null) onLive(live);
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

    /** The photo as the chips say: one frame or stacked, mineral colours or not, labelled or not. */
    private void redraw() {
        if (base == null) return;
        MoonLook.Picture p = showBefore ? before : base;
        int[] px = p.argb;
        if (mineral && !showBefore) {
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
        if (labelLevel != MoonLook.LABELS_NONE) {
            labels = MoonLook.layout(p, view, labelLevel, cp, 30);
            labelSize = MoonLook.fit(cp, p, labels, 30);
            labels = MoonLook.layout(p, view, labelLevel, cp, labelSize);
            MoonLook.paint(cp, p, labels, labelSize);
            if (!showBefore) MoonLook.track(cp, p, result, crossingLabel, labelSize);
        }
        MoonLook.captions(cp, p, showBefore ? "ONE FRAME" : "MOONSHOT", caption(), 30);
        Bitmap old = shown;
        shown = bm;
        photo.show(bm, true);
        if (old != null && old != bm) old.recycle();
    }

    private String caption() {
        String when = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(new Date(System.currentTimeMillis()));
        String where = place != null ? String.format(Locale.US, "%.1f°%s, %.1f°%s", Math.abs(place.latDeg), place.latDeg >= 0 ? "N" : "S",
                Math.abs(place.lonDeg), place.lonDeg >= 0 ? "E" : "W") : "";
        return MoonLook.caption(view, result, when, where);
    }

    private void tapped(float x, float y) {
        if (labels.isEmpty() || shown == null) return;
        CanvasPainter cp = new CanvasPainter(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
        MoonLook.Label l = MoonLook.at(labels, x, y, 60, cp, showBefore ? before : base, labelSize);
        if (l == null) return;
        MoonSites.Site s = l.site;
        StringBuilder sb = new StringBuilder();
        sb.append(s.name).append(" · ").append(s.date).append(" · ").append(s.who).append("\n").append(s.place).append("\n\n");
        if (!s.crew.isEmpty()) sb.append(s.crew).append(".\n\n");
        sb.append(s.story);
        sb.append(String.format(Locale.US, "\n\nTonight the Sun is %s there%s.", l.sunUp ? String.format(Locale.US, "%.0f° up", l.sunAlt)
                : "down: it's night", l.sunUp && l.sunAlt < 12 ? ", low enough for long shadows" : ""));
        storyText.setText(sb.toString());
    }

    /** Saves the photo as it is and labelled (famous landings), in Pictures/NightSky. */
    private void save(final MoonCamera.Burst b) {
        final MoonLook.Picture p = base;
        final MoonGeo.View v = view;
        final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(b.startMs));
        final String cap = caption();
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
                    plain.recycle();
                    lab.recycle();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            savedPlain = u1;
                            savedLabelled = u2;
                            if (u1 != null) {
                                prefs.setLastResultUri(u1.toString());
                                if (!isFinishing()) toast("Saved in Pictures/NightSky, with and without the labels");
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
        e.description = "Night Sky Moonshot: " + result.used + " of " + result.total + " frames stacked, " + b.lens;
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
                camera.shoot(TRANSIT_FRAMES);
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
            else status.setText(countdownWords);
            if (left <= 10_000 && left > 5000) clicks.play(MediaActionSound.FOCUS_COMPLETE);
        }
        main.postDelayed(tickTransit, left > 120_000 ? 1000 : 250);
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
