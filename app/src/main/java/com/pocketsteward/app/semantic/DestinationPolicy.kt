package com.pocketsteward.app.semantic

import com.pocketsteward.app.storage.FileRef

enum class DestinationPolicy {
    ROOT_LOCAL,
    RECOMMENDED_DOCUMENTS,
    EXPLICIT_FOLDER,
}

data class SemanticDestinationChoice(
    val policy: DestinationPolicy,
    val explicitRoot: FileRef.Direct? = null,
)

/**
 * Resolves an already-approved policy into a concrete destination root.
 * Models never call this and never emit absolute roots.
 */
object DestinationPolicyResolver {
    fun resolve(
        choice: SemanticDestinationChoice,
        sourceRoot: FileRef.Direct,
        recommendedDocumentsRoot: FileRef.Direct,
    ): FileRef.Direct = when (choice.policy) {
        DestinationPolicy.ROOT_LOCAL -> sourceRoot
        DestinationPolicy.RECOMMENDED_DOCUMENTS -> recommendedDocumentsRoot
        DestinationPolicy.EXPLICIT_FOLDER ->
            requireNotNull(choice.explicitRoot) { "Explicit destination policy needs a chosen folder." }
    }
}
