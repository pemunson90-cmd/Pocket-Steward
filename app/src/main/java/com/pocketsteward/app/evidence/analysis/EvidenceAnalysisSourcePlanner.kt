package com.pocketsteward.app.evidence.analysis

import com.pocketsteward.app.content.ContentExtractor
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.filing.FilingFolderIndex
import com.pocketsteward.app.image.ImageReviewBatch
import com.pocketsteward.app.plan.SourcePrecondition
import java.util.Locale

data class EvidenceAnalysisSources(val sources: List<EvidenceAnalysisSource>, val folders: List<EvidenceAnalysisFolder>)

object EvidenceAnalysisSourcePlanner {
    fun eligible(record: FileRecord, images: Boolean, content: Boolean): Boolean = !record.isDirectory &&
        (images && record.extension.lowercase(Locale.ROOT) in ImageReviewBatch.EXTENSIONS || content && ContentExtractor.supports(record.extension))

    fun forReview(artifacts: List<FilingArtifact>, indexed: List<FileRecord>, baselines: Map<String, SourcePrecondition>,
        images: Boolean, content: Boolean, rootFor: (FileRecord) -> String): EvidenceAnalysisSources {
        val byRef = indexed.associateBy { it.stableRef }
        val folders = artifacts.filter { it.isDirectory }.mapNotNull { artifact ->
            baselines[artifact.stableRef]?.takeIf { it.directoryDigest != null }?.let { EvidenceAnalysisFolder(artifact.stableRef, it) }
        }
        val graph = FilingFolderIndex(indexed, folders.mapTo(hashSetOf()) { it.ref })
        val result = linkedMapOf<String, EvidenceAnalysisSource>()
        artifacts.filterNot { it.isDirectory }.forEach { artifact ->
            val original = byRef[artifact.stableRef] ?: FileRecord(stableRef = artifact.stableRef, displayName = artifact.displayName,
                extension = artifact.extension, mimeType = null, absolutePathOrUri = artifact.stableRef, parentRef = artifact.parentRef,
                sizeBytes = artifact.sizeBytes, createdAt = artifact.createdAt, modifiedAt = artifact.modifiedAt,
                lastScannedAt = 0, isDirectory = false, isHidden = artifact.displayName.startsWith('.'))
            val record = original.copy(displayName = artifact.displayName, extension = artifact.extension, sizeBytes = artifact.sizeBytes,
                modifiedAt = artifact.modifiedAt, parentRef = artifact.parentRef)
            if (eligible(record, images, content)) result[record.stableRef] = EvidenceAnalysisSource(record, rootFor(record))
        }
        indexed.forEach { record ->
            val owner = graph.ownerOf(record.stableRef)
            if (owner != null && eligible(record, images, content)) {
                result.putIfAbsent(record.stableRef, EvidenceAnalysisSource(record, rootFor(record), owner))
            }
        }
        return EvidenceAnalysisSources(result.values.toList(), folders)
    }
}
