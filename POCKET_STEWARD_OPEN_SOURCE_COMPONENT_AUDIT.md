# Pocket Steward — Open-Source Component Audit

Written against `POCKET_STEWARD_OPEN_SOURCE_LEGO_AUDIT_INSTRUCTIONS_V1_0.md`. Every project below was actually fetched and read (README, license, commit/activity signals) rather than assessed from the audit instructions' own summaries alone — two of those summaries turned out to need correcting once checked.

**Timing note:** the instructions document assumes Milestone 3 is current and Milestone 4 hasn't started. By the time this audit ran, Milestone 4 (rule engine, duplicate detection, generated cleanup plan) was already built, compiled successfully, and shipped for on-device testing. This audit is therefore retroactive for Milestone 4 and prospective for Milestones 5–7, not a pre-M4 gate. Nothing in Milestone 4's actual implementation needs to be undone — see Section 3 below.

---

## 1. Corrections to the audit brief's own expectations

Two of the seven projects don't support the "strong expectation" the instructions document assigned them. Stated up front because they change the shortlist at the end.

**BFE is not mature.** The brief expected "BORROW for SAF / storage-backend techniques" on the assumption of mature, tested patterns. The actual repo has 27 commits total, is still on 0.x releases, and its own documentation states extraction from SAF/root locations isn't supported yet and SAF only reaches apps that expose a DocumentsProvider — i.e. it has the same open problems Pocket Steward has, not solved ones. There's a real storage-backend abstraction there (File/SAF/Root, cross-backend streaming copy/move) worth a look, but "borrow mature SAF patterns" overstates what exists.

**Shelly is not a good architectural reference for this project.** The brief expected "primarily reference/borrow architectural patterns." The actual repo is an ~800 MB Android terminal IDE that bundles bash, Node.js, Python, git, ssh, vim, tmux, and ripgrep, runs a local LLM via an embedded llama.cpp/Qwen3.5, and derives its terminal layer from Termux. Pocket Steward's own plan document explicitly rules out a Termux dependency and arbitrary shell access (Section 3) and explicitly rejects "dozens of autonomous subagents" and "generalized computer use" (Section 17) — Shelly is close to the shape of app Section 3 tells this project not to become. The one narrow thing worth noting is its `AlarmManager`-based scheduling for unattended background work, relevant only to Milestone 7's V2 "scheduled cleanup suggestion" idea (Section 25), and even that should be read as "here's one way someone did background scheduling," not adopted as an architecture.

The other five projects hold up against what the brief expected them to be.

---

## 2. Per-project findings

### 2.1 Google ADK for Kotlin
`github.com/google/adk-kotlin` — **Apache 2.0**, confirmed. Actively maintained (438 commits, current release 1.1.0 on Maven Central, semantic versioning). Modules: `core` (agents/models/tools/sessions/memory/artifacts/runners), `processor` (KSP tool generation), `webserver`, `a2a`, `litertlm` (on-device via LiteRT-LM), plus Android-specific `firebase`/`mlkit` (ML Kit + Gemini Nano) backends. No built-in filesystem tools — good, since that means no risk of it shipping something that bypasses `PlanValidator`, but also means the typed-tool layer (`inspectFiles`, `queryIndex`, `proposeOrganization`, `explainClassification`, `validateProposedPlan`) is work Pocket Steward still has to write regardless of whether ADK is adopted.

**One real tension the brief didn't flag:** ADK's own framing is "tight integration with Google Cloud services," and it ships Firebase and cloud-provider backends alongside the on-device ones. Plan Section 23 requires "no network requirement for organization" and "no AI cloud calls in V1." Adopting ADK is only consistent with that if Pocket Steward uses exclusively the `litertlm`/on-device `mlkit` path and never wires in the `firebase`/cloud integration modules — which is possible (the module is separable), but has to be a deliberate exclusion, not an accident of "we depend on ADK so we get whatever it defaults to."

