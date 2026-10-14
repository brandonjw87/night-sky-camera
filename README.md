# Night Sky Camera

A long-exposure star camera for the Google Pixel 10 Pro XL. Lay the phone screen-down under a dark sky and it
shoots a series of RAW frames on the main camera for as long as you choose, lines the stars up between frames,
and stacks them into one clean, deep photo. Or, in Star Trails mode, it draws each star's arc across the sky.

## What it does

- **Deep sky** (default). Shoots RAW frames for 1 to 30 minutes (5 by default), lines up the stars between
  frames as the sky turns, throws out satellites, planes and passing clouds, and stacks everything into one
  photo with far less noise than a single shot.
- **Star trails.** Keeps the stars where they fall in each frame, so they draw arcs around the pole star. Longer
  is better: 20 or 30 minutes.
- **How long.** 1, 3, 5, 10, 20 or 30 minutes.
- **Brightness.** Choose it before you start, then fine-tune it on the finished photo with a slider. "Save this
  brightness" saves another copy at the new setting.
- **Screen-down start.** Tap Start and lay the phone down. It waits until the phone is still, counts down with
  beeps, measures how bright the sky is, chimes, and starts. The screen goes dark while it works.
- **Safe to interrupt.** Lifting the phone pauses the capture; lay it back down to carry on. "Hold to stop and
  develop" finishes early with what it has. If the app is killed or the battery dies, the frames are kept and
  the home screen offers to develop them.
- **Files.** JPEG (and optionally a RAW DNG) in `Pictures/NightSky`, so they show up in Google Photos. A short
  log of each session goes in `Documents/NightSky`.

## Install it on your phone

You only need the file `NightSky.apk`.

### Easiest: straight on the phone

1. Open `NightSky.apk` on the phone (download it from the chat or from this repository's files).
2. If the phone asks, tap **Settings** and turn on **Allow from this source**, then go back.
3. Tap **Install**. If Play Protect warns you about an unknown app, tap **More details**, then **Install anyway**.
4. Open **Night Sky** from your app drawer.

### If the phone refuses: install over USB from Windows

1. On the phone, open **Settings > About phone** and tap **Build number** seven times. It says you are now a
   developer.
2. Open **Settings > System > Developer options** and turn on **USB debugging**.
3. On the PC, download Google's platform tools:
   https://dl.google.com/android/repository/platform-tools-latest-windows.zip
4. Unzip it so you have a folder `C:\platform-tools`, and copy `NightSky.apk` into that folder.
5. Plug the phone into the PC with a USB cable. On the phone, tap **Allow** when it asks about USB debugging.
6. Open **Command Prompt** (press the Windows key, type `cmd`, press Enter) and paste these two lines:

   ```
   cd C:\platform-tools
   adb install -r NightSky.apk
   ```

   It prints `Success` when it's done.

Updates install over the old version and keep your settings, as long as they come from the same place.

## Taking a photo

1. Go somewhere dark, away from street lights, and wait for your eyes (and the sky) to be properly dark.
2. Wipe the back camera lens.
3. Open Night Sky and pick **Deep sky** or **Star trails**, how long, and the brightness.
4. Tap **Start**. Allow the camera the first time it asks.
5. Lay the phone **screen-down** on the ground, a table or a car roof, with the back camera facing the sky. A
   soft cloth under the screen protects it.
6. Leave it. It beeps a short countdown, chimes when it starts, and says "Done" when it has finished. Don't
   touch it or the surface it's on while it works.
7. Pick it up. The photo develops in a minute or two and appears on the screen. Pinch to zoom in on the stars,
   drag the brightness slider, and tap **Save this brightness** if you like the new look.

The app's screen is dim red so it doesn't ruin your night vision.

## Things to know

- The Pixel decides how long each single frame can be. The app measures this the first time and adapts, so the
  first session may start with shorter frames. It remembers what works.
- If something goes wrong, the session log in `Documents/NightSky` says what happened.
- The frames need free space while a session runs: about 1 GB for 5 minutes, about 5 GB for 30 minutes. They
  are deleted as soon as the photo is saved.

## For developers

### Layout

```
app/src/main/java/com/brandon/nightsky/
    MainActivity        screens (built in code, red on black)
    CaptureService      foreground service: keeps the session alive with the screen off
    SessionController   the session: arming, countdown, probe, capture loop, recovery, developing
    CameraRig           Camera2 RAW_SENSOR manual capture
    ExposurePlan        frame length (stars must not trail inside a frame) and ISO
    MotionMonitor       face-down and stillness detection from the gyro and gravity sensors
    SensorSpecFactory   camera calibration from Camera2 metadata
    MediaSaver          MediaStore saving
    processing/         the engine, plain Java with no Android dependencies
        UnitProcessor   live processing while capturing: hot pixels, star detection, alignment
        Aligner         3D rotation fit between frames using the lens model
        SharpStacker    Bayer drizzle stack with outlier rejection (satellites, planes)
        TrailsStacker   star trails with a clean background
        PostProcessor   final stack, gradient removal, colour, noise analysis
        Renderer        colour and tone (asinh stretch, white balance at 5500 K)
        DngWriter       16-bit linear DNG
sim/                    desktop simulator that drives the engine with a synthetic night sky
tools/build_apk.sh      builds the APK without Gradle
```

### Building

- **Android Studio:** open this folder and press Run. Android Studio signs with its own debug key, so uninstall
  the existing app first if it was installed from `NightSky.apk`.
- **Without Gradle (Linux):** `tools/build_apk.sh` builds a signed `build/NightSky.apk` using only `javac`,
  `aapt2`, `d8` (or `dx`), `zipalign` and `apksigner`. Set `KEYSTORE` to keep the same signing key across
  builds.

The code is Java 8 with no libraries, so it builds with nothing but the Android platform.

### Simulator

The simulator renders a night sky as the phone's sensor would see it (star field, sky rotation, lens distortion,
noise, hot pixels, satellites, a plane, a meteor and passing clouds) and runs it through the same engine the app
uses, then reports how well the frames were aligned against the known truth.

```
javac -source 8 -target 8 -d build/sim app/src/main/java/com/brandon/nightsky/processing/*.java \
    sim/src/com/brandon/nightsky/sim/*.java
java -Xmx3g -cp build/sim com.brandon.nightsky.sim.SimRunner out 2 37 false deep true
```

Arguments: output folder, scale (1 full size, 2 half size), frames, calibrated lens (true/false), mode
(`deep` or `trails`), events such as satellites and clouds (true/false).
