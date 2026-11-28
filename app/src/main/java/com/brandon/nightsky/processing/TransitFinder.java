package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The Space Station crossing the Moon (3.0, the Transit Hunter): when, and where on the ground it is seen. A crossing
 * is seen only from a narrow strip a few kilometres wide, so it is worked out the way an eclipse is: the line from
 * the Moon's centre through the station, carried on to the ground, marks the one place where the station is in front
 * of the Moon's middle at that moment; as the station flies, that point draws the strip's centre line. The nearest
 * point of the centre line to here is the crossing's place and time.
 * <p>
 * Inside the strip the station's silhouette crosses the Moon's disk. From the station the Moon is a cone of the Moon's
 * angular radius, which meets the ground in an ellipse round the centre-line point, stretched along the Moon's
 * azimuth by 1 / sin(the Moon's height); the strip's half-width is that ellipse's reach across the centre line.
 * <p>
 * The station's position is SGP4's (TEME, turned to the Earth by Greenwich mean sidereal time, as {@link SatSky});
 * the Moon's is Meeus's (true equator and equinox of date, turned by the apparent sidereal time), carried between
 * knots ten minutes apart (the knots' straight lines are 0.1 km off its curve, a tenth of an arcsecond from here).
 * The ground is the WGS84 ellipsoid.
 */
public final class TransitFinder {
    private TransitFinder() {}

    /** The stations' catalogue numbers: the ISS (Zarya), and Tiangong (Tianhe). */
    public static final int ISS = 25544, TIANGONG = 48274;
    /** The ISS across its truss (m). */
    public static final double ISS_SPAN_M = 109;
    /** The Moon at least this high where the crossing is seen (degrees): lower, the air smears the station. */
    public static final double MIN_ALT = 8;
    /** Not within this of the Sun (degrees): a telephoto doesn't point near the Sun. */
    public static final double MIN_ELONGATION = 25;
    /** Knots for the Moon's place (ms). */
    static final long KNOT_MS = 600_000L;
    /** The Earth's turn (rad/s). */
    static final double OMEGA = 7.2921150e-5;

    /** One crossing. */
    public static final class Transit {
        public int norad;
        public String name = "";
        /** Mid-crossing where the centre line passes nearest here (Unix ms). */
        public long timeMs;
        /** Seen from here: the moment the station is nearest the Moon's middle (Unix ms). */
        public long hereMs;
        /** The centre line's nearest point (degrees), how far it is from here (km) and which way (degrees from north). */
        public double lat, lon, distKm, bearingDeg;
        /** Half the strip's width (km), and the way the strip runs (degrees from north). */
        public double halfWidthKm, pathDeg;
        /** The station's distance from the centre-line point (km). */
        public double rangeKm;
        /** How long it takes to cross the Moon's middle on the centre line, and here (s; 0 if it misses here). */
        public double durationS, hereS;
        /** The Moon from here: height and azimuth (degrees), angular radius (degrees), how much is lit (0..1). */
        public double moonAlt, moonAz, moonRadiusDeg, illum;
        /** The Moon's height where the centre line passes (degrees). */
        public double moonAltLine;
        /** The Sun's height here (degrees): a crossing in daylight shows too, as a dark dot. */
        public double sunAlt;
        /** Seen from here, the station's nearest to the Moon's middle, in Moon radii: under 1, it crosses the Moon here. */
        public double missRadii;
        /** The station's size on the sky (arcseconds), where known. */
        public double sizeArcsec;
        /** How old the station's orbit will be at the crossing (days). */
        public double ageDays;

        /** Whether it crosses the Moon as seen from right here. */
        public boolean here() {
            return missRadii < 1;
        }

        @Override
        public String toString() {
            return String.format(java.util.Locale.US, "%s at %d: line %.1f km away towards %.0f°, strip %.1f km wide, Moon %.0f° up, miss %.2f radii, %.2f s",
                    name, timeMs, distKm, bearingDeg, 2 * halfWidthKm, moonAlt, missRadii, durationS);
        }
    }

    /** The station and the Moon at one moment (Earth-fixed, km). */
    static final class State {
        final double[] s = new double[3], m = new double[3];
        boolean ok;
    }

    /** A search: one observer, one span of time, one station. */
    static final class Search {
        final Sgp4 orbit;
        final double[] obs, enu;
        final double latRad, lonRad;
        final long from;
        // the Moon (true equator and equinox of date, km) and the equation of the equinoxes at each knot
        final double[][] moon;
        final double[] eqeq;
        final boolean[] low;
        final double[] r = new double[3], v = new double[3];

        Search(Sgp4 orbit, double latDeg, double lonDeg, double hKm, long fromMs, long toMs) {
            this.orbit = orbit;
            latRad = Math.toRadians(latDeg);
            lonRad = Math.toRadians(lonDeg);
            obs = Astro.observerEcef(latRad, lonRad, hKm);
            enu = Astro.ecefToEnu(latRad, lonRad);
            from = fromMs - KNOT_MS;
            int n = (int) ((toMs - from) / KNOT_MS) + 3;
            moon = new double[n][];
            eqeq = new double[n];
            low = new boolean[n];
            double[] e = new double[3];
            for (int k = 0; k < n; k++) {
                double jd = Astro.jdUtc((from + k * KNOT_MS) / 1000.0), jdTt = Astro.jdTt(jd);
                double[] mo = Astro.moonOfDate(jdTt);
                moon[k] = new double[]{mo[0] * mo[3], mo[1] * mo[3], mo[2] * mo[3]};
                double[] nu = Astro.nutation(jdTt);
                eqeq[k] = nu[0] * Math.cos(nu[2]);
                double[] me = new double[3];
                toEarth(moon[k], Sgp4.gstime(jd) + eqeq[k], me);
                Rot.apply(enu, me[0] - obs[0], me[1] - obs[1], me[2] - obs[2], e);
                double alt = Math.toDegrees(Math.asin(e[2] / Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2])));
                low[k] = alt < MIN_ALT - 2.5;
            }
        }

        int knot(double tMs) {
            return (int) Math.floor((tMs - from) / KNOT_MS);
        }

        /** The station and the Moon at {@code tMs}, Earth-fixed. */
        boolean at(double tMs, State st) {
            double jd = Astro.jdUtc(tMs / 1000.0);
            st.ok = false;
            if (!orbit.at(jd, r, v)) return false;
            double g = Sgp4.gstime(jd), cg = Math.cos(g), sg = Math.sin(g);
            st.s[0] = cg * r[0] + sg * r[1];
            st.s[1] = -sg * r[0] + cg * r[1];
            st.s[2] = r[2];
            int k = Math.max(0, Math.min(moon.length - 2, knot(tMs)));
            double f = (tMs - (from + k * KNOT_MS)) / KNOT_MS;
            double[] a = moon[k], b = moon[k + 1];
            double[] md = {a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1]), a[2] + f * (b[2] - a[2])};
            toEarth(md, g + eqeq[k] + f * (eqeq[k + 1] - eqeq[k]), st.m);
            st.ok = true;
            return true;
        }
    }

    /** Of date to Earth-fixed: turned by the sidereal angle {@code g}. */
    static void toEarth(double[] d, double g, double[] out) {
        double cg = Math.cos(g), sg = Math.sin(g);
        out[0] = cg * d[0] + sg * d[1];
        out[1] = -sg * d[0] + cg * d[1];
        out[2] = d[2];
    }

    /**
     * Where the line from the Moon's centre through the station meets the ground: {x, y, z (km, Earth-fixed), the
     * station's distance from there (km), sin(the Moon's height there)}; null if it misses the Earth.
     */
    static double[] ground(State st) {
        double a = Astro.EARTH_RADIUS_KM, k = 1 / (1 - Astro.EARTH_FLATTENING);
        double dx = st.s[0] - st.m[0], dy = st.s[1] - st.m[1], dz = st.s[2] - st.m[2];
        double dn = Math.sqrt(dx * dx + dy * dy + dz * dz);
        dx /= dn;
        dy /= dn;
        dz /= dn;
        // the ellipsoid as a sphere: z stretched by a / b
        double sx = st.s[0], sy = st.s[1], sz = st.s[2] * k, ex = dx, ey = dy, ez = dz * k;
        double A = ex * ex + ey * ey + ez * ez, B = 2 * (sx * ex + sy * ey + sz * ez), C = sx * sx + sy * sy + sz * sz - a * a;
        double disc = B * B - 4 * A * C;
        if (disc < 0) return null;
        double s = (-B - Math.sqrt(disc)) / (2 * A);
        if (s <= 0) return null;
        double gx = st.s[0] + s * dx, gy = st.s[1] + s * dy, gz = st.s[2] + s * dz;
        // the ellipsoid's normal there, and the Moon's height (it is back up the line)
        double b2 = a * a / (k * k);
        double nx = gx / (a * a), ny = gy / (a * a), nz = gz / b2, nn = Math.sqrt(nx * nx + ny * ny + nz * nz);
        double sinH = -(dx * nx + dy * ny + dz * nz) / nn;
        return new double[]{gx, gy, gz, s, sinH};
    }

    /** Geodetic latitude and longitude (degrees) of an Earth-fixed point near the ground (Bowring). */
    public static double[] geodetic(double x, double y, double z) {
        double a = Astro.EARTH_RADIUS_KM, f = Astro.EARTH_FLATTENING, b = a * (1 - f);
        double e2 = f * (2 - f), ep2 = (a * a - b * b) / (b * b);
        double p = Math.sqrt(x * x + y * y);
        double th = Math.atan2(z * a, p * b), st = Math.sin(th), ct = Math.cos(th);
        double lat = Math.atan2(z + ep2 * b * st * st * st, p - e2 * a * ct * ct * ct);
        return new double[]{Math.toDegrees(lat), Math.toDegrees(Math.atan2(y, x))};
    }

    /** The farthest the station can be from a place that sees it this high ({@code minAlt} degrees): km. */
    static double slant(double satRadiusKm, double minAlt) {
        double re = Astro.EARTH_RADIUS_KM, sh = Math.sin(Math.toRadians(minAlt));
        return -re * sh + Math.sqrt(re * re * sh * sh + Math.max(0, satRadiusKm * satRadiusKm - re * re));
    }

    /** The centre line's distance from here at {@code tMs} (km), infinite where there is no centre line or the Moon is low. */
    static double lineDist(Search se, State st, double tMs) {
        if (!se.at(tMs, st)) return Double.POSITIVE_INFINITY;
        double[] g = ground(st);
        if (g == null || g[4] < Math.sin(Math.toRadians(MIN_ALT - 1))) return Double.POSITIVE_INFINITY;
        double dx = g[0] - se.obs[0], dy = g[1] - se.obs[1], dz = g[2] - se.obs[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The angle between the station and the Moon's middle seen from here at {@code tMs} (radians). */
    static double sepHere(Search se, State st, double tMs) {
        if (!se.at(tMs, st)) return Math.PI;
        double[] o = se.obs;
        double ax = st.s[0] - o[0], ay = st.s[1] - o[1], az = st.s[2] - o[2], an = Math.sqrt(ax * ax + ay * ay + az * az);
        double bx = st.m[0] - o[0], by = st.m[1] - o[1], bz = st.m[2] - o[2], bn = Math.sqrt(bx * bx + by * by + bz * bz);
        double cx = ax / an - bx / bn, cy = ay / an - by / bn, cz = az / an - bz / bn;
        return 2 * Math.asin(Math.min(1, Math.sqrt(cx * cx + cy * cy + cz * cz) / 2));
    }

    interface Fn {
        double at(double t);
    }

    /** The minimum of a function unimodal on [a, b] (golden section, to {@code tol}). */
    static double argmin(Fn f, double a, double b, double tol) {
        final double g = (Math.sqrt(5) - 1) / 2;
        double c = b - g * (b - a), d = a + g * (b - a), fc = f.at(c), fd = f.at(d);
        while (b - a > tol) {
            if (fc < fd) {
                b = d;
                d = c;
                fd = fc;
                c = b - g * (b - a);
                fc = f.at(c);
            } else {
                a = c;
                c = d;
                fc = fd;
                d = a + g * (b - a);
                fd = f.at(d);
            }
        }
        return (a + b) / 2;
    }

    /**
     * The crossings of the Moon by one station whose centre line passes within {@code maxKm} of here between
     * {@code fromMs} and {@code toMs}, soonest first.
     *
     * @param spanM the station's size across (m) for its size on the sky; 0 if unknown
     */
    public static List<Transit> find(Sgp4 orbit, int norad, String name, double spanM, double latDeg, double lonDeg, double hKm,
                                     long fromMs, long toMs, double maxKm) {
        final List<Transit> out = new ArrayList<Transit>();
        if (orbit == null || toMs <= fromMs) return out;
        final Search se = new Search(orbit, latDeg, lonDeg, hKm, fromMs, toMs);
        final State st = new State();
        double t = fromMs;
        // the last three samples while the station is near enough (1 s apart)
        double t0 = Double.NaN, f0 = Double.POSITIVE_INFINITY, t1 = Double.NaN, f1 = Double.POSITIVE_INFINITY;
        int failures = 0;
        while (t < toMs) {
            int k = se.knot(t);
            if (k >= 0 && k + 1 < se.low.length && se.low[k] && se.low[k + 1]) {
                t = se.from + (k + 1) * (double) KNOT_MS;
                t0 = t1 = Double.NaN;
                continue;
            }
            if (!se.at(t, st)) {
                // decayed, or elements too far from their epoch
                if (++failures > 20) break;
                t += 60_000;
                t0 = t1 = Double.NaN;
                continue;
            }
            double rs = Math.sqrt(st.s[0] * st.s[0] + st.s[1] * st.s[1] + st.s[2] * st.s[2]);
            double ox = st.s[0] - se.obs[0], oy = st.s[1] - se.obs[1], oz = st.s[2] - se.obs[2];
            double dSO = Math.sqrt(ox * ox + oy * oy + oz * oz);
            double near = slant(rs, MIN_ALT - 1) + maxKm + 150;
            if (dSO > near) {
                // nothing can happen until it is near: it flies under 8 km a second
                t += Math.max(1000, Math.min(120_000, (dSO - near) / 8.0 * 1000));
                t0 = t1 = Double.NaN;
                continue;
            }
            double f = lineDist(se, st, t);
            if (!Double.isNaN(t1) && !Double.isNaN(t0) && f1 < f0 && f1 <= f && f1 < maxKm * 4 + 200) {
                Transit tr = refine(se, st, norad, name, spanM, t0, t, maxKm);
                if (tr != null) {
                    tr.ageDays = Astro.jdUtc(tr.timeMs / 1000.0) - orbit.jdEpoch;
                    boolean dup = false;
                    for (Transit o : out) {
                        if (Math.abs(o.timeMs - tr.timeMs) < 60_000) {
                            dup = true;
                            if (tr.distKm < o.distKm) out.set(out.indexOf(o), tr);
                            break;
                        }
                    }
                    if (!dup) out.add(tr);
                }
            }
            t0 = t1;
            f0 = f1;
            t1 = t;
            f1 = f;
            t += 1000;
        }
        Collections.sort(out, new Comparator<Transit>() {
            @Override
            public int compare(Transit a, Transit b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        return out;
    }

    /** The crossing whose centre line is nearest here between {@code a} and {@code b} (ms); null if too far or too low. */
    static Transit refine(final Search se, final State st, int norad, String name, double spanM, double a, double b, double maxKm) {
        // a dense look first (the line is undefined, infinitely far, where the Moon is low), then the golden section
        double best = Double.POSITIVE_INFINITY, tb = (a + b) / 2;
        int n = 40;
        for (int i = 0; i <= n; i++) {
            double t = a + (b - a) * i / n, f = lineDist(se, st, t);
            if (f < best) {
                best = f;
                tb = t;
            }
        }
        if (Double.isInfinite(best)) return null;
        double step = (b - a) / n;
        double tc = argmin(new Fn() {
            @Override
            public double at(double t) {
                return lineDist(se, st, t);
            }
        }, tb - step, tb + step, 0.5);
        double d = lineDist(se, st, tc);
        if (!(d <= maxKm)) return null;
        se.at(tc, st);
        double[] g = ground(st);
        if (g == null) return null;
        Transit tr = new Transit();
        tr.norad = norad;
        tr.name = name;
        tr.timeMs = Math.round(tc);
        tr.distKm = d;
        double[] ll = geodetic(g[0], g[1], g[2]);
        tr.lat = ll[0];
        tr.lon = ll[1];
        double[] e = new double[3];
        Rot.apply(se.enu, g[0] - se.obs[0], g[1] - se.obs[1], g[2] - se.obs[2], e);
        tr.bearingDeg = MoonGeo.norm360(Math.toDegrees(Math.atan2(e[0], e[1])));
        tr.rangeKm = g[3];
        tr.moonAltLine = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, g[4]))));
        if (tr.moonAltLine < MIN_ALT) return null;
        // the Moon from the centre-line point: its direction (back up the line) and its angular radius there
        double mx = st.m[0] - g[0], my = st.m[1] - g[1], mz = st.m[2] - g[2], mn = Math.sqrt(mx * mx + my * my + mz * mz);
        double rho = Math.asin(MoonGeo.RADIUS_KM / mn);
        // the way the centre line runs: the point a fifth of a second either side
        State s2 = new State();
        se.at(tc - 200, s2);
        double[] ga = ground(s2);
        se.at(tc + 200, s2);
        double[] gb = ground(s2);
        double ux = 0, uy = 1, speed = Double.NaN;
        if (ga != null && gb != null) {
            double[] du = new double[3];
            Rot.apply(se.enu, gb[0] - ga[0], gb[1] - ga[1], gb[2] - ga[2], du);
            double h = Math.sqrt(du[0] * du[0] + du[1] * du[1]);
            if (h > 1e-9) {
                ux = du[0] / h;
                uy = du[1] / h;
            }
            speed = Math.sqrt(du[0] * du[0] + du[1] * du[1] + du[2] * du[2]) / 0.4;
        }
        tr.pathDeg = MoonGeo.norm360(Math.toDegrees(Math.atan2(ux, uy)));
        // the ellipse: Moon's azimuth (horizontal, east-north) and height from the point, as seen in here's frame
        double[] me = new double[3];
        Rot.apply(se.enu, mx / mn, my / mn, mz / mn, me);
        double hor = Math.sqrt(me[0] * me[0] + me[1] * me[1]), sinH = Math.max(0.05, g[4]);
        double alx = hor > 1e-9 ? me[0] / hor : 0, aly = hor > 1e-9 ? me[1] / hor : 1;
        // across the line, and the ellipse's reach that way
        double nx = -uy, ny = ux;
        double along = nx * alx + ny * aly, across = nx * (-aly) + ny * alx;
        double bR = tr.rangeKm * Math.tan(rho), aR = bR / sinH;
        tr.halfWidthKm = Math.sqrt(aR * aR * along * along + bR * bR * across * across);
        // the crossing's length on the line: the Moon's diameter at the station's speed across the sky there
        se.at(tc - 100, s2);
        double[] p1 = dirFrom(g, s2.s);
        se.at(tc + 100, s2);
        double[] p2 = dirFrom(g, s2.s);
        double w = 2 * Math.asin(Math.min(1, dist(p1, p2) / 2)) / 0.2;
        tr.durationS = w > 0 ? 2 * rho / w : Double.NaN;
        if (spanM > 0) tr.sizeArcsec = Math.toDegrees(spanM / 1000 / tr.rangeKm) * 3600;
        // from here: the Moon, the Sun, and how near the station comes to the Moon's middle
        se.at(tc, st);
        double[] mh = new double[3];
        Rot.apply(se.enu, st.m[0] - se.obs[0], st.m[1] - se.obs[1], st.m[2] - se.obs[2], mh);
        double mhn = Math.sqrt(mh[0] * mh[0] + mh[1] * mh[1] + mh[2] * mh[2]);
        tr.moonAlt = Math.toDegrees(Math.asin(mh[2] / mhn));
        tr.moonAz = MoonGeo.norm360(Math.toDegrees(Math.atan2(mh[0], mh[1])));
        double rhoHere = Math.asin(MoonGeo.RADIUS_KM / mhn);
        tr.moonRadiusDeg = Math.toDegrees(rhoHere);
        double jd = Astro.jdUtc(tc / 1000.0), jdTt = Astro.jdTt(jd);
        tr.illum = Astro.moonIllumination(jdTt);
        double[] sun = Astro.sunOfDate(jdTt), se3 = new double[3], sh = new double[3];
        toEarth(sun, Astro.gast(jd), se3);
        Rot.apply(se.enu, se3[0], se3[1], se3[2], sh);
        tr.sunAlt = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, sh[2]))));
        double elong = Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (sh[0] * mh[0] + sh[1] * mh[1] + sh[2] * mh[2]) / mhn))));
        if (elong < MIN_ELONGATION) return null;
        double th = argmin(new Fn() {
            @Override
            public double at(double t) {
                return sepHere(se, st, t);
            }
        }, tc - 6000, tc + 6000, 0.5);
        double sep = sepHere(se, st, th);
        tr.hereMs = Math.round(th);
        tr.missRadii = sep / rhoHere;
        if (tr.missRadii < 1) {
            double s1 = sepHere(se, st, th - 100), s3 = sepHere(se, st, th + 100);
            // the station's speed against the Moon from here, from the separation's growth either side of its least
            double rate = (Math.sqrt(Math.max(0, s1 * s1 - sep * sep)) + Math.sqrt(Math.max(0, s3 * s3 - sep * sep))) / 0.2;
            tr.hereS = rate > 0 ? 2 * rhoHere * Math.sqrt(1 - tr.missRadii * tr.missRadii) / rate : Double.NaN;
        }
        return tr;
    }

    static double[] dirFrom(double[] g, double[] s) {
        double x = s[0] - g[0], y = s[1] - g[1], z = s[2] - g[2], n = Math.sqrt(x * x + y * y + z * z);
        return new double[]{x / n, y / n, z / n};
    }

    static double dist(double[] a, double[] b) {
        double x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return Math.sqrt(x * x + y * y + z * z);
    }

    /** A station to look for. */
    public static final class Station {
        public final Sgp4 orbit;
        public final int norad;
        public final String name;
        /** Its size across (m), 0 if unknown. */
        public final double spanM;

        public Station(Sgp4 orbit, int norad, String name, double spanM) {
            this.orbit = orbit;
            this.norad = norad;
            this.name = name;
            this.spanM = spanM;
        }
    }

    /** Every station's crossings, merged and soonest first. */
    public static List<Transit> all(List<Station> stations, double latDeg, double lonDeg, double hKm, long fromMs, long toMs, double maxKm) {
        List<Transit> out = new ArrayList<Transit>();
        for (Station s : stations) out.addAll(find(s.orbit, s.norad, s.name, s.spanM, latDeg, lonDeg, hKm, fromMs, toMs, maxKm));
        Collections.sort(out, new Comparator<Transit>() {
            @Override
            public int compare(Transit a, Transit b) {
                return Long.compare(a.timeMs, b.timeMs);
            }
        });
        return out;
    }
}
