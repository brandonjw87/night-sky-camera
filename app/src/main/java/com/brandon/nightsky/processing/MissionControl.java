package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Mission Control (2.3): what a computer on the same Wi-Fi shows and says while the phone lies outside collecting
 * starlight. It tells the session's story ({@link MissionLog}): the steps, each frame, milestones, the stars found as the
 * live stack deepens, the sky solved (what the camera is looking at), satellites about to cross the picture and caught
 * in it ({@link StreakWatch}), planes and maybe-meteors, bright satellites flying over anywhere in the sky (the Space
 * Station above all: time to run outside), and at the end the mission patches earned ({@link Patches}). It also keeps
 * what the page draws: the live picture's sky map ({@link SkyMap}, solved on the live stack's own reference frame), the
 * satellites' paths across it ({@link LiveSats}), then the photo's map, and the stars named by you ({@link NamedStars}).
 * <p>
 * The heavy parts (solving the sky, predicting satellites, counting stars) run on a thread of its own at low priority;
 * the streaks are looked for on the units' thread as each unit comes in ({@link #watcher}). Nothing here can stop a
 * session: whatever fails is logged and left out. Plain Java (the phone's JPEG encoder and preferences come in through
 * {@link Jpeg} and {@link Keep}), so whole missions run off the phone.
 */
public final class MissionControl {
    /** Pictures to JPEG (the phone's encoder; ImageIO off the phone). */
    public interface Jpeg {
        byte[] encode(int[] argb, int w, int h, int quality);
    }

    /** Where what lasts between nights is kept (the phone's preferences). */
    public interface Keep {
        void namedStars(String json);

        void patches(String json);
    }

    /** The session as the phone sees it, asked every couple of seconds. */
    public interface Status {
        /** "idle", "arming", "focusing", "checking", "countdown", "capturing", "paused", "developing", "done", "failed". */
        String state();

        /** Words for a problem (the headline's detail). */
        String detail();

        /** Capture: ms run and left (0 when not capturing); seconds of countdown left (NaN if none). */
        long elapsedMs();

        long remainingMs();

        double countdownLeftS();

        /** How the live stack is stretched (for streak snapshots), and the session's brightness. */
        Renderer.Analysis analysis();

        float brightness();
    }

    /** The time (Unix ms): the phone's clock; a simulated session's own off the phone. */
    public interface Clock {
        long now();
    }

