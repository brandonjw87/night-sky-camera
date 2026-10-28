package com.brandon.nightsky;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import com.brandon.nightsky.processing.Master;
import com.brandon.nightsky.processing.MergeProcessor;
import com.brandon.nightsky.processing.Recipe;
import com.brandon.nightsky.processing.Renderer;
import com.brandon.nightsky.processing.RgbImage;
import com.brandon.nightsky.processing.SensorSpec;
import com.brandon.nightsky.processing.SessionStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Properties;

/** Draws the app's screens to PNG files (shots.out) from sessions copied in (shots.sessions). */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36, qualifiers = "w412dp-h915dp-port-xhdpi")
public class Shots {
    static File out;

    @Test
    public void shots() throws Exception {
        out = new File(System.getProperty("shots.out"));
        out.mkdirs();
        // no capture service: the screens are driven directly
        android.app.Application app = org.robolectric.RuntimeEnvironment.getApplication();
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(new android.content.ComponentName(app, CaptureService.class));
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        File root = SessionController.sessionsRoot(a);
        copy(new File(System.getProperty("shots.sessions")), root);
        List<SessionStore> all = SessionStore.list(root);
        String only = System.getProperty("shots.only", "");
        if (only.equals("edit")) {
            edit(a, all.get(0));
            return;
        }
        Bitmap photo = null;
        for (SessionStore s : all) {
            Bitmap b = thumbAndPhoto(s);
            if (photo == null && b != null && !"trails".equals(s.readSettings().getProperty("mode"))) photo = b;
        }
        // the frames of the oldest two as if kept, so they can be merged
        SessionStore night1 = all.get(all.size() - 1), night2 = all.get(all.size() - 2);
        fakeFrames(night1);
        fakeFrames(night2);
        setSite(night1, "Backyard");
        setSite(night2, "Backyard");
        new Prefs(a).setAdvancedOpen(true);

        if (only.isEmpty() || only.contains("home")) {
            page(a, 0);
            full(a, "home");
        }
        if (only.isEmpty() || only.contains("sessions")) {
            page(a, 6);
            waitIdle(1500);
            full(a, "sessions");
        }
        if (only.isEmpty() || only.contains("merge")) {
            set(a, "mergeBase", night1.dir.getName());
            set(a, "mergePick", null);
            page(a, 7);
            waitIdle(1500);
            full(a, "merge");
        }
        MergeProcessor.Result m = fakeMerge(night1, night2);
        if (only.isEmpty() || only.contains("result")) {
            // a session just developed: New photo, Sessions, and Merge with the other night once found
            SessionController.Status st = new SessionController.Status();
            st.phase = SessionController.Phase.DONE;
            st.headline = "Your photo is ready";
            st.detail = "Used 12 of 12 frames (1 min 36 s).";
            SessionController.Result r = new SessionController.Result();
            r.summary = st.detail;
            r.session = night1.dir.getName();
            r.startedMs = Long.parseLong(night1.readSettings().getProperty("capture.startWallMs"));
            r.durationSec = 300;
            r.endedMs = System.currentTimeMillis();
            r.brightness = 0.5f;
            r.master = Master.list(night1.dir).get(0);
            List<Recipe> rl = Recipe.list(night1.dir);
            r.recipe = rl.isEmpty() ? null : rl.get(rl.size() - 1);
            System.out.println("result: recipe " + (r.recipe == null ? "none" : "r" + r.recipe.number));
            st.result = r;
            call(a, "showResult", new Class<?>[]{SessionController.Status.class}, st);
            texts(a, st);
            show(a, photo);
            waitIdle(1500);
            screen(a, "result");
            if (r.recipe != null) {
                // made by an older version's processing: offered again with this one's
                Recipe old = Recipe.fromJson(r.recipe.toJson());
                old.pipeline = 0;
                r.recipe = old;
                call(a, "showResult", new Class<?>[]{SessionController.Status.class}, st);
                texts(a, st);
                show(a, photo);
                waitIdle(800);
                screen(a, "result-outdated");
                r.recipe = rl.get(rl.size() - 1);
            }
            // a merge of nights: its total and the way to its coverage map
            st.headline = "The merged photo is ready";
            st.detail = "Merged 2 sessions: 24 of 24 frames (3 min 12 s).";
            r.merge = m;
            r.summary = st.detail;
            call(a, "showResult", new Class<?>[]{SessionController.Status.class}, st);
            texts(a, st);
            show(a, photo);
            waitIdle(800);
            screen(a, "result-merged");
            call(a, "showMergeInfo", new Class<?>[]{MergeProcessor.Result.class}, m);
            waitIdle(300);
            fullOverlay(a, "merge-info");
            call(a, "closeReport", new Class<?>[0]);
        }
    }

