package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.HdrGain;
import com.brandon.nightsky.processing.MotionPhoto;
import com.brandon.nightsky.processing.UltraHdr;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * HDR photos (2.0): the stars' brightness map ({@link HdrGain}) and the file it's saved in ({@link UltraHdr}, Android's
 * Ultra HDR layout), read back four ways: by the app's own reader, by a copy of the logic Android's picture decoder
 * (Skia) finds a gain map with, both through the picture index and through the XMP directory, by a copy of Media3's
 * Motion Photo logic (the photo's video must still play), and by plain JPEG decoding (the photo must look exactly as
 * before on anything that doesn't know the format).
 * <p>
 * Usage: UltraHdrTest outDir [video.mp4]
 */
public final class UltraHdrTest {
    static final String HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/";
    static final String CONTAINER = "http://ns.google.com/photos/1.0/container/";
    static final String ITEM = "http://ns.google.com/photos/1.0/container/item/";

    public static void main(String[] args) throws Exception {
        File out = new File(args.length > 0 ? args[0] : "ultrahdr-out");
        out.mkdirs();
        File video = args.length > 1 ? new File(args[1]) : MotionPhotoTest.makeVideo(out);

        curve();
        BufferedImage img = nightPicture(640, 360);
        byte[] map = mapOf(img);
        mapChecks(img, map);
        screen(img, map);

        byte[] gain = grayJpeg(map, img.getWidth(), img.getHeight(), false);
        byte[] gainRgb = rgbJpeg(map, img.getWidth(), img.getHeight());
        System.out.println("== the map as a JPEG: " + gain.length + " bytes gray, " + gainRgb.length + " bytes as Android writes it "
                + "(three channels), for a photo of " + MotionPhotoTest.jpeg(img, false).length);

        // as the app writes it: Android puts the Exif block before the JFIF one; an old XMP block must go
        byte[] plain = MotionPhotoTest.jpeg(img, false);
        byte[] android = MotionPhotoTest.insertAfterSoi(plain, MotionPhotoTest.segment(0xE1, MotionPhotoTest.exifPayload()));
        android = MotionPhotoTest.insertAfterFirstApp0(android, MotionPhotoTest.concat(
                MotionPhotoTest.segment(0xE1, MotionPhotoTest.xmpPayload("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"/>")),
                MotionPhotoTest.segment(0xE1, MotionPhotoTest.extXmpPayload())));
        long stillUs = 4_766_667;
        variant(out, "android", android, gainRgb, video, stillUs, true);
        variant(out, "android-still", android, gainRgb, null, 0, true);
        variant(out, "jfif-only", plain, gain, video, stillUs, false);
        variant(out, "progressive", MotionPhotoTest.jpeg(img, true), grayJpeg(map, img.getWidth(), img.getHeight(), true), null, 0, false);
        // a photo that had a picture index of its own (an edited copy of an HDR photo): replaced, not doubled
        byte[] indexed = MotionPhotoTest.insertAfterFirstApp0(plain, MotionPhotoTest.segment(0xE2,
                MotionPhotoTest.concat(new byte[]{'M', 'P', 'F', 0}, new byte[60])));
        variant(out, "old-index", indexed, gain, null, 0, false);

        refusals(out, plain, gain, video);
        System.out.println(MotionPhotoTest.failed == 0 ? "ALL PASS" : MotionPhotoTest.failed + " failed");
        if (MotionPhotoTest.failed > 0) System.exit(1);
    }

    static void check(String what, boolean ok, String detail) {
        MotionPhotoTest.check(what, ok, detail);
    }

    // ---- the brightness map -----------------------------------------------------------------------------------------

    static void curve() {
        System.out.println("== the lift");
        boolean rising = true;
        float last = 0;
        for (int v = 0; v <= 255; v++) {
            float l = HdrGain.lift(v / 255f);
            if (l < last) rising = false;
            last = l;
        }
        check("nothing below the start, everything at white, rising in between",
                HdrGain.lift(HdrGain.START) == 0f && HdrGain.lift(0.2f) == 0f && HdrGain.lift(1f) == 1f && rising, "");
        check("white is shown four times brighter", Math.abs(HdrGain.times(255) - 4f) < 1e-4, HdrGain.times(255) + "x");
        check("the middle of the range is lifted gently", HdrGain.lift(0.6f) < 0.2f && HdrGain.lift(0.8f) > 0.5f,
                String.format("%.2f stops at 60%%, %.2f at 80%%", 2 * HdrGain.lift(0.6f), 2 * HdrGain.lift(0.8f)));
        check("bright surroundings hold it back to a quarter", HdrGain.held(0.1f) == 1f && Math.abs(HdrGain.held(0.9f) - 0.25f) < 1e-6
                && HdrGain.held(0.45f) == 1f && HdrGain.held(0.7f) < 1f && HdrGain.held(0.7f) > 0.25f,
                String.format("all of it up to 50%% (the Milky Way's brightest), %.2f at 70%%", HdrGain.held(0.7f)));
    }

    /** A night sky: a dim gradient with grain, stars of all sizes, a hazy band, and a floodlit wall in a corner. */
    static BufferedImage nightPicture(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(11);
        float[] v = new float[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float band = (float) Math.exp(-Math.pow((y - 0.45 * h - 0.2 * x) / (0.09 * h), 2));
                v[y * w + x] = 0.07f + 0.05f * y / h + 0.30f * band + 0.012f * (float) r.nextGaussian();
            }
        }
        for (int s = 0; s < 260; s++) {
            double cx = r.nextDouble() * w, cy = r.nextDouble() * h * 0.8;
            double amp = 0.15 * Math.pow(10, 1.6 * r.nextDouble()), sig = 0.7 + 0.5 * r.nextDouble();
            int rad = 7;
            for (int y = (int) cy - rad; y <= (int) cy + rad; y++) {
                for (int x = (int) cx - rad; x <= (int) cx + rad; x++) {
                    if (x < 0 || y < 0 || x >= w || y >= h) continue;
                    double d2 = (x + 0.5 - cx) * (x + 0.5 - cx) + (y + 0.5 - cy) * (y + 0.5 - cy);
                    v[y * w + x] += (float) (amp * Math.exp(-d2 / (2 * sig * sig)));
                }
            }
        }
        // the wall: wide and near white
        for (int y = h - 70; y < h; y++) for (int x = w - 150; x < w; x++) v[y * w + x] = 0.92f + 0.01f * (float) r.nextGaussian();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float l = Math.min(1f, Math.max(0f, v[y * w + x]));
                int g = Math.round(255 * l);
                // a little colour, so the brightest channel isn't always the same one
                int rr = Math.min(255, Math.round(g * (0.96f + 0.08f * ((x * 31 + y * 17) % 7) / 6f)));
                int bb = Math.min(255, Math.round(g * (0.94f + 0.10f * ((x * 13 + y * 29) % 5) / 4f)));
                img.setRGB(x, y, (rr << 16) | (g << 8) | bb);
            }
        }
        return img;
    }

    static int level(int rgb) {
        return Math.max((rgb >> 16) & 255, Math.max((rgb >> 8) & 255, rgb & 255));
    }

    static byte[] mapOf(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        HdrGain g = new HdrGain(w, h);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            g.measure(y, row, 0);
        }
        byte[] map = new byte[w * h];
        for (int y = 0; y < h; y++) {
            img.getRGB(0, y, w, 1, row, 0, w);
            g.row(y, row, 0, map, y * w);
        }
        lifted = g.lifted();
        most = g.most();
        note = g.note();
        return map;
    }

    static long lifted;
    static int most;
    static String note;

    static void mapChecks(BufferedImage img, byte[] map) {
        System.out.println("== the map of a night picture: " + note);
        int w = img.getWidth(), h = img.getHeight();
        int darkLifted = 0, dark = 0, starFull = 0, stars = 0, wallMax = 0, wallMin = 255, wall = 0, edgeMax = 0;
        boolean ordered = true;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int lv = level(img.getRGB(x, y)), m = map[y * w + x] & 255;
                boolean inWall = y >= h - 70 && x >= w - 150;
                // a cell and a half inside its edge, where the surroundings are all wall
                boolean deepInWall = y >= h - 70 + 48 && x >= w - 150 + 48;
                if (lv < 255 * HdrGain.START) {
                    dark++;
                    if (m != 0) darkLifted++;
                }
                if (deepInWall) {
                    wall++;
                    wallMax = Math.max(wallMax, m);
                    wallMin = Math.min(wallMin, m);
                } else if (inWall) {
                    edgeMax = Math.max(edgeMax, m);
                } else if (lv == 255 && y < h - 120) {
                    stars++;
                    if (m >= 250) starFull++;
                }
                if (m > Math.round(255 * HdrGain.lift(lv / 255f))) ordered = false;
            }
        }
        check("the sky and everything dim stay exactly as they are", darkLifted == 0 && dark > w * h / 2, dark + " dim pixels");
        check("star cores get the whole lift, in the hazy band too", stars > 50 && starFull == stars, starFull + " of " + stars);
        check("the floodlit wall is held back", wall > 1000 && HdrGain.stops(wallMax) < 0.55f && wallMin > 0 && edgeMax > wallMax,
                String.format("%.2f to %.2f stops over %d pixels inside it, up to %.2f at its edge", HdrGain.stops(wallMin),
                        HdrGain.stops(wallMax), wall, HdrGain.stops(edgeMax)));
        check("never more than the pixel's own level allows", ordered, "");
        check("the log's line counts them", lifted > 0 && most == 255, note);

        // rows measured in another order: the same map
        HdrGain g = new HdrGain(w, h);
        int[] row = new int[w];
        for (int y = h - 1; y >= 0; y--) {
            img.getRGB(0, y, w, 1, row, 0, w);
            g.measure(y, row, 0);
        }
        byte[] again = new byte[w * h];
        for (int y = h - 1; y >= 0; y--) {
            img.getRGB(0, y, w, 1, row, 0, w);
            g.row(y, row, 0, again, y * w);
        }
        check("the same map whatever order the rows come in", Arrays.equals(map, again), "");

        // odd sizes, a single pixel
        HdrGain one = new HdrGain(1, 1);
        one.measure(0, new int[]{0xFFFFFFFF}, 0);
        byte[] o = new byte[1];
        one.row(0, new int[]{0xFFFFFFFF}, 0, o, 0);
        HdrGain odd = new HdrGain(37, 5);
        int[] r37 = new int[37];
        Arrays.fill(r37, 0xFF202020);
        r37[36] = 0xFFFFFFFF;
        for (int y = 0; y < 5; y++) odd.measure(y, r37, 0);
        byte[] o37 = new byte[37];
        odd.row(4, r37, 0, o37, 0);
        check("small and odd-sized pictures", (o[0] & 255) == 64 && (o37[36] & 255) == 255 && o37[0] == 0,
                "a white pixel alone: " + (o[0] & 255) + "; a star in the last column: " + (o37[36] & 255));
    }

    static double srgbToLinear(double v) {
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    /** What a screen shows: the format's own arithmetic, at no headroom and at all of it. */
    static void screen(BufferedImage img, byte[] map) {
        System.out.println("== on a screen");
        UltraHdr.Meta m = HdrGain.meta();
        int w = img.getWidth(), h = img.getHeight();
        double maxSdrDiff = 0, brightest = 0, skyChange = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                double sdr = srgbToLinear(level(rgb) / 255.0);
                double g = m.gainMapMin + (m.gainMapMax - m.gainMapMin) * Math.pow((map[y * w + x] & 255) / 255.0, 1 / m.gamma);
                double none = (sdr + m.offsetSdr) * Math.pow(2, g * 0) - m.offsetHdr;
                double full = (sdr + m.offsetSdr) * Math.pow(2, g * 1) - m.offsetHdr;
                maxSdrDiff = Math.max(maxSdrDiff, Math.abs(none - sdr));
                brightest = Math.max(brightest, full);
                if (level(rgb) < 255 * HdrGain.START) skyChange = Math.max(skyChange, Math.abs(full - sdr));
            }
        }
        check("an ordinary screen shows the photo itself", maxSdrDiff < 1e-12, "");
        check("an HDR screen shows the stars about four times white", brightest > 3.9 && brightest < 4.1, String.format("%.2fx", brightest));
        check("and the sky as it was", skyChange < 1e-12, "");
    }

    // ---- the file ---------------------------------------------------------------------------------------------------

    static void variant(File dir, String name, byte[] jpeg, byte[] gain, File video, long stillUs, boolean hadExif) throws Exception {
        System.out.println("== " + name + (video != null ? " (with its video)" : ""));
        File in = new File(dir, name + ".jpg"), gf = new File(dir, name + ".gain.jpg");
        Files.write(in.toPath(), jpeg);
        Files.write(gf.toPath(), gain);
        File hdr = new File(dir, "NightSky_" + name + (video != null ? ".MP.jpg" : ".jpg"));
        UltraHdr.Meta meta = HdrGain.meta();
        OutputStream os = new FileOutputStream(hdr);
        try {
            UltraHdr.write(in, gf, meta, video, stillUs, os);
        } finally {
            os.close();
        }
        byte[] file = Files.readAllBytes(hdr.toPath());
        byte[] mp4 = video != null ? Files.readAllBytes(video.toPath()) : new byte[0];

        UltraHdr.Info info = UltraHdr.read(hdr);
        check("read back as an HDR photo", info != null, "");
        if (info == null) return;
        check("the map starts right after the photo's end marker and is a JPEG", (file[(int) info.primaryEnd] & 255) == 0xFF
                && (file[(int) info.primaryEnd + 1] & 255) == 0xD8 && (file[(int) info.gainEnd - 1] & 255) == 0xD9, "at " + info.primaryEnd);
        check("the file is the photo, the map and the video, nothing else", info.gainEnd + mp4.length == file.length,
                info.primaryEnd + " + " + (info.gainEnd - info.primaryEnd) + " + " + mp4.length + " = " + file.length);
        check("the directory has the map's length", info.dirGainLength == info.gainEnd - info.primaryEnd, info.dirGainLength + " bytes");
        check("the picture index has two pictures, the photo's length and the map's place and length", info.mpfImages == 2
                        && info.mpfPrimaryLength == info.primaryEnd && info.mpfGainStart == info.primaryEnd
                        && info.mpfGainLength == info.gainEnd - info.primaryEnd,
                info.mpfImages + " pictures, photo " + info.mpfPrimaryLength + ", map at " + info.mpfGainStart + " + " + info.mpfGainLength);
        check("the map's numbers come back", info.meta.gainMapMin == meta.gainMapMin && info.meta.gainMapMax == meta.gainMapMax
                && info.meta.gamma == meta.gamma && info.meta.offsetSdr == meta.offsetSdr && info.meta.offsetHdr == meta.offsetHdr
                && info.meta.capacityMin == meta.capacityMin && info.meta.capacityMax == meta.capacityMax, "up to "
                + info.meta.gainMapMax + " stops, offsets " + info.meta.offsetSdr);

        // Android's decoder, by the picture index
        long[] byIndex = skiaIndex(file);
        check("Android's reading of the picture index finds the map", byIndex != null && byIndex[0] == info.primaryEnd
                && byIndex[1] == info.gainEnd - info.primaryEnd, byIndex == null ? "no index it accepts" : "at " + byIndex[0] + ", "
                + byIndex[1] + " bytes");
        // and by the XMP directory
        long[] byXmp = skiaContainer(info.xmp);
        check("Android's reading of the XMP directory finds the map", byXmp != null && byXmp[0] == 0
                && byXmp[1] == info.gainEnd - info.primaryEnd, byXmp == null ? "no directory it accepts" : byXmp[0]
                + " bytes after the photo, " + byXmp[1] + " bytes");
        double[] base = skiaGainInfo(info.xmp), mapInfo = skiaGainInfo(info.gainXmp);
        check("the photo's XMP says a map follows", base != null, "");
        check("the map's XMP gives Android its numbers", mapInfo != null && near(mapInfo[0], 1) && near(mapInfo[1], 4)
                && near(mapInfo[2], 1) && near(mapInfo[3], 1 / 64.0) && near(mapInfo[4], 1 / 64.0) && near(mapInfo[5], 1)
                && near(mapInfo[6], 4), mapInfo == null ? "not read" : String.format("gain %.0fx to %.0fx, gamma %.0f, shown "
                + "in full from %.0fx headroom", mapInfo[0], mapInfo[1], mapInfo[2], mapInfo[6]));
        // the map's own JPEG: its JFIF block first, then its XMP, as Android's encoder writes it; and the attribute
        // names spelled as Google's libultrahdr looks for them (it matches the names letter for letter)
        List<byte[]> mapSegs = MotionPhotoTest.segments(Arrays.copyOfRange(file, (int) info.primaryEnd, (int) info.gainEnd));
        int mapXmpAt = -1, mapXmps = 0;
        for (int i = 0; i < mapSegs.size(); i++) {
            if (MotionPhotoTest.isXmp(mapSegs.get(i))) {
                mapXmpAt = i;
                mapXmps++;
            }
        }
        check("the map's XMP follows its JFIF block", mapXmps == 1 && mapXmpAt == 1 && MotionPhotoTest.marker(mapSegs.get(0)) == 0xE0,
                "block " + mapXmpAt + " of " + mapSegs.size());
        check("the map's XMP names its numbers the way libultrahdr reads them", info.gainXmp.contains("<rdf:Description ")
                && info.gainXmp.contains(" hdrgm:Version=\"1.0\"") && info.gainXmp.contains(" hdrgm:GainMapMax=\"")
                && info.gainXmp.contains(" hdrgm:HDRCapacityMax=\"") && info.gainXmp.contains(" hdrgm:BaseRenditionIsHDR=\"False\""), "");
        check("both XMP blocks fit a JPEG block and hold nothing after the packet", info.xmp.length() < 60000
                && info.xmp.endsWith("</x:xmpmeta>") && info.gainXmp.endsWith("</x:xmpmeta>"), info.xmp.length() + " and "
                + info.gainXmp.length() + " characters");

        // the photo itself: unchanged pixels, from the photo part and from the whole file
        BufferedImage before = ImageIO.read(in);
        BufferedImage part = ImageIO.read(new ByteArrayInputStream(Arrays.copyOf(file, (int) info.primaryEnd)));
        BufferedImage whole = ImageIO.read(hdr);
        check("the photo decodes the same as before", MotionPhotoTest.samePixels(before, part), "");
        check("the whole file decodes as the photo", MotionPhotoTest.samePixels(before, whole), "");
        BufferedImage mapBefore = ImageIO.read(gf);
        BufferedImage mapAfter = ImageIO.read(new ByteArrayInputStream(Arrays.copyOfRange(file, (int) info.primaryEnd, (int) info.gainEnd)));
        check("the map decodes the same as before", MotionPhotoTest.samePixels(mapBefore, mapAfter), mapAfter == null ? "not decoded"
                : mapAfter.getWidth() + "x" + mapAfter.getHeight());

        // every block but XMP and an old index kept as it was, in order; the new XMP and index after the JFIF/Exif blocks
        List<byte[]> a = MotionPhotoTest.segments(jpeg), b = MotionPhotoTest.segments(file);
        List<byte[]> aKept = new ArrayList<byte[]>(), bKept = new ArrayList<byte[]>();
        for (byte[] s : a) if (!MotionPhotoTest.isXmp(s) && !isIndex(s)) aKept.add(s);
        int xmpAt = -1, indexAt = -1, xmps = 0, indexes = 0;
        for (int i = 0; i < b.size(); i++) {
            if (MotionPhotoTest.isXmp(b.get(i))) {
                xmpAt = i;
                xmps++;
            } else if (isIndex(b.get(i))) {
                indexAt = i;
                indexes++;
            } else {
                bKept.add(b.get(i));
            }
        }
        boolean same = aKept.size() == bKept.size();
        for (int i = 0; same && i < aKept.size(); i++) same = Arrays.equals(aKept.get(i), bKept.get(i));
        check("every other block kept as it was", same, aKept.size() + " vs " + bKept.size() + " blocks");
        int leading = hadExif ? 2 : 1;
        check("one XMP block and one picture index, after the JFIF and Exif blocks", xmps == 1 && indexes == 1 && xmpAt == leading
                && indexAt == leading + 1, "XMP at " + xmpAt + ", index at " + indexAt);
        check("the image data copied as it was", Arrays.equals(MotionPhotoTest.tail(jpeg), Arrays.copyOfRange(file,
                (int) (info.primaryEnd - MotionPhotoTest.tail(jpeg).length), (int) info.primaryEnd)), "");

        // the video
        MotionPhoto.Info mo = MotionPhoto.read(hdr);
        if (video == null) {
            check("without a video it isn't a Motion Photo", mo == null && MotionPhotoTest.media3(info.xmp, file.length) == null, "");
        } else {
            check("still a Motion Photo: the video after the map, byte for byte", mo != null && mo.videoStart == info.gainEnd
                    && mo.videoLength == mp4.length && mo.stillUs == stillUs && mo.xmpBlocks == 1
                    && Arrays.equals(Arrays.copyOfRange(file, (int) info.gainEnd, file.length), mp4), mo == null ? "not read"
                    : "at " + mo.videoStart + ", " + mo.videoLength + " bytes");
            long[] media3 = MotionPhotoTest.media3(info.xmp, file.length);
            check("Media3's reading finds the same video", media3 != null && media3[2] == info.gainEnd && media3[3] == mp4.length
                    && media3[4] == stillUs, media3 == null ? "not a motion photo to it" : "video at " + media3[2] + ", " + media3[3]
                    + " bytes, still at " + media3[4] + " us");
            String probe = MotionPhotoTest.ffprobe(Arrays.copyOfRange(file, (int) info.gainEnd, file.length), dir);
            if (probe != null) check("ffprobe reads the extracted video", probe.contains("codec_name=h264"), probe.replace('\n', ' ').trim());
        }

        // another program's reading of the picture index (Pillow), where there is one. It opens such a file as the plain
        // photo on purpose (it knows the format by the photo's XMP and doesn't show HDR), so its index reader is asked.
        String pil = MotionPhotoTest.run(dir, "python3", "-c", "import sys\nfrom PIL import Image\nim = Image.open(sys.argv[1])\n"
                + "mp = im._getmp()\ne = mp[45058]\nprint(im.format, mp[45057], e[0]['Size'], e[0]['DataOffset'], "
                + "im.info['mpoffset'] + e[1]['DataOffset'], e[1]['Size'], e[0]['Attribute']['MPType'])\n", hdr.getAbsolutePath());
        if (pil != null) {
            String want = "JPEG 2 " + info.primaryEnd + " 0 " + info.primaryEnd + " " + (info.gainEnd - info.primaryEnd)
                    + " Baseline MP Primary Image";
            check("Pillow reads the same index, and shows the plain photo", pil.trim().equals(want), pil.trim());
        } else {
            System.out.println("  (Pillow not here: skipped)");
        }
    }

    static boolean near(double a, double b) {
        return Math.abs(a - b) <= 1e-4 * Math.max(1, Math.abs(b));
    }

    static boolean isIndex(byte[] seg) {
        return MotionPhotoTest.marker(seg) == 0xE2 && seg.length >= 8 && seg[4] == 'M' && seg[5] == 'P' && seg[6] == 'F' && seg[7] == 0;
    }

    static void refusals(File dir, byte[] jpeg, byte[] gain, File video) throws Exception {
        System.out.println("== refusals (the app then saves the photo as before)");
        File good = new File(dir, "good.jpg"), goodGain = new File(dir, "good.gain.jpg");
        Files.write(good.toPath(), jpeg);
        Files.write(goodGain.toPath(), gain);
        File notJpeg = new File(dir, "not-a-jpeg.jpg");
        Files.write(notJpeg.toPath(), "hello".getBytes("UTF-8"));
        check("the photo not a JPEG: refused, nothing written", refused(notJpeg, goodGain, null), "");
        check("the map not a JPEG: refused, nothing written", refused(good, notJpeg, null), "");
        File trailing = new File(dir, "trailing.jpg");
        Files.write(trailing.toPath(), MotionPhotoTest.concat(gain, new byte[]{1, 2, 3}));
        check("data after the map's end: refused, nothing written", refused(good, trailing, null), "");
        File cut = new File(dir, "cut.jpg");
        Files.write(cut.toPath(), MotionPhotoTest.concat(Arrays.copyOf(jpeg, 200), new byte[]{(byte) 0xFF, (byte) 0xD9}));
        check("a broken header: refused, nothing written", refused(cut, goodGain, null), "");
        File empty = new File(dir, "empty.mp4");
        Files.write(empty.toPath(), new byte[0]);
        check("an empty video: refused, nothing written", refused(good, goodGain, empty), "");
        check("a plain JPEG isn't taken for an HDR photo", UltraHdr.read(good) == null, "");
        File mp = new File(dir, "plain.MP.jpg");
        OutputStream os = new FileOutputStream(mp);
        try {
            MotionPhoto.write(good, video, 0, os);
        } finally {
            os.close();
        }
        check("nor is a Motion Photo without a map", UltraHdr.read(mp) == null, "");
    }

    static boolean refused(File jpeg, File gain, File video) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            UltraHdr.write(jpeg, gain, HdrGain.meta(), video, 0, bos);
            return false;
        } catch (IOException e) {
            return bos.size() == 0;
        }
    }

    // ---- Android's logic (Skia: SkJpegMultiPicture, SkJpegXmp / SkXmp, SkJpegCodec's gain map search) -----------------

    /**
     * {start, length} of the second picture as Android works it out from the Multi-Picture Format block, or null where
     * either of its readers would not accept the block. Skia (SkJpegMultiPicture.cpp, read 2026-11-11): "MPF\0", a
     * TIFF header, a directory whose tags come in rising order and are only the ones it knows; version "0100" (four
     * undefined bytes in the entry itself), the number of pictures (one long), the entries (16 undefined bytes a
     * picture, at an offset); the first picture, and only the first, marked as the primary one, every picture a JPEG,
     * the first at offset 0; places counted from the header's first byte. Google's libultrahdr (jpegr.cpp,
     * hasValidMpfAssociation) wants exactly two pictures and nothing else in either picture's type besides.
     */
    static long[] skiaIndex(byte[] file) {
        int p = 2;
        while (p + 4 <= file.length && (file[p] & 255) == 0xFF && (file[p + 1] & 255) != 0xDA) {
            int marker = file[p + 1] & 255;
            int len = ((file[p + 2] & 255) << 8) | (file[p + 3] & 255);
            if (marker == 0xE2 && len >= 6 && file[p + 4] == 'M' && file[p + 5] == 'P' && file[p + 6] == 'F' && file[p + 7] == 0) {
                int base = p + 8, end = p + 2 + len;
                boolean little;
                if (file[base] == 'I' && file[base + 1] == 'I' && file[base + 2] == 42 && file[base + 3] == 0) little = true;
                else if (file[base] == 'M' && file[base + 1] == 'M' && file[base + 2] == 0 && file[base + 3] == 42) little = false;
                else return null;
                long ifd = base + u32(file, base + 4, little);
                if (ifd + 2 > end) return null;
                int count = u16(file, (int) ifd, little);
                if (ifd + 2 + 12L * count + 4 > end) return null;
                long images = 0, entries = -1;
                boolean version = false;
                int previous = 0;
                for (int i = 0; i < count; i++) {
                    int e = (int) ifd + 2 + 12 * i;
                    int tag = u16(file, e, little), type = u16(file, e + 2, little);
                    long n = u32(file, e + 4, little);
                    if (previous >= tag) return null; // in rising order, each once
                    previous = tag;
                    if (tag == 0xB000) {
                        if (type != 7 || n != 4) return null;
                        if (file[e + 8] != '0' || file[e + 9] != '1' || file[e + 10] != '0' || file[e + 11] != '0') return null;
                        version = true;
                    } else if (tag == 0xB001) {
                        if (type != 4 || n != 1) return null;
                        images = u32(file, e + 8, little);
                        if (images < 1) return null;
                    } else if (tag == 0xB002) {
                        if (type != 7 || n != 16 * images) return null; // the number of pictures must come first
                        entries = base + u32(file, e + 8, little);
                        if (entries + n > end) return null;
                    } else if (tag == 0xB003) {
                        if (type != 7 || n != 33 * images) return null;
                    } else if (tag != 0xB004) {
                        return null; // a tag Skia doesn't know: it gives the whole block up
                    }
                }
                if (!version || images != 2 || entries < 0) return null;
                long attr0 = u32(file, (int) entries, little), attr1 = u32(file, (int) entries + 16, little);
                // the first is the primary picture and a JPEG, the second a JPEG and nothing else
                if ((attr0 & 0x0FFFFFFF) != 0x030000 || (attr1 & 0x0FFFFFFF) != 0) return null;
                // the first picture's place must be 0 (the file's start)
                if (u32(file, (int) entries + 8, little) != 0) return null;
                long size = u32(file, (int) entries + 16 + 4, little), off = u32(file, (int) entries + 16 + 8, little);
                if (off == 0) return null;
                return new long[]{base + off, size};
            }
            p += 2 + len;
        }
        return null;
    }

    static int u16(byte[] b, int p, boolean little) {
        return little ? (b[p] & 255) | ((b[p + 1] & 255) << 8) : ((b[p] & 255) << 8) | (b[p + 1] & 255);
    }

    static long u32(byte[] b, int p, boolean little) {
        long a = u16(b, p, little), c = u16(b, p + 2, little);
        return little ? a | (c << 16) : (a << 16) | c;
    }

    /**
     * Finds the rdf:Description Skia would use: under x:xmpmeta / rdf:RDF, the first one that itself declares every one
     * of {@code uris} as a namespace (declarations further up don't count for it). Returns the reader at that element
     * and fills {@code prefixes}, or null.
     */
    static XMLStreamReader description(String xmp, String[] uris, String[] prefixes) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader x = f.createXMLStreamReader(new StringReader(xmp));
        int depth = 0;
        while (x.hasNext()) {
            int ev = x.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                depth++;
                String q = MotionPhotoTest.qname(x);
                if (depth == 1 && !q.equals("x:xmpmeta")) return null;
                if (depth == 2 && !q.equals("rdf:RDF")) {
                    skip(x);
                    depth--;
                    continue;
                }
                if (depth == 3) {
                    if (q.equals("rdf:Description")) {
                        boolean all = true;
                        for (int u = 0; u < uris.length; u++) {
                            prefixes[u] = null;
                            for (int i = 0; i < x.getNamespaceCount(); i++) {
                                if (uris[u].equals(x.getNamespaceURI(i))) prefixes[u] = x.getNamespacePrefix(i);
                            }
                            if (prefixes[u] == null) all = false;
                        }
                        if (all) return x;
                    }
                    skip(x);
                    depth--;
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                depth--;
            }
        }
        return null;
    }

    /** Moves past the element the reader is at the start of. */
    static void skip(XMLStreamReader x) throws Exception {
        int depth = 1;
        while (depth > 0 && x.hasNext()) {
            int ev = x.next();
            if (ev == XMLStreamConstants.START_ELEMENT) depth++;
            else if (ev == XMLStreamConstants.END_ELEMENT) depth--;
        }
    }

    static String attribute(XMLStreamReader x, String prefix, String name) {
        for (int i = 0; i < x.getAttributeCount(); i++) {
            if (name.equals(x.getAttributeLocalName(i)) && prefix.equals(x.getAttributePrefix(i))) return x.getAttributeValue(i);
        }
        return null;
    }

    /**
     * {ratioMin, ratioMax, gamma, epsilonSdr, epsilonHdr, displayRatioSdr, displayRatioHdr} as Skia takes them from an
     * XMP packet's hdrgm attributes, or null where it finds no gain map there (the version must be exactly "1.0").
     */
    static double[] skiaGainInfo(String xmp) throws Exception {
        String[] prefix = new String[1];
        XMLStreamReader x = description(xmp, new String[]{HDRGM}, prefix);
        if (x == null) return null;
        String version = attribute(x, prefix[0], "Version");
        if (version == null || !version.equals("1.0")) return null;
        String base = attribute(x, prefix[0], "BaseRenditionIsHDR");
        if (base != null && !base.equals("False")) return null; // this app's photos are never HDR underneath
        double min = number(x, prefix[0], "GainMapMin", 0), max = number(x, prefix[0], "GainMapMax", 1);
        double gamma = number(x, prefix[0], "Gamma", 1);
        double eSdr = number(x, prefix[0], "OffsetSDR", 1 / 64.0), eHdr = number(x, prefix[0], "OffsetHDR", 1 / 64.0);
        double capMin = number(x, prefix[0], "HDRCapacityMin", 0), capMax = number(x, prefix[0], "HDRCapacityMax", 1);
        return new double[]{Math.pow(2, min), Math.pow(2, max), 1 / gamma, eSdr, eHdr, Math.pow(2, capMin), Math.pow(2, capMax)};
    }

    static double number(XMLStreamReader x, String prefix, String name, double otherwise) {
        String v = attribute(x, prefix, name);
        return v != null ? Double.parseDouble(v) : otherwise;
    }

    /**
     * {offset after the photo, length} of the gain map as Skia works it out from the XMP's container directory, or null:
     * every item needs a Mime and a Semantic, the first must be the Primary JPEG (its Padding counts), every other needs
     * a Length, and those before the GainMap are skipped over.
     */
    static long[] skiaContainer(String xmp) throws Exception {
        String[] prefix = new String[2];
        XMLStreamReader x = description(xmp, new String[]{CONTAINER, ITEM}, prefix);
        if (x == null) return null;
        boolean inDirectory = false, inSeq = false, first = true;
        long offset = 0;
        int depth = 0;
        while (x.hasNext()) {
            int ev = x.next();
            if (ev == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) break;
                depth--;
                if (depth == 0) inDirectory = false;
                if (depth == 1) inSeq = false;
                continue;
            }
            if (ev != XMLStreamConstants.START_ELEMENT) continue;
            depth++;
            String q = MotionPhotoTest.qname(x);
            if (depth == 1 && q.equals(prefix[0] + ":Directory")) inDirectory = true;
            else if (depth == 2 && inDirectory && q.equals("rdf:Seq")) inSeq = true;
            else if (depth == 4 && inSeq && q.equals(prefix[0] + ":Item")) {
                String sem = attribute(x, prefix[1], "Semantic"), mime = attribute(x, prefix[1], "Mime");
                if (sem == null || mime == null) return null;
                if (first) {
                    first = false;
                    if (!sem.equals("Primary") || !mime.equals("image/jpeg")) return null;
                    String pad = attribute(x, prefix[1], "Padding");
                    if (pad != null) offset += Long.parseLong(pad);
                } else {
                    String len = attribute(x, prefix[1], "Length");
                    if (len == null) return null;
                    if (!sem.equals("GainMap")) {
                        offset += Long.parseLong(len);
                        continue;
                    }
                    if (!mime.equals("image/jpeg")) return null;
                    return new long[]{offset, Long.parseLong(len)};
                }
            }
        }
        return null;
    }

    // ---- JPEGs of the map -----------------------------------------------------------------------------------------------

    static byte[] grayJpeg(byte[] map, int w, int h, boolean progressive) throws IOException {
        BufferedImage g = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        g.getRaster().setDataElements(0, 0, w, h, map);
        return encode(g, progressive);
    }

    static byte[] rgbJpeg(byte[] map, int w, int h) throws IOException {
        BufferedImage g = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) g.setRGB(x, y, (map[y * w + x] & 255) * 0x010101);
        }
        return encode(g, false);
    }

    static byte[] encode(BufferedImage img, boolean progressive) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageOutputStream ios = ImageIO.createImageOutputStream(bos);
        w.setOutput(ios);
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(0.90f);
        if (progressive) p.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        w.write(null, new IIOImage(img, null, null), p);
        ios.close();
        w.dispose();
        return bos.toByteArray();
    }
}
