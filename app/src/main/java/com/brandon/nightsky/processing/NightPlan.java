package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A night's plan of scheduled sessions (E2): each starts at full darkness, at moonset, at the best window or at
 * a clock time, "only if clear" by default (a test frame counts the stars; under a third of what a clear sky
 * shows here, it tries again every 10 minutes until a deadline), and stops by itself. This class decides; the
 * phone's alarms and camera carry it out. Every decision goes into {@link #log} with its reason.
 */
public final class NightPlan {
    /** One turn of the sky: the same stars are overhead this much earlier each night (23 h 56 min 4.1 s). */
    public static final long SIDEREAL_DAY_MS = 86_164_091L;
    /** Between tries while it's cloudy (or the phone isn't lying still). */
    public static final long RETRY_MS = 10 * 60_000L;
    /** The alarm goes off this long before a planned start: the camera opens, the phone is checked and a test frame read. */
    public static final long LEAD_MS = 90_000L;
    /** "Only if clear" waits this long past the planned start unless a deadline is set. */
    public static final long DEFAULT_CLEAR_WAIT_MS = 2 * 3600_000L;
    /** Clear enough: at least this share of the stars a clear sky shows here. */
    public static final double CLEAR_SHARE = 1 / 3.0;
    /** A session that doesn't wait for a clear sky still starts up to this late (after an earlier one, or a re-arm). */
    public static final long MAX_LATE_MS = 2 * 3600_000L;
    /** The longest a session runs (its frames are kept until it's developed: about 11 GB for 2 hours). */
    public static final int MAX_SESSION_SEC = 2 * 3600;

    public enum Rule {
        DARK("at full darkness"), MOONSET("at moonset"), BEST("at the best window"), CLOCK("at a set time");
        public final String words;

        Rule(String w) {
            words = w;
        }
    }

    public enum State { PLANNED, WAITING, RUNNING, DONE, SKIPPED, FAILED, CANCELLED }

    /** How an attempt ended. */
    public enum Outcome { DONE, CLOUDY, NOT_PLACED, LOW_BATTERY, FAILED, CANCELLED }

    public static final class Item {
        public String id = "";
        public boolean trails;
        public Rule rule = Rule.DARK;
        /** {@link Rule#CLOCK}: the time chosen (Unix ms). */
        public long clockMs;
        /** Minutes after the event (DARK, MOONSET, BEST). */
        public int offsetMin;
        public int durationSec = 300;
        /** Stop at this time (Unix ms; 0: after {@link #durationSec}). Star trails "until 1 AM" use it. */
        public long endMs;
        public boolean onlyIfClear = true;
        /** Stop waiting for a clear sky at this time (0: {@link #waitMin} after the planned start). */
        public long giveUpMs;
        /** "Only if clear" keeps trying this many minutes after the planned start (0: two hours; -1: until dawn). */
        public int waitMin;
        /** Settings preset to use (empty: the current settings). */
        public String preset = "";
        /** For a repeat: the session it repeats a sidereal day later, and when that one started. */
        public String repeatOf = "";
        public long repeatOfMs;

        // worked out for tonight
        public long startMs;
        public String when = "";
        /** Tonight's dawn (the Sun at -18 degrees), for "until dawn" (0 if unknown). */
        public long dawnMs;

        // progress
        public State state = State.PLANNED;
        public int tries;
        public long nextTryMs, startedMs, endedMs;
        /** One line on how it went ("18 good frames, like a 4-minute exposure" or why it was skipped). */
        public String outcome = "";
        /** What the last try was waiting for ("still cloudy"), for the message when it gives up. */
        public String lastWait = "";
        /** The saved photo (content URI), its session folder's name, a small picture of it and its report summary. */
        public String photo = "", session = "", thumb = "", spoken = "";

        public boolean finished() {
            return state == State.DONE || state == State.SKIPPED || state == State.FAILED || state == State.CANCELLED;
        }

        /** "5-minute stack" or "Star trails". */
        public String what() {
            if (trails) return endMs > 0 ? "Star trails" : "Star trails, " + minutes(durationSec);
            return minutes(durationSec) + " stack";
        }

