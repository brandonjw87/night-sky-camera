package com.brandon.nightsky;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;

import com.brandon.nightsky.processing.StarSong;

import java.util.List;

/**
 * Hear the Stars where the photo is (2.3): the photo fitted into the view, drawn by {@link SongArt} at the song's time,
 * which comes from the player (the speaker's clock), or from the view's own clock when there is no sound to follow.
 * Redrawn every frame until the song ends, then {@code done} runs.
 */
final class SongView extends View {
    private final SongArt art;
    private final Bitmap photo;
    private final RectF dst = new RectF();
    private SongPlayer player;
    private long startNs;
    private Runnable done;
    private boolean finished;

    SongView(Context c, Bitmap photo, List<StarSong.Note> notes) {
        super(c);
        this.photo = photo;
        art = new SongArt(notes);
        setBackgroundColor(0xFF000000);
    }

    /** Starts drawing the song as {@code p} plays it (null: by the clock alone); {@code done} runs once it ends. */
    void play(SongPlayer p, Runnable done) {
        player = p;
        this.done = done;
        startNs = System.nanoTime();
        finished = false;
        postInvalidateOnAnimation();
    }

    /** Seconds into the song. */
    double time() {
        if (player != null) return player.position();
        return startNs == 0 ? 0 : (System.nanoTime() - startNs) / 1e9;
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0 || photo == null) return;
        float s = Math.min(w / (float) photo.getWidth(), h / (float) photo.getHeight());
        float dw = photo.getWidth() * s, dh = photo.getHeight() * s;
        dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
        double t = time();
        art.draw(c, photo, dst, t, getResources().getDisplayMetrics().density, true);
        if (finished) return;
        boolean over = player != null ? player.ended() : t >= SongArt.seconds();
        if (over) {
            finished = true;
            if (done != null) post(done);
            return;
        }
        postInvalidateOnAnimation();
    }
}
