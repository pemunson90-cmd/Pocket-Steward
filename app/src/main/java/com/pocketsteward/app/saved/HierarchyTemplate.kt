package com.pocketsteward.app.saved

import com.pocketsteward.app.filing.InboxFilingEngine

/** Explicit user-selected relative role folders, never model-generated paths. */
data class HierarchyTemplate(val roleFolders: Map<String, String> = emptyMap()) {
    companion object {
        val roles = setOf("Manuscript", "Notes", "Drafts", "Images", "Versions", "Archive")
        fun parse(text: String): HierarchyTemplate {
            val mappings = linkedMapOf<String, String>()
            for (line in text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }) {
                val parts = line.split('=', limit = 2)
                require(parts.size == 2 && parts[0].trim() in roles) { "Use Role=Folder, with Manuscript, Notes, Drafts, Images, Versions or Archive." }
                val role = parts[0].trim()
                val folder = parts[1].trim()
                require(role !in mappings) { "Each role can appear only once." }
                val segments = folder.split('/')
                require(folder.isEmpty() || (segments.size <= 4 && segments.all { InboxFilingEngine.sanitizeSegment(it) == it })) {
                    "Folders must be safe relative paths with at most four levels. Leave a value empty for the project root."
                }
                mappings[role] = folder
            }
            return HierarchyTemplate(mappings)
        }
    }
    fun encode(): String = roleFolders.entries.joinToString("\n") { "${it.key}=${it.value}" }
}
