# Active completion register

Updated 2026-10-01. [GOAL_APP_COMPLETION_SPEC.md](GOAL_APP_COMPLETION_SPEC.md) is the accepted build specification. This register records implementation and acceptance separately; a checkpoint release does not close the app-completion goal. Historical feature traceability remains in MASTER_PLAN_RECONCILIATION.md and APP_COMPLETION_PROGRESS.md.

| Package | Current state | Remaining acceptance/work |
|---|---|---|
| REG-01 | Implemented | Keep this register current through the final acceptance matrix. |
| SCALE-01 | Implemented for dev12 | Bounded 200-image/40-OCR admission, cached evidence, cancellation and failure fairness tested on the host JVM. Run actual Android ML/device checks. |
| SCALE-02 | Implemented for dev12 | Continue/retry actions preserve intake, explicit assignments, release/home edits, deselections and original baselines. Checksummed private progress journal and matching draft revisions survive restart. Physical lifecycle acceptance remains open. |
| SCALE-03 | Partly implemented | Cold/warm 1k/4k/16k engine/adaptor benchmarks with 200 homes; scanner/index/IO, memory and UI/device measurements remain open. |
| CACHE-01 | Implemented for dev13 | Coalesced affected-directory inventory, overlapping memberships/deletions, private resumable slice checkpoints and durable own-move/undo invalidations. Private Android data/obb excluded with visible explanation. Physical Android event/battery/lifecycle acceptance remains open. |
| CACHE-02 | Partly implemented | Implemented for dev14: document-cache byte samples are verified before reuse and after extraction, and filters Search/Ask/Coherence/PDF admission through the same proof. Image/archive cache samples, observed-write revisions and affected-cohort invalidation remain open; sampling does not prove equality for unsampled middle bytes. |
| EVID-01 | Open | Reusable evidence inspector shared across Filing/Search/Ask/Coherence. |
| LEARN-01 | Partly implemented before dev12 | Shared terms/conflict policy exists; complete approved-choice learning and scope/edit/remove journeys. |
| HOME-01 | Partly implemented before dev12 | Standard direct layouts verified; custom roles, additional roots/grants and resumable discovery beyond 200 remain. |
| TOPIC-01 | Open | Grounded document topic classification and reusable topic templates. |
| RELEASE-01 | Partly implemented before dev12 | Explicit release assignments and existing VERSIONED conventions exist; automatic PROJECT_ROLES/mixed release conventions remain. |
| EDIT-01 | Partly implemented | Assignments/release splitting and destination edits exist. Dev12 preserves those choices across continuation and guards draft revisions; complete keep-in-place and all edit/lifecycle journeys. |
| SAF-01 | Open | Intact-folder filing and project consolidation parity inside granted trees. Dev12 also fixes the scheduled SAF source allowlist. |
| SEARCH-01 | Open | Additional authorized trees/volumes and selected-search-to-reviewed-organization flows. |
| FLOW-01 | Open | Per-recipe evidence/selection/destination preferences and migration. |
| ENTRY-01 | Open | Organize/Sort launcher actions and cold/warm/permission routing. |
| ARCHIVE-01 | Open | Bounded RAR/7z metadata support and precise version coverage. |
| MODEL-01 | Open | Optional connected edition, configured semantic providers/local runtimes, protected credentials and fallback. |
| MEDIA-01 | Partly implemented before dev12 | Grounded local labels/OCR/descriptions exist; improved capabilities and optional richer caption backend remain. |
| ACCEPT-01 | Open | All physical-phone rows in the accepted specification remain unverified. |
| REL-01 | Published dev13 checkpoint | Local gates, canonical signing, verified app-tree publication and public ZIP/APK retrieval passed. Final whole-app acceptance/release remains open. |

AppFunctions remains conditionally deferred pending SDK/invocation maturity. A dedicated project database remains conditional on demonstrated need. Optional backend availability must be explained and tested; it does not replace deterministic organization.
