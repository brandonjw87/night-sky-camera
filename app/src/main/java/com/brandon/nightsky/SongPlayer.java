package com.brandon.nightsky;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Plays Hear the Stars' song (2.3) through the phone's speaker: 16-bit stereo samples fed to an AudioTrack from a
 * thread of its own, a little at a time, and the time from the speaker's own count of what it has played, so the
 * picture keeps to the sound. One play each; {@link #stop} any time.
 */
final class SongPlayer {
    private final short[] pcm;
    private final int rate;
    private AudioTrack track;
    private volatile boolean stopped, ended;
    /** Frames already played when the track was let go (the clock stays there). */
    private volatile long playedAtEnd;

    SongPlayer(short[] pcm, int rate) {
        this.pcm = pcm;
        this.rate = rate;
    }

    /** Starts the song; false if the phone wouldn't play it. */
    synchronized boolean start() {
        try {
            int min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            int buf = Math.max(min > 0 ? 2 * min : 0, rate);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(buf).setTransferMode(AudioTrack.MODE_STREAM).build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return false;
            }
            track.play();
        } catch (RuntimeException e) {
            if (track != null) track.release();
            track = null;
            return false;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                feed();
            }
        }, "nightsky-song");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
        return true;
    }

    /** The samples into the track as it takes them, then the wait for the last of them to play, then the track let go. */
    private void feed() {
        int pos = 0;
        long total = pcm.length / 2;
        // a speaker that stops counting (a headset unplugged, a stuck route) doesn't hold the song up for ever
        long deadline = System.nanoTime() + Math.round((total / (double) rate + 5) * 1e9);
        try {
            while (!stopped && pos < pcm.length) {
                int w;
                synchronized (this) {
                    if (stopped || track == null) break;
                    w = track.write(pcm, pos, Math.min(8192, pcm.length - pos), AudioTrack.WRITE_NON_BLOCKING);
                }
                if (w < 0) break;
                pos += w;
                if (w == 0) pause(15);
            }
            while (!stopped && played() < total && System.nanoTime() < deadline) pause(20);
        } catch (RuntimeException ignored) {
            // the track went away: the song simply ends
        }
        synchronized (this) {
            playedAtEnd = Math.min(total, played());
            if (track != null) {
                try {
                    track.pause();
                    track.flush();
                    track.stop();
                } catch (RuntimeException ignored) {
                }
                track.release();
                track = null;
            }
            ended = true;
        }
    }

    private static void pause(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Frames the speaker has played. */
    private synchronized long played() {
        if (track == null) return playedAtEnd;
        try {
            return track.getPlaybackHeadPosition() & 0xFFFFFFFFL;
        } catch (RuntimeException e) {
            return playedAtEnd;
        }
    }

    /** Seconds into the song. */
    double position() {
        return played() / (double) rate;
    }

    /** Whether it has finished (played to its end, or stopped). */
    boolean ended() {
        return ended;
    }

    void stop() {
        stopped = true;
    }
}
