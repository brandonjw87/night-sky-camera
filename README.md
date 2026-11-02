# Night Sky Camera

A long-exposure star camera for the Google Pixel 10 Pro XL. Lay the phone screen-down under a dark sky and it
shoots a series of RAW frames on the main camera for as long as you choose, lines the stars up between frames,
and stacks them into one clean, deep photo. Or, in Star Trails mode, it draws each star's arc across the sky.

What changed in each version: [WHATS-NEW.md](WHATS-NEW.md). This is version 1.8.

## What it does

- **Deep sky** (default). Shoots RAW frames for 1 to 30 minutes (5 by default), lines up the stars between
  frames as the sky turns, throws out satellites, planes and passing clouds, and stacks everything into one
  photo with far less noise than a single shot.
- **Star trails.** Keeps the stars where they fall in each frame, so they draw arcs around the pole star. Longer
  is better: 20 or 30 minutes.
- **Camera check.** Up to three minutes indoors, before your first night: what this phone's camera really does
  with long exposures, frame after frame and in the dark. Sessions plan with what it finds.
- **Screen-down start.** Tap Start and lay the phone down. Once it's still it focuses on the stars, checks the
  sky (a smudged lens, trees, clouds, haze, the Moon), counts down and starts. The screen goes dark while it works.
- **Haze from the first night.** How much haze dims the stars is measured against how bright they'd be on a clear
  night, which the app works out from this camera's own numbers (its lens and its sensor) until it has measured a
  clear night itself. A hazy first night isn't taken for a clear one.
- **Safe to interrupt.** Lifting the phone pauses the capture; lay it back down to carry on. "Hold to stop and
  develop" finishes early with what it has. If the app is killed or the battery dies, the frames are kept and
  the home screen offers to develop them.
- **Clever stacking.** A frame half covered by cloud still gives its clear half; sparse star fields line up;
  sensor banding is taken out; the colours are measured from the stars themselves; satellites are named.
- **A clean sky that keeps its faint stars.** The photo's grain and blotches are taken out of the sky between
  the stars, which are found and set aside first, so they keep their light, down to the faintest ones the
  stack shows.
- **Meteors.** Every streak is sorted out: a satellite (named when the app knows it), a plane, or a meteor
  (one no satellite could have left). Meteors are taken out with the rest, or kept in the photo with **Keep
  meteors**. Each one caught is listed with its time, its shower, how bright it was and where it crossed, and the
  **Tonight** card says when a shower is worth going out for. Each frame is also searched for lines too faint to
  see in it, so fainter meteors are listed too.
- **Photos that play.** Each photo has a few seconds of video inside it that ends on the photo: the frames adding
  up until the sky's grain melts away, or the trails drawing themselves. Google Photos plays it (it's a Motion
  Photo, like the Pixel camera's own), and so does **Play its video** in the app.
- **Session report.** What the photo shows (constellations, planets, the Milky Way, galaxies to look for), a
  score for every frame, and what went wrong with the fix.
- **Edit any photo again.** Look, crop, north up, which frames go in, the reference frame, stars lined up by
  hand, the ground kept sharp. Every save is a new version; nothing is ever written over.
- **Photos that add up.** Add 5 minutes to the session you just took, or merge nights of the same sky.
- **Night plan.** Plan the night, arm it, and sessions start by themselves when it's dark and clear.
- **Presets and sites.** Backyard, Dark site, Partly cloudy, Quick look, Meteors and your own; places you shoot
  from remember their preset.
- **Tile, widget and shortcuts.** Two taps from the lock screen to a session.
- **Sky reading.** How dark is the sky here, in magnitudes per square arcsecond.
- **Viewer.** Watch the photo build up from a laptop, tablet or another phone on the same Wi-Fi.
- **Files.** JPEG with its video inside, named `.MP.jpg` (and optionally a RAW DNG) in `Pictures/NightSky`, so
  they show up in Google Photos. Logs, settings files and sky readings go in `Documents/NightSky`.

