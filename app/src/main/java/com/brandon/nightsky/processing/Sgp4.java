package com.brandon.nightsky.processing;

import java.util.Locale;

/**
 * The SGP4/SDP4 orbit model that published satellite elements (TLE and OMM, as CelesTrak gives them) are made
 * for: Vallado, Crawford, Hujsak and Kelso, "Revisiting Spacetrack Report #3", AIAA 2006-6753, ported line by
 * line from Brandon Rhodes's python-sgp4 (MIT licence, github.com/brandon-rhodes/python-sgp4, propagation.py),
 * itself a port of Vallado's C++. Near-earth and deep-space (period of 225 minutes or more) orbits, with the
 * 12- and 24-hour resonances. Positions and velocities are in TEME (true equator, mean equinox of date) in km
 * and km/s; times are minutes from the element set's epoch.
 * <p>
 * Checked against the published verification set (SGP4-VER.TLE and tcppver.out) in SatTest.
 */
public final class Sgp4 {
    /** Gravity models: the old and the standard WGS-72 (what the elements are fitted with) and WGS-84. */
    public static final int WGS72OLD = 0, WGS72 = 1, WGS84 = 2;

    private static final double PI = Math.PI, TWOPI = 2 * Math.PI, DEG2RAD = Math.PI / 180;
    private static final double X2O3 = 2.0 / 3.0;
    /** Revolutions per day to radians per minute: divide by this. */
    public static final double XPDOTP = 1440.0 / (2.0 * Math.PI);

    // ---- the element set
    public String name = "", satnum = "";
    /** Julian date (UTC) of the elements' epoch. */
    public double jdEpoch;
    public double bstar, ndot, nddot, ecco, argpo, inclo, mo, noKozai, nodeo;
    /** 'i' improved (default) or 'a' (as AFSPC computes it). */
    public char opsmode = 'i';

    /** 0 = fine; 1-6 as in Vallado's code (e.g. 6 = decayed). Set by {@link #propagate}. */
    public int error;

    // ---- gravity
    private double tumin, mu, radiusearthkm, xke, j2, j3, j4, j3oj2;

    // ---- initialised constants
    private char method = 'n', init = 'y';
    private int isimp, irez;
    private double aycof, con41, cc1, cc4, cc5, d2, d3, d4, delmo, eta, argpdot, omgcof, sinmao, t, t2cof, t3cof,
            t4cof, t5cof, x1mth2, x7thm1, mdot, nodedot, xlcof, xmcof, nodecf, noUnkozai, gsto;
    private double d2201, d2211, d3210, d3222, d4410, d4422, d5220, d5232, d5421, d5433, dedt, del1, del2, del3,
            didt, dmdt, dnodt, domdt, e3, ee2, peo, pgho, pho, pinco, plo, se2, se3, sgh2, sgh3, sgh4, sh2, sh3, si2,
            si3, sl2, sl3, sl4, xfact, xgh2, xgh3, xgh4, xh2, xh3, xi2, xi3, xl2, xl3, xl4, xlamo, zmol, zmos, atime,
            xli, xni;
    /** Semi-major axis (earth radii) and apogee and perigee heights (earth radii) at the epoch. */
    public double a, alta, altp;

    /** Python's % (the result takes the divisor's sign). */
    private static double pmod(double x, double m) {
        double r = x % m;
        return r < 0 ? r + m : r;
    }

    // -------------------------------------------------------------------------------------------- set up

    /**
     * From the two lines of a TLE (the name, if any, separately). Throws IllegalArgumentException for lines
     * that aren't a TLE.
     */
    public static Sgp4 fromTle(String name, String line1, String line2) {
        String l1 = rstrip(line1), l2 = rstrip(line2);
        if (!(l1.length() >= 64 && l1.startsWith("1 ") && l1.charAt(8) == ' ' && l1.charAt(23) == '.'
                && l1.charAt(32) == ' ' && l1.charAt(34) == '.' && l1.charAt(43) == ' ' && l1.charAt(52) == ' '
                && l1.charAt(61) == ' ' && l1.charAt(63) == ' ')) {
            throw new IllegalArgumentException("not a TLE line 1: " + line1);
        }
        if (!(l2.length() >= 68 && l2.startsWith("2 ") && l2.charAt(7) == ' ' && l2.charAt(11) == '.'
                && l2.charAt(16) == ' ' && l2.charAt(20) == '.' && l2.charAt(25) == ' ' && l2.charAt(33) == ' '
                && l2.charAt(37) == '.' && l2.charAt(42) == ' ' && l2.charAt(46) == '.' && l2.charAt(51) == ' ')) {
            throw new IllegalArgumentException("not a TLE line 2: " + line2);
        }
        Sgp4 s = new Sgp4();
        s.name = name != null ? name.trim() : "";
        s.satnum = l1.substring(2, 7).trim();
        if (!s.satnum.equals(l2.substring(2, 7).trim())) throw new IllegalArgumentException("TLE lines of different objects");
        int yy = Integer.parseInt(l1.substring(18, 20).trim());
        double epochDays = Double.parseDouble(l1.substring(20, 32).trim());
        double ndot = Double.parseDouble(l1.substring(33, 43).trim());
        double nddot = Double.parseDouble(l1.charAt(44) + "." + l1.substring(45, 50).trim());
        int nexp = Integer.parseInt(l1.substring(50, 52).trim());
        double bstar = Double.parseDouble(l1.charAt(53) + "." + l1.substring(54, 59).trim());
        int ibexp = Integer.parseInt(l1.substring(59, 61).trim());
        double incl = Double.parseDouble(l2.substring(8, 16).trim());
        double node = Double.parseDouble(l2.substring(17, 25).trim());
        double ecc = Double.parseDouble("0." + l2.substring(26, 33).replace(' ', '0'));
        double argp = Double.parseDouble(l2.substring(34, 42).trim());
        double m = Double.parseDouble(l2.substring(43, 51).trim());
        double n = Double.parseDouble(l2.substring(52, 63).trim());
        int year = yy < 57 ? yy + 2000 : yy + 1900;
        s.jdEpoch = jday(year, 1, 1, 0, 0, 0) - 1 + epochDays;
        s.init(WGS72, ndot / (XPDOTP * 1440.0), nddot * Math.pow(10, nexp) / (XPDOTP * 1440.0 * 1440),
                bstar * Math.pow(10, ibexp), ecc, argp * DEG2RAD, incl * DEG2RAD, m * DEG2RAD, n / XPDOTP, node * DEG2RAD);
        return s;
    }

