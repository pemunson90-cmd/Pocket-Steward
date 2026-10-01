# Consolidated master-plan reconciliation

Compared 2026-10-01 against dev6 source f19050817efcd776291d047eafc4e00ac0490e82 and APP_COMPLETION_PLAN.md. Source: [master plan consolidated 2026-09-30](POCKET_STEWARD_MASTER_PLAN_2026-09-30.md). This is a planning/code comparison, not a new APK or a claim of physical-device verification.

## Reconciliation decisions

1. Retain the master plan's safety spine and persistent-librarian product thesis. Finish integration, not just extension sorting.
2. Current user instructions supersede earlier unresolved-in-Inbox behavior: initially unresolved loose Downloads may move into the reviewed Downloads/Uncertain checkpoint. Files already in that checkpoint remain there until justified project/category assignment. No arbitrary destination guessing or nested checkpoint.
3. Current project-role hierarchy is additive. Preserve FLAT and VERSIONED. The master also names CATEGORY; dev6's enum contains FLAT, VERSIONED and PROJECT_ROLES only. CATEGORY must be explicitly implemented or reconciled with a named type-category template; PROJECT_ROLES is not silently its equivalent.
4. Reported Coherence review crash and cancel/back restarts are blocking retest items. Their existence in this document is hardware feedback from an earlier lineage, not proof that dev6 currently reproduces them or fixes them.
5. A separate background content indexer, durable progress, scan cache restoration, overlapping-root memberships, migrations and SAF mutations already exist. Retain and test them; do not restart those subsystems from scratch.
6. Optional user-configured providers and local runtimes were omitted from the first completion inventory. Restore them as explicit planned work. Offline/no-model operation remains mandatory; network use must be optional, deliberately configured and visibly consented to before sending filenames/content. The current no-INTERNET release boundary needs an explicit design decision for a provider-capable build, not a hidden permission change.
7. Rich project entities are conditional architectural evolution, not an unconditional requirement to replace DataStore. Track scale/data-model evidence and preserve migrations if escalation is needed.

## Section-by-section traceability

Each row carries all substantive requirements from its master-plan section. Open subrequirements remain open even when another item in the row already exists.

