package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.BrightStars;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.JupiterGeo;
import com.brandon.nightsky.processing.RehearsalSky;
import com.brandon.nightsky.processing.Rot;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SkyWindow;
import com.brandon.nightsky.processing.StarDetector;
import com.brandon.nightsky.processing.StarList;
import com.brandon.nightsky.processing.StarLock;

import java.io.File;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Sky Window's sums (3.3), off the phone.
 * <ul>
 * <li>Where the phone points: the camera's and the screen's axes for a phone held up toward the north, true north from a
 * compass with a declination, the screen's pinhole camera there and back, the readings between two of them and how far the
 * phone turned while a frame was exposed.</li>
 * <li>The star lock, on whole 12-megapixel frames of the real sky drawn as the Pixel's main camera would take them in an
 * eighth of a second hand-held ({@link RehearsalSky}, all the catalogue's stars where they are): held up at 20 parts of
 * the sky, low and high, the phone tilted sideways and not, with the compass 4 to 20 degrees off and the tilt a degree or
 * two off; the lock must put the camera within 0.05 degrees of where it truly points, and be refused rather than wrong
 * when it can't (no stars, the compass 60 degrees off, a town's bright sky).</li>
 * <li>What's out there: the Moon and Jupiter where the app's own sums put them, a nebula named once, the Geminids' radiant
 * on their night, rising and setting times, the way to something (the arrow and the words), the camera's frame as a
 * picture (stars bright on a dark sky by night, a photo by day), the exposure by day and night, the light's age.</li>
 * </ul>
 * Usage: WindowTest [work-dir]
 */
public final class WindowTest {
    static int failures;
    static BrightStars catalog;
    /** Somewhere in the eastern US, and an evening the Moon is down there. */
    static final double LAT = 39.0, LON = -77.0;
    static final int SO = 90;

