# Pocket Steward: whole-app completion plan

Updated 2026-10-01. Audited baseline: dev6, source f19050817efcd776291d047eafc4e00ac0490e82. This supersedes the narrower dev7 plan. These are planned changes, not features already shipped.

## Definition of finished

Organize Downloads and its existing Uncertain checkpoint into relevant sibling main folders and project subfolders, keeping related documents, images, release artifacts and existing folders together. Documents/<project> supports Manuscript, Notes, Drafts, Images, Versions and Archive. Non-project media has meaningful category homes. Downloads/Uncertain retains only files for which the app cannot justify a destination. Emptying it by inventing destinations is not success.

Every planned feature must have a working user journey, visible limitations, meaningful validation and an explicit disposition. A feature is not complete merely because its detector, screen or data model exists. Inspection screens can remain inspection screens when that is their purpose, but their findings must connect to relevant reviewed actions. Similarity never independently authorizes deletion. Permanent deletion, unattended file mutation and cloud-dependent organization remain outside the original authorized product scope.

## Audit method and evidence

Compared the foundational Android file-agent plan (including section 25), V1.1 completion ledger, inbox/project-filing ledger and smart-organization specification against dev6 source. Historical milestone TODOs are not treated as current defects. The V1.1 ledger's word “Complete” is implementation evidence, not proof of integration or target-phone acceptance.

Confirmed source anchors:
- `ui/scan/ScanViewModelFiling.kt`: existing Uncertain is excluded; filing reads cached text segments rather than ensuring content has been extracted; serial per-file metadata/content queries.
- `filing/InboxFilingEngine.kt`: project matching and supporting-asset inference include repeated full-list searches.
- `ui/scan/PlanPreviewScreen.kt`: expanded group cards and nested item rendering need bounded rendering at large scale.
- `ui/scan/ReviewScreen.kt`, `SimilarReview`: similar groups can open files, but have no keep/archive/organization workflow.
- `image/ImageUnderstanding.kt`: local labels and filename screenshot hints exist; they are not full image descriptions or a complete project-filing evidence pipeline.
- `ui/share/ShareIntakeActivity.kt`: single-file classification/save-copy flow; copy runs directly in its activity callback instead of the journaled executor workflow.
- `semantic/SemanticGroupingEngine.kt`: separate semantic grouping logic needs reconciliation with project-first filing and ambiguity handling.
- `data/AppDatabase.kt`: migration 3→4 exists; old destructive-migration concerns are not an outstanding implementation gap.

## Full feature disposition

“Implemented; verify” means preserve the capability and exercise its complete journey during acceptance. “Partial” means source exists but the promised integrated result is incomplete. “Planned extension” means an explicit original-plan item is retained in this backlog, not silently dropped.

