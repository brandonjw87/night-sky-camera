package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.MeteorShowers;
import com.brandon.nightsky.processing.NightPlan;
import com.brandon.nightsky.processing.ReportCard;
import com.brandon.nightsky.processing.Tonight;
import com.brandon.nightsky.remote.JsonTree;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Checks the night's plan (E2) without a phone: tonight's darkness, Moon and best window; a three-session
 * plan run to the end under a sky that clears at 10:35 PM; "only if clear" with the lens covered (it waits,
 * tries again every 10 minutes and gives up at the deadline); repeat tomorrow; and the plan's JSON.
 */
public final class PlanTest {
    static final TimeZone NY = TimeZone.getTimeZone("America/New_York");
    static final double LAT = 39.95, LON = -75.17; // Philadelphia
    static int failures;

    public static void main(String[] args) {
        tonight();
        showers();
        forecast();
        threeSessions();
        lensCovered();
        overlap();
        late();
        cancelRunning();
        untilDawn();
        noPlace();
        preview();
        repeat();
        json();
        // report.json files from ReportTest runs, if given: each must read back as it was written
        for (String a : args) reports(new File(a));
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(NY, Locale.US);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    static String t(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("MMM d HH:mm:ss", Locale.US);
        f.setTimeZone(NY);
        return ms == 0 ? "-" : f.format(new java.util.Date(ms));
    }

    static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    static boolean near(long ms, long want, long tolMs) {
        return Math.abs(ms - want) <= tolMs;
    }

    static void tonight() {
        System.out.println("== tonight, Philadelphia, 22 Oct 2026 from 5 PM");
        Tonight n = Tonight.compute(LAT, LON, at(2026, 10, 22, 17, 0), NY, null, Double.NaN);
        System.out.println("  sunset " + t(n.sunsetMs) + ", dark " + t(n.duskMs) + " to " + t(n.dawnMs) + ", sunrise " + t(n.sunriseMs)
                + ", civil dawn " + t(n.civilDawnMs));
        System.out.println("  moon " + Math.round(100 * n.moonIllum) + "% up at dusk " + n.moonUpAtDusk + " rise " + t(n.moonRiseMs) + " set " + t(n.moonSetMs));
        System.out.println("  " + n.headline);
        for (String l : n.lines) System.out.println("  | " + l);
        // almanac (USNO-style figures for Philadelphia): sunset 6:12 PM, astronomical twilight ends 7:41 PM, begins 5:51 AM
        check(near(n.sunsetMs, at(2026, 10, 22, 18, 12), 4 * 60_000L), "sunset about 6:12 PM");
        check(near(n.duskMs, at(2026, 10, 22, 19, 41), 5 * 60_000L), "full darkness about 7:41 PM");
        check(near(n.dawnMs, at(2026, 10, 23, 5, 51), 5 * 60_000L), "darkness ends about 5:51 AM");
        // waxing gibbous four days before the full Moon of 26 Oct: up at dusk, sets before dawn
        check(n.moonIllum > 0.7 && n.moonIllum < 0.95, "Moon waxing gibbous (" + Math.round(100 * n.moonIllum) + "%)");
        check(n.moonUpAtDusk && n.moonSetMs > n.duskMs && n.moonSetMs < n.dawnMs, "Moon up at dusk and sets in the night");
        check(n.bestStartMs >= n.moonSetMs - 60 * 60_000L && n.bestStartMs > 0, "best window after (or near) moonset: " + t(n.bestStartMs));
        // already dark: the night under way
        Tonight m = Tonight.compute(LAT, LON, at(2026, 10, 23, 1, 0), NY, null, Double.NaN);
        check(m.darkNow && m.dawnMs > at(2026, 10, 23, 1, 0) && near(m.dawnMs, at(2026, 10, 23, 5, 51), 5 * 60_000L),
                "at 1 AM: dark now, until about 5:51 AM (" + t(m.dawnMs) + ")");
        // a far-north summer night has no full darkness
        Tonight s = Tonight.compute(58.0, 10.0, at(2026, 6, 21, 18, 0), TimeZone.getTimeZone("Europe/Oslo"), null, Double.NaN);
        System.out.println("  58N on 21 June: " + s.headline + " | " + s.lines.get(s.lines.size() > 1 ? 1 : 0));
        check(s.duskMs == 0 && s.bestStartMs == 0, "58N midsummer: no full darkness, no window");
    }

    /** Meteor showers on the Tonight card (1.5): the major ones near their peak, the best time, about how many. */
    static void showers() {
        System.out.println("== meteor showers on the Tonight card");
        // the Geminids peak on 14 Dec 2026 near 13:00 UTC: the night of the 13th is theirs, the Moon nearly new
        Tonight g = Tonight.compute(LAT, LON, at(2026, 12, 13, 17, 0), NY, null, Double.NaN);
        for (String l : g.lines) System.out.println("  | " + l);
        Tonight.ShowerNight gem = g.showers.isEmpty() ? null : g.showers.get(0);
        check(gem != null && gem.shower.code.equals("GEM") && gem.line.startsWith("Geminids at their peak tonight: about ")
                        && g.lines.contains(gem.line),
                "13 Dec 2026: the Geminids at their peak" + (gem == null ? "" : ": " + gem.line));
        // their radiant (RA 7h 28m, Dec +33) crosses the meridian at Philadelphia near 2 AM, 83 degrees up
        check(gem != null && near(gem.bestMs, at(2026, 12, 14, 2, 0), 30 * 60_000L) && gem.radiantAlt > 75,
                "best around 2 AM, the radiant high" + (gem == null ? "" : String.format(Locale.US, " (%s, %.0f degrees)", t(gem.bestMs), gem.radiantAlt)));
        // a suburban sky (limiting magnitude about 6.1) shows about half the zenithal hourly rate of 150; the camera
        // only the brightest, about one an hour (a guess good to a factor of two or so)
        check(gem != null && gem.eyeRate > 50 && gem.eyeRate < 110 && gem.cameraRate > 0.3 && gem.cameraRate < 3
                        && gem.line.contains(" The camera catches only the brightest, roughly "),
                "about 75 an hour to the eye, the camera far fewer" + (gem == null ? "" : String.format(Locale.US, " (%.0f, %.2f)", gem.eyeRate, gem.cameraRate)));
        // a dark sky shows more of them, to the eye and to the camera
        Tonight d = Tonight.compute(LAT, LON, at(2026, 12, 13, 17, 0), NY, null, 21.8);
        check(!d.showers.isEmpty() && gem != null && d.showers.get(0).eyeRate > 1.3 * gem.eyeRate
                && d.showers.get(0).cameraRate > gem.cameraRate, "a dark site sees more of them, and so does the camera");
        // the camera's faintest: fainter in a darker sky, brighter for faster meteors (the Perseids against the
        // Geminids) and for ones far from their radiant
        MeteorShowers.Shower gs = MeteorShowers.byCode("GEM"), ps = MeteorShowers.byCode("PER");
        double dark = Tonight.cameraMag(gs, 60, 21.9), suburb = Tonight.cameraMag(gs, 60, 20.4), city = Tonight.cameraMag(gs, 60, 18.5);
        check(dark > suburb && suburb > city && Tonight.cameraMag(ps, 60, 21.9) < dark - 0.3 && Tonight.cameraMag(gs, 20, 21.9) < dark,
                String.format(Locale.US, "the camera's faintest Geminid %.1f under a dark sky, %.1f suburban, %.1f in a city; a Perseid %.1f; "
                        + "a Geminid far from its radiant %.1f", dark, suburb, city, Tonight.cameraMag(ps, 60, 21.9), Tonight.cameraMag(gs, 20, 21.9)));
        // the night before: building, peaking tomorrow night
        Tonight b = Tonight.compute(LAT, LON, at(2026, 12, 12, 17, 0), NY, null, Double.NaN);
        check(!b.showers.isEmpty() && b.showers.get(0).line.startsWith("Geminids, peaking tomorrow night: "), "12 Dec: peaking tomorrow night");
        // a week after the peak: nothing to say about them
        Tonight w = Tonight.compute(LAT, LON, at(2026, 12, 21, 17, 0), NY, null, Double.NaN);
        boolean gemAfter = false;
        for (Tonight.ShowerNight n : w.showers) gemAfter |= n.shower.code.equals("GEM");
        check(!gemAfter, "21 Dec: the Geminids are over");
        // the Perseids' peak, 12-13 Aug 2027 (the Moon a thin crescent): best before dawn, their radiant high in the northeast
        Tonight p = Tonight.compute(LAT, LON, at(2027, 8, 12, 17, 0), NY, null, Double.NaN);
        Tonight.ShowerNight per = p.showers.isEmpty() ? null : p.showers.get(0);
        check(per != null && per.shower.code.equals("PER") && per.bestMs > at(2027, 8, 13, 3, 0) && per.bestMs < at(2027, 8, 13, 5, 0),
                "12 Aug 2027: the Perseids, best before dawn" + (per == null ? "" : ": " + per.line));
        // the southern alpha-Centaurids never rise at Philadelphia
        Tonight a = Tonight.compute(LAT, LON, at(2027, 2, 8, 17, 0), NY, null, Double.NaN);
        boolean ace = false;
        for (Tonight.ShowerNight n : a.showers) ace |= n.shower.code.equals("ACE");
        check(!ace, "8 Feb 2027: the alpha-Centaurids' radiant never rises here");
        // a bright Moon's sky hides most of a shower: the Perseids of 2027 a few nights later are fewer
        Tonight l = Tonight.compute(LAT, LON, at(2027, 8, 16, 17, 0), NY, null, Double.NaN);
        double perLate = 0;
        for (Tonight.ShowerNight n : l.showers) if (n.shower.code.equals("PER")) perLate = n.eyeRate;
        check(per != null && perLate < 0.5 * per.eyeRate, String.format(Locale.US, "16 Aug 2027: past the peak and a waxing Moon, %.0f an hour", perLate));
    }

    static void forecast() {
        System.out.println("== forecast, 8 Nov (new Moon): clear until 1 AM, then cloudy; dew from 3 AM");
        long base = at(2026, 11, 8, 12, 0) / 3600_000L * 3600_000L;
        StringBuilder time = new StringBuilder(), cc = new StringBuilder(), low = new StringBuilder(), mid = new StringBuilder(),
                high = new StringBuilder(), rh = new StringBuilder(), dew = new StringBuilder(), temp = new StringBuilder();
        for (int h = 0; h < 48; h++) {
            long ms = base + h * 3600_000L;
            boolean cloudy = ms >= at(2026, 11, 9, 1, 0) && ms < at(2026, 11, 9, 12, 0);
            boolean dewy = ms >= at(2026, 11, 9, 3, 0) && ms < at(2026, 11, 9, 9, 0);
            String sep = h == 0 ? "" : ",";
            time.append(sep).append(ms / 1000);
            cc.append(sep).append(cloudy ? 95 : 5);
            low.append(sep).append(cloudy ? 90 : 0);
            mid.append(sep).append(cloudy ? 60 : 3);
            high.append(sep).append(h == 5 ? "null" : "10");
            rh.append(sep).append(dewy ? 96 : 70);
            dew.append(sep).append(dewy ? 7.5 : 4.0);
            temp.append(sep).append(dewy ? 8.0 : 11.0);
        }
        String json = "{\"latitude\":39.95,\"longitude\":-75.17,\"hourly_units\":{\"time\":\"unixtime\"},\"hourly\":{\"time\":[" + time
                + "],\"cloud_cover\":[" + cc + "],\"cloud_cover_low\":[" + low + "],\"cloud_cover_mid\":[" + mid
                + "],\"cloud_cover_high\":[" + high + "],\"relative_humidity_2m\":[" + rh + "],\"dew_point_2m\":[" + dew
                + "],\"temperature_2m\":[" + temp + "]}}";
        Tonight.Forecast f = Tonight.Forecast.fromOpenMeteo(json, at(2026, 11, 8, 15, 0));
        Tonight n = Tonight.compute(LAT, LON, at(2026, 11, 8, 17, 0), NY, f, Double.NaN);
        System.out.println("  " + n.headline);
        for (String l : n.lines) System.out.println("  | " + l);
        check(n.hasForecast, "forecast used");
        boolean story = false, dewSeen = false;
        for (String l : n.lines) {
            if (l.startsWith("Clear until 1:00 AM, then cloudy")) story = true;
            if (l.startsWith("Dew likely from 3:00 AM")) dewSeen = true;
        }
        check(story, "cloud story: clear until 1 AM, then cloudy");
        check(dewSeen, "dew from 3 AM");
        check(n.bestStartMs == n.duskMs && n.bestEndMs == at(2026, 11, 9, 1, 0), "best window from full darkness to 1 AM: " + t(n.bestStartMs) + " to " + t(n.bestEndMs));
        check(n.score >= 85, "a clear, moonless evening scores high (" + n.score + ")");
        // overcast all night: no good window, and why
        StringBuilder full = new StringBuilder();
        for (int h = 0; h < 48; h++) full.append(h == 0 ? "" : ",").append(100);
        String over = json.replaceAll("\"cloud_cover_low\":\\[[^]]*]", "\"cloud_cover_low\":[" + full + "]");
        Tonight o = Tonight.compute(LAT, LON, at(2026, 11, 8, 17, 0), NY, Tonight.Forecast.fromOpenMeteo(over, at(2026, 11, 8, 15, 0)), Double.NaN);
        System.out.println("  overcast: " + o.headline + " | " + o.lines.get(0));
        check(o.bestStartMs == 0 && o.lines.get(0).startsWith("No good window tonight: cloudy"), "overcast: no good window, cloudy");
    }

    /** A session's sky: cloudy before clearsAt (0: never clears). */
    static final class Sky {
        final long clearsAt;

        Sky(long clearsAt) {
            this.clearsAt = clearsAt;
        }

        boolean clear(long ms) {
            return clearsAt > 0 && ms >= clearsAt;
        }
    }

    /** Runs a plan the way the phone does: alarms, a test frame, the capture, developing. Returns the end time. */
    static long run(NightPlan p, long now, Sky sky) {
        for (int guard = 0; guard < 500; guard++) {
            NightPlan.Next n = p.next(now);
            if (n == null) break;
            now = Math.max(now, n.atMs);
            NightPlan.Item it = n.item;
            p.started(it, now);
            long check = now + 40_000L; // camera, stillness, test frame
            if (it.onlyIfClear && !sky.clear(check)) {
                p.ended(it, NightPlan.Outcome.CLOUDY, "3 of 120 stars that should show, 2%", check + 5_000L);
                now = check + 5_000L;
                continue;
            }
            long capStart = Math.max(check + 15_000L, it.startMs);
            long capEnd = it.endMs > 0 ? it.endMs : capStart + it.durationSec * 1000L;
            long devEnd = capEnd + 120_000L;
            p.ended(it, NightPlan.Outcome.DONE, "18 good frames, like a 4-minute exposure", devEnd);
            it.startedMs = capStart;
            now = devEnd;
        }
        return now;
    }

    static NightPlan plan(String night) {
        NightPlan p = new NightPlan();
        p.zone = NY;
        p.night = night;
        p.latDeg = LAT;
        p.lonDeg = LON;
        return p;
    }

    static void threeSessions() {
        System.out.println("== three sessions: 10:10 PM stack, star trails 11 PM to 1 AM, 2:30 AM stack; cloudy until 10:35 PM");
        NightPlan p = plan("2026-10-22");
        NightPlan.Item a = new NightPlan.Item();
        a.rule = NightPlan.Rule.CLOCK;
        a.clockMs = p.clockOnNight(22, 10);
        p.add(a);
        NightPlan.Item b = new NightPlan.Item();
        b.rule = NightPlan.Rule.CLOCK;
        b.trails = true;
        b.clockMs = p.clockOnNight(23, 0);
        b.endMs = p.clockOnNight(1, 0);
        b.durationSec = (int) ((b.endMs - b.clockMs) / 1000);
        p.add(b);
        NightPlan.Item c = new NightPlan.Item();
        c.rule = NightPlan.Rule.CLOCK;
        c.clockMs = p.clockOnNight(2, 30);
        p.add(c);
        check(c.clockMs == at(2026, 10, 23, 2, 30), "2:30 is the next morning");
        long armed = at(2026, 10, 22, 20, 5);
        Tonight n = Tonight.compute(LAT, LON, armed, NY, null, Double.NaN);
        p.resolve(n, armed);
        for (NightPlan.Item it : p.items) System.out.println("  plan: " + p.describe(it));
        long end = run(p, armed, new Sky(at(2026, 10, 22, 22, 35)));
        for (String l : p.log) System.out.println("  log: " + l);
        check(p.finished(), "the plan ran to the end");
        check(a.state == NightPlan.State.DONE && a.tries == 4, "10:10 PM stack: cloudy at 10:08, 10:18, 10:28; done at the 4th try (" + a.tries + ")");
        check(b.state == NightPlan.State.DONE && b.startedMs == at(2026, 10, 22, 23, 0), "star trails started at 11:00 PM: " + t(b.startedMs));
        check(c.state == NightPlan.State.DONE, "2:30 AM stack done");
        long morning = NightPlan.morningMs(n, end, NY);
        System.out.println("  plan ended " + t(end) + "; morning summary " + t(morning));
        check(morning == at(2026, 10, 23, 7, 0) || near(morning, n.civilDawnMs, 60_000L), "summary at 7 AM or first light");
        System.out.println("  " + p.summaryTitle());
        for (String l : p.summaryLines()) System.out.println("    " + l);
        check(p.summaryTitle().equals("Last night: 3 of 3 sessions done"), "summary title");
    }

    static void lensCovered() {
        System.out.println("== lens covered: a stack at full darkness, only if clear, deadline 11:00 PM");
        NightPlan p = plan("2026-10-22");
        NightPlan.Item a = new NightPlan.Item();
        a.rule = NightPlan.Rule.DARK;
        a.giveUpMs = p.clockOnNight(23, 0);
        p.add(a);
        long armed = at(2026, 10, 22, 18, 30);
        Tonight n = Tonight.compute(LAT, LON, armed, NY, null, Double.NaN);
        p.resolve(n, armed);
        System.out.println("  plan: " + p.describe(a));
        run(p, armed, new Sky(0));
        for (String l : p.log) System.out.println("  log: " + l);
        check(a.state == NightPlan.State.SKIPPED, "skipped in the end");
        check(a.tries >= 8, "tried every 10 minutes (" + a.tries + " tries)");
        check(a.outcome.startsWith("Skipped: still cloudy at the deadline, 11:00 PM (tried"), "clear message: " + a.outcome);
        check(a.endedMs <= p.clockOnNight(23, 0) + NightPlan.RETRY_MS, "gave up by the deadline: " + t(a.endedMs));
    }

    static void overlap() {
        System.out.println("== overlap: a stack waits for clear sky when star trails are due");
        NightPlan p = plan("2026-10-22");
        NightPlan.Item a = new NightPlan.Item();
        a.rule = NightPlan.Rule.CLOCK;
        a.clockMs = p.clockOnNight(22, 0);
        a.giveUpMs = p.clockOnNight(0, 30);
        p.add(a);
        NightPlan.Item b = new NightPlan.Item();
        b.rule = NightPlan.Rule.CLOCK;
        b.trails = true;
        b.clockMs = p.clockOnNight(22, 30);
        b.endMs = p.clockOnNight(23, 30);
        b.durationSec = 3600;
        b.onlyIfClear = false;
        p.add(b);
        long armed = at(2026, 10, 22, 21, 0);
        p.resolve(Tonight.compute(LAT, LON, armed, NY, null, Double.NaN), armed);
        run(p, armed, new Sky(at(2026, 10, 22, 22, 50)));
        for (String l : p.log) System.out.println("  log: " + l);
        check(b.state == NightPlan.State.DONE && b.startedMs == at(2026, 10, 22, 22, 30), "star trails (no clear check) ran at 10:30");
        check(a.state == NightPlan.State.DONE && a.startedMs >= b.endMs, "the stack ran after the trails, before its deadline: " + t(a.startedMs));
    }

    static boolean logged(NightPlan p, String part) {
        for (String l : p.log) if (l.contains(part)) return true;
        return false;
    }

    static NightPlan.Item clockItem(NightPlan p, int h, int m, boolean onlyIfClear) {
        NightPlan.Item a = new NightPlan.Item();
        a.rule = NightPlan.Rule.CLOCK;
        a.clockMs = p.clockOnNight(h, m);
        a.onlyIfClear = onlyIfClear;
        return p.add(a);
    }

    static void late() {
        System.out.println("== late starts: after a long session, and armed late");
        // star trails 10 PM to 10:45 PM, then a stack due at 10:30 PM: it starts when the trails are developed
        NightPlan p = plan("2026-10-22");
        NightPlan.Item b = clockItem(p, 22, 0, false);
        b.trails = true;
        b.endMs = p.clockOnNight(22, 45);
        b.durationSec = 45 * 60;
        NightPlan.Item a = clockItem(p, 22, 30, false);
        long armed = at(2026, 10, 22, 21, 0);
        p.resolve(Tonight.compute(LAT, LON, armed, NY, null, Double.NaN), armed);
        run(p, armed, new Sky(1));
        for (String l : p.log) System.out.println("  log: " + l);
        check(a.state == NightPlan.State.DONE && a.startedMs > b.endMs, "the stack ran after the trails: " + t(a.startedMs));
        check(logged(p, "17 minutes late: an earlier session ran until now"), "the log says why it was late");

        // star trails 10 PM to 1 AM, then a stack due at 10:30 PM: more than two hours late, so it's skipped
        NightPlan q = plan("2026-10-22");
        NightPlan.Item c = clockItem(q, 22, 0, false);
        c.trails = true;
        c.endMs = q.clockOnNight(1, 0);
        c.durationSec = 3 * 3600;
        NightPlan.Item d = clockItem(q, 22, 30, false);
        q.resolve(Tonight.compute(LAT, LON, armed, NY, null, Double.NaN), armed);
        run(q, armed, new Sky(1));
        for (String l : q.log) System.out.println("  log: " + l);
        check(c.state == NightPlan.State.DONE && d.state == NightPlan.State.SKIPPED, "the late stack was skipped");
        check(d.outcome.equals("Skipped: it was due at 10:30 PM but couldn't start before 12:30 AM (an earlier session ran until then)."),
                "and says why: " + d.outcome);

        // armed at 11:30 PM for a 10 PM stack (no clear check): within two hours, it starts at once
        NightPlan r = plan("2026-10-22");
        NightPlan.Item e = clockItem(r, 22, 0, false);
        long lateArm = at(2026, 10, 22, 23, 30);
        r.resolve(Tonight.compute(LAT, LON, lateArm, NY, null, Double.NaN), lateArm);
        run(r, lateArm, new Sky(1));
        for (String l : r.log) System.out.println("  log: " + l);
        check(e.state == NightPlan.State.DONE && near(e.startedMs, lateArm, 60_000L), "armed 90 minutes late: started at once (" + t(e.startedMs) + ")");
        check(logged(r, "90 minutes late: the plan was armed after its start time"), "the log says it was armed late");

        // armed at 12:30 AM: too late
        NightPlan s = plan("2026-10-22");
        NightPlan.Item f = clockItem(s, 22, 0, false);
        long tooLate = at(2026, 10, 23, 0, 30);
        s.resolve(Tonight.compute(LAT, LON, tooLate, NY, null, Double.NaN), tooLate);
        check(s.next(tooLate) == null && f.state == NightPlan.State.SKIPPED
                && f.outcome.contains("the plan was armed too late"), "armed 2.5 hours late: skipped, " + f.outcome);

        // only if clear: armed late but before its deadline, it still tries
        NightPlan u = plan("2026-10-22");
        NightPlan.Item g = clockItem(u, 22, 0, true);
        long arm2 = at(2026, 10, 22, 23, 15);
        u.resolve(Tonight.compute(LAT, LON, arm2, NY, null, Double.NaN), arm2);
        NightPlan.Next n = u.next(arm2);
        check(n != null && n.item == g && n.atMs < arm2, "only if clear, armed 75 minutes late: tries at once");
    }

    static void cancelRunning() {
        System.out.println("== cancel while a session runs");
        NightPlan p = plan("2026-10-22");
        NightPlan.Item a = clockItem(p, 22, 0, true);
        NightPlan.Item b = clockItem(p, 23, 0, true);
        long armed = at(2026, 10, 22, 21, 0);
        p.resolve(Tonight.compute(LAT, LON, armed, NY, null, Double.NaN), armed);
        p.armed = true;
        long now = at(2026, 10, 22, 21, 58, 30);
        NightPlan.Next n = p.next(now);
        check(n != null && n.item == a && n.atMs == now, "the first alarm is 90 s before 10 PM");
        p.started(a, now);
        p.cancelAll(at(2026, 10, 22, 22, 2), "cancelled by you");
        check(!p.armed && a.state == NightPlan.State.RUNNING && b.state == NightPlan.State.CANCELLED,
                "the running session goes on until it ends; the rest is cancelled");
        check(!p.finished() && p.next(now) == null, "nothing new starts");
        p.ended(a, NightPlan.Outcome.DONE, "4 good frames, like a 1-minute exposure", at(2026, 10, 22, 22, 3));
        check(p.finished() && a.state == NightPlan.State.DONE, "the session it stopped keeps what it caught");
        check(p.summaryTitle().equals("Last night: 1 of 1 session done"), "the summary leaves out what never ran: " + p.summaryTitle());
        check(!p.remove(b.id) || p.find(b.id) == null, "a cancelled session can be removed");
    }

    static long at(int y, int mo, int d, int h, int mi, int s) {
        return at(y, mo, d, h, mi) + s * 1000L;
    }

    static void untilDawn() {
        System.out.println("== only if clear, until dawn, never clears");
        NightPlan p = plan("2026-10-22");
        NightPlan.Item a = new NightPlan.Item();
        a.rule = NightPlan.Rule.DARK;
        a.waitMin = -1;
        p.add(a);
        NightPlan.Item b = new NightPlan.Item();
        b.rule = NightPlan.Rule.DARK;
        b.waitMin = 60;
        b.offsetMin = 5;
        p.add(b);
        long armed = at(2026, 10, 22, 18, 30);
        Tonight n = Tonight.compute(LAT, LON, armed, NY, null, Double.NaN);
        p.resolve(n, armed);
        System.out.println("  plan: " + p.describe(a));
        System.out.println("  plan: " + p.describe(b));
        long lastTry = n.dawnMs - 360_000L;
        check(p.describe(a).endsWith("waits until " + Tonight.clock(lastTry, NY) + ")"), "waits until 6 minutes before dawn");
        check(p.describe(b).contains("waits until " + Tonight.clock(b.startMs + 3600_000L, NY)), "or an hour, when asked");
        run(p, armed, new Sky(0));
        System.out.println("  log: ... " + p.log.get(p.log.size() - 1));
        check(a.state == NightPlan.State.SKIPPED && a.tries > 50, "tried all night (" + a.tries + " tries)");
        check(a.endedMs <= lastTry + 60_000L && a.endedMs > lastTry - NightPlan.RETRY_MS - 60_000L, "gave up at " + t(a.endedMs));
        check(b.state == NightPlan.State.SKIPPED && b.tries >= 5 && b.tries <= 7, "the one-hour one tried " + b.tries + " times");
    }

    static void noPlace() {
        System.out.println("== no place known");
        NightPlan p = plan("2026-10-22");
        NightPlan.Item a = clockItem(p, 22, 0, true);
        NightPlan.Item b = new NightPlan.Item();
        b.rule = NightPlan.Rule.MOONSET;
        p.add(b);
        long now = at(2026, 10, 22, 20, 0);
        p.resolve(null, now);
        check(a.startMs == a.clockMs, "a set time works without the place");
        check(b.startMs == 0 && b.when.contains("needs the phone's place"), "moonset needs it: " + b.when);
        NightPlan.Next n = p.next(now);
        check(n != null && n.item == a && b.state == NightPlan.State.SKIPPED, "the set time still runs; the other is skipped");
        check(b.outcome.contains("needs the phone's place"), "and says why: " + b.outcome);
    }

    static void preview() {
        System.out.println("== editor preview");
        NightPlan p = plan("2026-10-22");
        Tonight n = Tonight.compute(LAT, LON, at(2026, 10, 22, 17, 0), NY, null, Double.NaN);
        NightPlan.Item a = new NightPlan.Item();
        a.rule = NightPlan.Rule.MOONSET;
        a.offsetMin = 30;
        String s = p.preview(a, n);
        System.out.println("  " + s);
        check(s.startsWith("5-minute stack, from ") && s.contains("30 min after moonset"), "moonset + 30 min");
        check(p.previewStart(a, n) == n.moonSetMs + 30 * 60_000L, "starts 30 min after moonset");
        check(p.items.isEmpty() && a.startMs == 0, "the preview doesn't change the plan or the item");
        NightPlan.Item b = new NightPlan.Item();
        b.rule = NightPlan.Rule.CLOCK;
        b.trails = true;
        b.clockMs = p.clockOnNight(23, 0);
        b.endMs = p.clockOnNight(22, 30);
        String e = p.preview(b, n);
        System.out.println("  " + e);
        check(e.equals("It would start at 11:00 PM, after its end time (10:30 PM)."), "an end before the start is caught");
        b.endMs = p.clockOnNight(3, 0);
        String l = p.preview(b, n);
        System.out.println("  " + l);
        check(l.contains("(2 hours at most: it stops at 1:00 AM)"), "a trail longer than a session can run says where it stops");
    }

    static void repeat() {
        System.out.println("== repeat tomorrow");
        NightPlan p = plan("2026-10-22");
        NightPlan.Item a = new NightPlan.Item();
        a.rule = NightPlan.Rule.CLOCK;
        a.clockMs = p.clockOnNight(22, 10);
        p.add(a);
        a.startMs = a.clockMs;
        a.startedMs = at(2026, 10, 22, 22, 10) + 30_000L;
        a.session = "session_20261022_221030";
        a.state = NightPlan.State.DONE;
        NightPlan.Item r = NightPlan.repeatTomorrow(a);
        long want = a.startedMs + 86_164_091L;
        System.out.println("  " + t(a.startedMs) + " -> " + t(r.clockMs));
        check(r.clockMs == want && t(r.clockMs).equals("Oct 23 22:06:34"), "3 min 56 s earlier the next night");
        check(NightPlan.nightAfter("2026-10-22").equals("2026-10-23") && NightPlan.nightAfter("2026-12-31").equals("2027-01-01"), "next night's date");
        check(NightPlan.nightOf(at(2026, 10, 23, 1, 0), NY).equals("2026-10-22"), "1 AM belongs to the night before");
        NightPlan q = plan("2026-10-23");
        q.add(r);
        q.resolve(Tonight.compute(LAT, LON, at(2026, 10, 23, 19, 0), NY, null, Double.NaN), at(2026, 10, 23, 19, 0));
        System.out.println("  " + q.describe(r));
        check(q.describe(r).contains("the same stars as 10:10 PM the night before"), "says it repeats last night's stars");
        NightPlan.Item r2 = NightPlan.repeatAfter(a, 2);
        System.out.println("  two nights on: " + t(r2.clockMs));
        check(r2.clockMs == a.startedMs + 2 * 86_164_091L && t(r2.clockMs).equals("Oct 24 22:02:38"), "two nights later: 7 min 52 s earlier");
        check(NightPlan.nightsBetween("2026-10-22", "2026-10-24") == 2 && NightPlan.nightsBetween("2026-10-31", "2026-11-02") == 2
                && NightPlan.nightsBetween("2026-12-31", "2027-01-01") == 1, "nights between two dates (over a clock change and a new year)");
        NightPlan.Item hand = NightPlan.ofSession(true, at(2026, 10, 22, 23, 0), 1800, "session_20261022_230000");
        NightPlan.Item hr = NightPlan.repeatTomorrow(hand);
        check(hr.trails && hr.durationSec == 1800 && hr.clockMs == at(2026, 10, 22, 23, 0) + 86_164_091L && hr.repeatOf.equals(hand.session),
                "a session started by hand repeats too");
    }

    static void json() {
        System.out.println("== JSON");
        NightPlan p = plan("2026-10-22");
        NightPlan.Item a = new NightPlan.Item();
        a.rule = NightPlan.Rule.MOONSET;
        a.offsetMin = 15;
        a.trails = true;
        a.endMs = p.clockOnNight(3, 0);
        a.preset = "dark site";
        p.add(a);
        long armed = at(2026, 10, 22, 19, 0);
        p.resolve(Tonight.compute(LAT, LON, armed, NY, null, Double.NaN), armed);
        p.started(a, armed);
        p.ended(a, NightPlan.Outcome.CLOUDY, "only 4 stars: \"overcast\"", armed + 60_000L);
        p.minBatteryPct = 30;
        p.sounds = true;
        String j = p.toJson();
        NightPlan q = NightPlan.fromJson(j);
        boolean same = q.toJson().equals(j);
        if (!same) {
            String k = q.toJson();
            int i = 0;
            while (i < Math.min(j.length(), k.length()) && j.charAt(i) == k.charAt(i)) i++;
            System.out.println("  differs at " + i + ": " + j.substring(Math.max(0, i - 40), Math.min(j.length(), i + 40)) + "\n  vs " + k.substring(Math.max(0, i - 40), Math.min(k.length(), i + 40)));
        }
        check(same, "round trip is exact");
        check(q.items.get(0).outcome.contains("\"overcast\"") && q.minBatteryPct == 30 && q.sounds, "fields survive");
        check(Math.abs(q.lonDeg - LON) < 1e-7, "the place keeps its precision");
        System.out.println("  " + p.describe(a));
    }

    // ------------------------------------------------------------------------------------------------

    /** Every report.json under {@code dir} reads back (for the morning summary) and writes out the same again. */
    static void reports(File dir) {
        int n = 0, bad = 0;
        for (File f : find(dir, new java.util.ArrayList<File>())) {
            try {
                String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                ReportCard r = ReportCard.fromJson(json);
                String again = r.toJson();
                String diff = same("", JsonTree.parse(json), JsonTree.parse(again));
                if (diff != null) {
                    bad++;
                    System.out.println("  report " + f + ": " + diff);
                }
                if (!ReportCard.fromJson(again).toJson().equals(again)) {
                    bad++;
                    System.out.println("  report " + f + ": not stable when read and written twice");
                }
                n++;
            } catch (IOException | RuntimeException e) {
                bad++;
                System.out.println("  report " + f + ": " + e);
            }
        }
        check(n > 0 && bad == 0, "reports read back: " + n + " files, " + bad + " differ");
    }

    private static List<File> find(File d, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null) return out;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) find(f, out);
            else if (f.getName().equals("report.json")) out.add(f);
        }
        return out;
    }

    /** Where two JSON values differ (null if they don't), ignoring what only a fresh report knows (the noise sums). */
    @SuppressWarnings("unchecked")
    private static String same(String path, Object a, Object b) {
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> ma = (Map<String, Object>) a, mb = (Map<String, Object>) b;
            for (String k : ma.keySet()) {
                if (k.equals("sigmaFrame") || k.equals("sigmaStack") || k.equals("blocks") || k.equals("frameUnit")
                        || k.equals("catalogStars")) continue;
                if (!mb.containsKey(k)) return path + "." + k + " missing";
                String d = same(path + "." + k, ma.get(k), mb.get(k));
                if (d != null) return d;
            }
            for (String k : mb.keySet()) if (!ma.containsKey(k)) return path + "." + k + " added";
            return null;
        }
        if (a instanceof List && b instanceof List) {
            List<Object> la = (List<Object>) a, lb = (List<Object>) b;
            if (la.size() != lb.size()) return path + " has " + lb.size() + " items, not " + la.size();
            for (int i = 0; i < la.size(); i++) {
                String d = same(path + "[" + i + "]", la.get(i), lb.get(i));
                if (d != null) return d;
            }
            return null;
        }
        if (a instanceof Double && b instanceof Double) {
            double x = (Double) a, y = (Double) b;
            return Math.abs(x - y) <= 1e-9 * Math.max(1, Math.abs(x)) ? null : path + ": " + y + " for " + x;
        }
        if (a == null ? b == null : a.equals(b)) return null;
        return path + ": " + b + " for " + a;
    }
}
