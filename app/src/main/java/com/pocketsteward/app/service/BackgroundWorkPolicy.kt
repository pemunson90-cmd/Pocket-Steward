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
