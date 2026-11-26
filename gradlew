#!/bin/sh
# Bootstrap the official Gradle 8.13 wrapper JAR and verify its checksum.
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
URL="https://services.gradle.org/distributions/gradle-8.13-wrapper.jar"
EXPECTED="81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f"
if [ ! -f "$WRAPPER" ]; then
    TMP="$WRAPPER.tmp.$$"
    trap 'rm -f "$TMP"' EXIT HUP INT TERM
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL --retry 2 "$URL" -o "$TMP"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$URL" -O "$TMP"
    else
        echo "curl or wget is required for first-time setup." >&2
        exit 1
    fi
    if command -v sha256sum >/dev/null 2>&1; then
        ACTUAL=$(sha256sum "$TMP" | cut -d ' ' -f 1)
    elif command -v shasum >/dev/null 2>&1; then
        ACTUAL=$(shasum -a 256 "$TMP" | cut -d ' ' -f 1)
    else
        echo "sha256sum or shasum is required." >&2
        exit 1
    fi
    if [ "$ACTUAL" != "$EXPECTED" ]; then
        echo "Gradle wrapper checksum mismatch: refusing to execute." >&2
        exit 1
    fi
    mv "$TMP" "$WRAPPER"
    trap - EXIT HUP INT TERM
fi
exec java -cp "$WRAPPER" org.gradle.wrapper.GradleWrapperMain "$@"
