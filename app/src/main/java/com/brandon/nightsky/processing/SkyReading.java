package com.brandon.nightsky.processing;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A sky reading (H3): how dark the sky is overhead, from three test frames, each measured on its own against its
 * own stars. The sky brightness in magnitudes per square arcsecond, with the stars (what a handheld SQM-L meter
 * reads, over the same central 20 degrees) and between them, the frames' mean and spread, the naked-eye limiting
 * magnitude that goes with it, and whether the reading counts: not with the Moon up, in twilight, under cloud or
 * through a smudged lens. An offset learned from a real meter, if Brandon ever checks it against one, is added.
 * <p>
 * Not done from the full H3: the dark frame (the phone lies camera-up, so the lens can't be covered; the black
 * level the sensor reports for each frame stands in for it), the light-pollution map and "find darker sky".
 */
public final class SkyReading {
    /** Frames a reading takes. */
    public static final int FRAMES = 3;
    /** Frames further apart than this (mag/arcsec²) make the reading not count: the sky changed (passing cloud). */
    public static final double MAX_SPREAD = 0.25;

    public long timeMs;
    public double latDeg = Double.NaN, lonDeg = Double.NaN;
    /** Magnitudes per square arcsecond, means over the frames (offset included): with the stars, and between them. */
    public double meterMag = Double.NaN, skyMag = Double.NaN;
    /** Highest minus lowest of the frames' {@link #meterMag} (NaN with one frame). */
    public double spread = Double.NaN;
    /** Frames measured, and taken. */
    public int frames, taken;
    /** Each frame's exposure (s) and ISO. */
    public double exposureS = Double.NaN;
    public int iso;
    /** Added to every value: a real meter's reading minus this app's, from a check against one (0: none). */
    public double offset;
    /** Naked-eye limiting magnitude from {@link #meterMag}. */
    public double nelm = Double.NaN;
    /** The faintest catalogue star the camera matched (V); NaN if none. */
    public double faintestMag = Double.NaN;
    public boolean valid;
    /** Why it doesn't count ("" if it does). */
    public String why = "";
    public int stars, expected, seen;
    public double sunAltDeg = Double.NaN, moonAltDeg = Double.NaN, moonIllum = Double.NaN, hazeMag = Double.NaN;
    /** The saved site it was taken at ("" if none). */
    public String site = "";
    /** "20.41 mag/arcsec²: a suburban sky", for the screen. */
    public String headline = "";
    public final List<String> lines = new ArrayList<String>();
    public String spoken = "";

    /** Unihedron's relation from SQM to naked-eye limiting magnitude. */
    public static double nelm(double sqm) {
        return 7.93 - 5 * Math.log10(Math.pow(10, 4.316 - sqm / 5) + 1);
    }

    /** "a suburban sky" (the report card's classes). */
    public static String skyClass(double m) {
        return ReportCard.skyClass(m);
    }

    /** Whether a frame's report has a sky brightness. */
    public static boolean measured(Preflight.Report r) {
        return r != null && !Double.isNaN(r.skyMagWithStars) && !Double.isNaN(r.skyMag);
    }

