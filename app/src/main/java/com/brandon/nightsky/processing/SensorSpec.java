package com.brandon.nightsky.processing;

import java.util.Locale;
import java.util.Properties;

/**
 * Everything the processing pipeline needs to know about the RAW stream, independent of Android.
 * Plane index p = (y & 1) * 2 + (x & 1). Color codes: 0 = red, 1 = green, 2 = blue.
 */
public final class SensorSpec {
    public int width, height;
    public final int[] cfa = {0, 1, 1, 2};
    public final float[] black = {64, 64, 64, 64};
    public float white = 1023;
    public CameraModel model;
    public ShadingMap shading;

    /** 3x3 row-major: normalized camera RGB -> white-balanced linear sRGB. */
    public float[] camToSrgb = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    /** Camera RGB of a neutral surface under the chosen white balance, green = 1. */
    public float[] camNeutral = {1, 1, 1};
    /** The white balance behind {@link #camToSrgb}, in kelvin. */
    public float wbCct = ColorMath.DEFAULT_CCT;

    // Metadata for files and logs.
    public String deviceMake = "", deviceModel = "", cameraId = "";
    public int sensorOrientation = 90;
    public float focalLengthMm = 0, fNumber = 0, pixelPitchUm = 0;
    public int iso = 0;
    public long frameExposureNs = 0;

    // DNG color metadata (row-major 3x3), may be null.
    public float[] colorMatrix1, colorMatrix2, forwardMatrix1, forwardMatrix2, calibration1, calibration2;
    public int illuminant1 = 0, illuminant2 = 0;

    /** Per-plane noise model on normalized data: variance = S * signal + O. Zero when unknown. */
    public final float[] noiseS = new float[4], noiseO = new float[4];

    public int planeColor(int plane) {
        return cfa[plane];
    }

    public float range() {
        return white - (black[0] + black[1] + black[2] + black[3]) * 0.25f;
    }

    /** A copy to change (the lens model and the shading map are shared: they aren't changed in place). */
    public SensorSpec copy() {
        SensorSpec s = new SensorSpec();
        s.width = width;
        s.height = height;
        System.arraycopy(cfa, 0, s.cfa, 0, 4);
        System.arraycopy(black, 0, s.black, 0, 4);
        s.white = white;
        s.model = model;
        s.shading = shading;
        s.camToSrgb = camToSrgb.clone();
        s.camNeutral = camNeutral.clone();
        s.wbCct = wbCct;
        s.deviceMake = deviceMake;
        s.deviceModel = deviceModel;
        s.cameraId = cameraId;
        s.sensorOrientation = sensorOrientation;
        s.focalLengthMm = focalLengthMm;
        s.fNumber = fNumber;
        s.pixelPitchUm = pixelPitchUm;
        s.iso = iso;
        s.frameExposureNs = frameExposureNs;
        s.colorMatrix1 = colorMatrix1;
        s.colorMatrix2 = colorMatrix2;
        s.forwardMatrix1 = forwardMatrix1;
        s.forwardMatrix2 = forwardMatrix2;
        s.calibration1 = calibration1;
        s.calibration2 = calibration2;
        s.illuminant1 = illuminant1;
        s.illuminant2 = illuminant2;
        System.arraycopy(noiseS, 0, s.noiseS, 0, 4);
        System.arraycopy(noiseO, 0, s.noiseO, 0, 4);
        return s;
    }

    /** Sets {@link #cfa} from Camera2's colorFilterArrangement, shifted by the RAW buffer origin parity. */
    public void setCfaFromArrangement(int arrangement, int originX, int originY) {
        int[] base;
        switch (arrangement) {
            case 1: base = new int[]{1, 0, 2, 1}; break; // GRBG
            case 2: base = new int[]{1, 2, 0, 1}; break; // GBRG
            case 3: base = new int[]{2, 1, 1, 0}; break; // BGGR
            default: base = new int[]{0, 1, 1, 2}; break; // RGGB
        }
        for (int p = 0; p < 4; p++) {
            int ox = ((p & 1) + originX) & 1;
            int oy = ((p >> 1) + originY) & 1;
            cfa[p] = base[oy * 2 + ox];
        }
    }

    public String describe() {
        return String.format(Locale.US, "%dx%d cfa=%d%d%d%d black=%.1f/%.1f/%.1f/%.1f white=%.0f f=%.2fmm N=%.2f %s",
                width, height, cfa[0], cfa[1], cfa[2], cfa[3], black[0], black[1], black[2], black[3], white,
                focalLengthMm, fNumber, model == null ? "no model" : model.toString());
    }

    // ---- persistence (session manifest for crash recovery) ----

