package com.brandon.nightsky.processing;

/**
 * Draws words and simple shapes onto a picture (2.4): Deep Space Zoom's cards and Mission Replay's captions. The app's
 * is Android's Canvas, the tests' AWT, so both lay a card out the same way. Positions are the picture's pixels, colours
 * ARGB.
 */
public interface Painter {
    /** How wide {@code s} comes out at text size {@code size} (px). */
    float width(String s, float size, boolean bold);

    /** {@code s} with its baseline's left end at (x, y), with a soft dark shadow so it reads over any sky. */
    void text(String s, float x, float y, float size, boolean bold, int argb);

    /** The rectangle (x0, y0)-(x1, y1) filled from colour {@code top} at its top to {@code bottom} at its bottom. */
    void gradient(float x0, float y0, float x1, float y1, int top, int bottom);

    /** A circle's outline, {@code stroke} px wide. */
    void ring(float cx, float cy, float r, float stroke, int argb);

    /** A filled circle. */
    void disc(float cx, float cy, float r, int argb);

    /** A straight line, {@code stroke} px wide, round ends. */
    void line(float x0, float y0, float x1, float y1, float stroke, int argb);

    /**
     * {@code s} as if written by hand (Galileo's notebook, 3.2): an italic serif with no shadow, its baseline's left end
     * at (x, y). Plain words where a painter has no such letters.
     */
    default void script(String s, float x, float y, float size, int argb) {
        text(s, x, y, size, false, argb);
    }

    /** How wide {@link #script} writes {@code s}. */
    default float scriptWidth(String s, float size) {
        return width(s, size, false);
    }
}
