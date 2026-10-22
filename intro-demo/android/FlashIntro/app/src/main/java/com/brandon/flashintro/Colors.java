package com.brandon.flashintro;

/** The shooting bar's colours, in the intro's two looks. */
final class Colors {
    /** Night sky navy and the champagne gold of the letter bevels. */
    static final Colors STANDARD = new Colors(0xFF04060C, 0xED03050A, 0xB3101523, 0x3DBECCE8,
            0xFFEAEEF7, 0xFFA4AEC4, 0xFFE6C58A, 0xFFF8E6BD, 0xFFA7814A, 0xFF1A1205);
    /** Night Sky's red on black (its RED, RED_DIM and RED_DARK), so nothing on screen spoils dark-adapted eyes. */
    static final Colors NIGHT = new Colors(0xFF000000, 0xED000000, 0xB82A0807, 0x8C9A2A25,
            0xFFE53935, 0xFFB03A33, 0xFFE53935, 0xFFF0584D, 0xFF9A2A25, 0xFF2A0807);

    final int ground;                          // behind everything
    final int scrim;                           // behind the bar, fading to clear at its top
    final int glass;                           // the small round buttons and the look switch
    final int line;
    final int ink;
    final int muted;
    final int gold, goldHi, goldLo, goldInk;   // the shutter, and whatever is switched on

    private Colors(int ground, int scrim, int glass, int line, int ink, int muted, int gold, int goldHi, int goldLo, int goldInk) {
        this.ground = ground;
        this.scrim = scrim;
        this.glass = glass;
        this.line = line;
        this.ink = ink;
        this.muted = muted;
        this.gold = gold;
        this.goldHi = goldHi;
        this.goldLo = goldLo;
        this.goldInk = goldInk;
    }

    int scrimClear() {
        return scrim & 0x00FFFFFF;
    }

    /** {@code k} of the way from {@code a} to {@code b} (0 to 1), for the switch from one look to the other. */
    static Colors mix(Colors a, Colors b, float k) {
        return new Colors(mix(a.ground, b.ground, k), mix(a.scrim, b.scrim, k), mix(a.glass, b.glass, k),
                mix(a.line, b.line, k), mix(a.ink, b.ink, k), mix(a.muted, b.muted, k), mix(a.gold, b.gold, k),
                mix(a.goldHi, b.goldHi, k), mix(a.goldLo, b.goldLo, k), mix(a.goldInk, b.goldInk, k));
    }

    /** One colour {@code k} of the way to another, channel by channel (alpha too). */
    static int mix(int x, int y, float k) {
        int out = 0;
        for (int shift = 0; shift < 32; shift += 8) {
            int a = (x >>> shift) & 255, b = (y >>> shift) & 255;
            out |= Math.round(a + (b - a) * k) << shift;
        }
        return out;
    }
}
