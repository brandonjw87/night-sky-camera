# Robolectric tests: the real app off the phone

These run the app itself (MainActivity, CaptureService, SessionController and everything under them) on a desktop
JVM with [Robolectric](https://robolectric.org) 4.18.1 on Android 17 (SDK 37), without Gradle and without Google's Maven repository. The
simulator tests in `sim/` check the processing; these check the app around it: its screens, its state machine and
what it does when the camera misbehaves.

| Test | What it does | Output (in `build/robo`) |
|---|---|---|
| `Night` | Whole sessions through the real capture code, as Brandon uses it: Start on the home screen, the phone laid down, the focus sweep, the sky check, the countdown, capture with live stacking, developing and the result screen. The camera is simulated: `FakeRig` stands in for `CameraRig`, and `SimCamera` renders the real stars for this time and place (with `sim/SkySim`) and posts Camera2-style results. The motion sensors are fed as if the phone lay on the ground. Scenarios inject camera faults, knocks, clouds, low storage, a flat battery and more. | `night/night.log` (every status, each session's log and report), a screenshot at each phase |
| `Monkey` | Taps every control on every screen it can reach, and reports crashes, errors and stuck screens. | `monkey/monkey.log` |
| `Shots` | Draws each screen to a PNG. | PNGs |
| `Launches` | Opens the app every way Android can: the launcher, shortcuts, the widget, the quick settings tile, notifications. | `launches/` |
| `EditFlow` | Edits a kept session through the session controller, on the session `sim/G3Test` makes: an edit that changes nothing, edits of how it's rendered and ones that stack it again, a kept stack reused, a render whose stack was deleted made again, a re-stack cancelled, and the new processing for older renders (stacked again for an older stack, only rendered again for a render from before 1.4). The photo's video (1.8): an edit stacked again is saved as a Motion Photo with its own video, a render made again keeps it, and an edit of the look only has none. | `EditFlow.out`: each check, then the session's log |
| `Stops` | The stop recorder (2.1): a crash written down as it happens (and in its session's log), Android's own records of the process ending (memory while developing a rehearsal, a freeze with its trace, a kill from a version that said nothing, and the endings that are part of normal life: an idle app tidied away, an update, the app closing itself), the home screen's card for each, Share the details, and Dismiss putting them away for good. | `stops/stops.log`: each check, with the details shared |
| `Moon` | The Moonshot (3.0) end to end. The phone's three back cameras are described to Android as a Pixel 10 Pro XL describes them, so the app picks the 5x telephoto itself; `FakeMoonCamera` opens it without Camera2 and `SimTele` feeds the real `MoonCamera` whole 12-megapixel RAW frames of `sim/MoonSim`'s Moon in a shaking hand, with the exposure and focus the app asked for three frames before, a lens that blurs away from its sharpest setting and a sensor brighter than the meter's guess. It checks the Moon found, metered and focused; the watch's Shoot taking a burst; the stack, the photo upright from the phone's tilt, the labels and a tapped story, both photos saved; the Transit Hunter's card and map; then a staged ISS crossing twenty seconds away, armed from the watch's Catch, counted down (the watch's buzzes, the phone's beeps), shot by itself four seconds before, the station put in the frames at that moment, found and named; then the result's other controls (One frame and Stacked, No labels, Share sending the plain or the labelled photo, Shoot again, Focus again); and Done closing the screen. Since 3.1: the Moon map (names on the lit side and none overlapping, a tapped story, the `_map` photo saved and shared), and the Earth's glow: not offered on a fuller Moon; on a simulated crescent with a faint earthshine, offered and on, its switch, the 120 brighter frames after the burst, the merge, the night side grey instead of black and the sky still black, its chip and story, four photos saved (with the glow three ways, and without it), Share with and without it, the watch's words, and the map naming the night side by the Earth's light. The Moon is set 50° up rising in the east, so a run's sky doesn't flip with the clock; `robo/run.sh Moon west` puts it setting in the west instead. | `moon/`: every screen and the watch's Moon screens as PNGs, the saved photos in `moon/media/`; `Moon.out`: each check |
| `Jupiter` | Jupiter's four moons (3.2) end to end, on the same simulated Pixel 10 Pro XL: a place and time where Jupiter is 48° up in a dark sky, then `SimJupiter` feeds the real `MoonCamera` whole 12-megapixel RAW frames of `sim/JupiterSim`'s Jupiter and moons (the lens's blur and halo, photons, shot and read noise) in a shaking hand, with a sensor 1.8 times the meter's guess. It checks the screen's tonight (where Jupiter is, the line-up, the next 12 hours' happenings, the notebook, a story); the telephoto chosen, Jupiter's size and first exposure from the sums, the picture's up from the tilt; Jupiter found in the whole frame within three frames, metered (again once it's sharp), focused where the lens is sharpest, ready within 20 seconds; the watch's Shoot taking both bursts (90 frames of Jupiter, then 240 sixteen times brighter for the moons) with the screen counting them; the stack, each moon clear of the glare found where it is or said why not (one in another's dot named with it), the title, the moons' lines, the labels; three photos saved (plain, named, the notebook page); a moon tapped; the watch's words; tonight kept in the notebook's file and read back by a fresh screen; the nine stories; Rømer's light-minutes; and leaving closing the camera. | `jupiter/`: the screens and the saved photo and page as PNGs, the saved files in `jupiter/media/`; `Jupiter.out`: each check |
| `Window` | The Sky Window (3.3) end to end, on the same simulated Pixel 10 Pro XL at a place where it's dark now: `FakeSkyCamera` opens the real `SkyCamera` on the cameras described to Android (the main camera for the window, the 5x for its frame), and the phone's rotation sensors are fed through Android's own `SensorManager`, the gyro's north 40° off as a gyro's is and the compass 12° off the truth. It checks the window opening at a night exposure; the labels 12° off where the compass says; `RehearsalSky`'s RAW frames of the real stars through the camera's pipeline: the night picture, then the star lock (on the first still frame), the compass's error read back, every label within 0.1° of its star, the top's ★ Star lock, the gyro carrying the lock through a 60° turn with the compass 3° further off; a star tapped for its card and its rise and set; Find to Jupiter (or the Moon): its row, the arrow's words, turning to it, Got it! and the buzz, the 5x's frame and its button, Shoot opening the Jupiter screen; time travel 3 hours on; X-ray; red; the camera off and on with the lock kept; looking through the Earth with the stars still where they are; pause and resume (the lock found again); day frames giving way to the preview and the compass; and a window opened from the Jupiter screen going back to it. | `window/`: the screens as PNGs; `Window.out`: each check |
| `Intro` | The opening film (2.0) around Robolectric's stand-in for the phone's video player: when it plays and when it doesn't (once, from the icon only, never over a session), that it never stands in the app's way (a tap, a film that can't be played, a player that never shows a picture, the app left or closed while it plays), its sound, its night-red look, how it fills screens of other shapes, and its setting with Play it now. | `Intro.out`: each check |

## Set up (once)

    robo/setup.sh

It puts Robolectric, JUnit and Robolectric's Android 17 jars (about 400 MB) in `~/.cache/nightsky-robo17`, all from
Maven Central, and builds stand-ins for the few `androidx.test` classes Robolectric refers to. It needs Java 17 or
newer, curl, and the Android build tools and jars that `tools/build_apk.sh` uses (`aapt2`, and the platform jars in
`/opt/android-jars`, or say where with `ANDROID_JAR` and `ANDROID_RES_JAR`).

## Run

    robo/run.sh Night base,stall,checkstall     # comma-separated scenarios
    robo/run.sh Monkey
    SESSIONS=/path/to/sessions robo/run.sh Shots
    SESSIONS=/path/to/g3/sessions robo/run.sh EditFlow    # after: java ... sim.G3Test /path/to/g3 2 16
    robo/run.sh Intro
    robo/run.sh Stops
    robo/run.sh Moon
    robo/run.sh Jupiter
    robo/run.sh Window

Each run compiles the app and the tests from this tree first. `Night` runs its fake clock at twice the real one
(`SPEED`), so a 5-minute session takes a few minutes; the whole list takes hours. A run ends with a summary: each
scenario's outcome, then `==== N problems` and a `PROBLEM` line for each. A problem is a crash, an error on screen
that the scenario didn't mean to cause, a session that failed when it shouldn't have, or a check that didn't hold.

## Night's scenarios

| Scenario | What happens |
|---|---|
| `base` | 5 minutes with the settings as they come, phone put down, left alone. Then the photo's video (1.8), as `trails` checks it too: the photo is in the gallery as a Motion Photo (name.MP.jpg) with the session's video inside, after the photo, where Google Photos looks; the photo still decodes with its EXIF block; the video is H.264 in the photo's shape with the frames its timeline gives, and ends on the photo (code for code, as the Pixel's own Motion Photos' videos match their photos); Play its video plays it where the photo is, and the photo comes back when it ends, on a tap, and after an error; opened again from the sessions page, it's still there to play. Since 2.6: three particles from space sent through the simulated chip, each in one frame, which Cosmic Ray Hunter must find (one may fall on a star and go with it) and nothing else; the three new cards (Cosmic Ray Hunter, Earth's Address, the postcard), their pictures in the gallery at their sizes and their shares; Earth's Address's pulsars played (the button names the one ticking), X-Ray Vision's taking over when tapped meanwhile, and a tap stopping them; all three back when the session is opened again. |
| `wrist` | The Pixel Watch (2.5): the real watch app (`watch/`) beside the real phone app. Paired by the six digits the watch shows, typed on the phone's Pixel Watch card, with matching check digits on both. Then, over the internet alone (Bluetooth off; the relay is `sim/MockNtfy`, a stand-in for ntfy.sh on this computer with its limits): the crown changes the phone's preset, Start, the countdown, capturing, Mute, +5 min, the watch's screen off (its notification counts down), Stop twice, Done with its buzz and the X-ray line, and no message refused by the relay. Unpaired from the watch, paired again over Bluetooth alone (the relay down): a session with the live picture, then unpaired from the phone, each end hearing it from the other. Since 2.6, before that session, the watch's Sky Pointer: opened with **Sky** on Ready, it has the place the phone sent; pointed (through a simulated rotation sensor) at a bright star that's up, it names it; **Find** starts at the Moon, says **Higher** from 30° under it and **Got it!** with the buzz on it; the crown moves on to the next target; a tap flips the arm toward the elbow and back; **Back** returns to Ready. Since 3.0, Ready shows **Moon** too: it opens the watch's Moon screen (the phase, how much is lit, **Back** only while the phone's Moonshot is closed) and **Back** returns. |
| `rehearsal` | A rehearsal (2.1): Rehearse a night indoors on the home screen, the phone laid screen-up with its camera covered, a clear night drawn into the dark frames. The whole session runs and every step of its check must pass; while it captures, the stop recorder says "a rehearsal, capturing frame N of M" and the session; the photo is marked a rehearsal's (its name, its summary), isn't offered Repeat tomorrow, Add 5 minutes or Merge; the check opens over the photo and Share the check shares it as text; the home screen then says how the last rehearsal went. |
| `stop` | Stopped from the phone after a few frames: what there is is developed, and the photo's video is made of those frames. |
| `pickup` | Picked up for a few seconds mid-session, then put back. |
| `knock` | Knocked mid-session: the view shifts a quarter of a degree. |
| `stall` | The camera stops answering for a while: the watchdog restarts the capture session. |
| `stallhard` | The camera stops answering for good: restarted, reopened, then finished with what there is. |
| `fail` | Three frames fail in a row. |
| `lose` | Two frames lose their image (the result comes, the image doesn't). |
| `longfail` | The first long exposures fail: the session falls back to shorter frames. |
| `deadlong` | Even the longest exposure the camera admits to fails every time: the session halves it. |
| `battery` | The battery runs down to 4%: the session stops and develops what it has. |
| `clamp` | The camera gives 4 s however long the exposure asked for. |
| `addkey` | 1 minute on a camera that cuts every exposure to 4 s; volume up after 8 frames adds 5 minutes. |
| `camerror`, `camerror2` | The camera fails as it opens, once (opened again) or twice (the session can't start, and says why). |
| `taken` | Another app takes the camera mid-session. |
| `cancel` | Cancelled while focusing, then started again. |
| `screenoff` | The screen goes off mid-session and comes back after it's developed. |
| `remade` | The activity is made again mid-session, as after Android reclaimed it. |
| `replay` | The widget's quick session, then the screen made again and reopened from the recent apps (which mustn't start it again). |
| `trails` | Star trails for 2 minutes. |
| `earth` | The Earth's turn (2.2): 3 minutes in its mode. The clip is in the session's folder (H.264, 153 pictures: 4 s of the turn, 0.5 s held, 0.6 s back to the start) and in the gallery as an MP4 and an animated WebP; the result screen says what the turn measured (the day by the stars, within a minute of 23 h 56 min) and plays it. |
| `lapse` | The time lapse (2.5): 10 minutes in its mode, in the Comet tails look. The film is in the session's folder and the gallery: H.264 in a 16:12 shape, a picture for every frame of the night (stretched to 8 seconds, 4 pictures a frame at most) and 0.7 s held at the end; the result screen says what it is and plays it. Then it's made again from the result screen as Trails growing, and opened again from the sessions page, the card and its looks are still there. |
| `meteors` | 3 minutes with Keep meteors; two bright meteors cross the picture: both are reported, kept and listed in the meteor table (saved in Documents), and the photo's video is made with them. |
| `video` | The photo's video (1.8) when there's none to put in: a phone without a video encoder, the video off in Advanced settings, the encoder failing part way, and the video skipped from the developing screen. Each time the photo is saved as before, a plain JPEG, and the log says why. |
| `reading` | A sky reading (how dark it is). |
| `clouds` | Clouds over everything for three frames. |
| `haze` | A new phone's first two nights (nothing learned): 1.3 magnitudes of haze all session, named against what the camera's specs say a clear sky gives and not learned as the clear night; then a clear night, read clear against the specs and learned. |
| `storage` | Not enough free storage. |
| `more` | One minute, then Add 5 minutes from the result. |
| `plan`, `plancloudy` | Tonight's plan armed and started by its alarm; under clouds it waits to try again, then is cancelled (run `plancloudy` after other sessions: it needs a learned clear-night zero point). |
| `drift` | 30 minutes, with the best focus creeping between minutes 8 and 20. |
| `check` | The camera check on a covered camera that does everything right. |
| `checkstall` | The check on a camera that stops after 63 frames in a row until it gets a fresh capture session (the Pixel bug). |
| `checkreopen` | The same, but only opening the camera again gets it going. |
| `checklit` | The check with light on the sensor all along: it waits, then goes on without the dark measurements. |
| `checkcover` | The check started uncovered, covered 8 seconds later. |
| `checkclamp` | The check on a camera that cuts long exposures to 4 seconds. |
| `checkstop` | The check stopped part way: nothing saved, the camera let go; then a session. |
| `stoplearn` | A camera that stops after 20 captures in a row, nothing learned yet: the watchdog finds it, then the session renews before it. |
| `renewknown` | A camera whose stop the check found: renewed before it, it never stops. |
| `renewsooner` | Renewals planned every 30 frames, but the night's long frames stop after 20 in a row: the session renews sooner from then on. |
| `renewreopen` | The same, renewing by opening the camera again. |
| `renewfail` | Fresh capture sessions don't come up: the renewal opens the camera again instead, and from then on. |
| `sharelog` | Share the log from a session's report, from the sessions page, and from a failed session's screen. |

## What it can't see

- The phone's own video encoder (MediaCodec): Robolectric has none, so the photo's video is made by ffmpeg's H.264
  encoder here (`FfmpegVideo`, BT.709 colours and limited range as the app asks of the phone's). MediaStore keeps
  nothing in Robolectric either, so `FakeMediaStore` stands in for it: what the app saves in Pictures and Documents
  lands in `night/media/`, named by its row and display name, to be read back. Without ffmpeg the video is a stand-in
  and its frames aren't checked.

- `CameraRig` itself is replaced, so its ImageReader, abort and restart code never runs here. The same for the
  Moonshot's Camera2 session (`MoonCamera.open`): the lens choice, its setup and everything from the frame on are the
  app's own, but the telephoto's real exposures, focus and stabiliser can only be seen on the phone. The live
  picture's TextureView draws nothing here (it's white in the pictures).
- The watch (2.5): Robolectric's Bluetooth sockets don't reach each other, so the test joins the watch's and the
  phone's with two threads, each copying one end's output into the other's input (each end's own code runs as on the
  devices, down to the frames it writes). The internet way goes to `sim/MockNtfy` instead of ntfy.sh, and the watch's
  buzzes, crown and screen going off are Robolectric's stand-ins. A real watch and the real ntfy.sh are still to be
  tried.
- The simulated sky has no moonlight.
- Settings carry over from one scenario to the next in a run, as on a phone.
- Robolectric quirk: after a text file is saved through MediaStore, the next JPEG written can come out without its
  EXIF block and ExifInterface then refuses to save; the app saves such a photo without its EXIF block and logs
  it ("saved without its EXIF block"), so seeing that line here is the quirk, not the phone.
