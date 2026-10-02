# Complete Downloads sorting

User outcome: organize Downloads and its existing 16k-file Uncertain checkpoint into justified project/category homes. Keep related documents, images, builds and archives together. For an approved complete review, only Uncertain should remain in Downloads unless protected, inaccessible, conflicting, explicitly held or newly arrived items have visible reasons.

This is the next implementation focus within GOAL_APP_COMPLETION_SPEC.md. Other mandatory app-completion packages remain open in APP_COMPLETION_REGISTER.md. Dev18 is a review-decision checkpoint, not completion of this outcome.

## What already works

The engine uses filenames, known homes, explicit mappings, bounded document/image evidence, package/archive metadata and some cohort hints. Direct-access folders move intact. Project roles and manual project/release assignments exist. New unresolved inbox units receive reviewed Uncertain moves; items already in Uncertain remain there until resolved. Typed approval, source baselines, durable tasks, conservative recovery and Undo remain mandatory.

Current probable project/category proposals are intentionally unchecked, and the checkpoint adapter includes unresolved decisions only. Approving defaults can therefore leave probable items in Downloads. This is a missing complete-inventory decision flow, not a reason to select uncertain project guesses automatically.

Deep analysis admits 200 fresh images, 40 image OCR inspections and 40 fresh PDFs per review. Image continuation exists, but complete-library processing still requires user-driven continuation/rebuild/index journeys. Direct project discovery is bounded, custom-role/additional-grant support is incomplete, and shared evidence/approved-choice learning/topic classification remain unfinished.

## Implementation order

| Order | Work | Concrete result | Gate |
|---|---|---|---|
| 1 | FILING-01: complete inventory and dispositions | Every source unit has exactly one reviewed outcome: justified destination, Uncertain, explicit keep, or blocked reason. Probable proposals can be confirmed or explicitly deferred to Uncertain. Account for descendants of intact folders without planning them twice. Show new arrivals separately. | Complete manifests at 1k/4k/16k have no missing or duplicate sources. Deselecting an action never silently authorizes a different move. Final rescan/journal reconciliation explains every eligible original item still in Downloads. |
| 2 | SCALE-01/02/03: resumable whole-library analysis | Opt-in analysis continues through bounded batches until applicable evidence is examined or unavailable, without automatically approving moves. Persist candidate/attempt progress, preserve assignments/selections/original baselines, prioritize unseen work, and support pause/restart/retry. | Corpus larger than each budget reaches the end; failures cannot starve unseen sources; process interruption does not reset coverage or approval baselines. Measure host timing separately from phone memory/thermal behavior. |
| 3 | EVID-01, CACHE-02 and TOPIC-01: stronger grounded recognition | Reuse verified metadata, document headings/passages, OCR, archive/package identity and actual content topics. Preserve contradictions and partial coverage. Distinguish project-owned assets from generic category media. | Generic notes/drafts/images resolve where evidence supports them; competing owners stay Uncertain. Changed/revoked evidence is not reused. No invented project titles. |
| 4 | LEARN-01, HOME-01 and RELEASE-01: project identity and group learning | Recognize existing homes/aliases/custom roles beyond initial discovery limits. Remember approved, scoped group assignments; expose edit/remove/conflicts. Keep related mixed file types and releases under one project. | Representative NSTL/Lilith/Stories/Project Documentation fixtures retain correct ownership and role/release paths; approved corrections improve related later files without creating conflicting global rules. |
| 5 | EDIT-01, SAF-01 and clear review UI | Explicit confirm-project, change destination, defer-to-Uncertain and keep actions. Show total/examined/deferred/unavailable/proposed/checkpoint/held/blocked counts and a before/after tree. Complete intact-folder handling within valid grants; explain missing destination access before execution. | One visible, complete review reaches the existing approval/task pipeline in each supported access mode. No ungranted sibling access, hidden skipped items or inferred movement of deselected files. |
| 6 | ACCEPT-01: verify the actual outcome on a phone | Exercise the real 16k corpus, upgrades, repeated runs, cancel/restart, low storage, collisions, revoked access, interruption/recovery and Undo. Record elapsed time, peak memory, responsiveness and sustained thermal/battery load. | No app/native crash, ANR or phone reboot in the exercised journeys; no overwrite, lost bytes, duplicate execution or broken project bundles. Remaining uncertainty has evidence/coverage reasons. |

## Destination policy

Project identity wins over file type. Lilith's manuscript and illustrations belong under the same Documents/Lilith home, in the approved Manuscript/Images roles. Notes, Drafts, Versions and Archive follow the selected project convention. Generic landscape imagery can use Images/Landscape when project ownership is not established and that category is supported. Conflicting or insufficient ownership remains in Downloads/Uncertain.

Existing folders remain intact unless the user explicitly reviews a consolidation. Dates/proximity alone cannot establish strong ownership. Learning follows approved choices and never grants new storage authority. Optional connected models/caption backends remain separate app-completion work; they are not required to start this pipeline.

## Completion criteria

- Every eligible original item is accounted for, including intact-folder descendants; deferred analysis is visible and cannot be described as fully examined.
- Confirmed project assets remain together, with useful role/release destinations and reused existing homes.
- Final approved movement and rescan leave only the Uncertain checkpoint in Downloads when there are no explicit exceptions or new arrivals. Exceptions retain exact reasons and next actions.
- Sort Uncertain uses additional evidence and approved group learning to resolve supported ownership. Coverage and representative expected destinations are measured; relocating the same unresolved corpus does not establish intelligent sorting.
- Review/approval remains explicit. Partial failures preserve recoverable files and journal state; all safety and physical-phone gates above pass before closing this outcome.

Start with FILING-01. It makes incomplete coverage measurable and gives later recognition/continuation work a concrete acceptance target.


## Dev18 checkpoint and next implementation boundary

After the user confirmed no dev17 tab crashes on the Fold 7, the first review stage of FILING-01 was implemented in dev18. FilingInventoryPolicy accounts for each represented source once, and the real review/approval flow requires destination confirmation, explicit Uncertain deferral or Keep for undecided sources. Keep/deferral choices survive saved review and continuation; original unresolved-source baselines are preserved. Overlapping roots cannot plan both an intact folder and descendants, which are counted as indexed coverage only. See DEV18_FILING_REVIEW_REPORT.md.

FILING-01 remains open. Next, persist a versioned complete-inventory description with the approved task before foreground execution starts, including every intake root/source/outcome and original folder coverage. Keep it separate from the operation authority: it must not select actions or widen storage access. Extend the existing task manifest/history path to reconcile that description with operation journals and live source/destination listings off the UI thread. Report approved moves, retained Uncertain items, explicit keeps, protection/access/conflict/interruption reasons and newly arrived items separately. Old tasks without such metadata must say coverage is unavailable. Only then can post-task results assert that every eligible original item has an explained fate.

Acceptance remains the 1k/4k/16k mixed inventory, intact bundles, overlapping roots, interruption/recovery/Undo, revoked access and new-arrival gates above. Complete resumable evidence/recognition/learning follows that accounting boundary; moving more guesses into Uncertain does not close recognition coverage.