    private static final Clock SYSTEM = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };

    private volatile Clock clock = SYSTEM;

    public void setClock(Clock c) {
        clock = c == null ? SYSTEM : c;
        log.setClock(c);
    }

    private long now() {
        return clock.now();
    }

    /** A developed photo, for its map, its song and its patches. */
    public static final class Photo {
        /** The photo as shown (plain: no names drawn on it, not turned), and its size. */
        public byte[] jpeg;
        public int width, height;
        /** Where it pointed (the stack's plate solution, null if unsolved) and when (UTC Julian date, NaN if unknown). */
        public SkySolver.Solution sol;
        public double jdUtc = Double.NaN;
        /** The photo's crop in the stack's pixels: x0, y0, w, h. */
        public int[] crop;
        /** The small copy (for counting its stars), the develop step's streaks and meteors. */
        public RgbImage small;
        public SatMatch.Result streaks;
        public int meteors;
        /** When the capture ran (Unix ms) and for how long (s). */
        public long startMs, endMs;
        public double seconds;
        /** A session just taken (its story ends and its patches are earned), not one opened again. */
        public boolean fresh;
        /** The constellations in it (names). */
        public List<String> constellations = new ArrayList<String>();
    }

    /** Most satellites said aloud as coming, a minute apart at most; dimmer ones only shown. */
    static final double SAY_MAG = 4.0, PATH_MAG = 6.5, FLY_MAG = 3.0, FLY_MIN_ALT = 25;
    /** Flyovers of satellites other than the space stations: only this bright or brighter, this far apart at least. */
    static final double FLY_MAG_BIG = 2.0;
    static final long FLYOVER_GAP_MS = 5 * 60_000L;
    static final long SAY_GAP_MS = 20_000, COMING_CHECK_MS = 10_000, FLYOVER_CHECK_MS = 4 * 60_000L;
    /** Flyovers looked for this far ahead (minutes), and said again this long before (ms). */
    static final double FLYOVER_AHEAD_MIN = 40;
    static final long FLYOVER_WARN_MS = 120_000;
    /** Frames and stars that are milestones. */
    static final int[] FRAME_MILESTONES = {10, 25, 50, 100, 150, 200, 300, 500, 750, 1000};
    static final int[] STAR_MILESTONES = {100, 250, 500, 1000, 2000, 3000, 5000, 10000};
    /** Speeds for the counters: the ground around the Earth's axis at the equator, the Earth around the Sun (km/s). */
    static final double SPIN_KM_S = 0.4651, ORBIT_KM_S = 29.78, LIGHT_KM_S = 299792.458;
    /** A name's star is looked for this far (share of the crop's width) from where it was clicked. */
    static final double PICK_SHARE = 0.015;

    public final MissionLog log = new MissionLog();
    private final NamedStars named;
    private final Patches patches;
    private final Jpeg jpeg;
    private final Keep keep;
    private final ScheduledExecutorService worker;
    private volatile Status status;
    private volatile Session s;

    /** One session's state. */
    private final class Session {
        final long startMs = now();
        final SessionSky sky;
        final BrightStars cat;
        final SatCatalog sats;
        final int W, H, plannedUnits;
        final boolean live;
        /** The units' thread's satellite paths (each unit's, in its own pixels), and the worker's (the live picture's). */
        LiveSats unitSats, workSats;
        StreakWatch watch;
        CameraModel model;
        volatile UnitProcessor.Reference ref;
        volatile SkySolver.Solution liveSol;
        volatile double liveJd = Double.NaN;
        volatile SkyMap liveMap;
        volatile int[] liveCrop;
        volatile int liveVersion;
        volatile SkyMap photoMap;
        volatile int[] photoCrop;
        volatile byte[] photoJpeg;
        volatile int photoW, photoH;
        volatile String songJson;
        volatile List<StarSong.Note> song;
        // counters
        volatile int frames, starsFound, satsCaught, planes, meteors, streaks, named;
        volatile double lightS;
        volatile long captureStartMs;
        long t0Ns;
        volatile boolean solving, solveFailed, watched, over;
        int solveTries;
        String state = "";
        boolean saidHalf, saidMinute;
        int nextFrameMilestone, nextStarMilestone;
        final Set<Integer> comingSaid = new HashSet<Integer>();
        final Set<String> flyoversSaid = new HashSet<String>(), flyoversWarned = new HashSet<String>();
        final List<LiveSats.Flyover> flyovers = new ArrayList<LiveSats.Flyover>();
        final List<String> caught = new ArrayList<String>();
        /** The unit each satellite (NORAD number) was last caught in, and the mystery satellites' passes counted. */
        final java.util.Map<Integer, Integer> lastCaught = new java.util.HashMap<Integer, Integer>();
        int mysteries;
        /** Starlink trains seen (their first unit), and the snapshots taken of maybe-meteors held back a unit. */
        final java.util.Map<Integer, Integer> trains = new java.util.HashMap<Integer, Integer>();
        /** The satellites of the trains already told about (NORAD numbers). */
        final Set<Integer> trainSats = new HashSet<Integer>();
        final java.util.IdentityHashMap<StreakWatch.Streak, Snap> heldSnaps = new java.util.IdentityHashMap<StreakWatch.Streak, Snap>();
        long lookMs;
        int looks;
        long lastComingMs, lastFlyoverMs, lastSaidMs, lastFlyoverSaidMs;
        volatile String satsJson;
        volatile long satsJsonMs;
        volatile String skyJson;
        volatile int skyJsonKey = Integer.MIN_VALUE;

        Session(SessionSky sky, BrightStars cat, SatCatalog sats, int W, int H, int plannedUnits, boolean live) {
            this.sky = sky;
            this.cat = cat;
            this.sats = sats;
            this.W = W;
            this.H = H;
            this.plannedUnits = plannedUnits;
            this.live = live;
        }

        SatSky.Site site() {
            if (sky == null || !sky.located()) return null;
            SatSky.Site site = new SatSky.Site(Math.toRadians(sky.latDeg), Math.toRadians(sky.lonDeg),
                    Double.isNaN(sky.altM) ? 0 : sky.altM / 1000);
            site.errKm = Double.isNaN(sky.accuracyM) ? 0 : Math.min(5, sky.accuracyM / 1000);
            return site;
        }
    }

    public MissionControl(String namedJson, String patchesJson, Jpeg jpeg, Keep keep) {
        this.named = NamedStars.fromJson(namedJson);
        this.patches = Patches.fromJson(patchesJson);
        this.jpeg = jpeg;
        this.keep = keep;
        worker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nightsky-mission");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            }
        });
        worker.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    tick(now());
                } catch (Throwable t) {
                    // never let one bad tick stop the next
                    note("mission control: " + t);
                }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    public void setStatus(Status st) {
        status = st;
    }

    public NamedStars named() {
        return named;
    }

    public Patches patches() {
        return patches;
    }

    // ------------------------------------------------------------------------------------------------
    // The session's steps

    /**
     * A new session's capture is about to start: W x H frames, about {@code units} of them, where and when
     * ({@code sky}), the star catalogue and the satellites (either may be null). {@code live}: someone may watch
     * (the viewer is on), so the sky is solved and the streaks looked for as frames come in.
     */
    public void newSession(SessionSky sky, BrightStars cat, SatCatalog sats, int W, int H, int units, boolean live) {
        Session old = s;
        if (old != null) old.over = true;
        log.newMission(patches.nextMission());
        if (keep != null) keep.patches(patches.toJson());
        Session n = new Session(sky, cat, sats, W, H, units, live);
        if (sats != null && n.site() != null) {
            n.unitSats = new LiveSats(sats, n.site());
            n.workSats = new LiveSats(sats, n.site());
        }
        s = n;
        MissionLog.Event e = log.add(MissionLog.PHASE, "Mission Control is online. A new mission begins!",
                "Mission Control online. A new mission begins!");
        e.name = "online";
    }

    /** The capture's first frame is on its way. */
    public void captureStarted(long nowMs) {
        Session n = s;
        if (n == null) return;
        n.captureStartMs = nowMs;
    }

    /** Looks at each unit for streaks (on the units' thread), or null if nobody watches. */
    public UnitProcessor.UnitWatcher watcher(final UnitProcessor up) {
        final Session n = s;
        if (n == null || !n.live) return null;
        n.model = up.model();
        n.watch = new StreakWatch(n.W, n.H, n.model, Math.max(1, n.plannedUnits));
        return new UnitProcessor.UnitWatcher() {
            @Override
            public void unit(short[] raw, UnitRecord r, StarList stars, Aligner.Result live, PreviewAccumulator preview) {
                if (n != s || n.over) return;
                watchUnit(n, up, raw, r, stars, live, preview);
            }
        };
    }

    private void watchUnit(Session n, UnitProcessor up, short[] raw, UnitRecord r, StarList stars, Aligner.Result live,
                           PreviewAccumulator preview) {
        if (n.t0Ns == 0 && r.sensorTimestampNs != 0) n.t0Ns = r.sensorTimestampNs;
        currentStars = stars;
        if (n.ref == null && up.reference() != null) {
            n.ref = up.reference();
            final Session fn = n;
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    solveLive(fn, null, null);
                }
            });
        }
        // the satellites predicted to cross this unit, in its own pixels
        List<SatPredict.Track> tracks = null;
        SkySolver.Solution sol = n.liveSol;
        if (sol != null && live.ok && live.R != null && n.unitSats != null && n.sky.clockValid && r.sensorTimestampNs != 0) {
            SkySolver.Solution us = new SkySolver.Solution();
            us.R = Rot.mul(sol.R, Rot.transpose(live.R));
            us.model = sol.model;
            double jd0 = n.sky.jdUtc(r.sensorTimestampNs);
            double span = PostProcessor.unitSpan(r, n.t0Ns);
            tracks = n.unitSats.crossing(us, n.W, n.H, jd0, jd0 + span / 86400, PostProcessor.SAT_MAG_LIMIT, 0);
        }
        StreakWatch.Look look = n.watch.unit(raw, r.invScale, r.black, stars, r.index, live.ok ? live.R : null, tracks,
                r.disturbed || !live.ok && live.R == null && n.ref != null);
        n.lookMs += look.ms;
        n.looks++;
        if (n.looks == 1 || n.looks % 25 == 0) {
            note(String.format(Locale.US, "mission control: streak watch, unit %d: %s (%d ms; %.0f ms a unit so far)", r.index + 1,
                    look.note, look.ms, n.lookMs / (double) n.looks));
        }
        if (look.streaks.isEmpty() && look.held.isEmpty()) return;
        RgbImage thumb = preview.thumbnail(raw, r.invScale, r.black, null);
        int stacked = Math.max(1, preview.units());
        // a maybe-meteor is held back a unit (is it a satellite that goes on?): its picture is taken now, from its own unit
        for (StreakWatch.Streak st : look.held) {
            Snap sn = prepare(st, st.whole, thumb, stacked);
            synchronized (n.heldSnaps) {
                n.heldSnaps.put(st, sn != null ? sn : NO_SNAP);
            }
        }
        report(n, look.streaks, thumb, stacked);
        // held-back ones that turned out to be nothing (the same place again) are let go without a word
        synchronized (n.heldSnaps) {
            java.util.Iterator<StreakWatch.Streak> it = n.heldSnaps.keySet().iterator();
            while (it.hasNext()) if (it.next().unit < r.index) it.remove();
        }
    }

    /** Streaks to tell about: each satellite once (the frames after quietly), a train as one, the rest as they come. */
    private void report(Session n, List<StreakWatch.Streak> streaks, RgbImage thumb, int stacked) {
        synchronized (n) {
            reportLocked(n, streaks, thumb, stacked);
        }
    }

    private void reportLocked(Session n, List<StreakWatch.Streak> streaks, RgbImage thumb, int stacked) {
        // a Starlink train: one big catch for the whole line, its satellites each quietly
        java.util.Set<float[]> trainsDone = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<float[], Boolean>());
        for (StreakWatch.Streak st : streaks) {
            if (st.train < 3 || st.whole == null || trainsDone.contains(st.whole)) continue;
            trainsDone.add(st.whole);
            train(n, st, streaks, thumb, stacked);
        }
        for (StreakWatch.Streak st : streaks) {
            Snap sn = null;
            boolean quiet = st.train >= 3 && st.whole != null;
            if (st.held) {
                synchronized (n.heldSnaps) {
                    sn = n.heldSnaps.remove(st);
                }
                if (sn == NO_SNAP) sn = null;
            }
            SatCatalog.Sat sat = st.sat();
            boolean again = false;
            if (sat != null) {
                Integer last = n.lastCaught.get(sat.norad);
                again = last != null && st.unit - last <= 3 && st.unit >= last;
                n.lastCaught.put(sat.norad, Math.max(st.unit, last != null ? last : Integer.MIN_VALUE));
            } else if (StreakWatch.UNLISTED.equals(st.kind)) {
                // told about when first known for one (it may have looked like a meteor or a streak the unit before)
                again = st.continued && StreakWatch.UNLISTED.equals(st.fromKind);
            }
            if (!quiet && !again && sn == null && !st.held) sn = prepare(st, null, thumb, stacked);
            caught(n, st, sn, quiet || again);
        }
    }

    /** No snapshot (a held-back streak's map can't hold null). */
    private static final Snap NO_SNAP = new Snap();

    /** A snapshot: the JPEG, the streak in its pixels (x0, y0, x1, y1) and its size. */
    static final class Snap {
        byte[] jpeg;
        float[] line;
        int[] size;
    }

    /** A Starlink train: three or more satellites along one line in one unit, told about once. */
    private void train(Session n, StreakWatch.Streak first, List<StreakWatch.Streak> all, RgbImage thumb, int stacked) {
        List<StreakWatch.Streak> members = new ArrayList<StreakWatch.Streak>();
        for (StreakWatch.Streak st : all) if (st.whole == first.whole) members.add(st);
        boolean starlink = true;
        boolean seen = false;
        List<String> names = new ArrayList<String>();
        for (StreakWatch.Streak st : members) {
            SatCatalog.Sat sat = st.sat();
            if (sat == null) continue;
            names.add(sat.displayName());
            starlink &= sat.displayName().toUpperCase(Locale.US).startsWith("STARLINK");
            // a train is told about once, the first time three or more of it are in one frame
            seen |= n.trainSats.contains(sat.norad);
        }
        for (StreakWatch.Streak st : members) if (st.sat() != null) n.trainSats.add(st.sat().norad);
        int count = members.size();
        String what = starlink ? "Starlink train" : "satellite train";
        MissionLog.Event e;
        if (seen) {
            e = log.add(MissionLog.SAT_CAUGHT, String.format(Locale.US, "The %s is still crossing: %d satellites in a row in frame %d.", what,
                    count, first.unit + 1), "");
            e.big = false;
        } else {
            e = log.add(MissionLog.SAT_CAUGHT, String.format(Locale.US, "A %s! %d satellites in a row crossed frame %d, like pearls on a string: %s.",
                    what, count, first.unit + 1, list(names)), starlink ? "Whoa! A Starlink train! " + count + " satellites in a row!"
                    : "Whoa! " + count + " satellites in a row!");
            e.big = true;
            e.detail = starlink ? "Starlink satellites are launched 20 or more at a time, so for a few weeks they fly in a line, one "
                    + "after another, before they spread out. Each one beams the internet down to the ground." : "Satellites launched together "
                    + "fly one after another for a while before they spread out.";
            Snap sn = prepare(first, first.whole, thumb, stacked);
            if (sn != null) {
                e.snap = log.putSnap(sn.jpeg);
                e.snapLine = sn.line;
                e.snapSize = sn.size;
            }
        }
        e.name = what;
        e.unit = first.unit;
        float[] w = first.whole;
        if (!Float.isNaN(first.rx0)) {
            // the whole line in the reference frame's pixels: from the first piece's start to the last's end
            StreakWatch.Streak last = members.get(members.size() - 1);
            e.line = new float[]{first.rx0, first.ry0, last.rx1, last.ry1};
        }
        e.trainOf = count;
        if (!seen) n.trains.put(first.unit, count);
    }

    private static String list(List<String> names) {
        if (names.isEmpty()) return "";
        if (names.size() == 1) return names.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) b.append(i == names.size() - 1 ? " and " : ", ");
            b.append(names.get(i));
        }
        return b.toString();
    }

    /** The session is over: the maybe-meteors held back from the last unit, told about now. */
    private void finishWatch(Session n) {
        StreakWatch w = n.watch;
        if (w == null) return;
        List<StreakWatch.Streak> rest = w.finish();
        if (!rest.isEmpty()) report(n, rest, null, 1);
    }

    /** A streak found in a unit: its event (quiet: the same satellite again, or one of a train), the counters. */
    private void caught(Session n, StreakWatch.Streak st, Snap sn, boolean quiet) {
        int frame = st.unit + 1;
        MissionLog.Event e;
        SatCatalog.Sat sat = st.sat();
        if (sat != null) {
            String name = sat.displayName();
            boolean station = Patches.isStation(name);
            synchronized (n.caught) {
                if (!n.caught.contains(name)) {
                    n.caught.add(name);
                    n.satsCaught++;
                }
            }
            float mag = st.track.brightest();
            if (quiet) {
                e = log.add(MissionLog.SAT_CAUGHT, String.format(Locale.US, "%s crossed frame %d too.", SatPlanner.capital(SatPlanner.the(name)), frame), "");
            } else {
                e = log.add(MissionLog.SAT_CAUGHT, String.format(Locale.US, "Got it! %s crossed frame %d.", SatPlanner.capital(SatPlanner.the(name)), frame),
                        station ? "Got it! The Space Station is in the picture!" : "Got it! " + spoken(name) + "!");
            }
            e.name = name;
            e.norad = sat.norad;
            if (mag < 90) e.mag = mag;
            e.detail = satFacts(sat, st.track);
        } else if (StreakWatch.UNLISTED.equals(st.kind)) {
            if (!quiet) {
                n.mysteries++;
                n.satsCaught++;
                synchronized (n.caught) {
                    n.caught.add("a mystery satellite " + n.mysteries);
                }
                e = log.add(MissionLog.STREAK, String.format(Locale.US, "A satellite that isn't on my list crossed frame %d.", frame),
                        "Mystery satellite! It isn't on my list.");
            } else {
                e = log.add(MissionLog.STREAK, String.format(Locale.US, "The mystery satellite crossed frame %d too.", frame), "");
            }
            e.name = "mystery satellite";
            e.detail = "It kept going from one frame to the next, so it's something in orbit. There are thousands of "
                    + "satellites and bits of rockets up there, and not all of them are on the list.";
        } else if (StreakWatch.PLANE.equals(st.kind)) {
            n.planes++;
            e = log.add(MissionLog.STREAK, String.format(Locale.US, "A plane flew through frame %d. Its lights blink, so the trail is dotted.", frame),
                    "Plane spotted! Not a U F O. Probably.");
            e.name = "plane";
            e.detail = "Planes have blinking white strobes and red and green lights on their wing tips, so they leave dotted trails.";
        } else if (StreakWatch.METEOR.equals(st.kind)) {
            n.meteors++;
            e = log.add(MissionLog.METEOR, String.format(Locale.US, "A streak in frame %d. Maybe a meteor!", frame),
                    "Was that a meteor? Make a wish!");
            e.name = "meteor?";
            e.detail = "A meteor is a speck of space dust, often smaller than a grain of sand, burning up 100 kilometres up as it "
                    + "hits the air at up to 70 kilometres a second. It lasted less than a frame and didn't go on in the next, "
                    + "so it wasn't a satellite. The photo's develop step takes a closer look afterwards.";
        } else {
            n.streaks++;
            e = log.add(MissionLog.STREAK, String.format(Locale.US, "Something streaked across frame %d.", frame), "Something zipped through!");
            e.name = "streak";
            e.detail = "Maybe a satellite that's hard to see, maybe a plane far away. The develop step takes a closer look afterwards.";
        }
        e.big = !quiet;
        e.unit = st.unit;
        if (st.train > 1) e.trainOf = st.train;
        if (!Float.isNaN(st.rx0)) e.line = new float[]{st.rx0, st.ry0, st.rx1, st.ry1};
        e.unitLine = new float[]{st.x0, st.y0, st.x1, st.y1};
        if (sn != null) {
            e.snap = log.putSnap(sn.jpeg);
            e.snapLine = sn.line;
            e.snapSize = sn.size;
        }
    }

    /** A satellite's facts for its pop-up. */
    private static String satFacts(SatCatalog.Sat sat, SatPredict.Track t) {
        StringBuilder b = new StringBuilder();
        if (t != null && t.minRangeKm < Double.MAX_VALUE) {
            b.append(String.format(Locale.US, "It was %,.0f km away", t.minRangeKm));
            float sp = t.speed();
            if (sp == sp) b.append(String.format(Locale.US, ", crossing the picture at %.0f pixels a second", sp));
            b.append(". ");
        }
        String n = sat.displayName().toUpperCase(Locale.US);
        if (Patches.isStation(sat.displayName())) {
            b.append("Astronauts live up there! It goes around the whole Earth every 90 minutes, at 28,000 km an hour.");
        } else if (n.startsWith("STARLINK")) {
            b.append("Starlink satellites beam the internet down to the ground. There are thousands of them, about 550 km up.");
        } else {
            b.append(String.format(Locale.US, "Satellite number %d in the world's list of things in orbit.", sat.norad));
        }
        return b.toString();
    }

    /** "Starlink 1234" said as words (the dash read as nothing). */
    static String spoken(String name) {
        return SatPlanner.capital(SatPlanner.the(name)).replace('-', ' ');
    }

    /**
     * A snapshot of a streak in its frame (the unit, with the live stack's colours and a stretch for a single unit's
     * noise: {@code stacked} units' worth more than the stack's), cut around it (or around {@code line}, x0 y0 x1 y1 in
     * sensor pixels, if given); null if there's no picture to take it from.
     */
    private Snap prepare(StreakWatch.Streak st, float[] line, RgbImage thumb, int stacked) {
        try {
            return snapOf(line != null ? line : new float[]{st.x0, st.y0, st.x1, st.y1}, thumb, stacked);
        } catch (RuntimeException | OutOfMemoryError ex) {
            note("mission control: no snapshot: " + ex);
            return null;
        }
    }

    private Snap snapOf(float[] line, RgbImage thumb, int stacked) {
        Status stt = status;
        if (thumb == null || jpeg == null || stt == null) return null;
        Renderer.Analysis a = stt.analysis();
        if (a == null || a.cw <= 0 || a.ch <= 0 || a.cx0 < 0 || a.cy0 < 0 || a.cx0 + a.cw > thumb.width || a.cy0 + a.ch > thumb.height) return null;
        RgbImage view = new RgbImage(thumb.width, thumb.height, thumb.r, thumb.g, thumb.b);
        view.x0 = a.cx0;
        view.y0 = a.cy0;
        view.x1 = a.cx0 + a.cw;
        view.y1 = a.cy0 + a.ch;
        RgbImage src = view.binned(1);
        Renderer.Params p = new Renderer.Params();
        p.brightness = stt.brightness();
        // one unit is noisier than the stack of them by about the square root of how many there are
        int[] px = Renderer.renderSmall(src, Renderer.noisier(a, (float) Math.sqrt(Math.max(1, stacked))), p, 1);
        int sw = src.width, sh = src.height;
        // the streak in the rendered crop's pixels (the thumbnail is a quarter of the sensor's size)
        double f = PreviewAccumulator.FACTOR;
        double ax = line[0] / f - a.cx0, ay = line[1] / f - a.cy0, bx = line[2] / f - a.cx0, by = line[3] / f - a.cy0;
        double pad = Math.max(40, 0.25 * Math.hypot(bx - ax, by - ay));
        int x0 = (int) Math.max(0, Math.floor(Math.min(ax, bx) - pad)), x1 = (int) Math.min(sw, Math.ceil(Math.max(ax, bx) + pad));
        int y0 = (int) Math.max(0, Math.floor(Math.min(ay, by) - pad)), y1 = (int) Math.min(sh, Math.ceil(Math.max(ay, by) + pad));
        // at least a 4:3 box of 320 x 240, kept inside the picture
        int bw = Math.max(320, x1 - x0), bh = Math.max(240, y1 - y0);
        if (bw * 3 < bh * 4) bw = Math.min(sw, (bh * 4 + 2) / 3);
        else bh = Math.min(sh, (bw * 3 + 3) / 4);
        bw = Math.min(bw, sw);
        bh = Math.min(bh, sh);
        int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        x0 = Math.max(0, Math.min(sw - bw, cx - bw / 2));
        y0 = Math.max(0, Math.min(sh - bh, cy - bh / 2));
        int[] cut = new int[bw * bh];
        for (int y = 0; y < bh; y++) System.arraycopy(px, (y0 + y) * sw + x0, cut, y * bw, bw);
        byte[] j = jpeg.encode(cut, bw, bh, 88);
        if (j == null) return null;
        Snap sn = new Snap();
        sn.jpeg = j;
        sn.line = new float[]{(float) (ax - x0), (float) (ay - y0), (float) (bx - x0), (float) (by - y0)};
        sn.size = new int[]{bw, bh};
        return sn;
    }

    /**
     * Each unit as it comes in (the units' thread, after {@link #watcher}'s look): the frame's event, the milestones and
     * the light collected.
     */
    public void unit(UnitRecord r, Aligner.Result live, int unitsDone) {
        Session n = s;
        if (n == null || n.over) return;
        if (n.t0Ns == 0 && r.sensorTimestampNs != 0) n.t0Ns = r.sensorTimestampNs;
        n.frames = unitsDone;
        n.lightS += r.exposure;
        MissionLog.Event e;
        if (unitsDone == 1) {
            e = log.add(MissionLog.MILESTONE, String.format(Locale.US, "Frame 1 is in, with %d stars. %.0f seconds of starlight so far.",
                    r.stars, n.lightS), "First frame is in! " + r.stars + " stars.");
            e.big = true;
        } else {
            e = log.add(MissionLog.FRAME, String.format(Locale.US, "Frame %d is in: %d stars%s.", unitsDone, r.stars,
                    r.disturbed ? ", but the phone moved" : !live.ok ? ", not lined up" : ""), "");
        }
        e.unit = r.index;
        while (n.nextFrameMilestone < FRAME_MILESTONES.length && unitsDone >= FRAME_MILESTONES[n.nextFrameMilestone]) {
            int m = FRAME_MILESTONES[n.nextFrameMilestone++];
            if (m != unitsDone) continue;
            MissionLog.Event me = log.add(MissionLog.MILESTONE, String.format(Locale.US, "%d frames! That's %s of starlight in the bucket.", m,
                    duration(n.lightS)), m + " frames! Keep it up.");
            me.big = m >= 50;
        }
        if (n.liveSol == null && n.solveFailed && n.solveTries < 4 && unitsDone % 6 == 0 && live.ok && live.R != null) {
            // the reference frame's stars didn't solve (a cloud, a smudge): this unit's might, turned back by its rotation
            n.solveTries++;
            final StarList st = currentStars;
            final double[] R = live.R.clone();
            final Session fn = n;
            if (st != null) {
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        solveLive(fn, st, R);
                    }
                });
            }
        }
    }

    /** The stars of the unit being looked at (for a second try at the solve). */
    private volatile StarList currentStars;

    /** The units' stars as they're found (the session thread passes them on for the solve's second tries). */
    public void stars(StarList st) {
        currentStars = st;
    }

    /**
     * The live picture changed: the live stack (its crop {@code crop} in the reference frame's pixels, x0 y0 w h), for
     * the stars found and the sky map's crop.
     */
    public void livePicture(final RgbImage img, int[] crop, int version) {
        final Session n = s;
        if (n == null || n.over || !n.live) return;
        n.liveCrop = crop;
        n.liveVersion = version;
        if (img == null) return;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (n != s) return;
                int found = PictureStars.count(img);
                if (found <= n.starsFound) return;
                n.starsFound = found;
                while (n.nextStarMilestone < STAR_MILESTONES.length && found >= STAR_MILESTONES[n.nextStarMilestone]) {
                    int m = STAR_MILESTONES[n.nextStarMilestone++];
                    if (n.nextStarMilestone < STAR_MILESTONES.length && found >= STAR_MILESTONES[n.nextStarMilestone]) continue;
                    MissionLog.Event e = log.add(MissionLog.MILESTONE, String.format(Locale.US, "%,d stars found! The more frames stack up, "
                            + "the fainter the stars that show.", found), String.format(Locale.US, "%,d stars found!", m));
                    e.big = m >= 1000;
                }
            }
        });
    }

    /** Solves the live sky: the reference frame's stars, or a later unit's turned back by its rotation R (ref -> unit). */
    private void solveLive(Session n, StarList unitStars, double[] R) {
        if (n != s || n.over || n.liveSol != null || n.cat == null || n.model == null) return;
        UnitProcessor.Reference ref = n.ref;
        if (ref == null) return;
        SkySolver.Prior pr = new SkySolver.Prior();
        if (n.sky.clockValid && ref.sensorTimestampNs != 0) pr.jdUtc = n.sky.jdUtc(ref.sensorTimestampNs) + ref.exposure / 2 / 86400.0;
        if (n.sky.located()) {
            pr.lat = n.sky.latDeg * Astro.DEG;
            pr.lon = n.sky.lonDeg * Astro.DEG;
        }
        long t0ms = System.nanoTime() / 1000000;
        StarList stars = unitStars != null ? unitStars : ref.stars;
        SkySolver.Solution sol = SkySolver.solve(stars, stars, n.model, n.cat, pr);
        if (sol != null && R != null) {
            // the unit's camera to J2000, then back to the reference frame's camera
            sol.R = Rot.mul(sol.R, R);
        }
        if (sol == null) {
            n.solveFailed = true;
            if (unitStars == null) {
                log.add(MissionLog.NOTE, "I can't match the stars to my star map yet. I'll try again in a few frames.", "");
            }
            note(String.format(Locale.US, "mission control: the live sky didn't solve (%d stars, %d ms)", stars.count, System.nanoTime() / 1000000 - t0ms));
            return;
        }
        n.liveJd = pr.jdUtc;
        n.liveMap = new SkyMap(sol, pr.jdUtc, n.sky, n.cat, "live");
        n.liveSol = sol;
        note(String.format(Locale.US, "mission control: live sky solved: %s (%d ms)", sol.describe(), System.nanoTime() / 1000000 - t0ms));
        int[] crop = n.liveCrop != null ? n.liveCrop : new int[]{0, 0, n.W, n.H};
        SkyGuide g = SkyGuide.describe(sol, pr.jdUtc, n.sky, n.cat, crop[0], crop[1], crop[2], crop[3]);
        int stars0 = 0, planets = 0;
        String planet = null, object = null;
        boolean moon = false;
        List<String> cons = new ArrayList<String>();
        for (SkyGuide.Item it : g.items) {
            if ("star".equals(it.kind) && !Double.isNaN(it.x)) stars0++;
            else if ("planet".equals(it.kind) && !Double.isNaN(it.x)) {
                planets++;
                if (planet == null) planet = it.name;
            } else if ("moon".equals(it.kind) && !Double.isNaN(it.x)) moon = true;
            else if ("object".equals(it.kind) && !Double.isNaN(it.x) && object == null) object = it.name;
            else if ("constellation".equals(it.kind) && cons.size() < 4) cons.add(it.name);
        }
        StringBuilder t = new StringBuilder("Sky solved! I know exactly where the camera is looking");
        t.append(g.centre.isEmpty() ? "." : ": at " + g.centre + ".");
        if (!cons.isEmpty()) t.append(" In the picture: ").append(join(cons)).append('.');
        if (planet != null) t.append(" There's ").append(planets == 1 ? planet : planets + " planets").append(" too!");
        if (moon) t.append(" And the Moon.");
        if (object != null) t.append(" Look for ").append(object).append('.');
        String say = "Sky solved! The camera is looking at " + (g.centre.isEmpty() ? "the stars" : g.centre) + "."
                + (planet != null ? " I can see " + planet + "!" : "");
        MissionLog.Event e = log.add(MissionLog.SKY, t.toString(), say);
        e.big = true;
        e.detail = String.format(Locale.US, "%d stars matched to the star map, to %.1f pixels. Open Star Spotter to see their names.",
                sol.matched, sol.rmsPx);
        if (stars0 > 0) e.name = g.centre;
    }

    private static String join(List<String> xs) {
        if (xs.size() == 1) return xs.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(i == xs.size() - 1 ? " and " : ", ");
            b.append(xs.get(i));
        }
        return b.toString();
    }

    /** Every couple of seconds: the steps, satellites coming and flyovers. */
    private void tick(long now) {
        Session n = s;
        Status st = status;
        if (n == null || n.over || st == null) return;
        String state = st.state();
        if (!state.equals(n.state)) {
            String was = n.state;
            n.state = state;
            stateChanged(n, was, state, st, now);
        }
        if ("capturing".equals(state)) {
            long el = st.elapsedMs(), left = st.remainingMs();
            if (!n.saidHalf && el > 0 && left > 0 && el >= left && el + left >= 120_000) {
                n.saidHalf = true;
                log.add(MissionLog.MILESTONE, "Halfway there! The phone has been collecting starlight for " + duration(el / 1000.0) + ".",
                        "Halfway there!").big = true;
            }
            if (!n.saidMinute && left > 0 && left <= 60_000 && el + left >= 150_000) {
                n.saidMinute = true;
                log.add(MissionLog.MILESTONE, "One minute to go!", "One minute to go!");
            }
        }
        boolean active = !"done".equals(state) && !"failed".equals(state) && !"idle".equals(state);
        if (!active || !n.live) return;
        if (n.liveSol != null && n.workSats != null && n.sky.clockValid && now - n.lastComingMs >= COMING_CHECK_MS) {
            n.lastComingMs = now;
            coming(n, now);
        }
        if (n.workSats != null && n.sky.clockValid && now - n.lastFlyoverMs >= FLYOVER_CHECK_MS) {
            n.lastFlyoverMs = now;
            flyovers(n, now);
        }
        flyoverWarnings(n, now);
    }

    private void stateChanged(Session n, String was, String state, Status st, long now) {
        MissionLog.Event e = null;
        if ("arming".equals(state)) {
            e = log.add(MissionLog.PHASE, "Waiting for the phone to lie still, screen-down, camera to the sky.", "Waiting for the phone to lie still.");
        } else if ("focusing".equals(state)) {
            e = log.add(MissionLog.PHASE, "Focusing on the stars: to infinity... and beyond!", "Focusing. To infinity, and beyond!");
        } else if ("checking".equals(state)) {
            e = log.add(MissionLog.PHASE, "Pre-flight check: looking for clouds, the Moon and a smudged lens.", "Running the pre-flight check.");
        } else if ("countdown".equals(state)) {
            double left = st.countdownLeftS();
            e = log.add(MissionLog.PHASE, "Countdown! Hands off the phone.", "");
            if (left == left && left > 0) e.atMs = now + Math.round(left * 1000);
            e.big = true;
        } else if ("capturing".equals(state) && !"paused".equals(was)) {
            e = log.add(MissionLog.PHASE, "Liftoff! The camera is collecting starlight.", "Liftoff! The camera is collecting starlight.");
            e.big = true;
            if (n.captureStartMs == 0) n.captureStartMs = now;
        } else if ("capturing".equals(state)) {
            e = log.add(MissionLog.PHASE, "And we're back: collecting starlight again.", "And we're back.");
        } else if ("paused".equals(state)) {
            e = log.add(MissionLog.PHASE, "Paused: " + st.detail(), "Paused.");
        } else if ("developing".equals(state)) {
            finishWatch(n);
            String caught = n.satsCaught > 0 ? String.format(Locale.US, " %d satellite%s caught.", n.satsCaught, n.satsCaught == 1 ? "" : "s") : "";
            e = log.add(MissionLog.PHASE, String.format(Locale.US, "Capture complete: %d frames, %s of starlight.%s Now the phone develops "
                    + "the photo. That takes a minute or two.", n.frames, duration(n.lightS), caught), "Capture complete! Developing the photo.");
            e.big = true;
        } else if ("failed".equals(state)) {
            e = log.add(MissionLog.PROBLEM, "Houston, we have a problem: " + st.detail(), "Houston, we have a problem.");
            e.big = true;
        }
        if (e != null) e.name = state;
    }

    /** Satellites about to cross the live picture (in the next minute and a half): said once each. */
    private void coming(Session n, long now) {
        double jd = Astro.jdUtc(now / 1000.0);
        int[] crop = n.liveCrop != null ? n.liveCrop : new int[]{0, 0, n.W, n.H};
        List<SatPredict.Track> ts = n.workSats.crossing(n.liveSol, n.W, n.H, jd, jd + 90 / 86400.0, PATH_MAG, 0);
        for (SatPredict.Track t : ts) {
            if (n.comingSaid.contains(t.sat.norad)) continue;
            // when it enters the live picture's crop
            int first = -1, last = -1;
            for (int i = 0; i < t.jd.length; i++) {
                if (Float.isNaN(t.x[i]) || t.mag[i] > PATH_MAG) continue;
                if (t.x[i] < crop[0] || t.y[i] < crop[1] || t.x[i] > crop[0] + crop[2] || t.y[i] > crop[1] + crop[3]) continue;
                if (first < 0) first = i;
                last = i;
            }
            if (first < 0) continue;
            long inMs = Math.round((t.jd[first] - 2440587.5) * 86400000.0);
            if (inMs < now + 5000) continue;
            n.comingSaid.add(t.sat.norad);
            float mag = 99;
            for (int i = first; i <= last; i++) mag = Math.min(mag, t.mag[i]);
            String name = t.sat.displayName();
            boolean station = Patches.isStation(name);
            long secs = Math.round((inMs - now) / 1000.0);
            boolean say = (mag <= SAY_MAG || station) && now - n.lastSaidMs >= SAY_GAP_MS;
            if (say) n.lastSaidMs = now;
            MissionLog.Event e = log.add(MissionLog.SAT_COMING, String.format(Locale.US, "%s crosses the picture in %d seconds%s.",
                    SatPlanner.capital(SatPlanner.the(name)), secs, mag < 90 ? String.format(Locale.US, " (magnitude %.1f)", mag) : ""),
                    say ? (station ? "Heads up! The Space Station crosses the picture in " + secs + " seconds!"
                            : "Heads up! " + spoken(name) + " crosses the picture in " + secs + " seconds.") : "");
            e.name = name;
            e.norad = t.sat.norad;
            if (mag < 90) e.mag = mag;
            e.atMs = inMs;
            e.line = new float[]{t.x[first], t.y[first], t.x[last], t.y[last]};
            e.big = station;
        }
    }

    /** Bright satellites going over anywhere in the sky soon: the Space Station first. */
    private void flyovers(Session n, long now) {
        double jd = Astro.jdUtc(now / 1000.0);
        List<LiveSats.Flyover> fs = n.workSats.flyovers(jd, jd + FLYOVER_AHEAD_MIN / 1440, FLY_MAG, FLY_MIN_ALT);
        synchronized (n.flyovers) {
            n.flyovers.clear();
            n.flyovers.addAll(fs);
        }
        for (LiveSats.Flyover f : fs) {
            String key = f.sat.norad + "@" + Math.round(f.jdRise * 1440);
            if (n.flyoversSaid.contains(key)) continue;
            long rise = ms(f.jdRise);
            boolean station = Patches.isStation(f.name());
            // a space station always; another bright one only now and then, so that the alerts stay special
            if (!station && (f.magBest > FLY_MAG_BIG || now - n.lastFlyoverSaidMs < FLYOVER_GAP_MS || ms(f.jdSet) < now + 60_000)) continue;
            n.flyoversSaid.add(key);
            if (!station) n.lastFlyoverSaidMs = now;
            String when = minutesFrom(rise, now);
            boolean up = rise <= now + 5000;
            MissionLog.Event e = log.add(MissionLog.FLYOVER, up
                            ? String.format(Locale.US, "%s is flying over right now! It %s.", SatPlanner.capital(SatPlanner.the(f.name())), f.path())
                            : String.format(Locale.US, "%s flies over %s! It %s.", SatPlanner.capital(SatPlanner.the(f.name())), when, f.path()),
                    station ? (up ? "Space Station alert! It's flying over right now!" : "Space Station alert! It flies over " + when + ".") : "");
            e.name = f.name();
            e.norad = f.sat.norad;
            e.mag = f.magBest;
            e.atMs = rise;
            e.big = station;
            e.detail = String.format(Locale.US, "Brightest at magnitude %.1f, %.0f degrees up, at %s. Look %s when it comes up: a bright "
                    + "star moving steadily, no blinking lights.", f.magBest, f.altBest, clock(ms(f.jdBest)), LiveSats.compass(f.azRise));
        }
    }

    /** A flyover about to start: said again two minutes before, so there's time to run outside. */
    private void flyoverWarnings(Session n, long now) {
        List<LiveSats.Flyover> fs;
        synchronized (n.flyovers) {
            fs = new ArrayList<LiveSats.Flyover>(n.flyovers);
        }
        for (LiveSats.Flyover f : fs) {
            long rise = ms(f.jdRise);
            String key = f.sat.norad + "@" + Math.round(f.jdRise * 1440);
            // only the ones told about, and only once: two minutes before, or as it comes up
            if (!n.flyoversSaid.contains(key) || n.flyoversWarned.contains(key) || rise - now > FLYOVER_WARN_MS || rise < now - 30_000) continue;
            n.flyoversWarned.add(key);
            boolean station = Patches.isStation(f.name());
            String where = LiveSats.compass(f.azRise);
            boolean soon = rise - now > 5000;
            String said = soon ? spokenWait(rise - now) : "";
            MissionLog.Event e = log.add(MissionLog.FLYOVER, soon
                            ? String.format(Locale.US, "%s comes up in the %s %s. Go outside and look!", SatPlanner.capital(SatPlanner.the(f.name())),
                            where, minutesFrom(rise, now))
                            : String.format(Locale.US, "%s is coming up in the %s right now. Go outside and look!", SatPlanner.capital(SatPlanner.the(f.name())), where),
                    (station ? "The Space Station" : spoken(f.name())) + " comes up in the " + where
                            + (soon ? " " + said + ". Go outside and look up!" : " right now. Look up!"));
            e.name = f.name();
            e.norad = f.sat.norad;
            e.mag = f.magBest;
            e.atMs = rise;
            e.big = true;
            e.detail = "It " + f.path() + ".";
        }
    }

    static long ms(double jdUtc) {
        return Math.round((jdUtc - 2440587.5) * 86400000.0);
    }

    private static String minutesFrom(long at, long now) {
        long s = Math.max(0, Math.round((at - now) / 1000.0));
        if (s < 5) return "right now";
        if (s < 90) return "in " + s + " seconds";
        long m = Math.round(s / 60.0);
        return "in " + m + " minutes";
    }

    /** How long to wait, as said aloud: "in about a minute", "in 40 seconds". */
    static String spokenWait(long ms) {
        long s = Math.max(0, Math.round(ms / 1000.0));
        if (s < 50) return "in " + (s < 10 ? s : (s + 2) / 5 * 5) + " seconds";
        if (s < 90) return "in about a minute";
        return "in about " + Math.round(s / 60.0) + " minutes";
    }

    private static String clock(long ms) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(ms);
        int h = c.get(java.util.Calendar.HOUR_OF_DAY), m = c.get(java.util.Calendar.MINUTE);
        return String.format(Locale.US, "%d:%02d %s", h % 12 == 0 ? 12 : h % 12, m, h < 12 ? "am" : "pm");
    }

    /** "3 min 20 s", "45 seconds". */
    static String duration(double s) {
        long t = Math.round(s);
        if (t < 60) return t + " seconds";
        long m = t / 60, r = t % 60;
        return r == 0 ? m + " min" : m + " min " + r + " s";
    }

    // ------------------------------------------------------------------------------------------------
    // The photo

    /**
     * A developed photo is on the screen: its map and song; for a session just taken, its story's end and the patches
     * it earned.
     */
    public void photo(final Photo p) {
        final Session n0 = s;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    photoNow(n0, p);
                } catch (RuntimeException | OutOfMemoryError e) {
                    note("mission control: the photo's map wasn't made: " + e);
                }
            }
        });
    }

    private void photoNow(Session n0, Photo p) {
        Session n = n0;
        if (n != null && p.fresh) finishWatch(n);
        if (n == null || !p.fresh) {
            // a photo opened again: a session of its own, with no story to tell
            n = new Session(null, null, null, p.width, p.height, 0, false);
            if (n0 != null) n0.over = true;
            s = n;
        }
        BrightStars cat = n.cat != null ? n.cat : catalogue;
        SessionSky sky = n.sky != null ? n.sky : photoSky;
        if (p.sol != null && p.crop != null && cat != null) {
            n.photoMap = new SkyMap(p.sol, p.jdUtc, sky, cat, "photo");
            n.photoCrop = p.crop;
        }
        n.photoJpeg = p.jpeg;
        n.photoW = p.width;
        n.photoH = p.height;
        n.skyJsonKey = Integer.MIN_VALUE;
        n.song = null;
        n.songJson = null;
        if (!p.fresh) return;
        // the story's end: what it caught, and the patches
        Patches.Facts f = new Patches.Facts();
        f.startMs = p.startMs > 0 ? p.startMs : n.startMs;
        f.endMs = p.endMs > 0 ? p.endMs : now();
        f.seconds = p.seconds;
        synchronized (n.caught) {
            for (String c : n.caught) if (!c.startsWith("a mystery")) f.satellites.add(c);
        }
        int planes = n.planes;
        if (p.streaks != null) {
            int devPlanes = 0;
            for (SatMatch.Streak st : p.streaks.streaks) {
                if (st.sat != null) {
                    String name = st.sat.displayName();
                    if (!f.satellites.contains(name)) f.satellites.add(name);
                } else if ("plane".equals(st.kind)) devPlanes++;
            }
            // the live look saw each frame as it came; without it, the develop step's count
            if (!n.live) planes = devPlanes;
        }
        f.planes = planes;
        f.meteors = p.meteors;
        f.constellations.addAll(p.constellations);
        int found = n.starsFound;
        if (p.small != null) {
            try {
                found = Math.max(found, PictureStars.count(p.small));
            } catch (RuntimeException ignored) {
            }
        }
        f.stars = found;
        n.starsFound = Math.max(n.starsFound, found);
        SkyMap m = n.photoMap;
        if (m != null) {
            for (SkyMap.Thing t : m.things(p.crop[0], p.crop[1], p.crop[2], p.crop[3], 6.5, null)) {
                if ("planet".equals(t.kind)) f.planets.add(t.name);
                else if ("moon".equals(t.kind)) f.moon = true;
                else if ("object".equals(t.kind) && t.objectKind == 'G') f.galaxies.add(t.name);
                else if ("star".equals(t.kind) && t.ly == t.ly && !(t.ly <= f.farthestLy) && t.mag <= 5.5) {
                    f.farthestLy = t.ly;
                    f.farthestName = t.name.isEmpty() ? "a star" : t.name;
                }
            }
            double[] core = m.corePixel(p.crop[0], p.crop[1], p.crop[2], p.crop[3]);
            f.core = core != null;
        }
        f.named = n.named;
        f.watched = n.watched;
        String text = String.format(Locale.US, "Mission complete! The photo is ready: %d frames, %s of starlight", n.frames, duration(n.lightS));
        if (found > 0) text += String.format(Locale.US, ", %,d stars", found);
        text += ".";
        MissionLog.Event done = log.add(MissionLog.PHASE, text, "Mission complete! The photo is ready.");
        done.big = true;
        done.name = "done";
        List<Patches.Def> fresh = patches.session(f, now());
        for (int i = 0; i < fresh.size(); i++) {
            Patches.Def d = fresh.get(i);
            // a first night earns a pile of them: said once, shown a few at a time
            String say = fresh.size() <= 2 ? "New mission patch: " + d.name + "!"
                    : i == 0 ? "Wow! You earned " + fresh.size() + " new mission patches!" : "";
            MissionLog.Event e = log.add(MissionLog.PATCH, "New mission patch: " + d.name + "! " + d.what, say);
            e.name = d.id;
            e.detail = d.what;
            e.big = i < 3;
        }
        if (keep != null) keep.patches(patches.toJson());
    }

    /** The catalogue and the session's place for a photo opened again (the session thread sets them). */
    private volatile BrightStars catalogue;
    private volatile SessionSky photoSky;

    public void setCatalogue(BrightStars cat) {
        catalogue = cat;
    }

    public void setPhotoSky(SessionSky sky) {
        photoSky = sky;
    }

    // ------------------------------------------------------------------------------------------------
    // What the page asks for

    /** The story after event {@code since}, with the counters. */
    public String eventsJson(int since) {
        Session n = s;
        Status st = status;
        String state = st != null ? st.state() : "idle";
        if (n != null && ("capturing".equals(state) || "paused".equals(state) || "countdown".equals(state))) n.watched = true;
        Json c = new Json().obj();
        c.put("state", state);
        if (n != null) {
            c.put("frames", n.frames).put("planned", n.plannedUnits).put("light", n.lightS, 5).put("stars", n.starsFound);
            c.put("sats", n.satsCaught).put("planes", n.planes).put("meteors", n.meteors).put("streaks", n.streaks);
            long t0 = n.captureStartMs;
            long el = st != null ? st.elapsedMs() : 0;
            double secs = el > 0 ? el / 1000.0 : t0 > 0 ? (now() - t0) / 1000.0 : 0;
            double lat = n.sky != null && n.sky.located() ? n.sky.latDeg : Double.NaN;
            c.put("elapsed", secs, 6);
            if (lat == lat) c.put("spinKm", SPIN_KM_S * Math.cos(Math.toRadians(lat)) * secs, 5);
            c.put("orbitKm", ORBIT_KM_S * secs, 6);
            c.put("solved", n.liveSol != null || n.photoMap != null);
            c.put("photo", n.photoJpeg != null);
            LiveSats.Flyover next = null;
            synchronized (n.flyovers) {
                for (LiveSats.Flyover f : n.flyovers) {
                    if (ms(f.jdSet) < now()) continue;
                    if (next == null || (Patches.isStation(f.name()) && !Patches.isStation(next.name()))) next = f;
                }
            }
            if (next != null) {
                c.obj("flyover").put("name", next.name()).put("rise", ms(next.jdRise)).put("set", ms(next.jdSet)).put("mag", next.magBest, 3)
                        .put("alt", next.altBest, 3).put("from", LiveSats.compass(next.azRise)).put("to", LiveSats.compass(next.azSet))
                        .put("station", Patches.isStation(next.name())).put("path", next.path()).end();
            }
        }
        c.put("patches", patches.count()).put("named", named.size());
        c.end();
        return log.json(since, c.toString());
    }

    /** The sky map of whichever picture is shown (the photo once developed, else the live stack), or null. */
    public String skyJson() {
        Session n = s;
        if (n == null) return null;
        SkyMap m = n.photoMap;
        int[] crop = n.photoCrop;
        if (m == null) {
            m = n.liveMap;
            crop = n.liveCrop;
            if (crop == null) crop = new int[]{0, 0, n.W, n.H};
        }
        if (m == null || crop == null) return null;
        int key = System.identityHashCode(m) * 31 + java.util.Arrays.hashCode(crop) * 17 + named.version();
        String j = n.skyJson;
        if (j != null && key == n.skyJsonKey) return j;
        j = m.json(crop[0], crop[1], crop[2], crop[3], named.all());
        n.skyJson = j;
        n.skyJsonKey = key;
        return j;
    }

    /**
     * The satellites crossing the live picture from half a minute ago to two minutes ahead: each one's path (the sky
     * map's pixels, every half second) with its start time, for the page to move along with its clock.
     */
    public String satsJson() {
        Session n = s;
        long now = now();
        Json j = new Json().obj().put("now", now);
        if (n == null || n.liveSol == null || n.workSats == null || !n.sky.clockValid || n.photoMap != null) {
            return j.arr("tracks").end().end().toString();
        }
        String c = n.satsJson;
        if (c != null && now - n.satsJsonMs < 5000) return c;
        double jd = Astro.jdUtc(now / 1000.0);
        List<SatPredict.Track> ts;
        synchronized (this) {
            ts = n.workSats.crossing(n.liveSol, n.W, n.H, jd - 30 / 86400.0, jd + 120 / 86400.0, PATH_MAG, 200);
        }
        j.arr("tracks");
        for (SatPredict.Track t : ts) {
            j.obj().put("n", t.sat.displayName()).put("id", t.sat.norad);
            float m = t.brightest();
            if (m < 90) j.put("m", m, 3);
            if (Patches.isStation(t.sat.displayName())) j.put("st", true);
            j.put("t0", ms(t.jd[0])).put("dt", (long) (SatPredict.FINE_S * 1000));
            j.arr("p");
            for (int i = 0; i < t.jd.length; i++) {
                if (Float.isNaN(t.x[i])) j.val((String) null).val((String) null);
                else j.val(Math.round(t.x[i] * 10) / 10.0).val(Math.round(t.y[i] * 10) / 10.0);
            }
            j.end().end();
        }
        String out = j.end().end().toString();
        n.satsJson = out;
        n.satsJsonMs = now;
        return out;
    }

    /** The photo (plain) for Star Spotter, or null before there is one. */
    public byte[] photoJpeg() {
        Session n = s;
        return n != null ? n.photoJpeg : null;
    }

    public byte[] snap(int id) {
        return log.snap(id);
    }

    /** The song of the shown picture: the photo's once developed, else the live stack's; null without a map. */
    public List<StarSong.Note> song() {
        Session n = s;
        if (n == null) return null;
        List<StarSong.Note> notes = n.song;
        if (notes != null && n.photoMap != null) return notes;
        SkyMap m = n.photoMap != null ? n.photoMap : n.liveMap;
        int[] crop = n.photoMap != null ? n.photoCrop : n.liveCrop != null ? n.liveCrop : new int[]{0, 0, n.W, n.H};
        if (m == null || crop == null) return null;
        notes = StarSong.notes(m.things(crop[0], crop[1], crop[2], crop[3], StarSong.MAX_MAG, named.all()), m.milkyShares(crop[0], crop[1], crop[2], crop[3]));
        n.song = notes;
        return notes;
    }

    public String songJson() {
        List<StarSong.Note> notes = song();
        return notes == null ? null : StarSong.json(notes);
    }

    /** The photo's map and its crop (for the phone's own song and 3D flight), or null. */
    public SkyMap photoMap() {
        Session n = s;
        return n != null ? n.photoMap : null;
    }

    public int[] photoCrop() {
        Session n = s;
        return n != null ? n.photoCrop : null;
    }

    public String patchesJson() {
        return patches.json();
    }

    /**
     * Names the star nearest (x, y) in the shown picture's map (its pixels: the reference frame's live, the stack's
     * once developed), or the spot itself if no catalogue star is there. JSON with what it is, or an error.
     */
    public String nameStar(Map<String, String> args) {
        Session n = s;
        String name = NamedStars.clean(args.get("name"));
        if (name.isEmpty()) return error("Type a name for the star first.");
        double x, y;
        try {
            x = Double.parseDouble(args.get("x"));
            y = Double.parseDouble(args.get("y"));
        } catch (RuntimeException e) {
            return error("Click a star in the picture first.");
        }
        if (n == null) return error("There's no picture to pick a star in yet.");
        SkyMap m = n.photoMap != null ? n.photoMap : n.liveMap;
        int[] crop = n.photoMap != null ? n.photoCrop : n.liveCrop != null ? n.liveCrop : new int[]{0, 0, n.W, n.H};
        if (m == null || crop == null) return error("The sky isn't solved yet, so I don't know which star that is. Try again in a minute.");
        double radius = Math.max(8, PICK_SHARE * crop[2]);
        int k = m.catalogueStarNear(x, y, radius);
        double[] dir;
        double mag = Double.NaN;
        if (k >= 0) {
            dir = m.cat.vec(k);
            mag = m.cat.vmag[k];
        } else {
            dir = m.direction(x, y);
        }
        long now = now();
        NamedStars.Star st = named.name(name, args.get("by"), dir, mag, k, now, 0.05);
        if (st == null) return error("That name has nothing left in it to keep.");
        n.named++;
        n.skyJsonKey = Integer.MIN_VALUE;
        n.song = null;
        if (keep != null) keep.namedStars(named.toJson());
        String real = "";
        double ly = Double.NaN;
        if (k >= 0) {
            real = m.cat.label(k);
            if (!Float.isNaN(m.cat.ly[k])) ly = m.cat.ly[k];
        }
        StringBuilder t = new StringBuilder("A star has a new name: ").append(st.name).append("!");
        if (!real.isEmpty()) t.append(" Astronomers call it ").append(real).append('.');
        if (ly == ly) t.append(String.format(Locale.US, " Its light left it %s years ago.", ly < 10 ? String.format(Locale.US, "%.1f", ly) : String.format(Locale.US, "%,.0f", ly)));
        MissionLog.Event e = log.add(MissionLog.NAMED, t.toString(), "A star has a new name: " + st.name + "!");
        e.name = st.name;
        e.big = true;
        Patches.Def d = patches.earn("namer", st.name, now);
        if (d != null) {
            patchEvent(d);
            if (keep != null) keep.patches(patches.toJson());
        }
        Json j = new Json().obj().put("ok", true).put("id", st.id).put("name", st.name).put("real", real);
        if (ly == ly) j.put("ly", ly, 4);
        return j.end().toString();
    }

    /** Forgets a named star. */
    public String unnameStar(Map<String, String> args) {
        String id = args.get("id");
        if (id == null || !named.remove(id)) return error("There's no star by that name.");
        Session n = s;
        if (n != null) {
            n.skyJsonKey = Integer.MIN_VALUE;
            n.song = null;
        }
        if (keep != null) keep.namedStars(named.toJson());
        return new Json().obj().put("ok", true).end().toString();
    }

    /** Mission Control was opened on a computer (the "commander" patch, earned once a mission is watched). */
    public void opened() {
        Session n = s;
        Status st = status;
        if (n != null && st != null) {
            String state = st.state();
            if ("capturing".equals(state) || "countdown".equals(state) || "paused".equals(state)) n.watched = true;
        }
    }

    private static String error(String msg) {
        return new Json().obj().put("ok", false).put("error", msg).end().toString();
    }

    /** For the session's log (the phone's own log through {@link #setLogger}). */
    public interface Logger {
        void log(String line);
    }

    private volatile Logger logger;

    public void setLogger(Logger l) {
        logger = l;
    }

    private void note(String line) {
        Logger l = logger;
        if (l != null) l.log(line);
    }

    /** Stops the worker (tests). */
    public void shutdown() {
        worker.shutdownNow();
    }

    /** Waits until the worker has done what it was given so far (tests). */
    public void settle() {
        try {
            worker.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get(60, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }

    /** Runs a tick now (tests). */
    public void tickNow(long nowMs) {
        tick(nowMs);
    }
}
