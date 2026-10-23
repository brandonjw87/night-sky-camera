package com.brandon.nightsky;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.brandon.nightsky.processing.ReportCard;

import java.util.Set;

/**
 * The report's frames as a strip of bars, one per frame in time order: the height is the frame's score, bright
 * red for a good frame, dim for one under 50, an outline for one left out. Touching a bar picks that frame. On the
 * edit screen (G3) it also shows the time trim (frames outside it faint), frames left out by hand (crossed), the
 * reference frame (a mark above it) and frames lined up by hand (a dot).
 */
final class FrameStrip extends View {
    interface Listener {
        void onFrame(ReportCard.Frame f);
    }

    private ReportCard.Frame[] frames = new ReportCard.Frame[0];
    private int selected = -1;
    private Listener listener;
    private final Paint good = new Paint(), weak = new Paint(), out = new Paint(), sel = new Paint(), base = new Paint();
    // the edit screen's marks
    private boolean editing;
    private int from, to;
    private Set<Integer> leftOut, tapped;
    private int reference = -1;
    private final Paint faint = new Paint(), cross = new Paint(), mark = new Paint();

    FrameStrip(Context c) {
        super(c);
        good.setColor(MainActivity.RED);
        weak.setColor(MainActivity.RED_DIM);
        out.setColor(MainActivity.RED_DIM);
        out.setStyle(Paint.Style.STROKE);
        out.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density));
        sel.setColor(MainActivity.RED_SEL);
        base.setColor(MainActivity.RED_DARK);
        faint.setColor(MainActivity.RED_DARK);
        cross.setColor(MainActivity.RED);
        cross.setStrokeWidth(Math.max(1f, getResources().getDisplayMetrics().density * 1.5f));
        mark.setColor(MainActivity.RED);
        mark.setAntiAlias(true);
    }

    /**
     * The edit screen's marks: frames {@code from} to {@code to} (positions in the strip) kept by the trim, units left
     * out by hand, the reference unit (-1: none shown) and units lined up by hand.
     */
    void setEdit(int from, int to, Set<Integer> leftOut, int reference, Set<Integer> tapped) {
        editing = true;
        this.from = from;
        this.to = to;
        this.leftOut = leftOut;
        this.reference = reference;
        this.tapped = tapped;
        invalidate();
    }

    /** Picks the frame at {@code i} (a position in the strip), as touching it does, without telling the listener. */
    void select(int i) {
        selected = i;
        invalidate();
    }

    void setFrames(ReportCard.Frame[] f, Listener l) {
        frames = f != null ? f : new ReportCard.Frame[0];
        listener = l;
        selected = -1;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int n = frames.length;
        if (n == 0) return;
        float w = getWidth(), h = getHeight();
        float slot = w / n, gap = slot > 6 ? Math.max(1f, slot * 0.15f) : 0f;
        // room above the bars for the edit screen's marks
        float top0 = editing ? Math.min(h * 0.2f, 10 * getResources().getDisplayMetrics().density) : 0f;
        c.drawRect(0, h - 2, w, h, base);
        for (int i = 0; i < n; i++) {
            ReportCard.Frame f = frames[i];
            float x0 = i * slot + gap / 2, x1 = (i + 1) * slot - gap / 2;
            if (i == selected) c.drawRect(i * slot, 0, (i + 1) * slot, h, sel);
            boolean trimmed = editing && (i < from || i > to), byHand = editing && leftOut != null && leftOut.contains(f.index);
            float hh = h - top0;
            if (!f.used || byHand) {
                float top = top0 + hh * 0.75f;
                c.drawRect(x0 + 1, top, x1 - 1, h - 1, trimmed ? faint : out);
                if (byHand) {
                    c.drawLine(x0 + 1, top, x1 - 1, h - 1, cross);
                    c.drawLine(x0 + 1, h - 1, x1 - 1, top, cross);
                }
            } else {
                float top = h - Math.max(3f, hh * f.score / 100f);
                c.drawRect(x0, top, x1, h, trimmed ? faint : f.score >= 50 ? good : weak);
            }
            if (!editing) continue;
            float cx = (x0 + x1) / 2, r = Math.max(2f, Math.min(slot * 0.35f, top0 * 0.35f));
            if (f.index == reference) {
                // a triangle pointing down at the reference frame
                android.graphics.Path tri = new android.graphics.Path();
                tri.moveTo(cx - 1.6f * r, 0);
                tri.lineTo(cx + 1.6f * r, 0);
                tri.lineTo(cx, top0 - 1);
                tri.close();
                c.drawPath(tri, mark);
            } else if (tapped != null && tapped.contains(f.index)) {
                c.drawCircle(cx, top0 / 2, r, mark);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int n = frames.length;
        if (n == 0) return false;
        if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
            int i = (int) (e.getX() / (getWidth() / (float) n));
            i = Math.max(0, Math.min(n - 1, i));
            if (i != selected) {
                selected = i;
                invalidate();
                if (listener != null) listener.onFrame(frames[i]);
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        return e.getAction() == MotionEvent.ACTION_UP || super.onTouchEvent(e);
    }
}
