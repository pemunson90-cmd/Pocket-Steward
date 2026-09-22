# Pocket Steward V1.1 Major Completion Ledger

Updated: 2026-09-22

This ledger maps the live V1.1 branch to the foundational
`POCKET_STEWARD_ANDROID_FILE_AGENT_PLAN_V0_1.md`. It is intentionally
evidence-based: **implemented** is not the same claim as **automatically
tested**, and neither is the same as **verified on the target Fold**.

## Product spine

| Requirement | Status | Evidence |
| --- | --- | --- |
| Local Level-0 inventory | Complete | `FileScanner`, Room index, resumable scan checkpoint |
| Overlapping scan roots | Complete | `FileScope` join table + migration 3→4 |
| Level-1 metadata | Complete | image/EXIF, media duration, APK, PDF page count, ZIP listing |
| Level-2 text/content | Complete | local extractors, PDF/OCR, resumable content index |
| Exact duplicates | Complete | size/fingerprint/SHA-256 cascade |
| Near duplicates | Complete | image perceptual hash + document SimHash, review-only |
| Rule-first organization | Complete | deterministic `RuleEngine` and project keywords |
| Natural-language bounded intents | Complete | organize/find/group/move/copy/rename/archive/duplicate review |
| Follow-up intent refinement | Complete | prior bounded intent refinement inside a scan |
| On-device AI | Complete/optional | Gemini Nano behind `AgentModel`, deterministic fallback remains usable |
| Image understanding | Complete/optional | bundled local ML Kit labels/screenshot hints |
| Typed operation manifest | Complete | create/move/copy/rename/trash/write-text |
| Plan validation | Complete | collision, traversal, recursive move, authorization, no overwrite |
| Preview + arbitrary deselection | Complete | dependency-aware selection, Safe only / All / Clear |
| Preview editing | Complete | move/copy/rename, direct destination groups, selected-tree group editing |
| Deterministic execution | Complete | exact approved durable plan only |
| Write-ahead journal | Complete | PENDING before filesystem call, resolved after result |
| Crash recovery | Complete | forward + undo recovery, ambiguous states become NEEDS_REVIEW |
| Undo | Complete | reverse-order, collision-safe, copy/write undo through recoverable Trash |
| No permanent deletion | Complete | no app path permanently deletes user files |
| Foreground/background work | Complete | foreground service with WorkManager fallback |
| Live task progress | Complete | journal-backed Home/Tasks progress |
| Saved recipes/searches/preferences | Complete | DataStore workflows, searches, favorites, corrections |
| Scheduled suggestions | Complete | periodic review-only WorkManager suggestions |
| Export/import | Complete | verified Markdown/JSON manifests, CSV/JSON inventory, problem set, reviewed-plan import |
| Android integrations | Complete except experimental AppFunctions | share target, shortcuts, DocumentsProvider |
| Offline core | Complete | CI rejects direct INTERNET permission |

## Foundational V1 acceptance test

The canonical request is pinned in automated tests:

> Organize the obvious files by type. Put APKs together, put PDFs and
> documents together, keep images separate, and leave anything uncertain
> alone.

Automated coverage now includes:

1. A synthetic **1,000-file mixed corpus** scanned through the real
   `DirectStorageGateway` into in-memory Room.
2. Deterministic parsing and planning of the canonical request.
3. Validation before mutation.
4. Arbitrary operation deselection with dependency pruning.
5. Real filesystem execution through `PlanExecutor`.
6. Journal/status verification.
7. Full undo restoring all 1,000 original files and removing created folders.
8. Durable pause + resume with a fresh executor instance and no replayed
   journal sequences.
9. An assertion that the app has no direct `INTERNET` permission.

The emulator job runs the full instrumentation suite, not only a compile.

## Failure-injection matrix

| Foundational case | Automated status |
| --- | --- |
| Interrupted scan | Instrumented cancellation → PAUSED checkpoint → fresh scanner resumes |
| After plan approval | Instrumented durable pause/resume |
| Between two moves | Unit-tested committed sequence is never replayed |
| After filesystem mutation before UI update | MutationRecovery tests journal/filesystem reconciliation |
| During undo | Pending-undo recovery tests retry/complete/block outcomes |
| Source modified after approval | Instrumented real-file refusal |
| Destination created concurrently | Instrumented real-file no-overwrite + NEEDS_REVIEW |
| Missing source after preview | Unit-tested rejection |
| Exact/case-insensitive collisions | Unit-tested |
| Recursive directory move | Unit-tested |
| Path traversal | Unit-tested |
| Unicode / emoji filenames | Unit + instrumentation coverage |
| Long filename | Instrumented scanner coverage |
| Huge sparse file | Instrumented 2 GiB metadata scan without payload read |
| Interrupted COPY | Hash-proven recovery tests |
| Interrupted duplicate Trash | SHA-256-proven recovery tests |
| Partial/incorrect write | Hash mismatch becomes NEEDS_REVIEW |
| Provider reports failure but destination exists | Executor stops task for review |

## Selected Android document tree (SAF)

The old "browse-only" limitation is retired on V1.1. The gateway now supports
read, create-directory, verified copy, verified move, rename, write-text,
recoverable Trash and undo inside the granted tree.

Plans use symbolic `FileRef.Child` references for provider-created documents
whose concrete URI does not exist yet. The same references are journaled
durably and used during validation/recovery.

Automated coverage includes selected-tree planning/validation, durable plan
round-trip, symbolic nested destinations and duplicate quarantine proposal.
The remaining requirement here is a **target-device provider run** because a
JVM/emulator cannot prove every Samsung DocumentsProvider quirk.

## Hardware evidence already observed in this project

- Large real scan/index runs on the Fold.
- Background content indexing, pause and resume.
- Indexed content search and previews.
- Gemini Nano coherence/document audit.
- Semantic organization task execution.
- Exact-task journal/undo at thousands-of-operations scale in earlier milestones.
- Closed-Fold layout defects found and fixed.
- Android foreground-service start restriction found on hardware and fixed with
  WorkManager fallback.

## Remaining physical-device acceptance only

These are deliberately not claimed by CI because the condition belongs to a
real phone, power manager, storage provider or external device:

- install the next V1.1 APK over RC2 and smoke the major flows on the Fold;
- lock screen during a long task, then unlock;
- background/foreground during a long task;
- reboot while a durable task is incomplete, then verify recovery/resume;
- revoke storage permission mid-work, regrant, and verify fail-closed recovery;
- deliberately exercise low-battery and low-storage WorkManager constraints;
- SD/USB cross-volume run **if** such storage is available on the test device;
- Samsung selected-tree mutation/undo pass;
- closed/open/split-screen visual sweep after all V1.1 UI changes.

These are the remaining acceptance gates, not missing architecture.

## Deliberate deferral: AppFunctions

The original plan says "AppFunctions when mature/useful." As of September
2026 the AndroidX AppFunctions SDK remains a 1.0 alpha/experimental preview.
Pocket Steward already has share-sheet, launcher shortcuts and a DocumentsProvider.
Adding an experimental system-agent mutation surface is therefore **not a V1.1
completion blocker**. If added later, it must expose inspection/proposal
functions only or route any mutation request back through the existing
validator → preview → durable executor boundary.

## Release rule

Do not call V1.1 final merely because this ledger says "complete." The release
candidate must first pass:

1. full debug + optimized release builds;
2. JVM unit suite;
3. lint;
4. Android instrumentation/emulator acceptance suite;
5. canonical signing/signature verification;
6. the short physical-Fold acceptance pass above.

RC2 remains the rollback build while this branch advances.
