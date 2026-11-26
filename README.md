# Night Sky Camera

A long-exposure star camera for the Google Pixel 10 Pro XL. Lay the phone screen-down under a dark sky and it
shoots a series of RAW frames on the main camera for as long as you choose, lines the stars up between frames,
and stacks them into one clean, deep photo. Or, in Star Trails mode, it draws each star's arc across the sky. Or, in
Earth's turn mode, it adds a few seconds of looping video of the whole sky wheeling round the pole. And while it
works, a laptop on the same Wi-Fi becomes **Mission Control**: a countdown, a computer voice calling the mission,
satellites named live as they cross the picture, star cards, a 3D flight through the stars, and the sky played as
music. When the photo is done: sharp close-ups of the galaxies, nebulae and planets in it (**Deep Space Zoom**), the
whole night as a looping GIF in the gallery (**Mission Replay**), and where NASA's farthest spacecraft were in your
sky (**Deep Space Network**). Since 2.5 a night can also be a **Time lapse** film, **X-Ray Vision** shows what's in
the photo that no eye can see (neutron stars, black holes, exploded stars, stars with planets), and **Night Sky on
your Pixel Watch** starts, follows and stops it all from your wrist, over Bluetooth or the internet. Since 2.6 the
photo also brings the particles from space that shot through the camera while it looked up (**Cosmic Ray Hunter**),
the map to the Sun that the Voyagers carry, redrawn for your night (**Earth's Address**), and your sky as seen from a
planet round another star (**Postcard from Another World**); and the watch's **Sky Pointer** names whatever you point
your arm at. Since 3.0, **the Moonshot**: the Moon from the 5x telephoto, hand-held, 150 RAW pictures stacked into
one sharp photo with every landing site labelled where it is tonight, and the **Transit Hunter**, which catches the
International Space Station crossing the Moon by itself, with the countdown on your watch. Since 3.1 the Moonshot's
photo names the Moon's seas, craters and mountains where they are tonight (**Moon map**), and on a crescent it brings
out the dark part, lit by sunlight bouncing off the Earth (**the Earth's glow**). Since 3.2, **Jupiter's moons**:
Jupiter from the telephoto with its four big moons, each named where it is tonight, and **Galileo's notebook**, your
nights drawn beside the ones Galileo drew in January 1610.

It needs **Android 17** or newer and is made for the Pixel 10 and newer.

What changed in each version: [WHATS-NEW.md](WHATS-NEW.md). This is version 3.2.

## What it does

- **Deep sky** (default). Shoots RAW frames for 1 to 30 minutes (5 by default), lines up the stars between
  frames as the sky turns, throws out satellites, planes and passing clouds, and stacks everything into one
  photo with far less noise than a single shot.
- **Star trails.** Keeps the stars where they fall in each frame, so they draw arcs around the pole star. Longer
  is better: 20 or 30 minutes.
- **Time lapse** (2.5). A deep photo, plus the whole night as a film: every frame of the night is one moment of
  it, so the stars wheel across, clouds drift by and satellites zip through. 10 to 90 minutes (30 minutes makes
  about 8 seconds of film, 90 about 20), with the time of night and how much faster than real life in the corner.
  Three looks: **Sky turning** (the night as it was), **Trails growing** (each star draws its trail until the film
  ends on a star-trails picture) and **Comet tails** (each star drags a glowing tail). Made in one look and remade
  in the others afterwards from the same frames; any night whose frames are kept can be made into one (**Make a
  time lapse** on its result screen). The film is an MP4 in the gallery.
- **Earth's turn.** A deep photo, plus a 5-second clip that loops for ever: the sky turning round the pole while
  the ground stays still, made from how far the frames themselves measured the sky turning. It saves as a video
  (MP4) and as an animated picture that plays by itself like a GIF (WebP, about a third of a GIF's size), and says
  what it measured: how long the Earth takes to turn once by the stars, and your latitude. Try 15 or 30 minutes.
- **Bright stars in colour.** In deep sky the brightest stars usually burn out to flat white blobs. Now, after
  every few long frames, the app takes one 16 times shorter, which sees them without burning out, and gives their
  centres their real brightness and colour back.
- **Camera check.** Up to three minutes indoors, before your first night: what this phone's camera really does
  with long exposures, frame after frame and in the dark. Sessions plan with what it finds.
- **Rehearse a night.** About ten minutes indoors, at any time of day: the phone lies screen-up on a table and a
  clear night's sky is drawn into its camera's own RAW frames, so the whole night runs for real (the focus, the sky
  check, the frames, the stacking, the HDR photo and its video) and every step is checked against the sky it was
  given. "Rehearsal check: all 11 passed" means the phone is ready for a night.
- **Stop recorder.** If the app is ever stopped in the middle of something (a crash, Android closing it for memory
  or battery, a force stop), the next time it opens it says why and what it was doing, with the details to share.
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
  stack shows. Hot pixels are taken out too, the ones that come in pairs and threes as well as single ones.
- **The Milky Way brought out.** The sky's own detail under the grain, the Milky Way's dark lanes and star
  clouds, is made stronger, and never darker than the plain sky; **Sky detail** in the edit screen sets how much.
  The sky's glow (light pollution) is taken out evenly, even when trees or a roof fill part of the picture, and
  since 2.4 it's told apart from the Milky Way's own glow (the app knows where the Milky Way runs through the
  picture), so the glow goes and the Milky Way stays.
- **Sharp stars without coloured edges.** How big the lens and the air drew the stars is measured in each photo,
  on its own stars, and part of that blur is taken back out: stars come out smaller and brighter in the middle,
  with exactly the light they had. The red and blue edges a lens gives the stars towards the corners are measured
  on the stars too, and taken out as the frames are stacked. **Star sharpness** in the edit screen sets how much.
  Since 2.4 the purple halo a phone lens leaves round bright stars (its blue and red light focus a little apart
  from its green) is measured on the photo's own stars and taken out too.
- **Deep Space Zoom.** Up to three close-ups of the best things in the photo: galaxies (Andromeda!), nebulae,
  star clusters, and Jupiter, Saturn, Uranus or Neptune. Each one is made again from the frames at twice the
  detail (a drizzle, as the Hubble team does it), its stars drawn smaller and rounder so the galaxy or nebula
  shows, and saved as a card with its name, how far away it is (and when its light left), how big it is, and a
  fact. Jupiter's card shows where its four big moons were that night.
- **Mission Replay.** The whole night as one looping GIF in `Pictures/NightSky`: the frames adding up, the photo,
  the spacecraft in it marked, its close-up targets locked in green brackets, a zoom into each one that "enhances"
  into its card, and MISSION COMPLETE. It plays on the result screen and loops in any message.
- **Deep Space Network.** Where the farthest things people have made were when you took the photo: Voyager 1 and
  2, New Horizons, Pioneer 10 and 11, L2 (where the Webb telescope loops), and Pluto. The ones in the photo are
  marked on it (with **Names**); a card on the result screen says which were in it, which were up in your sky and
  where, how far away each one is, and when a radio message sent as you took the photo would get there.
- **X-Ray Vision** (2.5). The universe the photo can't show, marked where it is in it: black holes (Cygnus X-1,
  Gaia BH3, the giant ones in the middle of the Milky Way and of M87), neutron stars and pulsars (the Crab Pulsar,
  the first pulsar ever found), the clouds left by exploded stars (Cassiopeia A, Tycho's and Kepler's supernovae),
  the two neutron stars whose crash was heard in gravitational waves, stars with planets (51 Pegasi, TRAPPIST-1),
  the patches of sky NASA's telescopes stared at (the Hubble Deep Field, Kepler's field) and the messages across
  space (the Arecibo message, the Wow! signal): 46 in all. The photo turns into X-ray film blues with a symbol for
  each, saved in the gallery; the result screen's **X-Ray Vision** card says what each one is in a sentence or two,
  and **Hear the pulsars** plays each pulsar as the clicks it really makes, at its own speed.
- **Pixel Watch** (2.5). Night Sky on the watch: pick the setup with the crown, Start, see the countdown and the
  ring fill as the frames come in, add 5 minutes, Stop or Mute from your wrist, and feel a buzz when the photo is
  ready. Over Bluetooth when the phone is near (with the live picture), over the internet when it isn't. Steps
  below, under **Night Sky on your Pixel Watch**.
- **Sky Pointer** (2.6, on the watch). Point your arm at the sky and the watch names what's there (833 stars, the
  Moon, the planets, galaxies, black holes, Voyager 1 and the other far travellers), how high and which way. Or turn
  the crown to pick one and an arrow steers your arm to it, clicking faster as you close in, even below the horizon.
