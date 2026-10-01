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
) {
    private val started = AtomicBoolean(false)
    private val permissionRevision = MutableStateFlow(0L)
    private val serial = Dispatchers.IO.limitedParallelism(1)
    private var generation = 0L
    private val mutableStatus = MutableStateFlow(InboxObservationStatus())
    val status: StateFlow<InboxObservationStatus> = mutableStatus.asStateFlow()
    private val watchLock = Any()
    private val watches = mutableListOf<FileObserver>()
    private val refreshQueue = InboxRefreshQueue(
        scope = CoroutineScope(scope.coroutineContext + serial),
        now = SystemClock::elapsedRealtime,
        awaitIdle = {
            tasks.observeBusyCount().first { it == 0 }
            val wm = WorkManager.getInstance(context)
            for (name in listOf(LibraryRefreshWorker.NOW_NAME, LibraryRefreshWorker.OBSERVED_NAME)) {
                wm.getWorkInfosForUniqueWorkFlow(name).first { infos -> infos.all { it.state.isFinished } }
            }
        },
        allowed = {
            settings.librarySettings.first().backgroundRefreshEnabled &&
                settings.storageAccessState.first().mode == StorageAccessMode.DIRECT &&
                Environment.isExternalStorageManager()
        },
        refresh = {
            LibraryRefreshWorker.observeNow(context)
            rebuildCheckpointWatches(generation)
        },
        onFailure = {
            mutableStatus.update { it.copy(message = "Live refresh unavailable; scheduled refresh remains enabled.") }
        },
    )

    fun refreshPermissions() { permissionRevision.update { it + 1 } }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(serial) {
            combine(settings.librarySettings, settings.storageAccessState, settings.inboxRoots, permissionRevision) {
                    library, access, roots, _ -> Triple(library, access, roots)
            }.collectLatest { (library, access, roots) ->
                generation++
                refreshQueue.cancelPending()
                stopWatches()
                LibraryRefreshWorker.sync(context, library.backgroundRefreshEnabled)
                if (!library.backgroundRefreshEnabled) {
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
                val paths = watchPaths(roots.map { it.path })
                synchronized(watchLock) {
                    paths.forEach { path ->
                        val observer = object : FileObserver(File(path), EVENT_MASK) {
                            override fun onEvent(event: Int, path: String?) {
                                if (event and EVENT_MASK != 0) noticeChange()
                            }
                        }
                        observer.startWatching()
                        watches += observer
                    }
                }
                mutableStatus.update { it.copy(watchedFolders = paths, message =
                    if (paths.isEmpty()) "No accessible inbox folders. Scheduled refresh remains enabled."
                    else "Watching ${paths.size} folders while the app process is running. Scheduled refresh covers time away.") }
                if (paths.isNotEmpty()) {
                    // Also catch changes made while this process was absent or permissions were revoked.
                    refreshQueue.notice()
                }
            }
        }
    }

    private fun noticeChange() {
        mutableStatus.update { it.copy(lastChangeAt = System.currentTimeMillis()) }
        refreshQueue.notice()
    }

    private suspend fun rebuildCheckpointWatches(expectedGeneration: Long) {
        val roots = settings.inboxRoots.first()
        if (generation != expectedGeneration) return
        val paths = watchPaths(roots.map { it.path })
        if (paths == status.value.watchedFolders) return
        synchronized(watchLock) {
            stopWatches()
            paths.forEach { path ->
                val observer = object : FileObserver(File(path), EVENT_MASK) {
                    override fun onEvent(event: Int, path: String?) {
                        if (event and EVENT_MASK != 0) noticeChange()
                    }
                }
                observer.startWatching()
                watches += observer
            }
        }
        mutableStatus.update { it.copy(watchedFolders = paths) }
    }

    private fun stopWatches() = synchronized(watchLock) {
        watches.forEach { it.stopWatching() }
        watches.clear()
    }

    private fun watchPaths(roots: List<String>): List<String> {
        val storage = Environment.getExternalStorageDirectory().canonicalPath
        return roots.distinct().take(InboxObservationPolicy.MAX_ROOTS).flatMap { raw ->
            try {
                val root = File(raw).canonicalFile
                if (!InboxObservationPolicy.isWithinStorage(storage, root.path) || !root.isDirectory || !root.canRead()) {
                    emptyList()
                } else {
                    val paths = mutableListOf(root.path)
                    Files.newDirectoryStream(root.toPath()).use { children ->
                        children.forEach { child ->
                            if (child.fileName.toString().equals("Uncertain", ignoreCase = true) &&
                                !Files.isSymbolicLink(child) && Files.isDirectory(child) && Files.isReadable(child)) {
                                paths += child.toFile().canonicalPath
                            }
                        }
                    }
                    paths
                }
            } catch (_: Exception) { emptyList() }
        }.distinct().take(InboxObservationPolicy.MAX_ROOTS * 2)
    }

    companion object {
        private const val EVENT_MASK = FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
            FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF or FileObserver.ATTRIB
    }
}
