package com.brandon.nightsky.processing;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Google's Motion Photo format (https://developer.android.com/media/platform/motion-photo-format): a JPEG whose XMP
 * block says a video follows it, with the MP4 appended right after the JPEG's end. Google Photos shows the photo and
 * plays the video; galleries built on Media3 do the same, and anything else just shows the photo, since a JPEG
 * decoder stops at the image's end marker.
 *
 * <p>The XMP is written the way Media3's parser reads it (and the way the Pixel's own camera writes it): no xpacket
 * wrapper, one {@code rdf:Description} carrying the {@code GCamera:} flags as attributes, then the container
 * directory (the photo first, with length 0 and no padding, then the video and its length). The older "micro video"
 * fields are written too, for readers that only know those; they describe the same layout. The file's name should
 * end in ".MP.jpg": the format asks for it, and some readers only look for the video in files named so.
 */
public final class MotionPhoto {
    private MotionPhoto() {}

    static final String XMP_ID = "http://ns.adobe.com/xap/1.0/";
    static final String XMP_EXTENSION_ID = "http://ns.adobe.com/xmp/extension/";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final int MAX_SEGMENT = 65535;

    /** The XMP packet for a video of {@code videoLength} bytes whose frame at {@code stillUs} is the photo. */
    public static String xmp(long videoLength, long stillUs) {
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Night Sky Camera\">\n"
                + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
                + "  <rdf:Description rdf:about=\"\"\n"
                + "    xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"\n"
                + "    xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"\n"
                + "    xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"\n"
                + "   GCamera:MotionPhoto=\"1\"\n"
                + "   GCamera:MotionPhotoVersion=\"1\"\n"
                + "   GCamera:MotionPhotoPresentationTimestampUs=\"" + stillUs + "\"\n"
                + "   GCamera:MicroVideo=\"1\"\n"
                + "   GCamera:MicroVideoVersion=\"1\"\n"
                + "   GCamera:MicroVideoOffset=\"" + videoLength + "\"\n"
                + "   GCamera:MicroVideoPresentationTimestampUs=\"" + stillUs + "\">\n"
                + "   <Container:Directory>\n"
                + "    <rdf:Seq>\n"
                + "     <rdf:li rdf:parseType=\"Resource\">\n"
                + "      <Container:Item\n"
                + "        Item:Mime=\"image/jpeg\"\n"
                + "        Item:Semantic=\"Primary\"\n"
                + "        Item:Length=\"0\"\n"
                + "        Item:Padding=\"0\"/>\n"
                + "     </rdf:li>\n"
                + "     <rdf:li rdf:parseType=\"Resource\">\n"
                + "      <Container:Item\n"
                + "        Item:Mime=\"video/mp4\"\n"
                + "        Item:Semantic=\"MotionPhoto\"\n"
                + "        Item:Length=\"" + videoLength + "\"/>\n"
                + "     </rdf:li>\n"
                + "    </rdf:Seq>\n"
                + "   </Container:Directory>\n"
                + "  </rdf:Description>\n"
                + " </rdf:RDF>\n"
                + "</x:xmpmeta>";
    }

