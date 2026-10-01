# Pocket Steward — Master Plan

**Consolidated:** 2026-09-30  
**Status:** Canonical planning synthesis  
**Purpose:** One restart document for the entire Pocket Steward project, reconciling the original architecture, completed milestones, M11–M13 plans, the 1.3.x safety checkpoint, and the current 1.4 Inbox/Project Filing direction.

---

## 0. Provenance and status vocabulary

This document intentionally separates what exists from what is intended.

- **[CANON]** Ratified architectural or safety rule. Do not change casually.
- **[BUILT]** Implemented in a known project lineage.
- **[HARDWARE]** Observed working on Pat's phone.
- **[CURRENT]** Current product direction or active implementation line.
- **[PLANNED]** Intended future work, not yet assumed complete.
- **[DEBT]** Known technical or UX debt.
- **[OPEN]** Unresolved question or acceptance item.
- **[SUPERSEDED]** Earlier direction retained for history but replaced by a later decision.

Primary source lineage:
1. `POCKET_STEWARD_HANDOFF(2).md` — foundational architecture, M0–M7, invariants, debt, original AI direction.
2. `POCKET_STEWARD_NEW_CHAT_HANDOFF_M13_3_20260920.md` — M11/M12/M13 continuation, content indexing, Coherence, durable tasks, destination-aware organization.
3. `M13_AUDITED_BUILD_EXECUTION_PLAN_V1_0_20260920.md` — audited M13 implementation order and risk model.
4. `POCKET_STEWARD_HANDOFF_CURRENT.md` — private 1.3.2-dev1 safety checkpoint and signing continuity.
5. `INBOX_PROJECT_FILING_WORKING.patch` plus later V1.4 project state — Inbox roots, Project Homes, project/release inference, cross-root filing, evidence fusion, review UI.

The live GitHub repository still contains the `build/v1.4-inbox-filing` branch. The exact branch head should be re-verified before development resumes.

---

# 1. Product thesis

[CANON] Pocket Steward is a **personal Android file steward**, not merely a file sorter.

Its job is to take a messy, human filesystem and help Pat answer:

- What is this file?
- What project or body of work does it belong to?
- Is it part of a release/version cohort?
- Where should it live?
- What is safe to move?
- What should stay untouched because confidence is weak?
- What exactly will happen before anything changes?
- Can the change be audited, resumed, and undone afterward?

The end-state is not "AI moves files."

The end-state is:

> **Pocket Steward understands enough context to propose useful organization, then passes every proposed filesystem mutation through a deterministic, inspectable, reversible safety system.**

This distinction is the center of the architecture.

---

# 2. Non-negotiable safety spine

[CANON] All mutation-producing features must preserve this chain:

`intent / evidence`
→ `semantic interpretation`
→ `typed operations`
→ `authorized scope`
→ `PlanValidator`
→ `review / preview`
→ `user selection`
→ `durable task`
→ `foreground executor`
→ `write-ahead journal`
→ `recovery / Resume`
→ `Undo`

No shortcut may allow a model, classifier, recommendation engine, search result, Coherence result, or Inbox filing decision to directly mutate storage.

## 2.1 No permanent deletion

[CANON] Pocket Steward does not permanently delete user files.

"Trash" means moving the file into Pocket Steward's mirrored Trash location.

The one narrow exception remains undo cleanup of an empty directory that:
- the current task itself created,
- is still empty,
- contains no pre-existing user data.

There is deliberately no broad "empty trash" authority in the organizational engine.

## 2.2 Preview is a typed precondition

[CANON] Execution must consume an approved typed plan, not regenerate intent at execution time.

A reviewed plan is not advisory decoration. It is the authorization boundary.

## 2.3 Whole-plan validation happens before execution

[CANON] `PlanValidator` remains a whole-plan pre-pass.

Cross-root work must expand the authorized index rather than weaken validation rules.

## 2.4 Filesystem protection wins

