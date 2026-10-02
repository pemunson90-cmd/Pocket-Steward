package com.pocketsteward.app.evidence

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** Private cache revisions, never an integrity proof or filesystem authority.
 * Events update memory immediately. A durable dirty marker fences interrupted,
 * coalesced saves; restart then invalidates old evidence conservatively.
 */
class ObservedEvidenceStore(private val file: File) {
    private data class State(val salt: String, var generation: Long, var full: Long, val paths: MutableMap<String, Long>)
    private val marker = File(file.parentFile, "${file.name}.dirty")
    private var state: State? = null
    private var persistedGeneration = -1L
    private var dirty = false
    private var fenceFailed = false
    private var pathBytes = 0

    @Synchronized fun observe(paths: Set<String> = emptySet(), full: Boolean = false): Boolean {
        val before = load()
        if (!full && paths.isEmpty()) return true
        val valid = paths.all { it.length in 1..4096 && it.none(Char::isISOControl) }
        if (dirty && !fenceFailed && ((before.full > persistedGeneration) ||
                (!full && valid && paths.all { (before.paths[it] ?: 0) > persistedGeneration }))) return true
        if (!dirty || fenceFailed || !marker.exists()) fenceFailed = !fence()
        val next = Math.addExact(before.generation, 1)
        var collapse = full || !valid
        if (!collapse) for (path in paths) {
            if (!before.paths.containsKey(path)) pathBytes += path.toByteArray().size
            before.paths[path] = next
            if (before.paths.size > MAX_PATHS || pathBytes > MAX_PATH_BYTES) { collapse = true; break }
        }
        before.generation = next
        if (collapse) { before.full = next; before.paths.clear(); pathBytes = 0 }
        dirty = true
        return !fenceFailed
    }

    /** Exact identities for opaque URIs; segment ancestry only for direct paths. */
    @Synchronized fun revision(ref: String): String {
        val current = load()
        check(flush()) { "Observed source revisions could not be saved; cached evidence is unavailable." }
        var version = maxOf(current.full, current.paths[ref] ?: 0)
        if (ref.startsWith('/')) {
            var parent = ref.trimEnd('/').substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                version = maxOf(version, current.paths[parent] ?: 0)
                parent = parent.substringBeforeLast('/', "")
            }
            version = maxOf(version, current.paths["/"] ?: 0)
        }
        return "${current.salt}:$version"
    }

    @Synchronized fun inventoryRevision(): String {
        val current = load()
        check(flush()) { "Observed library revisions could not be saved." }
        return "${current.salt}:${current.generation}"
    }
    @Synchronized fun hasPending(): Boolean { load(); return dirty }

    @Synchronized fun flush(): Boolean {
        val current = load()
        if (!dirty) return true
        val temporary = File(file.parentFile, "${file.name}.pending")
        return try {
            require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(1); out.writeUTF(current.salt); out.writeLong(current.generation); out.writeLong(current.full)
                out.writeInt(current.paths.size)
                current.paths.forEach { (path, generation) -> out.writeUTF(path); out.writeLong(generation) }
            }
            val payload = bytes.toByteArray()
            FileOutputStream(temporary).use { out ->
                out.write(MessageDigest.getInstance("SHA-256").digest(payload)); out.write(payload); out.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            check(!marker.exists() || marker.delete())
            persistedGeneration = current.generation; dirty = false; fenceFailed = false
            true
        } catch (_: Exception) { false }
        finally { temporary.delete() }
    }

    private fun load(): State {
        state?.let { return it }
        var validState = file.exists()
        val loaded = if (!file.exists()) State(UUID.randomUUID().toString(), 0, 0, linkedMapOf()) else try {
            require(file.length() in 60..1_048_576)
            val bytes = file.readBytes(); val payload = bytes.copyOfRange(32, bytes.size)
            require(MessageDigest.isEqual(bytes.copyOfRange(0, 32), MessageDigest.getInstance("SHA-256").digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == 1)
                val salt = input.readUTF(); require(UUID.fromString(salt).toString() == salt)
                val generation = input.readLong(); require(generation in 0..Long.MAX_VALUE - 2)
                val full = input.readLong(); require(full in 0..generation)
                val count = input.readInt(); require(count in 0..MAX_PATHS)
                val paths = linkedMapOf<String, Long>()
                repeat(count) {
                    val path = input.readUTF(); require(path.length in 1..4096 && path.none(Char::isISOControl))
                    val version = input.readLong(); require(version in 1..generation && paths.put(path, version) == null)
                }
                require(paths.keys.sumOf { it.toByteArray().size } <= MAX_PATH_BYTES && input.available() == 0)
                State(salt, generation, full, paths)
            }
        } catch (_: Exception) { validState = false; State(UUID.randomUUID().toString(), 1, 1, linkedMapOf()) }
        persistedGeneration = loaded.generation
        val recovered = if (marker.exists()) loaded.copy(generation = loaded.generation + 1,
            full = loaded.generation + 1, paths = linkedMapOf()) else loaded
        // Corrupt state is rewritten with its new salt before a cache can use it.
        dirty = marker.exists() || !validState
        pathBytes = recovered.paths.keys.sumOf { it.toByteArray().size }
        state = recovered
        return recovered
    }

    private fun fence(): Boolean = try {
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        if (!marker.exists()) FileOutputStream(marker).use { it.fd.sync() }
        true
    } catch (_: Exception) { false }

    companion object { const val MAX_PATHS = 4096; private const val MAX_PATH_BYTES = 256 * 1024 }
}
