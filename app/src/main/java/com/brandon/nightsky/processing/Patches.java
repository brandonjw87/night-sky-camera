package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeSet;

/**
 * Mission patches (2.3): badges earned for what a night's photos catch, like an astronaut's patches, shown in Mission
 * Control and on the phone. Each finished session is checked against them all ({@link #session}); what's earned is
 * kept (with when, how often, and what earned it first), with the few things that build up over nights (the
 * constellations photographed, the nights out). Stored as JSON (in the app's preferences).
 */
public final class Patches {
    /** A patch: its id, name, what earns it, and how it's drawn (a colour and a shape for the badge). */
    public static final class Def {
        public final String id, name, what, colour, shape;

        Def(String id, String name, String what, String colour, String shape) {
            this.id = id;
            this.name = name;
            this.what = what;
            this.colour = colour;
            this.shape = shape;
        }
    }

    public static final Def[] ALL = {
            new Def("first-light", "First Light", "Finish your first night sky photo.", "#f5c542", "star"),
            new Def("night-owl", "Night Owl", "Take a photo after midnight.", "#7e57c2", "owl"),
            new Def("marathon", "Marathon", "Keep the camera going for 30 minutes or more.", "#ef6c00", "clock"),
            new Def("seven-nights", "Seven Nights", "Take photos on seven different nights.", "#26a69a", "seven"),
            new Def("sat-spotter", "Satellite Spotter", "Catch a satellite crossing your picture.", "#42a5f5", "satellite"),
            new Def("sat-swarm", "Satellite Swarm", "Catch five satellites in one night.", "#1e88e5", "swarm"),
            new Def("station", "Space Station", "Catch a space station (the ISS or Tiangong) in your picture.", "#e53935", "station"),
            new Def("meteor", "Meteor Hunter", "Catch a meteor: a shooting star.", "#ff7043", "meteor"),
            new Def("plane", "Plane Spotter", "Catch a plane's blinking lights. (Not a UFO. Probably.)", "#8d6e63", "plane"),
            new Def("planet", "Planet Hunter", "Get a planet in your picture.", "#ffa726", "planet"),
            new Def("moon", "Moonwalker", "Get the Moon in your picture.", "#bdbdbd", "moon"),
            new Def("galaxy", "Deep Space", "Get another galaxy in your picture, millions of light-years away.", "#ab47bc", "galaxy"),
            new Def("dsn", "Deep Space Network", "Get a spacecraft that's leaving the Solar System in your picture: a Voyager, a "
                    + "Pioneer or New Horizons.", "#26c6da", "dish"),
            new Def("pluto", "Pluto Patrol", "Get Pluto in your picture. (It's far too faint to see. It still counts.)", "#bcaaa4", "pluto"),
            new Def("core", "Heart of the Galaxy", "Get the middle of the Milky Way in your picture.", "#ffca28", "core"),
            new Def("time-traveller", "Time Traveller", "Catch starlight that left its star more than 1,000 years ago.", "#5c6bc0", "hourglass"),
            new Def("star-counter", "Star Counter", "Find 1,000 stars in one picture.", "#fff176", "count"),
            new Def("collector", "Constellation Collector", "Photograph ten different constellations.", "#66bb6a", "lines"),
            new Def("namer", "Star Namer", "Give a star a name of your own.", "#ec407a", "tag"),
            new Def("commander", "Mission Commander", "Watch a mission from Mission Control on a computer.", "#29b6f6", "headset"),
    };

    /** How a patch was earned. */
    public static final class Earned {
        public String id = "";
        /** When it was first and last earned (Unix ms), how many sessions earned it, and what earned it first. */
        public long firstMs, lastMs;
        public int times;
        public String first = "";
    }

    /** What a finished session caught: what the patches go by. */
    public static final class Facts {
        public long startMs, endMs;
        public TimeZone zone = TimeZone.getDefault();
        /** Seconds the camera ran. */
        public double seconds;
        /** Satellites caught (names), planes and meteors. */
        public final List<String> satellites = new ArrayList<String>();
        public int planes, meteors;
        /** Planets in the picture (names), the Moon, galaxies (names), the Milky Way's middle. */
        public final List<String> planets = new ArrayList<String>(), galaxies = new ArrayList<String>();
        public boolean moon, core;
        /** The farthest star's light-years, and the stars found in the photo. */
        public double farthestLy = Double.NaN;
        public String farthestName = "";
        public int stars;
        /** Constellations in the picture. */
        public final List<String> constellations = new ArrayList<String>();
        /** Spacecraft leaving the Solar System in the picture (names), and whether Pluto is in it (2.4). */
        public final List<String> craft = new ArrayList<String>();
        public boolean pluto;
        /** Stars named this session, and whether Mission Control watched it. */
        public int named;
        public boolean watched;
    }

