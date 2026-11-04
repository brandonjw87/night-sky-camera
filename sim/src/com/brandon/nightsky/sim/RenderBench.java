package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * A developed photo kept on disk so its rendering can be tried again and again without a night of frames: SimRunner
 * keeps one with SIM_KEEP=file (the stacked picture and its analysis), and
 * {@code RenderBench <file> <out dir> <detail>...} renders it with each amount of the sky's detail into
 * {@code detail_<v>.png}, printing the noise reduction's own line.
 */
public final class RenderBench {
    private RenderBench() {}

    public static void main(String[] args) throws Exception {
        Object[] kept = load(new File(args[0]));
        RgbImage img = (RgbImage) kept[0];
        File out = new File(args[1]);
        out.mkdirs();
        for (int i = 2; i < args.length; i++) {
            Renderer.Analysis a = (Renderer.Analysis) kept[1];
            Renderer.Params p = new Renderer.Params();
            p.detail = Float.parseFloat(args[i]);
            final int w = a.cw, h = a.ch;
            final int[] px = new int[w * h];
            long t0 = System.currentTimeMillis();
            Renderer.render(img, a, p, new Renderer.RowSink() {
                @Override
                public void rows(int y, int count, int[] argb, int width) {
                    System.arraycopy(argb, 0, px, y * width, count * width);
                }
            }, null);
            System.out.println("detail " + args[i] + ": " + (System.currentTimeMillis() - t0) + " ms; " + a.noiseNote());
            SimRunner.writePng(px, w, h, new File(out, "detail_" + args[i] + ".png"));
        }
    }

    /** Writes the picture and its analysis (their public fields: numbers and arrays of them) to {@code f}. */
    public static void save(RgbImage img, Renderer.Analysis a, File f) throws IOException, IllegalAccessException {
        DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 20));
        try {
            o.writeInt(img.width);
            o.writeInt(img.height);
            o.writeInt(img.x0);
            o.writeInt(img.y0);
            o.writeInt(img.x1);
            o.writeInt(img.y1);
            floats(o, img.r);
            floats(o, img.g);
            floats(o, img.b);
            o.writeBoolean(img.clip != null);
            if (img.clip != null) {
                o.writeInt(img.clip.length);
                o.write(img.clip);
            }
            for (Field fd : fields()) {
                Class<?> t = fd.getType();
                Object v = fd.get(a);
                if (t == float.class) o.writeFloat(fd.getFloat(a));
                else if (t == int.class) o.writeInt(fd.getInt(a));
                else if (t == float[].class) floats(o, (float[]) v);
                else if (t == double[][].class) {
                    double[][] d = (double[][]) v;
                    o.writeInt(d.length);
                    for (double[] row : d) {
                        o.writeInt(row.length);
                        for (double x : row) o.writeDouble(x);
                    }
                }
            }
        } finally {
            o.close();
        }
    }

    static Object[] load(File f) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 20));
        try {
            RgbImage img = new RgbImage(in.readInt(), in.readInt());
            img.x0 = in.readInt();
            img.y0 = in.readInt();
            img.x1 = in.readInt();
            img.y1 = in.readInt();
            readFloats(in, img.r);
            readFloats(in, img.g);
            readFloats(in, img.b);
            if (in.readBoolean()) {
                img.clip = new byte[in.readInt()];
                in.readFully(img.clip);
            }
            Renderer.Analysis a = new Renderer.Analysis();
            for (Field fd : fields()) {
                Class<?> t = fd.getType();
                if (t == float.class) fd.setFloat(a, in.readFloat());
                else if (t == int.class) fd.setInt(a, in.readInt());
                else if (t == float[].class) {
                    int n = in.readInt();
                    if (n < 0) {
                        fd.set(a, null);
                    } else {
                        float[] v = new float[n];
                        for (int i = 0; i < n; i++) v[i] = in.readFloat();
                        fd.set(a, v);
                    }
                } else if (t == double[][].class) {
                    double[][] d = new double[in.readInt()][];
                    for (int r = 0; r < d.length; r++) {
                        d[r] = new double[in.readInt()];
                        for (int i = 0; i < d[r].length; i++) d[r][i] = in.readDouble();
                    }
                    fd.set(a, d);
                }
            }
            return new Object[] {img, a};
        } finally {
            in.close();
        }
    }

    /** Analysis's public, non-static fields of the kinds kept, in a fixed order. */
    private static java.util.List<Field> fields() {
        java.util.List<Field> out = new java.util.ArrayList<Field>();
        for (Field fd : Renderer.Analysis.class.getDeclaredFields()) {
            int mod = fd.getModifiers();
            if (!Modifier.isPublic(mod) || Modifier.isStatic(mod)) continue;
            Class<?> t = fd.getType();
            if (t == float.class || t == int.class || t == float[].class || t == double[][].class) out.add(fd);
        }
        java.util.Collections.sort(out, new java.util.Comparator<Field>() {
            @Override
            public int compare(Field x, Field y) {
                return x.getName().compareTo(y.getName());
            }
        });
        return out;
    }

    private static void floats(DataOutputStream o, float[] v) throws IOException {
        if (v == null) {
            o.writeInt(-1);
            return;
        }
        o.writeInt(v.length);
        byte[] buf = new byte[4 * 4096];
        for (int i = 0; i < v.length; i += 4096) {
            int n = Math.min(4096, v.length - i);
            for (int k = 0; k < n; k++) {
                int b = Float.floatToRawIntBits(v[i + k]);
                buf[4 * k] = (byte) (b >>> 24);
                buf[4 * k + 1] = (byte) (b >>> 16);
                buf[4 * k + 2] = (byte) (b >>> 8);
                buf[4 * k + 3] = (byte) b;
            }
            o.write(buf, 0, 4 * n);
        }
    }

    private static void readFloats(DataInputStream in, float[] v) throws IOException {
        int n = in.readInt();
        if (n != v.length) throw new IOException("picture of " + n + " values, expected " + v.length);
        byte[] buf = new byte[4 * 4096];
        for (int i = 0; i < n; i += 4096) {
            int c = Math.min(4096, n - i);
            in.readFully(buf, 0, 4 * c);
            for (int k = 0; k < c; k++) {
                int b = (buf[4 * k] & 0xff) << 24 | (buf[4 * k + 1] & 0xff) << 16 | (buf[4 * k + 2] & 0xff) << 8 | (buf[4 * k + 3] & 0xff);
                v[i + k] = Float.intBitsToFloat(b);
            }
        }
    }
}