    /**
     * Writes {@code jpeg} to {@code out} as a Motion Photo: the same JPEG with this format's XMP block in place of any
     * XMP it had (placed after its JFIF and Exif blocks), then the video. Nothing else in the JPEG changes. Throws if
     * the JPEG can't be read as one or doesn't end at its end marker, before anything is written, so the caller can
     * keep the plain photo instead.
     */
    public static void write(File jpeg, File video, long stillUs, OutputStream out) throws IOException {
        long videoLength = video.length();
        if (videoLength < 8) throw new IOException("The video is empty");
        RandomAccessFile f = new RandomAccessFile(jpeg, "r");
        try {
            Layout l = layout(f);
            byte[] xmp = xmp(videoLength, stillUs).getBytes(UTF8);
            int segLength = 2 + XMP_ID.length() + 1 + xmp.length;
            if (segLength > MAX_SEGMENT) throw new IOException("XMP block too long");
            ByteArrayOutputStream seg = new ByteArrayOutputStream(segLength + 2);
            seg.write(0xFF);
            seg.write(0xE1);
            seg.write(segLength >> 8);
            seg.write(segLength & 255);
            seg.write(XMP_ID.getBytes(UTF8));
            seg.write(0);
            seg.write(xmp);

            byte[] buf = new byte[1 << 16];
            out.write(0xFF);
            out.write(0xD8);
            if (l.insertAfter < 0) seg.writeTo(out);
            for (int i = 0; i < l.segments.size(); i++) {
                Segment s = l.segments.get(i);
                if (!s.dropped) copy(f, s.start, s.end, out, buf);
                if (i == l.insertAfter) seg.writeTo(out);
            }
            copy(f, l.scanStart, f.length(), out, buf);
        } finally {
            f.close();
        }
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

    /** A marker segment before the image data: where it is in the file and what it is. */
    static final class Segment {
        long start, end;
        int marker;
        boolean exif, xmp, dropped;
    }

    /** The marker segments up to the first scan, and where the XMP block goes. */
    static final class Layout {
        final List<Segment> segments = new ArrayList<>();
        /** Index of the segment the XMP block follows; -1 for right after the start marker. */
        int insertAfter = -1;
        /** Where the first scan's marker starts: everything from here on is copied as it is. */
        long scanStart;
    }

    static Layout layout(RandomAccessFile f) throws IOException {
        long n = f.length();
        if (n < 4 || u8(f, 0) != 0xFF || u8(f, 1) != 0xD8) throw new IOException("Not a JPEG");
        if (u8(f, n - 2) != 0xFF || u8(f, n - 1) != 0xD9) throw new IOException("The JPEG doesn't end at its end marker");
        Layout l = new Layout();
        long p = 2;
        while (true) {
            if (p + 2 > n || u8(f, p) != 0xFF) throw new IOException("Broken JPEG header at byte " + p);
            long q = p + 1;
            while (q < n && u8(f, q) == 0xFF) q++; // fill bytes
            if (q >= n) throw new IOException("Broken JPEG header at byte " + p);
            int m = u8(f, q);
            q++;
            if (m == 0xDA) {
                l.scanStart = p;
                break;
            }
            if (m == 0xD9 || m == 0xD8) throw new IOException("The JPEG has no image data");
            Segment s = new Segment();
            s.start = p;
            s.marker = m;
            if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                s.end = q; // a marker without a length
            } else {
                if (q + 2 > n) throw new IOException("Broken JPEG header at byte " + p);
                int len = (u8(f, q) << 8) | u8(f, q + 1);
                if (len < 2 || q + len > n) throw new IOException("Broken JPEG segment at byte " + p);
                s.end = q + len;
                if (m == 0xE1) {
                    String id = id(f, q + 2, Math.min(len - 2, 40));
                    s.exif = id.startsWith("Exif");
                    s.xmp = id.equals(XMP_ID) || id.equals(XMP_EXTENSION_ID);
                }
            }
            s.dropped = s.xmp;
            l.segments.add(s);
            p = s.end;
        }
        // after the leading JFIF and Exif blocks, as the JFIF and Exif rules want those first
        for (int i = 0; i < l.segments.size(); i++) {
            Segment s = l.segments.get(i);
            if (s.dropped) continue;
            if (s.marker == 0xE0 || s.exif) l.insertAfter = i;
            else break;
        }
        return l;
    }

    /** What a reader finds in a Motion Photo. */
    public static final class Info {
        /** Just past the photo's end marker. */
        public long imageEnd;
        /** Where the video starts (from the end of the file and the directory's lengths, as readers find it). */
        public long videoStart, videoLength;
        /** The video's time of the photo, in microseconds; -1 if not given. */
        public long stillUs = -1;
        /** The whole XMP packet. */
        public String xmp;
        /** XMP blocks found (a Motion Photo has exactly one). */
        public int xmpBlocks;
    }

