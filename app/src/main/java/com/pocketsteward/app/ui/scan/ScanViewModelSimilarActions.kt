package com.pocketsteward.app.ui.scan

import androidx.lifecycle.viewModelScope
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.storage.child
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun ScanViewModel.proposeSimilarArchive(group: SimilarFileGroup, keeperRef: String) {
    val summary = _summary.value ?: return
    require(group.records.any { it.stableRef == keeperRef })
    viewModelScope.launch {
        try {
            val operations = mutableListOf<PlannedOperation>()
            val folders = hashSetOf<String>()
            group.records.filterNot { it.stableRef == keeperRef }.forEach { record ->
                val parent = parseFileRef(requireNotNull(record.parentRef) { "This file has no known parent folder." })
                val archive = parent.child("Archive")
                val similar = archive.child("Similar Files")
                if (folders.add(archive.rawValue())) operations += PlannedOperation.CreateDirectory(parent, "Archive", "Archive the files you reviewed as similar; preserve the chosen keeper.")
                if (folders.add(similar.rawValue())) operations += PlannedOperation.CreateDirectory(archive, "Similar Files", "Keep reviewed similar versions recoverable.")
                operations += PlannedOperation.Move(parseFileRef(record.stableRef), similar.child(record.displayName), "You chose another keeper after comparing similar files. Archive this version without deleting it.")
            }
            showPlanPreview(
                "Archive reviewed similar files", operations, summary.scopes,
                scopeNotes = listOf("These files are similar, not proven identical. The keeper stays in place. Other versions go to their current folder's Archive › Similar Files; review each move before approval."),
                defaultSelectedSourceRefs = emptySet(),
            )
        } catch (cancel: CancellationException) { throw cancel }
        catch (t: Throwable) { _error.value = t.message ?: "Could not propose the similar-file archive." }
    }
}
