package com.pocketsteward.app.intent

import com.pocketsteward.app.scan.FileCategory
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * Offline bounded request parser.
 *
 * Unknown language is never permission to guess. The parser supports the core
 * V1 command vocabulary plus explicit size/date/order criteria and a narrow
 * follow-up mode that can refine the previous request inside the same scan.
 */
object DeterministicIntentParser {
    fun parse(request: String): IntentParseResult =
        parse(request, previous = null, nowMillis = System.currentTimeMillis())

    fun parse(
        request: String,
        previous: BoundedIntent?,
        nowMillis: Long = System.currentTimeMillis(),
    ): IntentParseResult {
        val raw = request.trim()
        if (raw.isBlank()) return IntentParseResult.Unsupported("Type a request first.")

        val lower = raw.lowercase()
        val explicitAction = actionFor(lower)
        if (explicitAction == null && previous != null && looksLikeFollowUp(lower)) {
            return IntentParseResult.Parsed(
                refine(previous, raw, lower, nowMillis),
            )
        }
        val action = explicitAction ?: return IntentParseResult.Unsupported(
            "I can organize, group, find, move, copy, rename, review duplicates, or group existing archive files. " +
                "Follow-up requests can refine the previous command.",
        )

        val categories = parseCategories(lower).toMutableSet()
        if (action == IntentAction.ARCHIVE && categories.isEmpty()) {
            categories += FileCategory.ARCHIVE
        }

        val groupingMode =
            if (Regex("""\bproject|by\s+project|project\s+name""").containsMatchIn(lower)) {
                GroupingMode.PROJECT
            } else {
                GroupingMode.TYPE
            }

        val mainFolder = parseMainFolder(raw)
        val includeSubfolders = includesSubfolders(lower)

        if (action == IntentAction.RENAME) {
            val batch = Regex(
                """(?i)\brename\s+files?\s+(?:named|matching|containing)\s+["']?(.+?)["']?\s+to\s+["']?([^/"']+)["']?\s*$""",
            ).find(raw)
            if (batch != null) {
                val term = batch.groupValues[1].trim().trim('"', '\'')
                val template = batch.groupValues[2].trim().trim('"', '\'')
                if (term.isBlank() || template.isBlank() || '/' in template || '\\' in template) {
                    return IntentParseResult.Unsupported(
                        "Batch rename needs a filename term and one safe filename template.",
                    )
                }
                return IntentParseResult.Parsed(
                    BoundedIntent(
                        action = action,
                        rawRequest = raw,
                        renameMatchTerm = term,
                        renameTemplate = template,
                    ),
                )
            }

            val match = Regex(
                """(?i)\brename\s+["']?(.+?)["']?\s+to\s+["']?([^/"']+)["']?\s*$""",
            ).find(raw)
                ?: return IntentParseResult.Unsupported(
                    "Rename requests must use “rename <current name> to <new name>” or “rename files matching <term> to <template>”.",
                )
            val from = match.groupValues[1].trim().trim('"', '\'')
            val to = match.groupValues[2].trim().trim('"', '\'')
            if (from.isBlank() || to.isBlank() || '/' in to || '\\' in to) {
                return IntentParseResult.Unsupported("The rename target must be one plain file name.")
            }
            return IntentParseResult.Parsed(
                BoundedIntent(
                    action = action,
                    rawRequest = raw,
                    renameFrom = from,
                    renameTo = to,
                ),
            )
        }

        if (action == IntentAction.MOVE || action == IntentAction.COPY) {
            val destination = parseTransferDestination(raw)
                ?: return IntentParseResult.Unsupported(
                    "${if (action == IntentAction.MOVE) "Move" else "Copy"} requests must end with “to <folder>” or “into <folder>”.",
                )
            val criteria = parseCriteria(lower, nowMillis)
            val transferTerm = parseTransferTerm(raw)
            if (categories.isEmpty() && transferTerm.isNullOrBlank() && !criteria.hasAny) {
                return IntentParseResult.Unsupported(
                    "Tell me which files to ${if (action == IntentAction.MOVE) "move" else "copy"}, for example “${action.name.lowercase()} PDFs older than 6 months to Documents/Archive”.",
                )
            }
            return IntentParseResult.Parsed(
                BoundedIntent(
                    action = action,
                    rawRequest = raw,
                    categories = categories,
                    includeSubfolders = includeSubfolders,
                    findTerm = transferTerm,
                    destinationFolder = destination,
                    minSizeBytes = criteria.minSizeBytes,
                    maxSizeBytes = criteria.maxSizeBytes,
                    modifiedBefore = criteria.modifiedBefore,
                    modifiedAfter = criteria.modifiedAfter,
                    order = criteria.order,
                    resultLimit = criteria.resultLimit,
                ),
            )
        }

        val contentTerm = if (action == IntentAction.FIND) parseContentTerm(raw) else null
        val findTerm = if (action == IntentAction.FIND && contentTerm == null && categories.isEmpty()) {
            parseFindTerm(raw)
        } else {
            null
        }

        val criteria = parseCriteria(lower, nowMillis)

        if (action == IntentAction.FIND &&
            categories.isEmpty() &&
            findTerm.isNullOrBlank() &&
            contentTerm.isNullOrBlank() &&
            !criteria.hasAny
        ) {
            return IntentParseResult.Unsupported(
                "Tell me what to find, for example “find APKs”, “find files named invoice”, " +
                    "“find documents containing Lilith”, or “find files larger than 500 MB”.",
            )
        }

        return IntentParseResult.Parsed(
            BoundedIntent(
                action = action,
                rawRequest = raw,
                categories = categories,
                groupingMode = groupingMode,
                mainFolder = mainFolder,
                includeSubfolders = includeSubfolders,
                leaveUncertain = true,
                findTerm = findTerm,
                contentTerm = contentTerm,
                minSizeBytes = criteria.minSizeBytes,
                maxSizeBytes = criteria.maxSizeBytes,
                modifiedBefore = criteria.modifiedBefore,
                modifiedAfter = criteria.modifiedAfter,
                order = criteria.order,
                resultLimit = criteria.resultLimit,
            ),
        )
    }

