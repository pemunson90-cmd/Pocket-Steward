# Pocket Steward 1.4.0-dev17

This update repairs a reproduced tab-navigation crash and reduces large-task history overhead. It does not complete the Downloads sorting plan.

## Install

Install the dev17 APK over your current Pocket Steward installation. It uses the same canonical signer and a higher version code (57). Do not uninstall or clear app data: saved reviews, task history and Undo information belong to the existing installation.

## What changed

- Explore now retains the scan graph owner captured for its own destination. Leaving or restoring a tab no longer asks the new tab to supply a scan graph that has already left the active navigation stack. Outgoing/saved scan screens also stop consuming navigation events.
- Home, Tasks and other task-status screens read small display projections. They do not pull every persisted approved plan into memory or decode operations during composition. Full plans are retrieved in bounded UTF-8 byte chunks inside a database transaction for execution/recovery/manifest work; stored plans are unchanged. Saved-review restoration compares historical plans one at a time off the UI thread.
- If a Java/Kotlin crash occurs, reopening the app shows a local **Previous app failure** dialog. **Copy report** copies exception types, code frames, app/device version and the route pattern. Exception messages, file contents and user-entered requests are omitted. Nothing is sent automatically. **Dismiss record** removes the local record. Android may also provide an exit reason for an older build, native crash, ANR or low-memory termination; that reason alone is not an exception stack.

## Check the reported problem

1. Open the installed dev17 app on the Fold 7.
2. Tap Home, then Files, then Explore, then Home several times before starting a scan. Check both folded and unfolded layouts.
3. If it closes, reopen it and use **Copy report** in the failure dialog; include that text when reporting the failure.

Host tests exercise actual launcher/tab composition on Android API 35 and 36, including an expanded API 36 layout, repeated saved-tab restoration, a five-megabyte history record, 16k-operation plan reads and Unicode chunk boundaries. These are Robolectric JVM tests, not execution on a Samsung phone. Physical Samsung lifecycle/memory/thermal and actual-corpus acceptance remain open.

## Sorting status

Organize Downloads and Sort Uncertain retain the existing dev16 behavior. Strong proposals are selected, probable proposals need review, and unresolved checkpoint items stay unresolved until evidence or an explicit assignment supports a destination. The completion path remains in DOWNLOADS_SORTING_COMPLETION_PLAN.md and APP_COMPLETION_REGISTER.md.
