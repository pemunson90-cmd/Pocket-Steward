# Dev17 tab-crash repair

User report: Galaxy Fold 7, current Android, current Pocket Steward; tapping Home or Explore closes the app before starting a scan, with Android reporting that the app has a bug.

## Reproduced defect

An executable Robolectric Compose test against the dev16 navigation code failed during Explore -> Files tab switching with:

`IllegalArgumentException: No destination with route scan_flow is on the NavController's back stack. The current destination is ... route=files`

The stack points to `ScanFlowNav.kt`'s `scanViewModel()` lookup. That function observed the controller's global current entry and re-looked-up the parent graph whenever the active tab changed. During an exit animation, the outgoing scan screen still composed after the graph was saved/popped, so the lookup threw. The repair keys the retained graph owner to the actual composing destination entry. Navigation-event collection now runs only while that entry is RESUMED.

This is a confirmed navigation defect. It is not a retrieved stack from the user's Fold 7 and does not by itself prove that both reported cold-start paths had this exact exception.

## Large-history repair

Home/Tasks/other status observers previously selected full task rows, including multi-megabyte `planJson` payloads. Home and visible task cards also decoded entire operation lists during composition. Android cursor windows are bounded, and full-history materialization needlessly increases heap use. The repair returns bounded presentation fields and SQL-derived display hints, never full operation payloads. Execution still reads and validates the exact stored approved plan.

Full task retrieval now reads metadata and 64 KiB UTF-8 byte chunks within one transaction. This covers explicit task lookup, running/recovery task reads and queued-plan identity reads. Zero-length/exact-multiple payloads and multibyte characters split across chunks retain their original bytes. Review restoration compares historical plans individually off the main thread. No Room schema change or task rewrite is required.

The host large-history case did not reproduce a Home crash. These changes remove a concrete large-row/heap risk; they are not evidence of an observed phone OOM or cursor exception.

## Local crash evidence

Install an uncaught-exception recorder before app-container startup. It records only bounded exception types/code frames, version/device details and a route pattern in private app storage, then always delegates to Android's original fatal handler. It never continues a crashed process. On restart, the root UI can display/copy/dismiss the record without opening Settings. Historical Android exit reasons supplement older builds and native/ANR/low-memory terminations without claiming to recover an absent stack.

## Verification

All 682 unit/JVM tests pass with no failures or errors on the final app source. Targeted regressions pass: repeated tab switching on API 35/36; real launcher/onboarding-to-Files/Home/Explore on expanded API 36; large completed history; exact 16k-operation retrieval/resume metadata; Unicode/empty/exact chunk boundaries; private exception formatting and fatal-handler delegation. Build/signing/publication results are recorded in the release provenance and APP_COMPLETION_PROGRESS.md. A narrowly documented lint suppression covers its transitive onResume call-chain false positive: repeatOnLifecycle is created by the destination's LaunchedEffect, not by an Activity lifecycleScope callback.

No physical Fold 7, emulator, device logcat or user crash stack is attached to this environment. Android instrumentation compilation is not execution. Do not close physical crash/ANR/reboot/data-integrity acceptance until the installed update is exercised on the phone.

FILING-01 implementation remains pending because this actual crash report takes priority over new sorting behavior.
