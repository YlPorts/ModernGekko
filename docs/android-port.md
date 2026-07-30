# ModernGekko Android port

This branch contains the work required to run ModernGekko recompilations on Android.

## Current milestone: Android baseline

The first milestone builds the Android frontend already provided by the
`vendor/dolphin` RecompCore submodule. This verifies the Java, Gradle, Android
SDK, NDK, CMake, Vulkan/OpenGL, audio, touch input and controller toolchain
before the ModernGekko runtime is connected.

The baseline APK is **not yet the Kirby port**. It is a build-system checkpoint.

## Planned integration

1. Build the RecompCore Android frontend from this repository in GitHub Actions.
2. Add a JNI bridge owned by ModernGekko.
3. Add an Android host path that uses `ANativeWindow` instead of the desktop
   Win32/X11/Wayland platform classes.
4. Load an ARM64 static-recomp module through an Android-safe module source.
5. Add a game importer that accepts a user-provided legal copy and extracts the
   required `sys/main.dol`, REL and asset tree into app storage.
6. Add a Kirby-branded launcher, touch controls and gamepad support.
7. Produce installable debug and release APK artifacts.

## Architectures

The initial target is `arm64-v8a`. `x86_64` may remain available for emulator
and CI testing. 32-bit ARM is not part of the first milestone.

## Game data

No Nintendo game data, ISO, WAD, DOL, REL, keys, textures or copyrighted assets
will be stored in this repository or bundled in the APK. The user must provide
their own legal game dump.

## Branch

Development takes place on `android-port` until the APK can initialize the
ModernGekko runtime without breaking the desktop builds.
