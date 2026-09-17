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

None of these three are re-verified by an actual build yet — that's the next round. `BUILD_ENVIRONMENT.md` now carries the working SDK setup and the load-bearing version constraints so that doesn't need rediscovering each round, and `app/schemas/` is now committed (Room's `exportSchema` output) rather than gitignored, since it costs nothing now and starts mattering once real migrations replace `fallbackToDestructiveMigration`.

**Exit criterion not yet confirmed on-device:** scanning Downloads/Documents/Pictures and seeing accurate counts/sizes, in both Direct and SAF mode.

## Not started

Milestones 2–7 (deterministic executor + validator, journal/undo, rule engine, NL parsing, on-device AI, polish) per the plan's own sequencing — Milestone 3 (journal + undo) must be solid before Milestone 6 (AI planning) is allowed to touch real files, per Decision 2.
