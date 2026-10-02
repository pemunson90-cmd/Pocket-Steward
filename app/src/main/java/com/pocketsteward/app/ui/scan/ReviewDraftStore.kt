package com.pocketsteward.app.ui.scan

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializer
import com.pocketsteward.app.plan.DurablePlanCodec
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.plan.ReviewedSources
import com.pocketsteward.app.storage.FileRef
import com.pocketsteward.app.storage.FileRefJournalCodec
import com.pocketsteward.app.storage.StorageAccessMode
import com.pocketsteward.app.storage.rawValue
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** An unapproved draft. Never a durable task and never replayed automatically. */
internal data class ReviewDraft(
    val version: Int = 1,
    val mode: StorageAccessMode,
    val preview: ScanUiState.PlanPreview,
    val filingSession: FilingSession? = null,
)

internal object ReviewDraftCodec {
    const val MAX_BYTES = 128 * 1024 * 1024
    private val gson = GsonBuilder()
        .registerTypeHierarchyAdapter(FileRef::class.java, JsonSerializer<FileRef> { ref, _, _ ->
            JsonPrimitive(FileRefJournalCodec.encode(ref))
        })
        .registerTypeHierarchyAdapter(FileRef::class.java, JsonDeserializer<FileRef> { value, _, _ ->
            FileRefJournalCodec.decode(value.asString)
        })
        .registerTypeHierarchyAdapter(PlannedOperation::class.java, JsonSerializer<PlannedOperation> { op, _, _ ->
            JsonPrimitive(DurablePlanCodec.encode("Draft operation", listOf(op)))
        })
        .registerTypeHierarchyAdapter(PlannedOperation::class.java, JsonDeserializer<PlannedOperation> { value, _, _ ->
            requireNotNull(DurablePlanCodec.decodeOrNull(value.asString)).operations.single()
        })
        .create()

    fun encode(draft: ReviewDraft): ByteArray {
        validate(draft)
        val payload = gson.toJson(draft).toByteArray(Charsets.UTF_8)
        require(payload.size <= MAX_BYTES) { "Review is too large to save." }
        val checksum = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        return ("PSREVIEW1\n$checksum\n").toByteArray() + payload
    }

    fun decode(bytes: ByteArray): ReviewDraft? = runCatching {
        require(bytes.size <= MAX_BYTES + 128)
        val headerEnd = bytes.indexOf('\n'.code.toByte())
        require(headerEnd == 9 && String(bytes, 0, headerEnd) == "PSREVIEW1")
        val checksumEnd = headerEnd + 65
        require(bytes.size > checksumEnd && bytes[checksumEnd] == '\n'.code.toByte())
        val expected = String(bytes, headerEnd + 1, 64)
        val payload = bytes.copyOfRange(checksumEnd + 1, bytes.size)
        val actual = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        require(expected == actual)
        gson.fromJson(String(payload, Charsets.UTF_8), ReviewDraft::class.java).also(::validate)
    }.getOrNull()

