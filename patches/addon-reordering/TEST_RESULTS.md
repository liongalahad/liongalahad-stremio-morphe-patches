# Addon reordering acceptance result

Tested on 2026-08-22 against Stremio Android TV 1.10.4.

## Build verification

| Check | Result |
| --- | --- |
| Java 8 compilation against Android API 36 | Pass |
| Smali diff compatibility check against supported 1.10.4 x86_64 decode | Pass |
| Full x86_64 compose and Apktool rebuild | Pass |
| Full arm64-v8a compose and Apktool rebuild | Pass |
| x86_64 APK Signature Scheme v2/v3 verification | Pass |
| arm64-v8a APK Signature Scheme v2/v3 verification | Pass |
| Multi-account `classes10.dex` retained | Pass |
| Addon-reordering `classes11.dex` injected | Pass |
| Final dex contains selection, click-suppression, and Back-cancellation hooks | Pass |

## Device verification

Environment: Android API 36, x86_64 Android TV emulator, 3840 x 2160 display, existing authenticated multi-account installation updated in place.

| Check | Result |
| --- | --- |
| Signed APK installs as an in-place Morphe update | Pass |
| Account chooser cold launch | Pass |
| Existing account opens MainActivity | Pass |
| Addons screen opens on the Installed one-column list | Pass |
| Long-press OK enters reorder mode | Pass |
| Boundary item shows only the available lower chevron | Pass |
| Thick 4dp Stremio-purple outline does not change card layout | Pass |
| D-pad Down performs one adjacent swap | Pass |
| Focus remains on the moved addon | Pass |
| Interior item shows centered upper and lower chevrons | Pass |
| Back exits reorder mode | Pass |
| Back restores the exact original visible order | Pass |
| Fatal exception, verification error, or missing-class error | 0 |

## Deliberate test boundary

The moved item was cancelled with Back. OK-after-move was not exercised because it would mutate the authenticated account's server-side addon collection. Persistence has build/static coverage for current-collection fetch, unique URL matching, position-preserving merge, full collection submit, error rollback, and Stremio Core refresh, but still requires an explicitly authorized account-write test before it can be marked device-accepted.

No APK contents, authentication values, screenshots, or device captures are retained in this module.
