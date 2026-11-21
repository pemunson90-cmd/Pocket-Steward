# Discovery → Delivery: cross-project agent operating protocol

**Authoritative owner direction:** Pat, conversation of November 20, 2026 (America/New_York). **Recording:** November 20, 2026. **Status:** Owner-directed operating policy; specific examples, hypotheses and proposed architectures are identified below and are not automatically implemented product features. **Scope:** agents, orchestrators, subagents and reviewers on software, games, creative writing, research, documentation, and other project work. Apply at the next safe work boundary; do not interrupt active writers, especially Fable's independent NOVA Moonshot. Retain local security, release, source-authority and domain rules.

**Read alongside:** existing owner rapid-prototype/definition-of-done/test-stop rules, non-destructive feature-continuity rules, and the current project's governing source hierarchy. This policy extends them; it does not replace them.

## The primary invariant: delivery, not proxy completion

**The task is finished only when the requested experience or usable artifact exists, is accessible to its intended user, and can perform its defining job across the required connected parts.** A component, research packet, plan, audit, proof, generated file, test suite, checked box or polished report is not a substitute. Distinguish *created*, *integrated*, *usable*, *observed*, *owner accepted*, and *released*. Do not call one the other.

Before new work, obtain or carry forward Pat's already-set definition of finished. Do not ask again if it is settled. Preserve the real product-level target in the handoff. Report concrete usable changes first, then actual limitations, then focused verification. Identify work spent creating meaningful output versus work spent verifying or documenting it, when that distinction is material.

### Anti-proxy and test-budget rule

An agent may unconsciously optimize for certainty: thousands of checks around a small code unit yield easy-to-report evidence, while building the whole connected product creates unknowns. **Reducing uncertainty is not itself the end goal.** Don't use testing, formalism, comprehensive audit, setup, documentation, speculative extensibility or security theater to replace usable deliverables.

- **BUILD / DISCOVER:** read the relevant real sources and existing implementations, make coherent end-to-end functionality, tolerate temporary integration breakage within an isolated development environment, and capture discoveries. Minimal checks may resolve immediate blockers or dangerous unknowns; no repetitive broad suites by default.
- **INTEGRATE / DELIVER:** connect systems, correct observed mismatches, complete affected documentation, and run the smallest meaningful end-to-end and release checks applicable to the deliverable. Respect hard security, privacy, data-loss and safety checks; this does not authorize shipping unsafe systems.
- When a check answers its concrete question, stop checking and return to the product. A failing check that blocks real usage merits fixing. Repeatedly proving an isolated component while the product is incomplete is a workflow failure.
- Do not impose BUILD/INTEGRATE as a new mandatory scheduler on an existing owner's active session; adopt it at a safe boundary and follow any stricter already-approved phase design.

This is not a ban on tests or a universal assertion that all code should be messy. It is an order to make verification serve learning, integration, security and completion, not compete with them.

## The whole-feature contract: all connected representations must agree

Any new or changed feature is one **user-visible behavior across its connected layers**. An agent cannot mark it implemented because the layer it edited passes tests. Trace the real use path and identify its affected components. In a game, these ordinarily include:

1. **Design/source authority:** printed card, ratified rule, cost, timing, targeting, special cases and designer approval status. Working code does not silently promote its own implementation into canon.
2. **Legal option/state/referee layer:** exactly when actions can be chosen, costs paid, responses opened, effects applied, and results recorded.
3. **Human interface:** options are actually offered at the right time, with accurate costs/targets/disabled reasons and controls that can be reached and used.
4. **Computer decision-maker:** same legally allowed actions from its permitted information; it must neither peek at hidden state nor omit choices merely because its UI/path differs.
5. **Feedback/history/replay:** the player can see what was declared, answered, prevented, changed, moved and resolved; summaries and notifications reflect actual events rather than guesses.

**Changing one layer triggers an impact check of the others.** Read the affected callers, consumers, schemas and outputs before the patch. Implement what changes as a vertical feature rather than leaving integration to the next agent. Do one real representative player interaction or equivalent end-to-end usage that exercises the defining behavior; expand only for a concrete unresolved risk or real release requirement. A rendered card whose engine cannot execute it, an engine effect with no accessible player control, a working mechanic with misleading pop-up text, or a choice at the wrong timing window is **unfinished**, however many unit tests pass.

**Preferred architectural direction (proposal, not a claim that existing code already works this way):** where feasible, generate legal options from one authoritative state/rules interface; show those choices in the human UI and provide them to the computer player; have both submit choices through the same referee; emit structured outcome events consumed by feedback, history and replay. Keep rules interpretation centralized. Avoid independently hardcoding legal choices, payoff logic and timing in each layer. New stateful operations become reusable only when genuinely required by observed new effects; printed exceptions remain explicit, not ghost defaults.

