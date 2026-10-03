package com.pocketsteward.app.saved

import com.pocketsteward.app.filing.InboxFilingEngine
import java.util.Base64
import java.util.Locale

data class DocumentTopicTemplate(val name: String, val folder: String, val terms: List<String>) {
    fun validate() {
        require(InboxFilingEngine.sanitizeSegment(name) == name && '=' !in name) { "Use a short topic name without path separators." }
        require('|' !in folder && folder.split('/').size <= 4 && folder.split('/').all { InboxFilingEngine.sanitizeSegment(it) == it }) {
            "Use a relative destination of one to four safe folder names."
        }
        require(terms.size in 2..12 && terms.all { it.length in 2..64 && it == it.trim() && it.none(Char::isISOControl) && ',' !in it && '|' !in it }) {
            "Use 2–12 distinct keywords or phrases, each 2–64 characters."
        }
        require(terms.map { it.lowercase(Locale.ROOT) }.distinct().size == terms.size) { "Topic keywords must be distinct." }
    }
}

data class DocumentTopicRules(val topics: List<DocumentTopicTemplate> = emptyList()) {
    fun validate() {
        require(topics.size <= 20) { "At most 20 document topics can be active." }
        topics.forEach { it.validate() }
        require(topics.map { it.name.lowercase(Locale.ROOT) }.distinct().size == topics.size) { "Topic names must be distinct." }
        require(topics.map { it.folder.lowercase(Locale.ROOT) }.distinct().size == topics.size) { "Give each topic a distinct destination." }
    }
    fun encode(): String = topics.joinToString("\n") { "${it.name}=${it.folder}|${it.terms.joinToString(",")}" }
    companion object {
        fun parse(text: String): DocumentTopicRules {
            require(text.length <= 24_000) { "Topic template is too large." }
            val topics = text.lineSequence().filter { it.isNotBlank() }.map { line ->
                val fields = line.split('=', limit = 2); require(fields.size == 2) { "Invalid topic template." }
                val details = fields[1].split('|', limit = 2); require(details.size == 2) { "Invalid topic folder/keywords." }
                DocumentTopicTemplate(fields[0].trim(), details[0].trim(), details[1].split(',').map { it.trim() })
            }.take(21).toList()
            return DocumentTopicRules(topics).also { it.validate() }
        }
        val Defaults = DocumentTopicRules(listOf(
            DocumentTopicTemplate("Finance", "Finance", listOf("invoice", "bank statement", "payment due", "tax return", "account balance")),
            DocumentTopicTemplate("Legal", "Legal", listOf("contract", "agreement", "jurisdiction", "clause", "signatory")),
            DocumentTopicTemplate("Research", "Research", listOf("abstract", "methodology", "references", "citation", "hypothesis")),
            DocumentTopicTemplate("Education", "Education", listOf("syllabus", "assignment", "course", "lesson", "curriculum")),
            DocumentTopicTemplate("Writing", "Writing", listOf("chapter", "scene", "dialogue", "character", "narrator")),
            DocumentTopicTemplate("Technical Guides", "Technical Guides", listOf("installation", "configuration", "troubleshooting", "API reference", "system requirements")),
        ))
    }
}

data class NamedDocumentTopicTemplate(val name: String, val rules: DocumentTopicRules)
object NamedDocumentTopicTemplateCodec {
    fun encode(values: List<NamedDocumentTopicTemplate>): String = values.joinToString("\n") { "${enc(it.name)};${enc(it.rules.encode())}" }
    fun decode(text: String?): List<NamedDocumentTopicTemplate> = text.orEmpty().lineSequence().take(20).mapNotNull { line ->
        runCatching {
            require(line.length <= 50_000)
            val fields = line.split(';'); require(fields.size == 2)
            val name = dec(fields[0]); require(name.isNotBlank() && name.length <= 60 && name.none(Char::isISOControl))
            NamedDocumentTopicTemplate(name, DocumentTopicRules.parse(dec(fields[1])))
        }.getOrNull()
    }.distinctBy { it.name.lowercase(Locale.ROOT) }.toList()
    private fun enc(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun dec(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
