package com.brandon.nightsky;

import android.hardware.camera2.CaptureResult;
import android.os.SystemClock;

import com.brandon.nightsky.sim.JupiterSim;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The Pixel 10 Pro XL's 5x telephoto pointed at Jupiter, simulated, for the Jupiter robot (3.2): whole 12-megapixel
 * RAW frames (as {@link SimTele}'s), the sky's black level and read noise, and Jupiter with its four moons
 * ({@link JupiterSim}: the lens's blur, its halo, photons for the exposure's time, shot and read noise) where a hand
 * holding the phone has it: drifting towards the middle as the phone is pointed, trembling, a little turned, some
 * frames blurred by the shake. Each frame has the exposure and focus the app asked for {@link SimTele#DEPTH} frames
 * before, with its capture result (now and then none); the lens blurs away from its sharpest setting as
 * {@link SimTele}'s does. The frames go to {@link MoonCamera#frame} on the camera's own thread, one at a time.
 */
final class SimJupiter {
    static final int W = SimTele.W, H = SimTele.H;
    /** Half the side of the window Jupiter and its moons are drawn in (px): Callisto's farthest and a margin. */
    static final int HALF = 96;

    final JupiterSim sim;
    final MoonCamera cam;
    final short[] frame = new short[W * H];
    private final short[] sky;
    private final Random rnd = new Random(23);
    /** The settings asked for after each frame: {ns, iso, focus}; the first, before any frame. */
    final List<double[]> asked = Collections.synchronizedList(new ArrayList<double[]>());
    /** Where the hand has Jupiter (sensor px), and the frame's turn (degrees). */
    double X = 2610.4, Y = 1390.6, rot;
    private double prevFocus = Double.NaN;
    /** Frames given to the camera; the last frame's blur (px) and Jupiter's place. */
    int n;
    double lastBlur, lastJx, lastJy;
    /** Something the camera's frame code threw (null: nothing). */
    volatile Throwable failure;

    SimJupiter(MoonCamera cam, JupiterSim sim) {
        this.cam = cam;
        this.sim = sim;
        sky = new short[W * H + 4096];
        Random r = new Random(13);
        for (int k = 0; k < sky.length; k++) sky[k] = (short) (sim.black + Math.round(1.1 * r.nextGaussian()));
        asked.add(new double[]{cam.exposure.ns, cam.exposure.iso, cam.focus});
    }

    /** Takes one frame and gives it to the camera, waiting for the camera to finish with it. False if the camera is closed. */
    boolean frame() throws InterruptedException {
        if (cam.closed || cam.h == null) return false;
        double[] s = asked.get(Math.max(0, asked.size() - 1 - SimTele.DEPTH));
        long ns = (long) s[0];
        int iso = (int) s[1];
        double focus = s[2];
        // the hand: pointing (towards the middle), trembling, turning a little
        double t = n / 30.0;
        X += (W / 2.0 - X) * 0.004 + 0.12 * rnd.nextGaussian();
        Y += (H / 2.0 - Y) * 0.004 + 0.12 * rnd.nextGaussian();
        double jx = X + 0.35 * Math.sin(2 * Math.PI * 9.1 * t + 0.4) + 0.2 * Math.sin(2 * Math.PI * 11.3 * t + 1.3);
        double jy = Y + 0.3 * Math.sin(2 * Math.PI * 8.7 * t + 2.1) + 0.25 * Math.sin(2 * Math.PI * 12.1 * t);
        rot += 0.012 * rnd.nextGaussian();
        double u = rnd.nextDouble();
        double shake = u < 0.6 ? 0.2 + 0.3 * rnd.nextDouble() : u < 0.9 ? 0.6 + 0.4 * rnd.nextDouble() : 1.2 + 0.6 * rnd.nextDouble();
        double d = SimTele.defocus(focus);
        double blur = Math.min(2.4, Math.sqrt(shake * shake + d * d));
        final long tNs = SystemClock.elapsedRealtimeNanos();
        System.arraycopy(sky, rnd.nextInt(4096), frame, 0, W * H);
        int x0 = Math.max(0, Math.min(W - 2 * HALF, ((int) Math.floor(jx) - HALF) & ~1));
        int y0 = Math.max(0, Math.min(H - 2 * HALF, ((int) Math.floor(jy) - HALF) & ~1));
        int ww = 2 * HALF;
        short[] win = sim.frame(ww, ww, x0, y0, jx + 0.5, jy + 0.5, rot, blur, ns, iso, rnd);
        for (int j = 0; j < ww; j++) System.arraycopy(win, j * ww, frame, (y0 + j) * W + x0, ww);
        boolean moving = !Double.isNaN(prevFocus) && Math.abs(focus - prevFocus) > 1e-6;
        prevFocus = focus;
        if (rnd.nextDouble() >= 0.04) {
            cam.results.put(tNs, new long[]{ns, iso, Float.floatToIntBits((float) focus),
                    moving ? CaptureResult.LENS_STATE_MOVING : CaptureResult.LENS_STATE_STATIONARY});
        }
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = cam.h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    cam.frame(ShortBuffer.wrap(frame), W, W, H, tNs);
                } catch (Throwable e) {
                    if (failure == null) failure = e;
                } finally {
                    asked.add(new double[]{cam.exposure.ns, cam.exposure.iso, cam.focus});
                    done.countDown();
                }
            }
        });
        if (!posted) return false;
        if (!done.await(120, TimeUnit.SECONDS)) throw new IllegalStateException("the camera took two minutes over a frame");
        n++;
        lastBlur = blur;
        lastJx = jx;
        lastJy = jy;
        return true;
    }

    /** Waits until the camera's thread has done everything posted to it so far. */
    void barrier() throws InterruptedException {
        if (cam.h == null) return;
        final CountDownLatch done = new CountDownLatch(1);
        if (!cam.h.post(new Runnable() {
            @Override
            public void run() {
                done.countDown();
            }
        })) return;
        done.await(60, TimeUnit.SECONDS);
    }
}
