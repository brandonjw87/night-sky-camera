#!/usr/bin/env bash
# Copies the shared shaders and the generated lettering and sound into the Android app's assets.
# Run it after changing anything in shaders/ or assets/.
set -euo pipefail
cd "$(dirname "$0")/.."
A=android/FlashIntro/app/src/main/assets/intro
mkdir -p "$A/shaders"
cp shaders/*.glsl shaders/*.vert shaders/*.frag "$A/shaders/"
cp assets/name_portrait.r16f assets/name_landscape.r16f assets/labels.r8 assets/sdf_manifest.json \
   assets/intro_sound.pcm "$A/"
echo "copied shaders and data to $A"
