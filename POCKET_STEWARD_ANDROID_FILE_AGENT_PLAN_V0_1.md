# Pocket Steward
## Android-Only Agentic File Organizer — Build Plan / Handoff Spec
**Version:** 0.1  
**Date:** 2026-09-16  
**Primary constraint:** The phone is the entire runtime. No PC, companion desktop, remote executor, or required server.


### Personal-use implementation amendment — 2026-09-17

For the current build, personal use on Pat's own Android device is the primary target. Repository/GitHub setup is not part of the product dependency chain. Publishing/Play Store work is deferred unless later requested. Direct internal shared-storage access is the primary mutation path; SD-card/cross-volume and SAF mutation support are non-blocking follow-ons.

Crash-safety wording is tightened as follows: a mutation journal row is written `PENDING` with the expected destination **before** the filesystem call, then changed to `COMMITTED` only after success. Undo uses the same write-ahead rule. Model-reported confidence is never validator input. Long generalized mutation runs should eventually use a foreground execution service, but correctness must remain checkpoint/journal-based so process death cannot cause silent data loss. Any Level-2 document/PDF parsing dependency must be chosen explicitly before Milestone 4 content inspection rather than added ad hoc.

---

## 1. Product definition

Pocket Steward is an Android-native, agentic file manager whose primary job is to let the user describe an organizational outcome in natural language and have the app safely carry it out against files on the phone.

Core interaction:

> “My Downloads folder is a disaster. Put APKs together, organize documents by project where you can tell, group obvious duplicates, and leave anything uncertain alone.”

The app should:

1. Scan accessible phone storage.
2. Build a local structured index.
3. Interpret the request.
4. Produce a concrete file-operation plan.
5. Show the plan before risky changes.
6. Execute approved operations deterministically.
7. Journal every mutation.
8. Allow undo.
9. Report uncertain files rather than guessing.

The AI proposes intent. Ordinary code touches the filesystem.

That separation is non-negotiable.

---

## 2. V1 goals

V1 should be genuinely useful for cleaning and organizing a real Android phone.

### Required user capabilities

- Scan one folder, several selected folders, or broadly accessible shared storage.
- Ask for organization in natural language.
- Browse a structured summary of what is present.
- Classify files by:
  - extension / MIME type
  - filename
  - current path
  - size
  - created / modified date
  - basic document text when inexpensive
  - APK package metadata
  - image metadata
- Create folders.
- Move files.
- Rename files.
- Group apparent duplicates.
- Find large files.
- Find old files.
- Find likely project-related files.
- Preview every planned mutation.
- Approve all, approve selected actions, or reject.
- Undo a completed organization task.
- Resume an interrupted scan or task.
- Never permanently delete a file by default.

### Example V1 requests

- “Organize Downloads by file type.”
- “Put all my APK installers under Downloads/APKs.”
- “Find everything with NSTL, Leaseworld, Erica, Aleksei, or Lilith in the filename and group it by project.”
- “Show me duplicate files but don’t delete anything.”
- “Move PDFs older than six months into Documents/Archive/PDF.”
- “Find the 50 largest files under Downloads and Pictures.”
- “Rename these selected files into a consistent scheme.”
- “Do the obvious cleanup and leave anything ambiguous where it is.”
- “Undo the cleanup I just ran.”

---

## 3. V1 non-goals

Do NOT turn V1 into a general-purpose autonomous Android assistant.

Explicitly defer:

- controlling arbitrary third-party app UIs
- email/calendar agents
- browser automation
- coding agents
- voice assistant functionality
- cloud sync
- general web research
- rooting the phone
- Shizuku dependency
- Termux dependency
- arbitrary shell access
- modification of other apps’ private storage
- permanent AI-initiated deletion
- fully autonomous scheduled cleanup

Those can become later modules if the core file-agent architecture proves itself.

---

## 4. Platform target

Recommended initial target:

- Kotlin
- Jetpack Compose
- Room / SQLite
- Coroutines + Flow
- minSdk 30 unless a concrete need for older Android appears
- target current Android SDK when implementation begins
- single-device local-first architecture
- sideloading supported from day one
- Play Store compatibility considered, but not allowed to distort the personal-use build

Why minSdk 30: Android 11 is the point where modern shared-storage behavior and `MANAGE_EXTERNAL_STORAGE` become the relevant model. Supporting substantially older Android versions adds branches that are not useful to the primary device.

---

## 5. Storage-access strategy

Support two access modes behind one interface.

### Mode A: Broad file-manager access

Use `MANAGE_EXTERNAL_STORAGE` when granted.

