# Pocket Steward — build status

Tracking against `POCKET_STEWARD_ANDROID_FILE_AGENT_PLAN_V0_1.md`, Section 20.

## Milestone 0 — Skeleton: built and verified

Compiled successfully in a second environment with real access to Google's Maven repo (this sandbox blocks `dl.google.com`, see below). `assembleDebug` and `testDebugUnitTest` both green, Room's KSP processor generated `AppDatabase_Impl.kt` and all three DAO impls, and the resulting debug APK installs and launches (`com.pocketsteward.app.MainActivity`).

What's in this commit:

- Gradle project (Kotlin DSL), Gradle wrapper 8.13, AGP 8.13.2, Kotlin 2.3.20, Compose BOM 2026.06.01.
- Single `app` module (plan Section 19 explicitly allows collapsing modules for V1 velocity; package boundaries are kept as sub-packages: `storage`, `data.db`, `data.settings`, `ui.*`, `navigation`, `di`).
- `StorageGateway` interface plus `DirectStorageGateway` / `SafStorageGateway` stubs (bodies are `TODO()` — real implementations are Milestone 1/2 work, deliberately not written yet so a half-correct version never gets exercised against real files).
- Room database with the `FileRecord`, `TaskRun`, `MutationRecord` entities from plan Sections 7 and 15, wired but empty (no scanner writes to it yet).
- DataStore-backed `SettingsRepository`: storage access mode, granted SAF tree URI, and the four privacy switches from Section 23, defaulted to the narrowest setting.
- Permission onboarding screen: requests `MANAGE_EXTERNAL_STORAGE` or lets the user pick a SAF tree instead; remembers the choice across restarts.
- Home screen (quick-action tiles are inert placeholders — wired up starting Milestone 4/5) and Settings screen (privacy toggles, live).
- One JVM unit test pinning the privacy-default contract.

**Why this wasn't compiled in the environment that wrote it:** this sandbox's outbound network policy blocks `dl.google.com` (and `maven.google.com`, which redirects there), which is where the Android Gradle Plugin, Android SDK platform/build-tools, and most androidx artifacts are hosted — confirmed account-wide, not just this session. A separate environment with real internet access compiled it instead and reported back three real build-time fixes, folded into this commit:

1. `android { kotlinOptions { jvmTarget = "17" } }` is a hard error under Kotlin 2.3.x (the string-based accessor was removed) — replaced with the `kotlin { compilerOptions { jvmTarget.set(...) } }` DSL.
2. Compose BOM 2026.08.00 resolves to Compose 1.12.0, which requires `compileSdk 37` / AGP 9.1+ — downgraded to BOM 2026.06.01 (Compose 1.11.4), which stays inside AGP 8.13 / compileSdk 36. Moving to Compose 1.12 later means moving AGP and compileSdk together, not in isolation.
3. `material-icons-extended` → `material-icons-core`: the app only uses two icons (`Settings`, `ArrowBack`), and `-extended` alone added ~23 MB of unminified dex. Also fixed the `Icons.Filled.ArrowBack` deprecation (→ `Icons.AutoMirrored.Filled.ArrowBack`) while touching that file.

**What this means for you:** the project as committed now matches what actually compiled. If you build it again yourself (Android Studio, or `./gradlew assembleDebug` on a machine with normal internet), it should go straight through.

## Version choices and why

- **Room 2.8.4, not Room 3.0.** Room 3.0 shipped this March (Kotlin-only codegen, `androidx.room3` package, mandatory `SQLiteDriver`, its own Gradle plugin) — genuinely the current direction, but new enough that I'd be writing its `@Database`/driver wiring from documentation snippets with no compiler to catch a mistake. Room 2.x is a decade-deep, extremely well-trodden API I can write correctly with high confidence blind. Worth migrating later; not worth the risk on a skeleton I can't test.
- **Navigation Compose 2.8.4, not the newer Navigation 3.** Same reasoning — Navigation 3 (stable since Nov 2025) has a different ownership model for the back stack that I'm less certain of in detail; the older library is simpler and unambiguous.
- **No Hilt/Dagger.** Manual `AppContainer` instead — one more KSP processor is one more version-matching surface I can't verify here, and the M0 dependency graph is small enough not to need it yet.
- Compose BOM 2026.06.01 (Compose 1.11.4) rather than the newest 2026.08.00 (Compose 1.12.0), which needs AGP 9.1+/compileSdk 37 — see the fix log above. Kotlin 2.3.20 stayed as originally chosen; AGP 8.13.2 explicitly documents support for it.

## Milestone 1 — File inventory: written, partially verified

