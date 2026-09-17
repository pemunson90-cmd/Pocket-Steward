package com.pocketsteward.app.dedupe

/**
 * One member of a duplicate group, reduced to just what the keeper rule
 * needs. Deliberately not a `FileRecord`: keeping this a plain data class is
 * what lets [KeeperSelector] stay pure Kotlin and get real compiler and test
 * coverage in the throwaway verification module, the same boundary `plan` and
 * `rules` are held to.
 */
data class KeeperCandidate(
    val rawRef: String,
    val modifiedAt: Long?,
    val displayName: String,
)

/**
 * Picks which copy of a byte-identical set survives when the rest are
 * trashed. The files are identical so no data is lost either way, but
 * "whatever order the detector happened to return" meant the keeper could be
 * the copy buried three folders deep while the obvious one got trashed, and
 * two runs over the same storage could disagree.
 *
 * Order, each step breaking the previous step's ties:
 *
 * 1. Shallowest path. The copy nearest the top of the tree is the one a user
 *    is most likely to think of as "the" file.
 * 2. Oldest modification time. The original ahead of a later copy. A null
 *    `modifiedAt` sorts last: unknown is not evidence of being the original.
 * 3. Shortest display name. `report.pdf` over `report (1).pdf`, which is what
 *    a copy-suffixing file manager produces.
 * 4. Lexical on the raw ref. A total order, so the answer is stable across
 *    runs instead of depending on scan order.
 */
object KeeperSelector {

    fun keeperIndex(candidates: List<KeeperCandidate>): Int {
        require(candidates.isNotEmpty()) { "Cannot choose a keeper from an empty group" }
        var bestIndex = 0
        for (index in 1 until candidates.size) {
            if (ordering.compare(candidates[index], candidates[bestIndex]) < 0) bestIndex = index
        }
        return bestIndex
    }

    private val ordering: Comparator<KeeperCandidate> =
        compareBy<KeeperCandidate> { candidate -> candidate.rawRef.count { it == '/' } }
            .thenBy { it.modifiedAt ?: Long.MAX_VALUE }
            .thenBy { it.displayName.length }
            .thenBy { it.rawRef }
}