This is appropriate because file management is the product’s core purpose. It allows broad access to shared storage but still does not grant arbitrary access to other apps’ private `/Android/data` directories.

Use this for the intended personal/sideloaded build.

### Mode B: User-selected workspace access

Use Android’s Storage Access Framework (SAF) for users who only want to grant specific directory trees.

Persist URI permissions after the user selects a directory.

### Storage abstraction

Do not let the rest of the app care whether a file came through a direct path or a content URI.

Create an interface such as:

```kotlin
interface StorageGateway {
    suspend fun list(scope: StorageScope): List<FileEntry>
    suspend fun stat(ref: FileRef): FileMetadata
    suspend fun openRead(ref: FileRef): InputStream
    suspend fun createDirectory(parent: FileRef, name: String): FileRef
    suspend fun move(source: FileRef, destination: FileRef): MutationResult
    suspend fun rename(source: FileRef, newName: String): MutationResult
    suspend fun trash(source: FileRef): MutationResult
}
```

Implement:

- `DirectStorageGateway`
- `SafStorageGateway`

The executor talks only to `StorageGateway`.

---

## 6. Architecture

```text
Compose UI
    |
ViewModels
    |
Use Cases / Application Layer
    |
+----------------------+----------------------+
|                      |                      |
Indexer             Agent Core            Executor
|                      |                      |
Metadata DB        Intent -> Plan        Operation Manifest
|                      |                      |
Room                Rules + AI          StorageGateway
|                                             |
+------------------- Journal / Undo ---------+
```

Main modules:

1. Storage access
2. Local file index
3. Metadata extractors
4. Rule engine
5. Optional AI classifier/planner
6. Operation-plan validator
7. Deterministic executor
8. Transaction journal / undo engine
9. Compose UI
10. Background/resume coordinator

Keep these separable. The most dangerous architectural mistake would be letting “the agent” directly call Java/Kotlin filesystem functions with unconstrained parameters.

---

## 7. Local file index

Use Room.

### `FileRecord`

Suggested fields:

```text
id
stableRef
displayName
extension
mimeType
absolutePathOrUri
parentRef
sizeBytes
createdAt
modifiedAt
lastScannedAt
isDirectory
isHidden
mediaType
width
height
duration
apkPackageName
apkVersionName
sha256
quickFingerprint
textPreview
classification
classificationConfidence
```

Do not eagerly populate expensive fields.

### Scan levels

**Level 0 — inventory**

Cheap:
- path
- filename
- extension
- MIME
- size
- dates
- directory

**Level 1 — metadata**

On demand:
- image dimensions / EXIF
- APK metadata
- audio/video metadata
- PDF page count where cheap
- archive listing where cheap

**Level 2 — content**

Only when necessary:
- text extraction
- document snippets
- image description
- hashes
- semantic classification

This keeps “scan my phone” from becoming “cook my battery.”

---

## 8. Duplicate detection

Do not hash every byte of every file during initial indexing.

Use a cascade:

1. Group by exact size.
2. Ignore groups with one member.
3. Compute a quick fingerprint for candidate groups.
4. Compute SHA-256 only for remaining candidates.
5. Exact duplicate requires matching cryptographic hash.

Later V2:
- perceptual image duplicate detection
- near-duplicate documents
- renamed/re-encoded copies

V1 should never claim two files are exact duplicates solely from filename, size, or AI judgment.

---

## 9. Rule engine first, AI second

A huge percentage of file organization does not need AI.

Examples:

```text
*.apk        -> APK
*.pdf        -> Document/PDF
*.md         -> Document/Markdown
*.zip        -> Archive
IMG_*        -> likely camera/image
Screenshot_* -> screenshot
```

Project terms can be user-configurable:

```text
NSTL
Nobody Signed the Lease
Leaseworld
Lilith
Erica
Aleksei
MarkdownDesk
```

The rule engine should return:

```text
classification
confidence
reason
matchedRules[]
```

Only ambiguous records should reach the model.

This reduces latency, battery cost, context size, and hallucination risk.

---

## 10. AI layer

Define a provider interface. Do not bind the app to one model.

```kotlin
interface AgentModel {
    suspend fun classify(request: ClassificationRequest): ClassificationResult
    suspend fun plan(request: PlanningRequest): AgentPlan
}
```

Initial implementations:

### A. No-model / deterministic mode

The app remains useful even with AI completely disabled.

Natural-language requests can initially map onto a bounded intent parser and predefined operations.

### B. Gemini Nano / Android on-device model

Use Google’s Android Agent Development Kit / ML Kit GenAI Prompt API when supported by the device.

