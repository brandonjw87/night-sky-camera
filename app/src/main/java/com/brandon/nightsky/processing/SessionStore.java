package com.brandon.nightsky.processing;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * One capture session on disk: settings and sensor description, an append-only unit list, the raw units,
 * the star list of every unit, the sensor-fixed sum (for hot pixels) and a log. Everything is written as it
 * happens, so a crash or a dead battery never loses the session.
 */
public final class SessionStore {
    public final File dir, framesDir, starsDir;
    private static final Charset UTF8 = Charset.forName("UTF-8");

    public SessionStore(File dir) {
        this.dir = dir;
        framesDir = new File(dir, "frames");
        starsDir = new File(dir, "stars");
    }

    public static SessionStore create(File root) throws IOException {
        String id = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File d = new File(root, "session_" + id);
        int n = 1;
        while (d.exists()) d = new File(root, "session_" + id + "_" + (n++));
        SessionStore s = new SessionStore(d);
        if (!s.framesDir.mkdirs() || !s.starsDir.mkdirs()) throw new IOException("Cannot create " + d);
        return s;
    }

    /** Sessions under root, newest first. */
    public static List<SessionStore> list(File root) {
        List<SessionStore> out = new ArrayList<SessionStore>();
        File[] f = root.listFiles();
        if (f == null) return out;
        java.util.Arrays.sort(f);
        for (int i = f.length - 1; i >= 0; i--) {
            if (f[i].isDirectory() && f[i].getName().startsWith("session_")) out.add(new SessionStore(f[i]));
        }
        return out;
    }

    public String id() {
        return dir.getName().substring("session_".length());
    }

    // ---- settings ----