- **Cosmic Ray Hunter** (2.6). The particles from space (muons, mostly) that went straight through the camera's chip
  while it looked at the sky: each lit a few pixels in one frame only, so the photo never shows them. Found in the
  frames before they're deleted (stars, satellites, planes, meteors, clouds and hot pixels left out), shown under a
  microscope with how much energy each left and when, and a map of where they struck.
- **Earth's Address** (2.6). The pulsar map that Pioneer 10 and 11 and both Voyagers carry, the way to the Sun for
  whoever finds them, redrawn for the night of your photo: each pulsar's tick worked out for that date (they slow
  down, so the map gives the date as well as the place), the ones in your photo or up in your sky picked out, your
  photo where the Sun is. **Hear its pulsars** plays them at their real speeds.
- **Postcard from Another World** (2.6). The night sky from a real planet round another star (one whose star is in
  your photo, else one that was up, else Proxima b): every star moved and made brighter or fainter for being seen
  from there, the constellations pulled out of shape, our Sun one star among the rest; and the planet writes home.
- **HDR photos.** Photos are saved as Ultra HDR photos: on this phone's screen, in Google Photos and in the app,
  the stars shine up to four times brighter than white. Anywhere else they look exactly as before.
- **Meteors.** Every streak is sorted out: a satellite (named when the app knows it), a plane, or a meteor
  (one no satellite could have left). Meteors are taken out with the rest, or kept in the photo with **Keep
  meteors**. Each one caught is listed with its time, its shower, how bright it was and where it crossed, and the
  **Tonight** card says when a shower is worth going out for. Each frame is also searched for lines too faint to
  see in it, so fainter meteors are listed too.
- **Photos that play.** Each photo has a few seconds of video inside it that ends on the photo: the frames adding
  up until the sky's grain melts away, or the trails drawing themselves. Google Photos plays it (it's a Motion
  Photo, like the Pixel camera's own), and so does **Play its video** in the app.
- **Constellations you can see.** **Names** draws each constellation's figure on the photo, the lines from star
  to star that star charts draw, with its name inside it. **Star glow** in the edit screen gives the brightest
  stars a soft glow in their own colours, bigger the brighter the star; a star so bright it filled its pixels
  takes its brightness and colour from the star catalogue once the app has recognised the sky.
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
- **Mission Control (on a computer).** Open the phone's address with `/mission` on a laptop on the same Wi-Fi: a
  big countdown, a computer voice ("Focus locked!", "Liftoff!", "Halfway there!"), sound effects, the live picture
  and giant counters (frames, stars found, seconds of starlight, satellites caught). Steps below.
