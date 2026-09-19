# Death reports

One per milestone, written when the milestone lands on hardware, not when the
code is pushed. The name is literal: a milestone is dead once it ships, and
what survives it is the record of what it cost and what it froze.

A death report is not a changelog. `STATUS.md` is the changelog. This answers
four questions a changelog does not:

| Section | The question it answers |
| --- | --- |
| **What went in** | What was asked for, by whom, under what constraints, and what it cost to build |
| **What came out** | What actually shipped, verified how, and where the claim comes from |
| **What can't change** | Invariants later milestones must not break, and the reason each one exists |
| **What needs to go** | Debt, known-wrong behaviour, and things that were right once and no longer are |

## Rules

1. **Every claim carries provenance.** `[device]` observed on hardware,
   `[test]` a passing test, `[code]` read out of the source at a named commit,
   `[pat]` an owner decision, `[inferred]` reasoning. An unmarked claim is a
   defect in the report.
2. **"What can't change" needs a reason, not an assertion.** A frozen thing
   with no stated cost of breaking it will be broken by the next person who
   finds it inconvenient, correctly.
3. **"What needs to go" is written honestly or not at all.** This is the
   section that decays first, because writing it means admitting what was
   shipped knowingly wrong. A milestone with an empty "needs to go" section is
   a milestone whose author did not look.
4. **Report what failed, including process failures.** A bug caught by a build
   session that this environment missed belongs here with the reason it was
   missed.
5. **No retroactive editing to look better.** A superseded report gets a note
   pointing at the one that supersedes it, and keeps its original text.

A cold start begins with `POCKET_STEWARD_HANDOFF.md` in the repository root,
not here. These reports are what it points at for per-milestone detail.

## Index

| Milestone | Version | Report | Landed on hardware |
| --- | --- | --- | --- |
| M0–M5 | 0.1.0–0.6.0 | `M0-M5-RETROSPECTIVE.md` | partially, see report |
| M6 | 0.7.0-milestone6 | `M6.md` | 2026-09-18 |
| M7 | 0.8.0-milestone7 | pending | not yet — report is written when it lands, not when it is pushed |
