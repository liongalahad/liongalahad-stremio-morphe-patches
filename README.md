# Stremio Morphe Patches

Morphe patch source for the official Stremio Android TV application. The three current patches target only `com.stremio.one` `1.10.4`.

This repository distributes compact patch code and original Morphe source. It never distributes original, decoded, rebuilt, signed, patched, or otherwise modified Stremio APKs.

> [!WARNING]
> **These patches are work in progress.** Although the documented emulator checks have passed, real-device and server-write acceptance is incomplete. Patches may disrupt Stremio features, account state, or addon configuration. Use them only if you understand and accept this risk. Report bugs and regressions by [opening a GitHub issue](https://github.com/liongalahad/Stremio-Morphe-Patches/issues/new), but do not attach Stremio APKs, decoded files, signing material, screenshots, or device captures.

The suite adds a local multi-account chooser, remote-friendly installed-addon reordering, and a side-by-side application identity. Each patch owns its diff, Morphe source, scripts, tools, documentation, and test evidence inside its own directory. Shared root code is limited to generic discovery, composition, rebuild, signing, and verification infrastructure.

## Morphe Manager workflow

The repository builds a native Morphe patch bundle (`.mpp`). All three patches are enabled by default and can be imported into Morphe Manager without making this repository public.

Build the bundle locally against checked-out Morphe tooling:

```powershell
.\scripts\build-morphe.ps1 `
  -MorpheGradlePluginSource "C:\path\to\morphe-patches-gradle-plugin" `
  -MorphePatcherSource "C:\path\to\morphe-patcher"
```

The script prints the generated bundle path and SHA-256. Copy that `.mpp` file to the Android TV device, open Morphe Manager's local patch-bundle import, and select it. Choose the official Stremio Android TV 1.10.4 APK when Manager asks for the source application.

Manager compatibility requires the official Stremio signing certificate, an APK source file, and the ABI-specific 1.10.4 version code registered in `checksums.json`. The legacy PowerShell workflow below additionally performs an exact whole-APK SHA-256 check before decoding.

The two paths can instead be supplied through `MORPHE_GRADLE_PLUGIN_SRC` and `MORPHE_PATCHER_SRC`. To resolve the published dependencies instead, set `MORPHE_PACKAGES_TOKEN` to a GitHub token with `read:packages` scope. The build script also requires the authenticated `liongalahad` GitHub CLI account because the Morphe Gradle tooling configures GitHub Packages during startup.

## Legacy local workflow

### Requirements

- Windows PowerShell 7
- Android Studio with Android SDK platform and build-tools 36
- The JDK bundled with Android Studio
- Apktool 3.0.3 at `tools/apktool_3.0.3.jar`
- An Android debug keystore at `%USERPROFILE%\.android\debug.keystore`
- An official Stremio Android TV 1.10.4 APK whose SHA-256 is registered in `checksums.json`

Build all registered patches directly with Apktool:

```powershell
.\scripts\build.ps1 -OriginalApk "C:\path\to\the-supported-stremio.apk"
```

The build rejects unknown APKs before decoding, discovers modules through their local `patch.json` manifests, applies them in declared order, rebuilds the application, aligns and signs the result, and verifies its signature. Generated files remain under ignored `build/` and `artifacts/` paths.

See the [patch module contract](patches/README.md) for the required compartment structure.

## Stremio 1.10.4 compatibility

`checksums.json` registers the official `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` Stremio Android TV 1.10.4 APKs. A new Stremio version remains unsupported until patch application, assembly, installation, launch, and relevant device acceptance checks pass.

Current validation includes composed and signed `x86_64` and `arm64-v8a` builds. The `x86_64` build was installed and exercised on an Android TV API 36 emulator. Multi-account, navigation, isolation, and side-by-side launch checks passed within the documented scope. Addon reordering passed its local interaction and rollback checks; the final authenticated server-write boundary remains outstanding. Real Google TV Streamer acceptance also remains manual.

The 2024 Google TV Streamer exposes 32-bit ARM app support, so use Stremio's `armeabi-v7a` APK for that device. A 64-bit ARM CPU does not imply an `arm64-v8a` Android userspace.

## Available patches

<details open>
<summary>📦 Stremio Android TV&nbsp;&nbsp;•&nbsp;&nbsp;3 patches</summary>
<br>

**🎯 Supported version:**

| 1.10.4 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description |
|----------|----------------|
| [Multi-account](patches/multi-account/README.md) | Adds a D-pad-friendly chooser for up to five isolated local Stremio accounts, with names, colors, PINs, and account-local storage. |
| [Addon reordering](patches/addon-reordering/README.md) | Adds hold-OK and D-pad ordering to the installed-addon list, with provisional edits, safe rollback, and full-collection preservation. |
| [Side-by-side installation](patches/side-by-side-installation/README.md) | Installs the patched app separately as Stremio Morphe instead of replacing official Stremio. |

</details>

## Side-by-side identity

The resulting side-by-side build uses:

- Official package: `com.stremio.one`
- Morphe package: `com.stremio.morphe`
- Launcher label: `Stremio Morphe`

The identity patch also renames Stremio's app-defined signature permission and every provider authority. Android therefore gives Stremio Morphe separate app data, permissions, account state, and update history. It does not import data from `com.stremio.one`.

Detailed design, limitations, and acceptance evidence live with each patch under `patches/<patch-id>/`.
