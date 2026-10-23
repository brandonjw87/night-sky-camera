package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * How a photo was made from its session folder (G1, G3): which sessions and frames went in, the reference frame,
 * stars tapped by hand, the foreground mask, the stack's settings, and how it was rendered (stretch, exposure,
 * black point, saturation, look, crop, north up). Every render saves one, as renders/rNNN.json in the base
 * session's folder, with the processing version that made it, so running it again gives the same photo and a
 * later version can offer to render it again. A saved recipe is never overwritten.
 */
public final class Recipe {
    public static final String DIR = "renders";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** One session of the stack, and the frames of it that go in. */
    public static final class Part {
        public String session = "";
        /** Units (by index) that go in; null for all of them. */
        public TreeSet<Integer> include;
        /** How {@link #include} was chosen, in a few words ("" for all). */
        public String includeNote = "";

        public Part copy() {
            Part p = new Part();
            p.session = session;
            p.include = include != null ? new TreeSet<Integer>(include) : null;
            p.includeNote = includeNote;
            return p;
        }
    }

    public static final String LOOK_NATURAL = "natural", LOOK_PUNCHY = "punchy", LOOK_LINEAR = "linear";
    public static final String CROP_FULL = "full";
    /** The crops offered, as width:height. */
    public static final String[] CROPS = {CROP_FULL, "4:3", "3:2", "16:9", "1:1"};

    // ---- the stack ----
    /** The sessions, the base first (more than one: a merge). */
    public final List<Part> parts = new ArrayList<Part>();
    public boolean trails;
    /** The base session's reference frame, chosen by hand (-1: automatic). */
    public int reference = -1;
    /** Stars tapped by hand in the base session: per unit, pairs of positions (reference x, y, then the unit's x, y). */
    public final TreeMap<Integer, float[]> taps = new TreeMap<Integer, float[]>();
    /** The foreground mask painted by hand: a file in the base session's folder ("" for none). */
    public String mask = "";
    /** Keep the ground still: the foreground from the frames' plain average, unaligned. */
    public boolean freezeGround;
    /** Outlier rejection (a {@link Rejection.Method} name). */
    public String rejection = "AUTO";
    /** White balance from the catalogue stars (F4) when there are enough of them. */
    public boolean colourFromStars = true;
    public boolean keepSatellites;
    /** Quick developing (Quick look): one stacking pass. */
    public boolean quick;

    // ---- the render ----
    public float brightness = 0.5f;
    /** Strength of the stretch, 1 (gentle) to 10 (strong); 5 is the develop step's. */
    public float stretch = 5f;
    /** Exposure, in stops (-2 to +2). */
    public float ev;
    /** Black point: share of the sky's noise taken off below the background (0: the develop step's). */
    public float black;
    /** Colour saturation (1: the develop step's). */
    public float saturation = 1f;
    public String look = LOOK_NATURAL;
    public String crop = CROP_FULL;
    /** Turned so north is up (needs the plate solution). */
    public boolean northUp;

    // ---- what it made ----
    public int number;
    public int pipeline = PostProcessor.PIPELINE;
    public String app = "";
    public long createdMs;
    /** The photo's file name (no extension), and where it was saved ("" if not known). */
    public String name = "", uri = "";
    /** Why it was made: "developed", "edited", "merged", "rendered again with the new processing". */
    public String note = "";
    public String stackKey = "", masterHash = "", pixelHash = "";
    /** The stack's own summary when it was made, and the total ("47 minutes over 3 nights") for a merge. */
    public String summary = "", total = "";

    public static Recipe single(String session, boolean trails) {
        Recipe r = new Recipe();
        Part p = new Part();
        p.session = session;
        r.parts.add(p);
        r.trails = trails;
        return r;
    }

    public Part base() {
        return parts.isEmpty() ? null : parts.get(0);
    }

    public boolean isMerge() {
        return parts.size() > 1;
    }

    /** A copy to edit (what it made is cleared). */
    public Recipe edit() {
        Recipe r = new Recipe();
        for (Part p : parts) r.parts.add(p.copy());
        r.trails = trails;
        r.reference = reference;
        for (Map.Entry<Integer, float[]> e : taps.entrySet()) r.taps.put(e.getKey(), e.getValue().clone());
        r.mask = mask;
        r.freezeGround = freezeGround;
        r.rejection = rejection;
        r.colourFromStars = colourFromStars;
        r.keepSatellites = keepSatellites;
        r.quick = quick;
        r.brightness = brightness;
        r.stretch = stretch;
        r.ev = ev;
        r.black = black;
        r.saturation = saturation;
        r.look = look;
        r.crop = crop;
        r.northUp = northUp;
        return r;
    }

