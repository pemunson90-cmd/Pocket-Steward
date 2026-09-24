package com.pocketsteward.app.data.settings

import kotlinx.coroutines.flow.first
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.pocketsteward.app.picker.RecentFolders
import com.pocketsteward.app.content.index.ContentSearchFilters
import com.pocketsteward.app.content.index.ContentSearchSort
import androidx.datastore.preferences.preferencesDataStore
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.saved.CorrectionRule
import com.pocketsteward.app.saved.LastScanSessionCodec
import com.pocketsteward.app.saved.LastScanSession
import com.pocketsteward.app.saved.FavoriteDestination
import com.pocketsteward.app.saved.OrganizationPreferenceCodec
import com.pocketsteward.app.saved.SavedWorkflow
import com.pocketsteward.app.saved.SavedWorkflowCodec
import com.pocketsteward.app.saved.SavedSearch
import com.pocketsteward.app.saved.SavedSearchCodec
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.scheduled.PendingCleanupSuggestion
import com.pocketsteward.app.scheduled.PendingCleanupSuggestionCodec
import com.pocketsteward.app.scheduled.ScheduledCleanupCodec
import com.pocketsteward.app.scheduled.ScheduledCleanupSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

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

/**
 * The background library: a whole-storage inventory kept current on a
 * schedule so scans and the file browser start from data already on hand.
 * Reading file names and sizes never leaves the phone; content indexing
 * additionally needs [PrivacySettings.contentInspectionEnabled].
 */
data class LibrarySettings(
    val backgroundRefreshEnabled: Boolean = true,
    val contentIndexWhileCharging: Boolean = true,
)

data class StorageAccessState(
    val mode: StorageAccessMode? = null,
    val safTreeUri: String? = null,
)

data class UiSettings(
    val advancedModeEnabled: Boolean = false,
    /**
     * Follow the phone's wallpaper (Material You) instead of the fixed Pocket
     * Steward palette. Off by default: the fixed palette is the ratified look,
     * and this is an opt-in. Ignored below Android 12, which has no dynamic
     * colour.
     */
    val wallpaperColorsEnabled: Boolean = false,
    /**
     * Draw real previews for images, videos and PDFs in file lists. Previews
     * are decoded locally, kept only in memory, and never written anywhere.
     * Off means every file shows its type badge instead.
     */
    val thumbnailsEnabled: Boolean = true,
)

class SettingsRepository(private val context: Context) {

    private companion object {
        const val MAX_SAVED_WORKFLOWS = 12
        const val MAX_SAVED_SEARCHES = 20
    }

    private object Keys {
        val STORAGE_MODE = stringPreferencesKey("storage_access_mode")
        val SAF_TREE_URI = stringPreferencesKey("saf_tree_uri")
        val METADATA_INDEXING = booleanPreferencesKey("metadata_indexing_enabled")
        val CONTENT_INSPECTION = booleanPreferencesKey("content_inspection_enabled")
        val IMAGE_ANALYSIS = booleanPreferencesKey("image_analysis_enabled")
        val ON_DEVICE_AI = booleanPreferencesKey("on_device_ai_enabled")
        val PROJECT_KEYWORDS = stringPreferencesKey("project_keywords")
        val ADVANCED_MODE = booleanPreferencesKey("advanced_mode_enabled")
        val WALLPAPER_COLORS = booleanPreferencesKey("wallpaper_colors_enabled")
        val THUMBNAILS = booleanPreferencesKey("thumbnails_enabled")
        val LIBRARY_BACKGROUND = booleanPreferencesKey("library_background_refresh")
        val LIBRARY_CONTENT_CHARGING = booleanPreferencesKey("library_content_while_charging")
        val LIBRARY_LAST_COMPLETED = androidx.datastore.preferences.core.longPreferencesKey("library_last_completed_at")
        val LIBRARY_LAST_ROOT = androidx.datastore.preferences.core.stringPreferencesKey("library_last_completed_root")
        val SAVED_WORKFLOWS = stringPreferencesKey("saved_workflows")
        val SAVED_SEARCHES = stringPreferencesKey("saved_searches")
        val FAVORITE_DESTINATIONS = stringPreferencesKey("favorite_destinations")
        val CORRECTION_RULES = stringPreferencesKey("correction_rules")
        val SCHEDULED_CLEANUP = stringPreferencesKey("scheduled_cleanup")
        val PENDING_CLEANUP_SUGGESTION = stringPreferencesKey("pending_cleanup_suggestion")
        val LAST_SCAN_SESSION = stringPreferencesKey("last_scan_session")

