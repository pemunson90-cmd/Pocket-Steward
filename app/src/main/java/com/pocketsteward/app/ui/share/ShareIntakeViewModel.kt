package com.pocketsteward.app.ui.share

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.executor.InMemoryFileIndex
import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.SourcePrecondition
import com.pocketsteward.app.plan.SourcePreconditions
import com.pocketsteward.app.share.ShareCopyPlanner
import com.pocketsteward.app.storage.FileMetadata
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SharedPreview(val root: FileRef.Saf, val project: String, val files: List<FileRecord>, val operations: List<PlannedOperation>, val reviewedSources: Map<String, SourcePrecondition>)
data class ShareIntakeState(val files: List<FileRecord> = emptyList(), val loading: Boolean = false, val error: String? = null, val preview: SharedPreview? = null, val taskId: Long? = null)

class ShareIntakeViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as PocketStewardApplication).container
    private val gateway = container.gatewayFor(StorageAccessMode.SAF)
    private val mutableState = MutableStateFlow(ShareIntakeState())
    val state = mutableState.asStateFlow()
    val tasks = container.database.taskRunDao().observeAll()
    private var initialized = false

    fun load(uris: List<String>) {
        if (initialized) return
        initialized = true
        work {
            require(uris.isNotEmpty() && uris.size <= 1000) { "Share between 1 and 1,000 files at a time." }
            val files = uris.distinct().map { uri ->
                require(uri.startsWith("content://")) { "The source app must share a readable content URI." }
                gateway.stat(FileRef.Saf(uri)).also {
                    require(!it.isDirectory) { "Share files rather than folders." }
                    require(it.sizeBytes >= 0) { "The source app did not report a verifiable file size. Save it locally and share again." }
                    if (it.sizeBytes == 0L) require(gateway.openRead(FileRef.Saf(uri)).use { input -> input.read() == -1 }) { "The source app reported an empty file but supplied content. Save it locally and share again." }
                }.record(uri)
            }
            mutableState.value = ShareIntakeState(files = files)
        }
    }

    fun prepare(rootUri: String, project: String, selected: Set<String>) = work {
        val files = mutableState.value.files.filter { it.stableRef in selected }
        require(files.isNotEmpty()) { "Select files to import." }
        val root = FileRef.Saf(rootUri)
        val index = snapshot(root, project, files)
        val artifacts = files.map { FilingArtifact(it.stableRef, it.displayName, it.extension, it.sizeBytes, modifiedAt = it.modifiedAt, parentRef = null) }
        val template = container.settingsRepository.hierarchyTemplate.first()
        val operations = ShareCopyPlanner.build(artifacts, root, project, template.roleFolders, index)
        val validated = PlanValidator.validate(operations, index)
        require(validated.rejected.isEmpty() && validated.accepted.size == operations.size) { "The destination changed or contains conflicting names. Choose another destination and rebuild." }
        val reviewed = files.associate { file ->
            val current = SourcePreconditions.capture(gateway, FileRef.Saf(file.stableRef))
            require(SourcePreconditions.matches(requireNotNull(SourcePreconditions.from(file)), current)) { "A shared source changed while preparing the review. Share it again." }
            file.stableRef to current
        }
        mutableState.value = mutableState.value.copy(preview = SharedPreview(root, project, files, operations, reviewed), error = null)
    }

    fun edit() { if (!mutableState.value.loading && mutableState.value.taskId == null) mutableState.value = mutableState.value.copy(preview = null, error = null) }

    fun approve() = work {
        val preview = requireNotNull(mutableState.value.preview)
        val index = snapshot(preview.root, preview.project, preview.files)
        val id = container.planExecutor(StorageAccessMode.SAF).enqueueApproved(
            AgentPlan("Import ${preview.files.size} shared files", preview.operations, preview.reviewedSources), preview.root.rawValue(), StorageAccessMode.SAF, index,
        )
        mutableState.value = mutableState.value.copy(taskId = id)
        try {
            container.startForegroundTask(id)
        } catch (failure: Exception) {
            container.mutationRecovery.pauseInterruptedTask(id, System.currentTimeMillis(), "Import queued safely; foreground task did not start. Resume from Tasks.")
            throw failure
        }
    }

    private fun work(block: suspend () -> Unit) {
        if (mutableState.value.loading || mutableState.value.taskId != null) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { mutableState.value = mutableState.value.copy(error = failure.message ?: "Could not prepare the import.") }
            finally { mutableState.value = mutableState.value.copy(loading = false) }
        }
    }

    private suspend fun snapshot(root: FileRef.Saf, project: String, files: List<FileRecord>): InMemoryFileIndex {
        val records = mutableListOf(gateway.stat(root).also { require(it.isDirectory) }.record())
        val queue = ArrayDeque<Pair<FileRef, Int>>()
        queue.add(root to 0)
        val visited = hashSetOf<String>()
        val categories = setOf("images", "music", "movies", "archives", "apps", "documents")
        while (queue.isNotEmpty()) {
            val (directory, depth) = queue.removeFirst()
            require(visited.add(directory.rawValue())) { "The provider reports repeated folders." }
            for (entry in gateway.listChildren(directory)) {
                require(records.size < 20_000) { "Destination inspection exceeded 20,000 entries. Choose a narrower destination." }
                records += gateway.stat(entry.ref).record().copy(parentRef = directory.rawValue())
                val relevant = depth > 0 || (if (project.isBlank()) entry.displayName.lowercase() in categories else entry.displayName.equals(project.trim(), true))
                if (entry.isDirectory && depth < 6 && relevant) queue.add(entry.ref to depth + 1)
            }
        }
        records += files.map { gateway.stat(FileRef.Saf(it.stableRef)).record(it.stableRef) }
        return InMemoryFileIndex(records)
    }
    private fun FileMetadata.record(sourceRef: String = ref.rawValue()) = FileRecord(stableRef = sourceRef, displayName = displayName, extension = extension, mimeType = mimeType, absolutePathOrUri = sourceRef, parentRef = null, sizeBytes = sizeBytes, createdAt = createdAtEpochMs, modifiedAt = modifiedAtEpochMs, lastScannedAt = System.currentTimeMillis(), isDirectory = isDirectory, isHidden = isHidden)
}