| Feature family | Baseline disposition | Work required / completion evidence |
|---|---|---|
| Downloads and existing Uncertain | Partial; checkpoint excluded | Separate Sort Uncertain entry, same shared planner, restartable checkpoint review; no nested Uncertain/Uncertain. |
| Content-based document/project classification | Partial | Ensure bounded local extraction/OCR where useful, combine title/content/aliases/structure evidence, show conflicts and unsupported extraction. Generic filenames must be sortable from real evidence. |
| Project homes and role hierarchy | Partial | Ranked existing-home picker, editable project/role assignments, aliases, destination discovery coverage; preserve mixed bundles and whole folders. |
| Project vocabulary and correction learning | Implemented pieces; integrate | One consistent correction precedence across filing/semantic workflows; group assignment can teach a rule with scope and explanation. Conflicting rules remain reviewable. |
| Ambiguity review | Partial | Cluster by evidence, bulk assign project/role, searchable groups, representative samples, leave unresolved deliberately. No need to tap 16,000 rows. |
| Large-library scan/index/planning | Partial; real 16k acceptance outstanding | Batch queries, indexed matching, bounded concurrency, streaming progress, cancellation/resume and memory limits. Distinguish files, folders and descendant counts. |
| Clear organization preview | Partial at scale | Collapsed lazy groups, List/Now/After, friendly paths, reasons/confidence, selected totals, sticky controls, visible collisions and changed-source refusals. |
| Image labels, descriptions and screenshot detection | Partial / description extension | Feed usable local image evidence into filing; distinguish labels from captions; pixel/metadata-supported screenshot detection; disclose model availability. |
| EXIF/event filing | Planned extension | Optional metadata-based event/date groups, with missing/incorrect EXIF handled visibly and project evidence taking precedence. |
| Music/video tag filing | Planned extension | Artist/album/event metadata proposals through the same preview/executor, with explicit fallbacks. |
| Document-topic and custom hierarchy templates | Planned extension | Create/edit/reuse templates; preview resulting folders and role mappings; validate paths, collisions and permissions. |
| Archive inspection beyond ZIP | Planned extension | Declare supported formats, bounded inspection and failure states; use evidence without silently unpacking archives or separating bundles. |
| Exact duplicates | Implemented; verify integration | SHA-proven candidates, keeper explanation and reviewable recoverable quarantine; preserve originals needed by project/version history. |
| Near duplicates | View-only result | Compare previews/text, choose keepers, propose archive/move or explicit recoverable Trash through approval; similarity alone never treats files as byte-identical. |
| Version chains and archive decisions | Implemented components; verify integration | Expose coherent project version review and actions; never equate an older draft with an unwanted duplicate. |
| Duplicate project-home consolidation | Planned extension | Review a proposed merged home, resolve same-name/different-content conflicts, preserve structure, execute and undo safely. |
| Storage-wide project knowledge | Planned extension | Durable local registry of authorized project homes/evidence, incremental refresh, explicit incomplete coverage and permission revocation handling. |
| Natural-language commands and follow-ups | Implemented; verify integration | All supported commands lead to useful results/plans; refer to current scan, respect destination scope and shared project evidence. Unsupported requests explain available alternatives. |
| On-device model and deterministic fallback | Implemented optional boundary; verify | Model availability/download/status visible; offline filename/metadata/content rules remain usable; model output passes typed validation. |
| Search, Ask and inspection | Implemented; verify connected journey | Search/open/source citations remain useful; relevant selected results can enter supported reviewed organization flows without losing scope or provenance. Read-only factual answers remain intentional. |
| File browser, multi-root inventory and protections | Implemented; verify | Direct/SAF selection, overlapping roots, favorites, protected folders, refresh and revoked permissions work consistently. |
| Selected-tree organization | Partial relative to direct inbox parity | Explicitly support or visibly bound folder/bundle workflows; no hidden claim of sibling access outside a granted tree; validate actual Samsung provider behavior. |
| Typed operations, validation and editing | Implemented; preserve | Create/move/copy/rename/Trash/write-text and edited destinations retain dependencies, collision refusal, scope authorization and preconditions. |
| Execution, history, recovery and undo | Implemented; verify new paths | Every new mutation uses durable reviewed plans and journal; interruption, changed files, partial copy, no overwrite and undo are covered. |
| Saved workflows/searches/preferences | Implemented; verify | Reuse on a fresh scan, expose edit/delete and stale destination handling; project templates fit the same settings model. |
| Scheduled suggestions and stale inbox detection | Implemented suggestions / planned filing extension | Discover new/stale inbox items, deduplicate notifications, open a current review plan; never run unattended mutations. |
| Background inbox watching | Planned extension | Battery-conscious incremental observation with permission-aware fallback; surfaced status and review-only notifications. |
| Export/import | Implemented; verify | Inventory, task manifests and problem sets export usable data; reviewed import validates current sources/permissions/collisions, never blindly replays old paths. |
| Share sheet | Partial | Multi-file/project-aware intake, destination suggestions, background verified copy and recoverable failure through the approved execution architecture. |
| Home-screen actions | Implemented basic shortcuts; extend | Direct Organize Downloads / Sort Uncertain entry, correct navigation after cold start and permission prompts. |
| DocumentsProvider | Implemented intentionally read-only; verify | Browsing/opening works with authorized indexed content; writable provider is not required by the original plan. |
| AppFunctions | Explicit maturity-dependent item | Recheck stable SDK usefulness at integration gate. Implement safe bounded entry points if viable; record concrete blocker if still experimental. Never claim this implemented. |
| Settings/privacy/status | Implemented pieces; verify | Clearly show storage grants, local content/image inspection, model/index status, scheduling and destination configuration; no environment-variable setup expected from the phone user. |
| Phone usability and release readiness | Unverified for new completion build | Fold closed/open, split screen, accessibility, rotation, low storage, revoked/regranted access, reboot and background work; signed update installs over dev6 without losing state. |

