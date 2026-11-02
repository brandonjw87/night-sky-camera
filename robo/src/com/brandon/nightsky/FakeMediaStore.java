package com.brandon.nightsky;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import org.robolectric.Robolectric;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MediaStore off the phone: what the app saves in Pictures and Documents lands in real files (in {@link #dir}, named by
 * their id and display name), with its row (name, type, folder, pending), so a test can read back what the gallery
 * would show. Robolectric's own content resolver keeps none of it.
 */
public final class FakeMediaStore extends ContentProvider {
    static File dir;
    static final Map<Long, ContentValues> rows = new ConcurrentHashMap<Long, ContentValues>();
    private static final AtomicLong next = new AtomicLong(1);

    /** Stands in for MediaStore from now on, its files in {@code d}. */
    static void install(File d) {
        dir = d;
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        // an earlier run's files would be taken for this one's
        File[] old = d.listFiles();
        if (old != null) {
            //noinspection ResultOfMethodCallIgnored
            for (File f : old) f.delete();
        }
        Robolectric.setupContentProvider(FakeMediaStore.class, MediaStore.AUTHORITY);
    }

    /** The file behind a saved item's URI, or null. */
    static File file(Uri uri) {
        try {
            long id = ContentUris.parseId(uri);
            return rows.containsKey(id) ? fileOf(id) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A saved item's display name, or null. */
    static String name(Uri uri) {
        try {
            ContentValues v = rows.get(ContentUris.parseId(uri));
            return v != null ? v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static File fileOf(long id) {
        ContentValues v = rows.get(id);
        String n = v != null ? v.getAsString(MediaStore.MediaColumns.DISPLAY_NAME) : null;
        return new File(dir, id + "_" + (n != null ? n.replace('/', '_') : "item"));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        long id = next.getAndIncrement();
        rows.put(id, new ContentValues(values));
        return ContentUris.withAppendedId(uri, id);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        long id = ContentUris.parseId(uri);
        if (!rows.containsKey(id)) throw new FileNotFoundException("nothing at " + uri);
        return ParcelFileDescriptor.open(fileOf(id), ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        long id;
        try {
            id = ContentUris.parseId(uri);
        } catch (RuntimeException e) {
            return null;
        }
        ContentValues v = rows.get(id);
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols);
        if (v == null) return c;
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.SIZE.equals(cols[i])) row[i] = fileOf(id).length();
            else if (MediaStore.MediaColumns._ID.equals(cols[i])) row[i] = id;
            else row[i] = v.get(cols[i]);
        }
        c.addRow(row);
        return c;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        long id = ContentUris.parseId(uri);
        ContentValues v = rows.get(id);
        if (v == null) return 0;
        File was = fileOf(id);
        v.putAll(values);
        File now = fileOf(id);
        //noinspection ResultOfMethodCallIgnored
        if (!was.equals(now) && was.exists()) was.renameTo(now);
        return 1;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        long id = ContentUris.parseId(uri);
        if (!rows.containsKey(id)) return 0;
        //noinspection ResultOfMethodCallIgnored
        fileOf(id).delete();
        rows.remove(id);
        return 1;
    }

    @Override
    public String getType(Uri uri) {
        try {
            ContentValues v = rows.get(ContentUris.parseId(uri));
            return v != null ? v.getAsString(MediaStore.MediaColumns.MIME_TYPE) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
