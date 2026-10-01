**Pocket Steward dev7: large Downloads reliability and completion plan**

Requested outcome: organize a real Downloads library of approximately 16,000 files into meaningful sibling folders, preserve related material, and make every remaining exception visible. The next release must earn its claim through scale and recovery testing, not just additional features.

Current baseline: signed 1.4.0-dev6, version code 46; tested application source `f19050817efcd776291d047eafc4e00ac0490e82`, tag `v1.4.0-dev6`. Its 413 unit tests and lint passed. That is not evidence of phone acceptance at 16,000 files. This document plans work; its items are not implemented in dev6.

**1. Confirmed user symptom and first priority**

The user clarified that approximately **16,000 files are already in Uncertain**. The immediate problem is inadequate useful classification and the missing bulk checkpoint workflow. A move into Uncertain is an accounted-for hold, not successful project organization. Planning stalls or an execution crash are not the reported symptom and must not be assumed.

Dev6 explicitly skips existing Uncertain contents during the top-level inbox pass. Repeating Organize Downloads therefore does not revisit these 16,000 files. The next release must accept the existing checkpoint directly and make it practical to resolve it in related groups.

The first deliverable is **Review and sort Uncertain**: discover the existing checkpoint, report its file/folder coverage, build meaningful project/topic proposals from available filename and content evidence, and let the user confirm groups rather than make 16,000 manual decisions. Preserve existing files until their reviewed destinations are approved. Conflicting or genuinely insufficient evidence remains visible in Uncertain; do not force a classification to inflate a success count.

Still to establish during implementation: how much content is indexed, which file types dominate, whether the count includes descendants, and which stable project aliases already exist. These are diagnostic inputs, not reasons to postpone the checkpoint workflow.

Add a local stage report with discovered file/folder counts, items covered by intact folders, eligible/protected/inaccessible counts, available content coverage, proposed project groups, remaining uncertain groups, elapsed stage times, and task outcome. Keep filenames, paths, document content, and prompts out of the default diagnostic summary. A report containing such details should be an explicit separate export.

Reproduce with a populated **Uncertain containing 16,000 files**, not only a fresh Downloads inbox. Include generic filenames, mixed project documents/artwork, archives, existing project homes, conflicting projects, destination collisions, protected subtrees, and nested directories. Test both full-storage and granted-tree access.

**2. Confirmed code issues and limits**

| Area | Evidence in dev6 | Consequence to address |
| --- | --- | --- |
| Matching cost | `InboxFilingEngine` scans the full anchors list per release-less match, and scans the resolved list per possible supporting asset | Worst-case work can grow quadratically; 16,000 squared is 256 million pair comparisons before the rest of planning |
| Review rendering | `FilingDestinationCard` starts expanded, iterates all group items inside a Column, and repeatedly looks up source operation indices with `indexOfFirst` | A large Uncertain or project group can render thousands of rows and repeatedly search the full plan |
| Metadata/content preparation | `ScanViewModelFiling` enriches every top-level item serially and fetches cached document segments per record | Review can wait for slow PDFs, archives, metadata reads, and database requests without useful per-stage progress |
| Scanner batches | `FileScanner` lists and stats a directory's complete immediate contents before persisting its batch and reporting progress | A flat 16,000-file inbox has a long invisible unit of work |
| State size | The filing route loads scope records, builds lists/maps for enrichment, decisions, operations, review groups, and selected indices | Large plans multiply memory use and are not a paged durable filing session |
| Destination inspection | Live destination previews use depth 2 / 5,000-entry bounds for an existing destination root | Bounds must be represented explicitly; incomplete inspection must never imply collision-free storage |
| Selection/completion | Probable matches are unchecked; Strong only clears checkpoint selections; protected/colliding/changed items may remain | A successful selected subset is not an emptied Downloads folder; the app needs an explicit remaining-items account |
| Scope and uncertainty | Full-storage filing treats immediate folders as intact units; granted-tree inbox filing excludes source folders; existing Uncertain is skipped | A count of all descendants differs from move units, and there is no dedicated bulk checkpoint reclassification workflow |

These findings identify implementation weaknesses. The reported symptom is now checkpoint saturation; scale weaknesses still need fixing so that resolving this checkpoint is usable. The reason each file lacked confident project evidence still needs measurement. There is no confirmed general 16,000-file ceiling in the scope-record query; bounded UI views and specific scan paths must not be confused with such a ceiling.

**3. Implementation order**

