#!/usr/bin/env bash
# Builds a signed APK without Gradle, using only the Android platform jar and the SDK build tools
# (aapt2, javac, dx/d8, zipalign, apksigner). Used where Google's Maven repository is unreachable.
#
#   tools/build_apk.sh [out.apk]
#
# Environment:
#   ANDROID_JAR     platform jar to compile against   (default /opt/android-jars/android-36.jar)
#   ANDROID_RES_JAR platform jar aapt2 can read        (default /opt/android-jars/android-34.jar)
#   KEYSTORE        signing keystore (created if missing; keep it to install updates over old versions)
#   KS_PASS         keystore password                  (default android)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
OUT=${1:-$ROOT/build/NightSky.apk}
ANDROID_JAR=${ANDROID_JAR:-/opt/android-jars/android-36.jar}
ANDROID_RES_JAR=${ANDROID_RES_JAR:-/opt/android-jars/android-34.jar}
KEYSTORE=${KEYSTORE:-$ROOT/build/nightsky.keystore}
KS_PASS=${KS_PASS:-android}
B=$ROOT/build/apk
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$(dirname "$OUT")"

echo "== resources"
aapt2 compile --dir app/src/main/res -o "$B/res.zip"
aapt2 link -o "$B/base.apk" -I "$ANDROID_RES_JAR" --manifest app/src/main/AndroidManifest.xml \
    --java "$B/gen" --auto-add-overlay "$B/res.zip"

echo "== java"
find app/src/main/java "$B/gen" -name '*.java' > "$B/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
    -d "$B/classes" @"$B/sources.txt"

echo "== dex"
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api 26 --release --lib "$ANDROID_JAR" --output "$B" $(find "$B/classes" -name '*.class')
else
    dalvik-exchange --dex --min-sdk-version=26 --output="$B/classes.dex" "$B/classes"
fi

echo "== package"
cp "$B/base.apk" "$B/unsigned.apk"
(cd "$B" && zip -q -X unsigned.apk classes.dex)
zipalign -f -p 4 "$B/unsigned.apk" "$B/aligned.apk"
if [ ! -f "$KEYSTORE" ]; then
    echo "== creating signing key $KEYSTORE"
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias nightsky \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Night Sky Camera" >/dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias nightsky \
    --out "$OUT" "$B/aligned.apk"
apksigner verify "$OUT"
echo "built $OUT ($(du -h "$OUT" | cut -f1))"
