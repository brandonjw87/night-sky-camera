package com.brandon.nightsky;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowMediaPlayer;
import org.robolectric.shadows.util.DataSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * The opening film (2.0) in the app: when it plays and when it doesn't, that it never stands in the app's way (a tap,
 * a film that can't be played, a player that never shows a picture, the app left or closed while it plays), its sound,
 * its night-red look, how it fills screens of other shapes, and its setting. The player is Robolectric's stand-in: it
 * gets ready, plays and ends on the test's clock, and shows a picture when the test says so.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 36, qualifiers = "w412dp-h915dp-port-xhdpi")
public class Intro {
    static int passed;

    @Test
    public void intro() throws Exception {
        Monkey.out = new File(System.getProperty("shots.out"), "intro");
        Monkey.out.mkdirs();
        Monkey.log = new PrintStream(new FileOutputStream(new File(Monkey.out, "intro.log")), true, "UTF-8");
        Monkey.app = RuntimeEnvironment.getApplication();
        Monkey.sa = Shadows.shadowOf(Monkey.app);
        Monkey.sa.grantPermissions(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES);
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                Monkey.uncaught.add(new RuntimeException("on thread " + t.getName(), e));
            }
        });
        Monkey.root = SessionController.sessionsRoot(Monkey.app);
        Monkey.pristine = new File[0];
        Monkey.svc = Robolectric.buildService(CaptureService.class).create().get();
        Monkey.sa.setComponentNameAndServiceForBindService(new ComponentName(Monkey.app, CaptureService.class), Monkey.svc.onBind(new Intent()));
        media(IntroView.FILM_MS, 120);

        String[] names = {"wanted", "fromTheIcon", "tapSkips", "plays", "silentAndError", "pictureWithoutWord", "nightRed", "cover",
                "leaving", "advanced"};
        for (String n : names) {
            try {
                if (n.equals("wanted")) wanted();
                else if (n.equals("fromTheIcon")) fromTheIcon();
                else if (n.equals("tapSkips")) tapSkips();
                else if (n.equals("plays")) plays();
                else if (n.equals("silentAndError")) silentAndError();
                else if (n.equals("pictureWithoutWord")) pictureWithoutWord();
                else if (n.equals("nightRed")) nightRed();
                else if (n.equals("cover")) cover();
                else if (n.equals("leaving")) leaving();
                else advanced();
            } catch (Throwable e) {
                Monkey.problem(n + ": " + Monkey.trace(e));
            }
            Monkey.checkUncaught(n);
            // each part starts as the app does: nothing played, the film in colour, the player a working one
            ShadowChoreographer.setPaused(false);
            new Prefs(Monkey.app).setIntro(IntroView.COLOUR);
            new Prefs(Monkey.app).setSounds(true);
            media(IntroView.FILM_MS, 120);
        }
        Monkey.say("");
        Monkey.say("==== " + passed + " checks passed, " + Monkey.problems.size() + " problems");
        for (String p : Monkey.problems) Monkey.say("PROBLEM " + p);
        Monkey.log.close();
    }

    // ---- when it plays ----

    static void wanted() {
        Monkey.say("");
        Monkey.say("== when the film plays");
        Prefs prefs = new Prefs(Monkey.app);
        IntroView.forget();
        check(IntroView.wanted(prefs, null, icon()), "opened from the icon: it plays");
        check(!IntroView.wanted(prefs, new Bundle(), icon()), "the screen made again by Android (a saved state): not again");
        check(!IntroView.wanted(prefs, null, new Intent(Intent.ACTION_MAIN).setClass(Monkey.app, MainActivity.class)),
                "opened by something that isn't the launcher: no");
        check(!IntroView.wanted(prefs, null, Launches.launch("stack5", false)), "a shortcut: no");
        check(!IntroView.wanted(prefs, null, Launches.launch("arm", true)), "the tile, over the lock screen: no");
        check(!IntroView.wanted(prefs, null, Launches.notification("plan", false)), "a notification: no");
        check(!IntroView.wanted(prefs, null, Launches.notification("plan", true)), "the notification to re-arm the plan: no");
        check(!IntroView.wanted(prefs, null, icon().addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)), "from the recent apps: no");
        check(!IntroView.wanted(prefs, null, new Intent(Monkey.app, MainActivity.class)), "an intent without an action: no");
        check(!IntroView.wanted(prefs, null, null), "no intent at all: no");
        prefs.setIntro(IntroView.OFF);
        check(!IntroView.wanted(prefs, null, icon()), "turned off in the settings: no");
        prefs.setIntro(IntroView.RED);
        check(IntroView.wanted(prefs, null, icon()), "set to night red: it plays");
        prefs.setIntro(99);
        check(prefs.intro() == IntroView.COLOUR && IntroView.wanted(prefs, null, icon()), "a setting that makes no sense reads as in colour");
        prefs.setIntro(IntroView.COLOUR);
        // a session going on (the app's service says so): never over it
        SessionController ctl = Monkey.svc.controller();
        try {
            Object idle = Shots.field(ctl, "status");
            try {
                int no = 0, phases = 0;
                for (SessionController.Phase ph : SessionController.Phase.values()) {
                    if (ph == SessionController.Phase.IDLE) continue;
                    SessionController.Status busy = new SessionController.Status();
                    busy.phase = ph;
                    Shots.set(ctl, "status", busy);
                    phases++;
                    if (!IntroView.wanted(prefs, null, icon())) no++;
                }
                check(no == phases && phases == 12, "never while a session is on its way, running, developing or showing its photo ("
                        + no + " of " + phases + " phases)");
            } finally {
                Shots.set(ctl, "status", idle);
            }
            check(IntroView.wanted(prefs, null, icon()), "idle again: it plays");
            CaptureService was = CaptureService.instance;
            CaptureService.instance = null;
            try {
                check(IntroView.wanted(prefs, null, icon()), "before the app's service exists: it plays");
            } finally {
                CaptureService.instance = was;
            }
        } catch (Exception e) {
            Monkey.problem("wanted: " + Monkey.trace(e));
        }
    }

    static void fromTheIcon() throws Exception {
        Monkey.say("");
        Monkey.say("== opened from the icon (the player never shows a picture here)");
        IntroView.forget();
        long t0 = now();
        IntroView v = open(icon());
        check(v != null && Monkey.a.intro() == v, "the film is put up");
        if (v == null) return;
        ViewGroup content = (ViewGroup) Monkey.a.findViewById(android.R.id.content);
        check(content.getChildAt(content.getChildCount() - 1) == v && count() == 1, "on top of everything, once");
        check(v.getWidth() == content.getWidth() && v.getHeight() == content.getHeight() && v.getWidth() > 0,
                "covering the whole screen (" + v.getWidth() + "x" + v.getHeight() + ")");
        check(v.isClickable() && v.hasOnClickListeners(), "it takes the taps (nothing under it is pressed by accident)");
        String sig = Monkey.signature();
        int controls = Monkey.controls().size() - 1;
        check(sig.equals("screen:IDLE:Night Sky") && controls >= 20, "the first screen is built and ready under it (" + sig + ", "
                + controls + " controls)");
        check(v.outcome.equals("waiting") && texture(v).getAlpha() == 0f, "black until the film's first picture");
        until(t0 + IntroView.WATCHDOG_MS - 60);
        check(Monkey.a.intro() == v && v.outcome.equals("waiting"), "still waiting for a picture after " + (now() - t0) + " ms");
        until(t0 + IntroView.WATCHDOG_MS + 40);
        check(v.finished() && v.outcome.equals("no picture"), "given up after " + (now() - t0) + " ms without a picture (" + v.outcome + ")");
        check(v.getParent() == null && Monkey.a.intro() == null && count() == 0, "and taken off the screen");
        check(Monkey.signature().equals(sig), "the app is on its first screen");

        // once each time the app starts
        IntroView again = open(icon());
        check(again == null && count() == 0, "opened from the icon again while the app is still running: no film");
        IntroView.forget();
        new Prefs(Monkey.app).setIntro(IntroView.OFF);
        check(open(icon()) == null && count() == 0, "the app started with the film turned off: none");
        new Prefs(Monkey.app).setIntro(IntroView.COLOUR);
        check(open(Launches.launch("reading", false)) == null && count() == 0, "the app started by a shortcut: none");
        check(open(icon()) != null, "and the next opening from the icon in that same run plays it");
        Monkey.a.intro().finish("skipped");
    }

    static void tapSkips() throws Exception {
        Monkey.say("");
        Monkey.say("== a tap skips it");
        IntroView.forget();
        long t0 = now();
        IntroView v = open(icon());
        surface(v);
        MediaPlayer mp = player(v);
        Surface s = (Surface) Shots.field(v, "surface");
        until(t0 + 200);
        Shadows.shadowOf(mp).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        until(t0 + 700);
        check(v.outcome.equals("playing") && Monkey.a.intro() == v, "playing");
        Monkey.tap(v, 0.5f, 0.5f);
        check(v.finished() && v.outcome.equals("skipped"), "a tap in the middle of it: " + v.outcome);
        check(v.getParent() == null && count() == 0 && Monkey.a.intro() == null, "gone at once");
        check(s != null && Shadows.shadowOf(mp).getState() == ShadowMediaPlayer.State.END && player(v) == null
                && Shots.field(v, "surface") == null, "the player and its surface let go");
        long t1 = now();
        until(t1 + 5000);
        check(v.outcome.equals("skipped") && count() == 0, "nothing of it comes back later");

        // before any picture
        IntroView.forget();
        v = open(icon());
        Monkey.tap(v, 0.2f, 0.8f);
        check(v.finished() && v.outcome.equals("skipped") && count() == 0, "a tap before its first picture skips it too");
        // a tap after it has gone does nothing
        v.performClick();
        v.finish("ended");
        check(v.outcome.equals("skipped"), "and once gone, nothing changes what happened");
    }

    static void plays() throws Exception {
        Monkey.say("");
        Monkey.say("== the film plays through");
        IntroView.forget();
        final int[] doneRuns = {0};
        long t0 = now();
        IntroView v = open(icon());
        check(player(v) == null && Shots.field(v, "surface") == null, "no player until the screen gives it a surface");
        long ready = now() + 120;
        surface(v);
        MediaPlayer mp = player(v);
        ShadowMediaPlayer sm = Shadows.shadowOf(mp);
        check(sm.getState() == ShadowMediaPlayer.State.PREPARING, "the film file opens from the app's resources; the player is getting ready");
        // (on this computer a surface with no screen behind it never counts as valid, so only that there is one)
        check(Shots.field(v, "surface") instanceof Surface, "with a surface to draw on");
        until(ready + 20);
        check(sm.getState() == ShadowMediaPlayer.State.STARTED, "ready: it starts (" + sm.getState() + ")");
        check(Math.abs(sm.getLeftVolume() - 0.7f) < 1e-6 && Math.abs(sm.getRightVolume() - 0.7f) < 1e-6, "its sound at 0.7 of the media volume");
        check(v.outcome.equals("waiting") && texture(v).getAlpha() == 0f, "still black: no picture yet");
        sm.invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        long first = now();
        check(v.outcome.equals("playing"), "the first picture: " + v.outcome);
        until(first + 150);
        check(texture(v).getAlpha() == 1f && v.getAlpha() == 1f, "the film is shown");
        until(first + IntroView.FLASH_MS - 60);
        check(Shadows.shadowOf(v).lastHapticFeedbackPerformed() == -1, "no tick before the flash");
        until(first + IntroView.FLASH_MS + 30);
        check(Shadows.shadowOf(v).lastHapticFeedbackPerformed() == HapticFeedbackConstants.CONTEXT_CLICK, "a tick in the hand with the flash, "
                + (now() - first) + " ms in");
        until(first + IntroView.FADE_AT_MS - 60);
        check(!v.finished() && v.getAlpha() == 1f && Monkey.a.intro() == v, "whole until its last moments (" + (now() - first) + " ms in)");
        until(first + IntroView.FADE_AT_MS + IntroView.FADE_MS + 80);
        check(v.finished() && v.outcome.equals("ended"), "ended " + (now() - t0) + " ms after the app was opened (" + v.outcome + ")");
        check(v.getAlpha() == 0f, "faded out to the app");
        check(v.getParent() == null && count() == 0 && Monkey.a.intro() == null, "and off the screen");
        check(sm.getState() == ShadowMediaPlayer.State.END && player(v) == null && Shots.field(v, "surface") == null, "the player and its surface let go");
        check(now() - t0 <= IntroView.LATEST_MS, "well inside its longest time");
        until(t0 + IntroView.LATEST_MS + 500);
        check(v.outcome.equals("ended") && count() == 0 && Monkey.signature().equals("screen:IDLE:Night Sky"), "the app is on its first screen");

        // the same, told when it has gone
        IntroView.forget();
        IntroView w = IntroView.play(Monkey.a, IntroView.COLOUR, false, new Runnable() {
            @Override
            public void run() {
                doneRuns[0]++;
            }
        });
        Monkey.layout();
        surface(w);
        MediaPlayer mp2 = player(w);
        check(Shadows.shadowOf(mp2).getLeftVolume() == 0f && Shadows.shadowOf(mp2).getRightVolume() == 0f, "played without sound when asked");
        long t1 = now();
        until(t1 + 140);
        Shadows.shadowOf(mp2).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        check(doneRuns[0] == 0, "whoever asked isn't told before it's over");
        until(now() + IntroView.FADE_AT_MS + IntroView.FADE_MS + 80);
        check(w.finished() && doneRuns[0] == 1, "told once when it's over (" + doneRuns[0] + ")");
        w.finish("skipped");
        check(doneRuns[0] == 1 && w.outcome.equals("ended"), "and only once");
    }

    static void silentAndError() throws Exception {
        Monkey.say("");
        Monkey.say("== its sound, and a film that can't be played");
        Prefs prefs = new Prefs(Monkey.app);
        AudioManager am = (AudioManager) Monkey.app.getSystemService(Context.AUDIO_SERVICE);
        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        Shadows.shadowOf(am).setIsMusicActive(false);
        check(IntroView.soundWanted(Monkey.app, prefs), "the ringer on, the app's sounds on, nothing else playing: with its sound");
        am.setRingerMode(AudioManager.RINGER_MODE_VIBRATE);
        check(!IntroView.soundWanted(Monkey.app, prefs), "the phone on vibrate: silent");
        am.setRingerMode(AudioManager.RINGER_MODE_SILENT);
        check(!IntroView.soundWanted(Monkey.app, prefs), "the phone on silent: silent");
        IntroView.forget();
        IntroView v = open(icon());
        surface(v);
        check(Shadows.shadowOf(player(v)).getLeftVolume() == 0f && Shadows.shadowOf(player(v)).getRightVolume() == 0f,
                "opened with the phone on silent: the player's volume is 0");
        v.finish("skipped");
        am.setRingerMode(AudioManager.RINGER_MODE_NORMAL);
        prefs.setSounds(false);
        check(!IntroView.soundWanted(Monkey.app, prefs), "the app's sounds turned off: silent");
        prefs.setSounds(true);
        Shadows.shadowOf(am).setIsMusicActive(true);
        check(!IntroView.soundWanted(Monkey.app, prefs), "music or a podcast playing: it doesn't talk over it");
        Shadows.shadowOf(am).setIsMusicActive(false);
        check(IntroView.soundWanted(Monkey.app, prefs), "and with its sound again afterwards");

        // the player reports an error part of the way
        IntroView.forget();
        long t0 = now();
        v = open(icon());
        surface(v);
        MediaPlayer mp = player(v);
        until(t0 + 300);
        Shadows.shadowOf(mp).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        until(t0 + 600);
        Shadows.shadowOf(mp).invokeErrorListener(MediaPlayer.MEDIA_ERROR_UNKNOWN, -1010);
        check(v.finished() && v.outcome.equals("error: 1/-1010"), "the player failing while it plays: " + v.outcome);
        check(v.getParent() == null && count() == 0 && Shadows.shadowOf(mp).getState() == ShadowMediaPlayer.State.END, "taken away at once, the player let go");

        // the film can't be opened at all
        media(-1, 0);
        IntroView.forget();
        v = open(icon());
        check(v != null && !v.finished(), "a film that can't be opened: put up as usual");
        surface(v);
        check(v.finished() && v.outcome.startsWith("error: "), "and taken away the moment that's found out (" + v.outcome + ")");
        check(v.getParent() == null && count() == 0 && Monkey.signature().equals("screen:IDLE:Night Sky"), "the app is on its first screen");

        // the player never gets ready
        media(IntroView.FILM_MS, -1);
        IntroView.forget();
        t0 = now();
        v = open(icon());
        surface(v);
        mp = player(v);
        until(t0 + IntroView.WATCHDOG_MS - 60);
        check(!v.finished() && Shadows.shadowOf(mp).getState() == ShadowMediaPlayer.State.PREPARING, "a player that never gets ready: waited for");
        until(t0 + IntroView.WATCHDOG_MS + 40);
        check(v.finished() && v.outcome.equals("no picture") && count() == 0, "for a second and a half, no longer (" + v.outcome + ")");
        check(Shadows.shadowOf(mp).getState() == ShadowMediaPlayer.State.END, "and let go");

        // ready, playing, but no picture ever arrives
        media(IntroView.FILM_MS, 120);
        IntroView.forget();
        t0 = now();
        v = open(icon());
        surface(v);
        until(t0 + IntroView.WATCHDOG_MS + 40);
        check(v.finished() && v.outcome.equals("no picture") && count() == 0, "a player that plays without ever showing a picture: given up too");
    }

    static void pictureWithoutWord() throws Exception {
        Monkey.say("");
        Monkey.say("== a picture without the player saying so, and a fade that doesn't finish");
        IntroView.forget();
        long t0 = now();
        IntroView v = open(icon());
        surface(v);
        until(t0 + 200);
        check(v.outcome.equals("waiting"), "playing, no word of a picture yet");
        // the screen draws the film's first picture (some players never send the word)
        v.onSurfaceTextureUpdated(null);
        long first = now();
        check(v.outcome.equals("playing"), "the surface's first picture starts it: " + v.outcome);
        v.onSurfaceTextureUpdated(null);
        Shadows.shadowOf(player(v)).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        until(first + IntroView.FLASH_MS + 30);
        check(Shadows.shadowOf(v).lastHapticFeedbackPerformed() == HapticFeedbackConstants.CONTEXT_CLICK, "timed from that picture (the flash's tick)");
        until(first + IntroView.FADE_AT_MS + IntroView.FADE_MS + 80);
        check(v.finished() && v.outcome.equals("ended") && count() == 0, "and it ends as usual, " + (now() - first) + " ms after it");

        // the fade is cut short (the screen stops drawing): the last backstop takes the film away
        ShadowChoreographer.setPaused(true);
        try {
            IntroView.forget();
            t0 = now();
            v = open(icon());
            surface(v);
            until(t0 + 140);
            v.onSurfaceTextureUpdated(null);
            first = now();
            until(first + IntroView.FADE_AT_MS + IntroView.FADE_MS / 2);
            float mid = v.getAlpha();
            check(!v.finished() && mid > 0.05f && mid < 0.95f, String.format(Locale.US, "half way through its fade it's half there (%.2f)", mid));
            v.animate().cancel();
            until(first + IntroView.FADE_AT_MS + IntroView.FADE_MS + 600);
            check(!v.finished() && v.getParent() != null, "the fade cut short: still up " + (now() - t0) + " ms after opening");
            until(t0 + IntroView.LATEST_MS - 60);
            check(!v.finished(), "up to its longest time");
            until(t0 + IntroView.LATEST_MS + 40);
            check(v.finished() && v.outcome.equals("ended") && v.getParent() == null && count() == 0, "gone " + (now() - t0)
                    + " ms after opening, whatever happened");
        } finally {
            ShadowChoreographer.setPaused(false);
        }
    }

    // ---- how it looks ----

    static void nightRed() throws Exception {
        Monkey.say("");
        Monkey.say("== night red");
        Paint p = IntroView.nightRed();
        check(p.getColorFilter() instanceof ColorMatrixColorFilter, "a colour matrix");
        ColorMatrix m = new ColorMatrix();
        ((ColorMatrixColorFilter) p.getColorFilter()).getColorMatrix(m);
        float[] a = m.getArray();
        float r = a[0] + a[1] + a[2], g = a[5] + a[6] + a[7], b = a[10] + a[11] + a[12];
        check(Math.abs(r - 0.772f) < 0.002f && Math.abs(g - 0.193f) < 0.002f && Math.abs(b - 0.179f) < 0.002f,
                String.format(Locale.US, "white becomes the app's red, a little dimmer (%.3f %.3f %.3f)", r, g, b));
        check(a[3] == 0 && a[4] == 0 && a[9] == 0 && a[14] == 0 && a[18] == 1f && a[19] == 0, "nothing added, nothing see-through");
        // every colour lands on the same red: only its brightness is kept
        float[][] cols = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}, {0.3f, 0.6f, 0.9f}};
        boolean sameHue = true, byLuma = true;
        for (float[] c : cols) {
            float rr = a[0] * c[0] + a[1] * c[1] + a[2] * c[2], gg = a[5] * c[0] + a[6] * c[1] + a[7] * c[2];
            float bb = a[10] * c[0] + a[11] * c[1] + a[12] * c[2];
            float luma = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
            if (Math.abs(gg / rr - g / r) > 1e-4 || Math.abs(bb / rr - b / r) > 1e-4) sameHue = false;
            if (Math.abs(rr - r * luma) > 1e-4) byLuma = false;
        }
        check(sameHue && byLuma, "every colour becomes that red, as bright as it looked");

        new Prefs(Monkey.app).setIntro(IntroView.RED);
        IntroView.forget();
        IntroView v = open(icon());
        Paint lp = layerPaint(texture(v));
        check(lp != null && lp.getColorFilter() instanceof ColorMatrixColorFilter, "set to night red: the film is drawn through it");
        v.finish("skipped");
        new Prefs(Monkey.app).setIntro(IntroView.COLOUR);
        IntroView.forget();
        v = open(icon());
        lp = layerPaint(texture(v));
        check(lp == null || lp.getColorFilter() == null, "in colour: drawn as it is");
        v.finish("skipped");
    }

    static void cover() throws Exception {
        Monkey.say("");
        Monkey.say("== screens of other shapes");
        check(scale(1080, 2400)[0] == 1f && scale(1080, 2400)[1] == 1f, "a screen of the film's own shape: as it is");
        float[] px = scale(1344, 2992);
        check(px[1] == 1f && px[0] > 1f && px[0] < 1.004f, String.format(Locale.US, "the Pixel 10 Pro XL's 1344 x 2992: the film fills it, "
                + "%.1f%% of its width off the two sides together", (px[0] - 1) * 100));
        float[] narrow = scale(1080, 2640);
        check(narrow[1] == 1f && Math.abs(narrow[0] - 1.1f) < 1e-4, "a taller screen: its height filled, the sides cut");
        float[] wide = scale(1080, 1920);
        check(wide[0] == 1f && Math.abs(wide[1] - 1.25f) < 1e-4, "a wider screen: its width filled, top and bottom cut");
        float[] land = scale(2400, 1080);
        check(land[0] == 1f && land[1] > 4.9f, "held sideways: its width filled");
        check(IntroView.cover(0, 0).isIdentity() && IntroView.cover(-5, 10).isIdentity(), "no size yet: nothing done");
        // the cut is even on both sides: the middle of the film stays in the middle of the screen
        float[] pt = {672, 1496};
        IntroView.cover(1344, 2992).mapPoints(pt);
        check(Math.abs(pt[0] - 672) < 1e-3 && Math.abs(pt[1] - 1496) < 1e-3, "the film's middle stays in the middle");

        IntroView.forget();
        IntroView v = open(icon());
        TextureView t = texture(v);
        surface(v);
        Matrix got = t.getTransform(null);
        float[] gv = new float[9], want = new float[9];
        got.getValues(gv);
        IntroView.cover(v.getWidth(), v.getHeight()).getValues(want);
        boolean same = true;
        for (int i = 0; i < 9; i++) if (Math.abs(gv[i] - want[i]) > 1e-5) same = false;
        check(same, "the film's view is given that fit for this screen (" + v.getWidth() + "x" + v.getHeight() + ")");
        v.onSurfaceTextureSizeChanged(null, 1000, 1000);
        t.getTransform(null).getValues(gv);
        IntroView.cover(1000, 1000).getValues(want);
        same = true;
        for (int i = 0; i < 9; i++) if (Math.abs(gv[i] - want[i]) > 1e-5) same = false;
        check(same, "and again when the screen's size changes");
        v.finish("skipped");
    }

    static float[] scale(int w, int h) {
        float[] v = new float[9];
        IntroView.cover(w, h).getValues(v);
        return new float[]{v[Matrix.MSCALE_X], v[Matrix.MSCALE_Y]};
    }

    // ---- leaving while it plays ----

    static void leaving() throws Exception {
        Monkey.say("");
        Monkey.say("== the app left or closed while it plays");
        IntroView.forget();
        long t0 = now();
        IntroView v = open(icon());
        surface(v);
        MediaPlayer mp = player(v);
        until(t0 + 200);
        Shadows.shadowOf(mp).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        until(t0 + 800);
        v.dispatchWindowVisibilityChanged(View.GONE);
        check(v.finished() && v.outcome.equals("ended") && Shadows.shadowOf(mp).getState() == ShadowMediaPlayer.State.END,
                "another app in front while it plays: it stops there (" + v.outcome + "), nothing plays on behind");
        check(v.getParent() == null && count() == 0, "and isn't there on coming back");

        IntroView.forget();
        v = open(icon());
        v.dispatchWindowVisibilityChanged(View.INVISIBLE);
        check(v.finished() && v.outcome.equals("skipped") && count() == 0, "left before its first picture: skipped");

        // the surface taken back by Android
        IntroView.forget();
        t0 = now();
        v = open(icon());
        surface(v);
        mp = player(v);
        boolean released = v.onSurfaceTextureDestroyed(null);
        check(released && v.finished() && v.outcome.equals("no picture") && Shadows.shadowOf(mp).getState() == ShadowMediaPlayer.State.END,
                "its surface taken away before a picture: given up (" + v.outcome + ")");
        v.onSurfaceTextureAvailable(new SurfaceTexture(0), 100, 100);
        check(player(v) == null, "a surface arriving after it has gone starts nothing");

        // the screen closed
        IntroView.forget();
        t0 = now();
        v = open(icon());
        surface(v);
        mp = player(v);
        until(t0 + 200);
        Shadows.shadowOf(mp).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
        until(t0 + 500);
        Monkey.ctrl.pause().stop().destroy();
        check(v.finished() && Shadows.shadowOf(mp).getState() == ShadowMediaPlayer.State.END, "the app closed while it plays: the player let go ("
                + v.outcome + ")");
        long t1 = now();
        until(t1 + 6000);
        Monkey.checkUncaught("the film's timers after the screen closed");
        check(v.finished(), "and its timers do nothing afterwards");
        Monkey.ctrl = null;
        open(new Intent(Monkey.app, MainActivity.class));
    }

    // ---- its setting ----

    static void advanced() throws Exception {
        Monkey.say("");
        Monkey.say("== its setting (Advanced)");
        Prefs prefs = new Prefs(Monkey.app);
        prefs.setAdvancedOpen(true);
        prefs.setIntro(IntroView.COLOUR);
        IntroView.forget();
        check(open(new Intent(Monkey.app, MainActivity.class)) == null && count() == 0, "the app opened some other way: no film");
        List<View> cs = Monkey.controls();
        int at = -1;
        for (int i = 0; i < cs.size(); i++) if (Monkey.label(cs.get(i)).equals("In colour")) at = i;
        check(at >= 0 && at + 3 < cs.size() && Monkey.label(cs.get(at + 1)).equals("Night red") && Monkey.label(cs.get(at + 2)).equals("Off")
                && Monkey.label(cs.get(at + 3)).equals("Play it now"), "In colour, Night red, Off, then Play it now");
        if (at < 0) return;
        Button colour = (Button) cs.get(at), red = (Button) cs.get(at + 1), off = (Button) cs.get(at + 2), play = (Button) cs.get(at + 3);
        check(bold(colour) && !bold(red) && !bold(off), "In colour is the chosen one at first");
        red.performClick();
        idle(100);
        check(prefs.intro() == IntroView.RED && bold(red) && !bold(colour) && !bold(off), "Night red chosen and kept");
        check(count() == 0, "choosing plays nothing");
        play.performClick();
        Monkey.layout();
        IntroView v = Monkey.a.intro();
        check(v != null && count() == 1, "Play it now puts the film up");
        if (v != null) {
            Paint lp = layerPaint(texture(v));
            check(lp != null && lp.getColorFilter() instanceof ColorMatrixColorFilter, "in night red");
            play.performClick();
            check(count() == 1 && Monkey.a.intro() == v, "pressed again while it's up: still the one");
            Monkey.tap(v, 0.5f, 0.5f);
            check(v.finished() && count() == 0, "a tap skips it here too");
        }
        off.performClick();
        idle(100);
        check(prefs.intro() == IntroView.OFF && bold(off) && !bold(red) && !bold(colour), "Off chosen and kept");
        play.performClick();
        Monkey.layout();
        v = Monkey.a.intro();
        check(v != null && count() == 1, "Play it now still plays it when it's off");
        if (v != null) {
            Paint lp = layerPaint(texture(v));
            check(lp == null || lp.getColorFilter() == null, "in colour");
            long t0 = now();
            surface(v);
            until(t0 + 140);
            Shadows.shadowOf(player(v)).invokeInfoListener(MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START, 0);
            until(now() + IntroView.FADE_AT_MS + IntroView.FADE_MS + 80);
            check(v.finished() && v.outcome.equals("ended") && count() == 0, "and it plays to its end");
        }
        IntroView.forget();
        check(open(icon()) == null && count() == 0, "with Off kept, the next start from the icon shows none");
        // the screen made again shows the kept choice
        cs = Monkey.controls();
        at = -1;
        for (int i = 0; i < cs.size(); i++) if (Monkey.label(cs.get(i)).equals("In colour")) at = i;
        check(at >= 0 && bold((Button) cs.get(at + 2)) && !bold((Button) cs.get(at)), "and the setting shows Off");
        if (at >= 0) {
            cs.get(at).performClick();
            idle(100);
        }
        check(prefs.intro() == IntroView.COLOUR, "back to In colour");
        IntroView.forget();
        IntroView last = open(icon());
        check(last != null, "and it plays at the next start");
        if (last != null) last.finish("skipped");
        prefs.setAdvancedOpen(false);
    }

    static boolean bold(Button b) {
        return b.getTypeface() != null && b.getTypeface().equals(Typeface.DEFAULT_BOLD);
    }

    // ---- helpers ----

    /** The player stand-in's film: {@code durationMs} long (-1: can't be opened), ready after {@code prepareMs} (-1: never). */
    static void media(final int durationMs, final int prepareMs) {
        ShadowMediaPlayer.setMediaInfoProvider(new ShadowMediaPlayer.MediaInfoProvider() {
            @Override
            public ShadowMediaPlayer.MediaInfo get(DataSource d) {
                return durationMs < 0 ? null : new ShadowMediaPlayer.MediaInfo(durationMs, prepareMs);
            }
        });
    }

    /** As the launcher opens the app from its icon. */
    static Intent icon() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(Monkey.app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    /** A new activity for {@code i} (the last one closed first); the film it put up, or null. */
    static IntroView open(Intent i) throws Exception {
        if (Monkey.ctrl != null) {
            try {
                Monkey.ctrl.pause().stop().destroy();
            } catch (Throwable ignored) {
            }
        }
        Monkey.ctrl = Robolectric.buildActivity(MainActivity.class, i).setup();
        Monkey.a = Monkey.ctrl.get();
        Monkey.layout();
        return Monkey.a.intro();
    }

    /** Films on the screen. */
    static int count() {
        ViewGroup content = (ViewGroup) Monkey.a.findViewById(android.R.id.content);
        int n = 0;
        for (int i = 0; i < content.getChildCount(); i++) if (content.getChildAt(i) instanceof IntroView) n++;
        return n;
    }

    /** The screen hands the film's view its surface (Robolectric draws no TextureView, so it never does by itself). */
    static void surface(IntroView v) {
        v.onSurfaceTextureAvailable(new SurfaceTexture(0), v.getWidth(), v.getHeight());
    }

    static MediaPlayer player(IntroView v) throws Exception {
        return (MediaPlayer) Shots.field(v, "player");
    }

    static TextureView texture(IntroView v) throws Exception {
        return (TextureView) Shots.field(v, "texture");
    }

    static Paint layerPaint(View v) throws Exception {
        Field f = View.class.getDeclaredField("mLayerPaint");
        f.setAccessible(true);
        return (Paint) f.get(v);
    }

    static long now() {
        return SystemClock.uptimeMillis();
    }

    /** Runs the app's main thread up to the clock time {@code t} (animations move the clock on by themselves). */
    static void until(long t) {
        while (now() < t) ShadowLooper.idleMainLooper(Math.min(10, t - now()), TimeUnit.MILLISECONDS);
    }

    static void idle(long ms) {
        until(now() + ms);
    }

    static void check(boolean ok, String what) {
        if (ok) {
            passed++;
            Monkey.say("  ok  " + what);
        } else {
            Monkey.problem(what);
        }
    }
}
