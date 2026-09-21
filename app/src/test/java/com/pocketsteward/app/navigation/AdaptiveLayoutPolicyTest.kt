package com.pocketsteward.app.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveLayoutPolicyTest {
    @Test
    fun compactWidthsUseBottomNavigation() {
        assertThat(AdaptiveLayoutPolicy.useExpandedNavigation(599f)).isFalse()
    }

    @Test
    fun foldAndTabletWidthsUseNavigationRail() {
        assertThat(AdaptiveLayoutPolicy.useExpandedNavigation(600f)).isTrue()
        assertThat(AdaptiveLayoutPolicy.useExpandedNavigation(840f)).isTrue()
    }

    @Test
    fun mediumWorkspaceCanUseTwoPanesBeforeNavigationRailThreshold() {
        assertThat(AdaptiveLayoutPolicy.useTwoPane(519f)).isFalse()
        assertThat(AdaptiveLayoutPolicy.useTwoPane(520f)).isTrue()
    }
}