    // ---- the edit screen (G3), on a session whose frames are kept ----

    static void edit(MainActivity a, SessionStore st) throws Exception {
        SensorSpec sp = SensorSpec.fromProperties(st.readSettings());
        List<Recipe> rs = Recipe.list(st.dir);
        Recipe was = rs.get(rs.size() - 1);
        Master.Loaded l = null;
        for (int i = rs.size() - 1; i >= 0 && l == null; i--) {
            l = Master.read(st.dir, rs.get(i).stackKey);
            if (l != null) was = rs.get(i);
        }
        Renderer.Analysis an = Master.analyze(l.image, Master.colourSpec(sp, l.meta), l.meta.trails);
        RgbImage sm = l.image.binned(2);
        int[] px = Renderer.renderSmall(sm, an, was.params(), 2);
        Bitmap photo = Bitmap.createBitmap(px, sm.cropWidth(), sm.cropHeight(), Bitmap.Config.ARGB_8888);
        SessionController.EditInfo in = new SessionController.EditInfo();
        in.was = was;
        in.recipe = was.edit();
        in.dir = st.dir;
        Method sf = SessionController.class.getDeclaredMethod("sessionFrames", SessionStore.class);
        sf.setAccessible(true);
        in.frames = (com.brandon.nightsky.processing.ReportCard.Frame[]) sf.invoke(null, st);
        // as a report would grade them: one cloudy frame left out, two weaker
        in.frames[3].used = false;
        in.frames[3].score = 0;
        in.frames[3].reason = "clouds (34% light)";
        in.frames[7].score = 44;
        in.frames[7].reason = "thin cloud (71% light)";
        for (int i = 0; i < in.frames.length; i++) {
            if (in.frames[i].used && i != 7) in.frames[i].score = 100 - 3 * (i % 5);
            int min = 2 + i * 8 / 60, sec = (i * 8) % 60;
            in.frames[i].time = String.format(java.util.Locale.US, "11:%02d:%02d PM", 14 + min, sec);
        }
        in.referenceUnit = l.meta.referenceUnit >= 0 ? l.meta.referenceUnit : in.frames.length / 2;
        in.autoGround = com.brandon.nightsky.processing.EdgeMask.automatic(st, sp);
        com.brandon.nightsky.processing.EdgeMask pm = was.mask.isEmpty() ? null : com.brandon.nightsky.processing.EdgeMask.load(st.dir, was.mask);
        in.painting = pm != null ? pm : new com.brandon.nightsky.processing.EdgeMask(sp.width, sp.height);
        in.cx0 = an.cx0;
        in.cy0 = an.cy0;
        in.cw = an.cw;
        in.ch = an.ch;
        in.factor = 2;
        in.width = sp.width;
        in.height = sp.height;
        in.renders = rs;
        System.out.println("edit: " + in.frames.length + " frames, reference " + in.referenceUnit + ", auto ground "
                + com.brandon.nightsky.processing.EdgeMask.count(in.autoGround) + " cells, renders " + rs.size() + ", shown r" + was.number);
        // the result screen under it
        SessionController.Status s = new SessionController.Status();
        s.phase = SessionController.Phase.DONE;
        s.headline = "Your photo is ready";
        s.detail = l.meta.summary;
        SessionController.Result r = new SessionController.Result();
        r.summary = s.detail;
        r.session = st.dir.getName();
        r.recipe = was;
        r.master = l.meta;
        r.brightness = was.brightness;
        s.result = r;
        call(a, "showResult", new Class<?>[]{SessionController.Status.class}, s);
        show(a, photo);
        call(a, "showEdit", new Class<?>[]{SessionController.EditInfo.class, Bitmap.class}, in, photo);
        Object es = field(a, "edit");
        showOn(a, "editZoom", photo);
        waitIdle(300);
        screen(a, "edit-look");
        tab(a, es, "Crop");
        screen(a, "edit-crop");
        set(es, "picked", 7);
        tab(a, es, "Frames");
        screen(a, "edit-frames");
        // the trim and a frame left out by hand
        set(es, "from", 1);
        set(es, "to", in.frames.length - 2);
        ((java.util.TreeSet<Integer>) field(es, "leftOut")).add(in.frames[7].index);
        tab(a, es, "Frames");
        screen(a, "edit-frames-trimmed");
        set(es, "brush", (int) com.brandon.nightsky.processing.EdgeMask.GROUND);
        tab(a, es, "Ground");
        showOn(a, "editZoom", photo);
        screen(a, "edit-ground");
        paintChecks(a, es);
        tab(a, es, "Renders");
        screen(a, "edit-renders");
        // lining frame up by hand: two stars tapped in the reference and in the frame
        int unit = in.frames[in.frames.length - 2].index;
        call(a, "openTaps", new Class<?>[]{es.getClass(), int.class, Runnable.class}, es, unit, new Runnable() {
            @Override
            public void run() {
            }
        });
        waitIdle(4000);
        View tv = (View) field(es, "tapView");
        ZoomImageView z = findZoom(tv);
        Object tl = field(z, "tap");
        Method onTap = tl.getClass().getMethod("onTap", float.class, float.class);
        onTap.setAccessible(true);
        float[] sx = l.meta.starX, sy = l.meta.starY;
        int done = 0;
        for (int k = 0; k < sx.length && done < 2; k += 7) {
            onTap.invoke(tl, sx[k] / 2 + 1.5f, sy[k] / 2 - 1f);
            onTap.invoke(tl, sx[k] / 2 + 1.5f, sy[k] / 2 - 1f);
            done++;
        }
        waitIdle(300);
        screen(a, "edit-taps");
    }

