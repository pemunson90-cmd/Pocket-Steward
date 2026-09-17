package com.pocketsteward.app.dedupe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KeeperSelectorTest {

    @Test
    fun `shallowest path wins`() {
        val candidates = listOf(
            KeeperCandidate("/sd/Download/Archive/Old/report.pdf", 100L, "report.pdf"),
            KeeperCandidate("/sd/Download/report.pdf", 100L, "report.pdf"),
        )

        assertThat(KeeperSelector.keeperIndex(candidates)).isEqualTo(1)
    }

    @Test
    fun `at equal depth the oldest file wins`() {
        val candidates = listOf(
            KeeperCandidate("/sd/Download/b.pdf", 900L, "b.pdf"),
            KeeperCandidate("/sd/Download/a.pdf", 100L, "a.pdf"),
        )

        assertThat(KeeperSelector.keeperIndex(candidates)).isEqualTo(1)
    }

    @Test
    fun `an unknown modification time never beats a known one`() {
        val candidates = listOf(
            KeeperCandidate("/sd/Download/a.pdf", null, "a.pdf"),
            KeeperCandidate("/sd/Download/b.pdf", 500L, "b.pdf"),
        )

        assertThat(KeeperSelector.keeperIndex(candidates)).isEqualTo(1)
    }

    @Test
    fun `the copy-suffixed name loses to the plain one`() {
        val candidates = listOf(
            KeeperCandidate("/sd/Download/report (1).pdf", 100L, "report (1).pdf"),
            KeeperCandidate("/sd/Download/report.pdf", 100L, "report.pdf"),
        )

        assertThat(KeeperSelector.keeperIndex(candidates)).isEqualTo(1)
    }

    @Test
    fun `fully tied candidates fall back to lexical order, so runs agree`() {
        // Equal depth, equal mtime, and equal name *length* — otherwise the
        // name-length step decides and the lexical step never runs.
        val candidates = listOf(
            KeeperCandidate("/sd/Download/zeta.pdf", 100L, "zeta.pdf"),
            KeeperCandidate("/sd/Download/beta.pdf", 100L, "beta.pdf"),
        )

        assertThat(KeeperSelector.keeperIndex(candidates)).isEqualTo(1)
    }

    @Test
    fun `the same group in a different order picks the same keeper`() {
        val a = KeeperCandidate("/sd/Download/Deep/Deeper/x.pdf", 100L, "x.pdf")
        val b = KeeperCandidate("/sd/Download/x.pdf", 500L, "x.pdf")
        val c = KeeperCandidate("/sd/Download/Deep/x.pdf", 50L, "x.pdf")

        val forward = listOf(a, b, c)
        val reversed = listOf(c, b, a)

        assertThat(forward[KeeperSelector.keeperIndex(forward)])
            .isEqualTo(reversed[KeeperSelector.keeperIndex(reversed)])
    }

    @Test
    fun `an empty group is a programming error, not a silent pick`() {
        try {
            KeeperSelector.keeperIndex(emptyList())
            throw AssertionError("expected an IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertThat(expected).hasMessageThat().contains("empty group")
        }
    }
}
