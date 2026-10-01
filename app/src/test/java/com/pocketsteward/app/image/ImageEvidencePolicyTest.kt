package com.pocketsteward.app.image

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImageEvidencePolicyTest {
    @Test fun dimensionsAloneDoNotAssertAScreenshot() {
        assertThat(ImageEvidencePolicy.screenshotEvidence("IMG_1.png", 1080, 2400, "")).isNull()
    }

    @Test fun filenameHintsAreIdentifiedAsHints() {
        assertThat(ImageEvidencePolicy.screenshotEvidence("Screenshot_20261001.png", 0, 0, ""))
            .isEqualTo("Filename suggests a screenshot.")
    }

    @Test fun interfaceTextAndPixelDimensionsSupportAScreenshotSuggestion() {
        assertThat(ImageEvidencePolicy.screenshotEvidence("IMG_1.png", 1080, 2400,
            "10:42 87% Wi-Fi Settings Notifications Search")).contains("interface text")
    }

    @Test fun ordinaryDocumentsDoNotBecomeScreenshotsFromOneInterfaceWord() {
        assertThat(ImageEvidencePolicy.screenshotEvidence("notes.png", 1080, 2400,
            "Project settings for Lilith at 10:42")).isNull()
    }

    @Test fun landscapeNatureImageCannotBecomeAScreenshotFromTextAlone() {
        assertThat(ImageEvidencePolicy.screenshotEvidence("IMG_1.png", 2400, 1080,
            "10:42 87% Wi-Fi Settings Notifications Search")).isNull()
    }

    @Test fun ocrTextIsBoundedAndKeepsUsefulLineBreaks() {
        assertThat(ImageEvidencePolicy.boundedText("Project: Lilith\u0000\nNotes")).isEqualTo("Project: Lilith\nNotes")
        assertThat(ImageEvidencePolicy.boundedText("x".repeat(10_000)).length).isEqualTo(4_000)
    }

    @Test fun descriptionOnlyReportsObservedLabelsTextAndDimensions() {
        val description = ImageEvidencePolicy.description(1080, 2400,
            listOf(ImageLabelScore("Mountain", .8f), ImageLabelScore("Invented person", Float.NaN), ImageLabelScore("Animal", .2f)),
            null, "Project: Lilith\nNotes")
        assertThat(description).contains("Portrait image (1080 × 2400)")
        assertThat(description).contains("Local model labels: Mountain")
        assertThat(description).contains("Detected text: “Project: Lilith Notes”")
        assertThat(description).doesNotContain("Invented person")
        assertThat(description).doesNotContain("Animal")
    }

    @Test fun absentEvidenceHasAnExplicitFallback() {
        assertThat(ImageEvidencePolicy.description(0, 0, emptyList(), null, ""))
            .isEqualTo("No usable visual or text evidence was detected.")
    }
}