    /** One finger paints and can be undone; a pinch's first finger leaves nothing behind. */
    static void paintChecks(MainActivity a, Object es) throws Exception {
        ZoomImageView ez = (ZoomImageView) field(a, "editZoom");
        java.util.List<?> undo = (java.util.List<?>) field(es, "undo");
        com.brandon.nightsky.processing.EdgeMask p0 = ((com.brandon.nightsky.processing.EdgeMask) field(es, "painting")).copy();
        float cx = ez.getWidth() / 2f, cy = ez.getHeight() / 2f;
        long t = android.os.SystemClock.uptimeMillis();
        touch(ez, android.view.MotionEvent.ACTION_DOWN, t, t, new float[][]{{cx, cy}});
        touch(ez, android.view.MotionEvent.ACTION_MOVE, t, t + 30, new float[][]{{cx + 60, cy}});
        touch(ez, android.view.MotionEvent.ACTION_UP, t, t + 60, new float[][]{{cx + 60, cy}});
        com.brandon.nightsky.processing.EdgeMask p1 = ((com.brandon.nightsky.processing.EdgeMask) field(es, "painting")).copy();
        result("a one-finger stroke paints, one step to undo", !java.util.Arrays.equals(p0.paint, p1.paint) && undo.size() == 1,
                "undo " + undo.size());
        // a pinch: the first finger lands and moves a little, the second lands 80 ms later
        t += 1000;
        int sh = android.view.MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        touch(ez, android.view.MotionEvent.ACTION_DOWN, t, t, new float[][]{{cx, cy + 200}});
        touch(ez, android.view.MotionEvent.ACTION_MOVE, t, t + 40, new float[][]{{cx + 10, cy + 200}});
        touch(ez, android.view.MotionEvent.ACTION_POINTER_DOWN | (1 << sh), t, t + 80, new float[][]{{cx + 10, cy + 200}, {cx + 200, cy + 300}});
        touch(ez, android.view.MotionEvent.ACTION_MOVE, t, t + 120, new float[][]{{cx, cy + 190}, {cx + 260, cy + 340}});
        touch(ez, android.view.MotionEvent.ACTION_POINTER_UP | (1 << sh), t, t + 160, new float[][]{{cx, cy + 190}, {cx + 260, cy + 340}});
        touch(ez, android.view.MotionEvent.ACTION_UP, t, t + 200, new float[][]{{cx, cy + 190}});
        com.brandon.nightsky.processing.EdgeMask p2 = (com.brandon.nightsky.processing.EdgeMask) field(es, "painting");
        result("a pinch leaves no paint", java.util.Arrays.equals(p1.paint, p2.paint) && undo.size() == 1, "undo " + undo.size());
        call(a, "undoPainting", new Class<?>[]{es.getClass()}, es);
        com.brandon.nightsky.processing.EdgeMask p3 = (com.brandon.nightsky.processing.EdgeMask) field(es, "painting");
        result("Undo puts the painting back", java.util.Arrays.equals(p0.paint, p3.paint) && undo.isEmpty(), "undo " + undo.size());
        ShadowLooper.idleMainLooper();
    }