[CANON] Protection is represented by the marker file:

`POCKETSTEWARD-DO-NOT-SORT.md`

Presence is sufficient. Its contents are not parsed.

Protected folders and descendants win over:
- model suggestions,
- Inbox inference,
- project mappings,
- cohort evidence,
- destination policy,
- saved workflows.

## 2.5 Never overwrite

[CANON] Collision handling is fail-closed.

If a destination conflicts, the plan must:
- surface the collision,
- propose another safe destination/name,
- or leave the source untouched.

No "helpful" overwrite.

## 2.6 Uncertainty means leave it alone

[CANON] Unknown is a legitimate result.

If Pocket Steward cannot establish a strong enough project/destination interpretation, the file stays where it is and is surfaced as unresolved.

This is a feature, not a failure mode.

---

# 3. Product operating model

Pocket Steward has four conceptual layers.

## Layer A — Observe

Read-only collection of evidence:
- filesystem metadata,
- filename/path,
- type/extension,
- size and timestamps,
- known project keywords,
- saved user corrections,
- APK metadata,
- archive contents,
- document text/index excerpts,
- OCR provenance,
- image metadata where supported,
- existing folder structure,
- project-home history,
- download-time cohort relationships.

This layer may be expensive, cached, resumable, and incremental, but it does not mutate user storage.

## Layer B — Understand

Pure interpretation:
- classify file type/project,
- infer project identity,
- infer release/version,
- connect artifacts into cohorts,
- detect likely Project Homes,
- identify questionable files with Coherence,
- interpret natural-language organization requests.

This layer should be as pure and testable as possible.

Models can participate here, but output is treated as evidence or semantic intent, not authority.

## Layer C — Propose

Translate semantic conclusions into:
- `CreateDirectory`
- `Move`
- `Rename`
- `Trash`
- `WriteTextFile`
- other typed operations already supported by the executor

Destinations must be selected from deterministic, user-authorized policy.

No model emits unrestricted absolute paths.

## Layer D — Execute safely

The existing durable mutation machinery owns:
- validation,
- review,
- durable task state,
- foreground execution,
- journal,
- interruption recovery,
- pause/resume,
- Undo,
- manifest/reporting.

The AI or semantic layer never duplicates this machinery.

---

# 4. Historical implementation baseline

The milestone history matters because later features sit on safety properties built earlier.

## M0 — Skeleton
[BUILT]

- Android project
- Kotlin / Jetpack Compose
- Room
- DataStore
- storage gateways
- onboarding / permissions
- Home / Settings
- privacy-default test

## M1 — File inventory
[BUILT]

- real scanning
- local file index

## M2 — Deterministic file manager
[BUILT]

- move
- rename
- create directory
- trash
- validator
- executor

## M3 — Journal and Undo
[BUILT]

- write-ahead mutation journal
- PENDING / COMMITTED / FAILED
- Undo executor
- interrupted-run recovery

## M4 — Rules and smart cleanup
[BUILT]

- deterministic classification
- project keyword rules
- duplicate detection
- largest/old-file discovery
- cleanup plan generation

## M5 — Functional completion pass
[BUILT]

- existing features wired into Home
- long-operation working states
- root indexing repairs
- deterministic duplicate keeper selection
- safe fencing of incomplete SAF paths
- read-only Trash review
- `PlanSource` seam for future model planners

## M6 — Transparency and folder protection
[BUILT] [PARTLY HARDWARE]

- task manifests
- visible failure details
- `PARTIAL` status
- root-loose-file default
- protection marker
- arbitrary folder scope
- Undo progress
- large Undo confirmation
- `WriteTextFile`

Hardware history demonstrated multi-thousand-file move and Undo runs with journal recovery.

## M7 — Navigation, picker, visual structure
[BUILT]

- nested nav graph
- shared graph-scoped ViewModel
- folder picker
- folder recents
- spacing/type hierarchy pass
- created-folder summaries