- **Live catches.** Each frame is searched for streaks as it lands: satellites named as they cross ("Got it! The
  Space Station is in the picture!", with an alarm), satellites announced before they arrive, planes, meteors,
  and mystery satellites that aren't on the list, each with a close-up snapshot.
- **Star Spotter.** The live picture with the constellations' lines and the stars' names; click a star for its card
  (what kind of star, how far, and what was happening on Earth when its light set off); name a star yourself and
  it keeps that name, with a pink heart, on the page and in the phone's photos of that sky.
- **3D Star Flight.** Fly through the photo's stars at their real distances: the constellations fall apart, and
  from another star you can look back home at the Sun.
- **Hear the Stars.** The photo played as music: a line sweeps across it and each star sings as it's reached, blue
  stars high and red stars low. On the computer, and on the phone's result screen with a video to share.
- **Mission patches.** 20 badges earned over many nights (First Light, Space Station, Meteor Hunter, Night Owl,
  and since 2.4 Deep Space Network and Pluto Patrol...).
- **Viewer.** Watch the photo build up from a laptop, tablet or another phone on the same Wi-Fi.
- **Files.** JPEG with its video inside, named `.MP.jpg` (and optionally a RAW DNG) in `Pictures/NightSky`, so
  they show up in Google Photos. Logs, settings files and sky readings go in `Documents/NightSky`.

## Install it on your phone

Night Sky needs Android 17 or newer since 3.0 (**Settings > About phone > Android version**). You only need the file
`NightSky.apk`. It's attached in the Claude chat, and it's in this repository as
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

## The Moonshot (3.0)

Tap **Shoot the Moon** at the top of the main screen.

1. **Point the phone at the Moon.** It uses the 5x telephoto (on a phone without one, the main camera zoomed in),
   finds the Moon anywhere in the picture and rings it, with a close-up in the corner. The line at the top says the
   Moon's phase, how much is lit and how high it is (or when it rises).
2. **Wait for "Ready: hold steady and shoot".** It sets the exposure on the Moon's bright parts and focuses on its
   craters by itself, a few seconds. **Focus again** starts the focusing over.
3. **Shoot the Moon.** Hold the phone with both hands, elbows against your chest or a wall, for five seconds: it
   takes 150 RAW pictures. The sharpest half are lined up and stacked, which takes a few seconds more.
4. **Your Moon:** the photo with the sky's up at the top, and the landing sites labelled (Apollo in gold). **Tap a
   label** for its story. **One frame** shows a single picture to compare; **Every one** labels all 36 places;
   **Mineral colours** turns the Moon's real colours way up. It's saved in Pictures/NightSky with and without the
   labels, and as a Moon map; **Share** sends the one on screen.
5. **Moon map** (3.1) names the seas, craters and mountains on the lit side where they are tonight (134 of them, the
   famous first, never on top of each other), with the craters along the line between day and night picked out:
   their shadows make them tonight's best. **Tap a name** for its story.

**The Earth's glow** (3.1): when the Moon is a crescent, the button under the shutter says **Earth's glow too: on (4
more seconds)**. Keep holding after the first click: after the 150 pictures the phone takes 120 more, up to 400 times
brighter, until the second click. The crescent burns white in those, but the dark part shows, lit by sunlight
bouncing off the Earth; the app puts it into the photo's dark side. **Earth's glow** on the photo switches it off and
on, the Moon map names what you can see there by the Earth's light alone, and the photo is saved once more without
the glow. A thin crescent in the twilight, two to five days from new Moon, held as steady as you can, shows it best.
Tap **Earth's glow too** before shooting to skip it.

**The Transit Hunter** card below lists the times the International Space Station or Tiangong cross the Moon within
60 km of you in the next ten days: when, how far and which way to go, and how wide the strip is where it's seen.
**Show the nearest strip on the map** opens Maps there. When a crossing is seen right where you are within six hours,
tap **Catch the ISS at ...**, keep the Moon in the picture, and the phone shoots by itself four seconds before (it
beeps from ten). The photo then shows the station's silhouettes and its path: **Your Moon, with ISS crossing it**.
Tap the button again to stop waiting.

- The strips are only a few kilometres wide, so allow **Precise location** when it asks.
- The orbits come from CelesTrak with **Satellite data** on in Settings (it's on unless you turned it off). They
  change a little with every update: look again the day before.
- Something else crossing the Moon while you shoot (a plane, a bird) is found too, as a **Mystery flyer**.
- Best Moon nights: a half Moon (the craters along the line between day and night throw long shadows), high up.

## Jupiter's moons (3.2)

Tap **Shoot Jupiter's moons** on the main screen (the card under the Moonshot's).

1. **Tonight first.** The top says where Jupiter is (how high, which way, or when it rises) and whether now is a good
   time. The strip under it shows the four moons where the sums put them right now, east on the left, and below that
   what happens in the next 12 hours: a moon going behind Jupiter, crossing in front of it, or into its shadow.
2. **Point the phone at Jupiter**, the brightest "star" that doesn't twinkle. It uses the 5x telephoto, finds Jupiter
   anywhere in the picture, sets the exposure, focuses, and checks the exposure again once Jupiter is sharp. Wait for
   **Ready: hold steady and shoot**. **Focus again** starts the focusing over.
3. **Shoot Jupiter.** Hold the phone with both hands, elbows against your chest or a wall, for about eleven seconds:
   90 RAW pictures for Jupiter (3 seconds), then 240 sixteen times brighter for its moons (8 seconds), until the
   second click. The watch's **Shoot** on its Moon screen works too while this screen is open.
4. **Your Jupiter, with ...**: the photo with north up and east on the left, as Galileo drew it, Jupiter's glare
   dimmed so a moon close to it shows, and each moon named where it is. A moon that isn't there says why: behind
   Jupiter, crossing in front of it (and until when), in its shadow, lost in its glare, or in the same dot as another
   moon (two moons are often too close together for a phone to show apart). **Tap a moon** for where it is and its
   story (on a dot with two moons, tap again for the other). **Names** and **No names** switch the labels. It's
   saved in Pictures/NightSky three times: plain, with the names, and the notebook page; **Share** sends the one on
   screen.
5. **Galileo's notebook**: his seven nights of January 1610 as he drew them (Jupiter an O, the moons little stars,
   **Ori.** east and **Occ.** west), with what he wrote; your nights under them (the newest kept on the page, the last
   40 in the phone); and the next five nights from the sums, in pencil, each at the best time to look. **Share the
   notebook page** sends it.
6. **Stories**: Jupiter, each moon, Galileo in 1610, the speed of light from Io's eclipses, the 1:2:4 dance of Io,
   Europa and Ganymede, and how to see the moons yourself with binoculars.

Best: Jupiter high in a dark sky, the phone held as steady as you can (or against a wall or a car roof). Io moves
enough to see in an hour; the moons change sides from night to night. This season Jupiter is at its closest and
brightest on 11 February 2027, up all night.

## Night Sky on your Pixel Watch

Since version 2.5 there's a second app for the watch, `NightSkyWatch.apk`, next to `NightSky.apk`. With it on the
watch you can start a photo, follow it and stop it from your wrist: handy when the phone is lying screen-down in
the grass and you're sitting in the car, or inside where it's warm. Since 2.6 it's also a **Sky Pointer**: point
your arm at the sky and it says what's there.

### Bluetooth or the internet: what works where

- **Phone and watch near each other** (same yard, about 10 m): they talk over **Bluetooth**, straight to each
  other. Quickest, the watch shows the phone's live picture, and it needs no Wi-Fi and no phone signal at all, so
  it works at a dark site in the middle of nowhere.
- **Apart** (phone out in the yard, you indoors; or the watch on its own Wi-Fi or LTE): they talk over **the
  internet**. Both need a connection (the phone Wi-Fi or mobile data; the watch through the phone, Wi-Fi or its own
  LTE). Every button and the ring work the same, a few seconds slower; the live picture stays Bluetooth-only
  (pictures are too big to send through a free relay all night).
- **It picks by itself**, both at once when both work. The top of the watch's screen says which one it's using.
- **One rule from Android:** an app can open the camera only while it's on screen, so to **start** a photo from
  the watch, Night Sky must be open on the phone (or have just finished a photo: it keeps listening for the watch
  for 10 minutes after). Once a photo is running, everything works whatever the phone's screen is doing.
