package com.pocketsteward.app.ui.scan

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.data.settings.SettingsRepository
import com.pocketsteward.app.di.AppContainer
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
        val totalFiles: Int,
        val totalBytes: Long,
        val byCategory: Map<FileCategory, CategoryStat>,
    ) : ScanUiState
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
                    totalFiles = records.size,
                    totalBytes = records.sumOf { it.sizeBytes },
                    byCategory = byCategory,
                )
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