Ideal uses:
- ambiguous project classification
- interpreting natural-language cleanup requests
- suggesting human-readable folder names
- interpreting filenames
- optionally describing selected images

Do not make app launch or basic organization depend on Nano availability.

### C. Future provider adapters

Possible later:
- user-configured OpenAI API
- Anthropic API
- local GGUF runtime

These are optional gravy. Existing ChatGPT/Claude subscriptions are not needed for V1.

---

## 11. Agent model: constrained, not omnipotent

The model should NOT be given tools like:

```text
move_file("/whatever", "/wherever")
delete_file(...)
```

Instead it produces a typed **proposed plan** from an allowlisted schema.

Example:

```json
{
  "goal": "Organize Downloads",
  "operations": [
    {
      "type": "CREATE_DIRECTORY",
      "parent": "Download",
      "name": "APKs",
      "reason": "Destination for Android package installers"
    },
    {
      "type": "MOVE",
      "sourceFileId": 8182,
      "destinationDirectoryId": 551,
      "reason": "APK file",
      "confidence": 1.0
    }
  ],
  "unresolved": [
    {
      "fileId": 9891,
      "reason": "Project cannot be identified confidently"
    }
  ]
}
```

The model refers to indexed IDs, not arbitrary paths whenever possible.

The plan then passes through `PlanValidator`.

Only the deterministic executor receives validated operations.

---

## 12. Plan validation

Before execution, validate every operation.

Rules:

- Source must exist.
- Source must be inside authorized storage.
- Destination must be inside authorized storage.
- No path traversal.
- No self-moves.
- No recursive move of a directory into itself.
- No overwrites without explicit policy.
- No implicit permanent delete.
- No mutation of excluded/system locations.
- No action on files the scan has not established.
- Detect name collisions before mutation.
- Detect case-insensitive collisions where relevant.
- Reject impossible operations as a batch before execution begins.

Plan validation should not require an LLM.

---

## 13. Mutation safety classes

### Green — may execute after task-level approval

- create directory
- move file
- safe rename
- copy file

### Yellow — explicit preview required

- bulk rename
- merge directory contents
- move large directory trees
- reorganize hundreds/thousands of files
- actions with medium-confidence classification

### Red — individual confirmation

- overwrite
- trash
- deduplicate by removing a copy
- destructive archive replacement

### Black — unavailable in V1

- permanent delete
- modify protected/system storage
- modify app-private data belonging to other apps
- execute downloaded files
- arbitrary shell command

---

## 14. No permanent deletion in V1

“Delete” should mean one of:

- move to an app-managed quarantine folder, OR
- use Android-supported trash semantics where available.

Record original location.

A later cleanup command can empty quarantine with explicit confirmation.

This creates a second safety net beyond undo.

---

## 15. Transaction journal and undo

This is a core feature, not polish.

### `TaskRun`

```text
id
requestText
startedAt
completedAt
status
scanSnapshotId
planJson
summary
```

### `MutationRecord`

```text
id
taskRunId
sequence
operationType
sourceBefore
destinationAfter
sourceFingerprint
executedAt
undoState
error
```

For every successful operation, immediately persist the inverse operation.

Examples:

```text
MOVE A -> B
undo = MOVE B -> A

RENAME A -> C
undo = RENAME C -> A

CREATE DIRECTORY X
undo = REMOVE X only if empty and created by this task
```

Undo must be reverse-order.

If a conflict prevents exact restoration, stop and ask instead of overwriting newer data.

---

## 16. Core screens

### Home

Large command field:

> What should I clean up?

Quick actions:
- Organize Downloads
- Find duplicates
- Find large files
- Find old files
- Review uncategorized
- Recent tasks

### Storage Scope

Shows:
- All accessible files
- Downloads
- Documents
- Pictures
- selected custom folders

### Scan Summary

Example:

```text
Downloads
1,284 files · 18.7 GB

Images             405
Documents          219
APKs                83
Archives             42
Audio/Video          91
Other               444

Exact duplicates     27 sets
Files > 500 MB       13
Unclassified        116
```

### Plan Preview

Each action shows:

```text
MOVE
Download/foo.apk
→ Download/APKs/foo.apk

Reason: Android package
Confidence: Certain
```

Controls:
- Approve all safe actions
- select/deselect operations
- show only uncertain
- edit destination
- cancel

### Active Task

Cowork-ish execution view:

```text
Organizing Downloads

✓ Created 5 folders
✓ Moved 73 APKs
◌ Processing documents 142 / 219
○ Duplicate review

[Pause]
```

### Completion

```text
Task complete

237 moved
18 renamed
5 folders created
41 left untouched
3 failed

[Review changes] [Undo task]
```

