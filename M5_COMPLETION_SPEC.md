# Milestone 5 — Completion (non-AI)

Goal: every non-AI feature in Pocket Steward is reachable, working, and honest
about what it's doing. No new capability. No AI. Leave clean seams where the
AI planner will attach later.

Definition of done: Pat opens the app, taps anything visible, and it either
does the thing or clearly explains why it can't. Nothing advertises a feature
it doesn't deliver.

Provenance on every claim below:

- `[device]` observed on Pat's phone
- `[build]` observed building this tree (commit `cfc6b2e`)
- `[code]` read directly out of the source
- `[inferred]` reasoning, not observation

---

## 1. Home screen is lying to the user (highest priority)

`[device]` Tapping **Find duplicates** on Home shows a snackbar reading
"Not built yet — arriving in a later milestone." The feature *is* built and
works, on a different screen.

`[code]` `HomeScreen.kt` defines six `QuickAction`s. Exactly one,
`recent_tasks`, has `opensHistory = true`. The other five fall through to
`snackbarHostState.showSnackbar(notYetImplementedMessage)`. So five of six
tiles are dead, while four of those five features are fully implemented on the
scan summary screen and reachable only by tapping **Scan storage**, choosing a
target, waiting for a scan, and scrolling.

The app's front door advertises six things, delivers one, and hides the
working versions two screens deep. This is worse than a missing feature: it
teaches the user the app is broken.

**Work:**

Wire each tile to the feature that already exists. Each should trigger a scan
of the appropriate scope and land on the screen that is already written:

| Tile | Destination that already exists |
| --- | --- |
| Organize Downloads | scan Downloads → `proposeSmartCleanup` → `PlanPreview` |
| Find duplicates | scan Downloads → `findDuplicates` → `DuplicateReview` |
| Find large files | scan Downloads → `findLargestFiles` → `FileListReview` |
| Find old files | scan Downloads → `findOldFiles` → `FileListReview` |
| Review uncategorized | **no destination exists — see item 2** |
| Recent tasks | already works |

If a tile needs a scan first, show the existing scan progress UI rather than
appearing to hang. See item 3.

Delete `home_not_yet_implemented` from `strings.xml` when the last tile that
uses it is wired.

**Acceptance:** every Home tile either performs its action or is not on the
screen. No tile shows a "not built" message.

---

## 2. Review uncategorized has no implementation

`[code]` The only Home tile with no existing destination. `RuleEngine`
produces a `ClassificationResult` per file, and `CleanupPlanGenerator` only
emits operations for files classified with full confidence — the rest are
silently dropped.

**Work:** a screen listing files the rule engine could not classify, reusing
`FileListReview` if it fits. Read-only. Its value is telling the user what
Smart cleanup will ignore and why, which is also the honest answer to "why
didn't it move this file."

`[inferred]` This is also the natural home for the AI later: the uncategorized
list is exactly the input an AI planner would take. Build the screen so a
"suggest destinations for these" action can be added without restructuring.

**Acceptance:** the tile opens a list of unclassified files with a count.

---

## 3. Long operations give no feedback (systemic, not just duplicates)

`[device]` Pat tapped Find duplicates and the screen did nothing, which is
indistinguishable from a dead button.

`[code]` Audited every long-running function in `ScanViewModel`. Only
`undoTask` sets a working state before starting:

| Function | Sets a working state first |
| --- | --- |
| `undoTask` | yes — `ScanUiState.Undoing` |
| `findDuplicates` | **no** |
| `findLargestFiles` | **no** |
| `findOldFiles` | **no** |
| `proposeSmartCleanup` | **no** |
| `proposeOrganizeApks` | **no** |
| `approvePlan` | **no** |

Each one assigns `_uiState.value` only *after* the work completes. The UI sits
on the previous screen the entire time.

`approvePlan` is the serious one: it executes the whole plan, moving or
trashing every file, with no on-screen change until it finishes. On a large
plan the user has no way to tell it is working, and no way to cancel.

`findDuplicates` is the most visible: it opens and hashes files, and Pat's
Downloads is 13,738 files across 11.2 GB.

**Work:** a working state for every row above, following the pattern the
scanner and undo already use. Where the underlying operation can report
progress, show counts rather than a spinner. `DuplicateDetector` and
`PlanExecutor` both iterate collections and can take an `onProgress` callback
the way `FileScanner.scan` already does.

**Acceptance:** no tap anywhere in the app leaves the screen unchanged for
more than roughly half a second. Every long operation shows progress and, for
`approvePlan`, can be cancelled without corrupting the journal (the
write-ahead design already supports interruption).

---

## 4. The scan root is not indexed, so folder creation is always rejected

`[device]` Confirmed on Pat's phone, visible in the plan preview:
`Create folder: APKs` → "Parent directory does not exist in the index."

`[code]` `FileScanner` writes a `FileRecord` for every child it lists but
never for the root it started from. `InMemoryFileIndex.exists` is a lookup in
that record set. `PlanValidator.validateCreateDirectory` begins with
`if (!index.exists(op.parent))`, and the parent of the first folder in any
plan is the scan root. So the check fails on every run.

`[device]` It has been failing on every organize run since Milestone 2,
harmlessly, because `DirectStorageGateway.moveFile` calls
`destinationParent.mkdirs()` and creates the folder anyway.

This is a validator rejecting a legitimate operation because the index has a
blind spot. The next operation that hits it may not have a `mkdirs()` quietly
covering for it.

**Work:** either index the scan root as a directory record, or have the
validator treat the scope root as implicitly existing. Prefer indexing it, so
the index means what it claims.

