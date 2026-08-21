# Dynamic multi-account emulator result

Test completed on 2026-08-21 using the `Television_4K_Morphe_POC` AVD.

## Environment

- Stremio Android TV 1.10.4
- Android API 36
- x86_64 ABI
- 3840 x 2160 TV display
- One installed package: `com.stremio.one`
- Final retained profiles: Account A and Account B

## Result

| Check | Result |
| --- | --- |
| Existing authenticated Account A/B survive in-place APK update | Pass |
| Launcher opens remote-friendly chooser | Pass |
| Active account is labelled and receives initial focus | Pass |
| Account buttons are round avatars displaying account initials | Pass |
| Thick avatar outline follows focus only; an unfocused active account keeps the normal thin outline and Active badge | Pass |
| Chooser labels and avatar initials use Stremio's Plus Jakarta Sans fonts | Pass |
| Avatar initials are optically centered | Pass |
| Legacy PRESS OK TO OPEN and SELECTED text is absent | Pass |
| D-pad card navigation and OK selection | Pass |
| Long-press menu exposes Rename, Change color, Add/Remove PIN, and Remove Account | Pass |
| Avatar color selection persists | Pass |
| Color chooser displays eight colored preview swatches | Pass |
| Avatar outline uses a lighter shade of the selected color | Pass |
| Four-digit PIN add and confirmation flow | Pass |
| PIN add, confirmation, access, and management prompts submit automatically on the fourth digit | Pass |
| Incorrect four-digit PIN clears the field and permits an immediate retry | Pass |
| Incorrect PIN blocks protected-account access | Pass |
| Correct PIN permits protected-account access | Pass |
| Remove PIN requires the current PIN and clears PIN metadata | Pass |
| PIN is stored as a salted derived hash, not plaintext | Pass |
| Add account creates a distinct namespace | Pass |
| New profile opens Stremio QR login | Pass |
| Rename dialog is usable with D-pad and TV keyboard | Pass |
| Account names are capped at 10 characters | Pass |
| Enter/Done submits Rename and PIN input and dismisses the TV keyboard | Pass |
| Rename and PIN prompts contain no Cancel/Continue/Save action row | Pass |
| One Back press dismisses an open Rename or PIN prompt and its keyboard | Pass |
| Enter is consumed by the prompt and does not activate the underlying account | Pass |
| Renamed value persists and renders on its card | Pass |
| Remove displays local-only deletion confirmation | Pass |
| Remove clears the profile's core namespace | Pass |
| Remove clears profile metadata | Pass |
| Android/default preferences use one file per account | Pass |
| Inactive removal deletes the account's Android/default preference file | Pass |
| Active removal terminates the outgoing MainActivity before changing the active slot | Pass |
| Active removal clears core, Android preferences, chooser metadata, PIN material, and slot references | Pass |
| Active removal returns to the chooser without launching a fallback account | Pass |
| PIN-protected fallback still requests its PIN after active-account removal | Pass |
| Files, cache, databases, no-backup and non-control preferences rotate into permanent per-account containers | Pass |
| Internal storage rotation uses same-filesystem renames rather than recursive cache deletion | Pass (3-48 ms storage phase) |
| Native streaming-server directory and configuration restore independently per account | Pass (byte-identical SHA-256 restoration) |
| Removed account's complete internal/external container and legacy server seed are deleted | Pass |
| Runtime services are stopped and a final process check runs before destination commit | Pass |
| Outgoing notifications and scheduled jobs are cancelled on account boundaries | Pass |
| Android TV preview programs and channels are removed on account boundaries | Pass (32 preview programs and 3 channels in the migration run) |
| SDK databases, session files, WorkManager state and non-account preference stores rotate with their account | Pass |
| Legacy live auxiliary storage is adopted by the active account during the v2 container migration | Pass |
| Account destination is committed only after stale processes terminate and the storage transaction succeeds | Pass |
| Failed storage moves have a reverse-order transaction rollback path | Pass (static and build verification) |
| Direct ADB/broadcast slot mutation is rejected | Pass |
| Core-error recovery targets only the active core namespace and active Android preferences | Pass (static and build verification) |
| Android backup/restore is disabled | Pass |
| Five local profiles can coexist | Pass |
| Add is disabled at the five-profile limit | Pass |
| Left navigation renders the round active-account avatar above Search | Pass |
| Collapsed account avatar is 32dp and centered on the native menu icon axis | Pass |
| Expanded account label matches the native 13sp Plus Jakarta Sans weight and text column | Pass |
| Account and native menu focus targets have identical 145 x 48dp bounds and 20dp left inset | Pass |
| Account and native menu selectors have identical 45dp painted height inside their focus targets | Pass |
| Left-navigation label uses the active account name instead of “Accounts” | Pass |
| Active account name remains visible while any native side-menu item has focus | Pass |
| Account row matches the native expanded-menu background, selection, and typography | Pass |
| Account-through-Settings sidebar stack is vertically centered (1079px midpoint on a 2160px display) | Pass |
| Stremio logo remains fixed with additional separation above the account row | Pass |
| D-pad Up moves from Search to Accounts | Pass |
| D-pad Down moves from Accounts back to Search | Pass |
| OK on the active-account avatar opens the chooser | Pass |
| Selecting the active account returns to MainActivity without a reload (489 ms in the final run) | Pass |
| Active-account return retains the same MainActivity process PID | Pass |
| Exit Stremio returns to Android TV Home | Pass |
| Exit leaves no Stremio process running | Pass |
| Account A login profile survives repeated A/B switching | Pass |
| Account A library survives repeated A/B switching | Pass |
| Account B login profile survives repeated A/B switching | Pass |
| Account B authentication profile is byte-identical after B/empty-account/B container rotation | Pass (SHA-256 comparison) |
| Account B native server settings are byte-identical after B/empty-account/B container rotation | Pass (SHA-256 comparison) |
| New empty account receives no authenticated profile from Account B | Pass |
| Active temporary-account removal restores the fallback container before deletion | Pass |
| First migrated A-to-B storage boundary | Pass (99 ms; Home resumed in 2.27 s) |
| Repeated account storage boundaries | Pass (45-244 ms; Home resumed in 2.11-2.53 s) |
| Temporary capacity-test profiles removed successfully | Pass |
| Final profile count is two | Pass |
| Fatal exceptions during the final workflow | 0 |

No profile contents, credentials, tokens, email addresses, or account values are included in this report. Integrity comparisons used SHA-256 digests calculated without printing the protected values.

## Conclusion

One Stremio Android TV installation now retains multiple complete authenticated accounts and provides an on-device lifecycle for selecting, adding, renaming, recoloring, PIN-protecting, and removing them. Core state, Android/default preferences, caches, databases, WorkManager/SDK files and native streaming-server storage are retained separately per account. Switching terminates the outgoing runtime, transactionally rotates the destination container into place, then launches the destination. Removing either an inactive or active account deletes all of its logical app-private stores, while selecting the already-active account still returns immediately without a process restart.
