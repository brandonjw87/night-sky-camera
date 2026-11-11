#!/usr/bin/env bash
# Makes the opening film from nothing: the sky, the 3D render, the composite, the sound, and the MP4 the app plays.
#
#   intro/make.sh            everything into $INTRO_WORK (default build/intro), then app/src/main/res/raw/intro.mp4
#   SCALE=25 SAMPLES=16 intro/make.sh    a quick look: a quarter of the size, fewer light samples
#   KEEP=1 intro/make.sh     leaves the app's film as it is (the new one stays in $INTRO_WORK/intro.mp4)
#
# Needs python3 with bpy (Blender 4 as a Python module), numpy, scipy, Pillow, OpenEXR, uharfbuzz and fonttools; the
# Inter fonts (Debian's fonts-inter; INTER_DIR if they're somewhere else); ffmpeg with libx264 and aac. The render is
# the long part (132 frames of 1080x2400 with Cycles on the processor). Frames already rendered or composed are kept,
# so a run that was stopped carries on where it was; REDO=1 composes them again.
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd)
WORK=${INTRO_WORK:-$HERE/../build/intro}
mkdir -p "$WORK"
WORK=$(cd "$WORK" && pwd)
export INTRO_WORK=$WORK

echo "== the sky's glow for the reflections"
[ -s "$WORK/env.exr" ] || python3 make_env.py "$WORK/env.exr"
echo "== the 3D scene, one frame at a time"
python3 scene.py --frames 0 131 --scale "${SCALE:-100}" --samples "${SAMPLES:-64}" --threads "$(nproc)" \
    --out "$WORK/exr" --env "$WORK/env.exr"
echo "== the frames put together: sky, lights, glare, grain"
python3 comp.py "$WORK/exr" "$WORK/png"
echo "== the sound"
python3 sound.py "$WORK/intro_sound.wav"
echo "== the film"
ffmpeg -y -loglevel error -framerate 60 -i "$WORK/png/f%04d.png" -i "$WORK/intro_sound.wav" \
    -vf "scale=in_range=full:out_range=tv:out_color_matrix=bt709:flags=accurate_rnd+full_chroma_int,format=yuv420p" \
    -c:v libx264 -profile:v high -preset veryslow -crf 20 \
    -x264-params "aq-mode=3:aq-strength=1.0:keyint=132:min-keyint=132:bframes=3:ref=4" \
    -color_primaries bt709 -color_trc bt709 -colorspace bt709 -color_range tv \
    -c:a aac -b:a 160k -ar 48000 -ac 2 -movflags +faststart -t 2.2 "$WORK/intro.mp4"
ls -l "$WORK/intro.mp4"
if [ -z "${KEEP:-}" ] && [ "${SCALE:-100}" = 100 ]; then
    cp "$WORK/intro.mp4" "$HERE/../app/src/main/res/raw/intro.mp4"
    echo "copied to app/src/main/res/raw/intro.mp4"
fi
