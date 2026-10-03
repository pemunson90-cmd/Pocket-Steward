package com.pocketsteward.app.metadata

import com.google.common.truth.Truth.assertThat
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException
import java.util.zip.CRC32

/**
 * Real RAR4/RAR5/7z fixtures (see archive-fixtures/generate.sh and SHA256SUMS) plus
 * synthetic hostile headers. Counters come from the reader and the JVM, not constants.
 */
class RarSevenZipInspectionTest {
    @get:Rule val folder = TemporaryFolder()

    private val project = listOf(
        "NSTL", "NSTL/NSTL-1.4.0-dev18", "NSTL/NSTL-1.4.0-dev18/empty-dir", "NSTL/NSTL-1.4.0-dev18/notes",
        "NSTL/NSTL-1.4.0-dev18/release", "NSTL/NSTL-1.4.0-dev18/notes/empty.txt", "NSTL/NSTL-1.4.0-dev18/notes/build-notes.txt",
        "NSTL/NSTL-1.4.0-dev18/README.md", "NSTL/NSTL-1.4.0-dev18/release/NSTL-1.4.0-dev18.apk",
    )
    private val projectFixtures = listOf("project-release-rar4.rar", "project-release-rar5.rar", "project-release-rar5-solid.rar",
        "project-release.7z", "project-release-plain-header.7z")

    @Test fun realRarAndSevenZipFixturesListNestedProjectReleaseMembers() {
        for (name in projectFixtures) {
            val result = ArchiveInspector.file(fixture(name), name.substringAfterLast('.'))
            assertThat(result.names).containsExactlyElementsIn(project)
            assertThat(result.observedEntries).isEqualTo(9)
            assertThat(result.complete).isTrue()
            assertThat(result.note).isNull()
            assertThat(result.names).contains("NSTL/NSTL-1.4.0-dev18/release/NSTL-1.4.0-dev18.apk")
        }
    }

    @Test fun sevenZipNamesMatchCommonsCompressListing() {
        for (name in listOf("project-release.7z", "project-release-plain-header.7z", "unicode.7z", "long-name.7z", "payload-64mib-zeros.7z")) {
            val expected = SevenZFile.builder().setFile(fixture(name)).get().use { archive -> archive.entries.map { it.name } }
            val actual = ArchiveInspector.file(fixture(name), "7z")
            assertThat(actual.names).containsExactlyElementsIn(expected.map { it.take(500) }).inOrder()
            assertThat(actual.observedEntries).isEqualTo(expected.size)
        }
    }

    @Test fun unicodeNamesDecodeInRar4CompactEncodingRar5AndSevenZip() {
        val expected = listOf("Проект", "Проект/データ", "Проект/データ/заметки-ノート.txt")
        for (name in listOf("unicode-rar4.rar", "unicode-rar5.rar", "unicode.7z")) {
            val result = ArchiveInspector.file(fixture(name), name.substringAfterLast('.'))
            assertThat(result.names).containsExactlyElementsIn(expected)
            assertThat(result.complete).isTrue()
        }
    }

    @Test fun inspectionReadsHeadersOnlyAndNeverDecodesThe64MibPayload() {
        for (name in listOf("payload-64mib-zeros-rar5.rar", "payload-64mib-zeros.7z")) {
            val file = fixture(name)
            val bytes = file.readBytes()
            val channel = CountingChannel(bytes)
            var reader: BoundedArchiveReader? = null
            ArchiveInspector.channel(CountingChannel(bytes), name.substringAfterLast('.'), ArchiveLimits(), ::interruptCheck) // Warm-up: class loading is not inspection work.
            var result: ArchiveInspection? = null
            val allocated = allocatedBy {
                result = ArchiveInspector.channel(channel, name.substringAfterLast('.'), ArchiveLimits(), ::interruptCheck, observe = { reader = it })
            }
            assertThat(result?.names).containsExactly("Bomb", "Bomb/zeros.bin")
            assertThat(result?.complete).isTrue()
            // The compressed payload is most of the file; only header bytes are read.
            assertThat(channel.bytesRead).isLessThan(1_024L)
            assertThat(channel.bytesRead).isLessThan(bytes.size / 3L)
            assertThat(reader?.bytesRead).isEqualTo(channel.bytesRead)
            if (allocated >= 0) assertThat(allocated).isLessThan(256L * 1024) // Decoding would touch 64 MiB.
        }
    }

