# Camera Flash Intro (demo)

A 2.2-second intro: a camera shutter opens on a night sky, a burst of camera flashes goes off, and
**MADE BY: BRANDON J. WALLACE** lights up in 3D lettering with gold edges while the stars trail behind it.
The name is readable the whole time, from the first frame to the last.

It comes in two looks. **Standard** is the full-colour intro with its big white flash. **Night** is a dim red
version for use under a dark sky: red on black like Night Sky's screen, with a softer flash and a darker sky
behind the name, so it won't spoil night vision.

This folder is the stand-alone demo, kept apart from Night Sky Camera so it can be judged first. The camera app
doesn't use it yet.

## What's new in version 1.1

- A **STANDARD / NIGHT** switch, in the app and on the preview page.
- The night look itself: Night Sky's reds, a softer flash, and a darker sky behind the name.
- Sideways, the app's switch sits beside the buttons, so the space under the name stays clear.
- The speaker on the app's sound button now fits inside its circle.

The standard look hasn't changed at all: its frames are the same as version 1.0's, pixel for pixel.

## Watch it

- **On the phone, quickest:** open https://claude.ai/artifact/GEeN9Yt4WoLbSJnYv972Fn and tap the big play
  button. Turn the sound up. Tap **NIGHT** to see the red version.
- **As a phone app:** install [`dist/FlashIntro.apk`](dist/FlashIntro.apk) (steps below). It plays by itself
  when it opens.
- **On a PC:** download [`dist/camera-flash-intro.html`](dist/camera-flash-intro.html) (the download button on
  its page) and double-click it. It opens in Chrome or Edge and works without the internet.

All three show the same frames.

### What the buttons do

- **STANDARD / NIGHT** switches the look. The picture fades to the other look, and the buttons turn red or gold
  with it. The app and the page remember the choice.
- **PLAY / REPLAY** (the big round button) plays it again.
- **SLOW-MO** plays it at quarter speed, without sound, so you can see every flash.
- **SOUND ON / SOUND OFF** turns the sound on or off. In the app the sound follows the ringer, like a camera
  shutter: on silent there's no sound, and on vibrate you feel the flashes as taps instead.
- **The slider** stops the picture at any moment. Drag it to look at a single frame.
- **Tap the picture** while it plays to pause it.
- **Turn the phone sideways** to see the name on one line.

### Install the app on the phone

Version 1.0 already on the phone? Install this one over it the same way; there's no need to uninstall first.

1. Open https://github.com/brandonjw87/night-sky-camera/blob/main/intro-demo/dist/FlashIntro.apk in the
   phone's browser (signed in to GitHub) and tap the download button.
2. Open the downloaded file (tap the download notification, or open the **Files** app, then **Downloads**).
3. If the phone asks, tap **Settings** and turn on **Allow from this source**, then go back.
4. Tap **Install**. If Play Protect warns you about an unknown app, tap **More details**, then **Install anyway**.
5. Open **Flash Intro** from the app drawer.