These milestones established the original reliable file-manager substrate.

---

# 5. M11–M13 architecture that remains relevant

Later milestones shifted Pocket Steward from deterministic organization toward contextual organization without abandoning the safety spine.

## M11B — Semantic Plan Bridge
[BUILT / ARCHITECTURALLY CANON]

Coherence or semantic findings can become safe organization plans.

The model does not produce paths.

A deterministic adapter:
- accepts allowed semantic findings,
- resolves them against the current scan,
- sanitizes folder/group names,
- respects protection,
- applies nested-move policy,
- emits ordinary typed operations,
- uses normal validation, preview, durable execution, and Undo.

V1.4 should reuse this philosophy rather than invent a privileged Inbox executor.

## M12A — Saved Workflows
[BUILT / RETAIN]

A saved workflow stores intent/configuration, not old filesystem authority.

Running a saved workflow:
- rescans fresh,
- re-evaluates intent,
- regenerates a fresh plan,
- requires current validation.

Never replay an old move list blindly.

## M12B — Durable Plans
[BUILT / RETAIN]

Approved typed operations are serialized durably.

Interrupted tasks resume from the first unjournaled sequence.

Already journaled work is not replayed.

## M12C — Scan Pause / Resume
[BUILT / RETAIN]

Long scans can pause and resume from durable checkpoints.

Cancellation is not conflated with failure.

## M12D — Foreground Mutation Execution
[BUILT / RETAIN]

Mutation execution can outlive the UI.

The foreground service receives a durable `taskRunId`, not free-form filesystem instructions.

Only one mutation task should be RUNNING at a time.

---

# 6. Content understanding and search

The M13 content architecture remains part of the long-term system even as V1.4 emphasizes Inbox filing.

## 6.1 Separate derived content database
[CANON]

Authoritative mutation/history database:

`pocket_steward.db`

Derived search/index database:

`content_search.db`

The derived index is rebuildable and must never become mutation authority.

Do not introduce cross-database foreign-key coupling that makes the derived cache load-bearing for Undo or journals.

## 6.2 Segment/page indexing
[BUILT / RETAIN]

Content indexing should store:
- per-file metadata,
- extracted segments,
- page numbers when applicable,
- OCR provenance,
- extraction status,
- extractor version,
- indexed timestamp,
- FTS searchable text.

PDF/OCR indexing remains page/segment aware.

## 6.3 Incremental freshness
[CANON]

Skip unchanged content.

Re-extract when:
- file is new,
- metadata indicates change,
- extractor version changed,
- a cheap fingerprint indicates suspicious change.

Remove missing derived rows.

Do not repeatedly extract 17,000 unchanged files because the user reopened a screen.

## 6.4 Resumable background indexing
[PLANNED / PARTLY IMPLEMENTED DEPENDING ON BRANCH]

The intended architecture is a separate read-only foreground service:

`ContentIndexForegroundService`

It should:
- index in the background,
- pause at file boundaries,
- persist durable progress,
- survive process/UI interruption,
- expose completeness,
- let search operate against the already-indexed subset.

It must not receive mutation authority.

## 6.5 Search UX
[BUILT IN M13 LINEAGE]

Expected search model:
- one result card per file,
- best snippet,
- page/OCR context,
- extra match count,
- expandable snippets,
- sort and filter controls,
- saved searches storing query/view state rather than stale result rows.

## 6.6 Coherence integration
[BUILT IN EARLIER LINEAGE, CURRENT UX NEEDS REPAIR]

Coherence is the "does this belong here?" / contextual anomaly tool.

Long-term behavior:
- prefer fresh indexed excerpts,
- directly inspect only stale/missing content,
- allow partial-index operation,
- keep model/document/token bounds safe,
- convert findings into proposals through the semantic-plan bridge.

[CURRENT DEBT] Recent hardware feedback indicates a crash after Coherence Audit when entering review. Treat that as a blocking regression before adding more UX around the same path.

---

