package com.pocketsteward.app.library

import com.google.gson.Gson
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Durable derived-index hints. File contents and executable operations are never stored here. */
class DirectoryInvalidationStore(private val file: File) {
    data class Snapshot(val generation: Long, val directories: Map<String, Long>, val fullGeneration: Long?) {
        val pending get() = fullGeneration != null || directories.isNotEmpty()
    }
    private data class State(val generation: Long = 0, val directories: Map<String, Long> = emptyMap(), val fullGeneration: Long? = null)
    private var state: State? = null
    private val claims = hashSetOf<Long>()
    private val gson = Gson()

    @Synchronized fun mark(paths: Set<String>): Boolean {
        if (paths.isEmpty()) return true
        val before = load()
        // Repeated operations within one active task need only one durable hint per directory.
        val alreadyPending = paths.all { before.directories[it]?.let { version -> claims.none { it >= version } } == true }
        if (alreadyPending || (before.fullGeneration != null && claims.none { it >= before.fullGeneration })) return true
        val generation = Math.addExact(before.generation, 1)
        val merged = before.directories + paths.associateWith { generation }
        val overflow = merged.size > DirectoryRefreshPolicy.MAX_DIRECTORIES || merged.keys.sumOf { it.toByteArray(Charsets.UTF_8).size } > DirectoryRefreshPolicy.MAX_PATH_BYTES
        return save(State(generation, if (overflow) emptyMap() else merged, if (overflow) generation else before.fullGeneration))
    }

    @Synchronized fun hasPending(): Boolean = load().let { it.fullGeneration != null || it.directories.isNotEmpty() }

    @Synchronized fun markFull(): Boolean {
        val before = load()
        if (before.fullGeneration != null && claims.none { it >= before.fullGeneration }) return true
        val generation = Math.addExact(before.generation, 1)
        return save(State(generation, emptyMap(), generation))
    }

    @Synchronized fun claim(): Snapshot {
        val current = load()
        claims += current.generation
        return Snapshot(current.generation, current.directories.toMap(), current.fullGeneration)
    }
    @Synchronized fun complete(snapshot: Snapshot): Boolean {
        val current = load()
        val remaining = current.directories.filter { (path, version) -> snapshot.directories[path] != version }
        val full = current.fullGeneration.takeUnless { it == snapshot.fullGeneration }
        val saved = save(current.copy(directories = remaining, fullGeneration = full))
        claims -= snapshot.generation
        return saved
    }
    @Synchronized fun release(snapshot: Snapshot) { claims -= snapshot.generation }

    private fun load(): State {
        state?.let { return it }
        val loaded = if (!file.isFile) State() else try {
            require(file.length() <= 128 * 1024)
            val value = gson.fromJson(file.readText(), State::class.java)
            require(value.generation >= 0 && value.generation < Long.MAX_VALUE)
            require(value.directories.size <= DirectoryRefreshPolicy.MAX_DIRECTORIES)
            require(value.directories.keys.sumOf { it.toByteArray(Charsets.UTF_8).size } <= DirectoryRefreshPolicy.MAX_PATH_BYTES)
            require(value.directories.values.all { it in 1..value.generation })
            require(value.fullGeneration == null || value.fullGeneration in 1..value.generation)
            value
        } catch (_: Exception) { State(1, emptyMap(), 1) }
        state = loaded
        return loaded
    }
    private fun save(next: State): Boolean {
        val temporary = File(file.parentFile, "${file.name}.${UUID.randomUUID()}.pending")
        return try {
            require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            java.io.FileOutputStream(temporary).use { stream ->
                stream.write(gson.toJson(next).toByteArray(Charsets.UTF_8)); stream.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            state = next
            true
        } catch (_: Exception) { false }
        finally { temporary.delete() }
    }
}
