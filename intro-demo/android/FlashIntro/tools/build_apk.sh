#!/usr/bin/env bash
# Builds a signed APK without Gradle, using only a platform jar and the SDK build tools
# (aapt2, javac, dx/d8, zipalign, apksigner). Used where Google's Maven repository is unreachable.
# Android Studio users don't need this: open the folder and press Run.
#
#   tools/build_apk.sh [out.apk]
#
# Environment:
#   ANDROID_JAR     platform jar to compile against (API 31 or newer)
#   ANDROID_RES_JAR platform jar aapt2 can read     (any API 23 or newer)
#   KEYSTORE        signing keystore (created if missing; keep it to install updates over old versions)
#   KS_PASS         keystore password                (default android)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
OUT=${1:-$ROOT/build/FlashIntro.apk}
ANDROID_JAR=${ANDROID_JAR:-/opt/android-jars/android-36.jar}
ANDROID_RES_JAR=${ANDROID_RES_JAR:-/usr/lib/android-sdk/platforms/android-23/android.jar}
KEYSTORE=${KEYSTORE:-$ROOT/build/flashintro.keystore}
KS_PASS=${KS_PASS:-android}
PACKAGE=com.brandon.flashintro
MIN_SDK=31
TARGET_SDK=36
VERSION_CODE=2
VERSION_NAME=1.1
B=$ROOT/build/apk
rm -rf "$B"
mkdir -p "$B/gen" "$B/classes" "$(dirname "$OUT")"

echo "== resources"
# Gradle takes the package and SDK levels from app/build.gradle; give them to aapt2 the same way.
# An API 23 resource jar doesn't know the "density" config change (API 24), so it is left out here.
sed -e "s|<manifest |<manifest package=\"$PACKAGE\" |" -e "s/|density//" \
    app/src/main/AndroidManifest.xml > "$B/AndroidManifest.xml"
aapt2 compile --dir app/src/main/res -o "$B/res.zip"
aapt2 link -o "$B/base.apk" -I "$ANDROID_RES_JAR" --manifest "$B/AndroidManifest.xml" \
    --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK \
    --version-code $VERSION_CODE --version-name $VERSION_NAME \
    -A app/src/main/assets --java "$B/gen" --auto-add-overlay "$B/res.zip"

echo "== java"
find app/src/main/java "$B/gen" -name '*.java' > "$B/sources.txt"
if unzip -l "$ANDROID_JAR" java/lang/Object.class >/dev/null 2>&1; then
    # an SDK android.jar carries its own java.* classes
    javac -source 8 -target 8 -Xlint:-options -encoding UTF-8 -bootclasspath "$ANDROID_JAR" \
        -d "$B/classes" @"$B/sources.txt"
else
    # a framework jar without java.* (such as Robolectric's android-all): take java.* from the JDK, Java 8 APIs only
    javac --release 8 -Xlint:-options -encoding UTF-8 -classpath "$ANDROID_JAR" \
        -d "$B/classes" @"$B/sources.txt"
fi

echo "== dex"
if command -v d8 >/dev/null 2>&1; then
    d8 --min-api $MIN_SDK --release --lib "$ANDROID_JAR" --output "$B" $(find "$B/classes" -name '*.class')
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
    keytool -genkeypair -keystore "$KEYSTORE" -storepass "$KS_PASS" -keypass "$KS_PASS" -alias flashintro \
        -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Flash Intro" >/dev/null
fi
apksigner sign --ks "$KEYSTORE" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" --ks-key-alias flashintro \
    --min-sdk-version $MIN_SDK --out "$OUT" "$B/aligned.apk"
apksigner verify --min-sdk-version $MIN_SDK "$OUT"
echo "built $OUT ($(du -h "$OUT" | cut -f1))"
