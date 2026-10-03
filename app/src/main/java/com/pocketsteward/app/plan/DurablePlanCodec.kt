package com.pocketsteward.app.plan

import com.pocketsteward.app.storage.FileRefJournalCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

data class DurablePlan(
    val goal: String,
    val operations: List<PlannedOperation>,
    val sourcePreconditions: Map<Int, SourcePrecondition> = emptyMap(),
    val filingInventory: com.pocketsteward.app.filing.FilingTaskInventory? = null,
)

/**
 * Machine-resumable approved operations embedded beside the legacy
 * human-readable reason lines already stored in TaskRun.planJson.
 *
 * Existing manifest readers ignore @psplan/@psop lines because they do not
 * start with a numeric sequence. This lets new tasks become resumable without
 * a Room schema migration or invalidating old task history.
 */
object DurablePlanCodec {
    private const val HEADER_V1 = "@psplan\t1"
    private const val HEADER_V2 = "@psplan\t2"
    private const val HEADER_V3 = "@psplan\t3"
    private const val HEADER_V4 = "@psplan\t4"
    private const val HEADER_V5 = "@psplan\t5"
    private const val GOAL_PREFIX = "@psgoal\t"
    private const val OP_PREFIX = "@psop\t"
    private const val INVENTORY_PREFIX = "@psfiling\t"
    private const val PRECONDITION_PREFIX = "@pspre\t"
    private const val BINARY_VERSION = 1
    private const val MAX_STRING_BYTES = 16 * 1024 * 1024

    fun encode(
        goal: String,
        operations: List<PlannedOperation>,
        sourcePreconditions: Map<Int, SourcePrecondition> = emptyMap(),
        filingInventory: com.pocketsteward.app.filing.FilingTaskInventory? = null,
    ): String = buildString {
        filingInventory?.validate(operations)
        appendLine(humanLine(goal))
        appendLine(HEADER_V5)
        appendLine(GOAL_PREFIX + encodeToken(goal))
        filingInventory?.let { appendLine(INVENTORY_PREFIX + com.pocketsteward.app.filing.FilingTaskInventoryCodec.encode(it)) }
        operations.forEachIndexed { sequence, operation ->
            appendLine("$sequence\t${operation.typeLabel()}\t${humanLine(operation.reason)}")
            appendLine("$OP_PREFIX$sequence\t${encodeOperation(operation)}")
            sourcePreconditions[sequence]?.let { precondition ->
                appendLine(
                    "$PRECONDITION_PREFIX$sequence\t${precondition.sizeBytes}\t" +
                        (precondition.modifiedAtEpochMs?.toString() ?: "null") + "\t" +
                        (precondition.directoryDigest?.let(::encodeToken) ?: "null") + "\t" + precondition.directoryEntryCount + "\t" + (precondition.location?.let(::encodeToken) ?: "null"),
                )
            }
        }
    }

    fun decodeOrNull(text: String): DurablePlan? = runCatching {
        val lines = text.lineSequence().toList()
        require(lines.count { it in setOf(HEADER_V1, HEADER_V2, HEADER_V3, HEADER_V4, HEADER_V5) } == 1) { "Ambiguous durable plan headers." }
        val version = when {
            HEADER_V5 in lines -> 5
            HEADER_V4 in lines -> 4
            HEADER_V3 in lines -> 3
            HEADER_V2 in lines -> 2
            HEADER_V1 in lines -> 1
            else -> return null
        }

        val headerIndex = lines.indexOf("@psplan\t$version")
        require(lines.take(headerIndex).none { it.startsWith(OP_PREFIX) || it.startsWith(PRECONDITION_PREFIX) || it.startsWith(GOAL_PREFIX) || it.startsWith(INVENTORY_PREFIX) }) { "Machine record precedes plan header." }
        val indexed = lines.mapNotNull { line ->
            if (!line.startsWith(OP_PREFIX)) return@mapNotNull null
            val fields = line.split('\t', limit = 3)
            require(fields.size == 3) { "Malformed durable operation line." }
            fields[1].toInt() to decodeOperation(fields[2])
        }

        require(indexed.isNotEmpty()) { "Durable plan has no operations." }
        require(indexed.map { it.first } == indexed.indices.toList()) {
            "Durable plan operation sequence is not contiguous."
        }

        val preconditions = if (version >= 2) {
            val entries = lines.mapNotNull { line ->
                if (!line.startsWith(PRECONDITION_PREFIX)) return@mapNotNull null
                val fields = line.split('\t')
                require(fields.size == if (version >= 5) 7 else if (version >= 3) 6 else 4) { "Malformed source-precondition line." }
                val sequence = fields[1].toInt()
                val sizeBytes = fields[2].toLong()
                val modified = fields[3].takeUnless { it == "null" }?.toLong()
                sequence to SourcePrecondition(
                    sizeBytes, modified,
                    if (version >= 3) fields[4].takeUnless { it == "null" }?.let { if (version >= 4) decodeToken(it) else it } else null,
                    if (version >= 3) fields[5].toInt() else 0,
                    if (version >= 5) fields[6].takeUnless { it == "null" }?.let(::decodeToken) else null,
                )
            }
            require(entries.map { it.first }.distinct().size == entries.size) { "Duplicate source preconditions." }
            entries.toMap()
        } else {
            emptyMap()
        }
        require(preconditions.keys.all { it in indexed.indices }) {
            "Source precondition references an operation outside the durable plan."
        }

        val goal = if (version >= 4) {
            val goals = lines.filter { it.startsWith(GOAL_PREFIX) }
            require(goals.size == 1) { "Missing or duplicate durable goal." }
            decodeToken(goals.single().removePrefix(GOAL_PREFIX))
        } else lines.firstOrNull().orEmpty()
        val inventoryLines = lines.filter { it.startsWith(INVENTORY_PREFIX) }
        require(inventoryLines.size <= 1) { "Duplicate filing inventory." }
        val inventory = inventoryLines.singleOrNull()?.let {
            com.pocketsteward.app.filing.FilingTaskInventoryCodec.decode(it.removePrefix(INVENTORY_PREFIX))
                .also { inventory -> inventory.validate(indexed.map { it.second }) }
        }
        DurablePlan(
            goal = goal,
            operations = indexed.map { it.second },
            sourcePreconditions = preconditions,
            filingInventory = inventory,
        )
    }.getOrNull()

