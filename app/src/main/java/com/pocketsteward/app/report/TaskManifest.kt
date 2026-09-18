package com.pocketsteward.app.report

/**
 * The stated reason written onto every duplicate-trash operation, and the
 * only function allowed to write it.
 *
 * It is a parseable format on purpose. `MutationRecord` has no column for
 * "the copy this one lost to", and adding one would trigger
 * `fallbackToDestructiveMigration` and wipe every existing undo record in
 * the process — including a 4,829-operation run the owner still has open.
 * So the keeper's path rides in the reason text, which is already durable in
 * `TaskRun.planJson`, and [keeperPathFrom] is its inverse. The two are
 * tested as a round trip; nothing else may format or read this string.
 */
fun duplicateTrashReason(keeperPath: String): String = "$KEEPER_PREFIX$keeperPath$KEEPER_SUFFIX"

/** Inverse of [duplicateTrashReason]. Null for any reason that wasn't written by it. */
fun keeperPathFrom(reason: String?): String? {
    if (reason == null) return null
    if (!reason.startsWith(KEEPER_PREFIX) || !reason.endsWith(KEEPER_SUFFIX)) return null
    return reason.removePrefix(KEEPER_PREFIX).removeSuffix(KEEPER_SUFFIX).takeIf { it.isNotBlank() }
}

private const val KEEPER_PREFIX = "Duplicate of "
private const val KEEPER_SUFFIX = " (matching SHA-256)"

/**
 * One journaled operation, flattened into the plain strings a manifest needs.
 * Deliberately not `MutationRecord`: that type carries Room annotations, and
 * keeping this layer free of them is what lets the rendering and the
 * kept-count assertion be tested against a real compiler.
 */
data class ManifestEntry(
    val sequence: Int,
    val operation: String,
    val originalPath: String,
    val resultPath: String?,
    val fingerprint: String?,
    val succeeded: Boolean,
    val error: String?,
    val reason: String?,
)

/**
 * The headline the M6 spec asks for: *N groups, N kept, M trashed*, where
 * `groups == kept` is the property that actually matters. [consistent] is
 * false when they disagree, and the renderer shouts rather than printing two
 * numbers and moving on.
 */
data class DuplicateAssertion(
    val groups: Int,
    val kept: Int,
    val trashed: Int,
) {
    val consistent: Boolean get() = groups == kept

    fun headline(): String = if (consistent) {
        "$groups duplicate ${"group".plural(groups)}, $kept ${copies(kept)} kept, " +
            "$trashed ${copies(trashed)} trashed."
    } else {
        "CHECK THIS: $groups duplicate ${"group".plural(groups)} but $kept distinct kept ${copies(kept)}. " +
            "Every group should have kept exactly one. $trashed ${copies(trashed)} were trashed."
    }
}

object TaskManifest {

    /**
     * Counts the invariant from the journal rather than from the planner that
     * produced it. A planner asserting it kept one copy per group proves
     * nothing; these numbers come from what was actually written down at the
     * moment each file moved.
     */
    fun duplicateAssertion(entries: List<ManifestEntry>): DuplicateAssertion {
        val trashed = entries.filter { it.operation == "TRASH" && it.succeeded }
        return DuplicateAssertion(
            groups = trashed.mapNotNull { it.fingerprint }.distinct().size,
            kept = trashed.mapNotNull { keeperPathFrom(it.reason) }.distinct().size,
            trashed = trashed.size,
        )
    }

    /**
     * A plain-text account of one task run, written to be readable in a file
     * manager six months from now with the app uninstalled. That is the whole
     * point of exporting it: the database is on
     * `fallbackToDestructiveMigration`, so the journal this was built from
     * does not survive the next schema change, and this file does.
     */
    fun render(
        title: String,
        startedAtLabel: String,
        statusLabel: String,
        entries: List<ManifestEntry>,
    ): String = buildString {
        appendLine("# $title")
        appendLine()
        appendLine("Run started: $startedAtLabel")
        appendLine("Status: $statusLabel")
        appendLine("Operations recorded: ${entries.size}")
        appendLine()

        val trashed = entries.filter { it.operation == "TRASH" && it.succeeded }
        if (trashed.isNotEmpty()) {
            val assertion = duplicateAssertion(entries)
            appendLine("## ${assertion.headline()}")
            appendLine()
            appendLine("### Trashed copies")
            appendLine()
            // Grouped by the surviving copy, which is the question being
            // asked: "show me that each set kept one". Files whose reason
            // didn't name a keeper are grouped under a stated unknown rather
            // than quietly dropped.
            val byKeeper = trashed.groupBy { keeperPathFrom(it.reason) }
            for ((keeper, group) in byKeeper.entries.sortedBy { it.key ?: "" }) {
                appendLine("Kept: ${keeper ?: "(not recorded — this copy's reason did not name a survivor)"}")
                group.firstOrNull { it.fingerprint != null }?.fingerprint?.let {
                    appendLine("SHA-256: $it")
                }
                for (entry in group.sortedBy { it.originalPath }) {
                    appendLine("  - was: ${entry.originalPath}")
                    appendLine("    now: ${entry.resultPath ?: "(destination not recorded)"}")
                }
                appendLine()
            }
        }

        val moved = entries.filter { it.operation != "TRASH" && it.succeeded }
        if (moved.isNotEmpty()) {
            appendLine("### Other changes")
            appendLine()
            for (entry in moved) {
                appendLine("- ${entry.operation}: ${entry.originalPath}")
                entry.resultPath?.let { appendLine("    -> $it") }
                entry.reason?.takeIf { it.isNotBlank() }?.let { appendLine("    reason: $it") }
            }
            appendLine()
        }

        val failed = entries.filter { !it.succeeded }
        if (failed.isNotEmpty()) {
            appendLine("### Failed (${failed.size})")
            appendLine()
            for (entry in failed) {
                appendLine("- ${entry.operation}: ${entry.originalPath}")
                appendLine("    ${entry.error ?: "(no error recorded)"}")
            }
            appendLine()
        }

        appendLine("Nothing in this run was deleted. Trashed files were moved under PocketSteward/Trash/,")
        appendLine("mirroring their original folder structure, and are still there until removed by hand.")
    }

    /**
     * Recovers each operation's stated reason from `TaskRun.planJson`, which
     * `PlanExecutor.describePlan` writes as `sequence<TAB>TYPE<TAB>reason`.
     * Lines that don't start with a number are the goal line, the
     * left-untouched header, or a rejected operation, none of which have a
     * journal row to attach to.
     */
    fun reasonsBySequence(planJson: String): Map<Int, String> = planJson.lineSequence()
        .mapNotNull { line ->
            val fields = line.split('\t')
            if (fields.size < 3) return@mapNotNull null
            val sequence = fields[0].toIntOrNull() ?: return@mapNotNull null
            sequence to fields.drop(2).joinToString("\t")
        }
        .toMap()

    /** A filename that will not collide with an earlier export in the same folder. */
    fun fileName(taskRunId: Long, exportedAtEpochMs: Long): String =
        "POCKETSTEWARD-MANIFEST-task$taskRunId-$exportedAtEpochMs.md"
}

private fun String.plural(count: Int): String = if (count == 1) this else "${this}s"

private fun copies(count: Int): String = if (count == 1) "copy" else "copies"
