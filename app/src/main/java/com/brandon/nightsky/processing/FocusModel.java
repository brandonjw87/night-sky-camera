package com.brandon.nightsky.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the app knows about where this camera focuses on the stars (F7): daylight calibrations (the sharpest
 * focus on something far away, with the battery's temperature then) and the focus found on the stars at night
 * (A5's "custom infinity"). Three daylight calibrations or more spanning at least 5 C give a straight line of
 * best focus against temperature, which centres and narrows the night's sweep. A lens's best focus moves with
 * temperature as its plastic elements and the voice coil holding them warm and cool.
 */
public final class FocusModel {
    public static final int MIN_POINTS = 3;
    public static final double MIN_SPAN_C = 5;
    static final int KEEP = 16;

    /** One calibration: where the focus was sharpest, and the temperature then. */
    public static final class Cal {
        public double focus;
        public double tempC = Double.NaN;
        public long timeMs;
        /** Night: the stars' HFR there and how fast they grew away from it (px per focus unit); NaN if unknown. */
        public double hfr = Double.NaN, slope = Double.NaN;
        /** Daylight: how much sharper the best focus was than the blurriest (a check on the target's detail). */
        public double contrast = Double.NaN;
    }

    /** Best focus = a + b * temperature, fitted to the daylight calibrations. */
    public static final class Line {
        public double a, b;
        /** Scatter of the calibrations about the line (focus units). */
        public double rms;
        public int n, dropped;
        public double tMin, tMax;

        public double predict(double tempC) {
            return a + b * tempC;
        }

        public String describe() {
            return String.format(Locale.US, "best focus %.4f %+.5f per C (%d points, %.0f to %.0f C, scatter %.4f%s)", a, b, n, tMin, tMax, rms,
                    dropped > 0 ? ", " + dropped + " left out" : "");
        }
    }

    /** Daylight calibrations and night results, oldest first. */
    public final List<Cal> day = new ArrayList<Cal>(), night = new ArrayList<Cal>();

    public void addDay(Cal c) {
        day.add(c);
        while (day.size() > KEEP) day.remove(0);
    }

    public void addNight(Cal c) {
        night.add(c);
        while (night.size() > KEEP) night.remove(0);
    }

    public Cal lastNight() {
        return night.isEmpty() ? null : night.get(night.size() - 1);
    }

    /** The daylight calibration nearest in temperature (the newest if the temperature is unknown), or null. */
    public Cal closestDay(double tempC) {
        Cal best = null;
        for (Cal c : day) {
            if (best == null || Double.isNaN(tempC) || Double.isNaN(best.tempC)) {
                best = c;
                continue;
            }
            if (!Double.isNaN(c.tempC) && Math.abs(c.tempC - tempC) <= Math.abs(best.tempC - tempC)) best = c;
        }
        return best;
    }

    /**
     * The line through the daylight calibrations, or null with fewer than three (with a temperature) or less
     * than 5 C between the coldest and warmest. With five or more, one that sits far off the others' line (a
     * target too close, a shaky hand) is left out.
     */
    public Line line() {
        List<Cal> use = new ArrayList<Cal>();
        for (Cal c : day) if (!Double.isNaN(c.tempC)) use.add(c);
        Line l = fit(use);
        if (l == null) return null;
        if (use.size() >= 5) {
            int worst = -1;
            double we = 0;
            for (int i = 0; i < use.size(); i++) {
                double e = Math.abs(use.get(i).focus - l.predict(use.get(i).tempC));
                if (e > we) {
                    we = e;
                    worst = i;
                }
            }
            List<Cal> rest = new ArrayList<Cal>(use);
            rest.remove(worst);
            Line l2 = fit(rest);
            if (l2 != null && we > 3 * Math.max(l2.rms, 1e-3)) {
                l2.dropped = 1;
                return l2;
            }
        }
        return l;
    }

    private static Line fit(List<Cal> cs) {
        int n = cs.size();
        if (n < MIN_POINTS) return null;
        double tMin = Double.MAX_VALUE, tMax = -Double.MAX_VALUE, st = 0, sf = 0;
        for (Cal c : cs) {
            tMin = Math.min(tMin, c.tempC);
            tMax = Math.max(tMax, c.tempC);
            st += c.tempC;
            sf += c.focus;
        }
        if (tMax - tMin < MIN_SPAN_C) return null;
        double tm = st / n, fm = sf / n, stt = 0, stf = 0;
        for (Cal c : cs) {
            stt += (c.tempC - tm) * (c.tempC - tm);
            stf += (c.tempC - tm) * (c.focus - fm);
        }
        Line l = new Line();
        l.b = stf / stt;
        l.a = fm - l.b * tm;
        double ss = 0;
        for (Cal c : cs) ss += Math.pow(c.focus - l.predict(c.tempC), 2);
        l.rms = Math.sqrt(ss / Math.max(1, n - 2));
        l.n = n;
        l.tMin = tMin;
        l.tMax = tMax;
        return l;
    }

    /** For the settings screen: what's known and what's still missing. */
    public String describe(double tempC) {
        StringBuilder sb = new StringBuilder();
        Cal last = lastNight();
        if (last != null) {
            sb.append(String.format(Locale.US, "Focus on the stars: %.3f, found %s%s.", last.focus, ago(last.timeMs),
                    Double.isNaN(last.tempC) ? "" : String.format(Locale.US, " at %.0f C", last.tempC)));
        } else {
            sb.append("Focus on the stars: not found yet (the first session sweeps for it, about 30 seconds).");
        }
        int n = day.size();
        Line l = line();
        if (n == 0) {
            sb.append(" Daylight calibration: none yet.");
        } else {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (Cal c : day) {
                if (Double.isNaN(c.tempC)) continue;
                lo = Math.min(lo, c.tempC);
                hi = Math.max(hi, c.tempC);
            }
            sb.append(String.format(Locale.US, " Daylight calibration: %d point%s", n, n == 1 ? "" : "s"));
            if (lo <= hi) sb.append(String.format(Locale.US, " (%.0f to %.0f C)", lo, hi));
            if (l != null) {
                sb.append(String.format(Locale.US, ": focus moves %+.4f per 10 C", 10 * l.b));
                if (!Double.isNaN(tempC)) sb.append(String.format(Locale.US, ", %.3f predicted at %.0f C now", l.predict(tempC), tempC));
                sb.append(".");
            } else if (n < MIN_POINTS) {
                sb.append(String.format(Locale.US, ". %d more at other temperatures and the night focus gets quicker.", MIN_POINTS - n));
            } else {
                sb.append(String.format(Locale.US, ". One at a temperature at least %.0f C away from these and the night focus gets quicker.",
                        MIN_SPAN_C));
            }
        }
        return sb.toString();
    }

    public static String ago(long ms) {
        if (ms <= 0) return "earlier";
        long d = (System.currentTimeMillis() - ms) / 86_400_000L;
        if (d <= 0) return "today";
        if (d == 1) return "yesterday";
        return d + " days ago";
    }

    // ---- storage: one record per line, "d" (daylight) or "n" (night) and its numbers ----

    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Cal c : day) line(sb, 'd', c);
        for (Cal c : night) line(sb, 'n', c);
        return sb.toString();
    }

    private static void line(StringBuilder sb, char kind, Cal c) {
        sb.append(kind).append(',').append(String.format(Locale.US, "%.5f,%.2f,%d,%.4f,%.4f,%.4f", c.focus, c.tempC, c.timeMs, c.hfr, c.slope,
                c.contrast)).append('\n');
    }

    public static FocusModel parse(String s) {
        FocusModel m = new FocusModel();
        if (s == null) return m;
        for (String ln : s.split("\n")) {
            String[] p = ln.trim().split(",");
            if (p.length < 7) continue;
            try {
                Cal c = new Cal();
                c.focus = Double.parseDouble(p[1]);
                c.tempC = Double.parseDouble(p[2]);
                c.timeMs = Long.parseLong(p[3]);
                c.hfr = Double.parseDouble(p[4]);
                c.slope = Double.parseDouble(p[5]);
                c.contrast = Double.parseDouble(p[6]);
                if (Double.isNaN(c.focus) || c.focus < 0) continue;
                if ("d".equals(p[0])) m.day.add(c);
                else if ("n".equals(p[0])) m.night.add(c);
            } catch (NumberFormatException ignored) {
            }
        }
        while (m.day.size() > KEEP) m.day.remove(0);
        while (m.night.size() > KEEP) m.night.remove(0);
        return m;
    }
}
