# Pocket Steward dev14 code audit — 2026-10-02

Dev15 follow-up: A01–A08 repairs and crash/interruption defenses are documented in [DEV15_STABILIZATION_REPORT.md](DEV15_STABILIZATION_REPORT.md). The findings below describe the original dev14 baseline; physical-phone acceptance remains open.

Audited app source: local `a8c939cfd65b5b5f74b0bb06401cb24bb39da3f2`, as retained by documentation HEAD `5ad6fa5`. It is the dev14 app tree published as GitHub source `c967b176235f802c56710a8698e8ab1e5f4b8533`. This audit precedes further feature work.

**Assessment:** the typed-plan, approval, journal, and recovery architecture is sound in its overall separation of responsibilities. The review state and content-index scheduling are less cohesive. There are reproducible functional defects and a measured UI bottleneck; another feature release should address these first. No physical-device crash or ANR was reproduced: no Android device or emulator is attached.

Evidence labels below distinguish execution of app methods on the host JVM, deterministic source tracing, and filesystem experiments. Missing planned features are not counted as newly discovered bugs. Prior successful tests do not establish Android performance or absence of crashes.

## Findings and repair order

P1 means address before the next feature checkpoint because of possible file loss or a blocked core review. P2 means a functional or scaling defect that should be repaired in the same stabilization work.

| ID | Priority | Finding | Evidence |
|---|---|---|---|
| A01 | P1 | Direct moves do not enforce no-overwrite atomically against external writers. | Source plus reproduced host filesystem primitive; no phone file-loss incident claimed. |
| A02 | P1 | Cancelling filing can leave the UI busy and approval disabled. | Actual cancellation and Idle-routing methods reproduced on JVM. |
| A03 | P1 | A checkbox change scans a large plan repeatedly on the UI thread. | Actual selection function benchmarked at 16,000 files. |
| A04 | P2 | An indexing request for another root is discarded while the service is busy. | Deterministic service/queue/watcher trace. |
| A05 | P2 | Index resume can skip added/changed files before its cursor and still report completion. | Actual repository reproduced on JVM. |
| A06 | P2 | Concurrent filing/indexing writers can downgrade full searchable text to a truncated excerpt. | Two actual repositories sharing one DAO reproduced on JVM. |
| A07 | P2 | Legal short reads cause identical files to miss duplicate detection. | Actual DuplicateDetector reproduced on JVM. |
| A08 | P2 | Async snapshot writes can overwrite newer filter or selection choices. | Deterministic coroutine/state-update trace. |

### A01 — destination collision can overwrite an external file