## Install it on your phone

You only need the file `NightSky.apk`. It's attached in the Claude chat, and it's in this repository as
[`dist/NightSky.apk`](dist/NightSky.apk). Updates install over the old version and keep your settings.

### From a Windows PC with a USB cable

1. Download `NightSky.apk` to the PC.
2. Plug the phone into the PC. On the phone, tap the USB notification and choose **File transfer**.
3. In File Explorer on the PC, open the phone, then its **Download** folder, and copy `NightSky.apk` into it.
4. On the phone, open the **Files** app, then **Downloads**, and tap `NightSky.apk`.
5. If the phone asks, tap **Settings** and turn on **Allow from this source**, then go back.
6. Tap **Install** (or **Update**). If Play Protect warns you about an unknown app, tap **More details**, then
   **Install anyway**.
7. Open **Night Sky** from your app drawer.

### Straight on the phone

1. Download `NightSky.apk` on the phone: tap it in the Claude chat, or open
   https://github.com/brandonjw87/night-sky-camera/blob/main/dist/NightSky.apk in the phone's browser (signed in
   to GitHub) and tap the download button.
2. Open the downloaded file (tap the download notification, or open the **Files** app, then **Downloads**), and
   carry on from step 5 above.

### If the phone refuses to install it

Install it with Google's USB tool instead:

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

## Taking a photo

1. Go somewhere dark, away from street lights, and wait for the sky to be properly dark. The **Tonight** card on
   the home screen says when that is, where the Moon is and what the clouds will do.
2. Wipe the back camera lens.
3. Open Night Sky and pick a **Preset**, or choose **Deep sky** or **Star trails**, how long, and the brightness.
4. Tap **Start**. The first time, allow the camera and the location (approximate is enough: it's for the Sun,
   the Moon and naming the stars, and it never leaves the phone).
5. Lay the phone **screen-down** on the ground, a table or a car roof, with the back camera facing the sky. A
   soft cloth under the screen protects it.
6. Leave it. Once it's still it focuses on the stars (about 30 seconds), then checks the sky and says what it
   found. After a warning you have 10 seconds to pick it up and fix it (wipe the lens, move away from the tree);
   lay it back down and it checks again. Then it beeps a short countdown, chimes when it starts, and says
   "Done" when it has finished. Don't touch it or the surface it's on while it works.
7. Pick it up. The photo develops in a minute or two and appears on the screen. Pinch to zoom in on the stars
   and drag the brightness slider.

The app's screen is dim red so it doesn't ruin your night vision.

## After the photo

Under the photo:

- **Play its video** plays the photo's few seconds of video where the photo is (tap to stop). Google Photos plays
  it too: the video is inside the photo's file, a Motion Photo. To save photos without it: **Advanced > The
  photo's video**. Making it adds about 10 seconds to developing; **Skip the video** on the developing screen
  saves the photo without it.
- **Session report** opens what the picture shows, a score for every frame, and what went wrong with the fix.
  **Share the log** at its bottom sends the session's log (every step and choice it made) as a text file.
- **Satellites** and **Names** draw the satellites' tracks and the stars' names on the photo; **Save this copy**
  keeps that version.
- **Add 5 minutes** (for half an hour after a session): lay the phone back down where it was and the extra
  minutes are stacked into the same photo. Volume up (or a Bluetooth remote's button) does the same while it's
  capturing.
- **Repeat tomorrow** puts the same stars on tomorrow night's plan (they come back 3 min 56 s earlier each
  night). Once you have two nights of the same sky, **Merge with ...** stacks them into one deeper photo.
- **Edit** opens the edit screen (below).
- **Sessions** lists every session kept on the phone: open its photo again, merge nights, develop a session
  that wasn't finished, or delete frames to free space.

## Meteor showers

- **Tonight** names a major shower when it's near its peak: about how many meteors an hour you'd see by eye from
  there at best and when, whether the Moon hides the fainter ones, and roughly how many the camera may catch (only
  the brighter ones, in its smaller patch of sky). **Use the Meteors preset** sets up for it: 30 minutes, meteors
  kept.
