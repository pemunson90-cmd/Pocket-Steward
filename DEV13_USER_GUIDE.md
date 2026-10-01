# Pocket Steward dev13: keeping the inventory current

This checkpoint completes the CACHE-01 implementation. The whole-app goal and physical-phone acceptance remain open in APP_COMPLETION_REGISTER.md. Upgrade with the same canonical signer; your task history and approved review workflow remain in place.

## What changed

- With **Settings → Background library refresh** enabled, direct-access inbox monitoring in the primary library now refreshes affected folder trees after a burst of changes. The existing three-second quiet period and two-minute minimum interval still limit repeated work. Events never approve moves.
- Opening or returning to the app no longer rebuilds live watches merely because unchanged storage permission was checked. Changes to the background switch, access or inbox roots still rebuild the relevant watches.
- After an approved move, copy, rename, Trash action or Undo, the app queues read-only inventory reconciliation of source/destination areas within the primary library. This also updates descendants of intact folders. These refreshes are part of keeping your approved changes visible and run even if continuous background monitoring is off.
- Private refresh hints survive restart and failed scans. A newer change cannot be cleared by an older refresh completing. Oversized hints, missing directories and incomplete library coverage fall back to a full reconciliation. Explicit/periodic full refresh remains available.
- Refreshing an affected tree updates overlapping library/folder memberships and removed items together. Unrelated records retain their existing enrichment. Separate private slice checkpoints avoid overwriting a manual scan's progress. A partial refresh does not claim that the entire library was refreshed now.
- Full direct scans exclude Android's private **Android/data** and **Android/obb** containers, which are outside the shared-storage inventory. **Android/media** remains included. Settings explains this coverage. Other listing failures still show a failed/resumable scan rather than being treated as empty folders.
- Execution, Undo and recovery share one runner lock. Per-file restore marks its task busy before changing storage, releases that state after completion and retains collision failures for retry. Interrupted partial undo becomes retryable rather than remaining stuck as active work.

## How to use it

1. Choose storage access in Settings. Live affected-folder monitoring needs direct access. Selected-folder access continues to use scheduled/manual inventory refresh.
2. Enable **Background library refresh** if you want monitoring while the app process is running and periodic reconciliation while away.
3. Allow the first full inventory to complete. A partial inventory requires full reconciliation before it can safely use affected-folder refresh.
4. Use **Organize Inbox** or **Sort Uncertain**, review project/role/release destinations, and approve the selected operations as before. Refresh hints never approve another task.
5. After a move or restore, allow the read-only refresh to finish before judging descendant counts in the library. File-task history and its journal remain the authority for what actually moved.
6. Use **Refresh now** for an explicit full reconciliation. Restoring storage permission lets you refresh again after a revoked-access failure.

## Continuing large reviews

1. Open **Sort Uncertain** to review the existing Downloads/Uncertain checkpoint, or **Organize Inbox** for incoming items. Choose storage access and inboxes as usual.
2. Enable **Image analysis** in Settings for local pixel evidence. Enable **Content inspection** too if you want local image OCR and document evidence. Both switches must be on for image text inspection.
3. Review proposed project/release/role folders. Strong matches start selected. Probable matches require your review; unresolved checkpoint items stay in place.
4. Use file/group assignment to choose a project home, role and optional release bucket. Existing destination editing also remains available. Uncheck anything you want to hold.
5. The image coverage summary reports candidates, cached results, fresh attempts, unavailable evidence and deferred work. Each review admits at most **200 fresh image inspections and 40 OCR attempts**. Valid cached evidence remains usable across the entire intake.
6. Tap **Continue image evidence** to inspect the next deferred batch. Your original intake, assignments, destination/release choices and existing source selections are preserved. Newly supported sources can gain a proposal. Continuation never moves files.
7. **Retry unavailable image evidence** explicitly retries failed image work. Unseen/deferred evidence still takes priority, so one broken image cannot keep blocking the rest. A storage warning means progress could not be saved.
8. You can leave and use **Resume review**. The private draft retains selections, assignments, coverage and initial source baselines; it is never automatically approved. A changed source requires a fresh review. Continuing an older draft without an initial intact-folder snapshot requires rebuilding that review first.
9. Approve only the selected, validated operations when their destinations look right. The normal foreground task, journal, recovery and Undo workflow still handles moves. No overwrite or unattended cleanup path was added.

Turning off image analysis stops pixel/text inspection. Turning off content inspection stops image OCR and document extraction. Visual evidence is kept separately from OCR, so an OCR-derived screenshot suggestion is not carried into visual-only evidence.

Document caches now undergo live identity verification before reuse and after fresh extraction. If a source changes or access disappears, its old text cannot be returned as current evidence; the review reports a failure. These metadata checks do not prove equality for every same-size/same-date byte change; additional fingerprint work remains in the completion register.

The progress hints use one compact, checksummed private journal, rather than a private file for each attempt. An interrupted write preserves earlier verified hints. These hints influence scheduling only and never authorize file operations.

The host benchmark covers project matching and typed-plan construction for 1k, 4k and 16k files with 200 competing homes. It excludes Android scanning, disk IO, ML execution, rendering, battery use and physical-device acceptance. Uncertain cannot safely become empty unless the remaining files have justified ownership or your explicit assignments.

## Validation limits

Host regressions and exact SQLite checks cover bounded hints, interrupted refreshes, overlapping memberships, cache preservation and runner serialization. Android instrumentation is compiled, not executed. Actual FileObserver events, device lifecycle, the 16k user corpus, battery/thermal behavior and Fold accessibility still require physical-device acceptance. Same-size/same-date byte-change fingerprinting is a separate CACHE-02 task.
