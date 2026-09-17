package com.pocketsteward.app.rules

import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.scan.classifyByExtension

/**
 * Plan Section 9: rule engine first, AI second. Nothing here is a model —
 * every result is derived deterministically from a filename and extension,
 * which is why this stays in the same zero-Android-import boundary as the
 * `plan` package and gets the same real-compiler verification (see
 * RuleEngineTest, pulled into the throwaway plain-Kotlin module).
 *
 * A project keyword is checked before the extension falls back to a plain
 * category, since "this file mentions a known project" is a more specific
 * and more useful signal than "this is a PDF" — Section 9's own example
 * ("Find everything with NSTL, Leaseworld, Erica, Aleksei, or Lilith in the
 * filename and group it by project") asks for project grouping to win.
 */
object RuleEngine {
    fun classify(
        displayName: String,
        extension: String,
        projectKeywords: List<ProjectKeyword> = emptyList(),
    ): ClassificationResult {
        val matchedKeyword = projectKeywords.firstOrNull { displayName.contains(it.term, ignoreCase = true) }
        if (matchedKeyword != null) {
            return ClassificationResult(
                category = classifyByExtension(extension),
                confidence = 1.0f,
                reason = "Filename contains project keyword \"${matchedKeyword.term}\"",
                matchedRules = listOf("project-keyword:${matchedKeyword.term}"),
                projectFolder = matchedKeyword.projectFolder,
            )
        }

        val category = classifyByExtension(extension)
        return if (category == FileCategory.OTHER) {
            ClassificationResult(
                category = category,
                confidence = 0f,
                reason = "Extension \".${extension.lowercase()}\" doesn't match a known category or project keyword",
                matchedRules = emptyList(),
            )
        } else {
            ClassificationResult(
                category = category,
                confidence = 1.0f,
                reason = "Extension \".${extension.lowercase()}\" maps to ${category.displayName()}",
                matchedRules = listOf("extension:${extension.lowercase()}"),
            )
        }
    }
}

private fun FileCategory.displayName(): String = name.lowercase().replace('_', ' ')
