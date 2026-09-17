package com.pocketsteward.app.rules

/**
 * A file [RuleEngine] could not place with full confidence — exactly the set
 * [com.pocketsteward.app.cleanup.CleanupPlanGenerator] silently drops rather
 * than inventing a destination for (Decision 5: "leave this alone" is a
 * correct outcome).
 *
 * Surfacing it is the honest answer to "why didn't Smart cleanup move this
 * file", and it is the same set an AI planner would later be handed — plan
 * Section 9's "only ambiguous records should reach the model." Keeping the
 * definition here, in one place, means the screen that lists them and the
 * generator that skips them can never drift apart into two different ideas of
 * what "uncategorized" means.
 */
fun ClassificationResult.isUncategorized(): Boolean = confidence < 1.0f