    /** The renderer's settings for this recipe. */
    public Renderer.Params params() {
        Renderer.Params p = new Renderer.Params();
        p.brightness = brightness;
        return p;
    }

    // ---- the stack's identity ----

    /**
     * The stack part as JSON, written the same way every time (fixed order, floats to 9 digits, which read back
     * exactly), with the processing version: what decides the stacked picture.
     */
    public String stackJson() {
        Json j = new Json().obj();
        j.put("pipeline", pipeline);
        j.arr("parts");
        for (Part p : parts) {
            j.obj().put("session", p.session);
            if (p.include != null) j.put("include", ranges(p.include));
            j.end();
        }
        j.end();
        j.put("trails", trails).put("reference", reference);
        if (!taps.isEmpty()) {
            j.obj("taps");
            for (Map.Entry<Integer, float[]> e : taps.entrySet()) {
                j.arr(Integer.toString(e.getKey()));
                for (float v : e.getValue()) j.val(v, 9);
                j.end();
            }
            j.end();
        }
        j.put("mask", mask).put("freezeGround", freezeGround).put("rejection", rejection).put("colourFromStars", colourFromStars)
                .put("keepSatellites", keepSatellites).put("quick", quick);
        return j.end().toString();
    }

    /** Short hash of {@link #stackJson}: the name of the stacked picture's master. */
    public String stackKey() {
        MessageDigest md = Master.sha256();
        md.update(stackJson().getBytes(UTF8));
        return Master.hex(md.digest()).substring(0, 16);
    }

    // ---- the file ----

    public String toJson() {
        Json j = new Json().obj();
        j.put("file", "recipe").put("number", number).put("pipeline", pipeline).put("app", app).put("created", createdMs);
        j.put("name", name).put("uri", uri).put("note", note);
        j.raw("stack", stackJson());
        j.obj("render").put("brightness", brightness, 9).put("stretch", stretch, 9).put("ev", ev, 9).put("black", black, 9)
                .put("saturation", saturation, 9).put("look", look).put("crop", crop).put("northUp", northUp).end();
        j.put("stackKey", stackKey).put("masterHash", masterHash).put("pixelHash", pixelHash);
        j.put("summary", summary).put("total", total);
        return j.end().toString();
    }

    /** The recipe the text holds; throws IllegalArgumentException if it isn't one. */
    public static Recipe fromJson(String text) {
        JsonTree.Obj o = JsonTree.object(text);
        if (!"recipe".equals(o.str("file", ""))) throw new IllegalArgumentException("not a recipe");
        Recipe r = new Recipe();
        r.number = o.integer("number", 0);
        r.pipeline = o.integer("pipeline", 0);
        r.app = o.str("app", "");
        r.createdMs = o.lng("created", 0);
        r.name = o.str("name", "");
        r.uri = o.str("uri", "");
        r.note = o.str("note", "");
        JsonTree.Obj s = o.obj("stack");
        if (s == null) throw new IllegalArgumentException("a recipe needs its stack");
        readStack(r, s);
        JsonTree.Obj rd = o.obj("render");
        if (rd != null) {
            r.brightness = (float) rd.num("brightness", 0.5);
            r.stretch = (float) rd.num("stretch", 5);
            r.ev = (float) rd.num("ev", 0);
            r.black = (float) rd.num("black", 0);
            r.saturation = (float) rd.num("saturation", 1);
            r.look = rd.str("look", LOOK_NATURAL);
            r.crop = rd.str("crop", CROP_FULL);
            r.northUp = rd.bool("northUp", false);
        }
        r.stackKey = o.str("stackKey", "");
        r.masterHash = o.str("masterHash", "");
        r.pixelHash = o.str("pixelHash", "");
        r.summary = o.str("summary", "");
        r.total = o.str("total", "");
        return r;
    }

