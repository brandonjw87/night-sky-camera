# Night Sky Camera: what's new

## Version 1.7: haze from the first night

### What's new

- **Haze measured from the very first night.** Until now the sky check could tell haze only once the phone had
  seen a clear night to compare with; before that it said "haze not measured yet". Now it works out how bright a
  clear night's stars should be for this camera from the camera's own numbers: how wide its lens opens (6.9 mm at
  f/1.68, so 4.1 mm across), how many electrons make one step of its RAW values (from the noise figures the camera
  sends with every frame, checked against the frame's own sky), how much starlight its green pixels catch, how
  much of each star the lens spreads into a glow (measured on the same frame), and how much a clear sky takes off
  at the stars' height. So on the first night the sky check can say "Some haze: stars about 1.1 magnitudes dimmer
  than this camera's specs say a clear sky gives", or warn about thin cloud or thick haze.
- **It says less than a measured clear night would.** Worked out this way, a clear night's stars are known to
  about half a magnitude, so haze is named only from half a magnitude more than against a measured clear night:
  "Some haze" from about 0.9 magnitudes (against a clear night: "A little haze" from 0.4),
  the thin cloud or thick haze warning from about 1.8 (1.3), and **Only if the sky is clear** waits from about 3
  (2.5). A clear first night says "at most a little haze, going by the camera's specs until a clear night is
  measured".
- **A hazy night isn't learned as a clear one.** Each good session's star brightness is still kept, and the
  clearest night is what later checks compare with, unless it's more than a magnitude dimmer than the camera's
  specs say: then that night was likely hazy, and the check keeps using the specs until three nights agree (if
  three do, it's the specs that are off for this phone, and the nights win). Before, a hazy first night made every
  night as hazy look clear.
- **In the log:** the sky check now writes the clear sky it worked out and what went into it (the line starting
  "clear sky from the camera's specs"), and after a good session, what the next check will compare with and this
  session's own number. A sky reading says which it measured the haze against, and its table has a new last
  column, `haze_against`.
- **Checked on simulated nights:** a clear first night reads clear (within 0.05 magnitudes of what the specs say);
  1.25 magnitudes of haze reads as "Some haze, about 1.1"; 2 magnitudes brings the warning; 3.5 magnitudes makes
  **Only if the sky is clear** wait; a hazy first night, learned, doesn't make the next hazy night look clear; a
  camera whose specs are 1.5 magnitudes too hopeful shows some haze until three clear nights agree, then reads
  clear; and noise figures that disagree with the sky's own noise aren't trusted. Through the app itself, with a
  simulated camera: a new phone's first night under 1.3 magnitudes of haze said "Some haze: stars about 1.3
  magnitudes dimmer than this camera's specs say a clear sky gives" and wasn't learned as the clear night; the clear
  night after it read clear and was.

### Install the update (Windows, USB cable)

It installs over version 1.6 and keeps your settings and sessions.

1. Download `NightSky.apk` from the chat to your PC.
2. Plug the phone into the PC. On the phone, tap the USB notification and choose **File transfer**.
3. In File Explorer, open the phone, then its **Download** folder, and copy `NightSky.apk` into it (replace the
   old one if Windows asks).
4. On the phone, open the **Files** app, then **Downloads**, tap `NightSky.apk`, then tap **Update**.

### Defaults

- There's no setting. The sky check uses the camera's specs whenever no clear night has been measured (or the one
  measured looks hazy), and a measured clear night once there is one.
- If the camera doesn't send its noise figures, or they disagree with the frame's own sky, the check says "haze
  not measured yet", as in 1.6.
- Everything else is unchanged from version 1.6.

### Try today

- Nothing new to see in daylight: the haze check needs stars.

### Try tonight

1. Any night: a normal session. The sky check's line about clouds now says how hazy it is from the start.
2. On the first properly clear, dark night (no Moon, no haze), open **Session report > Share the log** afterwards
   and look near the end for the line starting "learned for the next check": its "this session" number against
   "the camera's specs say a clear sky gives" shows how close the estimate is for your Pixel. Send that line (or the
   whole log) here and the estimate can be set exactly for your phone.

### Not working yet, or not tested

- Tested off the phone only. How much light the Pixel's green pixels catch isn't published, so it's a typical
  phone camera's: on your Pixel the estimate could be off by half a magnitude, or more. If it's too hopeful, your
  first clear nights may say "Some haze"; after three clear nights that agree, the check uses them instead.
- Before a clear night is measured, haze under about 0.9 magnitudes isn't named.
- The Moon, twilight and town lights brighten the sky but don't dim the stars, so they aren't counted as haze (the
  check names them separately, as before).

## Version 1.6: fainter meteors

### What's new

- **Fainter meteors.** Each frame is now also searched for straight lines too faint to see in it. How far each of
  its pixels sits from the rest of the session, all three colours together, is added up in patches of 8 by 8
  pixels and then along every line, so a trail far too faint to stand out anywhere still adds up to a line that
  does. On simulated Geminid nights the camera now catches half of the meteors as faint as magnitude +1.6, under a
  dark sky or a suburban one (version 1.5: +1.3 and +0.8): about a quarter more meteors under a dark sky, and two
  thirds more under a suburban one.
- **Listed, not drawn.** A meteor found only this way is in the session report, the log and the meteor table like
  the others, marked "too faint to show in the picture" (in the table, its `faint` column is 1). It's never kept
  in the photo, even with **Keep meteors**: in its frame it's mostly noise, so keeping it would only add a faint
  stripe of noise.
- **A brighter meteor's faint ends**, found the same way, are joined on to it rather than counted as another
  meteor.
- **The sensor's own banding is taken out first.** Rows (and columns) of the sensor that are a little brighter or
  darker in one frame than the rest would otherwise look like faint lines; the search takes them out of each frame
  along that frame's own rows and columns.
- **Fixed:** a meteor that passed close to a bright star could be dropped as "the edge of something that isn't
  sky" (a roof, a branch). Now only something far darker or far brighter than the sky all around counts.
- **The Tonight card** counts the fainter meteors too: its camera estimate is about 40% higher than before.
- **Checked on simulated nights:** 58 Geminids in four full-size sessions under each sky (the numbers above); the
  5-minute Geminid night of version 1.5, where all 11 meteors are still found, named and placed, with nothing else
  called a meteor; the satellite nights, unchanged; and sessions on a sensor with strong banding and fixed
  pattern, where the search first found over 120 false lines and now finds none.

### Install the update (Windows, USB cable)

It installs over version 1.5 and keeps your settings and sessions.

1. Download `NightSky.apk` from the chat to your PC.
2. Plug the phone into the PC. On the phone, tap the USB notification and choose **File transfer**.
3. In File Explorer, open the phone, then its **Download** folder, and copy `NightSky.apk` into it (replace the
   old one if Windows asks).
4. On the phone, open the **Files** app, then **Downloads**, tap `NightSky.apk`, then tap **Update**.

### Defaults

- The faint search runs by itself on every deep-sky session of 12 frames or more (about a minute and a half);
  there's no setting. A session so long that its search would need more than 32 MB of memory (about 35 minutes of
  full-size frames, so the 30-minute Meteors preset is covered) skips it, and its log says so.
- Faint meteors stay out of the photo, whatever the **Streaks** setting.
- Everything else is unchanged from version 1.5.

### Try today

- Nothing new to see in daylight: the faint search needs a night sky.

### Try tonight

1. Any clear night: a **Meteors** session (the preset, 30 minutes). The session report lists every meteor caught;
   the faint ones are marked "too faint to show in the picture". On an ordinary night a session with none is still
   normal.
2. The real test is the Geminids, on the night of Sunday December 13: the Tonight card will say when, with the best
   time.
3. If the report lists a meteor that can't have been there, or misses one you saw, send the photo and **Session
   report > Share the log** here. The log names every faint line the search found and how strongly it stood out.

### Not working yet, or not tested

- Tested off the phone only: on simulated skies, and through the app itself with a simulated camera. A real sensor
  and a real sky can make faint lines the simulator doesn't (a lit cloud's edge, a pattern the sensor has that the
  simulator lacks), so your first nights are the real test of the faint search.
