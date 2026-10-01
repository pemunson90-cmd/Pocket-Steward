package com.pocketsteward.app.filing

import java.util.Locale

/** Shared vocabulary for inferred projects and automatically remembered filename corrections. */
object ProjectEvidenceTerms {
    val generic = setOf(
        "the", "and", "for", "with", "from", "app", "application", "apk", "source", "src", "build", "release", "debug",
        "final", "copy", "handoff", "notes", "note", "readme", "master", "private", "signed", "canonical", "arm64", "universal",
        "bundle", "zip", "file", "files", "image", "images", "img", "photo", "photos", "picture", "pictures", "track", "song",
        "audio", "video", "screenshot", "screenshots", "android", "version", "versions", "ver", "dev", "manuscript", "manuscripts",
        "novel", "chapter", "chapters", "draft", "drafts", "revision", "revisions", "research", "outline", "cover", "artwork",
        "document", "documents", "documentation", "download", "downloads", "untitled", "scan", "new", "export", "exports",
        "chatgpt", "chat", "gpt", "openai", "claude", "gemini", "conversation", "conversations", "backup", "archive", "archived",
        "attachment", "attachments", "generated", "output", "prompt", "response", "part", "page", "pages", "section", "text", "temp",
        "temporary", "unknown", "unsure", "uncertain", "landscape", "portrait", "landscapes", "portraits", "imgsrc",
    )
    private val numericVersion = Regex("(?i)(?:v?\\d+(?:\\.\\d+)*|(?:dev|alpha|beta|rc|hb)\\d*)")
    private val opaqueId = Regex("[0-9a-f]{8,}")
    private val termMatchers = object : LinkedHashMap<String, Regex>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Regex>?): Boolean = size > 256
    }

    fun usefulToken(token: String, minimumLength: Int = 3): Boolean =
        token.length >= minimumLength && token.any(Char::isLetter) &&
            token.lowercase(Locale.ROOT) !in generic && !numericVersion.matches(token) && !opaqueId.matches(token)

    fun filenameTokens(name: String): List<String> = name.substringBeforeLast('.', name)
        .lowercase(Locale.ROOT).split(Regex("[^\\p{L}\\p{N}]+"))
        .filter(::usefulToken)

    /** A partial word such as "history" must not match a project mapping for "story". */
    fun containsTerm(text: String, term: String): Boolean {
        val clean = term.trim()
        if (clean.isEmpty()) return false
        val matcher = synchronized(termMatchers) {
            termMatchers.getOrPut(clean.lowercase(Locale.ROOT)) {
                Regex("(?<![\\p{L}\\p{N}])${Regex.escape(clean)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            }
        }
        return matcher.containsMatchIn(text)
    }

    fun learnFilenameTerm(names: List<String>): String? {
        if (names.isEmpty()) return null
        val counts = names.flatMap { filenameTokens(it).toSet() }.groupingBy { it }.eachCount()
        // A majority split between two projects is insufficient to create a global correction.
        val minimum = ((names.size.toLong() * 4 + 4) / 5).toInt()
        val ranked = counts.entries.filter { it.value >= minimum }
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key.length })
        val best = ranked.firstOrNull() ?: return null
        if (ranked.getOrNull(1)?.value == best.value) return null
        return best.key
    }
}
