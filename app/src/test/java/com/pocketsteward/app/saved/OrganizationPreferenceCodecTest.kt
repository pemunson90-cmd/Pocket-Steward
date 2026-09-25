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

    @Test
    fun inboxRootsRoundTrip() {
        val input = listOf(
            InboxRoot("1", "Downloads", "/storage/emulated/0/Download"),
            InboxRoot("2", "Incoming", "/storage/emulated/0/Incoming"),
        )
        assertThat(OrganizationPreferenceCodec.decodeInboxRoots(
            OrganizationPreferenceCodec.encodeInboxRoots(input),
        )).containsExactlyElementsIn(input).inOrder()
    }

    @Test
    fun projectHomesRoundTripAliasesPackagesAndHierarchy() {
        val input = listOf(
            ProjectHome(
                id = "lilith",
                name = "Lilith Companion App",
                path = "/storage/emulated/0/Lilith Companion App",
                aliases = listOf("LilithCompanion", "Lilith"),
                packageIds = listOf("com.example.lilith"),
                hierarchy = ProjectHierarchy.VERSIONED,
            ),
        )
        assertThat(OrganizationPreferenceCodec.decodeProjectHomes(
            OrganizationPreferenceCodec.encodeProjectHomes(input),
        )).containsExactlyElementsIn(input).inOrder()
    }
}
