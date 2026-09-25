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
    fun projectHomesRoundTripAliasesPackagesAndStrategy() {
        val input = listOf(
            ProjectHome(
                id = "id-1",
                name = "Lilith Companion App",
                path = "/storage/emulated/0/Lilith Companion App",
                aliases = listOf("Lilith", "LilithCompanion"),
                packageIds = listOf("com.example.lilith"),
                hierarchy = ProjectHierarchyStrategy.VERSIONED,
            ),
        )
        assertThat(OrganizationPreferenceCodec.decodeProjectHomes(
            OrganizationPreferenceCodec.encodeProjectHomes(input),
        )).containsExactlyElementsIn(input).inOrder()
    }

    @Test
    fun inboxRootsRoundTrip() {
        val input = listOf(InboxRoot("/storage/emulated/0/Download", "Downloads"))
        assertThat(OrganizationPreferenceCodec.decodeInboxRoots(
            OrganizationPreferenceCodec.encodeInboxRoots(input),
        )).containsExactlyElementsIn(input).inOrder()
    }
}