- How long the faint search takes on the phone isn't measured: on a desktop it's about a tenth of a second a frame
  (half a minute for a 30-minute session); the phone is slower, so developing takes a little longer than in 1.5.
- A faint meteor shorter than about 64 pixels isn't looked for, and one that runs within a degree or so of the
  sensor's rows or columns is harder to find (that's where the banding is taken out).
- A faint meteor's brightness is rougher than a bright one's: on simulated nights within about half a magnitude.
- What version 1.5 says below about meteors' times, speeds and showers still holds.

## Version 1.5: meteors

### What's new

- **Meteors told from satellites and planes.** Every streak in a frame is now sorted out: a satellite the app
  knows (named, as before), a plane (its lights blink), or a meteor. A streak counts as a meteor when no
  satellite could have left it: it starts and ends in open sky, in the middle of the frame (a satellite crosses
  the whole picture unless it passes into or out of the Earth's shadow, which the app works out for both ends),
  it doesn't carry on in the next frame, or it crossed faster than any satellite can.
- **Keep meteors.** Under **Advanced**, **Streaks: satellites, planes and meteors** now has three choices: **Take
  them out** (as before), **Keep meteors** (new: the meteors stay in the photo at full brightness, satellites and
  planes go) and **Keep them all** (the satellites stay too, which is what "Keep satellites" did).
- **Every meteor listed.** The session report has a **Meteors** part: how many, from which showers, the
  brightest, then a line for each with its time, its shower (or "sporadic"), about how bright it was, how long,
  where in the sky it crossed and, when the photo shows it, which way it flew. The shower comes from where its
  line points back to (the shower's radiant, worked out for that night and that place) and how fast it crossed.
  The session log has a line for each too, and a table of them is saved as `NightSky_..._meteors.csv` in
  `Documents/NightSky`, for Excel or Sheets.
- **Meteor showers on the Tonight card.** When a major shower is near its peak, the card says so: about how many
  an hour you'd see by eye from where you are at best, and when; whether the Moon hides the fainter ones; and
  roughly how many the camera may catch. Coming up: the Northern Taurids (known for slow fireballs) on November 12,
  the Leonids on the night of November 17, and the year's best, the Geminids, on the night of Sunday December 13.
- **A Meteors preset**: 30 minutes, with the meteors kept. During a shower the Tonight card has a **Use the
  Meteors preset** button.
- **Checked on a simulated Geminid night** (8 Geminids, 3 sporadic meteors and a blinking plane in a 5-minute
  session): all 11 meteors found and called meteors, nothing else called one, the right shower for all 11 (and
  "sporadic" for the 3), the way each Geminid flew, both ends of each on its true path across the sky, its time
  within its frame, and its brightness typically within half a magnitude. With **Keep meteors** they're in the
  photo at full brightness; otherwise they're gone from it. The whole app was also run on a simulated phone
  with two meteors crossing: both were kept, listed and saved in the table.

### Install the update (Windows, USB cable)

It installs over version 1.4 and keeps your settings and sessions.

1. Download `NightSky.apk` from the chat to your PC.
2. Plug the phone into the PC. On the phone, tap the USB notification and choose **File transfer**.
3. In File Explorer, open the phone, then its **Download** folder, and copy `NightSky.apk` into it (replace the
   old one if Windows asks).
4. On the phone, open the **Files** app, then **Downloads**, tap `NightSky.apk`, then tap **Update**.

### Defaults

- Meteors are taken out of the photo with the other streaks, as before; **Keep meteors** or the **Meteors**
  preset keeps them. If you had chosen to keep satellites, that's now **Keep them all**.
- New: the **Meteors** preset (30 minutes, meteors kept). Your own presets keep their settings.
- Everything else is unchanged from version 1.4.

### Try today

- Open **Advanced** and find the three **Streaks** choices. Nothing else to try until a clear night.

### Try tonight

1. Any clear night: tap the **Meteors** preset (or pick **Keep meteors**), lay the phone down and let it run its
   30 minutes. On an ordinary night a bright meteor crosses the camera's patch of sky only now and then, so a
   session with none is normal; if one is caught, the session report lists it.
2. The real test is a shower night: the Tonight card will say when, with the best time. Start a Meteors session
   then, or plan the night with several of them.
3. If a streak is called the wrong thing (a satellite or a plane called a meteor, or a meteor taken for something
   else), send the photo and **Session report > Share the log** here.

### Not working yet, or not tested

- Tested off the phone only: on simulated skies, and through the app itself with a simulated camera. Your first
  meteor is the real test.
- The camera catches only the brighter meteors: on simulated skies, about half of those as bright as a bright star
  (magnitude +1.3 under a dark sky, +0.8 under a suburban one) and nearly all brighter ones. At the Geminids'
  peak that's roughly one an hour. The Tonight card works it out for the night, as a rough guess (good to a
  factor of two or so). A fainter meteor goes into the photo like any other passing light: spread over the whole
  session, too faint to see. Finding fainter ones is next on the list.
- A meteor's time is only known to within its frame (each frame lasts several seconds).
- A meteor's brightness is an estimate: its speed in the air comes from its shower (for a sporadic one, a typical
  speed), so a sporadic meteor that was slower than most comes out brighter than it was.