    /**
     * From OMM fields (CelesTrak's JSON): epoch as an ISO date and time (UTC), mean motion in revolutions per
     * day, angles in degrees, MEAN_MOTION_DOT and _DDOT as in a TLE.
     */
    public static Sgp4 fromOmm(String name, String noradId, String epochIso, double meanMotionRevDay, double ecc,
                               double inclDeg, double raanDeg, double argpDeg, double meanAnomalyDeg, double bstar,
                               double meanMotionDot, double meanMotionDdot) {
        Sgp4 s = new Sgp4();
        s.name = name != null ? name.trim() : "";
        s.satnum = noradId != null ? noradId.trim() : "";
        s.jdEpoch = jdIso(epochIso);
        s.init(WGS72, meanMotionDot / (XPDOTP * 1440.0), meanMotionDdot / (XPDOTP * 1440.0 * 1440.0), bstar, ecc,
                argpDeg * DEG2RAD, inclDeg * DEG2RAD, meanAnomalyDeg * DEG2RAD, meanMotionRevDay / 720.0 * PI,
                raanDeg * DEG2RAD);
        return s;
    }

    private static String rstrip(String s) {
        int e = s.length();
        while (e > 0 && Character.isWhitespace(s.charAt(e - 1))) e--;
        return s.substring(0, e);
    }

    /** Julian date of a calendar date and time (UTC), as Vallado's jday. */
    public static double jday(int year, int mon, int day, int hr, int minute, double sec) {
        return 367.0 * year - Math.floor(7 * (year + Math.floor((mon + 9) / 12.0)) * 0.25)
                + Math.floor(275 * mon / 9.0) + day + 1721013.5 + ((sec / 60.0 + minute) / 60.0 + hr) / 24.0;
    }

