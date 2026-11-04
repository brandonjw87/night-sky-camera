package com.brandon.nightsky;

import android.app.Application;

import com.brandon.nightsky.processing.EdgeMask;
import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.MotionPhoto;
import com.brandon.nightsky.processing.PostProcessor;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.SessionStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * The edit flow through the session controller, off the phone (G3): open a kept session, save edits that only change
 * how it's rendered and ones that stack it again, reuse a kept stack, make a render whose stack was deleted again,
 * cancel a re-stack, and the offer of the new processing: stacked again for an older stack, only rendered again for a
 * render from before 1.4.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36)
public class EditFlow {
    static int failures;
    static final List<SessionController.Status> seen = new ArrayList<SessionController.Status>();

    @Test
    public void flow() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(new android.content.ComponentName(app, CaptureService.class));
        final File root = SessionController.sessionsRoot(app);
        Shots.copy(new File(System.getProperty("shots.sessions")), root);
        // the photo's video (1.8): what's saved lands in files to read back, and ffmpeg stands in for the phone's encoder
        File out = new File(System.getProperty("shots.out", "build/robo"), "editflow");
        Monkey.out = out;
        FakeMediaStore.install(new File(out, "media"));
        Mp4Writer.factory = FfmpegVideo.FACTORY;
        final File dir = SessionStore.list(root).get(0).dir;
        final SessionController c = new SessionController(app, new Prefs(app), new SessionController.Host() {
            @Override
            public void onStatus(SessionController.Status s) {
                synchronized (seen) {
                    seen.add(s);
                }
            }

            @Override
            public void onCaptureEnded() {
            }

            @Override
            public void onIdle() {
            }
        });
        long t0 = System.currentTimeMillis();

        // 1. the session's latest photo, from its master
        SessionController.Status s = await(new Runnable() {
            @Override
            public void run() {
                c.openSession(dir);
            }
        }, 120);
        check("the latest render opens", s.phase == SessionController.Phase.DONE && s.result != null && s.result.recipe != null
                && s.result.recipe.number == 4, words(s));

        // 2. what the edit screen needs
        final SessionController.EditInfo in = info(c);
        check("the edit screen's frames, stack and renders", in.frames.length == 16 && in.noRestack.isEmpty() && in.painting != null
                && in.painting.isBlank() && in.renders.size() == 4 && in.was.number == 4, in.frames.length + " frames, noRestack '"
                + in.noRestack + "', renders " + in.renders.size() + ", reference " + in.referenceUnit + ", auto ground "
                + (in.autoGround == null ? "none" : EdgeMask.count(in.autoGround) + " cells"));

