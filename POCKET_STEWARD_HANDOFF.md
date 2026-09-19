# Pocket Steward — full handoff

**Document version:** 1.0
**Written:** 2026-09-19
**Describes the repository at commit:** `f934613` (`0.8.0-milestone7`, versionCode 8)
**Status:** Reference document. Everything here is either verified against the
working tree at `f934613`, observed on hardware, or tagged as unverified.
**Produced by:** Claude (Opus 5) in session with Pat O'Sullivan.

This document exists so a cold instance — a fresh chat, a different model, a
different person — can pick this project up and continue without asking Pat to
re-explain anything. If something needed to continue is not in here, that is a
defect in this document.

---

## HOW TO USE THIS DOCUMENT

Read sections 1 through 4 before doing anything. They contain the constraints
that cannot be violated and the build reality that shapes every decision.

Sections 5 through 9 are reference: architecture, history, errors, scope.

Section 10 is the cold-start prompt. Paste it into a fresh chat.

---

## ATTRIBUTION KEY

Every substantive claim in this document is tagged:

- **[PAT]** — Pat's direct words or explicit decision, from this session or
  recorded verbatim in a repository document. Quoted text is exact.
- **[PAT-PRIOR]** — A Pat decision carried forward from an earlier part of the
  conversation that has since been summarised rather than re-read. Directional
  and reliable, but the exact wording is not guaranteed unless quoted from a
  repository file.
- **[REPO]** — Read out of the working tree at `f934613`. The file path is
  given. This is the strongest tier in this document: it was verified by
  actually opening the file, not recalled.
- **[DEVICE]** — Observed on Pat's phone. The date is given.
- **[TEST]** — Verified by a passing test against a real Kotlin compiler.
- **[KEEL]** — Claude's synthesis or analysis. Pat did not generate it and has
  not necessarily ratified it. Useful context; not a decision.
- **[KEEL-FLAG]** — A concern Claude raised that Pat has not addressed. Open
  question only. Never treat as direction.

**Deliberate omission:** the `[CANON]` and `[PRIOR-SESSION]` tiers from the
doc-provenance protocol are not used here. `[CANON]` requires retrieval from
sealed project space, which does not apply to a code repository; `[REPO]` is
its analogue and is stronger, because it was verified by reading the file at a
named commit. This deviation is stated rather than left silent.

**Note on a second tag scheme.** The repository's own specs and death reports
use lowercase `[device]`, `[code]`, `[test]`, `[pat]`, `[inferred]`. A cold
instance will meet those inside those files. They map directly: `[code]` is
this document's `[REPO]`, `[inferred]` is `[KEEL]`. Do not introduce a third
scheme.

---

# 1. WHAT THIS IS, AND THE ONE RULE

## 1.1 The app

[REPO: `POCKET_STEWARD_ANDROID_FILE_AGENT_PLAN_V0_1.md`] Pocket Steward is an
Android-only, on-device, offline-first agentic file organiser. It scans
storage, proposes a typed plan of file operations, shows that plan to a human
for approval, executes it through a write-ahead journal, and can undo it.

[PAT-PRIOR] Personal use, Pat's own phone. No publishing commitment. Built
because he wanted a file organiser that could not destroy anything.

## 1.2 The constraint that overrides everything

[PAT-PRIOR] **Nothing deletes. Ever.** Pat's original statement, as carried in
the conversation record:

> My clarification is I don't want deleting to be available at all. It should
> move them to a folder called trash with some folders based on where they
> came from so I can manually delete it myself.

[KEEL] That wording comes from the conversation record rather than from a
repository file, so treat it as accurate in substance and close in wording
rather than character-verified. The substance is not in doubt: it is the rule
every milestone since M2 has been built under, and the repository restates it
in its own words in `M5_COMPLETION_SPEC.md`.

[REPO: `app/.../storage/DirectStorageGateway.kt:186`] `trash()` moves a file to
`PocketSteward/Trash/<original path mirrored>` under external storage.

[REPO: `M5_COMPLETION_SPEC.md:233`] There is deliberately **no empty-trash
action anywhere in the app**, and the M5 spec states it in those terms:

> **Do not** add an "empty trash" action. That constraint is owner policy, not
> an oversight.

[KEEL] Correction to an existing repository document: `DEATH_REPORTS/M6.md`
attributes that sentence to the M6 spec. It is in `M5_COMPLETION_SPEC.md`. The
M6 spec only lists "emptying trash" under Out of scope. A cold instance looking
for the constraint should look in the M5 spec.

**The one exception, and it is narrow.** [REPO:
`app/.../storage/StorageGateway.kt`] `removeEmptyDirectory` performs a real
deletion. It can only remove a directory that is empty, and it is only ever
called when undoing a `CreateDirectory` that the current task itself created.
It cannot touch a directory with contents and cannot touch one the app did not
make. [PAT-PRIOR] This was flagged to Pat explicitly and allowed to stand.