**P0 — Resolve the existing 16,000-file Uncertain checkpoint**

Add a visible **Sort Uncertain** action on Home and from the checkpoint folder. Use that folder as an explicit reclassification scope rather than hiding it behind the parent Downloads pass. Track original inbox/checkpoint provenance and avoid creating Uncertain/Uncertain or re-quarantining the same files as apparent progress.

Show content-index coverage before semantic enrichment. Reuse indexed text; incrementally extract supported unindexed documents with progress and cancellation. Combine distinctive project names, aliases, document titles/passages, archive entry samples, exact filename families, and explicit corrections. Use compatible on-device semantics where available and a clear evidence-based fallback where not. Generic filenames alone should not prevent content-backed grouping. Protect project ownership from broad topic or extension rules.

Build related-file bundles first, then propose project homes and roles within those bundles. Keep attachments, images, drafts, notes, and versions together when evidence supports that relationship. Present a few group-level decisions with representative files and reasons, while allowing inspection of every member. A user can confirm a project, choose another, split a mistaken group, or hold it in Uncertain. Remember corrections only with explicit user intent. Groups with competing owners must remain held until resolved.

The primary completion metric is useful, correct, user-confirmed filing out of the existing checkpoint, alongside clear remaining reasons. Merely moving unknown items into another hold folder does not pass this requirement.

**P0 — Make 16,000-file planning and review usable**

Create a durable filing session backed by paged database records rather than one enormous in-memory plan. Process discovery, cheap matching, optional enrichment, conflict checking, review, and execution as visible stages. Persist progress so a killed process or paused task can continue safely.

Begin with filename, saved-home, alias, package, existing indexed-text, and repeated-family evidence. Cache normalized project tokens and aliases. Index anchors by project and release/time window instead of comparing every source to every other source. Load text evidence in bounded database batches. Reuse unchanged metadata; postpone expensive content extraction and model work to an optional second stage. Any bounded concurrency must obey cancellation, storage access, and device-memory limits.

Break large flat-directory stat/upsert work into bounded batches while preserving directory-replay safety and scan-generation reconciliation. Do not silently truncate a directory or treat partial discovery as complete.

Move matching, validation, tree projection, and large selection calculations off the UI thread. Precompute source-to-operation lookup and directory dependency maps. Reuse these maps rather than repeatedly searching the full operation list.

Review groups should open collapsed. Expanding a project or Uncertain group loads lazy, paged rows. Now/After should show counted folder summaries and let the user expand relevant branches on demand. Show both source units and descendant file coverage for intact folder moves.

**P0 — Execute safely in resumable batches**

Separate the approved immutable session from execution batches. Batch size is a measured implementation choice, not a UI control the user must configure. Approval covers the exact reviewed source and destination decisions; adding or changing files must not expand that approval.

Retain mutation-boundary protection, no-overwrite checks, reviewed source snapshots, write-ahead journal, destination authorization, process recovery, and undo. Check each live source/destination before its mutation. Reconcile and revalidate folder snapshots at the relevant boundary, not on every preview repaint.

Report moved, checkpointed, excluded, changed, inaccessible, collision-blocked, and still-pending files separately. A failed batch must not block unrelated eligible items indefinitely or silently mark the entire session complete. Pause/resume must not duplicate moves or invent new decisions.

**P1 — Complete project setup and bundle handling**

Add a plain-language project manager with folder pickers, aliases, and a visible layout choice. Users should not need `strategy=PROJECT_ROLES` or raw Android paths for ordinary setup. Discover existing projects in authorized Documents locations and show a proposed new project for approval when needed.

Review by project bundles, not merely by extension. Make the reason for ownership distinct from the reason for a role. Preserve existing folder contents by default. Offer a separate, explicit reviewed merge/restructure workflow when the user wants to combine a bundle with an existing project hierarchy; do not scatter its contents as an incidental side effect.

Polish the P0 Uncertain workflow with accessible project/topic pickers, group search, saved review progress, and user corrections. Keep project evidence, filenames, indexed text, and user corrections available during reclassification. Do not force users to move items back into Downloads to retry.

Use a complete reconciliation summary: every discovered source is either covered by a reviewed intact folder, assigned to a destination, assigned to Uncertain, intentionally excluded, or blocked with a reason. Give the user a review choice for probable matches: confirm the project destination, keep at the checkpoint, or explicitly leave in place. Show the remaining Downloads count before Run and after execution. Never claim the folder is clear while unaccounted items remain.

