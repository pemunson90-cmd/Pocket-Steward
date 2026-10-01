package com.pocketsteward.app.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.PocketStewardApplication
import com.pocketsteward.app.backup.*
import com.pocketsteward.app.content.copyInspectionSource
import com.pocketsteward.app.service.LibraryRefreshWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal data class BackupUiState(val busy: Boolean = false, val message: String? = null, val pending: PortableBackup? = null, val importedHistory: List<ArchivedTaskSummary> = emptyList())

internal class BackupViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application
    private val container = (application as PocketStewardApplication).container
    private val settings = container.settingsRepository
    private val historyFile = File(application.noBackupFilesDir, "backup/imported-history.json")
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state

    init { viewModelScope.launch {
        val history = withContext(Dispatchers.IO) {
            runCatching { if (historyFile.isFile && historyFile.length() <= PortableBackupCodec.MAX_BYTES) PortableBackupCodec.decode(historyFile.readBytes()).history else emptyList() }.getOrDefault(emptyList())
        }
        _state.value = _state.value.copy(importedHistory = history)
    } }

    fun export(uri: Uri) = perform {
        val backup = PortableBackup(createdAt = System.currentTimeMillis(), settings = settings.exportPortableSettings(), history = container.database.taskRunDao().portableSummaries())
        val bytes = PortableBackupCodec.encode(backup)
        // The system picker created this artifact. Refuse a provider that returns a nonempty target.
        context.contentResolver.openInputStream(uri)?.use { require(it.read() == -1) { "Backup destination is not empty; choose a new file." } }
            ?: error("Could not verify the new backup destination.")
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes); it.flush() }
            ?: error("Could not write backup.")
        val verified = read(uri)
        require(verified.contentEquals(bytes)) { "Backup verification failed; the exported file is not a verified backup." }
        _state.value = _state.value.copy(message = "Backup exported and verified: ${backup.settings.size} settings, ${backup.history.size} read-only task summaries.")
    }

    fun inspect(uri: Uri) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(pending = null)
        perform {
            val backup = PortableBackupCodec.decode(read(uri))
            _state.value = _state.value.copy(pending = backup, message = null)
        }
    }
    fun dismissImport() { if (!_state.value.busy) _state.value = _state.value.copy(pending = null) }

    fun restore() {
        val backup = _state.value.pending ?: return
        perform {
            val historyOnly = backup.copy(settings = emptyMap())
            val bytes = PortableBackupCodec.encode(historyOnly)
            require(historyFile.parentFile!!.isDirectory || historyFile.parentFile!!.mkdirs())
            val pendingFile = File(historyFile.parentFile, "imported-history.pending")
            try {
                java.io.FileOutputStream(pendingFile).use { it.write(bytes); it.fd.sync() }
                settings.restorePortableSettings(backup.settings)
                container.scheduledCleanupCoordinator.apply(settings.scheduledCleanupSettings.first())
                LibraryRefreshWorker.sync(context, false)
                try {
                    Files.move(pendingFile.toPath(), historyFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (failure: Exception) {
                    _state.value = _state.value.copy(pending = null, message = "Settings restored, but imported history could not be saved. Retry importing the backup. Background reviews remain off.")
                    return@perform
                }
                _state.value = _state.value.copy(pending = null, importedHistory = backup.history, message = "Settings restored. Imported history is read-only. Background reviews remain off; storage grants and undo journals were not imported.")
            } finally { pendingFile.delete() }
        }
    }
    private suspend fun read(uri: Uri): ByteArray {
        val output = ByteArrayOutputStream()
        context.contentResolver.openInputStream(uri)?.use { copyInspectionSource(it, output, PortableBackupCodec.MAX_BYTES.toLong()) }
            ?: error("Backup cannot be read.")
        return output.toByteArray()
    }
    private fun perform(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { block() } }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { _state.value = _state.value.copy(message = failure.message ?: "Backup operation failed.") }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
