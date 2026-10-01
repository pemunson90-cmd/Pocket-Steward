# App completion implementation progress

Baseline: dev6 f19050817efcd776291d047eafc4e00ac0490e82. Working source starts with the existing Uncertain checkpoint. The full completion plan and master reconciliation remain authoritative backlog documents on the upgrade branch.

## First implementation: checkpoint intake

- Home has a distinct **Sort Uncertain** action.
- It scans configured inboxes (or the granted SAF tree) and selects immediate children of their existing Uncertain checkpoints using indexed parent identities.
- Direct-mode folders remain intact source units. Selected-tree mode retains its existing loose-file boundary; folder support remains open.
- The existing filing engine, adapters, whole-plan validator, approved preview and executor are reused.
- Unresolved checkpoint items remain exactly where they are; neither adapter produces a nested checkpoint or redundant move for them.
- Review identifies retained items as **Still uncertain · stays here**. Ordinary loose-inbox filing retains its reviewed checkpoint behavior.
- Generic Uncertain folders are excluded from discovered project-home candidates.
- Destination cards start collapsed; expanded cards use bounded lazy file lists. Selection uses one remembered source-operation index rather than a full operation search per file.
- Checkpoint-source selection is linear and deduplicates overlapping inventory; unresolved/checkpoint display filtering uses a source-ref set rather than nested searches.

Regression cases cover source selection and intact bundles, opaque SAF parent IDs, direct/SAF unresolved retention, mixed resolved/unresolved/new-loose input, and a 16,000-record intake. The latter is an intake correctness test, not evidence of full 16k planning/UI performance on the phone.

## Still open in the organizing journey

Fresh bounded content evidence, shared project interpretation, bulk project/role assignment, ranked destination choice, quadratic engine matching and full preview scalability remain open. This increment does not claim to sort all of the user's 16,000 files or complete the app. No new release APK has been published for this increment. Phone acceptance remains outstanding.

## Verification of this increment

419 unit tests passed, zero failures/errors; lintDebug and assembleDebug passed. No emulator or target-phone run was performed. The generated debug APK is a build check, not a canonical signed update for installation.