    @Test fun inspectionCreatesNoFilesBesideTheArchive() {
        val directory = folder.newFolder("downloads")
        projectFixtures.forEach { fixture(it).copyTo(File(directory, it)) }
        val before = directory.walk().map { it.relativeTo(directory).path to it.length() }.toSet()
        projectFixtures.forEach { ArchiveInspector.file(File(directory, it), it.substringAfterLast('.')) }
        assertThat(directory.walk().map { it.relativeTo(directory).path to it.length() }.toSet()).isEqualTo(before)
    }

    @Test fun entryAndNameLimitsReportPartialCoverage() {
        for (name in listOf("entries-2001-rar4.rar", "entries-2001-rar5.rar", "entries-2001.7z")) {
            val result = ArchiveInspector.file(fixture(name), name.substringAfterLast('.'))
            assertThat(result.observedEntries).isEqualTo(2_000)
            assertThat(result.names).hasSize(40)
            assertThat(result.complete).isFalse()
            assertThat(result.note).contains("Entry limit")
        }
        assertThat(ArchiveInspector.file(fixture("entries-2001.7z"), "7z").note).contains("declares 2002 entries")
        for (name in listOf("long-name-rar5.rar", "long-name.7z")) {
            val result = ArchiveInspector.file(fixture(name), name.substringAfterLast('.'))
            assertThat(result.names.maxOf { it.length }).isEqualTo(500)
            assertThat(result.names.filter { it.length == 500 }.all { it.startsWith("Long/segment01-") }).isTrue()
        }
        val sevenZip = ArchiveInspector.channel(SeekableInMemoryByteChannel(fixture("project-release.7z").readBytes()), "7z",
            ArchiveLimits(maxEntries = 3, maxSample = 2, maxNameChars = 6), ::interruptCheck)
        assertThat(sevenZip.observedEntries).isEqualTo(3)
        assertThat(sevenZip.names).containsExactly("NSTL", "NSTL/N").inOrder()
        assertThat(sevenZip.complete).isFalse()
    }

    @Test fun encryptedHeadersAreExplicitAndEncryptedContentsStillListNames() {
        for (name in listOf("encrypted-headers-rar4.rar", "encrypted-headers-rar5.rar", "encrypted-headers.7z")) {
            val result = ArchiveInspector.file(fixture(name), name.substringAfterLast('.'))
            assertThat(result.names).isEmpty()
            assertThat(result.complete).isFalse()
            assertThat(result.note).contains("headers are encrypted")
        }
        for (name in listOf("encrypted-content-rar4.rar", "encrypted-content-rar5.rar", "encrypted-content.7z")) {
            val result = ArchiveInspector.file(fixture(name), name.substringAfterLast('.'))
            assertThat(result.names).containsExactlyElementsIn(project)
            assertThat(result.complete).isTrue()
            assertThat(result.note).contains("contents are encrypted")
        }
    }

    @Test fun multipartVolumesNeverClaimACompleteInventory() {
        for (name in listOf("multipart-rar4.rar", "multipart-rar5.part1.rar")) {
            val result = ArchiveInspector.file(fixture(name), "rar")
            assertThat(result.names).containsExactly("Volumes/filler.bin")
            assertThat(result.complete).isFalse()
            assertThat(result.note).contains("multipart")
        }
        // A 7z split part keeps its header in the last part, so the first part is truncated.
        val firstPart = fixture("project-release.7z").readBytes().let { it.copyOf(it.size / 2) }
        val split = inspect(firstPart, "7z")
        assertThat(split.complete).isFalse()
        assertThat(split.note).contains("split")
    }

