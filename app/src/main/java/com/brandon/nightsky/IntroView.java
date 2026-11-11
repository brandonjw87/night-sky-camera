package com.brandon.nightsky;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.AssetFileDescriptor;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.IOException;

/**
 * The opening film (2.0): "Made by: Brandon J. Wallace" in brushed metal over a lake under the Milky Way, lit by a
 * camera flash. 2.2 seconds (res/raw/intro.mp4; how it was made is in the repository's intro folder), played over the
 * first screen when the app is opened from its icon, once each time the app starts.
 *
 * <p>It never stands in the app's way. A tap anywhere skips it; if the film can't be played, or shows no picture
 * within a second and a half, it's taken away; and however it goes it's gone four and a half seconds after it was put
 * up. The app's first screen is built and ready underneath the whole time.
 */
final class IntroView extends FrameLayout implements TextureView.SurfaceTextureListener {
    /** The setting: the film in its colours, in the app's night red (dimmer, for dark-adapted eyes), or not at all. */
    static final int COLOUR = 0, RED = 1, OFF = 2;
    /** The film's size and length; when its flash fires; when the fade to the app begins and how long it takes (ms). */
    static final int VIDEO_W = 1080, VIDEO_H = 2400, FILM_MS = 2200, FLASH_MS = 560, FADE_AT_MS = 1990, FADE_MS = 260;
    /** Without a picture this long after it's put up, the film is given up (ms). */
    static final int WATCHDOG_MS = 1500;
    /** However it goes, it's gone this long after it's put up (ms). */
    static final int LATEST_MS = 4500;
    /** The sound's level against the phone's media volume. */
    private static final float VOLUME = 0.7f;

    /** Played since the app started. */
    private static boolean played;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextureView texture;
    private final boolean sound;
    private final Runnable done;
    private MediaPlayer player;
    private Surface surface;
    /** The first picture has been shown; the film is over (or given up) and this view is on its way out or gone. */
    private boolean started, finished;
    /** What happened, for the log and the tests: "playing", "ended", "skipped", "no picture", "error: ...". */
    String outcome = "waiting";

    /**
     * Whether the film plays for this opening of the app: from its icon (not a shortcut, the tile, a notification or
     * the recent apps), the first time since the app started, with no session in progress, and not turned off.
     */
    static boolean wanted(Prefs prefs, Bundle state, Intent in) {
        if (played || state != null || in == null || prefs.intro() == OFF) return false;
        if (!Intent.ACTION_MAIN.equals(in.getAction()) || !in.hasCategory(Intent.CATEGORY_LAUNCHER)) return false;
        if ((in.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return false;
        CaptureService s = CaptureService.instance;
        if (s != null) {
            try {
                if (s.controller().status().phase != SessionController.Phase.IDLE) return false;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the film's sound plays: the app's sounds are on, the phone's ringer isn't silenced, and nothing else is
     * playing (it doesn't talk over music or a podcast).
     */
    static boolean soundWanted(Context c, Prefs prefs) {
        if (!prefs.sounds()) return false;
        try {
            AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.getRingerMode() == AudioManager.RINGER_MODE_NORMAL && !am.isMusicActive();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Puts the film over {@code a}'s screen and plays it in {@code look} ({@link #COLOUR} or {@link #RED}); {@code done}
     * (may be null) runs when it's gone. Returns the view, or null if it couldn't even be put up.
     */
    static IntroView play(Activity a, int look, boolean sound, Runnable done) {
        try {
            IntroView v = new IntroView(a, look == RED, sound, done);
            a.addContentView(v, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            played = true;
            return v;
        } catch (RuntimeException e) {
            android.util.Log.w("NightSky", "the opening film couldn't be shown: " + e);
            if (done != null) done.run();
            return null;
        }
    }

    /** For the tests: as if the app had just started. */
    static void forget() {
        played = false;
    }

    private IntroView(Context c, boolean red, boolean sound, Runnable done) {
        super(c);
        this.sound = sound;
        this.done = done;
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                finish("skipped");
            }
        });
        texture = new TextureView(c);
        texture.setAlpha(0f);
        if (red) texture.setLayerPaint(nightRed());
        texture.setSurfaceTextureListener(this);
        addView(texture, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!started) finish("no picture");
            }
        }, WATCHDOG_MS);
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                finish(started ? "ended" : "no picture");
            }
        }, LATEST_MS);
    }

