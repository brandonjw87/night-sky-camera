package com.brandon.nightsky.processing;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/**
 * The sessions kept on the phone (G1, G3): what each one is and holds, which ones could be merged, and keeping
 * their frames within a budget.
 * <p>
 * A session's frames (its RAW units, star lists and running sum) let it be edited and stacked again: trimmed,
 * frames left out, another reference, merged with other nights. Its masters let its photos be rendered again
 * without them. When the frames of all sessions pass the budget, or a new session needs the room, the oldest
 * developed sessions lose their frames first, then their extra masters; every deletion is written in that
 * session's log and returned, for the new session's log.
 */
public final class SessionLibrary {
    private SessionLibrary() {}

    /** The default budget for kept frames (bytes). */
    public static final long DEFAULT_BUDGET = 20L << 30;
    /** Masters kept per session besides the develop step's and the latest render's. */
    public static final int EXTRA_MASTERS = 2;

    /** A session as the library shows it. */
    public static final class Entry {
        public SessionStore store;
        public String id = "", state = "";
        /** When the capture began (Unix ms; 0 if unknown), how long it was meant to run (s), whether trails. */
        public long startMs;
        public int durationSec;
        public boolean trails;
        /** Units in its list, and whether their frames are still on the phone. */
        public int units;
        public boolean hasFrames;
        public long frameBytes, masterBytes, bytes;
        /** Its saved renders, oldest first, and its masters, newest first. */
        public List<Recipe> renders = new ArrayList<Recipe>();
        public List<Master.Meta> masters = new ArrayList<Master.Meta>();
        /** The folder of the session this one adds to ("Add 5 minutes"; "" if none). */
        public String continuesFrom = "";
        public double lat = Double.NaN, lon = Double.NaN;
        public String siteId = "", siteName = "", presetName = "";
        /** The develop step's summary ("" if not developed). */
        public String summary = "";
        /** J2000 direction of its reference frame's centre (null if its stars weren't matched to the catalogue). */
        public double[] centre;

        public boolean developed() {
            return "developed".equals(state);
        }

        /** Its folder's name ("session_" and its id). */
        public String folder() {
            return store.dir.getName();
        }

        /** The develop step's master, or null. */
        public Master.Meta developMaster() {
            for (Master.Meta m : masters) if (m.develop) return m;
            return null;
        }

        /** Its newest master (any), or null. */
        public Master.Meta newestMaster() {
            return masters.isEmpty() ? null : masters.get(0);
        }
    }

    /** Every session under root, newest first. */
    public static List<Entry> list(File root) {
        List<Entry> out = new ArrayList<Entry>();
        for (SessionStore s : SessionStore.list(root)) out.add(entry(s));
        return out;
    }

    /** What one session folder holds. */
    public static Entry entry(SessionStore s) {
        Entry e = new Entry();
        e.store = s;
        e.id = s.id();
        Properties p;
        try {
            p = s.readSettings();
        } catch (IOException ex) {
            p = new Properties();
        }
        e.state = p.getProperty("state", "");
        e.startMs = parseLong(p.getProperty("capture.startWallMs"));
        e.durationSec = (int) parseLong(p.getProperty("capture.durationSec", p.getProperty("durationSec")));
        e.trails = "trails".equals(p.getProperty("mode"));
        e.continuesFrom = p.getProperty("continuesFrom", "");
        e.lat = parseDouble(p.getProperty("lat"));
        e.lon = parseDouble(p.getProperty("lon"));
        e.siteId = p.getProperty("site.id", "");
        e.siteName = p.getProperty("site.name", "");
        e.presetName = p.getProperty("preset.name", "");
        e.frameBytes = frameBytes(s);
        e.hasFrames = size(s.framesDir) > 0;
        e.masterBytes = Master.bytes(s.dir);
        e.bytes = s.sizeBytes();
        try {
            e.units = s.readUnits().size();
        } catch (IOException ignored) {
        }
        e.renders = Recipe.list(s.dir);
        e.masters = Master.list(s.dir);
        Master.Meta dm = e.developMaster();
        if (dm != null) {
            e.summary = dm.summary;
            if (dm.solutionR != null && dm.model != null) {
                double[] b = new double[3], c = new double[3];
                dm.model.unproject(dm.model.cx, dm.model.cy, b);
                Rot.apply(dm.solutionR, b[0], b[1], b[2], c);
                e.centre = c;
            }
        }
        return e;
    }

