package com.brandon.nightsky.processing;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Stores each captured unit as a raw little-endian uint16 file, so frames survive crashes and can be
 * re-read (whole or a band of rows at a time) during stacking.
 */
public final class FrameStore {
    private final File dir;
    private final int W, H;
    private final ByteBuffer buf;

    public FrameStore(File dir, int width, int height) {
        this.dir = dir;
        this.W = width;
        this.H = height;
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Cannot create " + dir);
        }
        buf = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN);
    }

    public File dir() {
        return dir;
    }

    public File file(int index) {
        return new File(dir, String.format(java.util.Locale.US, "u%05d.raw", index));
    }

    public boolean exists(int index) {
        File f = file(index);
        return f.exists() && f.length() == (long) W * H * 2;
    }

    public synchronized void write(int index, short[] data) throws IOException {
        buf.clear();
        buf.asShortBuffer().put(data, 0, W * H);
        buf.position(0);
        buf.limit(W * H * 2);
        File tmp = new File(dir, file(index).getName() + ".part");
        RandomAccessFile f = new RandomAccessFile(tmp, "rw");
        try {
            FileChannel ch = f.getChannel();
            while (buf.hasRemaining()) ch.write(buf);
        } finally {
            f.close();
        }
        if (!tmp.renameTo(file(index))) throw new IOException("rename failed for " + tmp);
    }

    public void read(int index, short[] out) throws IOException {
        readRows(index, 0, H, out);
    }

    /** Reads rows [y0, y1) into the same positions of a full-size buffer. */
    public synchronized void readRows(int index, int y0, int y1, short[] out) throws IOException {
        y0 = Math.max(0, y0);
        y1 = Math.min(H, y1);
        if (y1 <= y0) return;
        int bytes = (y1 - y0) * W * 2;
        buf.clear();
        buf.limit(bytes);
        RandomAccessFile f = new RandomAccessFile(file(index), "r");
        try {
            FileChannel ch = f.getChannel();
            long pos = (long) y0 * W * 2;
            while (buf.hasRemaining()) {
                int n = ch.read(buf, pos);
                if (n < 0) throw new IOException("short read in " + file(index));
                pos += n;
            }
        } finally {
            f.close();
        }
        buf.flip();
        buf.asShortBuffer().get(out, y0 * W, (y1 - y0) * W);
    }

    /** Reads rows [y0, y1) into {@code out} starting at index 0 (row y0 lands at out[0]). */
    public synchronized void readBand(int index, int y0, int y1, short[] out) throws IOException {
        y0 = Math.max(0, y0);
        y1 = Math.min(H, y1);
        if (y1 <= y0) return;
        int bytes = (y1 - y0) * W * 2;
        buf.clear();
        buf.limit(bytes);
        RandomAccessFile f = new RandomAccessFile(file(index), "r");
        try {
            FileChannel ch = f.getChannel();
            long pos = (long) y0 * W * 2;
            while (buf.hasRemaining()) {
                int n = ch.read(buf, pos);
                if (n < 0) throw new IOException("short read in " + file(index));
                pos += n;
            }
        } finally {
            f.close();
        }
        buf.flip();
        buf.asShortBuffer().get(out, 0, (y1 - y0) * W);
    }

    public int width() {
        return W;
    }

    public int height() {
        return H;
    }

    public void deleteAll() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        //noinspection ResultOfMethodCallIgnored
        dir.delete();
    }
}
