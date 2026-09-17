package com.pocketsteward.app.di

import android.content.Context
import com.pocketsteward.app.data.db.AppDatabase
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.storage.DirectStorageGateway
import com.pocketsteward.app.storage.SafStorageGateway
import com.pocketsteward.app.storage.StorageGateway

/**
 * Deliberately manual dependency container rather than Hilt/Dagger. Milestone
 * 0's dependency graph is small enough that a DI framework would add build
 * complexity (another KSP/KAPT processor to version-match) without paying for
 * itself yet; revisit once the graph grows past what this can hold cleanly.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val settingsRepository: SettingsRepository by lazy { SettingsRepository(appContext) }
    val database: AppDatabase by lazy { AppDatabase.getInstance(appContext) }

    val directStorageGateway: StorageGateway by lazy { DirectStorageGateway(appContext) }
    val safStorageGateway: StorageGateway by lazy { SafStorageGateway(appContext) }
}
