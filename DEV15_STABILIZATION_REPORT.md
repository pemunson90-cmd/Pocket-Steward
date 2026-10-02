# Dev15 stabilization report — 2026-10-02

Priority: file integrity, then app/native crashes and phone responsiveness. This report resolves the implementation findings in [CODE_AUDIT_DEV14_2026-10-02.md](CODE_AUDIT_DEV14_2026-10-02.md). Physical-phone acceptance and the app-completion goal remain open.

## Audit resolutions

| Finding | Repair | Evidence |
|---|---|---|
| A01: destination collisions / unsafe cleanup | Direct moves use kernel `renameat2(RENAME_NOREPLACE)`, including folders. Copy/write destinations use exclusive creation. Failure retains both paths, without deleting a possibly external destination. Unsupported folder moves are refused; missing native library becomes an IOException. | Actual production C/JNI and Java wrapper on Linux: Unicode, file/folder collisions, malformed paths, 200 competing creates, missing-library refusal. Android gateway/native tests compiled; device execution pending. |
| A02: canceled filing remains busy | Clear busy on cancellation and Idle routing; disable selection during rebuild. | Actual ViewModel methods invoked on host with Android-only constructor bypassed. |
| A03: expensive selection scans | Cache dependency graph per operation-list identity; iterative parent/child selection and pruning. Clear retained graphs on scan/reset. | Existing nested selection regressions and 1k/4k/16k checkbox measurements with 800 generated folders. |
| A04: new indexing roots discarded | Ordered, coalescing admission queue on Main; drain until empty. Interrupted pending roots become paused in their current lifecycle. | Queue admission/coalescing tests and serialized completion trace; foreground-service device acceptance pending. |
| A05: cursor skips earlier new/changed files | Reconcile current manifest from the beginning, reuse verified cache, prune stale memberships, reset manifest counters. | Pause b/c, add a, resume: a/b/c indexed, completed count three. |
| A06: full content downgraded by concurrent filing | Shared document/root locks across repositories and short cache commit gate. | Hold filing extraction while full indexing waits; final FULL content/coverage retained. Cleared lifecycle rejects older queued roots. |
| A07: short reads miss duplicates | Fill prefix to EOF/limit, handle zero reads, check cancellation; version prefix hashes and rebuild legacy hashes. | One-byte provider reads group identical files; mismatched legacy prefix caches are recomputed. Full-SHA/cache tests retained. |
| A08: stale snapshots revert choices | Merge index-produced fields into latest matching review. Destination edits retain latest same-plan selection and reject replacement reviews. | Delayed-update regressions preserve filter/sort/deselection and reject another query, scope, screen or plan. |

## Crash and interruption defenses

Cache clearing pauses scheduling, invalidates its lifecycle, then clears records under a short commit gate. The live Room database stays open and retained DAOs remain usable. Older extraction/root work cannot recreate cleared records. Retiring runners cannot pause a newer lifecycle or a root another runner owns. Android tests cover concurrent readers, FTS/scope clearing and retained-DAO reuse; they are not executed here.

Direct copy/hash loops and baseline traversal check cancellation. Partial operations retain the durable PENDING journal and conservative recovery. The search watcher reports ordinary exceptions through its error state and canceled watchers cannot publish. Foreground admission failures preserve tasks and use the existing fallback where permitted.

The native helper uses bounded stack buffers, explicit UTF-8 arrays, length/NUL checks, JNI exception checks and one no-replace syscall; no recursive traversal or unbounded allocation. Minimum API is 30. [Android 11 Bionic](https://android.googlesource.com/platform/bionic/+/refs/tags/android-11.0.0_r1/libc/SYSCALLS.TXT) lists renameat2 for all architectures in its base syscall list; it is absent from the app/common seccomp blacklists. This does not prove vendor-filesystem support. The release must include arm64 code and pass 16 KiB alignment checks.

No Android app/native crash, ANR or system reboot has been reproduced here: no device/emulator or KVM is available. Existing image/extraction budgets are retained. Peak memory, sustained thermal/battery load and the actual 16k corpus remain unverified.

## Validation

The final full run passed **669 unit tests** (zero failures/errors/skips), `lintDebug` (zero errors, 85 warnings), `assembleDebug` and `assembleDebugAndroidTest`. All three exact SQLite checks, six release-tool safety tests and production native host tests passed. Android instrumentation was compiled, not executed. Signing/publication results are appended after completion.

| File moves | Generated folders | Initial full-run checkbox time, ms |
|---|---:|---|
| 1,000 | 800 | 1 / 1 / 1 |
| 4,000 | 800 | 2 / 3 / 3 |
| 16,000 | 800 | 4 / 3 / 3 |

The earlier targeted dev15 run measured 8–9 ms at 16k; dev14 audit measured 351–467 ms. These are shared-host observations without timing assertions; they exclude phone rendering, scanning, IO, ML and memory/thermal limits. Original audit probes describe pre-fix bugs and remain historical evidence, separate from the regression suite.

## Required physical-phone acceptance

| Scenario | Pass criteria |
|---|---|
| 1k / 4k / 16k scan, search and filing | Responsive UI, measured elapsed time/peak memory; no app/native crash, ANR or reboot. |
| Sustained extraction/image evidence | Bounded admission, working cancel, measured memory/thermal/battery behavior; no runaway retries. |
| Cancel, activity recreation, background/foreground, process interruption and service timeout | Durable pause/resume; no stale busy state, lost selections or automatic approval. |
| Clear index during search/index/filing | No closed-DB exception, late restoration or old runner pausing newer work; new indexing works. |
| Emulated storage, granted trees, extra volumes, Unicode and intact folders | Collision refusal, supported verified fallback; unsupported operations preserve sources. |
| Competing creates, revoked permission, low storage, interruption and Undo | External bytes intact; recoverable source/journal; ambiguous partial destinations need explicit review. |

ACCEPT-01 remains open. Device-discovered defects must be repaired before whole-app completion.
