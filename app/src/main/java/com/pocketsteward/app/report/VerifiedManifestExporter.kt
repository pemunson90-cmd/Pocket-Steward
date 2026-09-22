package com.pocketsteward.app.report

import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.MutationResult
import com.pocketsteward.app.storage.StorageGateway
import com.pocketsteward.app.storage.rawValue
import java.nio.charset.StandardCharsets

object VerifiedTextExporter {
    suspend fun export(
        gateway: StorageGateway,
        parent: FileRef,
        finalName: String,
        content: String,
    ): ExportResult {
        require(finalName.isNotBlank() && '/' !in finalName && '\\' !in finalName) {
            "Export file name must be one safe path segment."
        }
        val tempName = ".$finalName.partial"
        val expected = content.toByteArray(StandardCharsets.UTF_8)

        val tempWrite = gateway.writeTextFile(parent, tempName, content)
        val tempRef = when (tempWrite) {
            is MutationResult.Success -> tempWrite.resultRef
            is MutationResult.Failure -> return ExportResult.Failed(tempWrite.reason)
        }

        if (!verifyExact(gateway, tempRef, expected)) {
            runCatching { gateway.trash(tempRef) }
            return ExportResult.Failed(
                "Export write could not be verified after creation. No successful export was recorded.",
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
                "Export appeared to rename successfully, but the final file could not be reopened and verified.",
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

/**
 * Task-manifest-specific naming on top of the generic verified text writer.
 */
object VerifiedManifestExporter {
    suspend fun export(
        gateway: StorageGateway,
        parent: FileRef,
        document: TaskManifestDocument,
        exportedAtEpochMs: Long = System.currentTimeMillis(),
        format: ManifestFormat = ManifestFormat.MARKDOWN,
    ): ExportResult {
        val base = "POCKETSTEWARD-MANIFEST-task${document.taskRunId}-$exportedAtEpochMs"
        val name = when (format) {
            ManifestFormat.MARKDOWN -> "$base.md"
            ManifestFormat.JSON -> "$base.json"
        }
        val content = when (format) {
            ManifestFormat.MARKDOWN -> document.text
            ManifestFormat.JSON -> TaskManifestJson.render(document)
        }
        return VerifiedTextExporter.export(gateway, parent, name, content)
    }
}

enum class ManifestFormat { MARKDOWN, JSON }
