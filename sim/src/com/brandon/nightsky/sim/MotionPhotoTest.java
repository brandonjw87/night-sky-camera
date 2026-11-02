package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.MotionPhoto;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * The photo's video saved inside the photo (1.8): Google's Motion Photo layout, read back three ways: by the app's
 * own reader, by a copy of the logic Media3 (the Android media library galleries use) reads Motion Photos with, and by
 * plain JPEG decoding (the photo must look exactly as before, video or not).
 * <p>
 * Usage: MotionPhotoTest outDir [video.mp4]. Without a video one is made with ffmpeg (or, without ffmpeg, a stand-in
 * of the right shape).
 */
public final class MotionPhotoTest {
    static int failed;

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "motionphoto-out");
        out.mkdirs();
        File video = args.length > 1 ? new File(args[1]) : makeVideo(out);
        System.out.println("== video: " + video.getName() + ", " + video.length() + " bytes");
        BufferedImage img = picture(640, 360);
        long stillUs = 4_766_667;

        // as the app writes it: Android puts the Exif block before the JFIF one; an old XMP block must go
        byte[] plain = jpeg(img, false);
        byte[] android = insertAfterSoi(plain, segment(0xE1, exifPayload()));
        android = insertAfterFirstApp0(android, concat(segment(0xE1, xmpPayload("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>")),
                segment(0xE1, extXmpPayload())));
        variant(out, "android", android, video, stillUs, true);
        variant(out, "jfif-only", plain, video, stillUs, false);
        variant(out, "progressive", jpeg(img, true), video, 0, false);

        refusals(out, plain, video);
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    static void variant(File dir, String name, byte[] jpeg, File video, long stillUs, boolean hadExif) throws Exception {
        System.out.println("== " + name);
        File in = new File(dir, name + ".jpg");
        Files.write(in.toPath(), jpeg);
        File mp = new File(dir, "NightSky_" + name + ".MP.jpg");
        OutputStream os = new FileOutputStream(mp);
        try {
            MotionPhoto.write(in, video, stillUs, os);
        } finally {
            os.close();
        }
        byte[] file = Files.readAllBytes(mp.toPath());
        byte[] mp4 = Files.readAllBytes(video.toPath());

        MotionPhoto.Info info = MotionPhoto.read(mp);
        check("read back as a Motion Photo", info != null, "");
        if (info == null) return;
        check("one XMP block (the old one replaced)", info.xmpBlocks == 1, info.xmpBlocks + " blocks");
        check("the video starts right after the photo's end marker", info.imageEnd == info.videoStart,
                "photo ends at " + info.imageEnd + ", video starts at " + info.videoStart);
        check("the video's length is the MP4's", info.videoLength == mp4.length, info.videoLength + " vs " + mp4.length);
        check("the photo's time in the video", info.stillUs == stillUs, info.stillUs + " us");
        check("the video comes out byte for byte",
                Arrays.equals(Arrays.copyOfRange(file, (int) info.videoStart, file.length), mp4), "");

        long[] media3 = media3(info.xmp, file.length);
        check("Media3's reading finds the same video", media3 != null && media3[2] == info.videoStart && media3[3] == mp4.length,
                media3 == null ? "not a motion photo to it" : "photo 0.." + media3[1] + ", video at " + media3[2] + ", "
                        + media3[3] + " bytes, still at " + media3[4] + " us");
        check("Media3's reading puts the photo before the video", media3 != null && media3[0] == 0 && media3[1] == info.videoStart, "");

        // the photo itself: unchanged pixels, from the photo part and from the whole file
        BufferedImage before = ImageIO.read(in);
        BufferedImage part = ImageIO.read(new java.io.ByteArrayInputStream(Arrays.copyOf(file, (int) info.imageEnd)));
        BufferedImage whole = ImageIO.read(mp);
        check("the photo decodes the same as before", samePixels(before, part), "");
        check("the whole file decodes as the photo", samePixels(before, whole), "");

        // every block but XMP kept as it was, in order, with the XMP after the leading JFIF/Exif blocks
        List<byte[]> a = segments(jpeg), b = segments(file);
        List<byte[]> aKept = new ArrayList<byte[]>(), bKept = new ArrayList<byte[]>();
        for (byte[] s : a) if (!isXmp(s)) aKept.add(s);
        int xmpAt = -1;
        for (int i = 0; i < b.size(); i++) {
            if (isXmp(b.get(i))) xmpAt = i;
            else bKept.add(b.get(i));
        }
        boolean same = aKept.size() == bKept.size();
        for (int i = 0; same && i < aKept.size(); i++) same = Arrays.equals(aKept.get(i), bKept.get(i));
        check("every other block kept as it was", same, aKept.size() + " vs " + bKept.size() + " blocks");
        int leading = 0;
        while (leading < bKept.size() && (marker(bKept.get(leading)) == 0xE0 || isExif(bKept.get(leading)))) leading++;
        check("the XMP block follows the JFIF and Exif blocks", xmpAt == leading && leading == (hadExif ? 2 : 1),
                "at " + xmpAt + ", after " + leading);
        check("the image data copied as it was", Arrays.equals(tail(jpeg), Arrays.copyOfRange(file, (int) (info.imageEnd
                - tail(jpeg).length), (int) info.imageEnd)), "");

        String probe = ffprobe(Arrays.copyOfRange(file, (int) info.videoStart, file.length), dir);
        if (probe != null) check("ffprobe reads the extracted video", probe.contains("codec_name=h264"), probe.replace('\n', ' ').trim());
    }

    static void refusals(File dir, byte[] jpeg, File video) throws Exception {
        System.out.println("== refusals (the app then saves the plain photo)");
        File notJpeg = new File(dir, "not-a-jpeg.jpg");
        Files.write(notJpeg.toPath(), "hello".getBytes("UTF-8"));
        check("not a JPEG: refused, nothing written", refused(notJpeg, video), "");
        File trailing = new File(dir, "trailing.jpg");
        Files.write(trailing.toPath(), concat(jpeg, new byte[]{1, 2, 3}));
        check("data after the photo's end: refused, nothing written", refused(trailing, video), "");
        File cut = new File(dir, "cut.jpg");
        Files.write(cut.toPath(), concat(Arrays.copyOf(jpeg, 200), new byte[]{(byte) 0xFF, (byte) 0xD9}));
        check("a broken header: refused, nothing written", refused(cut, video), "");
        File good = new File(dir, "good.jpg");
        Files.write(good.toPath(), jpeg);
        File empty = new File(dir, "empty.mp4");
        Files.write(empty.toPath(), new byte[0]);
        check("an empty video: refused, nothing written", refused(good, empty), "");
        File plain = new File(dir, "plain.jpg");
        Files.write(plain.toPath(), jpeg);
        check("a plain JPEG isn't taken for a Motion Photo", MotionPhoto.read(plain) == null, "");
    }

    static boolean refused(File jpeg, File video) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            MotionPhoto.write(jpeg, video, 0, bos);
            return false;
        } catch (IOException e) {
            return bos.size() == 0;
        }
    }

    // ---- Media3's logic (androidx.media3.extractor.jpeg XmpMotionPhotoDescriptionParser + MotionPhotoDescription) --

    /**
     * {photoStart, photoLength, videoStart, videoLength, presentationUs} as Media3 works them out, or null where it
     * would not take the file for a Motion Photo. Its XML parser isn't namespace aware: it matches attribute and tag
     * names with their prefixes ("GCamera:MotionPhoto"), so the prefixes must be exactly these.
     */
    static long[] media3(String xmp, long fileLength) throws Exception {
        boolean flagged = false;
        for (String n : new String[]{"Camera:MotionPhoto", "GCamera:MotionPhoto", "Camera:MicroVideo", "GCamera:MicroVideo"}) {
            if (xmp.contains(n + "=\"1\"")) flagged = true;
        }
        if (!flagged) return null;
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader x = f.createXMLStreamReader(new StringReader(xmp));
        int ev = x.next();
        while (ev == XMLStreamConstants.PROCESSING_INSTRUCTION || ev == XMLStreamConstants.COMMENT) ev = x.next();
        if (ev != XMLStreamConstants.START_ELEMENT || !qname(x).equals("x:xmpmeta")) return null;
        long ts = Long.MIN_VALUE;
        List<String[]> items = new ArrayList<String[]>();
        while (x.hasNext()) {
            ev = x.next();
            if (ev == XMLStreamConstants.END_ELEMENT && qname(x).equals("x:xmpmeta")) break;
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            if (qname(x).equals("rdf:Description")) {
                String flag = first(x, "Camera:MotionPhoto", "GCamera:MotionPhoto", "Camera:MicroVideo", "GCamera:MicroVideo");
                if (flag == null || Integer.parseInt(flag) != 1) return null;
                String t = first(x, "Camera:MotionPhotoPresentationTimestampUs", "GCamera:MotionPhotoPresentationTimestampUs",
                        "Camera:MicroVideoPresentationTimestampUs", "GCamera:MicroVideoPresentationTimestampUs");
                if (t != null) ts = Long.parseLong(t) == -1 ? Long.MIN_VALUE : Long.parseLong(t);
                String off = first(x, "Camera:MicroVideoOffset", "GCamera:MicroVideoOffset");
                items = new ArrayList<String[]>();
                if (off != null) {
                    items.add(new String[]{"image/jpeg", "Primary", "0", "0"});
                    items.add(new String[]{"video/mp4", "MotionPhoto", off, "0"});
                }
            } else if (qname(x).equals("Container:Directory")) {
                items = new ArrayList<String[]>();
                while (x.hasNext()) {
                    ev = x.next();
                    if (ev == XMLStreamConstants.END_ELEMENT && qname(x).equals("Container:Directory")) break;
                    if (ev == XMLStreamConstants.START_ELEMENT && qname(x).equals("Container:Item")) {
                        String mime = first(x, "Item:Mime"), sem = first(x, "Item:Semantic");
                        if (mime == null || sem == null) return null;
                        String len = first(x, "Item:Length"), pad = first(x, "Item:Padding");
                        items.add(new String[]{mime, sem, len != null ? len : "0", pad != null ? pad : "0"});
                    }
                }
            }
        }
        if (items.size() < 2) return null;
        long photoStart = -1, photoLength = -1, videoStart = -1, videoLength = -1;
        long itemStart = fileLength, itemEnd;
        for (int i = items.size() - 1; i >= 0; i--) {
            String[] it = items.get(i);
            boolean video = it[0].equals("video/mp4") || it[0].equals("video/quicktime");
            itemEnd = itemStart;
            if (i == 0) {
                itemStart = 0;
                itemEnd -= Long.parseLong(it[3]);
            } else {
                itemStart -= Long.parseLong(it[2]);
            }
            if (video && itemStart != itemEnd) {
                videoStart = itemStart;
                videoLength = itemEnd - itemStart;
            }
            if (i == 0) {
                photoStart = itemStart;
                photoLength = itemEnd;
            }
        }
        if (videoStart < 0 || videoLength < 0 || photoStart < 0 || photoLength < 0) return null;
        return new long[]{photoStart, photoLength, videoStart, videoLength, ts};
    }

    static String qname(XMLStreamReader x) {
        String p = x.getPrefix();
        return (p == null || p.isEmpty() ? "" : p + ":") + x.getLocalName();
    }

    static String first(XMLStreamReader x, String... names) {
        for (String n : names) {
            for (int i = 0; i < x.getAttributeCount(); i++) {
                String p = x.getAttributePrefix(i);
                String q = (p == null || p.isEmpty() ? "" : p + ":") + x.getAttributeLocalName(i);
                if (q.equals(n)) return x.getAttributeValue(i);
            }
        }
        return null;
    }

    // ---- JPEG building and taking apart ---------------------------------------------------------------------------

    static BufferedImage picture(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(7);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int base = 10 + y * 30 / h;
                int n = r.nextInt(6);
                img.setRGB(x, y, ((base + n) << 16) | ((base + 4 + n) << 8) | (base + 14 + n));
            }
        }
        for (int s = 0; s < 300; s++) {
            int x = r.nextInt(w), y = r.nextInt(h), v = 120 + r.nextInt(135);
            img.setRGB(x, y, (v << 16) | (v << 8) | v);
        }
        return img;
    }

    static byte[] jpeg(BufferedImage img, boolean progressive) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageOutputStream ios = ImageIO.createImageOutputStream(bos);
        w.setOutput(ios);
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.95f);
        if (progressive) p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        w.write(null, new IIOImage(img, null, null), p);
        ios.close();
        w.dispose();
        return bos.toByteArray();
    }

    static byte[] segment(int marker, byte[] payload) {
        int len = payload.length + 2;
        byte[] s = new byte[len + 2];
        s[0] = (byte) 0xFF;
        s[1] = (byte) marker;
        s[2] = (byte) (len >> 8);
        s[3] = (byte) len;
        System.arraycopy(payload, 0, s, 4, payload.length);
        return s;
    }

    /** "Exif\0\0" and a big-endian TIFF header with one IFD holding the orientation. */
    static byte[] exifPayload() {
        return new byte[]{'E', 'x', 'i', 'f', 0, 0, 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, 1, 0, 0,
                0, 0, 0, 0};
    }

    static byte[] xmpPayload(String xmp) throws IOException {
        return concat("http://ns.adobe.com/xap/1.0/\0".getBytes("ISO-8859-1"), xmp.getBytes("UTF-8"));
    }

    static byte[] extXmpPayload() throws IOException {
        return concat("http://ns.adobe.com/xmp/extension/\0".getBytes("ISO-8859-1"), new byte[40]);
    }

    static byte[] insertAfterSoi(byte[] jpeg, byte[] seg) {
        return concat(Arrays.copyOf(jpeg, 2), seg, Arrays.copyOfRange(jpeg, 2, jpeg.length));
    }

    static byte[] insertAfterFirstApp0(byte[] jpeg, byte[] seg) {
        int p = 2;
        while (true) {
            int m = jpeg[p + 1] & 255;
            int len = ((jpeg[p + 2] & 255) << 8) | (jpeg[p + 3] & 255);
            p += 2 + len;
            if (m == 0xE0) break;
        }
        return concat(Arrays.copyOf(jpeg, p), seg, Arrays.copyOfRange(jpeg, p, jpeg.length));
    }

    /** The marker segments before the first scan, each with its marker. */
    static List<byte[]> segments(byte[] jpeg) {
        List<byte[]> out = new ArrayList<byte[]>();
        int p = 2;
        while ((jpeg[p + 1] & 255) != 0xDA) {
            int len = ((jpeg[p + 2] & 255) << 8) | (jpeg[p + 3] & 255);
            out.add(Arrays.copyOfRange(jpeg, p, p + 2 + len));
            p += 2 + len;
        }
        return out;
    }

    /** From the first scan to the end marker. */
    static byte[] tail(byte[] jpeg) {
        int p = 2;
        while ((jpeg[p + 1] & 255) != 0xDA) p += 2 + (((jpeg[p + 2] & 255) << 8) | (jpeg[p + 3] & 255));
        return Arrays.copyOfRange(jpeg, p, jpeg.length);
    }

    static int marker(byte[] seg) {
        return seg[1] & 255;
    }

    static boolean isXmp(byte[] seg) {
        String id = new String(seg, 4, Math.min(40, seg.length - 4), java.nio.charset.StandardCharsets.ISO_8859_1);
        return marker(seg) == 0xE1 && (id.startsWith("http://ns.adobe.com/xap/1.0/\0") || id.startsWith("http://ns.adobe.com/xmp/extension/\0"));
    }

    static boolean isExif(byte[] seg) {
        return marker(seg) == 0xE1 && seg.length > 10 && seg[4] == 'E' && seg[5] == 'x' && seg[6] == 'i' && seg[7] == 'f';
    }

    static boolean samePixels(BufferedImage a, BufferedImage b) {
        if (a == null || b == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
            }
        }
        return true;
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) n += p.length;
        byte[] out = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, o, p.length);
            o += p.length;
        }
        return out;
    }

    // ---- video ------------------------------------------------------------------------------------------------------

    static File makeVideo(File dir) throws Exception {
        File mp4 = new File(dir, "test.mp4");
        if (run(dir, "ffmpeg", "-y", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=30", "-t", "2",
                "-pix_fmt", "yuv420p", "-c:v", "libx264", mp4.getAbsolutePath()) != null && mp4.length() > 0) {
            return mp4;
        }
        // a stand-in: an ftyp box and random bytes
        byte[] b = new byte[50_000];
        new Random(3).nextBytes(b);
        byte[] ftyp = {0, 0, 0, 24, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm', 0, 0, 2, 0, 'i', 's', 'o', 'm', 'a', 'v', 'c', '1'};
        System.arraycopy(ftyp, 0, b, 0, ftyp.length);
        Files.write(mp4.toPath(), b);
        return mp4;
    }

    static String ffprobe(byte[] mp4, File dir) throws Exception {
        File f = new File(dir, "extracted.mp4");
        Files.write(f.toPath(), mp4);
        return run(dir, "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=codec_name,width,height,nb_frames:format=duration", "-of", "default=noprint_wrappers=1", f.getAbsolutePath());
    }

    /** The command's output, or null if it can't run or fails. */
    static String run(File dir, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true).start();
            InputStream in = p.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) bos.write(b, 0, n);
            return p.waitFor() == 0 ? bos.toString("UTF-8") : null;
        } catch (Exception e) {
            return null;
        }
    }
}
