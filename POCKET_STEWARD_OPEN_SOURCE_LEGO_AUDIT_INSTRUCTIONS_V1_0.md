# POCKET STEWARD — OPEN-SOURCE LEGO AUDIT INSTRUCTIONS
**Version:** 1.0  
**Purpose:** Audit the current canonical Pocket Steward M3 implementation against existing open-source Android / agent / local-AI projects before beginning Milestone 4 or writing substantial new infrastructure.

---

## 1. PRIMARY DIRECTIVE

Do **not** begin Milestone 4 implementation yet.

Before writing new functionality, perform an open-source component audit against the current canonical Pocket Steward M3 source tree and determine which planned subsystems should be:

- **KEEP** — current Pocket Steward implementation is already working and should remain authoritative.
- **REPLACE** — an open-source component is clearly superior and should replace an unbuilt or weak current subsystem.
- **BORROW** — Pocket Steward should reuse/adapt selected code, architecture, algorithms, or implementation patterns from an open-source project.
- **DEFER** — no integration is justified yet; leave the current plan unchanged until the feature is actually needed.

The goal is to stop hand-building commodity infrastructure that already exists while preserving the parts of Pocket Steward that are already working, safety-critical, and project-specific.

Pocket Steward should become an **integration project**, not a reinvention project.

---

## 2. CURRENT SOURCE OF TRUTH

Use the newest **working canonical M3 handoff** as the authoritative source tree.

Do not promote an older Milestone 1/2 source tree, old APK, pre-build M3 handoff, or historical archive over the final M3 tree that actually compiled and passed tests.

Historical files are useful for provenance only.

The current architecture that must be preserved unless a concrete defect is found is:

```text
Natural-language intent
        ↓
Agent / planner
        ↓
Typed operation manifest
        ↓
Pocket Steward PlanValidator
        ↓
Human preview / approval
        ↓
Deterministic PlanExecutor
        ↓
Write-ahead MutationJournal
        ↓
Undo / recovery / reconciliation
```

The AI must never receive unrestricted raw filesystem mutation authority.

---

## 3. NON-NEGOTIABLE SAFETY SPINE

These components are Pocket Steward's differentiating safety architecture.

Default assumption: **KEEP**.

Audit them, but do not replace them merely because another project has a file manager.

### Preserve unless a real technical defect is established

- `PlannedOperation` typed manifest
- `PlanValidator`
- validator-owned safety classification
- deterministic `PlanExecutor`
- write-ahead mutation journaling
- `PENDING -> COMMITTED / FAILED` state transitions
- inverse-operation / Undo semantics
- crash reconciliation
- collision-safe Undo
- no permanent deletion
- Pocket Steward trash/quarantine behavior
- human plan preview before risky mutations
- rule that ambiguous files may be left untouched

The model should propose plans.

The validator and executor should decide what is actually allowed to happen.

Do not collapse these layers.

---

## 4. LEGO RULE

Apply this rule throughout the audit:

### If a Pocket Steward component already works
**Keep it** unless the replacement offers a concrete, meaningful advantage.

Do not replace working code merely because another implementation exists.

### If a Pocket Steward component is not built yet
**Search open source first.**

Do not implement from scratch until the audit establishes that no suitable reusable component exists.

### If an open-source implementation is clearly better
Prefer:
1. direct dependency,
2. isolated adapter,
3. small code transplant,
4. architectural borrowing,

in that order.

Avoid copying large subsystems wholesale when a narrow adapter or library dependency would suffice.

---

## 5. PROJECTS TO AUDIT

At minimum, inspect the following projects.

Do not assume they should all be integrated.

The purpose is to identify useful Legos.

---

### 5.1 Google ADK for Kotlin

Repository:

https://github.com/google/adk-kotlin

Likely role:

- agent loop
- tool calling
- agent sessions
- memory
- artifacts
- runners
- model abstraction
- Android/on-device agent support
- structured tools
- possible Gemini Nano / LiteRT-LM integration

License:

**Apache 2.0**

Audit question:

> Can Google ADK for Kotlin replace most of Pocket Steward's planned custom Milestone 5/6 agent runtime without weakening the existing filesystem safety boundary?

Strong expectation:

**BORROW or REPLACE for unbuilt agent-runtime infrastructure.**

Do not let ADK bypass `PlanValidator` or `PlanExecutor`.

Pocket Steward-specific tools should expose inspection and planning, not unrestricted filesystem writes.

Desired conceptual boundary:

```text
Google ADK
    ↓
Pocket Steward typed tools
    ↓
ProposedPlan
    ↓
PlanValidator
    ↓
PlanExecutor
```

Potential tool examples:

```kotlin
inspectFiles(query)
queryIndex(request)
proposeOrganization(request)
explainClassification(fileId)
validateProposedPlan(planId)
```

Do **not** expose tools equivalent to:

```text
move(path, path)
delete(path)
rename(path)
runShell(command)
```