Deliverables per Section 20: storage gateway (real, not stubbed), recursive scanner, Room index, scan progress, inventory UI, resume support.

What's new:

- `DirectStorageGateway` and `SafStorageGateway` now have real `rootOf`/`listChildren`/`stat` — java.io.File for Direct, `DocumentFile` for SAF. Mutation methods (`move`, `rename`, `createDirectory`, `trash`, `openRead`) are still `TODO()` — that's Milestone 2.
- `FileScanner`: iterative (not recursive-call) breadth-first walk, Level 0 metadata only (Section 7). Writes to Room per directory visited, and persists a resumable checkpoint (`ScanCheckpoint`) in the same step, so a crash mid-scan loses at most the directory in flight, not the whole scan — re-listing that one directory is idempotent because `FileRecord.stableRef` now has a unique index (`OnConflictStrategy.REPLACE` against it, not the autogenerated id, which was a latent bug in the Milestone 0 schema — re-scanning would have silently duplicated every row).
- `FileCategory`: a minimal extension→category lookup (Image/Document/APK/Archive/Audio-Video/Other) for the Scan Summary screen. Deliberately not the Milestone 4 rule engine — no confidence, no reasons, no project keywords.
- Storage Scope screen (Section 16): pick Downloads/Documents/Pictures/Everything (Direct mode) or the one granted folder (SAF mode); shows live progress while scanning, then the category-count breakdown. Reachable from a new "Scan storage" button on Home.
- `FileRecord` gained a `scopeRootRef` column so the summary query can select "everything under this scan" without relying on path-prefix matching, which isn't reliable for SAF content URIs. DB version bumped to 2 with `fallbackToDestructiveMigration` — schema is still moving milestone to milestone and there's no scan data worth preserving yet.

**What's verified vs. reviewed-only, and why the difference matters:** `FileCategory.kt`, `FileRefCodec.kt` (the checkpoint queue's serialize/deserialize), and the pure model types in `StorageModels.kt`/`StorageGateway.kt` have zero Android imports, so I pulled them into a throwaway plain-Kotlin Gradle module (`kotlin("jvm")`, JUnit + Truth from Maven Central, no `google()` needed) and actually compiled and ran the tests against a real Kotlin 2.3.20 compiler in this sandbox — all 5 pass, including a round-trip test with a content URI containing colons. `FileScanner`, `AppDatabase`, both gateways, and the new UI can't be compiled here (Room and Compose aren't on Maven Central) — those were reviewed by hand, not proven, and that review was wrong in one place (see below).

**Round 1 fix-forward, from an actual build.** `assembleDebug` and `testDebugUnitTest` both went green (7 tests) in a real environment. Three things came back:

1. `DocumentFile.fromSingleUri` is `@Nullable` in `documentfile:1.0.1` — Kotlin refused to compile the unchecked call in `SafStorageGateway`. One-line null check, no behavior change.
2. **My own hand-review of `SafStorageGateway` was wrong.** I'd written the doc comment claiming `DocumentFile.fromTreeUri` "only ever resolves a tree's root document no matter what URI you pass it," and switched `listChildren`/`stat` to `fromSingleUri` to work around that. The actual library source contradicts that claim — `fromTreeUri` checks `DocumentsContract.isDocumentUri` and honors the specific document it's given when that's true, which `rootOf()`'s normalization already sets up correctly. `fromSingleUri` was the wrong fix: it returns a `SingleDocumentFile`, whose `listFiles()` unconditionally throws `UnsupportedOperationException` — so SAF-mode scanning compiled fine and failed at runtime on every node, root included. Reverted to `fromTreeUri`. Also found: `ScanViewModel.resolveRoot` was wrapping the raw stored SAF tree URI directly instead of calling `gateway.rootOf()`, so even the fixed gateway would have received an un-normalized root. Both fixed.
3. Scanning ran on `viewModelScope.launch`'s default dispatcher (`Dispatchers.Main.immediate`), so `DirectStorageGateway`'s blocking `java.io.File` calls were blocking the UI thread — fine for Downloads, a likely ANR for "Everything." Wrapped in `withContext(Dispatchers.IO)`.

`BUILD_ENVIRONMENT.md` now carries the working SDK setup and the load-bearing version constraints so that doesn't need rediscovering each round, and `app/schemas/` is now committed (Room's `exportSchema` output) rather than gitignored, since it costs nothing now and starts mattering once real migrations replace `fallbackToDestructiveMigration`.

