package com.brandon.flashintro;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.AudioManager;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import com.brandon.intro.FlashIntroView;

import java.util.Locale;

/**
 * Demo screen for the camera flash intro: it plays once on launch, then a camera-style bar offers
 * the standard or the red night look, replay, quarter-speed slow motion, sound on or off, and a scrubber
 * to stop on any frame. Turn the phone sideways to see the one-line layout of the name.
 */
public final class MainActivity extends Activity implements FlashIntroView.Listener {
    private static final String PREFS = "flash_intro", KEY_NIGHT = "night_look";
    private static final long LOOK_FADE_MS = 260;   // the same crossfade as the picture's
    private static final TimeInterpolator SMOOTHSTEP = new TimeInterpolator() {
        @Override
        public float getInterpolation(float k) {
            return k * k * (3f - 2f * k);
        }
    };

    private SharedPreferences prefs;
    private FrameLayout root;
    private FlashIntroView intro;
    private LinearLayout bar;
    private FrameLayout lookSlot;        // sideways, the look switch sits here, left of the buttons
    private GradientDrawable barGround, looksGround;
    private RadioGroup looks;
    private RadioButton standardLook, nightLook;
    private ControlButton shutter, slowButton, soundButton;
    private TextView hint, timeText, endText, shutterLabel, slowLabel, soundLabel, errorText;
    private SeekBar scrub;
    private boolean slow, soundOn = true, playedOnce, autoPlayed, scrubbing, landscape, night;
    private float shownNight;            // how far the bar has faded to the night colours
    private ValueAnimator lookFade;