Everything else that looks like removal is a trash move, including:

- [REPO: `app/.../executor/UndoExecutor.kt`] undoing a written file trashes it
- [REPO: `app/.../ui/scan/ScanViewModel.kt`] removing a folder's protection
  marker trashes the marker

## 1.3 How Pat works, and what he has said about it

[PAT] This session, verbatim: "No deferrals if I asked for it I want it".
Context: Milestone 6's spec described item 6c as convenience rather than
safety, and Claude deferred it. Pat rejected the deferral and it was built the
same day. **A spec's own prioritisation is not permission to skip the lower
priority.**

[PAT-PRIOR] Other standing corrections from earlier in the project:

- He asked for a document and got a Claude artifact instead. His response
  included the words "I asked for a document not an artifact" — deliverables
  are real files, not Claude artifacts.
- He told Claude to stop repeating the compile limitation. State it once where
  it is load-bearing, then stop.
- "We're moving too slow" — when a milestone stalled on process rather than
  work.
- He is not a coder. [PAT-PRIOR] His own words to that effect: he can only tell
  whether something works by installing it and using it. Design explanations
  should not assume otherwise, and "it compiles" is not evidence that it works.

---

# 2. WHERE THINGS STAND RIGHT NOW

## 2.1 Current state

| | |
| --- | --- |
| Repository | `github.com/pemunson90-cmd/Pocket-Steward` |
| Branch | `main` on GitHub; `master` locally (see §3.2) |
| HEAD | `f934613` |
| Version | `0.8.0-milestone7`, versionCode 8 |
| Last milestone verified on hardware | **M6**, at `b1f7386`, 2026-09-18 |
| Milestone written but **not yet built or tested** | **M7** (`b599c01`, `f934613`) |

[REPO] Source: 7,903 lines of Kotlin in `app/src/main`, 1,239 in
`app/src/test`, across 66 main and 12 test files.

[REPO] Test count in the repository: **98 `@Test` methods** across 12 test
classes.

[KEEL] A number that needs explaining, because three different counts appear in
the project's own documents and they are not contradictions:

- **98** is the repository's total `@Test` count at `f934613`.
- **91** is the count in Claude's pure-Kotlin scratch module (see §4.2). It is
  lower because the scratch module cannot hold tests that touch Android types.
- **80** was reported by the build session at `b1f7386`, before M7 added 18
  picker tests.

## 2.2 The immediate next action

[KEEL] M7 has never been compiled. It is the largest structural change since
Milestone 2 — a 748-line file deleted and replaced with a navigation graph and
six screens — and none of the navigation code was compiled in the environment
that wrote it.

**Next action: build `f934613` and install it.** The build prompt is §10.2.

---

# 3. THE BUILD LOOP

## 3.1 Why there is a loop at all

[REPO: `STATUS.md`] The environment Claude writes this code in cannot compile
Android. This is not a preference or a slow path; the Android Gradle Plugin and
most androidx artifacts are unreachable. See §4.1 for the specifics.

[PAT-PRIOR] Pat's stated division of labour, after several rounds of
frustration with it:

> only Claude.ai and chat gpt work mode can compile, you and chat gpt regular
> can design

[KEEL] So the loop is: Claude Code writes and pushes → a build session clones,
compiles, fixes real compile errors, pushes back → the APK comes to Pat through
chat → Pat installs it on his phone and reports what actually happened.

## 3.2 Git specifics that will trip up a cold instance

[REPO] The local repository is at `/home/user/pocket-steward` on the branch
**`master`**. The GitHub remote is named **`ps-github`** and its default branch
is **`main`**.

The push command is therefore:

```
git push ps-github master:main
```

[KEEL] A plain `git push` will not work. This mismatch exists because the
canonical history was built locally before the GitHub repository existed, and
was joined to it with an unrelated-histories merge (`8a3a6b4`) rather than a
re-clone.

[KEEL] GitHub reports "This repository moved" on every push, because the remote
URL is the lowercase `pocket-steward` and the repository is now
`Pocket-Steward`. It is a redirect, not an error. The push succeeds.

## 3.3 APK return path

[PAT-PRIOR] The APK comes back through chat as a zip. The M3 debug APK was
about 10.7 MB, comfortably under the 30 MB attachment cap. This replaced an
earlier zip-relay process that Pat called out as wasting his time.

---

# 4. BUILD ENVIRONMENT AND VERIFICATION

## 4.1 Network reality

[REPO: `STATUS.md`] Empirically confirmed by actual status codes, not assumed:

