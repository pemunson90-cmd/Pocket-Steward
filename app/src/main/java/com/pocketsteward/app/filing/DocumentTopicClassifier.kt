package com.pocketsteward.app.filing

import com.pocketsteward.app.content.ContentExtractor
import com.pocketsteward.app.saved.DocumentTopicRules
import com.pocketsteward.app.saved.DocumentTopicTemplate
import com.pocketsteward.app.saved.ProjectHierarchyStrategy

/** Compiles user-selected phrases once per filing pass. Topics never establish project ownership. */
class DocumentTopicClassifier(rules: DocumentTopicRules, private val checkCancelled: () -> Unit = {}) {
    private data class Compiled(val topic: DocumentTopicTemplate, val terms: List<Pair<String, Regex>>)
    private val compiled = rules.also { it.validate() }.topics.map { topic ->
        Compiled(topic, topic.terms.map { term -> term to Regex("(?<![\\p{L}\\p{N}])${Regex.escape(term)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE) })
    }
    private data class Match(val topic: DocumentTopicTemplate, val evidence: List<FilingEvidence>)

    fun propose(artifact: FilingArtifact, storageRoot: String, preferredBase: String?): FilingDecision? {
        checkCancelled()
        if (artifact.isDirectory || !ContentExtractor.supports(artifact.extension) || artifact.indexedText.isBlank()) return null
        val text = artifact.indexedText.take(8_000)
        val matches = compiled.mapNotNull { rule ->
            checkCancelled()
            val evidence = rule.terms.mapNotNull { (term, expression) ->
                checkCancelled()
                expression.find(text)?.let { hit ->
                    val excerpt = text.substring((hit.range.first - 30).coerceAtLeast(0), (hit.range.last + 40).coerceAtMost(text.length))
                        .replace(Regex("\\s+"), " ").take(100)
                    FilingEvidence(FilingEvidenceKind.DOCUMENT_TOPIC, "${rule.topic.name}: observed “$term” in document text · “$excerpt”", 35)
                }
            }
            if (evidence.size >= 2) Match(rule.topic, evidence) else null
        }
        if (matches.isEmpty()) return null
        if (matches.size > 1) return FilingDecision(artifact, null, null, null, null, FilingConfidence.UNRESOLVED,
            listOf(FilingEvidence(FilingEvidenceKind.DOCUMENT_TOPIC, "Document text matches several topics: ${matches.joinToString { it.topic.name }}. Choose a destination.", 100)) +
                matches.flatMap { it.evidence.take(2) })
        val match = matches.single()
        val base = preferredBase ?: "${storageRoot.trimEnd('/')}/Documents"
        val home = ProjectHomeCandidate(match.topic.name, "${base.trimEnd('/')}/${match.topic.folder}",
            hierarchy = ProjectHierarchyStrategy.FLAT, categoryHome = true)
        return FilingDecision(artifact, match.topic.name, home, null, home.path, FilingConfidence.PROBABLE,
            match.evidence + FilingEvidence(FilingEvidenceKind.DOCUMENT_TOPIC,
                "Topic from the inspected excerpt; confirm its category destination. Existing project evidence takes priority.", 10), createsProjectHome = true)
    }
}
