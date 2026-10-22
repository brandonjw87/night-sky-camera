package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Satellite tracks to draw over the picture as dashed lines: each satellite's predicted path through the
 * frames it crossed, joined up, in stack pixels, with where its name goes. The ones the picture confirmed (a
 * streak was named after them) are told apart from the ones only predicted.
 */
public final class SatOverlay {
    private SatOverlay() {}

    /** Predicted but unseen tracks are drawn down to this magnitude (a streak would have been expected). */
    public static final double UNSEEN_MAG = 4.5;

    /** One satellite's track. */
    public static final class Line {
        public final String name;
        public final int norad;
        /** Whether a streak in the picture was named after it. */
        public boolean seen;
        /** Brightest predicted magnitude while crossing. */
        public float mag = 99;
        /** Polylines, x and y in turn (stack pixels): a break wherever it was unlit or out of sight. */
        public final List<float[]> parts = new ArrayList<float[]>();
        /** Where its name goes (stack pixels): the middle of its longest stretch in the picture. */
        public float labelX = Float.NaN, labelY = Float.NaN;
        /** The direction of the track there (unit vector), for setting the name beside it. */
        public float dirX = 1, dirY;

        Line(String name, int norad) {
            this.name = name;
            this.norad = norad;
        }
    }

    /**
     * The tracks worth drawing over the crop {@code x0, y0, w, h} (stack pixels): the confirmed ones, then the
     * predicted ones brighter than {@link #UNSEEN_MAG}; brightest first. A confirmed track is drawn where its
     * streaks were found (the prediction moved across and along by what the fit measured), in the frames
     * without a streak by the satellite's mean offset: the elements' error is the same through a session.
     */
    public static List<Line> lines(List<SatPredict.Track> tracks, SatMatch.Result streaks, int x0, int y0, int w, int h) {
        List<Line> out = new ArrayList<Line>();
        if (tracks == null || tracks.isEmpty()) return out;
        java.util.Set<Integer> seen = new java.util.HashSet<Integer>();
        // measured offsets: per satellite and frame, and per satellite on average {across px, shift in samples}
        Map<Long, double[]> fitOf = new java.util.HashMap<Long, double[]>();
        Map<Integer, double[]> meanOf = new java.util.HashMap<Integer, double[]>();
        if (streaks != null) {
            for (SatMatch.Streak s : streaks.streaks) {
                if (s.sat == null) continue;
                seen.add(s.sat.norad);
                double shift = s.alongSec / SatPredict.FINE_S;
                fitOf.put(key(s.sat.norad, s.unit), new double[]{s.across, shift});
                double[] m = meanOf.get(s.sat.norad);
                if (m == null) meanOf.put(s.sat.norad, m = new double[3]);
                m[0] += s.across;
                m[1] += shift;
                m[2]++;
            }
        }
        List<SatPredict.Track> sorted = new ArrayList<SatPredict.Track>(tracks);
        Collections.sort(sorted, new Comparator<SatPredict.Track>() {
            @Override
            public int compare(SatPredict.Track a, SatPredict.Track b) {
                return Double.compare(a.jd0, b.jd0);
            }
        });
        Map<Integer, Line> byId = new LinkedHashMap<Integer, Line>();
        for (SatPredict.Track t : sorted) {
            boolean isSeen = seen.contains(t.sat.norad);
            float m = t.brightest();
            if (!isSeen && !(m <= UNSEEN_MAG)) continue;
            Line l = byId.get(t.sat.norad);
            if (l == null) {
                l = new Line(t.sat.displayName(), t.sat.norad);
                byId.put(t.sat.norad, l);
            }
            l.seen |= isSeen;
            l.mag = Math.min(l.mag, m);
            double across = 0;
            int shift = 0;
            double[] fit = fitOf.get(key(t.sat.norad, t.unit)), mean = meanOf.get(t.sat.norad);
            if (fit != null) {
                across = fit[0];
                shift = (int) Math.round(fit[1]);
            } else if (mean != null) {
                across = mean[0] / mean[2];
                shift = (int) Math.round(mean[1] / mean[2]);
            }
            int a = Math.max(0, t.in0 + shift), b = Math.min(t.jd.length - 1, t.in1 + shift);
            float[] buf = new float[2 * Math.max(0, b - a + 1)];
            int n = 0;
            for (int i = a; i <= b; i++) {
                if (Float.isNaN(t.x[i])) {
                    if (n >= 4) l.parts.add(java.util.Arrays.copyOf(buf, n));
                    n = 0;
                    continue;
                }
                // across: to the left of the direction of travel, as the fit measures it
                int j0 = i + 1 <= b && !Float.isNaN(t.x[i + 1]) ? i : i - 1, j1 = j0 + 1;
                double nx = 0, ny = 0;
                if (j0 >= a && j1 <= b && !Float.isNaN(t.x[j0]) && !Float.isNaN(t.x[j1])) {
                    double dx = t.x[j1] - t.x[j0], dy = t.y[j1] - t.y[j0], len = Math.hypot(dx, dy);
                    if (len > 1e-6) {
                        nx = -dy / len;
                        ny = dx / len;
                    }
                }
                buf[n++] = (float) (t.x[i] + nx * across);
                buf[n++] = (float) (t.y[i] + ny * across);
            }
            if (n >= 4) l.parts.add(java.util.Arrays.copyOf(buf, n));
        }
        for (Line l : byId.values()) {
            if (l.parts.isEmpty()) continue;
            place(l, x0, y0, w, h);
            if (!Float.isNaN(l.labelX)) out.add(l);
        }
        Collections.sort(out, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                if (a.seen != b.seen) return a.seen ? -1 : 1;
                return Float.compare(a.mag, b.mag);
            }
        });
        return out;
    }

    private static long key(int norad, int unit) {
        return ((long) norad << 32) | (unit & 0xffffffffL);
    }

    /** The label at the middle of the longest run of the track inside the crop (none if it never enters it). */
    static void place(Line l, int x0, int y0, int w, int h) {
        float bestLen = 0;
        for (float[] p : l.parts) {
            // runs of consecutive points inside the crop
            int a = -1;
            for (int i = 0; i <= p.length / 2; i++) {
                boolean in = i < p.length / 2 && p[2 * i] >= x0 && p[2 * i + 1] >= y0 && p[2 * i] < x0 + w && p[2 * i + 1] < y0 + h;
                if (in && a < 0) a = i;
                if (!in && a >= 0) {
                    int b = i - 1;
                    float len = (float) Math.hypot(p[2 * b] - p[2 * a], p[2 * b + 1] - p[2 * a + 1]);
                    if (len > bestLen && b > a) {
                        bestLen = len;
                        int m = (a + b) / 2, m2 = Math.min(b, m + 1), m1 = m2 == m ? Math.max(a, m - 1) : m;
                        l.labelX = p[2 * m];
                        l.labelY = p[2 * m + 1];
                        float dx = p[2 * m2] - p[2 * m1], dy = p[2 * m2 + 1] - p[2 * m1 + 1], d = (float) Math.hypot(dx, dy);
                        if (d > 0) {
                            l.dirX = dx / d;
                            l.dirY = dy / d;
                        }
                    }
                    a = -1;
                }
            }
        }
    }
}
