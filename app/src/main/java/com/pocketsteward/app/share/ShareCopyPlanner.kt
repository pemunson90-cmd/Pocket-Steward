package com.pocketsteward.app.share

import com.pocketsteward.app.filing.FilingArtifact
import com.pocketsteward.app.filing.InboxFilingEngine
import com.pocketsteward.app.filing.ProjectHomeCandidate
import com.pocketsteward.app.plan.FileIndex
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.saved.ProjectHierarchyStrategy
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.parseFileRef
import com.pocketsteward.app.storage.rawValue

/** Incoming sources have read-only authority: every source operation is a Copy. */
object ShareCopyPlanner {
    fun build(files: List<FilingArtifact>, destination: FileRef.Saf, projectTitle: String, roleFolders: Map<String, String>, index: FileIndex): List<PlannedOperation> {
        require(files.isNotEmpty()) { "Select at least one shared file." }
        require(files.distinctBy { it.stableRef }.size == files.size) { "Repeated shared source." }
        val title = projectTitle.trim()
        require(title.isEmpty() || InboxFilingEngine.sanitizeSegment(title) == title) { "Use a safe project title without path separators." }
        val operations = mutableListOf<PlannedOperation>()
        val created = linkedMapOf<String, FileRef>()
        val destinations = hashSetOf<String>()
        for (file in files) {
            require(!file.isDirectory && file.stableRef.startsWith("content://")) { "Only shared content files can be imported." }
            require(file.displayName.isNotBlank() && file.displayName !in setOf(".", "..") && file.displayName.none { it == '/' || it == '\\' || it.isISOControl() }) { "The shared filename is unsafe. Rename it in its source app first." }
            val relative = if (title.isNotEmpty()) {
                val home = ProjectHomeCandidate(title, destination.rawValue() + "/" + title, hierarchy = ProjectHierarchyStrategy.PROJECT_ROLES, roleFolders = roleFolders)
                InboxFilingEngine.destinationFor(home, null, file).removePrefix(destination.rawValue() + "/")
            } else category(file.extension)
            var parent: FileRef = destination
            for (segment in relative.split('/').filter { it.isNotEmpty() }) {
                val key = parent.rawValue() + "\u0000" + segment.lowercase()
                parent = created[key] ?: index.caseInsensitiveMatch(parent, segment)?.also {
                    require(index.isDirectory(it)) { "$segment is a file, so it cannot contain imports." }
                } ?: FileRef.Child(parent, segment).also { child ->
                    operations += PlannedOperation.CreateDirectory(parent, segment, "Create the reviewed import folder")
                    created[key] = child
                }
            }
            val target = FileRef.Child(parent, file.displayName)
            require(destinations.add(parent.rawValue() + "\u0000" + file.displayName.lowercase())) { "Two shared files have the same destination name. Import them separately into different folders." }
            require(index.caseInsensitiveMatch(parent, file.displayName) == null) { "${file.displayName} already exists at this destination. Choose another project or folder." }
            operations += PlannedOperation.Copy(parseFileRef(file.stableRef), target, "Copy shared file; preserve the original")
        }
        return operations
    }
    private fun category(extension: String): String = when (extension.lowercase()) {
        "jpg", "jpeg", "png", "webp", "heic", "gif" -> "Images"
        "mp3", "m4a", "flac", "ogg", "wav" -> "Music"
        "mp4", "mkv", "mov", "webm" -> "Movies"
        "zip", "7z", "tar", "gz", "rar" -> "Archives"
        "apk", "apks", "xapk" -> "Apps"
        else -> "Documents"
    }
}