**P1 — Finish semantic documents and image topics**

Improve role evidence from supported document contents, archive listings, and coherent filename families. Support manuscripts, notes, drafts, project documentation, images, versions, and archive without inventing confidence from file type or arrival time alone. Clarify Archives versus older draft versions, and keep user-approved project ownership stronger than generic media categories.

Connect the existing local image-analysis results to explicit, reviewable topic proposals where enabled and sufficiently confident. Local image labels cannot reliably identify every personal character or project; combine them with project aliases and related evidence, and hold ambiguous material in Uncertain. Keep model-dependent enrichment optional and report unavailable models clearly. Never promise pixel-based personal-project recognition without evidence.

**P2 — Finish feature integration after the filing gates pass**

| Feature | Completion work |
| --- | --- |
| Search and Ask | Visible indexing coverage, missing/unsupported reasons, indexing pause/resume, reliable citations, explicit model-unavailable fallback |
| Document audit | Clearly distinguish sample coverage from full-library coverage; make grouping proposals join the same durable review/execute workflow |
| Exact duplicates and similar files | Consistent keeper controls and recoverable quarantine; keep near-similarity separate from exact duplication |
| Older versions | Better project-aware version chains and visible keeper reasoning; no blind choice of “latest” from dates alone |
| Manual browser actions | Consistent accessible destination pickers, group-level move review, truthful folder-copy limitations |
| Saved workflows and imports | Reuse scope and intent with fresh coverage/validation; retain local correction decisions consistently |
| Scheduling/background library | Metadata/content refresh must not compete indefinitely with foreground filing; scheduled suggestions still require review |
| Exports and diagnostics | Include coverage and skip categories; offer safe summaries by default and explicit detailed exports |
| Delivery | Maintain one canonical signing certificate, increment version code, publish a normal HTTPS download, verify its bytes, and document exact tested source. Never commit signing secrets |

**4. Release gates for dev7**

- A pre-existing Uncertain checkpoint with 16,000 files can be reviewed and sorted directly, without moving files back to Downloads. Generic-named documents containing distinctive NSTL/Lilith/Stories/project-documentation evidence produce correct project proposals; ambiguous documents remain held. Related attachments are preserved.
- Every source in a synthetic 16,000-file run is accounted for exactly once, including descendants covered by intact folders. Add a larger 50,000-file stress fixture to expose scale regressions; do not market that size as supported until measured.
- A 16,000-item Uncertain group and a 16,000-item project group can open, scroll, select by group, and switch views without composing all rows at once or blocking the UI thread. Measure memory and responsiveness on a named reference phone; desktop unit tests alone cannot pass this gate.
- Record timings for discovery, matching, enrichment, validation, review loading, and execution. Establish hardware baselines before publishing timing promises. Cheap matching must show bounded/indexed growth rather than all-pairs growth.
- The same approved 16,000-source plan completes once despite pause/resume or forced process death. Recovery does not repeat mutations, lose journal records, or forget selection.
- Changed, inaccessible, protected, and colliding sources are refused and counted. A collision does not silently overwrite, merge, or break a related folder unit. Partial destination inspection is surfaced and handled safely.
- Mixed NSTL/Lilith/Stories/project-documentation fixtures keep related material together and show correct role proposals. Generic images and documents are not pulled into projects solely because of timing.
- Bulk reclassification from Uncertain can assign a project and remember an explicit correction without resubmitting thousands of manual move steps.
- Undo of an approved large run restores eligible changes and clearly reports any blocked restoration. Old saved plans/tasks remain readable.
- Full-storage mode can file to sibling folders; granted-tree mode never reaches beyond its grant and clearly explains unavailable destinations.
- Clean build, meaningful unit/integration and connected-device checks, lint, canonical certificate verification, installed-dev6-to-dev7 update acceptance, and a tested public download all pass.

**5. Proposed ship order**

Ship a dev7 candidate only after the P0 large-library and recovery gates pass, together with the coverage summary and bulk Uncertain review needed to finish a run. Include richer semantics only as far as they pass their fixture tests. Then schedule a separate follow-up for remaining P2 polish rather than hiding an unresolved 16,000-file filing failure behind new menus.

The confirmed phone symptom is 16,000 files in Uncertain. Content coverage, the reasons for low classification confidence, and reference-device measurements remain open inputs. The tutorial and plan are published on the upgrade branch; they do not alter the installed dev6 APK or merge that development source into main.
