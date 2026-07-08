#!/usr/bin/env bash
# Standalone APK build — no Android SDK, no Gradle.
#
# Why: this project was bootstrapped in an environment where dl.google.com
# (Android SDK + Google Maven artifacts) is unreachable, so the classic
# Gradle/AGP build cannot run. This script assembles the APK from first
# principles instead:
#
#   aapt2 (extracted from apktool-lib, Maven Central)  -> compile + link resources
#   javac against android.jar (GitHub AOSP mirror)     -> compile Java (source 8, no lambdas)
#   dx (Jake Wharton's repackage, Maven Central)       -> classes.dex
#   tools/pack_apk.py                                  -> zip + 4-byte alignment (zipalign)
#   apksig (Maven Central) via tools/Sign.java         -> v1+v2+v3 signatures
#
# The regular Gradle files are also checked in; on a normal machine with
# Android Studio you can ignore this script entirely (remove the manifest
# `package` attribute first — see the comment in AndroidManifest.xml).
set -euo pipefail
cd "$(dirname "$0")"

TOOLS=tools/dist
BUILD=build_standalone
SRC=app/src/main
KEYSTORE="$TOOLS/btcscreen.p12"
KS_PASS=btcscreen
MAVEN=https://repo1.maven.org/maven2

mkdir -p "$TOOLS" "$BUILD"

# ---------------------------------------------------------------- toolchain
[ -f "$TOOLS/android.jar" ] || curl -fSL -o "$TOOLS/android.jar" \
    https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-34/android.jar
[ -f "$TOOLS/dx.jar" ] || curl -fSL -o "$TOOLS/dx.jar" \
    "$MAVEN/com/jakewharton/android/repackaged/dalvik-dx/9.0.0_r3/dalvik-dx-9.0.0_r3.jar"
[ -f "$TOOLS/apksig.jar" ] || curl -fSL -o "$TOOLS/apksig.jar" \
    "$MAVEN/com/android/tools/build/apksig/2.3.0/apksig-2.3.0.jar"
if [ ! -x "$TOOLS/aapt2" ]; then
    curl -fSL -o "$BUILD/apktool-lib.jar" \
        "$MAVEN/org/apktool/apktool-lib/3.0.2/apktool-lib-3.0.2.jar"
    unzip -p "$BUILD/apktool-lib.jar" prebuilt/linux/aapt2 > "$TOOLS/aapt2"
    chmod +x "$TOOLS/aapt2"
fi

# ---------------------------------------------------------------- resources
rm -rf "$BUILD/res.zip" "$BUILD/linked.apk" "$BUILD/classes" "$BUILD/classes.dex"
"$TOOLS/aapt2" compile --dir "$SRC/res" -o "$BUILD/res.zip"
"$TOOLS/aapt2" link \
    -o "$BUILD/linked.apk" \
    -I "$TOOLS/android.jar" \
    --manifest "$SRC/AndroidManifest.xml" \
    --min-sdk-version 26 --target-sdk-version 34 \
    --version-code 1 --version-name 1.0 \
    "$BUILD/res.zip"

# --------------------------------------------------------------------- code
mkdir -p "$BUILD/classes"
javac -source 8 -target 8 \
    -bootclasspath "$TOOLS/android.jar" \
    -d "$BUILD/classes" \
    $(find "$SRC/java" -name '*.java') \
    2> >(grep -v "deprecat\|source value 8\|target value 8\|bootstrap class path" >&2 || true)
java -cp "$TOOLS/dx.jar" com.android.dx.command.Main --dex --min-sdk-version=26 \
    --output="$BUILD/classes.dex" "$BUILD/classes"

# ------------------------------------------------------------- pack + align
python3 tools/pack_apk.py "$BUILD/linked.apk" "$BUILD/classes.dex" "$BUILD/unsigned.apk"

# --------------------------------------------------------------------- sign
if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 \
        -storepass "$KS_PASS" -alias btcscreen -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=BTC Screen"
fi
javac -cp "$TOOLS/apksig.jar" -d "$BUILD" tools/Sign.java
# apksig 2.3.0 reaches into JDK-internal sun.security classes for v1 signing
java --add-exports java.base/sun.security.x509=ALL-UNNAMED \
     --add-exports java.base/sun.security.pkcs=ALL-UNNAMED \
     -cp "$TOOLS/apksig.jar:$BUILD" Sign \
    "$KEYSTORE" "$KS_PASS" btcscreen "$BUILD/unsigned.apk" "$BUILD/btc-screen.apk"

echo
echo "APK ready: $BUILD/btc-screen.apk"