# 7. V1.4: Inbox and Project Filing

This is the current product-direction layer.

The user problem is not merely "sort Downloads by extension."

It is:

> Files arrive in transient Inbox locations. Pocket Steward should infer which project they belong to, recognize versions/releases and related cohorts, locate or learn the project's real home, and propose filing them there safely.

## 7.1 Inbox roots
[CURRENT]

An Inbox is a landing zone, not a final organization hierarchy.

Default:
- Android public Downloads

Future/user-configurable:
- additional explicit Inbox roots

An Inbox scan should be optimized for:
- recently arrived artifacts,
- contextual grouping,
- project identification,
- destination discovery,
- low-friction review.

## 7.2 Project Homes
[CURRENT] [CORE CONCEPT]

A **Project Home** is Pocket Steward's durable knowledge of where a project actually lives.

Example conceptual record:

- project name
- destination path
- aliases
- known package IDs
- hierarchy policy
- learned version/release conventions

A Project Home can originate from:
1. explicit user selection,
2. an existing destination discovered on storage,
3. a previously approved filing decision,
4. a strong deterministic mapping.

Approval can teach the app.

A future run should prefer remembered evidence over rediscovering the project from scratch.

## 7.3 Project hierarchy modes
[CURRENT]

Known hierarchy modes include:

- **FLAT** — files live directly in the project home
- **VERSIONED** — artifacts go under a release/version subfolder
- **CATEGORY** — artifacts go under a shallow deterministic type category

Do not create arbitrary deep AI directory trees.

## 7.4 Evidence model
[CURRENT] [IMPORTANT]

Inbox filing is evidence fusion.

Current evidence types include:
- USER_MAPPING
- KNOWN_PROJECT_HOME
- APK_LABEL
- APK_PACKAGE
- APK_VERSION
- FILENAME
- ARCHIVE_ENTRY
- INDEXED_CONTENT
- VERSION_TOKEN
- COHORT_TIME
- EXISTING_FOLDER

Evidence should be inspectable in review.

### Evidence priority

Strongest:
1. explicit user mapping/correction
2. exact known package/project identity
3. known Project Home match
4. strong existing-folder/project-name relationship
5. strong filename/title identity
6. archive/APK metadata
7. indexed content
8. version relationship
9. cohort timing as supporting evidence

Cohort timing must never invent a project by itself.

It may strengthen or attach an otherwise weak artifact only when another artifact has already established the project strongly.

## 7.5 Confidence classes
[CURRENT]

- **STRONG** — safe to preselect for review
- **PROBABLE** — show prominently but leave unchecked by default
- **UNRESOLVED** — leave in Inbox

This behavior should remain visible and predictable.

Do not collapse confidence into an opaque one-number score in the UI.

The evidence matters more than the number.

## 7.6 Release/version inference
[CURRENT]

Use:
- APK version name/code,
- filename version tokens,
- archive entry names,
- project conventions,
- cohort agreement.

Normalize obvious spelling/format variations without erasing meaningful prerelease distinctions such as:
- dev
- alpha
- beta
- rc
- HB-style build identifiers

## 7.7 Existing destination discovery
[CURRENT]

Before proposing a new Project Home:
- search configured/authorized storage for plausible existing project folders,
- compare aliases/project stems,
- prefer a convincing existing home,
- surface ambiguity if multiple homes are plausible.

Avoid manufacturing duplicate project trees because capitalization or punctuation differs.

## 7.8 New Project Homes
[CURRENT]

If evidence clearly identifies a project but no home exists:
- propose a shallow safe home,
- keep it within an authorized storage parent,
- show that the home will be created,
- only remember it after approval.

Project-name sanitization must reject:
- blank names,
- `.` / `..`,
- separators,
- control characters,
- unsafe/absurd lengths.

Unicode remains allowed.

---

# 8. Cross-root filing policy

Earlier root-local behavior was explicitly superseded for document/project organization.