        /**
         * M7 spec 2d. In DataStore rather than Room on purpose. When this was
         * written `AppDatabase` was on `fallbackToDestructiveMigration`, so a
         * new entity would have destroyed the undo journals. It now uses real
         * migrations; a preference list still needs no schema change at all. Same serialised-list shape as [PROJECT_KEYWORDS].
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


    val savedWorkflows: Flow<List<SavedWorkflow>> = context.dataStore.data.map { prefs ->
        SavedWorkflowCodec.decode(prefs[Keys.SAVED_WORKFLOWS])
    }


    val favoriteDestinations: Flow<List<FavoriteDestination>> = context.dataStore.data.map { prefs ->
        OrganizationPreferenceCodec.decodeDestinations(prefs[Keys.FAVORITE_DESTINATIONS])
    }

    val correctionRules: Flow<List<CorrectionRule>> = context.dataStore.data.map { prefs ->
        OrganizationPreferenceCodec.decodeCorrections(prefs[Keys.CORRECTION_RULES])
    }

    val scheduledCleanupSettings: Flow<ScheduledCleanupSettings> = context.dataStore.data.map { prefs ->
        ScheduledCleanupCodec.decode(prefs[Keys.SCHEDULED_CLEANUP])
    }

    val pendingCleanupSuggestion: Flow<PendingCleanupSuggestion?> = context.dataStore.data.map { prefs ->
        PendingCleanupSuggestionCodec.decode(prefs[Keys.PENDING_CLEANUP_SUGGESTION])
    }

    val lastScanSession: Flow<LastScanSession?> = context.dataStore.data.map { prefs ->
        LastScanSessionCodec.decode(prefs[Keys.LAST_SCAN_SESSION])
    }

    suspend fun setLastScanSession(value: LastScanSession) {
        context.dataStore.edit {
            it[Keys.LAST_SCAN_SESSION] = LastScanSessionCodec.encode(value)
        }
    }

    suspend fun clearLastScanSession() {
        context.dataStore.edit {
            it.remove(Keys.LAST_SCAN_SESSION)
        }
    }

    suspend fun setPendingCleanupSuggestion(value: PendingCleanupSuggestion) {
        context.dataStore.edit {
            it[Keys.PENDING_CLEANUP_SUGGESTION] = PendingCleanupSuggestionCodec.encode(value)
        }
    }

    suspend fun clearPendingCleanupSuggestion() {
        context.dataStore.edit {
            it.remove(Keys.PENDING_CLEANUP_SUGGESTION)
        }
    }

    suspend fun setScheduledCleanupSettings(value: ScheduledCleanupSettings) {
        context.dataStore.edit {
            it[Keys.SCHEDULED_CLEANUP] = ScheduledCleanupCodec.encode(value)
        }
    }

    suspend fun setFavoriteDestinations(values: List<FavoriteDestination>) {
        val cleaned = values
            .map {
                it.copy(
                    name = it.name.trim().take(60),
                    path = it.path.trim().trimEnd('/'),
                )
            }
            .filter { it.name.isNotBlank() && it.path.isNotBlank() }
            .distinctBy { it.path.lowercase() }
            .take(20)
        context.dataStore.edit {
            it[Keys.FAVORITE_DESTINATIONS] = OrganizationPreferenceCodec.encodeDestinations(cleaned)
        }
    }

    suspend fun addCorrectionRule(term: String, destinationFolder: String) {
        val cleanedTerm = term.trim().take(80)
        val cleanedFolder = destinationFolder.trim().take(80)
        if (cleanedTerm.isBlank() || cleanedFolder.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = OrganizationPreferenceCodec.decodeCorrections(prefs[Keys.CORRECTION_RULES])
            val updated = (
                listOf(CorrectionRule(cleanedTerm, cleanedFolder)) +
                    current.filterNot { it.term.equals(cleanedTerm, ignoreCase = true) }
                ).take(100)
            prefs[Keys.CORRECTION_RULES] = OrganizationPreferenceCodec.encodeCorrections(updated)
        }
    }

    suspend fun setCorrectionRules(values: List<CorrectionRule>) {
        val cleaned = values
            .map { CorrectionRule(it.term.trim().take(80), it.destinationFolder.trim().take(80)) }
            .filter { it.term.isNotBlank() && it.destinationFolder.isNotBlank() }
            .distinctBy { it.term.lowercase() }
            .take(100)
        context.dataStore.edit {
            it[Keys.CORRECTION_RULES] = OrganizationPreferenceCodec.encodeCorrections(cleaned)
        }
    }


    val savedSearches: Flow<List<SavedSearch>> = context.dataStore.data.map { prefs ->
        SavedSearchCodec.decode(prefs[Keys.SAVED_SEARCHES])
            .sortedByDescending { it.lastOpenedAt }
    }

    suspend fun saveWorkflow(
        name: String,
        request: String,
        roots: List<String>,
    ): SavedWorkflow {
        val cleanedName = name.trim().take(60)
        require(cleanedName.isNotBlank()) { "Saved workflow name cannot be blank." }
        val cleanedRoots = roots
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()
        require(cleanedRoots.isNotEmpty()) { "Saved workflow needs at least one folder." }

        val workflow = SavedWorkflow(
            id = UUID.randomUUID().toString(),
            name = cleanedName,
            request = request.trim().take(2_000),
            roots = cleanedRoots,
        )
        context.dataStore.edit { prefs ->
            val current = SavedWorkflowCodec.decode(prefs[Keys.SAVED_WORKFLOWS])
            val updated = (listOf(workflow) + current)
                .distinctBy { it.id }
                .take(MAX_SAVED_WORKFLOWS)
            prefs[Keys.SAVED_WORKFLOWS] = SavedWorkflowCodec.encode(updated)
        }
        return workflow
    }

    suspend fun deleteSavedWorkflow(id: String) {
        context.dataStore.edit { prefs ->
            val updated = SavedWorkflowCodec.decode(prefs[Keys.SAVED_WORKFLOWS])
                .filterNot { it.id == id }
            prefs[Keys.SAVED_WORKFLOWS] = SavedWorkflowCodec.encode(updated)
        }
    }

    suspend fun saveSearch(
        name: String,
        query: String,
        roots: List<String>,
        sort: ContentSearchSort,
        filters: ContentSearchFilters,
        lastResultCount: Int,
    ): SavedSearch {
        val cleanedName = name.trim().take(80)
        val cleanedQuery = query.trim().take(2_000)
        val cleanedRoots = roots
            .map { it.trim().trimEnd('/') }
            .filter { it.isNotBlank() }
            .distinct()

        require(cleanedName.isNotBlank()) { "Saved search name cannot be blank." }
        require(cleanedQuery.isNotBlank()) { "Saved search query cannot be blank." }
        require(cleanedRoots.isNotEmpty()) { "Saved search needs at least one folder." }

        val saved = SavedSearch(
            id = UUID.randomUUID().toString(),
            name = cleanedName,
            query = cleanedQuery,
            roots = cleanedRoots,
            sort = sort,
            filters = filters,
            lastResultCount = lastResultCount.coerceAtLeast(0),
            lastOpenedAt = System.currentTimeMillis(),
        )

        context.dataStore.edit { prefs ->
            val current = SavedSearchCodec.decode(prefs[Keys.SAVED_SEARCHES])
            val updated = (listOf(saved) + current)
                .distinctBy { it.id }
                .sortedByDescending { it.lastOpenedAt }
                .take(MAX_SAVED_SEARCHES)
            prefs[Keys.SAVED_SEARCHES] = SavedSearchCodec.encode(updated)
        }
        return saved
    }

    suspend fun touchSavedSearch(id: String, resultCount: Int) {
        context.dataStore.edit { prefs ->
            val current = SavedSearchCodec.decode(prefs[Keys.SAVED_SEARCHES])
            val updated = current.map { saved ->
                if (saved.id == id) {
                    saved.copy(
                        lastResultCount = resultCount.coerceAtLeast(0),
                        lastOpenedAt = System.currentTimeMillis(),
                    )
                } else {
                    saved
                }
            }
            prefs[Keys.SAVED_SEARCHES] = SavedSearchCodec.encode(
                updated.sortedByDescending { it.lastOpenedAt }.take(MAX_SAVED_SEARCHES),
            )
        }
    }

    suspend fun deleteSavedSearch(id: String) {
        context.dataStore.edit { prefs ->
            val updated = SavedSearchCodec.decode(prefs[Keys.SAVED_SEARCHES])
                .filterNot { it.id == id }
            prefs[Keys.SAVED_SEARCHES] = SavedSearchCodec.encode(updated)
        }
    }

    /** Records [path] as the most recently scanned folder, moving it if it was already there. */
    suspend fun rememberRecentFolder(path: String) {
        context.dataStore.edit { prefs ->
            val updated = RecentFolders.add(RecentFolders.decode(prefs[Keys.RECENT_FOLDERS]), path)
            prefs[Keys.RECENT_FOLDERS] = RecentFolders.encode(updated)
        }
    }