    private fun actionFor(lower: String): IntentAction? = when {
        "duplicate" in lower -> IntentAction.DUPLICATE_REVIEW
        Regex("""\brename\b""").containsMatchIn(lower) -> IntentAction.RENAME
        Regex("""\bcopy\b""").containsMatchIn(lower) -> IntentAction.COPY
        Regex("""\bmove\b""").containsMatchIn(lower) -> IntentAction.MOVE
        Regex("""\bfind\b|\bshow\b|\blocate\b""").containsMatchIn(lower) -> IntentAction.FIND
        Regex("""\barchive\b""").containsMatchIn(lower) -> IntentAction.ARCHIVE
        Regex("""\bgroup\b""").containsMatchIn(lower) -> IntentAction.GROUP
        Regex("""\borganize\b|\bsort\b|\bclean\s*up\b|\bcleanup\b""").containsMatchIn(lower) ->
            IntentAction.ORGANIZE
        else -> null
    }

    private fun looksLikeFollowUp(lower: String): Boolean =
        lower.startsWith("same") ||
            lower.startsWith("those") ||
            lower.startsWith("that") ||
            lower.startsWith("now") ||
            lower.startsWith("also") ||
            lower.startsWith("only") ||
            "include subfolders" in lower ||
            "by project" in lower ||
            "by type" in lower

    private fun refine(
        previous: BoundedIntent,
        raw: String,
        lower: String,
        nowMillis: Long,
    ): BoundedIntent {
        val categories = parseCategories(lower)
        val criteria = parseCriteria(lower, nowMillis)
        val grouping = when {
            Regex("""\bby\s+project\b|\bproject\s+name\b""").containsMatchIn(lower) -> GroupingMode.PROJECT
            Regex("""\bby\s+type\b""").containsMatchIn(lower) -> GroupingMode.TYPE
            else -> previous.groupingMode
        }
        return previous.copy(
            rawRequest = raw,
            categories = if (categories.isEmpty()) previous.categories else categories,
            groupingMode = grouping,
            mainFolder = parseMainFolder(raw) ?: previous.mainFolder,
            destinationFolder = parseTransferDestination(raw) ?: previous.destinationFolder,
            includeSubfolders = if (includesSubfolders(lower)) true else previous.includeSubfolders,
            minSizeBytes = criteria.minSizeBytes ?: previous.minSizeBytes,
            maxSizeBytes = criteria.maxSizeBytes ?: previous.maxSizeBytes,
            modifiedBefore = criteria.modifiedBefore ?: previous.modifiedBefore,
            modifiedAfter = criteria.modifiedAfter ?: previous.modifiedAfter,
            order = if (criteria.order == IntentOrder.DEFAULT) previous.order else criteria.order,
            resultLimit = criteria.resultLimit ?: previous.resultLimit,
        )
    }

    private data class Criteria(
        val minSizeBytes: Long? = null,
        val maxSizeBytes: Long? = null,
        val modifiedBefore: Long? = null,
        val modifiedAfter: Long? = null,
        val order: IntentOrder = IntentOrder.DEFAULT,
        val resultLimit: Int? = null,
    ) {
        val hasAny: Boolean
            get() = minSizeBytes != null || maxSizeBytes != null ||
                modifiedBefore != null || modifiedAfter != null ||
                order != IntentOrder.DEFAULT || resultLimit != null
    }