- A sporadic meteor whose line happens to point back to a shower's radiant is counted as one of the shower's:
  one photo can't tell them apart.
- A satellite the app has no orbit for whose streak starts or ends mid-frame (a sudden flash of sunlight off it)
  can be called a meteor.

## Version 1.4: a clean sky that keeps its faint stars

### What's new

- **A smoother sky, the faint stars kept.** Every saved photo now has the sky's grain and blotchy noise taken out
  between the stars. The stars are found first and set aside, so the ones it finds keep all their light; fainter
  ones, hard to tell from the noise, keep part of it. On a simulated night sky, about a quarter of the fine grain
  is left (23% of the finest, 29% of the next size up) and a bit over a third of the wider blotches (38%). Stars
  only as bright as the noise keep about half their light, stars twice as bright 80%, and stars three times as
  bright or more all of it (97 to 100%). A faint glow like the Milky Way keeps 91% of its light. The photo's sky
  comes out about three times smoother (3.7 against 12.2 levels of noise in the JPEG).
- **Measured on each photo, part by part.** How much to take out comes from the photo itself: its noise is
  measured between the stars, at several sizes and region by region. The corners (which the lens's shading
  correction brightens) and the edges (where fewer frames overlap) are usually noisier, so each part of the sky
  is cleaned as much as it needs. The session log says what it found.
