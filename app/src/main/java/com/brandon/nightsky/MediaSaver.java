package com.brandon.nightsky;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import com.brandon.nightsky.processing.HdrGain;
import com.brandon.nightsky.processing.MotionPhoto;
import com.brandon.nightsky.processing.UltraHdr;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Saves results where the gallery finds them: photos (JPEG and DNG) in Pictures/NightSky, session logs in
 * Documents/NightSky. Uses MediaStore, so no storage permission is needed.
 */
final class MediaSaver {
    static final String PICTURES_DIR = Environment.DIRECTORY_PICTURES + "/NightSky";
    static final String DOCS_DIR = Environment.DIRECTORY_DOCUMENTS + "/NightSky";

    private MediaSaver() {}

    /** What goes into the JPEG's EXIF block. */
    static final class Exif {
        String make = "", model = "", description = "";
        double exposureSeconds;
        int iso;
        float fNumber, focalLengthMm;
        Date time = new Date();
        int width, height;
        /** Set by {@link #saveJpeg} when the block couldn't be written, so the photo was saved without it: why. */
        String failed;
        /** Set by {@link #saveJpeg} when its video couldn't go in, so the photo was saved without it: why. */
        String videoFailed;
        /** Set by {@link #saveJpeg} when the photo was saved as an HDR photo: what was brightened, for the log. */
        String hdr;
        /** Set by {@link #saveJpeg} when an HDR photo was asked for and it was saved as an ordinary one: why. */
        String hdrFailed;
    }

    interface Writer {
        void write(OutputStream os) throws IOException;
    }

    /**
     * Saves {@code bmp} as a JPEG in Pictures/NightSky. If its EXIF block can't be written, the photo is saved without
     * it and {@code exif.failed} says why: a photo without its date and exposure beats no photo.
     */
    static Uri saveJpeg(Context c, Bitmap bmp, String name, Exif exif) throws IOException {
        return saveJpeg(c, bmp, name, exif, null, 0);
    }

    /**
     * {@link #saveJpeg(Context, Bitmap, String, Exif)} with {@code video} (an MP4, null for none) inside the photo as a
     * Motion Photo whose frame at {@code stillUs} is the photo: named name.MP.jpg, as the format asks, so Google Photos
     * plays it. If it can't go in, the photo is saved as name.jpg without it and {@code exif.videoFailed} says why.
     */
    static Uri saveJpeg(Context c, Bitmap bmp, String name, Exif exif, File video, long stillUs) throws IOException {
        return saveJpeg(c, bmp, name, exif, video, stillUs, false);
    }

