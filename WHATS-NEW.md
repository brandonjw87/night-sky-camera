# Night Sky Camera: what's new

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