## 8.1 Old behavior
[SUPERSEDED AS UNIVERSAL DEFAULT]

"Everything found in Downloads should organize under Downloads."

This remains valid for some local cleanup operations but is not the correct universal filing policy.

## 8.2 Current behavior
[CANON]

A source root and destination root may differ if the destination is explicitly authorized.

Examples:
- Downloads → public Documents
- Downloads → an existing Project Home
- Inbox A → a remembered project root

No model may invent unrestricted absolute destinations.

## 8.3 Composite authorization
[CANON]

For cross-root preview/execution, build a composite authorized index containing:
- source scan roots,
- explicit approved destination roots,
- relevant live destination children,
- planned directories.

Then:

`typed operations`
→ `composite authorized index`
→ `PlanValidator`
→ `preview`

Do not weaken `PlanValidator` to make cross-root work pass.

## 8.4 Undo
[CANON]

Cross-root Undo restores the original source path.

The journal remains the source of truth for what actually happened.

---

# 9. Destination policy

The M13 destination model should survive into the broader V1.4 filing system.

Possible policies:

- `ROOT_LOCAL`
- `RECOMMENDED_DOCUMENTS`
- `EXPLICIT_FOLDER`
- `PROJECT_HOME` (V1.4 conceptual extension)

For generic document cleanup from Downloads:
- public Android Documents is the recommended semantic destination.

For recognized project artifacts:
- the known Project Home should usually outrank generic Documents.

The UI should allow:
- accept recommended destination,
- choose a known Project Home,
- choose another existing folder,
- create/select a destination,
- keep the file in the current root.

Remembered preference is evidence, not execution authority.

---

# 10. Review UX

Review is the user's control surface, not a legal checkbox before automation.

The screen should answer, at a glance:

- What files were examined?
- Which project does each appear to belong to?
- Why?
- Which release/version?
- Where will it go?
- Does that destination already exist?
- Is Pocket Steward creating a folder?
- How confident is the interpretation?
- Which files are unresolved?
- What will remain untouched?
- Are there collisions or protections?

## 10.1 Group review by destination/project

Prefer groups such as:

**Pocket Steward → 1.4.0-dev6**
- APK
- source archive
- handoff
- screenshots
- notes

rather than an undifferentiated list of fifty independent moves.

## 10.2 Group actions

Planned capabilities:
- change destination for the whole group,
- change/rename proposed release folder,
- select an existing Project Home,
- split a wrongly grouped artifact out,
- deselect individual files,
- leave probable files unchecked,
- reveal evidence,
- surface collision/protection reasons.

After any edit:

`rebuild operations`
→ `rebuild composite authorization`
→ `PlanValidator`
→ `redraw review`

Never patch executable operations in-place without revalidation.

## 10.3 Current UI debt
[DEBT]

Recent device feedback identifies:
- excessive dead space on initial/open screens,
- crash after Coherence Audit when opening review,
- canceled scan restarting rather than behaving like a cached/resumable state,
- repeated scan work where cached/incremental reuse is expected.

The next UX pass should prioritize these before cosmetic expansion.

---

# 11. Caching and incremental behavior

Pocket Steward should stop behaving as if every screen transition causes amnesia.

## 11.1 Filesystem scan cache
[CURRENT PRIORITY]

A completed scan should remain usable until:
- user explicitly refreshes,
- relevant filesystem state invalidates it,
- a mutation task changes the indexed area.

Back navigation must not automatically rescan large roots.

## 11.2 Incremental refresh
[PLANNED / PRIORITY]

Use the existing index as a baseline.

Refresh:
- new files,
- changed files,
- removed files,
- affected directories.

Do not re-enrich metadata for unchanged artifacts.

## 11.3 Metadata enrichment cache
[CURRENT PRIORITY]

Expensive enrichment such as:
- APK inspection,
- archive sampling,
- OCR/text extraction,
- image metadata

should be cached by stable identity plus freshness metadata.

