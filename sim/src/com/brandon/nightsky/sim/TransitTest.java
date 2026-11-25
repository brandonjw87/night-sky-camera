package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.MoonGeo;
import com.brandon.nightsky.processing.SatSky;
import com.brandon.nightsky.processing.Sgp4;
import com.brandon.nightsky.processing.TransitFinder;

import java.util.List;
import java.util.Locale;

/**
 * The Transit Hunter (3.0) checked against a second, independent way of seeing it: for every crossing found, the
 * station is looked at from the ground the slow way (SGP4 to J2000 to east-north-up with the light time, as the
 * satellite overlay does) and the Moon from {@link MoonGeo} (Meeus, without the knots), every 10 ms. From the
 * centre-line point it must pass over the Moon's middle; a step across the strip, just inside its edge it must
 * still cross the Moon and just outside it must miss; the crossing must last as long as it says. Then a place on a
 * centre line asks for its own crossings and must be told it's right there; places just inside and just outside the
 * strip must be told so. A synthetic ISS-like orbit, so it doesn't depend on a download.
 * <p>
 * Usage: TransitTest
 */
public final class TransitTest {
    static int failed;

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    static Sgp4 iss() {
        // 420 km up, 51.64 degrees, as the ISS
        return Sgp4.fromOmm("ISS (ZARYA)", "25544", "2026-12-01T00:00:00", 15.50, 0.0004, 51.64, 120.0, 90.0, 0.0, 0.0001, 0, 0);
    }

    /** The station's least angle from the Moon's middle seen from a place, between two times: {radians, at ms, Moon radius rad, time inside}. */
    static double[] slow(Sgp4 o, double latDeg, double lonDeg, long t0, long t1) {
        double best = 9, at = 0, rho = 0, inside = 0;
        SatSky.Look look = new SatSky.Look();
        SatSky.Site site = new SatSky.Site(Math.toRadians(latDeg), Math.toRadians(lonDeg), 0);
        for (long t = t0; t <= t1; t += 10) {
            double jd = Astro.jdUtc(t / 1000.0);
            if (!SatSky.look(o, -1.8, site.moment(jd), look)) continue;
            MoonGeo.View v = MoonGeo.view(t, latDeg, lonDeg);
            double a = Math.toRadians(v.alt), z = Math.toRadians(v.az);
            double mx = Math.sin(z) * Math.cos(a), my = Math.cos(z) * Math.cos(a), mz = Math.sin(a);
            double c = look.enu[0] * mx + look.enu[1] * my + look.enu[2] * mz;
            double sep = Math.acos(Math.max(-1, Math.min(1, c)));
            rho = Math.toRadians(v.radius);
            if (sep < rho) inside += 0.010;
            if (sep < best) {
                best = sep;
                at = t;
            }
        }
        return new double[]{best, at, rho, inside};
    }

    /** A place {@code km} from (lat, lon) towards {@code bearing} degrees. */
    static double[] offset(double lat, double lon, double km, double bearing) {
        double b = Math.toRadians(bearing);
        double dLat = km * Math.cos(b) / 111.2, dLon = km * Math.sin(b) / (111.2 * Math.cos(Math.toRadians(lat)));
        return new double[]{lat + dLat, lon + dLon};
    }

