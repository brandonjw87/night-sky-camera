package com.brandon.nightsky.processing;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Minimal DNG 1.4 writer for the stacked image: 16-bit LinearRaw RGB (camera colour space, black level and
 * lens shading already handled) with the sensor's own colour matrices, so Lightroom, Snapseed, darktable
 * and RawTherapee render it like a raw file. Android's DngCreator can't be used for the stack because it
 * takes the white level from the 10-bit sensor and would throw away the precision stacking gained.
 */
public final class DngWriter {
    private DngWriter() {}

    public static final class Meta {
        public String software = "Night Sky Camera";
        public Date time = new Date();
        public double exposureSeconds;
        public int iso;
        public String description = "";
        /** Extra exposure the raw converter should apply (EV), so the sky isn't rendered black. */
        public double baselineExposure;
    }

    private static final int BYTE = 1, ASCII = 2, SHORT = 3, LONG = 4, RATIONAL = 5, SRATIONAL = 10;

    private static final class Entry {
        final int tag, type, count;
        final byte[] data;
        long offset; // for data > 4 bytes

        Entry(int tag, int type, int count, byte[] data) {
            this.tag = tag;
            this.type = type;
            this.count = count;
            this.data = data;
        }
    }

    private static final class Ifd {
        final List<Entry> entries = new ArrayList<Entry>();

        Entry add(int tag, int type, int count, byte[] data) {
            Entry e = new Entry(tag, type, count, data);
            entries.add(e);
            return e;
        }

        void sort() {
            Collections.sort(entries, new Comparator<Entry>() {
                @Override
                public int compare(Entry a, Entry b) {
                    return a.tag - b.tag;
                }
            });
        }

        int headerSize() {
            return 2 + 12 * entries.size() + 4;
        }

        int dataSize() {
            int s = 0;
            for (Entry e : entries) if (e.data.length > 4) s += (e.data.length + 1) & ~1;
            return s;
        }

        /** Assigns offsets to out-of-line values that follow the IFD at {@code base}. */
        void layout(long base) {
            long off = base + headerSize();
            for (Entry e : entries) {
                if (e.data.length > 4) {
                    e.offset = off;
                    off += (e.data.length + 1) & ~1;
                }
            }
        }

        void write(ByteBuffer bb, long nextIfd) {
            bb.putShort((short) entries.size());
            for (Entry e : entries) {
                bb.putShort((short) e.tag);
                bb.putShort((short) e.type);
                bb.putInt(e.count);
                if (e.data.length > 4) {
                    bb.putInt((int) e.offset);
                } else {
                    bb.put(e.data);
                    for (int i = e.data.length; i < 4; i++) bb.put((byte) 0);
                }
            }
            bb.putInt((int) nextIfd);
            for (Entry e : entries) {
                if (e.data.length > 4) {
                    bb.put(e.data);
                    if ((e.data.length & 1) == 1) bb.put((byte) 0);
                }
            }
        }
    }

