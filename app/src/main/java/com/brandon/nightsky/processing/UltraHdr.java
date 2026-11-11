package com.brandon.nightsky.processing;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Android's Ultra HDR photo format (https://developer.android.com/media/platform/hdr-image-format): an ordinary JPEG
 * with a second, small JPEG right behind it, the gain map, which says how much brighter than white each part of the
 * picture may be shown on a screen that has the headroom. A phone with an HDR screen (the Pixel's Photos app, Chrome)
 * shows the stars shining; everything else decodes the first JPEG and shows the photo exactly as before.
 *
 * <p>The layout, as Android's own encoder writes it and its decoder reads it:
 * <ul>
 * <li>the photo's JPEG, with an XMP block that carries the gain map version and a container directory (the photo, the
 * gain map and its length, then the video and its length when the photo is a Motion Photo too), and a Multi-Picture
 * Format block (CIPA DC-007) giving the second picture's place and size;
 * <li>the gain map's JPEG, with an XMP block of its own carrying the numbers that turn its values into gains;
 * <li>the Motion Photo's video, if any (see {@link MotionPhoto}), last.
 * </ul>
 */
public final class UltraHdr {
    private UltraHdr() {}

    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_SEGMENT = 65535;
    /** The MPF block is always this long, marker and all (two pictures). */
    static final int MPF_SEGMENT = 90;
    /** From the MPF block's first byte to the byte its offsets count from (its byte-order mark). */
    static final int MPF_BASE = 8;

    /** How a gain map's values (0..1) become gains, in stops (log2), as the format's metadata has them. */
    public static final class Meta {
        /** The gain at map value 0 and at map value 1, in stops. */
        public double gainMapMin = 0, gainMapMax = 2;
        /** The map's values are raised to 1/gamma before they're used. */
        public double gamma = 1;
        /** Added to the photo's and the HDR picture's light before the ratio is taken (keeps black out of it). */
        public double offsetSdr = 1.0 / 64, offsetHdr = 1.0 / 64;
        /** The screen headroom, in stops, below which the map isn't used and from which it's used in full. */
        public double capacityMin = 0, capacityMax = 2;
    }

    /** The XMP of the photo: the gain map's version and the directory of what follows the photo. */
    public static String primaryXmp(long gainLength, long videoLength, long stillUs) {
        StringBuilder s = new StringBuilder(1200);
        s.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Night Sky Camera\">\n")
                .append(" <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
                .append("  <rdf:Description rdf:about=\"\"\n")
                .append("    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n")
                .append("    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n")
                .append("    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n")
                .append("    xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n")
                .append("   hdrgm:Version=\"1.0\"");
        if (videoLength > 0) {
            s.append("\n   GCamera:MotionPhoto=\"1\"\n")
                    .append("   GCamera:MotionPhotoVersion=\"1\"\n")
                    .append("   GCamera:MotionPhotoPresentationTimestampUs=\"").append(stillUs).append("\"\n")
                    .append("   GCamera:MicroVideo=\"1\"\n")
                    .append("   GCamera:MicroVideoVersion=\"1\"\n")
                    .append("   GCamera:MicroVideoOffset=\"").append(videoLength).append("\"\n")
                    .append("   GCamera:MicroVideoPresentationTimestampUs=\"").append(stillUs).append("\"");
        }
        s.append(">\n")
                .append("   <Container:Directory>\n")
                .append("    <rdf:Seq>\n")
                .append("     <rdf:li rdf:parseType=\"Resource\">\n")
                .append("      <Container:Item\n")
                .append("        Item:Mime=\"image/jpeg\"\n")
                .append("        Item:Semantic=\"Primary\"\n")
                .append("        Item:Length=\"0\"\n")
                .append("        Item:Padding=\"0\"/>\n")
                .append("     </rdf:li>\n")
                .append("     <rdf:li rdf:parseType=\"Resource\">\n")
                .append("      <Container:Item\n")
                .append("        Item:Mime=\"image/jpeg\"\n")
                .append("        Item:Semantic=\"GainMap\"\n")
                .append("        Item:Length=\"").append(gainLength).append("\"/>\n")
                .append("     </rdf:li>\n");
        if (videoLength > 0) {
            s.append("     <rdf:li rdf:parseType=\"Resource\">\n")
                    .append("      <Container:Item\n")
                    .append("        Item:Mime=\"video/mp4\"\n")
                    .append("        Item:Semantic=\"MotionPhoto\"\n")
                    .append("        Item:Length=\"").append(videoLength).append("\"/>\n")
                    .append("     </rdf:li>\n");
        }
        s.append("    </rdf:Seq>\n")
                .append("   </Container:Directory>\n")
                .append("  </rdf:Description>\n")
                .append(" </rdf:RDF>\n")
                .append("</x:xmpmeta>");
        return s.toString();
    }

