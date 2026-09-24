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

    /** Scheduled library walk: listing and stat only, but thousands of them, so not on a low battery. */
    fun libraryRefreshConstraints(): Constraints =
        Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

    /** A refresh the user asked for, or one on app open: no battery gate, because they are looking at the result. */
    fun libraryRefreshNowConstraints(): Constraints = Constraints.NONE

    /**
     * Whole-library content indexing reads every document and runs OCR on
     * scanned PDFs. That is real CPU and battery, so it waits for the charger.
     */
    fun libraryContentIndexConstraints(): Constraints =
        Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
}
