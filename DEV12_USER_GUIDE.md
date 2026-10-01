# Pocket Steward dev12: continuing large reviews

This update builds on dev11. The entire app-completion goal and phone acceptance are still open; see APP_COMPLETION_REGISTER.md.

1. Open **Sort Uncertain** to review the existing Downloads/Uncertain checkpoint, or **Organize Inbox** for incoming items. Choose storage access and inboxes as usual.
2. Enable **Image analysis** in Settings for local pixel evidence. Enable **Content inspection** too if you want local image OCR and document evidence. Both switches must be on for image text inspection.
3. Review proposed project/release/role folders. Strong matches start selected. Probable matches require your review; unresolved checkpoint items stay in place.
4. Use file/group assignment to choose a project home, role and optional release bucket. Existing destination editing also remains available. Uncheck anything you want to hold.
5. The image coverage summary reports candidates, cached results, fresh attempts, unavailable evidence and deferred work. Each review admits at most **200 fresh image inspections and 40 OCR attempts**. Valid cached evidence remains usable across the entire intake.
6. Tap **Continue image evidence** to inspect the next deferred batch. Your original intake, assignments, destination/release choices and existing source selections are preserved. Newly supported sources can gain a proposal. Continuation never moves files.
7. **Retry unavailable image evidence** explicitly retries failed image work. Unseen/deferred evidence still takes priority, so one broken image cannot keep blocking the rest. A storage warning means progress could not be saved.
8. You can leave and use **Resume review**. The private draft retains selections, assignments, coverage and initial source baselines; it is never automatically approved. A changed source requires a fresh review. Continuing an older draft without an initial intact-folder snapshot requires rebuilding that review first.
9. Approve only the selected, validated operations when their destinations look right. The normal foreground task, journal, recovery and Undo workflow still handles moves. No overwrite or unattended cleanup path was added.

Turning off image analysis stops pixel/text inspection. Turning off content inspection stops image OCR and document extraction. Visual evidence is kept separately from OCR, so an OCR-derived screenshot suggestion is not carried into visual-only evidence.

Document caches now undergo live identity verification before reuse and after fresh extraction. If a source changes or access disappears, its old text cannot be returned as current evidence; the review reports a failure. These metadata checks do not prove equality for every same-size/same-date byte change; additional fingerprint work remains in the completion register.

The progress hints use one compact, checksummed private journal, rather than a private file for each attempt. An interrupted write preserves earlier verified hints. These hints influence scheduling only and never authorize file operations.

The host benchmark covers project matching and typed-plan construction for 1k, 4k and 16k files with 200 competing homes. It excludes Android scanning, disk IO, ML execution, rendering, battery use and physical-device acceptance. Uncertain cannot safely become empty unless the remaining files have justified ownership or your explicit assignments.
