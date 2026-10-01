package com.pocketsteward.app.filing

import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import java.time.LocalDate

/** Category suggestions only after project evidence fails; always probable and unchecked. */
object NonProjectMediaFiling {
    fun propose(artifact: FilingArtifact, storageRoot: String): FilingDecision? {
        if (artifact.isDirectory) return null
        val extension = artifact.extension.lowercase()
        val root = storageRoot.trimEnd('/')
        val destination: String
        val explanation: String
        if (extension in setOf("mp3", "m4a", "flac", "wav", "ogg", "mp4", "mkv", "mov", "webm")) {
            val album = label(artifact.mediaAlbum) ?: return null
            val artist = label(artifact.mediaArtist) ?: "Unknown artist"
            val type = if (extension in setOf("mp3", "m4a", "flac", "wav", "ogg")) "Music" else "Movies"
            destination = "$root/$type/$artist/$album"
            explanation = "Embedded media tags identify $artist · $album; confirm this album grouping."
        } else if (extension in setOf("jpg", "jpeg", "heic", "png", "webp")) {
            val date = artifact.captureDate?.let { runCatching { LocalDate.parse(it).toString() }.getOrNull() }
            val topic = topicFromLabels(artifact.imageLabels)
            if (topic != null) {
                destination = "$root/Images/$topic"
                explanation = "Local image labels suggest $topic (${artifact.imageLabels.take(5).joinToString()}); confirm this topic."
            } else if (date != null) {
                destination = "$root/Images/Events/$date"
                explanation = "Camera metadata records $date; confirm the camera date before grouping this event."
            } else return null
        } else return null
        val name = destination.substringAfterLast('/')
        val home = ProjectHomeCandidate(name, destination, hierarchy = ProjectHierarchyStrategy.FLAT)
        return FilingDecision(artifact, name, home, null, destination, FilingConfidence.PROBABLE,
            listOf(FilingEvidence(if (artifact.imageLabels.isNotEmpty()) FilingEvidenceKind.IMAGE_CONTENT else FilingEvidenceKind.MEDIA_METADATA, explanation, 70)), createsProjectHome = true)
    }
    fun topicFromLabels(labels: List<String>): String? {
        val normalized = labels.map { it.lowercase() }.toSet()
        val topics = listOf(
            "Landscape" to setOf("landscape", "mountain", "forest", "beach", "waterfall", "desert", "nature"),
            "Portraits" to setOf("portrait", "selfie"),
            "Screenshots" to setOf("screenshot"),
        ).filter { (_, words) -> normalized.any { it in words } }.map { it.first }
        return topics.singleOrNull()
    }
    private fun label(value: String?): String? = value?.replace('/', '_')?.replace('\\', '_')?.let(InboxFilingEngine::sanitizeSegment)
}