**Acceptance:** an organize run on a fresh folder shows zero rejected
operations, and the completion screen reports `1 folder(s) created`, which no
run has ever displayed because `foldersCreated` has always been 0.

---

## 5. Which duplicate survives is arbitrary

`[code]` `ScanViewModel` line ~240: `group.members.drop(1)` — the keeper is
`members.first()`, in whatever order the group came back from the detector.
No preference for the shortest path, the oldest file, or the copy without a
`(1)` suffix.

The files are byte-identical so no data is lost either way, but the copy it
keeps may be the one buried three folders deep rather than the obvious one.

**Work:** a documented, deterministic keeper rule. Suggested order: shallowest
path, then oldest `modifiedAt`, then shortest filename, then lexical, so the
result is stable across runs. Show the user which copy is being kept in the
`DuplicateReview` screen before they propose anything.

**Acceptance:** the review screen labels the keeper. Running the same scan
twice picks the same keeper.

---

## 6. SAF mode is half-wired and fails in different ways per feature

`[code]` In SAF mode today:

- Scanning works.
- `SafStorageGateway.openRead` is `TODO("Milestone 2")`, so **Find duplicates
  throws** `NotImplementedError`, caught and shown as a raw error state.
- `createDirectory`, `move`, `rename`, `trash` are all `TODO`, deliberately.
- `proposeSmartCleanup` and `proposeOrganizeApks` guard properly and show
  "only wired up for broad storage access right now."

So three different behaviors for the same underlying limitation: a clean
message, a raw exception, and silent success.

`[device]` Separately: once broad access is granted, onboarding auto-advances
and there is no route in the app to choose SAF instead. Revoking All Files
Access in system settings is the only way back.

**Work:** pick one and apply it everywhere. Recommended, given this is Pat's
personal device on `MANAGE_EXTERNAL_STORAGE`: fence SAF cleanly. In SAF mode,
hide or disable every action that needs mutations or `openRead`, with one
consistent honest message. Do not implement SAF mutations in this milestone.

Also add a way to change storage mode from Settings, since Settings already
displays "Storage access" state.

**Acceptance:** no action in SAF mode produces a raw `NotImplementedError`.
Storage mode is visible and changeable in Settings.

---

## 7. Bulk execution still runs from the ViewModel coroutine

`[code]` `STATUS.md` already flags this. `approvePlan` executes inside
`viewModelScope`. The write-ahead journal makes interruption safe, so this is
not a correctness bug, but Android can stop the work at any time and the user
sees nothing (see item 3).

**Work:** move execution to a foreground service for large plans, or at
minimum keep the screen awake and show progress. Correctness must continue not
to depend on the service surviving — that property is already true and must
stay true.

**Acceptance:** a several-hundred-file plan completes with the screen off, or
resumes cleanly if killed.

---

## 8. Trash has no review surface

`[code]` `trash()` moves into `PocketSteward/Trash/<mirrored path>` and
nothing in the app ever empties it. That is deliberate and must stay.

But the user currently has no way to see what is in there without a file
manager. A trash the app fills and never shows is a hole in a "fully
functional" app.

**Work:** a read-only Trash review screen: what's in there, when it was
trashed, which task did it, and a per-file restore that goes through the
existing undo machinery.

**Do not** add an "empty trash" action. That constraint is owner policy, not
an oversight.

**Acceptance:** trashed files are visible and individually restorable in-app.

---

## 9. Small cleanups

`[build]` Two compiler warnings, both benign:

```
FileScanner.kt:50:47 Unnecessary non-null assertion (!!) on a non-null receiver of type 'ScanCheckpoint'
FileScanner.kt:56:58 Unnecessary non-null assertion (!!) on a non-null receiver of type 'ScanCheckpoint'
```

`[code]` `AppDatabase` is still on
`fallbackToDestructiveMigration(dropAllTables = true)`, which wipes the index
on every schema change. Acceptable pre-1.0. If this milestone is "finished,"
decide explicitly whether real migrations start now. Schemas `2.json` and
`3.json` are exported; `.gitignore` still excludes `app/schemas/`, so they
don't reach the repo.

---

## AI seams to leave open

Do not build any of this now. Build so that none of it requires
restructuring.

**The prompt box.** `HomeScreen` has a live `OutlinedTextField` ("What should
I clean up?") wired to nothing. Either hide it this milestone or leave it
visibly disabled with an honest label. Do not leave an enabled text field that
silently discards input.

**One plan-source interface.** `CleanupPlanGenerator` already produces an
`AgentPlan` from an index plus rules. Define the interface it satisfies now,
with the rule engine as the only implementation. An AI planner later becomes a
second implementation rather than a second code path.

**The validator and preview stay mandatory.** Every plan, whatever produced
it, goes through `PlanValidator`, then the preview screen, then the executor
and journal. No AI-generated plan may skip validation or approval. Enforce
this at the interface so it cannot be bypassed by accident.

**Uncategorized files are the AI's input.** Item 2's screen is where a
"suggest destinations" action attaches.

---

## Out of scope

Natural-language parsing. On-device AI. SAF mutation support. Emptying trash.
Any new file operation type beyond create, move, rename, trash.

---

## Verification

Unit tests where logic allows: keeper selection ordering, uncategorized
filtering, index-root presence.

On-device, after the build is green:

1. Every Home tile performs its action.
2. Find duplicates shows progress and returns on a 13,000-file scope.
3. An organize run on a fresh folder reports `1 folder(s) created` and zero
   rejections.
4. Approving a large plan shows progress throughout.
5. A trashed file is visible in Trash review and restorable.
6. SAF mode produces no raw exceptions.

Item 3 is the one that has never once succeeded, so it is the clearest signal
the index fix landed.
