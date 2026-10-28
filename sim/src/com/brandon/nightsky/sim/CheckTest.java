package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.CameraFacts;
import com.brandon.nightsky.processing.DarkFrames;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * The camera check without a camera: the dark frames measured right (each colour's level, the noise, hot pixels,
 * banding, the read noise from a pair, light reaching the sensor), and what it found said right (the headline, each
 * finding, how often sessions renew the capture, saving and reading it back).
 * <p>
 * Usage: CheckTest
 */
public final class CheckTest {
    static int failed;
    static final float WHITE = 1023, BLACK = 64;

    public static void main(String[] args) {
        darkFrames();
        facts();
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- the measurements ----

    static void darkFrames() {
        int W = 1024, H = 768;
        double[] lv = {64.0, 64.5, 63.5, 64.2};
        // a clean dark frame with 500 hot pixels
        Random rnd = new Random(7);
        short[] px = frame(W, H, lv, 2.0, null, 0, rnd);
        Set<Integer> hot = new HashSet<Integer>();
        while (hot.size() < 500) hot.add(rnd.nextInt(W * H));
        for (int i : hot) px[i] = (short) (px[i] + 200);
        DarkFrames.Frame d = DarkFrames.measure(px, W, H, WHITE, BLACK);
        for (int p = 0; p < 4; p++) near("level " + p, d.level[p], lv[p], 0.51);
        near("noise", d.noise, Math.sqrt(4 + 1 / 12.0), 0.1);
        check("hot pixels", d.hot == 500, Integer.toString(d.hot));
        near("excess", d.excess, 500 * 200.0 / (W * H), 0.05);
        check("no banding", d.rows < 0.15, fmt(d.rows));
        near("lit share, covered", DarkFrames.litShare(d, WHITE, BLACK), (d.mean() - BLACK) / (WHITE - BLACK), 1e-9);
        check("covered isn't lit", DarkFrames.litShare(d, WHITE, BLACK) <= DarkFrames.LIT_SHARE, fmt(DarkFrames.litShare(d, WHITE, BLACK)));
        // banding: each row off by its own amount
        double[] rowOff = new double[H];
        for (int y = 0; y < H; y++) rowOff[y] = 1.5 * rnd.nextGaussian();
        d = DarkFrames.measure(frame(W, H, lv, 2.0, rowOff, 0, rnd), W, H, WHITE, BLACK);
        near("banding", d.rows, 1.5, 0.2);
        // banding a third of a level: too fine for whole-number medians, not for the rows' means
        for (int y = 0; y < H; y++) rowOff[y] = 0.3 * rnd.nextGaussian();
        d = DarkFrames.measure(frame(W, H, lv, 2.0, rowOff, 0, rnd), W, H, WHITE, BLACK);
        near("fine banding", d.rows, 0.3, 0.08);
        // a pair with the same fixed pattern: the pair's read noise leaves it out, a single frame's spread doesn't
        double[] fixed = new double[W * H];
        for (int i = 0; i < fixed.length; i++) fixed[i] = 3 * rnd.nextGaussian();
        short[] a = frame(W, H, lv, 1.5, null, 0, rnd), b = frame(W, H, lv, 1.5, null, 0, rnd);
        for (int i = 0; i < fixed.length; i++) {
            a[i] = (short) Math.round((a[i] & 0xFFFF) + fixed[i]);
            b[i] = (short) Math.round((b[i] & 0xFFFF) + fixed[i]);
        }
        double rn = DarkFrames.pairNoise(a, b, W, H);
        near("read noise from the pair", rn, Math.sqrt(1.5 * 1.5 + 1 / 12.0), 0.12);
        d = DarkFrames.measure(a, W, H, WHITE, BLACK);
        check("one frame's spread has the pattern", d.noise > 3.0, fmt(d.noise));
        // light reaching the sensor: 5% of the range over black, and it isn't all "hot"
        double[] lit = new double[4];
        for (int p = 0; p < 4; p++) lit[p] = lv[p] + 0.05 * (WHITE - BLACK);
        d = DarkFrames.measure(frame(W, H, lit, 5.0, null, 0, rnd), W, H, WHITE, BLACK);
        check("lit", DarkFrames.litShare(d, WHITE, BLACK) > DarkFrames.LIT_SHARE, fmt(DarkFrames.litShare(d, WHITE, BLACK)));
        check("lit frame isn't hot", d.hot < 10, Integer.toString(d.hot));
        // the Pixel's whole RAW frame, timed
        int FW = 4080, FH = 3072;
        short[] big = frame(FW, FH, lv, 2.0, null, 0, rnd);
        long t0 = System.nanoTime();
        DarkFrames.Frame bd = DarkFrames.measure(big, FW, FH, WHITE, BLACK);
        long t1 = System.nanoTime();
        DarkFrames.pairNoise(big, a.length == big.length ? a : big, FW, FH);
        long t2 = System.nanoTime();
        System.out.printf(Locale.US, "info measure %dx%d: %.0f ms, pair noise %.0f ms%n", FW, FH, (t1 - t0) / 1e6, (t2 - t1) / 1e6);
        check("whole frame level", Math.abs(bd.mean() - 64.05) < 0.5, fmt(bd.mean()));
    }

    /** W x H raw values: each 2x2 position's level, Gaussian noise of {@code sigma}, each row's offset (or none). */
    static short[] frame(int W, int H, double[] level, double sigma, double[] rowOff, double fixedSigma, Random rnd) {
        short[] px = new short[W * H];
        for (int y = 0; y < H; y++) {
            double ro = rowOff != null ? rowOff[y] : 0;
            for (int x = 0; x < W; x++) {
                int p = ((y & 1) << 1) | (x & 1);
                long v = Math.round(level[p] + ro + sigma * rnd.nextGaussian() + (fixedSigma > 0 ? fixedSigma * rnd.nextGaussian() : 0));
                px[y * W + x] = (short) Math.max(0, Math.min(65535, v));
            }
        }
        return px;
    }

    // ---- what it found, in words ----

    static void facts() {
        // clean: everything in full, no stop, the dark measured
        CameraFacts f = clean();
        check("clean complete", f.complete(), "");
        same("clean headline", f.headline(), "Ready for the night");
        List<String> l = f.lines();
        same("clean exposure", l.get(0), "Long exposures: 16 seconds, the length nights use here, came out in full.");
        same("clean run", l.get(1), "Frame after frame: 70 1-second frames in a row without a stop, 45 ms between them.");
        same("clean dark", l.get(2), "In the dark (ISO 1600, 31 °C): 1,234 hot pixels in a 16-second exposure (under 0.01% of "
                + "the sensor), which every photo takes out; noise 1.8 levels.");
        check("clean renews never", f.renewAfter() == 0, Integer.toString(f.renewAfter()));
        check("clean no trouble", !f.trouble(), "");
        same("clean log", f.logLine(f.timeMs + 3 * 3_600_000L),
                "camera check 3 hours ago: 16.0 s in full; 70 in a row without a stop; 45 ms between frames; 1234 hot pixels");
        roundTrip("clean", f);

        // stops after 63 in a row, twice, and a fresh capture session gets it going: renewed every 60
        f = clean();
        f.stops = 2;
        f.stopAfter = 63;
        f.stopAgainAfter = 63;
        f.cure = "session";
        f.runAsked = f.runDone = 131;
        check("63 renews", f.renewAfter() == 60, Integer.toString(f.renewAfter()));
        same("63 headline", f.headline(), "Ready: sessions work around what it found");
        same("63 run", f.lines().get(1), "Frame after frame: the camera stopped after 63 frames in a row, and again after 63 more; "
                + "a fresh capture session got it going. Sessions now do that every 60 frames, a pause of about a second instead of "
                + "the minute it takes to notice a stop.");
        same("63 log", f.logLine(f.timeMs + 90_000L),
                "camera check just now: 16.0 s in full; stopped after 63 in a row and 63 (cure: session); 45 ms between frames; 1234 hot pixels");
        contains("63 shared", f.text(), "sessions: a fresh capture session every 60 frames");
        roundTrip("63", f);
        // the same, but only opening the camera again got it going
        f.cure = "reopen";
        contains("63 reopen", f.lines().get(1), "closing and opening the camera got it going. Sessions now do that every 60 frames, "
                + "a pause of about 2 seconds");
        // a second stop sooner than the first: the sooner one counts
        f.cure = "session";
        f.stopAgainAfter = 40;
        check("sooner second stop", f.renewAfter() == 37, Integer.toString(f.renewAfter()));
        // ... unless it's too soon to be a count the camera keeps to
        f.stopAgainAfter = 5;
        check("early second stop", f.renewAfter() == 60, Integer.toString(f.renewAfter()));

        // it stops and nothing gets it going: the camera needs a look
        f = clean();
        f.stops = 3;
        f.stopAfter = 63;
        f.cure = "none";
        f.runDone = 63;
        check("no cure: trouble", f.trouble(), "");
        same("no cure headline", f.headline(), "The camera needs a look");
        check("no cure renews never", f.renewAfter() == 0, Integer.toString(f.renewAfter()));
        contains("no cure run", f.lines().get(1), "the camera stopped once, after 63 frames in a row, and nothing got it going again");

        // a stall after 3 frames: not a count it keeps to, so sessions just ride it out
        f = clean();
        f.stops = 1;
        f.stopAfter = 3;
        f.cure = "session";
        check("early stall renews never", f.renewAfter() == 0, Integer.toString(f.renewAfter()));
        same("early stall headline", f.headline(), "Ready for the night");
        same("early stall run", f.lines().get(1), "Frame after frame: the camera stopped once, after 3 frames in a row; a fresh "
                + "capture session got it going, then 70 1-second frames came in all. A session does that by itself when the camera stalls.");
        f.stopAfter = 0;
        contains("stall at the start", f.lines().get(1), "the camera stopped once, at the start; a fresh capture session got it going");

        // the camera cuts the night's exposure to 4 seconds
        f = clean();
        f.exposures.get(0).gotNs = 4_000_000_000L;
        check("cut", f.cutTo() == 4_000_000_000L, Long.toString(f.cutTo()));
        same("cut headline", f.headline(), "Ready: sessions work around what it found");
        same("cut exposure", f.lines().get(0), "Long exposures: asked for 16 seconds, the camera gave 4 seconds. Sessions now plan "
                + "with that from the start: four 4-second exposures added together make each frame.");
        contains("cut shared", f.text(), "frames of 4.00 s");
        same("cut log", f.logLine(f.timeMs + 50 * 3_600_000L), "camera check 2 days ago: 16.0 s cut to 4.00 s; 70 in a row without a "
                + "stop; 45 ms between frames; 1234 hot pixels");
        // 15.5 of 16 seconds is in full (a camera's own rounding)
        f.exposures.get(0).gotNs = 15_500_000_000L;
        check("rounding is full", f.cutTo() == 0 && f.exposures.get(0).full(), "");

        // the night's exposure fails twice, half as long comes out
        f = clean();
        f.exposures.clear();
        f.exposures.add(exposure(16, 0));
        f.exposures.add(exposure(16, 0));
        f.exposures.add(exposure(8, 8));
        check("failed night", f.nightFailed() && f.longestFull() == 8_000_000_000L, "");
        same("failed night headline", f.headline(), "Ready: sessions work around what it found");
        same("failed night exposure", f.lines().get(0), "Long exposures: 16-second exposures failed, 8-second ones came out in "
                + "full. Sessions try 16 seconds first and switch to shorter ones by themselves when they fail.");
        // ... and so does half as long
        f.exposures.get(2).gotNs = 0;
        check("all failed: trouble", f.trouble(), "");
        same("all failed headline", f.headline(), "The camera needs a look");
        same("all failed exposure", f.lines().get(0), "Long exposures: every long exposure failed, 16 seconds and shorter. "
                + "Restart the phone and check again.");
        roundTrip("all failed", f);

        // light reached the sensor: nothing about the dark, said why
        f = clean();
        f.dark = false;
        f.lit = 0.07;
        same("lit headline", f.headline(), "Ready for the night (the dark wasn't measured)");
        same("lit dark", f.lines().get(2), "In the dark: not measured, as light reached the sensor (7% of full). To measure it, "
                + "check again in a dim room, with the camera against the table or in a drawer.");
        contains("lit shared", f.text(), "light check: 7.0% of the range over black (lit)");
        check("lit shared has no dark", !f.text().contains("dark: black"), f.text());

        // the black level isn't what the camera says
        f = clean();
        for (int p = 0; p < 4; p++) f.black[p] = 66;
        contains("black off", f.lines().get(2), "The black level measured 66.0 against the 64.0 the camera reports.");
        f.black[0] = 64.5;
        f.black[1] = f.black[2] = f.black[3] = 64.2;
        check("black close enough", !f.lines().get(2).contains("black level"), f.lines().get(2));

        // it didn't get through: the run never started; or it stopped after the run, before the long exposure
        f = new CameraFacts();
        f.timeMs = 1_700_000_000_000L;
        f.nightNs = 16_000_000_000L;
        check("nothing done", !f.complete(), "");
        same("nothing headline", f.headline(), "The check didn't finish");
        f = clean();
        f.exposures.clear();
        f.runDone = 40;
        f.stoppedBy = "The camera stopped (another app is using the camera).";
        check("stopped early incomplete", !f.complete(), "");
        same("stopped early first line", f.lines().get(0), "The check stopped early: the camera stopped (another app is using the camera).");
        same("stopped early exposure", f.lines().get(1), "Long exposures: not tried (the check stopped before).");

        // reading back what isn't there
        check("parse empty", CameraFacts.parse("") == null && CameraFacts.parse(null) == null, "");
        check("parse junk", CameraFacts.parse("this isn't it") == null, "");
        check("parse broken", CameraFacts.parse("time=5\nexposures=1,x,3\n") == null, "");

        // words
        same("16 seconds", CameraFacts.seconds(16_000_000_000L), "16 seconds");
        same("1 second", CameraFacts.seconds(1_000_000_000L), "1 second");
        same("2.5 seconds", CameraFacts.seconds(2_500_000_000L), "2.5 seconds");
        same("ago minutes", CameraFacts.ago(5 * 60_000L), "5 minutes ago");
        same("ago hours", CameraFacts.ago(5 * 3_600_000L), "5 hours ago");
        same("ago days", CameraFacts.ago(72 * 3_600_000L), "3 days ago");
    }

    static CameraFacts clean() {
        CameraFacts f = new CameraFacts();
        f.timeMs = 1_761_600_000_000L;
        f.version = "1.3";
        f.phone = "Google Pixel 10 Pro XL, Android 16";
        f.camera = "camera 0";
        f.iso = 1600;
        f.tempC = 31;
        f.lit = 0.001;
        f.dark = true;
        f.nightNs = 16_000_000_000L;
        f.exposures.add(exposure(16, 16));
        f.runExpNs = 1_000_000_000L;
        f.runAsked = 70;
        f.runDone = 70;
        f.gapMs = 45;
        f.gapMaxMs = 61;
        for (int p = 0; p < 4; p++) {
            f.black[p] = 64;
            f.blackReported[p] = 64;
        }
        f.noise = 2.1;
        f.readNoise = 1.8;
        f.profileNoise = 1.7;
        f.rows = 0.3;
        f.hot = 1234;
        f.pixels = 4080L * 3072;
        f.hotExpS = 16;
        f.darkPerS = 0.012;
        return f;
    }

    static CameraFacts.Exposure exposure(double askedS, double gotS) {
        CameraFacts.Exposure e = new CameraFacts.Exposure();
        e.askedNs = Math.round(askedS * 1e9);
        e.gotNs = Math.round(gotS * 1e9);
        e.backMs = Math.round(askedS * 1000) + 900;
        return e;
    }

    static void roundTrip(String name, CameraFacts f) {
        CameraFacts g = CameraFacts.parse(f.encode());
        check(name + ": saved and read back", g != null && g.text().equals(f.text()) && g.renewAfter() == f.renewAfter()
                && g.headline().equals(f.headline()), g == null ? "null" : g.text());
    }

    static void near(String what, double got, double want, double tol) {
        check(what, Math.abs(got - want) <= tol, String.format(Locale.US, "%.4f, wanted %.4f +- %.4f", got, want, tol));
    }

    static void same(String what, String got, String want) {
        check(what, got.equals(want), "\n  got:    " + got + "\n  wanted: " + want);
    }

    static void contains(String what, String got, String part) {
        check(what, got.contains(part), "\n  got: " + got + "\n  lacks: " + part);
    }

    static String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : ": " + detail));
        if (!ok) failed++;
    }
}