    private fun parseCriteria(lower: String, nowMillis: Long): Criteria {
        var minSize: Long? = null
        var maxSize: Long? = null
        var before: Long? = null
        var after: Long? = null
        var order = IntentOrder.DEFAULT

        val sizePattern = Regex(
            """(?:(larger|bigger|over|above|smaller|under|below)(?:\s+than)?\s+)?(\d+(?:\.\d+)?)\s*(kb|mb|gb|kib|mib|gib)\b""",
        )
        sizePattern.findAll(lower).forEach { match ->
            val comparator = match.groupValues[1]
            val bytes = sizeToBytes(match.groupValues[2], match.groupValues[3])
            when (comparator) {
                "larger", "bigger", "over", "above" -> minSize = bytes
                "smaller", "under", "below" -> maxSize = bytes
            }
        }

        val age = Regex(
            """\b(older|newer)\s+than\s+(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s+(day|days|week|weeks|month|months|year|years)\b""",
        ).find(lower)
        if (age != null) {
            val duration = durationMillis(parseSmallNumber(age.groupValues[2]), age.groupValues[3])
            if (age.groupValues[1] == "older") before = nowMillis - duration
            else after = nowMillis - duration
        }

        val last = Regex(
            """\b(?:from\s+)?(?:the\s+)?last\s+(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s+(day|days|week|weeks|month|months|year|years)\b""",
        ).find(lower)
        if (last != null) {
            after = nowMillis - durationMillis(parseSmallNumber(last.groupValues[1]), last.groupValues[2])
        }

        if (Regex("""\bold\s+files?\b""").containsMatchIn(lower)) {
            before = nowMillis - durationMillis(6, "months")
            order = IntentOrder.OLDEST_FIRST
        }
        if (Regex("""\brecent\s+files?\b""").containsMatchIn(lower)) {
            after = nowMillis - durationMillis(30, "days")
            order = IntentOrder.NEWEST_FIRST
        }

        parseAbsoluteDate(lower, "before")?.let { before = it }
        parseAbsoluteDate(lower, "after")?.let { after = it }

        order = when {
            Regex("""\blargest\b|\bbiggest\b""").containsMatchIn(lower) -> IntentOrder.LARGEST_FIRST
            Regex("""\bsmallest\b""").containsMatchIn(lower) -> IntentOrder.SMALLEST_FIRST
            Regex("""\bnewest\b|\bmost recent\b""").containsMatchIn(lower) -> IntentOrder.NEWEST_FIRST
            Regex("""\boldest\b""").containsMatchIn(lower) -> IntentOrder.OLDEST_FIRST
            else -> order
        }

        val limit = Regex("""\b(?:top|first|show)\s+(\d{1,4})\b""")
            .find(lower)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.coerceIn(1, 1000)
            ?: if (order != IntentOrder.DEFAULT &&
                Regex("""\blargest\b|\bbiggest\b|\bsmallest\b|\bnewest\b|\boldest\b""").containsMatchIn(lower)
            ) {
                50
            } else {
                null
            }

        return Criteria(
            minSizeBytes = minSize,
            maxSizeBytes = maxSize,
            modifiedBefore = before,
            modifiedAfter = after,
            order = order,
            resultLimit = limit,
        )
    }

