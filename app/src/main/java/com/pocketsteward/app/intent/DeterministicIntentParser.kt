package com.pocketsteward.app.intent

import com.pocketsteward.app.scan.FileCategory

/**
 * M9's offline request parser.
 *
 * It intentionally recognizes a bounded vocabulary and refuses requests it
 * cannot map deterministically. Unknown language is not permission to guess.
 */
object DeterministicIntentParser {
    fun parse(request: String): IntentParseResult {
        val raw = request.trim()
        if (raw.isBlank()) return IntentParseResult.Unsupported("Type a request first.")

        val lower = raw.lowercase()
        val action = when {
            "duplicate" in lower -> IntentAction.DUPLICATE_REVIEW
            Regex("""\brename\b""").containsMatchIn(lower) -> IntentAction.RENAME
            Regex("""\bfind\b|\bshow\b|\blocate\b""").containsMatchIn(lower) -> IntentAction.FIND
            Regex("""\barchive\b""").containsMatchIn(lower) -> IntentAction.ARCHIVE
            Regex("""\bgroup\b""").containsMatchIn(lower) -> IntentAction.GROUP
            Regex("""\borganize\b|\bsort\b|\bclean\s*up\b|\bcleanup\b""").containsMatchIn(lower) ->
                IntentAction.ORGANIZE
            else -> return IntentParseResult.Unsupported(
                "I can currently organize, group, find, rename, review duplicates, or group existing archive files.",
            )
        }

        val unsupportedCriterion = Regex(
            """\bolder\s+than\b|\bnewer\s+than\b|\bold\s+files?\b|\brecent\s+files?\b|\blargest\b|\bsmallest\b|\bbiggest\b|\bbefore\b|\bafter\b""",
        ).find(lower)?.value
        if (unsupportedCriterion != null && action != IntentAction.RENAME) {
            return IntentParseResult.Unsupported(
                "I understood the command, but M9 does not apply age, size, or date criteria inside text requests yet. " +
                    "Use the existing quick action where available.",
            )
        }

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
        val includeSubfolders = listOf(
            "include subfolders",
            "inside subfolders",
            "inside folders",
            "nested files",
            "recursively",
            "recursive",
        ).any { it in lower }

        if (action == IntentAction.RENAME) {
            val match = Regex(
                """(?i)\brename\s+["']?(.+?)["']?\s+to\s+["']?([^/"']+)["']?\s*$""",
            ).find(raw)
                ?: return IntentParseResult.Unsupported(
                    "Rename requests must use “rename <current name> to <new name>”.",
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

        val findTerm = if (action == IntentAction.FIND && categories.isEmpty()) {
            parseFindTerm(raw)
        } else {
            null
        }
        if (action == IntentAction.FIND && categories.isEmpty() && findTerm.isNullOrBlank()) {
            return IntentParseResult.Unsupported(
                "Tell me what to find, for example “find APKs” or “find files named invoice”.",
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
            ),
        )
    }

    private fun parseCategories(lower: String): Set<FileCategory> = buildSet {
        if (Regex("""\bapk|apks|installer|installers""").containsMatchIn(lower)) add(FileCategory.APK)
        if (Regex("""\bimage|images|photo|photos|picture|pictures""").containsMatchIn(lower)) add(FileCategory.IMAGE)
        if (Regex("""\bpdf|pdfs|document|documents|docs|text files?|spreadsheets?|presentations?""").containsMatchIn(lower)) {
            add(FileCategory.DOCUMENT)
        }
        if (Regex("""\bzip|zips|archive files?|compressed files?""").containsMatchIn(lower)) add(FileCategory.ARCHIVE)
        if (Regex("""\baudio|video|videos|music|media""").containsMatchIn(lower)) add(FileCategory.AUDIO_VIDEO)
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

    private fun parseFindTerm(raw: String): String? {
        val match = Regex(
            """(?i)\b(?:find|show|locate)\s+(?:files?\s+)?(?:named|matching|containing)?\s*["']?(.+?)["']?\s*$""",
        ).find(raw) ?: return null
        return match.groupValues[1]
            .trim()
            .trim('"', '\'')
            .takeIf { it.isNotBlank() && it.lowercase() !in setOf("files", "file") }
    }
}
