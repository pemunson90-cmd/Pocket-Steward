# Pocket Steward whole-app completion goal

Accepted 2026-10-01. This is the active build specification from dev11; APP_COMPLETION_PLAN.md and MASTER_PLAN_RECONCILIATION.md retain historical/master traceability. Nothing below is a completion claim.

## Goal and baseline

Finish every mandatory capability and acceptance journey in the consolidated master plan. Organize Downloads and its existing 16,000-file Uncertain checkpoint into justified project/category homes. Preserve NSTL, Lilith, Stories and Project Documentation assets under their project with Manuscript, Notes, Drafts, Images, Versions and Archive roles. Ambiguous files stay in Downloads/Uncertain. Protected, inaccessible and conflicting files have visible reasons. Never empty the checkpoint by guessing ownership.

Baseline: 1.4.0-dev11 (51), local commit 2beca08, published app-completion source 534e0483b3319940249091a3bc5b696e2fb29fa4. 578 app tests and six release safety tests passed. Canonical signer, arm64/offline APK and public ZIP/APK hashes verified. Independent GitHub validation passed. Physical-phone acceptance remains open.

## Execution rules

Keep the goal open until mandatory requirements pass their stated acceptance and conditional requirements receive explicit dispositions. Continue independent implementation when phone access/model credentials are unavailable. Preserve existing passing behavior. Publish meaningful checkpoints after relevant gates pass, rather than declaring the whole app finished after a checkpoint.

All mutations follow typed plan -> whole-plan validation -> exact review/approval -> durable task -> foreground executor -> journal/recovery/undo. No permanent deletion, unattended mutation, model filesystem authority or overwrite. Protection outranks inference. Preserve source baselines across review edits and continuation. Signing credentials stay outside source/backups; optional model credentials are separate.

## Ordered build stages and work packages

| Stage | ID | Deliverable | Gate |
|---|---|---|---|
| 0 | REG-01 | Current requirement register with implementation, reachable UI, automated evidence, device evidence and blocker for every master section/appendix. | No lost or silently deferred requirement. |
| 1 | SCALE-01 | Shared review budget: initially 200 fresh image analyses and 40 fresh image OCR inspections; retain existing PDF limits. Valid caches do not consume fresh budget. | 16k synthetic admission tests prove bounds, continuation fairness, cancellation and truthful coverage. |
| 1 | SCALE-02 | Deferred/failed/cached counts, continuation preserving assignments/deselections/baselines, durable attempt progress and lazy UI. | Restart does not starve unseen files; changed sources cannot gain new approval baselines. |
| 1 | SCALE-03 | Cold/warm benchmarks at 1k, 4k, 16k and many competing homes; fix repeated corpus searches and batch IO. | Record scan/index/plan time, memory and responsiveness; synthetic results remain separate from phone measurements. |
| 2 | CACHE-01 | Affected-directory add/change/remove refresh, coalesced observation, periodic full reconciliation and own-mutation invalidation. | Unchanged rerun reuses enrichment; one-file changes do not re-extract unrelated content. |
| 2 | CACHE-02 | Bounded suspicious-change fingerprinting, live checks before reuse/after extraction, overlapping memberships and affected cohort/project invalidation. | Changed or revoked sources cannot supply current evidence; caches hold no mutation authority. |
| 3 | EVID-01 | One reusable evidence inspector: metadata, inspection coverage, page/OCR excerpts, package/archive, ownership, correction, cohort and destination reasons. | Filing/Search/Ask/Coherence share explainable evidence and contradiction handling. |
| 3 | LEARN-01 | Consistent learned-rule precedence and scope; learn approved choices only; expose edit/remove and conflict handling. | Conflicting owners stay reviewable; remembered rules do not grant destination authority. |
| 3 | HOME-01 | Custom-role/additional-root project discovery with resumable coverage beyond first 200 candidates; ranked full paths. | Existing homes preferred; ambiguous homes not chosen by list order; grants respected. |
| 3 | TOPIC-01 | Grounded document-topic classification and reusable topic templates while preserving project ownership above type. | Generic names resolve from actual evidence; no invented project titles. |
| 4 | RELEASE-01 | Learned conventions and complete VERSIONED/PROJECT_ROLES release grouping including meaningful dev/alpha/beta/rc/build identity. | APK/archive/handoff/notes/images retain one justified project/release identity. |
| 4 | EDIT-01 | Destination/home/release edits, reassignment/splitting/keep-in-place and exact review-session persistence. | Each edit rebuilds operations, authorization and validation; selection and original source baselines survive. |
| 5 | SAF-01 | Folder/bundle filing, consolidation, roles and release conventions within authorized selected-folder grants. | No sibling access outside grant; unsupported destinations explained with access setup. |
| 5 | SEARCH-01 | Additional authorized trees/volumes and supported search-selection-to-review flows. | Reuse shared verified evidence; read-only factual answers remain read-only. |
| 5 | FLOW-01 | Per-recipe evidence/selection/destination preferences plus optional Ask text; compatible recipe migration. | Every recipe scans fresh storage and never stores operation authority. |
| 5 | ENTRY-01 | Organize Downloads/Sort Uncertain launcher entry, cold-start/permission routing and all feature integrations. | Sharing/scheduling/duplicates/similarity/versions/consolidation reach the common task pipeline. |
| 6 | ARCHIVE-01 | Bounded RAR/7z metadata inspection with maintained Android-compatible parsers and precise format-version support. | Damaged/encrypted/oversized headers stay safe/partial; archives remain intact. |
| 6 | MODEL-01 | Provider-independent external/local-runtime semantic adapters, Settings configuration/test/status/consent, protected credentials and fallback. | Bounded/schema-validated advice only; deterministic core remains usable. |
| 6 | MEDIA-01 | Better capability/download detection and optional richer image captions distinguishing observations/OCR/generated descriptions. | Backend unavailable state is honest and tested. |
| 7 | ACCEPT-01 | Full acceptance matrix, failure fixes, measured phone behavior and final tutorial. | Every mandatory row accepted; conditional rows explicitly resolved. |
| 7 | REL-01 | Canonical signed source-matched update and immutable verified download through existing tools. | App ID/signer/version/ABI/permissions/provenance/download hashes all verified. |

