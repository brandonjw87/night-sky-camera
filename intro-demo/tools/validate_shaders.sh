#!/usr/bin/env bash
# Checks that every shader compiles as GLSL ES 3.00, with the same header and common.glsl that the app
# and the page put in front of it. Needs glslangValidator.
set -euo pipefail
cd "$(dirname "$0")/../shaders"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
bad=0
for f in *.vert *.frag; do
    { printf '#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\n'
      cat common.glsl; printf '\n'; cat "$f"; } > "$tmp/$f"
    if ! out=$(glslangValidator -S "${f##*.}" "$tmp/$f" 2>&1); then
        echo "== $f"; echo "$out" | head -20; bad=1
    fi
done
[ $bad -eq 0 ] && echo "all shaders compile"
exit $bad
