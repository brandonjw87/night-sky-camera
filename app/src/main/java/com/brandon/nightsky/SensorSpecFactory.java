package com.brandon.nightsky;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.BlackLevelPattern;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensShadingMap;
import android.os.Build;
import android.util.Pair;
import android.util.Size;
import android.util.SizeF;

import com.brandon.nightsky.processing.CameraModel;
import com.brandon.nightsky.processing.ColorMath;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.ShadingMap;

/**
 * Turns Camera2 metadata into the processing engine's {@link SensorSpec}: CFA layout, black and white
 * levels, colour matrices, noise model, lens intrinsics and distortion (for star alignment) and the lens
 * shading map (vignetting).
 * <p>
 * Camera2 describes the colour filter, black levels, noise and shading for the sensor's pixel array, and
 * the lens calibration for the pre-correction active array. The RAW buffer starts at one of those two, so
 * everything is shifted into RAW buffer coordinates here.
 */
final class SensorSpecFactory {
    private SensorSpecFactory() {}

    /** Geometry shared by the per-frame helpers below. */
    static final class Geometry {
        /** Pixel-array position of the RAW buffer's top-left pixel. */
        int ox, oy;
        /** RAW pixels per pixel-array pixel (1 unless the RAW buffer is a scaled readout). */
        double scale = 1;
        /** Pre-correction active array, in pixel-array coordinates. */
        Rect pre;
        /** Colour of each 2x2 position of the pixel array: 0 red, 1 green, 2 blue. */
        int[] arrangement = {0, 1, 1, 2};

        /** Index into Camera2's per-channel arrays (CFA reading order) for RAW plane p. */
        int channel(int p) {
            int ax = ((p & 1) + ox) & 1;
            int ay = ((p >> 1) + oy) & 1;
            return ay * 2 + ax;
        }
    }

    static Geometry geometry(CameraCharacteristics cc, Size raw) {
        Geometry g = new Geometry();
        Size pa = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        Rect pre = cc.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        if (pre == null) pre = cc.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        if (pre == null) pre = new Rect(0, 0, raw.getWidth(), raw.getHeight());
        g.pre = pre;
        if (pa != null && raw.getWidth() == pa.getWidth() && raw.getHeight() == pa.getHeight()) {
            g.ox = 0;
            g.oy = 0;
        } else if (raw.getWidth() == pre.width() && raw.getHeight() == pre.height()) {
            g.ox = pre.left;
            g.oy = pre.top;
        } else if (pa != null && pa.getWidth() > 0) {
            g.scale = raw.getWidth() / (double) pa.getWidth();
        }
        Integer arr = cc.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        int a = arr == null ? 0 : arr;
        switch (a) {
            case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG: g.arrangement = new int[]{1, 0, 2, 1}; break;
            case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG: g.arrangement = new int[]{1, 2, 0, 1}; break;
            case CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR: g.arrangement = new int[]{2, 1, 1, 0}; break;
            default: g.arrangement = new int[]{0, 1, 1, 2}; break;
        }
        return g;
    }

    /** Everything that is known before the first frame. */
    static SensorSpec build(CameraCharacteristics cc, String cameraId, Size raw, Geometry geo) {
        SensorSpec s = new SensorSpec();
        s.width = raw.getWidth();
        s.height = raw.getHeight();
        Integer arr = cc.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT);
        s.setCfaFromArrangement(arr == null ? 0 : arr, geo.ox, geo.oy);

