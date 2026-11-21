package com.brandon.nightsky.processing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * An animated WebP (2.2), the picture format that plays and loops by itself like a GIF in a gallery, a browser or a
 * message, at a fraction of a GIF's size (a GIF has 256 colours and no lossy compression; a night sky's noise and
 * smooth glow cost it dearly). Each frame comes in as an ordinary still WebP (the phone's own encoder makes them:
 * {@code Bitmap.compress(WEBP_LOSSY)}); its image data is taken out of the still's container and put, frame after
 * frame, into one animation (RIFF "WEBP": VP8X with the animation flag, ANIM with an endless loop, one ANMF per frame,
 * each the whole canvas, not blended onto the one before), as the WebP container specification lays it out.
 */
public final class AnimatedWebp {
    private final int w, h;
    private final ByteArrayOutputStream frames = new ByteArrayOutputStream();
    private int count;
    private boolean alpha;

    public AnimatedWebp(int w, int h) {
        if (w < 1 || h < 1 || w > (1 << 24) || h > (1 << 24)) throw new IllegalArgumentException(w + "x" + h);
        this.w = w;
        this.h = h;
    }

    /** One frame: a still WebP of the canvas's size, shown for {@code durationMs}. */
    public void add(byte[] still, int durationMs) throws IOException {
        if (still.length < 20 || !tag(still, 0, "RIFF") || !tag(still, 8, "WEBP")) throw new IOException("not a WebP");
        int end = Math.min(still.length, 8 + le32(still, 4));
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        int at = 12;
        boolean image = false;
        while (at + 8 <= end) {
            int size = le32(still, at + 4);
            if (size < 0 || at + 8 + size > end) throw new IOException("a broken WebP chunk");
            if (tag(still, at, "ALPH") || tag(still, at, "VP8 ") || tag(still, at, "VP8L")) {
                if (tag(still, at, "ALPH")) alpha = true;
                if (tag(still, at, "VP8L")) alpha = true;
                data.write(still, at, 8 + size);
                if ((size & 1) != 0) data.write(0);
                if (!tag(still, at, "ALPH")) image = true;
            }
            at += 8 + size + (size & 1);
        }
        if (!image) throw new IOException("a WebP without its image");
        byte[] d = data.toByteArray();
        int dur = Math.max(1, Math.min((1 << 24) - 1, durationMs));
        ByteArrayOutputStream f = new ByteArrayOutputStream();
        f.write(ascii("ANMF"));
        le32(f, 16 + d.length);
        le24(f, 0);
        le24(f, 0);
        le24(f, w - 1);
        le24(f, h - 1);
        le24(f, dur);
        // bit 1: not blended onto the frame before (each frame is the whole picture); bit 0: not disposed
        f.write(0x02);
        f.write(d);
        frames.write(f.toByteArray());
        count++;
    }

    public int frames() {
        return count;
    }

    /** The whole file. */
    public byte[] toBytes() throws IOException {
        if (count == 0) throw new IOException("no frames");
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] body = frames.toByteArray();
        int riff = 4 + (8 + 10) + (8 + 6) + body.length;
        o.write(ascii("RIFF"));
        le32(o, riff);
        o.write(ascii("WEBP"));
        o.write(ascii("VP8X"));
        le32(o, 10);
        // flags: animation (0x02), alpha (0x10) when the frames carry it
        o.write(0x02 | (alpha ? 0x10 : 0));
        o.write(0);
        o.write(0);
        o.write(0);
        le24(o, w - 1);
        le24(o, h - 1);
        o.write(ascii("ANIM"));
        le32(o, 6);
        // background colour (blue, green, red, alpha: black), then the loop count (0: for ever)
        o.write(0);
        o.write(0);
        o.write(0);
        o.write(0xFF);
        o.write(0);
        o.write(0);
        o.write(body);
        return o.toByteArray();
    }

    public void writeTo(OutputStream os) throws IOException {
        os.write(toBytes());
    }

    private static boolean tag(byte[] b, int at, String t) {
        for (int i = 0; i < 4; i++) if (b[at + i] != (byte) t.charAt(i)) return false;
        return true;
    }

    private static int le32(byte[] b, int at) {
        return (b[at] & 0xFF) | (b[at + 1] & 0xFF) << 8 | (b[at + 2] & 0xFF) << 16 | (b[at + 3] & 0xFF) << 24;
    }

    private static void le32(OutputStream o, int v) throws IOException {
        o.write(v);
        o.write(v >>> 8);
        o.write(v >>> 16);
        o.write(v >>> 24);
    }

    private static void le24(OutputStream o, int v) throws IOException {
        o.write(v);
        o.write(v >>> 8);
        o.write(v >>> 16);
    }

    private static byte[] ascii(String s) {
        byte[] b = new byte[s.length()];
        for (int i = 0; i < b.length; i++) b[i] = (byte) s.charAt(i);
        return b;
    }
}
