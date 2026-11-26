package com.brandon.nightsky;

import android.hardware.camera2.CaptureResult;
import android.os.SystemClock;

import com.brandon.nightsky.processing.MoonMeter;
import com.brandon.nightsky.sim.MoonSim;

import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The Pixel 10 Pro XL's 5x telephoto, simulated, for the Moonshot's robot (3.0). Every frame is a whole 12-megapixel
 * RAW frame (4080 x 3072, 10 bits, RGGB): the sky's black level and read noise, and the simulated Moon
 * ({@link MoonSim}) where a hand holding the phone has it, drifting towards the middle as the phone is pointed,
 * trembling, a little turned, some frames blurred by the shake. Each frame has the exposure and focus the app asked
 * for {@link #DEPTH} frames before (a camera's pipeline), with its capture result as Camera2 gives it (now and then
 * none, as when a result comes late); the lens blurs away from its sharpest setting as a real 18 mm f/2.8 does; the
 * sensor gives more light than the meter's textbook guess, so the metering has to find it. A station can cross the
 * Moon at a set moment. The frames go to {@link MoonCamera#frame} on the camera's own thread, one at a time.
 */
final class SimTele {
    /** The RAW frame, the pixel and the lens (17 arcseconds a pixel). */
    static final int W = 4080, H = 3072;
    static final double PITCH_UM = 1.508, FOCAL_MM = 18.3, F_NUMBER = 2.8;
    /** Where the lens is sharp on the Moon (diopters: not quite the lens's 0), and how fast it blurs (blur sigma, px a diopter). */
    static final double BEST_FOCUS = 0.07, BLUR_PER_DIOPTER = 20;
    /** Frames between asking for settings and a frame taken with them. */
    static final int DEPTH = 3;
    /** Read noise on the black level (counts). */
    static final int BLACK = 64;

    /** The Moon drawn (the robot can swap in another: the Earth's glow's crescent, 3.1). */
    MoonSim sim;
    final MoonCamera cam;
    /** Simulated light (MoonSim's gain) per unit of exposure (seconds x ISO). */
    final double gainPerLight;
    final short[] frame = new short[W * H];
    private final short[] sky;
    private final Random rnd = new Random(7);
    /** The settings asked for after each frame: {ns, iso, focus}; the first, before any frame. */
    final List<double[]> asked = Collections.synchronizedList(new ArrayList<double[]>());
    /** Where the hand has the Moon (sensor px), and the frame's turn (degrees). */
    double X = 2830.4, Y = 1210.6, rot;
    private double prevFocus = Double.NaN;
    /** Frames given to the camera; the last frame's blur (px), gain, and the Moon's place. */
    int n;
    double lastBlur, lastGain, lastMx, lastMy;
    long lastNs;
    /** A station crossing the Moon: the fake clock's time (ns) it is nearest the Moon's middle, or {@link Long#MIN_VALUE} for none. */
    long crossNs = Long.MIN_VALUE;
    final double[] station;
    /** Frames drawn with the station's middle over the lit Moon, and the first and last of them. */
    int stationFrames, stationFirst = -1, stationLast = -1;
    /** Something the camera's frame code threw (null: nothing). */
    volatile Throwable failure;

    /**
     * @param sensitivity how much more light the sensor gives than the meter's textbook first guess expects
     */
    SimTele(MoonCamera cam, MoonSim sim, double sensitivity) {
        this.cam = cam;
        this.sim = sim;
        // the meter's guess for a full Moon overhead puts its highlands at the target: the sim's highlands at gain 1 are at
        // electrons / ePerDn of the range above the black level
        double range = sim.white - sim.black;
        double highlands = sim.electrons / sim.ePerDn / range;
        double fullLight = MoonMeter.start(0, 90, F_NUMBER, new MoonMeter.Limits()).light();
        gainPerLight = sensitivity * MoonMeter.TARGET / highlands / fullLight;
        sky = new short[W * H + 4096];
        Random r = new Random(11);
        double sd = sim.readE / sim.ePerDn;
        for (int k = 0; k < sky.length; k++) sky[k] = (short) (BLACK + Math.round(sd * r.nextGaussian()));
        asked.add(new double[]{cam.exposure.ns, cam.exposure.iso, cam.focus});
        // the ISS crossing in 0.65 s, a little tilted, a fifth of a radius from the middle; its truss 2.4 px a 100 m (500 km away)
        double rr = sim.rPx, speed = 2 * rr / 0.65, a = Math.toRadians(20);
        station = new double[]{-Math.sin(a) * 0.2 * rr, Math.cos(a) * 0.2 * rr, speed * Math.cos(a), speed * Math.sin(a), 28, 2.4};
    }

    /** The blur (sigma, px) the lens adds at a focus setting. */
    static double defocus(double focus) {
        return BLUR_PER_DIOPTER * Math.abs(focus - BEST_FOCUS);
    }

    /**
     * Takes one frame and gives it to the camera, waiting for the camera to finish with it. False if the camera is
     * closed (its thread gone).
     */
    boolean frame() throws InterruptedException {
        if (cam.closed || cam.h == null) return false;
        double[] s = asked.get(Math.max(0, asked.size() - 1 - DEPTH));
        double ns = s[0], iso = s[1], focus = s[2];
        double gain = gainPerLight * ns * 1e-9 * iso * (1 + 0.01 * rnd.nextGaussian());
        // the hand: pointing (towards the middle), trembling, turning a little
        double t = n / 30.0;
        X += (W / 2.0 - X) * 0.004 + 0.12 * rnd.nextGaussian();
        Y += (H / 2.0 - Y) * 0.004 + 0.12 * rnd.nextGaussian();
        double mx = X + 0.35 * Math.sin(2 * Math.PI * 9.1 * t + 0.4) + 0.2 * Math.sin(2 * Math.PI * 11.3 * t + 1.3);
        double my = Y + 0.3 * Math.sin(2 * Math.PI * 8.7 * t + 2.1) + 0.25 * Math.sin(2 * Math.PI * 12.1 * t);
        rot += 0.012 * rnd.nextGaussian();
        double u = rnd.nextDouble();
        double shake = u < 0.6 ? 0.2 + 0.3 * rnd.nextDouble() : u < 0.9 ? 0.6 + 0.4 * rnd.nextDouble() : 1.2 + 0.6 * rnd.nextDouble();
        double d = defocus(focus);
        double blur = Math.min(2.4, Math.sqrt(shake * shake + d * d));
        final long tNs = SystemClock.elapsedRealtimeNanos();
        // the sky, from a long strip of noise at a random place; then the Moon (and a station) drawn where it is
        System.arraycopy(sky, rnd.nextInt(4096), frame, 0, W * H);
        int half = (int) Math.ceil(1.25 * sim.rPx) + 4;
        int x0 = Math.max(0, Math.min(W - 2 * half, ((int) Math.floor(mx) - half) & ~1));
        int y0 = Math.max(0, Math.min(H - 2 * half, ((int) Math.floor(my) - half) & ~1));
        double tSec = 0;
        if (crossNs != Long.MIN_VALUE) {
            sim.station = station;
            tSec = (tNs - crossNs) / 1e9;
            double sx = station[0] + station[2] * tSec, sy = station[1] + station[3] * tSec;
            if (Math.hypot(sx, sy) < 0.97 * sim.rPx && sim.truth(1, sx, sy) > 0.05) {
                stationFrames++;
                if (stationFirst < 0) stationFirst = n;
                stationLast = n;
            }
        } else {
            sim.station = null;
        }
        int ww = 2 * half;
        short[] win = sim.frame(ww, ww, x0, y0, mx, my, rot, blur, gain, rnd, tSec);
        for (int j = 0; j < ww; j++) System.arraycopy(win, j * ww, frame, (y0 + j) * W + x0, ww);
        // the capture result (now and then none)
        boolean moving = !Double.isNaN(prevFocus) && Math.abs(focus - prevFocus) > 1e-6;
        prevFocus = focus;
        if (rnd.nextDouble() >= 0.04) {
            cam.results.put(tNs, new long[]{(long) ns, (long) iso, Float.floatToIntBits((float) focus),
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
        lastGain = gain;
        lastMx = mx;
        lastMy = my;
        lastNs = tNs;
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