| Host | Result |
| --- | --- |
| `dl.google.com` | connection failure |
| `maven.google.com` artifact paths | `403 CONNECT tunnel failed` |
| `repo1.maven.org` | `429` — reachable, rate-limited |
| `plugins.gradle.org` | `429` — reachable, rate-limited |
| `services.gradle.org` | `200` |
| `raw.githubusercontent.com` | `200` |
| `api.github.com` | `200` |

[KEEL] Consequence: pure-JVM Kotlin compiles here. Anything that resolves an
Android artifact does not.

[KEEL-FLAG] These codes were confirmed during Milestone 5. A cold instance
should re-check with a live request before asserting them, rather than
repeating them from this document. Asserting a blocked state from stale context
is a mistake this project has already made once.

## 4.2 The scratch module — how anything gets verified

[KEEL] A pure-Kotlin Gradle project outside the repository, used to compile and
run tests against a real Kotlin compiler for code that has zero Android
imports.

```
Path:  <scratchpad>/kotlin-verify
Run:   gradle test --console=plain
```

[KEEL] Notes that matter:

- The system Gradle is at `/opt/gradle/bin/gradle`. There is **no `./gradlew`**
  in the scratch module.
- Source files are copied in from the repository, tests are written against
  them, and passing tests are copied back into `app/src/test`.
- The scratch path is session-specific. A new session creates a new one.

**What can be verified there** [REPO]: `plan/`, `rules/`, `dedupe/`,
`picker/`, `cleanup/SortScope.kt`, `report/TaskManifest.kt`,
`executor/SingleFolderIndex.kt`, the pure parts of `storage/`.

**What cannot**: anything importing Room, Compose, Android SDK, or
`kotlinx.coroutines` against an Android dispatcher. That includes every
ViewModel, every screen, `PlanExecutor`, `UndoExecutor`, `MutationRecovery`,
and `TaskManifestService`.

[KEEL] **The pattern this produces, stated plainly:** the scratch-module
discipline pushes bugs to the boundary between tested logic and untested
adapter. It does not remove them. The last three real compile errors in this
project were all in adapter code. When a build fails, look at the adapters
first.

## 4.3 The build command

```
./gradlew clean assembleDebug testDebugUnitTest --stacktrace
```

[DEVICE] `b1f7386` built green with zero source changes — the first milestone
in this project to compile on the first attempt.

---

# 5. ARCHITECTURE — WHAT CANNOT CHANGE

[KEEL] Each of these has a stated cost of breaking it. That is deliberate: a
frozen rule with no reason attached gets broken by the next person who finds it
inconvenient, and they would be right to.

## 5.1 The chain

[REPO: `POCKET_STEWARD_ANDROID_FILE_AGENT_PLAN_V0_1.md`, Decision 1]

```
user intent
  → planner
  → typed operation manifest (PlannedOperation)
  → PlanValidator
  → human preview screen
  → PlanExecutor
  → write-ahead journal
  → undo / recovery
```

[REPO: `app/.../cleanup/PlanSource.kt`] Enforced by types, not by convention. A
`PlanSource` returns a `GeneratedCleanup` and physically cannot reach a
`StorageGateway`. Proposing and executing are different objects.

**Cost of breaking it:** the AI layer planned for M8 gets a path to the
filesystem that no human approved. Every safety property in this app is
downstream of this ordering.

## 5.2 The write-ahead journal

[REPO: `app/.../executor/PlanExecutor.kt`] A `MutationRecord` is written
`PENDING` **before** the gateway call and flipped to `COMMITTED` or `FAILED`
after. [DEVICE] This is what made a 4,834-operation undo survive a force-close
and reopen of the app on 2026-09-17.

**Cost of breaking it:** process death between the filesystem call and the
status update becomes invisible instead of recoverable.

## 5.3 The validator runs as a whole-plan pre-pass

[REPO: `app/.../plan/PlanValidator.kt`] Never interleaved with execution.
`PlanExecutor` re-validates before executing.

[REPO: `app/.../executor/PlanExecutor.kt`] This is why `execute` has an
optional `index: FileIndex?` parameter: M6c introduced folders the scan index
has never seen, and without a caller-supplied index the re-validation would
reject at execute time exactly what the preview had just accepted.

**Cost of breaking it:** a preview that shows one thing and an execution that
does another.

## 5.4 Protection lives in the filesystem, not the database

[REPO: `app/.../cleanup/SortScope.kt`] The marker file is
`POCKETSTEWARD-DO-NOT-SORT.md`. Presence is the entire signal. Nothing parses
it. An empty file works. It is deliberately not a dotfile so a person who finds
it in six months, with the app uninstalled, can read the name and understand
it.

**Cost of breaking it:** a protection that a destructive migration, a
reinstall, or moving storage to another device silently removes. It is also
what lets a model later *propose* protection without being able to weaken it —
enforcement reads a file on disk, not a model's output.

