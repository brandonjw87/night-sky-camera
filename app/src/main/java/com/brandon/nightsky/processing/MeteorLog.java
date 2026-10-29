package com.brandon.nightsky.processing;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The meteors a session caught (the streaks {@link SatMatch} found no satellite could have left): when (its frame's
 * exposure), where on the sky (each end's RA and Dec, altitude and azimuth, constellation), which shower it came
 * from ({@link MeteorShowers#attribute}) and so which way it flew, and roughly how bright it was at its brightest.
 * <p>
 * The brightness: a meteor of magnitude m crossing the sky at w pixels a second leaves 10^(0.4 (zp - m)) / (w T) of
 * a frame's light per pixel of its trail, where zp is the zero point of the reference frame's catalogue stars (the
 * light of a magnitude-0 star in one frame of T seconds). The trail's light is what its frame's green samples were
 * rejected for above the stack, summed across it: four times the star detector's units (the stack has a green value
 * at every pixel, as bright as a green photosite's; the detector's pixels are 2 x 2 photosites, the mean of their
 * two greens). Its speed comes from its shower's speed and how far from the radiant it was, or a typical sporadic
 * meteor's (40 km/s, 45 degrees from its radiant), at the height meteors burn. Good to about a magnitude: the
 * trail's faint edges weren't rejected, and the speed is taken, not measured. A faint one's light (seen only in the
 * deep map) is mostly noise cell by cell, so its brightest part is taken over a quarter of its length, not over
 * three cells' (the brightest three of a noisy trail are brighter than the trail was).
 */
public final class MeteorLog {
    private MeteorLog() {}

    /** A typical sporadic meteor: speed (km/s) and distance from its radiant (degrees). */
    static final double SPORADIC_SPEED = 40, SPORADIC_DISTANCE = 45;
    /** Altitude (degrees) taken for a meteor's speed without the place: the camera looks up. */
    static final double DEFAULT_ALTITUDE = 70;

    /** One meteor. */
    public static final class Meteor {
        public int unit;
        /** Its frame's exposure (UTC, Unix ms; NaN without the clock). */
        public double fromMs = Double.NaN, toMs = Double.NaN;
        /** Its ends in the stack's pixels: where it began first, when that's known ({@link #flew}). */
        public float x0, y0, x1, y1;
        /** The ends on the sky: J2000 right ascension and declination, altitude and azimuth (degrees; NaN where unknown). */
        public double ra0 = Double.NaN, dec0 = Double.NaN, ra1 = Double.NaN, dec1 = Double.NaN;
        public double alt0 = Double.NaN, az0 = Double.NaN, alt1 = Double.NaN, az1 = Double.NaN;
        /** Altitude and azimuth of its middle (degrees). */
        public double altMid = Double.NaN, azMid = Double.NaN;
        /** IAU abbreviation of each end's constellation ("" without the sky). */
        public String con0 = "", con1 = "";
        public double lengthDeg = Double.NaN;
        /** Whether its shower was looked for (the sky and the time were known), and the one it came from (null: sporadic). */
        public boolean attributed;
        public MeteorShowers.Shower shower;
        /** How far from its radiant it began (degrees), and whether it's known which end it began at. */
        public double fromRadiant = Double.NaN;
        public boolean flew;
        /** Its estimated magnitude at its brightest (NaN without a zero point), and the speed across the sky taken for it (degrees a second). */
        public double mag = Double.NaN, speedDegS = Double.NaN;
        public boolean kept;
        /**
         * Seen only by the deep search ({@link SatMatch.Streak#deep}): too faint for any of its samples to stand out
         * alone, so never kept in the picture (its frame there is mostly noise).
         */
        public boolean faint;

        /** "a Geminid", "a sporadic meteor", "a meteor" (without the time or the sky). */
        public String what() {
            if (shower != null) return article(shower.member) + " " + shower.member;
            return attributed ? "a sporadic meteor" : "a meteor";
        }

        /**
         * Where it was: "from high in the northeast to the east" when it's known which way it flew, else where its
         * middle was ("high in the northeast"); "" without the place.
         */
        public String where() {
            if (Double.isNaN(alt0) || Double.isNaN(alt1)) return "";
            if (!flew) {
                String p = place(altMid, azMid);
                return p.startsWith("the ") ? "in " + p : p;
            }
            String from = place(alt0, az0), to = place(alt1, az1);
            return from.equals(to) ? (from.startsWith("the ") ? "in " + from : from) : "from " + from + " to " + to;
        }

        /** "overhead", "high in the east", "the east", "low in the east". */
        static String place(double alt, double az) {
            String c = Preflight.compass(az);
            return alt >= 75 ? "overhead" : alt >= 50 ? "high in the " + c : alt >= 30 ? "the " + c : "low in the " + c;
        }
    }

    /**
     * One meteor's record from its streak (the kept flag and the frame's times are the caller's).
     *
     * @param sol         the plate solution (null: no sky positions, shower or brightness)
     * @param site        the place (null: no altitudes, and the showers' radiants taken as listed)
     * @param jdUtc       the middle of its frame's exposure (NaN without the clock: no shower)
     * @param degPerPx    the picture's scale at its centre (its length without a solution)
     * @param refExposure the reference frame's exposure (s), the zero point's
     */
    public static Meteor measure(SatMatch.Streak s, SkySolver.Solution sol, SatSky.Site site, double jdUtc, double degPerPx,
                                 double refExposure) {
        Meteor m = new Meteor();
        m.unit = s.unit;
        boolean ev = !Float.isNaN(s.sx0);
        float ax = ev ? s.sx0 : s.x0, ay = ev ? s.sy0 : s.y0, bx = ev ? s.sx1 : s.x1, by = ev ? s.sy1 : s.y1;
        m.x0 = ax;
        m.y0 = ay;
        m.x1 = bx;
        m.y1 = by;
        double lenPx = Math.hypot(bx - ax, by - ay);
        m.lengthDeg = lenPx * degPerPx;
        if (sol == null || lenPx < 1) return m;
        double[] a = sol.direction(ax, ay), b = sol.direction(bx, by);
        m.lengthDeg = Astro.sep(a, b) / Astro.DEG;
        SatSky.Moment mo = site != null && !Double.isNaN(jdUtc) ? site.moment(jdUtc) : null;
        double[] zenith = null;
        if (mo != null) {
            zenith = new double[3];
            mo.toJ2000(new double[]{0, 0, 1}, zenith);
        }
        MeteorShowers.Match match = null;
        if (!Double.isNaN(jdUtc)) {
            m.attributed = true;
            match = MeteorShowers.attribute(a, b, lenPx, jdUtc, zenith);
            if (match.shower != null) {
                m.shower = match.shower;
                m.fromRadiant = match.distance;
                m.flew = true;
                if (match.reversed) {
                    m.x0 = bx;
                    m.y0 = by;
                    m.x1 = ax;
                    m.y1 = ay;
                    double[] t = a;
                    a = b;
                    b = t;
                }
            }
        }
        double[] rd = Astro.raDec(a);
        m.ra0 = rd[0] / Astro.DEG;
        m.dec0 = rd[1] / Astro.DEG;
        rd = Astro.raDec(b);
        m.ra1 = rd[0] / Astro.DEG;
        m.dec1 = rd[1] / Astro.DEG;
        m.con0 = Constellations.at(a);
        m.con1 = Constellations.at(b);
        double[] e = new double[3];
        if (mo != null) {
            mo.toEnu(a, e);
            m.alt0 = altitude(e);
            m.az0 = Astro.azimuth(e) / Astro.DEG;
            mo.toEnu(b, e);
            m.alt1 = altitude(e);
            m.az1 = Astro.azimuth(e) / Astro.DEG;
            mo.toEnu(sol.direction((ax + bx) / 2.0, (ay + by) / 2.0), e);
            m.altMid = altitude(e);
            m.azMid = Astro.azimuth(e) / Astro.DEG;
        }
        // its brightest part (along the streak as found, from (ax, ay)), and its speed there
        int peak = -1;
        double best = 0;
        float[] l = s.light;
        if (l != null) {
            int half = s.deep ? Math.max(1, l.length / 8) : 1;
            for (int i = 0; i < l.length; i++) {
                // a faint one's whole window, or none (fewer of its noisy cells would stand out by chance)
                if (s.deep && l.length > 2 * half + 1 && (i < half || i >= l.length - half)) continue;
                double sum = 0;
                int n = 0;
                for (int k = Math.max(0, i - half); k <= Math.min(l.length - 1, i + half); k++) {
                    sum += l[k];
                    n++;
                }
                if (sum / n > best) {
                    best = sum / n;
                    peak = i;
                }
            }
        }
        double u = peak >= 0 && l.length > 1 ? peak / (double) (l.length - 1) : 0.5;
        double px = ax + u * (bx - ax), py = ay + u * (by - ay);
        double[] p = sol.direction(px, py);
        double alt = DEFAULT_ALTITUDE;
        if (mo != null) {
            mo.toEnu(p, e);
            alt = altitude(e);
        }
        m.speedDegS = m.shower != null ? MeteorShowers.angularSpeed(m.shower.speed, Astro.sep(match.radiant, p) / Astro.DEG, alt)
                : MeteorShowers.angularSpeed(SPORADIC_SPEED, SPORADIC_DISTANCE, alt);
        if (best > 0 && !Double.isNaN(sol.zeroPoint) && refExposure > 0) {
            // detector units per pixel of trail, and the scale along it there
            double perPx = best / StreakEvidence.CELL / 4;
            double ux = (bx - ax) / lenPx, uy = (by - ay) / lenPx;
            double degPx = Astro.sep(p, sol.direction(px + 10 * ux, py + 10 * uy)) / Astro.DEG / 10;
            double w = m.speedDegS / Math.max(1e-6, degPx);
            m.mag = sol.zeroPoint - 2.5 * Math.log10(perPx * w * refExposure);
        }
        return m;
    }

    private static double altitude(double[] enu) {
        return Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, enu[2]))));
    }

    static String article(String word) {
        return word.isEmpty() || "AEIOUaeiou".indexOf(word.charAt(0)) < 0 ? "a" : "an";
    }

    /** One line for the session's log. */
    public static String describe(Meteor m) {
        StringBuilder sb = new StringBuilder("meteor: unit ").append(m.unit);
        if (!Double.isNaN(m.fromMs)) sb.append(", ").append(utc(m.fromMs, "HH:mm:ss")).append('-').append(utc(m.toMs, "HH:mm:ss")).append(" UTC");
        sb.append(", ").append(m.what());
        if (m.shower != null) sb.append(String.format(Locale.US, " (began %.0f deg from its radiant)", m.fromRadiant));
        sb.append(String.format(Locale.US, ", %.1f deg", m.lengthDeg));
        if (!Double.isNaN(m.ra0)) {
            sb.append(String.format(Locale.US, " %s RA %.2f Dec %+.2f (%s%s) %s RA %.2f Dec %+.2f (%s%s)", m.flew ? "from" : "between",
                    m.ra0, m.dec0, m.con0, Double.isNaN(m.alt0) ? "" : String.format(Locale.US, ", alt %.0f az %.0f", m.alt0, m.az0),
                    m.flew ? "to" : "and", m.ra1, m.dec1, m.con1,
                    Double.isNaN(m.alt1) ? "" : String.format(Locale.US, ", alt %.0f az %.0f", m.alt1, m.az1)));
        }
        if (!Double.isNaN(m.mag)) sb.append(String.format(Locale.US, ", about mag %.1f (taking %.0f deg/s)", m.mag, m.speedDegS));
        if (m.faint) sb.append(", faint");
        sb.append(m.kept ? ", kept" : ", masked");
        return sb.toString();
    }

    static final String HEADER = "unit,utc_start,utc_end,shower,direction_known,from_ra,from_dec,to_ra,to_dec,from_alt,from_az,"
            + "to_alt,to_az,length_deg,from_radiant_deg,mag,speed_deg_s,kept,x0,y0,x1,y1,faint";

    /** The meteors as CSV (a header line, one line each; ends are given where each began first when known). */
    public static String csv(List<Meteor> list) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Meteor m : list) {
            sb.append(m.unit).append(',').append(Double.isNaN(m.fromMs) ? "" : utc(m.fromMs, "yyyy-MM-dd'T'HH:mm:ss.S'Z'")).append(',')
                    .append(Double.isNaN(m.toMs) ? "" : utc(m.toMs, "yyyy-MM-dd'T'HH:mm:ss.S'Z'")).append(',')
                    .append(m.shower != null ? m.shower.code : m.attributed ? "SPO" : "").append(',').append(m.flew ? 1 : 0)
                    .append(num(m.ra0, 3)).append(num(m.dec0, 3)).append(num(m.ra1, 3)).append(num(m.dec1, 3))
                    .append(num(m.alt0, 1)).append(num(m.az0, 1)).append(num(m.alt1, 1)).append(num(m.az1, 1))
                    .append(num(m.lengthDeg, 2)).append(num(m.fromRadiant, 1)).append(num(m.mag, 1)).append(num(m.speedDegS, 1))
                    .append(',').append(m.kept ? 1 : 0)
                    .append(num(m.x0, 1)).append(num(m.y0, 1)).append(num(m.x1, 1)).append(num(m.y1, 1))
                    .append(',').append(m.faint ? 1 : 0).append('\n');
        }
        return sb.toString();
    }

    private static String num(double v, int digits) {
        return Double.isNaN(v) ? "," : String.format(Locale.US, ",%." + digits + "f", v);
    }

    private static String utc(double ms, String pattern) {
        SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date((long) Math.floor(ms)));
    }
}
