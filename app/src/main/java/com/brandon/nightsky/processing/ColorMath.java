package com.brandon.nightsky.processing;

/**
 * Builds the camera-RGB -> linear sRGB matrix the way a DNG converter does, from the sensor's own
 * calibration matrices, for a fixed white balance (a color temperature in kelvin).
 */
public final class ColorMath {
    private ColorMath() {}

    /** Night-sky white balance: a sun-like (G2V) star renders white, hot stars blue, cool stars orange. */
    public static final float DEFAULT_CCT = 5500f;

    /** XYZ (D50) -> linear sRGB (D65), Bradford adapted. */
    static final double[] XYZ_D50_TO_SRGB = {
            3.1338561, -1.6168667, -0.4906146,
            -0.9787684, 1.9161415, 0.0334540,
            0.0719453, -0.2289914, 1.4052427};
    /** XYZ (D65) -> linear sRGB. */
    static final double[] XYZ_D65_TO_SRGB = {
            3.2404542, -1.5371385, -0.4985314,
            -0.9692660, 1.8760108, 0.0415560,
            0.0556434, -0.2040259, 1.0572252};
    static final double[] BRADFORD = {
            0.8951, 0.2664, -0.1614,
            -0.7502, 1.7135, 0.0367,
            0.0389, -0.0685, 1.0296};
    static final double[] D65_WHITE = {0.95047, 1.0, 1.08883};

    /** Correlated color temperature for an EXIF LightSource code (Camera2 reference illuminant). */
    public static double illuminantCct(int code) {
        switch (code) {
            case 1: return 5500;   // daylight
            case 2: return 4200;   // fluorescent
            case 3: return 2850;   // tungsten
            case 4: return 5500;   // flash
            case 9: return 5500;   // fine weather
            case 10: return 6500;  // cloudy
            case 11: return 7500;  // shade
            case 12: return 6430;  // daylight fluorescent
            case 13: return 5000;  // day white fluorescent
            case 14: return 4150;  // cool white fluorescent
            case 15: return 3450;  // white fluorescent
            case 17: return 2856;  // standard A
            case 18: return 4874;  // standard B
            case 19: return 6774;  // standard C
            case 20: return 5503;  // D55
            case 21: return 6504;  // D65
            case 22: return 7504;  // D75
            case 23: return 5003;  // D50
            case 24: return 3200;  // ISO studio tungsten
            default: return 5000;
        }
    }

    /** XYZ (Y = 1) of a Planckian (blackbody) white at the given temperature. */
    public static double[] whiteXyz(double t) {
        t = Stats.clamp(t, 1667, 25000);
        double x;
        if (t <= 4000) {
            x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
        } else {
            x = -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
        }
        double y;
        if (t <= 2222) {
            y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
        } else if (t <= 4000) {
            y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        } else {
            y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
        }
        return new double[]{x / y, 1.0, (1 - x - y) / y};
    }

    static double[] toD(float[] m) {
        if (m == null) return null;
        double[] r = new double[9];
        for (int i = 0; i < 9; i++) r[i] = m[i];
        return r;
    }

    static double[] mix(double[] a, double[] b, double g) {
        if (a == null) return b;
        if (b == null) return a;
        double[] r = new double[9];
        for (int i = 0; i < 9; i++) r[i] = g * a[i] + (1 - g) * b[i];
        return r;
    }

    static double[] bradford(double[] srcWhite, double[] dstWhite) {
        double[] s = Rot.mulVec(BRADFORD, srcWhite);
        double[] d = Rot.mulVec(BRADFORD, dstWhite);
        double[] inv = Rot.inverse(BRADFORD);
        return Rot.mul(inv, Rot.mul(Rot.diag(d[0] / s[0], d[1] / s[1], d[2] / s[2]), BRADFORD));
    }

    /** Fills spec.camToSrgb and spec.camNeutral for a white balance of {@code targetCct} kelvin. */
    public static void compute(SensorSpec spec, double targetCct) {
        spec.wbCct = (float) targetCct;
        double[] cm1 = toD(spec.colorMatrix1), cm2 = toD(spec.colorMatrix2);
        if (cm1 == null && cm2 == null) {
            spec.camNeutral = new float[]{0.5f, 1f, 0.65f};
            spec.camToSrgb = new float[]{2f, 0, 0, 0, 1f, 0, 0, 0, 1f / 0.65f};
            return;
        }
        double t1 = illuminantCct(spec.illuminant1), t2 = illuminantCct(spec.illuminant2);
        double g;
        if (cm1 == null) g = 0;
        else if (cm2 == null) g = 1;
        else if (Math.abs(1 / t1 - 1 / t2) < 1e-9) g = 0.5;
        else g = Stats.clamp((1 / targetCct - 1 / t2) / (1 / t1 - 1 / t2), 0, 1);

        double[] cm = mix(cm1, cm2, g);
        double[] cc = mix(toD(spec.calibration1), toD(spec.calibration2), g);
        if (cc == null) cc = Rot.identity();
        double[] fm = mix(toD(spec.forwardMatrix1), toD(spec.forwardMatrix2), g);

        double[] white = whiteXyz(targetCct);
        double[] xyzToCam = Rot.mul(cc, cm);
        double[] neutral = Rot.mulVec(xyzToCam, white);
        if (neutral[1] <= 0) neutral[1] = 1;
        for (int i = 0; i < 3; i++) neutral[i] = Math.max(1e-3, neutral[i] / neutral[1]);

        double[] camToSrgb;
        double[] ccInv = Rot.inverse(cc);
        if (fm != null && ccInv != null) {
            double[] ref = Rot.mulVec(ccInv, neutral);
            double[] d = Rot.diag(1 / ref[0], 1 / ref[1], 1 / ref[2]);
            double[] camToXyzD50 = Rot.mul(fm, Rot.mul(d, ccInv));
            camToSrgb = Rot.mul(XYZ_D50_TO_SRGB, camToXyzD50);
        } else {
            double[] camToXyz = Rot.inverse(xyzToCam);
            if (camToXyz == null) camToXyz = Rot.identity();
            camToSrgb = Rot.mul(XYZ_D65_TO_SRGB, Rot.mul(bradford(white, D65_WHITE), camToXyz));
        }
        // Force the chosen neutral to map exactly to (1, 1, 1).
        double[] w = Rot.mulVec(camToSrgb, neutral);
        float[] out = new float[9];
        for (int r = 0; r < 3; r++) {
            double s = Math.abs(w[r]) < 1e-6 ? 1 : 1 / w[r];
            for (int c = 0; c < 3; c++) out[r * 3 + c] = (float) (camToSrgb[r * 3 + c] * s);
        }
        spec.camToSrgb = out;
        spec.camNeutral = new float[]{(float) neutral[0], (float) neutral[1], (float) neutral[2]};
    }

    /**
     * Linear sRGB (white-balanced so that {@code wbCct} is (1, 1, 1)) of a blackbody at {@code t} kelvin,
     * as a well-calibrated camera would render it.
     */
    public static double[] blackbodyRgb(double t, double wbCct) {
        double[] white = whiteXyz(wbCct);
        double[] a = Rot.mul(XYZ_D65_TO_SRGB, bradford(white, D65_WHITE));
        double[] w = Rot.mulVec(a, white);
        double[] v = Rot.mulVec(a, whiteXyz(t));
        return new double[]{v[0] / w[0], v[1] / w[1], v[2] / w[2]};
    }
}
