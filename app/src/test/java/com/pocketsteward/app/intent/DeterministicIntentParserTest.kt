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
    fun unsupportedAgeQualifierIsRefusedRatherThanIgnored() {
        assertThat(DeterministicIntentParser.parse("organize old files"))
            .isInstanceOf(IntentParseResult.Unsupported::class.java)
    }

    @Test
    fun unknownCommandIsRefused() {
        assertThat(DeterministicIntentParser.parse("make my phone beautiful"))
            .isInstanceOf(IntentParseResult.Unsupported::class.java)
    }
}
