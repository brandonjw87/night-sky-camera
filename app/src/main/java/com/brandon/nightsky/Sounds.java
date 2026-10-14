package com.brandon.nightsky;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * Beeps and spoken cues on the alarm stream, so they are heard with the phone face-down and the ringer off.
 * The controller only plays them between exposures: a speaker buzzing during an exposure shakes the camera.
 */
final class Sounds {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final boolean enabled;
    /** Silenced from the remote viewer for now (the setting itself stays on). */
    private volatile boolean muted;
    private ToneGenerator tone;
    private TextToSpeech tts;
    private volatile boolean ttsReady;
    private int utterance;

    Sounds(Context c, boolean enabled) {
        this.enabled = enabled;
        if (!enabled) return;
        try {
            tone = new ToneGenerator(AudioManager.STREAM_ALARM, 85);
        } catch (RuntimeException e) {
            tone = null;
        }
        try {
            tts = new TextToSpeech(c.getApplicationContext(), new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    if (status != TextToSpeech.SUCCESS || tts == null) return;
                    int r = tts.setLanguage(Locale.getDefault());
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US);
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build());
                    ttsReady = true;
                }
            });
        } catch (RuntimeException e) {
            tts = null;
        }
    }

    void setMuted(boolean m) {
        muted = m;
        if (m && tts != null) {
            try {
                tts.stop();
            } catch (RuntimeException ignored) {
            }
        }
    }

    boolean muted() {
        return muted;
    }

    /** Short tick, for the countdown. */
    void tick() {
        play(ToneGenerator.TONE_PROP_BEEP, 120);
    }

    /** Two-tone "go" / "done" sound. */
    void chime() {
        play(ToneGenerator.TONE_PROP_ACK, 400);
    }

    /** Low warning sound. */
    void warn() {
        play(ToneGenerator.TONE_PROP_NACK, 400);
    }

    private void play(int t, int ms) {
        if (!enabled || muted || tone == null) return;
        try {
            tone.startTone(t, ms);
        } catch (RuntimeException ignored) {
        }
    }

    /** Speaks text, then runs {@code done} on the main thread (at once if speech is off or unavailable). */
    void say(String text, final Runnable done) {
        final Runnable once = new Runnable() {
            boolean ran;

            @Override
            public synchronized void run() {
                if (ran) return;
                ran = true;
                if (done != null) done.run();
            }
        };
        if (!enabled || muted || tts == null || !ttsReady) {
            main.post(once);
            return;
        }
        final String id = "u" + (++utterance);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
            }

            @Override
            public void onDone(String utteranceId) {
                if (id.equals(utteranceId)) main.post(once);
            }

            @Override
            public void onError(String utteranceId) {
                if (id.equals(utteranceId)) main.post(once);
            }
        });
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id);
        if (r != TextToSpeech.SUCCESS) main.post(once);
        else main.postDelayed(once, 3000 + 120L * text.length());
    }

    void release() {
        if (tone != null) {
            tone.release();
            tone = null;
        }
        if (tts != null) {
            tts.shutdown();
            tts = null;
        }
    }
}
