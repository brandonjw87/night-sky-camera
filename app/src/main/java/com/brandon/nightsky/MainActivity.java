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
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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

import java.io.File;
import java.util.List;
import java.util.Locale;

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

    private final Handler main = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    private CaptureService service;
    private SessionController.Phase shownPhase;
    private long shownVersion = -1;
    private int shownPreview = -1;
    private boolean pendingStart, askedNotifications;
    private Boolean windowActive;

    // views of the current screen (whichever exist)
    private TextView headline, detail, stage;
    private ProgressBar bar;
    private ImageView live;
    private ZoomImageView zoom;
    private SeekBar resultBrightness;
    private Button saveVersion;
    private float shownBrightness = -1f;

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
        showHome();
        bindService(new Intent(this, CaptureService.class), conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(poll);
        if (shownPhase == null || shownPhase == SessionController.Phase.IDLE) showHome();
    }

    @Override
    protected void onPause() {
        super.onPause();
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
            lp.screenBrightness = 0.12f;
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        }
        getWindow().setAttributes(lp);
    }

    private void clearRefs() {
        headline = detail = stage = null;
        bar = null;
        live = null;
        zoom = null;
        resultBrightness = null;
        saveVersion = null;
        shownPreview = -1;
    }

    // ------------------------------------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------------------------------------

    private void showHome() {
        clearRefs();
        root.removeAllViews();
        ScrollView sv = new ScrollView(this);
        LinearLayout col = column();
        sv.addView(col);
        root.addView(sv, match());

        TextView title = text("Night Sky", 30, RED, true);
        col.addView(title);
        col.addView(text("Long exposures of the stars from your Pixel's main camera", 14, RED_DIM, false), gap(4));

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
            }
        };
        deep.setOnClickListener(modeClick);
        trails.setOnClickListener(modeClick);
        LinearLayout modes = row();
        modes.addView(deep, weight());
        modes.addView(trails, weight());
        col.addView(modes, gap(8));
        col.addView(modeHelp, gap(6));

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
        col.addView(snd, gap(18));
        col.addView(dng);

        Button start = button("Start", true);
        start.setTextSize(22);
        start.setMinHeight(dp(64));
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSession();
            }
        });
        col.addView(start, gap(24));
        col.addView(text("Tap Start, then lay the phone screen-down on the ground or a table with the back camera "
                + "facing the sky. It starts by itself once the phone is still, and beeps when it's done.", 14, RED_DIM, false), gap(10));

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

    private void startSession() {
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS}, 1);
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
                .putExtra(CaptureService.EXTRA_DNG, prefs.saveDng());
        startForegroundService(i);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (!pendingStart) return;
        pendingStart = false;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startSession();
        } else {
            new AlertDialog.Builder(this).setMessage("The app needs the camera to take the photo. You can allow it in "
                    + "Settings > Apps > Night Sky > Permissions.").setPositiveButton("OK", null).show();
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Session screens
    // ------------------------------------------------------------------------------------------------

    private void showArming() {
        clearRefs();
        root.removeAllViews();
        LinearLayout col = column();
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(col, match());
        headline = text("", 30, RED, true);
        headline.setGravity(Gravity.CENTER);
        detail = text("", 16, RED_DIM, false);
        detail.setGravity(Gravity.CENTER);
        col.addView(spacer(), new LinearLayout.LayoutParams(1, 0, 1f));
        col.addView(headline);
        col.addView(detail, gap(14));
        col.addView(spacer(), new LinearLayout.LayoutParams(1, 0, 1f));
        Button cancel = button("Cancel", false);
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
        col.addView(row2, gap(6));
        String where = "Saved in Pictures/NightSky" + (r != null && r.dng != null ? " (JPEG and DNG)" : "");
        col.addView(text(where, 12, RED_DIM, false), gap(4));
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
