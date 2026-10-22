package com.brandon.nightsky;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.location.Location;
import android.location.LocationManager;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.SystemClock;

import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Where the phone is, roughly: enough for the Sun, the Moon, the compass and the frame length (a few kilometres
 * is plenty; the app asks for approximate location only). The newest fix any provider still has is used at once;
 * a fresh one is asked for in the background and remembered for nights without a signal. Nothing leaves the phone.
 */
final class Locator {
    static final class Place {
        double latDeg, lonDeg, altM = Double.NaN;
        /** How far off the fix may be (metres, NaN if the provider didn't say). */
        double accuracyM = Double.NaN;
        String source = "";
        /** When the fix was taken (Unix ms). */
        long timeMs;

        double ageMinutes() {
            return Math.max(0, (System.currentTimeMillis() - timeMs) / 60000.0);
        }

        String describe() {
            double age = ageMinutes();
            String a = age < 2 ? "just now" : age < 120 ? Math.round(age) + " min old"
                    : age < 48 * 60 ? Math.round(age / 60) + " h old" : Math.round(age / 1440) + " days old";
            return String.format(Locale.US, "%.3f, %.3f (%s, %s%s)", latDeg, lonDeg, source, a,
                    Double.isNaN(accuracyM) ? "" : String.format(Locale.US, ", within %.0f m", accuracyM));
        }
    }

    interface Listener {
        /** On the handler's thread; null if no fix came. */
        void onPlace(Place p);
    }

    private Locator() {}

    static boolean allowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** The freshest place known without waiting: the newest fix any provider still holds, else the saved one. */
    static Place best(Context c, Prefs prefs) {
        Place saved = prefs.savedPlace();
        if (!allowed(c)) return saved;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        Location newest = null;
        if (lm != null) {
            for (String p : lm.getAllProviders()) {
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (newest == null || l.getTime() > newest.getTime())) newest = l;
                } catch (SecurityException | IllegalArgumentException ignored) {
                }
            }
        }
        if (newest == null || (saved != null && saved.timeMs >= newest.getTime())) return saved;
        Place p = of(newest, "last fix");
        prefs.savePlace(p);
        return p;
    }

    /**
     * Asks for a fresh fix (fused, else network) and saves it; {@code l} hears once, on {@code h}, within
     * {@code timeoutMs} (null if none came). Returns a signal that cancels the request, or null if none was made.
     */
    static CancellationSignal refresh(Context c, final Prefs prefs, final Handler h, long timeoutMs, final Listener l) {
        if (!allowed(c)) return null;
        LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return null;
        String provider = null;
        for (String p : new String[]{LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                if (lm.getAllProviders().contains(p) && lm.isProviderEnabled(p)) {
                    provider = p;
                    break;
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (provider == null) return null;
        final CancellationSignal cancel = new CancellationSignal();
        final boolean[] heard = {false};
        final String src = LocationManager.FUSED_PROVIDER.equals(provider) ? "fused" : "network";
        Executor onHandler = new Executor() {
            @Override
            public void execute(Runnable r) {
                h.post(r);
            }
        };
        try {
            lm.getCurrentLocation(provider, cancel, onHandler, new java.util.function.Consumer<Location>() {
                @Override
                public void accept(Location loc) {
                    if (heard[0]) return;
                    heard[0] = true;
                    Place p = loc != null ? of(loc, src) : null;
                    if (p != null) prefs.savePlace(p);
                    l.onPlace(p);
                }
            });
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (heard[0]) return;
                heard[0] = true;
                cancel.cancel();
                l.onPlace(null);
            }
        }, timeoutMs);
        return cancel;
    }

    private static Place of(Location l, String source) {
        Place p = new Place();
        p.latDeg = l.getLatitude();
        p.lonDeg = l.getLongitude();
        if (l.hasAltitude()) p.altM = l.getAltitude();
        if (l.hasAccuracy()) p.accuracyM = l.getAccuracy();
        p.source = source;
        // a fix's age by the realtime clock, which a wrong wall clock doesn't upset
        long ageMs = Math.max(0, (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1_000_000L);
        p.timeMs = l.getElapsedRealtimeNanos() > 0 ? System.currentTimeMillis() - ageMs : l.getTime();
        return p;
    }

    /** Degrees to add to a magnetic heading for true north here (0 if the place is unknown). */
    static double declination(Place p) {
        if (p == null) return 0;
        float alt = Double.isNaN(p.altM) ? 0f : (float) p.altM;
        return new GeomagneticField((float) p.latDeg, (float) p.lonDeg, alt, System.currentTimeMillis()).getDeclination();
    }
}