- **Quick look's half-size photos are cleaned too**, matched to their smaller stars: about half the sky's noise
  goes, and faint stars keep most of their light.
- **The photo on the screen is the photo you get.** The result screen and the edit screen show the sky cleaned as
  in the saved photo. Once the full-size photo is saved, the screen shows exactly what it lost, scaled down.
- **Photos from earlier versions can have it.** Open one from the sessions page and tap **New processing** on its
  result screen, or **Make it again with the new processing** in the edit screen. It's rendered again from the
  stacked picture the app keeps with the session, so it usually takes a few seconds. It's saved as a new render;
  the old one stays. Edits of an older photo get it too, and their note says so ("edited: the new processing,
  stretch 7").

### Install the update (Windows, USB cable)

It installs over version 1.3 and keeps your settings and sessions.

1. Download `NightSky.apk` from the chat to your PC.
2. Plug the phone into the PC. On the phone, tap the USB notification and choose **File transfer**.
3. In File Explorer, open the phone, then its **Download** folder, and copy `NightSky.apk` into it (replace the
   old one if Windows asks).
4. On the phone, open the **Files** app, then **Downloads**, tap `NightSky.apk`, then tap **Update**.

### Defaults

- New: every saved photo, full size or Quick look, has the sky's noise taken out. There's no setting for it.
- Everything else is unchanged from version 1.3.

### Try today

- If you've already taken a session: open it from the sessions page, tap **New processing**, then compare the
  two photos in Google Photos, zoomed in on the sky between the stars.
- If you haven't yet: the **camera check** (from version 1.3) is still the thing to run first.

### Try tonight

1. Tap **Start**, lay the phone down and let it run 5 minutes. Open the photo in Google Photos and zoom in on the
   dark sky between the stars: it should look smooth rather than speckled, with the faint stars still there as
   points of light.
2. If anything looks off (smeared or missing faint stars, blotchy patches, dark rings around stars), send the
   photo here with **Session report > Share the log**.

### Not working yet, or not tested

- Tested off the phone only: on simulated skies, and through the app itself with a simulated camera. Your first
  night's photo is the real test.
- There's no strength setting yet: every photo loses the same share of its noise.
- The live picture while a session stacks, and the laptop viewer, aren't cleaned: they show the stack as it
  builds.
- Saving a full-size photo takes a few seconds longer (about 3 seconds for the full 12.5-megapixel photo on a
  4-core computer). Developing still needs at most about 320 MB of the 512 MB of memory Android gives the app.
- Haze is still only measured once the app has seen a clear night.

## Version 1.3: a camera check before your first night

### What's new

- **Camera check.** Up to three minutes indoors, with the camera against a table, and you know what this phone's
  camera really does before a night depends on it: whether the night's long exposure comes out in full, whether
  it keeps going frame after frame, and how its sensor looks in the dark (hot pixels, noise, the black level).
  Sessions plan with what it finds. The home screen offers it until you've run it (or tapped **Not now**), and
  it's always under **Advanced > Camera check**. **Share the details** sends everything it measured, and a copy is
  saved in `Documents/NightSky`.
- **Sessions keep going past a camera that stops after so many frames.** Some Pixels stop after 63 frames in a row
  (another astro app fixed exactly that for Pixels 7 to 10 this summer). A session only noticed once the camera had
  been silent for twice the frame length plus 15 seconds (47 seconds with 16-second frames), then restarted it.
  Now, once the check or a session has seen such a stop, sessions give the camera a fresh capture session a few
  frames before it would stop: a pause of about a second. If only closing and opening the camera gets it going,
  that's what they do (about 2 seconds). If a night's long frames stop sooner than the check's one-second ones did,
  the renewals come sooner from then on.
- **The session report says when that happened** ("The camera got a fresh capture session twice (every 60
  frames)"), or, when a session learns it from a stop of its own, says so with that stop ("It had stopped after 63
  frames in a row, so from then on it got a fresh capture session every 60 frames"). Each session's log notes what
  the last camera check found.
- **Share the log.** The session report has a **Share the log** button that sends the session's log, every step
  and choice it made, as a text file: the quickest way to show what happened on a night. A session that couldn't
  run has one on the screen it ends on.
- **A photo no longer depends on its details being written.** If the date, camera and exposure details (EXIF)
  can't be added to a photo, it's saved without them and the session log says so, instead of the whole develop
  failing.

### Install the update (Windows, USB cable)

It installs over version 1.2.1 and keeps your settings and sessions.

1. Download `NightSky.apk` from the chat to your PC.
2. Plug the phone into the PC. On the phone, tap the USB notification and choose **File transfer**.
3. In File Explorer, open the phone, then its **Download** folder, and copy `NightSky.apk` into it (replace the
   old one if Windows asks).
4. On the phone, open the **Files** app, then **Downloads**, tap `NightSky.apk`, then tap **Update**.

### Defaults

- New: the home screen offers the camera check until it has been run or put off.
- Everything else is unchanged from version 1.2.1.

### Try today (indoors, any time of day)

1. Open the app and tap **Check the camera** on the card at the top of the home screen.
2. In a dim room, lay the phone screen-up on a table so the camera is against it, tap **Start**, and leave it
   until it says it's done (up to three minutes).
3. **Ready for the night** means you're set. If it says **Ready: sessions work around what it found** or **The
   camera needs a look**, tap **Share the details** and paste them here in the chat.

### Try tonight

1. Tap **Start**, lay the phone down and let it run 5 minutes. Afterwards, **Session report > Share the log**
   sends what happened if anything looks off.
2. If the check found that the camera stops after so many frames, a 20- or 30-minute session is the real test:
   its **Session report** should note the fresh capture sessions and no camera trouble.

### Not working yet, or not tested

- Still all tested off the phone, with a simulated camera (including one that stops after 63 frames): the camera
  check on your Pixel is the first real test, and its shared details say exactly what the camera did.
- Haze is only measured once the app has seen a clear night, so on the first night a hazy sky still reads "Clear
  sky (haze not measured yet)".
- The check can't show how the phone copes with heat over a long session.
- Also checked at your Pixel's full 12.5-megapixel frame size: developing a session needs at most about 320 MB of
  the 512 MB of memory Android gives the app, so there's room to spare.

## Version 1.2.1: fixes from a full dress rehearsal

Before your first night, whole sessions were run through the app's own capture code with a simulated camera
showing tonight's real sky, with everything that can go wrong made to go wrong (the phone picked up or knocked,
the camera stalling or failing frames, another app taking it, the battery running down), and every screen was
tapped through again. That found these, now fixed:

- **Focus on the stars now lands on the sharpest focus.** At the sharpest focus a star falls on just a pixel or
  two, and the focus sweep couldn't measure stars that small: it took them for hot pixels. So it settled on a
  softer focus, or gave up on the stars. It now fits each star's shape across all four colour pixels, keeps
  bright stars whose middle clips, and measures two to five times as many stars in each focus frame. In a
  simulated session it found 0.36 against a true 0.35 (before: 0.10).
- **In a long session, a focus check that finds the focus has crept now follows it all the way.** It moves at
  most two small steps at a time (in case one reading is off), and then it didn't check again, so the stars stayed
  a little soft for the rest of the session. In a simulated 30-minute session whose focus crept, the stars ended
  the session 5% wider than at the start; now it checks again straight away until it gets there, and they end
  the session as sharp as they started.
- **A frame the camera fails no longer restarts the camera.** Only good frames counted as signs of life, so two
  failed frames in a row looked like a stalled camera, and the restart threw away the frames already exposing.
- **If even the camera's longest exposure keeps failing, the session uses frames half as long** (for that
  session only; the next tries the full length again) instead of ending with "No frames were captured".
- **If the camera errors as it opens, the app tries once more** before giving up, and camera errors are said in
  words ("another app is using the camera") instead of "camera error 1". The session log now keeps what happened
  before the first frame too (the camera erroring and being opened again, a sky check that was skipped and why).
