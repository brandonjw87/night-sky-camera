package com.brandon.nightsky.processing;

import java.util.List;
import java.util.Locale;

/**
 * The Earth's turn as the frames measured it (2.2): the phone lies still on the turning Earth, so every frame's sky is
 * the reference frame's sky turned about one fixed axis, the celestial pole, by an angle that grows at one steady rate.
 * The stack's plans hold each frame's rotation ({@link SharpStacker.Plan#R}, reference bearings to the frame's) and the
 * time it was taken; this fits the axis (the principal direction of the rotation vectors) and the angle as a straight
 * line in time, so the sky's position at any moment of the session comes from two numbers and an axis.
 * <p>
 * From them: how fast the sky turned (degrees an hour; the Earth's sidereal rate is 15.041), the length of a day by the
 * stars that rate gives (23 h 56 min 4 s), where the celestial pole is in the camera's view, and, with the phone lying
 * flat (the frame's middle at the zenith), the latitude: the pole stands as high above the horizon as the place is far
 * from the equator.
 */
public final class SkyTurn {
    /** The Earth's rotation rate against the stars (rad/s). */
    public static final double SIDEREAL_RAD_S = 7.2921159e-5;
    /** Least span (s) and frames worth fitting a turn to. */
    static final double MIN_SPAN_S = 20;
    static final int MIN_FRAMES = 3;

    /** The axis the sky turns about at a positive rate (camera bearings): the south celestial pole. */
    public final double[] axis;
    /** Turn about {@link #axis}: angle(t) = angle0 + rate * (t - tMid), radians, t in the plans' seconds. */
    public final double rate, angle0, tMid;
    /** First and last frame's time (s), and how many frames the fit used. */
    public final double t0, t1;
    public final int frames;
    /** Root mean square of the frames' angles about the fitted line (radians), and the axis's spread (radians). */
    public final double rmsAngle, axisSpread;

    private SkyTurn(double[] axis, double rate, double angle0, double tMid, double t0, double t1, int frames, double rms, double spread) {
        this.axis = axis;
        this.rate = rate;
        this.angle0 = angle0;
        this.tMid = tMid;
        this.t0 = t0;
        this.t1 = t1;
        this.frames = frames;
        this.rmsAngle = rms;
        this.axisSpread = spread;
    }

