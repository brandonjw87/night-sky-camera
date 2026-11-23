package com.brandon.nightsky.link;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Messages over a stream (the Bluetooth connection, 2.5): each one its length (4 bytes) then its bytes. The first byte
 * says what it is: {@link #PAIRING} (plain JSON, only while pairing) or {@link #SEALED} (a message sealed with the
 * link's key).
 */
public final class Frames {
    private Frames() {}

    public static final byte PAIRING = 'P', SEALED = 'S';
    /** The biggest frame taken (a preview picture is the biggest thing sent). */
    public static final int MAX = 512 * 1024;

    public static void write(OutputStream os, byte kind, byte[] body) throws IOException {
        int n = body.length + 1;
        if (n > MAX) throw new IOException("frame too big: " + n);
        byte[] b = new byte[4 + n];
        b[0] = (byte) (n >>> 24);
        b[1] = (byte) (n >>> 16);
        b[2] = (byte) (n >>> 8);
        b[3] = (byte) n;
        b[4] = kind;
        System.arraycopy(body, 0, b, 5, body.length);
        synchronized (os) {
            os.write(b);
            os.flush();
        }
    }

    /** The next frame (its kind first); EOFException at the end of the stream. */
    public static byte[] read(InputStream is) throws IOException {
        byte[] h = new byte[4];
        fill(is, h);
        int n = (h[0] & 0xFF) << 24 | (h[1] & 0xFF) << 16 | (h[2] & 0xFF) << 8 | (h[3] & 0xFF);
        if (n < 1 || n > MAX) throw new IOException("bad frame length " + n);
        byte[] b = new byte[n];
        fill(is, b);
        return b;
    }

    private static void fill(InputStream is, byte[] b) throws IOException {
        int at = 0;
        while (at < b.length) {
            int r = is.read(b, at, b.length - at);
            if (r < 0) throw new EOFException();
            at += r;
        }
    }
}