### History

Chronological tasks with:
- request
- summary
- status
- undo availability

---

## 17. “Cowork feel” without Cowork complexity

The useful Cowork behavior is:

1. User gives an outcome.
2. App inspects environment.
3. App forms a plan.
4. App exposes what it intends to do.
5. App performs multiple steps.
6. App keeps state.
7. User can interrupt.
8. App reports what changed.

Implement exactly that.

Do NOT chase:
- dozens of autonomous subagents
- arbitrary tool use
- browser control
- generalized computer use

The agent should feel broad to the user while remaining narrow internally.

---

## 18. Background work and interruption

Every expensive process must be resumable.

Persist checkpoints such as:

```text
scanRoot
lastDirectory
processedCount
pendingCount
currentTaskPhase
operationSequence
```

Prefer chunked work rather than assuming Android will let one process run forever.

Rules:

- No task should require uninterrupted process lifetime for correctness.
- Mutation journal is updated after each filesystem change.
- Scan results are committed in batches.
- On process restart, reconcile current filesystem state before resuming mutation.
- Never repeat an already committed mutation just because the process died.

A visible foreground execution mode can be added for user-initiated long tasks, but the app should be correct even if Android terminates it.

---

## 19. Package layout

Suggested project layout:

```text
app/
core-model/
core-storage/
core-index/
core-rules/
core-agent/
core-plan/
core-executor/
core-journal/
core-ai/
feature-home/
feature-scan/
feature-plan/
feature-execution/
feature-history/
feature-settings/
```

This may be collapsed initially if Gradle modularization slows V1 development. Preserve package boundaries even if everything starts in one app module.

---

## 20. Build sequence

### Milestone 0 — Skeleton

Deliver:
- Compose app
- navigation
- Room
- settings
- permission/access onboarding
- test harness

Exit:
App launches, user can grant storage scope, state persists.

### Milestone 1 — File inventory

Deliver:
- storage gateway
- recursive scanner
- Room index
- scan progress
- inventory UI
- resume support

Exit:
App can accurately inventory Downloads/Documents/Pictures and display counts/sizes.

### Milestone 2 — Deterministic file manager

Deliver:
- create folder
- move
- rename
- copy if useful
- collision handling
- operation manifest
- validator

Exit:
A hard-coded plan can safely reorganize real files.

### Milestone 3 — Journal + Undo

Deliver:
- task history
- mutation journal
- inverse operations
- undo
- interruption/reconciliation tests

Exit:
A completed organization can be rolled back reliably.

Do not proceed to autonomous AI planning before this milestone is solid.

### Milestone 4 — Rules / Smart cleanup

Deliver:
- extension/MIME classification
- project keyword rules
- duplicate candidate detection
- large/old file searches
- automatic confidence values
- generated cleanup plan

Exit:
Useful cleanup works without an LLM.

### Milestone 5 — Natural language

Deliver:
- bounded intent representation
- request parser
- commands such as:
  - organize
  - find
  - group
  - rename
  - archive
  - duplicate review
- plan preview generated from natural language

Initially allow a deterministic parser plus templates.

Exit:
“My Downloads are a mess; organize the obvious stuff and leave uncertain things alone” produces a valid preview.

### Milestone 6 — On-device AI

Deliver:
- `AgentModel` abstraction
- Gemini Nano capability detection
- Nano adapter
- fallback when unsupported/unavailable
- structured-output validation
- AI only receives the minimum metadata/content necessary

Exit:
Ambiguous classification improves when supported, but the app remains fully functional without AI.

### Milestone 7 — Polish / personal daily use

Deliver:
- fast re-scan
- saved organization rules
- exclusions
- favorite destinations
- better progress UI
- bulk-selection UX
- task summaries
- performance/battery profiling
- crash recovery

Exit:
The app is pleasant enough to trust on the actual phone.

---

## 21. Testing requirements

### Unit tests

- path validation
- collision resolution
- plan validation
- inverse-operation generation
- rule classification
- duplicate detection
- intent conversion
- confidence thresholds

### Filesystem integration tests

Create synthetic trees containing:

- identical names
- identical content
- case variants
- nested directories
- empty directories
- huge sparse files
- unicode filenames
- emojis
- long filenames
- files modified during execution
- destination created concurrently
- missing source after plan creation

### Failure injection

Simulate process death:

- during scan
- after plan approval
- between two moves
- after move but before UI update
- during undo

No scenario should silently lose a file.

### Real-device tests

At minimum test:

- thousands of files
- 10+ GB mixed folder
- SD/USB storage if supported
- locked screen
- app background/foreground
- low battery
- low storage
- revoked permission
- reboot during an unfinished task

