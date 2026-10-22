package com.brandon.nightsky;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.SatCatalog;
import com.brandon.nightsky.processing.SatData;

import java.io.File;

/**
 * The phone's satellite data: CelesTrak's orbital elements cached in the app's files ({@link SatData}), fetched
 * on demand on a background thread (the large Starlink lists only on Wi-Fi or another unmetered network), and
 * read into one catalogue that is kept in memory until a newer download arrives.
 */
final class Satellites {
    private static SatData data;
    private static SatCatalog cached;
    private static long cachedStamp;
    private static double cachedJd;
    private static volatile boolean busy;

    private Satellites() {}

    static synchronized SatData data(Context c) {
        if (data == null) {
            data = new SatData(new File(c.getApplicationContext().getFilesDir(), "satellites"),
                    SatData.http("NightSkyCamera/" + BuildInfo.VERSION + " (personal astrophotography app)"));
        }
        return data;
    }

    /**
     * Fetches whatever is due, on a background thread, if satellite data is on; {@code done} then runs on the
     * main thread with the log line ("" if nothing was due). {@code retryRefused}: Brandon tapped Update, so
     * lists CelesTrak refused are asked for again.
     */
    static void refresh(Context c, final boolean retryRefused, final Callback done) {
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String l = refreshNow(app, retryRefused);
                if (done != null) main.post(new Runnable() {
                    @Override
                    public void run() {
                        done.done(l);
                    }
                });
            }
        }, "satellite-data").start();
    }

    /**
     * Fetches whatever is due, on this thread (not the main one): the log line, "" if nothing was due, satellite
     * data is off or another download is running. The large Starlink lists only come over Wi-Fi.
     */
    static String refreshNow(Context c, boolean retryRefused) {
        Context app = c.getApplicationContext();
        if (!new Prefs(app).satelliteData()) return "";
        boolean large = !metered(app);
        synchronized (Satellites.class) {
            if (busy) return "";
            if (!retryRefused && !data(app).due(System.currentTimeMillis(), large)) return "";
            busy = true;
        }
        String log = "";
        try {
            log = data(app).refresh(System.currentTimeMillis(), large, retryRefused);
            if (!log.isEmpty()) android.util.Log.i("NightSky", log);
        } catch (RuntimeException e) {
            log = "satellite data: " + e;
        } finally {
            busy = false;
        }
        return log;
    }

    /** Whether a download is running. */
    static boolean busy() {
        return busy;
    }

    interface Callback {
        void done(String log);
    }

    /**
     * The catalogue for a session at {@code jdUtc}, from the cached lists (kept in memory until a newer
     * download); null if there are none or satellite data is off. Slow the first time: call off the main thread.
     */
    static synchronized SatCatalog catalog(Context c, double jdUtc) {
        if (!new Prefs(c).satelliteData()) return null;
        SatData d = data(c);
        long stamp = d.newestFetchMs();
        if (cached != null && stamp == cachedStamp && Math.abs(jdUtc - cachedJd) < 0.5) return cached;
        cached = d.catalog(jdUtc);
        cachedStamp = stamp;
        cachedJd = jdUtc;
        return cached;
    }

    /** The catalogue for now (see {@link #catalog(Context, double)}). */
    static SatCatalog catalogNow(Context c) {
        return catalog(c, Astro.jdUtc(System.currentTimeMillis() / 1000.0));
    }

    static boolean metered(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm == null || cm.isActiveNetworkMetered();
    }
}
