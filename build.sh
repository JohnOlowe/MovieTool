#!/usr/bin/env bash
#
# Build script for MovieTool.
#
# Produces movietool.jar (a runnable fat jar) in the project root.
# Requirements: a JDK (Java 8 or newer) on the PATH or in $JAVA_HOME.
# A JRE alone is not enough because javac is needed.
#
set -e
cd "$(dirname "$0")"

# ---------------------------------------------------------------- find a JDK
find_javac() {
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
        echo "$JAVA_HOME/bin/javac"
        return 0
    fi
    if command -v javac >/dev/null 2>&1; then
        command -v javac
        return 0
    fi
    # Common Linux/macOS install locations, newest first.
    for candidate in \
        /usr/lib/jvm/*/bin/javac \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/javac \
        /opt/java/*/bin/javac; do
        if [ -x "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}

find_jar() {
    local javac="$1"
    local jdk_bin
    jdk_bin="$(dirname "$javac")"
    if [ -x "$jdk_bin/jar" ]; then
        echo "$jdk_bin/jar"
    elif command -v jar >/dev/null 2>&1; then
        command -v jar
    else
        echo ""
    fi
}

JAVAC="$(find_javac)" || {
    echo "error: no JDK found (javac)."
    echo "Install a JDK (https://adoptium.net) or set JAVA_HOME to its folder."
    exit 1
}
JAR_TOOL="$(find_jar "$JAVAC")"
if [ -z "$JAR_TOOL" ]; then
    echo "error: the 'jar' tool was not found next to javac."
    exit 1
fi

echo "Using compiler: $JAVAC"

# ------------------------------------------------------------------- compile
rm -rf build/classes
mkdir -p build/classes
find src -name '*.java' > build/sources.txt

# -Xlint:-options silences the harmless "bootstrap classpath" note that newer
# JDKs emit when asked for Java 8 output; the bytecode then runs on Java 8+.
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn \
    -d build/classes @build/sources.txt

# ---------------------------------------------------------------------- pack
echo "Manifest-Version: 1.0" > build/MANIFEST.MF
echo "Implementation-Title: MovieTool" >> build/MANIFEST.MF
echo "Implementation-Version: 2.4.0" >> build/MANIFEST.MF
echo "Main-Class: movies.Main" >> build/MANIFEST.MF
echo "" >> build/MANIFEST.MF

"$JAR_TOOL" cfm movietool.jar build/MANIFEST.MF -C build/classes .

# --------------------------------------------------------------- (optional) tests
if [ "${SKIP_TESTS:-0}" != "1" ] && [ -d test ]; then
    mkdir -p build/test-classes
    find test -name '*.java' > build/test-sources.txt
    "$JAVAC" -encoding UTF-8 -source 8 -target 8 -Xlint:-options -nowarn \
        -cp build/classes -d build/test-classes @build/test-sources.txt
    java -cp build/classes:build/test-classes movies.SelfTest
fi

echo
echo "Build finished: $(pwd)/movietool.jar"
echo "Run it with:   ./movietool.sh            (graphical interface)"
echo "               ./movietool.sh help      (command line)"