    /**
     * Reads a file back the way a Motion Photo reader does: the XMP from the JPEG's APP1 block, the video's place from
     * the end of the file and the directory's lengths. Null if the file isn't a Motion Photo.
     */
    public static Info read(File file) throws IOException {
        RandomAccessFile f = new RandomAccessFile(file, "r");
        try {
            long n = f.length();
            if (n < 4 || u8(f, 0) != 0xFF || u8(f, 1) != 0xD8) return null;
            Info info = new Info();
            long p = 2;
            while (true) {
                if (p + 4 > n || u8(f, p) != 0xFF) return null;
                int m = u8(f, p + 1);
                if (m == 0xDA) break;
                int len = (u8(f, p + 2) << 8) | u8(f, p + 3);
                if (len < 2 || p + 2 + len > n) return null;
                if (m == 0xE1 && id(f, p + 4, Math.min(len - 2, 40)).equals(XMP_ID)) {
                    info.xmpBlocks++;
                    if (info.xmp == null) {
                        int off = XMP_ID.length() + 1;
                        byte[] b = new byte[len - 2 - off];
                        f.seek(p + 4 + off);
                        f.readFully(b);
                        int end = 0;
                        while (end < b.length && b[end] != 0) end++;
                        info.xmp = new String(b, 0, end, UTF8);
                    }
                }
                p += 2 + len;
            }
            info.imageEnd = imageEnd(f, p);
            if (info.xmp == null || !info.xmp.contains("GCamera:MotionPhoto=\"1\"")) return null;
            Matcher ts = Pattern.compile("GCamera:MotionPhotoPresentationTimestampUs=\"(-?\\d+)\"").matcher(info.xmp);
            if (ts.find()) info.stillUs = Long.parseLong(ts.group(1));
            // the items in order; the last one is at the very end of the file
            Matcher item = Pattern.compile("<Container:Item\\s([^>]*)/?>").matcher(info.xmp);
            List<String> mimes = new ArrayList<>();
            List<Long> lengths = new ArrayList<>(), paddings = new ArrayList<>();
            while (item.find()) {
                String a = item.group(1);
                mimes.add(attr(a, "Item:Mime"));
                String len = attr(a, "Item:Length"), pad = attr(a, "Item:Padding");
                lengths.add(len != null ? Long.parseLong(len) : 0L);
                paddings.add(pad != null ? Long.parseLong(pad) : 0L);
            }
            if (mimes.size() < 2) return null;
            long itemStart = n;
            for (int i = mimes.size() - 1; i >= 1; i--) {
                itemStart -= lengths.get(i);
                if ("video/mp4".equals(mimes.get(i)) && lengths.get(i) > 0) {
                    info.videoStart = itemStart;
                    info.videoLength = lengths.get(i);
                }
            }
            return info.videoLength > 0 ? info : null;
        } finally {
            f.close();
        }
    }

    /** Just past the end marker of a JPEG whose first scan's marker starts at {@code p}. */
    static long imageEnd(RandomAccessFile f, long p) throws IOException {
        long n = f.length();
        // the first scan's header
        if (p + 4 > n) throw new IOException("The JPEG ends early");
        p += 2 + ((u8(f, p + 2) << 8) | u8(f, p + 3));
        byte[] buf = new byte[1 << 16];
        long bufStart = -1;
        int bufLen = 0;
        while (p + 1 < n) {
            if (bufStart < 0 || p < bufStart || p + 1 >= bufStart + bufLen) {
                f.seek(p);
                bufStart = p;
                bufLen = (int) Math.min(buf.length, n - p);
                f.readFully(buf, 0, bufLen);
            }
            int b = buf[(int) (p - bufStart)] & 255;
            if (b != 0xFF) {
                p++;
                continue;
            }
            int m = buf[(int) (p + 1 - bufStart)] & 255;
            if (m == 0x00 || (m >= 0xD0 && m <= 0xD7)) {
                p += 2; // stuffed byte or restart marker: still image data
            } else if (m == 0xFF) {
                p++; // fill byte before a marker
            } else if (m == 0xD9) {
                return p + 2;
            } else {
                // another segment between scans (a progressive JPEG's tables and scans)
                if (p + 4 > n) break;
                p += 2 + ((u8(f, p + 2) << 8) | u8(f, p + 3));
                bufStart = -1;
            }
        }
        throw new IOException("The JPEG has no end marker");
    }

    private static String attr(String attrs, String name) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(attrs);
        return m.find() ? m.group(1) : null;
    }

    private static int u8(RandomAccessFile f, long pos) throws IOException {
        f.seek(pos);
        int b = f.read();
        if (b < 0) throw new IOException("The file ends early");
        return b;
    }

    /** The zero-terminated text at {@code pos} (at most {@code max} bytes). */
    private static String id(RandomAccessFile f, long pos, int max) throws IOException {
        if (max <= 0) return "";
        byte[] b = new byte[max];
        f.seek(pos);
        f.readFully(b);
        int end = 0;
        while (end < max && b[end] != 0) end++;
        return new String(b, 0, end, Charset.forName("ISO-8859-1"));
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
}