    /**
     * The reading from the frames' reports ({@code reps}, in the order taken; empty if none came), with {@code offset}
     * added; {@code failWhy} says why there are no more frames when that is a failure ("" otherwise).
     */
    public static SkyReading of(List<Preflight.Report> reps, long timeMs, double offset, String failWhy) {
        SkyReading s = new SkyReading();
        s.timeMs = timeMs;
        s.offset = Double.isNaN(offset) ? 0 : offset;
        s.taken = reps.size();
        double sumM = 0, sumS = 0, lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        Preflight.Report first = null, last = null;
        boolean cloud = false, lens = false;
        for (Preflight.Report r : reps) {
            last = r;
            Preflight.Item c = r.item("clouds"), l = r.item("lens");
            cloud |= c != null && c.level == Preflight.Level.WARN;
            lens |= l != null && l.level == Preflight.Level.WARN;
            if (!Double.isNaN(r.latDeg)) {
                s.latDeg = r.latDeg;
                s.lonDeg = r.lonDeg;
            }
            if (!measured(r)) continue;
            if (first == null) first = r;
            s.frames++;
            sumM += r.skyMagWithStars;
            sumS += r.skyMag;
            lo = Math.min(lo, r.skyMagWithStars);
            hi = Math.max(hi, r.skyMagWithStars);
            if (!(r.faintestMag <= s.faintestMag)) s.faintestMag = r.faintestMag;
        }
        Preflight.Report info = first != null ? first : last;
        if (info != null) {
            s.stars = info.stars;
            s.expected = info.expected;
            s.seen = info.seen;
            s.hazeMag = info.hazeMag;
            s.exposureS = info.exposureS;
            s.iso = info.iso;
        }
        if (last != null) {
            s.sunAltDeg = last.sunAltDeg;
            s.moonAltDeg = last.moonAltDeg;
            s.moonIllum = last.moonIllum;
        }
        if (s.frames > 0) {
            s.meterMag = sumM / s.frames + s.offset;
            s.skyMag = sumS / s.frames + s.offset;
            if (s.frames > 1) s.spread = hi - lo;
            s.nelm = nelm(s.meterMag);
        }

        // whether it counts
        List<String> no = new ArrayList<String>();
        if (s.frames == 0) {
            if (!failWhy.isEmpty()) no.add(failWhy);
            else if (!reps.isEmpty()) no.add("the stars' pattern wasn't found, so the sky couldn't be measured against them");
            else no.add("no test frame came");
        }
        if (!Double.isNaN(s.sunAltDeg) && s.sunAltDeg > Tonight.DARK_DEG) {
            no.add(s.sunAltDeg > 0 ? "the Sun is up"
                    : String.format(Locale.US, "it isn't fully dark (the Sun is %.0f° below the horizon, not 18°)", -s.sunAltDeg));
        }
        if (!Double.isNaN(s.moonAltDeg) && s.moonAltDeg > 0 && !(s.moonIllum < 0.02)) {
            no.add(String.format(Locale.US, "the Moon is up (%d%% lit) and brightens the sky", Math.round(100 * s.moonIllum)));
        }
        if (cloud) no.add("there's cloud or haze");
        if (lens) no.add("the lens may be smudged or fogged");
        if (s.spread > MAX_SPREAD) no.add(String.format(Locale.US, "the frames disagree by %.2f (cloud passing?)", s.spread));
        s.valid = no.isEmpty();
        s.why = join(no);

        // words
        if (s.frames == 0) {
            s.headline = "No reading";
            s.lines.add("The sky couldn't be measured: " + s.why + ".");
            s.spoken = "No reading: " + s.why + ".";
            return s;
        }
        s.headline = String.format(Locale.US, "%.2f mag/arcsec²: %s", s.meterMag, skyClass(s.meterMag));
        s.lines.add(String.format(Locale.US, "Sky brightness %.2f magnitudes per square arcsecond over the central 20°, stars included, "
                + "as a handheld meter reads it; %.2f between the stars.", s.meterMag, s.skyMag));
        if (s.frames > 1) {
            s.lines.add(String.format(Locale.US, "The %d frames agree within %.2f.", s.frames, s.spread));
        }
        s.lines.add(String.format(Locale.US, "Faintest stars to the naked eye: about magnitude %.1f.", s.nelm));
        if (!Double.isNaN(s.faintestMag)) {
            s.lines.add(String.format(Locale.US, s.faintestMag >= 6.3
                    ? "The camera picked up catalogue stars down to magnitude %.1f, about as faint as its catalogue goes."
                    : "The faintest catalogue star the camera picked up: magnitude %.1f.", s.faintestMag));
        }
        if (s.expected >= 12) {
            s.lines.add(String.format(Locale.US, "%d of the %d catalogue stars that should show do.", s.seen, s.expected));
        }
        if (!Double.isNaN(s.hazeMag) && s.hazeMag >= 0.4) {
            s.lines.add(String.format(Locale.US, "The stars are %.1f magnitudes dimmer than on this camera's clearest night.", s.hazeMag));
        }
        s.lines.add(s.valid ? "The reading counts: dark, clear and no Moon."
                : "This reading doesn't count for the sky's darkness: " + s.why + ".");
        String how = String.format(Locale.US, "%s %s test frame%s at ISO %d, each measured against its own stars, with the sensor's "
                        + "black level instead of a dark frame.", s.frames == 1 ? "One" : words(s.frames),
                Double.isNaN(s.exposureS) ? "" : String.format(Locale.US, "%.0f-second", s.exposureS), s.frames == 1 ? "" : "s", s.iso);
        how += s.offset != 0 ? String.format(Locale.US, " Your meter offset (%+.2f) is included.", s.offset)
                : " The camera's green pixels aren't a meter's filter: expect it within about 0.3 of an SQM-L until it's matched to one.";
        s.lines.add(how.replace("  ", " "));
        s.spoken = String.format(Locale.US, "Sky reading: %.1f, %s. Naked-eye stars to about magnitude %.1f.%s", s.meterMag,
                skyClass(s.meterMag), s.nelm, s.valid ? "" : " It doesn't count: " + s.why + ".");
        return s;
    }

    private static String words(int n) {
        String[] w = {"No", "One", "Two", "Three", "Four", "Five"};
        return n >= 0 && n < w.length ? w[n] : Integer.toString(n);
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? " and " : ", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------------
    // The readings' log, as CSV (opens in Excel)

    public static String csvHeader() {
        return "time,latitude,longitude,sky_mag_arcsec2_with_stars,sky_mag_arcsec2_between_stars,naked_eye_limit,valid,why_not,"
                + "frames,spread,exposure_s,iso,offset,faintest_star_mag,stars_seen,stars_expected,sun_altitude_deg,moon_altitude_deg,"
                + "moon_lit_percent,haze_mag,site";
    }

    public String csvRow(TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(timeMs)) + "," + num(latDeg, 4) + "," + num(lonDeg, 4) + "," + num(meterMag, 2) + "," + num(skyMag, 2)
                + "," + num(nelm, 1) + "," + (valid ? "yes" : "no") + "," + quote(why) + "," + frames + "," + num(spread, 2) + ","
                + num(exposureS, 1) + "," + (iso > 0 ? Integer.toString(iso) : "") + "," + num(offset, 2) + "," + num(faintestMag, 1) + ","
                + seen + "," + expected + "," + num(sunAltDeg, 1) + "," + num(moonAltDeg, 1) + ","
                + (Double.isNaN(moonIllum) ? "" : Long.toString(Math.round(100 * moonIllum))) + "," + num(hazeMag, 2) + "," + quote(site);
    }

    private static String num(double v, int digits) {
        return Double.isNaN(v) ? "" : String.format(Locale.US, "%." + digits + "f", v);
    }

    private static String quote(String s) {
        if (s.isEmpty()) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
