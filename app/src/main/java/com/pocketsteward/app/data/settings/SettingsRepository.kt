package com.pocketsteward.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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

class SettingsRepository(private val context: Context) {

    private object Keys {
        val STORAGE_MODE = stringPreferencesKey("storage_access_mode")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val METADATA_INDEXING = booleanPreferencesKey("metadata_indexing_enabled")
        val CONTENT_INSPECTION = booleanPreferencesKey("content_inspection_enabled")
        val IMAGE_ANALYSIS = booleanPreferencesKey("image_analysis_enabled")
        val ON_DEVICE_AI = booleanPreferencesKey("on_device_ai_enabled")
        val PROJECT_KEYWORDS = stringPreferencesKey("project_keywords")
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
