# Pocket Steward — Inbox / Project Filing Ledger

Status: RATIFIED IMPLEMENTATION TARGET
Established: 2026-09-25
Baseline: 1.3.2-dev1 (`release/v1.3.2-review-snapshot`, `d4a6c60af420686d9766e398e5e743ca1e6c02fb`)

## Product intent

Downloads and similar landing folders are **inboxes**, not permanent filing cabinets.
Pocket Steward must be able to inspect incoming files, infer what durable project/entity they belong to, discover or remember that project's existing home elsewhere in storage, infer a release/version/cohort when evidence supports it, and propose moving the related artifacts together into a useful hierarchy.

The organizing question is **what does this file belong to?**, not merely **what file type is this?**

Example target:

```
Internal storage/
  Download/                              # inbox / recent landing zone
    LilithCompanion-0.3.105.apk
    LilithCompanion-0.3.105-SOURCE.zip
    0.3.105-build-notes.md
    lilith-back-turnaround.png

  Lilith Companion App/                 # durable project home
    0.3.104/
    0.3.105/
      LilithCompanion-0.3.105.apk
      LilithCompanion-0.3.105-SOURCE.zip
      0.3.105-build-notes.md
      lilith-back-turnaround.png
```

A generic `APKs/`, `Archives/`, `Documents/` split is not an acceptable substitute when multiple heterogeneous files clearly belong to the same project/release.

## Feature split

### Smart cleanup
Retain the current feature for **local tidying inside the scanned folder**.

### Inbox filing
Add a distinct workflow for **moving incoming files from one or more inbox roots into durable homes elsewhere in authorized storage**.

Both workflows must ultimately produce ordinary typed `AgentPlan` operations and use the existing review / source-precondition / validation / execution / history / undo architecture.

No new privileged mutation bypass is permitted.

## Core data concepts

The implementation should support explicit representations equivalent to:

- `InboxRoot`: a landing folder such as Downloads.
- `ProjectHome`: durable root for a project/entity, with aliases and optional known package IDs.
- `ArtifactMetadata`: normalized metadata learned from the file itself.
- `ProjectIdentity`: inferred project/entity membership.
- `ReleaseIdentity`: inferred version/release/cohort.
- `FilingEvidence`: concrete reasons for a match.
- `FilingDecision`: proposed destination and confidence.
- `FilingCohort`: related heterogeneous files that belong together.
- `DestinationHierarchy`: safe semantic path segments resolved deterministically under an authorized root.

## Evidence priority

Prefer deterministic evidence in roughly this order:

1. Explicit user mapping or remembered correction.
2. Known Project Home and aliases.
3. Artifact-native metadata.
4. Strong filename/project/version evidence.
5. Indexed document contents.
6. Cohort evidence such as download/modified-time proximity and neighboring artifacts.
7. On-device semantic/model suggestion.

Time proximity alone is never sufficient for an automatic move. It may strengthen an already plausible relationship.

## Artifact metadata

### APK
APK files should be treated as first-class project evidence. Where platform access allows, inspect:

- app label
- package/application ID
- versionName
- versionCode
- signer/certificate identity when practical
- filename, size, modified time

An APK is not primarily classified as "APK" when metadata identifies the project and version it belongs to.

### ZIP
Inspect ZIP entry names without extracting the archive into user storage. Entry paths, README names, project directories, and version tokens can provide project/release evidence.

### Documents
Reuse indexed filename/title/content evidence.

### Images and other supporting files
Use filename/project terms and cohort evidence. Generic files with weak evidence should remain unresolved rather than being force-filed.

## Confidence behavior

Use operational confidence classes rather than decorative percentages:

- **STRONG** — selected by default in review.
- **PROBABLE** — proposed but demands attention / may default unselected.
- **UNRESOLVED** — no move is planned; file remains in inbox.

Every proposed file should retain human-readable evidence explaining why it was associated with a project/release/destination.

## Project-home behavior

Pocket Steward should prefer an existing durable project home over creating a near-duplicate folder.

Discovery priority:

1. remembered Project Homes
2. favorite destinations
3. previously indexed/scanned folders
4. shallow top-level storage directory discovery
5. deeper inspection only for promising candidates

Do not recursively walk the whole device for every filing run.

If no strong existing destination exists, Pocket Steward may propose creating a new project home, but that creation remains part of the reviewable plan.

User corrections may be one-time or explicitly remembered. Do not silently turn every manual destination edit into a permanent rule.

## Destination hierarchy

The semantic/model layer must never emit arbitrary absolute paths.

It may identify semantic segments such as:

```
project = "Lilith Companion App"
release = "0.3.105"
```

The deterministic planner resolves those into safe destination segments beneath an authorized root.

Each path segment is sanitized independently. Reject blank segments, `.` / `..`, separators, control characters, and unsafe traversal.

Initial structure strategies:

- flat project home
- project / version
- project / category

Do not force one universal taxonomy onto every project.

## Inbox defaults and persistence