    @Override
    @SuppressWarnings("deprecation") // setDecorFitsSystemWindows: needed before Android 15, which draws edge to edge anyway
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);
        getWindow().setDecorFitsSystemWindows(false);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        night = prefs.getBoolean(KEY_NIGHT, false);
        root = new FrameLayout(this);
        intro = new FlashIntroView(this);
        intro.setNight(night, false);
        intro.setListener(this);
        intro.setContentDescription("Camera flash intro: Made by: Brandon J. Wallace");
        intro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (intro.isPlaying()) intro.pause();
                else play();
            }
        });
        root.addView(intro, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bar = buildBar();
        bar.setAlpha(0f);
        bar.setVisibility(View.INVISIBLE);
        root.addView(bar, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        errorText = text(15);
        errorText.setGravity(Gravity.CENTER);
        errorText.setVisibility(View.GONE);
        int pad = dp(28);
        errorText.setPadding(pad, pad, pad, pad);
        root.addView(errorText, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                Insets i = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                bar.setPadding(dp(16) + i.left, dp(landscape ? 14 : 40), dp(16) + i.right, dp(landscape ? 10 : 18) + i.bottom);
                return insets;
            }
        });
        setContentView(root);

        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.hide(WindowInsets.Type.systemBars());
            c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
        applyLook(night ? 1f : 0f);
        applyOrientation(getResources().getConfiguration());
        updateUi(FlashIntroView.LENGTH);
    }

    private LinearLayout buildBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        barGround = new GradientDrawable();
        barGround.setOrientation(GradientDrawable.Orientation.BOTTOM_TOP);
        b.setBackground(barGround);
        b.setClickable(true);   // taps on the bar never reach the intro behind it

        hint = text(14);
        hint.setGravity(Gravity.CENTER);
        hint.setText("Turn the phone sideways to see the one-line layout.");
        b.addView(hint, wrap());

        looks = new RadioGroup(this);
        looks.setOrientation(LinearLayout.HORIZONTAL);
        looks.setPadding(dp(3), dp(3), dp(3), dp(3));
        looksGround = pill();
        looks.setBackground(looksGround);
        standardLook = lookButton("Standard");
        nightLook = lookButton("Night");
        looks.addView(standardLook, new RadioGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        RadioGroup.LayoutParams nightLp = new RadioGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nightLp.setMarginStart(dp(4));
        looks.addView(nightLook, nightLp);
        looks.check(night ? nightLook.getId() : standardLook.getId());
        looks.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup g, int id) {
                boolean n = id == nightLook.getId();
                if (n != night) setLook(n);
            }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        timeText = text(13);
        timeText.setTypeface(Typeface.MONOSPACE);
        timeText.setMinEms(4);
        row.addView(timeText, wrap());
        scrub = new SeekBar(this);
        scrub.setMax(Math.round(FlashIntroView.LENGTH * 1000));
        scrub.setContentDescription("Frame time");
        scrub.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (bar.getAlpha() < 1f) {   // a drag that starts while the bar fades out keeps it up
                    bar.animate().cancel();
                    bar.setVisibility(View.VISIBLE);
                    bar.setAlpha(1f);
                    bar.setTranslationY(0);
                }
                float t = progress / 1000f;
                intro.showFrame(t);
                playedOnce = true;
                updateUi(t);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                scrubbing = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                scrubbing = false;
            }
        });
        row.addView(scrub, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        endText = text(13);
        endText.setTypeface(Typeface.MONOSPACE);
        endText.setText(seconds(FlashIntroView.LENGTH));
        row.addView(endText, wrap());
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(6);
        rowLp.bottomMargin = dp(4);
        b.addView(row, rowLp);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        slowButton = new ControlButton(this, ControlButton.SLOW);
        slowButton.setContentDescription("Slow motion, quarter speed");
        slowButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                slow = !slow;
                updateUi(-1);
            }
        });
        shutter = new ControlButton(this, ControlButton.SHUTTER);
        shutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                play();
            }
        });
        soundButton = new ControlButton(this, ControlButton.SOUND);
        soundButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                soundOn = !soundOn;
                updateUi(-1);
            }
        });
        slowLabel = label("SLOW-MO");
        shutterLabel = label("PLAY");
        soundLabel = label("SOUND ON");
        controls.addView(column(slowButton, 48, slowLabel));
        controls.addView(column(shutter, 76, shutterLabel));
        controls.addView(column(soundButton, 48, soundLabel));
        // two equal slots either side keep the buttons centred, whatever sits in the left one
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        lookSlot = new FrameLayout(this);
        bottom.addView(lookSlot, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        bottom.addView(controls, wrap());
        bottom.addView(new View(this), new LinearLayout.LayoutParams(0, 0, 1f));
        b.addView(bottom, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private LinearLayout column(View button, int sizeDp, TextView label) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        blp.topMargin = dp((76 - sizeDp) / 2);
        blp.bottomMargin = dp((76 - sizeDp) / 2 + 6);
        col.addView(button, blp);
        col.addView(label, wrap());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT);
        col.setLayoutParams(clp);
        return col;
    }

    /** One side of the look switch. Its gold fill comes from {@link #applyLook}, so it fades with the picture. */
    private RadioButton lookButton(String name) {
        RadioButton v = new RadioButton(this);
        v.setId(View.generateViewId());
        v.setButtonDrawable(null);
        v.setText(name);
        v.setAllCaps(true);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setLetterSpacing(0.14f);
        v.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        v.setGravity(Gravity.CENTER);
        v.setMinWidth(0);
        v.setMinimumWidth(0);
        v.setMinHeight(dp(36));
        v.setMinimumHeight(dp(36));
        v.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable mask = pill();
        mask.setColor(0xFFFFFFFF);
        v.setBackground(new RippleDrawable(ColorStateList.valueOf(0), pill(), mask));
        return v;
    }

    private GradientDrawable pill() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(999));
        return d;
    }

    private void setLook(boolean n) {
        night = n;
        prefs.edit().putBoolean(KEY_NIGHT, n).apply();
        boolean fade = ValueAnimator.areAnimatorsEnabled();   // off when the phone is set to remove animations
        intro.setNight(n, fade);
        if (lookFade != null) lookFade.cancel();
        if (!fade) {
            applyLook(n ? 1f : 0f);
            return;
        }
        lookFade = ValueAnimator.ofFloat(shownNight, n ? 1f : 0f);
        lookFade.setDuration(LOOK_FADE_MS);
        lookFade.setInterpolator(SMOOTHSTEP);
        lookFade.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                applyLook((Float) a.getAnimatedValue());
            }
        });
        lookFade.start();
    }

    /** Colours the screen {@code k} of the way from the standard look (0) to the night look (1). */
    private void applyLook(float k) {
        shownNight = k;
        Colors c = Colors.mix(Colors.STANDARD, Colors.NIGHT, k);
        root.setBackgroundColor(c.ground);
        barGround.setColors(new int[]{c.scrim, c.scrim, c.scrimClear()});
        hint.setTextColor(c.muted);
        timeText.setTextColor(c.ink);
        endText.setTextColor(c.muted);
        scrub.setProgressTintList(ColorStateList.valueOf(c.gold));
        scrub.setThumbTintList(ColorStateList.valueOf(c.gold));
        scrub.setProgressBackgroundTintList(ColorStateList.valueOf(c.line));
        shutter.setColors(c);
        slowButton.setColors(c);
        soundButton.setColors(c);
        shutterLabel.setTextColor(c.gold);
        slowLabel.setTextColor(c.muted);
        soundLabel.setTextColor(c.muted);
        errorText.setTextColor(c.ink);
        looksGround.setColor(c.glass);
        looksGround.setStroke(dp(1), c.line);
        paintLookButton(standardLook, c, 1f - k);
        paintLookButton(nightLook, c, k);
    }

    /** {@code on}: how much of the gold "selected" fill the button shows, 0 to 1. */
    private static void paintLookButton(RadioButton v, Colors c, float on) {
        RippleDrawable ripple = (RippleDrawable) v.getBackground();
        ripple.setColor(ColorStateList.valueOf((c.ink & 0x00FFFFFF) | 0x33000000));
        ((GradientDrawable) ripple.getDrawable(0)).setColor((c.gold & 0x00FFFFFF) | (Math.round(255 * on) << 24));
        v.setTextColor(Colors.mix(c.muted, c.goldInk, on));
    }

    private void play() {
        errorText.setVisibility(View.GONE);
        playedOnce = true;
        intro.play(slow, soundOn);
        hideBar();
    }

    private void updateUi(float t) {
        if (t >= 0) {
            timeText.setText(seconds(t));
            if (!scrubbing) scrub.setProgress(Math.round(t * 1000));
        }
        shutter.setReplay(playedOnce);
        shutter.setContentDescription(playedOnce ? "Replay intro" : "Play intro");
        shutterLabel.setText(playedOnce ? "REPLAY" : "PLAY");
        slowButton.setOn(slow);
        soundButton.setOn(soundOn);
        soundButton.setContentDescription(soundOn ? "Sound on" : "Sound off");
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        boolean ringerOff = am != null && am.getRingerMode() != AudioManager.RINGER_MODE_NORMAL;
        soundLabel.setText(!soundOn ? "SOUND OFF" : slow ? "OFF IN SLOW-MO" : ringerOff ? "PHONE ON SILENT" : "SOUND ON");
    }

    private void showBar(long delayMs) {
        bar.animate().cancel();
        bar.setVisibility(View.VISIBLE);
        bar.setTranslationY(bar.getAlpha() > 0.5f ? 0 : dp(12));
        bar.animate().alpha(1f).translationY(0).setStartDelay(delayMs).setDuration(220).start();
    }

    private void hideBar() {
        bar.animate().cancel();
        bar.animate().alpha(0f).translationY(dp(12)).setStartDelay(0).setDuration(160).withEndAction(new Runnable() {
            @Override
            public void run() {
                bar.setVisibility(View.INVISIBLE);
            }
        }).start();
    }

    /**
     * Sideways the bar slims down to the scrubber over one row of buttons, with the look switch at the row's
     * start, so it stays clear of the one-line name.
     */
    private void applyOrientation(Configuration cfg) {
        landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int portraitOnly = landscape ? View.GONE : View.VISIBLE;
        hint.setVisibility(portraitOnly);
        slowLabel.setVisibility(portraitOnly);
        shutterLabel.setVisibility(portraitOnly);
        soundLabel.setVisibility(portraitOnly);
        ViewGroup parent = (ViewGroup) looks.getParent();
        if (parent != null) parent.removeView(looks);
        if (landscape) {
            lookSlot.addView(looks, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.START | Gravity.CENTER_VERTICAL));
        } else {
            LinearLayout.LayoutParams lp = wrap();
            lp.topMargin = dp(10);
            bar.addView(looks, 1, lp);   // under the hint, over the scrubber
        }
        bar.requestApplyInsets();
    }

    // ---------------------------------------------------------------- FlashIntroView.Listener

    @Override
    public void onIntroReady() {
        if (autoPlayed) return;
        autoPlayed = true;
        play();
    }

    @Override
    public void onIntroStopped(float t, boolean finished) {
        updateUi(t);
        showBar(finished ? 450 : 0);
    }

    @Override
    public void onIntroError(String message) {
        errorText.setText("The intro couldn't start on this phone.\n\n" + message);
        errorText.setVisibility(View.VISIBLE);
        intro.setVisibility(View.GONE);
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        intro.onResume();
        updateUi(-1);
    }

    @Override
    protected void onPause() {
        intro.onPause();
        super.onPause();
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        applyOrientation(cfg);
    }

    // ---------------------------------------------------------------- small helpers

    private TextView text(float sp) {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return v;
    }

    private TextView label(String s) {
        TextView v = text(12);
        v.setText(s);
        v.setLetterSpacing(0.14f);
        v.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        v.setGravity(Gravity.CENTER);
        return v;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static String seconds(float t) {
        return String.format(Locale.US, "%.2f s", t);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
