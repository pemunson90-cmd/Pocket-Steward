# Pocket Steward — build status

Tracking against `POCKET_STEWARD_ANDROID_FILE_AGENT_PLAN_V0_1.md`, Section 20.

## Milestone 0 — Skeleton: written, not yet compiled

What's in this commit:

- Gradle project (Kotlin DSL), Gradle wrapper 8.13, AGP 8.13.2, Kotlin 2.3.20, Compose BOM 2026.08.00.
- Single `app` module (plan Section 19 explicitly allows collapsing modules for V1 velocity; package boundaries are kept as sub-packages: `storage`, `data.db`, `data.settings`, `ui.*`, `navigation`, `di`).
- `StorageGateway` interface plus `DirectStorageGateway` / `SafStorageGateway` stubs (bodies are `TODO()` — real implementations are Milestone 1/2 work, deliberately not written yet so a half-correct version never gets exercised against real files).
- Room database with the `FileRecord`, `TaskRun`, `MutationRecord` entities from plan Sections 7 and 15, wired but empty (no scanner writes to it yet).
- DataStore-backed `SettingsRepository`: storage access mode, granted SAF tree URI, and the four privacy switches from Section 23, defaulted to the narrowest setting.
- Permission onboarding screen: requests `MANAGE_EXTERNAL_STORAGE` or lets the user pick a SAF tree instead; remembers the choice across restarts.
- Home screen (quick-action tiles are inert placeholders — wired up starting Milestone 4/5) and Settings screen (privacy toggles, live).
- One JVM unit test pinning the privacy-default contract.

**What I have not done: compiled it.** This sandbox's outbound network policy blocks `dl.google.com` (and `maven.google.com`, which redirects there), which is where the Android Gradle Plugin, Android SDK platform/build-tools, and most androidx artifacts are hosted. `mavenCentral()` works fine, `google()` does not. I confirmed this concretely: running `gradle wrapper` against this project resolves and parses every build file correctly, then fails at exactly one point — fetching `com.android.application:8.13.2` — which is the expected and only failure this blocker should cause.

**What this means for you:** open this folder in Android Studio (Quail or newer) on a machine with normal internet, or run `./gradlew assembleDebug` from a terminal there. First sync will pull the SDK components and dependencies (one-time, then cached). Send me whatever error comes back — the code was written carefully but not proven against a real compiler, so treat the first local build as the actual review pass, not a formality.

## Version choices and why

- **Room 2.8.4, not Room 3.0.** Room 3.0 shipped this March (Kotlin-only codegen, `androidx.room3` package, mandatory `SQLiteDriver`, its own Gradle plugin) — genuinely the current direction, but new enough that I'd be writing its `@Database`/driver wiring from documentation snippets with no compiler to catch a mistake. Room 2.x is a decade-deep, extremely well-trodden API I can write correctly with high confidence blind. Worth migrating later; not worth the risk on a skeleton I can't test.
- **Navigation Compose 2.8.4, not the newer Navigation 3.** Same reasoning — Navigation 3 (stable since Nov 2025) has a different ownership model for the back stack that I'm less certain of in detail; the older library is simpler and unambiguous.
- **No Hilt/Dagger.** Manual `AppContainer` instead — one more KSP processor is one more version-matching surface I can't verify here, and the M0 dependency graph is small enough not to need it yet.
- Compose BOM 2026.08.00 requires Kotlin 2.2+; I used Kotlin 2.3.20 since AGP 8.13.2 explicitly documents support for it.

## Not started

Milestones 1–7 (scanner, deterministic executor + validator, journal/undo, rule engine, NL parsing, on-device AI, polish) per the plan's own sequencing — Milestone 3 (journal + undo) must be solid before Milestone 6 (AI planning) is allowed to touch real files, per Decision 2.
