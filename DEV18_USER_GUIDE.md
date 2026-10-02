# Pocket Steward 1.4.0-dev18

This update makes Downloads filing choices explicit. It preserves the dev17 navigation repair, which the user reports has stopped the Home/Explore tab crashes on their Galaxy Fold 7 / Android API 36.

## Install

Extract the downloaded ZIP and install its APK over your current app. Dev18 uses the canonical installed-app signer and version code 58. Keep app data so saved reviews, task history and Undo remain available.

## Review Downloads

1. Scan Downloads in Explore, then choose Organize Downloads. Use Sort Uncertain to review the existing checkpoint.
2. The overview counts the reviewed source items and shows selected destinations, selected Uncertain moves, items already retained in Uncertain, explicit keeps, blocked items and items needing a decision. An intact folder is one source item; its indexed descendants are counted separately and are not separate moves.
3. Strong proposals remain selected by default. For probable proposals, confirm their destination, assign a different project/role, send them to Uncertain, or choose Keep here.
4. Use **Send remaining to Uncertain** or **Keep remaining here** to resolve all currently undecided items while preserving already selected destinations. Folder cards also offer these choices for their unselected items; expand Files for individual choices.
5. Run becomes available when there is at least one selected action and every reviewed source has an explained outcome. Unchecking a move creates a decision to resolve; it never silently authorizes another move. Selecting a previously kept item cancels its Keep choice.
6. Run queues only the selected validated actions through the existing durable task, source checks, journal and Undo pipeline. Blocked and explicitly kept items remain where they are; already unresolved checkpoint items stay in Uncertain without creating Uncertain/Uncertain.

Keep choices and explicit Uncertain assignments are saved with the review and survive restart/continued image evidence. Project assignments and deferrals preserve original source baselines, including originally unresolved sources. Files that change after the original review still require a fresh review.

## Crash notices

The previous-process exit notice now displays a readable UTC timestamp and explains that it is historical. Android exit reasons without a saved exception stack cannot identify the failed build. A fresh recorded Java/Kotlin exception still includes the app version and code frames. Copy report and Dismiss record remain available.

## Remaining sorting work

Dev18 does not complete intelligent sorting of the 16k-file Uncertain corpus. Automatic full-library evidence continuation, stronger project/topic recognition and approved group learning remain in DOWNLOADS_SORTING_COMPLETION_PLAN.md. Durable complete-inventory task manifests, post-task rescan/journal reconciliation and separate new-arrival reporting remain open under FILING-01. Selected-folder access still reports intact folders it cannot file; direct access can move reviewed folders intact. Indexed descendant counts do not prove that every inaccessible/unscanned descendant was inspected.

Automated verification and publication results are recorded in APP_COMPLETION_PROGRESS.md and the packaged build provenance. Physical-phone scanning, long cleanup, memory/thermal behavior, interruption, Undo and data-integrity acceptance remain open. The dev17 tab retest is not full app acceptance.
