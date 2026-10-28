package com.brandon.nightsky.processing;

/**
 * Where an Earth satellite appears to an observer: from its SGP4 position (TEME, km) to a topocentric J2000
 * direction, which the plate solution turns into a pixel among the stars; whether the Sun lights it; and
 * roughly how bright it is.
 * <p>
 * TEME to Earth-fixed is a rotation by Greenwich mean sidereal time (IAU 1982, as SGP4 defines it); Earth-fixed
 * to J2000 is the transpose of {@link Astro#j2000ToEcef}, which applies the apparent sidereal time, nutation and
 * precession (leaving out precession alone would put a satellite 0.37 degrees off in 2026). The satellite's
 * position is taken where it was when the light left it (a few milliseconds earlier: up to 20 arcseconds for
 * the ISS). Annual aberration, which moves the stars by up to 20 arcseconds, doesn't apply to a satellite that
 * travels with the Earth; it is under a third of a pixel either way.
 * <p>
 * The Earth's shadow is a cylinder of the Earth's equatorial radius along the Sun's direction. The brightness
 * scales a standard magnitude (at 1000 km, half lit) by distance and by the phase of a diffusely reflecting
 * sphere, F(phase) = ((pi - phase) cos phase + sin phase) / pi.
 */
public final class SatSky {
    private SatSky() {}

    /** Speed of light (km/s). */
    static final double C_KM_S = 299792.458;

    /**
     * An observer's place, and what changes slowly enough to share between nearby moments: precession and
     * nutation, the equation of the equinoxes and the Sun's direction, recomputed every quarter of an hour (in
     * which they move by under 0.1 arcsecond, and the Sun by 0.25 degrees).
     */
    public static final class Site {
        public final double latRad, lonRad, hKm;
        /** How far off the place may be (km): widens the across-track search when naming streaks. */
        public double errKm;
        final double[] ecefToEnu, obs;
        private double slowJd = Double.NaN, eqeq;
        private double[] np, sunOfDate;

        public Site(double latRad, double lonRad, double hKm) {
            this.latRad = latRad;
            this.lonRad = lonRad;
            this.hKm = hKm;
            ecefToEnu = Astro.ecefToEnu(latRad, lonRad);
            obs = Astro.observerEcef(latRad, lonRad, hKm);
        }

        /** The moment {@code jdUtc} here. Not for use from several threads at once. */
        public Moment moment(double jdUtc) {
            if (!(Math.abs(jdUtc - slowJd) < SLOW_DAYS)) {
                slowJd = jdUtc;
                double jdTt = Astro.jdTt(jdUtc);
                np = Astro.j2000ToDate(jdTt);
                double[] n = Astro.nutation(jdTt);
                eqeq = n[0] * Math.cos(n[2]);
                sunOfDate = Astro.sunOfDate(jdTt);
            }
            return new Moment(this, jdUtc);
        }
    }

    /** How long a site keeps its precession, nutation and Sun (days). */
    static final double SLOW_DAYS = 0.25 / 24;

    /** One instant at one place: everything that doesn't depend on the satellite. */
    public static final class Moment {
        public final double jdUtc;
        final Site site;
        /** Earth-fixed to J2000 (row-major 3x3). */
        final double[] ecefToJ2000;
        /** SGP4's sidereal angle (radians: TEME to Earth-fixed) and the Sun's Earth-fixed unit vector. */
        final double gmst;
        final double[] sun = new double[3];
        /** The Sun's altitude at the observer (degrees). */
        public final double sunAltDeg;