## 5.5 No Room schema change

[REPO: `app/.../data/db/AppDatabase.kt:12,77`] `version = 3`, on
`fallbackToDestructiveMigration(dropAllTables = true)`.

[DEVICE] The phone holds undo journals for a 4,829-operation run and a
2,657-operation run.

Adding an **enum value** is safe — the TypeConverter stores a String. Adding a
**column or entity** wipes every journal on the device on first launch.

**Cost of breaking it:** silent, total loss of the undo history, with no error
shown to the user.

[KEEL] This is a frozen constraint, not a good design. See §8.1.

## 5.6 Journal encoding, which has already caused one bug

[REPO: `app/.../storage/FileRefJournalCodec.kt`] `MutationRecord.sourceBefore`
and `destinationAfter` hold type-preserving codec strings with a prefix.
`TaskRun.scopeRootRef` holds a raw `rawValue()` and does **not**.

Using `FileRefJournalCodec.decode` on a `scopeRootRef` throws "Unknown durable
FileRef encoding". Use `parseFileRef` for that one. [REPO:
`app/.../report/TaskManifestService.kt`] There is a comment at the call site
saying so.

## 5.7 The keeper-path round trip

[REPO: `app/.../report/TaskManifest.kt`] `duplicateTrashReason` writes the
surviving copy's full path into a Trash operation's reason string;
`keeperPathFrom` reads it back. They are tested as a pair. Nothing else may
format or parse that string.

[KEEL] It exists because `MutationRecord` has no column for "the copy this one
lost to", and adding one would trigger §5.5.

**Cost of breaking it:** a manifest that silently reports "not recorded" for
every trashed file, with no error anywhere.

---

# 6. SOURCE MAP

[REPO] Package layout at `f934613`. Everything under
`app/src/main/java/com/pocketsteward/app/`.

| Package | What lives there | Verifiable in scratch module |
| --- | --- | --- |
| `plan/` | `PlannedOperation`, `AgentPlan`, `FileIndex`, `PlanValidator`, safety classes | yes |
| `rules/` | `RuleEngine`, extension + project-keyword classification | yes |
| `dedupe/` | `DuplicateDetector` (size → fingerprint → SHA-256 cascade), `KeeperSelector` | yes |
| `cleanup/` | `SortScope` (depth + marker protection), `CleanupPlanGenerator`, `PlanSource` | `SortScope` only |
| `picker/` | `FolderPicker` (search/sort/filter), `RecentFolders` | yes |
| `report/` | `TaskManifest` (pure), `TaskManifestService` (Room + gateway) | `TaskManifest` only |
| `storage/` | `StorageGateway`, `DirectStorageGateway`, `SafStorageGateway`, `FileRef`, codecs | codecs only |
| `executor/` | `PlanExecutor`, `UndoExecutor`, `MutationRecovery`, `InMemoryFileIndex`, `SingleFolderIndex` | `SingleFolderIndex` only |
| `data/db/` | Room entities and DAOs | no |
| `data/settings/` | `SettingsRepository` (DataStore) | no |
| `ui/scan/` | The M7 navigation flow: 6 screens + `ScanFlowNav` + `ScanFlowUi` + `ScanViewModel` | no |
| `ui/history/`, `ui/trash/`, `ui/home/`, `ui/settings/`, `ui/onboarding/` | the other screens | no |
| `ui/theme/` | `Theme`, `Type`, `Color`, and M7's `Spacing` scale | no |
| `navigation/` | `PocketStewardNavHost`, `Routes` | no |
| `di/` | `AppContainer` — manual DI, deliberately not Hilt | no |

## 6.1 Files with a landmine in them

[REPO: `app/.../executor/InMemoryFileIndex.kt`] `collisionKey` contains a
`\u0000` escape sequence. It was once a **raw NUL byte**, which broke two
separate build attempts. The Read tool renders the escape as a space, which
causes Edit-tool match failures.

**If you need to edit that line, use `sed` with a line number, or inspect with
`od -c`. `grep -P '\x00'` does not reliably find a raw NUL.**

---

# 7. MILESTONE HISTORY AND DOCUMENT INVENTORY

## 7.1 Documents in the repository, with authority

[REPO] All present at `f934613`.