## Delivery sequence

### 1. Complete the core organizing journey

Implement checkpoint intake, bounded fresh content enrichment, shared project evidence, group assignment and ranked destination selection together. Connect correction learning and visual review. Add accurate indexing/classification coverage and explain why each unresolved group remains unresolved. This phase is not accepted merely because the checkpoint has been rescanned.

### 2. Make that journey usable at 16,000 files

Remove quadratic matching and per-row linear operation lookup; batch database access; render only visible rows; persist progress and choices. Run a 16,000-file corpus containing generic names, multiple projects, mixed document/image bundles, conflicting evidence, duplicates and changed sources. Record scan/index/plan time, memory and interaction responsiveness on stated hardware; establish target-phone limits before declaring scale verified.

### 3. Finish view-only and disconnected workflows

Connect similarity review, image evidence, version review, search selections, share intake and scheduled filing suggestions to validated plans. Reuse the executor instead of creating alternate mutation paths. Finish direct/SAF scope parity where permissions permit, and show precise limitations where they do not.

### 4. Finish the explicitly planned extensions

Deliver configurable hierarchy/topic templates, EXIF/event and media-tag filing, additional archive support, project-home consolidation, incremental project registry and inbox observation. Each needs an accessible UI path, preview/explanation, failure behavior and acceptance case. The maturity-dependent AppFunctions item receives a documented decision rather than being lost from the inventory.

### 5. Verify and ship a coherent completion build

Complete the cross-feature matrix, required unit/lint/instrumentation checks, performance measurements and device acceptance. Build with the canonical signer, verify its certificate, publish source and a working APK download, and provide an updated tutorial reflecting exactly what ships. Keep every unfinished row open until verified or explicitly resolved with the user.

## Acceptance gates

1. Reopen an existing Downloads/Uncertain with 16,000 files. Proposals use actual evidence; unresolved files stay there; related assets reach the same project home and appropriate role folders.
2. Generic filenames with clear local document content resolve correctly. Two competing projects, unreadable documents and unsupported formats remain visible and safely unresolved.
3. Project examples NSTL, Lilith, Stories and Project Documentation retain their manuscripts, notes, drafts, images and versions together; arbitrary folders remain intact unless a reviewed restructuring is requested.
4. Select/deselect a group, change its project/role, remember an assignment and revisit the plan after interruption. Counts and preview match executed operations.
5. Every actionable screen produces an ordinary validated plan; no new direct mutation shortcut bypasses journaling, recovery or undo.
6. Test interruption before/after mutation, revoked grants, destination collisions, source changes, low storage, background/reboot recovery and full undo. Never overwrite conflicting content.
7. Verify direct sibling destinations and selected-tree constraints separately on the target phone. Record failures rather than calling unavailable access complete.
8. Feature acceptance register lists each table row, its reachable screen, automated evidence, device evidence and remaining blocker. Green tests alone do not close device-only rows.

## Existing capabilities to preserve

The dev6 baseline has 413 passing unit tests and passed lint/build/signature verification. Those checks do not establish completion of this plan or physical-phone behavior. Existing scan/content-index resumability, SAF operations, exact duplicates, journal recovery, undo, privacy boundaries and export/import should be reused, not rebuilt unnecessarily.