---

## 22. Performance rules

- Never load entire files into memory when streaming is possible.
- Never hash all files by default.
- Limit concurrent I/O.
- Batch Room writes.
- Cache metadata.
- Diff scans using size / modified timestamp before deeper inspection.
- Throttle AI inference.
- Let the user disable content inspection.
- Prefer filenames + paths + metadata first.
- Explain when deeper inspection is necessary.

---

## 23. Privacy model

Default:

- index stays on device
- task history stays on device
- file contents stay on device
- no analytics containing filenames
- no AI cloud calls in V1
- no network requirement for organization

Settings should clearly distinguish:

```text
Metadata indexing
Document content inspection
Image analysis
On-device AI
Network AI providers [future]
```

Do not silently send filenames or document snippets anywhere.

---

## 24. V1 acceptance test

A build is V1-worthy when the following works on a real phone:

1. Install APK without any PC-side runtime requirement.
2. Grant file-manager access or select a directory tree.
3. Scan Downloads containing 1,000+ mixed files.
4. Ask:
   > “Organize the obvious files by type. Put APKs together, put PDFs and documents together, keep images separate, and leave anything uncertain alone.”
5. Receive a preview before mutation.
6. Deselect arbitrary actions.
7. Execute remaining plan.
8. Kill and reopen the app during a task without corruption.
9. See an accurate completion summary.
10. Undo the task and restore the original structure.
11. Perform all of the above with network disabled.

That is the actual product.

---

## 25. V1.1 / V2 gravy

After the core is trustworthy:

### Smarter organization

- semantic document classification
- OCR when genuinely needed
- image descriptions
- perceptual image duplicates
- screenshot detection
- saved project vocabularies
- user correction learning

### Better agent behavior

- “Do the obvious ones.”
- follow-up commands referring to previous scan
- persistent preferences
- reusable cleanup recipes
- explain why a file was classified a certain way

### Scheduled cleanup

- periodic scan suggestions
- never mutate unattended at first
- notification:
  > “I found 43 new files I can organize. Review plan?”

### Import/export

- export task manifest as JSON/Markdown
- export file inventory
- share a problem set into ChatGPT/Claude manually
- import a reviewed organization manifest

### Android ecosystem

- AppFunctions when mature/useful
- share-sheet integration:
  > Share to Pocket Steward → classify/store this file
- home-screen quick actions
- document provider integration

---

## 26. Important implementation decisions

### Decision 1
**No AI direct filesystem access.**

Model -> plan -> validator -> executor.

### Decision 2
**Undo is built before agentic planning.**

If undo is unreliable, the agent is not allowed to become more autonomous.

### Decision 3
**Rules do most work.**

AI handles ambiguity, not extensions and obvious metadata.

### Decision 4
**Offline is a real mode, not marketing.**

Core organization must work in airplane mode.

### Decision 5
**Uncertainty is allowed.**

The correct result for an ambiguous file is often:

> Leave this alone.

### Decision 6
**No permanent delete in V1.**

Ever.

---

# IMPLEMENTATION HANDOFF PROMPT

Use the following when handing this project to an implementation agent:

> Build the Android application described in `POCKET_STEWARD_ANDROID_FILE_AGENT_PLAN_V0_1.md`.
>
> Treat the plan as the product and architectural source of truth. The primary constraint is that the application must operate entirely on the Android phone. Do not introduce a required PC, remote executor, server-side agent, Termux dependency, Shizuku dependency, or cloud runtime.
>
> Begin at Milestone 0 and proceed in dependency order. Do not skip directly to the AI layer. The deterministic storage, plan-validation, journal, and undo layers must exist and be tested before autonomous planning is allowed to mutate real files.
>
> Use Kotlin and Jetpack Compose. Prefer current stable Android/Jetpack dependencies rather than blindly pinning versions written in this planning document. Target modern Android, with minSdk 30 unless implementation evidence establishes a strong reason to change it.
>
> Maintain the safety architecture:
>
> `user intent -> planner -> typed operation manifest -> deterministic validator -> deterministic executor -> transaction journal`
>
> The model must never receive unrestricted filesystem mutation primitives.
>
> Work in small buildable milestones. At the end of each milestone:
>
> 1. compile the project,
> 2. run relevant automated tests,
> 3. inspect warnings/errors,
> 4. fix regressions,
> 5. record what is complete and what remains,
> 6. keep the application runnable.
>
> Prioritize a trustworthy working file organizer over breadth. Do not add unrelated assistant features until the V1 acceptance test in the plan passes.