- **While the camera is being restarted, the screen and the laptop viewer say so**, instead of a frame count that
  looks frozen.
- **The session report blames the right thing.** A pickup, a stalled camera and failed frames were all "Frames
  dropped: close other apps". Now a pickup is "The phone was moved" (and how long it paused), camera trouble is
  "The camera had trouble" (frames it lost, times it went quiet until the app restarted it), and only frames the
  phone couldn't keep up with are frames dropped. And since a session keeps going until it has every frame it
  planned, a hiccup it made up for is a note ("the session ran 47 seconds longer to get all its frames"), not
  "cost 21% of the light".
- **A session that ends early says why**: the camera stopped, another app took it, the battery ran low, storage
  ran out or the phone got too hot, with how much of the session it cost and what to do. Stopping it yourself, or
  at its end time or dawn, is a note.
- **The report no longer blames a Moon it couldn't see.** With the Moon up but hidden from where you were (behind
  the house, trees or a hill), the report said moonlight cost 95% of the light, right next to "an excellent dark
  sky". It now goes by how bright the sky actually measured, and at a saved site it knows how bright that sky is
  without the Moon, so the site's own light isn't put down to the Moon either.
- **No false "smudged lens" warnings once the app knows your lens.** After a few good nights it learns what a
  clean lens reads, but it set its limit from one or two readings, and ordinary variation from night to night
  then tripped it (once in nine clean sessions in testing); the report then blamed a smudge too. It now learns
  how much a clean lens's readings vary and keeps its limit clear of that. A smudged lens still reads several
  times over it.
- **When the camera can't expose as long as planned**, frames are combined (four 4-second exposures make one
  16-second frame) and the session now plans whole ones. The last could be a quarter of the others, and the
  report called it a frame with a "brighter sky". The report now also says when and why exposures were combined,
  since the screen counts the exposures ("Frame 44 of 44") and the report the frames they make ("11 frames").
- **Star trails get a real report.** It said "0 frames, all used" and the voice said "Done. 0 good frames". It now
  counts the frames, notes any that were knocked, and says "Star trails from 18 frames".
- **Reopening the app doesn't start another session.** If a session was started from the widget or a shortcut,
  and Android later closed the app in the background, reopening it from the recent apps started a second one
  (or re-armed the night plan, if a plan notification had opened the app). Now it just opens where you were.
- **The night plan says each session's time once**: it read "5-minute stack, from 10:10 PM: 10:10 PM, only if
  clear" on the plan page and the widget.
- **The app comes back on the page you were on** (Sessions, the plan, Sites) when Android has closed it in the
  background, instead of the home screen.

### Install the update (Windows, USB cable)

It installs over version 1.2 and keeps your settings and sessions.

1. Download `NightSky.apk` from the chat to your PC.
2. Plug the phone into the PC. On the phone, tap the USB notification and choose **File transfer**.
3. In File Explorer, open the phone, then its **Download** folder, and copy `NightSky.apk` into it (replace the
   old one if Windows asks).
