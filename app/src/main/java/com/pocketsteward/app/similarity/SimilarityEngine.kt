package com.pocketsteward.app.similarity

import java.nio.ByteBuffer
import java.security.MessageDigest

enum class SimilarityKind { IMAGE, DOCUMENT }

data class SimilaritySignature(
    val stableRef: String,
    val kind: SimilarityKind,
    val hash: Long,
)

data class SimilarityGroup(
    val kind: SimilarityKind,
    val stableRefs: List<String>,
)

/**
 * Read-only near-duplicate grouping. It deliberately never feeds the exact
 * duplicate trash path: similarity is evidence for review, never permission
 * to remove a file.
 */
object SimilarityEngine {
    fun group(
        signatures: List<SimilaritySignature>,
        imageThreshold: Int = 6,
        documentThreshold: Int = 8,
    ): List<SimilarityGroup> {
        if (signatures.size < 2) return emptyList()
        val parent = IntArray(signatures.size) { it }

        fun root(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }

        fun union(a: Int, b: Int) {
            val ra = root(a)
            val rb = root(b)
            if (ra != rb) parent[rb] = ra
        }

        for (i in signatures.indices) {
            for (j in i + 1 until signatures.size) {
                val a = signatures[i]
                val b = signatures[j]
                if (a.kind != b.kind) continue
                val threshold = if (a.kind == SimilarityKind.IMAGE) imageThreshold else documentThreshold
                if (java.lang.Long.bitCount(a.hash xor b.hash) <= threshold) {
                    union(i, j)
                }
            }
        }

        return signatures.indices
            .groupBy(::root)
            .values
            .filter { it.size >= 2 }
            .map { indices ->
                val members = indices.map { signatures[it] }
                SimilarityGroup(
                    kind = members.first().kind,
                    stableRefs = members.map { it.stableRef }.distinct(),
                )
            }
            .filter { it.stableRefs.size >= 2 }
            .sortedByDescending { it.stableRefs.size }
    }
}

object DocumentSimHash {
    fun of(text: String): Long? {
        val tokens = text
            .lowercase()
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.length >= 3 }
        if (tokens.size < 8) return null

        val counts = tokens.groupingBy { it }.eachCount()
        val weights = IntArray(64)
        for ((token, count) in counts) {
            val hash = hash64(token)
            for (bit in 0 until 64) {
                if (((hash ushr bit) and 1L) == 1L) weights[bit] += count
                else weights[bit] -= count
            }
        }

        var result = 0L
        for (bit in 0 until 64) {
            if (weights[bit] >= 0) result = result or (1L shl bit)
        }
        return result
    }

    private fun hash64(value: String): Long {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return ByteBuffer.wrap(digest, 0, 8).long
    }
}