    /** Julian date of an ISO date-time such as 2026-10-14T21:03:17.123456 (UTC; a trailing Z is fine). */
    public static double jdIso(String iso) {
        String s = iso.trim();
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1);
        int T = s.indexOf('T');
        if (T < 0) T = s.indexOf(' ');
        String date = T >= 0 ? s.substring(0, T) : s, time = T >= 0 ? s.substring(T + 1) : "00:00:00";
        String[] d = date.split("-");
        String[] h = time.split(":");
        int year = Integer.parseInt(d[0]), mon = Integer.parseInt(d[1]), day = Integer.parseInt(d[2]);
        int hr = h.length > 0 ? Integer.parseInt(h[0]) : 0, mi = h.length > 1 ? Integer.parseInt(h[1]) : 0;
        double sec = h.length > 2 ? Double.parseDouble(h[2]) : 0;
        return jday(year, mon, day, hr, mi, sec);
    }

    private void gravity(int which) {
        switch (which) {
            case WGS72OLD:
                mu = 398600.79964;
                radiusearthkm = 6378.135;
                xke = 0.0743669161;
                tumin = 1.0 / xke;
                j2 = 0.001082616;
                j3 = -0.00000253881;
                j4 = -0.00000165597;
                break;
            case WGS84:
                mu = 398600.5;
                radiusearthkm = 6378.137;
                xke = 60.0 / Math.sqrt(radiusearthkm * radiusearthkm * radiusearthkm / mu);
                tumin = 1.0 / xke;
                j2 = 0.00108262998905;
                j3 = -0.00000253215306;
                j4 = -0.00000161098761;
                break;
            default:
                mu = 398600.8;
                radiusearthkm = 6378.135;
                xke = 60.0 / Math.sqrt(radiusearthkm * radiusearthkm * radiusearthkm / mu);
                tumin = 1.0 / xke;
                j2 = 0.001082616;
                j3 = -0.00000253881;
                j4 = -0.00000165597;
                break;
        }
        j3oj2 = j3 / j2;
    }

    /** Earth's equatorial radius of the gravity model, km. */
    public double earthRadiusKm() {
        return radiusearthkm;
    }

    /** Orbital period at the epoch, minutes. */
    public double periodMinutes() {
        return TWOPI / noUnkozai;
    }

    // -------------------------------------------------------------------------------------------- deep space

    /** Lunar-solar periodics (dpper): e = {ep, inclp, nodep, argpp, mp}, changed in place. */
    private void dpper(double inclo, char init, double[] e) {
        final double zns = 1.19459e-5, zes = 0.01675, znl = 1.5835218e-4, zel = 0.05490;
        double ep = e[0], inclp = e[1], nodep = e[2], argpp = e[3], mp = e[4];
        double zm = zmos + zns * t;
        if (init == 'y') zm = zmos;
        double zf = zm + 2.0 * zes * Math.sin(zm);
        double sinzf = Math.sin(zf);
        double f2 = 0.5 * sinzf * sinzf - 0.25;
        double f3 = -0.5 * sinzf * Math.cos(zf);
        double ses = se2 * f2 + se3 * f3;
        double sis = si2 * f2 + si3 * f3;
        double sls = sl2 * f2 + sl3 * f3 + sl4 * sinzf;
        double sghs = sgh2 * f2 + sgh3 * f3 + sgh4 * sinzf;
        double shs = sh2 * f2 + sh3 * f3;
        zm = zmol + znl * t;
        if (init == 'y') zm = zmol;
        zf = zm + 2.0 * zel * Math.sin(zm);
        sinzf = Math.sin(zf);
        f2 = 0.5 * sinzf * sinzf - 0.25;
        f3 = -0.5 * sinzf * Math.cos(zf);
        double sel = ee2 * f2 + e3 * f3;
        double sil = xi2 * f2 + xi3 * f3;
        double sll = xl2 * f2 + xl3 * f3 + xl4 * sinzf;
        double sghl = xgh2 * f2 + xgh3 * f3 + xgh4 * sinzf;
        double shll = xh2 * f2 + xh3 * f3;
        double pe = ses + sel;
        double pinc = sis + sil;
        double pl = sls + sll;
        double pgh = sghs + sghl;
        double ph = shs + shll;
        if (init == 'n') {
            pe = pe - peo;
            pinc = pinc - pinco;
            pl = pl - plo;
            pgh = pgh - pgho;
            ph = ph - pho;
            inclp = inclp + pinc;
            ep = ep + pe;
            double sinip = Math.sin(inclp);
            double cosip = Math.cos(inclp);
            if (inclp >= 0.2) {
                ph /= sinip;
                pgh -= cosip * ph;
                argpp += pgh;
                nodep += ph;
                mp += pl;
            } else {
                double sinop = Math.sin(nodep);
                double cosop = Math.cos(nodep);
                double alfdp = sinip * sinop;
                double betdp = sinip * cosop;
                double dalf = ph * cosop + pinc * cosip * sinop;
                double dbet = -ph * sinop + pinc * cosip * cosop;
                alfdp = alfdp + dalf;
                betdp = betdp + dbet;
                nodep = nodep % TWOPI; // C fmod, as in the original
                if (nodep < 0.0 && opsmode == 'a') nodep = nodep + TWOPI;
                double xls = mp + argpp + pl + pgh + (cosip - pinc * sinip) * nodep;
                double xnoh = nodep;
                nodep = Math.atan2(alfdp, betdp);
                if (nodep < 0.0 && opsmode == 'a') nodep = nodep + TWOPI;
                if (Math.abs(xnoh - nodep) > PI) {
                    if (nodep < xnoh) nodep = nodep + TWOPI;
                    else nodep = nodep - TWOPI;
                }
                mp += pl;
                argpp = xls - mp - cosip * nodep;
            }
        }
        e[0] = ep;
        e[1] = inclp;
        e[2] = nodep;
        e[3] = argpp;
        e[4] = mp;
    }

    /** dscom's results that dsinit needs. */
    private static final class Ds {
        double snodm, cnodm, sinim, cosim, sinomm, cosomm, day, em, emsq, gam, rtemsq;
        double s1, s2, s3, s4, s5, s6, s7, ss1, ss2, ss3, ss4, ss5, ss6, ss7;
        double sz1, sz2, sz3, sz11, sz12, sz13, sz21, sz22, sz23, sz31, sz32, sz33;
        double nm, z1, z2, z3, z11, z12, z13, z21, z22, z23, z31, z32, z33;
    }

    /** Deep-space common terms (dscom); sets this record's lunar-solar coefficients. */
    private Ds dscom(double epoch, double ep, double argpp, double tc, double inclp, double nodep, double np) {
        final double zes = 0.01675, zel = 0.05490, c1ss = 2.9864797e-6, c1l = 4.7968065e-7, zsinis = 0.39785416,
                zcosis = 0.91744867, zcosgs = 0.1945905, zsings = -0.98088458;
        Ds r = new Ds();
        r.nm = np;
        r.em = ep;
        r.snodm = Math.sin(nodep);
        r.cnodm = Math.cos(nodep);
        r.sinomm = Math.sin(argpp);
        r.cosomm = Math.cos(argpp);
        r.sinim = Math.sin(inclp);
        r.cosim = Math.cos(inclp);
        r.emsq = r.em * r.em;
        double betasq = 1.0 - r.emsq;
        r.rtemsq = Math.sqrt(betasq);
        peo = 0.0;
        pinco = 0.0;
        plo = 0.0;
        pgho = 0.0;
        pho = 0.0;
        r.day = epoch + 18261.5 + tc / 1440.0;
        double xnodce = pmod(4.5236020 - 9.2422029e-4 * r.day, TWOPI);
        double stem = Math.sin(xnodce);
        double ctem = Math.cos(xnodce);
        double zcosil = 0.91375164 - 0.03568096 * ctem;
        double zsinil = Math.sqrt(1.0 - zcosil * zcosil);
        double zsinhl = 0.089683511 * stem / zsinil;
        double zcoshl = Math.sqrt(1.0 - zsinhl * zsinhl);
        r.gam = 5.8351514 + 0.0019443680 * r.day;
        double zx = 0.39785416 * stem / zsinil;
        double zy = zcoshl * ctem + 0.91744867 * zsinhl * stem;
        zx = Math.atan2(zx, zy);
        zx = r.gam + zx - xnodce;
        double zcosgl = Math.cos(zx);
        double zsingl = Math.sin(zx);

        double zcosg = zcosgs, zsing = zsings, zcosi = zcosis, zsini = zsinis, zcosh = r.cnodm, zsinh = r.snodm;
        double cc = c1ss;
        double xnoi = 1.0 / r.nm;
        for (int lsflg = 1; lsflg <= 2; lsflg++) {
            double a1 = zcosg * zcosh + zsing * zcosi * zsinh;
            double a3 = -zsing * zcosh + zcosg * zcosi * zsinh;
            double a7 = -zcosg * zsinh + zsing * zcosi * zcosh;
            double a8 = zsing * zsini;
            double a9 = zsing * zsinh + zcosg * zcosi * zcosh;
            double a10 = zcosg * zsini;
            double a2 = r.cosim * a7 + r.sinim * a8;
            double a4 = r.cosim * a9 + r.sinim * a10;
            double a5 = -r.sinim * a7 + r.cosim * a8;
            double a6 = -r.sinim * a9 + r.cosim * a10;

            double x1 = a1 * r.cosomm + a2 * r.sinomm;
            double x2 = a3 * r.cosomm + a4 * r.sinomm;
            double x3 = -a1 * r.sinomm + a2 * r.cosomm;
            double x4 = -a3 * r.sinomm + a4 * r.cosomm;
            double x5 = a5 * r.sinomm;
            double x6 = a6 * r.sinomm;
            double x7 = a5 * r.cosomm;
            double x8 = a6 * r.cosomm;

            r.z31 = 12.0 * x1 * x1 - 3.0 * x3 * x3;
            r.z32 = 24.0 * x1 * x2 - 6.0 * x3 * x4;
            r.z33 = 12.0 * x2 * x2 - 3.0 * x4 * x4;
            r.z1 = 3.0 * (a1 * a1 + a2 * a2) + r.z31 * r.emsq;
            r.z2 = 6.0 * (a1 * a3 + a2 * a4) + r.z32 * r.emsq;
            r.z3 = 3.0 * (a3 * a3 + a4 * a4) + r.z33 * r.emsq;
            r.z11 = -6.0 * a1 * a5 + r.emsq * (-24.0 * x1 * x7 - 6.0 * x3 * x5);
            r.z12 = -6.0 * (a1 * a6 + a3 * a5) + r.emsq * (-24.0 * (x2 * x7 + x1 * x8) - 6.0 * (x3 * x6 + x4 * x5));
            r.z13 = -6.0 * a3 * a6 + r.emsq * (-24.0 * x2 * x8 - 6.0 * x4 * x6);
            r.z21 = 6.0 * a2 * a5 + r.emsq * (24.0 * x1 * x5 - 6.0 * x3 * x7);
            r.z22 = 6.0 * (a4 * a5 + a2 * a6) + r.emsq * (24.0 * (x2 * x5 + x1 * x6) - 6.0 * (x4 * x7 + x3 * x8));
            r.z23 = 6.0 * a4 * a6 + r.emsq * (24.0 * x2 * x6 - 6.0 * x4 * x8);
            r.z1 = r.z1 + r.z1 + betasq * r.z31;
            r.z2 = r.z2 + r.z2 + betasq * r.z32;
            r.z3 = r.z3 + r.z3 + betasq * r.z33;
            r.s3 = cc * xnoi;
            r.s2 = -0.5 * r.s3 / r.rtemsq;
            r.s4 = r.s3 * r.rtemsq;
            r.s1 = -15.0 * r.em * r.s4;
            r.s5 = x1 * x3 + x2 * x4;
            r.s6 = x2 * x3 + x1 * x4;
            r.s7 = x2 * x4 - x1 * x3;

            if (lsflg == 1) {
                r.ss1 = r.s1;
                r.ss2 = r.s2;
                r.ss3 = r.s3;
                r.ss4 = r.s4;
                r.ss5 = r.s5;
                r.ss6 = r.s6;
                r.ss7 = r.s7;
                r.sz1 = r.z1;
                r.sz2 = r.z2;
                r.sz3 = r.z3;
                r.sz11 = r.z11;
                r.sz12 = r.z12;
                r.sz13 = r.z13;
                r.sz21 = r.z21;
                r.sz22 = r.z22;
                r.sz23 = r.z23;
                r.sz31 = r.z31;
                r.sz32 = r.z32;
                r.sz33 = r.z33;
                zcosg = zcosgl;
                zsing = zsingl;
                zcosi = zcosil;
                zsini = zsinil;
                zcosh = zcoshl * r.cnodm + zsinhl * r.snodm;
                zsinh = r.snodm * zcoshl - r.cnodm * zsinhl;
                cc = c1l;
            }
        }
        zmol = pmod(4.7199672 + 0.22997150 * r.day - r.gam, TWOPI);
        zmos = pmod(6.2565837 + 0.017201977 * r.day, TWOPI);

        se2 = 2.0 * r.ss1 * r.ss6;
        se3 = 2.0 * r.ss1 * r.ss7;
        si2 = 2.0 * r.ss2 * r.sz12;
        si3 = 2.0 * r.ss2 * (r.sz13 - r.sz11);
        sl2 = -2.0 * r.ss3 * r.sz2;
        sl3 = -2.0 * r.ss3 * (r.sz3 - r.sz1);
        sl4 = -2.0 * r.ss3 * (-21.0 - 9.0 * r.emsq) * zes;
        sgh2 = 2.0 * r.ss4 * r.sz32;
        sgh3 = 2.0 * r.ss4 * (r.sz33 - r.sz31);
        sgh4 = -18.0 * r.ss4 * zes;
        sh2 = -2.0 * r.ss2 * r.sz22;
        sh3 = -2.0 * r.ss2 * (r.sz23 - r.sz21);

        ee2 = 2.0 * r.s1 * r.s6;
        e3 = 2.0 * r.s1 * r.s7;
        xi2 = 2.0 * r.s2 * r.z12;
        xi3 = 2.0 * r.s2 * (r.z13 - r.z11);
        xl2 = -2.0 * r.s3 * r.z2;
        xl3 = -2.0 * r.s3 * (r.z3 - r.z1);
        xl4 = -2.0 * r.s3 * (-21.0 - 9.0 * r.emsq) * zel;
        xgh2 = 2.0 * r.s4 * r.z32;
        xgh3 = 2.0 * r.s4 * (r.z33 - r.z31);
        xgh4 = -18.0 * r.s4 * zel;
        xh2 = -2.0 * r.s2 * r.z22;
        xh3 = -2.0 * r.s2 * (r.z23 - r.z21);
        return r;
    }

    /**
     * Deep-space initialisation (dsinit): the resonance terms. m = {em, argpm, inclm, mm, nm, nodem} in and out;
     * returns dndt.
     */
    private double dsinit(Ds s, double tc, double xpidot, double eccsq, double[] m) {
        final double q22 = 1.7891679e-6, q31 = 2.1460748e-6, q33 = 2.2123015e-7, root22 = 1.7891679e-6,
                root44 = 7.3636953e-9, root54 = 2.1765803e-9, rptim = 4.37526908801129966e-3, root32 = 3.7393792e-7,
                root52 = 1.1428639e-7, znl = 1.5835218e-4, zns = 1.19459e-5;
        double em = m[0], argpm = m[1], inclm = m[2], mm = m[3], nm = m[4], nodem = m[5];
        double emsq = s.emsq, cosim = s.cosim, sinim = s.sinim;

        irez = 0;
        if (0.0034906585 < nm && nm < 0.0052359877) irez = 1;
        if (8.26e-3 <= nm && nm <= 9.24e-3 && em >= 0.5) irez = 2;

        double ses = s.ss1 * zns * s.ss5;
        double sis = s.ss2 * zns * (s.sz11 + s.sz13);
        double sls = -zns * s.ss3 * (s.sz1 + s.sz3 - 14.0 - 6.0 * emsq);
        double sghs = s.ss4 * zns * (s.sz31 + s.sz33 - 6.0);
        double shs = -zns * s.ss2 * (s.sz21 + s.sz23);
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shs = 0.0;
        if (sinim != 0.0) shs = shs / sinim;
        double sgs = sghs - cosim * shs;

        dedt = ses + s.s1 * znl * s.s5;
        didt = sis + s.s2 * znl * (s.z11 + s.z13);
        dmdt = sls - znl * s.s3 * (s.z1 + s.z3 - 14.0 - 6.0 * emsq);
        double sghl = s.s4 * znl * (s.z31 + s.z33 - 6.0);
        double shll = -znl * s.s2 * (s.z21 + s.z23);
        if (inclm < 5.2359877e-2 || inclm > PI - 5.2359877e-2) shll = 0.0;
        domdt = sgs + sghl;
        dnodt = shs;
        if (sinim != 0.0) {
            domdt = domdt - cosim / sinim * shll;
            dnodt = dnodt + shll / sinim;
        }

        double dndt = 0.0;
        double theta = pmod(gsto + tc * rptim, TWOPI);
        em = em + dedt * t;
        inclm = inclm + didt * t;
        argpm = argpm + domdt * t;
        nodem = nodem + dnodt * t;
        mm = mm + dmdt * t;

        if (irez != 0) {
            double aonv = Math.pow(nm / xke, X2O3);
            if (irez == 2) {
                double cosisq = cosim * cosim;
                double emo = em;
                em = ecco;
                double emsqo = emsq;
                emsq = eccsq;
                double eoc = em * emsq;
                double g201 = -0.306 - (em - 0.64) * 0.440;
                double g211, g310, g322, g410, g422, g520, g533, g521, g532;
                if (em <= 0.65) {
                    g211 = 3.616 - 13.2470 * em + 16.2900 * emsq;
                    g310 = -19.302 + 117.3900 * em - 228.4190 * emsq + 156.5910 * eoc;
                    g322 = -18.9068 + 109.7927 * em - 214.6334 * emsq + 146.5816 * eoc;
                    g410 = -41.122 + 242.6940 * em - 471.0940 * emsq + 313.9530 * eoc;
                    g422 = -146.407 + 841.8800 * em - 1629.014 * emsq + 1083.4350 * eoc;
                    g520 = -532.114 + 3017.977 * em - 5740.032 * emsq + 3708.2760 * eoc;
                } else {
                    g211 = -72.099 + 331.819 * em - 508.738 * emsq + 266.724 * eoc;
                    g310 = -346.844 + 1582.851 * em - 2415.925 * emsq + 1246.113 * eoc;
                    g322 = -342.585 + 1554.908 * em - 2366.899 * emsq + 1215.972 * eoc;
                    g410 = -1052.797 + 4758.686 * em - 7193.992 * emsq + 3651.957 * eoc;
                    g422 = -3581.690 + 16178.110 * em - 24462.770 * emsq + 12422.520 * eoc;
                    if (em > 0.715) g520 = -5149.66 + 29936.92 * em - 54087.36 * emsq + 31324.56 * eoc;
                    else g520 = 1464.74 - 4664.75 * em + 3763.64 * emsq;
                }
                if (em < 0.7) {
                    g533 = -919.22770 + 4988.6100 * em - 9064.7700 * emsq + 5542.21 * eoc;
                    g521 = -822.71072 + 4568.6173 * em - 8491.4146 * emsq + 5337.524 * eoc;
                    g532 = -853.66600 + 4690.2500 * em - 8624.7700 * emsq + 5341.4 * eoc;
                } else {
                    g533 = -37995.780 + 161616.52 * em - 229838.20 * emsq + 109377.94 * eoc;
                    g521 = -51752.104 + 218913.95 * em - 309468.16 * emsq + 146349.42 * eoc;
                    g532 = -40023.880 + 170470.89 * em - 242699.48 * emsq + 115605.82 * eoc;
                }
                double sini2 = sinim * sinim;
                double f220 = 0.75 * (1.0 + 2.0 * cosim + cosisq);
                double f221 = 1.5 * sini2;
                double f321 = 1.875 * sinim * (1.0 - 2.0 * cosim - 3.0 * cosisq);
                double f322 = -1.875 * sinim * (1.0 + 2.0 * cosim - 3.0 * cosisq);
                double f441 = 35.0 * sini2 * f220;
                double f442 = 39.3750 * sini2 * sini2;
                double f522 = 9.84375 * sinim * (sini2 * (1.0 - 2.0 * cosim - 5.0 * cosisq)
                        + 0.33333333 * (-2.0 + 4.0 * cosim + 6.0 * cosisq));
                double f523 = sinim * (4.92187512 * sini2 * (-2.0 - 4.0 * cosim + 10.0 * cosisq)
                        + 6.56250012 * (1.0 + 2.0 * cosim - 3.0 * cosisq));
                double f542 = 29.53125 * sinim * (2.0 - 8.0 * cosim + cosisq * (-12.0 + 8.0 * cosim + 10.0 * cosisq));
                double f543 = 29.53125 * sinim * (-2.0 - 8.0 * cosim + cosisq * (12.0 + 8.0 * cosim - 10.0 * cosisq));
                double xno2 = nm * nm;
                double ainv2 = aonv * aonv;
                double temp1 = 3.0 * xno2 * ainv2;
                double temp = temp1 * root22;
                d2201 = temp * f220 * g201;
                d2211 = temp * f221 * g211;
                temp1 = temp1 * aonv;
                temp = temp1 * root32;
                d3210 = temp * f321 * g310;
                d3222 = temp * f322 * g322;
                temp1 = temp1 * aonv;
                temp = 2.0 * temp1 * root44;
                d4410 = temp * f441 * g410;
                d4422 = temp * f442 * g422;
                temp1 = temp1 * aonv;
                temp = temp1 * root52;
                d5220 = temp * f522 * g520;
                d5232 = temp * f523 * g532;
                temp = 2.0 * temp1 * root54;
                d5421 = temp * f542 * g521;
                d5433 = temp * f543 * g533;
                xlamo = pmod(mo + nodeo + nodeo - theta - theta, TWOPI);
                xfact = mdot + dmdt + 2.0 * (nodedot + dnodt - rptim) - noUnkozai;
                em = emo;
                emsq = emsqo;
            }
            if (irez == 1) {
                double g200 = 1.0 + emsq * (-2.5 + 0.8125 * emsq);
                double g310 = 1.0 + 2.0 * emsq;
                double g300 = 1.0 + emsq * (-6.0 + 6.60937 * emsq);
                double f220 = 0.75 * (1.0 + cosim) * (1.0 + cosim);
                double f311 = 0.9375 * sinim * sinim * (1.0 + 3.0 * cosim) - 0.75 * (1.0 + cosim);
                double f330 = 1.0 + cosim;
                f330 = 1.875 * f330 * f330 * f330;
                del1 = 3.0 * nm * nm * aonv * aonv;
                del2 = 2.0 * del1 * f220 * g200 * q22;
                del3 = 3.0 * del1 * f330 * g300 * q33 * aonv;
                del1 = del1 * f311 * g310 * q31 * aonv;
                xlamo = pmod(mo + nodeo + argpo - theta, TWOPI);
                xfact = mdot + xpidot - rptim + dmdt + domdt + dnodt - noUnkozai;
            }
            xli = xlamo;
            xni = noUnkozai;
            atime = 0.0;
            nm = noUnkozai + dndt;
        }
        m[0] = em;
        m[1] = argpm;
        m[2] = inclm;
        m[3] = mm;
        m[4] = nm;
        m[5] = nodem;
        return dndt;
    }

    /**
     * Deep-space secular and resonance effects (dspace), integrated in 720-minute steps.
     * m = {em, argpm, inclm, mm, nm, nodem} in and out.
     */
    private void dspace(double tc, double[] m) {
        final double fasx2 = 0.13130908, fasx4 = 2.8843198, fasx6 = 0.37448087, g22 = 5.7686396, g32 = 0.95240898,
                g44 = 1.8014998, g52 = 1.0508330, g54 = 4.4108898, rptim = 4.37526908801129966e-3, stepp = 720.0,
                stepn = -720.0, step2 = 259200.0;
        double em = m[0], argpm = m[1], inclm = m[2], mm = m[3], nm = m[4], nodem = m[5];
        double dndt = 0.0;
        double theta = pmod(gsto + tc * rptim, TWOPI);
        em = em + dedt * t;
        inclm = inclm + didt * t;
        argpm = argpm + domdt * t;
        nodem = nodem + dnodt * t;
        mm = mm + dmdt * t;

        double ft = 0.0;
        if (irez != 0) {
            if (atime == 0.0 || t * atime <= 0.0 || Math.abs(t) < Math.abs(atime)) {
                atime = 0.0;
                xni = noUnkozai;
                xli = xlamo;
            }
            double delt = t > 0.0 ? stepp : stepn;
            double xndt = 0, xldot = 0, xnddt = 0;
            boolean again = true;
            while (again) {
                if (irez != 2) {
                    xndt = del1 * Math.sin(xli - fasx2) + del2 * Math.sin(2.0 * (xli - fasx4))
                            + del3 * Math.sin(3.0 * (xli - fasx6));
                    xldot = xni + xfact;
                    xnddt = del1 * Math.cos(xli - fasx2) + 2.0 * del2 * Math.cos(2.0 * (xli - fasx4))
                            + 3.0 * del3 * Math.cos(3.0 * (xli - fasx6));
                    xnddt = xnddt * xldot;
                } else {
                    double xomi = argpo + argpdot * atime;
                    double x2omi = xomi + xomi;
                    double x2li = xli + xli;
                    xndt = d2201 * Math.sin(x2omi + xli - g22) + d2211 * Math.sin(xli - g22)
                            + d3210 * Math.sin(xomi + xli - g32) + d3222 * Math.sin(-xomi + xli - g32)
                            + d4410 * Math.sin(x2omi + x2li - g44) + d4422 * Math.sin(x2li - g44)
                            + d5220 * Math.sin(xomi + xli - g52) + d5232 * Math.sin(-xomi + xli - g52)
                            + d5421 * Math.sin(xomi + x2li - g54) + d5433 * Math.sin(-xomi + x2li - g54);
                    xldot = xni + xfact;
                    xnddt = d2201 * Math.cos(x2omi + xli - g22) + d2211 * Math.cos(xli - g22)
                            + d3210 * Math.cos(xomi + xli - g32) + d3222 * Math.cos(-xomi + xli - g32)
                            + d5220 * Math.cos(xomi + xli - g52) + d5232 * Math.cos(-xomi + xli - g52)
                            + 2.0 * (d4410 * Math.cos(x2omi + x2li - g44) + d4422 * Math.cos(x2li - g44)
                            + d5421 * Math.cos(xomi + x2li - g54) + d5433 * Math.cos(-xomi + x2li - g54));
                    xnddt = xnddt * xldot;
                }
                if (Math.abs(t - atime) >= stepp) {
                    again = true;
                } else {
                    ft = t - atime;
                    again = false;
                }
                if (again) {
                    xli = xli + xldot * delt + xndt * step2;
                    xni = xni + xndt * delt + xnddt * step2;
                    atime = atime + delt;
                }
            }
            nm = xni + xndt * ft + xnddt * ft * ft * 0.5;
            double xl = xli + xldot * ft + xndt * ft * ft * 0.5;
            if (irez != 1) {
                mm = xl - 2.0 * nodem + 2.0 * theta;
                dndt = nm - noUnkozai;
            } else {
                mm = xl - nodem - argpm + theta;
                dndt = nm - noUnkozai;
            }
            nm = noUnkozai + dndt;
        }
        m[0] = em;
        m[1] = argpm;
        m[2] = inclm;
        m[3] = mm;
        m[4] = nm;
        m[5] = nodem;
    }

    // -------------------------------------------------------------------------------------------- init

    /** Greenwich mean sidereal time (radians) as SGP4 uses it, from the UT1 Julian date. */
    public static double gstime(double jdut1) {
        double tut1 = (jdut1 - 2451545.0) / 36525.0;
        double temp = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1
                + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841;
        temp = pmod(temp * DEG2RAD / 240.0, TWOPI);
        if (temp < 0.0) temp += TWOPI;
        return temp;
    }

    private void init(int whichconst, double xndot, double xnddot, double xbstar, double xecco, double xargpo,
                      double xinclo, double xmo, double xnoKozai, double xnodeo) {
        final double temp4 = 1.5e-12;
        gravity(whichconst);
        error = 0;
        bstar = xbstar;
        ndot = xndot;
        nddot = xnddot;
        ecco = xecco;
        argpo = xargpo;
        inclo = xinclo;
        mo = xmo;
        noKozai = xnoKozai;
        nodeo = xnodeo;
        double epoch = jdEpoch - 2433281.5;

        double ss = 78.0 / radiusearthkm + 1.0;
        double qzms2ttemp = (120.0 - 78.0) / radiusearthkm;
        double qzms2t = qzms2ttemp * qzms2ttemp * qzms2ttemp * qzms2ttemp;
        init = 'y';
        t = 0.0;

        // initl
        double eccsq = ecco * ecco;
        double omeosq = 1.0 - eccsq;
        double rteosq = Math.sqrt(omeosq);
        double cosio = Math.cos(inclo);
        double cosio2 = cosio * cosio;
        double ak = Math.pow(xke / noKozai, X2O3);
        double d1 = 0.75 * j2 * (3.0 * cosio2 - 1.0) / (rteosq * omeosq);
        double del = d1 / (ak * ak);
        double adel = ak * (1.0 - del * del - del * (1.0 / 3.0 + 134.0 * del * del / 81.0));
        del = d1 / (adel * adel);
        noUnkozai = noKozai / (1.0 + del);
        double ao = Math.pow(xke / noUnkozai, X2O3);
        double sinio = Math.sin(inclo);
        double po = ao * omeosq;
        double con42 = 1.0 - 5.0 * cosio2;
        con41 = -con42 - cosio2 - cosio2;
        double posq = po * po;
        double rp = ao * (1.0 - ecco);
        method = 'n';
        if (opsmode == 'a') {
            double ts70 = epoch - 7305.0;
            double ds70 = Math.floor(ts70 + 1.0e-8);
            double tfrac = ts70 - ds70;
            double c1 = 1.72027916940703639e-2, thgr70 = 1.7321343856509374, fk5r = 5.07551419432269442e-15;
            double c1p2p = c1 + TWOPI;
            gsto = pmod(thgr70 + c1 * ds70 + c1p2p * tfrac + ts70 * ts70 * fk5r, TWOPI);
            if (gsto < 0.0) gsto = gsto + TWOPI;
        } else {
            gsto = gstime(epoch + 2433281.5);
        }

        a = Math.pow(noUnkozai * tumin, -2.0 / 3.0);
        alta = a * (1.0 + ecco) - 1.0;
        altp = a * (1.0 - ecco) - 1.0;

        if (omeosq >= 0.0 || noUnkozai >= 0.0) {
            isimp = 0;
            if (rp < 220.0 / radiusearthkm + 1.0) isimp = 1;
            double sfour = ss;
            double qzms24 = qzms2t;
            double perige = (rp - 1.0) * radiusearthkm;
            if (perige < 156.0) {
                sfour = perige - 78.0;
                if (perige < 98.0) sfour = 20.0;
                double qzms24temp = (120.0 - sfour) / radiusearthkm;
                qzms24 = qzms24temp * qzms24temp * qzms24temp * qzms24temp;
                sfour = sfour / radiusearthkm + 1.0;
            }
            double pinvsq = 1.0 / posq;
            double tsi = 1.0 / (ao - sfour);
            eta = ao * ecco * tsi;
            double etasq = eta * eta;
            double eeta = ecco * eta;
            double psisq = Math.abs(1.0 - etasq);
            double coef = qzms24 * Math.pow(tsi, 4.0);
            double coef1 = coef / Math.pow(psisq, 3.5);
            double cc2 = coef1 * noUnkozai * (ao * (1.0 + 1.5 * etasq + eeta * (4.0 + etasq))
                    + 0.375 * j2 * tsi / psisq * con41 * (8.0 + 3.0 * etasq * (8.0 + etasq)));
            cc1 = bstar * cc2;
            double cc3 = 0.0;
            if (ecco > 1.0e-4) cc3 = -2.0 * coef * tsi * j3oj2 * noUnkozai * sinio / ecco;
            x1mth2 = 1.0 - cosio2;
            cc4 = 2.0 * noUnkozai * coef1 * ao * omeosq * (eta * (2.0 + 0.5 * etasq) + ecco * (0.5 + 2.0 * etasq)
                    - j2 * tsi / (ao * psisq) * (-3.0 * con41 * (1.0 - 2.0 * eeta + etasq * (1.5 - 0.5 * eeta))
                    + 0.75 * x1mth2 * (2.0 * etasq - eeta * (1.0 + etasq)) * Math.cos(2.0 * argpo)));
            cc5 = 2.0 * coef1 * ao * omeosq * (1.0 + 2.75 * (etasq + eeta) + eeta * etasq);
            double cosio4 = cosio2 * cosio2;
            double temp1 = 1.5 * j2 * pinvsq * noUnkozai;
            double temp2 = 0.5 * temp1 * j2 * pinvsq;
            double temp3 = -0.46875 * j4 * pinvsq * pinvsq * noUnkozai;
            mdot = noUnkozai + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13.0 - 78.0 * cosio2 + 137.0 * cosio4);
            argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7.0 - 114.0 * cosio2 + 395.0 * cosio4)
                    + temp3 * (3.0 - 36.0 * cosio2 + 49.0 * cosio4);
            double xhdot1 = -temp1 * cosio;
            nodedot = xhdot1 + (0.5 * temp2 * (4.0 - 19.0 * cosio2) + 2.0 * temp3 * (3.0 - 7.0 * cosio2)) * cosio;
            double xpidot = argpdot + nodedot;
            omgcof = bstar * cc3 * Math.cos(argpo);
            xmcof = 0.0;
            if (ecco > 1.0e-4) xmcof = -X2O3 * coef * bstar / eeta;
            nodecf = 3.5 * omeosq * xhdot1 * cc1;
            t2cof = 1.5 * cc1;
            if (Math.abs(cosio + 1.0) > 1.5e-12) xlcof = -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / (1.0 + cosio);
            else xlcof = -0.25 * j3oj2 * sinio * (3.0 + 5.0 * cosio) / temp4;
            aycof = -0.5 * j3oj2 * sinio;
            double delmotemp = 1.0 + eta * Math.cos(mo);
            delmo = delmotemp * delmotemp * delmotemp;
            sinmao = Math.sin(mo);
            x7thm1 = 7.0 * cosio2 - 1.0;

            if (TWOPI / noUnkozai >= 225.0) {
                method = 'd';
                isimp = 1;
                double tc = 0.0;
                double inclm = inclo;
                Ds s = dscom(epoch, ecco, argpo, tc, inclo, nodeo, noUnkozai);
                double[] e = {ecco, inclo, nodeo, argpo, mo};
                dpper(inclm, init, e);
                ecco = e[0];
                inclo = e[1];
                nodeo = e[2];
                argpo = e[3];
                mo = e[4];
                double[] m = {s.em, 0.0, inclm, 0.0, s.nm, 0.0};
                dsinit(s, tc, xpidot, eccsq, m);
            }

            if (isimp != 1) {
                double cc1sq = cc1 * cc1;
                d2 = 4.0 * ao * tsi * cc1sq;
                double temp = d2 * tsi * cc1 / 3.0;
                d3 = (17.0 * ao + sfour) * temp;
                d4 = 0.5 * temp * ao * tsi * (221.0 * ao + 31.0 * sfour) * cc1;
                t3cof = d2 + 2.0 * cc1sq;
                t4cof = 0.25 * (3.0 * d3 + cc1 * (12.0 * d2 + 10.0 * cc1sq));
                t5cof = 0.2 * (3.0 * d4 + 12.0 * cc1 * d3 + 6.0 * d2 * d2 + 15.0 * cc1sq * (2.0 * d2 + cc1sq));
            }
        }
        propagate(0.0, new double[3], new double[3]);
        init = 'n';
    }

    // -------------------------------------------------------------------------------------------- propagate

    /**
     * Position r (km) and velocity v (km/s) in TEME, {@code tsince} minutes after the epoch. Returns false (and
     * sets {@link #error}) if the model fails there (decayed, or the elements make no sense).
     */
    public boolean propagate(double tsince, double[] r, double[] v) {
        final double temp4 = 1.5e-12;
        double mrt;
        double vkmpersec = radiusearthkm * xke / 60.0;
        t = tsince;
        error = 0;

        double xmdf = mo + mdot * t;
        double argpdf = argpo + argpdot * t;
        double nodedf = nodeo + nodedot * t;
        double argpm = argpdf;
        double mm = xmdf;
        double t2 = t * t;
        double nodem = nodedf + nodecf * t2;
        double tempa = 1.0 - cc1 * t;
        double tempe = bstar * cc4 * t;
        double templ = t2cof * t2;

        if (isimp != 1) {
            double delomg = omgcof * t;
            double delmtemp = 1.0 + eta * Math.cos(xmdf);
            double delm = xmcof * (delmtemp * delmtemp * delmtemp - delmo);
            double temp = delomg + delm;
            mm = xmdf + temp;
            argpm = argpdf - temp;
            double t3 = t2 * t;
            double t4 = t3 * t;
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4;
            tempe = tempe + bstar * cc5 * (Math.sin(mm) - sinmao);
            templ = templ + t3cof * t3 + t4 * (t4cof + t * t5cof);
        }
        double nm = noUnkozai;
        double em = ecco;
        double inclm = inclo;
        if (method == 'd') {
            double tc = t;
            double[] m = {em, argpm, inclm, mm, nm, nodem};
            dspace(tc, m);
            em = m[0];
            argpm = m[1];
            inclm = m[2];
            mm = m[3];
            nm = m[4];
            nodem = m[5];
        }
        if (nm <= 0.0) {
            error = 2;
            return false;
        }
        double am = Math.pow(xke / nm, X2O3) * tempa * tempa;
        nm = xke / Math.pow(am, 1.5);
        em = em - tempe;
        if (em >= 1.0 || em < -0.001) {
            error = 1;
            return false;
        }
        if (em < 1.0e-6) em = 1.0e-6;
        mm = mm + noUnkozai * templ;
        double xlm = mm + argpm + nodem;

        nodem = nodem % TWOPI; // C fmod
        argpm = pmod(argpm, TWOPI);
        xlm = pmod(xlm, TWOPI);
        mm = pmod(xlm - argpm - nodem, TWOPI);

        double sinim = Math.sin(inclm);
        double cosim = Math.cos(inclm);

        double ep = em;
        double xincp = inclm;
        double argpp = argpm;
        double nodep = nodem;
        double mp = mm;
        double sinip = sinim;
        double cosip = cosim;
        if (method == 'd') {
            double[] e = {ep, xincp, nodep, argpp, mp};
            dpper(inclo, 'n', e);
            ep = e[0];
            xincp = e[1];
            nodep = e[2];
            argpp = e[3];
            mp = e[4];
            if (xincp < 0.0) {
                xincp = -xincp;
                nodep = nodep + PI;
                argpp = argpp - PI;
            }
            if (ep < 0.0 || ep > 1.0) {
                error = 3;
                return false;
            }
        }
        if (method == 'd') {
            sinip = Math.sin(xincp);
            cosip = Math.cos(xincp);
            aycof = -0.5 * j3oj2 * sinip;
            if (Math.abs(cosip + 1.0) > 1.5e-12) xlcof = -0.25 * j3oj2 * sinip * (3.0 + 5.0 * cosip) / (1.0 + cosip);
            else xlcof = -0.25 * j3oj2 * sinip * (3.0 + 5.0 * cosip) / temp4;
        }
        double axnl = ep * Math.cos(argpp);
        double temp = 1.0 / (am * (1.0 - ep * ep));
        double aynl = ep * Math.sin(argpp) + temp * aycof;
        double xl = mp + argpp + nodep + temp * xlcof * axnl;

        // Kepler's equation
        double u = pmod(xl - nodep, TWOPI);
        double eo1 = u;
        double tem5 = 9999.9;
        int ktr = 1;
        double sineo1 = 0, coseo1 = 0;
        while (Math.abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = Math.sin(eo1);
            coseo1 = Math.cos(eo1);
            tem5 = 1.0 - coseo1 * axnl - sineo1 * aynl;
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5;
            if (Math.abs(tem5) >= 0.95) tem5 = tem5 > 0.0 ? 0.95 : -0.95;
            eo1 = eo1 + tem5;
            ktr = ktr + 1;
        }
        double ecose = axnl * coseo1 + aynl * sineo1;
        double esine = axnl * sineo1 - aynl * coseo1;
        double el2 = axnl * axnl + aynl * aynl;
        double pl = am * (1.0 - el2);
        if (pl < 0.0) {
            error = 4;
            return false;
        }
        double rl = am * (1.0 - ecose);
        double rdotl = Math.sqrt(am) * esine / rl;
        double rvdotl = Math.sqrt(pl) / rl;
        double betal = Math.sqrt(1.0 - el2);
        temp = esine / (1.0 + betal);
        double sinu = am / rl * (sineo1 - aynl - axnl * temp);
        double cosu = am / rl * (coseo1 - axnl + aynl * temp);
        double su = Math.atan2(sinu, cosu);
        double sin2u = (cosu + cosu) * sinu;
        double cos2u = 1.0 - 2.0 * sinu * sinu;
        temp = 1.0 / pl;
        double temp1 = 0.5 * j2 * temp;
        double temp2 = temp1 * temp;
        if (method == 'd') {
            double cosisq = cosip * cosip;
            con41 = 3.0 * cosisq - 1.0;
            x1mth2 = 1.0 - cosisq;
            x7thm1 = 7.0 * cosisq - 1.0;
        }
        mrt = rl * (1.0 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u;
        su = su - 0.25 * temp2 * x7thm1 * sin2u;
        double xnode = nodep + 1.5 * temp2 * cosip * sin2u;
        double xinc = xincp + 1.5 * temp2 * cosip * sinip * cos2u;
        double mvt = rdotl - nm * temp1 * x1mth2 * sin2u / xke;
        double rvdot = rvdotl + nm * temp1 * (x1mth2 * cos2u + 1.5 * con41) / xke;

        double sinsu = Math.sin(su);
        double cossu = Math.cos(su);
        double snod = Math.sin(xnode);
        double cnod = Math.cos(xnode);
        double sini = Math.sin(xinc);
        double cosi = Math.cos(xinc);
        double xmx = -snod * cosi;
        double xmy = cnod * cosi;
        double ux = xmx * sinsu + cnod * cossu;
        double uy = xmy * sinsu + snod * cossu;
        double uz = sini * sinsu;
        double vx = xmx * cossu - cnod * sinsu;
        double vy = xmy * cossu - snod * sinsu;
        double vz = sini * cossu;

        double mr = mrt * radiusearthkm;
        r[0] = mr * ux;
        r[1] = mr * uy;
        r[2] = mr * uz;
        v[0] = (mvt * ux + rvdot * vx) * vkmpersec;
        v[1] = (mvt * uy + rvdot * vy) * vkmpersec;
        v[2] = (mvt * uz + rvdot * vz) * vkmpersec;
        if (mrt < 1.0) {
            error = 6;
            return false;
        }
        return true;
    }

    /** Position and velocity at a Julian date (UTC). */
    public boolean at(double jdUtc, double[] r, double[] v) {
        return propagate((jdUtc - jdEpoch) * 1440.0, r, v);
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%s (%s) epoch JD %.6f, %.2f min period", name, satnum, jdEpoch, periodMinutes());
    }
}