4. On the phone, open the **Files** app, then **Downloads**, tap `NightSky.apk`, then tap **Update**.

### Defaults

Unchanged from version 1.2.

### Try tonight

1. Tap **Start**, lay the phone down and let it run 5 minutes.
2. Under the photo, tap **Session report**: the note that starts with "Focus" says where it focused and how sharp
   the stars were there, and if anything went wrong, the report now says what and what to do.

### Not working yet, or not tested

- Still all tested off the phone, with simulated skies: the first real night is the real test, and the session
  log in `Documents/NightSky` says what happened.

## Version 1.2: smarter nights, photos that add up, and edits you can always undo

### What's new

**Before a session**

- **Pre-flight check.** Once the phone lies still, it takes one test frame and says what it found: a smudged
  lens, a tree or roof in the way, clouds, the Sun or the Moon, clipping, storage and battery. After a warning
  you have 10 seconds to pick the phone up and fix it; putting it back down checks again.
- **Focus on the stars.** The lens sweeps through focus on the stars themselves and keeps the sharpest point
  (about 30 seconds). Sessions of half an hour or more check it again if the stars grow. **Daylight focus
  calibration** (Advanced) teaches it how this lens's focus moves with temperature, so the sweep gets quicker.
- **Presets.** One tap for **Backyard**, **Dark site** (10 minutes, brighter), **Partly cloudy** (keeps going
  until it has the clear minutes you asked for) or **Quick look** (2 minutes, JPEG only, half-size photo, about
  twice as fast to develop), plus your own.
- **Saved sites.** A place you shoot from remembers its preset, how dark its sky is and what blocks the view. A
  session that starts within about 2 km of a site uses its preset and says so.
- **Night plan.** Plan the night (start at full darkness, at moonset, in the best window or at a set time), arm
  it, lay the phone down outside, and the sessions start by themselves, only if the sky is clear. In the
  morning a notification shows the photos.
- **Tonight card.** On the home screen: when it gets dark, the Moon, the cloud forecast and the best window.
- **Quick Settings tile, widget and shortcuts.** A "Night sky" tile (two taps from the lock screen to a
  session), a home-screen widget with tonight's score and your last photo, and shortcuts when you touch and hold
  the app icon.
- **Sky reading.** "Sky reading: how dark is it here?" measures the sky in magnitudes per square arcsecond, like
  a Sky Quality Meter, and keeps a log you can save for Excel.

**While it stacks**

- **Partly cloudy nights.** Each frame is used region by region, so a frame half covered by cloud still gives
  its clear half. An optional **cloud-blur version** turns drifting clouds into soft streaks.
- **Sparse star fields** (low down, hazy, between clouds) line up far better.
- **No more walking noise or banding streaks** across the stack.
- **True star colours.** The white balance is measured from hundreds of stars of known colour in your own photo.
- **Satellites named.** Streaks are matched to named satellites, planes and meteors are told apart, and the
  streaks are taken out of the photo. **Satellites** and **Names** under the photo draw their tracks and names
  on it, and **Save this copy** keeps that version.

**After a session**

- **Session report.** Tap **Session report** under the photo: what the picture shows (constellations, planets,
  the Milky Way, galaxies to look for), a score for every frame, and what went wrong, with the evidence and the
  fix.
- **Sessions page.** Every session's photo stays openable, and its frames are kept (up to 20 GB, oldest go
  first) so you can do more with them later.
- **Add 5 minutes.** Under a photo you just took, or with volume up (or a Bluetooth remote) while it captures.
  The extra minutes are stacked into the same photo.
- **Merge nights.** Shoot the same sky on another night (**Repeat tomorrow** sets it up) and merge the sessions
  into one deeper photo.
- **Edit.** Tap **Edit** under any photo:
  - **Look:** stretch, exposure, black point, colour, sky brightness, and a natural, punchy or linear look.
  - **Crop:** 4:3, 3:2, 16:9 or 1:1, and **North up**.
  - **Frames:** trim the start or the end, leave frames out, choose the frame the others line up on, or line a
    frame up by hand by tapping the same stars in both.
  - **Ground:** keep the ground still and sharp, and paint where the ground is if it missed a bit.
  - **Renders:** every saved version, to open again.
  - Press and hold **Compare** to see the photo as it was. Every save is a new version; nothing is ever
    written over.

### Install the update (Windows, USB cable)