| File | Version / date | What it is | Authority |
| --- | --- | --- | --- |
| `POCKET_STEWARD_ANDROID_FILE_AGENT_PLAN_V0_1.md` | V0.1 | The original full plan: decisions, sections, milestone sequence | **Foundational.** Decisions 1–6 and the section numbers are cited throughout the code. |
| `M5_COMPLETION_SPEC.md` | M5 | Spec for milestone 5 | Delivered. Holds the no-empty-trash rule. |
| `M6_TRANSPARENCY_SPEC.md` | M6 | Spec for milestone 6, incl. the 6a/6b/6c addendum | Delivered and verified on hardware. |
| `M7_BUILD_REQUEST.md` | M7, 2026-09-18 | Spec for milestone 7 | **Delivered in code, not yet built.** |
| `STATUS.md` | rolling, 56 KB | Per-milestone changelog with verified-vs-reviewed-only splits | Reference. Longest document in the repo. |
| `DEATH_REPORTS/README.md` | v1 | The death report format and its rules | Process. |
| `DEATH_REPORTS/M0-M5-RETROSPECTIVE.md` | retrospective | M0–M5, written after the fact | Thinner than a live report; unrecorded items marked. |
| `DEATH_REPORTS/M6.md` | M6 | Full death report | Complete. Contains one misattribution — see §1.2. |
| `DEATH_REPORTS/M7-NOTES.md` | in-flight | Running log for M7's report | **Add to this as M7 findings arrive.** |
| `BUILD_ENVIRONMENT.md` | M3-era | Build environment notes | Partly superseded by §4 here. |
| `M3_BUILD_NOTES.md`, `BUILD_M3_NOW.md` | M3-era | Historical | Superseded. |
| `POCKET_STEWARD_OPEN_SOURCE_COMPONENT_AUDIT.md` | v1 | Dependency/licence audit | Reference. |
| `POCKET_STEWARD_OPEN_SOURCE_LEGO_AUDIT_INSTRUCTIONS_V1_0.md` | V1.0 | Instructions the audit was produced from | Reference. |
| `POCKET_STEWARD_HANDOFF.md` | **1.0** | This document | Cold-start reference. |

## 7.2 Milestones, by version

| M | Version | Commit | Shipped | Hardware |
| --- | --- | --- | --- | --- |
| 0 | `0.1.0-milestone0` | — | Skeleton: Gradle, Room, DataStore, permission onboarding, `StorageGateway` with stub bodies | [KEEL] not separately recorded |
| 1 | `0.2.0-milestone1` | — | Real gateway, recursive scanner, Room index, resume support | not separately recorded |
| 2 | `0.3.0-milestone2` | — | Typed manifest, `PlanValidator`, preview, `PlanExecutor`, journal, undo | [DEVICE] validator collision behaviour confirmed |
| 3 | `0.4.0-milestone3` | — | Journal recovery across process death, Trash, history | [DEVICE] **4,834 restored, 0 blocked, 1 skipped, across a force-close**, 2026-09-17 |
| 4 | `0.5.0-milestone4` | `e64e9ab`, `cfc6b2e` | `RuleEngine`, project keywords, rule-based Smart cleanup | built after a fix (§8.2) |
| 5 | `0.6.0-milestone5` | `17827f8` | `KeeperSelector`, `DuplicateDetector` rewrite, `PlanSource` seam, progress everywhere | [PAT-PRIOR] reported clearing Decision 2's gate |
| 6 | `0.7.0-milestone6` | `88d6ce3`, `b1f7386` | Transparency pass + folder protection + arbitrary scope | [DEVICE] **fully verified 2026-09-18** — table below |
| 7 | `0.8.0-milestone7` | `b599c01`, `f934613` | Navigation restructure, folder picker, visual pass, named folders | **not built, not tested** |

## 7.3 M6 hardware verification — the last known-good state

[DEVICE] On Pat's phone at `b1f7386`, 2026-09-18. [REPO:
`M7_BUILD_REQUEST.md`] recorded verbatim in the M7 spec:

| Check | Result |
| --- | --- |
| Flag file protection | "1 folder protected by POCKETSTEWARD-DO-NOT-SORT.md, sparing 2 files" |
| Depth default | "19601 files left alone inside existing folders" |
| Protect / unprotect round trip | both directions work, marker lands in Trash |
| Arbitrary folder scope | "Choose a folder…" works, header names the folder |
| Partial status | "Partly done · 1 folder(s) created · 2 moved · 1 failed" |
| Failure detail | named the file and `Source does not exist: …` verbatim |
| Real run at scale | 2,661 planned, 4 folders created, 2,657 moved, 0 failures |

[DEVICE] The forced failure was produced by deleting a source file between the
preview and Approve. [REPO] Every collision that exists at scan time is caught
by `PlanValidator` and lands in "Left untouched" instead, which is why that was
the only way to produce one.

**[KEEL] This is the rollback target. If M7 goes wrong, `b1f7386` is a known
good build.**

## 7.4 What M7 changed (unverified)

[REPO: `b599c01`, `f934613`]

1. **Navigation.** `StorageScopeScreen.kt` (748 lines) deleted. Replaced by a
   nested nav graph, `ScanFlow`, with six destinations sharing one
   `ScanViewModel` scoped to the **graph's** back stack entry. `ScanUiState`
   survives as an internal event bus; `routeToDestination` fans each value into
   the per-destination flow that owns it.
