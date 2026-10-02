package com.pocketsteward.app.library

import android.content.Context
import android.os.Environment
import android.os.FileObserver
import android.os.SystemClock
import androidx.work.WorkManager
import com.pocketsteward.app.data.db.TaskRunDao
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.service.LibraryRefreshWorker
import com.pocketsteward.app.storage.StorageAccessMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

data class InboxObservationStatus(
    val watchedFolders: List<String> = emptyList(),
    val message: String = "Checking inbox monitoring…",
    val lastChangeAt: Long? = null,
)

/** Process-lifetime direct-storage watches, backed by the existing periodic library worker. */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxObservation(
    private val context: Context,
    private val settings: SettingsRepository,
    private val tasks: TaskRunDao,
    private val scope: CoroutineScope,
    private val onEvidenceChange: (Set<String>) -> Unit,
) {
    private val started = AtomicBoolean(false)
    private val permissionState = MutableStateFlow(Environment.isExternalStorageManager())
    private val serial = Dispatchers.IO.limitedParallelism(1)
    @Volatile private var generation = 0L
    private val mutableStatus = MutableStateFlow(InboxObservationStatus())
    val status: StateFlow<InboxObservationStatus> = mutableStatus.asStateFlow()
    private val watchLock = Any()
    private val watches = mutableListOf<FileObserver>()
    private val changedDirectories = linkedSetOf<String>()
    private val refreshQueue = InboxRefreshQueue(
        scope = CoroutineScope(scope.coroutineContext + serial),
        now = SystemClock::elapsedRealtime,
        awaitIdle = {
            tasks.observeBusyCount().first { it == 0 }
            val wm = WorkManager.getInstance(context)
            for (name in listOf(LibraryRefreshWorker.NOW_NAME, LibraryRefreshWorker.OBSERVED_NAME, LibraryRefreshWorker.MUTATION_NAME)) {
                wm.getWorkInfosForUniqueWorkFlow(name).first { infos -> infos.all { it.state.isFinished } }
            }
        },
        allowed = {
            settings.librarySettings.first().backgroundRefreshEnabled &&
                settings.storageAccessState.first().mode == StorageAccessMode.DIRECT &&
                Environment.isExternalStorageManager()
        },
        refresh = {
            val directories = synchronized(watchLock) { changedDirectories.toList().also { changedDirectories.clear() } }
            try { LibraryRefreshWorker.observeNow(context, directories) }
            catch (failure: Exception) { synchronized(watchLock) { changedDirectories += directories }; throw failure }
            rebuildCheckpointWatches(generation)
        },
        onFailure = {
            mutableStatus.update { it.copy(message = "Live refresh unavailable; scheduled refresh remains enabled.") }
        },
    )

    fun refreshPermissions() { permissionState.value = Environment.isExternalStorageManager() }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(serial) {
            combine(settings.librarySettings.map { it.backgroundRefreshEnabled }.distinctUntilChanged(), settings.storageAccessState, settings.inboxRoots, permissionState) {
                    backgroundEnabled, access, roots, _ -> Triple(backgroundEnabled, access, roots)
            }.collectLatest { (backgroundEnabled, access, roots) ->
                generation++
                refreshQueue.cancelPending()
                stopWatches()
                synchronized(watchLock) { changedDirectories.clear() }
                LibraryRefreshWorker.sync(context, backgroundEnabled)
                if (!backgroundEnabled) {
                    WorkManager.getInstance(context).cancelUniqueWork(LibraryRefreshWorker.OBSERVED_NAME)
                    mutableStatus.update { it.copy(watchedFolders = emptyList(), message = "Inbox monitoring is off.") }
                    return@collectLatest
                }
                val direct = access.mode == StorageAccessMode.DIRECT && Environment.isExternalStorageManager()
                if (!direct) {
                    mutableStatus.update { it.copy(watchedFolders = emptyList(), message =
                        if (access.mode == StorageAccessMode.SAF) "Selected folders use scheduled refresh; live events are unavailable."
                        else "Choose storage access to enable inbox monitoring.") }
                    return@collectLatest
                }
                val coverage = watchPaths(roots.map { it.path })
                val paths = coverage.paths
                synchronized(watchLock) {
                    val expected = generation
                    paths.forEach { watchedPath ->
                        val observer = object : FileObserver(File(watchedPath), EVENT_MASK) {
                            override fun onEvent(event: Int, path: String?) {
                                if (event and EVENT_MASK != 0 && generation == expected) noticeChange(watchedPath, path)
                            }
                        }
                        observer.startWatching()
                        watches += observer
                    }
                }
                mutableStatus.update { it.copy(watchedFolders = paths, message =
                    if (paths.isEmpty()) "No accessible inbox folders. Scheduled refresh remains enabled."
                    else "Watching ${paths.size} folders while the app process is running. " +
                        if (coverage.limited) "Watch coverage is limited; scheduled refresh covers the rest."
                        else "Scheduled refresh covers time away.") }
                if (paths.isNotEmpty()) {
                    // Also catch changes made while this process was absent or permissions were revoked.
                    synchronized(watchLock) { changedDirectories += paths }
                    refreshQueue.notice()
                }
            }
        }
    }

    private fun noticeChange(directory: String, relativePath: String?) {
        val changed = relativePath?.takeIf { path -> path.isNotBlank() && !path.startsWith('/') && path.none(Char::isISOControl) && path.split('/').none { it == "." || it == ".." } }
            ?.let { "$directory/$it" } ?: directory
        onEvidenceChange(setOf(changed))
        synchronized(watchLock) { changedDirectories += directory }
        mutableStatus.update { it.copy(lastChangeAt = System.currentTimeMillis()) }
        refreshQueue.notice()
    }

    private suspend fun rebuildCheckpointWatches(expectedGeneration: Long) {
        val roots = settings.inboxRoots.first()
        if (generation != expectedGeneration) return
        val coverage = watchPaths(roots.map { it.path })
        val paths = coverage.paths
        if (generation != expectedGeneration) return
        if (paths == status.value.watchedFolders) {
            mutableStatus.update { it.copy(message = "Watching ${paths.size} folders while the app process is running. " +
                if (coverage.limited) "Watch coverage is limited; scheduled refresh covers the rest." else "Scheduled refresh covers time away.") }
            return
        }
        synchronized(watchLock) {
            if (generation != expectedGeneration) return
            stopWatches()
            paths.forEach { watchedPath ->
                val observer = object : FileObserver(File(watchedPath), EVENT_MASK) {
                    override fun onEvent(event: Int, path: String?) {
                        if (event and EVENT_MASK != 0 && generation == expectedGeneration) noticeChange(watchedPath, path)
                    }
                }
                observer.startWatching()
                watches += observer
            }
        }
        mutableStatus.update { it.copy(watchedFolders = paths, message = "Watching ${paths.size} folders while the app process is running. " +
            if (coverage.limited) "Watch coverage is limited; scheduled refresh covers the rest." else "Scheduled refresh covers time away.") }
    }

    private fun stopWatches() = synchronized(watchLock) {
        watches.forEach { it.stopWatching() }
        watches.clear()
    }

    private fun watchPaths(roots: List<String>): InboxWatchCoverage {
        val storage = Environment.getExternalStorageDirectory().canonicalPath
        return InboxWatchPlanner.plan(storage, roots, excluded = { path ->
            com.pocketsteward.app.scan.ScanReadPolicy.excludedPrivateDirectory(storage, com.pocketsteward.app.storage.FileRef.Direct(path))
        })
    }

    companion object {
        private const val EVENT_MASK = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.MODIFY or FileObserver.CLOSE_WRITE or
            FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or FileObserver.ATTRIB
    }
}
