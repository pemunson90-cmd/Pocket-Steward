# Pocket Steward dev15: stabilization checkpoint

This update repairs the dev14 audit findings. The app-completion goal and physical-phone acceptance remain open in [APP_COMPLETION_REGISTER.md](APP_COMPLETION_REGISTER.md).

## What changed

- Direct-access moves and renames use a kernel no-replace operation, including intact folders. A competing destination cannot be overwritten by the rename. Copy/write destinations are created exclusively. A failed or interrupted copy leaves the source and any destination for journal recovery or explicit review instead of deleting a path that another app may own.
- If the filesystem cannot perform an atomic no-replace rename, regular files use the existing verified copy-and-remove path. Intact folders are refused safely rather than recursively copied. If the packaged native helper cannot load, moving is refused with an error.
- Canceling a filing rebuild clears the busy state. Selection controls are disabled while rebuilding; once canceled, the retained preview can be reviewed again.
- Checkbox dependencies are indexed once per plan. Host checks cover 1k, 4k and 16k files with 800 generated destination folders. Phone rendering and memory measurements remain open.
- Additional content-search roots arriving during indexing remain queued. Resuming indexing reconciles the whole current manifest, reusing verified cached documents; newly added or changed files before the old cursor are included.
- Filing and full indexing serialize writes for each document. A shorter filing extraction cannot replace full content produced by a concurrent index run.
- Clearing the content index clears its records without closing the live database. Older extraction work cannot write those records back. Task history, mutation journals and Undo are in a separate database.
- Duplicate prefix hashing handles short provider reads and rebuilds old prefix hashes. Full SHA verification remains required for exact duplicate groups.
- Background search updates retain newer filters and sort choices. Destination edits retain newer selections and cannot replace another plan review.

## After updating

1. Install this APK over your existing app. It uses the canonical signing certificate and version code 55.
2. Use **Sort Uncertain** for the existing checkpoint, or **Organize Inbox** for new downloads. Review project/role/release destinations and selections before approving.
3. Continue using **Continue image evidence**, explicit assignments, saved review restoration, Tasks and Undo as described in [DEV14_USER_GUIDE.md](DEV14_USER_GUIDE.md).
4. Run **Content search** after scanning your folders. Let the index finish, or pause it and resume later. Resuming checks the current file manifest from its beginning; valid cached extraction is reused.
5. If a task reports a partial-copy or collision problem, inspect its task/journal details before retrying. A preserved destination may be incomplete or belong to another app. Recovery conservatively leaves ambiguous cases for review.

## Phone acceptance still required

The host suite, SQLite checks and production native races have passed. Android instrumentation is compiled but has not been run here. These results cannot establish that your phone is free of app crashes, ANRs, native crashes, system reboots, memory pressure or thermal/battery problems.

The remaining acceptance uses representative 1k/4k/16k inventories and includes cancel/restart, background/foreground transitions, process interruption, permission revocation, low storage, collisions, intact folders, cross-volume moves, recovery and Undo. It must measure elapsed time, peak memory, responsiveness and sustained extraction load on the target phone. See [DEV15_STABILIZATION_REPORT.md](DEV15_STABILIZATION_REPORT.md).
