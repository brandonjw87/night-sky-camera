#!/usr/bin/env bash
# How much Java heap a full-size (4080x3072) simulated session really needs, under a phone-sized heap: a full
# collection is forced every half second while SimRunner runs, and the most that's left after one is the peak.
#
#   tools/memprobe.sh <classes-dir> <out-dir> [deep|trails] [frames=19]
#
# <classes-dir> is the simulator compiled as the README says (build/sim). Prints the peak in MB; <out-dir>/gc.log has
# every sample ("Pause Full (Diagnostic Command) before->after"), to see which step it was. The last samples include
# SimRunner's own full-size picture and PNG writing (about 100 MB that the app doesn't hold). Needs a JDK (jcmd).
set -uo pipefail
cd "$(dirname "$0")/.."
CLS=${1:?usage: tools/memprobe.sh <classes-dir> <out-dir> [deep|trails] [frames]}
OUT=${2:?usage: tools/memprobe.sh <classes-dir> <out-dir> [deep|trails] [frames]}
MODE=${3:-deep}
FRAMES=${4:-19}
export JAVA_TOOL_OPTIONS=
rm -rf "$OUT"
mkdir -p "$OUT"
# a small young generation, as ART has none: a big array must fit in what's really free
java -Xmx512m -Xmn16m -XX:+UseSerialGC -Xlog:gc:file="$OUT/gc.log" -cp "$CLS" com.brandon.nightsky.sim.SimRunner \
    "$OUT/run" 1 "$FRAMES" false "$MODE" true > "$OUT/console.txt" 2>&1 &
PID=$!
while kill -0 $PID 2>/dev/null; do
    jcmd $PID GC.run >/dev/null 2>&1
    sleep 0.5
done
wait $PID
echo "SimRunner exit $?" >> "$OUT/console.txt"
PEAK=$(grep 'Pause Full (Diagnostic Command)' "$OUT/gc.log" | sed -E 's/.* [0-9]+M->([0-9]+)M.*/\1/' | sort -n | tail -1)
echo "peak live heap: ${PEAK:-?} MB of 512"
tail -1 "$OUT/console.txt"
