# Pocket Steward — Milestone 7 build request

Target: `0.8.0-milestone7`, versionCode 8.

Milestone 7 is navigation restructure, folder picker, and a visual pass. No new
file operations. No AI. No Room schema change.

Provenance:

- `[device]` observed on Pat's phone running `b1f7386`, 2026-09-18
- `[code]` read out of the source at commit `b1f7386`
- `[pat]` Pat's stated decision
- `[inferred]` reasoning, not observation

---

## M6 verified on hardware first, so you know what not to touch

`[device]` All of this passed on the phone. It works. Do not restructure any of
it except where this document says so.

| Check | Result |
| --- | --- |
| Flag file protection | "1 folder protected by POCKETSTEWARD-DO-NOT-SORT.md, sparing 2 files" |
| Depth default | "19601 files left alone inside existing folders" |
| Protect / unprotect round trip | both directions work, marker lands in Trash |
| Arbitrary folder scope | "Choose a folder…" works, header names the folder |
| Partial status | "Partly done · 1 folder(s) created · 2 moved · 1 failed" |
| Failure detail | named the file and `Source does not exist: …` verbatim |
| Real run at scale | 2,661 planned, 4 folders created, 2,657 moved, 0 failures |

The forced failure was produced by deleting a source file between the preview
and Approve. `[code]` Every collision that exists at scan time is caught by
`PlanValidator` and lands in "Left untouched" instead, which is correct.

---

## 1. Break the scan flow into real navigation destinations

`[pat]` Chosen explicitly over a visual-only pass.

`[code]` `StorageScopeScreen.kt` is 748 lines and does a `when` over
`ScanUiState` with every review as a private composable inside that one file.
`PocketStewardNavHost` exists and has real routes, but the entire scan flow is
one destination.

`[device]` The consequence Pat hit: tapping into "Files older than 6 months",
then Back, dropped him to an empty scan screen and cost a full rescan of 22,000
files.

`[code]` The cause, exactly. Every review screen's back is wired to the same
function:

```
StorageScopeScreen.kt:123  onBack = viewModel::reset
StorageScopeScreen.kt:125  is ScanUiState.FileListReview -> FileListReviewContent(state, onBack = viewModel::reset)
StorageScopeScreen.kt:133  onBack = viewModel::reset
StorageScopeScreen.kt:138  onBack = viewModel::reset

ScanViewModel.kt:796  fun reset() { autoStarted = false; _uiState.value = ScanUiState.Idle }
```

So it is not specific to old files. Duplicate review, plan preview, protect
folders and uncategorized all discard the scan on back.

**Work:**

Split the scan flow into separate nav destinations with a real back stack:

```
ScanScreen          target selection, folder picker, scan progress
ResultsScreen       the summary and the action tiles
ReviewScreen        FileListReview / DuplicateReview, as a destination
PlanPreviewScreen   the plan and Approve / Cancel
CompletionScreen    the result, failures, Undo
```

Back pops the stack. The scan and its index survive until the user leaves the
scan flow entirely or starts a new scan.

`[inferred]` The index is the expensive thing, not the screen. Whatever shape
the state takes, the invariant that matters is that returning from a review
does not re-walk the filesystem.

Keep `reset()` for the case it was written for, which is starting a genuinely
new scan. It should stop being the back handler.

**Acceptance:** scan Downloads, open "Files older than 6 months", press back,
and the results are still there with no rescan. Same for every other review.

---

## 2. Folder picker: search, sort, filters, recents

`[pat]` All four requested.

`[code]` The picker today lists subfolders in whatever order the index returned
and offers no way to narrow them. On a real Downloads folder that is an
unusable list.

**2a. Name search.** A text field that filters the current directory's folder
list by name as you type. Case-insensitive substring match is enough.

**2b. Sort.** Name, file count, size, date modified. Name ascending is the
default. Pick one control, not four toggles.

**2c. Filter toggles.**

- Hide empty folders
- Show only protected folders
- Hide system folders (`Android/`, `.thumbnails`, anything starting with `.`)

**2d. Recent folders.** A short list at the top of the picker of folders
previously scanned. Cap it, five or so. Most recent first.

