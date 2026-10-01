# App completion implementation progress

Updated 2026-10-01. This is an implementation checkpoint, not a declaration that the entire master plan or target-phone acceptance is finished. APP_COMPLETION_PLAN.md and MASTER_PLAN_RECONCILIATION.md remain the full requirement inventory.

## Implemented in dev7

- Separate **Sort Uncertain** intake with indexed parent identities. Unresolved checkpoint items stay in place; no nested Uncertain folder. Direct folders remain intact source units. Selected-tree mode currently handles loose files.
- Fresh live source metadata precedes evidence reuse. Changed or unknown modification times invalidate derived metadata; document cache reuse also checks fingerprints when present.
- On-demand content extraction and bounded excerpts feed project matching. Single-document refresh preserves unrelated indexed documents and does not claim a whole root is indexed. Content and image inspection respect their privacy switches; a preview can explicitly enable local content inspection and rebuild.
- Search unresolved names, evidence and excerpts; assign matching batches or destination groups to a known or new project and role. Ranked home choices show full paths, including duplicate project names. Rebuilding preserves prior deselections and reviewed source signatures.
- Configurable project role folders, CATEGORY hierarchy, and reusable named templates. Legacy project-home and workflow encodings remain readable.
- Image labels feed reviewed media category suggestions. Image decoding is bounded; unchanged image and metadata evidence is cached privately. Camera dates suggest event folders; embedded artist/album tags suggest music or movie collections. Category suggestions remain probable and unchecked; project evidence takes priority.
- Metadata-only ZIP, APK-container ZIP, TAR and compressed-TAR inspection with byte/entry limits. Partial or failed inspections are labeled. No archive contents are extracted to user storage. Other archive formats remain unsupported.
- Sorted cohort indexes replace repeated inbox-wide searches. Plan selection and intake avoid repeated operation searches. Destination review cards and expanded file lists render lazily.
- Destination checks inspect affected names and ancestors instead of silently stopping after 5,000 home entries. Case variants of new destinations conflict; case-only renames remain valid. Symbolic nested SAF destinations recognize existing opaque children.
- Scans save/provide progress in batches while keeping directory-level resume checkpoints truthful. Cancellation and resumption do not inflate counts.
- Similar-file review lets the user choose a keeper and preview archiving alternatives. Alternatives start unchecked; similarity is not proof of equality.
- Project consolidation offers a fresh inventory and reviewed merge into a chosen known home. Unmatched folders remain intact; matching folders merge; naming conflicts and known protected bundles stay at source. Empty source folders remain. This currently requires direct storage access.
- Multi-file share intake builds a destination preview and queues verified Copies through the validator, durable task, foreground executor and journal. Originals are retained. Temporary source grants can expire; the UI explains safe recovery limitations.
- Saved workflows distinguish Ask requests, Organize Inbox and Sort Uncertain. They perform fresh scans. Scheduled suggestions use the same project planner and retain an exact new-source allowlist; they never execute unattended. Scheduled/manual walkers share a root lock.
- Transactional task activation prevents simultaneous active mutation/undo tasks.
- Completed previews survive backing out within the app and expose **Resume review**. Filing preparation and assignment have cancellation controls.
- Coherence output is constrained to its current input batch. Duplicate findings produce one row; conflicting duplicates become uncertain. This addresses a concrete crash risk but is not a reproduction of the reported phone crash.

## Verification of this checkpoint

477 unit tests passed with no failures or errors. lintDebug and assembleDebug passed on the final dev7 source. The canonical signed release build passed, its certificate matches the installed dev6 signer, and its version is 1.4.0-dev7 (47). INTERNET permission remains absent.

The synthetic 16,000-file test covers project ownership, probable supporting assets and selection. Separate cases cover competing cohorts, checkpoint retention, scanner resume, metadata freshness, templates, sharing, protection and collisions beyond the old destination cutoff. This is not a measurement of all indexing/IO/UI stages on the user's phone.

## Implemented for dev8

