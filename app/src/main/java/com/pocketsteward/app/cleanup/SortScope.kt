package com.pocketsteward.app.cleanup

/**
 * The name of the marker file that means "do not sort the contents of this
 * folder." Deliberately not a dotfile: someone who finds it in six months,
 * with the app uninstalled, should be able to read the name and understand
 * what it is.
 *
 * Presence is the entire signal. An empty file works. Whatever is inside is
 * human notes, and nothing in this app may ever require a format, a header,
 * or a parse to honor it — a protection that stops working because its
 * contents didn't match an expected shape would be worse than no protection.
 *
 * It lives in the filesystem rather than the database on purpose. It survives
 * app reinstall, a database reset, and moving storage to another device,
 * none of which the database can promise.
 * The database is not the source of truth for this.
 */
const val DO_NOT_SORT_MARKER: String = "POCKETSTEWARD-DO-NOT-SORT.md"

/** Default body, written when the marker is created from inside the app. Never parsed. */
val DO_NOT_SORT_TEMPLATE: String = """
    # Do not sort this folder

    Pocket Steward will not move, rename, or trash anything in this folder or
    any folder inside it, for as long as this file exists.

    Delete this file to remove that protection.

    The presence of this file is the entire signal. You can write whatever you
    like below — notes to yourself, why this folder is grouped, anything. The
    app never reads it.
""".trimIndent()

/**
 * A file's position relative to the scan root, as the plan generator needs to
 * judge it. Kept as plain strings rather than `FileRecord` so the decision
 * logic stays pure Kotlin and gets real test coverage, the same boundary
 * `plan`, `rules` and `KeeperSelector` are held to.
 */
data class SortCandidate(
    val stableRef: String,
    val parentRef: String?,
    val displayName: String,
    val isDirectory: Boolean,
)

/**
 * Precomputed protection ancestry for a candidate set.
 *
 * Building the directory-parent map is O(n), so callers that inspect many
 * files in the same scan must build this once and reuse it rather than
 * reconstructing it for every candidate.
 */
data class SortProtectionIndex internal constructor(
    val protectedFolders: Set<String>,
    internal val parentByRef: Map<String, String?>,
)

/**
 * Decides what Smart cleanup is allowed to touch. Two independent mechanisms,
 * and they are not substitutes for each other:
 *
 * **Depth** ([includeSubfolders], default false). Only files sitting loose
 * directly in the scan root get sorted. A file someone already put inside a
 * folder has been organized by a human, and moving it into a category folder
 * destroys that grouping. This default alone would have prevented the
 * 2026-09-17 run that pulled 4,829 files out of their folders, with no
 * configuration and nothing for the user to remember.
 *
 * **Markers** ([DO_NOT_SORT_MARKER]). A folder containing the marker file is
 * off limits, along with everything beneath it, whatever the depth setting
 * says. This is the mechanism that survives the user opting into subfolder
 * sorting, and the one an AI can later propose without being able to mutate
 * anything itself.
 */
object SortScope {

    /**
     * Folders protected by a marker file: the parent folder of every marker
     * found in [candidates]. Returned as raw path prefixes.
     */
    fun protectedFolders(candidates: List<SortCandidate>): Set<String> =
        candidates
            .filter { !it.isDirectory && it.displayName == DO_NOT_SORT_MARKER }
            .mapNotNull { it.parentRef }
            .toSet()

    fun protectionIndex(candidates: List<SortCandidate>): SortProtectionIndex =
        SortProtectionIndex(
            protectedFolders = protectedFolders(candidates),
            parentByRef = candidates
                .asSequence()
                .filter { it.isDirectory }
                .associate { it.stableRef to it.parentRef },
        )

    /**
     * True when [candidate] sits inside [protectedFolders] or inside anything
     * beneath one. Recursive by default, which is the conservative reading:
     * a user who protects a project folder means its subfolders too.
     */
    fun isProtected(candidate: SortCandidate, protectedFolders: Set<String>): Boolean {
        val parent = candidate.parentRef ?: return false
        return protectedFolders.any { protectedFolder ->
            parent == protectedFolder || (!parent.startsWith("content://") && !protectedFolder.startsWith("content://") && parent.startsWith("${protectedFolder.trimEnd('/')}/"))
        }
    }