## 11.4 Cohort persistence

A download cohort is derived context, not permanent authority.

It may be cached for UX, but should be rebuilt when relevant files materially change.

---

# 12. Natural-language "Ask Pocket Steward"

[PLANNED / RETAIN]

Natural language should become another `PlanSource`, not a privileged command interpreter.

Examples:
- "Put all the Pocket Steward 1.4 build stuff where it belongs."
- "Clean up these downloaded documents."
- "Find things that belong to NSTL."
- "Show me old APKs but don't move anything."
- "Group these files by project and version."

Pipeline:

user text
→ semantic request
→ deterministic scope/destination policy
→ evidence gathering
→ semantic results
→ typed plan
→ validator
→ review

A language model can interpret intent but does not gain direct filesystem APIs.

---

# 13. Model strategy

[CANON] Pocket Steward must remain useful when no model is available.

Planned model ladder:

1. deterministic/no-model mode
2. on-device Android model adapter where supported
3. optional user-configured provider
4. optional local runtime

Use models for:
- ambiguous project interpretation,
- natural-language requests,
- human-readable naming suggestions,
- content semantic clues,
- Coherence reasoning,
- optionally image description.

Do not use models for:
- direct filesystem mutation,
- absolute destination authority,
- overwrite decisions,
- protection bypass,
- Undo reconstruction,
- journal interpretation.

Model output should be bounded, schema-constrained where available, and fail-closed.

---

# 14. Database boundaries

## `pocket_steward.db`
[AUTHORITATIVE]

Contains load-bearing operational state such as:
- file index metadata,
- task runs,
- mutation journal,
- scan checkpoints,
- Undo/recovery state.

Schema changes require real migrations.

Historical use of destructive fallback was a serious design constraint and should not be reintroduced.

## `content_search.db`
[DERIVED]

Contains rebuildable:
- indexed document metadata,
- extracted text/OCR segments,
- FTS,
- index progress/state.

"Clear content index" may destroy this cache only.

## DataStore
[SETTINGS / LIGHT DURABLE KNOWLEDGE]

Appropriate for:
- UI preferences,
- saved searches,
- recents,
- Inbox roots,
- Project Homes while the scale remains modest,
- correction rules,
- workflow definitions,
- favorite destinations.

If Project Homes become richer relational entities, migrate deliberately rather than letting DataStore become an accidental database.

---

# 15. Known technical debt

## 15.1 Migration history
[DEBT]

Earlier destructive Room migration behavior forced awkward schema side channels.

Future authoritative DB changes need explicit migrations.

## 15.2 Scope overlap / index identity
[DEBT]

Repeated scans of overlapping roots historically risked re-scoping shared file records.

The index model must guarantee that scanning a child does not make it disappear from a parent scope.

## 15.3 SAF
[DEBT]

SAF support historically existed as incomplete/stubbed functionality behind UI fences.

Either:
- implement it completely,
- or formally narrow the product to direct-storage mode.

Do not let a type-checking stub masquerade as a supported backend.

## 15.4 Process death vs UI state
[DEBT]

Durable operation state is stronger than transient result-screen state.

Important review/search/scan state should restore gracefully after process death without requiring expensive full recomputation.

## 15.5 Classification coverage
[ACCEPTED LIMIT / FUTURE OPPORTUNITY]

The deterministic extension classifier intentionally leaves much uncategorized.

That is acceptable because contextual/project understanding is now a major layer.

Do not "solve" it with a giant brittle extension taxonomy unless there is a concrete user benefit.

---

# 16. Current restart point

Before any new feature work:

1. Verify the live head of `build/v1.4-inbox-filing`.
2. Preserve the latest known-green source and installable APK.
3. Confirm canonical signing continuity.
4. Run focused device acceptance on the current V1.4 flow.
5. Reproduce the known review crash.
6. Reproduce cancel/rescan behavior.
7. Confirm Project Home learning and reuse.
8. Confirm existing-destination discovery.
9. Confirm APK, archive, filename, indexed-content, and cohort evidence in review.
10. Confirm cross-root Undo.
11. Only then advance the roadmap.