    public static void main(String[] args) throws Exception {
        File work = new File(args.length > 0 ? args[0] : "build/window-test");
        //noinspection ResultOfMethodCallIgnored
        work.mkdirs();
        catalog = SimRunner.loadCatalog();
        pose();
        readings();
        long night = moonless(1795744800000L); // 27 November 2026, 02:00 UTC (9 PM in the eastern US)
        SensorSpec spec = RehearsalTest.spec(1);
        lock(spec, night);
        refused(spec, night);
        town(spec, night);
        shaky(spec, night);
        sky(night);
        passes();
        riseSet();
        guide();
        picture(spec, night);
        exposure();
        words();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILURE(S)");
        if (failures > 0) System.exit(1);
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static long moonless(long t) {
        for (int k = 0; k < 96; k++) {
            double alt = Astro.altitude(Astro.moonEnu(t, LAT * Astro.DEG, LON * Astro.DEG)) / Astro.DEG;
            double sun = Astro.altitude(Astro.sunEnu(t, LAT * Astro.DEG, LON * Astro.DEG)) / Astro.DEG;
            if (alt < -5 && sun < -18) return t;
            t += 1800_000L;
        }
        return t;
    }

    // ------------------------------------------------------------------------------------------------
    // Holding the phone

    /** East-north-up of a compass direction and height (degrees). */
    static double[] enu(double azDeg, double altDeg) {
        double a = Math.toRadians(azDeg), h = Math.toRadians(altDeg);
        return new double[]{Math.cos(h) * Math.sin(a), Math.cos(h) * Math.cos(a), Math.sin(h)};
    }

    /** The phone's axes into east-north-up, held up with its back toward (az, alt) and turned {@code rollDeg} clockwise. */
    static double[] hold(double azDeg, double altDeg, double rollDeg) {
        double[] a = enu(azDeg, altDeg);
        double[] z = {-a[0], -a[1], -a[2]};
        double[] up = {0, 0, 1};
        double d = up[0] * a[0] + up[1] * a[1] + up[2] * a[2];
        double[] y = {up[0] - d * a[0], up[1] - d * a[1], up[2] - d * a[2]};
        if (norm(y) < 1e-6) y = new double[]{0, 1, 0};
        normalize(y);
        // turned about the back's direction: clockwise as the screen is seen (the top toward the right)
        double r = Math.toRadians(rollDeg);
        double[] x0 = cross(y, z);
        double[] y1 = {Math.cos(r) * y[0] + Math.sin(r) * x0[0], Math.cos(r) * y[1] + Math.sin(r) * x0[1], Math.cos(r) * y[2] + Math.sin(r) * x0[2]};
        double[] x = cross(y1, z);
        return new double[]{x[0], y1[0], z[0], x[1], y1[1], z[1], x[2], y1[2], z[2]};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double norm(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    static void normalize(double[] v) {
        double n = norm(v);
        for (int i = 0; i < 3; i++) v[i] /= n;
    }

    static double[] col(double[] m, int j) {
        return new double[]{m[j], m[3 + j], m[6 + j]};
    }

    static double sepDeg(double[] a, double[] b) {
        return Astro.sep(a, b) / Astro.DEG;
    }

    /** A turn about the vertical by {@code deg} (anticlockwise from above), then a tilt of {@code tiltDeg} about east. */
    static double[] error(double deg, double tiltDeg) {
        return Rot.mul(Rot.fromRotVec(0, 0, Math.toRadians(deg)), Rot.fromRotVec(Math.toRadians(tiltDeg), 0, 0));
    }

    static void pose() {
        System.out.println("== where the phone points");
        // upright in portrait, its back toward the northern horizon: right is east, top is up, the screen faces south
        float[] R = {1, 0, 0, 0, 0, -1, 0, 1, 0};
        double[] dev = SkyWindow.deviceToEnu(R, 0);
        double[] cam = SkyWindow.cameraToEnu(dev, SO), view = SkyWindow.viewToEnu(dev);
        check(sepDeg(col(cam, 2), enu(0, 0)) < 1e-9, "the camera looks north, level");
        check(sepDeg(col(cam, 1), enu(270, 0)) < 1e-9 && sepDeg(col(cam, 0), new double[]{0, 0, -1}) < 1e-9,
                "its picture's rows run down the sky and its columns west (a sensor turned 90 degrees)");
        check(sepDeg(col(view, 2), col(cam, 2)) < 1e-9, "the screen looks where the camera looks");
        check(sepDeg(col(view, 0), enu(90, 0)) < 1e-9 && sepDeg(col(view, 1), new double[]{0, 0, -1}) < 1e-9,
                "the screen's right is east and its down is down");
        double[] picUp = {-cam[1], -cam[4], -cam[7]};
        double[] want = new double[3];
        Rot.apply(dev, Math.sin(Math.toRadians(SO)), Math.cos(Math.toRadians(SO)), 0, want);
        check(sepDeg(picUp, want) < 1e-9, "the picture's up is the phone's (sin 90, cos 90) as the Moonshot has it");
        // the compass: magnetic north 10 degrees east of true north
        double[] cam10 = SkyWindow.cameraToEnu(SkyWindow.deviceToEnu(R, 10), SO);
        check(Math.abs(Astro.azimuth(col(cam10, 2)) / Astro.DEG - 10) < 1e-9, "a declination of 10° east puts magnetic north at 10°");
        // the screen's camera there and back
        SkyWindow.View v = new SkyWindow.View(1080, 2400, 1000, SkyWindow.viewToEnu(hold(135, 40, 15)));
        double worst = 0;
        double[] p = new double[2];
        for (int k = 0; k < 50; k++) {
            double sx = 37 + 21.3 * k, sy = 100 + 45.1 * k;
            double[] e = v.enuAt(sx, sy);
            if (!v.project(e, p)) {
                worst = 1e9;
                continue;
            }
            worst = Math.max(worst, Math.hypot(p[0] - sx, p[1] - sy));
        }
        check(worst < 1e-6, String.format(Locale.US, "a screen point to the sky and back (%.1e px)", worst));
        check(sepDeg(v.axis(), enu(135, 40)) < 1e-9, "the window's middle is where the phone's back points");
        double[] zen = new double[2];
        v.project(new double[]{0, 0, 1}, zen);
        check(zen[1] < v.cy, "the zenith is up the screen from the middle");
    }

    static void readings() {
        System.out.println("== the readings");
        SkyWindow.Readings r = new SkyWindow.Readings();
        // turning right at 10 degrees a second, a reading every 10 ms
        long t0 = 5_000_000_000L;
        for (int k = 0; k <= 200; k++) r.add(t0 + k * 10_000_000L, Rot.fromRotVec(0, 0, -Math.toRadians(10 * k * 0.01)));
        double[] mid = r.at(t0 + 1_005_000_000L);
        double want = 10 * 1.005;
        double got = Math.toDegrees(Rot.angle(mid));
        check(Math.abs(got - want) < 1e-3, String.format(Locale.US, "between two readings: %.4f° (%.4f°)", got, want));
        double turn = r.turnDeg(t0 + 500_000_000L, t0 + 625_000_000L);
        check(Math.abs(turn - 1.25) < 0.01, String.format(Locale.US, "turned %.3f° in an eighth of a second at 10°/s", turn));
        check(r.at(t0 - 1) == null, "nothing before the first reading kept");
    }

    // ------------------------------------------------------------------------------------------------
    // The star lock

    /** One hold of the phone, drawn and locked. */
    static final class Shot {
        double az, alt, roll, compass, tilt;
        StarLock.Fix fix;
        int stars;
        double errDeg = Double.NaN;
        long ms;
    }

    static Shot shoot(SensorSpec spec, long t, double az, double alt, double roll, double compassDeg, double tiltDeg, double skyMag,
                      double exposureS, int iso, long seed, boolean drawSky) {
        Shot s = new Shot();
        s.az = az;
        s.alt = alt;
        s.roll = roll;
        s.compass = compassDeg;
        s.tilt = tiltDeg;
        double[] trueDev = hold(az, alt, roll);
        double[] trueCam = SkyWindow.cameraToEnu(trueDev, SO);
        int W = spec.width, H = spec.height;
        short[] raw = new short[W * H];
        RehearsalTest.dark(raw, W, H, iso, seed);
        long mid = t + Math.round(exposureS * 500);
        if (drawSky) {
            RehearsalSky.Setup su = new RehearsalSky.Setup();
            su.spec = spec;
            su.model = spec.model;
            su.catalog = catalog;
            su.latDeg = LAT;
            su.lonDeg = LON;
            su.altM = 100;
            su.camToEnu = trueCam;
            su.startUtcMs = t;
            su.seed = seed;
            su.skyMag = skyMag;
            su.hours = 0.1;
            RehearsalSky rs = RehearsalSky.build(su);
            rs.draw(raw, RehearsalTest.frame(t, exposureS, iso, Double.NaN, seed + 1));
        }
        StarList stars = StarLock.detector(spec).detect(raw, 1f, 400);
        s.stars = stars.count;
        // the sensors: the compass off by compassDeg, the tilt off by tiltDeg
        double[] sensorCam = Rot.mul(error(compassDeg, tiltDeg), trueCam);
        long c0 = System.nanoTime();
        s.fix = StarLock.lock(stars, spec.model, catalog, sensorCam, mid, LAT, LON);
        s.ms = (System.nanoTime() - c0) / 1_000_000L;
        if (s.fix != null) s.errDeg = Math.toDegrees(Rot.angle(Rot.mul(Rot.mul(s.fix.C, sensorCam), Rot.transpose(trueCam))));
        return s;
    }

    static void lock(SensorSpec spec, long t) {
        System.out.println("== the star lock: 20 holds, an eighth of a second at ISO 3200, a suburb's edge");
        double[][] holds = {
                {0, 20, 0, 8, 1}, {45, 35, 10, -12, -1.5}, {90, 50, -15, 5, 2}, {135, 25, 0, 20, 0.5}, {180, 40, 30, -18, -2},
                {225, 60, 0, 10, 1}, {270, 30, -25, -6, 1}, {315, 45, 0, 15, -1}, {20, 70, 5, -9, 2.5}, {200, 80, 0, 12, -1},
                {110, 15, 0, -15, 1}, {160, 55, 40, 4, -0.5}, {250, 65, -10, -20, 1.5}, {300, 20, 0, 7, -2}, {60, 85, 0, 14, 1},
                {340, 35, 20, -11, 0}, {75, 30, 0, 18, 2}, {190, 22, -5, -4, -1}, {240, 45, 0, 9, 3}, {30, 50, 90, -16, -1},
        };
        int locked = 0, wrong = 0;
        double worst = 0;
        long slowest = 0;
        List<String> missed = new ArrayList<String>();
        for (int k = 0; k < holds.length; k++) {
            double[] h = holds[k];
            Shot s = shoot(spec, t + k * 7000L, h[0], h[1], h[2], h[3], h[4], RehearsalSky.SKY_MAG, 0.125, 3200, 100 + k, true);
            String line = String.format(Locale.US, "    az %3.0f alt %2.0f roll %3.0f, compass %+5.1f° tilt %+4.1f°: %3d stars; %s (%d ms)", h[0], h[1],
                    h[2], h[3], h[4], s.stars, s.fix == null ? "no lock" : String.format(Locale.US, "locked on %d, %.3f° off after, %.1f° before (compass %+.1f°)",
                            s.fix.stars, s.errDeg, s.fix.offDeg, s.fix.headingDeg), s.ms);
            System.out.println(line);
            if (s.fix == null) {
                missed.add(String.format(Locale.US, "az %.0f alt %.0f", h[0], h[1]));
                continue;
            }
            locked++;
            slowest = Math.max(slowest, s.ms);
            worst = Math.max(worst, s.errDeg);
            if (s.errDeg > 0.1) wrong++;
        }
        check(wrong == 0, "no lock is wrong");
        check(locked >= 18, String.format(Locale.US, "%d of 20 holds locked%s", locked, missed.isEmpty() ? "" : " (missed " + missed + ")"));
        check(worst < 0.05, String.format(Locale.US, "after the lock the camera is placed within %.3f° (under 0.05°: a screen pixel or two)", worst));
        check(slowest < 1500, String.format(Locale.US, "a lock takes at most %d ms here", slowest));
        // the compass reading back: sensors 11 degrees anticlockwise of the truth, so the camera points 11 clockwise of them
        Shot s = shoot(spec, t + 200_000L, 160, 45, 0, 11, 0, RehearsalSky.SKY_MAG, 0.125, 3200, 77, true);
        check(s.fix != null && Math.abs(s.fix.headingDeg - 11) < 0.3 && s.fix.tiltDeg < 0.3,
                s.fix == null ? "an 11° compass error read back: no lock" : String.format(Locale.US,
                        "sensors 11° anticlockwise of the truth read back as the camera %+.2f° clockwise of them (tilt %.2f°)",
                        s.fix.headingDeg, s.fix.tiltDeg));
    }

    static void refused(SensorSpec spec, long t) {
        System.out.println("== refused rather than wrong");
        Shot dark = shoot(spec, t, 100, 40, 0, 5, 0, RehearsalSky.SKY_MAG, 0.125, 3200, 501, false);
        check(dark.fix == null, "a frame with no sky in it (" + dark.stars + " specks): no lock");
        Shot far = shoot(spec, t, 100, 40, 0, 60, 0, RehearsalSky.SKY_MAG, 0.125, 3200, 502, true);
        check(far.fix == null || far.errDeg < 0.1, "the compass 60° off, past what's searched: "
                + (far.fix == null ? "no lock" : String.format(Locale.US, "locked right anyway (%.3f°)", far.errDeg)));
        Shot tilted = shoot(spec, t, 100, 40, 0, 5, 12, RehearsalSky.SKY_MAG, 0.125, 3200, 503, true);
        check(tilted.fix == null, "gravity 12° off (no phone's is): the lock that would tilt the sky that far is refused");
    }

    static void town(SensorSpec spec, long t) {
        System.out.println("== a town's bright sky (18 magnitudes a square arcsecond)");
        int locked = 0, wrong = 0;
        double[][] holds = {{90, 45, 0}, {200, 60, 10}, {300, 35, -10}, {20, 75, 0}, {150, 30, 0}};
        for (int k = 0; k < holds.length; k++) {
            Shot s = shoot(spec, t + k * 9000L, holds[k][0], holds[k][1], holds[k][2], 9, 1, 18.0, 0.125, 3200, 600 + k, true);
            System.out.println(String.format(Locale.US, "    az %3.0f alt %2.0f: %3d stars, %s", holds[k][0], holds[k][1], s.stars,
                    s.fix == null ? "no lock" : String.format(Locale.US, "locked on %d, %.3f° off", s.fix.stars, s.errDeg)));
            if (s.fix != null) {
                locked++;
                if (s.errDeg > 0.1) wrong++;
            }
        }
        check(wrong == 0, "no lock is wrong under a town's sky");
        check(locked >= 3, locked + " of 5 holds locked under a town's sky");
    }

    /**
     * Hands that shake: the camera turning 0.1 to 0.3 degrees while the frame is exposed (its stars short trails, their
     * middles where the camera pointed mid-exposure), drawn as six shorter exposures along the turn.
     */
    static void shaky(SensorSpec spec, long t) {
        System.out.println("== shaking hands: the phone turning while the eighth of a second is exposed");
        double[][] holds = {{70, 40, 0, 9, 0.10, 0.3}, {150, 55, 15, -14, 0.18, 1.2}, {250, 35, 0, 6, 0.25, 2.0}, {330, 60, -20, 17, 0.30, 2.8}};
        int locked = 0, wrong = 0;
        double worst = 0;
        for (int k = 0; k < holds.length; k++) {
            double[] h = holds[k];
            double[] trueCam = SkyWindow.cameraToEnu(hold(h[0], h[1], h[2]), SO);
            // the turn: about an axis across the view, the frame's middle where the camera points mid-exposure
            double[] across = Rot.mulVec(trueCam, new double[]{Math.cos(h[5]), Math.sin(h[5]), 0});
            int W = spec.width, H = spec.height, n = 6;
            short[] raw = new short[W * H];
            RehearsalTest.dark(raw, W, H, 3200, 900 + k);
            long t0 = t + k * 11000L;
            for (int j = 0; j < n; j++) {
                double ang = Math.toRadians(h[4]) * ((j + 0.5) / n - 0.5);
                double[] turn = Rot.fromRotVec(across[0] * ang, across[1] * ang, across[2] * ang);
                RehearsalSky.Setup su = new RehearsalSky.Setup();
                su.spec = spec;
                su.model = spec.model;
                su.catalog = catalog;
                su.latDeg = LAT;
                su.lonDeg = LON;
                su.altM = 100;
                su.camToEnu = Rot.mul(turn, trueCam);
                su.startUtcMs = t0;
                su.seed = 910 + k * 10 + j;
                su.hours = 0.1;
                RehearsalSky.build(su).draw(raw, RehearsalTest.frame(t0 + j * 125.0 / n, 0.125 / n, 3200, Double.NaN, 920 + k * 10 + j));
            }
            StarList stars = StarLock.detector(spec).detect(raw, 1f, 400);
            double[] sensorCam = Rot.mul(error(h[3], 0.5), trueCam);
            StarLock.Fix fix = StarLock.lock(stars, spec.model, catalog, sensorCam, t0 + 62, LAT, LON);
            double err = fix == null ? Double.NaN : Math.toDegrees(Rot.angle(Rot.mul(Rot.mul(fix.C, sensorCam), Rot.transpose(trueCam))));
            System.out.println(String.format(Locale.US, "    az %3.0f alt %2.0f, turned %.2f° in the exposure (%.0f px), compass %+5.1f°: %3d stars; %s", h[0], h[1],
                    h[4], h[4] * 3600 / 65, h[3], stars.count, fix == null ? "no lock" : String.format(Locale.US, "locked on %d, %.3f° off after", fix.stars, err)));
            if (fix == null) continue;
            locked++;
            worst = Math.max(worst, err);
            if (err > 0.1) wrong++;
        }
        check(wrong == 0, "no lock is wrong with shaking hands");
        check(locked >= 3, locked + " of 4 shaken frames locked (the steadiness the window asks for is under 0.3°)");
        check(worst < 0.06, String.format(Locale.US, "placed within %.3f° of where the camera pointed mid-exposure", worst));
    }

    /** A made-up but proper ISS orbit (51.64°, 15.5 a day), its elements of 26 November 2026. */
    static final String ISS_TLE = "ISS (ZARYA)\n"
            + "1 25544U 98067A   26330.25000000  .00012000  00000-0  21000-3 0  9990\n"
            + "2 25544  51.6380 210.4000 0004500  80.0000 280.1000 15.49800000123452\n";

    /** The space station's passes: each one seen, lit, in a dark sky, as high as said; and placed in the window while it's up. */
    static void passes() {
        System.out.println("== the space station's passes over Washington, DC");
        double lat = 38.8951, lon = -77.0364;
        long from = 1795669200000L; // 26 November 2026, midnight in Washington
        com.brandon.nightsky.processing.SatCatalog cat = new com.brandon.nightsky.processing.SatCatalog();
        int added = cat.add(ISS_TLE, "stations", Astro.jdUtc(from));
        check(added == 1, "the station's orbit read (" + added + ")");
        long c0 = System.nanoTime();
        List<SkyWindow.Pass> ps = SkyWindow.stationPasses(cat.all(), lat, lon, from, 24 * 40);
        long ms = (System.nanoTime() - c0) / 1_000_000L;
        System.out.println(String.format(Locale.US, "    %d passes in the next 40 days, found in %d ms; the first: %s", ps.size(), ms,
                ps.isEmpty() ? "none" : ps.get(0).words(TimeZone.getTimeZone("America/New_York"))));
        check(ps.size() >= 4, ps.size() + " passes seen in 40 days (the station shows in spells of a week or two every month or two)");
        check(ms < 4000, "40 days of passes worked out in " + ms + " ms (a day's in a tenth of that)");
        int bad = 0;
        long prevEnd = 0;
        boolean placed = true;
        com.brandon.nightsky.processing.SatSky.Site site = new com.brandon.nightsky.processing.SatSky.Site(lat * Astro.DEG, lon * Astro.DEG, 0.1);
        com.brandon.nightsky.processing.SatSky.Look a = new com.brandon.nightsky.processing.SatSky.Look();
        for (SkyWindow.Pass p : ps) {
            double sun = Astro.altitude(Astro.sunEnu(p.peakMs, lat * Astro.DEG, lon * Astro.DEG)) / Astro.DEG;
            boolean ok = com.brandon.nightsky.processing.SatSky.look(cat.all().get(0).orbit, cat.all().get(0).stdMag, site.moment(Astro.jdUtc(p.peakMs)), a);
            long min = (p.endMs - p.startMs) / 60000L;
            if (!ok || !a.sunlit || Math.abs(a.altDeg - p.maxAltDeg) > 0.01 || sun > -4 || p.maxAltDeg < 10 || p.mag > 3.5 || min > 8
                    || p.startMs < prevEnd || p.endMs < p.startMs) {
                bad++;
                System.out.println(String.format(Locale.US, "    not as said: %s (lit %b, %.2f° up at its highest against %.2f°, the Sun %.1f°, magnitude %.1f, %d min)",
                        p.words(TimeZone.getTimeZone("America/New_York")), a.sunlit, a.altDeg, p.maxAltDeg, sun, p.mag, min));
            }
            prevEnd = p.endMs;
            // the window places it, up and lit, at its highest
            List<SkyWindow.Item> up = SkyWindow.satellites(cat.all(), lat, lon, p.peakMs);
            SkyWindow.Item iss = up.isEmpty() ? null : up.get(0);
            if (iss == null || iss.norad != 25544 || Math.abs(iss.altDeg - p.maxAltDeg) > 0.2 || iss.enuNext == null
                    || !iss.about.startsWith("the International Space Station")) placed = false;
            else {
                // moving: 0.3 to 1.5 degrees a second when high up (it crosses the sky in a few minutes)
                double rate = sepDeg(iss.enu, iss.enuNext);
                if (p.maxAltDeg > 40 && (rate < 0.3 || rate > 2.0)) placed = false;
            }
        }
        check(bad == 0, bad + " passes not as said (each lit, in a dark sky, at least 10° up, as high as said at its highest, under 8 minutes)");
        check(placed, "the window places the station where the pass is, with people on board, moving across the sky");
        List<SkyWindow.Item> day = SkyWindow.satellites(cat.all(), lat, lon, from + 17 * 3600_000L);
        check(day.isEmpty() || day.get(0).altDeg > 0, "never one below the horizon");
    }

    // ------------------------------------------------------------------------------------------------
    // What's out there

    static void sky(long t) {
        System.out.println("== what's out there");
        SkyWindow.Catalogue cat = new SkyWindow.Catalogue(catalog);
        check(cat.dots.length > 4000 && cat.dots.length < catalog.n, cat.dots.length + " stars to draw as dots");
        SkyWindow.Sky s = SkyWindow.sky(cat, t, LAT, LON, true);
        SkyWindow.Item moon = s.find("the Moon"), jup = s.find("Jupiter");
        double moonAlt = Astro.altitude(Astro.moonEnu(t, LAT * Astro.DEG, LON * Astro.DEG)) / Astro.DEG;
        check(moon != null && Math.abs(moon.altDeg - moonAlt) < 1e-6 && "moon".equals(moon.shoot), "the Moon where the sums put it, ready for the 5x");
        double jAlt = JupiterGeo.altOnly(t, LAT, LON);
        check(jup != null && Math.abs(jup.altDeg - jAlt) < 0.1 && "jupiter".equals(jup.shoot), jup == null ? "Jupiter: missing"
                : String.format(Locale.US, "Jupiter %.2f° up as the Jupiter screen has it (%.2f°)", jup.altDeg, jAlt));
        SkyWindow.Item v1 = s.find("Voyager 1");
        check(v1 != null && v1.story.contains("radio message"), "Voyager 1 there, with how long a message takes");
        int m42 = 0;
        double[] m42d = Astro.vec(83.822 * Astro.DEG, -5.391 * Astro.DEG);
        for (SkyWindow.Item it : s.items) if (it.dir != null && it.kind == SkyWindow.Kind.DEEP && Astro.sep(it.dir, m42d) < Astro.DEG) m42++;
        check(m42 == 1, "the Orion Nebula named once (" + m42 + ")");
        SkyWindow.Item vega = s.find("Vega");
        check(vega != null && vega.story.startsWith("The light you see left it in 20"), vega == null ? "Vega missing" : "Vega: " + vega.story);
        boolean hole = false;
        for (SkyWindow.Item it : s.items) if (it.kind == SkyWindow.Kind.BLACK_HOLE && it.name.equals("Cygnus X-1")) hole = true;
        check(hole, "Cygnus X-1, the first black hole found, marked");
        // the Geminids' peak night, 14 December 2026, 3 AM in the eastern US
        long gem = 1797235200000L;
        SkyWindow.Sky g = SkyWindow.sky(cat, gem, LAT, LON, false);
        SkyWindow.Item rad = g.find("Geminids");
        check(rad != null && rad.about.contains("tonight, its peak") && rad.up(), rad == null ? "the Geminids: missing" : "the Geminids' radiant: " + rad.about
                + String.format(Locale.US, " (%.0f° up)", rad.altDeg));
        double[] castor = Astro.vec(113.65 * Astro.DEG, 31.89 * Astro.DEG);
        check(rad != null && sepDeg(rad.dir, castor) < 4, rad == null ? "" : String.format(Locale.US, "  beside Castor (%.1f°)", sepDeg(rad.dir, castor)));
        List<SkyWindow.Item> tg = SkyWindow.targets(s);
        check(!tg.isEmpty() && tg.get(0).kind == SkyWindow.Kind.MOON && tg.contains(jup), "Find lists the Moon first, and Jupiter");
        // every star in a constellation, even the two thirds the catalogue doesn't say for ("A star in Orion")
        int unsaid = 0, unnamed = 0;
        String sample = "";
        for (int i = 0; i < catalog.n; i++) {
            if (!catalog.con[i].isEmpty()) continue;
            unsaid++;
            String abbr = SkyWindow.constellationOf(catalog, i), name = BrightStars.constellation(abbr);
            // (an unknown abbreviation comes back as it is; Ara and Leo are their own names)
            if (abbr.isEmpty() || name.equals(abbr) && !abbr.equals("Ara") && !abbr.equals("Leo")) unnamed++;
            else if (sample.isEmpty()) sample = name;
        }
        double[] betelgeuse = Astro.vec(88.79 * Astro.DEG, 7.41 * Astro.DEG);
        check(unsaid > 1000 && unnamed == 0, unsaid + " stars the catalogue doesn't place, all placed by the boundaries (" + sample + ", ...)");
        check("Ori".equals(com.brandon.nightsky.processing.Constellations.at(betelgeuse)), "  Betelgeuse's spot is in Orion");
    }

    static void riseSet() {
        System.out.println("== rising and setting");
        TimeZone et = TimeZone.getTimeZone("America/New_York");
        SkyWindow.Catalogue cat = new SkyWindow.Catalogue(catalog);
        // Washington, DC, from midnight of 26 November 2026 (05:00 UTC): NOAA's solar calculator has sunrise at 7:02:45 AM and
        // sunset at 4:47:57 PM
        double lat = 38.8951, lon = -77.0364;
        long midnight = 1795669200000L;
        SkyWindow.Sky s = SkyWindow.sky(cat, midnight, lat, lon, false);
        SkyWindow.RiseSet sun = SkyWindow.riseSet(s.find("the Sun"), midnight, lat, lon);
        String w = SkyWindow.riseSetWords(sun, et);
        System.out.println("    the Sun: " + w);
        long rise = midnight + (7 * 3600 + 2 * 60 + 45) * 1000L, set = midnight + (16 * 3600 + 47 * 60 + 57) * 1000L;
        check(Math.abs(sun.rise - rise) < 2 * 60_000L, String.format(Locale.US, "sunrise within 2 minutes of NOAA's (%+.1f min)", (sun.rise - rise) / 60000.0));
        check(Math.abs(sun.set - set) < 2 * 60_000L, String.format(Locale.US, "sunset within 2 minutes of NOAA's (%+.1f min)", (sun.set - set) / 60000.0));
        check(sun.highAltDeg > 30 && sun.highAltDeg < 32, String.format(Locale.US, "the Sun's highest, %.1f° (90 - 38.9 - 21.2)", sun.highAltDeg));
        SkyWindow.Item jup = s.find("Jupiter");
        SkyWindow.RiseSet j = SkyWindow.riseSet(jup, midnight, lat, lon);
        long jr = JupiterGeo.next(midnight + 12 * 3600_000L, lat, lon, true);
        SkyWindow.RiseSet j2 = SkyWindow.riseSet(jup, midnight + 12 * 3600_000L, lat, lon);
        System.out.println("    Jupiter: " + SkyWindow.riseSetWords(j, et));
        check(j2.rise > 0 && jr > 0 && Math.abs(j2.rise - jr) < 4 * 60_000L, String.format(Locale.US, "Jupiter rises as the Jupiter screen says (%s vs %s)",
                SkyWindow.clock(j2.rise, et), jr > 0 ? SkyWindow.clock(jr, et) : "-"));
        SkyWindow.RiseSet pol = SkyWindow.riseSet(s.find("Polaris"), midnight, lat, lon);
        check(pol.alwaysUp && pol.rise < 0 && pol.set < 0, "the North Star never sets");
    }

    static void guide() {
        System.out.println("== the way to something");
        SkyWindow.View v = new SkyWindow.View(1080, 2400, 900, SkyWindow.viewToEnu(hold(0, 30, 0)));
        SkyWindow.Item t = new SkyWindow.Item("test", SkyWindow.Kind.STAR);
        SkyWindow.Guide right = SkyWindow.guide(v, t, enu(60, 30), 2.5);
        // the shortest way along the sky, which on a screen tipped 30 degrees up bends a little up the screen
        check(right.arrowDeg > 60 && right.arrowDeg < 100 && right.words.startsWith("To the right"), String.format(Locale.US,
                "to the right: arrow %.0f°, \"%s\"", right.arrowDeg, right.words));
        SkyWindow.Guide up = SkyWindow.guide(v, t, enu(0, 75), 2.5);
        check(Math.abs(up.arrowDeg) < 1 || Math.abs(up.arrowDeg - 360) < 1, String.format(Locale.US, "higher: arrow %.0f°, \"%s\"", up.arrowDeg, up.words));
        SkyWindow.Guide back = SkyWindow.guide(v, t, enu(180, 30), 2.5);
        check(back.behind && back.words.startsWith("Turn around"), "behind: \"" + back.words + "\"");
        SkyWindow.Guide got = SkyWindow.guide(v, t, enu(1, 31), 2.5);
        check(got.got && got.onScreen && Math.abs(got.x - v.cx) < 30, "on it: \"" + got.words + "\"");
        SkyWindow.Guide below = SkyWindow.guide(v, t, enu(20, -30), 2.5);
        check(below.below && below.words.contains("through the Earth") && below.arrowDeg > 90 && below.arrowDeg < 180,
                String.format(Locale.US, "below the horizon: arrow %.0f°, \"%s\"", below.arrowDeg, below.words));
    }

    static void picture(SensorSpec spec, long t) {
        System.out.println("== the camera's frame on the screen");
        int W = spec.width, H = spec.height, iso = 3200;
        short[] raw = new short[W * H];
        RehearsalTest.dark(raw, W, H, iso, 9);
        RehearsalSky.Setup su = new RehearsalSky.Setup();
        su.spec = spec;
        su.model = spec.model;
        su.catalog = catalog;
        su.latDeg = LAT;
        su.lonDeg = LON;
        su.camToEnu = SkyWindow.cameraToEnu(hold(30, 50, 0), SO);
        su.startUtcMs = t;
        su.seed = 9;
        su.hours = 0.1;
        RehearsalSky.build(su).draw(raw, RehearsalTest.frame(t, 0.125, iso, Double.NaN, 10));
        int ow = W / 4, oh = H / 4;
        int[] px = new int[ow * oh];
        double[] gains = SkyWindow.daylightGains(spec);
        long c0 = System.nanoTime();
        double med = SkyWindow.picture(ShortBuffer.wrap(raw), W, W, H, spec, 2, gains, 1, px);
        long ms = (System.nanoTime() - c0) / 1_000_000L;
        int[] lum = new int[px.length];
        for (int i = 0; i < px.length; i++) lum[i] = ((px[i] >> 16 & 255) * 3 + (px[i] >> 8 & 255) * 6 + (px[i] & 255)) / 10;
        int[] sorted = lum.clone();
        java.util.Arrays.sort(sorted);
        int median = sorted[sorted.length / 2], top = sorted[sorted.length - 40];
        check(median < 70, String.format(Locale.US, "by night the sky is dark (%d of 255; its glow %.1f levels)", median, med));
        check(top > 200, "and its brightest stars bright (" + top + ")");
        check(ms < 2000, "the picture made in " + ms + " ms");
        // by day: an even grey sky at the exposure's aim
        short[] day = new short[W * H];
        java.util.Arrays.fill(day, (short) (64 + 0.16 * (1023 - 64)));
        SkyWindow.picture(ShortBuffer.wrap(day), W, W, H, spec, 2, new double[]{1, 1, 1}, 0, px);
        int g = px[px.length / 2] >> 8 & 255;
        check(g > 130 && g < 190, "by day the sky at the aim is a photo's light grey (" + g + ")");
    }

    static void exposure() {
        System.out.println("== the exposure");
        long minNs = 10_000L, maxNs = 1_000_000_000L;
        int minIso = 50, maxIso = 3200;
        // by day: the median goes with the light, 0.16 of the range at 1/2000 s at ISO 50
        double k = 0.16 * 959 / (500_000.0 * 50);
        long ns = 33_000_000L;
        int iso = 400;
        for (int i = 0; i < 25; i++) {
            long[] e = SkyWindow.nextExposure(ns, iso, Math.min(959, k * ns * iso), 959, minNs, maxNs, minIso, maxIso);
            ns = e[0];
            iso = (int) e[1];
        }
        double got = k * ns * iso / 959;
        check(Math.abs(got - 0.16) < 0.02 && iso == minIso, String.format(Locale.US, "by day it settles at %.3f of the range (%s at ISO %d)", got,
                ns >= 1_000_000L ? (ns / 1_000_000L) + " ms" : (ns / 1000L) + " µs", iso));
        // at night: as long and as bright as a hand can hold
        double kn = 0.003 * 959 / (125_000_000.0 * 3200);
        for (int i = 0; i < 25; i++) {
            long[] e = SkyWindow.nextExposure(ns, iso, kn * ns * iso, 959, minNs, maxNs, minIso, maxIso);
            ns = e[0];
            iso = (int) e[1];
        }
        check(ns == SkyWindow.MAX_EXPOSURE_NS && iso == maxIso, "at night: an eighth of a second at the sensor's most gain (" + ns / 1_000_000L + " ms, ISO "
                + iso + ")");
        check(SkyWindow.nightness(ns, iso, maxNs, maxIso) > 0.99 && SkyWindow.nightness(500_000L, 50, maxNs, maxIso) < 0.01, "night and day told apart");
    }

    static void words() {
        System.out.println("== the light's age");
        String vega = SkyWindow.lightLeft(25.04, 2026);
        check(vega.equals("The light you see left it in 2001."), vega);
        String bet = SkyWindow.lightLeft(548, 2026);
        check(bet.contains("around 1480") && bet.contains("Columbus"), bet);
        String m31 = SkyWindow.lightLeft(2.5e6, 2026);
        check(m31.contains("2.5 million years ago") && m31.contains("first tools"), m31);
        String m42 = SkyWindow.lightLeft(1344, 2026);
        check(m42.contains("around 680"), m42);
    }
}