- **Private:** the internet way goes through [ntfy.sh](https://ntfy.sh), a free public relay. Every message is
  locked (AES-256) with a key that only your phone and your watch have: they made it between them when you paired
  them, and it never leaves them. So the relay only ever sees scrambled text, and nobody else can read or send
  Night Sky's messages, even knowing where to look.

### Put it on the watch (from the Windows PC, over Wi-Fi)

A watch has no USB socket, so the PC sends the app over Wi-Fi with Google's tool (the same `C:\platform-tools` as
in **If the phone refuses to install it** above; download it first if you haven't). The watch and the PC must be
on the same Wi-Fi: on the watch, **Settings > Connectivity > Wi-Fi**, turn it on and pick the PC's Wi-Fi.

1. Download `NightSkyWatch.apk` and copy it into `C:\platform-tools`.
2. On the watch, open **Settings > System > About > Versions** (on some watches just **Settings > System >
   About**) and tap **Build number** seven times, until it says you're a developer.
3. Go back to **Settings**: there's a new **Developer options** near the bottom. Open it and turn on **ADB
   debugging**, then **Wireless debugging** (tap **Always allow on this network** when it asks).
4. In **Wireless debugging**, tap **Pair new device**. The watch shows a **Wi-Fi pairing code** (six digits) and
   an address like `192.168.1.42:37123`. Leave that on the screen.
5. On the PC, open **Command Prompt** (Windows key, type `cmd`, Enter) and paste these two lines, with the address
   from the watch instead of mine:

   ```
   cd C:\platform-tools
   adb pair 192.168.1.42:37123
   ```

   Type the watch's pairing code when it asks. It says `Successfully paired`.
6. Back on the watch, go back to the **Wireless debugging** screen itself (not **Pair new device**): it shows the
   watch's **IP address** and **Port**, with a different port from step 4, like `192.168.1.42:41234`. Paste these
   two lines with that address:

   ```
   adb connect 192.168.1.42:41234
   adb -s 192.168.1.42:41234 install -r NightSkyWatch.apk
   ```

   It prints `Success`. Night Sky is in the watch's app list (press the crown).
7. Turn **Wireless debugging** off again on the watch: it uses battery.

Next time (an update): turn **Wireless debugging** on again and do step 6 with the address it shows then (the port
changes each time). Steps 4 and 5 are needed only once. If `adb connect` can't reach the watch, the Wi-Fi may keep
devices apart (some do): put the PC and the watch on the phone's hotspot instead.

### Pair the watch with the phone

1. Open **Night Sky** on the watch. It asks to find nearby devices (that's Bluetooth) and to send notifications
   (to tell you the photo is ready): tap **Allow** for both. It shows six digits.
2. On the phone, open Night Sky, scroll down the home screen to **Pixel Watch** and tap **Pair a watch**. Type the
   six digits and tap **Pair**. If it asks about **Nearby devices**, allow it (that's the Bluetooth way).
3. A few seconds later the watch buzzes twice and both show the same four **check digits**. If they're the same,
   it's your watch and your phone (tap **OK** on the watch). If not, unpair on both and pair again.

You pair once: from then on, opening Night Sky on the watch finds the phone by itself.

### Using it

- **Ready:** the setup the phone will use (a preset's name, or the mode and length). **Turn the crown** to pick
  another of the phone's presets: the phone takes it at once. Tap **Start**, then lay the phone screen-down as
  usual.
- **Getting ready:** what the phone is doing (focusing, checking the sky, the countdown in big digits), with **Start
  now** and **Cancel**.
- **Capturing:** the ring fills as the frames come in, with the time left and the frame count. **+5 min** adds five
  minutes, **Stop** (tap it twice: it asks "Sure?") develops what's been taken, **Mute** silences the phone's beeps
  and voice.
- **Lower your wrist:** the watch keeps following with its screen off and shows the time left in a notification. It
  taps once when the first frame starts, and buzzes long-short-long when the photo is ready, with a notification
  saying so (and what X-Ray Vision found).
- **Done:** **Again** takes another the same way; **+5 min** adds five more minutes stacked into the same photo.
  When the photo caught cosmic rays, it says how many.
- **Tap the middle** of the screen for the connections and **Unpair** (while a photo is running: the Sky Pointer).
- **Sky Pointer** (2.6): tap **Sky** (on Ready, Done, or when something failed; while a photo is running, tap the
  middle) and hold your arm out straight at the sky. The watch names what you're pointing at, what it is, how high
  and which way. **Find** works the other way round: turn the crown to pick something (the Moon, the planets, the
  spacecraft, the black holes, the galaxies, Polaris and the brightest stars that are up, Pluto, the Sun), then move
  your arm the way the arrow points (**Higher**, **To the right**, **Turn around**...). The clicks on your wrist come
  faster as you close in, and it buzzes with **Got it!** when you're on it. Below the horizon too: then you're
  pointing through the Earth. **What's there** goes back to naming, **Back** closes it.
  - It needs to know where you are: the phone tells it (the place it saved, to about 10 km), so open Night Sky on
    the phone once with the watch connected. After that it works anywhere, with no phone and no connection.
  - The top says how sure the watch's compass is (±4° is good). **Compass unsure**: wave the watch in a figure 8 a
    few times, away from cars and metal railings.
  - It takes your arm's direction from the watch's crown side, toward your hand, as a watch is usually worn. If you
    wear yours with the crown toward your elbow, tap the middle of the Sky Pointer once: it flips, and remembers.

- **Moon** (3.0): tap **Moon** (on Ready, and on Done when there's room). The Moon as it looks tonight (its lit side
  and seas turned as they are), how much is lit, how high and which way, and the next Space Station crossing near
  you. While the Moonshot is open on the phone the watch shows it by itself: **Shoot** takes the burst without
  touching the phone, **Catch** arms a crossing. A crossing is counted down to the second with buzzes: a long one at
  a minute, two at ten seconds, taps at three, two and one, and a long one at now. **Back** closes it.

If the watch says **Looking for the phone**, open Night Sky on the phone: the phone only listens while the app is
open or a photo is running. If the Bluetooth way never connects, the internet way still does; check that Night Sky
has **Nearby devices** allowed on both (Settings > Apps > Night Sky > Permissions).

## Rehearse a night (before your first one)

Everything a night does, done indoors in about ten minutes, at any time of day. The stars over where you are, as a
clear dark night would show them that minute (with the Milky Way, and the Moon if it's up), are drawn into the
camera's own RAW frames: their brightness for this phone's lens and sensor, their colours, the sky's glow, the sky
turning through each exposure and the blur where the lens is out of focus. So the phone's real camera, the focus,
the sky check, the stacking, the saving and the memory all get a full run, and each step is checked against the
sky the app drew.

1. Open Night Sky and tap **Rehearse** on the home screen's card (or **Rehearse a night indoors** at the bottom of
   the home screen), then **Rehearse**.
2. Lay the phone **screen-up** on a table, its camera flat against the table: wood, cloth or a closed book, not
   glass. A dim room helps but isn't needed.
3. Leave it. You can watch the screen: it focuses, checks the drawn sky and says what it found, counts down, takes
   five minutes of frames with your settings, and develops the photo.
4. The result screen says how it went: **Rehearsal check: all 11 passed**, or what to look at, or what failed.
   Tap it to see every step (what failed first, then what to look at, then what passed). **Share the check** sends
   it as text: paste it into the Claude chat and Claude can tell what went wrong.

It checks that the camera gave every frame, as long as asked; that the camera was in the dark; that the focus sweep
found where the stars were drawn sharpest; that the sky check read the drawn sky's brightness; that the stars were
recognised where the phone pointed; that the frames were stacked and lined up to a fraction of a pixel; the colours;
the noise; that the photo, its HDR brightness, its video and the DNG were saved; and how fast it drew the sky,
how long developing took and how much memory it needed.

The rehearsal's photo is saved like any other, with `_rehearsal` in its name and "Rehearsal, a drawn sky" in its
description, so it's easy to tell apart and delete. It's kept on the **Sessions** page, labelled **Rehearsal**,
and never merged with a real night. Nothing about its drawn sky (the focus, how dark the sky was, haze, the battery
with the screen on) is kept for real nights; what it finds about the camera itself is real, and kept.

## Taking a photo

1. Go somewhere dark, away from street lights, and wait for the sky to be properly dark. The **Tonight** card on
   the home screen says when that is, where the Moon is and what the clouds will do.
2. Wipe the back camera lens.
3. Open Night Sky and pick a **Preset**, or choose **Deep sky**, **Star trails** or **Earth's turn**, how long, and
   the brightness.
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

The app's screen is dim red so it doesn't ruin your night vision. The two-second film it opens with (when opened
from its icon; a tap skips it) can be shown in dim red too, or turned off: **Advanced > The opening film**.

## After the photo

Under the photo:

- **Mission Replay** (2.4): the night's GIF, playing. Tap it to open it full size; **Share the GIF** sends it. It's
  in `Pictures/NightSky` as `..._replay.gif`.
- **Deep Space Zoom** (2.4): the close-ups side by side; tap one to open it, **Share the close-ups** sends them all.
  They're in `Pictures/NightSky` as `..._zoom1.jpg` and so on. When a photo has none, the card says why (nothing
  bright enough in it, or the sky wasn't recognised). Both cards come back when you open the session again from
  **Sessions**, and stay after an edit; one you've deleted from the gallery can't be opened or shared from there.
- **Deep Space Network** (2.4): which of the far travellers were in the photo, which were up in your sky (how
  high, which way) and which were below your horizon, how far each one is, and how long a radio message takes:
  "one sent when you took this photo arrives at 6:31 pm the next day". **Show all** lists the rest.
- **Cosmic Ray Hunter** (2.6): how many particles from space went through the chip, the picture of them under the
  microscope (tap it to open it full size), and what they are. **Share the particles** sends the picture. It's in
  `Pictures/NightSky` as `..._cosmic.jpg`. "No particles from space this time" happens: the longer the night, the
  more you catch.
- **Earth's Address** (2.6): the pulsar map redrawn for your night (tap to open it), which pulsars were in your photo
  or up in your sky, and how many of the map's digits have changed since 1971 (the blue ones). **Hear its
  pulsars** plays each pulsar's tick at its real speed, one after another (tap again to stop); **Share the map**
  sends it. In `Pictures/NightSky` as `..._address.jpg`.
- **Postcard from Another World** (2.6): the postcard (tap to open it) and the planet's message home. **Send the
  postcard** shares it. In `Pictures/NightSky` as `..._postcard.jpg`.
- **Hear the Stars**: **Hear it** plays the photo as music where the photo is: a line sweeps across it and each
  star rings as it's reached, in its own colour, blue stars high and red stars low, the brightest loudest; the
  planets, the Moon and the stars you named say their names (tap to stop). **Share the song** makes a 27-second
  video of it with the sound (a few seconds' work), puts it in `Pictures/NightSky` as `..._song.mp4` and offers
  to send it.
- **The Earth's turn** (in that mode): the clip plays over the photo by itself the first time, looping (tap to
  stop). **Play it** plays it again; **Share video** sends the MP4 and **Share as GIF** the animated picture, which
  loops by itself in WhatsApp, Messages, Discord or a browser. Both are in `Pictures/NightSky` as
  `..._earth.mp4` and `..._earth.webp`. The card says how far the sky turned, how long a whole turn of the Earth
  takes by these frames (the real answer: 23 h 56 min 4 s) and your latitude, all measured from the stars alone.
- **Play its video** plays the photo's few seconds of video where the photo is (tap to stop). Google Photos plays
  it too: the video is inside the photo's file, a Motion Photo. To save photos without it: **Advanced > The
  photo's video**. Making it adds about 10 seconds to developing; **Skip the video** on the developing screen
  saves the photo without it.
- **Session report** opens what the picture shows, a score for every frame, and what went wrong with the fix.
  **Share the log** at its bottom sends the session's log (every step and choice it made) as a text file.
- **Satellites** and **Names** draw the satellites' tracks and the stars' names on the photo, Names with each
  constellation's figure (when the app recognised the sky); **Save this copy** keeps that version.
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

- **Look:** stretch (1 to 10), exposure (-2 to +2), black point, colour, sky detail (0 to 200%: how much the
  Milky Way's lanes and star clouds are brought out), star sharpness (0 to 140%: how far each star's light is
  drawn back in towards its middle; 100% as developed), star glow (0 to 200%: a soft glow on the brightest stars
  in their own colours; none as developed), sky brightness, and a natural, punchy or linear look. Star trails
  photos have neither star sharpness nor star glow. **As developed** puts them back.
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
   clouds hide the stars, tries again every 10 minutes. Planned when it's dark already, a new session starts
   at a set time, the next ten-minute mark at least five minutes away.
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

## Mission Control on a Windows laptop

The viewer is on unless you untick **Viewer** on the home screen. Nothing goes through the internet: the phone
itself serves the page, so it works in a field with no phone signal.

1. Put the laptop on the **same Wi-Fi** as the phone.
   No Wi-Fi out there? On the phone's start screen, tap **No Wi-Fi here? Start a hotspot for it**. Then on the
   laptop, click the Wi-Fi icon at the bottom right of the screen (next to the clock), pick the network name the
   phone shows, and type the password it shows.
2. On the phone, tap **Start**. The screen says something like **On a laptop, open
   http://192.168.1.23:8765/mission for Mission Control and type the code 123 456**.
3. On the laptop, open **Chrome** or **Edge**, click the address bar at the top, type that address exactly as the
   phone shows it (starting with `http://`, ending with `/mission`), and press **Enter**.
4. Type the 6-digit code and click **Open Mission Control**.
5. Click **START MISSION CONTROL** (a browser only lets a page talk after a click), turn the laptop's sound up,
   and lay the phone down, screen-down, camera to the sky.

The page has six tabs along the top:

| Tab | What it does |
|---|---|
| **Mission** | The countdown, the mission log the computer voice reads out, the live picture and the big counters. |
| **Live Catches** | Satellites flying across the picture right now, on their real paths, the ones coming up next, and everything caught tonight with its snapshot. |
| **Star Spotter** | The live picture with names and constellation lines. Click a star for its card, then **name it** if you like. |
| **3D Star Flight** | Drag to look around, scroll to fly in and out, pick a star to fly to, then **Look back home**. |
| **Hear the Stars** | Plays the picture as music (the photo once it's developed, the live picture before). |
| **Patches** | The 18 mission patches, the ones earned and how. |

**Voice on**, **Sounds on** and **Night vision** (the page in dark red, kind to night eyes) are at the top right.

### For the astronaut in training

- The phone is the spacecraft, the laptop is Mission Control, and you're the flight director. Your job: keep the
  phone perfectly still (no touching once the countdown starts!) and watch for catches.
- A **satellite** is a machine going round the Earth. You can see one because the Sun is shining on it high up,
  even though it's dark where you are. Starlink satellites often fly in a line, like a train.
- The **Space Station** is the brightest satellite of all, as big as a football pitch, with astronauts inside,
  going round the Earth every 90 minutes. If the alarm goes off, they just flew through your picture.
- A **meteor** (a shooting star) is a speck of space dust, smaller than a grain of sand, burning up 100 km above
  you. Make a wish.
- A **light-year** is how far light goes in a year: about 9.5 million million km. When the star card says the
  light left a star 600 years ago, you're seeing that star as it was 600 years ago. The photo is a time machine.
- **Blue stars** are the hottest (over 10,000 degrees), **red stars** the coolest (about 3,000). That's why they
  sing high and low in Hear the Stars.
- **Voyager 1** is the farthest thing people have ever made: in November 2026 it became the first spacecraft a
  whole **light-day** from Earth, so a radio message to it takes 24 hours. It's still talking to us, 49 years after
  launch, on about as much power as a fridge light. If a green diamond in your photo says Voyager 1, it's right
  there, 26 billion km away. (In Hear the Stars, spacecraft go beep-beep-BEEP.)
- **Jupiter's moons** are the four tiny dots in a line beside it that Galileo found in 1610. They move every night:
  compare two nights' close-ups.

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
- The bright-star short frames (deep sky, on by default) take up to 24 extra RAW frames' space, about 600 MB at
  most, and a second or two of each minute. Turn them off under **Advanced > Bright stars' colour**.
- Photos are saved as HDR photos (Ultra HDR): a screen and an app that show HDR, like this phone's and Google
  Photos, show their stars brighter than white; everything else shows the photo as before. Each file is read
  back by the phone's own decoder before it's saved, and saved as an ordinary photo if that fails. To save
  ordinary photos: **Advanced > HDR photos**. A copy with names or satellite tracks drawn on it (**Save this
  copy**) is an ordinary photo.
- What goes online: satellite orbits from CelesTrak (nothing is sent) and the cloud forecast from Open-Meteo
  (your place rounded to about 10 km). Both can be turned off under Advanced. With a watch paired, the watch's
  messages go through ntfy.sh, locked with your phone's and watch's own key (the relay sees only scrambled text,
  and when they talk); since 2.6 they include the phone's place to about 10 km, for the Sky Pointer. Nothing else
  leaves the phone.
- If something goes wrong, the session log in `Documents/NightSky` says what happened, including every choice
  the app made by itself (**Share the log** on the session report sends it, and so does the screen a session that
  couldn't run ends on).
- If the app is ever stopped in the middle of something (a crash, Android closing it to free memory or save
  battery, a force stop, a freeze), the next time it opens the home screen says why, when and what it was doing,
  with **Share the details**: where in the code it went wrong, or Android's own record of how it ended. The
  session it stopped gets the same line in its log. Endings that are part of normal life (an update, Android
  tidying away the app while it wasn't doing anything) aren't shown.

## Credits

The opening film's lettering is set in [Inter](https://rsms.me/inter/) by Rasmus Andersson, used under the SIL
Open Font License 1.1 (the film holds only pictures of the letters, not the font), and the film is rendered with
[Blender](https://www.blender.org/). How it's made: [intro/README.md](intro/README.md).

The stars come from the Yale Bright Star Catalogue; their distances and spectral types (for the star cards and the 3D
flight) from the [HYG database](https://github.com/astronexus/HYG-Database) 3.7 by David Nash, which is under the
Creative Commons Attribution-ShareAlike licence (CC BY-SA 4.0), as is the star list made from it
(`app/src/main/assets/stars.csv`). The constellations' figures and where their names go come from
d3-celestial (https://github.com/ofrohn/d3-celestial), under its licence:

```
Copyright (c) 2015, Olaf Frohn
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
   disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
   following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
   products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

Deep Space Network (2.4) places the spacecraft from [Heavens-Above](https://www.heavens-above.com/SolarEscape.aspx)'s
table of spacecraft leaving the Solar System (their directions, distances and speeds on 2026-11-22), checked against
[Wikipedia's list](https://en.wikipedia.org/wiki/List_of_artificial_objects_leaving_the_Solar_System) and
[EarthSky](https://earthsky.org/space/voyager-1-1-light-day-from-earth-november-17-18-2026/) (Voyager 1 one
light-day from the Earth on 2026-11-18). Pluto's orbit is E. M. Standish's (JPL) mean elements for it, checked against
[in-the-sky.org](https://in-the-sky.org/data/object.php?id=A134340); Jupiter's moons are J. Meeus's, *Astronomical
Algorithms* (2nd ed., 1998), chapter 44. The close-ups' drizzle is A. S. Fruchter and R. N. Hook's (2002, the Hubble
Deep Field's); the background model follows [GraXpert](https://github.com/Steffenhir/GraXpert)'s idea of a smooth
surface through the sky's own samples, and the halo fix the purple fringe that [Siril](https://siril.org/)'s tools
take out. No code was taken from any of them.

Earth's Address (2.6) redraws the pulsar map of the Pioneer plaques and the Voyager Golden Records (F. Drake and C.
Sagan, 1972) from Wm. Robert Johnston's reading of it ([The pulsar map](https://johnstonsarchive.net/astro/pulsarmap.html):
its binary periods, its lines' angles and lengths), with each pulsar's period, slowdown and distance from the
[ATNF Pulsar Catalogue](https://www.atnf.csiro.au/research/pulsar/psrcat/) (R. N. Manchester et al., 2005), and
B0950+08's distance from W. F. Brisken et al. (2002). Postcard from Another World's planets are X-Ray Vision's
stars with planets, named as the IAU named them where it has, checked against the
[NASA Exoplanet Archive](https://exoplanetarchive.ipac.caltech.edu/); its stars are moved by their HYG distances.
Cosmic Ray Hunter's muon count (about one a minute through each square centimetre at sea level) is the Particle
Data Group's figure (*Review of Particle Physics*, "Cosmic rays"). The Sky Pointer turns the watch's compass to true
north with Android's own model of the Earth's magnetic field (the World Magnetic Model).

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
    StopRecorder        why the app last stopped: crashes written down, Android's record of how it ended (NightSkyApp)
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
    TrackPainter        satellite tracks, star names, constellation figures, spacecraft and Pluto drawn on the photo
    CanvasPainter       Deep Space Zoom's cards and Mission Replay's captions drawn with Canvas (2.4)
    MediaSaver          MediaStore saving (a photo with its video saved as a Motion Photo, as an HDR photo)
    HdrScreen           HDR photos on this phone's screen, and its decoder reading a saved one back
    IntroView           the opening film (res/raw/intro.mp4) over the first screen
    Mp4Writer           the photo's video: H.264 through the phone's encoder (MediaCodec) into an MP4 (with AAC sound, 2.3)
    SongArt, SongView   Hear the Stars on the phone: the photo lit up star by star as its song plays
    SongPlayer, SongVideo  the song through the speaker (AudioTrack); the song as a video with its sound
    RemoteHost          the remote viewer and Mission Control: what the web pages show and the commands they send
    WatchLink           the Pixel Watch's link on the phone (2.5): pairing, the status and live picture out, its commands in
    MoonActivity        the Moonshot (3.0): the telephoto's picture, the photo and its labels, the Transit Hunter's card;
                        the Moon map and the Earth's glow (3.1)
    MoonCamera          the Moonshot's camera: the telephoto's RAW frames, finding the Moon, metering, the focus sweep, bursts,
                        and the Earth's glow's brighter frames after the burst (3.1); Jupiter and its moons' two bursts (3.2)
    JupiterActivity     Jupiter's moons (3.2): tonight, the telephoto's picture, the photo and its names, Galileo's
                        notebook, the stories
    Transits            the Transit Hunter's list, worked out from the satellite orbits for this place
    link/               the link both apps share (plain Java): P-256 pairing by code, AES-GCM sealing, replay guard,
                        the ntfy relay (publish, stream, ntfy.sh's limits) and Bluetooth framing
    remote/             the viewer's web server, pairing page, QR codes and JSON (plain Java)
    res/raw/mission.html  Mission Control's page (one file: its sounds, voice, 3D flight and music all made in the page)
    processing/         the engine, plain Java with no Android dependencies
        UnitProcessor   live processing while capturing: hot pixels, star detection, alignment
        MissionControl  Mission Control's engine (2.3): the mission's story, the live sky, catches, patches, the song
        MissionLog      its story: events with what the voice says, and the catches' snapshots
        StreakWatch     each unit searched for streaks as it lands, and each one told apart (satellite, plane, meteor)
        LiveSats        the satellites crossing the live picture, and the ones coming
        SkyMap, PictureStars  the stars, planets and deep-sky objects in a picture, for the page's map and the song
        StarSong        Hear the Stars: a picture's stars as notes (blue high, red low), and the song's sound
        NamedStars, Patches  stars named by you; the mission patches earned over nights
        Aligner         3D rotation fit between frames using the lens model
        SkyMotion       the frames' path on the sky (Kalman smoother)
        SensorPattern   fixed-pattern noise and banding
        LocalWeights    per-region normalisation and transparency (partly cloudy nights)
        SharpStacker    Bayer drizzle stack with outlier rejection (satellites, planes)
        ChromaShift     the lens's colour fringes, measured on the stack's stars
        TrailsStacker   star trails with a clean background
        PostProcessor   final stack, gradient removal, colour, noise analysis
        ColorCalibration white balance from catalogue stars' B-V colours
        SkySolver       plate solving against the Yale Bright Star Catalogue
        SkyGuide, Figures  what's in the picture and where; the constellations' figures (from d3-celestial)
        Astro, Planets  time, Sun, Moon, planets
        Sat*, Sgp4      satellite prediction, matching and masks
        MeteorShowers   the shower calendar: radiants, their drift, rates, and which shower a meteor came from
        MeteorLog       the meteors caught: when, how bright, where, and the table of them
        Preflight       the pre-flight check
        RehearsalSky    a rehearsal's clear night, drawn into the camera's own RAW frames as this camera would see it
        RehearsalCheck  every step of a rehearsal checked against the sky it was given
        ClearSky        how bright a clear night's stars are for this camera (from its lens and sensor), for haze
        CameraFacts, DarkFrames  what the camera check found; dark frames measured
        Focus*          focus on the stars and its temperature model
        ReportCard      the session report
        NightPlan       planning the night
        Master, Recipe  kept stacks and the recipes that make every render
        MergeProcessor  nights merged into one stack
        EdgeMask, GroundMap  the painted foreground and keeping the ground still
        Denoise         the sky's noise taken out, the stars found and set aside first; the stars sharpened
        Renderer        colour and tone (asinh stretch, looks, black point, saturation)
        StarGlow        the brightest stars' glow: found in the picture, made bright and coloured from the catalogue
        PhotoVideo      the photo's video: the frames adding up again (or the trails drawing), rendered as the photo
        MotionPhoto     the video put inside the JPEG as a Motion Photo, and read back
        SkyTurn, EarthTurn  the sky's turn fitted to the frames (axis, rate, day by the stars, latitude); the clip of it
        AnimatedWebp    an animated WebP put together from the phone's own still WebPs (the clip as a GIF would be)
        StarBrackets    the short frames: the stack's clipped star cores given their light and colour from them
        HdrGain, UltraHdr  how much brighter than white the stars may show; the Ultra HDR file that says so
        Framing         crops and north up
        SkyBackground   the sky's glow taken out with the Milky Way kept (2.4): the band left out of the fit
        StarHalo        purple star halos (2.4): each colour's star size measured, the colours matched
        DeepZoom        Deep Space Zoom (2.4): the frames drizzled again at twice the resolution into close-ups
        MissionReplay, GifWriter  Mission Replay (2.4): the night as a looping GIF; the GIF89a encoder
        DeepSpace       Deep Space Network (2.4): the far travellers in the photo and its card, Jupiter's moons
        Probes          where the Voyagers, Pioneers, New Horizons, L2 and Pluto are (2.4's, on its own since 2.6)
        TimeLapse       the time lapse (2.5): every unit rendered as the photo is, in three looks, captioned, to MP4
        XRayVision      X-Ray Vision (2.5): the invisible things in the photo, their picture, the pulsars' clicks
        CosmicRays      Cosmic Ray Hunter (2.6): particles found in the kept frames, things that aren't taken out, the picture
        GoldenRecord    Earth's Address (2.6): the pulsar map redrawn for a night, its picture, the pulsars' tour
        Postcard        Postcard from Another World (2.6): the sky from another star's planet, the card, its message
        SkyPointer      the watch's Sky Pointer (2.6): what's in the sky, what the arm points at, the way to a target
        MoonGeo         the Moon from here (3.0): phase, libration, its north and lit side, the picture's up from the tilt
        MoonFinder, MoonMeter  the Moon found in a RAW frame and followed; its exposure from the phase, then measured
        MoonStack       the Moonshot's stack: the sharpest frames lined up, stacked, sharpened; crossings found in them
        MoonLook, MoonSites  the photo enlarged and finished, its labels and captions; the landing sites and their stories
        MoonFeatures    the Moon map's places (3.1): seas, craters, mountains, their IAU positions and sizes, their stories
        Earthshine      the Earth's glow (3.1): the brighter frames' night side lined up, cleaned and merged into the stack
        TransitFinder   the Transit Hunter: where the Space Station's shadow-path on the Moon crosses the ground
        JupiterGeo      Jupiter tonight (3.2): where it is, its four moons (Meeus), their happenings, Galileo's nights
        PointFinder, JupiterMeter  Jupiter found in a RAW frame and followed, its sharpness; its exposure and the moons'
        JupiterStack    both bursts lined up on Jupiter, its glow taken off, the moons found and named (or why not)
        JupiterLook     the photo, its names, tonight's line-up and Galileo's notebook page; JupiterStories the stories
        Painter         words and shapes on a picture, the same on the phone (Canvas) and in the tests (AWT)
        DngWriter       16-bit linear DNG
watch/                  Night Sky on the Pixel Watch (2.5, Wear OS, no libraries): WatchActivity (the round red screen,
                        DialView), WatchClient (Bluetooth and the relay), WatchService (following with the screen off),
                        SkyScreen (the Sky Pointer, 2.6: the rotation sensor, the arrow and the clicks), MoonFace (the
                        Moon screen, 3.0: the Moon drawn as tonight, the crossing's countdown and its buzzes)
sim/                    desktop simulator and tests that drive the engine with synthetic night skies (MissionTest:
                        a night through Mission Control; MissionDemo: the page served from a simulated night)
robo/                   Robolectric tests: the app's screens, and whole sessions with a simulated camera
intro/                  the scripts that made the opening film (Blender, Python; see intro/README.md)
tools/build_apk.sh      builds the APK without Gradle (tools/build_watch.sh the watch's)
tools/gate.sh           every simulator test and the regression nights in one go, on a commit
tools/memprobe.sh       the Java heap a full-size session needs, measured under a phone-sized heap
tools/make_stars.py     builds the star table (stars.csv) from the Yale Bright Star Catalogue
```

### Building

- **Android Studio:** open this folder and press Run. Android Studio signs with its own debug key, so uninstall
  the existing app first if it was installed from `NightSky.apk`.
- **Without Gradle (Linux):** `tools/build_apk.sh` builds a signed `build/NightSky.apk` using only `javac`,
  `aapt2`, `d8` (or `dx`), `zipalign` and `apksigner`. Set `KEYSTORE` to keep the same signing key across
  builds. `tools/build_watch.sh` builds the watch's `build/NightSkyWatch.apk` the same way (in Android Studio it's
  the `watch` module).

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
`MeteorTest`, `DeepLinesTest`, `MotionPhotoTest`, `HotPixelTest`, `DetailTest`, `FiguresTest`, `StarGlowTest` and
`SatTest` (with the SGP4 verification files) run on their own (the four before `SatTest` can take a folder for
their pictures); `SharpTest` (sharper stars), `CaTest` (colour fringes) and `UltraHdrTest` (HDR photos) take an
output folder;
`ReportTest`, `SatSessionTest`, `MeteorSessionTest`, `LibraryTest`, `MergeTest` and `G3Test` take an output folder,
for example `java -Xmx6g -cp build/sim com.brandon.nightsky.sim.G3Test out/g3 2 16`. `PhotoVideoTest` takes an
output folder, the scale and the frames (`out/pv 2 24`) and, with ffmpeg on the path, also writes its videos as MP4.
`MeteorLimit` (an output folder, then the scale) measures how faint a meteor the camera catches. `RehearsalTest`
(2.1) draws a rehearsal's sky into dark frames and runs the app's own measurements on it: the sky check, the star
solve, the focus sweep, a whole session and its check. `MissionTest` (2.3; an output folder, then optionally the
scale, frames, seed and `catches` or `quiet`) runs a night through Mission Control: a sky full of satellites, a
Starlink train, the Space Station, a plane and a meteor, each trail checked against the truth (named right, nothing
named wrong, nothing made up), then a quiet night with clouds, banding, a branch and a knock that must report
nothing; its story, its patches and its song are checked too. `MissionDemo` serves the page from a simulated night
(`MissionDemo app/src/main/res/raw 8765 3 2 37`: the pages' folder, the port, the clock's speed, the scale and the
frames) for working on the page in a browser.
The 2.4 tests each take an output folder for their pictures: `BackgroundTest` (light pollution out, the Milky Way
kept), `PurpleTest` (purple halos), `DeepZoomTest` (the close-ups sharper than the photo, and the replay),
`GifTest` (the GIF read back frame by frame) and `DeepSpaceTest` (the spacecraft, Pluto and Jupiter's moons against
published positions).
The 2.5 tests: `TimeLapseTest` (an output folder: the three looks from a simulated night, read back),
`XRayTest` (an output folder: the catalogue against published positions, the picture, the pulsars' clicks) and
`LinkTest` (the watch link: RFC 5869's HKDF vector, sealing and tampering, pairing with right and wrong codes and a
stranger's keys, replays, Bluetooth frames, and the relay through `MockNtfy`, a stand-in for ntfy.sh with its
limits, dropping out and coming back).
The 2.6 tests: `CosmicTest` (an output folder: particles put into a simulated night with stars, a satellite, a plane,
a meteor, clouds, a knock and hot pixels, four tracks right by or across a hot pixel, then found again, each once
and nothing else; `-Dcosmic.seed` and `-Dcosmic.sim` pick other particles and another night), `RecordTest` (an output folder: the pulsar map against the plaque's binary periods and the
catalogue), `PostcardTest` (an output folder: the sky from Proxima b and 51 Pegasi against hand sums, the card) and
`PointerTest` (the Sky Pointer's sky against an independent sidereal-time sum, the arm from the sensor, pointing and
finding, its speed).
The 3.0 tests: `MoonGeoTest` (the Moon's phase, libration, north and lit side against published values),
`MoonCamTest` (the Moon found in a whole frame beside a street light, the exposure settling from far off),
`MoonshotTest` (an output folder: a simulated hand-held Moon stacked, its sharpness and geometry against the truth,
lamps on the simulated Moon to check that every label lands where its place is), `TransitTest` (the Transit Hunter
against an independent calculation: the strip's middle, its edges, how long a crossing lasts) and `TransitShotTest`
(an output folder: the ISS put in front of a simulated Moon, found again in the frames where it was, nothing found
without it). `MoonSim` is the simulated Moon they share.
The 3.1 tests: `MoonMapTest` (the Moon map's table, lamps on the simulated Moon at four places, the layout's rules at
four phases, taps) and `EarthshineTest` (an output folder: a simulated crescent with a real earthshine shot 66 and 400
times brighter after the burst, the glow merged and checked against the truth: its brightness, seas and colour, no
seam, the sunlit crescent untouched, the sky black, the glare gone, no rim round the crescent; and three ways the
merge must say no).
The 3.2 tests: `JupiterGeoTest` (the moons against the 2.4 card's, Galileo's seven nights against his drawings, the
shadows, eclipses and hidings in order, rising, setting and the best time), `JupiterShotTest` (an output folder:
simulated hand-held nights with a street light, a bright star and hot pixels in the frame: Jupiter found, metered and
focused, both bursts stacked, every moon that shows found where it is and named, on five nights: all four out, one
in Jupiter's shadow, one close in, two in one dot, the tilt unknown) and `JupiterLookTest` (an output folder: the
photo's sky black and moons clear, every moon named or its absence said, the line-up, and the notebook page with
Galileo's nights as he drew them). `JupiterSim` is the simulated Jupiter they share.
`tools/gate.sh` runs them all, then six simulated nights whose logs `tools/gate.sh compare` sets side by side with
another version's.

### The app off the phone

`robo/` runs the app itself (its screens, the session's state machine, the capture loop and developing) on a
desktop JVM with Robolectric, without Gradle or Google's Maven repository. `robo/setup.sh` fetches what it needs
from Maven Central once; then `robo/run.sh Night base,stall` runs whole sessions through the real capture code
with a simulated camera that renders the real stars and can stop, stall, fail or be taken away, and
`robo/run.sh Monkey` taps every control on every screen, `robo/run.sh Intro` checks the opening film,
`robo/run.sh Night rehearsal` runs a whole rehearsal from the home screen's button, `robo/run.sh Stops` checks
the stop recorder, and `robo/run.sh Night wrist` (2.5) pairs the watch app with the phone and runs a session from
it, over the internet alone and then over Bluetooth alone, drawing each watch screen to a picture (since 2.6 it also
points the watch's Sky Pointer at a star and steers it to the Moon, through a simulated rotation sensor).
`robo/run.sh Moon` (3.0) runs the Moonshot on a simulated 5x telephoto (whole 12-megapixel RAW frames of `MoonSim`'s
Moon in a shaking hand, a lens to focus, a sensor brighter than the meter's guess): it finds, meters and focuses,
shoots from the watch's Shoot, stacks and saves, then catches a staged ISS crossing from the watch's Catch,
checking the watch's Moon screen and buzzes on the way, and draws every screen to a picture. Since 3.1 it also
checks the Moon map and shoots a crescent with the Earth's glow, from the switch to the four saved photos.
`robo/run.sh Jupiter` (3.2) does the same for Jupiter's moons: tonight's screen, finding, metering and focusing on
`JupiterSim`'s Jupiter, both bursts from the watch's Shoot, the moons named, three photos saved, the notebook kept
and read back, and the stories.
[robo/README.md](robo/README.md) lists every scenario.
