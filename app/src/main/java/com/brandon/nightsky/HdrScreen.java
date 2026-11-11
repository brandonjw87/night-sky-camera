package com.brandon.nightsky;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Gainmap;
import android.os.Build;

import com.brandon.nightsky.processing.HdrGain;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * HDR photos on this phone's own screen (2.0). Android 14 and later draw a bitmap that carries a brightness map (a
 * "gainmap") brighter than white where the map says so, in a window that asks for HDR and on a screen that has the
 * headroom. The photo on the result screen gets the same map its saved file has ({@link HdrGain}), so what's seen here
 * is what Google Photos shows. On older Android, or a screen without the headroom, nothing changes.
 */
final class HdrScreen {
    private HdrScreen() {}

    /** Whether this Android can draw a photo brighter than white at all. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= 34;
    }

    /**
     * Gives {@code b} the brightness map of its own pixels. False, and {@code b} as it was, when this Android can't,
     * nothing in the picture is bright enough to lift, or the map couldn't be made.
     */
    static boolean shine(Bitmap b) {
        if (!supported() || b == null || b.isRecycled()) return false;
        try {
            int w = b.getWidth(), h = b.getHeight();
            HdrGain g = new HdrGain(w, h);
            int rows = Math.max(1, Math.min(h, (1 << 18) / w));
            int[] px = new int[w * rows];
            for (int y = 0; y < h; y += rows) {
                int n = Math.min(rows, h - y);
                b.getPixels(px, 0, w, 0, y, w, n);
                for (int j = 0; j < n; j++) g.measure(y + j, px, j * w);
            }
            Bitmap map = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
            int stride = map.getRowBytes();
            byte[] values = new byte[stride * h];
            for (int y = 0; y < h; y += rows) {
                int n = Math.min(rows, h - y);
                b.getPixels(px, 0, w, 0, y, w, n);
                for (int j = 0; j < n; j++) g.row(y + j, px, j * w, values, (y + j) * stride);
            }
            if (g.most() == 0) {
                map.recycle();
                return false;
            }
            map.copyPixelsFromBuffer(ByteBuffer.wrap(values));
            Gainmap gm = new Gainmap(map);
            float top = (float) Math.pow(2, HdrGain.MAX_STOPS), e = 1f / 64f;
            gm.setRatioMin(1f, 1f, 1f);
            gm.setRatioMax(top, top, top);
            gm.setGamma(1f, 1f, 1f);
            gm.setEpsilonSdr(e, e, e);
            gm.setEpsilonHdr(e, e, e);
            gm.setMinDisplayRatioForHdrTransition(1f);
            gm.setDisplayRatioForFullHdr(top);
            b.setGainmap(gm);
            return true;
        } catch (RuntimeException | OutOfMemoryError | LinkageError e) {
            android.util.Log.w("NightSky", "no brightness map for the screen: " + e);
            return false;
        }
    }

    /** Takes {@code b}'s brightness map off again (a copy of a bitmap keeps its original's). */
    static void plain(Bitmap b) {
        if (!supported() || b == null || b.isRecycled()) return;
        try {
            if (b.hasGainmap()) b.setGainmap(null);
        } catch (RuntimeException | LinkageError e) {
            android.util.Log.w("NightSky", "couldn't take the brightness map off: " + e);
        }
    }

