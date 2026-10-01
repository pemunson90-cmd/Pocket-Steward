package com.pocketsteward.app.ui.scan

import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.filing.FilingFreshness
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.parseFileRef

/** Shared live observation before filing or Coherence reuses derived evidence. */
internal suspend fun ScanViewModel.freshEvidenceRecord(record: FileRecord, mode: StorageAccessMode): FileRecord {
    val gateway = container.gatewayFor(mode)
    val ref = parseFileRef(record.stableRef)
    require(gateway.exists(ref)) { "Source disappeared or access was revoked. Refresh the scan." }
    val fresh = FilingFreshness.refresh(record, gateway.stat(ref))
    if (fresh != record) container.database.fileRecordDao().upsert(fresh)
    return fresh
}
