package com.pocketsteward.app.ui.browser

import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.browser.BrowserItem
import com.pocketsteward.app.browser.BrowserLogic
import com.pocketsteward.app.browser.BrowserSort
import com.pocketsteward.app.browser.ClipMode
import com.pocketsteward.app.browser.Clipboard
import com.pocketsteward.app.browser.WalkedFolder
import com.pocketsteward.app.content.ask.AskRetrieval
import com.pocketsteward.app.content.index.ContentSearchDatabase
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.data.db.TaskRunStatus
import com.pocketsteward.app.data.settings.StorageAccessState
import com.pocketsteward.app.di.AppContainer
import com.pocketsteward.app.executor.CompositeFileIndex
import com.pocketsteward.app.executor.SingleFolderIndex
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.knownParentOrNull
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import com.pocketsteward.app.ui.components.FileKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The fixed groups on the Files home screen. */
enum class BrowserCategory(val label: String, val kinds: List<FileKind>) {
    IMAGES("Images", listOf(FileKind.IMAGE)),
    VIDEOS("Videos", listOf(FileKind.VIDEO)),
    AUDIO("Audio", listOf(FileKind.AUDIO)),
    DOCUMENTS(
        "Documents",
        listOf(FileKind.PDF, FileKind.DOCUMENT, FileKind.SPREADSHEET, FileKind.SLIDES, FileKind.TEXT, FileKind.EBOOK),
    ),
    APPS("Apps", listOf(FileKind.APP)),
    ARCHIVES("Archives", listOf(FileKind.ARCHIVE)),
    RECENT("Recent", emptyList()),
}

sealed interface BrowserLocation {
    data object Home : BrowserLocation
    data class Folder(val ref: FileRef) : BrowserLocation
    data class Category(val category: BrowserCategory) : BrowserLocation
    data class Search(val query: String) : BrowserLocation
}

data class Crumb(val ref: FileRef, val name: String)

data class CategoryStat(val category: BrowserCategory, val files: Int, val bytes: Long)

data class HomeData(
    val totalBytes: Long?,
    val freeBytes: Long?,
    val stats: List<CategoryStat>,
    val libraryFiles: Int,
    val libraryReady: Boolean,
    /** Standard folders that exist on this phone, as (folder, label). */
    val shortcuts: List<Pair<FileRef, String>> = emptyList(),
)

/** A change waiting on the confirmation sheet. Nothing has touched storage yet. */
data class PendingPlan(
    val goal: String,
    val accepted: List<PlannedOperation>,
    val rejected: List<RejectedOperation>,
    val notes: List<String>,
    internal val index: FileIndex,
    /** Clear the clipboard once this runs (a paste). */
    internal val consumesClipboard: Boolean,
)

data class TaskOutcome(val taskRunId: Long, val message: String, val canUndo: Boolean)

/** A search hit inside a document, from the content index. */
data class ContentHit(val item: BrowserItem, val snippet: String, val page: Int?)

data class BrowserUiState(
    val ready: Boolean = false,
    val noAccess: Boolean = false,
    val location: BrowserLocation = BrowserLocation.Home,
    val crumbs: List<Crumb> = emptyList(),
    val items: List<BrowserItem> = emptyList(),
    val contentHits: List<ContentHit> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
    val sort: BrowserSort = BrowserSort(),
    val showHidden: Boolean = false,
    val grid: Boolean = false,
    val selection: Set<String> = emptySet(),
    val clipboard: Clipboard? = null,
    val pending: PendingPlan? = null,
    val working: String? = null,
    val outcome: TaskOutcome? = null,
    val home: HomeData? = null,
    val rootRef: FileRef? = null,
    val mode: StorageAccessMode? = null,
) {
    val selectedItems: List<BrowserItem> get() = items.filter { it.key in selection }
    val inSelection: Boolean get() = selection.isNotEmpty()
    val currentFolder: FileRef? get() = (location as? BrowserLocation.Folder)?.ref
}

/**
 * The Files tab. Folders are listed live from storage, so what you see is
 * what is on the phone right now. Categories, recents and name search read
 * the background library, which is instant but can trail by a refresh.
 *
 * Every change goes the long way on purpose: build operations, validate
 * them against live listings of the folders involved, show them on a
 * confirmation sheet, then hand the approved plan to the same executor as
 * everything else, which journals it for Undo. Trash is a move into
 * Pocket Steward's recoverable Trash. Nothing here deletes.
 */
class BrowserViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val _state = MutableStateFlow(BrowserUiState())
    val state: StateFlow<BrowserUiState> = _state.asStateFlow()

    private val history = ArrayDeque<Pair<BrowserLocation, List<Crumb>>>()
    private var raw: List<BrowserItem> = emptyList()

    /** Parent of every item shown so far, by key; granted-folder URIs can't be walked upward. */
    private val knownParents = HashMap<String, String>()
    private var loadJob: Job? = null
    private var access: StorageAccessState = StorageAccessState()

    init {
        viewModelScope.launch { start() }
    }

    private suspend fun start() {
        access = container.settingsRepository.storageAccessState.first()
        val mode = access.mode
        if (mode == null) {
            _state.update { it.copy(ready = true, noAccess = true) }
            return
        }
        val root = withContext(Dispatchers.IO) { runCatching { container.library.root(access) }.getOrNull() }
        _state.update { it.copy(ready = true, rootRef = root, mode = mode) }
        loadHome()
    }

    private val gateway: StorageGateway get() = container.gatewayFor(access.mode ?: StorageAccessMode.DIRECT)

    private val libraryKey: String? get() = _state.value.rootRef?.rawValue()

    // ---- Navigation --------------------------------------------------------

    fun goHome() {
        history.clear()
        navigate(BrowserLocation.Home, emptyList(), push = false)
    }

    fun openFolder(ref: FileRef, name: String) {
        val s = _state.value
        val crumbs = when {
            s.location is BrowserLocation.Folder -> s.crumbs + Crumb(ref, name)
            else -> crumbsFor(ref, name)
        }
        navigate(BrowserLocation.Folder(ref), crumbs, push = true)
    }

    fun openRoot() {
        val root = _state.value.rootRef ?: return
        navigate(BrowserLocation.Folder(root), listOf(Crumb(root, rootName())), push = true)
    }

    /** Opens the folder an item from a category or search sits in. */
    fun showInFolder(item: BrowserItem) {
        val parent = item.parentRef ?: item.ref.knownParentOrNull() ?: return
        navigate(BrowserLocation.Folder(parent), crumbsFor(parent, parent.displayName()), push = true)
    }

    fun openCrumb(index: Int) {
        val crumbs = _state.value.crumbs
        if (index !in crumbs.indices || index == crumbs.lastIndex) return
        navigate(BrowserLocation.Folder(crumbs[index].ref), crumbs.take(index + 1), push = true)
    }

    fun openCategory(category: BrowserCategory) = navigate(BrowserLocation.Category(category), emptyList(), push = true)

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        navigate(BrowserLocation.Search(q), emptyList(), push = _state.value.location !is BrowserLocation.Search)
    }

    /** True when back was handled here; false means leave the tab. */
    fun back(): Boolean {
        val s = _state.value
        if (s.inSelection) {
            clearSelection()
            return true
        }
        if (s.location is BrowserLocation.Folder && s.crumbs.size > 1) {
            val parent = s.crumbs.dropLast(1)
            navigate(BrowserLocation.Folder(parent.last().ref), parent, push = false)
            return true
        }
        val previous = history.removeLastOrNull() ?: return if (s.location != BrowserLocation.Home) {
            navigate(BrowserLocation.Home, emptyList(), push = false)
            true
        } else {
            false
        }
        navigate(previous.first, previous.second, push = false)
        return true
    }

    private fun navigate(location: BrowserLocation, crumbs: List<Crumb>, push: Boolean) {
        val s = _state.value
        if (push && s.location != location) history.addLast(s.location to s.crumbs)
        if (history.size > 50) history.removeFirst()
        _state.update {
            it.copy(location = location, crumbs = crumbs, selection = emptySet(), items = emptyList(), contentHits = emptyList(), message = null)
        }
        reload()
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            when (val loc = _state.value.location) {
                BrowserLocation.Home -> loadHome()
                is BrowserLocation.Folder -> loadFolder(loc.ref)
                is BrowserLocation.Category -> loadCategory(loc.category)
                is BrowserLocation.Search -> loadSearch(loc.query)
            }
        }
    }

    private fun rootName(): String =
        if (access.mode == StorageAccessMode.SAF) "Granted folder" else "Internal storage"

    /** Breadcrumbs from the storage root, for plain paths; granted-folder URIs can only show root and target. */
    private fun crumbsFor(ref: FileRef, name: String): List<Crumb> {
        val root = _state.value.rootRef ?: return listOf(Crumb(ref, name))
        val rootPath = (root as? FileRef.Direct)?.absolutePath?.trimEnd('/')
        val path = (ref as? FileRef.Direct)?.absolutePath?.trimEnd('/')
        if (rootPath != null && path != null && (path == rootPath || path.startsWith("$rootPath/"))) {
            val crumbs = mutableListOf(Crumb(root, rootName()))
            var acc = rootPath
            path.removePrefix(rootPath).split('/').filter { it.isNotEmpty() }.forEach { seg ->
                acc = "$acc/$seg"
                crumbs += Crumb(FileRef.Direct(acc), seg)
            }
            return crumbs
        }
        return if (ref.rawValue().trimEnd('/') == root.rawValue().trimEnd('/')) {
            listOf(Crumb(root, rootName()))
        } else {
            listOf(Crumb(root, rootName()), Crumb(ref, name))
        }
    }

    // ---- Loading -----------------------------------------------------------

    private suspend fun loadHome() {
        val root = _state.value.rootRef ?: return
        _state.update { it.copy(loading = true) }
        val home = withContext(Dispatchers.IO) {
            val key = root.rawValue()
            val dao = container.database.fileRecordDao()
            val byExt = runCatching { dao.libraryExtensionStats(key) }.getOrDefault(emptyList())
            val stats = BrowserCategory.entries.filter { it != BrowserCategory.RECENT }.map { cat ->
                val exts = FileKind.extensionsOf(*cat.kinds.toTypedArray()).toSet()
                val rows = byExt.filter { it.extension in exts }
                CategoryStat(cat, rows.sumOf { it.fileCount }, rows.sumOf { it.totalBytes })
            }
            val space = (root as? FileRef.Direct)?.let {
                runCatching { StatFs(it.absolutePath).let { fs -> fs.totalBytes to fs.availableBytes } }.getOrNull()
            }
            val status = runCatching { container.library.status(access) }.getOrNull()
            val shortcuts = (root as? FileRef.Direct)?.absolutePath?.let { base ->
                listOf("Download" to "Downloads", "DCIM" to "Camera", "Pictures" to "Pictures", "Documents" to "Documents", "Movies" to "Movies", "Music" to "Music")
                    .filter { (dir, _) -> File("$base/$dir").isDirectory }
                    .map { (dir, label) -> FileRef.Direct("$base/$dir") to label }
            }.orEmpty()
            HomeData(
                shortcuts = shortcuts,
                totalBytes = space?.first,
                freeBytes = space?.second,
                stats = stats,
                libraryFiles = status?.fileCount ?: 0,
                libraryReady = status?.lastCompletedAt != null,
            )
        }
        _state.update { it.copy(home = home, loading = false) }
    }

    private suspend fun loadFolder(dir: FileRef) {
        _state.update { it.copy(loading = true, message = null) }
        try {
            val items = withContext(Dispatchers.IO) { listFolder(dir) }
            raw = items
            publishItems()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (t: Throwable) {
            raw = emptyList()
            _state.update { it.copy(items = emptyList(), loading = false, message = "Couldn't open this folder: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    private suspend fun listFolder(dir: FileRef): List<BrowserItem> {
        val g = gateway
        return g.listChildren(dir).map { entry ->
            val meta = runCatching { g.stat(entry.ref) }.getOrNull()
            val count = if (entry.isDirectory && entry.ref is FileRef.Direct) {
                runCatching { File(entry.ref.absolutePath).list()?.size }.getOrNull()
            } else {
                null
            }
            BrowserItem(
                ref = entry.ref,
                name = entry.displayName,
                isDirectory = entry.isDirectory,
                sizeBytes = meta?.sizeBytes ?: 0L,
                modifiedAt = meta?.modifiedAtEpochMs,
                mimeType = meta?.mimeType,
                isHidden = meta?.isHidden ?: entry.displayName.startsWith("."),
                parentRef = dir,
                childCount = count,
            )
        }
    }

    private suspend fun loadCategory(category: BrowserCategory) {
        val key = libraryKey ?: return
        _state.update { it.copy(loading = true) }
        val records = withContext(Dispatchers.IO) {
            val dao = container.database.fileRecordDao()
            if (category == BrowserCategory.RECENT) {
                dao.libraryRecentFiles(key, 300)
            } else {
                dao.libraryFilesByExtension(key, FileKind.extensionsOf(*category.kinds.toTypedArray()), 2_000)
            }
        }
        raw = records.map { it.toItem() }
        publishItems(keepLibraryOrder = category == BrowserCategory.RECENT)
        if (records.isEmpty()) {
            _state.update { it.copy(message = libraryEmptyMessage()) }
        }
    }

    private suspend fun loadSearch(query: String) {
        val key = libraryKey ?: return
        _state.update { it.copy(loading = true) }
        val names = withContext(Dispatchers.IO) {
            val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            container.database.fileRecordDao().librarySearchByName(key, "%$escaped%", 300)
        }
        raw = names.map { it.toItem() }
        publishItems(keepLibraryOrder = true)
        // Inside documents, from the content index, when it has anything.
        val hits = withContext(Dispatchers.IO) {
            runCatching {
                val keywords = AskRetrieval.keywords(query).ifEmpty { listOf(query.lowercase()) }
                val match = AskRetrieval.ftsQuery(keywords) ?: return@runCatching emptyList()
                val dao = ContentSearchDatabase.getInstance(container.appContextForUi).contentIndexDao()
                val rows = dao.askCandidateRows(match, 300)
                AskRetrieval.rank(rows, keywords, maxPassages = 30, perFile = 1, excerptChars = 180).map { p ->
                    val ref = parseFileRef(p.stableRef)
                    ContentHit(
                        item = BrowserItem(ref, p.displayName, false, 0, null, null, false, p.parentRef?.let(::parseFileRef)),
                        snippet = p.excerpt,
                        page = p.pageNumber,
                    )
                }
            }.getOrDefault(emptyList())
        }
        _state.update {
            it.copy(
                contentHits = hits,
                message = if (names.isEmpty() && hits.isEmpty()) "Nothing named or containing “$query”. ${libraryEmptyMessage()}" else null,
            )
        }
    }

    private suspend fun libraryEmptyMessage(): String {
        val ready = withContext(Dispatchers.IO) { runCatching { container.library.status(access).lastCompletedAt != null }.getOrDefault(false) }
        return if (ready) "" else "The file library is still being built; results fill in as it finishes."
    }

    private fun FileRecord.toItem() = BrowserItem(
        ref = parseFileRef(stableRef),
        name = displayName,
        isDirectory = isDirectory,
        sizeBytes = sizeBytes,
        modifiedAt = modifiedAt,
        mimeType = mimeType,
        isHidden = isHidden,
        parentRef = parentRef?.let(::parseFileRef),
    )

    private fun publishItems(keepLibraryOrder: Boolean = false) {
        raw.forEach { item -> item.parentRef?.let { knownParents[item.key] = it.rawValue() } }
        val s = _state.value
        val shown = if (keepLibraryOrder && s.sort == BrowserSort()) {
            raw.filter { s.showHidden || (!it.isHidden && !it.name.startsWith(".")) }
        } else {
            BrowserLogic.sort(raw, s.sort, s.showHidden)
        }
        _state.update { it.copy(items = shown, loading = false, selection = it.selection.intersect(shown.map { i -> i.key }.toSet())) }
    }

    fun setSort(sort: BrowserSort) {
        _state.update { it.copy(sort = sort) }
        publishItems()
    }

    fun toggleHidden() {
        _state.update { it.copy(showHidden = !it.showHidden) }
        publishItems()
    }

    fun toggleGrid() = _state.update { it.copy(grid = !it.grid) }

    // ---- Selection and clipboard ------------------------------------------

    fun toggleSelected(item: BrowserItem) = _state.update {
        it.copy(selection = if (item.key in it.selection) it.selection - item.key else it.selection + item.key)
    }

    fun selectAll() = _state.update { it.copy(selection = it.items.map { i -> i.key }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }

    fun cut() = clip(ClipMode.MOVE)

    fun copy() = clip(ClipMode.COPY)

    private fun clip(mode: ClipMode) {
        val items = allowed(_state.value.selectedItems, moving = mode == ClipMode.MOVE) ?: return
        if (items.isEmpty()) return
        _state.update { it.copy(clipboard = Clipboard(mode, items), selection = emptySet()) }
    }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    // ---- Proposing changes -------------------------------------------------

    fun paste() {
        val s = _state.value
        val clip = s.clipboard ?: return
        val dest = s.currentFolder ?: return
        if (BrowserLogic.isInsideTrash(dest.rawValue(), libraryKey)) {
            _state.update { it.copy(message = "That's Pocket Steward's Trash. Use Trash on the files instead, so they can be restored.") }
            return
        }
        propose(consumesClipboard = true) {
            val destListing = gateway.listChildren(dest)
            val walked = mutableMapOf<String, WalkedFolder>()
            val walkedDirs = mutableListOf<Pair<FileRef, List<com.pocketsteward.app.storage.FileEntry>>>()
            if (clip.mode == ClipMode.COPY) {
                clip.items.filter { it.isDirectory }.forEach { folder ->
                    walk(folder.ref, folder.name, walkedDirs, budget = intArrayOf(MAX_COPY_FILES))?.let { walked[folder.key] = it }
                }
            }
            val result = BrowserLogic.paste(clip, dest, destListing.map { it.displayName }.toSet(), walked)
            val sourceDirs = clip.items.mapNotNull { it.parentRef }.distinctBy { it.rawValue() }
            val listings = (listOf(dest to destListing) +
                sourceDirs.filter { it.rawValue() != dest.rawValue() }.map { it to gateway.listChildren(it) } +
                walkedDirs)
            val verb = if (clip.mode == ClipMode.MOVE) "Move" else "Copy"
            Proposal(
                goal = "$verb ${plural(clip.items.size, "item")} to ${dest.displayName()}",
                operations = result.operations,
                index = indexOf(listings),
                notes = result.skipped + if (walked.values.any { it.fileCount >= MAX_COPY_FILES }) {
                    listOf("Folder copies stop at ${"%,d".format(MAX_COPY_FILES)} files; the rest weren't included.")
                } else {
                    emptyList()
                },
            )
        }
    }

    fun rename(item: BrowserItem, newName: String) {
        if (allowed(listOf(item), moving = true) == null) return
        BrowserLogic.validName(newName)?.let { problem ->
            _state.update { it.copy(message = problem) }
            return
        }
        if (newName.trim() == item.name) return
        val parent = item.parentRef ?: return
        propose(consumesClipboard = false) {
            Proposal("Rename ${item.name}", listOf(BrowserLogic.rename(item, newName)), indexOf(listOf(parent to gateway.listChildren(parent))))
        }
    }

    fun newFolder(name: String) {
        BrowserLogic.validName(name)?.let { problem ->
            _state.update { it.copy(message = problem) }
            return
        }
        val dir = _state.value.currentFolder ?: return
        propose(consumesClipboard = false) {
            Proposal("New folder ${name.trim()}", listOf(BrowserLogic.newFolder(dir, name)), indexOf(listOf(dir to gateway.listChildren(dir))))
        }
    }

    fun trashSelected() {
        val items = allowed(_state.value.selectedItems, moving = true) ?: return
        if (items.isEmpty()) return
        propose(consumesClipboard = false) {
            val parents = items.mapNotNull { it.parentRef }.distinctBy { it.rawValue() }
            Proposal(
                goal = "Move ${plural(items.size, "item")} to Trash",
                operations = BrowserLogic.trash(items),
                index = indexOf(parents.map { it to gateway.listChildren(it) }),
                notes = listOf("Trash is recoverable: files go to Pocket Steward's Trash and can be restored or undone."),
            )
        }
    }

    fun protectCurrentFolder() {
        val dir = _state.value.currentFolder ?: return
        propose(consumesClipboard = false) {
            Proposal(
                goal = "Protect ${dir.displayName()} from sorting",
                operations = listOf(BrowserLogic.protect(dir)),
                index = indexOf(listOf(dir to gateway.listChildren(dir))),
                notes = listOf("Adds a small marker file. Automatic cleanups then leave this folder and everything in it alone."),
            )
        }
    }

    private data class Proposal(
        val goal: String,
        val operations: List<PlannedOperation>,
        val index: FileIndex,
        val notes: List<String> = emptyList(),
    )

    private fun propose(consumesClipboard: Boolean, build: suspend () -> Proposal) {
        viewModelScope.launch {
            _state.update { it.copy(working = "Checking…") }
            try {
                val p = withContext(Dispatchers.IO) { build() }
                val validated = PlanValidator.validate(p.operations, p.index)
                _state.update {
                    it.copy(
                        working = null,
                        pending = if (p.operations.isEmpty() && p.notes.isNotEmpty()) null else PendingPlan(
                            goal = p.goal,
                            accepted = validated.accepted,
                            rejected = validated.rejected,
                            notes = p.notes,
                            index = p.index,
                            consumesClipboard = consumesClipboard,
                        ),
                        message = if (p.operations.isEmpty()) p.notes.joinToString(" ").ifBlank { null } else it.message,
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update { it.copy(working = null, message = t.message ?: t.javaClass.simpleName) }
            }
        }
    }

    fun dismissPending() = _state.update { it.copy(pending = null) }

    /** The approved plan goes to the executor and journal, exactly like a plan from the review screen. */
    fun confirmPending() {
        val pending = _state.value.pending ?: return
        if (pending.accepted.isEmpty()) {
            dismissPending()
            return
        }
        val mode = access.mode ?: return
        viewModelScope.launch {
            _state.update { it.copy(pending = null, working = "Working…", selection = emptySet()) }
            try {
                val scopeRoot = libraryKey ?: pending.accepted.firstNotNullOfOrNull { anchorOf(it)?.rawValue() } ?: ""
                val taskRunId = withContext(Dispatchers.IO) {
                    syncSourceRecords(pending.accepted)
                    container.planExecutor(mode).enqueueApproved(
                        plan = AgentPlan(pending.goal, pending.accepted),
                        scopeRootRef = scopeRoot,
                        storageAccessMode = mode,
                        index = pending.index,
                    )
                }
                if (pending.consumesClipboard) _state.update { it.copy(clipboard = null) }
                container.startForegroundTask(taskRunId)
                val done = withContext(Dispatchers.IO) {
                    container.database.taskRunDao().observeAll().first { runs ->
                        runs.firstOrNull { it.id == taskRunId }?.status?.let { it != TaskRunStatus.RUNNING } == true
                    }.first { it.id == taskRunId }
                }
                _state.update {
                    it.copy(
                        working = null,
                        outcome = TaskOutcome(
                            taskRunId = taskRunId,
                            message = outcomeMessage(pending.goal, done.status, done.summary),
                            canUndo = done.status == TaskRunStatus.COMPLETED || done.status == TaskRunStatus.PARTIAL,
                        ),
                    )
                }
                reload()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update { it.copy(working = null, message = t.message ?: t.javaClass.simpleName) }
                reload()
            }
        }
    }

    fun undo(outcome: TaskOutcome) {
        viewModelScope.launch {
            _state.update { it.copy(outcome = null, working = "Undoing…") }
            try {
                val summary = withContext(Dispatchers.IO) { container.undoExecutor.undo(outcome.taskRunId) { _, _ -> } }
                _state.update {
                    it.copy(
                        working = null,
                        message = if (summary.complete) "Undone." else "Partly undone: ${summary.messages.firstOrNull() ?: "some items had changed since"}.",
                    )
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                _state.update { it.copy(working = null, message = "Couldn't undo: ${t.message ?: t.javaClass.simpleName}") }
            }
            reload()
        }
    }

    fun dismissOutcome() = _state.update { it.copy(outcome = null) }

    fun dismissMessage() = _state.update { it.copy(message = null) }

    // ---- Helpers -----------------------------------------------------------

    /**
     * Returns the items that may be changed, or null (with a message) when
     * the whole request has to stop. Folders can't be moved, renamed or
     * trashed in granted-folder mode: the executor fingerprints what it moves
     * there and can only do that for files.
     */
    private fun allowed(items: List<BrowserItem>, moving: Boolean): List<BrowserItem>? {
        val root = libraryKey
        if (items.any { BrowserLogic.isAppManaged(it, root) }) {
            _state.update {
                it.copy(message = "PocketSteward/Trash is managed by the app. Restore trashed files from Settings, Trash.")
            }
            return null
        }
        if (moving && access.mode == StorageAccessMode.SAF && items.any { it.isDirectory }) {
            _state.update {
                it.copy(message = "In granted-folder mode, folders can be copied but not moved, renamed or trashed. Select files instead.")
            }
            return null
        }
        return items
    }

    /**
     * The Files tab shows live listings, so the person approved what is on
     * disk now. The executor compares each source with its database record
     * and refuses the whole plan when the record is older, which the library
     * (refreshed every few hours) often is. Bringing just these records up to
     * date first makes that check compare against what was actually shown.
     * Granted-folder sources the library hasn't seen yet are added, because
     * the executor needs a record to journal them.
     */
    private suspend fun syncSourceRecords(operations: List<PlannedOperation>) {
        val dao = container.database.fileRecordDao()
        val g = gateway
        val now = System.currentTimeMillis()
        for (op in operations) {
            val source = when (op) {
                is PlannedOperation.Move -> op.source
                is PlannedOperation.Copy -> op.source
                is PlannedOperation.Rename -> op.source
                is PlannedOperation.Trash -> op.source
                else -> null
            } ?: continue
            if (source is FileRef.Child) continue
            val meta = runCatching { g.stat(source) }.getOrNull() ?: continue
            val raw = source.rawValue()
            val existing = dao.getByStableRef(raw)
            val fresh = FileRecord(
                stableRef = raw,
                displayName = meta.displayName,
                extension = meta.extension,
                mimeType = meta.mimeType,
                absolutePathOrUri = raw,
                parentRef = existing?.parentRef ?: knownParents[raw] ?: source.knownParentOrNull()?.rawValue(),
                sizeBytes = meta.sizeBytes,
                createdAt = meta.createdAtEpochMs,
                modifiedAt = meta.modifiedAtEpochMs,
                lastScannedAt = now,
                isDirectory = meta.isDirectory,
                isHidden = meta.isHidden,
            )
            if (existing != null) {
                dao.update(com.pocketsteward.app.data.db.mergeScanRecord(existing, fresh))
            } else if (source is FileRef.Saf) {
                dao.upsertFromScan(fresh)
                libraryKey?.let { dao.insertScopeTag(com.pocketsteward.app.data.db.FileScope(raw, it)) }
            }
        }
    }

    /** Walks [folder] for a copy, recording every listing so the validator can see the sources. */
    private suspend fun walk(
        folder: FileRef,
        name: String,
        listings: MutableList<Pair<FileRef, List<com.pocketsteward.app.storage.FileEntry>>>,
        budget: IntArray,
    ): WalkedFolder? {
        val children = runCatching { gateway.listChildren(folder) }.getOrNull() ?: return null
        listings += folder to children
        val files = mutableListOf<Pair<FileRef, String>>()
        val subs = mutableListOf<WalkedFolder>()
        for (c in children) {
            if (budget[0] <= 0) break
            if (c.isDirectory) {
                walk(c.ref, c.displayName, listings, budget)?.let { subs += it }
            } else {
                files += c.ref to c.displayName
                budget[0]--
            }
        }
        return WalkedFolder(folder, name, files, subs)
    }

    private fun indexOf(listings: List<Pair<FileRef, List<com.pocketsteward.app.storage.FileEntry>>>): FileIndex {
        val parts = listings.distinctBy { it.first.rawValue().trimEnd('/') }.map { (dir, children) -> SingleFolderIndex(dir, children) }
        return if (parts.size == 1) parts.single() else CompositeFileIndex(parts)
    }

    private fun anchorOf(op: PlannedOperation): FileRef? = when (op) {
        is PlannedOperation.Move -> op.source
        is PlannedOperation.Copy -> op.source
        is PlannedOperation.Rename -> op.source
        is PlannedOperation.Trash -> op.source
        is PlannedOperation.CreateDirectory -> op.parent
        is PlannedOperation.WriteTextFile -> op.parent
    }

    private fun outcomeMessage(goal: String, status: TaskRunStatus, summary: String?): String = when (status) {
        TaskRunStatus.COMPLETED -> "Done: $goal."
        TaskRunStatus.PARTIAL -> "Partly done. ${summary.orEmpty()}".trim()
        else -> "Didn't finish (${status.name.lowercase().replace('_', ' ')}). ${summary.orEmpty()} Details are in Tasks.".trim()
    }

    private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

    companion object {
        const val MAX_COPY_FILES = 5_000
    }
}

/** A readable name for a folder reference, without asking storage. */
internal fun FileRef.displayName(): String = when (this) {
    is FileRef.Direct -> absolutePath.trimEnd('/').substringAfterLast('/').ifBlank { "Storage" }
    is FileRef.Child -> name
    is FileRef.Saf -> android.net.Uri.decode(documentUri.substringAfterLast('/')).substringAfterLast(':').substringAfterLast('/').ifBlank { "Folder" }
}
