package com.pocketsteward.app.data.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Section 23 requires the privacy-sensitive switches to default to the
 * narrowest setting. This pins that contract so a future refactor of
 * [PrivacySettings] can't silently flip a default to "on".
 */
class SettingsDefaultsTest {

    @Test
    fun `metadata indexing defaults on, everything content-facing defaults off`() {
        val defaults = PrivacySettings()

        assertThat(defaults.metadataIndexingEnabled).isTrue()
        assertThat(defaults.contentInspectionEnabled).isFalse()
        assertThat(defaults.imageAnalysisEnabled).isFalse()
        assertThat(defaults.onDeviceAiEnabled).isFalse()
    }

    @Test
    fun `storage access state defaults to no mode chosen`() {
        val defaults = StorageAccessState()

        assertThat(defaults.mode).isNull()
        assertThat(defaults.safTreeUri).isNull()
    }
}