    /**
     * {@link #saveJpeg(Context, Bitmap, String, Exif, File, long)}, as an HDR photo when {@code hdr} (2.0): the photo
     * with a second, small picture behind it that tells a screen with the headroom how much brighter than white to show
     * its stars ({@link HdrGain}, in Android's Ultra HDR layout: {@link UltraHdr}). Everything else shows the photo
     * exactly as before. If that can't be made or written, the photo is saved as before and {@code exif.hdrFailed} says
     * why; a photo with nothing bright enough to lift is saved as before too.
     */
    static Uri saveJpeg(Context c, Bitmap bmp, String name, Exif exif, File video, long stillUs, boolean hdr) throws IOException {
        File tmp = new File(c.getCacheDir(), "save_" + System.nanoTime() + ".jpg");
        File gain = null, out = null;
        try {
            encode(bmp, tmp);
            if (exif != null) {
                try {
                    writeExif(tmp, exif);
                } catch (IOException | RuntimeException e) {
                    exif.failed = e.getMessage() != null ? e.getMessage() : e.toString();
                    // a write that failed part of the way can leave the file broken: encoded again, as it was
                    encode(bmp, tmp);
                }
            }
            String note = null;
            if (hdr) {
                try {
                    gain = new File(c.getCacheDir(), "save_" + System.nanoTime() + ".gain.jpg");
                    note = gainMap(bmp, gain);
                    if (note == null && exif != null) exif.hdrFailed = "nothing in it is bright enough to lift";
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    note = null;
                    if (exif != null) exif.hdrFailed = why(e);
                }
            }
            File src = tmp;
            String display = name + ".jpg";
            out = new File(c.getCacheDir(), "save_" + System.nanoTime() + ".out.jpg");
            boolean done = false;
            // the photo with its map (and its video); failing that with its video alone; failing that with its map alone
            if (note != null) {
                String[] back = new String[1];
                String err = writeHdr(tmp, gain, video, stillUs, out, back);
                if (err == null) {
                    done = true;
                    src = out;
                    if (video != null) display = name + ".MP.jpg";
                    if (exif != null) exif.hdr = note + "; " + back[0];
                } else if (exif != null) {
                    exif.hdrFailed = err;
                }
            }
            if (!done && video != null) {
                try {
                    OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
                    try {
                        MotionPhoto.write(tmp, video, stillUs, os);
                    } finally {
                        os.close();
                    }
                    done = true;
                    src = out;
                    display = name + ".MP.jpg";
                } catch (IOException | RuntimeException e) {
                    if (exif != null) exif.videoFailed = why(e);
                    String[] back = new String[1];
                    if (note != null && writeHdr(tmp, gain, null, 0, out, back) == null) {
                        src = out;
                        if (exif != null) {
                            exif.hdr = note + "; " + back[0];
                            exif.hdrFailed = null;
                        }
                    }
                }
            }
            final File from = src;
            return insert(c, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), display,
                    "image/jpeg", PICTURES_DIR, exif != null ? exif.time : new Date(), new Writer() {
                        @Override
                        public void write(OutputStream os) throws IOException {
                            copy(from, os);
                        }
                    });
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            //noinspection ResultOfMethodCallIgnored
            if (gain != null) gain.delete();
            //noinspection ResultOfMethodCallIgnored
            if (out != null) out.delete();
        }
    }

    private static String why(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /**
     * The photo, its map and its video (null: none) into {@code out}; null if written, or why not. Written, the file is
     * handed to this phone's own decoder ({@link HdrScreen#readsBack}): if that reads photos' maps and not this one's,
     * the file isn't kept (it's "why not"). {@code back[0]}: what the decoder said, for the log.
     */
    private static String writeHdr(File jpeg, File gain, File video, long stillUs, File out, String[] back) {
        try {
            OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 16);
            try {
                UltraHdr.write(jpeg, gain, HdrGain.meta(), video, stillUs, os);
            } finally {
                os.close();
            }
        } catch (IOException | RuntimeException e) {
            return why(e);
        }
        int read = HdrScreen.readsBack(out);
        if (read == HdrScreen.NOT_READ) {
            return "this phone's own decoder didn't find the brightness map in the file as it was written, so it's left out "
                    + "rather than trusted";
        }
        back[0] = read == HdrScreen.READ ? "read back by this phone's own decoder before it was saved"
                : "not read back, as this Android's decoder reads no brightness maps";
        return null;
    }

    /** The map's JPEG quality: its values are smooth but for the stars, whose shapes it must keep. */
    private static final int GAIN_QUALITY = 90;

    /**
     * Writes {@code bmp}'s brightness map to {@code f}, a JPEG as large as the photo (a star is a pixel or two wide: a
     * smaller map would smear its lift over the sky around it). Returns the line for the log, or null when the photo
     * has nothing to lift (and no file is written).
     */
    static String gainMap(Bitmap bmp, File f) throws IOException {
        int w = bmp.getWidth(), h = bmp.getHeight();
        HdrGain g = new HdrGain(w, h);
        int rows = Math.max(1, Math.min(h, (1 << 18) / w));
        int[] px = new int[w * rows];
        for (int y = 0; y < h; y += rows) {
            int n = Math.min(rows, h - y);
            bmp.getPixels(px, 0, w, 0, y, w, n);
            for (int j = 0; j < n; j++) g.measure(y + j, px, j * w);
        }
        // the encoder takes no single-channel bitmap: grey in all three, which costs the file next to nothing
        Bitmap map = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        try {
            byte[] line = new byte[w];
            for (int y = 0; y < h; y += rows) {
                int n = Math.min(rows, h - y);
                bmp.getPixels(px, 0, w, 0, y, w, n);
                for (int j = 0; j < n; j++) {
                    g.row(y + j, px, j * w, line, 0);
                    for (int x = 0, o = j * w; x < w; x++, o++) px[o] = 0xFF000000 | ((line[x] & 255) * 0x010101);
                }
                map.setPixels(px, 0, w, 0, y, w, n);
            }
            if (g.most() == 0) return null;
            OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16);
            try {
                if (!map.compress(Bitmap.CompressFormat.JPEG, GAIN_QUALITY, os)) throw new IOException("JPEG encoding of the map failed");
            } finally {
                os.close();
            }
            return g.note();
        } finally {
            map.recycle();
        }
    }

    static Uri saveDng(Context c, String name, Date time, Writer w) throws IOException {
        return insert(c, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name + ".dng",
                "image/x-adobe-dng", PICTURES_DIR, time, w);
    }

    /** A file of any type in Documents/NightSky (a CSV log, a settings export). */
    static Uri saveDocument(Context c, String name, String mime, Writer w) throws IOException {
        return insert(c, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, mime, DOCS_DIR, new Date(), w);
    }

    static Uri saveText(Context c, String name, final String text) throws IOException {
        return insert(c, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name, "text/plain",
                DOCS_DIR, new Date(), new Writer() {
                    @Override
                    public void write(OutputStream os) throws IOException {
                        os.write(text.getBytes("UTF-8"));
                    }
                });
    }

    private static Uri insert(Context c, Uri collection, String displayName, String mime, String relPath, Date time,
                              Writer w) throws IOException {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, relPath);
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = cr.insert(collection, v);
        if (uri == null) throw new IOException("Could not create " + displayName);
        boolean ok = false;
        try {
            OutputStream os = cr.openOutputStream(uri, "w");
            if (os == null) throw new IOException("Could not write " + displayName);
            OutputStream bos = new BufferedOutputStream(os, 1 << 20);
            try {
                w.write(bos);
            } finally {
                bos.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            ok = true;
            return uri;
        } finally {
            if (!ok) {
                try {
                    cr.delete(uri, null, null);
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    private static void encode(Bitmap bmp, File f) throws IOException {
        OutputStream os = new BufferedOutputStream(new FileOutputStream(f), 1 << 16);
        try {
            // an ordinary JPEG even when the bitmap carries a brightness map for the screen: the file's own map is
            // written by this class
            if (!HdrScreen.compressPlain(bmp, 95, os)) throw new IOException("JPEG encoding failed");
        } finally {
            os.close();
        }
    }

    private static void writeExif(File f, Exif e) throws IOException {
        ExifInterface x = new ExifInterface(f.getAbsolutePath());
        String t = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(e.time);
        x.setAttribute(ExifInterface.TAG_DATETIME, t);
        x.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, t);
        x.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, t);
        x.setAttribute(ExifInterface.TAG_MAKE, e.make);
        x.setAttribute(ExifInterface.TAG_MODEL, e.model);
        x.setAttribute(ExifInterface.TAG_SOFTWARE, "Night Sky Camera");
        x.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, e.description);
        x.setAttribute(ExifInterface.TAG_USER_COMMENT, e.description);
        if (e.exposureSeconds > 0) x.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, String.format(Locale.US, "%.1f", e.exposureSeconds));
        if (e.iso > 0) x.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, Integer.toString(e.iso));
        if (e.fNumber > 0) x.setAttribute(ExifInterface.TAG_F_NUMBER, String.format(Locale.US, "%.2f", e.fNumber));
        if (e.focalLengthMm > 0) {
            x.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, Math.round(e.focalLengthMm * 1000) + "/1000");
        }
        x.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_NORMAL));
        x.saveAttributes();
    }

    private static void copy(File f, OutputStream os) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } finally {
            in.close();
        }
    }
}
