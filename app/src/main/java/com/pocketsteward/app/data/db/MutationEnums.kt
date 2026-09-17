package com.pocketsteward.app.data.db

/**
 * Split out from MutationRecord.kt (which needs Room) so these two enums
 * stay plain Kotlin — [com.pocketsteward.app.plan.InverseCalculator] depends
 * on [MutationOperationType] and is verified in the same throwaway
 * plain-Kotlin module as the rest of the `plan` package (see
 * PlanValidatorTest); that only works if nothing it imports drags in an
 * androidx.room class.
 */
enum class MutationOperationType { CREATE_DIRECTORY, MOVE, RENAME, COPY, TRASH }
enum class MutationStatus { PENDING, COMMITTED, FAILED, UNDONE }