    fun write(file: File, draft: ReviewDraft) {
        validate(draft)
        java.io.FileOutputStream(file).use { stream ->
            stream.write(("PSREVIEW1\n" + "0".repeat(64) + "\n").toByteArray())
            val digest = MessageDigest.getInstance("SHA-256")
            val bounded = object : java.io.OutputStream() {
                var count = 0L
                override fun write(value: Int) { require(++count <= MAX_BYTES); stream.write(value) }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    count += length
                    require(count <= MAX_BYTES) { "Review is too large to save." }
                    stream.write(bytes, offset, length)
                }
            }
            val writer = java.io.OutputStreamWriter(java.security.DigestOutputStream(bounded, digest), Charsets.UTF_8)
            gson.toJson(draft, writer)
            writer.flush()
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            stream.channel.position(10)
            stream.write(checksum.toByteArray())
            stream.fd.sync()
        }
    }

    fun read(file: File): ReviewDraft? = runCatching {
        require(file.length() <= MAX_BYTES + 128)
        file.inputStream().use { input ->
            val header = ByteArray(75)
            java.io.DataInputStream(input).readFully(header)
            require(String(header, 0, 10) == "PSREVIEW1\n" && header[74] == '\n'.code.toByte())
            val expected = String(header, 10, 64)
            val digest = MessageDigest.getInstance("SHA-256")
            val reader = java.io.InputStreamReader(java.security.DigestInputStream(input, digest), Charsets.UTF_8)
            val draft = gson.fromJson(reader, ReviewDraft::class.java)
            require(expected == digest.digest().joinToString("") { "%02x".format(it) })
            validate(draft)
            draft
        }
    }.getOrNull()

    private fun validate(draft: ReviewDraft) {
        require(draft.version == 1)
        require(draft.preview.storageMode == draft.mode)
        require(draft.preview.taskHistoryWatermark >= 0)
        val preview = draft.preview
        require(preview.scopes.isNotEmpty() && preview.scopes.size <= 100)
        require(preview.accepted.size <= 100_000 && preview.rejected.size <= 100_000)
        require(preview.pendingCorrections.orEmpty().size <= 100)
        preview.pendingCorrections.orEmpty().forEach { request ->
            require(com.pocketsteward.app.saved.CorrectionRulePolicy.clean(request.rule) == request.rule)
            require(request.sourceDestinations.size in 1..100_000)
            require(request.sourceDestinations.keys.all { it in preview.reviewedSources })
        }
        require(preview.acceptedScopeLabels.size == preview.accepted.size)
        require(preview.selectedIndices.all { it in preview.accepted.indices })
        require(preview.scopes.map { it.root.rawValue() }.distinct().size == preview.scopes.size)
        // A draft must not lose the original review baseline and recapture changed files later.
        require(preview.accepted.mapNotNull(ReviewedSources::sourceOf).all { it.rawValue() in preview.reviewedSources })
        preview.reviewedSources.values.forEach { require(it.sizeBytes >= 0 && it.directoryEntryCount >= 0) }
        require(ReviewDraftPolicy.matchesFilingSession(preview, draft.filingSession))
        draft.filingSession?.let { session ->
            require(preview.filingPresentation != null)
            require(session.result.decisions.size <= 100_000)
            require(session.result.decisions.map { it.artifact.stableRef }.distinct().size == session.result.decisions.size)
        }
        // Gson bypasses constructors: run the preview's constructor invariants explicitly.
        preview.copy()
    }
}

/** Atomic private file, deliberately outside Android backup and user storage. */
internal class ReviewDraftStore(private val file: File) {
    @Synchronized fun save(draft: ReviewDraft?, isCurrent: () -> Boolean = { true }) {
        if (!isCurrent()) return
        if (draft == null) {
            Files.deleteIfExists(file.toPath())
            return
        }
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val temporary = File(file.parentFile, file.name + ".pending")
        try {
            ReviewDraftCodec.write(temporary, draft)
            if (!isCurrent()) return
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }

    @Synchronized fun load(): ReviewDraft? {
        if (!file.isFile) return null
        if (file.length() > ReviewDraftCodec.MAX_BYTES + 128) { file.delete(); return null }
        val draft = ReviewDraftCodec.read(file)
        if (draft == null) file.delete()
        return draft
    }
}

internal object ReviewDraftPolicy {
    fun matchesFilingSession(preview: ScanUiState.PlanPreview?, session: FilingSession?): Boolean {
        val filing = preview?.filingPresentation ?: return true
        return session != null && filing.reviewSessionId == session.reviewId
    }

    fun hasCurrentAccess(preview: ScanUiState.PlanPreview, mode: StorageAccessMode?, grant: String?): Boolean =
        preview.storageMode == mode && mode != null && (mode != StorageAccessMode.SAF || (grant != null && preview.storageGrantIdentity == grant))

    fun wasQueued(preview: ScanUiState.PlanPreview, tasks: List<Pair<Long, List<PlannedOperation>>>): Boolean {
        val selected = com.pocketsteward.app.plan.PlanSelection.selectedOperations(preview.accepted, preview.selectedIndices)
        return selected.isNotEmpty() && tasks.any { (id, operations) -> id > preview.taskHistoryWatermark && operations == selected }
    }
}
