package com.pocketsteward.app.content.ask

/**
 * One page (or text segment) pulled from the content index as evidence for
 * a question. [number] is what the answer cites as [n].
 */
data class AskPassage(
    val number: Int,
    val stableRef: String,
    val displayName: String,
    val parentRef: String?,
    val extension: String,
    val pageNumber: Int?,
    val ocr: Boolean,
    val excerpt: String,
)

/** A raw index row before ranking. Mirrors the DAO projection. */
data class AskCandidateRow(
    val segmentId: Long,
    val stableRef: String,
    val displayName: String,
    val parentRef: String?,
    val extension: String,
    val pageNumber: Int?,
    val ocr: Boolean,
    val body: String,
)

/**
 * Turns a question into index lookups and picks the passages worth showing
 * a model. Pure Kotlin so it is unit tested; the only Android-side step is
 * the FTS query itself.
 *
 * Retrieval is keyword based on purpose: it runs on the existing local
 * index, needs no embedding model, and every passage it returns can be
 * shown to Pat verbatim, so an answer can always be checked against its
 * source.
 */
object AskRetrieval {

    private val stopwords = setOf(
        "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "can", "could", "did", "do", "does",
        "for", "from", "had", "has", "have", "how", "i", "if", "in", "into", "is", "it", "its", "me", "my",
        "of", "on", "or", "our", "should", "so", "than", "that", "the", "their", "them", "then", "there",
        "these", "they", "this", "to", "up", "was", "we", "were", "what", "when", "where", "which", "who",
        "whom", "why", "will", "with", "would", "you", "your", "about", "any", "all", "find", "show", "tell",
        "file", "files", "document", "documents", "doc", "docs", "say", "says", "said", "mention", "mentions",
        "please", "give", "list", "much", "many", "get", "got", "just", "also", "some", "there's", "whats",
    )

    const val MAX_KEYWORDS = 8

    /** Distinct content words, longest first (longer words are usually the rarer, more telling ones). */
    fun keywords(question: String): List<String> =
        Regex("""[\p{L}\p{N}]+(?:['’][\p{L}]+)?""").findAll(question.lowercase())
            .map { it.value.replace("’", "'") }
            .map { it.removeSuffix("'s") }
            .filter { it.length >= 2 && it !in stopwords }
            .distinct()
            .sortedByDescending { it.length }
            .take(MAX_KEYWORDS)
            .toList()

    /**
     * FTS4 standard-syntax query: any keyword may match (OR), each as a
     * prefix so "invoice" also finds "invoices". Tokens are letters and
     * digits only after [keywords], and lower case, so none can be read as
     * an FTS operator.
     */
    fun ftsQuery(keywords: List<String>): String? {
        val safe = keywords.filter { k -> k.all { it.isLetterOrDigit() } }
        if (safe.isEmpty()) return null
        return safe.joinToString(" OR ") { "$it*" }
    }

    /**
     * Best [maxPassages] passages: rows matching more distinct keywords
     * first, then more total hits, then shorter files names win ties for
     * stability. At most [perFile] passages from one file, so a single long
     * PDF cannot crowd out everything else.
     */
    fun rank(
        rows: List<AskCandidateRow>,
        keywords: List<String>,
        maxPassages: Int = 6,
        perFile: Int = 2,
        excerptChars: Int = 700,
    ): List<AskPassage> {
        if (keywords.isEmpty()) return emptyList()
        data class Scored(val row: AskCandidateRow, val distinct: Int, val hits: Int)
        val scored = rows.distinctBy { it.segmentId }.map { row ->
            val lower = row.body.lowercase()
            val lowerName = row.displayName.lowercase()
            var distinct = 0
            var hits = 0
            for (k in keywords) {
                val n = countOccurrences(lower, k)
                val inName = k in lowerName
                if (n > 0 || inName) distinct++
                hits += n + if (inName) 2 else 0
            }
            Scored(row, distinct, hits)
        }.filter { it.distinct > 0 }
            .sortedWith(
                compareByDescending<Scored> { it.distinct }
                    .thenByDescending { it.hits }
                    .thenBy { it.row.stableRef }
                    .thenBy { it.row.pageNumber ?: 0 },
            )

        val perFileCount = mutableMapOf<String, Int>()
        val picked = mutableListOf<AskPassage>()
        for (s in scored) {
            if (picked.size >= maxPassages) break
            val used = perFileCount.getOrDefault(s.row.stableRef, 0)
            if (used >= perFile) continue
            perFileCount[s.row.stableRef] = used + 1
            picked += AskPassage(
                number = picked.size + 1,
                stableRef = s.row.stableRef,
                displayName = s.row.displayName,
                parentRef = s.row.parentRef,
                extension = s.row.extension,
                pageNumber = s.row.pageNumber,
                ocr = s.row.ocr,
                excerpt = window(s.row.body, keywords, excerptChars),
            )
        }
        return picked
    }

    /**
     * The [maxChars] of [body] around the densest cluster of keyword hits,
     * cut at word boundaries, with "…" where text was dropped.
     */
    fun window(body: String, keywords: List<String>, maxChars: Int): String {
        val text = body.replace(Regex("""\s+"""), " ").trim()
        if (text.length <= maxChars) return text
        val lower = text.lowercase()
        val positions = keywords.flatMap { k -> allIndexes(lower, k) }.sorted()
        if (positions.isEmpty()) return cut(text, 0, maxChars)
        // Slide a window over the hit positions and keep the one covering most hits.
        var bestStart = positions.first()
        var bestCount = 0
        var j = 0
        for (i in positions.indices) {
            while (j < positions.size && positions[j] - positions[i] < maxChars) j++
            if (j - i > bestCount) {
                bestCount = j - i
                bestStart = positions[i]
            }
        }
        val start = (bestStart - maxChars / 5).coerceIn(0, (text.length - maxChars).coerceAtLeast(0))
        return cut(text, start, maxChars)
    }

    private fun cut(text: String, start: Int, maxChars: Int): String {
        var s = start
        var e = (start + maxChars).coerceAtMost(text.length)
        if (s > 0) text.indexOf(' ', s).takeIf { it in s until e }?.let { s = it + 1 }
        if (e < text.length) text.lastIndexOf(' ', e).takeIf { it > s }?.let { e = it }
        return buildString {
            if (s > 0) append("… ")
            append(text, s, e)
            if (e < text.length) append(" …")
        }
    }

    private fun countOccurrences(haystack: String, needle: String): Int = allIndexes(haystack, needle).size

    private fun allIndexes(haystack: String, needle: String): List<Int> {
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<Int>()
        var i = haystack.indexOf(needle)
        while (i >= 0 && out.size < 500) {
            out += i
            i = haystack.indexOf(needle, i + needle.length)
        }
        return out
    }
}