    /**
     * Provider-neutral protection inheritance. SAF document URIs do not
     * necessarily preserve path ancestry as a string prefix, so walk the
     * indexed directory parent links before falling back to the legacy path
     * prefix rule.
     */
    fun isProtected(
        candidate: SortCandidate,
        protection: SortProtectionIndex,
    ): Boolean {
        var parent = candidate.parentRef ?: return false
        val visited = mutableSetOf<String>()

        while (visited.add(parent)) {
            if (parent in protection.protectedFolders) return true
            parent = protection.parentByRef[parent] ?: break
        }
        return isProtected(candidate, protection.protectedFolders)
    }

    fun isProtected(
        candidate: SortCandidate,
        protectedFolders: Set<String>,
        candidates: List<SortCandidate>,
    ): Boolean =
        isProtected(
            candidate,
            SortProtectionIndex(
                protectedFolders = protectedFolders,
                parentByRef = candidates
                    .asSequence()
                    .filter { it.isDirectory }
                    .associate { it.stableRef to it.parentRef },
            ),
        )

    /** True when [candidate] is loose directly in [scopeRoot] rather than inside a subfolder. */
    fun isDirectlyInRoot(candidate: SortCandidate, scopeRoot: String): Boolean =
        candidate.parentRef?.trimEnd('/') == scopeRoot.trimEnd('/')

    /**
     * Splits [candidates] into what a run may touch and why the rest was
     * spared, so the plan preview can state the protection rather than
     * silently applying it.
     */
    fun partition(
        candidates: List<SortCandidate>,
        scopeRoot: String,
        includeSubfolders: Boolean,
    ): SortPartition {
        val protection = protectionIndex(candidates)
        val sortable = mutableListOf<SortCandidate>()
        var skippedByProtection = 0
        var skippedByDepth = 0

        for (candidate in candidates) {
            if (candidate.isDirectory) continue
            if (candidate.displayName == DO_NOT_SORT_MARKER) continue

            if (isProtected(candidate, protection)) {
                skippedByProtection++
                continue
            }
            if (!includeSubfolders && !isDirectlyInRoot(candidate, scopeRoot)) {
                skippedByDepth++
                continue
            }
            sortable += candidate
        }

        return SortPartition(
            sortable = sortable,
            protectedFolders = protection.protectedFolders,
            skippedByProtection = skippedByProtection,
            skippedByDepth = skippedByDepth,
        )
    }
}

data class SortPartition(
    val sortable: List<SortCandidate>,
    val protectedFolders: Set<String>,
    val skippedByProtection: Int,
    val skippedByDepth: Int,
)

/**
 * What a generated plan spared and why, so the preview can state it rather
 * than applying it silently. Lives here rather than beside the generator
 * because it is a [SortScope] fact, and because keeping it free of Android
 * imports is what lets it be tested.
 */
data class CleanupScopeReport(
    val protectedFolderCount: Int,
    val skippedByProtection: Int,
    val skippedByDepth: Int,
    val sortedCount: Int,
)

/**
 * Spec 6a/6b: the preview has to *state* what a run declined to touch, so a
 * protection is visible rather than silent. Returns nothing when nothing was
 * spared — an empty list renders as no section at all, which is honest; a
 * "0 folders protected" line would train the eye to skip the whole block.
 */
fun CleanupScopeReport.previewLines(): List<String> = buildList {
    if (protectedFolderCount > 0) {
        add(
            "$protectedFolderCount ${"folder".plural(protectedFolderCount)} protected by " +
                "$DO_NOT_SORT_MARKER, sparing $skippedByProtection ${"file".plural(skippedByProtection)}",
        )
    }
    if (skippedByDepth > 0) {
        add("$skippedByDepth ${"file".plural(skippedByDepth)} left alone inside existing folders")
    }
}

private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"