    fun isDurable(text: String): Boolean =
        text.lineSequence().any { it == HEADER_V1 || it == HEADER_V2 || it == HEADER_V3 || it == HEADER_V4 || it == HEADER_V5 }

    private fun humanLine(value: String): String = value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t")

    private fun encodeToken(value: String): String {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun decodeToken(value: String): String {
        require(value.length <= MAX_STRING_BYTES * 2)
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size <= MAX_STRING_BYTES)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun encodeOperation(operation: PlannedOperation): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(BINARY_VERSION)
            when (operation) {
                is PlannedOperation.CreateDirectory -> {
                    out.writeByte(1)
                    out.writeString(FileRefJournalCodec.encode(operation.parent))
                    out.writeString(operation.name)
                    out.writeString(operation.reason)
                }
                is PlannedOperation.Move -> {
                    out.writeByte(2)
                    out.writeString(FileRefJournalCodec.encode(operation.source))
                    out.writeString(FileRefJournalCodec.encode(operation.destination))
                    out.writeString(operation.reason)
                }
                is PlannedOperation.Copy -> {
                    out.writeByte(6)
                    out.writeString(FileRefJournalCodec.encode(operation.source))
                    out.writeString(FileRefJournalCodec.encode(operation.destination))
                    out.writeString(operation.reason)
                }
                is PlannedOperation.Rename -> {
                    out.writeByte(3)
                    out.writeString(FileRefJournalCodec.encode(operation.source))
                    out.writeString(operation.newName)
                    out.writeString(operation.reason)
                }
                is PlannedOperation.Trash -> {
                    out.writeByte(4)
                    out.writeString(FileRefJournalCodec.encode(operation.source))
                    out.writeString(operation.reason)
                    out.writeNullableString(operation.sourceFingerprint)
                }
                is PlannedOperation.WriteTextFile -> {
                    out.writeByte(5)
                    out.writeString(FileRefJournalCodec.encode(operation.parent))
                    out.writeString(operation.name)
                    out.writeString(operation.content)
                    out.writeString(operation.reason)
                }
            }
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray())
    }

    private fun decodeOperation(encoded: String): PlannedOperation {
        val bytes = Base64.getUrlDecoder().decode(encoded)
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            require(input.readInt() == BINARY_VERSION) { "Unsupported durable plan binary version." }
            when (input.readUnsignedByte()) {
                1 -> PlannedOperation.CreateDirectory(
                    parent = FileRefJournalCodec.decode(input.readString()),
                    name = input.readString(),
                    reason = input.readString(),
                )
                2 -> PlannedOperation.Move(
                    source = FileRefJournalCodec.decode(input.readString()),
                    destination = FileRefJournalCodec.decode(input.readString()),
                    reason = input.readString(),
                )
                3 -> PlannedOperation.Rename(
                    source = FileRefJournalCodec.decode(input.readString()),
                    newName = input.readString(),
                    reason = input.readString(),
                )
                4 -> PlannedOperation.Trash(
                    source = FileRefJournalCodec.decode(input.readString()),
                    reason = input.readString(),
                    sourceFingerprint = input.readNullableString(),
                )
                5 -> PlannedOperation.WriteTextFile(
                    parent = FileRefJournalCodec.decode(input.readString()),
                    name = input.readString(),
                    content = input.readString(),
                    reason = input.readString(),
                )
                6 -> PlannedOperation.Copy(
                    source = FileRefJournalCodec.decode(input.readString()),
                    destination = FileRefJournalCodec.decode(input.readString()),
                    reason = input.readString(),
                )
                else -> error("Unknown durable operation type.")
            }
        }
    }

    private fun PlannedOperation.typeLabel(): String = when (this) {
        is PlannedOperation.CreateDirectory -> "CREATE_DIRECTORY"
        is PlannedOperation.Move -> "MOVE"
        is PlannedOperation.Copy -> "COPY"
        is PlannedOperation.Rename -> "RENAME"
        is PlannedOperation.Trash -> "TRASH"
        is PlannedOperation.WriteTextFile -> "WRITE_TEXT_FILE"
    }

    private fun DataOutputStream.writeString(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STRING_BYTES) { "Durable plan string is too large." }
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readString(): String {
        val length = readInt()
        require(length in 0..MAX_STRING_BYTES) { "Invalid durable plan string length." }
        val bytes = ByteArray(length)
        readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun DataOutputStream.writeNullableString(value: String?) {
        writeBoolean(value != null)
        if (value != null) writeString(value)
    }

    private fun DataInputStream.readNullableString(): String? =
        if (readBoolean()) readString() else null
}