        BlackLevelPattern bl = cc.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN);
        for (int p = 0; p < 4; p++) {
            s.black[p] = bl != null ? bl.getOffsetForIndex(((p & 1) + geo.ox) & 1, ((p >> 1) + geo.oy) & 1) : 64f;
        }
        Integer wl = cc.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL);
        s.white = wl != null ? wl : 1023;

        s.colorMatrix1 = mat(cc.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM1));
        s.colorMatrix2 = mat(cc.get(CameraCharacteristics.SENSOR_COLOR_TRANSFORM2));
        s.forwardMatrix1 = mat(cc.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX1));
        s.forwardMatrix2 = mat(cc.get(CameraCharacteristics.SENSOR_FORWARD_MATRIX2));
        s.calibration1 = mat(cc.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM1));
        s.calibration2 = mat(cc.get(CameraCharacteristics.SENSOR_CALIBRATION_TRANSFORM2));
        Integer il1 = cc.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT1);
        Byte il2 = cc.get(CameraCharacteristics.SENSOR_REFERENCE_ILLUMINANT2);
        s.illuminant1 = il1 == null ? 0 : il1;
        s.illuminant2 = il2 == null ? 0 : (il2 & 0xFF);

        s.deviceMake = Build.MANUFACTURER;
        s.deviceModel = Build.MODEL;
        s.cameraId = cameraId;
        Integer so = cc.get(CameraCharacteristics.SENSOR_ORIENTATION);
        s.sensorOrientation = so == null ? 90 : so;
        float[] fl = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        s.focalLengthMm = fl != null && fl.length > 0 ? fl[0] : 0f;
        float[] ap = cc.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        s.fNumber = ap != null && ap.length > 0 ? ap[0] : 0f;
        SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pa = cc.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (phys != null && pa != null && pa.getWidth() > 0) {
            s.pixelPitchUm = (float) (phys.getWidth() / pa.getWidth() * 1000.0 / geo.scale);
        }
        s.model = model(cc, raw, geo, s);
        ColorMath.compute(s, ColorMath.DEFAULT_CCT);
        return s;
    }

    /** Lens intrinsics and distortion in RAW coordinates; a nominal pinhole if the camera has no calibration. */
    static CameraModel model(CameraCharacteristics cc, Size raw, Geometry geo, SensorSpec s) {
        float[] k = cc.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        CameraModel m;
        if (k != null && k.length >= 5 && k[0] > 100 && k[1] > 100) {
            m = new CameraModel();
            m.width = raw.getWidth();
            m.height = raw.getHeight();
            double sc = geo.scale;
            m.fx = k[0] * sc;
            m.fy = k[1] * sc;
            m.cx = (k[2] + geo.pre.left - geo.ox) * sc;
            m.cy = (k[3] + geo.pre.top - geo.oy) * sc;
            m.skew = k[4] * sc;
            m.source = "camera calibration";
            // a calibration that puts the optical centre off the sensor is not believable
            if (m.cx < 0.2 * m.width || m.cx > 0.8 * m.width || m.cy < 0.2 * m.height || m.cy > 0.8 * m.height) m = null;
        } else {
            m = null;
        }
        if (m == null) {
            SizeF phys = cc.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
            double f;
            if (phys != null && phys.getWidth() > 0 && s.focalLengthMm > 0) {
                f = s.focalLengthMm / phys.getWidth() * raw.getWidth();
            } else {
                f = raw.getWidth() / (2 * Math.tan(Math.toRadians(35)));
            }
            m = CameraModel.nominal(raw.getWidth(), raw.getHeight(), f);
            m.source = "focal length";
            return m;
        }
        float[] d = cc.get(CameraCharacteristics.LENS_DISTORTION);
        if (d != null && d.length >= 5) {
            m.k1 = d[0];
            m.k2 = d[1];
            m.k3 = d[2];
            m.p1 = d[3];
            m.p2 = d[4];
        }
        return m;
    }

    /** Per-capture metadata: black and white levels, noise model and the lens shading map. */
    static void applyResult(SensorSpec s, CaptureResult r, Geometry geo) {
        float[] black = dynamicBlack(r, geo);
        if (black != null) System.arraycopy(black, 0, s.black, 0, 4);
        Integer wl = r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL);
        if (wl != null && wl > 0) s.white = wl;
        Pair<Double, Double>[] np = r.get(CaptureResult.SENSOR_NOISE_PROFILE);
        if (np != null && np.length >= 4) {
            for (int p = 0; p < 4; p++) {
                Pair<Double, Double> q = np[geo.channel(p)];
                s.noiseS[p] = q.first.floatValue();
                s.noiseO[p] = q.second.floatValue();
            }
        }
        LensShadingMap lsm = r.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP);
        if (lsm != null && lsm.getColumnCount() >= 2 && lsm.getRowCount() >= 2) {
            int cols = lsm.getColumnCount(), rows = lsm.getRowCount();
            float[] gains = new float[4 * cols * rows];
            for (int p = 0; p < 4; p++) {
                int ch = geo.channel(p);
                int colour = geo.arrangement[ch];
                // RggbChannelVector order: red, green on even rows, green on odd rows, blue
                int idx = colour == 0 ? 0 : (colour == 2 ? 3 : (ch < 2 ? 1 : 2));
                for (int y = 0; y < rows; y++) {
                    for (int x = 0; x < cols; x++) {
                        float v = lsm.getGainFactor(idx, x, y);
                        gains[(p * rows + y) * cols + x] = Float.isNaN(v) || v < 0.5f ? 1f : v;
                    }
                }
            }
            double sc = geo.scale;
            double x0 = (geo.pre.left - geo.ox + 0.5) * sc;
            double y0 = (geo.pre.top - geo.oy + 0.5) * sc;
            double x1 = (geo.pre.left - geo.ox + geo.pre.width() - 0.5) * sc;
            double y1 = (geo.pre.top - geo.oy + geo.pre.height() - 0.5) * sc;
            s.shading = new ShadingMap(cols, rows, gains, x0, y0, x1, y1);
        }
    }

    /** The capture's black level for each RAW plane, or null if the camera does not report one per frame. */
    static float[] dynamicBlack(CaptureResult r, Geometry geo) {
        float[] d = r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (d == null || d.length < 4) return null;
        float[] out = new float[4];
        for (int p = 0; p < 4; p++) out[p] = d[geo.channel(p)];
        return out;
    }

    private static float[] mat(ColorSpaceTransform t) {
        if (t == null) return null;
        float[] m = new float[9];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) m[r * 3 + c] = t.getElement(c, r).floatValue();
        }
        return m;
    }
}