- **Streaks** (under **Advanced**): **Take them out** (the default) takes satellites, planes and meteors out of
  the photo; **Keep meteors** keeps the meteors at full brightness and takes the rest out; **Keep them all** keeps
  the satellites too.
- A streak counts as a meteor when no satellite could have left it: it starts and ends in open sky, mid-frame (a
  satellite crosses the whole picture, unless it passes into or out of the Earth's shadow, which the app works out
  for each end), it doesn't carry on in the next frame, or it crossed faster than any satellite can. Its shower
  comes from where its line points back to (the shower's radiant) and how fast it crossed.
- **Fainter meteors.** Each frame is also searched for straight lines too faint to see in it: how far each pixel
  of the frame sits from the rest of the session, all three colours together, is added up in patches of 8 by 8
  pixels and then along every line, and a line of patches that stands out together is a streak. A meteor found
  only this way is listed like the others, marked "too faint to show in the picture": in its frame it's mostly
  noise, so it's never kept in the photo. The search needs a session of 12 frames or more, and is skipped (the log
  says so) when its maps would take more than 32 MB, about 35 minutes of full-size frames.
- Each meteor caught is in the **Session report** (its time, its shower or "sporadic", about how bright it was,
  how long and where in the sky), in the session log, and in a table that opens in Excel or Sheets:
  `NightSky_..._meteors.csv` in `Documents/NightSky` (its `faint` column is 1 for the faint ones).

## Editing a photo

Tap **Edit** under a photo. The picture changes as you go; press and hold **Compare** to see the photo as it
was.

- **Look:** stretch (1 to 10), exposure (-2 to +2), black point, colour, sky brightness, and a natural, punchy
  or linear look. **As developed** puts them back.
- **Crop:** 4:3, 3:2, 16:9 or 1:1, and **North up** (when the app recognised the sky).
- **Frames:** each frame with its time and score. Drag **From** and **To** to trim the start or the end, tap a
  frame and **Leave out** to drop it (a plane, a bump), or make it the **Reference** the others line up on.
  **Line up by hand** helps a frame the app couldn't line up: tap a star in the reference frame, then the same
  star in that frame, for two or more stars.
- **Ground:** **Keep the ground still** keeps trees and buildings sharp while the stars line up behind them. The
  ground found automatically is shaded. To fix it, pick **Ground**, **Sky** or **Rubs out** and paint with one
  finger (two fingers zoom and move); **Undo** takes back the last stroke.
- **Renders:** every version saved, newest first. Tap one to open it again.

**Save** keeps the edit as a new version with its own recipe, and never writes over the photo. Changing the look
or crop is quick; changing the frames, the reference or the ground stacks the session again from its frames,
which takes a minute or two. A version whose stacked picture was deleted to make room is stacked again from its
recipe when you open it, and comes out the same, pixel for pixel. If a later version of the app improves the
processing, **New processing** under an older photo makes it again with it.

## Plan the night

1. On the home screen, under **Tonight**, tap **Plan the night**.
2. Tap **+ Add a session**: Deep sky or Star trails, when it starts (full darkness, moonset, the best window or a
   set time) and how long. **Only if the sky is clear** (on by default) takes a test frame first and, if
   clouds hide the stars, tries again every 10 minutes.
3. Tap **Arm the plan**. Allow it to run in the background when it asks.
4. Lay the phone screen-down outside, camera to the sky, on a cloth somewhere dry. On a long night, keep it on a
   charger or a power bank.

Each session opens the camera by itself and stops at its end, at dawn, on low battery (20% by default), low
storage or if the phone gets too hot. In the morning a notification shows the photos. If the phone restarts
while armed, a notification re-arms the plan with one tap.

## Quick Settings tile, widget and shortcuts

