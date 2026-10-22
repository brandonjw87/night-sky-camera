package com.brandon.intro;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTimestamp;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.io.IOException;

/**
 * The intro's sound (shutter, flash pops, shine) and the matching taps of the vibration motor.
 *
 * The sound follows the phone's ringer: it plays only in normal mode, and vibrate mode keeps just the taps.
 * It also tells the renderer how much of the sound has reached the speaker, so the picture runs on the
 * same clock as the sound.
 */
final class IntroSound {
    static final int RATE = 48000;
    /** Output delay assumed until the first real timestamp arrives (a typical fast-mixer path). */
    private static final double LATENCY_GUESS = 0.035;
    /** Moments the vibration motor taps, in intro seconds: four paparazzi flashes, then the big flash. */
    private static final float[] TAPS = {0.340f, 0.430f, 0.505f, 0.565f};
    private static final float HERO_T = 0.640f;

    private final AudioManager audio;
    private final Vibrator vibrator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
    private final AudioTimestamp stamp = new AudioTimestamp();
    private final AudioFocusRequest focus;
    private byte[] pcm;                     // 16-bit stereo, little endian, 2.2 s
    private volatile AudioTrack track;
    private long anchorNs;
    private int anchorFrames;
    private VibrationEffect tap, heroTap;

    IntroSound(Context c) {
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        VibratorManager vm = (VibratorManager) c.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        Vibrator v = vm != null ? vm.getDefaultVibrator() : null;
        vibrator = v != null && v.hasVibrator() ? v : null;
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build();
        try {
            pcm = IntroAssets.read(c.getAssets(), IntroAssets.DIR + "intro_sound.pcm");
        } catch (IOException e) {
            pcm = null;
        }
    }

    boolean soundAllowed() {
        return pcm != null && audio != null && audio.getRingerMode() == AudioManager.RINGER_MODE_NORMAL;
    }

    boolean hapticsAllowed() {
        return vibrator != null && (audio == null || audio.getRingerMode() != AudioManager.RINGER_MODE_SILENT);
    }

    /** Starts the sound from the top. Returns false if it could not play. */
    boolean play() {
        stop();
        AudioTrack t;
        try {
            t = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.length)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
        } catch (RuntimeException e) {
            return false;
        }
        if (t.write(pcm, 0, pcm.length) != pcm.length) {
            t.release();
            return false;
        }
        anchorNs = 0;
        audio.requestAudioFocus(focus);
        try {
            t.play();
        } catch (IllegalStateException e) {
            t.release();
            audio.abandonAudioFocusRequest(focus);
            return false;
        }
        track = t;
        final AudioTrack mine = t;
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (track == mine) stop();
            }
        }, 2600);
        return true;
    }

    /**
     * Seconds of the sound that have come out of the speaker, or NaN while nothing has played yet.
     * Called on the GL thread every frame.
     */
    double positionSeconds() {
        AudioTrack t = track;
        if (t == null) return Double.NaN;
        long now = System.nanoTime();
        try {
            if (t.getTimestamp(stamp) && stamp.framePosition > 0) {
                return (stamp.framePosition + (now - stamp.nanoTime) * (RATE / 1e9)) / RATE;
            }
            int head = t.getPlaybackHeadPosition();
            if (head <= 0) return Double.NaN;
            if (anchorNs == 0) {
                anchorNs = now;
                anchorFrames = head;
            }
            return (anchorFrames + (now - anchorNs) * (RATE / 1e9)) / RATE - LATENCY_GUESS;
        } catch (IllegalStateException e) {
            return Double.NaN;
        }
    }

    /** Taps the vibration motor with the flashes; speed is 1 for normal play, 0.25 for slow motion. */
    void scheduleTaps(float speed) {
        if (vibrator == null) return;
        if (tap == null) {
            tap = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
                    ? VibrationEffect.startComposition().addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.45f).compose()
                    : VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
            heroTap = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK,
                    VibrationEffect.Composition.PRIMITIVE_THUD)
                    ? VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.8f, 12)
                        .compose()
                    : VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK);
        }
        for (float t : TAPS) post(tap, t, speed);
        post(heroTap, HERO_T, speed);
    }

    private void post(final VibrationEffect e, float t, float speed) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    vibrator.vibrate(e);
                } catch (RuntimeException ignored) {
                    // the motor is optional
                }
            }
        }, Math.round(t / speed * 1000f + LATENCY_GUESS * 1000));
    }

    /** Stops sound and pending taps. Safe to call any time. */
    void stop() {
        main.removeCallbacksAndMessages(null);
        AudioTrack t = track;
        track = null;
        if (t != null) {
            try {
                t.stop();
            } catch (IllegalStateException ignored) {
                // not playing
            }
            t.release();
            audio.abandonAudioFocusRequest(focus);
        }
        if (vibrator != null) vibrator.cancel();
    }
}
