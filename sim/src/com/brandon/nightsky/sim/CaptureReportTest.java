package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Astro;
import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.SessionSky;
import com.brandon.nightsky.processing.SkySolver;

import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;

/**
 * The report card on what the capture saw, without images: frames on a timeline with gaps and the capture's notes
 * (pauses, the camera failing or stalling, frames dropped, why it ended early, star trails). Each gap must be put
 * down to its real cause, with the right fix, and a session cut short must say why.
 * <p>
 * Usage: CaptureReportTest
 */
public final class CaptureReportTest {
    static int failed;

    public static void main(String[] args) {
        PostProcessor.Output o0;
        // a pause while the phone was picked up: "moved", not "frames dropped"
        ReportCard r = card(frames(11, 16, 3, 25), "capture.pauses", "1", "capture.pausedMs", "6000");
        expect("pause", r, "moved", "dropped", "camera");
        // the camera went quiet and was restarted: "camera", not "close other apps"
        r = card(frames(11, 16, 4, 47), "capture.stalls", "1", "capture.stalledMs", "47000");
        expect("stall", r, "camera", "dropped", "moved");
        title("stall", r, "camera", "The camera had trouble");
        evidence("stall", r, "camera", "stopped sending frames once until the app restarted it");
        // frames the camera failed or lost
        r = card(frames(11, 16, 3, 32), "capture.framesFailed", "2");
        expect("failed", r, "camera", "dropped");
        evidence("failed", r, "camera", "lost 2 frames");
        // frames processing had no room for: those are the phone keeping up
        r = card(frames(11, 16, 3, 16), "capture.framesDropped", "1");
        expect("dropped", r, "dropped", "camera");
        fix("dropped", r, "dropped", "Close other apps");
        // dropped frames that cost no time: no "0 seconds weren't exposed"
        r = card(frames(11, 16, -1, 0), "capture.framesDropped", "2");
        noteHas("dropped, no gap", r, "2 frames dropped while the phone caught up.");
        noteLacks("dropped, no gap", r, "0 seconds");
        // the camera stopped for good: the session ended early and says why, as the camera's problem
        r = card(frames(5, 16, -1, 0), "capture.stalls", "5", "capture.stalledMs", "235000", "capture.framesPlanned", "11",
                "capture.durationSec", "180", "capture.stopKey", "camera", "capture.stopReason", "the camera stopped responding");
        expect("camera stopped", r, "camera");
        title("camera stopped", r, "camera", "The camera stopped");
        evidence("camera stopped", r, "camera", "The camera stopped responding, so the session stopped with 1 min 20 s of the planned 3 min captured (5 of 11 frames).");
        impact("camera stopped", r, "camera", 6 / 11.0);
        tip("camera stopped", r, "Restart the phone");
        // it faltered (a gap), then stopped: one problem, both said
        r = card(frames(8, 16, 3, 47), "capture.stalls", "6", "capture.stalledMs", "282000", "capture.framesPlanned", "11",
                "capture.durationSec", "180", "capture.stopKey", "camera", "capture.stopReason", "the camera stopped responding");
        count("faltered then stopped", r, "camera", 1);
        evidence("faltered then stopped", r, "camera", "until the app restarted it");
        evidence("faltered then stopped", r, "camera", "The camera stopped responding, so the session stopped with 2 min 8 s of the planned 3 min captured (8 of 11 frames).");
        impact("faltered then stopped", r, "camera", 3 / 11.0);
        // another app took the camera
        r = card(frames(3, 16, -1, 0), "capture.framesPlanned", "11", "capture.durationSec", "180", "capture.stopKey", "taken",
                "capture.stopReason", "the camera was taken by another app or the system");
        title("taken", r, "camera", "Another app took the camera");
        // the battery ran low
        r = card(frames(4, 16, -1, 0), "capture.framesPlanned", "11", "capture.durationSec", "180", "capture.stopKey", "battery",
                "capture.stopReason", "the battery fell to 4% (sessions stop below 5%)");
        expect("battery", r, "battery");
        evidence("battery", r, "battery", "The battery fell to 4% (sessions stop below 5%), so the session stopped with 1 min 4 s of the planned 3 min captured (4 of 11 frames).");
        spoken("battery", r, "The battery ran low, so the session ended early.");
        // too hot: the heat's own problem says it ended the session
        r = card(frames(6, 16, -1, 0), "capture.thermalMax", "4", "capture.framesPlanned", "11", "capture.durationSec", "180",
                "capture.stopKey", "hot", "capture.stopReason", "the phone got critically hot");
        count("hot", r, "thermal", 1);
        evidence("hot", r, "thermal", "The phone reported it was critically hot. That ended the session with 1 min 36 s of the planned 3 min captured (6 of 11 frames).");
        // stopped by hand, or at its end time: a note, not a problem
        r = card(frames(4, 16, -1, 0), "capture.framesPlanned", "11", "capture.durationSec", "180", "capture.stopKey", "you",
                "capture.stopReason", "stopped by you");
        expect("stopped by you", r, null);
        noteHas("stopped by you", r, "Stopped by you after 1 min 4 s (4 of 11 frames).");
        r = card(frames(11, 16, -1, 0), "capture.framesPlanned", "40", "capture.durationSec", "640", "capture.stopKey", "end",
                "capture.stopReason", "it reached its end time, 5:00 AM");
        expect("end time", r, null);
        noteHas("end time", r, "Stopped as planned: it reached its end time, 5:00 AM.");
        // the capture runs on until it has its planned frames: a gap it made up cost time, not light (a note, not a problem)
        r = card(frames(11, 16, 4, 47), "capture.stalls", "1", "capture.stalledMs", "47000", "capture.framesPlanned", "11");
        expect("stall made up", r, null);
        noteHas("stall made up", r, "The camera stopped sending frames once until the app restarted it; the session ran 46 seconds longer to get all its frames.");
        r = card(frames(11, 16, 3, 25), "capture.pauses", "1", "capture.pausedMs", "6000", "capture.framesPlanned", "11");
        expect("pause made up", r, null);
        noteHas("pause made up", r, "The capture paused once when the phone was picked up or moved; the session ran 24 seconds longer to get all its frames.");
        r = card(frames(11, 16, 3, 32), "capture.framesFailed", "2", "capture.framesPlanned", "11");
        expect("failed made up", r, null);
        spoken("failed made up", r, "A clear, steady session.");
        // stopped by hand after a stall: the stall cost the frames it would have held, out of those planned
        r = card(frames(4, 16, 1, 47), "capture.stalls", "1", "capture.stalledMs", "47000", "capture.framesPlanned", "11",
                "capture.durationSec", "180", "capture.stopKey", "you", "capture.stopReason", "stopped by you");
        expect("stall then stopped", r, "camera");
        impact("stall then stopped", r, "camera", 46.05 / 16 / 11);
        evidence("stall then stopped", r, "camera", "so 46 seconds of the session weren't exposed.");
        noteHas("stall then stopped", r, "Stopped by you after 1 min 51 s (4 of 11 frames).");
        // the battery ran out after a pause: each has its own share of the frames not got, together all of them
        r = card(frames(4, 16, 1, 47), "capture.pauses", "1", "capture.pausedMs", "40000", "capture.framesPlanned", "11",
                "capture.durationSec", "180", "capture.stopKey", "battery", "capture.stopReason", "the battery fell to 4% (sessions stop below 5%)");
        impact("paused then battery", r, "moved", 46.05 / 16 / 11);
        impact("paused then battery", r, "battery", (7 - 46.05 / 16) / 11);
        // two frames to a unit (frames combined when the camera can't expose long enough): frames as stacked
        r = card(frames(5, 16, -1, 0), "capture.framesPlanned", "22", "capture.framesPerUnit", "2", "capture.durationSec", "180",
                "capture.stopKey", "you", "capture.stopReason", "stopped by you");
        noteHas("combined frames", r, "Stopped by you after 1 min 20 s (5 of 11 frames).");
        // the capture session renewed before the camera's known stop: a note, its short pauses not a problem
        o0 = frames(40, 16, 19, 1.0);
        r = card(o0, "capture.renewals", "1", "capture.renewAfter", "20", "capture.framesPlanned", "40");
        expect("renewed", r, null);
        noteHas("renewed", r, "The camera got a fresh capture session once (every 20 frames), as it stops after a few more in a row: "
                + "a pause of about a second each time.");
        // ... and a stall on top of that is still the camera's trouble
        r = card(frames(30, 16, 25, 47), "capture.renewals", "1", "capture.renewAfter", "20", "capture.framesPlanned", "40",
                "capture.stalls", "1", "capture.stalledMs", "47000", "capture.durationSec", "640", "capture.stopKey", "you",
                "capture.stopReason", "stopped by you");
        expect("renewed and stalled", r, "camera");
        evidence("renewed and stalled", r, "camera", "stopped sending frames once until the app restarted it");
        // ... and learned from this session's own stop: told with that stop (a note, as the frames were made up)
        r = card(frames(40, 16, 17, 49), "capture.renewals", "1", "capture.renewAfter", "17", "capture.renewLearned", "20",
                "capture.framesPlanned", "40", "capture.stalls", "1", "capture.stalledMs", "47000");
        expect("learned and renewed", r, null);
        noteContains("learned and renewed", r, "The camera stopped sending frames once until the app restarted it; the session ran ");
        noteContains("learned and renewed", r, " seconds longer to get all its frames. It had stopped after 20 frames in a row, so from "
                + "then on it got a fresh capture session every 17 frames (once), a pause of about a second each time.");
        check("learned and renewed: one note", r.notes.toString().indexOf("fresh capture session") == r.notes.toString().lastIndexOf(
                "fresh capture session"), r.notes.toString());
        // the check warned of a smudge: the report says so only if the frames bear it out, well clear of a clean lens
        PostProcessor.Output o = halos(frames(11, 16, -1, 0), 0.38);
        r = card(o, "preflight.lensWarned", "true", "preflight.haloLimit", "0.11", "preflight.haloClean", "0.005");
        expect("smudge borne out", r, "smudge");
        o = halos(frames(11, 16, -1, 0), 0.03);
        r = card(o, "preflight.lensWarned", "true", "preflight.haloLimit", "0.11", "preflight.haloClean", "0.005");
        expect("smudge not borne out", r, null);
        // a last unit short of its frames (stopped part way through it): shorter, not a brighter sky
        o = frames(5, 16, -1, 0);
        o.frames.get(4).exposure = 4;
        o.frames.get(4).noise = 7.4;
        r = card(o);
        check("short unit", r.frames[4].reason.equals("shorter (4 s, the others 16 s)"), r.frames[4].score + " " + r.frames[4].reason);
        // star trails: frames, not "0 frames"; no noise gain or lining up; said as trails
        o = frames(8, 16, -1, 0);
        o.trails = true;
        o.frames.get(5).used = false;
        o.frames.get(5).reason = "moved";
        o.frames.get(5).disturbed = true;
        r = card(o, "mode", "trails");
        check("trails headline", r.headline.startsWith("8 frames: 7 used fully, 1 rejected"), r.headline);
        check("trails gain", r.gain.isEmpty(), r.gain);
        check("trails registration", r.registration.isEmpty(), r.registration);
        spokenStarts("trails", r, "Done. Star trails from 7 frames.");
        o = frames(8, 16, -1, 0);
        o.trails = true;
        r = card(o, "mode", "trails");
        check("trails tip", r.tip.contains("longer session"), r.tip);
        check("trails spoken", r.spoken.equals("Done. Star trails from 8 frames."), r.spoken);
        // deep sky says "an 8-minute exposure", "an 80-second" (it's spoken aloud)
        r = card(frames(30, 16, -1, 0));
        check("deep spoken", r.spoken.startsWith("Done. 30 good frames, like an 8-minute exposure."), r.spoken);
        r = card(frames(5, 16, -1, 0));
        check("deep spoken 80 s", r.spoken.startsWith("Done. 5 good frames, like an 80-second exposure."), r.spoken);
        r = card(frames(19, 16, -1, 0));
        check("deep spoken 5 min", r.spoken.startsWith("Done. 19 good frames, like a 5-minute exposure."), r.spoken);
        // frames added up from shorter exposures: said, with why
        r = card(frames(11, 16, -1, 0), "capture.framesPerUnit", "4", "frameExposureNs", "4000000000", "capture.framesDone", "44",
                "capture.shortWhy", "The camera cut the 16-second exposures to 4 seconds");
        noteHas("combined", r, "The camera cut the 16-second exposures to 4 seconds, so each frame here is four 4-second exposures "
                + "added together (44 in all).");
        expect("combined", r, null);
        r = card(frames(11, 16, -1, 0), "capture.framesPerUnit", "2", "frameExposureNs", "8000000000", "capture.framesDone", "22",
                "capture.shortWhy", "Exposures of 16 seconds kept failing, even the longest the camera admits to");
        noteHas("halved", r, "Exposures of 16 seconds kept failing, even the longest the camera admits to, so each frame here is two "
                + "8-second exposures added together (22 in all).");
        r = card(frames(11, 16, -1, 0), "capture.framesPerUnit", "1", "frameExposureNs", "16000000000", "capture.framesDone", "11");
        noteLacks("not combined", r, "added together");
        // a focus move cut short and checked again straight away is one check, said once
        r = card(frames(19, 16, -1, 0), "focus.how", "swept", "focus.value", "0.35", "focus.checks", "20,0.350,0.553;20,0.553,0.756;20,0.756,0.830");
        noteContains("focus steps", r, "the focus was checked once: it moved from 0.350 to 0.830, a step at a time, after 20 minutes.");
        r = card(frames(19, 16, -1, 0), "focus.how", "swept", "focus.value", "0.35", "focus.checks", "12,0.350,0.350;25,0.350,0.450");
        noteContains("focus checks", r, "the focus was checked 2 times: it moved from 0.350 to 0.450 after 25 minutes.");
        // moonlight is judged by the sky as measured: a Moon that's up but hidden lights nothing here
        long moonMs = moonHigh();
        r = moonCard(moonMs, 21.9);
        expect("hidden moon", r, null);
        boolean hid = false;
        for (String s : r.notes) hid |= s.contains("didn't brighten the sky here, so something hid it");
        check("hidden moon: note", hid, r.notes.toString());
        r = moonCard(moonMs, 18.0);
        expect("moonlit sky", r, "moon");
        evidence("moonlit sky", r, "moon", "times brighter than it would have been without it");
        spoken("moonlit sky", r, "Moonlight brightened the sky.");
        // a bright sky at a site known to be that bright without the Moon: little of it was moonlight
        r = moonCard(moonMs, 19.0);
        double unknownSite = find(r, "moon") != null ? find(r, "moon").impact : 0;
        r = moonCard(moonMs, 19.0, "site.skyMag", "19.2");
        ReportCard.Problem lit = find(r, "moon");
        check("moon at a bright site", lit != null && lit.impact < 0.25 && lit.impact < unknownSite - 0.3,
                String.format(Locale.US, "%.2f against %.2f", lit == null ? 0 : lit.impact, unknownSite));
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** {@code n} frames of {@code exp} seconds back to back, with a gap of {@code gap} seconds after frame {@code at} (-1: none). */
    static PostProcessor.Output frames(int n, double exp, int at, double gap) {
        PostProcessor.Output o = new PostProcessor.Output();
        double t = exp / 2;
        for (int i = 0; i < n; i++) {
            PostProcessor.FrameReport f = new PostProcessor.FrameReport();
            f.index = i;
            f.t = t;
            f.exposure = exp;
            f.used = true;
            f.noise = 3.7;
            f.hfr = 1.3;
            o.frames.add(f);
            t += exp + 0.05 + (i == at ? gap : 0);
        }
        o.unitsTotal = n;
        o.unitsUsed = n;
        o.integrationSeconds = n * exp;
        o.summary = String.format(Locale.US, "Used %d of %d frames.", n, n);
        return o;
    }

    /** The first hour of 2026 (from New Year) when the Moon stands over 45 degrees up at 40 N, 75 W, over 85% lit. */
    static long moonHigh() {
        double lat = 40 * Astro.DEG, lon = -75 * Astro.DEG;
        for (long ms = 1767225600000L; ; ms += 3_600_000L) {
            double jd = Astro.jdUtc(ms / 1000.0), jdTt = Astro.jdTt(jd);
            double[] m = Astro.moonOfDate(jdTt);
            double alt = Astro.altitude(Astro.topocentric(m, m[3], jd, lat, lon)) / Astro.DEG;
            if (alt > 45 && Astro.moonIllumination(jdTt) > 0.85) return ms;
        }
    }

    /** 19 frames at {@code utcMs} from 40 N, 75 W, the camera at the celestial pole, the sky measuring {@code skyMag}. */
    static ReportCard moonCard(long utcMs, double skyMag, String... kv) {
        PostProcessor.Output o = frames(19, 16, -1, 0);
        double fx = 1437.5, pix = 206264.806 / fx, zp = 25;
        for (PostProcessor.FrameReport f : o.frames) {
            f.timestampNs = (long) ((f.t - f.exposure / 2) * 1e9);
            f.background = 4 * pix * pix * Math.pow(10, -0.4 * (skyMag - zp)) / f.exposure;
        }
        o.referenceUnit = 9;
        SkySolver.Solution s = new SkySolver.Solution();
        s.R = new double[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        s.model = CameraModel.nominal(2040, 1536, fx);
        s.zeroPoint = zp;
        s.matched = 300;
        o.solution = s;
        ReportCard.Input in = new ReportCard.Input();
        in.out = o;
        in.props = new Properties();
        for (int i = 0; i + 1 < kv.length; i += 2) in.props.setProperty(kv[i], kv[i + 1]);
        in.zone = TimeZone.getTimeZone("America/New_York");
        in.sky = new SessionSky();
        in.sky.latDeg = 40;
        in.sky.lonDeg = -75;
        in.sky.clockValid = true;
        in.sky.clockWallMs = utcMs;
        return ReportCard.build(in);
    }

    /** Every frame's halo ratio (the glow around its stars) set to {@code h}. */
    static PostProcessor.Output halos(PostProcessor.Output o, double h) {
        for (PostProcessor.FrameReport f : o.frames) f.halo = h;
        return o;
    }

    static ReportCard card(PostProcessor.Output o, String... kv) {
        ReportCard.Input in = new ReportCard.Input();
        in.out = o;
        in.props = new Properties();
        for (int i = 0; i + 1 < kv.length; i += 2) in.props.setProperty(kv[i], kv[i + 1]);
        in.zone = TimeZone.getTimeZone("America/New_York");
        return ReportCard.build(in);
    }

    static ReportCard.Problem find(ReportCard r, String key) {
        for (ReportCard.Problem p : r.problems) if (p.key.equals(key)) return p;
        return null;
    }

    /** The first problem is {@code first} (null: none at all), and none of {@code not} is there. */
    static void expect(String name, ReportCard r, String first, String... not) {
        String keys = keys(r);
        if (first == null) check(name + ": no problems", r.problems.isEmpty(), keys);
        else check(name + ": " + first + " first", !r.problems.isEmpty() && r.problems.get(0).key.equals(first), keys);
        for (String k : not) check(name + ": no " + k, find(r, k) == null, keys);
    }

    static void count(String name, ReportCard r, String key, int k) {
        int c = 0;
        for (ReportCard.Problem p : r.problems) if (p.key.equals(key)) c++;
        check(name + ": " + k + " " + key, c == k, keys(r));
    }

    static void title(String name, ReportCard r, String key, String t) {
        ReportCard.Problem p = find(r, key);
        check(name + ": title", p != null && p.title.equals(t), p == null ? keys(r) : p.title);
    }

    static void evidence(String name, ReportCard r, String key, String part) {
        ReportCard.Problem p = find(r, key);
        check(name + ": evidence", p != null && p.evidence.contains(part), p == null ? keys(r) : p.evidence);
    }

    static void fix(String name, ReportCard r, String key, String part) {
        ReportCard.Problem p = find(r, key);
        check(name + ": fix", p != null && p.fix.contains(part), p == null ? keys(r) : p.fix);
    }

    static void impact(String name, ReportCard r, String key, double v) {
        ReportCard.Problem p = find(r, key);
        check(name + ": impact", p != null && Math.abs(p.impact - v) < 0.01, p == null ? keys(r) : Double.toString(p.impact));
    }

    static void tip(String name, ReportCard r, String part) {
        check(name + ": tip", r.tip.contains(part), r.tip);
    }

    static void spoken(String name, ReportCard r, String part) {
        check(name + ": spoken", r.spoken.contains(part), r.spoken);
    }

    static void spokenStarts(String name, ReportCard r, String start) {
        check(name + ": spoken", r.spoken.startsWith(start), r.spoken);
    }

    static void noteHas(String name, ReportCard r, String note) {
        boolean found = false;
        for (String s : r.notes) found |= s.equals(note);
        for (ReportCard.Problem p : r.problems) found |= p.evidence.equals(note);
        check(name + ": note", found, r.notes.toString());
    }

    static void noteContains(String name, ReportCard r, String part) {
        boolean found = false;
        for (String s : r.notes) found |= s.contains(part);
        check(name + ": note", found, r.notes.toString());
    }

    static void noteLacks(String name, ReportCard r, String part) {
        boolean found = false;
        for (String s : r.notes) found |= s.contains(part);
        check(name + ": no note with \"" + part + "\"", !found, r.notes.toString());
    }

    static String keys(ReportCard r) {
        StringBuilder sb = new StringBuilder("problems:");
        for (ReportCard.Problem p : r.problems) sb.append(' ').append(p.key).append(String.format(Locale.US, "(%.2f)", p.impact));
        return sb.toString();
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "ok   " : "FAIL ") + what + (ok ? "" : ": " + detail));
        if (!ok) failed++;
    }
}