    public Properties toProperties() {
        Properties p = new Properties();
        p.setProperty("width", "" + width);
        p.setProperty("height", "" + height);
        p.setProperty("cfa", join(cfa));
        p.setProperty("black", join(black));
        p.setProperty("white", "" + white);
        if (model != null) {
            p.setProperty("model", model.fx + "," + model.fy + "," + model.cx + "," + model.cy + "," + model.skew + ","
                    + model.k1 + "," + model.k2 + "," + model.k3 + "," + model.p1 + "," + model.p2);
            p.setProperty("modelSource", model.source);
        }
        if (shading != null) p.setProperty("shading", shading.encode());
        p.setProperty("camToSrgb", join(camToSrgb));
        p.setProperty("camNeutral", join(camNeutral));
        p.setProperty("wbCct", "" + wbCct);
        p.setProperty("deviceMake", deviceMake);
        p.setProperty("deviceModel", deviceModel);
        p.setProperty("cameraId", cameraId);
        p.setProperty("sensorOrientation", "" + sensorOrientation);
        p.setProperty("focalLengthMm", "" + focalLengthMm);
        p.setProperty("fNumber", "" + fNumber);
        p.setProperty("pixelPitchUm", "" + pixelPitchUm);
        p.setProperty("iso", "" + iso);
        p.setProperty("frameExposureNs", "" + frameExposureNs);
        putMatrix(p, "colorMatrix1", colorMatrix1);
        putMatrix(p, "colorMatrix2", colorMatrix2);
        putMatrix(p, "forwardMatrix1", forwardMatrix1);
        putMatrix(p, "forwardMatrix2", forwardMatrix2);
        putMatrix(p, "calibration1", calibration1);
        putMatrix(p, "calibration2", calibration2);
        p.setProperty("illuminant1", "" + illuminant1);
        p.setProperty("illuminant2", "" + illuminant2);
        p.setProperty("noiseS", join(noiseS));
        p.setProperty("noiseO", join(noiseO));
        return p;
    }

    public static SensorSpec fromProperties(Properties p) {
        SensorSpec s = new SensorSpec();
        s.width = Integer.parseInt(p.getProperty("width"));
        s.height = Integer.parseInt(p.getProperty("height"));
        int[] cfa = parseInts(p.getProperty("cfa"));
        System.arraycopy(cfa, 0, s.cfa, 0, 4);
        float[] bl = parseFloats(p.getProperty("black"));
        System.arraycopy(bl, 0, s.black, 0, 4);
        s.white = Float.parseFloat(p.getProperty("white"));
        String m = p.getProperty("model");
        if (m != null) {
            double[] v = parseDoubles(m);
            CameraModel cm = new CameraModel();
            cm.width = s.width;
            cm.height = s.height;
            cm.fx = v[0]; cm.fy = v[1]; cm.cx = v[2]; cm.cy = v[3]; cm.skew = v[4];
            cm.k1 = v[5]; cm.k2 = v[6]; cm.k3 = v[7]; cm.p1 = v[8]; cm.p2 = v[9];
            cm.source = p.getProperty("modelSource", "saved");
            s.model = cm;
        }
        s.shading = ShadingMap.decode(p.getProperty("shading"));
        s.camToSrgb = parseFloats(p.getProperty("camToSrgb"));
        s.camNeutral = parseFloats(p.getProperty("camNeutral"));
        s.wbCct = Float.parseFloat(p.getProperty("wbCct", "" + ColorMath.DEFAULT_CCT));
        s.deviceMake = p.getProperty("deviceMake", "");
        s.deviceModel = p.getProperty("deviceModel", "");
        s.cameraId = p.getProperty("cameraId", "");
        s.sensorOrientation = Integer.parseInt(p.getProperty("sensorOrientation", "90"));
        s.focalLengthMm = Float.parseFloat(p.getProperty("focalLengthMm", "0"));
        s.fNumber = Float.parseFloat(p.getProperty("fNumber", "0"));
        s.pixelPitchUm = Float.parseFloat(p.getProperty("pixelPitchUm", "0"));
        s.iso = Integer.parseInt(p.getProperty("iso", "0"));
        s.frameExposureNs = Long.parseLong(p.getProperty("frameExposureNs", "0"));
        s.colorMatrix1 = getMatrix(p, "colorMatrix1");
        s.colorMatrix2 = getMatrix(p, "colorMatrix2");
        s.forwardMatrix1 = getMatrix(p, "forwardMatrix1");
        s.forwardMatrix2 = getMatrix(p, "forwardMatrix2");
        s.calibration1 = getMatrix(p, "calibration1");
        s.calibration2 = getMatrix(p, "calibration2");
        s.illuminant1 = Integer.parseInt(p.getProperty("illuminant1", "0"));
        s.illuminant2 = Integer.parseInt(p.getProperty("illuminant2", "0"));
        float[] ns = parseFloats(p.getProperty("noiseS", "0,0,0,0"));
        float[] no = parseFloats(p.getProperty("noiseO", "0,0,0,0"));
        System.arraycopy(ns, 0, s.noiseS, 0, 4);
        System.arraycopy(no, 0, s.noiseO, 0, 4);
        return s;
    }

    private static void putMatrix(Properties p, String key, float[] m) {
        if (m != null) p.setProperty(key, join(m));
    }

    private static float[] getMatrix(Properties p, String key) {
        String v = p.getProperty(key);
        return v == null ? null : parseFloats(v);
    }

    static String join(float[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    static String join(int[] a) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    static float[] parseFloats(String s) {
        String[] p = s.split(",");
        float[] r = new float[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Float.parseFloat(p[i].trim());
        return r;
    }

    static double[] parseDoubles(String s) {
        String[] p = s.split(",");
        double[] r = new double[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Double.parseDouble(p[i].trim());
        return r;
    }

    static int[] parseInts(String s) {
        String[] p = s.split(",");
        int[] r = new int[p.length];
        for (int i = 0; i < p.length; i++) r[i] = Integer.parseInt(p[i].trim());
        return r;
    }
}