- **Tile:** swipe down twice from the top of the screen, tap the pencil, and drag **Night sky** into your tiles.
  Tap it on the lock screen and then **Start**: two taps to a session.
- **Widget:** touch and hold the home screen, then **Widgets > Night Sky**. It shows tonight's score, the plan
  and your last photo.
- **Shortcuts:** touch and hold the Night Sky icon: a 5-minute stack, 30-minute star trails, a quick look or a
  sky reading.

## Presets and sites

- **Presets** (top of the home screen): **Backyard**, **Dark site** (10 minutes, brighter), **Partly cloudy**
  (keeps going until it has the clear minutes you asked for, plus a cloud-blur version) and **Quick look**
  (2 minutes, JPEG only, half-size photo, quick to develop). **Save them as a preset** keeps your own.
- **Sites:** a place you shoot from. A session that starts within about 2 km of a site uses its preset and says
  so, and the site learns how dark its sky is and what blocks the view.
- **Settings file** (Advanced): all settings, presets and sites in one file in `Documents/NightSky`, to keep a
  copy or move to a new phone.

## Sky reading

Tap **Sky reading: how dark is it here?** and lay the phone down. It measures the sky in magnitudes per square
arcsecond (21.7 or more is an excellent dark sky, about 20 a suburb, under 18.5 a city), says the faintest stars
you could see by eye, and keeps a log you can save for Excel under Advanced.

## Camera check

Before your first night, find out what this phone's camera really does with long exposures. It takes up to three
minutes, indoors, at any time of day.

1. In a dim room, lay the phone screen-up on a table, so the camera is against the table (or put it in a drawer).
2. Tap **Check the camera** on the home screen's card (or open **Advanced > Camera check**), tap **Start** and
   leave it until it says it's done. Keep the app open.

It finds whether the night's long exposure comes out in full, whether the camera keeps going frame after frame
(some Pixels stop after 63 frames in a row until the capture is started afresh), and how its sensor looks in the
dark: hot pixels, noise and the black level. Sessions plan with what it finds: if the camera cuts long exposures
short, shorter ones are added together from the start; if it stops after so many frames, sessions give it a fresh
capture a few frames before then. **Share the details** sends everything it measured, and a copy is saved in
`Documents/NightSky`. After a system update, check again: the update can change what the camera does.

## Daylight focus calibration

At night the app finds the focus on the stars by itself. A few calibrations in daylight help it start closer:

1. Leave the phone outside in the shade for ten minutes.
2. Prop it so the back camera looks at something at least 500 m away with sharp edges (a far building, a tower,
   trees on a distant hill).
3. Open **Advanced > Daylight focus calibration**, tap **Calibrate** and keep the phone still for about 15
   seconds.

Three calibrations at temperatures at least 5 °C apart (a cool morning and a warm afternoon) let it predict the
night's focus.

## Watch it from a Windows laptop

The viewer is on unless you untick **Viewer** on the home screen.

1. Put the laptop on the **same Wi-Fi** as the phone.
   No Wi-Fi out there? On the phone's start screen, tap **No Wi-Fi here? Start a hotspot for it**. Then on the
   laptop, click the Wi-Fi icon at the bottom right of the screen, pick the network name the phone shows, and
   type the password it shows.
2. On the phone, tap **Start**. The screen shows an address like `http://192.168.1.23:8765` and a 6-digit
   code.
3. On the laptop, open Chrome or Edge, type that address into the address bar exactly as shown (with the
   `http://`), and press Enter.
4. Type the 6-digit code and click **Open the viewer**.
5. Lay the phone down. The laptop page shows the pre-flight check, the countdown, then the stack building up.

A phone or tablet can scan the QR code on the phone's screen instead of typing anything. From the viewer you
can start early, add 5 minutes, mute the beeps, stop and develop, and download the finished files. It also
shows tonight's plan. After the photo is done, the viewer stops by itself 30 minutes after you last used it, or
tap **Stop the viewer** on the phone.

## Things to know

