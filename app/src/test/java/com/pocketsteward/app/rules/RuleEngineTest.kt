package com.pocketsteward.app.rules

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.scan.FileCategory
import org.junit.Test

class RuleEngineTest {

    @Test
    fun `classifies a known extension with full confidence`() {
        val result = RuleEngine.classify("invoice.pdf", "pdf")

        assertThat(result.category).isEqualTo(FileCategory.DOCUMENT)
        assertThat(result.confidence).isEqualTo(1.0f)
        assertThat(result.matchedRules).containsExactly("extension:pdf")
        assertThat(result.projectFolder).isNull()
    }

    @Test
    fun `unknown extension gets zero confidence, not a guess`() {
        val result = RuleEngine.classify("mystery.xyz", "xyz")

        assertThat(result.category).isEqualTo(FileCategory.OTHER)
        assertThat(result.confidence).isEqualTo(0f)
        assertThat(result.matchedRules).isEmpty()
    }

    @Test
    fun `project keyword wins over a plain extension match`() {
        val keywords = listOf(ProjectKeyword("Leaseworld", "Leaseworld"))
        val result = RuleEngine.classify("Leaseworld-contract.pdf", "pdf", keywords)

        assertThat(result.confidence).isEqualTo(1.0f)
        assertThat(result.projectFolder).isEqualTo("Leaseworld")
        assertThat(result.matchedRules).containsExactly("project-keyword:Leaseworld")
        assertThat(result.reason).contains("Leaseworld")
    }

    @Test
    fun `project keyword match is case-insensitive`() {
        val keywords = listOf(ProjectKeyword("NSTL", "NSTL"))
        val result = RuleEngine.classify("nstl-notes.txt", "txt", keywords)

        assertThat(result.projectFolder).isEqualTo("NSTL")
    }

    @Test
    fun `a keyword that does not appear in the filename does not match`() {
        val keywords = listOf(ProjectKeyword("Aleksei", "Aleksei"))
        val result = RuleEngine.classify("random-report.pdf", "pdf", keywords)

        assertThat(result.projectFolder).isNull()
        assertThat(result.matchedRules).containsExactly("extension:pdf")
    }

    @Test
    fun `first matching keyword wins when several are configured`() {
        val keywords = listOf(
            ProjectKeyword("Erica", "Erica"),
            ProjectKeyword("report", "Reports"),
        )
        val result = RuleEngine.classify("Erica-report.pdf", "pdf", keywords)

        assertThat(result.projectFolder).isEqualTo("Erica")
    }

    @Test
    fun `apk extension classifies as APK regardless of case`() {
        val result = RuleEngine.classify("app.APK", "APK")

        assertThat(result.category).isEqualTo(FileCategory.APK)
        assertThat(result.confidence).isEqualTo(1.0f)
    }
}