    /**
     * Sessions that could be merged with {@code base}: developed, of the same sky (centres within
     * {@link MergeProcessor#MAX_CENTRE_DEG}) from the same place (within {@link MergeProcessor#MAX_SITE_KM}), the same
     * kind (not star trails), and with their frames still kept. Nearest in time first.
     */
    public static List<Entry> mergeCandidates(Entry base, List<Entry> all) {
        List<Entry> out = new ArrayList<Entry>();
        if (base.trails || base.centre == null) return out;
        for (Entry e : all) {
            if (e == base || e.id.equals(base.id) || e.trails || !e.developed() || !e.hasFrames || e.centre == null) continue;
            if (MergeProcessor.close(base.centre, base.lat, base.lon, e.centre, e.lat, e.lon)) out.add(e);
        }
        final long t = base.startMs;
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return Long.compare(Math.abs(a.startMs - t), Math.abs(b.startMs - t));
            }
        });
        return out;
    }

    /** The sessions that add to this one ("Add 5 minutes"), oldest first. */
    public static List<Entry> continuations(Entry base, List<Entry> all) {
        List<Entry> out = new ArrayList<Entry>();
        for (Entry e : all) if (base.folder().equals(e.continuesFrom)) out.add(e);
        Collections.sort(out, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.id.compareTo(b.id);
            }
        });
        return out;
    }

    /** Bytes of a session's frames (RAW units, star lists, running sum). */
    public static long frameBytes(SessionStore s) {
        long n = size(s.framesDir) + size(s.starsDir);
        File sum = new File(s.dir, "sum.f32");
        if (sum.isFile()) n += sum.length();
        return n;
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] l = f.listFiles();
        if (l != null) for (File c : l) s += size(c);
        return s;
    }

    /** What {@link #trim} did. */
    public static final class Trim {
        /** One line per deletion, for the log. */
        public final List<String> lines = new ArrayList<String>();
        public long freedBytes;
        /** Frames kept by all sessions afterwards (bytes). */
        public long keptFrameBytes;
    }

    /**
     * Keeps the sessions' frames within {@code budget} bytes and frees {@code need} bytes when only {@code free} are
     * free: the oldest developed sessions' frames go first, then (only if more room is still needed) their extra
     * masters. Sessions in {@code keep} and sessions not developed yet are left alone; their frames are what
     * developing (or developing again) needs. A budget of 0 keeps no frames of developed sessions.
     */
    public static Trim trim(File root, long budget, long need, long free, Set<String> keep) {
        Trim t = new Trim();
        List<SessionStore> all = SessionStore.list(root);
        // oldest first
        Collections.reverse(all);
        long kept = 0;
        List<SessionStore> developed = new ArrayList<SessionStore>();
        for (SessionStore s : all) {
            long fb = frameBytes(s);
            kept += fb;
            if (fb > 0 && "developed".equals(s.state()) && (keep == null || !keep.contains(s.id()))) developed.add(s);
        }
        long short_ = need > 0 && free >= 0 ? Math.max(0, need - free) : 0;
        for (SessionStore s : developed) {
            if (kept <= budget && short_ <= 0) break;
            String why = kept > budget ? String.format(Locale.US, "to keep the sessions' frames within %s", gb(budget))
                    : "to make room for the new session";
            long fb = frameBytes(s);
            s.deleteBulk();
            kept -= fb;
            short_ -= fb;
            t.freedBytes += fb;
            String line = String.format(Locale.US, "deleted the frames of session %s (%s) %s; its photos and masters stay", s.id(), gb(fb), why);
            s.log("decision: " + line + "; it can't be edited or merged again");
            t.lines.add(line);
        }
        if (short_ > 0) {
            // still short: the masters other than each session's develop one and latest render's, oldest sessions first
            for (SessionStore s : all) {
                if (short_ <= 0) break;
                if (keep != null && keep.contains(s.id())) continue;
                Set<String> protect = new HashSet<String>();
                Recipe last = Recipe.latest(s.dir);
                if (last != null) protect.add(last.stackKey);
                long before = Master.bytes(s.dir);
                List<String> gone = Master.prune(s.dir, 0, protect);
                long freed = before - Master.bytes(s.dir);
                if (gone.isEmpty() || freed <= 0) continue;
                short_ -= freed;
                t.freedBytes += freed;
                String line = String.format(Locale.US, "deleted %d stacked picture%s of session %s (%s) to make room for the new session",
                        gone.size(), gone.size() == 1 ? "" : "s", s.id(), gb(freed));
                s.log("decision: " + line);
                t.lines.add(line);
            }
        }
        t.keptFrameBytes = kept;
        return t;
    }

    /** "1.2 GB", "350 MB". */
    public static String gb(long bytes) {
        if (bytes >= 1L << 30) return String.format(Locale.US, "%.1f GB", bytes / (double) (1L << 30));
        return String.format(Locale.US, "%d MB", Math.max(0, Math.round(bytes / (double) (1L << 20))));
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String s) {
        try {
            return s == null ? Double.NaN : Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }
}
