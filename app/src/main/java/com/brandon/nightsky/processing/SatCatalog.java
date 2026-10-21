package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Satellites to look for: orbital elements as CelesTrak publishes them (OMM in JSON, the GP and the
 * supplemental GP files; or two-line elements), each with a guess at its standard magnitude.
 * <p>
 * The same satellite can come from several files (the visual list and the space stations; the Starlink GP
 * elements and SpaceX's own ephemerides in the supplemental file): one set is kept per catalogue number, the
 * supplemental one when it is less than a few days from the session (it follows the satellite's manoeuvres),
 * else the newest.
 */
public final class SatCatalog {
    /** One satellite. */
    public static final class Sat {
        public final Sgp4 orbit;
        public final String name;
        public final int norad;
        /** Magnitude at 1000 km, half lit (an estimate). */
        public final double stdMag;
        /** Which list it came from ("visual", "stations", "starlink", "supgp-starlink", "tle"). */
        public final String source;

        Sat(Sgp4 orbit, String name, int norad, double stdMag, String source) {
            this.orbit = orbit;
            this.name = name;
            this.norad = norad;
            this.stdMag = stdMag;
            this.source = source;
        }

        /** Days from the elements' epoch to {@code jdUtc}. */
        public double ageDays(double jdUtc) {
            return jdUtc - orbit.jdEpoch;
        }

        /** The name as people know it: "ISS" for the station's modules, "Starlink-1234", title case otherwise. */
        public String displayName() {
            return SatCatalog.displayName(name, norad);
        }
    }

    private final Map<Integer, Sat> byId = new LinkedHashMap<Integer, Sat>();
    /** Lines or objects that couldn't be read. */
    public int rejected;

    public int size() {
        return byId.size();
    }

    public List<Sat> all() {
        return new ArrayList<Sat>(byId.values());
    }

    public Sat get(int norad) {
        return byId.get(norad);
    }

    /**
     * Adds the satellites of one file (OMM JSON, or two- or three-line elements): a set already here is
     * replaced when the new one is better for a session at {@code jdUtc} (supplemental and within
     * {@link #SUP_DAYS}, else newer). Returns how many were read.
     */
    public int add(String text, String source, double jdUtc) {
        if (text == null) return 0;
        String t = text.trim();
        List<Sat> read = t.startsWith("[") || t.startsWith("{") ? parseOmm(t, source) : parseTle(t, source);
        for (Sat s : read) {
            Sat old = byId.get(s.norad);
            if (old == null || better(s, old, jdUtc)) byId.put(s.norad, s);
        }
        return read.size();
    }

    /** Supplemental elements are preferred while their epoch is within this many days of the session. */
    static final double SUP_DAYS = 3;

    private static boolean better(Sat a, Sat b, double jdUtc) {
        boolean as = a.source.startsWith("supgp") && Math.abs(a.ageDays(jdUtc)) < SUP_DAYS;
        boolean bs = b.source.startsWith("supgp") && Math.abs(b.ageDays(jdUtc)) < SUP_DAYS;
        if (as != bs) return as;
        return a.orbit.jdEpoch > b.orbit.jdEpoch;
    }

    // ------------------------------------------------------------------------------------------------
    // OMM in JSON: an array of flat objects

    List<Sat> parseOmm(String json, String source) {
        List<Sat> out = new ArrayList<Sat>();
        Json p = new Json(json);
        try {
            p.ws();
            if (p.peek() == '{') {
                one(p.object(), source, out);
                return out;
            }
            p.expect('[');
            p.ws();
            if (p.peek() == ']') return out;
            while (true) {
                p.ws();
                one(p.object(), source, out);
                p.ws();
                char c = p.next();
                if (c == ']') break;
                if (c != ',') throw new IllegalArgumentException("expected , at " + p.i);
            }
        } catch (RuntimeException e) {
            rejected++;
        }
        return out;
    }

    private void one(Map<String, Object> o, String source, List<Sat> out) {
        try {
            String name = str(o, "OBJECT_NAME");
            String id = str(o, "NORAD_CAT_ID");
            String epoch = str(o, "EPOCH");
            if (id == null || epoch == null) {
                rejected++;
                return;
            }
            Sgp4 s = Sgp4.fromOmm(name != null ? name : "", id, epoch, num(o, "MEAN_MOTION"), num(o, "ECCENTRICITY"),
                    num(o, "INCLINATION"), num(o, "RA_OF_ASC_NODE"), num(o, "ARG_OF_PERICENTER"), num(o, "MEAN_ANOMALY"),
                    num(o, "BSTAR"), num(o, "MEAN_MOTION_DOT"), num(o, "MEAN_MOTION_DDOT"));
            if (s.error != 0) {
                rejected++;
                return;
            }
            int norad = (int) Math.round(Double.parseDouble(id.trim()));
            String nm = name != null ? name.trim() : String.valueOf(norad);
            out.add(new Sat(s, nm, norad, stdMag(nm, norad, source), source));
        } catch (RuntimeException e) {
            rejected++;
        }
    }

    private static String str(Map<String, Object> o, String k) {
        Object v = o.get(k);
        return v == null ? null : v.toString();
    }

    private static double num(Map<String, Object> o, String k) {
        Object v = o.get(k);
        if (v == null) return 0;
        if (v instanceof Double) return (Double) v;
        return Double.parseDouble(v.toString().trim());
    }

    /** Just enough JSON: objects of strings, numbers, true, false and null (nested values are skipped). */
    static final class Json {
        final String s;
        int i;

        Json(String s) {
            this.s = s;
        }

        char peek() {
            return i < s.length() ? s.charAt(i) : '\0';
        }

        char next() {
            if (i >= s.length()) throw new IllegalArgumentException("end of text");
            return s.charAt(i++);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        void expect(char c) {
            ws();
            if (next() != c) throw new IllegalArgumentException("expected " + c + " at " + (i - 1));
        }

        Map<String, Object> object() {
            Map<String, Object> m = new HashMap<String, Object>();
            expect('{');
            ws();
            if (peek() == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = string();
                expect(':');
                ws();
                m.put(k, value());
                ws();
                char c = next();
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , at " + (i - 1));
            }
        }

        Object value() {
            char c = peek();
            if (c == '"') return string();
            if (c == '{') {
                object();
                return null;
            }
            if (c == '[') {
                i++;
                ws();
                if (peek() == ']') {
                    i++;
                    return null;
                }
                while (true) {
                    ws();
                    value();
                    ws();
                    char d = next();
                    if (d == ']') return null;
                    if (d != ',') throw new IllegalArgumentException("expected , at " + (i - 1));
                }
            }
            if (s.startsWith("true", i)) {
                i += 4;
                return Boolean.TRUE;
            }
            if (s.startsWith("false", i)) {
                i += 5;
                return Boolean.FALSE;
            }
            if (s.startsWith("null", i)) {
                i += 4;
                return null;
            }
            int a = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (a == i) throw new IllegalArgumentException("unexpected " + c + " at " + i);
            return Double.parseDouble(s.substring(a, i));
        }

        String string() {
            if (next() != '"') throw new IllegalArgumentException("expected a string at " + (i - 1));
            StringBuilder b = null;
            int a = i;
            while (true) {
                char c = next();
                if (c == '"') return b == null ? s.substring(a, i - 1) : b.toString();
                if (c == '\\') {
                    if (b == null) b = new StringBuilder(s.substring(a, i - 1));
                    char e = next();
                    switch (e) {
                        case 'n':
                            b.append('\n');
                            break;
                        case 't':
                            b.append('\t');
                            break;
                        case 'r':
                            b.append('\r');
                            break;
                        case 'b':
                            b.append('\b');
                            break;
                        case 'f':
                            b.append('\f');
                            break;
                        case 'u':
                            b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default:
                            b.append(e);
                    }
                } else if (b != null) {
                    b.append(c);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------
    // Two-line elements, with or without a name line

    List<Sat> parseTle(String text, String source) {
        List<Sat> out = new ArrayList<Sat>();
        String[] lines = text.split("\r?\n");
        String name = null, l1 = null;
        for (String raw : lines) {
            String l = raw.replaceAll("\\s+$", "");
            if (l.isEmpty() || l.startsWith("#")) continue;
            if (l.startsWith("1 ") && l.length() >= 64) {
                l1 = l;
            } else if (l.startsWith("2 ") && l.length() >= 63 && l1 != null) {
                try {
                    Sgp4 s = Sgp4.fromTle(name != null ? name : "", l1, l);
                    if (s.error == 0) {
                        int norad = Integer.parseInt(s.satnum.trim());
                        String nm = name != null ? name : s.satnum.trim();
                        out.add(new Sat(s, nm, norad, stdMag(nm, norad, source), source));
                    } else {
                        rejected++;
                    }
                } catch (RuntimeException e) {
                    rejected++;
                }
                l1 = null;
                name = null;
            } else {
                name = l.startsWith("0 ") ? l.substring(2).trim() : l.trim();
            }
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------
    // Brightness and names

    /** Standard magnitudes (1000 km, half lit) of the brightest regulars, by catalogue number: ISS, Tianhe, Hubble. */
    private static final int[] KNOWN_ID = {25544, 48274, 20580};
    private static final double[] KNOWN_MAG = {-1.3, -0.5, 2.2};

    /**
     * A guess at the standard magnitude: the brightest regulars by number; the ISS's and the Chinese station's
     * parts and visitors as the station; Starlink and OneWeb satellites as the mitigated designs are; anything
     * on CelesTrak's list of easily seen satellites 3.5; the rest 6.5.
     */
    static double stdMag(String name, int norad, String source) {
        for (int k = 0; k < KNOWN_ID.length; k++) if (KNOWN_ID[k] == norad) return KNOWN_MAG[k];
        String n = name.toUpperCase(Locale.US);
        if (n.startsWith("ISS ") || n.equals("ISS")) return -1.3;
        if (n.startsWith("CSS ") || n.startsWith("TIANHE") || n.startsWith("TIANGONG")) return -0.5;
        if (n.startsWith("SOYUZ") || n.startsWith("PROGRESS") || n.startsWith("DRAGON") || n.startsWith("CREW DRAGON")
                || n.startsWith("CYGNUS") || n.startsWith("SHENZHOU") || n.startsWith("TIANZHOU") || n.startsWith("HTV")
                || n.startsWith("STARLINER")) return 3.0;
        if (n.startsWith("STARLINK")) return 6.0;
        if (n.startsWith("ONEWEB")) return 7.5;
        if (source.equals("visual")) return 3.5;
        if (source.equals("stations")) return 6.5;
        return 6.5;
    }

    /** "ISS (ZARYA)" to "ISS", "CSS (TIANHE)" to "Tiangong", "HST" to "Hubble", "STARLINK-1234" to "Starlink-1234"; others as listed. */
    static String displayName(String name, int norad) {
        String n = name.trim();
        String u = n.toUpperCase(Locale.US);
        if (u.startsWith("ISS ") || u.equals("ISS")) return "ISS";
        if (u.startsWith("CSS ") || u.startsWith("TIANHE")) return "Tiangong";
        if (u.equals("HST")) return "Hubble";
        if (u.startsWith("STARLINK")) return "Starlink" + n.substring(8);
        if (u.startsWith("ONEWEB")) return "OneWeb" + n.substring(6);
        return n.isEmpty() ? "#" + norad : n;
    }
}