Location: [DirectStorageGateway.kt:258](app/src/main/java/com/pocketsteward/app/storage/DirectStorageGateway.kt#L258), particularly `destinationFile.exists()` followed by `sourceFile.renameTo(destinationFile)` at line 273. Copy cleanup also deserves the same ownership review at lines 175–178 and 336–346.

Interleaving: destination is absent at the check; another app creates it; rename executes. `File.renameTo` is not a no-replace primitive. In the host filesystem experiment, that exact primitive returned success and replaced the intervening destination's contents. The shared mutation mutex serializes Pocket Steward's runners; it cannot serialize another app. Preview validation and the journal do not close this gap, and the overwritten external file would not have its own recovery record.

Repair: enforce no-replace at the actual filesystem boundary, and remove partial destinations only when ownership of that created file has been established. Test collisions injected between preflight and the mutation, including copy failure cleanup and ordinary text writes. Another existence check, or assuming `ATOMIC_MOVE` also means no-replace, is insufficient. Validate the chosen primitive on supported Android filesystems. This is a code-confirmed safety gap; its filesystem behavior was reproduced on the host, not on the phone.

### A02 — cancellation does not clear the busy projection

Locations: [ScanViewModel.kt:579](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModel.kt#L579), [ScanViewModel.kt:674](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModel.kt#L674), [PlanPreviewScreen.kt:517](app/src/main/java/com/pocketsteward/app/ui/scan/PlanPreviewScreen.kt#L517).

After Working has been routed, `_busy` holds the working indicator. Cancel cancels the jobs and emits Idle. Idle routing is `Unit`, so `_busy` remains set. Cancelled filing coroutines rethrow cancellation and do not publish a completion state to clear it. Approval requires `busy == null`; reopening the preview also returns early while busy. The user can therefore finish cancellation but remain blocked until another state-clearing action or ViewModel recreation.

The probe invoked the real cancellation and Idle methods, confirming the job was cancelled, the event was Idle, and busy still said Planning. Android-only constructor dependencies were bypassed for this narrowly scoped probe; it did not simulate Compose or navigation.

Repair: clear the working projection when its owning job is cancelled, with job identity/revision checks so an old cancellation cannot clear a newer job. Cover Cancel, Back, restart, and reopening the saved review.

### A03 — checkbox dependency pruning costs hundreds of milliseconds

Locations: [PlanSelection.kt:75](app/src/main/java/com/pocketsteward/app/plan/PlanSelection.kt#L75), [PlanSelection.kt:133](app/src/main/java/com/pocketsteward/app/plan/PlanSelection.kt#L133), [ScanViewModelPlanEditing.kt:620](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModelPlanEditing.kt#L620).

For every selected CreateDirectory, pruning scans all operations to find dependents. A pass costs roughly directories × operations, with additional passes possible. The checkbox handler calls it synchronously and also performs additional directory/selection scans. Unlike the earlier engine/adaptor benchmark, this work is on the UI thread.

Host measurements of just `PlanSelection.setSelected`, deselecting one file in an initially selected plan with 200 project homes and three new role folders per home (800 directory operations):

| Files | Three calls, milliseconds |
|---|---|
| 1,000 | 208, 100, 28 |
| 4,000 | 251, 93, 191 |
| 16,000 | 467, 358, 351 |

These are synthetic host measurements, not phone timings or ANR evidence. They exclude the remainder of the checkbox handler, Compose recomposition, and draft persistence. The demonstrated cost is already far above a frame budget. Plans reusing existing folders have fewer creates and may cost much less.

Repair: build parent/dependent indexes once for a plan revision, maintain selected-dependent counts, and update only affected ancestors. Measure complete checkbox latency on the phone. Preserve the current dependency semantics.

### A04 — a second indexing root can stay queued without a runner

Locations: [ScanViewModelIndexedSearch.kt:165](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModelIndexedSearch.kt#L165), [ContentIndexForegroundService.kt:78](app/src/main/java/com/pocketsteward/app/service/ContentIndexForegroundService.kt#L78), [ScanViewModelIndexedSearch.kt:248](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModelIndexedSearch.kt#L248).

Search queues a durable job for root B and starts the indexing service. If that service is indexing root A, ACTION_RUN returns immediately without retaining B. The running loop processes its original roots only. B stays QUEUED, and the search watcher treats QUEUED as nonterminal, continuing to poll once a second without making progress. Startup recovery may rescue it after process recreation; another successful start after A finishes may also rescue it. Neither drains B automatically at A's completion.

Repair: a durable root queue with one coordinator that drains new requests, acknowledges admission, and handles foreground/background handoff. Test requesting B while A is blocked. The Android service lifecycle still needs an instrumentation/device test.

### A05 — resume assumes an unchanged ordered inventory

Location: [ContentIndexRepository.kt:315](app/src/main/java/com/pocketsteward/app/content/index/ContentIndexRepository.kt#L315).

Resume starts at the first reference greater than the saved cursor. It does not bind that cursor to a manifest or inventory revision. Probe: index b.txt, pause before c.txt, add a.txt, resume with a/b/c. The repository indexed b and c only, then reported COMPLETED with processedCount 3. Changes to an already processed file are similarly skipped until a fresh run. Pruning only when startIndex is zero also leaves deleted memberships behind on such a resume; search freshness verification reduces the stale-evidence impact but does not correct completion accounting.

Repair: persist the candidate inventory revision/manifest, or reconcile all current candidates against per-file evidence on resume. Completion must mean every current eligible candidate was accounted for, and stale memberships must be reconciled independently of cursor position.

### A06 — transactions do not serialize the inspection lifecycle

Locations: [AppContainer.kt:130](app/src/main/java/com/pocketsteward/app/di/AppContainer.kt#L130), [ContentIndexRepository.kt:40](app/src/main/java/com/pocketsteward/app/content/index/ContentIndexRepository.kt#L40), [ContentIndexDao.kt:131](app/src/main/java/com/pocketsteward/app/content/index/ContentIndexDao.kt#L131).

Foreground indexing, charging workers, and filing create independent repositories sharing the content DB. Each loads an existing document before extraction; there is no shared root/document ownership mechanism. Room makes an individual replaceDocument atomic but cannot make the earlier read/extract decision atomic.

Probe: pause a FILING extraction after its initial cache lookup; complete a FULL root index through another repository; release the filing extraction. The final document profile became FILING with incomplete coverage, while the root retained completed=true. Full passages disappeared from the searchable cache despite the full index having finished. This reproduces an ordinary writer interleaving with unchanged source bytes, not an exotic provider failure.

Repair: shared per-document inspection coordination plus root job ownership; recheck the current cache at commit and never replace equivalent fresh FULL evidence with lower coverage. Exercise overlapping foreground, charging, filing, pause, and clear requests.

### A07 — quick fingerprints assume one read fills the window

Location: [DuplicateDetector.kt:95](app/src/main/java/com/pocketsteward/app/dedupe/DuplicateDetector.kt#L95).

The quick stage hashes a single `stream.read(buffer)`. InputStream may legally return fewer bytes than requested before EOF, including streams provided through SAF. Two identical files can therefore hash different prefix lengths and be separated before the full SHA comparison. The probe supplied the same complete bytes through an ordinary stream and a stream returning one byte per read. Actual duplicate detection returned no group.

Repair: fill the bounded quick window until EOF, with cancellation and zero-read handling. Include a quick-fingerprint algorithm version or invalidate old cached prefixes; repairing the read loop alone will retain previous short-read values stored in FileRecord.

### A08 — stale async snapshots overwrite current user choices

Locations: [ScanViewModelIndexedSearch.kt:224](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModelIndexedSearch.kt#L224), [ScanViewModelIndexedSearch.kt:241](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModelIndexedSearch.kt#L241), [ScanViewModelPlanEditing.kt:130](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModelPlanEditing.kt#L130), [ScanViewModelPlanEditing.kt:590](app/src/main/java/com/pocketsteward/app/ui/scan/ScanViewModelPlanEditing.kt#L590).

The search poller captures `current`, suspends for IO, then writes `current.copy(...)`. If the user changes sort or filters during that IO, the poller restores the older choices. It also checks review identity only before IO, allowing an old result to replace a newer review after the suspension. Generic plan edits have the same pattern: capture a preview, suspend while validating, and publish its old selectedIndices even if the user changed selections meanwhile. The editors are not uniformly governed by the tracked filing jobs.

Repair: publish against the latest state using an atomic update and identity/revision checks; merge only fetched data, preserving current preferences. Reject edits against a replaced review. Test deliberately delayed IO with a filter change, deselection, and new review during the wait. This is a source-confirmed interleaving; no phone reproduction is claimed.

## Cohesion and maintainability

There are 205 production Kotlin/Java files, about 37,326 lines. The ScanViewModel family alone spans 15 files and 6,348 lines; the main file is 1,719 lines, ReviewScreen 1,970, and PlanPreviewScreen 1,224. Size alone is not a bug. The concrete problem is shared mutable ownership across those extension files:

- `_uiState` acts as a StateFlow event bus while `_busy`, `_review`, `_preview`, and `filingSession` are separately written projections. A02 and A08 show these becoming inconsistent.
- Some work has owned, cancellable jobs; other edits and reviews launch independent coroutines that can publish after navigation or a newer action.
- Index job admission, foreground execution, background execution, and document cache commits have different owners. A04 and A06 show missing coordination between them.

Refactor by ownership: a review state/reducer with revisions, a filing session coordinator, and a content-index coordinator. Keep filesystem mutation authority in the existing typed executor. Splitting more files without changing state ownership will not solve these defects. A new dependency-injection framework is not required to make these repairs.

## Risks requiring Android verification; not confirmed crashes

1. **Clear content index during active use.** Settings calls `ContentSearchDatabase.delete`, which closes/deletes the singleton without stopping and joining its readers/writers. The polling coroutine has no exception boundary, and retained DAO users can continue after the clear. Verify closed-connection errors, an unexpected cache rebuild, and the application's unhandled coroutine behavior on Android. Coordinate clear with indexing and replace retained DAO ownership. No device crash is asserted from this audit.
2. **Long operations delay pause/cancellation.** SourcePreconditions.capture and duplicate/gateway hash loops perform synchronous reads/walks without per-chunk coroutine checks. Folder capture has a 100,000-entry bound, but cancellation may wait for a substantial walk or full file hash. Some waiting at a mutation boundary is intentional; measure that latency rather than calling it a deadlock.
3. **A metadata refresh can hold the shared mutation gate for a full library walk.** Serialization protects the journal, but an approved foreground task may wait behind a slow background scan. Verify wait-time visibility, pause behavior, and priorities with the 16k library and a slow provider. No lock cycle/deadlock was established.

## What passed and what remains unmeasured

The six audit probes reproduced the behavior above and passed their observation assertions. They intentionally assert current dev14 behavior, including defects; they are preserved outside the normal app test suite in [tools/audit/dev14/Dev14AuditProbeTest.kt](tools/audit/dev14/Dev14AuditProbeTest.kt), with [raw measurements](tools/audit/dev14/PROBE_RESULTS.txt). They are not regression acceptance tests for repaired behavior.

Baseline validation: the 655 existing app unit tests were rerun and passed; lint completed with zero errors and 85 warnings. The warnings concern KTX/style suggestions, unused resources, dependency updates, compatibility metadata, and the usable-space fallback; they do not establish additional crashes. The three real SQLite query/membership checks and six release-tool tests pass. Actual mutation/recovery gate sharing was checked in AppContainer; it is present. Scanner writes are batched, content/archive extraction has bounds, image review admission is bounded, and most critical cancellation catches propagate cancellation. These are useful safeguards, but do not resolve the findings above.

Android filesystem races, lifecycle/foreground-service behavior, native PDF/media/ML failure paths, peak phone memory, UI frame timings, and battery costs remain unmeasured. The user's actual 16k corpus is not mounted here. Known unfinished discovery, evidence, SAF, and model features remain in the completion register and are not counted as audit defects.

Repair acceptance should cover: external destination races; Cancel/Back/reopen; root-B admission during root-A indexing; inventory changes across resume; simultaneous FULL/FILING writers; short-read streams and old fingerprint invalidation; delayed fetch/edit publication; and complete large-plan checkbox latency. Keep the existing mutation, recovery, undo, and scope-query gates green while repairing these paths.
