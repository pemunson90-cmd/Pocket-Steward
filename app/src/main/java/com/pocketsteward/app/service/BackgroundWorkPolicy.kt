package com.pocketsteward.app.service

import androidx.work.Constraints

/**
 * One place for Android scheduler safety constraints.
 *
 * Keeping these policies centralized makes the battery/storage rules both
 * inspectable and testable; individual workers cannot silently drift into a
 * less conservative configuration.
 */
object BackgroundWorkPolicy {
    const val MAX_TRANSIENT_ATTEMPTS: Int = 3

    fun shouldRetry(runAttemptCount: Int): Boolean =
        runAttemptCount >= 0 && runAttemptCount + 1 < MAX_TRANSIENT_ATTEMPTS

    fun fileMutationConstraints(): Constraints =
        Constraints.Builder()
            .setRequiresStorageNotLow(true)
            .build()

    fun contentIndexConstraints(): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

    fun scheduledSuggestionConstraints(): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
}
