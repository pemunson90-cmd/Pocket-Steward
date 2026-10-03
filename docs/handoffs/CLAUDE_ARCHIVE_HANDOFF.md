# Pocket Steward: Claude archive-inspection handoff

## Task and baseline

Implement ARCHIVE-01: bounded, metadata-only RAR and 7z inspection integrated with existing metadata evidence. This is an isolated contribution; Codex continues storage, recovery, undo and whole-app integration.

Repository: https://github.com/pemunson90-cmd/Pocket-Steward

Source branch: `upgrade/app-completion`

Pinned starting commit: `f34b9502fe280ee759b32ff02e66ff03b8899a1a`

Check out that exact commit before editing. The ZIP includes focused reference files, not a complete build checkout. Use a separate archive-inspection branch. Do not replace newer Codex work or push over the source branch. No signing credentials are needed or included.

## User goal and boundaries

The user has about 16,000 Downloads/Uncertain files. Actual archive member names should help identify projects/releases while keeping related bundles together. Every user-file mutation requires a typed reviewed plan, whole-plan validation, durable task and journal. This module must never move/delete user files, create/execute plans, extract archive payloads, enable networking, change storage authorization, or weaken protection.

The next APK must be feature complete. Do not increment versions, sign/publish releases or claim physical phone acceptance.

## Existing integration

`ArchiveInspection(observedEntries: Int, names: List<String>, complete: Boolean, note: String?)` already feeds transient metadata evidence. Preserve existing public APIs where practical. `ArchiveInspector.supportedExtensions` currently includes zip/apks/xapk/tar/tgz/gz. Add rar and 7z only with working inspection paths.

`MetadataEnricher` handles direct paths and content URIs. Direct ZIP uses `zipFile`; other containers use `stream`. Count/sample/completeness/note feed the current evidence cache and UI. Privacy, live source identity, byte samples and observed-write revisions already gate metadata enrichment; retain them.

Commons Compress 1.28.0 is already present. Research Android-compatible parsing libraries and licensing. Prefer member/header listing without decoding file payloads. Entry paths are untrusted evidence, never extraction destinations.

## Requirements

1. Inspect real unencrypted RAR and 7z containers: bounded member-name samples, observed count and truthful completeness/coverage. Explain supported RAR versions explicitly; RAR4 support must not imply RAR5 support if the parser lacks it.
2. Preserve existing budgets: 2,000 entries, 40 sample names, 500 characters per name, 64 MiB compressed bytes for streaming, 8 MiB decoded work when decoding is unavoidable. Stricter limits are acceptable if documented. Bound actual reads/allocations, including seeks, skips, encoded headers and malicious declared lengths. A header-only parser can still allocate or decompress excessively.
3. Never read a whole archive into a byte array. Random-access parsers may use bounded seekable reads. Nonseekable SAF input may be staged to a uniquely owned app-private temporary file, within a real compressed-byte budget. Close handles, propagate cancellation and remove only that owned temporary on success, limit, exception or cancellation. No archive entry payload extraction or temporary output in Downloads.
4. Explicitly report encrypted headers, damaged/truncated input, unavailable multipart volumes, unsupported encodings/formats and resource limits. Partial names/counts must never be presented as a complete inventory. Password/decryption UX is out of scope.
5. Propagate cancellation/interruption; do not convert cancellation to an unreadable result. Keep live evidence checks and global metadata privacy authoritative. No job, model or provider SDK changes.
6. Prevent derived metadata caches from indefinitely reusing old unsupported/no-inspection results for the newly supported types. Use a suitable parser/evidence revision or narrow cache migration, preserving unrelated fields and freshness checks. Explain the decision.
7. Feed real observed member names through the existing result. Add a filing regression showing actual archive member metadata supports current project/version inference, without fabricated content, changed bundle rules or guessed ownership. Retain ambiguity handling.

## Permitted edits

- Primary: `app/src/main/java/com/pocketsteward/app/metadata/ArchiveInspector.kt`; focused parser/budget helpers in the same package.
- Small necessary integration changes: `MetadataEnricher.kt`, `MetadataEvidenceCache.kt`, `app/build.gradle.kts`, metadata/archive tests and small fixture resources.
- A filing regression test is allowed. Production filing-engine changes should be proposed separately with a specific independent reason.
- Do not edit storage, executor, observed-revision store, settings, UI, topic classifier, workflows or project discovery.

## Required verification

- Real small RAR/7z names, counts and completeness, including a nested project/release member path.
- Metadata inspection does not read uncompressed payloads or extract outputs.
- Entry/sample/name limits produce truthful partial coverage.
- Truncated/damaged, encrypted-header, unsupported-version and multipart behavior where applicable.
- Compressed/decoded/encoded-header read and memory limits using observed counters or hostile fixtures, not tests merely repeating constants.
- Nonseekable staging success, limit, cancellation and exception cleanup with no leaked temporary files or user-storage writes.
- Existing ZIP/TAR/compressed-TAR regressions pass.
- Cache version/freshness does not reuse the old unsupported result as current support.

Use small redistributable fixtures or generate them where possible. Include fixture provenance, licensing and hashes; no user files or credentials.

Android min SDK30, target/API36, Kotlin2.3.20, AGP8.13.2, Gradle8.13, JDK21. Follow the repo Gradle setup. Run relevant tests, for example `./gradlew testDebugUnitTest --tests 'com.pocketsteward.app.metadata.*'` and any added filing test. Report actual commands/results; if your environment cannot run them, say so. Codex owns the final full suite and integration audit.

## Return packet

Return a ZIP containing:

- `changes.patch`: a Git diff against the pinned commit, including tracked new files; a separate-branch commit link is useful too.
- Added small fixtures if they are not represented in the patch.
- `REPORT.md`: implementation, changed files, dependencies/licensing, supported formats and limits, actual checks/results, fixture provenance and unresolved limitations.

Return a focused patch, not a full-repository replacement or APK. Codex will audit bounds/privacy, integrate it with concurrent work, and run full checks. If a format cannot be supported safely, return an honest tested partial contribution rather than claiming completeness.
