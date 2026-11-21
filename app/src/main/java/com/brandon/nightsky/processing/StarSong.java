package com.brandon.nightsky.processing;

import com.brandon.nightsky.remote.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Hear the Stars (2.3): a photo played as music. A line sweeps across the picture from left to right, and each star it
 * passes rings a note: blue (hot) stars high, red (cool) ones low, the brighter the louder and the longer, on a
 * pentatonic scale so that any sky sounds like a tune. Planets play a warm organ note, the Moon a deep gong, galaxies and
 * nebulae a soft hum, and stars named by you a sparkle; a quiet drone swells where the Milky Way's middle line goes by.
 * <p>
 * The notes ({@link #notes}) are what the phone plays and puts in the song's video ({@link #render}, 16-bit stereo), and
 * what Mission Control's page plays with the browser's own synthesizer ({@link #json}): the same song in both places.
 */
public final class StarSong {
    /** How long the sweep takes (seconds), and the quiet after it. */
    public static final double SWEEP_S = 24, TAIL_S = 3;
    public static final int RATE = 44100;
    /** Most stars that play (the brightest), and the faintest that may. */
    static final int MAX_STARS = 140;
    static final double MAX_MAG = 6.0;
    /** The scale: a major pentatonic (semitones above the root), over three octaves from the root. */
    static final int[] PENTATONIC = {0, 2, 4, 7, 9};
    static final int OCTAVES = 3;
    /** The lowest note's frequency (C3). */
    static final double ROOT_HZ = 130.8128;
    /** B-V of the bluest and the reddest stars (the ends of the scale). */
    static final double BV_BLUE = -0.3, BV_RED = 1.8;

    /** One note. */
    public static final class Note {
        /** When it starts (seconds), its frequency (Hz), loudness (0-1), place left to right (-1 to 1), length (s). */
        public double t, hz, gain, pan, dur;
        /** "star", "planet", "moon", "object", "mine", "milky". */
        public String kind = "star";
        public String name = "";
        /** Where it is in the picture (shares of the crop), and its colour (B-V, NaN if unknown). */
        public double u, v, bv = Double.NaN, mag = Double.NaN;
    }

    private StarSong() {}

    /**
     * The song of the things in a picture ({@link SkyMap#things}; their u is when they play) and the Milky Way's middle
     * line through it (shares of the crop: [u, v] pairs, may be null), in the order they play.
     */
    public static List<Note> notes(List<SkyMap.Thing> things, double[][] milky) {
        List<Note> out = new ArrayList<Note>();
        // the brightest stars only: a few hundred at once would be a hiss
        List<SkyMap.Thing> stars = new ArrayList<SkyMap.Thing>();
        for (SkyMap.Thing t : things) if ("star".equals(t.kind) && !(t.mag > MAX_MAG)) stars.add(t);
        Collections.sort(stars, new Comparator<SkyMap.Thing>() {
            @Override
            public int compare(SkyMap.Thing a, SkyMap.Thing b) {
                return Double.compare(a.mag, b.mag);
            }
        });
        if (stars.size() > MAX_STARS) stars = stars.subList(0, MAX_STARS);
        double brightest = stars.isEmpty() ? 0 : stars.get(0).mag;
        for (SkyMap.Thing t : stars) {
            Note n = base(t);
            n.hz = pitch(t.bv);
            // loudness as the root of the light (how the ear takes it), the brightest star at full
            n.gain = Math.max(0.06, Math.min(1, Math.pow(10, -0.2 * (t.mag - brightest))));
            n.dur = 0.5 + 2.0 * n.gain;
            out.add(n);
        }
        for (SkyMap.Thing t : things) {
            if ("star".equals(t.kind)) continue;
            Note n = base(t);
            if ("planet".equals(t.kind)) {
                n.hz = ROOT_HZ * 2;
                n.gain = 0.8;
                n.dur = 3.0;
            } else if ("moon".equals(t.kind)) {
                n.hz = ROOT_HZ / 2;
                n.gain = 1.0;
                n.dur = 5.0;
            } else if ("object".equals(t.kind)) {
                n.hz = ROOT_HZ * semis(t.objectKind == 'G' ? 7 : 4);
                n.gain = 0.35;
                n.dur = 4.0;
            } else {
                // a star named by you: its own colour's note, as a sparkle
                n.hz = pitch(t.bv) * 2;
                n.gain = 0.7;
                n.dur = 1.6;
            }
            out.add(n);
        }
        if (milky != null) {
            // where the Milky Way's middle line goes by: a swell of the drone, as long as the line takes to cross
            double su = 0, sv = 0, lo = 1, hi = 0;
            int k = 0;
            for (double[] p : milky) {
                if (p == null || p.length < 2 || p[0] < 0 || p[0] > 1) continue;
                su += p[0];
                sv += p[1];
                lo = Math.min(lo, p[0]);
                hi = Math.max(hi, p[0]);
                k++;
            }
            if (k > 0) {
                Note n = new Note();
                n.kind = "milky";
                n.name = "the Milky Way";
                n.u = su / k;
                n.v = sv / k;
                n.t = Math.max(0, lo * SWEEP_S - 1);
                n.hz = ROOT_HZ;
                n.gain = 0.25;
                n.dur = 2.5 + (hi - lo) * SWEEP_S;
                n.pan = 0;
                out.add(n);
            }
        }
        Collections.sort(out, new Comparator<Note>() {
            @Override
            public int compare(Note a, Note b) {
                return Double.compare(a.t, b.t);
            }
        });
        return out;
    }

    private static Note base(SkyMap.Thing t) {
        Note n = new Note();
        n.kind = t.kind;
        n.name = t.name == null ? "" : t.name;
        n.u = t.u;
        n.v = t.v;
        n.bv = t.bv;
        n.mag = t.mag;
        n.t = Math.max(0, Math.min(1, t.u)) * SWEEP_S;
        n.pan = Math.max(-1, Math.min(1, (t.v - 0.5) * 1.6));
        return n;
    }

    /** A star's note from its colour: the scale's step, blue at the top; a star of unknown colour as the Sun (B-V 0.65). */
    static double pitch(double bv) {
        if (!(bv == bv)) bv = 0.65;
        double x = (BV_RED - Math.max(BV_BLUE, Math.min(BV_RED, bv))) / (BV_RED - BV_BLUE);
        int steps = PENTATONIC.length * OCTAVES;
        int k = (int) Math.round(x * (steps - 1));
        int semis = 12 * (k / PENTATONIC.length) + PENTATONIC[k % PENTATONIC.length];
        return ROOT_HZ * semis(semis);
    }

    static double semis(int s) {
        return Math.pow(2, s / 12.0);
    }

    /** The song's length (seconds). */
    public static double seconds() {
        return SWEEP_S + TAIL_S;
    }

    /** The notes as JSON for the page: {"sweep": s, "tail": s, "notes": [[t, hz, gain, pan, dur, kind, u, v, name], ...]}. */
    public static String json(List<Note> notes) {
        Json j = new Json().obj().put("sweep", SWEEP_S).put("tail", TAIL_S).put("root", ROOT_HZ, 7).arr("notes");
        for (Note n : notes) {
            j.arr().val(n.t, 5).val(n.hz, 6).val(n.gain, 3).val(n.pan, 3).val(n.dur, 3).val(n.kind).val(n.u, 4).val(n.v, 4)
                    .val(n.name).end();
        }
        return j.end().end().toString();
    }

    /**
     * The song as 16-bit stereo samples (left, right, ...) at {@link #RATE}: each note's voice, then the drone, then the
     * whole kept just under full scale.
     */
    public static short[] render(List<Note> notes) {
        int total = (int) Math.ceil(seconds() * RATE);
        float[] l = new float[total], r = new float[total];
        for (Note n : notes) voice(n, l, r);
        // the drone: the scale's root and fifth, very quiet, a little louder through the Milky Way
        double milkyT = Double.NaN, milkyW = 1.5;
        for (Note n : notes) {
            if ("milky".equals(n.kind)) {
                milkyT = n.t + n.dur / 2;
                milkyW = Math.max(1.5, n.dur / 2);
            }
        }
        double fifth = ROOT_HZ * semis(7) / 2, root = ROOT_HZ / 2;
        for (int i = 0; i < total; i++) {
            double t = i / (double) RATE;
            double env = Math.min(1, t / 2) * Math.min(1, Math.max(0, (seconds() - t) / 2));
            double swell = milkyT == milkyT ? 1 + 2.5 * Math.exp(-Math.pow((t - milkyT) / milkyW, 2)) : 1;
            double d = 0.025 * env * swell * (Math.sin(2 * Math.PI * root * t) + 0.6 * Math.sin(2 * Math.PI * fifth * t + 0.3 * Math.sin(0.4 * t)));
            l[i] += d;
            r[i] += d;
        }
        float peak = 1e-6f;
        for (int i = 0; i < total; i++) peak = Math.max(peak, Math.max(Math.abs(l[i]), Math.abs(r[i])));
        // soft limit to keep a crowd of notes from clipping, then scale to just under full
        float k = 0.89f / Math.max(peak, 0.35f);
        short[] out = new short[2 * total];
        for (int i = 0; i < total; i++) {
            out[2 * i] = (short) Math.round(32767 * soft(l[i] * k));
            out[2 * i + 1] = (short) Math.round(32767 * soft(r[i] * k));
        }
        return out;
    }

    private static float soft(float x) {
        return x > 0.9f ? 0.9f + 0.1f * (float) Math.tanh((x - 0.9f) / 0.1f) : x < -0.9f ? -0.9f + 0.1f * (float) Math.tanh((x + 0.9f) / 0.1f) : x;
    }

    /** One note into the buffers: its kind's timbre, its envelope, its place between the speakers. */
    private static void voice(Note n, float[] l, float[] r) {
        int i0 = (int) Math.round(n.t * RATE), len = (int) Math.round(n.dur * RATE);
        double pl = Math.cos((n.pan + 1) * Math.PI / 4), pr = Math.sin((n.pan + 1) * Math.PI / 4);
        double g = 0.22 * n.gain;
        double w = 2 * Math.PI * n.hz / RATE;
        for (int k = 0; k < len && i0 + k < l.length; k++) {
            double t = k / (double) RATE;
            double a = Math.min(1, t / 0.004);
            double s;
            if ("planet".equals(n.kind)) {
                // an organ: the note and its octave, swelling in and fading out slowly
                double env = Math.min(1, t / 0.25) * Math.exp(-t / (0.45 * n.dur));
                s = env * (Math.sin(w * k) + 0.5 * Math.sin(2 * w * k) + 0.25 * Math.sin(3 * w * k));
            } else if ("moon".equals(n.kind)) {
                // a gong: inharmonic partials, a slow beat, a long fade
                double env = a * Math.exp(-t / (0.35 * n.dur));
                s = env * (Math.sin(w * k) + 0.6 * Math.sin(2.76 * w * k) * (1 + 0.3 * Math.sin(2 * Math.PI * 1.3 * t))
                        + 0.35 * Math.sin(5.4 * w * k));
            } else if ("object".equals(n.kind) || "milky".equals(n.kind)) {
                // a hum: two voices slightly apart, a slow swell
                double env = Math.sin(Math.PI * Math.min(1, t / n.dur));
                s = 0.8 * env * (Math.sin(w * k) + Math.sin(w * 1.006 * k));
            } else if ("mine".equals(n.kind)) {
                // a sparkle: the note, its fifth and octave in quick turns
                double step = t < 0.12 ? 1 : t < 0.24 ? 1.5 : 2;
                double env = a * Math.exp(-t / 0.5);
                s = env * Math.sin(w * step * k);
            } else {
                // a star: a bell, its overtones dying first
                double env = a * Math.exp(-t / (0.32 * n.dur));
                s = env * (Math.sin(w * k) + 0.35 * Math.exp(-t / 0.2) * Math.sin(2.0 * w * k) + 0.15 * Math.exp(-t / 0.1) * Math.sin(3.01 * w * k));
            }
            float v = (float) (g * s);
            l[i0 + k] += v * pl;
            r[i0 + k] += v * pr;
        }
    }

    /** What the song is, for the log. */
    public static String describe(List<Note> notes) {
        int stars = 0, other = 0;
        for (Note n : notes) {
            if ("star".equals(n.kind)) stars++;
            else other++;
        }
        return String.format(Locale.US, "%d stars and %d other things, %.0f s", stars, other, seconds());
    }
}
