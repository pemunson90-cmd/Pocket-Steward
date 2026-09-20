package com.pocketsteward.app.saved

import com.pocketsteward.app.content.index.ContentSearchFilters
import com.pocketsteward.app.content.index.ContentSearchProvenance
import com.pocketsteward.app.content.index.ContentSearchSort
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

data class SavedSearch(
    val id: String,
    val name: String,
    val query: String,
    val roots: List<String>,
    val sort: ContentSearchSort,
    val filters: ContentSearchFilters,
    val lastResultCount: Int,
    val lastOpenedAt: Long,
)

object SavedSearchCodec {
    private const val VERSION = 1
    private const val MAX_STRING_BYTES = 64 * 1024

    fun encode(searches: List<SavedSearch>): String =
        searches.joinToString("\n") { encodeOne(it) }

    fun decode(raw: String?): List<SavedSearch> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { line -> runCatching { decodeOne(line) }.getOrNull() }
            .distinctBy { it.id }
            .toList()
    }

    private fun encodeOne(search: SavedSearch): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(VERSION)
            out.writeString(search.id)
            out.writeString(search.name)
            out.writeString(search.query)
            out.writeStrings(search.roots)
            out.writeString(search.sort.name)
            out.writeStrings(search.filters.sourceRoots.sorted())
            out.writeStrings(search.filters.categories.sorted())
            out.writeStrings(search.filters.extensions.sorted())
            out.writeString(search.filters.provenance.name)
            out.writeNullableLong(search.filters.modifiedAfter)
            out.writeNullableLong(search.filters.modifiedBefore)
            out.writeNullableLong(search.filters.minSizeBytes)
            out.writeNullableLong(search.filters.maxSizeBytes)
            out.writeString(search.filters.pathContains)
            out.writeInt(search.lastResultCount)
            out.writeLong(search.lastOpenedAt)
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    private fun decodeOne(encoded: String): SavedSearch {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == VERSION) { "Unsupported saved-search version." }
            SavedSearch(
                id = input.readString(),
                name = input.readString(),
                query = input.readString(),
                roots = input.readStrings().map { it.trimEnd('/') }.filter { it.isNotBlank() }.distinct(),
                sort = runCatching { ContentSearchSort.valueOf(input.readString()) }
                    .getOrDefault(ContentSearchSort.RELEVANCE),
                filters = ContentSearchFilters(
                    sourceRoots = input.readStrings().toSet(),
                    categories = input.readStrings().toSet(),
                    extensions = input.readStrings().map { it.lowercase() }.toSet(),
                    provenance = runCatching { ContentSearchProvenance.valueOf(input.readString()) }
                        .getOrDefault(ContentSearchProvenance.ANY),
                    modifiedAfter = input.readNullableLong(),
                    modifiedBefore = input.readNullableLong(),
                    minSizeBytes = input.readNullableLong(),
                    maxSizeBytes = input.readNullableLong(),
                    pathContains = input.readString(),
                ),
                lastResultCount = input.readInt().coerceAtLeast(0),
                lastOpenedAt = input.readLong(),
            ).also {
                require(it.id.isNotBlank()) { "Saved search id is blank." }
                require(it.name.isNotBlank()) { "Saved search name is blank." }
                require(it.query.isNotBlank()) { "Saved search query is blank." }
                require(it.roots.isNotEmpty()) { "Saved search has no roots." }
            }
        }
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Saved-search string is too large." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val size = readInt()
        require(size in 0..MAX_STRING_BYTES) { "Invalid saved-search string size." }
        val bytes = ByteArray(size)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeStrings(values: Collection<String>) {
        require(values.size <= 256) { "Too many saved-search values." }
        writeInt(values.size)
        values.forEach(::writeString)
    }

    private fun DataInputStream.readStrings(): List<String> {
        val count = readInt()
        require(count in 0..256) { "Invalid saved-search list size." }
        return List(count) { readString() }
    }

    private fun DataOutputStream.writeNullableLong(value: Long?) {
        writeBoolean(value != null)
        if (value != null) writeLong(value)
    }

    private fun DataInputStream.readNullableLong(): Long? =
        if (readBoolean()) readLong() else null
}
