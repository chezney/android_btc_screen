# android_btc_screen

A tiny Android app that floats the **live BTC/USDT price from Binance** over
everything on your screen, in a translucent draggable bubble — like a chat
head, but for the Bitcoin price.

- Polls `https://api.binance.com/api/v3/ticker/price?symbol=BTCUSDT` every 2 s
- Price flashes green on a tick up, red on a tick down
- Drag the bubble anywhere; while dragging, an **✕** target appears at the
  bottom of the screen — drop the bubble on it to close the app completely
- Runs as a foreground service, so it stays alive while you use other apps
- No dependencies, no analytics, ~11 KB APK

## Install

1. Copy `btc-screen.apk` to your phone and open it (allow "install unknown
   apps" if prompted).
2. Launch **BTC Screen**. Grant the *Display over other apps* permission when
   the settings screen opens, then go back — the bubble appears.
3. To close it: drag the bubble down onto the ✕.

## Building

### Option A: `./build_apk.sh` (no Android SDK required)

This project was bootstrapped in an environment where `dl.google.com` (the
Android SDK and Google Maven) is unreachable, so it ships a standalone build
pipeline that only needs a JDK, python3, curl, and unzip:

| Step | Tool | Source |
|------|------|--------|
| Compile/link resources | `aapt2` | extracted from `apktool-lib` (Maven Central) |
| Compile Java | `javac -source 8` vs `android.jar` | AOSP mirror on GitHub |
| Dex | legacy `dx` | Jake Wharton's repackage (Maven Central) |
| zipalign | `tools/pack_apk.py` | this repo (pure python) |
| Sign (v2) | `apksig` + `tools/Sign.java` | Maven Central |

Because `dx` cannot dex `invokedynamic`, the Java sources deliberately avoid
lambdas.

The signing keystore is generated on first build and is **not** committed.

### Option B: Android Studio / Gradle

Standard Gradle files are included (`compileSdk 34`, no dependencies). On a
normal network, open the project in Android Studio — but first remove the
`package` attribute from `AndroidManifest.xml` (AGP 8+ forbids it; the
standalone aapt2 build requires it).

## How the code is laid out

```
app/src/main/java/com/chezney/btcscreen/
  MainActivity.java           invisible launcher: gets overlay permission,
                              starts the service, finishes
  FloatingWidgetService.java  foreground service: draws the bubble +
                              dismiss target, drags, polls Binance
build_apk.sh                  standalone build pipeline
tools/pack_apk.py             python zipalign replacement
tools/Sign.java               apksigner replacement built on apksig
```
