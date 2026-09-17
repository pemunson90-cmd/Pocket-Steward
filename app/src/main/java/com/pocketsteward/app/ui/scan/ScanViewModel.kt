package com.pocketsteward.app.ui.scan

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.di.AppContainer
import com.pocketsteward.app.executor.ExecutionSummary
import com.pocketsteward.app.executor.InMemoryFileIndex
import com.pocketsteward.app.plan.AgentPlan
import com.pocketsteward.app.plan.PlanValidator
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.RejectedOperation
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.scan.ScanPhase
import com.pocketsteward.app.scan.ScanProgress
import com.pocketsteward.app.scan.classifyByExtension
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.StorageScope
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CategoryStat(val fileCount: Int, val totalBytes: Long)

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(val progress: ScanProgress) : ScanUiState
    data class Summary(
        val scopeLabel: String,
        val scopeRoot: FileRef,
        val mode: StorageAccessMode,
        val totalFiles: Int,
        val totalBytes: Long,
        val byCategory: Map<FileCategory, CategoryStat>,
    ) : ScanUiState
    data class PlanPreview(
        val goal: String,
        val accepted: List<PlannedOperation>,
        val rejected: List<RejectedOperation>,
        val scopeRoot: FileRef,
    ) : ScanUiState
    data class ExecutionDone(val summary: ExecutionSummary) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

class ScanViewModel(
    private val settingsRepository: SettingsRepository,
    private val container: AppContainer,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val uiState: StateFlow<ScanUiState> = _uiState

    fun startScan(target: ScanTarget) {
        viewModelScope.launch {
            _uiState.value = ScanUiState.Scanning(ScanProgress(0, null, ScanPhase.SCANNING))
            try {
                val accessState = settingsRepository.storageAccessState.first()
                val mode = accessState.mode
                if (mode == null) {
                    _uiState.value = ScanUiState.Error("No storage access granted yet.")
                    return@launch
                }

                val gateway = container.gatewayFor(mode)
                val root = resolveRoot(target, mode, accessState.safTreeUri, gateway)
                val scanner = container.fileScanner(mode)

                // DirectStorageGateway's listChildren/stat are suspend
                // functions doing blocking java.io.File work with no
                // dispatcher of their own — without this, that I/O runs on
                // Dispatchers.Main.immediate (viewModelScope's default) and
                // a scan of "Everything" would freeze the UI thread long
                // enough to ANR.
                withContext(Dispatchers.IO) {
                    scanner.scan(root) { progress ->
                        _uiState.value = ScanUiState.Scanning(progress)
                    }
                }

                val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root.rawValue())
                val byCategory = records
                    .groupBy { classifyByExtension(it.extension) }
                    .mapValues { (_, files) ->
                        CategoryStat(fileCount = files.size, totalBytes = files.sumOf { it.sizeBytes })
                    }

                _uiState.value = ScanUiState.Summary(
                    scopeLabel = target.label,
                    scopeRoot = root,
                    mode = mode,
                    totalFiles = records.size,
                    totalBytes = records.sumOf { it.sizeBytes },
                    byCategory = byCategory,
                )
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * Milestone 2's own exercise of the executor: a hard-coded plan (plan
     * Section 2's own example — "put APK installers under Downloads/APKs")
     * built from what's already indexed, not from natural language or a
     * rule engine. Real request-driven planning is Milestone 4/5.
     */
    fun proposeOrganizeApks(summary: ScanUiState.Summary) {
        viewModelScope.launch {
            try {
                val root = summary.scopeRoot
                if (root !is FileRef.Direct) {
                    _uiState.value = ScanUiState.Error("Organizing is only wired up for broad storage access right now.")
                    return@launch
                }
                val records = container.database.fileRecordDao().getFilesUnderScopeRoot(root.rawValue())
                val apkRecords = records.filter { classifyByExtension(it.extension) == FileCategory.APK }
                if (apkRecords.isEmpty()) {
                    _uiState.value = ScanUiState.Error("No APKs found under ${summary.scopeLabel}.")
                    return@launch
                }

                val apksFolder = FileRef.Direct("${root.absolutePath.trimEnd('/')}/APKs")
                val operations = buildList {
                    add(PlannedOperation.CreateDirectory(root, "APKs", "Destination for Android package installers"))
                    for (record in apkRecords) {
                        add(
                            PlannedOperation.Move(
                                source = FileRef.Direct(record.stableRef),
                                destination = FileRef.Direct("${apksFolder.absolutePath}/${record.displayName}"),
                                reason = "APK file",
                            ),
                        )
                    }
                }
                val index = InMemoryFileIndex(container.database.fileRecordDao().getAllUnderScopeRoot(root.rawValue()))
                val validated = PlanValidator.validate(operations, index)

                _uiState.value = ScanUiState.PlanPreview(
                    goal = "Organize APKs under ${summary.scopeLabel}",
                    accepted = validated.accepted,
                    rejected = validated.rejected,
                    scopeRoot = root,
                )
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun approvePlan(preview: ScanUiState.PlanPreview) {
        viewModelScope.launch {
            try {
                val accessState = settingsRepository.storageAccessState.first()
                val mode = accessState.mode ?: run {
                    _uiState.value = ScanUiState.Error("No storage access granted yet.")
                    return@launch
                }
                val executor = container.planExecutor(mode)
                // Only the operations the preview showed as accepted are
                // sent for execution — rejected ones stay untouched, per
                // Decision 5, rather than being re-submitted for the
                // executor's own validation pass to reject again.
                val plan = AgentPlan(preview.goal, preview.accepted)
                val summary = withContext(Dispatchers.IO) {
                    executor.execute(plan, preview.scopeRoot.rawValue())
                }
                _uiState.value = ScanUiState.ExecutionDone(summary)
            } catch (t: Throwable) {
                _uiState.value = ScanUiState.Error(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    fun reset() {
        _uiState.value = ScanUiState.Idle
    }

    private suspend fun resolveRoot(
        target: ScanTarget,
        mode: StorageAccessMode,
        safTreeUri: String?,
        gateway: StorageGateway,
    ): FileRef {
        if (mode == StorageAccessMode.SAF) {
            val uri = requireNotNull(safTreeUri) { "SAF mode with no granted tree URI" }
            // Must go through rootOf(), not a bare FileRef.Saf(uri): it
            // normalizes the raw tree URI (".../tree/X") into document-URI
            // form (".../tree/X/document/X"), which is what makes
            // SafStorageGateway's fromTreeUri-based listing/stat resolve
            // this node instead of misbehaving on an un-normalized ref.
            return gateway.rootOf(StorageScope.Tree(FileRef.Saf(uri), "Granted folder"))
        }
        // Environment.getExternalStoragePublicDirectory is deprecated for
        // scoped-storage apps in general, but this app deliberately runs
        // under MANAGE_EXTERNAL_STORAGE (plan Section 5, Mode A), which is
        // exactly the case that API still serves correctly.
        @Suppress("DEPRECATION")
        return when (target) {
            ScanTarget.Downloads ->
                FileRef.Direct(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
            ScanTarget.Documents ->
                FileRef.Direct(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath)
            ScanTarget.Pictures ->
                FileRef.Direct(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).absolutePath)
            ScanTarget.Everything ->
                FileRef.Direct(Environment.getExternalStorageDirectory().absolutePath)
            is ScanTarget.GrantedFolder ->
                error("GrantedFolder target is only valid in SAF mode")
        }
    }
}