Do not rebuild completed M11–M13 subsystems merely because their implementation predates V1.4.

---

# 17. Near-term roadmap

This roadmap prioritizes hardware behavior over adding more conceptual machinery.

## V1.4-A — Stabilize the current Inbox Filing path
[CURRENT NEXT]

Goals:
- eliminate Coherence→Review crash,
- eliminate scan restart after cancel/back where cached state should survive,
- preserve scan/review state,
- remove obvious dead-space/layout failures,
- verify existing Project Home reuse,
- verify learned Project Home persistence,
- verify unresolved/probable defaults.

Acceptance:
- scan a realistic Downloads set,
- cancel/resume without unexpected full restart,
- enter/exit review repeatedly,
- no crash,
- no lost scan unless explicitly refreshed.

## V1.4-B — Incremental scan/enrichment
[PLANNED NEXT]

Goals:
- cache scan results,
- identify changed files,
- reuse unchanged APK/archive/content enrichment,
- expose "refresh" vs "use current scan."

Acceptance:
- second run over unchanged Downloads is materially cheaper,
- one new file only enriches the changed cohort,
- one removed file disappears without rebuilding unrelated metadata.

## V1.4-C — Review editing
[PLANNED]

Goals:
- change destination per group,
- choose another Project Home,
- split file from group,
- rename proposed release folder,
- preserve validation after every edit.

## V1.4-D — Better Project Home discovery
[PLANNED]

Goals:
- storage search for plausible existing homes,
- alias learning,
- package-ID learning,
- conflict/ambiguity surfacing,
- avoid duplicate project roots.

## V1.4-E — Saved Inbox workflow
[PLANNED]

Allow a one-tap workflow such as:

"Review new Downloads and file strong project matches."

The workflow stores:
- Inbox roots,
- evidence/selection preferences,
- destination policy,
- optional Ask text.

It never stores stale move authority.

---

# 18. Medium-term roadmap

## 18.1 Indexed Coherence convergence

Make Inbox Filing, Search, and Coherence consume the same derived content evidence rather than running parallel extraction systems.

## 18.2 Unified evidence inspector

A file should have one explainable evidence view:
- filesystem metadata,
- project identity,
- package/archive metadata,
- text excerpts,
- cohort relationships,
- user corrections,
- learned home.

Different features should reuse it.

## 18.3 Project entity model

If Project Homes become central enough, evolve from lightweight settings records to an explicit project entity with:
- canonical name,
- aliases,
- package IDs,
- roots/homes,
- release conventions,
- known artifact patterns,
- user corrections,
- history.

Do this only when the data model genuinely needs it.

## 18.4 Scheduled suggestions

Possible later mode:
- periodically scan Inbox roots,
- prepare a proposal,
- notify that a review is ready.

Do not silently execute organization in the background.

---

# 19. Longer-term roadmap

Earlier planning named later areas roughly M14–M16. Preserve the intent, not necessarily the exact milestone numbers.

## Model/provider expansion

- richer on-device capability detection
- additional optional provider adapters
- local-model experimentation
- provider-independent semantic interfaces

## Media understanding

- image metadata and optional visual description
- richer archive/document inspection
- better audio/video contextual metadata where useful

## Hardening

- lifecycle torture tests
- process-death recovery
- large-volume filesystem tests
- Unicode/path edge cases
- battery/thermal tests
- migration tests
- backup/export
- complete accessibility pass
- signing/release automation

---

# 20. Release and handoff discipline

Every substantial release should leave enough evidence that a fresh session can continue without archaeology.

Record:

- branch
- source commit SHA
- versionName/versionCode
- clean build result
- unit/instrumentation results
- APK filename
- APK SHA-256
- signer certificate fingerprint
- hardware acceptance notes
- known regressions
- next exact restart point