        Moment(Site site, double jdUtc) {
            this.site = site;
            this.jdUtc = jdUtc;
            // SGP4's GMST is the IAU 1982 one, as Astro's; apparent sidereal time adds the equation of the equinoxes
            gmst = Sgp4.gstime(jdUtc);
            double[] dateToEcef = Astro.r3(gmst + site.eqeq);
            ecefToJ2000 = Rot.transpose(Rot.mul(dateToEcef, site.np));
            double[] so = site.sunOfDate;
            Rot.apply(dateToEcef, so[0], so[1], so[2], sun);
            double[] e = new double[3];
            Rot.apply(site.ecefToEnu, sun[0], sun[1], sun[2], e);
            sunAltDeg = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, e[2]))));
        }

        /** A J2000 direction as east, north, up here. */
        public void toEnu(double[] j2000, double[] enu) {
            double x = ecefToJ2000[0] * j2000[0] + ecefToJ2000[3] * j2000[1] + ecefToJ2000[6] * j2000[2];
            double y = ecefToJ2000[1] * j2000[0] + ecefToJ2000[4] * j2000[1] + ecefToJ2000[7] * j2000[2];
            double z = ecefToJ2000[2] * j2000[0] + ecefToJ2000[5] * j2000[1] + ecefToJ2000[8] * j2000[2];
            Rot.apply(site.ecefToEnu, x, y, z, enu);
        }

        /** An east-north-up direction here as J2000. */
        public void toJ2000(double[] enu, double[] j2000) {
            double[] m = site.ecefToEnu;
            double x = m[0] * enu[0] + m[3] * enu[1] + m[6] * enu[2];
            double y = m[1] * enu[0] + m[4] * enu[1] + m[7] * enu[2];
            double z = m[2] * enu[0] + m[5] * enu[1] + m[8] * enu[2];
            Rot.apply(ecefToJ2000, x, y, z, j2000);
        }
    }

    /** What the observer sees of one satellite at one moment. */
    public static final class Look {
        /** Topocentric unit vector, J2000 and east-north-up. */
        public final double[] j2000 = new double[3], enu = new double[3];
        public double rangeKm, altDeg, azDeg;
        public boolean sunlit;
        /** Sun-satellite-observer angle (degrees) and the estimated magnitude (99 in shadow). */
        public double phaseDeg, mag = 99;
    }

    /** The moment {@code jdUtc} at geodetic latitude and east longitude (radians) and height (km). */
    public static Moment moment(double jdUtc, double latRad, double lonRad, double hKm) {
        return new Site(latRad, lonRad, hKm).moment(jdUtc);
    }

    /** A geocentric TEME position (km) as J2000 at {@code jdUtc} (for tests: no observer, no light time). */
    public static double[] temeToJ2000(double jdUtc, double[] r) {
        Moment m = moment(jdUtc, 0, 0, 0);
        double cg = Math.cos(m.gmst), sg = Math.sin(m.gmst);
        double[] o = new double[3];
        Rot.apply(m.ecefToJ2000, cg * r[0] + sg * r[1], -sg * r[0] + cg * r[1], r[2], o);
        return o;
    }

    /**
     * The satellite at the moment, seen from its place. False (and {@code out} unchanged) if SGP4 fails (decayed,
     * or elements far from their epoch).
     *
     * @param stdMag magnitude at 1000 km, half lit
     */
    public static boolean look(Sgp4 s, double stdMag, Moment m, Look out) {
        double[] r = new double[3], v = new double[3];
        if (!s.at(m.jdUtc, r, v)) return false;
        double cg = Math.cos(m.gmst), sg = Math.sin(m.gmst);
        // TEME -> Earth-fixed: the frame turned by GMST; the velocity only for the light time, so no Earth-rotation term
        double x = cg * r[0] + sg * r[1], y = -sg * r[0] + cg * r[1], z = r[2];
        double vx = cg * v[0] + sg * v[1], vy = -sg * v[0] + cg * v[1], vz = v[2];
        double[] obs = m.site.obs;
        double dx = x - obs[0], dy = y - obs[1], dz = z - obs[2];
        double range = Math.sqrt(dx * dx + dy * dy + dz * dz);
        // where it was when the light left it
        double tau = range / C_KM_S;
        x -= vx * tau;
        y -= vy * tau;
        z -= vz * tau;
        dx = x - obs[0];
        dy = y - obs[1];
        dz = z - obs[2];
        range = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double ux = dx / range, uy = dy / range, uz = dz / range;
        Rot.apply(m.ecefToJ2000, ux, uy, uz, out.j2000);
        Rot.apply(m.site.ecefToEnu, ux, uy, uz, out.enu);
        out.rangeKm = range;
        out.altDeg = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, out.enu[2]))));
        out.azDeg = Math.toDegrees(Astro.azimuth(out.enu));
        // the Earth's shadow: behind the Earth (away from the Sun) and within its radius of the Sun-Earth line
        double along = x * m.sun[0] + y * m.sun[1] + z * m.sun[2];
        double px = x - along * m.sun[0], py = y - along * m.sun[1], pz = z - along * m.sun[2];
        out.sunlit = along > 0 || px * px + py * py + pz * pz > Astro.EARTH_RADIUS_KM * Astro.EARTH_RADIUS_KM;
        // phase: angle at the satellite between the Sun and the observer
        double cosPh = -(ux * m.sun[0] + uy * m.sun[1] + uz * m.sun[2]);
        double ph = Math.acos(Math.max(-1, Math.min(1, cosPh)));
        out.phaseDeg = Math.toDegrees(ph);
        out.mag = out.sunlit ? magnitude(stdMag, range, ph) : 99;
        return true;
    }

    /**
     * Where the line of sight in J2000 direction {@code j2000} leaves the Earth's shadow: {distance along it (km),
     * height there (km above the Earth's equatorial radius)}. A satellite seen along it below that height is in the
     * shadow, above it in sunlight, and one at it passes into or out of the shadow. {0, 0} if the observer is in
     * sunlight; infinite if the line never leaves.
     */
    public static double[] shadowExit(Moment m, double[] j2000) {
        double[] e = m.ecefToJ2000, o = m.site.obs, s = m.sun;
        // J2000 to Earth-fixed: the transpose
        double dx = e[0] * j2000[0] + e[3] * j2000[1] + e[6] * j2000[2];
        double dy = e[1] * j2000[0] + e[4] * j2000[1] + e[7] * j2000[2];
        double dz = e[2] * j2000[0] + e[5] * j2000[1] + e[8] * j2000[2];
        double os = o[0] * s[0] + o[1] * s[1] + o[2] * s[2], ds = dx * s[0] + dy * s[1] + dz * s[2];
        // the parts across the Sun's direction, where the cylinder's radius applies
        double ox = o[0] - os * s[0], oy = o[1] - os * s[1], oz = o[2] - os * s[2];
        double px = dx - ds * s[0], py = dy - ds * s[1], pz = dz - ds * s[2];
        double rr = Astro.EARTH_RADIUS_KM * Astro.EARTH_RADIUS_KM, oo = ox * ox + oy * oy + oz * oz;
        if (os > 0 || oo > rr) return new double[]{0, 0};
        double pp = px * px + py * py + pz * pz, op = ox * px + oy * py + oz * pz;
        double r = Double.POSITIVE_INFINITY;
        // out through the cylinder's side (the observer is inside it, so one root is ahead), or its sunward end
        if (pp > 1e-12) r = (-op + Math.sqrt(op * op - pp * (oo - rr))) / pp;
        if (ds > 0) r = Math.min(r, -os / ds);
        if (Double.isInfinite(r)) return new double[]{r, r};
        double x = o[0] + r * dx, y = o[1] + r * dy, z = o[2] + r * dz;
        return new double[]{r, Math.sqrt(x * x + y * y + z * z) - Astro.EARTH_RADIUS_KM};
    }

    /** Gravitational parameter of the Earth (km^3/s^2). */
    static final double MU_KM3_S2 = 398600.4418;

    /** The fastest anything in orbit goes at height {@code hKm} (km/s): the escape speed there. */
    public static double fastest(double hKm) {
        return Math.sqrt(2 * MU_KM3_S2 / (Astro.EARTH_RADIUS_KM + hKm));
    }

    /** Magnitude at {@code rangeKm} and phase angle {@code ph} (radians) of a satellite of standard magnitude {@code stdMag}. */
    public static double magnitude(double stdMag, double rangeKm, double ph) {
        double f = ((Math.PI - ph) * Math.cos(ph) + Math.sin(ph)) / Math.PI;
        // the standard magnitude is at phase 90 degrees, F = 1/pi
        return stdMag + 5 * Math.log10(rangeKm / 1000.0) - 2.5 * Math.log10(Math.max(1e-4, f * Math.PI));
    }
}