**Round 2: verified on real hardware, zero build changes, one runtime bug found and fixed.** `assembleDebug`/`testDebugUnitTest` went green with no source changes needed — round 1's three fixes held. Broad-access scanning was exercised twice: once on an emulator against a planted 11-file corpus (5,498,880 bytes, exact category split, two levels of recursion — all matched), once on a real phone. That's the Milestone 1 exit criterion (accurate Downloads/Documents/Pictures inventory) actually confirmed, not just built.

One new bug, found by running it rather than reading it: **the onboarding grant race.** `OnboardingScreen`'s launcher callbacks (`manageStorageLauncher`, `openTreeLauncher`) called `onAccessGranted()` — which navigates away and pops the Onboarding destination — immediately after firing `viewModel.onBroadAccessGranted()`/`onSafTreeSelected()`. Those writes run in `viewModelScope.launch`, and popping the destination cancels that scope, so the DataStore write could lose the race against navigation and get silently cancelled mid-flight — the grant would appear to succeed but never actually persist. Fixed by making the `LaunchedEffect` watching the *persisted* `storageAccessState` (widened from SAF-only to "any mode") the single source of truth for navigation, and removing the premature direct calls from both callbacks. Navigation now only happens once the write is observed as actually landed.

**Still open, both deliberately parked for Milestone 2 rather than fixed here:** the SAF path is unverified on-device (only Direct/broad-access has been exercised for real), and there's no in-app way to switch from broad access to a SAF folder once broad access is already granted — onboarding only offers the choice once, up front.

**Exit criterion: met for Direct/broad-access mode**, confirmed on real hardware. SAF mode still needs its own on-device pass.

## Milestone 2 — Deterministic file manager: written, partially verified

Deliverables per Section 20: create folder, move, rename, copy if useful, collision handling, operation manifest, validator. Exit: a hard-coded plan can safely reorganize real files.

**Trash design, per explicit direction:** no operation anywhere in this app ever permanently deletes a file — that's not a policy toggle, there is no code path for it. "Trash" moves a file into `PocketSteward/Trash/<mirrors its original path>` under external storage (so a file at `Download/foo.apk` lands at `PocketSteward/Trash/Download/foo.apk`), and nothing in the app ever empties that folder — that stays a manual, outside-the-app action, permanently. This goes further than plan Section 14 itself, which left room for a later in-app "empty quarantine with confirmation" command; that command will not be built.

What's new:

- **`plan` package** (`PlanModels.kt`, `FileIndex.kt`, `PlanValidator.kt`) — pure Kotlin, zero Android imports, by design. `PlannedOperation` (CreateDirectory/Move/Rename/Trash) is the typed manifest from Section 11: nothing in this app ever hands a model (or a button) a raw `move(path, path)` primitive, only this. `PlanValidator` runs the Section 12 checks — source exists, no path traversal, no self-move, no recursive move into itself, destination collision (exact and case-insensitive), no overwrite without explicit policy — as one pre-pass over the whole plan, operation-by-operation, excluding failures rather than rejecting the batch (Decision 5: "leave this alone" is a correct per-operation outcome). `safetyClass()` classifies GREEN/YELLOW/RED (Section 13) from operation type alone — never from a model's own confidence, closing a gap flagged in the original plan review.
- **Verified for real**, same throwaway plain-Kotlin module as Milestone 1: 12 new tests, all passing. That process caught a real bug before it shipped — the case-insensitive collision check could match the *source itself* when renaming a file to a pure case variant of its own name (e.g. normalizing `FOO.txt` → `foo.txt`), masking a genuine collision when a *different* file already held that exact case-variant name. Fixed by adding an `excluding` parameter to `FileIndex.caseInsensitiveMatch` rather than trying to compare identity after the fact.
- **`DirectStorageGateway`** now implements `createDirectory`/`move`/`rename`/`trash` for real: `move`/`rename` try `File.renameTo` first and fall back to copy-then-delete for cross-filesystem moves (e.g. internal storage to an SD card), never deleting the source until the copy is confirmed. `trash` computes the mirrored destination and reuses the same move path.
- **`PlanExecutor`** (new `executor` package): validates against a fresh index snapshot, then runs only the accepted operations, journaling each as `MutationRecord` `PENDING` → `COMMITTED`/`FAILED` around the gateway call — same crash-safety pattern as `FileScanner`. Updates the moved/renamed file's own `FileRecord` row in place so a second operation later in the same plan sees the new location without a rescan; does *not* walk a moved directory's descendants (documented, not silently dropped — nothing in this milestone's own plan exercises that case). A trashed file's record is dropped rather than re-tagged, since Trash isn't itself a scan target.
- **UI**: Scan Summary gained "Test: move N APK(s) into an APKs subfolder" when APKs are present — Section 11's own canonical example, triggered by a button, not natural language. Shows a Plan Preview (each operation, its reason, accept/reject counts) before executing, matching Section 16's Plan Preview screen shape even without an AI behind it yet.

**Scope decision: `SafStorageGateway`'s mutation methods stay `TODO()` this round too.** `move`/`rename` need the source's *parent* document URI, which `DocumentsContract.moveDocument` requires and which SAF doesn't reliably expose from a single stored node URI — solving that is real work, not a quick follow-on, and Milestone 1's own on-device testing already flagged SAF as unverified and parked. Extending that same boundary rather than rushing a second unverified subsystem.

**What's verified vs. reviewed-only:** `plan` package — real compiler, real tests, as above. `DirectStorageGateway`'s new methods, `PlanExecutor`, `InMemoryFileIndex`, and the UI changes need Room/Compose and can't be compiled in this sandbox — reviewed by hand, not proven. Given Milestone 1's own track record, expect at least one real bug to surface on the next actual build.

**Round 1: exit criterion met on real hardware.** `assembleDebug`/`testDebugUnitTest` green — 19 unit tests, not the 24 loosely expected (that number assumed the prior suite was 12 tests; it was 7, so 12 new + 7 existing = 19, nothing missing). On Pat's own phone: granted broad access, scanned Downloads, opened the plan preview, approved it, and 17 APKs moved into `Downloads/APKs` for real.

One bug found by running it, not reading it: **the plan preview's Approve/Cancel row could be pushed off-screen with no way to reach it.** `PlanPreviewContent` put a `LazyColumn` in a plain `Column` with no `weight`, so once a plan had enough operations to fill the screen (18, in Pat's case) the list consumed all remaining height and the button row landed past the bottom edge with no scroll to reach it. A lazy list shorter than its constraint just sizes to content, which is why the 6-row category list on the summary screen never exposed this — the 18-operation plan preview was the first list long enough to hit it. Fixed with `Modifier.weight(1f)` on the list and `fillMaxSize()` on the Column; the same exposure applies to any future long list dropped into a plain `Column`, worth remembering.

