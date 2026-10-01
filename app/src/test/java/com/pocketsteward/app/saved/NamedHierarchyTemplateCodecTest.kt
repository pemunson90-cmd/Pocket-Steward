package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NamedHierarchyTemplateCodecTest {
    @Test fun unicodeNamesAndRoleFoldersRoundTrip() {
        val value = NamedHierarchyTemplate("Writing 📚", HierarchyTemplate.parse("Notes=Research\nImages=Assets/Images"))
        assertThat(NamedHierarchyTemplateCodec.decode(NamedHierarchyTemplateCodec.encode(listOf(value)))).containsExactly(value)
    }
    @Test fun malformedEntryCannotDiscardOtherProfilesOrDuplicateNames() {
        val value = NamedHierarchyTemplate("Writing", HierarchyTemplate())
        val same = value.copy(name = "WRITING")
        assertThat(NamedHierarchyTemplateCodec.decode("broken\n" + NamedHierarchyTemplateCodec.encode(listOf(value, same)))).containsExactly(value)
    }
}