The same invariant applies outside games: promise or specification → underlying state/content/model → user-accessible action → visible outcome → persistence/retrieval. A polished surface over a nonfunctional system is unfinished; a functioning backend the user cannot actually operate is also unfinished.

## Play/use the real artifact as a discovery instrument

Prefer actual interaction with the real deliverable when it reveals things isolated checks cannot. In a game, use screenshot-driven clicks, eyes-on-the-board decisions, tooltips, coach, legal prompts, UI response windows, event history and full playable trajectories. In apps, use actual screens and user workflows. In writing, read complete scenes/chapters in intended order from the intended viewpoint, with fresh-reader interpretation where appropriate. In research, follow evidence through to an answer that the user can use.

Record **what the user/agent could see**, what it believed, what it attempted, what the system actually did, and what became confusing or unavailable. A value existing in internal state is not enough if its display vanishes when needed. A correct engine resolution is not enough if an event flies past too fast to understand. A tooltip rendered successfully is not proof it teaches.

Differentiate one successfully executed screen action from understanding the mechanic; a complete task flow from an isolated demonstration; and a real owner's experience from a simulated or headless one. Do not claim a partial game is a finished race.

### Observational tutoring / onboarding audit

An agent playing a game without consulting full rules may provide evidence about how well coaching and tooltips teach. **Whether this was a genuinely blind onboarding attempt is a hypothesis**, not an established fact merely because the agent asked a beginner question. Record prior knowledge and any documentation/code consulted. Distinguish:

- Already known before the session.
- Learned from a coach, tooltip or printed material.
- Discovered by experimentation.
- Understood only after history/log/source-code inspection.
- Still misunderstood or unknown.

Strong evidence of teaching includes later independent correct application without another prompt, not just following a highlighted button. Identify coach interference, obscured controls, wrong timing, inaccurate guidance, repeated help and missing context. Preserve failures and mistaken assumptions; they are valuable.

## Intelligent-agent gameplay → evidence-grounded heuristic discovery

A strong general-purpose reasoning agent can *play* and report why choices seemed sensible, what it anticipated, what it learned and what it got wrong. Use this as a **source of candidate strategic heuristics**, not as a substitute for genuine results or as proof the agent made optimal moves. The existing lightweight opponent may be a heuristic/planner; do not conflate it with a language-model player.

When the agent still has fresh context, **capture recollection before reviewing logs/code**, then reconstruct from recorded screens and events. Preserve separately:
- Directly logged observations.
- The agent's own reportable recollection of factors, intentions, expectations and tradeoffs.
- Later reconstruction from screenshots or event history.
- Hypothetical explanation, strategy proposal, and uncertainty.
Never invent recovered memories or disclose internal private chain-of-thought; factual decision summaries and explanations suffice.

For each meaningful decision record: visible state; known/private hand and resources as legally available; considered legal options; chosen action; expected response; intended payoff or threat; actual outcome; error/surprise; lesson; evidence location; confidence/provenance. Include accidental, exploratory, misinformed and suboptimal choices. Do not rewrite mistakes as secret cleverness.

Derive candidate heuristics as **condition → action tendency → strategic reason → observed supporting decision → failure condition/counterexample → minimum legal information and implementation sketch**. Favor whole-game conditions such as impending finish, position, future movement, Burden, T/DT, NU affordability, SR Tank/Bank, action-vs-response opportunity, Log quality, synergy, multi-seat priorities and response timing rather than guessed universal numeric weights. Separate decisions actually observed from new theoretical suggestions.

Then compare actual whole-game outcomes and decision differences between the reference reasoning agent and the lightweight policy under controlled, legal, mirrored conditions when useful. Do not infer strategy quality from a single attractive explanation or force every Stack matchup to 50%. The objective is stronger, coherent decision-making in a working game, not a benchmark for its own sake. Delay extensive AI evaluation until there's a usable integration and a specific hypothesis to test.

## Build large to learn; then deliberately restructure

A broad, instrumented working prototype can be an **experimental apparatus**, not a production architecture that must be maintained forever. Extra UI, verbose logs, independent effects, debug controls and deliberately simple implementations may make mechanics and dependencies observable. Build and explore a complete usable vertical slice—or the broader playable system when that is the actual goal—before pretending to know all final abstractions.

After *observed* requirements become clear, distinguish:
- **Refactor:** restructure internals while intentionally preserving behavior.
- **Rewrite/reimplementation:** replace modules or the system using what the working prototype taught us.
- **Product change:** intentional altered behavior requiring source/designer approval.