**Verdict: BORROW for Milestone 5/6's agent-runtime plumbing** (sessions, tool-calling loop, structured output), on-device backends only, with Pocket Steward's own typed tools as the only surface it's allowed to call — never handed `move`/`delete`/`rename`/shell primitives. Confirm before adopting: whether `litertlm`-only usage genuinely excludes any Firebase/cloud transitive dependency from the APK.

### 2.2 BFE
`github.com/The412Banner/BFE` — **GPLv3**, plus bundled third-party components (7-Zip, innoextract, unarc, FFmpeg with x264, GPL v2+) carrying their own notices. 27 commits, 0.x releases — early stage, not the mature reference the brief expected (see Section 1). Storage layer abstracts File/SAF/Root backends with cross-backend streaming copy/move; SAF write operations (new folder, rename, delete) exist, but extraction from SAF/root sources doesn't yet, and SAF reach is limited to apps exposing a DocumentsProvider — the same open problem Pocket Steward already has, not a solved one.

**Verdict: reference only, low priority.** Worth a narrow look at how it structures the File/SAF/Root backend switch (Pocket Steward's own `StorageGateway` already has this shape via `DirectStorageGateway`/`SafStorageGateway`) before finishing SAF mutations, but not a "borrow this, it's proven" case — it hasn't been proven yet either.

### 2.3 Material Files
`github.com/zhanghai/MaterialFiles` — **GPLv3**. Genuinely mature: 2,375 commits, 8.9k stars, 734 forks, long-running with active issue/PR traffic. Built on Java NIO2 rather than a custom file model, uses direct Linux syscalls instead of parsing `ls` output, and handles symlinks, file permissions, SELinux context, and invalid UTF-8 filenames as first-class cases.

**Verdict: BORROW/REFERENCE, as the brief expected.** This is the one project in the set that's actually earned "mature" — worth mining specifically for the failure-mode list in plan Section 21's filesystem integration tests (unicode filenames, emojis, long filenames, symlinks, nested/empty directories) before Pocket Steward discovers the same edge cases through bug reports instead. GPLv3 means reference/pattern-borrowing, not direct code import, unless the code stays isolated and clearly marked per Section 6's licensing rules.

### 2.4 Amaze File Utilities
`github.com/TeamAmaze/AmazeFileUtilities` — **GPLv3**, explicit anti-rebranding clause. 570 commits, active. Confirmed feature set includes duplicate/large/hidden-file storage analysis and near-duplicate media grouping ("videos that may not be useful"), but the README fetch didn't surface the actual detection algorithm — that would need direct source inspection, which the brief also calls for but wasn't done here given the next point.

**This is substantially moot now for exact-duplicate detection specifically:** Pocket Steward's own `DuplicateDetector` already implements plan Section 8's exact cascade (group by size, drop singletons, quick-fingerprint the rest, full SHA-256 only for quick-fingerprint collisions) as working, committed, compiler-verified Milestone 4 code. There's no unbuilt gap here to fill anymore.

**Verdict: DEFER, not BORROW, for what's already built.** The genuinely open gap is near-duplicate/perceptual detection (blurred images, similar-but-not-identical media), which plan Section 25 already scopes as V2/V1.1 "gravy," not V1. Worth a real source-level look at Amaze's near-duplicate algorithm specifically when that V2 work starts, not now.

### 2.5 Shelly
See Section 1 — GPLv3, active (2,421 commits), but architecturally the closest thing in this set to what Pocket Steward's own plan explicitly rules out. **Verdict: DEFER / reference with caution.** The only extractable idea is `AlarmManager`-based unattended scheduling, relevant only to a Milestone 7 V2 feature, and even that should be treated as "one example of how someone did it" rather than a pattern to adopt wholesale.

### 2.6 libsu
`github.com/topjohnwu/libsu` — **Apache 2.0**, confirmed. Active (484 commits). Root shell process management, root-service IPC, privileged filesystem operations. Matches the brief's own expectation exactly.

**Verdict: DEFER, confirmed.** Root is explicitly out of scope for personal-use V1 (plan Section 3). No new information changes this.

### 2.7 llama.cpp
`github.com/ggml-org/llama.cpp` — MIT-licensed (well-established fact about this project; not directly visible in the specific Android-docs page fetched, but not in dispute). Android support via three paths: Android Studio example app, Termux-based CLI build, or NDK cross-compilation; GGUF model format; requires disabling OpenMP/native-CPU-opt/OpenSSL for Android NDK builds.

**Verdict: DEFER, confirmed**, matching the brief. Milestone 6's primary path is Android-native (Gemini Nano / ML Kit GenAI, or ADK's `litertlm`/`mlkit` backends per 2.1). The real reason to keep this on the shortlist at all isn't "integrate it now" — it's that Nano/AICore hardware support is inconsistent across the Android device fleet, so a llama.cpp-backed `AgentModel` implementation is a plausible fallback for devices that can't run the on-device Google path. Plan Section 10 already designed for this (a provider interface, "no model" / Nano / future-provider tiers) — the actionable item is making sure that interface boundary stays real when Milestone 6 is actually built, not adopting llama.cpp now.