| Master section | Feature/requirement coverage | Baseline finding and completion disposition |
|---|---|---|
| 0 Provenance | Lineage, status vocabulary, live branch verification | Preserve supplied document; use dev6 tag/source SHA and upgrade branch as the current baseline, not the older branch name alone. Record code evidence separately from hardware evidence. |
| 1 Thesis | Identity, project, cohort, destination, explanation, audit/resume/undo | Covered by core organization, shared evidence and execution rows; actual 16k checkpoint outcomes required. |
| 2 Safety | Typed chain, no permanent delete, approved plan, whole-plan validation, marker protection, no overwrite, uncertainty | Existing architecture to retain. Add an explicit invariant checklist below; apply to share, search, similar-file and new provider workflows as well. |
| 3 Layers | Read-only observations, bounded interpretation, deterministic authorized destinations, common execution | Add shared evidence inspector/convergence task; all new actions must use existing typed-plan/executor machinery. |
| 4 M0–M7 | Onboarding/privacy, scan, move/rename/create/Trash, journal/undo/recovery, rules/duplicates/largest/old, working states/root repairs, Trash review, PlanSource, manifests/PARTIAL/protection/scope/undo progress/write-text, navigation/picker/recents/visual hierarchy | Preserve implemented substrate. Include each journey in regression acceptance; historical multi-thousand-file hardware evidence does not verify new dev6 paths. |
| 5 M11–M12 | Semantic bridge/sanitization/nested moves, fresh saved workflows, durable plans/no replay, scan pause/resume, foreground task ID, single running mutation | Implemented architecture; add concurrency acceptance for competing entry points, workflow freshness and lifecycle checks. |
| 6.1–6.2 Content databases | Separate authoritative/derived stores, page/segment/OCR/status/version/time/FTS | Implemented separate stores and segment index; verify cache clearing never touches task/journal authority or creates cross-database coupling. |
| 6.3 Freshness | New/changed/version/fingerprint invalidation and missing-row removal | Policy checks size/mtime/extension/extractor version; stale-row cleanup exists. Suspicious same-size/same-mtime content change detection needs an explicit cheap-fingerprint strategy and test. |
| 6.4 Indexing | Separate read-only foreground service, file-boundary pause, durable progress, interruption, completeness, partial search | `ContentIndexForegroundService` and durable repository exist; device acceptance and coherent coverage/status remain required. Not a missing service implementation. |
| 6.5 Search | Per-file card, best/page/OCR snippet, extra count/expansion, sort/filter, saved query/view state | Existing search family; explicitly verify every listed UI behavior and saved-state restoration rather than closing the whole row on FTS existence. |
| 6.6 Coherence | Fresh indexed excerpts, stale/missing inspection, partial-index operation, bounded model/token/document input, semantic bridge, review crash | Add stabilization gate and shared index/evidence convergence. Retest reported crash before expanding review; no claim of current reproduction yet. |
| 7.1 Inbox | Public Downloads, extra configured roots, recent/contextual optimized scan | Inbox settings exist; include multiple-root workflows and checkpoint intake, not a hardcoded single source path. |
| 7.2 Homes | Explicit/discovered/approved/deterministic homes, aliases/package IDs, hierarchy, learned release conventions | Basic ProjectHome settings exist. Release conventions/history/artifact patterns are not in its current record; extend deliberately with persistence/migration tests. Remember only approved choices. |
| 7.3 Hierarchy | FLAT, VERSIONED, CATEGORY; shallow safe hierarchy | FLAT/VERSIONED and newer PROJECT_ROLES exist; CATEGORY discrepancy explicitly open. User-selected templates govern added depth. |
| 7.4 Evidence | Mapping/home/APK label/package/version/filename/archive/content/version/cohort/folder; priority; inspectability; time alone insufficient | Filing engine implements multiple signals. Add unified inspector plus exact positive/ambiguity matrix; explicit corrections and protection outrank inferred signals. |
| 7.5 Confidence | STRONG preselection, PROBABLE unchecked, UNRESOLVED untouched/checkpoint | Verify strong/probable defaults. Explicitly distinguish initial reviewed checkpoint placement from actual confident project filing. |
| 7.6 Releases | APK version name/code, tokens, archive names, conventions/cohort; dev/alpha/beta/rc/HB retained | Existing inference to verify and extend; add normalization fixtures preserving meaningful prerelease/build identity. |
| 7.7–7.8 Discovery/create | Authorized storage, aliases/stems, existing-home preference, ambiguity, approved shallow new homes, sanitization incl Unicode | Partial discovery/editing; add ranked picker and duplicate-home prevention plus blank/dot/separator/control/length/Unicode cases. |
| 8 Cross-root | Superseded universal root-local default, explicit destinations, composite index/live/planned children, validation, original-path undo | Implemented direct infrastructure; verify cross-root and edited destinations, granted-tree limits and undo. Do not loosen validation. |
| 9 Destination policy | ROOT_LOCAL/Documents/explicit/project home, choose existing/create/keep current, remembered preference not authority | Existing policies/components; complete a consistent selection UI and after-edit authorization refresh. |
| 10 Review | Examined/project/evidence/release/path/existing/new/confidence/unresolved/untouched/collision/protection, groups, destination/release edit, split/deselect, revalidation | Partial group preview/editing; explicitly add release-folder edit and split/move an artifact between groups, with all selections preserved and revalidated. Retest dead space and review navigation. |
| 11 Cache | Valid completed scan reuse, refresh vs reuse, navigation reuse, mutation invalidation, incremental add/change/remove, unchanged enrichment reuse, derived cohort invalidation | Cached summaries exist; transient metadata details such as ZIP samples/APK label are re-enriched. Add freshness-keyed enrichment cache and measured incremental behavior. Restore important review state after process death. |
| 12 Ask | Bounded NL PlanSource for project/release organization, document cleanup, project find, old-APK read-only query, grouping | Implemented bounded intent/model seams; verify all five examples reach the correct read-only result or current validated plan. |
| 13 Models | Deterministic, Android on-device, optional provider, optional local runtime; bounded semantic uses, prohibited authority | First two exist. Optional provider/local runtime adapters added to backlog, with provider-independent schema/availability/error/privacy tests and deterministic fallback. |
| 14 Databases | Explicit authoritative migrations, derived-only clear, settings/recents/inboxes/homes/corrections/workflows/favorites, deliberate relational escalation | Migration 3→4 exists; old destructive fallback is superseded debt. Add upgrade/data preservation and cache-isolation gates for future changes. |
| 15 Debt | Migrations, overlapping identity, real SAF, process-death UI state, limited taxonomy | Overlap membership/migration/backend already addressed in code. Device backend testing, durable review state and evidence-based classification remain open; avoid giant extension taxonomy as substitute. |
| 16 Restart | Verify heads/preserve baseline/signer, current device flow, review/cancel defects, learned/discovered homes, evidence, cross-root undo | Source/build/signer baseline recorded; target-phone acceptance outstanding. Record exact build for every observation. |
| 17 A–E | Stabilize; incremental enrichment; group destination/home/split/release edits; discovery/aliases/packages/conflicts; fresh one-tap inbox workflow storing roots/preferences/policy/Ask | All five roadmap slices retained; add stabilization before core expansion and explicit saved Inbox workflow acceptance. General saved workflows alone do not close this specific journey. |
| 18 Medium term | Shared indexed Coherence, unified inspector, conditional richer project entity, review-only scheduling | Convergence/inspector now named tasks; registry/entity evolution evidence-driven; scheduled suggestions must open current proposals. |
| 19 Longer term | Capability/provider/local runtime, media; lifecycle/process death/volume/Unicode/battery/thermal/migrations/backup/accessibility/signing automation | Provider work restored; add backup/restore design and acceptance, battery/thermal budget, full accessibility and automated signing/provenance release path. Inventory export is not automatically a full backup. |
| 20 Handoff | Branch/SHA/version/build/tests/APK hash/cert/hardware/regressions/restart; private keys excluded; signer continuity | Preserve release/provenance discipline. Private key custody stays outside public docs, source, exports and backup artifacts. |
| 21 Rules | Safety, weak evidence hold, explicit authorization, valid cache reuse, explainability, unify duplicate layers | Apply as design-review gates for each completed feature; do not fork scanning/extraction/execution subsystems. |
| 22 End-state | Persistent librarian, learned project/release/protection/uncertainty, six-artifact coherent example, exact review/approval | Integration acceptance: APK/archive/handoff/screenshots/notes retain one project/release; probable screenshot unchecked; unrelated artifacts checkpointed. |
| Appendix A | Fifteen frozen invariants | Retained individually below and required for each filesystem feature. |
| Appendix B | Eleven evidence classes, positive and ambiguity cases | Retained individually below; require mixed/contradictory cases as well as isolated signals. |
| Appendix C | Coherence crash, cancel/back restart, dead space, repeated scans, review navigation state | All five included in Phase 0 stabilization; historical reports require current-build reproduction/status. |

