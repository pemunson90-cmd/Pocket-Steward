# RAR/7z inspection fixtures

Small archives for `RarSevenZipInspectionTest` and `ArchiveMemberFilingTest`.

- Content: synthetic only, written by `generate.sh` (short placeholder text, empty
  files, zero filler, seeded pseudo-random filler). No user files, no credentials.
  The fixture-only password `fixture` protects the encrypted fixtures.
- Tools: RAR 6.23 (Ubuntu multiverse `rar` 2:6.23-1~22.04.1; RAR 7 can no longer
  write RAR4) and 7-Zip 23.01 (Ubuntu `7zip`). Archive files carry only the
  generated content; neither tool's license restricts redistributing them.
- Integrity: `SHA256SUMS` records the committed bytes. Regenerating produces
  different bytes (archivers embed timestamps) with the same member lists.
- License: the fixtures and `generate.sh` are contributed under the repository's
  terms.

| Fixture | Exercises |
| --- | --- |
| `project-release-rar4.rar`, `-rar5.rar` (recovery record), `-rar5-solid.rar`, `project-release.7z` (LZMA header), `project-release-plain-header.7z` | Nested `NSTL/NSTL-1.4.0-dev18/...` project/release tree, 9 entries |
| `encrypted-headers-rar4.rar`, `-rar5.rar`, `encrypted-headers.7z` | Names unavailable without a password |
| `encrypted-content-rar4.rar`, `-rar5.rar`, `encrypted-content.7z` | Encrypted data, visible names |
| `multipart-rar4.rar`, `multipart-rar5.part1.rar` | First volume of a multipart set |
| `unicode-rar4.rar`, `unicode-rar5.rar`, `unicode.7z` | Cyrillic/Japanese names (RAR4 compact Unicode encoding) |
| `entries-2001-rar4.rar`, `-rar5.rar`, `entries-2001.7z` | Entry and sample limits |
| `payload-64mib-zeros-rar5.rar`, `payload-64mib-zeros.7z` | 64 MiB payload that must never be decoded |
| `long-name-rar5.rar`, `long-name.7z` | Member paths longer than 500 characters |