Keep private signing material out of public Git history.

Preserve the canonical signer identity across installable releases.

A private master handoff should carry the signing package forward so it never has to be rediscovered.

---

# 21. Decision rules for future development

When choosing between a clever new feature and preserving the safety/continuity model:

**Preserve the model.**

When a semantic engine wants filesystem authority:

**Translate to typed operations instead.**

When evidence is weak:

**Leave the file untouched.**

When a destination is outside the current scan:

**Authorize the destination explicitly; do not weaken validation.**

When a screen can reuse valid cached work:

**Reuse it.**

When the app cannot explain why it wants to move a file:

**The review experience is not finished.**

When a feature duplicates scanning, extraction, project identity, destination logic, or execution machinery:

**Unify the layer instead of building a second Pocket Steward inside Pocket Steward.**

---

# 22. Product end-state

Pocket Steward should eventually feel less like a file manager with AI bolted onto it and more like a persistent local librarian for Pat's projects.

It knows:
- where files tend to arrive,
- which projects exist,
- where those projects live,
- how their releases are named,
- what evidence ties an artifact to a project,
- what it has learned from prior corrections,
- what is protected,
- what it is unsure about.

It can say:

> These six files appear to be Pocket Steward 1.4.0-dev6. The APK package matches the known project, the archive contains the same project stem, the handoff names the same version, and all six arrived in the same download cohort. Your existing Pocket Steward Project Home is here. I propose filing five strong matches into the 1.4.0-dev6 release folder. This screenshot is only a probable match, so I left it unchecked. Two unrelated files stay in Downloads.

Then it shows the exact operations.

Pat approves or edits them.

Only then does the deterministic machinery touch the filesystem.

That is the product.

---

# Appendix A — Frozen invariants checklist

Before merging a major filesystem feature, answer yes to all:

- [ ] No permanent delete path was introduced.
- [ ] Model output cannot directly call storage mutation.
- [ ] Operations are typed.
- [ ] Destinations are within explicit authorized roots.
- [ ] `PlanValidator` still runs before execution.
- [ ] Review reflects the operations that will actually execute.
- [ ] Protection markers override the plan.
- [ ] Collisions cannot overwrite.
- [ ] Durable task state exists before foreground mutation.
- [ ] Journal/recovery semantics still apply.
- [ ] Undo restores original locations.
- [ ] Process interruption cannot cause replay of committed operations.
- [ ] Weak/ambiguous semantic results can remain untouched.
- [ ] Current authoritative DB data is not exposed to destructive migration.
- [ ] Signing continuity is preserved for installable builds.

# Appendix B — V1.4 evidence acceptance matrix

For each evidence class, create at least one positive and one ambiguity case.

| Evidence | Positive acceptance | Ambiguity/fail-closed case |
|---|---|---|
| User mapping | explicit correction selects correct Project Home | mapping points to missing/unsafe target |
| Known Project Home | alias/path clearly matches project | two homes share similar names |
| APK package | known package ID resolves project | package unknown |
| APK label | label reinforces existing project | generic label does not invent home |
| APK version | release folder inferred correctly | malformed/absent version stays neutral |
| Filename | strong project/release stem | generic `final-build.zip` remains unresolved |
| Archive entries | internal names reinforce project/version | generic archive contents do not dominate |
| Indexed content | text identifies project | unrelated mention does not overpower stronger contradiction |
| Version token | joins known project cohort | version alone cannot invent project |
| Cohort time | weak artifact attaches to one strong cohort | multiple competing cohorts remain unresolved |
| Existing folder | real project destination reused | ambiguous folders surfaced, not guessed |

# Appendix C — Current known user-facing defects to retest

- Coherence Audit → review crash.
- Cancel/back causing scan to run again unexpectedly.
- Initial/open-screen dead space.
- Expensive rescanning where cached/incremental behavior is expected.
- Verify review survives navigation without losing the expensive work that produced it.
