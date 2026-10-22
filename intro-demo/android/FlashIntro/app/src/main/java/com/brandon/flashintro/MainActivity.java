package com.brandon.flashintro;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
import android.widget.SeekBar;
import android.widget.TextView;

import com.brandon.intro.FlashIntroView;

import java.util.Locale;

/**
 * Demo screen for the camera flash intro: it plays once on launch, then a camera-style bar offers
 * replay, quarter-speed slow motion, sound on or off, and a scrubber to stop on any frame.
 * Turn the phone sideways to see the one-line layout of the name.
 */
public final class MainActivity extends Activity implements FlashIntroView.Listener {
    private FlashIntroView intro;
    private LinearLayout bar;
    private ControlButton shutter, slowButton, soundButton;
    private TextView hint, timeText, shutterLabel, slowLabel, soundLabel, errorText;
    private SeekBar scrub;
    private boolean slow, soundOn = true, playedOnce, autoPlayed, scrubbing, landscape;

    @Override
    @SuppressWarnings("deprecation") // setDecorFitsSystemWindows: needed before Android 15, which draws edge to edge anyway
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setAttributes(lp);
        getWindow().setDecorFitsSystemWindows(false);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Colors.NIGHT);
        intro = new FlashIntroView(this);
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

        errorText = text(15, Colors.INK);
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
        applyOrientation(getResources().getConfiguration());
        updateUi(FlashIntroView.LENGTH);
    }

    private LinearLayout buildBar() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER_HORIZONTAL);
        b.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{Colors.SCRIM, Colors.SCRIM, Colors.SCRIM_CLEAR}));
        b.setClickable(true);   // taps on the bar never reach the intro behind it

        hint = text(14, Colors.MUTED);
        hint.setGravity(Gravity.CENTER);
        hint.setText("Turn the phone sideways to see the one-line layout.");
        b.addView(hint, wrap());

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        timeText = text(13, Colors.INK);
        timeText.setTypeface(Typeface.MONOSPACE);
        timeText.setMinEms(4);
        row.addView(timeText, wrap());
        scrub = new SeekBar(this);
        scrub.setMax(Math.round(FlashIntroView.LENGTH * 1000));
        scrub.setProgressTintList(ColorStateList.valueOf(Colors.GOLD));
        scrub.setThumbTintList(ColorStateList.valueOf(Colors.GOLD));
        scrub.setProgressBackgroundTintList(ColorStateList.valueOf(Colors.LINE));
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
        TextView end = text(13, Colors.MUTED);
        end.setTypeface(Typeface.MONOSPACE);
        end.setText(seconds(FlashIntroView.LENGTH));
        row.addView(end, wrap());
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
        slowLabel = label("SLOW-MO", Colors.MUTED);
        shutterLabel = label("PLAY", Colors.GOLD);
        soundLabel = label("SOUND ON", Colors.MUTED);
        controls.addView(column(slowButton, 48, slowLabel));
        controls.addView(column(shutter, 76, shutterLabel));
        controls.addView(column(soundButton, 48, soundLabel));
        b.addView(controls, wrap());
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

    /** Sideways the bar slims down to the scrubber and the buttons, so it stays clear of the one-line name. */
    private void applyOrientation(Configuration cfg) {
        landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE;
        int portraitOnly = landscape ? View.GONE : View.VISIBLE;
        hint.setVisibility(portraitOnly);
        slowLabel.setVisibility(portraitOnly);
        shutterLabel.setVisibility(portraitOnly);
        soundLabel.setVisibility(portraitOnly);
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

    private TextView text(float sp, int color) {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        return v;
    }

    private TextView label(String s, int color) {
        TextView v = text(12, color);
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
