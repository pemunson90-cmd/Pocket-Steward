package com.pocketsteward.app.saved

import com.pocketsteward.app.filing.ProjectEvidenceTerms
import java.util.Locale

/** Preferences select evidence, never grant storage authority. Conflicts are retained. */
object CorrectionRulePolicy {
    fun identity(rule: CorrectionRule): List<String> = listOf(rule.term.lowercase(Locale.ROOT), rule.destinationFolder.lowercase(Locale.ROOT),
        rule.sourceFolder.orEmpty(), rule.projectHomePath.orEmpty())

    fun matching(rules: List<CorrectionRule>, ref: String, parent: String?, name: String): List<CorrectionRule> {
        val matches = rules.filter { rule -> ProjectEvidenceTerms.containsTerm(name, rule.term) && within(ref, parent, rule.sourceFolder) }
        // The nearest explicit folder overrides wider rules. Equal-scope owners still compete.
        val specificity = matches.maxOfOrNull { it.sourceFolder?.length ?: 0 } ?: return emptyList()
        return matches.filter { (it.sourceFolder?.length ?: 0) == specificity }.distinctBy(::identity)
    }

    private fun within(ref: String, parent: String?, scope: String?): Boolean {
        if (scope == null) return true
        val root = if (scope == "/") scope else scope.trimEnd('/')
        // Document IDs are opaque: a URI prefix is never ancestry evidence.
        if (root.startsWith("content://") || root.startsWith("ps-child:")) return parent == root
        return root.startsWith('/') && (parent == root || ref.startsWith("$root/"))
    }

    fun clean(rule: CorrectionRule): CorrectionRule? {
        val cleaned = rule.copy(term = rule.term.trim().take(80), destinationFolder = rule.destinationFolder.trim().take(80),
            sourceFolder = rule.sourceFolder?.trim()?.let { if (it == "/") it else it.trimEnd('/') }?.takeIf { it.isNotBlank() },
            projectHomePath = rule.projectHomePath?.trim()?.trimEnd('/')?.takeIf { it.isNotBlank() })
        if (cleaned.term.isBlank() || com.pocketsteward.app.filing.InboxFilingEngine.sanitizeSegment(cleaned.destinationFolder) != cleaned.destinationFolder) return null
        if (listOfNotNull(cleaned.sourceFolder, cleaned.projectHomePath).any { path -> path.length > 4096 || path.any(Char::isISOControl) ||
            !(path.startsWith('/') || path.startsWith("content://") || path.startsWith("ps-child:")) || path.split('/').any { it == "." || it == ".." } }) return null
        return cleaned
    }
}
