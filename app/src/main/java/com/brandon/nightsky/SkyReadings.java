package com.brandon.nightsky;

import android.content.Context;
import android.net.Uri;

import com.brandon.nightsky.processing.SkyReading;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The sky readings' log (H3): one line per reading, kept in the app's own files, and saved on request as a CSV
 * file in Documents/NightSky that opens in Excel (UTF-8 with a byte-order mark, so the degree signs survive).
 */
final class SkyReadings {
    private SkyReadings() {}

    static File file(Context c) {
        return new File(c.getFilesDir(), "sky-readings.csv");
    }

    /** Adds a reading to the log; null if it worked, else what went wrong. */
    static synchronized String append(Context c, SkyReading r) {
        File f = file(c);
        try {
            boolean fresh = !f.exists() || f.length() == 0;
            Writer w = new OutputStreamWriter(new FileOutputStream(f, true), "UTF-8");
            try {
                if (fresh) w.write(SkyReading.csvHeader() + "\r\n");
                w.write(r.csvRow(TimeZone.getDefault()) + "\r\n");
            } finally {
                w.close();
            }
            return null;
        } catch (IOException e) {
            return e.toString();
        }
    }

    /** Readings in the log. */
    static synchronized int count(Context c) {
        File f = file(c);
        if (!f.exists()) return 0;
        int n = 0;
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            try {
                while (r.readLine() != null) n++;
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return 0;
        }
        return Math.max(0, n - 1);
    }

    /** Saves the whole log as a new file in Documents/NightSky; its address. */
    static Uri export(Context c) throws IOException {
        final File f = file(c);
        if (!f.exists()) throw new IOException("There are no readings yet");
        SimpleDateFormat d = new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US);
        String name = "sky-readings_" + d.format(new Date()) + ".csv";
        synchronized (SkyReadings.class) {
            return MediaSaver.saveDocument(c, name, "text/csv", new MediaSaver.Writer() {
                @Override
                public void write(OutputStream os) throws IOException {
                    os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
                    FileInputStream in = new FileInputStream(f);
                    try {
                        byte[] buf = new byte[1 << 14];
                        int k;
                        while ((k = in.read(buf)) > 0) os.write(buf, 0, k);
                    } finally {
                        in.close();
                    }
                }
            });
        }
    }
}
