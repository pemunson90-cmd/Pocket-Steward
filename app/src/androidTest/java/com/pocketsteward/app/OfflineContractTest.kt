package com.pocketsteward.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The V1 offline promise is architectural, not a preference toggle.
 *
 * The application itself must not request INTERNET. Model download is owned
 * by Android/AICore, while scanning, planning, mutation, journaling and undo
 * remain functional without a network permission in Pocket Steward.
 */
@RunWith(AndroidJUnit4::class)
class OfflineContractTest {
    @Test
    fun applicationDoesNotRequestInternetPermission() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requested = info.requestedPermissions.orEmpty().toSet()

        assertFalse(
            "Pocket Steward V1 must remain network-independent",
            Manifest.permission.INTERNET in requested,
        )
    }
}
