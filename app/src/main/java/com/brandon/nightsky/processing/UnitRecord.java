package com.brandon.nightsky.processing;

import java.util.Locale;

/** What was captured for one stored unit (one frame, or the sum of a few short frames). */
public final class UnitRecord {
    public int index;
    /** Seconds from session start to the middle of the unit's exposure. */
    public double t;
    /** Total exposure of the unit in seconds. */
    public double exposure;
    public int frames = 1;
    public int iso;
    public float invScale = 1f;
    public final float[] black = new float[4];
    /** Largest gyroscope rate (rad/s) seen while the unit was exposed. */
    public float gyroMax;
    public boolean disturbed;
    public float focus;
    public long sensorTimestampNs;
    // live diagnostics
    public int stars;
    public boolean liveOk;
    public float rms, hfr, background, noise;

    public static final String HEADER = "index,t,exposure,frames,iso,invScale,b0,b1,b2,b3,gyroMax,disturbed,focus,ts,stars,liveOk,rms,hfr,bg,noise";

    public String toCsv() {
        return String.format(Locale.US, "%d,%.4f,%.4f,%d,%d,%.8f,%.3f,%.3f,%.3f,%.3f,%.5f,%d,%.4f,%d,%d,%d,%.3f,%.3f,%.3f,%.3f",
                index, t, exposure, frames, iso, invScale, black[0], black[1], black[2], black[3], gyroMax,
                disturbed ? 1 : 0, focus, sensorTimestampNs, stars, liveOk ? 1 : 0, rms, hfr, background, noise);
    }

    public static UnitRecord fromCsv(String line) {
        String[] p = line.trim().split(",");
        if (p.length < 20) return null;
        try {
            UnitRecord r = new UnitRecord();
            r.index = Integer.parseInt(p[0]);
            r.t = Double.parseDouble(p[1]);
            r.exposure = Double.parseDouble(p[2]);
            r.frames = Integer.parseInt(p[3]);
            r.iso = Integer.parseInt(p[4]);
            r.invScale = Float.parseFloat(p[5]);
            for (int i = 0; i < 4; i++) r.black[i] = Float.parseFloat(p[6 + i]);
            r.gyroMax = Float.parseFloat(p[10]);
            r.disturbed = Integer.parseInt(p[11]) != 0;
            r.focus = Float.parseFloat(p[12]);
            r.sensorTimestampNs = Long.parseLong(p[13]);
            r.stars = Integer.parseInt(p[14]);
            r.liveOk = Integer.parseInt(p[15]) != 0;
            r.rms = Float.parseFloat(p[16]);
            r.hfr = Float.parseFloat(p[17]);
            r.background = Float.parseFloat(p[18]);
            r.noise = Float.parseFloat(p[19]);
            return r;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
