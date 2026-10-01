package com.pocketsteward.app.backup

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.*
import org.junit.Test

class PortableBackupTest {
    private fun backup(settings: Map<String, String> = emptyMap()) = PortableBackup(createdAt = 100, settings = settings, history = listOf(ArchivedTaskSummary("Sort Uncertain", "COMPLETED", 10, 20, "Moved 12 files")))
    @Test fun configurationAndReadOnlySummariesRoundTripWithoutExecutableAuthority() {
        val input = backup(mapOf("project_homes" to OrganizationPreferenceCodec.encodeProjectHomes(listOf(ProjectHome("id", "Lilith", "/Documents/Lilith"))), "hierarchy_template" to "Notes=Research/Notes", "saved_workflows" to SavedWorkflowCodec.encode(listOf(SavedWorkflow("id", "Sort", "", listOf("/Downloads"), WorkflowKind.UNCERTAIN_FILING)))))
        val bytes = PortableBackupCodec.encode(input)
        assertThat(PortableBackupCodec.decode(bytes)).isEqualTo(input)
        val json = String(bytes)
        assertThat(json).doesNotContain("planJson")
        assertThat(json).doesNotContain("sourcePreconditions")
    }
    @Test fun grantsAndPrivateKeysCannotEnterPortableSettings() {
        for (key in listOf("saf_tree_uri", "storage_access_mode", "last_scan_session", "provider_api_key", "signing_password", "pending_cleanup_suggestion")) {
            assertThat(runCatching { PortableBackupCodec.encode(backup(mapOf(key to "secret"))) }.isFailure).isTrue()
        }
    }
    @Test fun invalidListAndUnsafeTemplateFailBeforeAnyRestore() {
        assertThat(runCatching { PortableSettingsPolicy.normalize(mapOf("saved_workflows" to "not-a-workflow")) }.isFailure).isTrue()
        assertThat(runCatching { PortableSettingsPolicy.normalize(mapOf("hierarchy_template" to "Notes=../Other")) }.isFailure).isTrue()
        assertThat(runCatching { PortableSettingsPolicy.normalize(mapOf("content_inspection_enabled" to "yes")) }.isFailure).isTrue()
    }
    @Test fun restoredScheduleIsAlwaysDisabledButKeepsIntervalAndRoots() {
        assertThat(PortableSettingsPolicy.normalize(mapOf("scheduled_cleanup" to "1|24|/Downloads"))["scheduled_cleanup"]).isEqualTo("0|24|/Downloads")
    }
    @Test fun unsupportedFormatAndOversizedPayloadAreRejected() {
        assertThat(runCatching { PortableBackupCodec.encode(backup().copy(version = 99)) }.isFailure).isTrue()
        assertThat(runCatching { PortableBackupCodec.decode(ByteArray(PortableBackupCodec.MAX_BYTES + 1)) }.isFailure).isTrue()
        assertThat(runCatching { PortableBackupCodec.decode("{\"format\":\"PocketStewardSettings\",\"version\":1,\"createdAt\":100,\"settings\":{},\"history\":[],\"planJson\":\"run\"}".toByteArray()) }.isFailure).isTrue()
    }    @Test fun deeplyNestedUntrustedBackupIsRejectedBeforeBuildingAJsonTree() {
        val nested = "[".repeat(1000) + "0" + "]".repeat(1000)
        assertThat(runCatching { PortableBackupCodec.decode(nested.toByteArray()) }.isFailure).isTrue()
        val normal = backup().copy(history = listOf(ArchivedTaskSummary("[".repeat(100), "COMPLETED", 10, 20, "Braces { are text }")))
        assertThat(PortableBackupCodec.decode(PortableBackupCodec.encode(normal))).isEqualTo(normal)
    }

}
