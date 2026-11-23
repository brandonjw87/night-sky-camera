#!/usr/bin/env bash
# Builds the signed Pixel Watch app (Wear OS) without Gradle, as tools/build_apk.sh builds the phone's: the watch's
# own code (watch/src/main) plus the link they share with the phone (link/ and the JSON in remote/).
#
#   tools/build_watch.sh [out.apk]
#
# Environment: as tools/build_apk.sh (ANDROID_JAR, ANDROID_RES_JAR, KEYSTORE, KS_PASS). The same key signs both.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
OUT=${1:-$ROOT/build/NightSkyWatch.apk}
ANDROID_JAR=${ANDROID_JAR:-/opt/android-jars/android-36.jar}
ANDROID_RES_JAR=${ANDROID_RES_JAR:-/opt/android-jars/android-34.jar}
KEYSTORE=${KEYSTORE:-$ROOT/build/nightsky.keystore}
KS_PASS=${KS_PASS:-android}
B=$ROOT/build/watch-apk
SHARED=app/src/main/java/com/brandon/nightsky
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$(dirname "$OUT")"

echo "== resources"
aapt2 compile --dir watch/src/main/res -o "$B/res.zip"
aapt2 link -o "$B/base.apk" -I "$ANDROID_RES_JAR" --manifest watch/src/main/AndroidManifest.xml \
    --java "$B/gen" --auto-add-overlay "$B/res.zip"

echo "== java"
{
    find watch/src/main/java "$B/gen" -name '*.java'
    ls $SHARED/link/*.java
    echo $SHARED/remote/Json.java
    echo $SHARED/remote/JsonTree.java
} > "$B/sources.txt"
javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
    -d "$B/classes" @"$B/sources.txt"

echo "== dex"
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api 30 --release --lib "$ANDROID_JAR" --output "$B" $(find "$B/classes" -name '*.class')
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