    static void touch(View v, int action, long down, long at, float[][] pts) {
        int n = pts.length;
        android.view.MotionEvent.PointerProperties[] pp = new android.view.MotionEvent.PointerProperties[n];
        android.view.MotionEvent.PointerCoords[] pc = new android.view.MotionEvent.PointerCoords[n];
        for (int i = 0; i < n; i++) {
            pp[i] = new android.view.MotionEvent.PointerProperties();
            pp[i].id = i;
            pp[i].toolType = android.view.MotionEvent.TOOL_TYPE_FINGER;
            pc[i] = new android.view.MotionEvent.PointerCoords();
            pc[i].x = pts[i][0];
            pc[i].y = pts[i][1];
            pc[i].pressure = 1;
            pc[i].size = 1;
        }
        android.view.MotionEvent e = android.view.MotionEvent.obtain(down, at, action, n, pp, pc, 0, 0, 1, 1, 0, 0,
                android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        v.dispatchTouchEvent(e);
        e.recycle();
    }

    static void result(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : ": " + detail));
    }

    static void tab(MainActivity a, Object es, String t) throws Exception {
        set(es, "tab", t);
        call(a, "fillEditTab", new Class<?>[]{es.getClass()}, es);
        ShadowLooper.idleMainLooper();
    }

    static Object field(Object o, String name) throws Exception {
        Class<?> c = o.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    static void showOn(MainActivity a, String name, Bitmap b) throws Exception {
        Object z = field(a, name);
        Method m = z.getClass().getDeclaredMethod("show", Bitmap.class, boolean.class);
        m.setAccessible(true);
        m.invoke(z, b, false);
    }

    static ZoomImageView findZoom(View v) {
        if (v instanceof ZoomImageView) return (ZoomImageView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                ZoomImageView z = findZoom(g.getChildAt(i));
                if (z != null) return z;
            }
        }
        return null;
    }

    // ---- data ----

    static Bitmap thumbAndPhoto(SessionStore s) throws Exception {
        List<Master.Meta> ms = Master.list(s.dir);
        if (ms.isEmpty()) return null;
        Master.Loaded l = Master.read(s.dir, ms.get(0).key);
        if (l == null) return null;
        SensorSpec sp = SensorSpec.fromProperties(s.readSettings());
        Renderer.Analysis an = Master.analyze(l.image, Master.colourSpec(sp, l.meta), l.meta.trails);
        Renderer.Params p = new Renderer.Params();
        int[] px = Master.render(l.image, an, p);
        Bitmap b = Bitmap.createBitmap(px, l.image.cropWidth(), l.image.cropHeight(), Bitmap.Config.ARGB_8888);
        int w = 360, h = Math.round(w * b.getHeight() / (float) b.getWidth());
        Bitmap t = Bitmap.createScaledBitmap(b, w, h, true);
        OutputStream os = new FileOutputStream(new File(s.dir, "thumb.jpg"));
        t.compress(Bitmap.CompressFormat.JPEG, 85, os);
        os.close();
        return b;
    }

    static void fakeFrames(SessionStore s) throws Exception {
        s.framesDir.mkdirs();
        OutputStream os = new FileOutputStream(new File(s.framesDir, "u0000.raw"));
        os.write(new byte[3 << 20]);
        os.close();
    }

    static void setSite(SessionStore s, String name) throws Exception {
        Properties p = s.readSettings();
        p.setProperty("site.name", name);
        p.setProperty("site.id", "s1");
        s.writeSettings(p);
    }

    /** Three nights of nearly the same pointing over a 64 x 48 cell frame. */
    static MergeProcessor.Result fakeMerge(SessionStore a, SessionStore b) throws Exception {
        MergeProcessor.Result m = new MergeProcessor.Result();
        m.coverW = 64;
        m.coverH = 48;
        m.coverage = new byte[64 * 48];
        int[][] offs = {{0, 0}, {4, 2}, {-3, 5}};
        for (int[] o : offs) {
            for (int y = 0; y < 48; y++) {
                for (int x = 0; x < 64; x++) {
                    int bx = x - o[0], by = y - o[1];
                    if (bx >= 0 && by >= 0 && bx < 64 && by < 48) m.coverage[y * 64 + x]++;
                }
            }
        }
        m.sessions = 3;
        m.nights = 3;
        m.total = "47 minutes over 3 nights";
        String[] labels = {"22 Oct", "23 Oct", "24 Oct"};
        for (int k = 0; k < 3; k++) {
            MergeProcessor.Part p = new MergeProcessor.Part();
            p.id = k == 0 ? a.id() : k == 1 ? b.id() : "x";
            p.label = labels[k];
            p.framesTotal = 52;
            p.framesUsed = k == 2 ? 41 : 50;
            p.seconds = k == 2 ? 41 * 6 * 1.4 : 50 * 6 * 1.4;
            p.signal = k == 0 ? 1 : k == 1 ? 0.94 : 0.81;
            p.photometryStars = k == 0 ? 0 : 63 - 9 * k;
            p.matched = k == 0 ? 0 : 140 - 20 * k;
            p.rms = k == 0 ? Double.NaN : 0.31 + 0.05 * k;
            m.parts.add(p);
        }
        return m;
    }

    static void copy(File from, File to) throws Exception {
        if (from.isDirectory()) {
            to.mkdirs();
            File[] l = from.listFiles();
            if (l != null) for (File c : l) copy(c, new File(to, c.getName()));
            return;
        }
        InputStream in = new FileInputStream(from);
        OutputStream os = new FileOutputStream(to);
        byte[] buf = new byte[1 << 16];
        int n;
        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        in.close();
        os.close();
    }

    // ---- driving the activity ----

    static void page(MainActivity a, int p) throws Exception {
        set(a, "page", p);
        call(a, "showHome", new Class<?>[0]);
        ShadowLooper.idleMainLooper();
    }

    /** What refresh() would put in the headline and detail from the status. */
    static void texts(MainActivity a, SessionController.Status st) throws Exception {
        for (String n : new String[]{"headline", "detail"}) {
            Field f = MainActivity.class.getDeclaredField(n);
            f.setAccessible(true);
            android.widget.TextView t = (android.widget.TextView) f.get(a);
            if (t != null) t.setText(n.equals("headline") ? st.headline : st.detail);
        }
    }

    static void show(MainActivity a, Bitmap b) throws Exception {
        if (b == null) return;
        Field f = MainActivity.class.getDeclaredField("zoom");
        f.setAccessible(true);
        Object z = f.get(a);
        Method m = z.getClass().getDeclaredMethod("show", Bitmap.class, boolean.class);
        m.setAccessible(true);
        m.invoke(z, b, false);
    }

    /** Lets threads post back and the main looper run what they posted. */
    static void waitIdle(long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50);
            ShadowLooper.idleMainLooper();
        }
    }

    static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        if (f.getType() == int.class) {
            f.setInt(o, (Integer) v);
            return;
        }
        f.setAccessible(true);
        f.set(o, v);
    }

    static Object call(Object o, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(o, args);
    }

    // ---- drawing ----

    static View content(MainActivity a) {
        return a.findViewById(android.R.id.content);
    }

    /** The screen as it would show. */
    static void screen(MainActivity a, String name) throws Exception {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        View v = content(a);
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, dm.widthPixels, dm.heightPixels);
        ShadowLooper.idleMainLooper();
        v.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, dm.widthPixels, dm.heightPixels);
        Bitmap b = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF000000);
        v.draw(new Canvas(b));
        save(b, name);
    }

    /** A scrolling page whole, however long. */
    static void full(MainActivity a, String name) throws Exception {
        View c = content(a);
        ScrollView sv = findScroll(c);
        if (sv == null) {
            screen(a, name);
            return;
        }
        drawWhole(a, sv.getChildAt(0), name);
    }

    static void fullOverlay(MainActivity a, String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField("reportOverlay");
        f.setAccessible(true);
        FrameLayout ov = (FrameLayout) f.get(a);
        ScrollView sv = findScroll(ov);
        drawWhole(a, sv.getChildAt(0), name);
    }

    static void drawWhole(MainActivity a, View child, String name) throws Exception {
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        child.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = Math.max(1, child.getMeasuredHeight());
        child.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF000000);
        child.draw(new Canvas(b));
        save(b, name);
    }

    static ScrollView findScroll(View v) {
        if (v instanceof ScrollView) return (ScrollView) v;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {
                ScrollView s = findScroll(g.getChildAt(i));
                if (s != null) return s;
            }
        }
        return null;
    }

    static void save(Bitmap b, String name) throws Exception {
        OutputStream os = new FileOutputStream(new File(out, name + ".png"));
        b.compress(Bitmap.CompressFormat.PNG, 100, os);
        os.close();
        System.out.println("saved " + name + ".png " + b.getWidth() + "x" + b.getHeight());
    }
}