## Newly explicit tasks and acceptance

- **STAB-01:** Retest Coherence → review on direct and SAF scopes with empty/partial/complete index and model available/unavailable. If reproduced, fix before expanding the path; if not, record exact build/device/cases. Review navigation must preserve the result.
- **STAB-02:** Test pause/cancel/back, return to results, refresh and process restart separately. Valid cached results must not trigger an implicit full scan. Complete vs paused inventory must never be mislabeled.
- **CACHE-01:** Persist expensive enrichment by identity/freshness/extractor version, including presently transient archive samples and APK labels; unchanged second runs demonstrably avoid reinspection.
- **CACHE-02:** Define suspicious-change fingerprinting and derived cohort invalidation. Add/remove/change one file without rebuilding unrelated content; keep live execution preconditions independent of caches.
- **STATE-01:** Restore review/group edits/search view/scan state after lifecycle interruption without regranting stale mutation authority. Fresh authorization, preconditions and whole-plan validation remain mandatory.
- **EVID-01:** One reusable evidence inspector shared by filing, Search, Ask and Coherence, with excerpts/page/OCR context, correction, home, package/archive/version and cohort provenance.
- **HOME-01:** CATEGORY vs PROJECT_ROLES explicit hierarchy decision and codec compatibility; preserve existing homes. Store richer conventions/history only where product evidence requires it.
- **EDIT-01:** Split misgrouped artifact, change home/destination, rename proposed release, keep current location; rebuild plan/composite index and validate after every edit.
- **FLOW-01:** One-tap fresh Inbox workflow retains roots/evidence/selection/destination/Ask settings; never stores old operation authority.
- **MODEL-01:** Optional provider adapters and local-runtime route, provider-independent bounded schema, capability detection, unavailable fallback, explicit data/network configuration and privacy disclosure. No model paths or mutations.
- **HARD-01:** Competing mutation requests, cache clearing, upgrade migration, background/reboot, battery/thermal, accessibility/Fold/split-screen and backup/restore acceptance. Backup design must protect authoritative state and exclude signing secrets; distinguish it from report export.
- **REL-01:** Automate required build/check/signature/provenance/download verification using private credentials outside Git; record hardware status and next restart point.

## Frozen invariant register (master Appendix A)

SAFE-01 no permanent delete; SAFE-02 no model storage mutation; SAFE-03 typed operations; SAFE-04 explicitly authorized destinations; SAFE-05 whole-plan pre-validation; SAFE-06 preview equals executed operations; SAFE-07 marker protections win; SAFE-08 no overwrite; SAFE-09 durable task precedes foreground mutation; SAFE-10 journal/recovery applies; SAFE-11 undo restores original locations; SAFE-12 committed sequences never replay; SAFE-13 ambiguity remains untouched or in explicitly reviewed checkpoint; SAFE-14 no destructive authoritative migrations; SAFE-15 canonical signer continuity.

## Evidence acceptance register (master Appendix B)

| Evidence | Positive case | Ambiguity/refusal case |
|---|---|---|
| User mapping | Correct explicit project home | Missing/protected/unsafe mapping target |
| Known home | Alias/path match reused | Two similarly named homes |
| APK package | Known package resolves project | Unknown package stays neutral |
| APK label | Reinforces known project | Generic label cannot invent identity |
| APK version | Correct release | Absent/malformed version neutral |
| Filename | Strong project/release stem | Generic final-build.zip unresolved |
| Archive entries | Reinforce project/version | Generic entries cannot dominate |
| Indexed content | Identifies actual project | Incidental mention cannot defeat contradiction |
| Version token | Joins known cohort | Version alone cannot invent project |
| Cohort time | Attaches weak asset to one strong cohort | Multiple competing cohorts unresolved |
| Existing folder | Real home reused | Ambiguous homes surfaced |

## Coverage outcome

All numbered sections and both requirement appendices plus the defect appendix are now mapped. This establishes planning traceability, not complete implementation: detailed UI behaviors, historical hardware defects and conditional integrations still require their stated acceptance evidence. Existing completion-plan extensions remain tracked in addition to this master; none is dropped because it is absent here.
