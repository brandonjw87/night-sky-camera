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
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.brandon.nightsky.processing.CancelledException;
import com.brandon.nightsky.processing.JupiterGeo;
import com.brandon.nightsky.processing.JupiterLook;
import com.brandon.nightsky.processing.JupiterMeter;
import com.brandon.nightsky.processing.JupiterStack;
import com.brandon.nightsky.processing.JupiterStories;
import com.brandon.nightsky.processing.MoonStack;
import com.brandon.nightsky.processing.Progress;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Jupiter's moons (3.2), Galileo's notebook: Jupiter and its four big moons from the Pixel's 5x telephoto, each moon
 * named where it is, or why it isn't there. The screen says where Jupiter is tonight and draws the moons' line-up
 * from the sums, with tonight's crossings, shadows and eclipses. Point the phone at Jupiter: it finds it, sets the
 * exposure and focuses ({@link MoonCamera} in its Jupiter mode); Shoot Jupiter takes 90 frames of Jupiter and then 240
 * sixteen times brighter for its moons, stacked into the photo ({@link JupiterStack}), drawn and named
 * ({@link JupiterLook}), saved in the gallery plain and named, and written into the notebook page beside Galileo's own
 * seven nights of January 1610 and the nights to come. Tap a moon for its story; more stories below.
 */
public final class JupiterActivity extends Activity implements MoonCamera.Listener {
    static final int GOLD = 0xFFFFD54F, WHITE = 0xFFFFFFFF, GREY = 0xFFB0BEC5, DIM = 0xFF78909C, CARD = 0xFF101418, LINE = 0xFF37474F;
    private static final int REQ_CAMERA = 32;
    static final String HELP = "Hold the phone with both hands, elbows against your chest or a wall. Three seconds for Jupiter, then eight "
            + "more, much brighter, for its moons: keep holding until the second click.";
    /** The notebook's file (your nights), in the app's own files. */
    static final String NIGHTS = "jupiter_notebook.json";
    /** The notebook page's width (px), and how many of your nights it shows (the newest). */
    static final int PAGE_W = 1200, PAGE_NIGHTS = 8, COMING = 5;

    private Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private MoonCamera camera;
    private TextureView texture;
    private Overlay overlay;
    private ImageView closeUp, lineUp, notebook;
    private TextView status, details, tonight, lineUpWhen, events, storyText, storiesText, moonsText;
    private Button shutter, refocus;
    /** The page's scroll (3.2: the new photo is scrolled up into view). */
    private ScrollView scroll;
    private LinearLayout resultBox;
    private ProgressBar bar;
    private TextView barText;
    private ZoomImageView photo;
    private MoonCamera.Live live;
    private volatile Thread job;
    private volatile boolean jobCancelled;

    // the result
    private JupiterStack.Result result;
    /** The moon the last tap told of (-1: none), for a dot with two moons in it. */
    private int tapMoon = -1;
    private JupiterGeo.View view;
    private JupiterLook.Picture pic;
    private long shotMs;
    private boolean names = true;
    private Bitmap shown, pageShown, lineShown;
    private Uri savedPlain, savedNamed, savedPage;
    private Locator.Place place;
    private MediaActionSound clicks;
    /** Your nights in the notebook (oldest first). */
    private List<JupiterLook.Entry> nights = new ArrayList<JupiterLook.Entry>();
    private Button[] storyChips;
    private int story;

