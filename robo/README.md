# Robolectric tests: the real app off the phone

These run the app itself (MainActivity, CaptureService, SessionController and everything under them) on a desktop
JVM with [Robolectric](https://robolectric.org) 4.17, without Gradle and without Google's Maven repository. The
simulator tests in `sim/` check the processing; these check the app around it: its screens, its state machine and
what it does when the camera misbehaves.

| Test | What it does | Output (in `build/robo`) |
|---|---|---|
| `Night` | Whole sessions through the real capture code, as Brandon uses it: Start on the home screen, the phone laid down, the focus sweep, the sky check, the countdown, capture with live stacking, developing and the result screen. The camera is simulated: `FakeRig` stands in for `CameraRig`, and `SimCamera` renders the real stars for this time and place (with `sim/SkySim`) and posts Camera2-style results. The motion sensors are fed as if the phone lay on the ground. Scenarios inject camera faults, knocks, clouds, low storage, a flat battery and more. | `night/night.log` (every status, each session's log and report), a screenshot at each phase |
| `Monkey` | Taps every control on every screen it can reach, and reports crashes, errors and stuck screens. | `monkey/monkey.log` |
| `Shots` | Draws each screen to a PNG. | PNGs |
| `Launches` | Opens the app every way Android can: the launcher, shortcuts, the widget, the quick settings tile, notifications. | `launches/` |
| `EditFlow` | Edits a kept session through the session controller, on the session `sim/G3Test` makes: an edit that changes nothing, edits of how it's rendered and ones that stack it again, a kept stack reused, a render whose stack was deleted made again, a re-stack cancelled, and the new processing for older renders (stacked again for an older stack, only rendered again for a render from before 1.4). | `EditFlow.out`: each check, then the session's log |

## Set up (once)

    robo/setup.sh

It puts Robolectric, JUnit and Robolectric's Android 16 jars (about 400 MB) in `~/.cache/nightsky-robo`, all from
Maven Central, and builds stand-ins for the few `androidx.test` classes Robolectric refers to. It needs Java 17 or
newer, curl, and the Android build tools and jars that `tools/build_apk.sh` uses (`aapt2`, and the platform jars in
`/opt/android-jars`, or say where with `ANDROID_JAR` and `ANDROID_RES_JAR`).

## Run

    robo/run.sh Night base,stall,checkstall     # comma-separated scenarios
    robo/run.sh Monkey
    SESSIONS=/path/to/sessions robo/run.sh Shots
    SESSIONS=/path/to/g3/sessions robo/run.sh EditFlow    # after: java ... sim.G3Test /path/to/g3 2 16

Each run compiles the app and the tests from this tree first. `Night` runs its fake clock at twice the real one
(`SPEED`), so a 5-minute session takes a few minutes; the whole list takes hours. A run ends with a summary: each
scenario's outcome, then `==== N problems` and a `PROBLEM` line for each. A problem is a crash, an error on screen
that the scenario didn't mean to cause, a session that failed when it shouldn't have, or a check that didn't hold.

## Night's scenarios

| Scenario | What happens |
|---|---|
| `base` | 5 minutes with the settings as they come, phone put down, left alone. Then the photo's video (1.8), as `trails` checks it too: the photo is in the gallery as a Motion Photo (name.MP.jpg) with the session's video inside, after the photo, where Google Photos looks; the photo still decodes with its EXIF block; the video is H.264 in the photo's shape with the frames its timeline gives, and ends on the photo (code for code, as the Pixel's own Motion Photos' videos match their photos); Play its video plays it where the photo is, and the photo comes back when it ends, on a tap, and after an error; opened again from the sessions page, it's still there to play. |
| `stop` | Stopped from the phone after a few frames: what there is is developed. |
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

- `CameraRig` itself is replaced, so its ImageReader, abort and restart code never runs here.
- The simulated sky has no moonlight.
- Settings carry over from one scenario to the next in a run, as on a phone.
- Robolectric quirk: after a text file is saved through MediaStore, the next JPEG written can come out without its
  EXIF block and ExifInterface then refuses to save; the app saves such a photo without its EXIF block and logs
  it ("saved without its EXIF block"), so seeing that line here is the quirk, not the phone.
