package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;
import com.brandon.nightsky.remote.JsonTree;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stars named by you (2.3): a star picked in the viewer's Star Spotter, or on the phone, is given a name, and from then on
 * the name is drawn beside it in every picture of that part of the sky (the viewer's names, and the photo's Names). A
 * star is kept as where it is on the sky (J2000), so any later picture whose sky solution takes in that spot shows it; a
 * catalogue star also keeps its catalogue number, and a fainter one the magnitude it showed if known. Stored as JSON (in
 * the app's preferences).
 */
public final class NamedStars {
    /** Most stars kept: the oldest goes first. */
    public static final int MAX = 100;
    /** Longest name kept (characters). */
    public static final int MAX_NAME = 40;

    public static final class Star {
        public String id = "", name = "", by = "";
        /** J2000 right ascension and declination (degrees). */
        public double ra, dec;
        /** Its V magnitude if known (NaN if not), and its catalogue row in the app's star table (-1 if none). */
        public double mag = Double.NaN;
        public int cat = -1;
        /** When it was named (Unix ms). */
        public long whenMs;

        public double[] dir() {
            return Astro.vec(ra * Astro.DEG, dec * Astro.DEG);
        }
    }

    private final List<Star> stars = new ArrayList<Star>();
    /** Bumped at every change, so a page's copy knows it's out of date. */
    private int version;

    public synchronized int version() {
        return version;
    }

    public synchronized List<Star> all() {
        return new ArrayList<Star>(stars);
    }

    public synchronized int size() {
        return stars.size();
    }

    /**
     * A name made fit to keep: letters, digits, spaces and a little punctuation, at most {@link #MAX_NAME} characters, or
     * "" if nothing is left.
     */
    public static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < MAX_NAME; i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '\'' || c == '-' || c == '.' || c == '!' || c == '&' || c == '’') {
                if (c == ' ' && (b.length() == 0 || b.charAt(b.length() - 1) == ' ')) continue;
                b.append(c);
            }
        }
        return b.toString().trim();
    }

    /**
     * Names the star at J2000 direction {@code dir}; a star already named within {@code sameDeg} degrees of it is renamed
     * instead. Returns the star kept, or null if the name is empty.
     */
    public synchronized Star name(String name, String by, double[] dir, double mag, int cat, long now, double sameDeg) {
        String n = clean(name);
        if (n.isEmpty()) return null;
        Star s = null;
        for (Star o : stars) {
            if ((cat >= 0 && o.cat == cat) || Astro.sep(o.dir(), dir) < sameDeg * Astro.DEG) {
                s = o;
                break;
            }
        }
        if (s == null) {
            s = new Star();
            s.id = String.format(Locale.US, "s%d", now);
            for (int k = 1; idTaken(s.id); k++) s.id = String.format(Locale.US, "s%d_%d", now, k);
            double[] rd = Astro.raDec(dir);
            s.ra = Astro.norm(rd[0]) / Astro.DEG;
            s.dec = rd[1] / Astro.DEG;
            s.mag = mag;
            s.cat = cat;
            stars.add(s);
            while (stars.size() > MAX) stars.remove(0);
        }
        s.name = n;
        s.by = clean(by);
        s.whenMs = now;
        version++;
        return s;
    }

    private boolean idTaken(String id) {
        for (Star o : stars) if (o.id.equals(id)) return true;
        return false;
    }

    /** Forgets a star; false if there was none by that id. */
    public synchronized boolean remove(String id) {
        for (int i = 0; i < stars.size(); i++) {
            if (stars.get(i).id.equals(id)) {
                stars.remove(i);
                version++;
                return true;
            }
        }
        return false;
    }

    public synchronized String toJson() {
        Json j = new Json().arr();
        for (Star s : stars) {
            j.obj().put("id", s.id).put("n", s.name).put("by", s.by).put("ra", s.ra, 8).put("de", s.dec, 8).put("when", s.whenMs);
            if (!Double.isNaN(s.mag)) j.put("m", s.mag, 3);
            if (s.cat >= 0) j.put("cat", s.cat);
            j.end();
        }
        return j.end().toString();
    }

    /** The stars kept as {@link #toJson} wrote them; nothing (and no error) if the text isn't that. */
    public static NamedStars fromJson(String text) {
        NamedStars out = new NamedStars();
        if (text == null || text.trim().isEmpty()) return out;
        try {
            Object o = JsonTree.parse(text);
            if (!(o instanceof List)) return out;
            for (Object x : (List<?>) o) {
                if (!(x instanceof java.util.Map)) continue;
                @SuppressWarnings("unchecked")
                JsonTree.Obj e = new JsonTree.Obj((java.util.Map<String, Object>) x);
                Star s = new Star();
                s.id = e.str("id", "");
                s.name = clean(e.str("n", ""));
                s.by = clean(e.str("by", ""));
                s.ra = e.num("ra", Double.NaN);
                s.dec = e.num("de", Double.NaN);
                s.mag = e.num("m", Double.NaN);
                s.cat = e.integer("cat", -1);
                s.whenMs = e.lng("when", 0);
                if (s.id.isEmpty() || s.name.isEmpty() || Double.isNaN(s.ra) || Double.isNaN(s.dec)) continue;
                out.stars.add(s);
            }
        } catch (RuntimeException ignored) {
            // a damaged list is started again
        }
        while (out.stars.size() > MAX) out.stars.remove(0);
        return out;
    }
}
