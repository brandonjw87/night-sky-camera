package com.brandon.nightsky.processing;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

/**
 * The phone's copy of CelesTrak's orbital elements (OMM JSON), one file per group: fetched on demand, at most
 * once per CelesTrak update (every 2 hours) per group, never again by itself after a 403 (CelesTrak's answer to
 * too many downloads), backing off after other errors, and kept with the time it was fetched. Elements stay
 * good for days, so naming streaks and warning of passes work offline.
 */
public final class SatData {
    /** One list from CelesTrak. */
    public static final class Group {
        public final String key, url;
        /** What the settings call it. */
        public final String label;
        /** Thousands of objects (Starlink): fetched only when a large download is fine (Wi-Fi). */
        public final boolean large;

        Group(String key, String label, String url, boolean large) {
            this.key = key;
            this.label = label;
            this.url = url;
            this.large = large;
        }
    }

    static final String GP = "https://celestrak.org/NORAD/elements/gp.php?";
    /** The bright satellites, the space stations (ISS, Tiangong) and Starlink, and Starlink's own predictions
     *  (more accurate just after a launch, when the trains are brightest). The key is also the source name the
     *  catalogue gives the satellites ("supgp" ones are preferred while fresh). */
    public static final Group[] GROUPS = {
            new Group("visual", "the bright satellites", GP + "GROUP=visual&FORMAT=json", false),
            new Group("stations", "the space stations", GP + "GROUP=stations&FORMAT=json", false),
            new Group("starlink", "Starlink", GP + "GROUP=starlink&FORMAT=json", true),
            new Group("supgp-starlink", "Starlink's own predictions",
                    "https://celestrak.org/NORAD/elements/supplemental/sup-gp.php?FILE=starlink&FORMAT=json", true)};

    /** CelesTrak updates the elements every 2 hours: no group is fetched more often. */
    public static final long MIN_INTERVAL_MS = 2 * 3600_000L;
    /** After an error: wait this long, doubling with each further error, up to a day. */
    static final long BACKOFF_MS = 15 * 60_000L, MAX_BACKOFF_MS = 24 * 3600_000L;
    /** Largest answer read (bytes). */
    static final int MAX_BYTES = 40 << 20;

    /** What a fetch got back. */
    public static final class Response {
        public final int code;
        public final byte[] body;

        public Response(int code, byte[] body) {
            this.code = code;
            this.body = body;
        }
    }

    /** Fetches a URL (an interface so tests can stand in for CelesTrak). */
    public interface Fetcher {
        Response get(String url) throws IOException;
    }

    private final File dir;
    private final Fetcher fetcher;
    /** Held while a refresh runs: one at a time. */
    private final Object fetching = new Object();

    public SatData(File dir, Fetcher fetcher) {
        this.dir = dir;
        this.fetcher = fetcher;
    }

    /** The real thing: HTTPS with a 20-second timeout, gzip accepted. */
    public static Fetcher http(final String userAgent) {
        return new Fetcher() {
            @Override
            public Response get(String url) throws IOException {
                HttpURLConnection c = (HttpURLConnection) java.net.URI.create(url).toURL().openConnection();
                c.setConnectTimeout(20000);
                c.setReadTimeout(30000);
                c.setRequestProperty("User-Agent", userAgent);
                c.setRequestProperty("Accept-Encoding", "gzip");
                try {
                    int code = c.getResponseCode();
                    if (code != 200) return new Response(code, null);
                    InputStream in = c.getInputStream();
                    if ("gzip".equalsIgnoreCase(c.getContentEncoding())) in = new GZIPInputStream(in);
                    try {
                        return new Response(code, readAll(in, MAX_BYTES));
                    } finally {
                        in.close();
                    }
                } finally {
                    c.disconnect();
                }
            }
        };
    }

    // ------------------------------------------------------------------------------------------------
    // fetching