    private final Map<String, Earned> earned = new LinkedHashMap<String, Earned>();
    private final TreeSet<String> constellations = new TreeSet<String>();
    /** The nights out (local dates of the evenings, "2026-11-21"). */
    private final TreeSet<String> nights = new TreeSet<String>();
    /** Missions so far (sessions watched from Mission Control or not), for their numbers. */
    private int missions;

    /** The next mission's number (counted, so it's kept with the patches). */
    public synchronized int nextMission() {
        return ++missions;
    }

    public synchronized boolean has(String id) {
        return earned.containsKey(id);
    }

    public synchronized int count() {
        return earned.size();
    }

    public synchronized int constellationsSeen() {
        return constellations.size();
    }

    public synchronized int nightsOut() {
        return nights.size();
    }

    public static Def def(String id) {
        for (Def d : ALL) if (d.id.equals(id)) return d;
        return null;
    }

    /**
     * Earns a patch now and then (outside a session: naming a star, opening Mission Control). Returns it if this is
     * the first time.
     */
    public synchronized Def earn(String id, String what, long now) {
        Def d = def(id);
        if (d == null) return null;
        Earned e = earned.get(id);
        boolean fresh = e == null;
        if (fresh) {
            e = new Earned();
            e.id = id;
            e.firstMs = now;
            e.first = what == null ? "" : what;
            earned.put(id, e);
        }
        e.lastMs = now;
        e.times++;
        return fresh ? d : null;
    }

    /** What a finished session earns; the patches earned for the first time, in the list's order. */
    public synchronized List<Def> session(Facts f, long now) {
        List<Def> fresh = new ArrayList<Def>();
        Calendar c = Calendar.getInstance(f.zone);
        c.setTimeInMillis(f.startMs > 0 ? f.startMs : now);
        // a night is the evening's date: a session at 2 am belongs to the night before
        Calendar ev = (Calendar) c.clone();
        if (ev.get(Calendar.HOUR_OF_DAY) < 12) ev.add(Calendar.DAY_OF_MONTH, -1);
        nights.add(String.format(java.util.Locale.US, "%04d-%02d-%02d", ev.get(Calendar.YEAR), ev.get(Calendar.MONTH) + 1,
                ev.get(Calendar.DAY_OF_MONTH)));
        constellations.addAll(f.constellations);
        add(fresh, "first-light", true, "your first photo", now);
        Calendar end = Calendar.getInstance(f.zone);
        end.setTimeInMillis(f.endMs > 0 ? f.endMs : now);
        int h0 = c.get(Calendar.HOUR_OF_DAY), h1 = end.get(Calendar.HOUR_OF_DAY);
        add(fresh, "night-owl", (h0 >= 0 && h0 < 5) || (h1 >= 0 && h1 < 5 && f.endMs - f.startMs < 12 * 3600_000L),
                "a photo at " + String.format(java.util.Locale.US, "%d:%02d", h0 == 0 ? 12 : h0 > 12 ? h0 - 12 : h0,
                        c.get(Calendar.MINUTE)) + (h0 < 12 ? " am" : " pm"), now);
        add(fresh, "marathon", f.seconds >= 30 * 60, Math.round(f.seconds / 60) + " minutes", now);
        add(fresh, "seven-nights", nights.size() >= 7, "your seventh night", now);
        add(fresh, "sat-spotter", !f.satellites.isEmpty(), f.satellites.isEmpty() ? "" : f.satellites.get(0), now);
        add(fresh, "sat-swarm", f.satellites.size() >= 5, f.satellites.size() + " satellites in one night", now);
        String station = null;
        for (String s : f.satellites) if (isStation(s)) station = s;
        add(fresh, "station", station != null, station, now);
        add(fresh, "meteor", f.meteors > 0, f.meteors == 1 ? "a meteor" : f.meteors + " meteors", now);
        add(fresh, "plane", f.planes > 0, f.planes == 1 ? "a plane" : f.planes + " planes", now);
        add(fresh, "planet", !f.planets.isEmpty(), f.planets.isEmpty() ? "" : f.planets.get(0), now);
        add(fresh, "moon", f.moon, "the Moon", now);
        add(fresh, "galaxy", !f.galaxies.isEmpty(), f.galaxies.isEmpty() ? "" : f.galaxies.get(0), now);
        add(fresh, "dsn", !f.craft.isEmpty(), f.craft.isEmpty() ? "" : f.craft.get(0), now);
        add(fresh, "pluto", f.pluto, "Pluto", now);
        add(fresh, "core", f.core, "the middle of the Milky Way", now);
        add(fresh, "time-traveller", f.farthestLy > 1000, f.farthestName + (f.farthestLy == f.farthestLy
                ? String.format(java.util.Locale.US, ", %,.0f light-years away", f.farthestLy) : ""), now);
        add(fresh, "star-counter", f.stars >= 1000, String.format(java.util.Locale.US, "%,d stars", f.stars), now);
        add(fresh, "collector", constellations.size() >= 10, constellations.size() + " constellations", now);
        add(fresh, "namer", f.named > 0, "", now);
        add(fresh, "commander", f.watched, "", now);
        return fresh;
    }

