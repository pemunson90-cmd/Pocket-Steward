package com.pocketsteward.app.rules

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UncategorizedTest {

    @Test
    fun `an unknown extension is uncategorized`() {
        assertThat(RuleEngine.classify("mystery.xyz", "xyz").isUncategorized()).isTrue()
    }

    @Test
    fun `a known extension is not uncategorized`() {
        assertThat(RuleEngine.classify("invoice.pdf", "pdf").isUncategorized()).isFalse()
    }

    @Test
    fun `a project keyword rescues an otherwise unknown extension`() {
        val keywords = listOf(ProjectKeyword("Leaseworld", "Leaseworld"))

        assertThat(RuleEngine.classify("Leaseworld-notes.xyz", "xyz", keywords).isUncategorized()).isFalse()
    }

    @Test
    fun `uncategorized is exactly what the cleanup generator skips`() {
        // The generator's own rule is `confidence < 1.0f -> skip`. If these
        // two ever disagree, the "review uncategorized" screen starts lying
        // about what Smart cleanup will ignore.
        val unknown = RuleEngine.classify("mystery.xyz", "xyz")

        assertThat(unknown.isUncategorized()).isEqualTo(unknown.confidence < 1.0f)
    }
}
