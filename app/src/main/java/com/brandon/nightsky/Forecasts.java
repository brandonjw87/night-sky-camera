package com.brandon.nightsky;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.brandon.nightsky.processing.Tonight;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.GZIPInputStream;

/**
 * The cloud forecast for tonight's score (D1): Open-Meteo's free hourly forecast (no account, no key), asked
 * for the place rounded to 0.1 degrees (about 10 km), so nothing more exact than the town leaves the phone.
 * Kept in the app's files and asked for again after an hour; the screen works without it.
 */
final class Forecasts {
    /** Asked for again after this long. */
    static final long FRESH_MS = 3600_000L;
    /** Used for tonight's score up to this old. */
    static final long MAX_AGE_MS = 18 * 3600_000L;
    private static final int MAX_BYTES = 1 << 20;
    private static final Object LOCK = new Object();
    private static volatile boolean busy;

    interface Callback {
        /** On the main thread: the forecast now cached (null if there is none), and an error ("" if none). */
        void done(Tonight.Forecast f, String error);
    }

    private Forecasts() {}

    private static File file(Context c) {
        return new File(c.getApplicationContext().getFilesDir(), "forecast.json");
    }

    /** The cached forecast if it is for about this place and not too old; else null. */
    static Tonight.Forecast cached(Context c, double latDeg, double lonDeg) {
        synchronized (LOCK) {
            File f = file(c);
            if (!f.exists()) return null;
            try {
                Tonight.Forecast fc = Tonight.Forecast.fromOpenMeteo(Plans.readText(f), f.lastModified());
                if (System.currentTimeMillis() - fc.fetchedMs > MAX_AGE_MS) return null;
                if (!near(fc, latDeg, lonDeg)) return null;
                return fc;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }
    }

    private static boolean near(Tonight.Forecast fc, double lat, double lon) {
        if (Double.isNaN(fc.latDeg) || Double.isNaN(lat)) return false;
        double dLon = Math.abs(fc.lonDeg - lon);
        if (dLon > 180) dLon = 360 - dLon;
        return Math.abs(fc.latDeg - lat) < 0.2 && dLon * Math.cos(Math.toRadians(lat)) < 0.2;
    }

    /** Whether it is time to ask again (nothing cached for here, or older than an hour). */
    static boolean due(Context c, double latDeg, double lonDeg) {
        Tonight.Forecast f = cached(c, latDeg, lonDeg);
        return f == null || System.currentTimeMillis() - f.fetchedMs > FRESH_MS;
    }

    static String url(double latDeg, double lonDeg) {
        return String.format(Locale.US, "https://api.open-meteo.com/v1/forecast?latitude=%.1f&longitude=%.1f"
                + "&hourly=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,relative_humidity_2m,dew_point_2m,"
                + "temperature_2m,wind_speed_10m,visibility&forecast_days=3&past_days=1&timezone=GMT&timeformat=unixtime",
                latDeg, lonDeg);
    }

    /** Asks for a new forecast on a background thread if one is due (or {@code force}); {@code cb} may be null. */
    static void refresh(Context c, final double latDeg, final double lonDeg, boolean force, final Callback cb) {
        final Context app = c.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        if (Double.isNaN(latDeg) || !new Prefs(app).forecast() || (!force && !due(app, latDeg, lonDeg)) || busy) {
            if (cb != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(Double.isNaN(latDeg) ? null : cached(app, latDeg, lonDeg), "");
                    }
                });
            }
            return;
        }
        busy = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = "";
                try {
                    fetch(app, latDeg, lonDeg);
                } catch (IOException | RuntimeException e) {
                    err = e.getMessage() != null ? e.getMessage() : e.toString();
                    android.util.Log.w("NightSky", "forecast: " + err);
                } finally {
                    busy = false;
                }
                final String fe = err;
                if (cb != null) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            cb.done(cached(app, latDeg, lonDeg), fe);
                        }
                    });
                }
            }
        }, "forecast").start();
    }

    /** Downloads and saves the forecast (on this thread, not the main one). */
    static void fetch(Context c, double latDeg, double lonDeg) throws IOException {
        HttpURLConnection h = (HttpURLConnection) java.net.URI.create(url(latDeg, lonDeg)).toURL().openConnection();
        h.setConnectTimeout(15000);
        h.setReadTimeout(20000);
        h.setRequestProperty("User-Agent", "NightSkyCamera/" + BuildInfo.VERSION + " (personal astrophotography app)");
        h.setRequestProperty("Accept-Encoding", "gzip");
        try {
            int code = h.getResponseCode();
            if (code != 200) throw new IOException("the forecast service answered " + code);
            InputStream in = h.getInputStream();
            if ("gzip".equalsIgnoreCase(h.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream(64 * 1024);
            try {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > MAX_BYTES) throw new IOException("the forecast is too large");
                }
            } finally {
                in.close();
            }
            String json = new String(bo.toByteArray(), StandardCharsets.UTF_8);
            Tonight.Forecast.fromOpenMeteo(json, System.currentTimeMillis()); // throws if it isn't a forecast
            synchronized (LOCK) {
                Plans.writeText(file(c), json);
            }
        } finally {
            h.disconnect();
        }
    }
}
