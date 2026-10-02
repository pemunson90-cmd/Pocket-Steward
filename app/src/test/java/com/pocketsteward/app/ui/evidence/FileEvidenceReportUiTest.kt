package com.pocketsteward.app.ui.evidence

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import com.pocketsteward.app.evidence.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w360dp-h640dp", application = android.app.Application::class)
class FileEvidenceReportUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun metadataOcrAndAdviceRemainReadableInTheSharedScrollableView() {
        val report = FileEvidenceReport("Lilith notes.pdf", "/Downloads/Lilith.pdf", "Observed locally", listOf(
            EvidenceSection(EvidenceOrigin.FILE_METADATA, "Observed file", listOf("/Downloads/Lilith.pdf", "12 bytes")),
            EvidenceSection(EvidenceOrigin.DOCUMENT_OCR, "OCR · page 2", listOf("Scanned manuscript notes"), false),
            EvidenceSection(EvidenceOrigin.MODEL_ADVICE, "Model advice for this review", listOf("Possible manuscript")),
        ))
        compose.setContent { MaterialTheme { FileEvidenceReportContent(report) } }
        compose.onNodeWithText("Lilith notes.pdf").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("OCR · page 2"))
        compose.onNodeWithText("Partial inspection · more evidence may exist").assertIsDisplayed()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Model advice for this review"))
        compose.onNodeWithText("Possible manuscript").assertIsDisplayed()
    }
}