Inventory the actual modules, their contracts, the dependencies and intended behaviors. Research established solutions when material. Rebuild reusable boundaries from demonstrated patterns rather than anticipating arbitrary possibilities. Keep a faithful reference prototype and enough decision examples to compare meaningful behavior; do not preserve quirks as requirements merely because code currently behaves that way. The goal is the strongest known architecture **given what was learned**, not a promise of a perfect once-for-all rewrite.

Architectural debt becomes costly when a wrong assumption (e.g. exactly two players) propagates across rules, state, UI, AI, replay and content. This is a reason to recognize boundaries and to refactor/rewrite once the shape is known, **not** permission to indefinitely defer building until hypothetical future cases are solved. AI can reduce rewriting labor, but does not automatically prove behavioral parity.

**Feature continuity remains mandatory:** preserve usable owner-accepted functions, data and provenance during rewrites unless Pat approves a specific removal. Keep old work recoverable; never permanently delete unique files. Distinguish actual owner requirements from accidental prototype behavior. Run proportional representative parity checks at integration rather than broad speculative gauntlets.

## Cross-domain translation (do not copy game mechanics into unrelated projects)

- **Games, including NOVA, Mecha and Siren's Song:** piece/rule → legal options/referee → humans/AI → state changes → readable feedback and replay. Test actual play through the interface. Observe learning and strategic decisions; teach lightweight policies only from evidence, not lore. Preserve game-specific canon and printed rules.
- **Novels, worldbuilding, narrative design:** concept/character intention → scene causality and POV → manuscript wording and continuity → reader's sequential comprehension. A character sheet or lore database is not a finished scene or manuscript. Preserve ratified prose above subsequent assistant extrapolation; record what a reader could infer *from the text* before explaining it with author notes. Fresh-reader misinterpretation is evidence, not automatic permission to rewrite canon.
- **Applications, mobile, websites, tools:** requirement → data/state layer → interaction controls → actual displayed result → persistence/export/security. A correct algorithm with an inaccessible button, broken error display or out-of-sync output remains incomplete. Real supported phone/device use outranks a purely internal function demonstration.
- **Research, analysis, archival work:** source → verified extraction → reasoning with provenance → decision-ready output. Research is complete only when the requested usable artifact is delivered, not when more searching becomes possible. Distinguish fact, recollection, source testimony, inference, proposal and unresolved conflicts. Do not replace the user's actual question with an adjacent, easier deliverable.
- **Creative/media pipelines:** prompt/specification → generation → selection and author control → usable output in intended setting. Inspect actual reception and failure cases; don't mistake a model's affirmative commentary for audience comprehension.

These are examples of **one connected outcome**, not universal checklists. Tailor the actual affected layers to the project.

## Handoffs, preservation, context and ownership

At significant context or agent handoff boundaries, capture fragile experiences **before** the context is cleared, especially first-person observations that cannot be reconstructed from code. Save work with version, source, date, authority, branch, actual evidence and what's missing. Include an exact restart path and current deliverable state. Do not claim recollection is preserved after a restart unless written evidence exists. A fresh agent must read the correct current handoff and project authority before acting.

Preserve original discussion and an **audited extracted interpretation** as distinct artifacts when the exact semantic distinctions matter. Audit the extraction against the source: did it flatten hypotheses into facts, assistant suggestions into Pat rulings, partial play into full completion, prototype behaviors into canon, source code success into user experience, or maintenance work into finished product? Repair each. Do not silently suppress errors, negative results or superseded history.

Respect active agent ownership: never reset, edit, rebase, clean, replace, merge or otherwise interfere with another worker's active worktree. Stage cross-project policy adoption on separate branches when the repo's merge rules require review; owner approval of the general principle does not fabricate project-specific merge authorization. In PNB2026, update PROJECT_LEDGER.md and its current-state index transactionally with substantive changes, and sync its Library mirror only against canonical merged main. If publication is incomplete, identify the exact checkpoint and pending integration; don't claim it was deployed.

## Concrete completion check for an agent's feature

For the actual feature under consideration, not for an imagined future catalog:
1. Is the user-requested outcome accessible and useful right now, or only a document / test / backend stub?
2. Do source authority, execution, accessible actions, AI if applicable, feedback and persistence agree about the changed behavior—including timing and the player's actual opportunity to choose?
3. Has the relevant real path been used once sufficiently to expose the defining behavior and causal feedback? What was actually observed, and what is still untested?
4. Did the work create capabilities instead of predominantly creating assertions about capabilities? Are over-testing and duplicate analyses stopped?
5. Is meaningful provenance preserved, including failures, owner decisions, remaining questions, and unchanged old work?
6. Is its handoff accurate enough to continue after context compaction without reconstructing the task?

**Do the thing; use the thing; learn what the thing really needs; rebuild intelligently if warranted; deliver the thing.**
