#!/usr/bin/env bash
# Runs one of the Robolectric tests on the app as it is in this tree:
#
#   robo/run.sh Night [scenarios]    whole sessions with the simulated camera, comma-separated (default: base)
#   robo/run.sh Monkey [starts]      taps every control on every screen
#   robo/run.sh Shots [screens]      draws the screens to PNG
#   robo/run.sh Launches             opens the app every way Android can
#   robo/run.sh EditFlow             edits a kept session (SESSIONS: the one folder of sessions sim/G3Test writes)
#   robo/run.sh Intro                the opening film: when it plays, how it ends, and that it never stands in the way
#
# Output goes to $OUT (default build/robo): the test's log and pictures, and TEST.out (the console, whose end is shown).
# Environment: ROBO_HOME (as for robo/setup.sh); ANDROID_JAR and ANDROID_RES_JAR (as for tools/build_apk.sh); SESSIONS,
# folders of sessions to copy in for Monkey, Shots and Launches (colon-separated: the sessions sim/LibraryTest and
# sim/G3Test write); SPEED, Night's fake clock against the real one (default 2); JTIMEOUT in seconds (default 7200);
# TAIL, lines of the console to show (default 30); EXTRA, more JVM options.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
TEST=${1:?usage: robo/run.sh Night|Monkey|Shots|Launches|EditFlow|Intro [only]}
ONLY=${2:-}
ROBO_HOME=${ROBO_HOME:-$HOME/.cache/nightsky-robo}
ANDROID_JAR=${ANDROID_JAR:-/opt/android-jars/android-36.jar}
ANDROID_RES_JAR=${ANDROID_RES_JAR:-/opt/android-jars/android-34.jar}
OUT=${OUT:-$ROOT/build/robo}
B=$ROOT/build/robo-classes
SDK_VERSION=16-robolectric-13921718
export JAVA_TOOL_OPTIONS=
[ -f "$ROBO_HOME/androidx-test-stubs.jar" ] || { echo "run robo/setup.sh first"; exit 1; }
rm -rf "$B"
mkdir -p "$B/gen" "$B/app" "$B/test" "$B/cfg/com/android/tools" "$OUT"

echo "== app"
aapt2 compile --dir app/src/main/res -o "$B/res.zip"
aapt2 link -o "$B/res.apk" -I "$ANDROID_RES_JAR" --manifest app/src/main/AndroidManifest.xml --java "$B/gen" \
    --auto-add-overlay -A app/src/main/assets "$B/res.zip"
find app/src/main/java "$B/gen" -name '*.java' > "$B/sources.txt"
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -bootclasspath "$ANDROID_JAR" -d "$B/app" @"$B/sources.txt" \
    2>&1 | grep -v "^Note:" || true
[ -f "$B/app/com/brandon/nightsky/MainActivity.class" ] || { echo "the app didn't compile"; exit 1; }
# where Robolectric finds the app's manifest, resources and assets
cat > "$B/cfg/com/android/tools/test_config.properties" <<EOF
android_merged_manifest=$ROOT/app/src/main/AndroidManifest.xml
android_merged_assets=$ROOT/app/src/main/assets
android_resource_apk=$B/res.apk
android_custom_package=com.brandon.nightsky
EOF

echo "== tests"
CP="$B/test:$B/cfg:$B/app:$(ls "$ROBO_HOME"/deps/*.jar | tr '\n' ':')$ROBO_HOME/androidx-test-stubs.jar:$ROBO_HOME/sdk/android-all-$SDK_VERSION.jar"
javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -cp "$CP" -d "$B/test" robo/src/com/brandon/nightsky/*.java \
    sim/src/com/brandon/nightsky/sim/SkySim.java 2>&1 | grep -v "^Note:" || true
[ -f "$B/test/com/brandon/nightsky/$TEST.class" ] || { echo "the tests didn't compile"; exit 1; }

echo "== $TEST $ONLY"
OPENS=()
for p in java.io java.lang java.lang.reflect java.util java.nio sun.nio.ch java.net java.text java.util.concurrent jdk.internal.access; do
    OPENS+=(--add-opens "java.base/$p=ALL-UNNAMED")
done
timeout "${JTIMEOUT:-7200}" java -Xmx5g --add-exports java.base/jdk.internal.access=ALL-UNNAMED "${OPENS[@]}" \
    -Drobolectric.offline=true -Drobolectric.dependency.dir="$ROBO_HOME/sdk" -Dshots.out="$OUT" -Dshots.sessions="${SESSIONS:-}" \
    -Dshots.only="$ONLY" -Dnight.only="${ONLY:-base}" -Dnight.speed="${SPEED:-2}" -Dnight.stars="$ROOT/app/src/main/assets/stars.csv" \
    ${EXTRA:-} -cp "$CP" org.junit.runner.JUnitCore "com.brandon.nightsky.$TEST" 2>&1 \
    | grep -v "^\s*at org.junit\|^\s*at java.base\|Picked up" > "$OUT/$TEST.out" || true
tail -"${TAIL:-30}" "$OUT/$TEST.out"