    /**
     * The turn the plans measured, or null when there's too little to measure it on (fewer than {@link #MIN_FRAMES}
     * frames, less than {@link #MIN_SPAN_S} seconds, or no turn at all: the frames weren't lined up).
     */
    public static SkyTurn fit(List<SharpStacker.Plan> plans) {
        if (plans == null || plans.size() < MIN_FRAMES) return null;
        int n = plans.size();
        double[][] r = new double[n][];
        double[] t = new double[n];
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            SharpStacker.Plan p = plans.get(i);
            if (p.R == null) return null;
            r[i] = Rot.toRotVec(p.R);
            t[i] = p.t;
            lo = Math.min(lo, t[i]);
            hi = Math.max(hi, t[i]);
        }
        if (!(hi - lo >= MIN_SPAN_S)) return null;
        // the axis: the rotation vectors all lie along it (one sign or the other of the reference frame's)
        double[][] M = new double[3][3];
        for (double[] v : r) for (int a = 0; a < 3; a++) for (int b = 0; b < 3; b++) M[a][b] += v[a] * v[b];
        double[] d = new double[3];
        double[][] V = new double[3][3];
        Rot.jacobi(M, d, V);
        int best = 0;
        for (int k = 1; k < 3; k++) if (d[k] > d[best]) best = k;
        double[] ax = {V[0][best], V[1][best], V[2][best]};
        double norm = Math.sqrt(ax[0] * ax[0] + ax[1] * ax[1] + ax[2] * ax[2]);
        if (!(norm > 0)) return null;
        for (int k = 0; k < 3; k++) ax[k] /= norm;
        // the angle about it, a straight line in time
        double[] ang = new double[n];
        double st = 0, sa = 0;
        for (int i = 0; i < n; i++) {
            ang[i] = ax[0] * r[i][0] + ax[1] * r[i][1] + ax[2] * r[i][2];
            st += t[i];
            sa += ang[i];
        }
        double tm = st / n, am = sa / n, stt = 0, sta = 0;
        for (int i = 0; i < n; i++) {
            stt += (t[i] - tm) * (t[i] - tm);
            sta += (t[i] - tm) * (ang[i] - am);
        }
        if (!(stt > 0)) return null;
        double w = sta / stt;
        if (w < 0) {
            // turning the other way about this direction: the axis is the opposite one
            for (int k = 0; k < 3; k++) ax[k] = -ax[k];
            w = -w;
            am = -am;
            for (int i = 0; i < n; i++) ang[i] = -ang[i];
        }
        if (!(w > 0)) return null;
        double ss = 0, off = 0;
        for (int i = 0; i < n; i++) {
            double e = ang[i] - (am + w * (t[i] - tm));
            ss += e * e;
            // how far each frame's own axis is from the fitted one (only meaningful for frames turned a fair way)
            double len = Math.sqrt(r[i][0] * r[i][0] + r[i][1] * r[i][1] + r[i][2] * r[i][2]);
            if (len > 1e-4) {
                double c = Math.abs(ax[0] * r[i][0] + ax[1] * r[i][1] + ax[2] * r[i][2]) / len;
                off += Math.acos(Math.min(1, c)) * len;
            }
        }
        double totalLen = 0;
        for (double[] v : r) totalLen += Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new SkyTurn(ax, w, am, tm, lo, hi, n, Math.sqrt(ss / n), totalLen > 0 ? off / totalLen : 0);
    }

    /** The sky's rotation at time t: reference-frame bearings to the bearings at t. */
    public double[] at(double t) {
        double a = angle(t);
        return Rot.fromRotVec(axis[0] * a, axis[1] * a, axis[2] * a);
    }

    /** The turn about the axis at time t (radians), from the reference frame's. */
    public double angle(double t) {
        return angle0 + rate * (t - tMid);
    }

    /** How far the sky turned from the first frame to the last (degrees). */
    public double turnedDeg() {
        return Math.toDegrees(rate * (t1 - t0));
    }

    /** The turn's rate in degrees an hour. */
    public double degPerHour() {
        return Math.toDegrees(rate) * 3600;
    }

    /** The measured rate against the Earth's (1 = the same). */
    public double rateRatio() {
        return rate / SIDEREAL_RAD_S;
    }

    /** A day by the stars at the measured rate (seconds): one whole turn. */
    public double siderealDaySeconds() {
        return 2 * Math.PI / rate;
    }

    /** The north celestial pole's bearing in the camera (the axis turned the other way). */
    public double[] northPole() {
        return new double[]{-axis[0], -axis[1], -axis[2]};
    }

    /**
     * The latitude a flat-lying phone measures (degrees, north positive): 90 minus the north pole's angle from the
     * camera's axis, which then points at the zenith. Off by about the phone's own tilt.
     */
    public double latitudeDeg() {
        double[] np = northPole();
        double c = Math.max(-1, Math.min(1, np[2]));
        return 90 - Math.toDegrees(Math.acos(c));
    }

    /** The pole above the horizon (north in the northern hemisphere, south in the southern). */
    public double[] visiblePole() {
        return latitudeDeg() >= 0 ? northPole() : axis.clone();
    }

    /** "23 h 56 min 4 s" for a time in seconds. */
    public static String hms(double s) {
        long k = Math.round(s);
        long h = k / 3600, m = (k % 3600) / 60, sec = k % 60;
        return String.format(Locale.US, "%d h %d min %d s", h, m, sec);
    }

    /** For the log. */
    public String describe() {
        return String.format(Locale.US, "the sky turned %.3f deg in %.1f min about the pole at camera (%.4f, %.4f, %.4f): %.3f deg an hour "
                        + "(%.4f of the Earth's), a day by the stars of %s; latitude from it about %.1f deg; %d frames, %.1f arcsec rms about "
                        + "the line, the frames' axes %.2f deg apart", turnedDeg(), (t1 - t0) / 60, -axis[0], -axis[1], -axis[2], degPerHour(),
                rateRatio(), hms(siderealDaySeconds()), latitudeDeg(), frames, Math.toDegrees(rmsAngle) * 3600, Math.toDegrees(axisSpread));
    }

    /** What the Earth's turn showed, in words for the result screen (two or three sentences). */
    public String words() {
        double lat = latitudeDeg();
        String ns = lat >= 0 ? "north" : "south";
        String day = hms(siderealDaySeconds());
        double minutes = (t1 - t0) / 60;
        return String.format(Locale.US, "In %s the sky turned %.2f degrees: %.2f degrees an hour, so one whole turn of the Earth takes %s "
                        + "by these frames (the Earth's own: 23 h 56 min 4 s, four minutes short of a day by the Sun, as the Earth also "
                        + "goes round the Sun). The stars circle the %s celestial pole, which stands %.0f degrees above your horizon: "
                        + "you're about %.0f degrees %s of the equator.", minutes >= 1.5 ? String.format(Locale.US, "%.0f minutes", minutes)
                        : String.format(Locale.US, "%.0f seconds", t1 - t0), turnedDeg(), degPerHour(), day, ns, Math.abs(lat),
                Math.abs(lat), ns);
    }
}