        /** When it ends at the latest: its end time, or its start plus its length. */
        long latestEnd() {
            return endMs > 0 ? endMs : startMs + durationSec * 1000L;
        }

        long giveUp() {
            if (giveUpMs > 0) return giveUpMs;
            long w = waitMin > 0 ? waitMin * 60_000L : DEFAULT_CLEAR_WAIT_MS;
            // until dawn: the last try leaves time for a 5-minute session before the sky brightens
            if (waitMin < 0) w = dawnMs > startMs ? Math.max(0, dawnMs - startMs - (durationSec + 60) * 1000L) : DEFAULT_CLEAR_WAIT_MS;
            long g = startMs + w;
            return endMs > 0 ? Math.min(g, endMs - 5 * 60_000L) : g;
        }

        Item copy() {
            Item c = new Item();
            c.trails = trails;
            c.rule = rule;
            c.clockMs = clockMs;
            c.offsetMin = offsetMin;
            c.durationSec = durationSec;
            c.endMs = endMs;
            c.onlyIfClear = onlyIfClear;
            c.giveUpMs = giveUpMs;
            c.waitMin = waitMin;
            c.preset = preset;
            return c;
        }
    }

    /** What to do next: start (or try again) an item at a time, or nothing until something changes. */
    public static final class Next {
        public final Item item;
        /** When to act (Unix ms): for a first start, {@link #LEAD_MS} before the planned start. */
        public final long atMs;

        Next(Item item, long atMs) {
            this.item = item;
            this.atMs = atMs;
        }
    }

    /** The evening the plan is for, as "2026-10-22" (a night belongs to the date it began on). */
    public String night = "";
    public long createdMs;
    public double latDeg = Double.NaN, lonDeg = Double.NaN;
    /** Beeps and voice during scheduled sessions (off: someone may be asleep nearby). */
    public boolean sounds;
    /** Stop a session, and don't start one, below this battery level (%). */
    public int minBatteryPct = 20;
    public boolean armed;
    public long armedMs;
    /** The morning summary has been shown (or isn't wanted any more). */
    public boolean summaryDone;
    public final List<Item> items = new ArrayList<Item>();
    /** Decisions and events, oldest first: "10:10 PM: 12 of 140 stars (9%): cloudy. Trying again at 10:20 PM." */
    public final List<String> log = new ArrayList<String>();
    public TimeZone zone = TimeZone.getDefault();
    private int nextId = 1;

    // ------------------------------------------------------------------------------------------------
    // Building

    public Item add(Item it) {
        it.id = "s" + nextId++;
        items.add(it);
        return it;
    }

    public Item find(String id) {
        for (Item it : items) if (it.id.equals(id)) return it;
        return null;
    }

    public boolean remove(String id) {
        Item it = find(id);
        if (it == null || it.state == State.RUNNING) return false;
        items.remove(it);
        return true;
    }

    /** The night a time belongs to: the date twelve hours earlier ("2026-10-22" from 1 AM on the 23rd). */
    public static String nightOf(long ms, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(zone);
        return f.format(new Date(ms - 12 * 3600_000L));
    }

