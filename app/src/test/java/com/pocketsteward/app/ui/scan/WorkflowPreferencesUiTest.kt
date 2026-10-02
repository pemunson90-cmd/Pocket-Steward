package com.pocketsteward.app.ui.scan

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.saved.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp", application = android.app.Application::class)
class WorkflowPreferencesUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun evidenceDestinationAndManualReviewControlsChangeIndependentPreferences() {
        var value by mutableStateOf(WorkflowPreferences())
        compose.setContent { MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) {
            WorkflowPreferencesEditor(value, true) { value = it }
        } } }
        compose.onNodeWithText("Document text and OCR").performClick()
        compose.onNodeWithText("Start with all actions unchecked").performScrollTo().performClick()
        compose.onNodeWithText("Create new groups in Documents").performScrollTo().performClick()
        compose.runOnIdle {
            assertThat(value.content).isFalse(); assertThat(value.images).isTrue()
            assertThat(value.selection).isEqualTo(WorkflowSelection.MANUAL)
            assertThat(value.destination).isEqualTo(WorkflowDestination.DOCUMENTS)
        }
    }
    @Test fun folderPickerMappingRejectsPrivateRelativeAndForeignProviderIds() {
        val base = "content://com.android.externalstorage.documents/tree/"
        assertThat(directPrimaryTreePath(Uri.parse(base + Uri.encode("primary:Documents/Lilith")))).endsWith("/Documents/Lilith")
        assertThat(directPrimaryTreePath(Uri.parse(base + Uri.encode("primary:Documents/../secret")))).isNull()
        assertThat(directPrimaryTreePath(Uri.parse("content://foreign/tree/" + Uri.encode("primary:Documents")))).isNull()
        assertThat(directPrimaryTreePath(Uri.parse(base + Uri.encode("1234-5678:Documents")))).isNull()
    }

    @Test fun preferencesAndIncompleteFolderInputSurviveRecreation() {
        val restoration = StateRestorationTester(compose)
        var read: () -> WorkflowPreferences = { error("Content not created") }
        var write: (WorkflowPreferences) -> Unit = { error("Content not created") }
        restoration.setContent {
            var value by rememberSaveable(stateSaver = WorkflowPreferencesSaver) { mutableStateOf(WorkflowPreferences()) }
            SideEffect { read = { value }; write = { value = it } }
            MaterialTheme { WorkflowPreferencesEditor(value, true) { value = it } }
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertThat(read()).isEqualTo(WorkflowPreferences()) }
        val incomplete = WorkflowPreferences(content = false, selection = WorkflowSelection.MANUAL,
            destination = WorkflowDestination.CHOSEN_FOLDER, destinationFolder = "")
        compose.runOnIdle { write(incomplete) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertThat(read()).isEqualTo(incomplete) }
        val complete = incomplete.copy(destinationFolder = "/storage/emulated/0/Documents/Lilith")
        compose.runOnIdle { write(complete) }
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle { assertThat(read()).isEqualTo(complete) }
    }
}
