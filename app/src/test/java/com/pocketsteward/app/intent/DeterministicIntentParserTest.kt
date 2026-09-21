package com.pocketsteward.app.intent

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.scan.FileCategory
import org.junit.Test

class DeterministicIntentParserTest {
    @Test
    fun acceptanceRequestParsesAsBoundedOrganizeIntent() {
        val result = DeterministicIntentParser.parse(
            "Organize the obvious files by type. Put APKs together, put PDFs and documents together, " +
                "keep images separate, and leave anything uncertain alone.",
        ) as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.ORGANIZE)
        assertThat(result.intent.categories).containsExactly(
            FileCategory.APK,
            FileCategory.DOCUMENT,
            FileCategory.IMAGE,
        )
        assertThat(result.intent.leaveUncertain).isTrue()
        assertThat(result.intent.groupingMode).isEqualTo(GroupingMode.TYPE)
    }

    @Test
    fun oneMainFolderCreatesExplicitNestedContainerIntent() {
        val result = DeterministicIntentParser.parse(
            "Organize images and documents into one main folder",
        ) as IntentParseResult.Parsed

        assertThat(result.intent.mainFolder).isEqualTo("Organized")
    }

    @Test
    fun quotedDestinationFolderIsCapturedWithoutTrailingProse() {
        val result = DeterministicIntentParser.parse(
            "Group images under \"Media Library\"",
        ) as IntentParseResult.Parsed

        assertThat(result.intent.mainFolder).isEqualTo("Media Library")
    }

    @Test
    fun explicitContainingPhraseBecomesContentSearch() {
        val result = DeterministicIntentParser.parse(
            "find documents containing Lilith",
        ) as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.FIND)
        assertThat(result.intent.categories).containsExactly(FileCategory.DOCUMENT)
        assertThat(result.intent.contentTerm).isEqualTo("Lilith")
        assertThat(result.intent.findTerm).isNull()
    }

    @Test
    fun filenameSearchStaysMetadataOnly() {
        val result = DeterministicIntentParser.parse(
            "find files named invoice",
        ) as IntentParseResult.Parsed

        assertThat(result.intent.contentTerm).isNull()
        assertThat(result.intent.findTerm).isEqualTo("invoice")
    }

    @Test
    fun renameRequiresExplicitFromAndTo() {
        val result = DeterministicIntentParser.parse("rename old.txt to new.txt") as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.RENAME)
        assertThat(result.intent.renameFrom).isEqualTo("old.txt")
        assertThat(result.intent.renameTo).isEqualTo("new.txt")
    }

    @Test
    fun findWithoutCategoryKeepsAReadOnlyFilenameTerm() {
        val result = DeterministicIntentParser.parse("find files named invoice") as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.FIND)
        assertThat(result.intent.findTerm).isEqualTo("invoice")
    }

    @Test
    fun archiveMeansExistingArchiveFormatsNotCompression() {
        val result = DeterministicIntentParser.parse("archive files") as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.ARCHIVE)
        assertThat(result.intent.categories).containsExactly(FileCategory.ARCHIVE)
    }

    @Test
    fun ageQualifierBecomesBoundedCriterion() {
        val now = 2_000_000_000_000L
        val result = DeterministicIntentParser.parse(
            request = "find files older than 30 days",
            previous = null,
            nowMillis = now,
        ) as IntentParseResult.Parsed

        assertThat(result.intent.modifiedBefore).isEqualTo(now - 30L * 24L * 60L * 60L * 1000L)
    }

    @Test
    fun sizeAndOrderCriteriaParse() {
        val result = DeterministicIntentParser.parse("find largest files over 500 MB") as IntentParseResult.Parsed

        assertThat(result.intent.minSizeBytes).isEqualTo(500_000_000L)
        assertThat(result.intent.order).isEqualTo(IntentOrder.LARGEST_FIRST)
        assertThat(result.intent.resultLimit).isEqualTo(50)
    }

    @Test
    fun followUpRefinesPreviousRequestWithoutInventingNewAction() {
        val previous = (DeterministicIntentParser.parse("organize images") as IntentParseResult.Parsed).intent
        val result = DeterministicIntentParser.parse(
            request = "same but include subfolders and only files larger than 10 MB",
            previous = previous,
            nowMillis = 1_000L,
        ) as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.ORGANIZE)
        assertThat(result.intent.includeSubfolders).isTrue()
        assertThat(result.intent.minSizeBytes).isEqualTo(10_000_000L)
    }

    @Test
    fun unknownCommandIsRefused() {
        assertThat(DeterministicIntentParser.parse("make my phone beautiful"))
            .isInstanceOf(IntentParseResult.Unsupported::class.java)
    }
    @Test
    fun moveRequestParsesDestinationCategoryAndWordAge() {
        val now = 2_000_000_000_000L
        val result = DeterministicIntentParser.parse(
            request = "move PDFs older than six months to Documents/Archive/PDF",
            previous = null,
            nowMillis = now,
        ) as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.MOVE)
        assertThat(result.intent.categories).containsExactly(com.pocketsteward.app.scan.FileCategory.DOCUMENT)
        assertThat(result.intent.destinationFolder).isEqualTo("Documents/Archive/PDF")
        assertThat(result.intent.modifiedBefore)
            .isEqualTo(now - 6L * 30L * 24L * 60L * 60L * 1000L)
    }

    @Test
    fun copyNamedFileParsesAsBoundedCopy() {
        val result = DeterministicIntentParser.parse(
            "copy file named invoice.pdf to Documents/Invoices",
        ) as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.COPY)
        assertThat(result.intent.findTerm).isEqualTo("invoice.pdf")
        assertThat(result.intent.destinationFolder).isEqualTo("Documents/Invoices")
    }

    @Test
    fun moveWithoutDestinationIsRefused() {
        val result = DeterministicIntentParser.parse("move PDFs older than six months")

        assertThat(result).isInstanceOf(IntentParseResult.Unsupported::class.java)
    }

    @Test
    fun `batch rename template parses`() {
        val result = DeterministicIntentParser.parse(
            "rename files matching \"IMG_\" to \"Vacation-{n}.{ext}\"",
        ) as IntentParseResult.Parsed

        assertThat(result.intent.action).isEqualTo(IntentAction.RENAME)
        assertThat(result.intent.renameMatchTerm).isEqualTo("IMG_")
        assertThat(result.intent.renameTemplate).isEqualTo("Vacation-{n}.{ext}")
        assertThat(result.intent.renameFrom).isNull()
    }

}
