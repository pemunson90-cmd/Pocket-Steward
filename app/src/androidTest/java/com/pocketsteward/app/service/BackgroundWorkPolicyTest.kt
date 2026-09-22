package com.pocketsteward.app.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundWorkPolicyTest {
    @Test
    fun contentIndex_waitsForBatteryAndStorageHealth() {
        val constraints = BackgroundWorkPolicy.contentIndexConstraints()
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun scheduledSuggestions_waitForBatteryAndStorageHealth() {
        val constraints = BackgroundWorkPolicy.scheduledSuggestionConstraints()
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun approvedFileMutations_onlyRequireStorageHeadroom() {
        val constraints = BackgroundWorkPolicy.fileMutationConstraints()
        assertFalse(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
    }
}
