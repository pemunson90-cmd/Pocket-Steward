# Milestone 6 — Transparency pass (non-AI)

Goal: the app can account for what it did. Every completed task should be
answerable after the fact without a database inspector.

Triggered by a real run on 2026-09-17: Smart cleanup moved 4,829 files and
created 5 folders with 1 failure, and a duplicate pass trashed 3,316 files.
Both succeeded. Neither could be audited afterward from inside the app.

Provenance:

- `[device]` observed on Pat's phone
- `[code]` read out of the source at commit `17827f8`
- `[inferred]` reasoning, not observation

---

## 1. Trash manifest

`[device]` Pat opened the Trash folder after 3,316 files were trashed and
could tell it was "all dupes, as far as I can tell" but had no way to confirm
it. His words: he wants a manifest of where the untrashed files live, and
confirmation that each group kept exactly one copy.

`[code]` The invariant already holds structurally. `DuplicateGroup` carries
`keeper` and `extras` as separate fields, `proposeTrashDuplicates` iterates
only `extras`, and `KeeperSelector.keeperIndex` requires a non-empty group.
A group cannot lose every copy. The gap is that nothing displays this.

`[code]` The data needed is already journaled. Each `MutationRecord` holds
`sourceBefore`, `destinationAfter`, `operationType` and `taskRunId`, and the
`Trash` operation's `reason` string already names the keeper:
`"Duplicate of ${group.keeper.displayName} (matching SHA-256)"`.

**Work:**

A per-task manifest, reachable from Task history and from the Trash screen.
For a duplicate-trash run it lists, per group:

- the surviving copy's full path
- each trashed copy's original path
- where that trashed copy now sits under `PocketSteward/Trash/`
- the group's SHA-256

Add a headline assertion the user can read in one glance: *N groups, N copies
kept, M copies trashed*, with `groups == kept` being the property that
matters. If those two numbers ever disagree, say so loudly rather than
printing them and moving on.

Exportable as a text or CSV file into the scope root, so it can be read in a
file manager or kept after the app's database is wiped. `[code]`
`AppDatabase` is still on `fallbackToDestructiveMigration`, so a schema change
destroys the journal and with it every record of what was trashed. An exported
manifest is the only durable account. This matters more than it looks.

**Acceptance:** after a duplicate-trash run, the manifest names every trashed
file, its original location, its current location, and its surviving twin.
Kept-count equals group-count, stated on screen.

---

## 2. Failures are invisible

`[device]` The 4,835-operation run reported `1 failed`. Which operation, and
why, is not available anywhere in the app.

`[code]` `PlanExecutor` writes the failure into the journal at the
`is MutationResult.Failure ->` branch. No screen reads it back. The
completion screen prints `${summary.failed} failed` and Task history prints
the same count. The reason is in the database and nowhere else.

**Work:** surface failed operations with their error text, on the completion
screen and in Task history. Minimum: the file, the attempted operation, and
the failure message. This is the same shape as the "Left untouched" section
that already exists for validator rejections, and can reuse it.

`[inferred]` Common causes worth rendering legibly rather than as a raw
exception: destination already exists, source vanished mid-run, permission
denied, cross-volume move failure.

**Acceptance:** `1 failed` is tappable or expandable and names the file and
the reason.

---

## 3. One failure marks the whole run Failed

`[code]` `PlanExecutor` line 205:
`status = if (failed == 0) TaskRunStatus.COMPLETED else TaskRunStatus.FAILED`

`[device]` Task history therefore shows **Smart cleanup — Failed** for a run
that created 5 folders and moved 4,829 files with a single failure. The
summary text directly beneath it reads "4834 succeeded ... 1 failed".

**Work:** a third status. `PARTIAL`, or `COMPLETED_WITH_ERRORS`. `FAILED` is
for a run where nothing succeeded, or where the executor itself threw.
Distinguish them in the history list styling so a partial run doesn't read
like a disaster.

**Acceptance:** a run with at least one success and at least one failure shows
as partial, not failed.

---

## 4. Show the keeper before approving, not after

`[code]` `DuplicateReviewContent` lists group members. The keeper is now a
distinct field on `DuplicateGroup` but the review screen does not mark which
member it is.

`[inferred]` Pat approved 3,316 trash operations without being able to see,
per group, which copy was going to survive. The plan preview does show every
operation before approval, but a preview of 3,316 rows is not reviewable in
practice, which is exactly why the summary assertion in item 1 matters more
than the row list.

**Work:** in `DuplicateReview`, label the keeper explicitly and show its path.
Add a group-level summary line at the top: *N groups, N will be kept, M will
be trashed*.

**Acceptance:** the keeper is visually distinct from the extras before the
user proposes anything.

---

## 5. Undo at scale works; it needs progress and a guard

`[device]` Confirmed 2026-09-17 22:16. The 4,834-operation Smart cleanup was
undone from Task history: **4834 restored, 0 blocked, 1 skipped**, and the
undo was performed after a force-close and reopen of the app. Both the scale
claim and the persistent-journal claim now hold on hardware.

