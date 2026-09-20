package com.pocketsteward.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketsteward.app.picker.RecentFolders
import androidx.datastore.preferences.preferencesDataStore
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "pocket_steward_settings")

/**
 * Section 23 privacy switches, defaulted to the safest / narrowest setting.
 * Nothing here defaults to "on" for a capability that leaves the device or
 * inspects file contents.
 */
data class PrivacySettings(
    val metadataIndexingEnabled: Boolean = true,
    val contentInspectionEnabled: Boolean = false,
    val imageAnalysisEnabled: Boolean = false,
    val onDeviceAiEnabled: Boolean = false,
)

data class StorageAccessState(
    val mode: StorageAccessMode? = null,
    val safTreeUri: String? = null,
)

data class UiSettings(
    val advancedModeEnabled: Boolean = false,
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val STORAGE_MODE = stringPreferencesKey("storage_access_mode")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val METADATA_INDEXING = booleanPreferencesKey("metadata_indexing_enabled")
        val CONTENT_INSPECTION = booleanPreferencesKey("content_inspection_enabled")
        val IMAGE_ANALYSIS = booleanPreferencesKey("image_analysis_enabled")
        val ON_DEVICE_AI = booleanPreferencesKey("on_device_ai_enabled")
        val PROJECT_KEYWORDS = stringPreferencesKey("project_keywords")
        val ADVANCED_MODE = booleanPreferencesKey("advanced_mode_enabled")

        /**
         * M7 spec 2d. In DataStore rather than Room on purpose: `AppDatabase`
         * is on `fallbackToDestructiveMigration` and holds undo journals for
         * runs of several thousand operations, so a new entity would destroy
         * them. Same serialised-list shape as [PROJECT_KEYWORDS].
         */
        val RECENT_FOLDERS = stringPreferencesKey("recent_folders")
    }

    /**
     * Plan Section 9's user-configurable project terms (NSTL, Leaseworld,
     * Lilith, Erica, Aleksei, MarkdownDesk are the plan's own examples).
     * Encoded as one "term=folder" pair per line rather than JSON — nothing
     * else in this app has needed a JSON library yet, and a delimited
     * string is enough for a flat list with no nesting. `=` and newlines
     * inside a term or folder name are not supported; neither showed up in
     * the plan's own examples and there is no UI yet that would let a user
     * type one in.
     */
    val projectKeywords: Flow<List<ProjectKeyword>> = context.dataStore.data.map { prefs ->
        (prefs[Keys.PROJECT_KEYWORDS] ?: "").lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) ProjectKeyword(term = parts[0], projectFolder = parts[1]) else null
            }
            .toList()
    }

    suspend fun setProjectKeywords(keywords: List<ProjectKeyword>) {
        context.dataStore.edit {
            it[Keys.PROJECT_KEYWORDS] = keywords.joinToString("\n") { keyword -> "${keyword.term}=${keyword.projectFolder}" }
        }
    }

    /** Most recent first, capped by [RecentFolders.MAX]. */
    val recentFolders: Flow<List<String>> = context.dataStore.data.map { prefs ->
        RecentFolders.decode(prefs[Keys.RECENT_FOLDERS])
    }

    /** Records [path] as the most recently scanned folder, moving it if it was already there. */
    suspend fun rememberRecentFolder(path: String) {
        context.dataStore.edit { prefs ->
            val updated = RecentFolders.add(RecentFolders.decode(prefs[Keys.RECENT_FOLDERS]), path)
            prefs[Keys.RECENT_FOLDERS] = RecentFolders.encode(updated)
        }
    }

    val uiSettings: Flow<UiSettings> = context.dataStore.data.map { prefs ->
        UiSettings(advancedModeEnabled = prefs[Keys.ADVANCED_MODE] ?: false)
    }

    val storageAccessState: Flow<StorageAccessState> = context.dataStore.data.map { prefs ->
        StorageAccessState(
            mode = prefs[Keys.STORAGE_MODE]?.let { StorageAccessMode.valueOf(it) },
            safTreeUri = prefs[Keys.SAF_TREE_URI],
        )
    }

    val privacySettings: Flow<PrivacySettings> = context.dataStore.data.map { prefs ->
        PrivacySettings(
            metadataIndexingEnabled = prefs[Keys.METADATA_INDEXING] ?: true,
            contentInspectionEnabled = prefs[Keys.CONTENT_INSPECTION] ?: false,
            imageAnalysisEnabled = prefs[Keys.IMAGE_ANALYSIS] ?: false,
            onDeviceAiEnabled = prefs[Keys.ON_DEVICE_AI] ?: false,
        )
    }

    suspend fun setStorageAccessMode(mode: StorageAccessMode) {
        context.dataStore.edit { it[Keys.STORAGE_MODE] = mode.name }
    }

    suspend fun setSafTreeUri(uri: String) {
        context.dataStore.edit { it[Keys.SAF_TREE_URI] = uri }
    }

    /**
     * Forgets which access mode was chosen, so onboarding asks again. Does
     * not revoke the OS-level grant — that is the system's to give and take,
     * and only the user can do it from Android settings.
     */
    suspend fun clearStorageAccessChoice() {
        context.dataStore.edit {
            it.remove(Keys.STORAGE_MODE)
            it.remove(Keys.SAF_TREE_URI)
        }
    }

    suspend fun setAdvancedModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADVANCED_MODE] = enabled }
    }

    suspend fun setMetadataIndexingEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.METADATA_INDEXING] = enabled }
    }

    suspend fun setContentInspectionEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.CONTENT_INSPECTION] = enabled }
    }

    suspend fun setImageAnalysisEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.IMAGE_ANALYSIS] = enabled }
    }

    suspend fun setOnDeviceAiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ON_DEVICE_AI] = enabled }
    }
}
