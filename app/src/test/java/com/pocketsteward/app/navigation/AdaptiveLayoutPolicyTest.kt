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
}
