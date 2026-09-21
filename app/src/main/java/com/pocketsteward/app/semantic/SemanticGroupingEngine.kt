package com.pocketsteward.app.semantic

import com.pocketsteward.app.ai.CoherenceClass
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.rules.ProjectKeyword

enum class SemanticEvidence {
    PROJECT_KEYWORD_FILENAME,
    REPEATED_FILENAME_TITLE,
    PROJECT_KEYWORD_CONTENT,
    MODEL,
}

data class SemanticGroupingDecision(
    val suggestion: SemanticSuggestion,
    val evidence: SemanticEvidence,
)

/**
 * Deterministic evidence-priority layer for one-level document grouping.
 *
 * Priority follows the M13 plan: explicit project keyword mapping, strong
 * filename/title signal, indexed content signal, then on-device model advice.
 * If none is strong enough the file is left untouched.
 */
object SemanticGroupingEngine {
    private val stopWords = setOf(
        "the", "and", "for", "with", "from", "copy", "final", "new",
        "file", "document", "download", "untitled", "scan", "image", "img",
        "screenshot", "notes", "note",
    )

    fun decide(
        records: List<FileRecord>,
        projectKeywords: List<ProjectKeyword>,
        indexedTextByRef: Map<String, String>,
        modelSuggestions: List<SemanticSuggestion>,
    ): List<SemanticGroupingDecision> {
        val modelByRef = modelSuggestions
            .groupBy { it.stableRef }
            .mapValues { (_, values) -> values.singleOrNull() }

        val tokenKeys = records.associate { record ->
            record.stableRef to filenameKeys(record.displayName)
        }
        val firstCounts = tokenKeys.values
            .mapNotNull { it.firstOrNull() }
            .groupingBy { it }
            .eachCount()
        val pairCounts = tokenKeys.values
            .mapNotNull { it.getOrNull(1) }
            .groupingBy { it }
            .eachCount()

        return records.mapNotNull { record ->
            val filenameKeyword = projectKeywords.firstOrNull { keyword ->
                record.displayName.contains(keyword.term, ignoreCase = true)
            }
            if (filenameKeyword != null) {
                return@mapNotNull decision(
                    record,
                    filenameKeyword.projectFolder,
                    SemanticEvidence.PROJECT_KEYWORD_FILENAME,
                )
            }

            val keys = tokenKeys[record.stableRef].orEmpty()
            val repeatedPair = keys.getOrNull(1)
                ?.takeIf { (pairCounts[it] ?: 0) >= 2 }
            val repeatedFirst = keys.firstOrNull()
                ?.takeIf { (firstCounts[it] ?: 0) >= 3 }
            val filenameGroup = repeatedPair ?: repeatedFirst
            if (filenameGroup != null) {
                return@mapNotNull decision(
                    record,
                    filenameGroup.toDisplayGroup(),
                    SemanticEvidence.REPEATED_FILENAME_TITLE,
                )
            }

            val indexed = indexedTextByRef[record.stableRef].orEmpty()
            val contentKeyword = projectKeywords.firstOrNull { keyword ->
                indexed.contains(keyword.term, ignoreCase = true)
            }
            if (contentKeyword != null) {
                return@mapNotNull decision(
                    record,
                    contentKeyword.projectFolder,
                    SemanticEvidence.PROJECT_KEYWORD_CONTENT,
                )
            }

            val model = modelByRef[record.stableRef]
            val group = model?.suggestedGroup
            if (model != null &&
                group?.isNotBlank() == true &&
                model.classification in setOf(CoherenceClass.QUESTIONABLE, CoherenceClass.DOES_NOT_BELONG)
            ) {
                return@mapNotNull SemanticGroupingDecision(model, SemanticEvidence.MODEL)
            }

            null
        }
    }

    private fun decision(
        record: FileRecord,
        group: String,
        evidence: SemanticEvidence,
    ): SemanticGroupingDecision =
        SemanticGroupingDecision(
            suggestion = SemanticSuggestion(
                stableRef = record.stableRef,
                classification = CoherenceClass.QUESTIONABLE,
                suggestedGroup = group,
            ),
            evidence = evidence,
        )

    /**
     * Index 0 = first-token key, index 1 = first-two-token key when present.
     * Numbers and generic download words are intentionally ignored.
     */
    private fun filenameKeys(displayName: String): List<String> {
        val base = displayName.substringBeforeLast('.', displayName)
        val tokens = base
            .lowercase()
            .split(Regex("""[^a-z0-9]+"""))
            .filter { token ->
                token.length >= 3 &&
                    token !in stopWords &&
                    token.any { it.isLetter() } &&
                    !token.all { it.isDigit() }
            }
        if (tokens.isEmpty()) return emptyList()
        return buildList {
            add(tokens.first())
            if (tokens.size >= 2) add(tokens.take(2).joinToString(" "))
        }
    }

    private fun String.toDisplayGroup(): String =
        split(' ')
            .joinToString(" ") { token ->
                token.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
            .take(SemanticPlanAdapter.MAX_GROUP_LENGTH)
}
