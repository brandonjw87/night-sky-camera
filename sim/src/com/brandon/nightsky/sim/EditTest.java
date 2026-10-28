package com.brandon.nightsky.sim;

import com.brandon.nightsky.processing.FramePreview;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Renderer;

import java.util.Locale;
import java.util.Random;
import java.util.TreeSet;

/**
 * The edit screen's helpers (G3):
 * <ul>
 * <li>An edit keeps its stack's processing version until the stack changes; changing only how it's rendered keeps
 * the same stack; and what changed reads in a few words.</li>
 * <li>A frame shown for tapping stars: a tap a few pixels off a star lands on its centre, and a tap on empty sky
 * finds nothing.</li>
 * </ul>
 * Usage: EditTest
 */
public final class EditTest {
    private static int failures;

    public static void main(String[] args) {
        recipes();
        taps();
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }

    private static void recipes() {
        Recipe r0 = Recipe.single("20261022_2315", false);
        r0.pipeline = PostProcessor.PIPELINE - 1;
        Recipe r1 = r0.edit();
        check("an edit keeps its stack's processing version", r1.pipeline == r0.pipeline, "pipeline " + r1.pipeline);
        r1.stretch = 7;
        r1.crop = "16:9";
        r1.northUp = true;
        r1.ev = -0.5f;
        check("a render-only edit keeps the stack", r1.sameStack(r0), r1.stackJson());
        String c1 = r1.changes(r0);
        check("render changes in words", c1.equals("stretch 7, -0.5 EV, 16:9 crop, north up"), c1);
        Recipe r2 = r1.edit();
        TreeSet<Integer> inc = new TreeSet<Integer>();
        for (int i = 3; i <= 30; i++) if (i != 12) inc.add(i);
        r2.base().include = inc;
        r2.base().includeNote = "between 11:02 PM and 11:31 PM, 1 left out by hand";
        r2.reference = 17;
        r2.freezeGround = true;
        check("a frames edit changes the stack", !r2.sameStack(r1), "");
        r2.restacked();
        check("a changed stack is this version's", r2.pipeline == PostProcessor.PIPELINE && !r2.outdated(), "pipeline " + r2.pipeline);
        String c2 = r2.changes(r1);
        check("stack changes in words", c2.equals("frames between 11:02 PM and 11:31 PM, 1 left out by hand, frame 17 as the reference, "
                + "the ground kept still, the new processing"), c2);
        check("nothing changed reads as nothing", r2.edit().changes(r2).isEmpty(), r2.edit().changes(r2));
        // read back as written: the same stack key, the same words
        Recipe back = Recipe.fromJson(r2.toJson());
        check("an edit's recipe reads back", back.stackKey().equals(r2.stackKey()) && back.changes(r2).isEmpty(), back.changes(r2));

        // rendered before 1.4 (no rendering version written): made again from the same stack, only rendered anew
        Recipe cur = Recipe.single("20261022_2315", false);
        Recipe old = Recipe.fromJson(cur.toJson().replace("\"renderer\":" + Renderer.VERSION + ",", ""));
        check("a render from before 1.4 is the old rendering's", old.renderer == 1 && old.outdated() && !old.stackOutdated(),
                "renderer " + old.renderer);
        Recipe again = old.edit();
        again.renew();
        check("made again, it keeps its stack and takes the new rendering", again.sameStack(old) && !again.outdated()
                && again.changes(old).equals("the new processing"), again.changes(old));
        Recipe older = old.edit();
        older.pipeline = PostProcessor.PIPELINE - 1;
        older.renew();
        check("an older stack is made again too", !older.outdated() && older.pipeline == PostProcessor.PIPELINE, "pipeline " + older.pipeline);
    }

    private static void taps() {
        int W = 1200, H = 900;
        short[] raw = new short[W * H];
        Random rnd = new Random(5);
        float black = 64, sky = 180;
        // stars at sub-pixel places (sensor pixels), a Gaussian blur of sigma 1.4 px
        float[][] stars = {{301.3f, 222.7f, 9000}, {640.8f, 455.2f, 3000}, {900.5f, 700.4f, 1500}};
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                double v = sky;
                for (float[] s : stars) {
                    double dx = x + 0.5 - s[0], dy = y + 0.5 - s[1];
                    v += s[2] / (2 * Math.PI * 1.4 * 1.4) * Math.exp(-(dx * dx + dy * dy) / (2 * 1.4 * 1.4));
                }
                v += rnd.nextGaussian() * Math.sqrt(v);
                raw[y * W + x] = (short) Math.max(0, Math.min(65535, Math.round(v + black)));
            }
        }
        FramePreview fp = FramePreview.of(raw, W, H, 1f, new float[]{black, black, black, black});
        check("a frame's picture is half size", fp.w == W / 2 && fp.h == H / 2, fp.w + "x" + fp.h);
        for (float[] s : stars) {
            // a fingertip off by 5 picture pixels (10 on the sensor)
            float[] p = fp.snap(s[0] / 2 + 4, s[1] / 2 - 3, 12);
            double err = p == null ? 1e9 : Math.hypot(p[0] - s[0], p[1] - s[1]);
            check(String.format(Locale.US, "a tap lands on the star at (%.1f, %.1f)", s[0], s[1]), err < 0.5,
                    p == null ? "nothing found" : String.format(Locale.US, "(%.2f, %.2f), off by %.2f px", p[0], p[1], err));
        }
        check("a tap on empty sky finds nothing", fp.snap(100, 400, 12) == null, "");
    }

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failures++;
    }
}