    public static void main(String[] args) {
        Sgp4 o = iss();
        long from = MoonshotTest.utc(2026, 12, 1, 0, 0), to = from + 10 * 86_400_000L;
        double lat = MoonshotTest.LAT, lon = MoonshotTest.LON;

        System.out.println("== every centre line within 1500 km over ten days");
        long c0 = System.nanoTime();
        List<TransitFinder.Transit> wide = TransitFinder.find(o, 25544, "ISS", TransitFinder.ISS_SPAN_M, lat, lon, 0, from, to, 1500);
        double secs = (System.nanoTime() - c0) / 1e9;
        System.out.println(String.format(Locale.US, "  %d centre lines [%.2f s]", wide.size(), secs));
        check("found several", wide.size() >= 4, wide.size() + "");
        int checkedLine = 0, lineOk = 0, edgeOk = 0, lengthOk = 0;
        for (TransitFinder.Transit tr : wide) {
            if (checkedLine >= 8) break;
            checkedLine++;
            double[] s = slow(o, tr.lat, tr.lon, tr.timeMs - 3000, tr.timeMs + 3000);
            double missLine = s[0] / s[2];
            boolean line = missLine < 0.04 && Math.abs(s[1] - tr.timeMs) < 150;
            if (line) lineOk++;
            double[] in = offset(tr.lat, tr.lon, 0.88 * tr.halfWidthKm, tr.pathDeg + 90);
            double[] out = offset(tr.lat, tr.lon, 1.12 * tr.halfWidthKm, tr.pathDeg + 90);
            double[] sIn = slow(o, in[0], in[1], tr.timeMs - 3000, tr.timeMs + 3000);
            double[] sOut = slow(o, out[0], out[1], tr.timeMs - 3000, tr.timeMs + 3000);
            boolean edge = sIn[0] < sIn[2] && sOut[0] > sOut[2];
            if (edge) edgeOk++;
            boolean length = Math.abs(s[3] - tr.durationS) < Math.max(0.03, 0.06 * tr.durationS);
            if (length) lengthOk++;
            System.out.println(String.format(Locale.US, "  %s: on the line %.3f radii off at %+.0f ms; strip %.1f km wide: %.2f inside, %.2f outside; %.2f s (said %.2f)",
                    new java.util.Date(tr.timeMs), missLine, s[1] - tr.timeMs, 2 * tr.halfWidthKm, sIn[0] / sIn[2], sOut[0] / sOut[2], s[3], tr.durationS)
                    + String.format(Locale.US, " Moon %.0f° up there, range %.0f km, %.0f\"", tr.moonAltLine, tr.rangeKm, tr.sizeArcsec));
        }
        check("the centre line is where the station covers the Moon's middle", lineOk == checkedLine, lineOk + " of " + checkedLine);
        check("the strip's width: crosses just inside its edge, misses just outside", edgeOk == checkedLine, edgeOk + " of " + checkedLine);
        check("the crossing lasts as long as it says", lengthOk == checkedLine, lengthOk + " of " + checkedLine);
        boolean sane = true;
        for (TransitFinder.Transit tr : wide) {
            sane &= tr.moonAltLine >= TransitFinder.MIN_ALT && tr.halfWidthKm > 1 && tr.halfWidthKm < 60 && tr.durationS > 0.1 && tr.durationS < 5
                    && tr.rangeKm > 380 && tr.rangeKm < 2600 && tr.ageDays >= 0 && tr.ageDays < 10.5;
        }
        check("every one sensible (the Moon high enough, strips a few km, under a few seconds)", sane, "");

        System.out.println("== from a place on a centre line, inside and outside the strip");
        TransitFinder.Transit pick = wide.get(0);
        for (TransitFinder.Transit tr : wide) if (tr.moonAltLine > pick.moonAltLine) pick = tr;
        List<TransitFinder.Transit> here = TransitFinder.find(o, 25544, "ISS", TransitFinder.ISS_SPAN_M, pick.lat, pick.lon, 0, from, to, 60);
        TransitFinder.Transit h = null;
        for (TransitFinder.Transit tr : here) if (Math.abs(tr.timeMs - pick.timeMs) < 5000) h = tr;
        check("found it from there", h != null, here.size() + " near there");
        if (h != null) {
            System.out.println("  " + h);
            check("right there: on the line, crossing the Moon's middle", h.distKm < 0.3 && h.missRadii < 0.05 && h.here(),
                    String.format(Locale.US, "%.2f km, %.3f radii", h.distKm, h.missRadii));
            check("the time seen from there is the line's", Math.abs(h.hereMs - h.timeMs) < 200, (h.hereMs - h.timeMs) + " ms");
            check("its length there is the line's", Math.abs(h.hereS - h.durationS) < 0.05, String.format(Locale.US, "%.2f s and %.2f s", h.hereS, h.durationS));
            check("the Moon's height there agrees", Math.abs(h.moonAlt - pick.moonAltLine) < 0.3, String.format(Locale.US, "%.1f and %.1f", h.moonAlt, pick.moonAltLine));
        }
        double[] inP = offset(pick.lat, pick.lon, 0.7 * pick.halfWidthKm, pick.pathDeg - 90);
        double[] outP = offset(pick.lat, pick.lon, 1.4 * pick.halfWidthKm, pick.pathDeg - 90);
        TransitFinder.Transit hi = near(TransitFinder.find(o, 25544, "ISS", 0, inP[0], inP[1], 0, pick.timeMs - 600_000, pick.timeMs + 600_000, 60), pick);
        TransitFinder.Transit ho = near(TransitFinder.find(o, 25544, "ISS", 0, outP[0], outP[1], 0, pick.timeMs - 600_000, pick.timeMs + 600_000, 60), pick);
        check("inside the strip: told it crosses the Moon there", hi != null && hi.here() && Math.abs(hi.missRadii - 0.7) < 0.08
                && Math.abs(hi.distKm - 0.7 * pick.halfWidthKm) < 0.08 * pick.halfWidthKm + 0.2,
                hi == null ? "none" : String.format(Locale.US, "%.2f radii, %.1f km from the line", hi.missRadii, hi.distKm));
        check("outside it: told it misses, and which way the line is", ho != null && !ho.here() && Math.abs(MoonGeo.norm180(ho.bearingDeg - (pick.pathDeg + 90))) < 10,
                ho == null ? "none" : String.format(Locale.US, "%.2f radii, line %.1f km towards %.0f°", ho.missRadii, ho.distKm, ho.bearingDeg));
        if (hi != null && hi.here()) {
            double[] s = slow(o, inP[0], inP[1], pick.timeMs - 3000, pick.timeMs + 3000);
            check("its length there", Math.abs(s[3] - hi.hereS) < Math.max(0.03, 0.08 * hi.hereS), String.format(Locale.US, "%.2f s, said %.2f s", s[3], hi.hereS));
        }

        System.out.println("== ten days near one place: quick enough");
        c0 = System.nanoTime();
        List<TransitFinder.Transit> mine = TransitFinder.find(o, 25544, "ISS", TransitFinder.ISS_SPAN_M, lat, lon, 0, from, to, 60);
        secs = (System.nanoTime() - c0) / 1e9;
        System.out.println(String.format(Locale.US, "  %d within 60 km [%.2f s]", mine.size(), secs));
        for (TransitFinder.Transit tr : mine) System.out.println("  " + tr);
        check("ten days in under 1.5 s here", secs < 1.5, String.format(Locale.US, "%.2f s", secs));
        boolean inWide = true;
        for (TransitFinder.Transit tr : mine) inWide &= near(wide, tr) != null;
        check("the near ones are among the wide search's", inWide, "");

        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        if (failed > 0) System.exit(1);
    }

    static TransitFinder.Transit near(List<TransitFinder.Transit> l, TransitFinder.Transit t) {
        for (TransitFinder.Transit x : l) if (Math.abs(x.timeMs - t.timeMs) < 5000) return x;
        return null;
    }
}