    private void add(List<Def> fresh, String id, boolean got, String what, long now) {
        if (!got) return;
        Def d = earn(id, what, now);
        if (d != null) fresh.add(d);
    }

    /** Whether a satellite's name is a space station's. */
    public static boolean isStation(String name) {
        if (name == null) return false;
        String n = name.toUpperCase(java.util.Locale.US);
        return n.equals("ISS") || n.startsWith("ISS ") || n.contains("ZARYA") || n.contains("TIANGONG") || n.contains("TIANHE")
                || n.contains("CSS (") || n.equals("CSS") || n.contains("SPACE STATION");
    }

    /** All the patches with what's earned, for Mission Control and the phone: {"earned": n, "patches": [...]}. */
    public synchronized String json() {
        Json j = new Json().obj().put("earned", earned.size()).put("of", ALL.length);
        j.put("constellations", constellations.size()).put("nights", nights.size());
        j.arr("patches");
        for (Def d : ALL) {
            Earned e = earned.get(d.id);
            j.obj().put("id", d.id).put("name", d.name).put("what", d.what).put("colour", d.colour).put("shape", d.shape);
            if (e != null) j.put("got", true).put("first", e.firstMs).put("last", e.lastMs).put("times", e.times).put("for", e.first);
            j.end();
        }
        return j.end().end().toString();
    }

    /** What's kept between nights. */
    public synchronized String toJson() {
        Json j = new Json().obj();
        j.arr("earned");
        for (Earned e : earned.values()) {
            j.obj().put("id", e.id).put("first", e.firstMs).put("last", e.lastMs).put("times", e.times).put("for", e.first).end();
        }
        j.end();
        j.arr("cons");
        for (String c : constellations) j.val(c);
        j.end();
        j.arr("nights");
        for (String n : nights) j.val(n);
        j.end();
        j.put("missions", missions);
        return j.end().toString();
    }

    /** What {@link #toJson} wrote; nothing (and no error) if the text isn't that. */
    public static Patches fromJson(String text) {
        Patches p = new Patches();
        if (text == null || text.trim().isEmpty()) return p;
        try {
            Object o = JsonTree.parse(text);
            if (!(o instanceof Map)) return p;
            @SuppressWarnings("unchecked")
            JsonTree.Obj root = new JsonTree.Obj((Map<String, Object>) o);
            for (Object x : root.list("earned")) {
                if (!(x instanceof Map)) continue;
                @SuppressWarnings("unchecked")
                JsonTree.Obj e = new JsonTree.Obj((Map<String, Object>) x);
                Earned r = new Earned();
                r.id = e.str("id", "");
                if (def(r.id) == null) continue;
                r.firstMs = e.lng("first", 0);
                r.lastMs = e.lng("last", r.firstMs);
                r.times = Math.max(1, e.integer("times", 1));
                r.first = e.str("for", "");
                p.earned.put(r.id, r);
            }
            for (Object x : root.list("cons")) if (x instanceof String) p.constellations.add((String) x);
            for (Object x : root.list("nights")) if (x instanceof String) p.nights.add((String) x);
            p.missions = Math.max(0, root.integer("missions", 0));
        } catch (RuntimeException ignored) {
            // a damaged record starts again
        }
        return p;
    }
}
