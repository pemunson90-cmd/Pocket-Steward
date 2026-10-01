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

473 unit tests passed with no failures or errors. lintDebug and assembleDebug passed on the final dev7 source. The canonical signed release build passed, its certificate matches the installed dev6 signer, and its version is 1.4.0-dev7 (47). INTERNET permission remains absent.

The synthetic 16,000-file test covers project ownership, probable supporting assets and selection. Separate cases cover competing cohorts, checkpoint retention, scanner resume, metadata freshness, templates, sharing, protection and collisions beyond the old destination cutoff. This is not a measurement of all indexing/IO/UI stages on the user's phone.

## Remaining completion work

- Durable preview/edit restoration after process death; incremental content coverage across overlapping roots; smaller bulk PDF/OCR budgets and evidence coverage reporting.
- Unified evidence/correction learning across semantic and deterministic workflows; explicit release-folder conventions and release/group split editing.
- Expanded archive formats where safe; richer grounded image descriptions and project/topic template coverage.
- Persistent storage-wide project knowledge and responsive inbox observation with permission-aware background fallback.
- Backup/restore of app configuration and safe history; optional configured provider and compatible local-runtime adapters with a deliberate network-edition/privacy design.
- AppFunctions maturity decision and any viable bounded integration; remaining shortcut/integration coverage.
- Current-phone acceptance: full 16k corpus timing and memory, navigation/restart/cancel, Fold/split/accessibility, revoked grants, low storage, thermal/battery, reboot/recovery, migrations and cross-root undo.
- Reproducible release/CI automation and final end-to-end tutorial updates.

No emulator or target-phone run was available in this environment. Every remaining row stays open until implemented and verified or explicitly resolved with the user.
