# The opening film

The 2.2 seconds the app plays when it opens: "Made by: Brandon J. Wallace" as metal letters floating in a night
sky, caught by a camera flash. The film the app plays is `app/src/main/res/raw/intro.mp4`; this folder is what made
it, so it can be made again or changed.

## What happens in it

| When | What |
| --- | --- |
| 0.00 to 0.56 s | The camera swings round to the name in the dark while the flash charges (a rising whine). |
| 0.56 s | The flash fires: the letters go white, dust lights up in the air, the lens flares. |
| 0.56 to 1.10 s | The flash dies away and the lit look settles: warm light on the letters, the Milky Way behind. |
| 1.10 to 1.45 s | A glint crosses the name from left to right. |
| 1.38 s | A shooting star, high on the right. |
| to 2.20 s | The night settles. |

In the app the film fades into the first screen from 1.99 s over 0.26 s, a tap skips it at any moment, and
Advanced has "Opening film": In colour, Night red, or Off, with "Play it now". Those live in
`app/src/main/java/com/brandon/nightsky/IntroView.java`; the times above are in `intro_common.py`, and the sound
(`sound.py`) follows the same ones.

The film is 1080 x 2400 at 60 frames a second (132 frames), H.264 High profile with AAC stereo sound at 48 kHz,
about 4.6 MB.

## Making it again

```
intro/make.sh
```

It makes the sky's glow for the reflections, renders the 3D scene one frame at a time, puts each frame together
(sky, lights, glare, grain), makes the sound and encodes the film into `build/intro/intro.mp4`, then copies it
over the app's. A quick look at a quarter of the size with fewer light samples, which doesn't touch the app's
film: `SCALE=25 SAMPLES=16 intro/make.sh`. The render is by far the longest part: 132 frames of 1080 x 2400
with Cycles on the processor. Frames already rendered or composed are kept, so a run that was stopped carries on
where it was.

It needs:

- Python 3 with `bpy` (Blender 4 as a Python module; the scene uses Blender 4's names for the Principled
  shader's inputs, such as "Coat Weight" and "Emission Color"), `numpy`, `scipy`, `Pillow`, `OpenEXR`,
  `uharfbuzz` and `fonttools`
- the Inter fonts (Debian's `fonts-inter` package; set `INTER_DIR` if they are somewhere else)
- `ffmpeg` with libx264 and AAC

The rendered frames are not kept in the repository (132 multilayer EXR files, many times the size of the film).
The scripts are as they were run to make the film in the app, except that the paths to the fonts and to the star
catalogue were made settable afterwards; the film has not been made again since that change.

## The files

| File | What it does |
| --- | --- |
| `make.sh` | Everything, in order. |
| `intro_common.py` | The numbers they all share: the frame's size, the timing, the camera's path, when each light is on. |
| `lettering.py` | The lettering's outlines: the three lines shaped with the font's own kerning, as curves. |
| `sky.py` | The night sky behind the name: the real bright stars (the app's own catalogue, `stars.csv`), a Milky Way from a model of its light and dust, and faint stars scattered as that light lies. |
| `make_env.py` | The whole sky's soft glow as one picture, for the letters and the water to reflect. |
| `scene.py` | The 3D scene in Blender: bevelled metal letters floating over a dark shore and a still lake, each light rendered to its own pass so the flash and the lit look can be timed afterwards. |
| `comp.py` | Puts a frame together: the sky behind, each light weighted for the moment, dust in the flash, the lens's glare, exposure, the film curve and grain. |
| `sound.py` | The sound, synthesised: the swing, the flash charging, the pop and the shutter, the glint, the shooting star. |
| `exr.py` | Reading and writing EXR files. |
| `keyframes.py`, `passes.py`, `showpass.py` | For looking at single frames and single lights while changing things. |

## Credits

- The lettering is set in [Inter](https://rsms.me/inter/) by Rasmus Andersson, used under the SIL Open Font
  License 1.1. The film holds only pictures of the letters, not the font itself.
- The 3D scene is rendered with [Blender](https://www.blender.org/)'s Cycles.
- The stars are the app's own star table, built from the Yale Bright Star Catalogue (see the main README).