    /** The open Jupiter screen (the watch's shutter reaches it), or null. */
    static volatile JupiterActivity current;
    /** What the status line says, and what the photo came to, for the watch. */
    private volatile String watchWords = "", watchDone = "";

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
        nights = loadNights(this);
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
        scroll = sv;

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text("JUPITER'S MOONS", 26, GOLD, true);
        title.setLetterSpacing(0.12f);
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
        tonight = text("", 15, WHITE, false);
        col.addView(tonight, gap(4));
        // where it is (3.3): the Sky Window steers the phone to it, and comes back here once it's in the 5x's frame
        Button findIt = button("Find Jupiter in the sky", false);
        findIt.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(JupiterActivity.this, SkyWindowActivity.class).putExtra(SkyWindowActivity.EXTRA_FIND, "Jupiter")
                        .putExtra(SkyWindowActivity.EXTRA_FROM, "jupiter"));
            }
        });
        col.addView(findIt, gap(8));

        // tonight's line-up from the sums, and what happens tonight
        lineUp = new ImageView(this);
        lineUp.setAdjustViewBounds(true);
        lineUp.setScaleType(ImageView.ScaleType.FIT_CENTER);
        GradientDrawable lb = new GradientDrawable();
        lb.setColor(Color.BLACK);
        lb.setStroke(dp(1), LINE);
        lb.setCornerRadius(dp(10));
        lineUp.setBackground(lb);
        lineUp.setPadding(dp(2), dp(2), dp(2), dp(2));
        col.addView(lineUp, gap(10));
        lineUpWhen = text("", 12, DIM, false);
        col.addView(lineUpWhen, gap(4));
        events = text("", 13, GREY, false);
        col.addView(events, gap(6));

        // the telephoto's picture, Jupiter ringed, and its close-up in the corner
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
        col.addView(liveBox, gap(14));
        final FrameLayout box = liveBox;
        liveBox.post(new Runnable() {
            @Override
            public void run() {
                // the picture is 4:3 on its side: three wide, four tall
                ViewGroup.LayoutParams p = box.getLayoutParams();
                p.height = box.getWidth() * 4 / 3;
                box.setLayoutParams(p);
            }
        });
        details = text("", 12, DIM, false);
        col.addView(details, gap(6));

        shutter = button("Shoot Jupiter", true);
        shutter.setTextSize(22);
        shutter.setMinHeight(dp(64));
        shutter.setEnabled(false);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (camera == null) return;
                clicks.play(MediaActionSound.SHUTTER_CLICK);
                camera.glowWant = MoonCamera.MOONS;
                camera.shoot(MoonCamera.JUPITER_BURST);
            }
        });
        col.addView(shutter, gap(12));
        col.addView(text(HELP, 13, GREY, false), gap(6));
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

        // Galileo's notebook
        LinearLayout nb = card();
        nb.addView(text("Galileo's notebook", 18, GOLD, true));
        nb.addView(text("In January 1610 Galileo drew Jupiter as an O and the little stars beside it, night after night, and worked out that "
                + "they go round it. Your nights go on the same page, and the nights to come, from the sums, in pencil.", 13, GREY, false), gap(4));
        notebook = new ImageView(this);
        notebook.setAdjustViewBounds(true);
        notebook.setScaleType(ImageView.ScaleType.FIT_CENTER);
        nb.addView(notebook, gap(10));
        StringBuilder said = new StringBuilder("What Galileo wrote:");
        for (int i = 0; i < JupiterGeo.GALILEO_DAY.length; i++) {
            said.append("\nJan. ").append(JupiterGeo.GALILEO_DAY[i]).append(": ").append(JupiterGeo.GALILEO_SAID[i]);
        }
        TextView sw = text(said.toString(), 13, GREY, false);
        sw.setLineSpacing(0, 1.15f);
        nb.addView(sw, gap(10));
        Button sharePage = button("Share the notebook page", false);
        sharePage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sharePage();
            }
        });
        nb.addView(sharePage, gap(10));
        col.addView(nb, gap(18));

        // the stories
        LinearLayout st = card();
        st.addView(text("Stories", 18, GOLD, true));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        hs.addView(chips);
        storyChips = new Button[JupiterStories.TITLES.length];
        for (int i = 0; i < storyChips.length; i++) {
            final int k = i;
            Button c = chip(JupiterStories.TITLES[i], i == 0);
            c.setPadding(dp(12), dp(8), dp(12), dp(8));
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickStory(k);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chips.addView(c, lp);
            storyChips[i] = c;
        }
        st.addView(hs, gap(8));
        storiesText = text("", 14, WHITE, false);
        storiesText.setLineSpacing(0, 1.1f);
        st.addView(storiesText, gap(10));
        col.addView(st, gap(18));

        showTonight();
        pickStory(0);
        drawNotebook();
    }

    /** A story chosen from the chips. */
    private void pickStory(int k) {
        story = k;
        for (int i = 0; i < storyChips.length; i++) chipStyle(storyChips[i], i == k);
        storiesText.setText(JupiterStories.story(k, here(System.currentTimeMillis())));
    }

    /** Jupiter from here at a moment (from the middle of the Earth's map when the place isn't known: the moons are the same). */
    private JupiterGeo.View here(long t) {
        return JupiterGeo.view(t, place != null ? place.latDeg : 0, place != null ? place.lonDeg : 0);
    }

    /** Where Jupiter is tonight, the line-up drawn, and the night's crossings, shadows and eclipses. */
    private void showTonight() {
        long now = System.currentTimeMillis();
        TimeZone z = TimeZone.getDefault();
        if (place == null) {
            tonight.setText("Where you are isn't known yet: allow the location in the main screen and this screen says where Jupiter is. "
                    + "Look for the brightest 'star' that doesn't twinkle.");
            drawLineUp(here(now), "Where the sums put the moons now");
        } else {
            double lat = place.latDeg, lon = place.lonDeg;
            JupiterGeo.View v = JupiterGeo.view(now, lat, lon);
            long best = JupiterGeo.best(now, lat, lon, 24);
            String bright = "brighter than any star";
            String words;
            if (v.alt > 0 && JupiterGeo.goodSky(now, lat, lon)) {
                words = "Jupiter is " + JupiterGeo.where(v) + ", " + bright + ". Now is a good time.";
            } else if (v.alt > 0) {
                words = "Jupiter is " + JupiterGeo.where(v) + (JupiterGeo.sunAlt(now, lat, lon) >= -8 ? ", but the sky is too bright for its moons yet."
                        : ", low: the higher it is, the sharper.");
                if (best > 0) words += " Best at " + JupiterGeo.clock(best, z) + ".";
            } else {
                long rise = JupiterGeo.next(now, lat, lon, true);
                words = "Jupiter is below the horizon" + (rise > 0 ? ": it rises at " + JupiterGeo.clock(rise, z) + " in the "
                        + MoonActivity.compass(JupiterGeo.view(rise, lat, lon).az) : "") + ".";
                if (best > 0) words += " Best at " + JupiterGeo.clock(best, z) + ".";
                else words += " It's too close to the Sun to see these days.";
            }
            tonight.setText(words);
            long at = v.alt > 0 && JupiterGeo.goodSky(now, lat, lon) || best < 0 ? now : best;
            drawLineUp(JupiterGeo.view(at, lat, lon), at == now ? "Where the sums put the moons now" : "Where the sums put the moons at "
                    + JupiterGeo.clock(at, z) + " (" + JupiterLook.dayWord(at, now, z) + ")");
        }
        String h = JupiterLook.happenings(now, now + 12 * 3_600_000L, z);
        events.setText(h.isEmpty() ? "In the next 12 hours no moon crosses Jupiter, hides behind it or goes into its shadow."
                : "In the next 12 hours:\n" + h);
    }

    private void drawLineUp(JupiterGeo.View v, String when) {
        int w = 1080, h = JupiterLook.lineUpHeight(w);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.BLACK);
        JupiterLook.lineUp(new CanvasPainter(b), w, h, v, 40);
        lineUp.setImageBitmap(b);
        if (lineShown != null) lineShown.recycle();
        lineShown = b;
        lineUpWhen.setText(when);
    }

    // ------------------------------------------------------------------------------------------------
    // the notebook

    static List<JupiterLook.Entry> loadNights(android.content.Context c) {
        File f = new File(c.getFilesDir(), NIGHTS);
        if (!f.exists()) return new ArrayList<JupiterLook.Entry>();
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] all = new byte[(int) Math.min(f.length(), 1 << 20)];
            int n = 0, r;
            while (n < all.length && (r = in.read(all, n, all.length - n)) > 0) n += r;
            return JupiterLook.fromJson(new String(all, 0, n, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new ArrayList<JupiterLook.Entry>();
        }
    }

    static void saveNights(android.content.Context c, List<JupiterLook.Entry> nights) {
        File f = new File(c.getFilesDir(), NIGHTS), tmp = new File(c.getFilesDir(), NIGHTS + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(JupiterLook.toJson(nights).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            android.util.Log.w("NightSky", "jupiter notebook", e);
            return;
        }
        if (!tmp.renameTo(f)) android.util.Log.w("NightSky", "jupiter notebook: couldn't keep it");
    }

    /** The notebook page's parts: Galileo's nights, yours (the newest few), the nights to come here. */
    private List<JupiterLook.Section> sections() {
        List<JupiterLook.Section> secs = new ArrayList<JupiterLook.Section>();
        JupiterLook.Section g = new JupiterLook.Section("Galileo Galilei, Padua, January 1610", false);
        for (int i = 0; i < JupiterGeo.GALILEO_DAY.length; i++) g.nights.add(JupiterLook.galileo(i));
        secs.add(g);
        JupiterLook.Section mine = new JupiterLook.Section("Your nights", false);
        mine.nights.addAll(nights.subList(Math.max(0, nights.size() - PAGE_NIGHTS), nights.size()));
        secs.add(mine);
        if (place != null) {
            JupiterLook.Section next = new JupiterLook.Section("The nights to come (from the sums)", true);
            // (from tomorrow's night when tonight is already on the page, yours)
            long now = System.currentTimeMillis();
            boolean shotTonight = !nights.isEmpty() && JupiterLook.sameNight(nights.get(nights.size() - 1).t, now, place.lonDeg);
            next.nights.addAll(JupiterLook.coming(shotTonight ? JupiterLook.nextNight(now, place.lonDeg) : now, place.latDeg, place.lonDeg, COMING));
            if (!next.nights.isEmpty()) secs.add(next);
        }
        return secs;
    }

    /** Draws the notebook page (away from the screen's thread) and shows it. */
    private void drawNotebook() {
        final List<JupiterLook.Section> secs = sections();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Bitmap b = page(secs);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            notebook.setImageBitmap(b);
                            if (pageShown != null) pageShown.recycle();
                            pageShown = b;
                        }
                    });
                } catch (RuntimeException | OutOfMemoryError e) {
                    android.util.Log.w("NightSky", "notebook page", e);
                }
            }
        }, "jupiter-notebook").start();
    }

    static Bitmap page(List<JupiterLook.Section> secs) {
        int h = JupiterLook.pageHeight(PAGE_W, secs);
        Bitmap b = Bitmap.createBitmap(PAGE_W, h, Bitmap.Config.ARGB_8888);
        b.setPixels(JupiterLook.paper(PAGE_W, h), 0, PAGE_W, 0, 0, PAGE_W, h);
        JupiterLook.page(new CanvasPainter(b), PAGE_W, secs, TimeZone.getDefault());
        return b;
    }

    private void sharePage() {
        if (savedPage != null) {
            share(savedPage, "Share Galileo's notebook");
            return;
        }
        final List<JupiterLook.Section> secs = sections();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap b = page(secs);
                    final Uri u = MediaSaver.saveJpeg(JupiterActivity.this, b, "Galileos_notebook_" + stamp(System.currentTimeMillis()), pageExif(b));
                    b.recycle();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            savedPage = u;
                            if (u != null && !isFinishing()) share(u, "Share Galileo's notebook");
                        }
                    });
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    android.util.Log.w("NightSky", "notebook share", e);
                }
            }
        }, "jupiter-page").start();
    }

    private void share(Uri u, String title) {
        Intent s = new Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(s, title));
    }

    // ------------------------------------------------------------------------------------------------
    // the camera

    @Override
    protected void onResume() {
        super.onResume();
        current = this;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
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
        else say("Jupiter's moons need the camera: allow it in Settings > Apps > Night Sky > Permissions.");
        freshPlace();
    }

    private boolean askedFix;

    /** A fresh fix: where Jupiter is, the line-up and the camera's up again if the place changed. */
    private void freshPlace() {
        if (askedFix || !Locator.allowed(this)) return;
        askedFix = true;
        Locator.refresh(this, prefs, main, 20_000L, new Locator.Listener() {
            @Override
            public void onPlace(Locator.Place p) {
                if (p == null || isFinishing()) return;
                Locator.Place old = place;
                place = p;
                if (camera != null) camera.setJupiter(JupiterGeo.view(System.currentTimeMillis(), p.latDeg, p.lonDeg));
                double moved = old == null ? 1e9 : 111.2 * Math.hypot(p.latDeg - old.latDeg, (p.lonDeg - old.lonDeg) * Math.cos(Math.toRadians(p.latDeg)));
                if (moved > 1) {
                    showTonight();
                    drawNotebook();
                }
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
        camera = new MoonCamera(this, this, true);
        if (place != null) camera.setJupiter(JupiterGeo.view(System.currentTimeMillis(), place.latDeg, place.lonDeg));
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
        clicks.release();
    }

    @Override
    public void onLive(MoonCamera.Live l) {
        live = l;
        String w = l.stage == MoonCamera.Stage.SHOOTING ? String.format(Locale.US, "Shooting Jupiter: %d of %d, hold steady", l.got, l.want)
                : l.stage == MoonCamera.Stage.GLOW ? String.format(Locale.US, "Now its moons: %d of %d, keep holding steady", l.got, l.want)
                : l.words;
        say(w);
        details.setText(l.lens + (l.exposure.isEmpty() ? "" : " · " + l.exposure) + (l.focus.isEmpty() ? "" : " · " + l.focus)
                + (l.fps > 0 ? String.format(Locale.US, " · %.0f frames a second", l.fps) : ""));
        shutter.setEnabled(l.stage == MoonCamera.Stage.READY && job == null);
        shutter.setText(l.stage == MoonCamera.Stage.SHOOTING ? String.format(Locale.US, "Jupiter %d of %d", l.got, l.want)
                : l.stage == MoonCamera.Stage.GLOW ? String.format(Locale.US, "Its moons %d of %d", l.got, l.want) : "Shoot Jupiter");
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
        if (b.frames.size() < 12) {
            say("Jupiter slipped out of the picture: only " + b.frames.size() + " frames. Try again, braced against something.");
            return;
        }
        stack(b);
    }

    /** Jupiter ringed on the telephoto's picture (the frame turned as the screen shows it). */
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
            c.drawCircle((float) (vx * getWidth()), (float) (vy * getHeight()), dp(16), ring);
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
        barText.setText("Stacking " + (b.frames.size() + b.glow.size()) + " frames…");
        watchDone = "Stacking";
        jobCancelled = false;
        final Locator.Place pl = place;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                String fail = null;
                JupiterStack.Result r = null;
                JupiterGeo.View v = null;
                JupiterLook.Picture p = null;
                try {
                    v = JupiterGeo.view(b.startMs, pl != null ? pl.latDeg : 0, pl != null ? pl.lonDeg : 0);
                    JupiterStack.Options o = new JupiterStack.Options();
                    if (!Double.isNaN(b.arcsecPerPx) && b.arcsecPerPx > 0) o.arcsecPerPx = b.arcsecPerPx;
                    o.view = v;
                    if (pl == null) {
                        // the sky's up unknown: the turn is found from the moons alone
                        for (MoonStack.Frame f : b.frames) f.upPa = Double.NaN;
                        for (MoonStack.Frame f : b.glow) f.upPa = Double.NaN;
                    }
                    boolean moons = b.glow.size() >= 6;
                    r = JupiterStack.stack(b.frames, moons ? b.glow : null, moons ? b.glowGains : null, b.glowGain > 0 ? b.glowGain : JupiterMeter.MOONS_GAIN,
                            b.spec, o, progress(0, 0.9f));
                    android.util.Log.i("NightSky", r.log);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            barText.setText("Drawing the photo…");
                        }
                    });
                    p = JupiterLook.photo(r);
                } catch (CancelledException e) {
                    fail = "";
                } catch (RuntimeException | OutOfMemoryError e) {
                    android.util.Log.w("NightSky", "jupiter stack", e);
                    fail = e instanceof IllegalArgumentException ? "No Jupiter in the frames: point the phone at it and try again."
                            : "The stack went wrong: " + e;
                }
                final String f = fail;
                final JupiterStack.Result rr = r;
                final JupiterGeo.View vv = v;
                final JupiterLook.Picture pp = p;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        job = null;
                        bar.setVisibility(View.GONE);
                        barText.setVisibility(View.GONE);
                        if (isFinishing()) return;
                        if (f != null) {
                            watchDone = "";
                            if (!f.isEmpty()) say(f);
                            return;
                        }
                        shown(rr, vv, pp, b);
                    }
                });
            }
        }, "jupiter-stack");
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

    /** "Io, Europa and Callisto". */
    static String and(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(i == names.size() - 1 ? " and " : ", ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    /** The photo is made: shown, named, saved (plain and named), and the night written into the notebook. */
    private void shown(JupiterStack.Result r, JupiterGeo.View v, JupiterLook.Picture p, MoonCamera.Burst b) {
        result = r;
        view = v;
        pic = p;
        shotMs = b.startMs;
        resultBox.removeAllViews();
        resultBox.setVisibility(View.VISIBLE);
        bringIntoView(resultBox);
        List<String> found = new ArrayList<String>();
        for (int k = 0; k < 4; k++) if (r.status[k] == JupiterStack.FOUND || r.status[k] == JupiterStack.BLENDED) found.add(JupiterGeo.NAMES[k]);
        String what = found.isEmpty() ? "Your Jupiter" : "Your Jupiter, with " + and(found);
        watchDone = what + (found.isEmpty() ? " is ready" : ": ready");
        resultBox.addView(text(what, 20, GOLD, true));
        String how = String.format(Locale.US, "The %d sharpest of %d frames of Jupiter", r.used, r.total);
        if (r.moonsTotal > 0) how += String.format(Locale.US, ", then %d of %d frames %.0f times brighter for its moons", r.moonsUsed, r.moonsTotal, r.moonsGain);
        how += ": lined up on Jupiter, turned so its north is up, and stacked. Jupiter's glare is dimmed on the photo so a moon close to it "
                + "shows. East is on the left, as Galileo drew it.";
        resultBox.addView(text(how, 13, GREY, false), gap(4));
        photo = new ZoomImageView(this);
        photo.setBackgroundColor(Color.BLACK);
        int pw = getResources().getDisplayMetrics().widthPixels - dp(32);
        resultBox.addView(photo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, pw * JupiterLook.H / JupiterLook.W));
        photo.setTapListener(new ZoomImageView.TapListener() {
            @Override
            public void onTap(float x, float y) {
                tapped(x, y);
            }
        });
        LinearLayout row1 = new LinearLayout(this);
        final Button bNames = chip("Names", names), bPlain = chip("No names", !names);
        row1.addView(bNames, weight());
        row1.addView(bPlain, weight());
        resultBox.addView(row1, gap(8));
        View.OnClickListener pick = new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                names = x == bNames;
                chipStyle(bNames, names);
                chipStyle(bPlain, !names);
                redraw();
            }
        };
        bNames.setOnClickListener(pick);
        bPlain.setOnClickListener(pick);
        LinearLayout mc = card();
        moonsText = text(moonLines(r, b.startMs), 14, WHITE, false);
        moonsText.setLineSpacing(0, 1.15f);
        mc.addView(moonsText);
        resultBox.addView(mc, gap(10));
        storyText = text("Tap a moon on the photo for its story.", 14, WHITE, false);
        LinearLayout sc = card();
        sc.addView(storyText);
        resultBox.addView(sc, gap(10));
        LinearLayout row3 = new LinearLayout(this);
        Button share = button("Share", true), again = button("Shoot again", false);
        row3.addView(share, weight());
        row3.addView(again, weight());
        resultBox.addView(row3, gap(10));
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                Uri u = names ? savedNamed : savedPlain;
                if (u == null) u = savedPlain;
                if (u != null) share(u, "Share your Jupiter");
            }
        });
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View x) {
                resultBox.setVisibility(View.GONE);
                if (scroll != null) scroll.smoothScrollTo(0, 0);
                if (live != null) onLive(live);
            }
        });
        redraw();
        // the night into the notebook
        nights.add(JupiterLook.fromPhoto(r, v, b.startMs));
        while (nights.size() > JupiterLook.KEEP) nights.remove(0);
        final List<JupiterLook.Entry> keep = new ArrayList<JupiterLook.Entry>(nights);
        new Thread(new Runnable() {
            @Override
            public void run() {
                saveNights(JupiterActivity.this, keep);
            }
        }, "jupiter-nights").start();
        drawNotebook();
        save(b);
        if (live != null) onLive(live);
    }

    /** Each moon on its own line: where it is on the photo, or why it isn't there (and until when). */
    String moonLines(JupiterStack.Result r, long t) {
        TimeZone z = TimeZone.getDefault();
        List<JupiterGeo.Event> ev = JupiterGeo.events(t - 6 * 3_600_000L, t + 12 * 3_600_000L);
        StringBuilder sb = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            if (k > 0) sb.append('\n');
            sb.append(moonLine(r, k, t, ev, z));
        }
        return sb.toString();
    }

    static String moonLine(JupiterStack.Result r, int k, long t, List<JupiterGeo.Event> ev, TimeZone z) {
        String name = JupiterGeo.NAMES[k];
        switch (r.status[k]) {
            case JupiterStack.FOUND: {
                JupiterStack.Light l = r.moon[k];
                double widths = Math.abs(l.jx) / 2;
                String with = "";
                for (int b = 0; b < 4; b++) if (r.status[b] == JupiterStack.BLENDED && r.with[b] == k) with += ", with " + JupiterGeo.NAMES[b] + " in the same dot";
                return String.format(Locale.US, "%s: there, %s Jupiter-widths to the %s%s", name, widths < 10 ? String.format(Locale.US, "%.1f", widths)
                        : String.format(Locale.US, "%.0f", widths), l.jx < 0 ? "east (left)" : "west (right)", with);
            }
            case JupiterStack.BLENDED:
                return name + ": there, in the same dot as " + JupiterGeo.NAMES[r.with[k]] + ": the two are too close together for the phone to show apart";
            case JupiterStack.HIDDEN:
                return name + ": hidden behind Jupiter" + until(ev, k, JupiterGeo.HIDDEN, t, z);
            case JupiterStack.CROSSING:
                return name + ": crossing in front of Jupiter, lost in its light" + until(ev, k, JupiterGeo.TRANSIT, t, z);
            case JupiterStack.DARK:
                return name + ": in Jupiter's shadow, gone dark" + until(ev, k, JupiterGeo.ECLIPSE, t, z);
            case JupiterStack.GLARE:
                return name + ": right beside Jupiter, lost in its glare";
            case JupiterStack.OUTSIDE:
                return name + ": outside the photo";
            default:
                if (r.with[k] >= 0) return name + ": not picked out this time: it's right beside " + JupiterGeo.NAMES[r.with[k]] + ", lost in its light";
                return name + ": not seen this time (too faint against the sky)";
        }
    }

    /** " until about 10:42 PM" for the happening going on at t, or nothing. */
    static String until(List<JupiterGeo.Event> ev, int moon, int kind, long t, TimeZone z) {
        for (JupiterGeo.Event e : ev) {
            if (e.moon == moon && e.kind == kind && e.at(t) && e.endMs != Long.MAX_VALUE) return " until about " + JupiterGeo.clock(e.endMs, z);
        }
        return "";
    }

    /** The photo as the chips say: named or not; with its captions. */
    private void redraw() {
        if (pic == null) return;
        Bitmap bm = Bitmap.createBitmap(pic.w, pic.h, Bitmap.Config.ARGB_8888);
        bm.setPixels(pic.argb, 0, pic.w, 0, 0, pic.w, pic.h);
        CanvasPainter cp = new CanvasPainter(bm);
        if (names) JupiterLook.label(cp, pic, result, 34);
        else pic.labels.clear();
        JupiterLook.captions(cp, pic.w, pic.h, "JUPITER'S MOONS", caption(), 34);
        Bitmap old = shown;
        shown = bm;
        photo.show(bm, true);
        if (old != null && old != bm) old.recycle();
    }

    private String caption() {
        String when = new SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(new Date(shotMs));
        String where = place != null ? String.format(Locale.US, "%.1f°%s, %.1f°%s", Math.abs(place.latDeg), place.latDeg >= 0 ? "N" : "S",
                Math.abs(place.lonDeg), place.lonDeg >= 0 ? "E" : "W") : "";
        return JupiterLook.caption(result, view, when, where);
    }

    private void tapped(float x, float y) {
        if (pic == null || shown == null) return;
        if (!names) {
            storyText.setText("Choose Names to tap a moon for its story.");
            return;
        }
        if (Math.hypot(x - pic.cx, y - pic.cy) < Math.max(40, 3 * pic.rJ)) {
            storyText.setText(JupiterStories.jupiter(view));
            return;
        }
        JupiterLook.Label l = JupiterLook.at(pic, x, y, 80);
        if (l == null) return;
        if (l.moon < 0) {
            storyText.setText("A star far behind Jupiter, in our own galaxy: its light set out years to thousands of years ago. Jupiter's moons "
                    + "move from night to night; it doesn't.");
            return;
        }
        // a dot with two moons in it: the first tap the one found, the next the one beside it
        int k = l.moon, partner = -1;
        for (int b = 0; b < 4; b++) if (result.status[b] == JupiterStack.BLENDED && result.with[b] == l.moon) partner = b;
        if (partner >= 0 && tapMoon == l.moon) k = partner;
        tapMoon = k == l.moon ? k : -1;
        String line = moonLine(result, k, shotMs, JupiterGeo.events(shotMs - 6 * 3_600_000L, shotMs + 12 * 3_600_000L), TimeZone.getDefault());
        String more = partner >= 0 && k == l.moon ? "\n\nTap the dot again for " + JupiterGeo.NAMES[partner] + "'s story." : "";
        storyText.setText(line + "\n\n" + JupiterStories.moon(k) + more);
    }

    static String stamp(long t) {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(t));
    }

    /** Saves the photo twice in Pictures/NightSky (plain and named), and the notebook page with tonight on it. */
    private void save(final MoonCamera.Burst b) {
        savedPlain = savedNamed = savedPage = null;
        final JupiterLook.Picture p = pic;
        final JupiterStack.Result rr = result;
        final String cap = caption(), st = stamp(b.startMs);
        final List<JupiterLook.Section> secs = sections();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Bitmap plain = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888);
                    plain.setPixels(p.argb, 0, p.w, 0, 0, p.w, p.h);
                    JupiterLook.captions(new CanvasPainter(plain), p.w, p.h, "JUPITER'S MOONS", cap, 34);
                    final Uri u1 = MediaSaver.saveJpeg(JupiterActivity.this, plain, "Jupiter_" + st, exif(b, rr, p.w, p.h));
                    plain.recycle();
                    Bitmap named = Bitmap.createBitmap(p.w, p.h, Bitmap.Config.ARGB_8888);
                    named.setPixels(p.argb, 0, p.w, 0, 0, p.w, p.h);
                    CanvasPainter np = new CanvasPainter(named);
                    JupiterLook.Picture copy = new JupiterLook.Picture();
                    copy.w = p.w;
                    copy.h = p.h;
                    copy.cx = p.cx;
                    copy.cy = p.cy;
                    copy.k = p.k;
                    copy.rJ = p.rJ;
                    JupiterLook.label(np, copy, rr, 34);
                    JupiterLook.captions(np, p.w, p.h, "JUPITER'S MOONS", cap, 34);
                    final Uri u2 = MediaSaver.saveJpeg(JupiterActivity.this, named, "Jupiter_" + st + "_named", exif(b, rr, p.w, p.h));
                    named.recycle();
                    Bitmap pg = page(secs);
                    final Uri u3 = MediaSaver.saveJpeg(JupiterActivity.this, pg, "Galileos_notebook_" + st, pageExif(pg));
                    pg.recycle();
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            savedPlain = u1;
                            savedNamed = u2;
                            savedPage = u3;
                            if (u1 != null) {
                                prefs.setLastResultUri(u1.toString());
                                if (!isFinishing()) toast("Saved in Pictures/NightSky: plain, with the moons named, and the notebook page");
                            }
                        }
                    });
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    android.util.Log.w("NightSky", "jupiter save", e);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isFinishing()) toast("Couldn't save the photo: " + e.getMessage());
                        }
                    });
                }
            }
        }, "jupiter-save").start();
    }

    private MediaSaver.Exif exif(MoonCamera.Burst b, JupiterStack.Result r, int w, int h) {
        MediaSaver.Exif e = new MediaSaver.Exif();
        e.make = android.os.Build.MANUFACTURER;
        e.model = android.os.Build.MODEL;
        e.description = "Night Sky, Jupiter's moons: " + r.used + " of " + r.total + " frames of Jupiter and " + r.moonsUsed + " for its moons stacked, "
                + b.lens;
        e.exposureSeconds = b.exposureNs * 1e-9;
        e.iso = b.iso;
        e.fNumber = b.spec.fNumber;
        e.focalLengthMm = b.spec.focalLengthMm;
        e.time = new Date(b.startMs);
        e.width = w;
        e.height = h;
        return e;
    }

    private MediaSaver.Exif pageExif(Bitmap b) {
        MediaSaver.Exif e = new MediaSaver.Exif();
        e.make = android.os.Build.MANUFACTURER;
        e.model = android.os.Build.MODEL;
        e.description = "Night Sky: Galileo's notebook, his nights of January 1610 and yours";
        e.time = new Date();
        e.width = b.getWidth();
        e.height = b.getHeight();
        return e;
    }

    // ------------------------------------------------------------------------------------------------
    // the watch: the Moon screen's shutter reaches this screen too

    /** This screen's state for the watch's Moon screen (open, ready, what it says). What of it counts as a change. */
    static String watchState(com.brandon.nightsky.remote.Json j) {
        JupiterActivity a = current;
        j.put("open", a != null);
        if (a == null) return "closed";
        MoonCamera.Live l = a.live;
        boolean ready = l != null && l.stage == MoonCamera.Stage.READY && a.job == null && a.camera != null;
        String stage = l != null ? l.stage.name() : "OPENING";
        String words = a.watchWords;
        if (words.length() > 120) words = words.substring(0, 119) + "…";
        j.put("ready", ready).put("stage", stage).put("words", words).put("target", "jupiter");
        String done = a.job != null || !a.watchDone.isEmpty() ? a.watchDone : "";
        if (!done.isEmpty()) j.put("done", done);
        j.put("canCatch", false);
        return "jupiter|" + ready + "|" + stage + "|" + words + "|" + done;
    }

    /** A command from the watch ("shoot"), run on the screen's thread: what to tell the watch. */
    static String fromWatch(final String what) {
        final JupiterActivity a = current;
        if (a == null) return "Open Jupiter's moons on the phone first";
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
        if (isFinishing() || camera == null) return "The Jupiter camera isn't on";
        if ("shoot".equals(what)) {
            if (job != null) return "Still stacking the last one";
            if (live == null || live.stage != MoonCamera.Stage.READY) return live != null ? live.words : "The camera isn't ready";
            shutter.performClick();
            return "Shooting Jupiter: hold the phone steady";
        }
        return "Jupiter's screen can't catch a crossing";
    }

    // ------------------------------------------------------------------------------------------------
    // the look

    /**
     * Scrolls {@code v} up into view once it's laid out (3.2): the photo is made below the camera's picture, out of
     * sight on a phone that was just pointed at the sky.
     */
    private void bringIntoView(final View v) {
        v.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                v.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (scroll != null && v.getVisibility() == View.VISIBLE) scroll.smoothScrollTo(0, Math.max(0, v.getTop() - dp(8)));
            }
        });
    }

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
}
