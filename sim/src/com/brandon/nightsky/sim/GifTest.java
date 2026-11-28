package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.GifWriter;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

/**
 * Mission Replay's GIF encoder (2.4, {@link GifWriter}) read back by Java's own GIF decoder, frame by frame, laid over
 * each other as a viewer does:
 * <ul>
 * <li>every frame comes back exactly, colour for colour, through the changed-rectangle frames and the transparent
 * "unchanged" pixels;
 * <li>a frame of noise big enough to fill the LZW table many times over (its clear codes) comes back exactly;
 * <li>a frame that changes nothing adds its time to the frame before; it loops forever;
 * <li>the median-cut palette keeps its exact colours, and the dither leaves a flat colour's pixels the same frame to frame.
 * </ul>
 */
public final class GifTest {
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        int W = 300, H = 200;
        Random rnd = new Random(7);
        // pictures: a sky gradient with a moving bright square, then noise, then the same again
        List<int[]> pics = new ArrayList<int[]>();
        int[] delays = {8, 8, 8, 120, 8, 50};
        for (int k = 0; k < 4; k++) {
            int[] p = new int[W * H];
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    int b = 20 + y * 60 / H, g = 12 + x * 30 / W;
                    p[y * W + x] = 0xFF000000 | (8 << 16) | (g << 8) | b;
                }
            }
            for (int y = 40; y < 80; y++) for (int x = 30 + 40 * k; x < 70 + 40 * k; x++) p[y * W + x] = 0xFFFFE3A3;
            pics.add(p);
        }
        int[] noise = new int[W * H];
        for (int i = 0; i < noise.length; i++) noise[i] = 0xFF000000 | rnd.nextInt(0x1000000);
        pics.add(noise);
        pics.add(pics.get(3).clone());
        // the palette from all of them
        int[] samples = new int[400000];
        int n = 0;
        for (int[] p : pics) for (int i = 0; i < p.length && n < samples.length; i += 2) samples[n++] = p[i] & 0xFFFFFF;
        GifWriter.Palette pal = GifWriter.Palette.cut(samples, n, 255, new int[]{0x000000, 0xFFFFFF, 0xFFE3A3});
        check("the palette holds 255 colours at most, its exact ones first", pal.rgb.length <= 255 && pal.rgb[0] == 0 && pal.rgb[1] == 0xFFFFFF
                && pal.rgb[2] == 0xFFE3A3, pal.rgb.length + " colours");
        // a flat colour dithers to the same pixels each time
        int[] flat = new int[64 * 64];
        java.util.Arrays.fill(flat, 0xFF203040);
        byte[] fa = new byte[flat.length], fb = new byte[flat.length];
        pal.index(flat, 64, 64, fa);
        pal.index(flat, 64, 64, fb);
        check("the dither leaves a still picture's pixels as they were", java.util.Arrays.equals(fa, fb), "");
        // written: the pictures, then one that changes nothing (its time goes to the one before)
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        GifWriter gw = new GifWriter(bos, W, H, pal.rgb, 0);
        List<byte[]> want = new ArrayList<byte[]>();
        for (int k = 0; k < pics.size(); k++) {
            byte[] idx = new byte[W * H];
            pal.index(pics.get(k), W, H, idx);
            want.add(idx);
            gw.frame(idx, delays[k]);
        }
        gw.frame(want.get(want.size() - 1).clone(), 70);
        gw.finish();
        byte[] gif = bos.toByteArray();
        System.out.printf("  %d frames written, %d bytes%n", gw.frames(), gif.length);
        check("a frame that changes nothing isn't one", gw.frames() == pics.size(), gw.frames() + " frames");

        // read back as a viewer would
        ImageReader rd = ImageIO.getImageReadersByFormatName("gif").next();
        ImageInputStream iis = ImageIO.createImageInputStream(new ByteArrayInputStream(gif));
        rd.setInput(iis, false);
        int frames = rd.getNumImages(true);
        check("Java's decoder reads every frame", frames == pics.size(), frames + " frames");
        int[] screen = new int[W * H];
        int total = 0;
        boolean exact = true;
        String where = "";
        for (int f = 0; f < frames; f++) {
            BufferedImage im = rd.read(f);
            IIOMetadata md = rd.getImageMetadata(f);
            Node root = md.getAsTree("javax_imageio_gif_image_1.0");
            int left = 0, top = 0, trans = -1, delay = 0;
            for (Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
                NamedNodeMap at = c.getAttributes();
                if ("ImageDescriptor".equals(c.getNodeName())) {
                    left = Integer.parseInt(at.getNamedItem("imageLeftPosition").getNodeValue());
                    top = Integer.parseInt(at.getNamedItem("imageTopPosition").getNodeValue());
                } else if ("GraphicControlExtension".equals(c.getNodeName())) {
                    if ("TRUE".equals(at.getNamedItem("transparentColorFlag").getNodeValue())) {
                        trans = Integer.parseInt(at.getNamedItem("transparentColorIndex").getNodeValue());
                    }
                    delay = Integer.parseInt(at.getNamedItem("delayTime").getNodeValue());
                }
            }
            total += delay;
            for (int y = 0; y < im.getHeight(); y++) {
                for (int x = 0; x < im.getWidth(); x++) {
                    int v = im.getRaster().getSample(x, y, 0);
                    if (v == trans) continue;
                    screen[(top + y) * W + left + x] = v;
                }
            }
            byte[] w = want.get(f);
            for (int i = 0; i < screen.length && exact; i++) {
                if (screen[i] != (w[i] & 0xFF)) {
                    exact = false;
                    where = "frame " + f + " pixel " + i + ": " + screen[i] + " for " + (w[i] & 0xFF);
                }
            }
        }
        check("every frame comes back exactly, the noise's too (the LZW table filled and cleared)", exact, where);
        int sum = 0;
        for (int d : delays) sum += d;
        check("the time adds up (the unchanged frame's to the one before)", total == sum + 70, total + " cs");
        IIOMetadata sm = rd.getStreamMetadata();
        boolean loops = false;
        Node sroot = rd.getImageMetadata(0).getAsTree("javax_imageio_gif_image_1.0");
        for (Node c = sroot.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!"ApplicationExtensions".equals(c.getNodeName())) continue;
            for (Node a = c.getFirstChild(); a != null; a = a.getNextSibling()) {
                Object u = ((javax.imageio.metadata.IIOMetadataNode) a).getUserObject();
                if (u instanceof byte[]) {
                    byte[] b = (byte[]) u;
                    loops = b.length >= 3 && b[0] == 1 && b[1] == 0 && b[2] == 0;
                }
            }
        }
        check("it loops forever", loops, sm != null ? "" : "no stream metadata");
        System.out.println(failed == 0 ? "ALL PASS" : failed + " failed");
        if (failed > 0) System.exit(1);
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println("  " + (ok ? "PASS" : "FAIL") + ": " + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failed++;
    }

    @SuppressWarnings("unused")
    private static Iterator<ImageReader> readers() {
        return ImageIO.getImageReadersByFormatName("gif");
    }
}