It's a separate app with its own name and icon, so it doesn't touch Night Sky. To remove it, press and hold its
icon and tap **Uninstall**. The USB cable and `adb` ways in the [main README](../README.md#install-it-on-your-phone)
work for this app too: use `FlashIntro.apk` in place of `NightSky.apk`.

### Things to know

- The app's drawing code was checked on a computer standing in for a phone's graphics chip, and its frames match
  the preview page exactly, in both looks. Its buttons were drawn on a computer too, upright and sideways. It
  hasn't run on a real phone yet, so the sound timing, the vibration taps and the feel of the buttons are still
  to be tried.
- When the intro goes into Night Sky, it can start in the night look, so it matches the camera's red screen.
- The app has no internet permission and no tracking. It asks for nothing.
- The app's signing key is not in this repository. It's kept in the Claude project files
  (`intro-demo/.signing/flashintro.keystore`); builds signed with it install over this version.

## Timeline

| Time (s)    | What happens                                                              |
|-------------|---------------------------------------------------------------------------|
| 0.00 – 0.30 | A camera shutter opens on the night sky and the viewfinder locks focus    |
| 0.34 – 0.57 | Four quick paparazzi flashes pop around the name                          |
| 0.64        | The big flash: the name blazes white and the air fills with bright specks |
| 0.70 – 2.00 | The stars draw their trails behind the name                               |
| 1.10 – 1.68 | A shine of gold light sweeps across the letters                           |
| 0.00 – 2.20 | The camera drifts in and slowly swings round to face the name             |

One set of GLSL ES 3.00 shaders draws it in the Android app (OpenGL ES 3.0) and in the preview page (WebGL2).
Every frame is a pure function of time, so both draw the same pictures.

## Layout

```
dist/FlashIntro.apk                         the signed test app
dist/camera-flash-intro.html                the preview page in one file
android/FlashIntro/                         Android Studio project of the demo app (com.brandon.flashintro)
    app/src/main/java/com/brandon/intro/        the intro itself, ready to copy into another app
        FlashIntroView        the view: play, pause, show one frame, the night look, and a listener (ready,
                              stopped, error)
        FlashIntroRenderer    runs the passes and keeps the clock (it follows the sound while that plays)
        IntroAssets           loads the shaders and lettering data from assets/intro/
        IntroSound            the sound and the vibration taps
    app/src/main/java/com/brandon/flashintro/   the demo screen: the look switch, replay, slow motion, sound,
                                                scrubber
    app/src/main/assets/intro/                  copies of shaders/ and the app's files from assets/
    tools/build_apk.sh                          builds the APK without Gradle
shaders/            scene (sky, iris, 3D name, labels), flash discs, bloom, streak, light shafts, final
assets/             lettering distance fields (.r16f, .r8), their layout (sdf_manifest.json), previews, sound
fonts/              Montserrat, under the SIL Open Font License 1.1 (OFL.txt)
sdf/gen_sdf.py      makes the lettering fields from the font
audio/gen_audio.py  makes the sound, timed to the picture
web/                the preview page (artifact_template.html, packed by build_artifact.py), and a reference
                    page (dev.html with intro.js) that draws single frames from the loose files
tools/              shader check and asset sync
```

## Build the app

- **Android Studio:** File > Open, pick `intro-demo/android/FlashIntro` (not the repository's top folder, which
  is Night Sky), and press Run. Android Studio signs with its own debug key, so uninstall Flash Intro from the
  phone first if it came from `FlashIntro.apk`.
- **Without Gradle (Linux):** `android/FlashIntro/tools/build_apk.sh` builds a signed APK with only `javac`,
  `aapt2`, `d8` (or `dx`), `zipalign` and `apksigner`. Point `KEYSTORE` at the same key every time so updates
  install over the old version.

The code is Java 8 with no libraries. It needs Android 12 (API 31) or newer and OpenGL ES 3.0.

## Put the intro in another app

1. Copy `android/FlashIntro/app/src/main/java/com/brandon/intro/` into the app's `app/src/main/java/com/brandon/`.
2. Copy `android/FlashIntro/app/src/main/assets/intro/` into the app's `app/src/main/assets/`.
3. Add `<uses-permission android:name="android.permission.VIBRATE" />` to its manifest, for the taps.
4. Lay the view over the first screen, play it when it is ready, and take it away when it stops:

```java
final FlashIntroView intro = new FlashIntroView(this);
intro.setListener(new FlashIntroView.Listener() {
    @Override
    public void onIntroReady() {
        intro.play(false, true);   // normal speed, with sound
    }

    @Override
    public void onIntroStopped(float t, boolean finished) {
        root.removeView(intro);    // root: the activity's top FrameLayout
    }

    @Override
    public void onIntroError(String message) {
        root.removeView(intro);    // the phone can't draw it: carry on without it
    }
});
intro.setNight(true, false);       // only for a red night-vision screen like Night Sky's
root.addView(intro, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
```

While it is on screen, call `intro.onResume()` and `intro.onPause()` from the activity's own `onResume()` and
`onPause()`. A tap that should skip it can call `intro.pause()`, which ends in `onIntroStopped`.

The sound follows the phone's ringer: it plays in normal mode only, and vibrate mode keeps just the taps.

`setNight(night, fade)` picks the look. Called before `play` it sets the look of the very first frame; called
later with `fade` true, the picture crossfades to the other look in a quarter of a second, even while it plays.
The shaders take the look as `uNight` (0 standard, 1 night, values between crossfade).

## Change the look or the sound

You need Python 3 with numpy, scipy and Pillow, plus uharfbuzz for the lettering. Run these from this folder:

```
python3 sdf/gen_sdf.py                  # lettering fields and their layout, into assets/
python3 audio/gen_audio.py              # the sound, into assets/ (.wav and .pcm)
ffmpeg -i assets/intro_sound.wav -c:a libopus -b:a 160k assets/intro_sound.ogg    # the page's copy
tools/validate_shaders.sh               # checks that the shaders compile (needs glslangValidator)
tools/sync_android_assets.sh            # copies shaders and data into the app
python3 web/build_artifact.py dist/camera-flash-intro.html --standalone           # rebuilds the page
```

To look at single frames from the loose files, run `python3 -m http.server 8000` in this folder, open
`http://localhost:8000/web/dev.html`, and type `renderAt(0.64)` in the browser console (any time from 0 to 2.2).
Add `?night=1` to the address, or type `setNight(1)`, for the night look.

The preview page remembers the look it was last switched to. A link ending in `#night` or `#standard` opens it in
that look.
