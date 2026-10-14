package com.brandon.nightsky;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

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
    }

    interface Writer {
        void write(OutputStream os) throws IOException;
    }

    static Uri saveJpeg(Context c, Bitmap bmp, String name, Exif exif) throws IOException {
        File tmp = new File(c.getCacheDir(), "save_" + System.nanoTime() + ".jpg");
        try {
            OutputStream os = new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16);
            try {
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 95, os)) throw new IOException("JPEG encoding failed");
            } finally {
                os.close();
            }
            if (exif != null) writeExif(tmp, exif);
            final File src = tmp;
            return insert(c, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name + ".jpg",
                    "image/jpeg", PICTURES_DIR, exif != null ? exif.time : new Date(), new Writer() {
                        @Override
                        public void write(OutputStream os) throws IOException {
                            copy(src, os);
                        }
                    });
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    static Uri saveDng(Context c, String name, Date time, Writer w) throws IOException {
        return insert(c, MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), name + ".dng",
                "image/x-adobe-dng", PICTURES_DIR, time, w);
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
