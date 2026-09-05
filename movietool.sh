#!/usr/bin/env bash
#
# MovieTool launcher (Linux / macOS / any Unix).
#
#   ./movietool.sh              start the graphical interface
#   ./movietool.sh <command>    run a command line operation
#   ./movietool.sh help         list the commands
#
# The jar is built automatically on first use when a JDK is available.
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"
JAR="$DIR/movietool.jar"

# ------------------------------------------------------------------ find java
find_java() {
    if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        echo "$JAVA_HOME/bin/java"
        return 0
    fi
    if command -v java >/dev/null 2>&1; then
        command -v java
        return 0
    fi
    for candidate in \
        /usr/lib/jvm/*/bin/java \
        /Library/Java/JavaVirtualMachines/*/Contents/Home/bin/java \
        /opt/java/*/bin/java; do
        if [ -x "$candidate" ]; then
            echo "$candidate"
            return 0
        fi
    done
    return 1
}

JAVA="$(find_java)" || {
    echo "error: no Java runtime found (java)."
    echo "Install Java 8 or newer (https://adoptium.net) or set JAVA_HOME."
    exit 1
}

# ------------------------------------------------------------------ build jar if needed
if [ ! -f "$JAR" ]; then
    echo "movietool.jar not found, trying to build it..."
    if [ -x "$DIR/build.sh" ]; then
        SKIP_TESTS=1 "$DIR/build.sh"
    else
        echo "error: $JAR is missing and no build.sh was found to create it."
        exit 1
    fi
fi

exec "$JAVA" -Dfile.encoding=UTF-8 -jar "$JAR" "$@"