        // 3. nothing changed: nothing saved
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(in.recipe, in.painting);
            }
        }, 30);
        check("an edit that changes nothing isn't saved", s.headline.equals("Nothing to save") && Recipe.list(dir).size() == 4, words(s));

        // 4. how it's rendered only: from the same stack
        final Recipe e = in.recipe.edit();
        e.stretch = 6.5f;
        e.glow = 1f;
        e.look = Recipe.LOOK_PUNCHY;
        e.crop = "16:9";
        int masters = Master.list(dir).size();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(e, in.painting);
            }
        }, 120);
        Recipe r5 = last(dir);
        check("a render-only edit is saved as render 5", s.headline.equals("Saved as render 5") && r5.number == 5
                && s.result.recipe.number == 5, words(s));
        check("from the same stack, no new master", r5.stackKey.equals(in.was.stackKey) && Master.list(dir).size() == masters,
                r5.stackKey + " vs " + in.was.stackKey + ", masters " + Master.list(dir).size());
        check("its note says what changed", r5.note.equals("edited: stretch 6.5, star glow 100%, punchy look, 16:9 crop"), r5.note);
        check("its stars glow, and the log says which", r5.glow == 1f && lastLines(dir, 12).contains("star glow 100%: "),
                lastLines(dir, 6));
        check("its picture is new", !r5.pixelHash.isEmpty() && !r5.pixelHash.equals(in.was.pixelHash), r5.pixelHash);
        check("a look-only edit has no video (nothing read the frames)", s.result.video == null && !s.result.motion
                && !r5.motion && String.valueOf(FakeMediaStore.name(s.result.jpeg)).endsWith(".jpg")
                && !String.valueOf(FakeMediaStore.name(s.result.jpeg)).endsWith(".MP.jpg"), FakeMediaStore.name(s.result.jpeg));

        // 5. frames trimmed, one left out, another reference: stacked again
        final SessionController.EditInfo in2 = info(c);
        final Recipe f = in2.recipe.edit();
        TreeSet<Integer> inc = new TreeSet<Integer>();
        for (int i = 1; i <= 14; i++) if (i != 5) inc.add(i);
        f.base().include = inc;
        f.base().includeNote = "frames 2 to 15, 1 left out by hand";
        f.reference = 8;
        long ts = System.currentTimeMillis();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(f, in2.painting);
            }
        }, 900);
        Recipe r6 = last(dir);
        check("a frames edit is stacked again and saved as render 6", s.headline.equals("Saved as render 6") && r6.number == 6
                && s.result.recipe.number == 6, words(s) + String.format(" (%.0f s)", (System.currentTimeMillis() - ts) / 1000.0));
        check("its stack is this version's, kept, and keyed by its recipe", r6.pipeline == PostProcessor.PIPELINE
                && r6.stackKey.equals(r6.stackKey()) && Master.read(dir, r6.stackKey) != null, r6.stackKey + " " + r6.stackKey());
        check("13 frames went in", s.detail.contains("13 of 13") || s.detail.contains("of 13"), s.detail);
        check("how it's rendered carries over", r6.stretch == 6.5f && r6.glow == 1f && r6.crop.equals("16:9")
                && r6.look.equals(Recipe.LOOK_PUNCHY), "");
        check("the stacking showed its progress", sawPhase(SessionController.Phase.DEVELOPING, "Stacking the edit"), "");
        check("its master knows its reference frame", Master.read(dir, r6.stackKey).meta.referenceUnit == 8,
                "" + Master.read(dir, r6.stackKey).meta.referenceUnit);
        // the edit stacked again gets its own video, of its own frames, ended on its own photo
        final File v6 = s.result.video;
        File g6 = FakeMediaStore.file(s.result.jpeg);
        MotionPhoto.Info mi6 = g6 != null ? MotionPhoto.read(g6) : null;
        check("the edit's photo is a Motion Photo with its own video", v6 != null && v6.isFile() && s.result.motion && r6.motion
                && mi6 != null && mi6.videoLength == v6.length() && (r6.name + ".MP.jpg").equals(FakeMediaStore.name(s.result.jpeg)),
                v6 + ", " + FakeMediaStore.name(s.result.jpeg) + ", " + (mi6 != null ? mi6.videoLength + " bytes inside" : "no video inside")
                        + " | " + lastLines(dir, 4));
        check("the log says the edit's photo plays", lastLines(dir, 12).contains("a Motion Photo: Google Photos plays its "), lastLines(dir, 6));

        // 6. back to all the frames: render 4's stack, which is kept, so not stacked again
        final SessionController.EditInfo in3 = info(c);
        final Recipe g = in3.recipe.edit();
        g.base().include = null;
        g.base().includeNote = "";
        g.reference = -1;
        g.stretch = 5f;
        mark();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(g, in3.painting);
            }
        }, 120);
        Recipe r7 = last(dir);
        check("a kept stack isn't stacked again", s.headline.equals("Saved as render 7") && r7.stackKey.equals(in.was.stackKey)
                && !sawSince(SessionController.Phase.DEVELOPING, "Stacking the edit") && log(dir).contains("is kept, so it isn't stacked again"),
                words(s) + ", " + r7.stackKey);

        // 7. render 6's stack deleted to make room: made again from its recipe, the same picture, not saved twice
        for (String x : new String[]{".f16", ".json"}) new File(new File(dir, "masters"), r6.stackKey + x).delete();
        check("render 6's stack is gone", Master.read(dir, r6.stackKey) == null, "");
        ts = System.currentTimeMillis();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.openRender(dir, 6);
            }
        }, 900);
        check("render 6 is made again, pixel for pixel", s.headline.equals("Render 6 made again") && s.detail.contains("pixel for pixel")
                && s.result.recipe.number == 6, words(s) + String.format(" (%.0f s)", (System.currentTimeMillis() - ts) / 1000.0));
        check("and isn't saved twice", Recipe.list(dir).size() == 7 && Master.read(dir, r6.stackKey) != null, Recipe.list(dir).size() + " renders");
        check("the log says so", log(dir).contains("render r006 made again from its recipe: the same stack and the same picture, pixel for pixel, "
                + "so it isn't saved twice"), lastLines(dir, 3));
        check("making it again showed its own words", sawPhase(SessionController.Phase.DEVELOPING, "Making it again"), "");
        check("made again, it still has its video", v6 != null && v6.equals(s.result.video) && v6.isFile() && s.result.motion,
                s.result.video + ", inside: " + s.result.motion);

        // 8. a re-stack cancelled: the photo as it was
        final SessionController.EditInfo in4 = info(c);
        final Recipe k = in4.recipe.edit();
        TreeSet<Integer> ten = new TreeSet<Integer>();
        for (int i = 0; i < 10; i++) ten.add(i);
        k.base().include = ten;
        k.base().includeNote = "the first 10";
        mark();
        c.saveEdit(k, in4.painting);
        long end = System.currentTimeMillis() + 120_000;
        while (!sawSince(SessionController.Phase.DEVELOPING, "Stacking the edit") && System.currentTimeMillis() < end) idle();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.cancel();
            }
        }, 300);
        check("a cancelled re-stack leaves the photo as it was", s.headline.equals("Cancelled") && s.result != null
                && s.result.recipe.number == 6 && Recipe.list(dir).size() == 7, words(s));

        // 9. render 2 (frames chosen, a reference, stars lined up by hand, the ground painted): from its kept master
        s = await(new Runnable() {
            @Override
            public void run() {
                c.openRender(dir, 2);
            }
        }, 120);
        check("render 2 opens from its master", s.result != null && s.result.recipe.number == 2 && !s.result.recipe.taps.isEmpty(), words(s));
        SessionController.EditInfo in5 = info(c);
        check("its painting and reference come back for editing", in5.painting != null && !in5.painting.isBlank() && in5.referenceUnit == 9,
                "reference " + in5.referenceUnit + ", painting " + (in5.painting == null ? "none" : in5.painting.isBlank() ? "blank" : "painted"));

        // 10. a render by an older version's processing: offered again with this one's, saved as a new render
        File r1f = new File(new File(dir, Recipe.DIR), "r001.json");
        String j = read(r1f);
        write(r1f, j.replace("\"pipeline\":" + PostProcessor.PIPELINE, "\"pipeline\":" + (PostProcessor.PIPELINE - 1)));
        s = await(new Runnable() {
            @Override
            public void run() {
                c.openRender(dir, 1);
            }
        }, 120);
        check("an older render shows as outdated", s.result != null && s.result.recipe.number == 1 && s.result.recipe.outdated(), words(s));
        final Recipe nw = s.result.recipe.edit();
        nw.renew();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(nw, null);
            }
        }, 900);
        Recipe r8 = last(dir);
        check("the new processing saves a new render", s.headline.equals("Saved as render 8") && r8.number == 8 && !r8.outdated()
                && r8.note.equals("edited: the new processing"), words(s) + ", note " + r8.note);
        check("render 1 stays as it was", Recipe.list(dir).get(0).outdated(), "");

        // 11. a render from before 1.4's noise reduction, its stack this version's: rendered anew from the same stack
        File r8f = new File(new File(dir, Recipe.DIR), "r008.json");
        write(r8f, read(r8f).replace("\"renderer\":" + Renderer.VERSION + ",", ""));
        s = await(new Runnable() {
            @Override
            public void run() {
                c.openRender(dir, 8);
            }
        }, 120);
        check("a render from before 1.4 offers the new processing, keeping its stack", s.result != null && s.result.recipe.number == 8
                && s.result.recipe.renderer == 1 && s.result.recipe.outdated() && !s.result.recipe.stackOutdated(), words(s));
        final Recipe nr = s.result.recipe.edit();
        nr.renew();
        mark();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(nr, null);
            }
        }, 300);
        Recipe r9 = last(dir);
        check("it's rendered anew from the same stack as render 9", s.headline.equals("Saved as render 9") && r9.number == 9
                && !r9.outdated() && r9.note.equals("edited: the new processing") && r9.stackKey.equals(r8.stackKey)
                && !sawSince(SessionController.Phase.FINISHING, "Stacking the edit")
                && log(dir).contains("edit: the new processing, rendered from the same stack"), words(s) + ", note " + r9.note);
        check("its log says what noise was taken out", lastLines(dir, 5).contains("decision: the sky's noise taken out of the photo"),
                lastLines(dir, 5));
        // an edit of how it's rendered, from render 8 as it was: the new rendering too, and its note says so
        write(r8f, read(r8f).replace("\"renderer\":" + Renderer.VERSION + ",", ""));
        s = await(new Runnable() {
            @Override
            public void run() {
                c.openRender(dir, 8);
            }
        }, 120);
        final Recipe st = s.result.recipe.edit();
        st.stretch = 7f;
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(st, null);
            }
        }, 300);
        Recipe r10 = last(dir);
        check("an edit of a render from before 1.4 is rendered by this version, and says so", s.headline.equals("Saved as render 10")
                && !r10.outdated() && r10.note.equals("edited: the new processing, stretch 7"), words(s) + ", note " + r10.note);

        // 12. an older stack and rendering whose frames were deleted to make room: rendered anew from the stack it has
        File[] raws = new SessionStore(dir).framesDir.listFiles();
        if (raws != null) {
            for (File raw : raws) if (raw.getName().endsWith(".raw") && !raw.delete()) throw new IllegalStateException("kept " + raw);
        }
        write(r1f, read(r1f).replace("\"renderer\":" + Renderer.VERSION + ",", ""));
        s = await(new Runnable() {
            @Override
            public void run() {
                c.openRender(dir, 1);
            }
        }, 120);
        check("a render older in both, its frames gone, still offers the new rendering", s.result != null && s.result.recipe.number == 1
                && s.result.recipe.renderOutdated() && s.result.recipe.stackOutdated(), words(s));
        final Recipe nf = s.result.recipe.edit();
        nf.renew();
        mark();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(nf, null);
            }
        }, 300);
        final Recipe r11 = last(dir);
        check("it's rendered anew from the stack it has, its stacking as it was", s.headline.equals("Saved as render 11")
                && r11.number == 11 && r11.stackOutdated() && !r11.renderOutdated() && r11.note.equals("edited: the new processing")
                && !sawSince(SessionController.Phase.FINISHING, "Stacking the edit"), words(s) + ", note " + r11.note);
        final Recipe nn = r11.edit();
        nn.renew();
        s = await(new Runnable() {
            @Override
            public void run() {
                c.saveEdit(nn, null);
            }
        }, 120);
        check("then there's nothing more to make with the new processing", s.headline.equals("Nothing to save")
                && last(dir).number == 11, words(s));

        // the stacked pictures kept: the develop step's, the newest and two more at most
        check("old stacks of edits are deleted to make room", Master.list(dir).size() <= 4, Master.list(dir).size() + " masters");
        System.out.println(String.format("flow in %.0f s", (System.currentTimeMillis() - t0) / 1000.0));
        System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
        System.out.println("---- log (last 40 lines)");
        System.out.println(lastLines(dir, 40));
        if (failures > 0) throw new AssertionError(failures + " failed");
    }

    // ---- driving the controller ----

    static int mark;

    static void mark() {
        synchronized (seen) {
            mark = seen.size();
        }
    }

    /** Runs {@code r}, then waits for the next status that isn't work in progress. */
    static SessionController.Status await(Runnable r, int seconds) throws Exception {
        int from;
        synchronized (seen) {
            from = seen.size();
        }
        r.run();
        long end = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < end) {
            idle();
            synchronized (seen) {
                for (int i = from; i < seen.size(); i++) {
                    SessionController.Status s = seen.get(i);
                    if (s.phase == SessionController.Phase.DONE || s.phase == SessionController.Phase.FAILED
                            || s.phase == SessionController.Phase.IDLE) {
                        // let the session thread settle
                        return s;
                    }
                }
            }
        }
        throw new AssertionError("timed out; last: " + words(seen.get(seen.size() - 1)));
    }

    static SessionController.EditInfo info(SessionController c) throws Exception {
        final SessionController.EditInfo[] got = new SessionController.EditInfo[1];
        final String[] why = new String[1];
        final boolean[] done = new boolean[1];
        c.editInfo(new SessionController.EditCallback() {
            @Override
            public void ready(SessionController.EditInfo info, String w) {
                got[0] = info;
                why[0] = w;
                done[0] = true;
            }
        });
        long end = System.currentTimeMillis() + 120_000;
        while (!done[0] && System.currentTimeMillis() < end) idle();
        if (got[0] == null) throw new AssertionError("no edit info: " + why[0]);
        return got[0];
    }

    static void idle() throws InterruptedException {
        Thread.sleep(20);
        ShadowLooper.idleMainLooper();
    }

    static boolean sawPhase(SessionController.Phase p, String headline) {
        synchronized (seen) {
            for (SessionController.Status s : seen) if (s.phase == p && s.headline.startsWith(headline)) return true;
        }
        return false;
    }

    static boolean sawSince(SessionController.Phase p, String headline) {
        synchronized (seen) {
            for (int i = mark; i < seen.size(); i++) {
                SessionController.Status s = seen.get(i);
                if (s.phase == p && s.headline.startsWith(headline)) return true;
            }
        }
        return false;
    }

    static Recipe last(File dir) {
        List<Recipe> l = Recipe.list(dir);
        return l.get(l.size() - 1);
    }

    static String words(SessionController.Status s) {
        return s.phase + " '" + s.headline + "' '" + s.detail + "'" + (s.result != null && s.result.recipe != null ? " r" + s.result.recipe.number : "");
    }

    static String log(File dir) throws Exception {
        return read(new File(dir, "log.txt"));
    }

    static String lastLines(File dir, int n) throws Exception {
        String[] l = log(dir).split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, l.length - n); i < l.length; i++) b.append(l[i]).append('\n');
        return b.toString();
    }

    static String read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        int n = 0;
        while (n < b.length) {
            int k = in.read(b, n, b.length - n);
            if (k < 0) break;
            n += k;
        }
        in.close();
        return new String(b, 0, n, StandardCharsets.UTF_8);
    }

    static void write(File f, String s) throws Exception {
        FileOutputStream os = new FileOutputStream(f);
        os.write(s.getBytes(StandardCharsets.UTF_8));
        os.close();
    }

    static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
        if (!ok) failures++;
    }
}