2. **Folder picker.** Search, sort, filters, recents. Logic in
   `picker/FolderPicker.kt`, 18 tests. Recents in DataStore, not Room.
3. **Visual pass.** Buttons are Buttons; one `Spacing` scale; one headline per
   screen; action rows pinned below the scroll; size column no longer wraps
   per character; "Didn't work (1)" replaces "Failed" under "Partly done".
4. **Created folders named.** `ExecutionSummary.createdFolders`, read from the
   `resultRef` the journal already records.

[KEEL] **The one thing that must be right**, and which would compile fine while
being wrong: `scanViewModel()` in `ScanFlowNav.kt` resolves the ViewModel
against `navController.getBackStackEntry(ScanFlow.GRAPH)`. If it resolves
against the destination instead, every screen gets its own empty scan and the
exact bug M7 exists to fix comes straight back, silently.

---

# 8. ERRORS FOUND AND FIXED

[KEEL] Kept because the pattern matters more than any individual bug.

## 8.1 Design decisions that turned out wrong

**`fallbackToDestructiveMigration`.** [REPO: `AppDatabase.kt:77`] Set in M0 when
there was no data. It stopped being right at M3, when the journal started being
worth keeping, and that moment was not marked. It forced three separate
workarounds in M6 alone: the SHA in the previously-unused `sourceFingerprint`
column, the keeper path smuggled through a reason string, and `planJson`
repurposed as a tab-separated side table. [KEEL] This is the worst thing in the
codebase.

**Room 2.8.4 and Navigation Compose 2.8.4 over Room 3.0 / Navigation 3.**
[REPO: `STATUS.md`] Chosen because this environment cannot compile and
therefore cannot catch a mistake in unfamiliar API surface. Correct at the
time; debt now.

## 8.2 Real compile failures that shipped

**A raw NUL byte in Kotlin source.** [REPO] `InMemoryFileIndex.collisionKey`
contained a literal NUL rather than the escape sequence, in two independently
produced M3 trees that shared an M2 ancestor. Broke two build attempts.
`grep -P '\x00'` does not find it reliably; `od -c` does.

**`parseFileRef` was never defined.** M4 referenced it in seven places. It had
existed in a superseded tree and vanished when a different tree was swapped in.
Caught by a build session, not here. [KEEL] Static review missed it because
`InMemoryFileIndex.refFor` was a private duplicate of the same logic. **The
generalisable lesson: a hand review verifies that code is consistent with
itself, not that the symbols it references exist.**

**`maxOfOrNull` over a nullable selector.** [PAT] Caught by Pat reading the
diff, 2026-09-19:

```kotlin
// shipped in b599c01, does not compile
modifiedAt = stats.maxOfOrNull { it.modifiedAtEpochMs }
// correct, in f934613
modifiedAt = stats.mapNotNull { it.modifiedAtEpochMs }.maxOrNull()
```

[REPO: `storage/StorageModels.kt:60`] `FileMetadata.modifiedAtEpochMs` is
`Long?`. [TEST] Reproduced against a real compiler in a four-line scratch
project: *"Overload resolution ambiguity between candidates"*, listing the
`Double`, `Float` and `R : Comparable<R>` overloads. The corrected form
compiles. [KEEL] It is also the behaviour wanted on its own terms — a file with
no recorded timestamp should not decide its folder's. Logged in
`DEATH_REPORTS/M7-NOTES.md`.

## 8.3 Bugs caught before shipping

[KEEL] Worth recording because each one would have been silent:

- `PlanExecutor` status was `if (failed == 0) COMPLETED else FAILED`, so a run
  that moved 4,829 files and missed one was filed as FAILED directly above its
  own "4834 succeeded" summary. Fixed by adding `PARTIAL`.
- `UndoExecutor` required status `COMPLETED || FAILED` to undo. Adding `PARTIAL`
  without updating that check would have made exactly the runs most worth
  undoing the only ones that could not be.
- `TaskManifestService.export` initially used `FileRefJournalCodec.decode` on
  `TaskRun.scopeRootRef`, which is not codec-encoded. Would have thrown on
  every export. See §5.6.
- `MutationRecovery` has two exhaustive `when (operationType)` blocks that both
  needed the new `WRITE_TEXT_FILE` branch.
- A test fixture bug in `KeeperSelector`, where the test was wrong and the code
  was right. [KEEL] Recorded because the instinct on a red test is to change
  the code under test.

## 8.4 Process failures

[PAT-PRIOR] **Zip relay.** Early milestones moved source between environments
as chat attachments. Pat called it out directly. Replaced mid-M5 with the
GitHub loop in §3. Everything after that point moved faster for that reason
alone.

[PAT] **Deferral.** See §1.3.

