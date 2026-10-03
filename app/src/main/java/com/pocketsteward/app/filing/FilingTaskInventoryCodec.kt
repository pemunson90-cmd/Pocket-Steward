package com.pocketsteward.app.filing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Bounded binary metadata embedded in the approved task. No Gson constructor bypass. */
object FilingTaskInventoryCodec {
    private const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_STRING = 64 * 1024

    fun encode(inventory: FilingTaskInventory): String {
        val bytes = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            private var count = 0L
            override fun write(value: Int) {
                require(++count <= MAX_BYTES)
                bytes.write(value)
            }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                count += length
                require(count <= MAX_BYTES)
                bytes.write(buffer, offset, length)
            }
        }
        DataOutputStream(bounded).use { out ->
            out.writeInt(2)
            out.writeInt(inventory.indexedFolderDescendants)
            out.writeInt(inventory.items.size)
            inventory.items.forEach { item ->
                out.string(item.source); out.string(item.displayName); out.writeBoolean(item.directory)
                out.string(item.outcome.name); out.string(item.reason)
                out.writeInt(item.operationSequence ?: -1); out.nullable(item.destination); out.nullable(item.originalLocation)
            }
            out.writeBoolean(inventory.intake != null)
            inventory.intake?.let { intake ->
                out.writeLong(intake.capturedAt); out.writeInt(intake.roots.size)
                intake.roots.forEach { root ->
                    out.string(root.root); out.nullable(root.error); out.writeInt(root.children.size)
                    root.children.forEach { out.string(it) }
                }
            }
        }
        require(bytes.size() <= MAX_BYTES)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    fun decode(encoded: String): FilingTaskInventory {
        require(encoded.length.toLong() <= MAX_BYTES.toLong() * 4 / 3 + 4)
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size <= MAX_BYTES)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val version = input.readInt().also { require(it in 1..2) { "Unsupported filing inventory version." } }
            val descendants = input.readInt().also { require(it >= 0) }
            val items = List(input.count(100_000)) {
                val source = input.string(); val name = input.string(); val directory = input.readBoolean()
                val outcome = FilingOutcome.valueOf(input.string()); val reason = input.string()
                val sequence = input.readInt().also { require(it >= -1) }.takeUnless { it == -1 }
                FilingTaskItem(source, name, directory, outcome, reason, sequence, input.nullable(), if (version >= 2) input.nullable() else null)
            }
            val intake = if (input.readBoolean()) {
                val time = input.readLong()
                var total = 0
                FilingIntakeSnapshot(List(input.count(1024)) {
                    val root = input.string(); val error = input.nullable()
                    val size = input.count(100_000)
                    total += size; require(total <= 200_000)
                    FilingIntakeRoot(root, List(size) { input.string() }, error)
                }, time)
            } else null
            require(input.available() == 0) { "Unexpected filing inventory data." }
            FilingTaskInventory(items, intake, descendants)
        }
    }

    private fun DataInputStream.count(max: Int): Int = readInt().also { require(it in 0..max) }
    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.string(): String {
        val size = count(MAX_STRING)
        require(size <= available())
        return String(ByteArray(size).also { readFully(it) }, StandardCharsets.UTF_8)
    }
    private fun DataOutputStream.nullable(value: String?) { writeBoolean(value != null); if (value != null) string(value) }
    private fun DataInputStream.nullable(): String? = if (readBoolean()) string() else null
}