- The Pixel decides how long each single frame can be. The app measures this the first time (or in the camera
  check) and adapts, so the first session may start with shorter frames. It remembers what works. If the camera
  stops after so many frames in a row, the session gets it going again and from then on gives it a fresh capture
  before it would stop.
- A session needs free space while it runs: about 1 GB for 5 minutes, about 5 GB for 30 minutes. After
  developing, the frames stay on the phone (up to 20 GB in all, set under **Advanced > Sessions and storage**)
  so you can edit, merge and add minutes later; the oldest sessions' frames go first, and the photos always
  stay. Each session's stacked picture takes about 75 MB more.
- What goes online: satellite orbits from CelesTrak (nothing is sent) and the cloud forecast from Open-Meteo
  (your place rounded to about 10 km). Both can be turned off under Advanced. Nothing else leaves the phone.
- If something goes wrong, the session log in `Documents/NightSky` says what happened, including every choice
  the app made by itself (**Share the log** on the session report sends it, and so does the screen a session that
  couldn't run ends on).

## For developers

### Layout

```
app/src/main/java/com/brandon/nightsky/
    MainActivity        screens (built in code, red on black): home, plan, result, report, edit, sessions, merge
    CaptureService      foreground service: keeps the session alive with the screen off
    SessionController   the session: pre-flight, focus, countdown, capture loop, recovery, developing, edits
    CameraRig           Camera2 RAW_SENSOR manual capture
    ExposurePlan        frame length (stars must not trail inside a frame) and ISO
    MotionMonitor       face-down and stillness detection from the gyro and gravity sensors
    SensorSpecFactory   camera calibration from Camera2 metadata
    FocusCalibrator     daylight focus calibration
    CameraCheck         the camera check: long exposures, frame after frame, the sensor in the dark
    PlanRunner, Plans   the night plan: alarms, only-if-clear starts, the morning summary
    PlanReceiver        restarts and updates while a plan is armed
    NightTileService    the Quick Settings tile
    NightWidget, SkyJob the home-screen widget and its two-hourly refresh
    Shortcuts, Glance   app-icon shortcuts; tonight's sky in a few words
    Forecasts, Locator  the cloud forecast; the approximate place
    Satellites          satellite orbits from CelesTrak
    Presets, Sites      presets and saved sites; SettingsIO the settings file
    SkyReadings         the sky reading log
    FrameStrip          the frame strip on the report and edit screens
    ZoomImageView       pinch-zoom picture with taps, painting and overlays
    TrackPainter        satellite tracks and star names drawn on the photo
    MediaSaver          MediaStore saving (a photo with its video saved as a Motion Photo)
    Mp4Writer           the photo's video: H.264 through the phone's encoder (MediaCodec) into an MP4
    RemoteHost          the remote viewer: what the web page shows and the commands it can send
    remote/             the viewer's web server, pairing page, QR codes and JSON (plain Java)
    processing/         the engine, plain Java with no Android dependencies
        UnitProcessor   live processing while capturing: hot pixels, star detection, alignment
        Aligner         3D rotation fit between frames using the lens model
        SkyMotion       the frames' path on the sky (Kalman smoother)
        SensorPattern   fixed-pattern noise and banding
        LocalWeights    per-region normalisation and transparency (partly cloudy nights)
        SharpStacker    Bayer drizzle stack with outlier rejection (satellites, planes)
        TrailsStacker   star trails with a clean background
        PostProcessor   final stack, gradient removal, colour, noise analysis
        ColorCalibration white balance from catalogue stars' B-V colours
        SkySolver       plate solving against the Yale Bright Star Catalogue
        Astro, Planets  time, Sun, Moon, planets
        Sat*, Sgp4      satellite prediction, matching and masks
        MeteorShowers   the shower calendar: radiants, their drift, rates, and which shower a meteor came from
        MeteorLog       the meteors caught: when, how bright, where, and the table of them
        Preflight       the pre-flight check
        ClearSky        how bright a clear night's stars are for this camera (from its lens and sensor), for haze
        CameraFacts, DarkFrames  what the camera check found; dark frames measured
        Focus*          focus on the stars and its temperature model
        ReportCard      the session report
        NightPlan       planning the night
        Master, Recipe  kept stacks and the recipes that make every render
        MergeProcessor  nights merged into one stack
        EdgeMask, GroundMap  the painted foreground and keeping the ground still
        Denoise         the sky's noise taken out, the stars found and set aside first
        Renderer        colour and tone (asinh stretch, looks, black point, saturation)
        PhotoVideo      the photo's video: the frames adding up again (or the trails drawing), rendered as the photo
        MotionPhoto     the video put inside the JPEG as a Motion Photo, and read back
        Framing         crops and north up
        DngWriter       16-bit linear DNG
sim/                    desktop simulator and tests that drive the engine with synthetic night skies
robo/                   Robolectric tests: the app's screens, and whole sessions with a simulated camera
tools/build_apk.sh      builds the APK without Gradle
tools/make_stars.py     builds the star table (stars.csv) from the Yale Bright Star Catalogue
```

### Building

- **Android Studio:** open this folder and press Run. Android Studio signs with its own debug key, so uninstall
  the existing app first if it was installed from `NightSky.apk`.
- **Without Gradle (Linux):** `tools/build_apk.sh` builds a signed `build/NightSky.apk` using only `javac`,
  `aapt2`, `d8` (or `dx`), `zipalign` and `apksigner`. Set `KEYSTORE` to keep the same signing key across
  builds.

The code is Java 8 with no libraries, so it builds with nothing but the Android platform.

### Simulator and tests

The simulator renders a night sky as the phone's sensor would see it (star field, sky rotation, lens distortion,
noise, hot pixels, satellites, a plane, a meteor, passing clouds, a branch at the edge) and runs it through the
same engine the app uses, then reports how well the frames were aligned against the known truth.