Three more display-layer issues came back from the same round, diagnosed but left for me to fix rather than fixed on the spot (correctly — they're behavior/UX changes, not build fixes) — all three are now fixed in this tree:

1. **The completion screen overcounted moves.** `PlanExecutor` incremented one `succeeded` counter for every accepted operation, `CreateDirectory` included, so 17 files moved plus 1 folder created read as "18 moved." `ExecutionSummary` now tracks `foldersCreated`/`filesMoved`/`filesRenamed`/`filesTrashed` separately.
2. **The plan preview couldn't show where anything was going.** `operationSummary` rendered both sides of a move as a bare filename (`rawValue().substringAfterLast('/')`), so a move into a subfolder always read `X.apk → X.apk` — correct plan, unreadable preview, which matters because the preview's whole job is letting a human see what's about to happen before it happens. Now shows the last two path segments (parent folder + filename) on each side, so the same move reads `Download/foo.apk → APKs/foo.apk`.
3. **Rejected operations were invisible.** The preview only ever rendered `plan.operations` (everything, including what the validator had already rejected) against counts that reflected accepted-only, with nothing distinguishing which was which or why. `ScanUiState.PlanPreview` now carries the validator's accepted and rejected lists separately; rejected operations render under a "Left untouched" heading with their actual rejection reason, which `RejectedOperation` was already carrying and nothing surfaced.

Also bumped `versionCode`/`versionName` (3 / `0.3.0-milestone2`) so this build is distinguishable from Milestone 1 on-device — it had stayed at Milestone 1's stamp through all of Milestone 2 until now.

**Carried over from Milestone 1, still genuinely open:** SAF mode has never been exercised on a device — the `fromTreeUri` fix and `rootOf()` normalization are in place and compile, but synthetic taps can't drive Android's DocumentsUI folder picker in an emulator, and Pat has only ever used broad access. Treat it as unverified, not as parity with Direct mode, until someone actually taps through it on a phone. There's also still no in-app path back to SAF once broad access is already granted (onboarding auto-advances past the choice) — OS-level revoke is the only way back right now.

## Not started

Milestones 3–7 (journal + undo, rule engine, NL parsing, on-device AI, polish) per the plan's own sequencing — Milestone 3 (journal + undo) must be solid before Milestone 6 (AI planning) is allowed to touch real files, per Decision 2. Milestone 2 writes the journal rows; it doesn't yet compute or expose an inverse/undo — that's what Milestone 3 adds on top of what's here now.