    /** The film's light as the app's red (its luminance on the red of the app's lettering), a little dimmer. */
    static Paint nightRed() {
        float k = 0.86f, r = 0.898f * k, g = 0.224f * k, b = 0.208f * k;
        float lr = 0.2126f, lg = 0.7152f, lb = 0.0722f;
        ColorMatrix m = new ColorMatrix(new float[]{
                r * lr, r * lg, r * lb, 0, 0,
                g * lr, g * lg, g * lb, 0, 0,
                b * lr, b * lg, b * lb, 0, 0,
                0, 0, 0, 1, 0});
        Paint p = new Paint();
        p.setColorFilter(new ColorMatrixColorFilter(m));
        return p;
    }

    /** The film fills the view, cut at the sides or at the top and bottom where the screen's shape isn't the film's. */
    static Matrix cover(int w, int h) {
        Matrix m = new Matrix();
        if (w <= 0 || h <= 0) return m;
        float scale = Math.max(w / (float) VIDEO_W, h / (float) VIDEO_H);
        m.setScale(VIDEO_W * scale / w, VIDEO_H * scale / h, w / 2f, h / 2f);
        return m;
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
        if (finished) return;
        texture.setTransform(cover(w, h));
        try {
            surface = new Surface(st);
            player = new MediaPlayer();
            AssetFileDescriptor fd = getResources().openRawResourceFd(R.raw.intro);
            try {
                player.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            } finally {
                fd.close();
            }
            player.setSurface(surface);
            player.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build());
            float v = sound ? VOLUME : 0f;
            player.setVolume(v, v);
            player.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    if (finished) return;
                    try {
                        mp.start();
                    } catch (RuntimeException e) {
                        finish("error: " + e);
                    }
                }
            });
            player.setOnInfoListener(new MediaPlayer.OnInfoListener() {
                @Override
                public boolean onInfo(MediaPlayer mp, int what, int extra) {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) firstPicture();
                    return false;
                }
            });
            player.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp) {
                    // the last picture stays up while the view fades (the fade is under way by now)
                    if (!started) finish("ended");
                }
            });
            player.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    finish("error: " + what + "/" + extra);
                    return true;
                }
            });
            player.prepareAsync();
        } catch (IOException | RuntimeException e) {
            finish("error: " + e);
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
        texture.setTransform(cover(w, h));
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        finish(started ? "ended" : "no picture");
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
        // a picture has arrived, whether or not the player said so
        firstPicture();
    }

    /** The film's first picture is on the screen: it's shown, and its moments are timed from here. */
    private void firstPicture() {
        if (started || finished) return;
        started = true;
        outcome = "playing";
        texture.animate().alpha(1f).setDuration(90).start();
        int at = 0;
        try {
            if (player != null) at = Math.max(0, player.getCurrentPosition());
        } catch (RuntimeException ignored) {
        }
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!finished) performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
            }
        }, Math.max(0, FLASH_MS - at));
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished) return;
                animate().alpha(0f).setDuration(FADE_MS).withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        finish("ended");
                    }
                }).start();
            }
        }, Math.max(0, FADE_AT_MS - at));
    }

    /** Takes the film away, once: the player and its surface let go, the view off the screen, the app told. */
    void finish(String how) {
        finish(how, true);
    }

    private void finish(String how, boolean remove) {
        if (finished) return;
        finished = true;
        outcome = how;
        main.removeCallbacksAndMessages(null);
        try {
            animate().cancel();
        } catch (RuntimeException ignored) {
        }
        if (player != null) {
            try {
                player.release();
            } catch (RuntimeException ignored) {
            }
            player = null;
        }
        if (surface != null) {
            try {
                surface.release();
            } catch (RuntimeException ignored) {
            }
            surface = null;
        }
        if (how.startsWith("error") || how.equals("no picture")) android.util.Log.w("NightSky", "the opening film: " + how);
        if (remove && getParent() instanceof ViewGroup) {
            try {
                ((ViewGroup) getParent()).removeView(this);
            } catch (RuntimeException e) {
                setVisibility(GONE);
            }
        }
        if (done != null) done.run();
    }

    boolean finished() {
        return finished;
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // the app left for another one: nothing plays on behind it
        if (visibility != VISIBLE && !finished && isAttachedToWindow()) finish(started ? "ended" : "skipped");
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // the screen itself is going: nothing to take off it
        finish(started ? "ended" : "skipped", false);
    }
}