[KEEL] **Claiming a blocked state from stale context.** A build-kit script
correctly challenged an unverified "blocked" claim that had been asserted from
memory rather than from a fresh status code. Re-check before asserting.

---

# 9. SCOPE

## 9.1 Ruled closed — do not reopen without Pat

[REPO: `M7_BUILD_REQUEST.md`, decided 2026-09-18]

- [PAT] **`FileListReview` stays read-only.** Large files and old files are
  browse surfaces. No trash-selected or move-selected action.
- [PAT] **`RuleEngine`'s extension table stays as it is.** Roughly 13,192 of
  13,700 files classifying as uncategorized is accepted behaviour.
  Uncategorized files are the AI layer's input later. [KEEL] "Accepted" and
  "fine" are different words; this is the first.

## 9.2 Out of scope, currently

[REPO: `M7_BUILD_REQUEST.md`] AI planner, natural-language parsing, SAF
mutations, emptying trash, new file operation types.

## 9.3 Next, if M7 lands clean

[REPO: `POCKET_STEWARD_ANDROID_FILE_AGENT_PLAN_V0_1.md`, Section 20] Milestone
8 is the on-device AI planner, plus the plan's item 7 foreground execution
service.

[REPO: `M6_TRANSPARENCY_SPEC.md` §7] The spec already constrains how the AI
layer must be built, and this is the single most important forward-looking
paragraph in the project:

- The AI's output is a **proposed flag file**, not a mutation. It reasons over
  folder contents and proposes protection; the human approves; the
  deterministic layer writes a file.
- Writing a flag file is not destructive and is reversible by deleting it,
  which makes it the safest possible first thing to let a model propose.
- Enforcement stays in `CleanupPlanGenerator`, which reads the filesystem, not
  the model.
- No AI-proposed operation reaches the executor without passing
  `PlanValidator` and the preview screen.

[REPO] M6 built `PlannedOperation.WriteTextFile` specifically so that "propose
a flag file" is a one-line addition to a planner rather than a new pathway into
the mutation layer.

## 9.4 Debt, in priority order

[KEEL] From `DEATH_REPORTS/M6.md` and this session:

1. `fallbackToDestructiveMigration` — see §8.1.
2. `planJson` as a parseable side channel. It is a schema pretending not to be
   one, and it exists only because of item 1.
3. **Scope overlap in `FileRecord`.** `scopeRootRef` is set per scan and
   `upsert` is keyed on `stableRef`, so scanning Downloads and then a folder
   inside it re-scopes the shared records, and `getFilesUnderScopeRoot` on the
   outer scope then misses them. Pre-existing — Downloads and "Everything"
   already overlapped before M6c. Not observed to have produced a wrong result.
   [KEEL-FLAG] It is a correctness bug waiting for the right sequence of scans.
4. **`SafStorageGateway` is a stub that type-checks.** Six methods are
   `TODO()`. The UI fences SAF out of every path that reaches them, so the stub
   never throws — which means nothing tests that the fencing is complete.
   [KEEL-FLAG] Either implement it or delete the mode.
5. `MutationOperationType.COPY` exists and no code path can produce it.
6. The APK test tile — M2's hard-coded plan, a developer affordance on a
   user-facing screen.

---

# 10. STARTING A FRESH SESSION

## 10.1 Cold-start prompt

[KEEL] Paste this into a new chat:

> I'm continuing work on Pocket Steward, an Android on-device file organiser.
>
> Repository: `pemunson90-cmd/Pocket-Steward`, branch `main`, at commit
> `f934613`, version `0.8.0-milestone7`.
>
> Read `POCKET_STEWARD_HANDOFF.md` in the repository root first. It has
> everything: the constraints, the build loop, the architecture, the error
> history, and what is in and out of scope. Then read `STATUS.md` for the
> per-milestone detail and `DEATH_REPORTS/` for what each milestone froze and
> what it left behind.
>
> Three things before you start:
>
> 1. Nothing in this app deletes. Trash is a move. There is no empty-trash
>    action and there will not be one.
> 2. No Room schema change. The phone holds undo journals worth thousands of
>    operations and the database is on `fallbackToDestructiveMigration`.
> 3. You cannot compile Android here. Verify pure-Kotlin code in a scratch
>    module against a real compiler; hand-review the rest and say plainly which
>    is which.
>
> Milestone 7 is written but has never been built. That is the next thing.

## 10.2 The M7 build prompt

[KEEL] Hand this to a session that can compile.

