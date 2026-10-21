package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Sgp4;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * SGP4 against the published verification set: the element sets of SGP4-VER.TLE propagated over the times
 * each one lists, against Vallado's results in tcppver.out (both from python-sgp4's repository). Also the OMM
 * path: every TLE turned into OMM fields (epoch as an ISO date, mean motion in revolutions per day, angles
 * in degrees) must give the same positions.
 * <p>
 * Usage: SatTest dir-with-SGP4-VER.TLE-and-tcppver.out
 */
public final class SatTest {
    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        List<String[]> tles = new ArrayList<String[]>();
        BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream(new File(dir, "SGP4-VER.TLE")), "US-ASCII"));
        String l, prev = null;
        while ((l = in.readLine()) != null) {
            if (l.startsWith("#")) continue;
            if (l.startsWith("1 ")) prev = l;
            else if (l.startsWith("2 ") && prev != null) {
                tles.add(new String[]{prev, l});
                prev = null;
            }
        }
        in.close();
        // expected: per satellite number, rows of tsince x y z vx vy vz
        Map<String, List<double[]>> expect = new HashMap<String, List<double[]>>();
        List<String> order = new ArrayList<String>();
        in = new BufferedReader(new InputStreamReader(new FileInputStream(new File(dir, "tcppver.out")), "US-ASCII"));
        List<double[]> cur = null;
        while ((l = in.readLine()) != null) {
            String t = l.trim();
            if (t.isEmpty()) continue;
            if (t.endsWith("xx")) {
                String num = t.substring(0, t.length() - 2).trim();
                cur = new ArrayList<double[]>();
                expect.put(num, cur);
                order.add(num);
                continue;
            }
            String[] f = t.split("\\s+");
            if (cur == null || f.length < 7) continue;
            double[] row = new double[7];
            boolean ok = true;
            for (int i = 0; i < 7; i++) {
                try {
                    row[i] = Double.parseDouble(f[i]);
                } catch (NumberFormatException e) {
                    ok = false;
                }
            }
            if (ok) cur.add(row);
        }
        in.close();

        int sats = 0, rows = 0, bad = 0;
        double worstR = 0, worstV = 0, worstOmm = 0;
        String worstSat = "";
        for (String[] tle : tles) {
            Sgp4 s = Sgp4.fromTle("", tle[0], tle[1]);
            String num = s.satnum.replaceFirst("^0+(?=.)", "");
            List<double[]> rowsOf = expect.get(num);
            if (rowsOf == null) {
                System.out.println("no expected results for " + s.satnum);
                continue;
            }
            Sgp4 o = omm(tle[0], tle[1]);
            sats++;
            double[] r = new double[3], v = new double[3], ro = new double[3], vo = new double[3];
            double satWorst = 0;
            for (double[] e : rowsOf) {
                boolean ok = s.propagate(e[0], r, v);
                rows++;
                if (!ok && s.error != 6) {
                    bad++;
                    System.out.printf(Locale.US, "%s at %.1f min: error %d%n", s.satnum, e[0], s.error);
                    continue;
                }
                double dr = Math.sqrt(sq(r[0] - e[1]) + sq(r[1] - e[2]) + sq(r[2] - e[3]));
                double dv = Math.sqrt(sq(v[0] - e[4]) + sq(v[1] - e[5]) + sq(v[2] - e[6]));
                satWorst = Math.max(satWorst, dr);
                if (dr > worstR) {
                    worstR = dr;
                    worstSat = s.satnum + " at " + e[0];
                }
                worstV = Math.max(worstV, dv);
                if (o.propagate(e[0], ro, vo) || o.error == 6) {
                    worstOmm = Math.max(worstOmm, Math.sqrt(sq(ro[0] - r[0]) + sq(ro[1] - r[1]) + sq(ro[2] - r[2])));
                }
            }
            System.out.printf(Locale.US, "%-6s %3d steps, period %7.1f min: worst %.2e km%n", s.satnum, rowsOf.size(),
                    s.periodMinutes(), satWorst);
        }
        System.out.printf(Locale.US, "%d satellites, %d steps (%d failed): worst position %.3e km (%s), velocity %.3e km/s; "
                + "OMM vs TLE %.3e km%n", sats, rows, bad, worstR, worstSat, worstV, worstOmm);
        boolean pass = sats >= 30 && worstR < 1e-3 && worstV < 1e-6 && worstOmm < 1e-3;
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    static double sq(double x) {
        return x * x;
    }

    /** The TLE turned into OMM fields, then read back through the OMM path. */
    static Sgp4 omm(String l1, String l2) {
        int yy = Integer.parseInt(l1.substring(18, 20).trim());
        double days = Double.parseDouble(l1.substring(20, 32).trim());
        int year = yy < 57 ? yy + 2000 : yy + 1900;
        // day of year -> calendar date and time, to the microsecond, as CelesTrak writes it
        double jd = Sgp4.jday(year, 1, 1, 0, 0, 0) - 1 + days;
        long micros = Math.round((days - Math.floor(days)) * 86400e6);
        int doy = (int) Math.floor(days);
        int[] md = monthDay(year, doy);
        long sec = micros / 1000000, us = micros % 1000000;
        String iso = String.format(Locale.US, "%04d-%02d-%02dT%02d:%02d:%02d.%06d", year, md[0], md[1], sec / 3600, (sec / 60) % 60,
                sec % 60, us);
        double ndot = Double.parseDouble(l1.substring(33, 43).trim());
        double nddot = Double.parseDouble(l1.charAt(44) + "." + l1.substring(45, 50).trim()) * Math.pow(10, Integer.parseInt(l1.substring(50, 52).trim()));
        double bstar = Double.parseDouble(l1.charAt(53) + "." + l1.substring(54, 59).trim()) * Math.pow(10, Integer.parseInt(l1.substring(59, 61).trim()));
        Sgp4 s = Sgp4.fromOmm("", l1.substring(2, 7), iso, Double.parseDouble(l2.substring(52, 63).trim()),
                Double.parseDouble("0." + l2.substring(26, 33).replace(' ', '0')), Double.parseDouble(l2.substring(8, 16).trim()),
                Double.parseDouble(l2.substring(17, 25).trim()), Double.parseDouble(l2.substring(34, 42).trim()),
                Double.parseDouble(l2.substring(43, 51).trim()), bstar, ndot, nddot);
        if (Math.abs(s.jdEpoch - jd) > 1e-9) System.out.printf(Locale.US, "OMM epoch off by %.3g days%n", s.jdEpoch - jd);
        return s;
    }

    static int[] monthDay(int year, int doy) {
        int[] len = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
        if ((year % 4 == 0 && year % 100 != 0) || year % 400 == 0) len[1] = 29;
        int m = 0;
        while (m < 11 && doy > len[m]) {
            doy -= len[m];
            m++;
        }
        return new int[]{m + 1, doy};
    }
}
