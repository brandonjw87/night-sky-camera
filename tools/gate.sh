#!/usr/bin/env bash
# The release gate: every simulator test and the regression nights, on committed sources (never on the working tree).
#
#   tools/gate.sh [out-dir]          the tests and the nights, on `git archive HEAD` (default out-dir: build/gate)
#   REF=<commit> tools/gate.sh DIR   on another commit (the previous release, to compare the nights with)
#   REF=WORK tools/gate.sh DIR       on the working tree as it is (for trying things out; a release is gated on a commit)
#   ONLY=tests|nights tools/gate.sh  one half only
#   tools/gate.sh compare A B        the nights of two runs side by side: only what differs is printed
#
# Out-dir afterwards: src/ (the sources tested), tests/NAME.txt (each test's output), nights/NAME.txt (the lines of each
# night's session log that say what the stack did, timings taken out), summary.txt. Exit code 0 only if every test passed.
#
# Needs java 17+, python3 and ffmpeg (the photo's video), and for SatTest the two files of the published SGP4
# verification set, fetched once from python-sgp4's repository into $GATE_CACHE (default ~/.cache/nightsky-gate).
# JOBS tests run at a time (default 3). About 20 minutes on 4 cores.
set -uo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
export JAVA_TOOL_OPTIONS=

if [ "${1:-}" = compare ]; then
    A=${2:?usage: tools/gate.sh compare A B}
    B=${3:?usage: tools/gate.sh compare A B}
    same=0
    differ=0
    for f in "$A"/nights/*.txt; do
        n=$(basename "$f")
        if [ ! -f "$B/nights/$n" ]; then
            echo "== $n: only in $A"
            differ=$((differ + 1))
        elif diff -q "$f" "$B/nights/$n" >/dev/null; then
            same=$((same + 1))
        else
            echo "== $n"
            diff "$f" "$B/nights/$n" | head -"${LINES_SHOWN:-60}"
            differ=$((differ + 1))
        fi
    done
    echo "$same nights the same, $differ differ"
    exit 0
fi

OUT=${1:-$ROOT/build/gate}
REF=${REF:-HEAD}
ONLY=${ONLY:-}
JOBS=${JOBS:-3}
CACHE=${GATE_CACHE:-$HOME/.cache/nightsky-gate}
case "$OUT" in /*) ;; *) OUT=$ROOT/$OUT ;; esac
rm -rf "$OUT"
mkdir -p "$OUT/src" "$OUT/classes" "$OUT/tests" "$OUT/nights" "$OUT/work" "$CACHE"

if [ "$REF" = WORK ]; then
    # for trying things out only: the working tree as it is (a release is gated on a commit)
    echo "== sources: the working tree (not a commit)"
    git ls-files -co --exclude-standard -z | tar -c --null -T - 2>/dev/null | tar -x -C "$OUT/src"
else
    echo "== sources: $(git rev-parse --short "$REF") $(git log -1 --format=%s "$REF")"
    git archive "$REF" | tar -x -C "$OUT/src"
fi
cd "$OUT/src"
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -d "$OUT/classes" \
    app/src/main/java/com/brandon/nightsky/processing/*.java app/src/main/java/com/brandon/nightsky/remote/*.java \
    sim/src/com/brandon/nightsky/sim/*.java 2>&1 | grep -v "^Note:" || true
[ -f "$OUT/classes/com/brandon/nightsky/sim/SimRunner.class" ] || { echo "the sources didn't compile"; exit 1; }

# the SGP4 verification set (MIT licence), as data for SatTest
SGP4=https://raw.githubusercontent.com/brandon-rhodes/python-sgp4/master/sgp4
for f in SGP4-VER.TLE tcppver.out; do
    [ -s "$CACHE/$f" ] || curl -fsSL -m 60 -o "$CACHE/$f" "$SGP4/$f" || echo "  couldn't fetch $f: SatTest will fail"
done

J="java -Xmx3g -cp $OUT/classes"
export SIM_CATALOG="$OUT/src/app/src/main/assets/stars.csv"

# name|arguments ({W} is the test's own work folder)
TESTS="
EditTest|
DenoiseTest|
HotPixelTest|{W}
DetailTest|
FiguresTest|
StarGlowTest|
SettingsTest|
ReadingTest|
FocusTest|
PlanTest|
ColorTest|$SIM_CATALOG
SatTest|$CACHE
SatPlanTest|{W}
PreflightTest|
ClearSkyTest|
CaptureReportTest|
CheckTest|
ReportTest|{W}
SatSessionTest|{W}
MeteorTest|
MeteorSessionTest|{W}
DeepLinesTest|
LibraryTest|{W}
MergeTest|{W}
G3Test|{W} 2 16
MotionPhotoTest|{W}
PhotoVideoTest|{W} 2 24
UltraHdrTest|{W}
"
# tests added by later versions run when the sources have them
for extra in CaTest SharpTest; do
    [ -f "sim/src/com/brandon/nightsky/sim/$extra.java" ] && TESTS="$TESTS$extra|{W}
"
done

run_test() {
    local name=${1%%|*} args=${1#*|} w="$OUT/work/${1%%|*}"
    [ -f "$OUT/classes/com/brandon/nightsky/sim/$name.class" ] || { echo "skip $name (not in these sources)"; return 0; }
    mkdir -p "$w"
    local t0=$SECONDS
    (cd "$OUT/src" && $J "com.brandon.nightsky.sim.$name" ${args//\{W\}/$w}) > "$OUT/tests/$name.txt" 2>&1
    local code=$?
    # every test ends with "ALL PASS" (SatTest with "PASS": the step its summary counts as failed is one the published
    # set itself marks as decayed) and exit code 0
    local verdict=FAIL
    if [ $code -eq 0 ] && tail -1 "$OUT/tests/$name.txt" | grep -q "PASS$"; then verdict=PASS; fi
    echo "$verdict $name ($((SECONDS - t0)) s)"
    echo "$verdict $name" >> "$OUT/work/verdicts.txt"
}

# name|environment
NIGHTS="
base|
partial|SIM_PARTIAL=8,20
cloud|SIM_CLOUD=10,16
sparse|SIM_STARS=0.04
branch|SIM_BRANCH=1
pattern|SIM_FPN=1.5,1.0 SIM_ROWBAND=0.8,0.5 SIM_COLFIX=0.5
"

run_night() {
    local name=${1%%|*} envs=${1#*|} w="$OUT/work/night-${1%%|*}"
    mkdir -p "$w"
    local t0=$SECONDS
    (cd "$OUT/src" && env $envs $J com.brandon.nightsky.sim.SimRunner "$w" 2 37) > "$w/console.txt" 2>&1
    local code=$?
    local log
    log=$(sed -n 's/^session log: //p' "$w/console.txt" | tail -1)
    if [ $code -ne 0 ] || [ -z "$log" ] || [ ! -f "$log" ]; then
        echo "FAIL night $name (no session log; see $w/console.txt)"
        echo "FAIL night-$name" >> "$OUT/work/verdicts.txt"
        return 0
    fi
    # what the stack did, without the clock: times of day, durations and memory figures differ from run to run
    grep -iE "streak|satellite|meteor|stacked|hot pixel|warm pixel|rejection|analysis|noise reduction|aligned|frames? (used|left out)|sky glow|colour|background|glow|fringe|sharpen" "$log" \
        | sed -E 's/^[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]+)? +//; s/[0-9]+(\.[0-9]+)? ?(ms|s|min)\b/T/g; s/[0-9]+(\.[0-9]+)? ?MB/M/g' \
        > "$OUT/nights/$name.txt"
    tail -40 "$w/console.txt" | grep -iE "error|rms|worst" | head -8 >> "$OUT/nights/$name.txt" || true
    echo "DONE night $name ($((SECONDS - t0)) s, $(wc -l < "$OUT/nights/$name.txt") lines kept)"
    echo "PASS night-$name" >> "$OUT/work/verdicts.txt"
}

export -f run_test run_night
export OUT J CACHE SIM_CATALOG
: > "$OUT/work/verdicts.txt"
if [ "$ONLY" != nights ]; then
    echo "== tests ($JOBS at a time)"
    echo "$TESTS" | grep . | xargs -P "$JOBS" -I{} bash -c 'run_test "$@"' _ {}
fi
if [ "$ONLY" != tests ]; then
    echo "== nights"
    echo "$NIGHTS" | grep . | xargs -P 2 -I{} bash -c 'run_night "$@"' _ {}
fi

sort "$OUT/work/verdicts.txt" > "$OUT/summary.txt"
bad=$(grep -vc "^PASS" "$OUT/summary.txt" || true)
total=$(wc -l < "$OUT/summary.txt")
echo "== $((total - bad)) of $total passed$([ "$bad" -gt 0 ] && echo ": $(grep -v '^PASS' "$OUT/summary.txt" | tr '\n' ';')")"
[ "$bad" -eq 0 ]