    @Test fun unsupportedVersionsAreNamed() {
        assertThat(inspect("RE~^".toByteArray() + ByteArray(32), "rar").note).contains("RAR 1.4")
        assertThat(inspect(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x02, 0x00) + ByteArray(32), "rar").note).contains("newer")
        assertThat(inspect(ByteArray(64), "rar").note).contains("No RAR signature")
        val futureSevenZip = fixture("project-release.7z").readBytes().also { it[6] = 1 }
        assertThat(inspect(futureSevenZip, "7z").note).contains("version 1.")
        assertThat(inspect(ByteArray(64), "7z").note).contains("No 7z signature")
    }

    @Test fun everyTruncationIsPartialAndNeverThrows() {
        for (name in projectFixtures + listOf("unicode-rar4.rar", "unicode-rar5.rar", "encrypted-content.7z")) {
            val bytes = fixture(name).readBytes()
            val full = ArchiveInspector.file(fixture(name), name.substringAfterLast('.')).names.toSet()
            for (length in 0 until bytes.size) {
                val result = inspect(bytes.copyOf(length), name.substringAfterLast('.'))
                assertThat(result.complete).isFalse()
                assertThat(result.note).isNotNull()
                assertThat(full).containsAtLeastElementsIn(result.names)
            }
        }
    }

    @Test fun everySingleByteCorruptionEitherFailsVisiblyOrListsTheTrueNames() {
        for (name in projectFixtures + listOf("unicode-rar4.rar", "unicode.7z")) {
            val bytes = fixture(name).readBytes()
            val ext = name.substringAfterLast('.')
            val full = ArchiveInspector.file(fixture(name), ext)
            for (index in bytes.indices) {
                val damaged = bytes.copyOf().also { it[index] = (it[index].toInt() xor 0x5A).toByte() }
                var reader: BoundedArchiveReader? = null
                val result = ArchiveInspector.channel(SeekableInMemoryByteChannel(damaged), ext, ArchiveLimits(), ::interruptCheck, observe = { reader = it })
                // Packed member bytes are not covered by header checksums and are never read,
                // so a complete result must be exactly the original listing.
                if (result.complete) assertThat(result).isEqualTo(full) else assertThat(result.note).isNotNull()
                assertThat(reader?.bytesRead).isAtMost(ArchiveLimits().maxHeaderReadBytes)
            }
        }
    }

    @Test fun seededMultiByteFuzzNeverEscapesBudgetsOrClaimsAFalseInventory() {
        val random = java.util.Random(20261003)
        val limits = ArchiveLimits()
        for (name in projectFixtures + listOf("entries-2001-rar4.rar", "entries-2001.7z", "encrypted-headers.7z", "multipart-rar5.part1.rar")) {
            val bytes = fixture(name).readBytes()
            val ext = name.substringAfterLast('.')
            val full = ArchiveInspector.file(fixture(name), ext)
            repeat(300) {
                val damaged = bytes.copyOf()
                repeat(1 + random.nextInt(8)) { damaged[random.nextInt(damaged.size)] = random.nextInt(256).toByte() }
                val cut = if (random.nextBoolean()) damaged.copyOf(random.nextInt(damaged.size + 1)) else damaged
                var reader: BoundedArchiveReader? = null
                val result = ArchiveInspector.channel(SeekableInMemoryByteChannel(cut), ext, limits, ::interruptCheck, observe = { reader = it })
                if (result.complete) assertThat(result).isEqualTo(full)
                assertThat(result.observedEntries).isAtMost(limits.maxEntries)
                assertThat(result.names.size).isAtMost(limits.maxSample)
                assertThat(result.names.all { it.length <= limits.maxNameChars }).isTrue()
                reader?.let { assertThat(it.bytesRead).isAtMost(limits.maxHeaderReadBytes) }
            }
        }
    }

    @Test fun hostileSevenZipHeaderLengthsAreRejectedBeforeAllocation() {
        val real = fixture("project-release-plain-header.7z").readBytes()
        val header = real.copyOfRange(32 + real.u64(12).toInt(), real.size)
        // Declared next header of 1 TiB at a valid offset.
        val huge = sevenZip(header, nextHeaderSize = 1L shl 40)
        var reader: BoundedArchiveReader? = null
        val result = ArchiveInspector.channel(SeekableInMemoryByteChannel(huge), "7z", ArchiveLimits(), ::interruptCheck, observe = { reader = it })
        assertThat(result.complete).isFalse()
        assertThat(reader?.largestRead).isAtMost(32)
        // A real header larger than the read budget is reported, not read.
        val tight = ArchiveInspector.channel(SeekableInMemoryByteChannel(real), "7z", ArchiveLimits(maxHeaderReadBytes = 256), ::interruptCheck, observe = { reader = it })
        assertThat(tight.note).contains("byte limit")
        assertThat(reader?.bytesRead).isAtMost(256L)
        // Negative/overflowing offset.
        assertThat(inspect(sevenZip(header, nextHeaderOffset = Long.MAX_VALUE), "7z").complete).isFalse()
    }

    @Test fun encodedSevenZipHeaderDecodingIsBoundedByOutputAndDictionary() {
        val real = fixture("project-release-plain-header.7z").readBytes()
        val plain = real.copyOfRange(32 + real.u64(12).toInt(), real.size)
        val (props, dictionary, packed) = lzma(plain)
        // Honest declaration decodes and lists names.
        val honest = inspect(encodedSevenZip(packed, props, dictionary, plain.size.toLong(), crc(plain)), "7z")
        assertThat(honest.names).containsExactlyElementsIn(project)
        assertThat(honest.complete).isTrue()
        // A 4 GiB dictionary and 1 TiB unpack size: memory stays near the 8 MiB decoded budget.
        val bomb = encodedSevenZip(packed, props, 0xFFFF_FFF0L, 1L shl 40, null)
        inspect(bomb, "7z") // Warm-up.
        var bombed: ArchiveInspection? = null
        val allocated = allocatedBy { bombed = inspect(bomb, "7z") }
        assertThat(bombed?.complete).isFalse()
        // xz allocates the dictionary up front; it is capped at the 8 MiB decoded budget instead of the
        // declared 4 GiB, and the output buffer grows only with bytes actually decoded.
        if (allocated >= 0) assertThat(allocated).isLessThan(ArchiveLimits().maxDecodedBytes + 1L * 1024 * 1024)
        // A decoded budget smaller than the header gives a truthful partial listing.
        val partial = ArchiveInspector.channel(SeekableInMemoryByteChannel(encodedSevenZip(packed, props, dictionary, plain.size.toLong(), crc(plain))), "7z",
            ArchiveLimits(maxDecodedBytes = plain.size - 40L), ::interruptCheck)
        assertThat(partial.complete).isFalse()
        assertThat(partial.note).contains("decoded budget")
        assertThat(project).containsAtLeastElementsIn(partial.names)
        // Unsupported header filters are named.
        val deflate = encodedSevenZip(packed, props, dictionary, plain.size.toLong(), null, coderId = byteArrayOf(0x04, 0x01, 0x08))
        assertThat(inspect(deflate, "7z").note).contains("compression method 040108")
    }

    @Test fun hostileRarHeadersStayWithinReadAndBlockBudgets() {
        // Packed size of 2^62 jumps past the end: truncated, never a seek-read.
        val farJump = rar5(rar5File("NSTL/a.txt", dataSize = 1L shl 62))
        val jump = inspect(farJump, "rar")
        assertThat(jump.names).containsExactly("NSTL/a.txt")
        assertThat(jump.complete).isFalse()
        // Header size beyond the RAR5 2 MiB limit.
        val oversized = RAR5_SIGNATURE + byteArrayOf(0, 0, 0, 0) + vint(3L * 1024 * 1024) + ByteArray(16)
        assertThat(inspect(oversized, "rar").note).contains("damaged")
        // Name length larger than its header.
        val badName = rar5Block(byteArrayOf(2, 0) + vint(0) + vint(0) + vint(0) + vint(0) + vint(1) + vint(500) + "x".toByteArray())
        assertThat(inspect(RAR5_SIGNATURE + rar5Block(byteArrayOf(1, 0, 0)) + badName, "rar").note).contains("damaged")
        // Endless service headers are cut by the block limit with reads bounded.
        val service = rar5Block(byteArrayOf(3, 0) + vint(0) + vint(0) + vint(0) + vint(0) + vint(1) + vint(2) + "QO".toByteArray())
        val flood = ByteArrayOutputStream().also { out ->
            out.write(RAR5_SIGNATURE); out.write(rar5Block(byteArrayOf(1, 0, 0)))
            repeat(25_000) { out.write(service) }
        }.toByteArray()
        var reader: BoundedArchiveReader? = null
        val flooded = ArchiveInspector.channel(SeekableInMemoryByteChannel(flood), "rar", ArchiveLimits(), ::interruptCheck, observe = { reader = it })
        assertThat(flooded.complete).isFalse()
        assertThat(flooded.note).contains("byte limit")
        // At most two positional reads per visited header (prefix, then the rest).
        assertThat(reader?.readCalls).isAtMost(2 * ArchiveLimits().maxBlocks + 2)
        assertThat(reader?.bytesRead).isLessThan(flood.size.toLong())
        // RAR4 block shorter than its fixed header.
        assertThat(inspect(byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00, 0, 0, 0x73, 0, 0, 3, 0), "rar").note).contains("damaged")
        // Tight read budget on a real archive.
        val tight = ArchiveInspector.channel(SeekableInMemoryByteChannel(fixture("entries-2001-rar5.rar").readBytes()), "rar",
            ArchiveLimits(maxHeaderReadBytes = 4_096), ::interruptCheck, observe = { reader = it })
        assertThat(tight.note).contains("byte limit")
        assertThat(tight.observedEntries).isGreaterThan(0)
        assertThat(reader?.bytesRead).isAtMost(4_096L)
    }

    @Test fun nonSeekableSourcesStageOneOwnedTemporaryFileAndRemoveIt() {
        val staging = folder.newFolder("app-cache", ArchiveStaging.DIRECTORY_NAME)
        val unrelated = File(staging, "keep-me").apply { writeText("not owned by inspection") }
        for (name in projectFixtures) {
            val bytes = fixture(name).readBytes()
            val input = OneWayInput(bytes)
            val result = ArchiveInspector.stream(input, name.substringAfterLast('.'), staging)
            assertThat(result).isEqualTo(ArchiveInspector.file(fixture(name), name.substringAfterLast('.')))
            assertThat(input.consumed).isEqualTo(bytes.size.toLong())
            assertThat(staging.list()!!.toList()).containsExactly("keep-me")
        }
        assertThat(unrelated.readText()).isEqualTo("not owned by inspection")
    }

    @Test fun stagingBudgetLimitsCopiedBytesAndReportsPartialRarCoverage() {
        val staging = folder.newFolder("staging")
        val bytes = fixture("entries-2001-rar5.rar").readBytes()
        val input = OneWayInput(bytes)
        val result = ArchiveInspector.stream(input, "rar", staging, ArchiveLimits(maxStagedBytes = 2_000), ::interruptCheck)
        assertThat(input.consumed).isAtMost(2_001L)
        assertThat(result.observedEntries).isGreaterThan(0)
        assertThat(result.complete).isFalse()
        assertThat(result.note).contains("byte limit")
        assertThat(staging.list()!!.toList()).isEmpty()
        // A 7z header lives at the end; an oversized non-seekable 7z is not staged at all.
        val sevenZip = OneWayInput(fixture("project-release.7z").readBytes())
        val skipped = ArchiveInspector.stream(sevenZip, "7z", staging, ArchiveLimits(maxStagedBytes = 100), ::interruptCheck, declaredSize = 369)
        assertThat(sevenZip.consumed).isEqualTo(0L)
        assertThat(skipped.note).contains("staging budget")
        val prefix = ArchiveInspector.stream(OneWayInput(fixture("project-release.7z").readBytes()), "7z", staging, ArchiveLimits(maxStagedBytes = 100), ::interruptCheck)
        assertThat(prefix.complete).isFalse()
        assertThat(prefix.note).contains("byte limit")
        assertThat(staging.list()!!.toList()).isEmpty()
    }

    @Test fun stagingCleansUpOnCancellationAndExceptions() {
        val staging = folder.newFolder("staging")
        val bytes = fixture("entries-2001-rar5.rar").readBytes()
        var calls = 0
        assertThrows(CancellationException::class.java) {
            ArchiveInspector.stream(OneWayInput(bytes, chunk = 512), "rar", staging, ArchiveLimits(), { if (++calls == 20) throw CancellationException("stop") })
        }
        assertThat(staging.list()!!.toList()).isEmpty()
        val failed = ArchiveInspector.stream(OneWayInput(bytes, failAfter = 10_000), "rar", staging)
        assertThat(failed.complete).isFalse()
        assertThat(failed.note).contains("could not be staged")
        assertThat(staging.list()!!.toList()).isEmpty()
        // Cancellation during parsing of the staged copy also removes it.
        calls = 0
        assertThrows(CancellationException::class.java) {
            ArchiveInspector.stream(OneWayInput(bytes), "rar", staging, ArchiveLimits(), { if (++calls > 200) throw CancellationException("stop") })
        }
        assertThat(staging.list()!!.toList()).isEmpty()
    }

    @Test fun cancellationAndInterruptionPropagateInsteadOfBecomingUnreadable() {
        for (name in projectFixtures + "payload-64mib-zeros.7z") {
            var calls = 0
            assertThrows(CancellationException::class.java) {
                ArchiveInspector.channel(SeekableInMemoryByteChannel(fixture(name).readBytes()), name.substringAfterLast('.'),
                    ArchiveLimits(), { if (++calls == 3) throw CancellationException("stop") })
            }
        }
        Thread.currentThread().interrupt()
        try {
            assertThrows(CancellationException::class.java) { ArchiveInspector.file(fixture("project-release-rar5.rar"), "rar") }
        } finally { Thread.interrupted() }
        assertThrows(CancellationException::class.java) {
            ArchiveInspector.stream(fixture("project-release-rar4.rar").inputStream(), "zip") { throw CancellationException("stop") }
        }
    }

    @Test fun rar5UnsignedIntegerOverflowCannotWrapIntoACompleteHeader() {
        // A tenth vint byte containing 2 used to wrap out of a signed Long.
        val overflowingFlags = ByteArray(9) { 0x80.toByte() } + byteArrayOf(2)
        val archive = rar5(rar5Block(byteArrayOf(1, 0) + overflowingFlags), rar5Block(byteArrayOf(5, 0, 0)))
        val result = inspect(archive, "rar")
        assertThat(result.complete).isFalse()
        assertThat(result.note).contains("integer out of range")
    }

    // ---- helpers ----

    private fun fixture(name: String): File = File(requireNotNull(javaClass.classLoader!!.getResource("archive-fixtures/$name")) { name }.toURI())

    private fun inspect(bytes: ByteArray, extension: String) =
        ArchiveInspector.channel(SeekableInMemoryByteChannel(bytes), extension, ArchiveLimits(), ::interruptCheck)

    private fun allocatedBy(block: () -> Unit): Long {
        // Android's compile stubs omit the HotSpot extension. Reflect on its
        // public host interface so the measurement still runs in JVM tests.
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val api = runCatching { Class.forName("com.sun.management.ThreadMXBean") }.getOrNull()
        if (api == null || !api.isInstance(bean) || api.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) {
            block(); return -1
        }
        api.getMethod("setThreadAllocatedMemoryEnabled", Boolean::class.javaPrimitiveType).invoke(bean, true)
        val measurement = api.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val threadId = Thread.currentThread().id
        val before = measurement.invoke(bean, threadId) as Long
        block()
        return (measurement.invoke(bean, threadId) as Long) - before
    }

    private class CountingChannel(bytes: ByteArray) : SeekableInMemoryByteChannel(bytes) {
        var bytesRead = 0L
        override fun read(buffer: ByteBuffer): Int = super.read(buffer).also { if (it > 0) bytesRead += it }
    }

    /** Non-seekable source: no mark/reset, no channel, optional failure. */
    private class OneWayInput(private val bytes: ByteArray, private val chunk: Int = 8_192, private val failAfter: Long = Long.MAX_VALUE) : InputStream() {
        var consumed = 0L
        override fun read(): Int {
            if (consumed >= failAfter) throw IOException("provider failed")
            return if (consumed >= bytes.size) -1 else bytes[consumed++.toInt()].toInt() and 0xFF
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (consumed >= failAfter) throw IOException("provider failed")
            if (consumed >= bytes.size) return -1
            val count = minOf(length, chunk, bytes.size - consumed.toInt())
            System.arraycopy(bytes, consumed.toInt(), buffer, offset, count)
            consumed += count
            return count
        }
        override fun markSupported() = false
    }

    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value
    private fun le(value: Long, size: Int) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array().copyOf(size)

    private fun sevenZip(nextHeader: ByteArray, body: ByteArray = ByteArray(0), nextHeaderOffset: Long = body.size.toLong(),
                         nextHeaderSize: Long = nextHeader.size.toLong()): ByteArray {
        val start = le(nextHeaderOffset, 8) + le(nextHeaderSize, 8) + le(crc(nextHeader), 4)
        return byteArrayOf(0x37, 0x7A, 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C, 0, 4) + le(crc(start), 4) + start + body + nextHeader
    }

    private fun number(value: Long) = if (value in 0..0x7F) byteArrayOf(value.toByte()) else byteArrayOf(0xFF.toByte()) + le(value, 8)

    private fun encodedSevenZip(packed: ByteArray, props: Byte, dictionary: Long, unpackSize: Long, headerCrc: Long?,
                                coderId: ByteArray = byteArrayOf(0x03, 0x01, 0x01)): ByteArray {
        val coderProps = byteArrayOf(props) + le(dictionary, 4)
        val encoded = byteArrayOf(0x17, 0x06) + number(0) + number(1) + byteArrayOf(0x09) + number(packed.size.toLong()) + byteArrayOf(0x00,
            0x07, 0x0B) + number(1) + byteArrayOf(0x00) + number(1) + byteArrayOf((coderId.size or 0x20).toByte()) + coderId +
            number(coderProps.size.toLong()) + coderProps + byteArrayOf(0x0C) + number(unpackSize) +
            (if (headerCrc != null) byteArrayOf(0x0A, 0x01) + le(headerCrc, 4) else ByteArray(0)) + byteArrayOf(0x00, 0x00)
        return sevenZip(encoded, packed)
    }

    private fun lzma(data: ByteArray): Triple<Byte, Long, ByteArray> {
        val options = LZMA2Options().apply { dictSize = 1 shl 16 }
        val out = ByteArrayOutputStream()
        val encoder = LZMAOutputStream(out, options, false)
        val props = encoder.props.toByte()
        encoder.use { it.write(data) }
        return Triple(props, options.dictSize.toLong(), out.toByteArray())
    }

    private fun vint(value: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var rest = value
        do {
            val low = (rest and 0x7F).toInt()
            rest = rest ushr 7
            out.write(if (rest != 0L) low or 0x80 else low)
        } while (rest != 0L)
        return out.toByteArray()
    }

    private fun rar5Block(body: ByteArray): ByteArray {
        val sized = vint(body.size.toLong()) + body
        return le(crc(sized), 4) + sized
    }

    private fun rar5File(name: String, dataSize: Long): ByteArray {
        val nameBytes = name.toByteArray()
        return rar5Block(byteArrayOf(2, 2) + vint(dataSize) + vint(0) + vint(0) + vint(0) + vint(0) + vint(1) + vint(nameBytes.size.toLong()) + nameBytes)
    }

    private fun rar5(vararg blocks: ByteArray): ByteArray =
        blocks.fold(RAR5_SIGNATURE + rar5Block(byteArrayOf(1, 0, 0))) { all, block -> all + block } + rar5Block(byteArrayOf(5, 0, 0))

    private companion object {
        val RAR5_SIGNATURE = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x01, 0x00)
    }
}
