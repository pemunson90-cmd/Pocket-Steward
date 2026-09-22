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
    private const val OP_PREFIX = "@psop\t"
    private const val PRECONDITION_PREFIX = "@pspre\t"
    private const val BINARY_VERSION = 1
    private const val MAX_STRING_BYTES = 16 * 1024 * 1024

    fun encode(
        goal: String,
        operations: List<PlannedOperation>,
        sourcePreconditions: Map<Int, SourcePrecondition> = emptyMap(),
    ): String = buildString {
        appendLine(goal)
        appendLine(HEADER_V2)
        operations.forEachIndexed { sequence, operation ->
            appendLine("$sequence\t${operation.typeLabel()}\t${operation.reason}")
            appendLine("$OP_PREFIX$sequence\t${encodeOperation(operation)}")
            sourcePreconditions[sequence]?.let { precondition ->
                appendLine(
                    "$PRECONDITION_PREFIX$sequence\t${precondition.sizeBytes}\t" +
                        (precondition.modifiedAtEpochMs?.toString() ?: "null"),
                )
            }
        }
    }

    fun decodeOrNull(text: String): DurablePlan? = runCatching {
        val lines = text.lineSequence().toList()
        val version = when {
            HEADER_V2 in lines -> 2
            HEADER_V1 in lines -> 1
            else -> return null
        }

        val indexed = lines.mapNotNull { line ->
            if (!line.startsWith(OP_PREFIX)) return@mapNotNull null
            val fields = line.split('\t', limit = 3)
            require(fields.size == 3) { "Malformed durable operation line." }
            fields[1].toInt() to decodeOperation(fields[2])
        }.sortedBy { it.first }

        require(indexed.isNotEmpty()) { "Durable plan has no operations." }
        require(indexed.map { it.first } == indexed.indices.toList()) {
            "Durable plan operation sequence is not contiguous."
        }

        val preconditions = if (version >= 2) {
            lines.mapNotNull { line ->
                if (!line.startsWith(PRECONDITION_PREFIX)) return@mapNotNull null
                val fields = line.split('\t')
                require(fields.size == 4) { "Malformed source-precondition line." }
                val sequence = fields[1].toInt()
                val sizeBytes = fields[2].toLong()
                val modified = fields[3].takeUnless { it == "null" }?.toLong()
                sequence to SourcePrecondition(sizeBytes, modified)
            }.toMap()
        } else {
            emptyMap()
        }
        require(preconditions.keys.all { it in indexed.indices }) {
            "Source precondition references an operation outside the durable plan."
        }

        DurablePlan(
            goal = lines.firstOrNull().orEmpty(),
            operations = indexed.map { it.second },
            sourcePreconditions = preconditions,
        )
    }.getOrNull()

    fun isDurable(text: String): Boolean =
        text.lineSequence().any { it == HEADER_V1 || it == HEADER_V2 }

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