- Private, atomic review drafts restore selections, original source baselines, rich filing presentation and project assignments after process death. No draft is automatically approved or executed. Exact-selection task matching closes the enqueue/delete crash window; stale asynchronous writes cannot recreate cleared drafts. Saving streams JSON to avoid duplicating a large draft in memory. Storage-mode changes invalidate restoration.
- Baselines now cover unresolved loose files too. A later assignment cannot silently recapture changed evidence as its initial review baseline. Selecting an Uncertain root directly works in direct mode; a SAF grant limited to Uncertain explains why it cannot move files out of the checkpoint.
- Group assignments can apply to selected files only and explicitly choose a release folder. Mixed roles stay beneath one release bucket, custom role templates remain effective, intact folders stay intact, unsafe release paths are rejected. Automatic VERSIONED filing preserves an existing release folder's exact v-prefix/spelling; competing conventions stay uncertain.
- Derived content database migration 2→3 adds multiple root memberships without changing the authoritative mutation database. Reuse attaches each observed root; pruning one root retains other memberships and text. Root-filtered search returns a file once and keeps every matching membership.
- Bulk filing uses a bounded extraction profile: 12,000 text characters, eight PDF pages, at most two OCR pages per PDF, and at most 40 fresh PDFs per review. New PDFs precede failed cached PDFs. Full indexing expands filing caches separately. Partial/unverified coverage is recorded and shown. Office archive draining has decoded-byte/entry budgets; PDF source copying enforces a real byte limit and cancellation propagates. OCR bitmap recycling waits for the ML task to complete.

516 unit tests, lintDebug, assembleDebug and assembleDebugAndroidTest passed for dev8 (48). Instrumentation was compiled, not run. The actual derived migration/search SQL passed SQLite tests for overlapping roots, scope removal, legacy coverage flags and FK cascade. Canonical signed dev8 release build passed; the installed-app signer pin matches, version is 1.4.0-dev8 (48), and INTERNET permission remains absent. Target-phone acceptance remains pending.

- Backup/restore now exports a verified settings artifact and up to 1,000 read-only task summaries. The entire input is bounded/validated before a single atomic preference restore. Storage grants, signing/provider keys, active tasks, mutation journals, previews and file contents are excluded. Imported summaries stay separate from live history and expose no execution/undo actions. Background refresh and scheduled reviews stay off after restore. Partial history-save failures are reported separately.
- Durable plan format v4 separates the exact goal from single-line display text, escapes display reasons, and encodes digest tokens. Decoding enforces operation encounter order, rejects machine records before the header and duplicate preconditions, and preserves normal v1–v3 compatibility. Tests demonstrate that goal/reason newlines cannot introduce extra unapproved operations. Task reports recover exact reasons from structured records.
- Review restoration checks the current tree grant as well as storage mode. Draft watermarks and backup histories use metadata-only queries instead of loading all historical operation plans.

## Remaining completion work

- Target-phone acceptance of durable review restoration, overlapping content scopes and bounded bulk extraction; any defects discovered in acceptance remain open.
- Unified evidence/correction learning across semantic and deterministic workflows; broader release convention discovery and selected-tree convention parity.
- Expanded archive formats where safe; richer grounded image descriptions and project/topic template coverage.
- Persistent storage-wide project knowledge and responsive inbox observation with permission-aware background fallback.
- Target-phone acceptance of settings backup/restore; optional configured provider and compatible local-runtime adapters with a deliberate network-edition/privacy design.
- AppFunctions maturity decision and any viable bounded integration; remaining shortcut/integration coverage.
- Current-phone acceptance: full 16k corpus timing and memory, navigation/restart/cancel, Fold/split/accessibility, revoked grants, low storage, thermal/battery, reboot/recovery, migrations and cross-root undo.
- Reproducible release/CI automation and final end-to-end tutorial updates.

No emulator or target-phone run was available in this environment. Every remaining row stays open until implemented and verified or explicitly resolved with the user.