Downloads should work as the default inbox under Direct storage access without requiring setup ceremony.

Settings should expose:

- Inbox roots
- Project Homes
- remembered aliases/package IDs
- hierarchy strategy per home when applicable

The app should be useful before the user configures any of these manually; successful explicit decisions can populate them over time.

## Review UI target

The review surface should emphasize human concepts first and raw filesystem mechanics second.

Target hierarchy:

```
Review changes
Inbox filing
7 selected · 9 proposed

[List] [Now] [After]
Safe only   Select all   Clear

DESTINATIONS

Lilith Companion App
Internal storage › Lilith Companion App › 0.3.105
Existing project · New version folder
7 files · 34.2 MB

  ✓ LilithCompanion-0.3.105.apk
    APK metadata + project alias

  ? rear_sprite_fix.png
    Possible supporting file

UNRESOLVED
1 file stays in Downloads
```

UI requirements:

- tighten title/context spacing
- compact List / Now / After control
- compact filter controls
- use `Select all`, not ambiguous `All`
- fix destination cards being clipped behind sticky actions
- scrolling content must include footer-safe bottom padding
- show friendly/breadcrumb paths by default; raw absolute path remains available on demand
- destination cards expandable/collapsible
- show selected count and total size by destination
- allow destination editing and "leave here" decisions
- expose evidence/reason per file
- keep Run action sticky
- reduce Export Plan visual dominance while preserving access
- `Now` should show relevant current structure
- `After` should show the proposed resulting tree

## Folder discovery UI

When destination is uncertain, offer ranked existing candidates and creation:

```
Possible project homes
● Lilith Companion App — strong name/history match
○ Animator — weak match
+ Create new project home
```

Reuse the existing direct-storage folder browser for manual destination selection.

## Collision and duplicate behavior

Never overwrite silently.

- same destination name + identical content: surface as duplicate; leave source by default or offer reversible cleanup separately
- same name + different content: conflict requiring revised plan/user choice
- timestamps alone never justify replacement

Reuse existing fingerprint/dedupe facilities where possible.

## SAF behavior

Direct-storage mode gets storage-wide project-home discovery within authorized storage.

SAF mode may perform the workflow only within the granted tree. UI must clearly state this limitation rather than offering unavailable cross-storage behavior.

## Safety invariants

All Inbox Filing proposals must preserve existing Pocket Steward invariants:

- no permanent deletion
- no silent overwrite
- protected folders remain protected
- source scope and destination authorization validated
- review captures source preconditions
- source changed after review invalidates execution
- unsafe destination changes invalidate execution
- every mutation journaled
- undo restores original locations
- model never directly performs filesystem mutation

The organizer gets smarter. The executor does not get more authority.

## Initial integrated implementation target

The first substantial implementation should handle the user's real workflow end-to-end:

- Downloads as default inbox
- cross-root filing in Direct mode
- existing project-home discovery
- persistent project homes / aliases / package IDs
- project identity inference
- version/release inference
- APK metadata evidence
- ZIP entry evidence
- filename evidence
- indexed-document/content evidence where already available
- temporal/cohort evidence only as a supporting signal
- project/version hierarchy
- unresolved files left in place
- learned explicit mappings
- destination-group review
- Review UI cleanup
- useful Now/After tree representations
- existing review/precondition/history/undo safety retained
- focused unit tests plus broader suite and clean APK build when environment permits

## Explicit non-blocking later extensions

Design for, but do not block the first implementation on:

- richer image understanding
- EXIF/event filing
- music/video tag filing
- document-topic templates
- archive formats beyond ZIP
- automatic stale-inbox suggestions
- duplicate Project Home consolidation
- background inbox watching
- scheduled filing suggestions
- storage-wide project knowledge graph
- user-created hierarchy templates

## Acceptance matrix

At minimum cover:

1. existing Lilith project root + new release artifacts
2. missing project root
3. APK with weak filename but strong package metadata
4. notes with explicit project/version
5. generic image beside strong release cohort
6. unrelated simultaneous download remains unresolved
7. two projects downloaded at once
8. same project with two versions in inbox
9. existing version folder
10. filename collision
11. identical duplicate
12. protected destination
13. source changed after review
14. destination changed after review where relevant
15. undo multi-file cross-root filing
16. restart/resume where supported
17. Direct cross-root filing
18. SAF constrained filing
19. large Downloads folder
20. review UI with enough groups/files to require heavy scrolling

## Implementation sequencing

Implement as one integrated feature line, not a chain of tiny user-facing builds:

1. data models / persistence
2. artifact metadata inspectors
3. project + release inference
4. destination discovery
5. hierarchical plan adapter
6. existing validation/executor integration
7. remembered mappings
8. Review UI cleanup / destination grouping
9. inbox + project-home settings/entry points
10. Now/After hierarchy preview
11. focused tests
12. broad unit tests
13. clean build
14. canonical signing
15. signer verification
16. user hardware acceptance

Do not stop for every isolated nonfatal defect. Produce the next APK at a coherent integrated boundary.
