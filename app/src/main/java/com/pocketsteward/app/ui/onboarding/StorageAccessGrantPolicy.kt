package com.pocketsteward.app.ui.onboarding

import com.pocketsteward.app.data.settings.StorageAccessState
import com.pocketsteward.app.storage.StorageAccessMode

/**
 * Pure policy for deciding whether a persisted storage choice is still backed
 * by an Android OS grant. Persisted preference alone is never authority.
 */
object StorageAccessGrantPolicy {
    fun isUsable(
        state: StorageAccessState,
        broadAccessGranted: Boolean,
        safReadGranted: Boolean,
        safWriteGranted: Boolean,
    ): Boolean = when (state.mode) {
        StorageAccessMode.DIRECT -> broadAccessGranted
        StorageAccessMode.SAF ->
            !state.safTreeUri.isNullOrBlank() && safReadGranted && safWriteGranted
        null -> false
    }
}
