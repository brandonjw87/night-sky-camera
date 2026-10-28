#!/usr/bin/env bash
# One-time setup for the Robolectric tests in robo/, without Gradle or Google's Maven repository:
#
#   robo/setup.sh
#
# Everything goes in $ROBO_HOME (default ~/.cache/nightsky-robo):
#   deps/                     Robolectric 4.17 and JUnit, from Maven Central (pom.xml)
#   sdk/                      Robolectric's Android 16 jars: the plain one (on the classpath) and the instrumented one
#                             it runs SDK 36 on
#   androidx-test-stubs.jar   stand-ins for the androidx.test classes Robolectric refers to (they're Google-Maven only)
#
# Needs java 17 or newer, curl, and mvn (fetched from Maven Central if missing). Behind an HTTPS proxy ($HTTPS_PROXY),
# Maven is pointed at it.
set -euo pipefail
cd "$(dirname "$0")"
HERE=$(pwd)
ROBO_HOME=${ROBO_HOME:-$HOME/.cache/nightsky-robo}
CENTRAL=https://repo1.maven.org/maven2
# Google's copy of Maven Central: repo1 has refused the 213 MB jar with 429 (too many requests)
MIRROR=https://maven-central.storage-download.googleapis.com/maven2
SDK_VERSION=16-robolectric-13921718
mkdir -p "$ROBO_HOME/deps" "$ROBO_HOME/sdk"
# the JVM's "Picked up JAVA_TOOL_OPTIONS" notice left out (the options themselves can hold the proxy's settings)
quiet() {
    "$@" 2>&1 | { grep -v "^Picked up JAVA_TOOL_OPTIONS" || true; }
}

echo "== maven"
MVN=$(command -v mvn || true)
if [ -z "$MVN" ]; then
    if [ ! -x "$ROBO_HOME/maven/bin/mvn" ]; then
        curl -fsSL "$CENTRAL/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.tar.gz" | tar -xz -C "$ROBO_HOME"
        rm -rf "$ROBO_HOME/maven"
        mv "$ROBO_HOME/apache-maven-3.9.9" "$ROBO_HOME/maven"
    fi
    MVN=$ROBO_HOME/maven/bin/mvn
fi
SETTINGS=()
PROXY=${HTTPS_PROXY:-${https_proxy:-}}
if [ -n "$PROXY" ]; then
    hostport=${PROXY#*://}
    hostport=${hostport%%/*}
    hostport=${hostport##*@}
    host=${hostport%:*}
    port=${hostport##*:}
    cat > "$ROBO_HOME/settings.xml" <<EOF
<settings>
  <proxies>
    <proxy><id>https</id><active>true</active><protocol>https</protocol><host>$host</host><port>$port</port><nonProxyHosts>localhost</nonProxyHosts></proxy>
    <proxy><id>http</id><active>true</active><protocol>http</protocol><host>$host</host><port>$port</port><nonProxyHosts>localhost</nonProxyHosts></proxy>
  </proxies>
</settings>
EOF
    SETTINGS=(-s "$ROBO_HOME/settings.xml")
fi

echo "== robolectric and junit"
quiet "$MVN" -q "${SETTINGS[@]}" -f "$HERE/pom.xml" dependency:copy-dependencies -DoutputDirectory="$ROBO_HOME/deps"
ls "$ROBO_HOME/deps" | wc -l | xargs echo "  jars:"

echo "== android jars"
fetch() {   # fetch group-path artifact version: into sdk/, checked against Maven Central's SHA-1
    local path=$1/$2/$3/$2-$3.jar out=$ROBO_HOME/sdk/$2-$3.jar
    if [ -f "$out" ]; then
        echo "  have $(basename "$out")"
        return
    fi
    local sha
    sha=$(curl -fsSL "$CENTRAL/$path.sha1" | cut -c1-40)
    curl -fsSL -o "$out.part" "$CENTRAL/$path" || curl -fsSL -o "$out.part" "$MIRROR/$path"
    if [ "$(sha1sum "$out.part" | cut -c1-40)" != "$sha" ]; then
        echo "  $(basename "$out"): checksum doesn't match Maven Central's" >&2
        rm -f "$out.part"
        exit 1
    fi
    mv "$out.part" "$out"
    echo "  got $(basename "$out") ($(du -h "$out" | cut -f1))"
}
fetch org/robolectric android-all "$SDK_VERSION"
fetch org/robolectric android-all-instrumented "$SDK_VERSION-i7"

echo "== androidx.test stand-ins"
rm -rf "$ROBO_HOME/stubs" && mkdir -p "$ROBO_HOME/stubs"
quiet javac -source 8 -target 8 -Xlint:-options -nowarn -encoding UTF-8 -cp "$(ls "$ROBO_HOME"/deps/*.jar | tr '\n' ':')$ROBO_HOME/sdk/android-all-$SDK_VERSION.jar" \
    -d "$ROBO_HOME/stubs" $(find "$HERE/stubs" -name '*.java')
(cd "$ROBO_HOME/stubs" && quiet jar cf "$ROBO_HOME/androidx-test-stubs.jar" .)
rm -rf "$ROBO_HOME/stubs"
echo "set up in $ROBO_HOME"