## Stage detail

Large reviews use cached evidence first and prioritize previously deferred/unseen sources over repeated failures. Show examined, cached, freshly inspected, deferred, unreadable and unresolved separately. Cancellation acknowledgement target is two seconds: late noncancellable vision work may finish safely but cannot publish results or initiate new inspections. Candidate work and UI rendering remain bounded; continuation retains the original review source allowlist, assignments, selections and baselines.

Incremental refresh reconciles changed folders against existing inventory. Full scanning remains available for explicit refresh, incomplete coverage and recovery. Handle event bursts/continuous downloads, process termination and move/undo changes. Fingerprints for suspicious content changes remain independent from executor integrity checks. Preserve existing metadata/document/image caches and overlapping scopes.

Evidence inspection is shared by Filing/Search/Ask/Coherence. Learning occurs only after approved choices. Broader discovery preserves role spelling/custom layouts and ambiguous full paths. Rich project entity/schema evolution is conditional on demonstrated need, not a prerequisite to replace working settings.

Release organization preserves existing naming conventions and meaningful suffixes. For justified releases, project-role homes may contain Versions/<release> with build artifacts and Notes/Images/Archive below it. Material without a justified release retains ordinary project roles. Intact source folders stay intact unless reviewed consolidation is requested. Edits and continuation always rebuild/validate typed operations.

Selected-folder access never implies authority outside a saved grant. Recipes store preferences, not executable plans. Direct launcher entries, search selections and scheduled suggestions open current reviewed proposals. Factual Ask and DocumentsProvider browsing remain intentionally read-only.

Optional models use a clearly labeled connected build variant; the ordinary core APK continues to omit INTERNET permission. Settings owns configuration, consent, connection testing, availability and encrypted credentials. Credentials are excluded from backup/log/source artifacts. Models receive bounded excerpts and return validated IDs/citations, not arbitrary paths/operations. Preserve deterministic fallback and cancellation.

Archive support declares tested format versions, byte/entry/header/memory limits and partial/error states. Optional captions require a compatible backend and remain distinguishable from observed labels/OCR.

## Acceptance register

| ID | Journey | Required evidence |
|---|---|---|
| QA-16K | Actual user corpus | Correct ownership/bundle retention, useful coverage, justified uncertainty, timing, peak memory, responsive controls. |
| QA-DEFECTS | Five reported defects | Retest Coherence-review crash, cancel/back restart, opening dead space, repeated scans, lost review state; record build/device and fix reproduced failures. |
| QA-LIFE | Lifecycle | Pause/cancel/navigation/rotation/process death/reboot with selections and no duplicate execution. |
| QA-STORAGE | Access modes | Direct/selected-folder, overlaps/multiple volumes, revoked/regranted permissions, expired share grants. |
| QA-MUTATE | Mutation safety | Collisions/source changes/interrupted copies/moves/low storage/competing tasks/recovery/cross-root undo. |
| QA-MODELS | Evidence/backends | Generic names/contradictory projects/unavailable models/OCR failures/partial coverage/privacy switches. |
| QA-BACKGROUND | Observation/scheduling | Real Android events, continuous bursts, process termination, battery/thermal behavior. |
| QA-DATA | Persistence | Upgrade migrations/settings restore/cache clearing preserve task/journal authority. |
| QA-UI | Usability/accessibility | Fold/split/large text/screen reader/clear progress and long-operation controls. |
| QA-FEATURES | Connected journeys | All supported NL examples/searches/recipes/notifications/sharing/protection/versions/duplicates/consolidation. |

Controlled synthetic tests establish controlled behavior; phone measurements establish phone acceptance. Instrumentation compilation is not execution. Missing phone access leaves acceptance open while independent work continues.

## Conditional dispositions

AppFunctions remains conditionally deferred while SDK/invocation maturity is insufficient; recheck official availability at the integration gate. Separate project database evolution remains conditional on proven data-model needs. Optional model/caption backend unavailability must be surfaced/tested; it cannot silently close mandatory deterministic organization requirements.

## Release gate

Run relevant regressions and full app tests, lint, debug/instrumentation compilation, exact SQLite checks and release-tool tests. Execute available instrumentation/device checks and record actual status. Increment version code; sign canonically; verify app ID/certificate/version/ABI and edition permissions. Commit/publish verified app tree, package provenance/tutorial, retrieve the public ZIP, verify both ZIP/APK hashes and record remaining blockers/next starting point. Use BUILD_AND_RELEASE.md and the checked-in tools.

The goal closes only after every mandatory register row passes and final signed build/source/download/tutorial are published. A checkpoint release does not close the goal.