---

## 3. Current M3/M4 subsystem inventory

| Subsystem | Current implementation | Status | Candidate OSS | License | Decision | Reason | Integration cost |
|---|---|---|---|---|---|---|---|
| Direct storage (move/rename/trash) | Pocket Steward `DirectStorageGateway` | Working, device-tested (M2) | BFE, Material Files | GPLv3 | **KEEP** | Already proven on real hardware; BFE is less mature, not more | none |
| SAF storage (read) | Pocket Steward `SafStorageGateway` | Working, `fromTreeUri` fix confirmed by build | BFE | GPLv3 | **KEEP** | Read path already works; nothing to replace | none |
| SAF storage (mutations) | `TODO()`, parked since M1 | Unbuilt | BFE (File/SAF/Root abstraction) | GPLv3 | **BORROW (reference only)** | BFE hasn't solved this either — same open problem, not a finished answer | low (reading, not adopting) |
| File index | Pocket Steward / Room `FileRecord` | Working (M0–M4) | — | — | **KEEP** | Project-specific schema, no general-purpose substitute makes sense | none |
| Rule engine / classification | Pocket Steward `RuleEngine` (pure Kotlin, compiler-verified) | Working, shipped M4 | — | — | **KEEP** | Simple, deterministic, already matches Section 9's exact required shape | none |
| Duplicate detection (exact) | Pocket Steward `DuplicateDetector` | Working, shipped M4 | Amaze File Utilities | GPLv3 | **KEEP** | Already implements Section 8's cascade correctly; Amaze's algorithm wasn't even confirmed superior | none |
| Duplicate detection (near/perceptual) | Not built (V2 per Section 25) | Unbuilt, deferred | Amaze File Utilities | GPLv3 | **DEFER, revisit for real when V2 starts** | Out of V1 scope; worth a real source-level look later | medium, later |
| Large/old file search | Pocket Steward `FileRecordDao` queries | Working, shipped M4 | — | — | **KEEP** | Trivial Room queries, no dependency justified | none |
| Cleanup plan generation | Pocket Steward `CleanupPlanGenerator` | Working, shipped M4 | — | — | **KEEP** | Thin adapter over the existing typed manifest, project-specific by nature | none |
| Journal / Undo / crash recovery | Pocket Steward `MutationRecovery`, `UndoExecutor` | Working, compiled + tested M3 (unverified on-device) | — | — | **KEEP** | This is the safety spine; nothing here is a commodity problem | none |
| Agent runtime (NL parsing, tool loop, sessions) | Not built (M5/M6) | Unbuilt | Google ADK Kotlin | Apache 2.0 | **BORROW** | Real, maintained, Android-capable framework; building this from scratch is exactly the "reinvention" this audit exists to avoid | medium — new dependency, needs the on-device-only exclusion confirmed |
| Local model provider | Not built (M6) | Unbuilt | ADK (`litertlm`/`mlkit`), llama.cpp | Apache/MIT | **BORROW via ADK primarily; llama.cpp DEFER** | ADK already wraps the Android-native paths; llama.cpp only matters as a fallback for devices without Nano/AICore | medium, later |
| Background/scheduled execution | Not built (M7 V2) | Unbuilt, deferred | Shelly (pattern only) | GPLv3 | **DEFER**, reference `AlarmManager` pattern only when M7 V2 work starts | Shelly itself is architecturally the wrong model for this app | low, later |
| File browser UI edge cases | Pocket Steward basic Compose screens | Partial | Material Files | GPLv3 | **BORROW (reference)** | Genuinely mature edge-case handling worth mining for Section 21's test list | low — reading, not importing |
| Root/privileged access | Not built | Not planned | libsu | Apache 2.0 | **DEFER** | Explicitly out of scope (Section 3) | none |