    /** Whether {@code b} carries a brightness map. */
    static boolean shines(Bitmap b) {
        try {
            return supported() && b != null && !b.isRecycled() && b.hasGainmap();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /**
     * Writes {@code b} as an ordinary JPEG. Android 14 and later would write a bitmap that carries a brightness map as
     * an HDR photo by itself; thumbnails, the widget's picture, the laptop viewer's picture and the photo this app
     * gives its own map ({@link com.brandon.nightsky.processing.UltraHdr}) must stay ordinary ones.
     */
    static boolean compressPlain(Bitmap b, int quality, java.io.OutputStream os) {
        if (!shines(b)) return b.compress(Bitmap.CompressFormat.JPEG, quality, os);
        Bitmap c = withoutMap(b);
        try {
            return c.compress(Bitmap.CompressFormat.JPEG, quality, os);
        } finally {
            if (c != b) c.recycle();
        }
    }

    /** {@code b} itself when it carries no brightness map, else a copy of it without one (the caller's to recycle). */
    static Bitmap withoutMap(Bitmap b) {
        if (!shines(b)) return b;
        Bitmap c = b.copy(Bitmap.Config.ARGB_8888, true);
        if (c == null) throw new IllegalStateException("the picture couldn't be copied");
        plain(c);
        if (shines(c)) {
            c.recycle();
            throw new IllegalStateException("the copy kept its brightness map");
        }
        return c;
    }

    /** What {@link #readsBack} found. */
    static final int READ = 1, NOT_CHECKED = 0, NOT_READ = -1;
    /** For the tests: what {@link #readsBack} says instead of looking (null: it looks). */
    static Integer readsBackForced;
    /** Whether this Android reads the brightness map of a photo it wrote itself: 1, 0, or -1 while not yet tried. */
    private static int readsOwn = -1;

    /**
     * Asks Android's own decoder, the one this app's screen and the gallery on this phone draw with, for the brightness
     * map of the HDR photo {@code f}, before the photo goes to the gallery. {@link #READ}: found, with the numbers that
     * were written. {@link #NOT_CHECKED}: this Android reads no brightness map from any photo, one it wrote itself
     * included (or is too old to know of them), so nothing can be said here. {@link #NOT_READ}: it reads its own and
     * not this one, or reads other numbers from it: the file isn't what was meant, and the photo is better saved
     * without a map.
     */
    static int readsBack(File f) {
        if (readsBackForced != null) return readsBackForced;
        if (!supported()) return NOT_CHECKED;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 8;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), o);
            if (b == null) return NOT_READ;
            try {
                if (!b.hasGainmap()) return readsOwn() ? NOT_READ : NOT_CHECKED;
                Gainmap g = b.getGainmap();
                float top = (float) Math.pow(2, HdrGain.MAX_STOPS), near = 0.05f;
                float[] max = g.getRatioMax(), min = g.getRatioMin(), gamma = g.getGamma();
                for (int i = 0; i < 3; i++) {
                    if (Math.abs(max[i] - top) > near * top || Math.abs(min[i] - 1f) > near || Math.abs(gamma[i] - 1f) > near) return NOT_READ;
                }
                if (Math.abs(g.getDisplayRatioForFullHdr() - top) > near * top) return NOT_READ;
                if (Math.abs(g.getMinDisplayRatioForHdrTransition() - 1f) > near) return NOT_READ;
                return READ;
            } finally {
                b.recycle();
            }
        } catch (RuntimeException | OutOfMemoryError | LinkageError e) {
            android.util.Log.w("NightSky", "the saved photo's brightness map couldn't be asked for: " + e);
            return NOT_CHECKED;
        }
    }

    /**
     * Whether this Android reads back the brightness map of a small photo it writes itself (Android 14 and later write
     * a bitmap that carries a map as an HDR photo). Tried once.
     */
    private static synchronized boolean readsOwn() {
        if (readsOwn >= 0) return readsOwn == 1;
        boolean reads = false;
        Bitmap b = null, map = null, back = null;
        try {
            int n = 64;
            b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888);
            b.eraseColor(0xFF808080);
            map = Bitmap.createBitmap(n, n, Bitmap.Config.ALPHA_8);
            byte[] values = new byte[map.getRowBytes() * n];
            for (int i = 0; i < values.length; i++) values[i] = (byte) (i * 7);
            map.copyPixelsFromBuffer(ByteBuffer.wrap(values));
            Gainmap gm = new Gainmap(map);
            gm.setRatioMax(4f, 4f, 4f);
            gm.setDisplayRatioForFullHdr(4f);
            b.setGainmap(gm);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(8192);
            if (b.compress(Bitmap.CompressFormat.JPEG, 90, bo)) {
                byte[] jpeg = bo.toByteArray();
                back = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                reads = back != null && back.hasGainmap();
            }
        } catch (RuntimeException | OutOfMemoryError | LinkageError e) {
            android.util.Log.w("NightSky", "couldn't try this Android's own HDR photo: " + e);
        } finally {
            if (back != null) back.recycle();
            if (b != null) b.recycle();
            if (map != null) map.recycle();
        }
        readsOwn = reads ? 1 : 0;
        return reads;
    }

    /** Asks for the window to be drawn with HDR headroom (a page showing a photo), or as usual (every other page). */
    static void window(Activity a, boolean hdr) {
        if (!supported()) return;
        try {
            int want = hdr ? ActivityInfo.COLOR_MODE_HDR : ActivityInfo.COLOR_MODE_DEFAULT;
            if (a.getWindow().getColorMode() != want) a.getWindow().setColorMode(want);
        } catch (RuntimeException | LinkageError e) {
            android.util.Log.w("NightSky", "couldn't set the window's colour mode: " + e);
        }
    }
}
