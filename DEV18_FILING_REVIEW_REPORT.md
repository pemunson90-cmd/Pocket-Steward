# Dev18 filing review checkpoint

After the user reported no dev17 Home/Explore tab crashes on the Fold 7, FILING-01 resumed. This checkpoint implements the review stage; it does not close the complete Downloads sorting outcome.

## Behavior

- Each source represented in the filing review has one displayed outcome: selected destination, selected checkpoint, retained existing checkpoint, explicit Keep, blocked with a reason, or decision still needed. Copy operations explain that the original remains. Checkpoint items shown both in the unresolved list and destination cards are counted once.
- Probable unchecked proposals require confirmation, explicit deferral or Keep. Group and individual actions are available; the overview can defer/keep all undecided items without changing existing selected destinations. Both the Run button and approval entry point reject incomplete choices. Deselection never substitutes a different move.
- Keep choices are persisted with the unapproved review. Reselecting an item rescinds Keep; a subsequent deselection is again undecided. Explicit Uncertain choices are persisted as scoped manual assignments and survive evidence continuation. Existing checkpoint items remain in place without a nested checkpoint.
- Assignment/defer rebuilds merge the original session source baselines, including unresolved inputs, with previously reviewed operation baselines. The durable execution pipeline continues to validate selected sources against those original identities.
- Parent identities suppress descendant source units when an intact ancestor folder is selected across overlapping scan roots. Memoized ancestor traversal counts indexed descendants without separate operations or repeated scans of the same parent chain. The count covers indexed entries only; inaccessible/unscanned descendants are not claimed as examined.
- Android historical exit notices display UTC time and explicitly say that an absent stack/build version cannot identify the failed build. The user-reported dev17 tab retest is recorded separately from other physical acceptance.

## Verification scope

Meaningful regressions cover mixed 16,000-source outcomes, exactly counted checkpoint duplicates, source-choice validation, immutable other project choices during defer, retained checkpoint behavior, copy retention, duplicate-source refusal, overlapping direct and opaque-provider parents, 16,001 indexed descendants, draft restart and real API 36 Compose review/approval/defer transitions. A Robolectric paused-looper delivery issue and a test assumption of empty history were corrected in the fixture; neither establishes a phone defect. Full validation results are recorded in APP_COMPLETION_PROGRESS.md.

## Remaining FILING-01 and sorting work

Durable complete task-inventory manifests, post-task live rescan/journal reconciliation, separate new arrivals, all inaccessible/protected inventory exceptions and selected-tree intact-folder parity remain open. Current reviewed source counts are not a claim that every physical file on the phone was inventoried or deeply analyzed. Automatic full-corpus evidence continuation, stronger recognition and approved group learning remain later ordered packages. Real scanning/cleanup/thermal/memory/recovery/Undo/data-integrity acceptance is still required.
