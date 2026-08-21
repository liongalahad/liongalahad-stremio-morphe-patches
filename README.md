# Stremio Morphe Patches

Patches for Stremio Android TV 1.10.4 that add a local multi-account chooser and install the result as a separate app named **Stremio Morphe**.

## Side-by-side identity

The default build installs separately from official Stremio:

- Official app: `com.stremio.one`
- Patched app: `com.stremio.morphe`
- Launcher label: `Stremio Morphe`

The identity patch also renames Stremio's app-defined signature permission and all provider authorities. Runtime implementation class names remain unchanged so Android can load the original Stremio components.

Android gives `com.stremio.morphe` separate app data, permissions, account state, and update history. It does not import data from `com.stremio.one`.

## Patch contents

- `patches/multi-account.patch` contains the compact manifest, layout, and bytecode hooks.
- `patches/side-by-side-installation.patch` contains only the install-identity transformation.
- `poc/launcher-src` contains the original Java implementation of the chooser, account isolation boundary, and navigation integration.
- `scripts/build.ps1` checksum-gates the original APK, decodes it, applies both patches, compiles the Morphe classes, rebuilds, aligns, signs, and verifies the result.

Original APKs, decoded Stremio files, patched APKs, screenshots, and signing material are deliberately excluded from Git.

## Build

Requirements:

- Windows PowerShell 7
- Android Studio with Android SDK platform/build-tools 36
- JDK bundled with Android Studio
- Apktool 3.0.3 at `tools/apktool_3.0.3.jar`
- Android debug keystore at `%USERPROFILE%\.android\debug.keystore`

Run:

```powershell
.\scripts\build.ps1 -OriginalApk "C:\path\to\the-supported-stremio.apk"
```

Supported official APK hashes and ABIs are listed in `checksums.json`. Unknown files fail before patching.

The 2024 Google TV Streamer exposes 32-bit ARM app support, so use Stremio's `armeabi-v7a` APK for that device. A 64-bit ARM CPU does not imply an `arm64-v8a` Android userspace.

## Validation status

- Multi-account behavior was exercised on the 4K Android TV API 36 x86_64 emulator.
- The side-by-side package installed beside `com.stremio.one`, launched its Leanback activity, rendered the chooser, and produced no fatal exception in the launch window.
- The Google TV Streamer deliverable is built from the official `armeabi-v7a` Stremio 1.10.4 APK and passes APK metadata and v2/v3 signature verification.
- Real Google TV Streamer installation remains a device-side acceptance check.

See `poc/README.md` and `poc/TEST_RESULTS.md` for design and emulator test details.
