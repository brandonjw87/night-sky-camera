package com.brandon.nightsky.processing;

import java.util.Locale;
import java.util.Properties;

/**
 * Where and when a session was taken: the observer's place, the clock that turns the camera's timestamps
 * into UTC, and the phone's heading when it was put down. Stored with the session's settings, so a session
 * finished after a crash knows them too.
 */
public final class SessionSky {
    /** Observer (degrees, metres); NaN where unknown. */
    public double latDeg = Double.NaN, lonDeg = Double.NaN, altM = Double.NaN;
    /** Where the place came from ("gps", "network", "last known", "saved"), and how old it was (minutes). */
    public String locationSource = "";
    public double locationAgeMin = Double.NaN;
    /** How far off the place may be (metres, NaN if unknown): an approximate fix is good to a few kilometres. */
    public double accuracyM = Double.NaN;
    /** A wall-clock time (Unix ms) and the camera timestamp (ns) of the same instant. */
    public long clockWallMs, clockTimestampNs;
    public boolean clockValid;
    /** Azimuth (degrees east of north) of the phone's top edge when it was put down; NaN if unknown. */
    public double headingTopDeg = Double.NaN;
    /** The bright-star catalogue (not stored). */
    public BrightStars catalog;

    public SessionSky copy() {
        SessionSky s = new SessionSky();
        s.latDeg = latDeg;
        s.lonDeg = lonDeg;
        s.altM = altM;
        s.locationSource = locationSource;
        s.locationAgeMin = locationAgeMin;
        s.accuracyM = accuracyM;
        s.clockWallMs = clockWallMs;
        s.clockTimestampNs = clockTimestampNs;
        s.clockValid = clockValid;
        s.headingTopDeg = headingTopDeg;
        s.catalog = catalog;
        return s;
    }

    public boolean located() {
        return !Double.isNaN(latDeg) && !Double.isNaN(lonDeg);
    }

    /** UTC (Unix ms) of a camera timestamp. */
    public double utcMs(long timestampNs) {
        return clockWallMs + (timestampNs - clockTimestampNs) / 1e6;
    }

    /** UTC Julian date of a camera timestamp. */
    public double jdUtc(long timestampNs) {
        return Astro.jdUtc(utcMs(timestampNs) / 1000.0);
    }

    public void toProperties(Properties p) {
        if (located()) {
            p.setProperty("lat", Double.toString(latDeg));
            p.setProperty("lon", Double.toString(lonDeg));
            if (!Double.isNaN(altM)) p.setProperty("alt", Double.toString(altM));
            p.setProperty("locationSource", locationSource);
            if (!Double.isNaN(locationAgeMin)) p.setProperty("locationAgeMin", Double.toString(locationAgeMin));
            if (!Double.isNaN(accuracyM)) p.setProperty("accuracyM", Double.toString(accuracyM));
        }
        if (clockValid) {
            p.setProperty("clockWallMs", Long.toString(clockWallMs));
            p.setProperty("clockTimestampNs", Long.toString(clockTimestampNs));
        }
        if (!Double.isNaN(headingTopDeg)) p.setProperty("headingTopDeg", Double.toString(headingTopDeg));
    }

    public static SessionSky fromProperties(Properties p) {
        SessionSky s = new SessionSky();
        s.latDeg = num(p, "lat");
        s.lonDeg = num(p, "lon");
        s.altM = num(p, "alt");
        s.locationSource = p.getProperty("locationSource", "");
        s.locationAgeMin = num(p, "locationAgeMin");
        s.accuracyM = num(p, "accuracyM");
        s.headingTopDeg = num(p, "headingTopDeg");
        try {
            String w = p.getProperty("clockWallMs"), t = p.getProperty("clockTimestampNs");
            if (w != null && t != null) {
                s.clockWallMs = Long.parseLong(w);
                s.clockTimestampNs = Long.parseLong(t);
                s.clockValid = true;
            }
        } catch (NumberFormatException ignored) {
        }
        return s;
    }

    private static double num(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null) return Double.NaN;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (located()) {
            sb.append(String.format(Locale.US, "at %.3f, %.3f", latDeg, lonDeg));
            if (!locationSource.isEmpty()) sb.append(" (").append(locationSource).append(')');
        } else {
            sb.append("place unknown");
        }
        sb.append(clockValid ? ", clock known" : ", clock unknown");
        if (!Double.isNaN(headingTopDeg)) sb.append(String.format(Locale.US, ", phone top toward %.0f deg", headingTopDeg));
        return sb.toString();
    }
}