to the model.

---

### 5.2 llama.cpp

Repository:

https://github.com/ggml-org/llama.cpp

Android documentation:

https://github.com/ggml-org/llama.cpp/blob/master/docs/android.md

Likely role:

- optional GGUF local-model runtime
- fallback model provider
- future user-selectable local models

License:

**MIT**

Audit question:

> Is llama.cpp worth integrating now, or should Pocket Steward first use Android-native model paths and defer GGUF support?

Expected classification:

Probably **DEFER** for current milestone work, but preserve a provider abstraction that makes later integration easy.

Do not make V1 depend on llama.cpp.

---

### 5.3 BFE

Repository:

https://github.com/The412Banner/BFE

Likely role:

- Android file-manager architecture
- All Files Access patterns
- SAF abstraction
- cross-backend move/copy
- rename/create-directory behavior
- storage-provider design

License:

**GPLv3**

Audit question:

> Which unbuilt Pocket Steward storage behaviors can be borrowed from BFE without replacing our already-working DirectStorageGateway, validator, executor, or journal?

Strong expectation:

**BORROW** for SAF / storage-backend techniques.

Do not rip out working Direct-mode storage merely because BFE exists.

Pay particular attention to:
- SAF mutation handling
- document URI handling
- storage abstraction boundaries
- error handling
- collision handling
- copy/move implementation

---

### 5.4 Material Files

Repository:

https://github.com/zhanghai/MaterialFiles

Likely role:

- mature Android filesystem behavior
- archives
- permissions
- symlinks
- root / NAS concepts
- long-tested file-operation edge cases
- UI patterns for file browsing

License:

**GPLv3**

Audit question:

> What filesystem edge-case handling or UI patterns are mature enough here that Pocket Steward should borrow them instead of discovering them through bugs?

Expected classification:

Mostly **BORROW / REFERENCE**, not wholesale replacement.

---

### 5.5 Amaze File Utilities

Repository:

https://github.com/TeamAmaze/AmazeFileUtilities

Likely role:

- duplicate detection
- large-file analysis
- cleanup utilities
- media/file categorization concepts
- storage analysis

License:

**GPLv3**

Audit question:

> Can Pocket Steward reuse or adapt mature cleanup / duplicate-analysis logic instead of implementing these features from zero in Milestone 4?

Expected classification:

Potential **BORROW** for deterministic cleanup features.

Do not import broad UI or unrelated app infrastructure unless justified.

---

### 5.6 Shelly

Repository:

https://github.com/RYOITABASHI/Shelly

Likely role:

- architectural reference for Android-native agents
- local GGUF models
- scheduled/background work
- tools
- Android-native autonomous execution

License:

**GPLv3**

Audit question:

> What can Pocket Steward learn from Shelly's Android agent lifecycle, background work, model hosting, and tool orchestration without importing terminal machinery Pocket Steward does not need?

Expected classification:

Primarily **REFERENCE / BORROW architectural patterns**.

Do not transform Pocket Steward into a terminal app.

---

### 5.7 libsu

Repository:

https://github.com/topjohnwu/libsu

Likely role:

- possible future root support
- privileged Android operations

License:

**Apache 2.0**

Audit question:

> Does Pocket Steward need this now?

Expected classification:

**DEFER**.

Root is not part of the personal-use V1 requirement.

---

## 6. LICENSING RULES

Do not treat "open source" as one bucket.

For every candidate, record:

- repository
- exact license
- whether direct code reuse is permitted
- attribution requirements
- redistribution/source obligations
- whether integration could affect Pocket Steward's future licensing options

### Permissive licenses

Apache 2.0 / MIT are generally suitable for direct reuse with required notices preserved.

### GPLv3

For the current **private personal-use build**, GPL code is usable.

If Pocket Steward is ever distributed, incorporating GPLv3 code can impose GPLv3 distribution/source obligations on the resulting derivative work.

Therefore:

- clearly mark all GPL-derived code,
- preserve license notices,
- keep GPL imports isolated when practical,
- distinguish "copied/adapted code" from "architectural idea/reference",
- do not silently mix GPL code into unrelated files without provenance comments.

Do **not** make a product-licensing decision on Pat's behalf.

The primary use case is personal/private.

Publishing may never happen.

---

## 7. AUDIT THE CURRENT M3 TREE BY SUBSYSTEM

Inspect the actual current source tree.

Do not evaluate the roadmap only from planning documents.

Produce a table covering at least:

| Subsystem | Current implementation | Current status | Candidate OSS | License | Decision | Reason | Integration cost |
|---|---|---|---|---|---|---|---|
| Direct storage | Pocket Steward | working / device-tested | BFE / Material Files | GPLv3 | KEEP | already proven | none |
| SAF storage | Pocket Steward partial | unverified / incomplete | BFE | GPLv3 | BORROW | mature SAF patterns | medium |
| File index | Pocket Steward / Room | working | ? | ? | KEEP | project-specific | low |
| Duplicate detection | planned | unbuilt | Amaze | GPLv3 | BORROW | avoid reinvention | medium |
| Rule engine | planned | unbuilt | inspect candidates | varies | TBD | | |
| Agent runtime | planned | unbuilt | Google ADK Kotlin | Apache 2.0 | REPLACE/BORROW | existing framework | medium |
| Local model runtime | planned | unbuilt | ADK / LiteRT / llama.cpp | Apache/MIT | DEFER/REPLACE | | |
| Journal / Undo | Pocket Steward | working M3 | other projects only for reference | varies | KEEP | safety spine | none |
| Background execution | partial/planned | incomplete | Shelly / AndroidX | GPL/Apache | BORROW | lifecycle patterns | medium |
| File browser UI | Pocket Steward basic | partial | Material Files / BFE | GPL | BORROW selectively | | |

Expand this table as needed.

---

## 8. MILSTONE IMPACT

After the subsystem audit, explicitly rewrite the roadmap impact.

Current expectation:

### Milestone 3
**Keep.**

This is the working deterministic safety spine.

### Milestone 4
Still primarily Pocket Steward-owned:

- file indexing
- deterministic classification
- duplicate detection
- large/old file analysis
- confidence/reason metadata
- project-keyword rules

But use open-source algorithms/utilities where they clearly save work.

### Milestone 5 + Milestone 6
Re-evaluate aggressively.

Instead of separately building:

- natural-language parser
- custom agent loop
- tool router
- sessions
- model abstraction
- local-model glue

prefer integrating Google ADK Kotlin if the audit confirms it fits.

Potentially collapse Milestones 5 and 6 into one **Agent Integration Milestone**.

### Milestone 7
Polish remains project-specific.

---

## 9. DO NOT OVER-INTEGRATE

This audit is not permission to create dependency soup.

For every proposed dependency, ask:

1. What exact problem does it solve?
2. Is Pocket Steward already solving that problem correctly?
3. How many lines / modules would the dependency replace?
4. What maintenance burden does it introduce?
5. Does it expand APK size materially?
6. Does it require network access?
7. Does it weaken offline operation?
8. Does it complicate Android version compatibility?
9. Does it introduce licensing obligations?
10. Does it bypass the Pocket Steward safety model?

If the value is marginal, classify it **DEFER**.

---

## 10. PROVENANCE REQUIREMENT

For every piece of third-party code actually copied or adapted later, record:

```text
Source project:
Repository:
File(s):
Commit/tag:
License:
Original copyright:
Modification summary:
Pocket Steward destination:
```

Create a durable third-party provenance document if implementation begins later.

Suggested filename:

`THIRD_PARTY_PROVENANCE.md`

Do not rely on memory or chat history for licensing provenance.

---

## 11. EXPECTED OUTPUT

Do **not** implement the integrations yet.

First produce:

### A. `POCKET_STEWARD_OPEN_SOURCE_COMPONENT_AUDIT.md`

It must contain:

- current M3 subsystem inventory
- candidate repository for each subsystem
- exact license
- KEEP / REPLACE / BORROW / DEFER decision
- concrete rationale
- estimated integration complexity
- likely code/modules/files to inspect
- safety implications
- roadmap impact
- recommended integration order

### B. Revised milestone proposal

Give a concise proposed roadmap showing how the open-source audit changes Milestones 4–7.

### C. Integration shortlist

End with a prioritized list such as:

```text
1. Google ADK Kotlin — integrate before custom NL/agent work.
2. BFE — inspect SAF move/copy implementation before finishing SAF mutations.
3. Amaze File Utilities — inspect duplicate-analysis implementation before M4 duplicates.
4. Material Files — mine filesystem edge-case behavior as needed.
5. Shelly — use as lifecycle/background-agent reference only.
6. llama.cpp — defer until local-model provider work proves necessary.
7. libsu — defer indefinitely unless root becomes an explicit requirement.
```

This ordering is provisional.

Change it only if the evidence from the actual repositories and current Pocket Steward code justifies doing so.

---

## 12. RESEARCH STANDARD

Actually inspect the repositories.

Do not write the audit from README summaries alone if source files relevant to the claimed reuse are available.

For each major candidate:

- inspect repository structure,
- identify the relevant modules/files,
- inspect license,
- inspect recent activity / maintenance state,
- identify Android/API assumptions,
- determine whether the relevant code is reusable as a dependency or would require extraction/adaptation.

Distinguish:

- documented fact,
- source-code observation,
- inference,
- recommendation.

If uncertain, say so.

---

## 13. FINAL RULE

Pocket Steward already has a working M3 safety core.

The purpose of this audit is to **reduce future work**, not to destabilize what already works.

Do not rewrite functioning Pocket Steward code simply because another project solved a similar problem.

Search first.

Reuse what is genuinely better.

Keep what is already proven.

Build only the connective tissue that makes Pocket Steward itself.
