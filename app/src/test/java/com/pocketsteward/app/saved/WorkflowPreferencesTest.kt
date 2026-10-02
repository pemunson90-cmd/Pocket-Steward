package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.backup.PortableSettingsPolicy
import com.pocketsteward.app.data.settings.PrivacySettings
import org.junit.Test

class WorkflowPreferencesTest {
    @Test fun recipePreferenceCannotTurnOnGlobalPrivacySwitches() {
        val all = WorkflowPreferences()
        val off = PrivacySettings(metadataIndexingEnabled = false, contentInspectionEnabled = false, imageAnalysisEnabled = false)
        assertThat(all.permittedBy(off)).isEqualTo(off)
        val cap = all.copy(content = false, images = false)
        val on = off.copy(metadataIndexingEnabled = true, contentInspectionEnabled = true, imageAnalysisEnabled = true)
        assertThat(cap.permittedBy(on).metadataIndexingEnabled).isTrue()
        assertThat(cap.permittedBy(on).contentInspectionEnabled).isFalse()
        assertThat(cap.permittedBy(on).imageAnalysisEnabled).isFalse()
    }

    @Test fun manualDefaultsDoNotEraseExplicitChoicesOnContinuation() {
        val preference = WorkflowPreferences(selection = WorkflowSelection.MANUAL)
        val choices = setOf(1, 3, 5)
        assertThat(preference.initialSelection(choices)).isEmpty()
        assertThat(preference.initialSelection(choices, preservingChoices = true)).isEqualTo(choices)
        assertThat(WorkflowPreferences().initialSelection(choices)).isEqualTo(choices)
    }

    @Test fun typedAndOriginalLegacyRecipesKeepTheirOriginalDefaults() {
        val current = SavedWorkflow("1", "Legacy", "find notes", listOf("/Download"))
        val fields = SavedWorkflowCodec.encode(listOf(current)).split(';')
        assertThat(SavedWorkflowCodec.decode(fields.take(4).joinToString(";"))).containsExactly(current)
        assertThat(SavedWorkflowCodec.decode(fields.take(4).joinToString(";") + ";UNCERTAIN_FILING"))
            .containsExactly(current.copy(kind = WorkflowKind.UNCERTAIN_FILING))
    }

    @Test fun scopedEvidenceAndDestinationPreferencesSurviveCodecAndPortableBackup() {
        val preferences = WorkflowPreferences(metadata = false, images = false, selection = WorkflowSelection.MANUAL,
            destination = WorkflowDestination.CHOSEN_FOLDER, destinationFolder = "/storage/emulated/0/Documents/Café 📚")
        val recipe = SavedWorkflow("1", "Writing", "organize documents", listOf("/storage/emulated/0/Download"), WorkflowKind.INBOX_FILING, preferences)
        val encoded = SavedWorkflowCodec.encode(listOf(recipe))
        assertThat(SavedWorkflowCodec.decode(encoded)).containsExactly(recipe)
        val normalized = PortableSettingsPolicy.normalize(mapOf("saved_workflows" to encoded))
        assertThat(SavedWorkflowCodec.decode(normalized.getValue("saved_workflows"))).containsExactly(recipe)
    }

    @Test fun invalidNewPreferencesCannotBecomeAnExecutableOrUnboundedDestination() {
        val valid = SavedWorkflow("1", "Valid", "", listOf("/Download"))
        val encoded = SavedWorkflowCodec.encode(listOf(valid))
        assertThat(SavedWorkflowCodec.decode(encoded + ";extra\n" + encoded)).containsExactly(valid)
        assertThat(runCatching { WorkflowPreferences(destination = WorkflowDestination.CHOSEN_FOLDER, destinationFolder = "/a/../b").validate() }.isFailure).isTrue()
        assertThat(runCatching { WorkflowPreferences(destinationFolder = "/secret").validate() }.isFailure).isTrue()
        assertThat(runCatching { WorkflowPreferencesCodec.decode("1|111|EXECUTE|DEFAULT|") }.isFailure).isTrue()
    }
}