---

## 4. Roadmap impact

**Milestones 0–4: unchanged, already shipped.** Nothing in this audit found a defect in what's built that justifies replacing working, tested code. The one legitimate outstanding item — SAF mutations — stays Pocket-Steward-owned; BFE gives reference material, not a ready answer.

**Milestone 5 (Natural language) and Milestone 6 (On-device AI): the audit does change these.** Building a custom bounded-intent parser, a custom agent loop, a custom tool router, and custom session/memory handling from zero, as the original plan sketched, is exactly the kind of commodity infrastructure Google ADK Kotlin already provides under a permissive license with active maintenance and real Android on-device support. Recommend collapsing Milestones 5 and 6 into one **Agent Integration Milestone**: adopt ADK for the agent loop and on-device model backends, write Pocket Steward's own typed tool layer on top of it (`inspectFiles`, `queryIndex`, `proposeOrganization`, `explainClassification`, `validateProposedPlan` — read/plan operations only), and route every proposed plan through the unchanged `PlanValidator` → `PlanExecutor` → journal chain. ADK sits entirely above that boundary; nothing about adopting it should touch `plan`, `executor`, or `storage`.

**Milestone 7 (Polish): mostly unchanged.** Material Files is worth mining for edge-case test coverage before this milestone, not during it. Shelly's scheduling pattern is worth a glance only if the V2 "scheduled cleanup suggestion" idea gets built.

---

## 5. Integration shortlist

```text
1. Material Files — mine filesystem edge-case behavior (Section 21's test
   list: symlinks, unicode/emoji filenames, permissions) before or during
   Milestone 7 polish. Reference only, not a dependency.

2. Google ADK Kotlin — adopt for the Milestone 5/6 agent runtime, on-device
   backends only (litertlm / ML Kit Gemini Nano), before writing custom
   NL-parsing or agent-loop infrastructure. Confirm no Firebase/cloud
   transitive dependency comes along for the ride. Expose only typed,
   read/plan-only tools; never a raw filesystem primitive.

3. BFE — inspect the File/SAF/Root backend abstraction once SAF mutations
   are actually being built, as one reference point among others. Not a
   dependency, not "mature," despite the original brief's framing.

4. Amaze File Utilities — revisit specifically for near-duplicate/
   perceptual detection when that V2 feature (Section 25) actually starts.
   Exact-duplicate detection is already built and shipped; there's nothing
   to replace there.

5. llama.cpp — defer until a specific device without Nano/AICore support
   actually needs a fallback local-model provider. Keep the AgentModel
   provider boundary real so this stays a clean swap-in later.

6. Shelly — defer. Reference the AlarmManager scheduling pattern only if
   Milestone 7's V2 scheduled-cleanup idea gets built, and only that one
   pattern — the rest of its architecture is close to what this project's
   own plan document explicitly rules out.

7. libsu — defer indefinitely. Root is not a V1 requirement and nothing
   in this audit changes that.
```

This ordering reflects what was actually found in each repository, not the audit brief's own priors — items 3 and 6 were downgraded from the brief's "strong expectation" after checking the repos directly (Section 1).

---

## 6. Provenance

No third-party code has been copied or adapted as a result of this audit — it is a research pass only, per the brief's own instruction not to implement integrations yet. If and when ADK, Material Files, or BFE material is actually adopted, `THIRD_PARTY_PROVENANCE.md` should be created at that point per the brief's Section 10 template, before any GPL-licensed reference code crosses from "read for pattern ideas" into "adapted into Pocket Steward's own files."