    private static ByteBuffer le(int n) {
        return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static byte[] shorts(int... v) {
        ByteBuffer b = le(2 * v.length);
        for (int x : v) b.putShort((short) x);
        return b.array();
    }

    private static byte[] longs(long... v) {
        ByteBuffer b = le(4 * v.length);
        for (long x : v) b.putInt((int) x);
        return b.array();
    }

    private static byte[] ascii(String s) {
        byte[] t = s.getBytes(Charset.forName("US-ASCII"));
        byte[] r = new byte[t.length + 1];
        System.arraycopy(t, 0, r, 0, t.length);
        return r;
    }

    private static byte[] srational(float[] v) {
        ByteBuffer b = le(8 * v.length);
        for (float x : v) {
            b.putInt(Math.round(x * 10000f));
            b.putInt(10000);
        }
        return b.array();
    }

    private static byte[] rational(double... v) {
        ByteBuffer b = le(8 * v.length);
        for (double x : v) {
            long den = 1000000;
            long num = Math.round(x * den);
            while (num > Integer.MAX_VALUE && den > 1) {
                den /= 10;
                num = Math.round(x * den);
            }
            b.putInt((int) Math.max(0, Math.min(Integer.MAX_VALUE, num)));
            b.putInt((int) den);
        }
        return b.array();
    }

    /**
     * Writes the crop area of {@code img} as a LinearRaw DNG.
     *
     * @param maxGain largest lens-shading gain applied (values can exceed the per-frame clip level by this)
     */
    public static void write(OutputStream os, RgbImage img, SensorSpec spec, Meta meta, float maxGain) throws IOException {
        final int w = img.cropWidth(), h = img.cropHeight();
        final int ped = 1024;
        final double scale = (65535.0 - ped) / (Math.max(1f, spec.range()) * Math.max(1f, maxGain));
        final int rowsPerStrip = 32;
        final int strips = (h + rowsPerStrip - 1) / rowsPerStrip;
        String dt = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(meta.time);
        String model = spec.deviceModel.isEmpty() ? "Phone" : spec.deviceModel;
        String make = spec.deviceMake.isEmpty() ? "Unknown" : spec.deviceMake;

        Ifd ifd = new Ifd();
        ifd.add(254, LONG, 1, longs(0));
        ifd.add(256, LONG, 1, longs(w));
        ifd.add(257, LONG, 1, longs(h));
        ifd.add(258, SHORT, 3, shorts(16, 16, 16));
        ifd.add(259, SHORT, 1, shorts(1));
        ifd.add(262, SHORT, 1, shorts(34892));
        ifd.add(270, ASCII, ascii(meta.description).length, ascii(meta.description));
        ifd.add(271, ASCII, ascii(make).length, ascii(make));
        ifd.add(272, ASCII, ascii(model).length, ascii(model));
        Entry stripOffsets = ifd.add(273, LONG, strips, new byte[4 * strips]);
        ifd.add(274, SHORT, 1, shorts(1));
        ifd.add(277, SHORT, 1, shorts(3));
        ifd.add(278, LONG, 1, longs(rowsPerStrip));
        long[] counts = new long[strips];
        for (int s = 0; s < strips; s++) counts[s] = (long) Math.min(rowsPerStrip, h - s * rowsPerStrip) * w * 6;
        ifd.add(279, LONG, strips, longs(counts));
        ifd.add(284, SHORT, 1, shorts(1));
        ifd.add(305, ASCII, ascii(meta.software).length, ascii(meta.software));
        ifd.add(306, ASCII, ascii(dt).length, ascii(dt));
        Entry exifPtr = ifd.add(34665, LONG, 1, longs(0));
        ifd.add(50706, BYTE, 4, new byte[]{1, 4, 0, 0});
        ifd.add(50707, BYTE, 4, new byte[]{1, 1, 0, 0});
        String ucm = make + " " + model + " NightSky";
        ifd.add(50708, ASCII, ascii(ucm).length, ascii(ucm));
        ifd.add(50714, LONG, 3, longs(ped, ped, ped));
        ifd.add(50717, LONG, 3, longs(65535, 65535, 65535));

        float[] cm1 = spec.colorMatrix1, cm2 = spec.colorMatrix2;
        if (cm1 == null && cm2 == null) cm1 = fallbackColorMatrix(spec);
        if (cm1 == null) {
            cm1 = cm2;
            cm2 = null;
        }
        int ill1 = spec.illuminant1 != 0 ? spec.illuminant1 : 21;
        int ill2 = spec.illuminant2 != 0 ? spec.illuminant2 : 17;
        ifd.add(50721, SRATIONAL, 9, srational(cm1));
        if (cm2 != null) ifd.add(50722, SRATIONAL, 9, srational(cm2));
        if (spec.calibration1 != null) ifd.add(50723, SRATIONAL, 9, srational(spec.calibration1));
        if (spec.calibration2 != null && cm2 != null) ifd.add(50724, SRATIONAL, 9, srational(spec.calibration2));
        float[] n = spec.camNeutral;
        ifd.add(50728, RATIONAL, 3, rational(n[0] / n[1], 1.0, n[2] / n[1]));
        ifd.add(50730, SRATIONAL, 1, srational(new float[]{(float) meta.baselineExposure}));
        ifd.add(50778, SHORT, 1, shorts(ill1));
        if (cm2 != null) ifd.add(50779, SHORT, 1, shorts(ill2));
        if (spec.forwardMatrix1 != null && spec.colorMatrix1 != null) {
            ifd.add(50964, SRATIONAL, 9, srational(spec.forwardMatrix1));
            if (spec.forwardMatrix2 != null && cm2 != null) ifd.add(50965, SRATIONAL, 9, srational(spec.forwardMatrix2));
        }
        ifd.sort();

        Ifd exif = new Ifd();
        if (meta.exposureSeconds > 0) exif.add(33434, RATIONAL, 1, rational(meta.exposureSeconds));
        if (spec.fNumber > 0) exif.add(33437, RATIONAL, 1, rational(spec.fNumber));
        if (meta.iso > 0) exif.add(34855, SHORT, 1, shorts(Math.min(65535, meta.iso)));
        exif.add(36864, 7, 4, new byte[]{'0', '2', '3', '1'});
        exif.add(36867, ASCII, ascii(dt).length, ascii(dt));
        if (spec.focalLengthMm > 0) exif.add(37386, RATIONAL, 1, rational(spec.focalLengthMm));
        exif.sort();

        long ifd0 = 8;
        ifd.layout(ifd0);
        long exifOff = ifd0 + ifd.headerSize() + ifd.dataSize();
        exifOff = (exifOff + 1) & ~1L;
        exif.layout(exifOff);
        long dataStart = exifOff + exif.headerSize() + exif.dataSize();
        dataStart = (dataStart + 15) & ~15L;
        long[] offs = new long[strips];
        long off = dataStart;
        for (int s = 0; s < strips; s++) {
            offs[s] = off;
            off += counts[s];
        }
        if (off > 0xFFFFFFFFL) throw new IOException("DNG too large");
        System.arraycopy(longs(offs), 0, stripOffsets.data, 0, 4 * strips);
        System.arraycopy(longs(exifOff), 0, exifPtr.data, 0, 4);

        ByteBuffer head = le((int) dataStart);
        head.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt((int) ifd0);
        head.position((int) ifd0);
        ifd.write(head, 0);
        head.position((int) exifOff);
        exif.write(head, 0);
        os.write(head.array(), 0, (int) dataStart);

        final byte[] strip = new byte[rowsPerStrip * w * 6];
        for (int s = 0; s < strips; s++) {
            final int r0 = s * rowsPerStrip, r1 = Math.min(h, r0 + rowsPerStrip);
            Parallel.forRange(r1 - r0, 4, new Parallel.Range() {
                @Override
                public void run(int a, int b) {
                    for (int j = a; j < b; j++) {
                        int src = (img.y0 + r0 + j) * img.width + img.x0;
                        int o = j * w * 6;
                        for (int x = 0; x < w; x++) {
                            int i = src + x;
                            put16(strip, o, img.r[i], scale, ped);
                            put16(strip, o + 2, img.g[i], scale, ped);
                            put16(strip, o + 4, img.b[i], scale, ped);
                            o += 6;
                        }
                    }
                }
            });
            os.write(strip, 0, (r1 - r0) * w * 6);
        }
        os.flush();
    }

    private static void put16(byte[] b, int o, float v, double scale, int ped) {
        long q = Math.round(ped + v * scale);
        int u = (int) (q < 0 ? 0 : (q > 65535 ? 65535 : q));
        b[o] = (byte) u;
        b[o + 1] = (byte) (u >>> 8);
    }

    /** XYZ -> camera estimate from the rendering matrix when the device gives no colour matrices (D65). */
    private static float[] fallbackColorMatrix(SensorSpec spec) {
        double[] srgbToXyz = {0.4124564, 0.3575761, 0.1804375, 0.2126729, 0.7151522, 0.0721750, 0.0193339, 0.1191920, 0.9503041};
        double[] m = new double[9];
        for (int i = 0; i < 9; i++) m[i] = spec.camToSrgb[i];
        double[] inv = Rot.inverse(Rot.mul(srgbToXyz, m));
        if (inv == null) return new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1};
        float[] r = new float[9];
        for (int i = 0; i < 9; i++) r[i] = (float) inv[i];
        return r;
    }
}