    val uiSettings: Flow<UiSettings> = context.dataStore.data.map { prefs ->
        UiSettings(
            advancedModeEnabled = prefs[Keys.ADVANCED_MODE] ?: false,
            wallpaperColorsEnabled = prefs[Keys.WALLPAPER_COLORS] ?: false,
            thumbnailsEnabled = prefs[Keys.THUMBNAILS] ?: true,
        )
    }

    val librarySettings: Flow<LibrarySettings> = context.dataStore.data.map { prefs ->
        LibrarySettings(
            backgroundRefreshEnabled = prefs[Keys.LIBRARY_BACKGROUND] ?: true,
            contentIndexWhileCharging = prefs[Keys.LIBRARY_CONTENT_CHARGING] ?: true,
        )
    }

    /** When the library root last finished a full walk, and which root that was. */
    suspend fun libraryLastCompleted(): Pair<String, Long>? = context.dataStore.data.first().let { prefs ->
        val root = prefs[Keys.LIBRARY_LAST_ROOT] ?: return@let null
        val at = prefs[Keys.LIBRARY_LAST_COMPLETED] ?: return@let null
        root to at
    }

    suspend fun setLibraryLastCompleted(root: String, at: Long) {
        context.dataStore.edit {
            it[Keys.LIBRARY_LAST_ROOT] = root
            it[Keys.LIBRARY_LAST_COMPLETED] = at
        }
    }

    suspend fun setLibraryBackgroundRefresh(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIBRARY_BACKGROUND] = enabled }
    }

    suspend fun setLibraryContentWhileCharging(enabled: Boolean) {
        context.dataStore.edit { it[Keys.LIBRARY_CONTENT_CHARGING] = enabled }
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
            it.remove(Keys.LAST_SCAN_SESSION)
        }
    }

    suspend fun setAdvancedModeEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADVANCED_MODE] = enabled }
    }

    suspend fun setWallpaperColorsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.WALLPAPER_COLORS] = enabled }
    }

    suspend fun setThumbnailsEnabled(enabled: Boolean) {
        context.dataStore.edit { it[Keys.THUMBNAILS] = enabled }
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
