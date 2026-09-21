package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OrganizationPreferenceCodecTest {
    @Test
    fun destinationsRoundTripPathsAndNames() {
        val input = listOf(
            FavoriteDestination("1", "Writing", "/storage/emulated/0/Documents/Writing"),
            FavoriteDestination("2", "Art = weird", "/storage/emulated/0/Pictures/My Art"),
        )
        assertThat(OrganizationPreferenceCodec.decodeDestinations(
            OrganizationPreferenceCodec.encodeDestinations(input),
        )).containsExactlyElementsIn(input).inOrder()
    }

    @Test
    fun correctionsRoundTrip() {
        val input = listOf(
            CorrectionRule("lilith", "Project Lilith"),
            CorrectionRule("invoice", "Finance"),
        )
        assertThat(OrganizationPreferenceCodec.decodeCorrections(
            OrganizationPreferenceCodec.encodeCorrections(input),
        )).containsExactlyElementsIn(input).inOrder()
    }
}