```
javac -source 8 -target 8 -d build/sim app/src/main/java/com/brandon/nightsky/processing/*.java \
    app/src/main/java/com/brandon/nightsky/remote/*.java sim/src/com/brandon/nightsky/sim/*.java
java -Xmx3g -cp build/sim com.brandon.nightsky.sim.SimRunner out 2 37 false deep true
```

Arguments: output folder, scale (1 full size, 2 half size), frames, calibrated lens (true/false), mode
(`deep` or `trails`), events such as satellites and clouds (true/false).

The tests in `sim/` each print `ALL PASS` when they pass: `EditTest`, `SettingsTest`, `ReadingTest`, `FocusTest`,
`PlanTest`, `ColorTest`, `SatPlanTest`, `PreflightTest`, `ClearSkyTest`, `CaptureReportTest`, `CheckTest`, `DenoiseTest`,
`MeteorTest`, `DeepLinesTest`, `MotionPhotoTest` and `SatTest` (with the SGP4 verification files) run on their own;
`ReportTest`, `SatSessionTest`, `MeteorSessionTest`, `LibraryTest`, `MergeTest` and `G3Test` take an output folder,
for example `java -Xmx6g -cp build/sim com.brandon.nightsky.sim.G3Test out/g3 2 16`. `PhotoVideoTest` takes an
output folder, the scale and the frames (`out/pv 2 24`) and, with ffmpeg on the path, also writes its videos as MP4.
`MeteorLimit` (an output folder, then the scale) measures how faint a meteor the camera catches.

### The app off the phone

`robo/` runs the app itself (its screens, the session's state machine, the capture loop and developing) on a
desktop JVM with Robolectric, without Gradle or Google's Maven repository. `robo/setup.sh` fetches what it needs
from Maven Central once; then `robo/run.sh Night base,stall` runs whole sessions through the real capture code
with a simulated camera that renders the real stars and can stop, stall, fail or be taken away, and
`robo/run.sh Monkey` taps every control on every screen. [robo/README.md](robo/README.md) lists every scenario.