    /** The night after this one ("2026-10-23" after "2026-10-22"). */
    public static String nightAfter(String night) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = f.parse(night);
            return f.format(new Date(d.getTime() + 86_400_000L + 3600_000L));
        } catch (java.text.ParseException e) {
            return night;
        }
    }

    /**
     * A clock time on this plan's night: evening hours (noon and later) on its date, morning hours on the next
     * day. Hour 0-23, minute 0-59.
     */
    public long clockOnNight(int hour, int minute) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(zone);
            c.setTime(f.parse(night));
        } catch (java.text.ParseException e) {
            c.setTimeInMillis(System.currentTimeMillis() - 12 * 3600_000L);
        }
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (hour < 12) c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, hour);
        c.set(Calendar.MINUTE, minute);
        return c.getTimeInMillis();
    }

    /**
     * The same session a sidereal day later (3 min 56 s earlier by the clock), so the same stars are overhead
     * and the nights can be stacked together. Goes on the next night's plan.
     */
    public static Item repeatTomorrow(Item done) {
        return repeatAfter(done, 1);
    }

    /** The same session {@code nights} sidereal days later (3 min 56 s earlier by the clock for each). */
    public static Item repeatAfter(Item done, int nights) {
        Item r = done.copy();
        long base = done.startedMs > 0 ? done.startedMs : done.startMs;
        long shift = Math.max(1, nights) * SIDEREAL_DAY_MS;
        r.rule = Rule.CLOCK;
        r.offsetMin = 0;
        r.clockMs = base + shift;
        r.endMs = done.endMs > 0 ? done.endMs + shift : 0;
        r.giveUpMs = 0;
        r.repeatOf = done.session;
        r.repeatOfMs = base;
        return r;
    }

    /** A session started by hand, as a plan item (for Repeat tomorrow from its result). */
    public static Item ofSession(boolean trails, long startedMs, int durationSec, String session) {
        Item it = new Item();
        it.trails = trails;
        it.rule = Rule.CLOCK;
        it.clockMs = startedMs;
        it.startMs = startedMs;
        it.startedMs = startedMs;
        it.durationSec = Math.max(60, durationSec);
        it.session = session;
        it.state = State.DONE;
        return it;
    }

    /** Nights from {@code a} to {@code b} ("2026-10-22" to "2026-10-24" is 2). */
    public static int nightsBetween(String a, String b) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return (int) Math.round((f.parse(b).getTime() - f.parse(a).getTime()) / 86_400_000.0);
        } catch (java.text.ParseException e) {
            return 1;
        }
    }

    /** How an item would read if it were added to this plan now (its times worked out from tonight's sky). */
    public String preview(Item it, Tonight t) {
        Item c = it.copy();
        c.repeatOf = it.repeatOf;
        c.repeatOfMs = it.repeatOfMs;
        resolve(c, t);
        if (c.startMs > 0 && c.endMs > 0 && c.startMs > c.endMs - 5 * 60_000L) {
            return "It would start at " + c.when + ", after its end time (" + clock(c.endMs) + ").";
        }
        return describe(c);
    }

    /** The worked-out start of an item if it were added now (0 if it can't start tonight). */
    public long previewStart(Item it, Tonight t) {
        Item c = it.copy();
        resolve(c, t);
        return c.startMs;
    }

    // ------------------------------------------------------------------------------------------------
    // Working out the start times

    /** Works out every planned item's start from tonight's darkness, Moon and best window. */
    public void resolve(Tonight t, long nowMs) {
        for (Item it : items) {
            if (it.state != State.PLANNED) continue;
            resolve(it, t);
            if (it.startMs > 0 && it.endMs > 0 && it.startMs > it.endMs - 5 * 60_000L) {
                it.when = "It would start at " + it.when + ", after its end time (" + clock(it.endMs) + ")";
                it.startMs = 0;
            }
        }
        sort();
    }

    void resolve(Item it, Tonight t) {
        if (t == null) {
            // the place isn't known: only a clock time can be worked out
            if (it.rule == Rule.CLOCK) {
                it.startMs = it.clockMs;
                it.when = clock(it.clockMs);
            } else {
                it.startMs = 0;
                it.when = "Starting " + it.rule.words + " needs the phone's place (allow location)";
            }
            return;
        }
        long off = it.offsetMin * 60_000L;
        it.dawnMs = t.dawnMs > 0 ? t.dawnMs : t.nauticalDawnMs;
        long dusk = t.duskMs > 0 ? t.duskMs : t.darkNow ? t.fromMs : 0;
        String darkWord = "full darkness";
        if (dusk == 0) {
            // no astronomical darkness (summer far north): the darkest the night gets
            dusk = t.nauticalDuskMs > 0 ? t.nauticalDuskMs : t.darkestMs;
            darkWord = t.nauticalDuskMs > 0 ? "nautical darkness (it doesn't get fully dark tonight)" : "the darkest moment (it doesn't get dark tonight)";
        }
        switch (it.rule) {
            case CLOCK:
                it.startMs = it.clockMs;
                it.when = clock(it.clockMs) + (it.repeatOf.isEmpty() ? "" : " (the same stars as " + clock(it.repeatOfMs) + " the night before)");
                break;
            case DARK:
                it.startMs = dusk + off;
                it.when = clock(it.startMs) + offsetWords(it.offsetMin, darkWord);
                break;
            case MOONSET:
                if (t.moonIllum <= 0.02 || (!t.moonUpAtDusk && t.moonRiseMs == 0)) {
                    it.startMs = dusk + off;
                    it.when = clock(it.startMs) + offsetWords(it.offsetMin, darkWord + "; the Moon is down all night");
                } else if (!t.moonUpAtDusk && t.moonRiseMs > 0) {
                    it.startMs = dusk + off;
                    it.when = clock(it.startMs) + " (the Moon is down until it rises at " + clock(t.moonRiseMs) + ")";
                } else if (t.moonSetMs > 0) {
                    it.startMs = Math.max(dusk, t.moonSetMs + off);
                    it.when = clock(it.startMs) + offsetWords(it.offsetMin, "moonset");
                } else {
                    it.startMs = 0;
                    it.when = "The Moon doesn't set before dawn tonight";
                }
                break;
            case BEST:
                if (t.bestStartMs > 0) {
                    it.startMs = t.bestStartMs + off;
                    it.when = clock(it.startMs) + offsetWords(it.offsetMin, "the best window");
                } else {
                    it.startMs = dusk + off;
                    it.when = clock(it.startMs) + offsetWords(it.offsetMin, darkWord + "; no good window tonight");
                }
                break;
        }
    }

    private String offsetWords(int min, String event) {
        if (min == 0) return " (" + event + ")";
        return String.format(Locale.US, " (%d min %s %s)", Math.abs(min), min > 0 ? "after" : "before", event);
    }

    private void sort() {
        // finished and running first in the order they ran, then by start
        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                long ka = a.startMs > 0 ? a.startMs : Long.MAX_VALUE, kb = b.startMs > 0 ? b.startMs : Long.MAX_VALUE;
                return ka < kb ? -1 : ka > kb ? 1 : 0;
            }
        });
    }

    // ------------------------------------------------------------------------------------------------
    // Running

    /** The item running now, or null. */
    public Item running() {
        for (Item it : items) if (it.state == State.RUNNING) return it;
        return null;
    }

    /**
     * Gives up on items that can't happen any more (logging why) and returns what to do next: the item due
     * soonest and when, or null when nothing is left (or one is running: it decides when it ends).
     */
    public Next next(long nowMs) {
        if (running() != null) return null;
        Next best = null;
        for (Item it : items) {
            if (it.finished()) continue;
            if (it.state == State.PLANNED && it.startMs == 0) {
                skip(it, nowMs, it.when.isEmpty() ? "its start couldn't be worked out" : uncapital(it.when));
                continue;
            }
            if (it.endMs > 0 && nowMs >= it.endMs - 60_000L) {
                skip(it, nowMs, "its end time (" + clock(it.endMs) + ") came before it could start"
                        + (it.tries > 0 ? " (" + triesWords(it) + ")" : ""));
                continue;
            }
            if (it.state == State.WAITING && nowMs > it.giveUp()) {
                skip(it, nowMs, (it.lastWait.isEmpty() ? "still cloudy" : it.lastWait) + " at the deadline, " + clock(it.giveUp())
                        + " (" + triesWords(it) + ")");
                continue;
            }
            if (it.state == State.PLANNED && it.tries == 0) {
                long deadline = it.onlyIfClear ? it.giveUp() : it.startMs + MAX_LATE_MS;
                if (nowMs > deadline) {
                    skip(it, nowMs, "it was due at " + clock(it.startMs) + " but couldn't start before " + clock(deadline)
                            + (busyDuring(it, nowMs) ? " (an earlier session ran until then)"
                            : " (the plan was armed too late, or the phone was restarted and not re-armed)"));
                    continue;
                }
            }
            long at = it.state == State.WAITING ? it.nextTryMs : it.startMs - LEAD_MS;
            if (best == null || at < best.atMs || (at == best.atMs && it.startMs < best.item.startMs)) best = new Next(it, at);
        }
        return best;
    }

    /** Whether another session ran between the item's start and now. */
    private boolean busyDuring(Item it, long nowMs) {
        for (Item o : items) {
            if (o == it || o.startedMs == 0) continue;
            long end = o.state == State.RUNNING ? nowMs : o.endedMs;
            if (o.startedMs < nowMs && end > it.startMs) return true;
        }
        return false;
    }

    /** The item has begun (camera opening). */
    public void started(Item it, long nowMs) {
        it.state = State.RUNNING;
        it.tries++;
        if (it.startedMs == 0 || it.tries == 1) it.startedMs = nowMs;
        String late = "";
        if (it.tries == 1 && nowMs > it.startMs + 3 * 60_000L) {
            late = " (" + minutesLate(nowMs - it.startMs) + " late: "
                    + (busyDuring(it, nowMs) ? "an earlier session ran until now" : "the plan was armed after its start time") + ")";
        }
        note(nowMs, it.what() + (it.tries > 1 ? ": try " + it.tries : ": starting") + late);
    }

    /**
     * How an attempt ended. A cloudy sky (or a phone not lying still) tries again in {@link #RETRY_MS} until
     * the deadline; everything else is final. {@code detail} says what was seen ("12 of 140 stars").
     */
    public void ended(Item it, Outcome o, String detail, long nowMs) {
        it.endedMs = nowMs;
        switch (o) {
            case DONE:
                it.state = State.DONE;
                it.outcome = detail;
                note(nowMs, it.what() + " done: " + detail);
                break;
            case CLOUDY:
            case NOT_PLACED: {
                it.lastWait = o == Outcome.CLOUDY ? "still cloudy" : "the phone still wasn't lying still, screen-down";
                long again = nowMs + RETRY_MS;
                if (again > it.giveUp() || (it.endMs > 0 && again > it.endMs - 5 * 60_000L)) {
                    skip(it, nowMs, it.lastWait + " at the deadline, " + clock(Math.min(it.giveUp(), it.endMs > 0 ? it.endMs : Long.MAX_VALUE))
                            + " (" + triesWords(it) + "; the last check saw " + uncapital(detail) + ")");
                } else {
                    it.state = State.WAITING;
                    it.nextTryMs = again;
                    it.outcome = detail + ". Trying again at " + clock(again) + ".";
                    note(nowMs, it.what() + ": " + it.outcome);
                }
                break;
            }
            case LOW_BATTERY:
                skip(it, nowMs, detail);
                break;
            case CANCELLED:
                it.state = State.CANCELLED;
                it.outcome = detail.isEmpty() ? "Cancelled" : detail;
                note(nowMs, it.what() + ": " + it.outcome);
                break;
            default:
                it.state = State.FAILED;
                it.outcome = detail;
                note(nowMs, it.what() + " failed: " + detail);
                break;
        }
    }

    /** Ends the plan: whatever hasn't run is cancelled (a session running now ends through {@link #ended}). */
    public void cancelAll(long nowMs, String why) {
        for (Item it : items) {
            if (it.finished() || it.state == State.RUNNING) continue;
            it.state = State.CANCELLED;
            it.outcome = why;
        }
        armed = false;
        note(nowMs, "plan cancelled: " + why);
    }

    /** Nothing left to run. */
    public boolean finished() {
        for (Item it : items) if (!it.finished()) return false;
        return true;
    }

    private void skip(Item it, long nowMs, String why) {
        it.state = State.SKIPPED;
        it.outcome = "Skipped: " + why + ".";
        note(nowMs, it.what() + ": " + it.outcome);
    }

    private static String triesWords(Item it) {
        return it.tries <= 0 ? "never tried" : it.tries == 1 ? "tried once" : "tried " + it.tries + " times";
    }

    public void note(long nowMs, String s) {
        log.add(clock(nowMs) + ": " + s);
        while (log.size() > 200) log.remove(0);
    }

    // ------------------------------------------------------------------------------------------------
    // The morning

    /**
     * When to show the morning summary: once it's light (civil dawn) and not before 7 AM; at once if that's
     * already past or the plan ended in the evening (before 9 PM, nobody is asleep yet).
     */
    public static long morningMs(Tonight t, long endedMs, TimeZone zone) {
        Calendar c = Calendar.getInstance(zone, Locale.US);
        c.setTimeInMillis(endedMs);
        int hour = c.get(Calendar.HOUR_OF_DAY);
        if (hour >= 12 && hour < 21) return endedMs;
        if (hour >= 21) c.add(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 7);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long seven = c.getTimeInMillis();
        long dawn = t != null && t.civilDawnMs > endedMs ? t.civilDawnMs : 0;
        long at = Math.max(seven, dawn);
        if (at - endedMs > 14 * 3600_000L) at = seven;
        return Math.max(at, endedMs);
    }

    /** "Last night: 2 of 3 sessions done" */
    public String summaryTitle() {
        int done = 0, all = 0;
        for (Item it : items) {
            if (it.state == State.CANCELLED && it.tries == 0) continue;
            all++;
            if (it.state == State.DONE) done++;
        }
        if (all == 0) return "Last night's plan was cancelled";
        return String.format(Locale.US, "Last night: %d of %d session%s done", done, all, all == 1 ? "" : "s");
    }

    /** One line per session: "10:10 PM · 5-minute stack: 18 good frames, like a 4-minute exposure". */
    public List<String> summaryLines() {
        List<String> out = new ArrayList<String>();
        for (Item it : items) {
            long t = it.startedMs > 0 ? it.startedMs : it.startMs;
            String o = it.outcome.isEmpty() ? it.state.name().toLowerCase(Locale.US) : it.outcome;
            out.add((t > 0 ? clock(t) + " · " : "") + it.what() + ": " + o);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // Words

    /** "at full darkness (9:42 PM), only if clear until 11:42 PM" */
    public String describe(Item it) {
        StringBuilder sb = new StringBuilder();
        sb.append(it.what());
        if (it.trails && it.endMs > 0) sb.append(" until ").append(clock(it.endMs));
        if (it.startMs > 0) sb.append(", from ").append(it.when);
        if (it.trails && it.endMs > 0 && it.startMs > 0 && it.endMs - it.startMs > MAX_SESSION_SEC * 1000L) {
            sb.append(" (").append(MAX_SESSION_SEC / 3600).append(" hours at most: it stops at ")
                    .append(clock(it.startMs + MAX_SESSION_SEC * 1000L)).append(")");
        }
        else sb.append(": ").append(it.when.isEmpty() ? it.rule.words : uncapital(it.when));
        if (it.onlyIfClear && it.startMs > 0) sb.append(", only if clear (waits until ").append(clock(it.giveUp())).append(")");
        return sb.toString();
    }

    private String clock(long ms) {
        return Tonight.clock(ms, zone);
    }

    static String minutes(int sec) {
        int m = Math.round(sec / 60f);
        return m >= 60 && m % 60 == 0 ? (m / 60) + "-hour" : m + "-minute";
    }

    private static String minutesLate(long ms) {
        long m = Math.round(ms / 60_000.0);
        return m + (m == 1 ? " minute" : " minutes");
    }

    static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    static String uncapital(String s) {
        return s.length() < 2 || Character.isUpperCase(s.charAt(1)) ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    // ------------------------------------------------------------------------------------------------
    // JSON

    public String toJson() {
        Json j = new Json().obj();
        j.put("version", 1);
        j.put("night", night).put("createdMs", createdMs);
        j.put("latDeg", latDeg, 9).put("lonDeg", lonDeg, 10);
        j.put("zone", zone.getID());
        j.put("sounds", sounds).put("minBatteryPct", minBatteryPct);
        j.put("armed", armed).put("armedMs", armedMs).put("summaryDone", summaryDone).put("nextId", nextId);
        j.arr("items");
        for (Item it : items) {
            j.obj().put("id", it.id).put("trails", it.trails).put("rule", it.rule.name()).put("clockMs", it.clockMs)
                    .put("offsetMin", it.offsetMin).put("durationSec", it.durationSec).put("endMs", it.endMs)
                    .put("onlyIfClear", it.onlyIfClear).put("giveUpMs", it.giveUpMs).put("waitMin", it.waitMin).put("preset", it.preset)
                    .put("repeatOf", it.repeatOf).put("repeatOfMs", it.repeatOfMs)
                    .put("startMs", it.startMs).put("when", it.when).put("dawnMs", it.dawnMs)
                    .put("state", it.state.name()).put("tries", it.tries).put("nextTryMs", it.nextTryMs)
                    .put("startedMs", it.startedMs).put("endedMs", it.endedMs).put("outcome", it.outcome)
                    .put("lastWait", it.lastWait)
                    .put("photo", it.photo).put("session", it.session).put("thumb", it.thumb).put("spoken", it.spoken).end();
        }
        j.end();
        j.arr("log");
        for (String l : log) j.val(l);
        j.end();
        return j.end().toString();
    }

    public static NightPlan fromJson(String s) {
        JsonTree.Obj o = JsonTree.object(s);
        NightPlan p = new NightPlan();
        p.night = o.str("night", "");
        p.createdMs = o.lng("createdMs", 0);
        p.latDeg = o.num("latDeg", Double.NaN);
        p.lonDeg = o.num("lonDeg", Double.NaN);
        String z = o.str("zone", "");
        if (!z.isEmpty()) p.zone = TimeZone.getTimeZone(z);
        p.sounds = o.bool("sounds", false);
        p.minBatteryPct = o.integer("minBatteryPct", 20);
        p.armed = o.bool("armed", false);
        p.armedMs = o.lng("armedMs", 0);
        p.summaryDone = o.bool("summaryDone", false);
        p.nextId = Math.max(1, o.integer("nextId", 1));
        for (JsonTree.Obj x : o.objs("items")) {
            Item it = new Item();
            it.id = x.str("id", "");
            if (it.id.isEmpty()) it.id = "s" + p.nextId++;
            it.trails = x.bool("trails", false);
            it.rule = parseRule(x.str("rule", "DARK"));
            it.clockMs = x.lng("clockMs", 0);
            it.offsetMin = x.integer("offsetMin", 0);
            it.durationSec = Math.max(60, x.integer("durationSec", 300));
            it.endMs = x.lng("endMs", 0);
            it.onlyIfClear = x.bool("onlyIfClear", true);
            it.giveUpMs = x.lng("giveUpMs", 0);
            it.waitMin = x.integer("waitMin", 0);
            it.preset = x.str("preset", "");
            it.repeatOf = x.str("repeatOf", "");
            it.repeatOfMs = x.lng("repeatOfMs", 0);
            it.startMs = x.lng("startMs", 0);
            it.when = x.str("when", "");
            it.dawnMs = x.lng("dawnMs", 0);
            it.state = parseState(x.str("state", "PLANNED"));
            it.tries = x.integer("tries", 0);
            it.nextTryMs = x.lng("nextTryMs", 0);
            it.startedMs = x.lng("startedMs", 0);
            it.endedMs = x.lng("endedMs", 0);
            it.outcome = x.str("outcome", "");
            it.lastWait = x.str("lastWait", "");
            it.photo = x.str("photo", "");
            it.session = x.str("session", "");
            it.thumb = x.str("thumb", "");
            it.spoken = x.str("spoken", "");
            p.items.add(it);
        }
        p.log.addAll(o.strs("log"));
        return p;
    }

    private static Rule parseRule(String s) {
        try {
            return Rule.valueOf(s);
        } catch (IllegalArgumentException e) {
            return Rule.DARK;
        }
    }

    private static State parseState(String s) {
        try {
            return State.valueOf(s);
        } catch (IllegalArgumentException e) {
            return State.PLANNED;
        }
    }
}
