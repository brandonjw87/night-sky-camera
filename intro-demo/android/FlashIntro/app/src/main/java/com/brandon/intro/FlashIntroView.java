package com.brandon.intro;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Looper;

/**
 * The camera flash intro as a full-screen view: a shutter opens on a night sky, a burst of flashes lights up
 * "MADE BY: BRANDON J. WALLACE" in 3D, and the stars trail behind the name. It lasts {@link #LENGTH} seconds.
 *
 * Use: add it over the app's first screen, call {@link #play} once {@link Listener#onIntroReady} arrives,
 * and remove it in {@link Listener#onIntroStopped} (finished = true). Forward the activity's onPause and
 * onResume. If {@link Listener#onIntroError} arrives instead, just remove it: the app works without it.
 *
 * It needs the files in {@code assets/intro/} and OpenGL ES 3.0.
 */
public final class FlashIntroView extends GLSurfaceView {
    public static final float LENGTH = FlashIntroRenderer.T_END;

    /** All calls arrive on the main thread. */
    public interface Listener {
        /** Ready to play; until then the view shows black. */
        void onIntroReady();

        /** Playback stopped at {@code t} seconds: at the end (finished) or because of {@link #pause}. */
        void onIntroStopped(float t, boolean finished);

        /** The phone could not draw the intro. The message is for a log, not for the screen. */
        void onIntroError(String message);
    }

    private final FlashIntroRenderer renderer;
    private final IntroSound sound;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private boolean playing;

    public FlashIntroView(Context context) {
        super(context);
        sound = new IntroSound(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 8, 0, 0);   // every phone has this one; the final pass writes alpha 1
        setPreserveEGLContextOnPause(true);
        renderer = new FlashIntroRenderer(context.getAssets(), new FlashIntroRenderer.Callbacks() {
            @Override
            public void onReady() {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onIntroReady();
                    }
                });
            }

            @Override
            public void onStopped(final float t, final boolean finished) {
                setRenderMode(RENDERMODE_WHEN_DIRTY);
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        playing = false;
                        if (!finished) sound.stop();
                        if (listener != null) listener.onIntroStopped(t, finished);
                    }
                });
            }

            @Override
            public void onError(final String message) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (listener != null) listener.onIntroError(message);
                    }
                });
            }
        });
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public boolean isPlaying() {
        return playing;
    }

    /**
     * Plays from the start. With sound on, the sound follows the phone's ringer (normal mode only) and the
     * vibration motor taps with the flashes. Slow motion runs at a quarter of the speed, without sound.
     */
    public void play(boolean slowMotion, boolean withSound) {
        final float speed = slowMotion ? 0.25f : 1f;
        sound.stop();
        final boolean audio = withSound && !slowMotion && sound.soundAllowed() && sound.play();
        if (withSound && sound.hapticsAllowed()) sound.scheduleTaps(speed);
        final long t0 = System.nanoTime();
        playing = true;
        queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.begin(t0, speed, audio ? sound : null);
            }
        });
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    /** Freezes on the frame showing now; {@link Listener#onIntroStopped} follows with its time. */
    public void pause() {
        sound.stop();
        queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.freeze();
            }
        });
    }

    /** Shows the frame at {@code t} seconds (0 to {@link #LENGTH}) and stays there. */
    public void showFrame(final float t) {
        playing = false;
        sound.stop();
        queueEvent(new Runnable() {
            @Override
            public void run() {
                renderer.hold(t);
            }
        });
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        requestRender();
    }

    @Override
    public void onPause() {
        if (playing) pause();
        super.onPause();
    }
}
