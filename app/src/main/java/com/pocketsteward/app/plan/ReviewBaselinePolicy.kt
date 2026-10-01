package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.rawValue

/** Rebuilding a draft cannot replace its evidence-time source baseline with a changed file. */
object ReviewBaselinePolicy {
    fun merge(
        operations: List<PlannedOperation>,
        current: Map<String, SourcePrecondition>,
        original: Map<String, SourcePrecondition>,
    ): Map<String, SourcePrecondition> {
        operations.mapNotNull(ReviewedSources::sourceOf).map { it.rawValue() }.distinct().forEach { key ->
            val before = original[key] ?: return@forEach
            val failure = ReviewedSources.failure(key, before, key in current, current[key])
            require(failure == null) { failure.orEmpty() }
        }
        return current + original
    }
}