    public synchronized void writeSettings(Properties p) throws IOException {
        File tmp = new File(dir, "session.properties.part");
        OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), UTF8);
        try {
            p.store(w, "Night Sky Camera session");
        } finally {
            w.close();
        }
        if (!tmp.renameTo(new File(dir, "session.properties"))) throw new IOException("rename failed");
    }

    public synchronized Properties readSettings() throws IOException {
        Properties p = new Properties();
        File f = new File(dir, "session.properties");
        if (!f.exists()) return p;
        InputStreamReader r = new InputStreamReader(new FileInputStream(f), UTF8);
        try {
            p.load(r);
        } finally {
            r.close();
        }
        return p;
    }

    public void setState(String state) throws IOException {
        Properties p = readSettings();
        p.setProperty("state", state);
        writeSettings(p);
    }

    public String state() {
        try {
            return readSettings().getProperty("state", "");
        } catch (IOException e) {
            return "";
        }
    }

    // ---- units ----

    public synchronized void appendUnit(UnitRecord r) throws IOException {
        File f = new File(dir, "units.csv");
        boolean header = !f.exists();
        FileOutputStream fos = new FileOutputStream(f, true);
        try {
            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(fos, UTF8));
            if (header) w.write(UnitRecord.HEADER + "\n");
            w.write(r.toCsv() + "\n");
            w.flush();
            fos.getFD().sync();
        } finally {
            fos.close();
        }
    }

    public synchronized List<UnitRecord> readUnits() throws IOException {
        List<UnitRecord> out = new ArrayList<UnitRecord>();
        File f = new File(dir, "units.csv");
        if (!f.exists()) return out;
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), UTF8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("index")) continue;
                UnitRecord u = UnitRecord.fromCsv(line);
                if (u != null) out.add(u);
            }
        } finally {
            r.close();
        }
        return out;
    }

    // ---- star lists ----

    private File starsFile(int index) {
        return new File(starsDir, String.format(Locale.US, "s%05d.bin", index));
    }

    public void writeStars(int index, StarList s) throws IOException {
        File tmp = new File(starsDir, starsFile(index).getName() + ".part");
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
        try {
            o.writeInt(0x53544152);
            o.writeInt(s.count);
            o.writeFloat(s.background);
            o.writeFloat(s.noise);
            for (int i = 0; i < s.count; i++) {
                o.writeFloat(s.x[i]);
                o.writeFloat(s.y[i]);
                o.writeFloat(s.flux[i]);
                o.writeFloat(s.hfr[i]);
                o.writeFloat(s.elong[i]);
                o.writeBoolean(s.saturated[i]);
            }
        } finally {
            o.close();
        }
        if (!tmp.renameTo(starsFile(index))) throw new IOException("rename failed");
    }

    public StarList readStars(int index) {
        File f = starsFile(index);
        if (!f.exists()) return null;
        try {
            DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)));
            try {
                if (in.readInt() != 0x53544152) return null;
                int n = in.readInt();
                StarList s = new StarList(n);
                s.background = in.readFloat();
                s.noise = in.readFloat();
                for (int i = 0; i < n; i++) {
                    float x = in.readFloat(), y = in.readFloat(), fl = in.readFloat(), h = in.readFloat(), e = in.readFloat();
                    boolean sat = in.readBoolean();
                    s.add(x, y, fl, h, e, sat);
                }
                return s;
            } finally {
                in.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    // ---- sensor-fixed sum ----

    public void writeSum(float[] sum, int count) throws IOException {
        File tmp = new File(dir, "sum.f32.part");
        RandomAccessFile f = new RandomAccessFile(tmp, "rw");
        try {
            FileChannel ch = f.getChannel();
            ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            head.putInt(count).putInt(sum.length);
            head.flip();
            while (head.hasRemaining()) ch.write(head);
            ByteBuffer b = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
            int i = 0;
            while (i < sum.length) {
                b.clear();
                int n = Math.min(sum.length - i, b.capacity() / 4);
                b.asFloatBuffer().put(sum, i, n);
                b.limit(n * 4);
                while (b.hasRemaining()) ch.write(b);
                i += n;
            }
        } finally {
            f.close();
        }
        if (!tmp.renameTo(new File(dir, "sum.f32"))) throw new IOException("rename failed");
    }

    /** Returns the sum, or null; count[0] receives how many units it holds. */
    public float[] readSum(int expectedLength, int[] count) {
        File file = new File(dir, "sum.f32");
        if (!file.exists() || file.length() != 8L + 4L * expectedLength) return null;
        try {
            RandomAccessFile f = new RandomAccessFile(file, "r");
            try {
                FileChannel ch = f.getChannel();
                ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                while (head.hasRemaining()) if (ch.read(head) < 0) return null;
                head.flip();
                count[0] = head.getInt();
                if (head.getInt() != expectedLength) return null;
                float[] sum = new float[expectedLength];
                ByteBuffer b = ByteBuffer.allocateDirect(1 << 20).order(ByteOrder.LITTLE_ENDIAN);
                int i = 0;
                while (i < expectedLength) {
                    b.clear();
                    int n = Math.min(expectedLength - i, b.capacity() / 4);
                    b.limit(n * 4);
                    while (b.hasRemaining()) if (ch.read(b) < 0) return null;
                    b.flip();
                    b.asFloatBuffer().get(sum, i, n);
                    i += n;
                }
                return sum;
            } finally {
                f.close();
            }
        } catch (IOException e) {
            return null;
        }
    }

    // ---- log ----

    public File logFile() {
        return new File(dir, "log.txt");
    }

    public synchronized void log(String line) {
        try {
            FileWriter w = new FileWriter(logFile(), true);
            try {
                String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                w.write(ts + " " + line + "\n");
            } finally {
                w.close();
            }
        } catch (IOException ignored) {
        }
    }

    public String readLog() {
        try {
            BufferedReader r = new BufferedReader(new FileReader(logFile()));
            try {
                StringBuilder sb = new StringBuilder();
                String l;
                while ((l = r.readLine()) != null) sb.append(l).append('\n');
                return sb.toString();
            } finally {
                r.close();
            }
        } catch (IOException e) {
            return "";
        }
    }

    public FrameStore frames(int width, int height) {
        return new FrameStore(framesDir, width, height);
    }

    public long sizeBytes() {
        return size(dir);
    }

    private static long size(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] l = f.listFiles();
        if (l != null) for (File c : l) s += size(c);
        return s;
    }

    public void deleteAll() {
        delete(dir);
    }

    /** Deletes the bulk of a session (frames, star lists, running sum); its settings, unit list and log stay. */
    public void deleteBulk() {
        delete(framesDir);
        delete(starsDir);
        //noinspection ResultOfMethodCallIgnored
        new File(dir, "sum.f32").delete();
    }

    private static void delete(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) delete(c);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
