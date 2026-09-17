package com.pocketsteward.app.rules

import com.pocketsteward.app.scan.FileCategory

/**
 * A user-configurable project term (plan Section 9's own examples: NSTL,
 * Leaseworld, Lilith, Erica, Aleksei, MarkdownDesk) mapped to the folder a
 * matching file should land in.
 */
data class ProjectKeyword(val term: String, val projectFolder: String)

/**
 * What [RuleEngine] returns for one file: a category, how confident the
 * match is, why, and which rules fired (plan Section 9's own required
 * shape). [confidence] is deliberately binary in V1 (1.0 for any rule that
 * actually matched, 0.0 for none) rather than a graded score — there is no
 * model here to produce a meaningful in-between value, and a fabricated
 * number between 0 and 1 would be worse than an honest yes/no. Only 1.0
 * results are ever auto-planned; anything else is Decision 5's "leave this
 * alone."
 */
data class ClassificationResult(
    val category: FileCategory,
    val confidence: Float,
    val reason: String,
    val matchedRules: List<String>,
    val projectFolder: String? = null,
)
