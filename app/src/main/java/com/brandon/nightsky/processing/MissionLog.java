package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What happened, as Mission Control tells it (2.3): the session's steps, each frame, the satellites crossing the picture
 * and caught in it, other streaks, flyovers of the whole sky, mission patches. The viewer asks for the events after the
 * last one it has and turns them into its voice, sounds and pop-ups; a few keep a small picture (a snapshot of a streak in
 * its frame). Kept in memory only: the last {@link #MAX_EVENTS} events and {@link #MAX_SNAPS} snapshots. Thread-safe.
 */
public final class MissionLog {
    public static final int MAX_EVENTS = 300, MAX_SNAPS = 16;

    /** Kinds of event. */
    public static final String PHASE = "phase", FRAME = "frame", MILESTONE = "milestone", SAT_COMING = "sat-coming",
            SAT_CAUGHT = "sat-caught", STREAK = "streak", FLYOVER = "flyover", PATCH = "patch", METEOR = "meteor", NAMED = "named",
            SKY = "sky", NOTE = "note", PROBLEM = "problem";

    public static final class Event {
        public final int seq;
        public final long ms;
        public final String kind, text;
        /** What the computer says aloud ("" for nothing), and whether it is worth a pop-up. */
        public String say = "";
        public boolean big;
        /** A satellite's or object's name, its NORAD number (0 if none), magnitude (NaN if unknown). */
        public String name = "";
        public int norad;
        public double mag = Double.NaN;
        /** The frame it was seen in (-1 if none), and its snapshot (-1 if none). */
        public int unit = -1, snap = -1;
        /** Where it crossed, in the live picture's reference pixels: x0, y0, x1, y1 (null if nowhere). */
        public float[] line;
        /** When something is coming (Unix ms, 0 if not), e.g. a flyover's rise. */
        public long atMs;
        /** Extra words for the pop-up ("" if none): where to look, what it was. */
        public String detail = "";
        /** Where it crossed in its own frame's pixels (x0, y0, x1, y1; null if nowhere): for the log and the tests. */
        public float[] unitLine;
        /** Where it crossed in its snapshot (x0, y0, x1, y1 in the snapshot's pixels), and the snapshot's size; null if none. */
        public float[] snapLine;
        public int[] snapSize;
        /** Satellites found along one line in the frame (a Starlink train): how many (0 if not part of one). */
        public int trainOf;

        Event(int seq, long ms, String kind, String text) {
            this.seq = seq;
            this.ms = ms;
            this.kind = kind;
            this.text = text;
        }
    }

    private final ArrayDeque<Event> events = new ArrayDeque<Event>();
    private final LinkedHashMap<Integer, byte[]> snaps = new LinkedHashMap<Integer, byte[]>();
    private int nextSeq = 1, nextSnap = 1;
    /** Bumped for each new session, so a page knows to start its story again. */
    private int mission;

    /** The clock events are timed by (null: the system's). */
    private volatile MissionControl.Clock clock;

    void setClock(MissionControl.Clock c) {
        clock = c;
    }

    /** A new session: the story starts again (the numbers keep counting, so a page never mixes the two up). */
    public synchronized void newMission() {
        newMission(0);
    }

    /** A new session, number {@code number} if that's higher than the last one's (the missions so far, kept). */
    public synchronized void newMission(int number) {
        mission = number > mission ? number : mission + 1;
        events.clear();
        snaps.clear();
    }

    public synchronized int mission() {
        return mission;
    }

    public synchronized Event add(String kind, String text, String say) {
        MissionControl.Clock c = clock;
        Event e = new Event(nextSeq++, c != null ? c.now() : System.currentTimeMillis(), kind, text == null ? "" : text);
        e.say = say == null ? "" : say;
        events.addLast(e);
        while (events.size() > MAX_EVENTS) events.pollFirst();
        return e;
    }

    /** Keeps a JPEG for an event; its number, for {@link Event#snap}. */
    public synchronized int putSnap(byte[] jpeg) {
        int id = nextSnap++;
        snaps.put(id, jpeg);
        Iterator<Map.Entry<Integer, byte[]>> it = snaps.entrySet().iterator();
        while (snaps.size() > MAX_SNAPS && it.hasNext()) {
            it.next();
            it.remove();
        }
        return id;
    }

    public synchronized byte[] snap(int id) {
        return snaps.get(id);
    }

    /** The events after number {@code seq}, oldest first. */
    public synchronized List<Event> since(int seq) {
        List<Event> out = new ArrayList<Event>();
        for (Event e : events) if (e.seq > seq) out.add(e);
        return out;
    }

    public synchronized int lastSeq() {
        return nextSeq - 1;
    }

    /** Events of one kind so far this mission. */
    public synchronized int count(String kind) {
        int n = 0;
        for (Event e : events) if (e.kind.equals(kind)) n++;
        return n;
    }

    /** {"mission": n, "last": seq, "events": [...]} with the events after {@code seq}. */
    public String json(int seq) {
        return json(seq, null);
    }

    /** The same with {@code state} (a JSON object, or null) as "state". */
    public String json(int seq, String state) {
        List<Event> es;
        int m, last;
        synchronized (this) {
            es = since(seq);
            m = mission;
            last = lastSeq();
        }
        Json j = new Json().obj().put("mission", m).put("last", last).arr("events");
        for (Event e : es) {
            j.obj().put("seq", e.seq).put("ms", e.ms).put("kind", e.kind).put("text", e.text);
            if (!e.say.isEmpty()) j.put("say", e.say);
            if (e.big) j.put("big", true);
            if (!e.name.isEmpty()) j.put("name", e.name);
            if (e.norad != 0) j.put("norad", e.norad);
            if (!Double.isNaN(e.mag)) j.put("mag", e.mag, 3);
            if (e.unit >= 0) j.put("unit", e.unit);
            if (e.snap >= 0) j.put("snap", e.snap);
            if (e.trainOf > 1) j.put("train", e.trainOf);
            if (e.atMs > 0) j.put("at", e.atMs);
            if (!e.detail.isEmpty()) j.put("detail", e.detail);
            if (e.line != null && e.line.length == 4) {
                j.arr("line");
                for (float v : e.line) j.val(Math.round(v * 10) / 10.0);
                j.end();
            }
            if (e.snapLine != null && e.snapLine.length == 4 && e.snapSize != null && e.snapSize.length == 2) {
                j.arr("snapLine");
                for (float v : e.snapLine) j.val(Math.round(v * 10) / 10.0);
                j.end();
                j.arr("snapSize").val(e.snapSize[0]).val(e.snapSize[1]).end();
            }
            j.end();
        }
        j.end();
        if (state != null) j.raw("state", state);
        return j.end().toString();
    }
}