    /** Reads the stack part (as {@link #stackJson} writes it) into r. */
    static void readStack(Recipe r, JsonTree.Obj s) {
        // the stack's processing version is the recipe's (they're written together)
        if (s.has("pipeline")) r.pipeline = s.integer("pipeline", r.pipeline);
        r.parts.clear();
        for (JsonTree.Obj p : s.objs("parts")) {
            Part q = new Part();
            q.session = p.str("session", "");
            String inc = p.str("include", null);
            if (inc != null) q.include = parseRanges(inc);
            r.parts.add(q);
        }
        if (r.parts.isEmpty()) throw new IllegalArgumentException("a recipe needs a session");
        r.trails = s.bool("trails", false);
        r.reference = s.integer("reference", -1);
        r.taps.clear();
        JsonTree.Obj t = s.obj("taps");
        if (t != null) {
            for (String k : t.map.keySet()) {
                double[] v = t.nums(k);
                if (v == null || v.length < 8 || v.length % 4 != 0) continue;
                float[] f = new float[v.length];
                for (int i = 0; i < v.length; i++) f[i] = (float) v[i];
                try {
                    r.taps.put(Integer.parseInt(k), f);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        r.mask = s.str("mask", "");
        r.freezeGround = s.bool("freezeGround", false);
        r.rejection = s.str("rejection", "AUTO");
        r.colourFromStars = s.bool("colourFromStars", true);
        r.keepSatellites = s.bool("keepSatellites", false);
        r.quick = s.bool("quick", false);
    }

    /** Units as ranges: "0-41,44,46-80". */
    public static String ranges(TreeSet<Integer> s) {
        StringBuilder sb = new StringBuilder();
        Integer start = null, prev = null;
        for (Integer v : s) {
            if (start == null) {
                start = v;
            } else if (v != prev + 1) {
                range(sb, start, prev);
                start = v;
            }
            prev = v;
        }
        if (start != null) range(sb, start, prev);
        return sb.toString();
    }

    private static void range(StringBuilder sb, int a, int b) {
        if (sb.length() > 0) sb.append(',');
        sb.append(a);
        if (b > a) sb.append('-').append(b);
    }

    public static TreeSet<Integer> parseRanges(String s) {
        TreeSet<Integer> out = new TreeSet<Integer>();
        for (String part : s.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int dash = p.indexOf('-', 1);
            try {
                if (dash < 0) {
                    out.add(Integer.parseInt(p));
                } else {
                    int a = Integer.parseInt(p.substring(0, dash).trim()), b = Integer.parseInt(p.substring(dash + 1).trim());
                    if (b - a > 1_000_000) throw new IllegalArgumentException("range too long: " + p);
                    for (int v = a; v <= b; v++) out.add(v);
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("not a frame range: " + p);
            }
        }
        return out;
    }

    // ---- saved recipes ----

    /**
     * Saves it as the session's next render (renders/rNNN.json), never over an earlier one; sets {@link #number},
     * {@link #createdMs} if unset, and returns the file.
     */
    public synchronized File save(File sessionDir) throws IOException {
        File dir = new File(sessionDir, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        if (createdMs == 0) createdMs = System.currentTimeMillis();
        int n = 1;
        for (Recipe r : list(sessionDir)) n = Math.max(n, r.number + 1);
        for (int tries = 0; tries < 1000; tries++, n++) {
            File f = file(sessionDir, n);
            // claims the number: an earlier render's file is never written over
            if (!f.createNewFile()) continue;
            number = n;
            Master.writeText(f, toJson());
            return f;
        }
        throw new IOException("no free render number in " + dir);
    }

    public static File file(File sessionDir, int number) {
        return new File(new File(sessionDir, DIR), String.format(Locale.US, "r%03d.json", number));
    }

    /** The session's saved recipes, oldest first (unreadable ones are left out). */
    public static List<Recipe> list(File sessionDir) {
        List<Recipe> out = new ArrayList<Recipe>();
        File[] fs = new File(sessionDir, DIR).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("r") || !n.endsWith(".json")) continue;
            String text = Master.readText(f);
            if (text == null || text.isEmpty()) continue;
            try {
                Recipe r = fromJson(text);
                if (r.number <= 0) r.number = parseNumber(n);
                out.add(r);
            } catch (IllegalArgumentException ignored) {
            }
        }
        Collections.sort(out, new Comparator<Recipe>() {
            @Override
            public int compare(Recipe a, Recipe b) {
                return a.number - b.number;
            }
        });
        return out;
    }

    private static int parseNumber(String fileName) {
        try {
            return Integer.parseInt(fileName.substring(1, fileName.length() - 5));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The session's latest recipe, or null if it has none. */
    public static Recipe latest(File sessionDir) {
        List<Recipe> l = list(sessionDir);
        return l.isEmpty() ? null : l.get(l.size() - 1);
    }
}