> Clone `pemunson90-cmd/Pocket-Steward`, branch `main`, commit `f934613`
> (`0.8.0-milestone7`, versionCode 8).
>
> ```
> ./gradlew clean assembleDebug testDebugUnitTest --stacktrace
> ```
>
> Return the debug APK directly.
>
> `b1f7386` built green with zero source changes and 80 passing tests. Any new
> failure is from M7.
>
> **Expect this one to break.** `StorageScopeScreen.kt` (748 lines) was deleted
> and replaced with a nested navigation graph and six destinations. None of the
> navigation was compiled in the environment that wrote it.
>
> Verified against a real compiler (91 scratch-module tests): `picker/`,
> `plan/`, `cleanup/SortScope.kt`, `report/TaskManifest.kt`,
> `executor/SingleFolderIndex.kt`, `rules/`, `dedupe/KeeperSelector.kt`,
> `storage/FileRefJournalCodec.kt`.
>
> Reviewed by hand only, most likely to fail first: `ui/scan/ScanFlowNav.kt`,
> `ui/scan/ScanViewModel.kt`, the six new screen files,
> `navigation/PocketStewardNavHost.kt`.
>
> Two imports to check first on an unresolved reference:
> `androidx.navigation.compose.navigation` (not `androidx.navigation.navigation`),
> and `androidx.compose.material3.FilterChip` against Compose BOM 2026.06.01.
>
> **No Room schema change.** If a fix appears to need a new column or entity,
> stop and say so rather than adding it.
>
> **Do not touch the M6 engine** — `SortScope`, `CleanupPlanGenerator`, marker
> handling, `PlanValidator`, `PlanExecutor`'s failure and `PARTIAL` handling,
> `TaskManifestService`. All of it passed on hardware at `b1f7386`.
>
> **Keep the seam.** Every plan goes `PlanValidator` → preview destination →
> `PlanExecutor` → journal. If splitting the flow into routes created a path to
> the executor that skips the preview, that is a bug, not a shortcut.

## 10.3 M7 on-device acceptance

[REPO: `M7_BUILD_REQUEST.md`] The acceptance test for the whole milestone is
step 2. Everything else is secondary.

1. Scan Downloads.
2. **Open "Find files older than 6 months", press back. The results must still
   be there with no rescan.** Repeat for Review uncategorized, Find duplicates,
   Protect folders, and a plan preview's Cancel.
3. "Scan again" on the results screen clears it and returns to target
   selection — the one control allowed to throw the index away.
4. Picker: type in search and watch the list narrow; change the sort chip;
   toggle "Hide empty".
5. Scan a folder from the picker, leave the app, come back: it appears under
   "Recent folders".
6. Run a Smart cleanup that creates folders. The completion screen names each
   one, with its path.
7. Force a failure (delete a source file between preview and Approve).
   Completion reads "Partly done" with a section headed "Didn't work (1)", not
   "Failed".
8. "Find largest files": the size column does not wrap one character per line.

## 10.4 After M7 lands

[KEEL] Write `DEATH_REPORTS/M7.md` from `DEATH_REPORTS/M7-NOTES.md` plus
whatever the device shows. The README's rules apply: every claim tagged, a
stated cost of breaking each frozen thing, and an honest "what needs to go".
[REPO: `DEATH_REPORTS/README.md`] A milestone with an empty "needs to go"
section is a milestone whose author did not look.

---

# 11. OPEN QUESTIONS FOR PAT

[KEEL-FLAG] None of these are decisions. They need Pat's ruling before anything
is built on them.

1. **`fallbackToDestructiveMigration`.** Reversing it means writing real
   migrations, and the first one has to be written before the next schema
   change is needed, not during it. Is that worth doing before M8, or does the
   AI layer come first and the database stays frozen?
2. **`SafStorageGateway`.** Six `TODO()` methods behind UI fences that nothing
   tests. Implement it, or delete SAF mode and require full file-manager access?
3. **The `FileRecord` scope-overlap bug** (§9.4 item 3). It has not produced a
   wrong result yet. Fix it now, or wait for it to bite?
4. **The APK test tile.** It is a Milestone 2 developer affordance still on the
   results screen. Remove it, or keep it as a sanity check?
5. **M8 scope.** The M6 spec constrains *how* the AI layer is built. It does
   not say what model, running where, or what its first shipped capability is
   beyond "propose a flag file". That needs a spec from Pat the way M5, M6 and
   M7 each got one.

---

# 12. WHAT THIS DOCUMENT IS NOT

- It is not a substitute for `STATUS.md`, which has the per-milestone detail
  this compresses.
- It is not canon in the sense of being sealed. It is a snapshot at `f934613`
  and it goes stale the moment M7 is built.
- It does not record decisions that were explored and abandoned mid-session.
- [KEEL] Anything here tagged `[KEEL]` or `[KEEL-FLAG]` is Claude's reading,
  not Pat's decision, and should be treated as such by whoever reads this next.

---

*Pocket Steward handoff v1.0 — 2026-09-19, at commit `f934613`.*
*Produced under `doc-provenance-protocol` v1.1.*