    /** The XMP of the gain map: the numbers that turn its values into gains. */
    public static String gainXmp(Meta m) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Night Sky Camera\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description rdf:about=\"\"\n"
                + "    xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"\n"
                + "   hdrgm:Version=\"1.0\"\n"
                + "   hdrgm:GainMapMin=\"" + num(m.gainMapMin) + "\"\n"
                + "   hdrgm:GainMapMax=\"" + num(m.gainMapMax) + "\"\n"
                + "   hdrgm:Gamma=\"" + num(m.gamma) + "\"\n"
                + "   hdrgm:OffsetSDR=\"" + num(m.offsetSdr) + "\"\n"
                + "   hdrgm:OffsetHDR=\"" + num(m.offsetHdr) + "\"\n"
                + "   hdrgm:HDRCapacityMin=\"" + num(m.capacityMin) + "\"\n"
                + "   hdrgm:HDRCapacityMax=\"" + num(m.capacityMax) + "\"\n"
                + "   hdrgm:BaseRenditionIsHDR=\"False\"/>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>";
    }

    private static String num(double v) {
        String s = String.format(Locale.US, "%.6f", v);
        if (s.indexOf('.') >= 0) {
            int end = s.length();
            while (end > 0 && s.charAt(end - 1) == '0') end--;
            if (s.charAt(end - 1) == '.') end--;
            s = s.substring(0, end);
        }
        return s;
    }

    static byte[] xmpSegment(String xmp) throws IOException {
        byte[] body = xmp.getBytes(UTF8);
        int len = 2 + MotionPhoto.XMP_ID.length() + 1 + body.length;
        if (len > MAX_SEGMENT) throw new IOException("XMP block too long");
        ByteArrayOutputStream seg = new ByteArrayOutputStream(len + 2);
        seg.write(0xFF);
        seg.write(0xE1);
        seg.write(len >> 8);
        seg.write(len & 255);
        seg.write(MotionPhoto.XMP_ID.getBytes(UTF8));
        seg.write(0);
        seg.write(body);
        return seg.toByteArray();
    }

    /**
     * The Multi-Picture Format block for two pictures: the photo ({@code primaryLength} bytes from the file's start)
     * and the gain map ({@code gainLength} bytes, starting {@code gainOffset} bytes after this block's byte-order mark).
     */
    static byte[] mpfSegment(long primaryLength, long gainLength, long gainOffset) throws IOException {
        if (primaryLength > 0xFFFFFFFFL || gainLength > 0xFFFFFFFFL || gainOffset > 0xFFFFFFFFL || gainOffset < 0) {
            throw new IOException("The photo is too large for its picture index");
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream(MPF_SEGMENT);
        b.write(0xFF);
        b.write(0xE2);
        put16(b, MPF_SEGMENT - 2);
        b.write('M');
        b.write('P');
        b.write('F');
        b.write(0);
        // a TIFF header (big-endian), the index directory right after it
        b.write('M');
        b.write('M');
        put16(b, 0x002A);
        put32(b, 8);
        put16(b, 3);
        // version "0100"
        put16(b, 0xB000);
        put16(b, 7);
        put32(b, 4);
        b.write('0');
        b.write('1');
        b.write('0');
        b.write('0');
        // number of pictures
        put16(b, 0xB001);
        put16(b, 4);
        put32(b, 1);
        put32(b, 2);
        // the entries: 16 bytes a picture, right after this directory
        put16(b, 0xB002);
        put16(b, 7);
        put32(b, 32);
        put32(b, 8 + 2 + 3 * 12 + 4);
        put32(b, 0); // no further directory
        // the photo: a baseline primary image at the file's start
        put32(b, 0x00030000L);
        put32(b, primaryLength);
        put32(b, 0);
        put16(b, 0);
        put16(b, 0);
        // the gain map
        put32(b, 0);
        put32(b, gainLength);
        put32(b, gainOffset);
        put16(b, 0);
        put16(b, 0);
        if (b.size() != MPF_SEGMENT) throw new IOException("picture index of " + b.size() + " bytes");
        return b.toByteArray();
    }

    private static void put16(ByteArrayOutputStream b, int v) {
        b.write((v >> 8) & 255);
        b.write(v & 255);
    }

    private static void put32(ByteArrayOutputStream b, long v) {
        b.write((int) ((v >> 24) & 255));
        b.write((int) ((v >> 16) & 255));
        b.write((int) ((v >> 8) & 255));
        b.write((int) (v & 255));
    }

    /**
     * Writes {@code jpeg} to {@code out} as an Ultra HDR photo with the gain map {@code gain} (a JPEG of its own, any
     * size with the photo's shape) and, if {@code video} isn't null, as a Motion Photo too. The photo's own blocks stay
     * as they are but for XMP (replaced) and an old picture index (dropped). Throws before anything is written if
     * either JPEG can't be read as one or doesn't end at its end marker, so the caller can save the plain photo.
     */
    public static void write(File jpeg, File gain, Meta meta, File video, long stillUs, OutputStream out) throws IOException {
        long videoLength = video != null ? video.length() : 0;
        if (video != null && videoLength < 8) throw new IOException("The video is empty");
        RandomAccessFile f = new RandomAccessFile(jpeg, "r");
        RandomAccessFile g = new RandomAccessFile(gain, "r");
        try {
            MotionPhoto.Layout l = MotionPhoto.layout(f);
            MotionPhoto.Layout lg = MotionPhoto.layout(g);
            dropIndex(f, l);
            dropIndex(g, lg);

            // the gain map's file: its blocks and its data as they are, its own XMP after the leading JFIF block (where
            // Android's encoder puts it)
            byte[] gainSeg = xmpSegment(gainXmp(meta));
            long gainLength = 2 + gainSeg.length + kept(lg) + (g.length() - lg.scanStart);

            byte[] xmpSeg = xmpSegment(primaryXmp(gainLength, videoLength, stillUs));
            long before = 2; // what stands before the XMP block
            for (int i = 0; i <= l.insertAfter; i++) {
                MotionPhoto.Segment s = l.segments.get(i);
                if (!s.dropped) before += s.end - s.start;
            }
            long primaryLength = 2 + kept(l) + xmpSeg.length + MPF_SEGMENT + (f.length() - l.scanStart);
            long mpfBase = before + xmpSeg.length + MPF_BASE;
            byte[] mpfSeg = mpfSegment(primaryLength, gainLength, primaryLength - mpfBase);

            byte[] buf = new byte[1 << 16];
            long written = 0;
            out.write(0xFF);
            out.write(0xD8);
            written += 2;
            if (l.insertAfter < 0) {
                out.write(xmpSeg);
                out.write(mpfSeg);
                written += xmpSeg.length + mpfSeg.length;
            }
            for (int i = 0; i < l.segments.size(); i++) {
                MotionPhoto.Segment s = l.segments.get(i);
                if (!s.dropped) {
                    copy(f, s.start, s.end, out, buf);
                    written += s.end - s.start;
                }
                if (i == l.insertAfter) {
                    out.write(xmpSeg);
                    out.write(mpfSeg);
                    written += xmpSeg.length + mpfSeg.length;
                }
            }
            copy(f, l.scanStart, f.length(), out, buf);
            written += f.length() - l.scanStart;
            if (written != primaryLength) throw new IOException("The photo came to " + written + " bytes, not " + primaryLength);

            out.write(0xFF);
            out.write(0xD8);
            if (lg.insertAfter < 0) out.write(gainSeg);
            for (int i = 0; i < lg.segments.size(); i++) {
                MotionPhoto.Segment s = lg.segments.get(i);
                if (!s.dropped) copy(g, s.start, s.end, out, buf);
                if (i == lg.insertAfter) out.write(gainSeg);
            }
            copy(g, lg.scanStart, g.length(), out, buf);
        } finally {
            f.close();
            g.close();
        }
        if (video != null) {
            InputStream in = new FileInputStream(video);
            try {
                byte[] buf = new byte[1 << 16];
                long copied = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    copied += n;
                }
                if (copied != videoLength) throw new IOException("The video changed while being copied");
            } finally {
                in.close();
            }
        }
    }

    /** Marks an old picture index (an APP2 block starting "MPF") as dropped: this file gets its own. */
    private static void dropIndex(RandomAccessFile f, MotionPhoto.Layout l) throws IOException {
        byte[] id = new byte[4];
        for (MotionPhoto.Segment s : l.segments) {
            if (s.marker != 0xE2 || s.end - s.start < 8) continue;
            f.seek(s.start + 4);
            f.readFully(id);
            if (id[0] == 'M' && id[1] == 'P' && id[2] == 'F' && id[3] == 0) s.dropped = true;
        }
    }

    private static long kept(MotionPhoto.Layout l) {
        long n = 0;
        for (MotionPhoto.Segment s : l.segments) if (!s.dropped) n += s.end - s.start;
        return n;
    }

    private static void copy(RandomAccessFile f, long start, long end, OutputStream out, byte[] buf) throws IOException {
        f.seek(start);
        long left = end - start;
        while (left > 0) {
            int n = f.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("The file ends early");
            out.write(buf, 0, n);
            left -= n;
        }
    }

    /** What a reader finds in an Ultra HDR photo. */
    public static final class Info {
        /** Just past the photo's end marker, where the gain map's JPEG starts. */
        public long primaryEnd;
        /** Just past the gain map's end marker. */
        public long gainEnd;
        /** The gain map's length in the photo's directory (0 if the directory has none). */
        public long dirGainLength;
        /** The picture index: how many pictures, the photo's length, the gain map's start in the file and its length. */
        public int mpfImages;
        public long mpfPrimaryLength, mpfGainStart, mpfGainLength;
        /** The photo's XMP and the gain map's. */
        public String xmp, gainXmp;
        /** The gain map's numbers. */
        public Meta meta;
    }

    /**
     * Reads a file back the way an Ultra HDR reader does: the photo's XMP and picture index, the second JPEG right
     * behind the photo, and that one's XMP. Null if the file isn't an Ultra HDR photo.
     */
    public static Info read(File file) throws IOException {
        RandomAccessFile f = new RandomAccessFile(file, "r");
        try {
            long n = f.length();
            Info info = new Info();
            long[] scan = new long[1];
            String[] xmp = new String[1];
            long[] mpf = new long[1];
            if (!header(f, 0, n, scan, xmp, mpf)) return null;
            info.xmp = xmp[0];
            if (info.xmp == null || !info.xmp.contains("hdrgm:Version=\"1.0\"")) return null;
            Matcher m = Pattern.compile("Item:Semantic=\"GainMap\"\\s+Item:Length=\"(\\d+)\"").matcher(info.xmp);
            if (m.find()) info.dirGainLength = Long.parseLong(m.group(1));
            info.primaryEnd = MotionPhoto.imageEnd(f, scan[0]);
            if (mpf[0] > 0) {
                // the index: a TIFF header at mpf[0], one directory, then 16 bytes a picture
                long base = mpf[0];
                boolean big = u8(f, base) == 'M';
                long dir = base + u32(f, base + 4, big);
                int count = u16(f, dir, big);
                for (int i = 0; i < count; i++) {
                    long e = dir + 2 + 12L * i;
                    int tag = u16(f, e, big);
                    if (tag == 0xB001) info.mpfImages = (int) u32(f, e + 8, big);
                    if (tag == 0xB002) {
                        long entries = base + u32(f, e + 8, big);
                        info.mpfPrimaryLength = u32(f, entries + 4, big);
                        info.mpfGainLength = u32(f, entries + 16 + 4, big);
                        info.mpfGainStart = base + u32(f, entries + 16 + 8, big);
                    }
                }
            }
            if (info.primaryEnd + 4 > n || u8(f, info.primaryEnd) != 0xFF || u8(f, info.primaryEnd + 1) != 0xD8) return null;
            long[] scan2 = new long[1];
            String[] xmp2 = new String[1];
            if (!header(f, info.primaryEnd, n, scan2, xmp2, new long[1])) return null;
            info.gainXmp = xmp2[0];
            info.gainEnd = MotionPhoto.imageEnd(f, scan2[0]);
            if (info.gainXmp == null || !info.gainXmp.contains("hdrgm:Version=\"1.0\"")) return null;
            Meta meta = new Meta();
            meta.gainMapMin = attr(info.gainXmp, "GainMapMin", 0);
            meta.gainMapMax = attr(info.gainXmp, "GainMapMax", Double.NaN);
            meta.gamma = attr(info.gainXmp, "Gamma", 1);
            meta.offsetSdr = attr(info.gainXmp, "OffsetSDR", 1.0 / 64);
            meta.offsetHdr = attr(info.gainXmp, "OffsetHDR", 1.0 / 64);
            meta.capacityMin = attr(info.gainXmp, "HDRCapacityMin", 0);
            meta.capacityMax = attr(info.gainXmp, "HDRCapacityMax", Double.NaN);
            if (Double.isNaN(meta.gainMapMax) || Double.isNaN(meta.capacityMax)) return null;
            info.meta = meta;
            return info;
        } finally {
            f.close();
        }
    }

    /**
     * Walks the blocks of the JPEG starting at {@code start}: where its first scan begins, its XMP, and where its
     * picture index's byte-order mark is (0 if it has none). False if it isn't a JPEG.
     */
    private static boolean header(RandomAccessFile f, long start, long n, long[] scan, String[] xmp, long[] mpf) throws IOException {
        if (start + 4 > n || u8(f, start) != 0xFF || u8(f, start + 1) != 0xD8) return false;
        long p = start + 2;
        while (true) {
            if (p + 4 > n || u8(f, p) != 0xFF) return false;
            int m = u8(f, p + 1);
            if (m == 0xDA) break;
            int len = (u8(f, p + 2) << 8) | u8(f, p + 3);
            if (len < 2 || p + 2 + len > n) return false;
            if (m == 0xE1 && len > MotionPhoto.XMP_ID.length() + 3) {
                byte[] b = new byte[len - 2];
                f.seek(p + 4);
                f.readFully(b);
                String s = new String(b, Charset.forName("ISO-8859-1"));
                if (s.startsWith(MotionPhoto.XMP_ID) && xmp[0] == null) {
                    int off = MotionPhoto.XMP_ID.length() + 1;
                    xmp[0] = new String(b, off, b.length - off, UTF8);
                }
            }
            if (m == 0xE2 && len >= 6 && u8(f, p + 4) == 'M' && u8(f, p + 5) == 'P' && u8(f, p + 6) == 'F' && u8(f, p + 7) == 0) {
                mpf[0] = p + 8;
            }
            p += 2 + len;
        }
        scan[0] = p;
        return true;
    }

    private static double attr(String xmp, String name, double otherwise) {
        Matcher m = Pattern.compile("hdrgm:" + name + "=\"([^\"]*)\"").matcher(xmp);
        if (!m.find()) return otherwise;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }

    private static int u8(RandomAccessFile f, long pos) throws IOException {
        f.seek(pos);
        int b = f.read();
        if (b < 0) throw new IOException("The file ends early");
        return b;
    }

    private static int u16(RandomAccessFile f, long pos, boolean big) throws IOException {
        int a = u8(f, pos), b = u8(f, pos + 1);
        return big ? (a << 8) | b : (b << 8) | a;
    }

    private static long u32(RandomAccessFile f, long pos, boolean big) throws IOException {
        long a = u16(f, pos, big), b = u16(f, pos + 2, big);
        return big ? (a << 16) | b : (b << 16) | a;
    }
}