It installs over version 1.1 and keeps your settings.

1. Download `NightSky.apk` from the chat to your PC.
2. Plug the phone into the PC. On the phone, tap the USB notification and choose **File transfer**.
3. In File Explorer, open the phone, then its **Download** folder, and copy `NightSky.apk` into it.
4. On the phone, open the **Files** app, then **Downloads**, tap `NightSky.apk`, then tap **Update**.
5. The first time you tap **Start**, it asks for **location**: allow it (approximate is enough). It's for the
   Sun, the Moon and naming the stars, and it never leaves the phone.

### Defaults

- **Pre-flight check:** on (untick **Check the sky first** on the home screen to skip it).
- **Focus on the stars:** on (Advanced > Focus).
- **Satellites:** taken out of the photo. Their orbits come from CelesTrak, and the cloud forecast from
  Open-Meteo with your place rounded to about 10 km. Nothing else is sent; both can be turned off in Advanced.
- **Frames kept after developing:** up to 20 GB, oldest sessions' frames first (Advanced > Sessions and
  storage; the photos always stay).
- **Night plan:** only if the sky is clear, silent, and stops below 20% battery.
- **Edits:** the stacked picture of the original photo and of your newest edits stay on the phone; older edits'
  are made again from their recipe if you open them.

### Try tonight

1. Tap **Start**, lay the phone down and listen to the pre-flight check, then let it run 5 minutes.
2. Under the photo, tap **Session report** to see what's in the picture and how each frame scored.
3. Tap **Add 5 minutes**, lay the phone back down, and see the longer photo.
4. Tap **Edit**: try **Punchy** and a **16:9** crop, press and hold **Compare** to see the difference, then
   tap **Save**.
5. Before bed, tap **Plan the night**, add a session, tap **Arm the plan**, and leave the phone screen-down
   outside on a cloth, somewhere dry.

### Not working yet, or not tested

- Everything new was tested on a desktop with simulated skies, and every screen was drawn off the phone. None of
  it has run on the Pixel yet, so the first real night is the real test; the session log in `Documents/NightSky`
  says what happened.
- Focusing on the stars depends on how the Pixel's lens reports its focus, which only a real night shows.
- Satellites are only named once the phone has downloaded their orbits (it does that by itself when online).
- Sessions developed before version 1.2 can't be opened, edited or merged in the app (their photos are still in
  Google Photos): their stacks weren't kept.
- Merged photos can change their look and crop, but not their frames.
- A watch app for the viewer isn't made; the laptop or a second phone's browser does that job.

## Version 1.1: the viewer

Watch the photo build up on a laptop, tablet or second phone while your phone lies screen-down.

- **What it shows:** the stack so far, the latest frame, a countdown, and, for each frame, the number of stars,
  how sharp they are, how clear the sky was, and why any frame was left out (clouds, a bump).
- **What it can do:** start the countdown early, add 5 minutes, mute the phone's beeps, stop and develop, take
  another photo, and download the finished JPEG, DNG and log.
- **Safe by design:** it only works on your own Wi-Fi or the phone's hotspot, never over mobile data or the
  internet, and it needs the 6-digit code shown on the phone. The code changes every session.

### Open the viewer from a Windows laptop

1. Put the laptop on the **same Wi-Fi** as the phone. No Wi-Fi out there? On the phone's start screen tap
   **No Wi-Fi here? Start a hotspot for it**, then on the laptop click the Wi-Fi icon at the bottom right of
   the screen, pick the network name the phone shows and type the password it shows.
2. On the phone, tap **Start**. The screen shows an address like `http://192.168.1.23:8765` and a 6-digit code.
3. On the laptop, open Chrome or Edge, type that address into the address bar exactly as shown (with the
   `http://`), and press Enter.
4. Type the 6-digit code and click **Open the viewer**.
5. Lay the phone down. The laptop shows the countdown, then the stack building up.

A phone or tablet can just scan the QR code on the phone's screen instead.

- The viewer is **on**. Untick **Viewer** on the home screen to turn it off.
- Once the photo is done, the viewer stops by itself 30 minutes after you last used it (2 hours at most), or
  tap **Stop the viewer** under the finished photo.
- With the phone's screen off, Wi-Fi saves power, so the page can lag a few seconds behind the phone.
- A laptop on the phone's hotspot has no internet while it's connected.