The `1 skipped` is correct: it corresponds to the single operation that failed
during the forward run, so there was nothing to reverse.

`[code]` What remains is not correctness. `UndoExecutor` has no progress
reporting of its own, so `ScanUiState.Undoing` shows a static "Restoring
files…" for the whole run, and there is no confirmation before starting.

**Work:** progress reporting for undo matching what execution now has, and a
confirmation step before undoing a run above some size. Undoing 4,829 moves by
accidental tap is worse than the original organize was.

**Acceptance:** undo of a large run shows progress and asks first.

---

## Out of scope

AI planner, NL parsing, SAF mutations, emptying trash. Unchanged.

---

## Verification

On device after the build is green:

1. Run a duplicate pass on a small planted set. Manifest names every file and
   its twin, kept-count equals group-count.
2. Export the manifest, open it in a file manager.
3. Force a failure (a destination that already exists) and confirm the
   completion screen names the file and reason.
4. That same run shows as partial in Task history, not failed.
5. Undo a run of a few hundred operations and watch progress.
   (Correctness at scale is already confirmed: 4,834 restored, 0 blocked,
   across a force-close.)

---

# Addendum — Scope, folder protection, and the flag file

Added 2026-09-17 after the first full-scale Smart cleanup run.

## 6. Smart cleanup flattens coherent folders

`[code]` `CleanupPlanGenerator.generate` iterates
`records.filter { !it.isDirectory }` across the entire scope at any depth, and
every destination is `"${scopeRoot}/$folderName/${record.displayName}"`. The
record's existing `parentRef` is never consulted.

So a file at `Downloads/SomeProject/cover.jpg` is planned to move to
`Downloads/Images/cover.jpg`, and the `.pdf` beside it goes to
`Downloads/Documents/`. Any folder that meant something as a unit gets split
across category folders.

`[device]` This already ran once on Downloads: 4,829 files moved. That run
retains its Undo in Task history.

**Three separate mechanisms are needed. They are not substitutes.**

### 6a. Depth default: only sort what's loose

Default Smart cleanup to files **directly in the scan root**, leaving anything
already inside a subfolder alone. A file someone put in a folder has already
been organized by a human.

`[inferred]` This single default would have prevented the flattening with no
configuration and no user memory required. Make sorting into subfolders an
explicit opt-in per run, shown in the plan preview as a count ("4,829 files,
3,100 of them from inside existing folders").

### 6b. Folder protection via a flag file

A marker file inside a folder means "do not sort the contents of this folder."
The scanner still indexes it; the plan generator refuses to emit operations
for anything under it.

Requirements:

- **Visible and self-explanatory**, not a dotfile. A file called
  `POCKETSTEWARD-DO-NOT-SORT.md` that a person finds in six months should
  explain itself without the app installed.
- **Presence is the whole signal.** An empty file works. Any content is
  human notes, and the app must never require a format to honor it.
- **Recursive by default**, covering the folder and everything under it, with
  the body able to narrow that if a user wants.
- **Lives in the filesystem, not the database.** It survives app reinstall,
  a destructive schema migration, and moving storage to another device. The
  database is not the source of truth for this.
- Creatable from inside the app, on any folder, in one tap.

Protected folders appear in the plan preview as a stated count
("2 folders protected, 340 files skipped"), so the protection is visible
rather than silent.

### 6c. Arbitrary scope selection

`[code]` `ScanTarget` currently offers Downloads, Documents, Pictures,
Everything, and the one SAF granted folder. There is no way to scan or act on
an arbitrary folder.

Add folder selection so a run can be narrowed to one folder. This is
convenience; 6a and 6b are the safety. Scoping only protects a user who
remembers to narrow it, and the failure mode is forgetting once.

**Acceptance for 6:** a folder containing a flag file is skipped entirely by
Smart cleanup, the preview states how many folders were protected and how many
files that spared, and the default run does not touch files already inside
subfolders.

## 7. What this makes the AI's job

`[inferred]` Stated by Pat, 2026-09-17, describing what he wanted the agentic
layer for: a folder holding markdown, images and an audio file all named for
one project, where the app asks "should I leave this folder alone?" and on a
yes writes the flag file into it.

That is the correct division and it should constrain how the AI is built:

- The AI's output is a **proposed flag file**, not a mutation. It reasons over
  folder contents and proposes protection; the human approves; the
  deterministic layer writes a file.
- Writing a flag file is not destructive and is trivially reversible by
  deleting it, which makes it the safest possible first thing to let a model
  propose.
- Enforcement stays in `CleanupPlanGenerator`, which reads the filesystem, not
  the model. A model that goes offline, changes, or is removed cannot weaken
  a protection that is already a file on disk.
- The existing rule holds: no AI-proposed operation reaches the executor
  without passing `PlanValidator` and the preview screen.

Build 6b so that "propose a flag file" is a one-line addition later, rather
than a new pathway into the mutation layer.
