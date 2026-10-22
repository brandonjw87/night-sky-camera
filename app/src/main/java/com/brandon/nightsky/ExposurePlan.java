package com.brandon.nightsky;

import java.util.Locale;

/**
 * How long each exposure is, at what ISO, how many there are, and how many are summed into one stored unit.
 * <p>
 * The exposure length comes from geometry: the longest exposure in which stars drift about 1.5 pixels
 * (t = drift / (sidereal rate x cos(declination) x focal length in pixels)). With the phone flat, the frame
 * centre is the zenith, whose declination is the latitude. Frames are then shortened only if the camera
 * cannot deliver that long, or if the sky is so bright the background would use up the sensor's range.
 */
final class ExposurePlan {
    static final double SIDEREAL_RAD_S = 7.2921e-5;
    static final double DRIFT_PX = 1.5;
    static final double DEFAULT_LATITUDE = 40;
    static final long SEC = 1_000_000_000L;

    long targetExpNs;
    long frameExpNs;
    int iso;
    int framesPerUnit = 1;
    int frames;
    final StringBuilder notes = new StringBuilder();

    /** Longest exposure (s) in which a star at declination decDeg drifts driftPx pixels. */
    static double trailFreeSeconds(double fPx, double decDeg, double driftPx) {
        double c = Math.max(0.15, Math.cos(Math.toRadians(decDeg)));
        return driftPx / (SIDEREAL_RAD_S * c * fPx);
    }

    /**
     * @param fPx            focal length in RAW pixels
     * @param latitude       degrees (the zenith's declination)
     * @param durationSec    session length
     * @param advertisedMaxNs camera's advertised longest exposure
     * @param verifiedNs     longest exposure seen delivered in full before (0 = unknown)
     * @param clampDeliveredNs what the camera delivered when it cut a request short before (0 = never)
     * @param isoStart       ISO to use if the sky is dark
     * @param isoMin         lowest ISO the camera offers
     * @param white          sensor white level (for how many frames fit in a 16-bit sum)
     */
    static ExposurePlan make(double fPx, double latitude, int durationSec, long advertisedMaxNs, long verifiedNs,
                             long clampDeliveredNs, int isoStart, int isoMin, float white) {
        ExposurePlan p = new ExposurePlan();
        double t = trailFreeSeconds(fPx, latitude, DRIFT_PX);
        t = Math.max(2.0, Math.min(16.0, t));
        p.targetExpNs = Math.round(t * SEC);
        p.frameExpNs = p.targetExpNs;
        p.iso = isoStart;
        p.notes.append(String.format(Locale.US, "trail-free %.1fs at lat %.0f (f=%.0fpx)", t, latitude, fPx));
        if (clampDeliveredNs > 0 && clampDeliveredNs < p.frameExpNs && verifiedNs < p.frameExpNs) {
            p.frameExpNs = Math.max(clampDeliveredNs, verifiedNs);
            p.notes.append(String.format(Locale.US, "; camera limit %.2fs", p.frameExpNs / 1e9));
        }
        p.notes.append(String.format(Locale.US, "; advertised max %.2fs", advertisedMaxNs / 1e9));
        p.finish(durationSec, white);
        return p;
    }

    /**
     * Adjusts the plan after the test frame.
     *
     * @param bgPerSec   sky background, DN above black per second, at ISO {@code probeIso}
     * @param range      white minus black
     * @param deliveredNs exposure the test frame actually got; if it was cut short, frames are limited to it
     * @param requestedNs exposure the test frame asked for
     */
    void adjust(double bgPerSec, int probeIso, float range, long requestedNs, long deliveredNs, int isoMin,
                int durationSec, float white) {
        if (deliveredNs > 0 && deliveredNs < requestedNs * 0.93) {
            frameExpNs = Math.min(frameExpNs, deliveredNs);
            notes.append(String.format(Locale.US, "; test frame cut to %.3fs", deliveredNs / 1e9));
        }
        if (bgPerSec > 0 && range > 0) {
            double target = 0.10 * range, max = 0.18 * range;
            double bg = bgPerSec * (frameExpNs / 1e9) * iso / (double) probeIso;
            if (bg > max) {
                int newIso = (int) Math.max(isoMin, Math.round(iso * target / bg));
                bg *= newIso / (double) iso;
                notes.append(String.format(Locale.US, "; bright sky: ISO %d -> %d", iso, newIso));
                iso = newIso;
                if (bg > max) {
                    long e = Math.max(SEC / 4, Math.round(frameExpNs * target / bg));
                    notes.append(String.format(Locale.US, ", frames %.2fs -> %.2fs", frameExpNs / 1e9, e / 1e9));
                    bg *= e / (double) frameExpNs;
                    frameExpNs = e;
                }
            }
            notes.append(String.format(Locale.US, "; sky %.1f DN/frame (%.1f%% of range)", bg, 100 * bg / range));
        }
        finish(durationSec, white);
    }

    /** The camera turned out to cut frames to deliveredNs. */
    void limitTo(long deliveredNs, int durationSec, float white) {
        frameExpNs = Math.min(frameExpNs, deliveredNs);
        notes.append(String.format(Locale.US, "; frames limited to %.3fs", deliveredNs / 1e9));
        finish(durationSec, white);
    }

    private void finish(int durationSec, float white) {
        int kMax = Math.max(1, (int) (65535f / Math.max(1f, white)));
        framesPerUnit = 1;
        if (frameExpNs < targetExpNs * 0.67) {
            framesPerUnit = (int) Math.max(1, Math.min(kMax, Math.round(targetExpNs / (double) frameExpNs)));
        }
        frames = (int) Math.max(1, Math.round(durationSec * 1e9 / frameExpNs));
    }

    /** A new length for the session (its end time came nearer while it waited to start, or it runs longer). */
    void setDuration(int durationSec) {
        frames = (int) Math.max(1, Math.round(durationSec * 1e9 / frameExpNs));
    }

    long unitExpNs() {
        return frameExpNs * framesPerUnit;
    }

    String describe() {
        return String.format(Locale.US, "%d frames of %.2fs at ISO %d%s (%s)", frames, frameExpNs / 1e9, iso,
                framesPerUnit > 1 ? ", " + framesPerUnit + " per unit" : "", notes);
    }
}
