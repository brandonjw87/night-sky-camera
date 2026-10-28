package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The year's meteor showers, from the International Meteor Organization's working list of visual meteor showers:
 * when each is active, where its radiant is, how fast its meteors are, and how many an observer under a perfect
 * sky would see at its peak (the zenithal hourly rate, ZHR). And which shower a meteor caught in a frame belongs
 * to: one whose trail, followed backwards, runs through an active shower's radiant.
 * <p>
 * Dates are solar longitudes (the Sun's ecliptic longitude, J2000, in degrees), which fall on the same dates every
 * year to within a few hours. A radiant drifts east as the Earth moves along its orbit; between the list's
 * positions it is taken to move evenly. Seen from the ground a radiant sits a little closer to the zenith than in
 * the list (the Earth's gravity bends the meteors' paths: most for slow ones low in the sky), which
 * {@link #apparentRadiant} allows for.
 */
public final class MeteorShowers {
    /** A shower of the list. */
    public static final class Shower {
        /** The IMO's three-letter code, the shower's name, and what one of its meteors is called. */
        public final String code, name, member;
        /** Solar longitudes (degrees): activity begins, peaks, ends. */
        public final double begin, peak, end;
        /** The radiant at the peak (degrees, J2000) and how far it moves per degree of solar longitude. */
        public final double ra, dec, dRa, dDec;
        /** Speed in the atmosphere (km/s), population index (how fast numbers grow towards fainter meteors). */
        public final double speed, r;
        /** Zenithal hourly rate at the peak in a typical year. */
        public final double zhr;
        /** How steeply activity rises to the peak and falls after it: ZHR = zhr * 10^(-b * degrees from the peak). */
        public final double bRise, bFall;
        /** How far its meteors' radiants spread around the listed one (degrees, one sigma). */
        public final double spread;
        /** Its rate changes a lot from year to year (outbursts some years, little in others); zhr is a quiet year's. */
        public final boolean variable;
        /** Known for slow, bright meteors and fireballs rather than numbers. */
        public final boolean fireballs;

        Shower(String code, String name, String member, double begin, double peak, double end, double ra, double dec,
               double dRa, double dDec, double speed, double r, double zhr, double bRise, double bFall, double spread,
               boolean variable, boolean fireballs) {
            this.code = code;
            this.name = name;
            this.member = member;
            this.begin = begin;
            this.peak = peak;
            this.end = end;
            this.ra = ra;
            this.dec = dec;
            this.dRa = dRa;
            this.dDec = dDec;
            this.speed = speed;
            this.r = r;
            this.zhr = zhr;
            this.bRise = bRise;
            this.bFall = bFall;
            this.spread = spread;
            this.variable = variable;
            this.fireballs = fireballs;
        }

        /** Degrees of solar longitude from the peak (negative before it), -180 to 180. */
        public double fromPeak(double lon) {
            return wrap(lon - peak);
        }

        /** Whether it is active at a solar longitude. */
        public boolean active(double lon) {
            double a = wrap(begin - peak), b = wrap(end - peak), d = fromPeak(lon);
            return d >= a && d <= b;
        }

        /** Its zenithal hourly rate at a solar longitude (0 outside its activity). */
        public double zhr(double lon) {
            if (!active(lon)) return 0;
            double d = fromPeak(lon);
            return zhr * Math.pow(10, -(d < 0 ? bRise : bFall) * Math.abs(d));
        }

        /** The radiant (J2000 unit vector, geocentric) at a solar longitude. */
        public double[] radiant(double lon) {
            double d = fromPeak(lon);
            return Astro.vec((ra + dRa * d) * Astro.DEG, (dec + dDec * d) * Astro.DEG);
        }

        /** Major: worth telling about on its own (a typical peak of ten an hour or more, or known for fireballs). */
        public boolean major() {
            return zhr >= 10 || fireballs;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * The working list (IMO Meteor Shower Calendar): activity and peak as solar longitudes, radiant at the peak
     * and its drift, V-infinity, r, ZHR. The activity slopes follow Jenniskens (1994) where he measured them,
     * else a gentle 0.2 a degree; the variable showers' rates are those of a quiet year.
     */
    public static final Shower[] ALL = {
            new Shower("QUA", "Quadrantids", "Quadrantid", 274.1, 283.15, 296.5, 230, 49, 0.6, -0.2, 41, 2.1, 80, 2.0, 2.0, 1.5, false, false),
            new Shower("GUM", "gamma-Ursae Minorids", "gamma-Ursae Minorid", 289.4, 298, 302.6, 228, 67, 0, 0, 31, 3.0, 3, 0.2, 0.2, 2, false, false),
            new Shower("ACE", "alpha-Centaurids", "alpha-Centaurid", 310.7, 319.2, 332.0, 210, -59, 1.25, -0.3, 58, 2.0, 6, 0.2, 0.2, 2, false, false),
            new Shower("GNO", "gamma-Normids", "gamma-Normid", 336.0, 354, 8.0, 239, -50, 1.0, 0.2, 56, 2.4, 6, 0.2, 0.2, 2, false, false),
            new Shower("LYR", "Lyrids", "Lyrid", 23.7, 32.32, 40.3, 271, 34, 1.1, 0, 49, 2.1, 18, 0.3, 0.3, 1.5, false, false),
            new Shower("PPU", "pi-Puppids", "pi-Puppid", 24.7, 33.5, 38.4, 110, -45, 0, 0, 18, 2.0, 2, 0.2, 0.2, 2, true, false),
            new Shower("ETA", "eta-Aquariids", "eta-Aquariid", 28.6, 45.5, 67.3, 338, -1, 0.85, 0.35, 66, 2.4, 50, 0.08, 0.08, 1.5, false, false),
            new Shower("ELY", "eta-Lyrids", "eta-Lyrid", 42.2, 48.0, 53.9, 287, 44, 1.0, 0, 43, 3.0, 3, 0.2, 0.2, 2, false, false),
            new Shower("JBO", "June Bootids", "June Bootid", 90.3, 95.7, 100.8, 224, 48, 0, 0, 18, 2.2, 2, 0.2, 0.2, 2, true, false),
            new Shower("CAP", "alpha-Capricornids", "alpha-Capricornid", 100.7, 127, 142.8, 307, -10, 0.9, 0.3, 23, 2.5, 5, 0.04, 0.04, 3, false, true),
            new Shower("SDA", "Southern delta-Aquariids", "Southern delta-Aquariid", 109.3, 128, 150.5, 340, -16, 0.8, 0.2, 41, 2.5, 25, 0.09, 0.09, 2, false, false),
            new Shower("PER", "Perseids", "Perseid", 114.1, 140.0, 151.5, 48, 58, 1.35, 0.12, 59, 2.2, 100, 0.15, 0.25, 1.5, false, false),
            new Shower("KCG", "kappa-Cygnids", "kappa-Cygnid", 130.3, 145, 152.5, 286, 59, 0.3, 0.5, 23, 3.0, 3, 0.1, 0.1, 3, false, false),
            new Shower("AUR", "Aurigids", "Aurigid", 154.4, 158.6, 163.1, 91, 39, 1.1, 0, 66, 2.5, 6, 0.4, 0.4, 1.5, false, false),
            new Shower("SPE", "September epsilon-Perseids", "September epsilon-Perseid", 162.1, 166.7, 178.7, 48, 40, 1.0, 0.2, 64, 3.0, 5, 0.2, 0.2, 1.5, false, false),
            new Shower("DRA", "Draconids", "Draconid", 192.4, 195.4, 197.3, 262, 54, 0, 0, 20, 2.6, 5, 1.0, 1.0, 1.5, true, false),
            new Shower("STA", "Southern Taurids", "Southern Taurid", 166.9, 197, 238.3, 32, 9, 0.8, 0.25, 27, 2.3, 5, 0.026, 0.026, 3, false, true),
            new Shower("DAU", "delta-Aurigids", "delta-Aurigid", 196.3, 198, 205.3, 84, 44, 1.0, -0.1, 64, 3.0, 2, 0.2, 0.2, 2, false, false),
            new Shower("EGE", "epsilon-Geminids", "epsilon-Geminid", 200.3, 205, 214.2, 102, 27, 0.7, 0, 70, 3.0, 3, 0.2, 0.2, 2, false, false),
            new Shower("ORI", "Orionids", "Orionid", 188.4, 208, 225.2, 95, 16, 0.7, 0.1, 66, 2.5, 20, 0.08, 0.08, 1.5, false, false),
            new Shower("LMI", "Leonis Minorids", "Leonis Minorid", 205.2, 211, 214.2, 162, 37, 0.7, -0.4, 62, 3.0, 2, 0.3, 0.3, 2, false, false),
            new Shower("NTA", "Northern Taurids", "Northern Taurid", 206.2, 230, 258.5, 58, 22, 0.85, 0.15, 29, 2.3, 5, 0.026, 0.026, 3, false, true),
            new Shower("LEO", "Leonids", "Leonid", 223.2, 235.27, 248.4, 152, 22, 0.6, -0.35, 71, 2.5, 15, 0.4, 0.4, 1.5, true, false),
            new Shower("AMO", "alpha-Monocerotids", "alpha-Monocerotid", 232.3, 239.32, 243.3, 117, 1, 0.8, 0, 65, 2.4, 2, 0.2, 0.2, 1.5, true, false),
            new Shower("NOO", "November Orionids", "November Orionid", 230.2, 246, 254.5, 91, 16, 0.7, 0, 44, 3.0, 3, 0.15, 0.15, 2, false, false),
            new Shower("PHO", "Phoenicids", "Phoenicid", 245.4, 250.0, 257.5, 18, -53, 0, 0, 18, 2.8, 2, 0.2, 0.2, 2, true, false),
            new Shower("PUP", "Puppid-Velids", "Puppid-Velid", 248.4, 255, 263.6, 123, -45, 0.6, 0, 40, 2.9, 10, 0.2, 0.2, 3, false, false),
            new Shower("MON", "Monocerotids", "Monocerotid", 252.5, 257, 268.7, 100, 8, 0.75, 0, 41, 3.0, 2, 0.3, 0.3, 2, false, false),
            new Shower("HYD", "sigma-Hydrids", "sigma-Hydrid", 250.4, 257, 268.7, 125, 2, 0.7, -0.2, 58, 3.0, 7, 0.2, 0.2, 2, false, false),
            new Shower("GEM", "Geminids", "Geminid", 251.5, 262.2, 268.7, 112, 33, 1.0, -0.1, 35, 2.6, 150, 0.39, 0.81, 1.0, false, false),
            new Shower("COM", "Comae Berenicids", "Comae Berenicid", 259.6, 264, 271.8, 175, 18, 0.8, -0.4, 65, 3.0, 3, 0.2, 0.2, 2, false, false),
            new Shower("DLM", "December Leonis Minorids", "December Leonis Minorid", 252.5, 268, 315.5, 161, 30, 0.8, -0.4, 64, 3.0, 5, 0.04, 0.04, 2, false, false),
            new Shower("URS", "Ursids", "Ursid", 264.7, 270.7, 274.8, 217, 76, 0, 0, 33, 3.0, 10, 0.6, 0.6, 1.5, false, false),
    };

    /** Sporadic meteors an hour under the same perfect sky a ZHR counts (the background every shower stands out from). */
    static final double SPORADIC_RATE = 10;
    /** Share of a shower's meteors whose trail runs within the tolerance of its radiant. */
    static final double MEMBER_SHARE = 0.9;
    /** Farthest from its radiant a member can be seen (degrees). */
    static final double MAX_DISTANCE = 100;
    /** The trail's direction: a line's end points are good to about this many pixels. */
    static final double END_PX = 2.0;
    /** Escape velocity at a meteor's height (km/s). */
    static final double V_ESCAPE = 11.1;

    private MeteorShowers() {}

    /** The Sun's ecliptic longitude (degrees, J2000) at a UTC Julian date. */
    public static double solarLongitude(double jdUtc) {
        double jdTt = Astro.jdTt(jdUtc);
        double[] s = Astro.sunOfDate(jdTt);
        double[] v = new double[3];
        Rot.apply(Rot.transpose(Astro.j2000ToDate(jdTt)), s[0], s[1], s[2], v);
        double eps = 23.4392911 * Astro.DEG;
        double l = Math.atan2(v[1] * Math.cos(eps) + v[2] * Math.sin(eps), v[0]) / Astro.DEG;
        return l < 0 ? l + 360 : l;
    }

    /** The showers active at a time, the most active first. */
    public static List<Shower> active(double jdUtc) {
        final double lon = solarLongitude(jdUtc);
        List<Shower> out = new ArrayList<Shower>();
        for (Shower s : ALL) if (s.active(lon)) out.add(s);
        Collections.sort(out, new Comparator<Shower>() {
            @Override
            public int compare(Shower a, Shower b) {
                return Double.compare(b.zhr(lon), a.zhr(lon));
            }
        });
        return out;
    }

    /** The shower with this code, or null. */
    public static Shower byCode(String code) {
        for (Shower s : ALL) if (s.code.equals(code)) return s;
        return null;
    }

    /**
     * Where a radiant appears from the ground (J2000 unit vector): moved towards the zenith by zenith attraction,
     * the Earth's gravity curving the meteors' paths (Schiaparelli: tan(dZ/2) = (v - vg)/(v + vg) tan(Z/2)).
     * {@code zenith} is the J2000 direction straight up at the time.
     */
    public static double[] apparentRadiant(double[] radiant, double[] zenith, double speed) {
        double z = Astro.sep(radiant, zenith);
        if (z < 1e-6) return radiant.clone();
        double vg = Math.sqrt(Math.max(1, speed * speed - V_ESCAPE * V_ESCAPE));
        double dz = 2 * Math.atan((speed - vg) / (speed + vg) * Math.tan(z / 2));
        // rotate the radiant towards the zenith by dz, in the plane they share
        double[] n = cross(radiant, zenith);
        normalize(n);
        double[] t = cross(n, radiant); // in the plane, at right angles to the radiant, towards the zenith
        double c = Math.cos(dz), s = Math.sin(dz);
        double[] out = {radiant[0] * c + t[0] * s, radiant[1] * c + t[1] * s, radiant[2] * c + t[2] * s};
        normalize(out);
        return out;
    }

    /** Which shower a meteor belongs to (or none: sporadic), and which way it flew. */
    public static final class Match {
        /** The shower, or null for a sporadic meteor. */
        public Shower shower;
        /** How far the radiant is from the trail's line (degrees), and from where the meteor began. */
        public double offset = Double.NaN, distance = Double.NaN;
        /** The trail's second end is where it began (it flew towards the first). */
        public boolean reversed;
        /** The radiant as seen (J2000), NaN-free only with a shower. */
        public double[] radiant;
        /** How strongly the shower's rate outweighs sporadic meteors that would line up by chance (above 1: a member). */
        public double odds;
    }

    /**
     * The shower a meteor's trail points back to: among the showers active at {@code jdUtc} whose radiant (as seen
     * from the place, {@link #apparentRadiant}) lies behind one end of the trail, within its spread and how well
     * the trail's direction is known, the one whose meteors most outnumber sporadic ones lining up with it by chance.
     *
     * @param a        one end of the trail (J2000 unit vector)
     * @param b        its other end
     * @param lengthPx its length in pixels (a short trail's direction is less certain)
     * @param zenith   straight up at the time (J2000 unit vector), or null to take the listed radiants as they are
     */
    public static Match attribute(double[] a, double[] b, double lengthPx, double jdUtc, double[] zenith) {
        Match best = new Match();
        double lon = solarLongitude(jdUtc);
        double len = Astro.sep(a, b);
        double[] n = cross(a, b);
        if (len < 1e-6 || !normalize(n)) return best;
        double sigDir = END_PX / Math.max(1, lengthPx); // radians of direction
        double bestOdds = 0;
        for (Shower s : ALL) {
            if (!s.active(lon)) continue;
            double[] rad = s.radiant(lon);
            double h = 0;
            if (zenith != null) {
                rad = apparentRadiant(rad, zenith, s.speed);
                h = Math.PI / 2 - Astro.sep(rad, zenith);
            }
            // the rate an observer would see: lower radiants send fewer meteors (none from below the horizon)
            double rate = s.zhr(lon) * (zenith != null ? Math.sin(Math.max(0, h + 2 * Astro.DEG)) : 1);
            if (rate <= 0) continue;
            double off = Math.asin(Math.min(1, Math.abs(dot(rad, n))));
            // where the radiant falls along the trail's great circle: behind its first end (< 0) or beyond its second
            double[] p = {rad[0] - dot(rad, n) * n[0], rad[1] - dot(rad, n) * n[1], rad[2] - dot(rad, n) * n[2]};
            if (!normalize(p)) continue;
            double along = Math.atan2(dot(cross(a, p), n), dot(a, p));
            if (along >= 0 && along <= len) continue; // the radiant lies on the trail itself
            boolean reversed = along > len;
            double dist = Astro.sep(rad, reversed ? b : a);
            if (dist > MAX_DISTANCE * Astro.DEG) continue;
            double spread = s.spread * Astro.DEG;
            double tol = Math.min(10 * Astro.DEG, 2 * Math.sqrt(spread * spread + 1 * Astro.DEG * Astro.DEG
                    + (dist * sigDir) * (dist * sigDir)));
            if (off > tol) continue;
            // chance that a sporadic meteor here lines up as well: its line passes within tol of the radiant
            double chance = 2 * Math.asin(Math.min(1, Math.sin(tol) / Math.max(1e-9, Math.sin(dist)))) / Math.PI;
            double odds = rate * MEMBER_SHARE / (SPORADIC_RATE * Math.max(1e-3, chance));
            if (odds > bestOdds) {
                bestOdds = odds;
                best.odds = odds;
                best.offset = off / Astro.DEG;
                best.distance = dist / Astro.DEG;
                best.reversed = reversed;
                best.radiant = rad;
                best.shower = odds > 1 ? s : null;
            }
        }
        if (best.shower == null) best.radiant = null;
        return best;
    }

    /**
     * How fast a meteor of a shower crosses the sky (degrees a second) where it is seen: its speed across the line of
     * sight over its distance, at the height meteors of that speed burn (higher for faster ones).
     *
     * @param distance degrees from the radiant
     * @param altitude the meteor's altitude above the horizon (degrees)
     */
    public static double angularSpeed(double speed, double distance, double altitude) {
        double hKm = 85 + 0.3 * speed;
        double R = Astro.EARTH_RADIUS_KM, h = Math.max(1, altitude) * Astro.DEG;
        double range = Math.sqrt((R + hKm) * (R + hKm) - R * R * Math.cos(h) * Math.cos(h)) - R * Math.sin(h);
        return speed * Math.sin(distance * Astro.DEG) / range / Astro.DEG;
    }

    static double wrap(double d) {
        double t = (d + 180) % 360;
        if (t < 0) t += 360;
        return t - 180;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static boolean normalize(double[] v) {
        double l = Math.sqrt(dot(v, v));
        if (!(l > 1e-12)) return false;
        v[0] /= l;
        v[1] /= l;
        v[2] /= l;
        return true;
    }
}