`[code]` **Persist recents in DataStore, not Room.** `SettingsRepository`
already owns a preferences DataStore with `stringPreferencesKey`, and
`PROJECT_KEYWORDS` is already a serialized list in exactly this shape. Follow
that pattern. The Room schema stays frozen.

**Acceptance:** typing in the picker narrows the list live; sort changes the
order; hiding empty folders removes them; a previously scanned folder appears
in recents after app restart.

---

## 3. Visual pass

`[pat]` Requested alongside the restructure. Do this after item 1, on top of
the new destinations, not before.

`[code]` What is actually wrong, from the source and the screenshots:

- **`Card(onClick = …)` is used as a button** throughout `StorageScopeScreen`.
  Approve, Cancel, Back, Done, Undo task are all Cards. Use `Button`,
  `OutlinedButton`, `TextButton` with the roles they carry. Destructive actions
  should look destructive.
- **No type hierarchy.** "Task complete", "4 folder(s) created" and "2657
  moved" all render at the same weight, so the headline does not read as one.
  Apply `MaterialTheme.typography` deliberately.
- **No spacing system.** Padding is applied ad hoc per composable. Pick a scale
  and use it.
- **Long screens have no scroll affordance** and content runs under the action
  row.
- `[device]` **`FileListReviewContent`'s Row wraps the size column one
  character per line.** The column needs `weight(1f)` and `maxLines`.
- `[device]` **The completion screen's section header reads "Failed" directly
  under "Partly done".** Old vocabulary under the new status. "Failed (1)" or
  "Didn't work" reads correctly.
- **Empty and loading states** are missing on most destinations.

`[inferred]` The theme already supports Material 3 dynamic color
(`Theme.kt:38`, `dynamicColor: Boolean = true`), so the palette is not the
problem and does not need replacing.

**Acceptance:** no `Card(onClick)` acting as a primary button; the completion
screen has a clear headline; no text column wraps per character.

---

## 4. Name the folders that were created

`[device]` Pat's words: the completion screen not saying what folders it
created or where is an oversight. A run reported "4 folder(s) created" and
nothing anywhere named them.

`[code]` `ExecutionSummary` carries `foldersCreated: Int` and nothing else. The
names never leave the executor, even though every `CreateDirectory` wrote a
`MutationRecord` with its `destinationAfter`.

`[code]` The data already exists and is already read back.
`TaskManifestService.build` maps every record for a run, including
`CREATE_DIRECTORY`, so the manifest already answers this question. The gap is
only that the completion screen does not.

**Work:** carry the created folder paths on `ExecutionSummary` and list them on
the completion screen. This is a read of data the journal already holds, so it
needs no schema change.

**Acceptance:** a run that creates folders names them, with their paths, on the
completion screen.

---

## Ruled closed, do not change

`[pat]` Decided 2026-09-18:

- **`FileListReview` stays read-only.** Large files and old files are browse
  surfaces. No trash-selected or move-selected action.
- **`RuleEngine`'s extension table stays as it is.** Roughly 13,192 of 13,700
  files classifying as uncategorized is accepted behavior. Uncategorized files
  are the AI layer's input later.

---

## Constraints

**No Room schema change.** `AppDatabase` is `version = 3` on
`fallbackToDestructiveMigration(dropAllTables = true)`, and the phone holds the
undo journal for a 4,829-operation run and a 2,657-operation run. If a fix
appears to require a new column or entity, stop and say so rather than adding
it. Recents go in DataStore for this reason.

**Do not touch the M6 logic that passed on hardware.** `SortScope`,
`CleanupPlanGenerator`, the marker handling, `PlanValidator`, `PlanExecutor`'s
failure and `PARTIAL` handling, `TaskManifestService`. Items 1 and 3 change how
screens are reached and how they look, not what the engine does.

**Keep the seams.** Every plan still goes through `PlanValidator`, then the
preview destination, then the executor and journal. Splitting the flow into
routes must not create a path that reaches the executor without passing the
preview.

---

## Out of scope

AI planner, natural-language parsing, SAF mutations, emptying trash, new file
operation types.

---

## Build

```
./gradlew clean assembleDebug testDebugUnitTest --stacktrace
```

`b1f7386` built green with zero source changes and 80 passing tests. Any new
failure is from this milestone's work.
