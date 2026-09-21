package com.pocketsteward.app.report

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue
import java.nio.charset.StandardCharsets

/**
 * Verified, non-overwriting manifest export.
 *
 * A gateway success is not treated as durable evidence by itself. We write a
 * temporary sibling, reopen and compare exact bytes, rename it into place,
 * then reopen the final file and compare again. The UI only receives Written
 * after both verification steps succeed.
 */
object VerifiedManifestExporter {
    suspend fun export(
        gateway: StorageGateway,
        parent: FileRef.Direct,
        document: TaskManifestDocument,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
    ): ExportResult {
        val finalName = TaskManifest.fileName(document.taskRunId, exportedAtEpochMs)
        val tempName = ".$finalName.partial"
        val expected = document.text.toByteArray(StandardCharsets.UTF_8)

        val tempWrite = gateway.writeTextFile(parent, tempName, document.text)
        val tempRef = when (tempWrite) {
            is MutationResult.Success -> tempWrite.resultRef
            is MutationResult.Failure -> return ExportResult.Failed(tempWrite.reason)
        }

        val tempVerified = verifyExact(gateway, tempRef, expected)
        if (!tempVerified) {
            runCatching { gateway.trash(tempRef) }
            return ExportResult.Failed(
                "Manifest write could not be verified after creation. No successful export was recorded.",
            )
        }

        val renamed = gateway.rename(tempRef, finalName)
        val finalRef = when (renamed) {
            is MutationResult.Success -> renamed.resultRef
            is MutationResult.Failure -> {
                runCatching { gateway.trash(tempRef) }
                return ExportResult.Failed(renamed.reason)
            }
        }

        if (!verifyExact(gateway, finalRef, expected)) {
            return ExportResult.Failed(
                "Manifest appeared to rename successfully, but the final file could not be reopened and verified.",
            )
        }

        return ExportResult.Written(finalRef.rawValue())
    }

    private suspend fun verifyExact(
        gateway: StorageGateway,
        ref: FileRef,
        expected: ByteArray,
    ): Boolean {
        if (!runCatching { gateway.exists(ref) }.getOrDefault(false)) return false

        val sizeMatches = runCatching {
            gateway.stat(ref).sizeBytes == expected.size.toLong()
        }.getOrDefault(false)
        if (!sizeMatches) return false

        val actual = runCatching {
            gateway.openRead(ref).use { it.readBytes() }
        }.getOrNull() ?: return false

        return actual.contentEquals(expected)
    }
}
