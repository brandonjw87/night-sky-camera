package com.brandon.intro;

import android.content.res.AssetManager;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws the intro with OpenGL ES 3.0, pass for pass the same as the web preview. All of the animation
 * lives in the shared shaders as a function of time, so this class only runs the passes and keeps the clock.
 *
 * Passes: scene (sky, shutter iris, 3D name, label, flash heads) -> flash backscatter discs -> bloom chain
 * -> anamorphic streak -> light shafts -> final composite with the viewfinder overlay.
 * The scene and the effects run at {@link #scale} of the screen; the final pass runs at full resolution.
 * The look (standard, or the red night look) crossfades over {@link #NIGHT_FADE_NS}.
 */
final class FlashIntroRenderer implements GLSurfaceView.Renderer {
    static final float T_END = 2.2f;

    interface Callbacks {
        /** GL thread: shaders and textures are in place, the first frame can play. */
        void onReady();
        /** GL thread: playback reached the end, or {@link #freeze()} ran. */
        void onStopped(float t, boolean finished);
        /** GL thread: something failed; the intro should be skipped. */
        void onError(String message);
        /** GL thread: the still frame on screen is changing (the look is fading), so draw again. */
        void onFrameNeeded();
    }

    private static final String HEADER = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n";
    private static final double BUDGET_MS = 13.0;      // keeps 60 frames a second with room to spare
    private static final double TARGET_PIXELS = 1.6e6; // starting size of the scene pass
    private static final long NIGHT_FADE_NS = 260000000L;

    private final AssetManager assetManager;
    private final Callbacks callbacks;
    private IntroAssets assets;

    private final HashMap<String, Program> programs = new HashMap<String, Program>();
    private int vao, texPortrait, texLandscape, texLabels;
    private boolean hdr;
    private boolean ready, benchmarked;
    private int screenW, screenH;
    private float scale = 1f;
    private Target scene, stA, stB, rays;
    private final Target[] down = new Target[6];
    private final Target[] up = new Target[5];

    // clock (written by begin() on the GL thread)
    private boolean playing;
    private long startNs;
    private float speed = 1f;
    private IntroSound audioClock;
    private double lastT;
    private float holdT;
    private volatile float shownT;
    private long lastFrameNs;
    private int frames, lateFrames;

    // the look: 0 standard, 1 night, fading from one to the other (written by setNight() on the GL thread)
    private float nightFrom, nightTo, night;
    private long nightStartNs;

    FlashIntroRenderer(AssetManager am, Callbacks cb) {
        assetManager = am;
        callbacks = cb;
    }

    float shownTime() {
        return shownT;
    }

    // ---------------------------------------------------------------- control (call on the GL thread)

    void begin(long t0, float spd, IntroSound clock) {
        startNs = t0;
        speed = spd;
        audioClock = clock;
        lastT = 0;
        playing = true;
        lastFrameNs = 0;
        frames = 0;
        lateFrames = 0;
    }

    void hold(float t) {
        playing = false;
        holdT = Math.max(0f, Math.min(T_END, t));
    }

    void freeze() {
        if (!playing) return;
        playing = false;
        holdT = shownT;
        callbacks.onStopped(holdT, false);
    }

    boolean isPlaying() {
        return playing;
    }

    void setNight(boolean on, boolean fade) {
        long now = System.nanoTime();
        nightFrom = fade ? nightAt(now) : on ? 1f : 0f;
        nightTo = on ? 1f : 0f;
        nightStartNs = now;
    }

    private float nightAt(long now) {
        float k = (now - nightStartNs) / (float) NIGHT_FADE_NS;
        if (k >= 1f) return nightTo;
        k = Math.max(0f, k);
        return nightFrom + (nightTo - nightFrom) * k * k * (3f - 2f * k);
    }

    // ---------------------------------------------------------------- GLSurfaceView.Renderer

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        ready = false;
        programs.clear();
        // a new context: ids from an old one mean nothing here, so forget them rather than delete them
        scene = stA = stB = rays = null;
        Arrays.fill(down, null);
        Arrays.fill(up, null);
        try {
            if (assets == null) assets = IntroAssets.load(assetManager);
            buildPrograms();
            int[] ids = new int[1];
            GLES30.glGenVertexArrays(1, ids, 0);
            vao = ids[0];
            IntroAssets.NameField p = assets.portrait, l = assets.landscape;
            texPortrait = texture(p.w, p.h, GLES30.GL_R16F, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, p.data, false);
            texLandscape = texture(l.w, l.h, GLES30.GL_R16F, GLES30.GL_RED, GLES30.GL_HALF_FLOAT, l.data, false);
            texLabels = texture(assets.labelsW, assets.labelsH, GLES30.GL_R8, GLES30.GL_RED, GLES20.GL_UNSIGNED_BYTE, assets.labels, true);
            hdr = halfFloatTargets();
            ready = true;
        } catch (Exception e) {
            callbacks.onError(e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        if (!ready) return;
        boolean first = screenW == 0;
        screenW = width;
        screenH = height;
        if (first) scale = (float) Math.min(1.0, Math.sqrt(TARGET_PIXELS / ((double) width * height)));
        allocateTargets();
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        if (!ready || scene == null) {
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            return;
        }
        if (!benchmarked) {
            benchmarked = true;
            benchmark();
            render(holdT);
            shownT = holdT;
            callbacks.onReady();
            if (night != nightTo) callbacks.onFrameNeeded();
            return;
        }
        float t = holdT;
        boolean finished = false;
        if (playing) {
            long now = System.nanoTime();
            if (lastFrameNs != 0) {
                frames++;
                if (now - lastFrameNs > 26000000L) lateFrames++;
            }
            lastFrameNs = now;
            t = (float) clock(now);
            if (t >= T_END) {
                t = T_END;
                playing = false;
                holdT = T_END;
                finished = true;
            }
        }
        shownT = t;
        render(t);
        if (finished) {
            adaptScale();
            callbacks.onStopped(T_END, true);
        }
        if (!playing && night != nightTo) callbacks.onFrameNeeded();
    }

    private double clock(long now) {
        double wall = (now - startNs) / 1e9 * speed;
        double t = wall;
        if (audioClock != null) {
            double a = audioClock.positionSeconds();
            if (!Double.isNaN(a)) t = a;
            else if (wall < 0.4) t = 0;                  // waiting for the first sound to reach the speaker
            else {                                       // no sound came out: start over on the wall clock
                audioClock = null;
                startNs = now;
                t = 0;
            }
        }
        if (t < lastT) t = lastT;                        // never step backwards
        lastT = t;
        return t;
    }

    // ---------------------------------------------------------------- frame size

    /** Times the two heaviest kinds of frame and shrinks the effect passes until they fit the budget. */
    private void benchmark() {
        render(T_END);
        GLES20.glFinish();
        double a = Math.min(timeFrame(T_END), timeFrame(T_END));
        double b = Math.min(timeFrame(0.70f), timeFrame(0.70f));
        double ms = Math.max(a, b);
        if (ms > BUDGET_MS) {
            scale = clampScale(scale * (float) Math.sqrt(BUDGET_MS / ms));
            allocateTargets();
        }
    }

    private double timeFrame(float t) {
        long t0 = System.nanoTime();
        render(t);
        GLES20.glFinish();
        return (System.nanoTime() - t0) / 1e6;
    }

    /** After each play: late frames shrink the next one, a smooth run grows it back. */
    private void adaptScale() {
        if (frames < 20) return;
        float late = lateFrames / (float) frames;
        float before = scale;
        if (late > 0.15f) scale = clampScale(scale * 0.85f);
        else if (late < 0.02f) scale = Math.min(maxScale(), scale * 1.1f);
        if (scale != before) allocateTargets();
    }

    private float maxScale() {
        return (float) Math.min(1.0, Math.sqrt(2.6e6 / ((double) screenW * screenH)));
    }

    private float clampScale(float s) {
        return Math.max(0.4f, Math.min(maxScale(), s));
    }

    // ---------------------------------------------------------------- GL resources

    private static final class Program {
        final int id;
        private final HashMap<String, Integer> locs = new HashMap<String, Integer>();

        Program(int id) {
            this.id = id;
        }

        int loc(String name) {
            Integer l = locs.get(name);
            if (l == null) {
                l = GLES20.glGetUniformLocation(id, name);
                locs.put(name, l);
            }
            return l;
        }
    }

    private static final class Target {
        int tex, fbo, w, h;
    }

    private void buildPrograms() {
        String common = assets.shaders.get("common.glsl");
        String[][] list = {
                {"scene", "fullscreen.vert", "scene.frag"}, {"particles", "particles.vert", "particles.frag"},
                {"bright", "fullscreen.vert", "bright.frag"}, {"down", "fullscreen.vert", "down.frag"},
                {"up", "fullscreen.vert", "up.frag"}, {"streak", "fullscreen.vert", "streak.frag"},
                {"rays", "fullscreen.vert", "rays.frag"}, {"final", "fullscreen.vert", "final.frag"}};
        for (String[] e : list) {
            int vs = shader(GLES20.GL_VERTEX_SHADER, HEADER + common + "\n" + assets.shaders.get(e[1]), e[1]);
            int fs = shader(GLES20.GL_FRAGMENT_SHADER, HEADER + common + "\n" + assets.shaders.get(e[2]), e[2]);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("link " + e[0] + ": " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            programs.put(e[0], new Program(p));
        }
    }

    private static int shader(int type, String src, String name) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException(name + ": " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    private static int texture(int w, int h, int internal, int format, int type, ByteBuffer data, boolean mips) {
        int[] ids = new int[1];
        GLES20.glGenTextures(1, ids, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0]);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, internal, w, h, 0, format, type, data);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, mips ? GLES20.GL_LINEAR_MIPMAP_LINEAR : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        if (mips) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        return ids[0];
    }

    /** Half-float render targets keep the flash brighter than white for the bloom; fall back to 8 bits. */
    private boolean halfFloatTargets() {
        String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
        String ver = GLES20.glGetString(GLES20.GL_VERSION);
        boolean maybe = (ext != null && (ext.contains("GL_EXT_color_buffer_half_float") || ext.contains("GL_EXT_color_buffer_float")))
                || (ver != null && ver.contains("OpenGL ES 3.2"));
        if (!maybe) return false;
        int tex = texture(4, 4, GLES30.GL_RGBA16F, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null, false);
        int[] fb = new int[1];
        GLES20.glGenFramebuffers(1, fb, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fb[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0);
        boolean ok = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fb, 0);
        GLES20.glDeleteTextures(1, new int[]{tex}, 0);
        return ok;
    }

    private Target target(int w, int h) {
        Target t = new Target();
        t.w = Math.max(1, w);
        t.h = Math.max(1, h);
        t.tex = hdr ? texture(t.w, t.h, GLES30.GL_RGBA16F, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null, false)
                : texture(t.w, t.h, GLES30.GL_RGBA8, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null, false);
        int[] fb = new int[1];
        GLES20.glGenFramebuffers(1, fb, 0);
        t.fbo = fb[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, t.fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, t.tex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        return t;
    }

    private static void free(Target t) {
        if (t == null) return;
        GLES20.glDeleteTextures(1, new int[]{t.tex}, 0);
        GLES20.glDeleteFramebuffers(1, new int[]{t.fbo}, 0);
    }

    private void allocateTargets() {
        free(scene);
        for (Target t : down) free(t);
        for (Target t : up) free(t);
        free(stA);
        free(stB);
        free(rays);
        int w = Math.max(2, Math.round(screenW * scale)), h = Math.max(2, Math.round(screenH * scale));
        scene = target(w, h);
        int dw = w, dh = h;
        for (int i = 0; i < 6; i++) {
            dw = Math.max(1, Math.round(dw / 2f));
            dh = Math.max(1, Math.round(dh / 2f));
            down[i] = target(dw, dh);
        }
        for (int i = 0; i < 5; i++) up[i] = target(down[i].w, down[i].h);
        Target q = down[1];
        stA = target(q.w, q.h);
        stB = target(q.w, q.h);
        rays = target(q.w, q.h);
    }

    // ---------------------------------------------------------------- drawing

    private boolean landscape() {
        return screenW > screenH * 1.15f;
    }

    private Program use(String name, Target dst, float t) {
        Program p = programs.get(name);
        GLES20.glUseProgram(p.id);
        if (dst != null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dst.fbo);
            GLES20.glViewport(0, 0, dst.w, dst.h);
        } else {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, screenW, screenH);
        }
        boolean land = landscape();
        IntroAssets.NameField n = land ? assets.landscape : assets.portrait;
        GLES20.glUniform1f(p.loc("uTime"), t);
        GLES20.glUniform2f(p.loc("uRes"), dst != null ? dst.w : screenW, dst != null ? dst.h : screenH);
        GLES20.glUniform2f(p.loc("uScreen"), screenW, screenH);
        GLES20.glUniform4f(p.loc("uNameRect"), n.rect[0], n.rect[1], n.rect[2], n.rect[3]);
        GLES20.glUniform2f(p.loc("uNameInk"), n.ink[0], n.ink[1]);
        GLES20.glUniform1f(p.loc("uNameSpread"), n.spreadEm);
        GLES20.glUniform1f(p.loc("uNameEps"), 0.7f / n.emTex);
        GLES20.glUniform4f(p.loc("uMadeUV"), assets.madeUv[0], assets.madeUv[1], assets.madeUv[2], assets.madeUv[3]);
        GLES20.glUniform2f(p.loc("uMadeSize"), assets.madeSize[0], assets.madeSize[1]);
        GLES20.glUniform4f(p.loc("uHudUV"), assets.hudUv[0], assets.hudUv[1], assets.hudUv[2], assets.hudUv[3]);
        GLES20.glUniform2f(p.loc("uHudSize"), assets.hudSize[0], assets.hudSize[1]);
        GLES20.glUniform1f(p.loc("uLabelSpread"), assets.labelSpreadEm);
        GLES20.glUniform1f(p.loc("uLandscape"), land ? 1f : 0f);
        GLES20.glUniform1f(p.loc("uNight"), night);
        return p;
    }

    private static void bind(Program p, String name, int unit, int tex) {
        int loc = p.loc(name);
        if (loc < 0) return;
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(loc, unit);
    }

    private static void fullscreen() {
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3);
    }

    private void render(float t) {
        t = Math.max(0f, Math.min(T_END, t));
        night = nightAt(System.nanoTime());
        GLES30.glBindVertexArray(vao);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);

        // 1. scene
        Program p = use("scene", scene, t);
        bind(p, "uNameSdf", 0, landscape() ? texLandscape : texPortrait);
        bind(p, "uLabels", 1, texLabels);
        fullscreen();

        // 2. flash backscatter discs, added on top
        if (t > 0.6f && t < 1.6f) {
            use("particles", scene, t);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);
            GLES20.glColorMask(true, true, true, false);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 56 * 6);
            GLES20.glColorMask(true, true, true, true);
            GLES20.glDisable(GLES20.GL_BLEND);
        }

        // 3. bloom chain
        p = use("bright", down[0], t);
        bind(p, "uSrc", 0, scene.tex);
        GLES20.glUniform2f(p.loc("uSrcTexel"), 1f / scene.w, 1f / scene.h);
        GLES20.glUniform1f(p.loc("uThreshold"), 1f);
        fullscreen();
        for (int i = 1; i < 6; i++) {
            p = use("down", down[i], t);
            bind(p, "uSrc", 0, down[i - 1].tex);
            GLES20.glUniform2f(p.loc("uSrcTexel"), 1f / down[i - 1].w, 1f / down[i - 1].h);
            fullscreen();
        }
        for (int i = 4; i >= 0; i--) {
            Target low = i == 4 ? down[5] : up[i + 1];
            p = use("up", up[i], t);
            bind(p, "uLow", 0, low.tex);
            bind(p, "uCur", 1, down[i].tex);
            GLES20.glUniform2f(p.loc("uLowTexel"), 1f / low.w, 1f / low.h);
            fullscreen();
        }

        // 4. anamorphic streak from the quarter-size bright level
        Target q = down[1];
        streak(1f, q, stA, 1f, t, q.w);
        streak(5f, stA, stB, 1f, t, q.w);
        streak(25f, stB, stA, 2.2f, t, q.w);

        // 5. light shafts
        boolean land = landscape();
        p = use("rays", rays, t);
        bind(p, "uScene", 0, scene.tex);
        GLES20.glUniform2f(p.loc("uLight"), 0.5f, land ? 0.5f + 0.5f * 0.03f : 0.5f + 0.5f * 0.075f + 0.01f);
        fullscreen();

        // 6. final composite, full resolution
        p = use("final", null, t);
        bind(p, "uScene", 0, scene.tex);
        bind(p, "uBloom", 1, up[0].tex);
        bind(p, "uStreak", 2, stA.tex);
        bind(p, "uRays", 3, rays.tex);
        bind(p, "uLabels", 4, texLabels);
        fullscreen();
    }

    private void streak(float step, Target src, Target dst, float gain, float t, int qw) {
        Program p = use("streak", dst, t);
        bind(p, "uSrc", 0, src.tex);
        GLES20.glUniform2f(p.loc("uStep"), step / qw, 0f);
        GLES20.glUniform1f(p.loc("uGain"), gain);
        fullscreen();
    }
}
