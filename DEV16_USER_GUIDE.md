# Pocket Steward dev16: stabilization and service retirement

Dev16 (version code 56) contains the audit repairs described in [DEV15_USER_GUIDE.md](DEV15_USER_GUIDE.md), plus a guard for interrupted service cleanup. Whole-app completion and physical-phone acceptance remain open.

## What changed

- A canceled foreground index job retains ownership until cleanup ends. Requests arriving during cleanup are handed to the durable background worker; they cannot start a competing runner or be discarded by older cleanup.
- Approved file tasks also retain ownership during cancellation/recovery cleanup. A second start request leaves its durable task untouched while the first runner retires.
- Direct moves refuse replacement; copies/writes create destinations exclusively. Failed copies preserve ambiguous paths for review. Content-cache clearing keeps the database open and rejects older writers.
- Filing cancellation clears busy state; cached selection dependencies improve large reviews. Index resume reconciles the current manifest; full content survives concurrent filing extraction. Duplicate hashing handles short provider reads. Async updates retain newer review choices.

## Using the update

1. Install over the existing app using the canonical signing certificate.
2. Use **Sort Uncertain** for Downloads/Uncertain or **Organize Inbox** for incoming downloads. Review project, role, release and destination choices before approval. Strong proposals start selected; unsure files need evidence or your explicit assignments.
3. Use **Continue image evidence** or **Retry unavailable image evidence** for the next bounded batch. Rebuilding preserves assignments and selections; it never automatically approves a task.
4. Restore through **Resume review**. After approval, use **Tasks** for progress, pause/resume and Undo. Ambiguous destinations can require review before retrying; existing files must never be overwritten to force completion.
5. Enable **Content inspection** in Settings and run **Content search** on scanned folders. Resume checks the current manifest from the beginning and reuses valid cached extraction. Additional roots remain queued during active indexing or retirement.

## Validation limits

Host regressions include cancellation with delayed cleanup and a request arriving during retirement. Android instrumentation is compiled, not run here. The signed release is arm64, uses the canonical certificate and has no INTERNET permission.

Phone app/native crashes, ANRs, system reboots, 16k peak memory, sustained extraction, thermal/battery use, storage providers and recovery/Undo require physical acceptance. See [DEV15_STABILIZATION_REPORT.md](DEV15_STABILIZATION_REPORT.md) and [APP_COMPLETION_REGISTER.md](APP_COMPLETION_REGISTER.md).