    private fun parseAbsoluteDate(lower: String, keyword: String): Long? {
        val date = Regex("""\b$keyword\s+(\d{4}-\d{2}-\d{2})\b""")
            .find(lower)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null
        return runCatching {
            LocalDate.parse(date)
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

    private fun sizeToBytes(value: String, unit: String): Long {
        val number = value.toDouble()
        val multiplier = when (unit) {
            "kb" -> 1_000.0
            "mb" -> 1_000_000.0
            "gb" -> 1_000_000_000.0
            "kib" -> 1024.0
            "mib" -> 1024.0 * 1024.0
            "gib" -> 1024.0 * 1024.0 * 1024.0
            else -> 1.0
        }
        return (number * multiplier).roundToLong().coerceAtLeast(0)
    }

    private fun parseSmallNumber(value: String): Long =
        value.toLongOrNull() ?: when (value.lowercase()) {
            "one" -> 1L
            "two" -> 2L
            "three" -> 3L
            "four" -> 4L
            "five" -> 5L
            "six" -> 6L
            "seven" -> 7L
            "eight" -> 8L
            "nine" -> 9L
            "ten" -> 10L
            "eleven" -> 11L
            "twelve" -> 12L
            else -> error("Unsupported number word: $value")
        }

    private fun durationMillis(value: Long, unit: String): Long {
        val days = when (unit.removeSuffix("s")) {
            "day" -> value
            "week" -> value * 7
            "month" -> value * 30
            "year" -> value * 365
            else -> 0
        }
        return days * 24L * 60L * 60L * 1000L
    }

    private fun parseCategories(lower: String): Set<FileCategory> = buildSet {
        if (Regex("""\bapk|apks|installer|installers\b""").containsMatchIn(lower)) add(FileCategory.APK)
        if (Regex("""\bimage|images|photo|photos|picture|pictures\b""").containsMatchIn(lower)) add(FileCategory.IMAGE)
        if (Regex("""\bpdf|pdfs|document|documents|docs|text files?|spreadsheets?|presentations?\b""").containsMatchIn(lower)) {
            add(FileCategory.DOCUMENT)
        }
        if (Regex("""\bzip|zips|archive files?|compressed files?\b""").containsMatchIn(lower)) add(FileCategory.ARCHIVE)
        if (Regex("""\baudio|video|videos|music|media\b""").containsMatchIn(lower)) add(FileCategory.AUDIO_VIDEO)
    }

    private fun parseMainFolder(raw: String): String? {
        val quoted = Regex("""(?i)\b(?:under|inside|within)\s+["']([^/"']+)["']""")
            .find(raw)?.groupValues?.getOrNull(1)
        if (!quoted.isNullOrBlank()) return quoted.trim()

        val called = Regex("""(?i)\b(?:main\s+)?folder\s+(?:called|named)\s+["']?([A-Za-z0-9 _-]{1,48})["']?""")
            .find(raw)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.trimEnd('.', ',', ';')
        if (!called.isNullOrBlank()) return called

        return if (Regex("""(?i)\bone\s+main\s+folder\b""").containsMatchIn(raw)) "Organized" else null
    }

    private fun includesSubfolders(lower: String): Boolean = listOf(
        "include subfolders",
        "inside subfolders",
        "inside folders",
        "nested files",
        "recursively",
        "recursive",
    ).any { it in lower }

    private fun parseTransferDestination(raw: String): String? {
        val match = Regex("""(?i)\b(?:to|into)\s+["']?(.+?)["']?\s*$""").find(raw) ?: return null
        return match.groupValues[1]
            .trim()
            .trim('"', '\'')
            .trimEnd('.', ',', ';')
            .takeIf { it.isNotBlank() && it != ".." }
    }

    private fun parseTransferTerm(raw: String): String? {
        val destination = Regex("""(?i)\b(?:to|into)\s+["']?.+?["']?\s*$""").find(raw)
        val beforeDestination = if (destination == null) raw else raw.substring(0, destination.range.first)
        val withoutAction = beforeDestination.replaceFirst(
            Regex("""(?i)^\s*(?:move|copy)\s+"""),
            "",
        ).trim()
        val named = Regex("""(?i)^(?:files?\s+)?(?:named|matching)\s+["']?(.+?)["']?$""")
            .find(withoutAction)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"', '\'')
        if (!named.isNullOrBlank()) return named

        val lower = withoutAction.lowercase()
        val looksLikeCriteria = parseCategories(lower).isNotEmpty() ||
            Regex("""\b(?:older|newer|largest|biggest|smallest|newest|oldest|over|under|above|below)\b""")
                .containsMatchIn(lower)
        return withoutAction.takeIf {
            it.isNotBlank() &&
                !looksLikeCriteria &&
                !it.equals("files", ignoreCase = true)
        }
    }

    private fun parseContentTerm(raw: String): String? {
        val patterns = listOf(
            Regex("""(?i)\b(?:containing|contains|mentioning|mentions)\s+["']?(.+?)["']?\s*$"""),
            Regex("""(?i)\bwith\s+(?:the\s+)?(?:text|content)\s+["']?(.+?)["']?\s*$"""),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(raw)?.groupValues?.getOrNull(1)
                ?.trim()
                ?.trim('"', '\'')
                ?.takeIf { it.isNotBlank() }
        }
    }

    private fun parseFindTerm(raw: String): String? {
        val match = Regex(
            """(?i)\b(?:find|show|locate)\s+(?:files?\s+)?(?:named|matching|containing)?\s*["']?(.+?)["']?\s*$""",
        ).find(raw) ?: return null
        val candidate = match.groupValues[1]
            .trim()
            .trim('"', '\'')
        val lower = candidate.lowercase()
        return candidate.takeIf {
            it.isNotBlank() &&
                lower !in setOf("files", "file") &&
                !Regex("""\b(larger|bigger|over|above|smaller|under|below|older|newer|largest|smallest|newest|oldest)\b""")
                    .containsMatchIn(lower)
        }
    }
}