    /**
     * Fetches the groups that are due: older than {@link #MIN_INTERVAL_MS} (or never fetched), not refused with
     * a 403 (unless {@code retryRefused}, when Brandon asks), past any back-off, and small unless
     * {@code large}. Returns a line for the log saying what happened ("" if nothing was due).
     */
    public String refresh(long nowMs, boolean large, boolean retryRefused) {
        // one refresh at a time; the cache stays readable while a download runs (files are replaced whole)
        synchronized (fetching) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            List<String> done = new ArrayList<String>();
            for (Group g : GROUPS) {
                Properties m;
                synchronized (this) {
                    m = meta(g);
                    long fetched = num(m, "fetchedMs"), backoff = num(m, "backoffUntilMs");
                    boolean refused = num(m, "refusedMs") > 0;
                    if (g.large && !large) continue;
                    if (refused && !retryRefused) continue;
                    if (fetched > 0 && nowMs - fetched < MIN_INTERVAL_MS) continue;
                    if (!refused && nowMs < backoff) continue;
                }
                m.setProperty("triedMs", Long.toString(nowMs));
                String what, before = m.getProperty("fetchedMs"), countBefore = m.getProperty("count");
                byte[] body = null;
                int count = 0;
                try {
                    Response r = fetcher.get(g.url);
                    if (r.code == 200 && r.body != null) {
                        String text = new String(r.body, StandardCharsets.UTF_8);
                        SatCatalog check = new SatCatalog();
                        count = check.add(text, g.key, Astro.jdUtc(nowMs / 1000.0));
                        if (count == 0 && !text.trim().equals("[]")) throw new IOException("no elements in the answer");
                        body = r.body;
                        m.setProperty("fetchedMs", Long.toString(nowMs));
                        m.setProperty("count", Integer.toString(count));
                        m.setProperty("bytes", Integer.toString(r.body.length));
                        m.remove("failures");
                        m.remove("backoffUntilMs");
                        m.remove("refusedMs");
                        m.remove("error");
                        what = g.key + " " + count;
                    } else if (r.code == 403) {
                        // CelesTrak refuses clients that download too often: never by itself again
                        m.setProperty("refusedMs", Long.toString(nowMs));
                        m.setProperty("error", "refused (403)");
                        what = g.key + " refused (403), not asking again by itself";
                    } else {
                        throw new IOException("HTTP " + r.code);
                    }
                } catch (IOException | RuntimeException e) {
                    int fails = (int) num(m, "failures") + 1;
                    long wait = Math.min(MAX_BACKOFF_MS, BACKOFF_MS << Math.min(10, fails - 1));
                    m.setProperty("failures", Integer.toString(fails));
                    m.setProperty("backoffUntilMs", Long.toString(nowMs + wait));
                    m.setProperty("error", String.valueOf(e.getMessage()));
                    what = String.format(Locale.US, "%s failed (%s), next try in %d min", g.key, e.getMessage(), wait / 60_000L);
                }
                synchronized (this) {
                    if (body != null) {
                        try {
                            write(new File(dir, g.key + ".json"), body);
                        } catch (IOException e) {
                            // the list on file (if any) is still the one fetched before
                            if (before != null) m.setProperty("fetchedMs", before);
                            else m.remove("fetchedMs");
                            if (countBefore != null) m.setProperty("count", countBefore);
                            else m.remove("count");
                            m.setProperty("error", "could not save: " + e.getMessage());
                            what = g.key + " could not be saved (" + e.getMessage() + ")";
                        }
                    }
                    saveMeta(g, m);
                }
                done.add(what);
            }
            return done.isEmpty() ? "" : "satellite data: " + SkyGuide.list(done);
        }
    }

    /** Whether any group could be fetched now (so a caller can skip starting a thread). */
    public synchronized boolean due(long nowMs, boolean large) {
        for (Group g : GROUPS) {
            if (g.large && !large) continue;
            Properties m = meta(g);
            if (num(m, "refusedMs") > 0) continue;
            long fetched = num(m, "fetchedMs");
            if (fetched > 0 && nowMs - fetched < MIN_INTERVAL_MS) continue;
            if (nowMs < num(m, "backoffUntilMs")) continue;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------------
    // reading

    /** Every cached group as one catalogue, for a session at {@code jdUtc}; null if nothing is cached. */
    public synchronized SatCatalog catalog(double jdUtc) {
        SatCatalog c = new SatCatalog();
        for (Group g : GROUPS) {
            File f = new File(dir, g.key + ".json");
            if (!f.isFile()) continue;
            try {
                c.add(new String(read(f), StandardCharsets.UTF_8), g.key, jdUtc);
            } catch (IOException ignored) {
            }
        }
        return c.size() > 0 ? c : null;
    }

    /** Newest time any group was fetched (ms), 0 if never. */
    public synchronized long newestFetchMs() {
        long t = 0;
        for (Group g : GROUPS) t = Math.max(t, num(meta(g), "fetchedMs"));
        return t;
    }

    /** Oldest fetch among the groups that have one (ms), 0 if none. */
    public synchronized long oldestFetchMs() {
        long t = 0;
        for (Group g : GROUPS) {
            long f = num(meta(g), "fetchedMs");
            if (f > 0) t = t == 0 ? f : Math.min(t, f);
        }
        return t;
    }

    /** Whether CelesTrak refused a group (a 403) and it isn't being asked again. */
    public synchronized boolean refused() {
        for (Group g : GROUPS) if (num(meta(g), "refusedMs") > 0) return true;
        return false;
    }

    /** One line for the settings: how many satellites, how old, and anything wrong. */
    public synchronized String status(long nowMs) {
        int count = 0;
        long newest = 0;
        List<String> problems = new ArrayList<String>();
        for (Group g : GROUPS) {
            Properties m = meta(g);
            count += (int) num(m, "count");
            newest = Math.max(newest, num(m, "fetchedMs"));
            if (num(m, "refusedMs") > 0) problems.add(g.label + " refused by CelesTrak (too many downloads), not asked for again until you tap Update");
            else if (m.getProperty("error") != null) problems.add(g.label + " failed (" + m.getProperty("error") + ")");
        }
        StringBuilder sb = new StringBuilder();
        if (newest == 0) sb.append("No satellite data yet");
        else sb.append(String.format(Locale.US, "%,d satellites, updated %s", count, ago(nowMs - newest)));
        if (!problems.isEmpty()) sb.append(". ").append(SkyGuide.list(problems));
        return sb.toString();
    }

    /** "just now", "12 min ago", "3 h ago", "2 days ago". */
    static String ago(long ms) {
        long min = Math.max(0, ms / 60_000L);
        if (min < 2) return "just now";
        if (min < 120) return min + " min ago";
        long h = min / 60;
        if (h < 48) return h + " h ago";
        return h / 24 + " days ago";
    }

    // ------------------------------------------------------------------------------------------------
    // files

    private Properties meta(Group g) {
        Properties p = new Properties();
        File f = new File(dir, g.key + ".properties");
        if (!f.isFile()) return p;
        try {
            FileInputStream in = new FileInputStream(f);
            try {
                p.load(in);
            } finally {
                in.close();
            }
        } catch (IOException ignored) {
        }
        return p;
    }

    private void saveMeta(Group g, Properties m) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            m.store(b, "CelesTrak " + g.key);
            write(new File(dir, g.key + ".properties"), b.toByteArray());
        } catch (IOException ignored) {
        }
    }

    private static long num(Properties p, String k) {
        String v = p.getProperty(k);
        if (v == null) return 0;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Writes through a temporary file, so a half-written file never replaces a good one. */
    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            o.write(data);
            o.getFD().sync();
        } finally {
            o.close();
        }
        if (!tmp.renameTo(f)) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("could not replace " + f.getName());
        }
    }

    private static byte[] read(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            return readAll(in, MAX_BYTES);
        } finally {
            in.close();
        }
    }

    static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) {
            o.write(buf, 0, n);
            if (o.size() > max) throw new IOException("answer too large");
        }
        return o.toByteArray();
    }
}
