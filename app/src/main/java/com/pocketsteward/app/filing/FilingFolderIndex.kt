package com.pocketsteward.app.filing

import com.pocketsteward.app.data.db.FileRecord

/** Indexed parent identities, never URI prefixes, associate evidence with an intact move unit. */
class FilingFolderIndex(records: List<FileRecord>, folderRefs: Set<String>) {
    private val parents = records.associate { it.stableRef to it.parentRef }
    private val owners = hashMapOf<String, String?>()
    private val folders = folderRefs.toHashSet()

    fun ownerOf(ref: String): String? {
        if (owners.containsKey(ref)) return owners[ref]
        val visited = linkedSetOf<String>()
        var node: String? = ref
        var owner: String? = null
        while (node != null && visited.add(node)) {
            if (node != ref && node in folders) { owner = node; break }
            if (owners.containsKey(node)) { owner = owners[node]; break }
            node = parents[node]
        }
        visited.filterNot { it in folders }.forEach { owners[it] = owner }
        return owner
    }

    fun descendants(records: List<FileRecord>): Map<String, List<FileRecord>> =
        records.distinctBy { it.stableRef }.mapNotNull { record -> ownerOf(record.stableRef)?.let { it to record } }
            .groupBy({ it.first }, { it.second })
}
