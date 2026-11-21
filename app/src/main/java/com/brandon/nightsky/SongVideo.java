package com.brandon.nightsky;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;

import com.brandon.nightsky.processing.StarSong;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Hear the Stars' video (2.3): the song's 27 seconds drawn as {@link SongView} shows them, at 30 frames a second, with
 * the song itself as the sound track, into an MP4 to share: a phone's video at 1280 pixels on the long side (smaller if
 * the encoder won't take that), about 10 MB in all.
 */
final class SongVideo {
    static final int FPS = 30;
    /** Long sides tried, biggest first. */
    private static final int[] SIDES = {1280, 960, 720};

    interface Progress {
        /** Share done (0 to 1), from the thread making it. */
        void at(float share);
    }

    private SongVideo() {}

    /** The video's size for a photo w x h: {width, height}, even, or null if no size fits the encoder. */
    static int[] size(VideoEncoder.Factory f, int w, int h) {
        for (int side : SIDES) {
            double s = side / (double) Math.max(w, h);
            int vw = Math.max(16, (int) Math.round(w * s) & ~15), vh = Math.max(16, (int) Math.round(h * s) & ~15);
            if (f.supports(vw, vh)) return new int[]{vw, vh};
        }
        return null;
    }

    /** Bits a second: the photo hardly changes, so a modest rate keeps its stars and keeps the file shareable. */
    static int bitRate(int w, int h) {
        return (int) Math.max(2_000_000L, Math.min(5_000_000L, Math.round(w * (double) h * FPS * 0.08)));
    }

    /**
     * Makes the video of {@code notes} over {@code photo} with {@code pcm} (the song's samples, {@link StarSong#render})
     * into {@code out}. Throws, with {@code out} deleted, if it can't.
     */
    static String make(VideoEncoder.Factory f, Bitmap photo, List<StarSong.Note> notes, short[] pcm, File out, Progress p)
            throws IOException {
        int[] sz = size(f, photo.getWidth(), photo.getHeight());
        if (sz == null) throw new IOException("the phone can't make a video that size");
        int w = sz[0], h = sz[1];
        Bitmap base = null, frame = null;
        VideoEncoder enc = null;
        boolean ok = false;
        try {
            // the photo at the video's size, once
            base = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(base).drawBitmap(photo, null, new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(frame);
            RectF dst = new RectF(0, 0, w, h);
            SongArt art = new SongArt(notes);
            // sizes as they look on a phone's screen, the video shown across its width
            float px = w / 412f;
            int[] argb = new int[w * h];
            int frames = (int) Math.ceil(SongArt.seconds() * FPS);
            enc = f.open(out, w, h, FPS, bitRate(w, h), pcm, StarSong.RATE);
            for (int i = 0; i < frames; i++) {
                double t = i / (double) FPS;
                art.draw(c, base, dst, t, px, true);
                frame.getPixels(argb, 0, w, 0, 0, w, h);
                enc.frame(argb, Math.round(t * 1e6));
                if (p != null && (i % 10 == 0 || i == frames - 1)) p.at((i + 1) / (float) frames);
            }
            enc.finish();
            ok = true;
            return enc.note();
        } catch (RuntimeException | OutOfMemoryError e) {
            throw new IOException("the song's video failed: " + e, e);
        } finally {
            if (enc != null && !ok) enc.close();
            if (!ok) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            if (base != null) base.recycle();
            if (frame != null) frame.recycle();
        }
    }
}
