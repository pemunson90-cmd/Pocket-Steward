package com.pocketsteward.app.intent

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.db.FileRecord
import com.pocketsteward.app.plan.PlannedOperation
import com.pocketsteward.app.scan.FileCategory
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class V1AcceptanceIntentTest {
    private val root = FileRef.Direct("/storage/emulated/0/Download")

    @Test
    fun canonicalV1RequestPlansOnlyObviousKnownFiles() {
        val request =
            "Organize the obvious files by type. Put APKs together, put PDFs and documents together, " +
                "keep images separate, and leave anything uncertain alone."

        val parsed = DeterministicIntentParser.parse(request) as IntentParseResult.Parsed

        assertThat(parsed.intent.action).isEqualTo(IntentAction.ORGANIZE)
        assertThat(parsed.intent.categories).containsAtLeast(
            FileCategory.APK,
            FileCategory.DOCUMENT,
            FileCategory.IMAGE,
        )

        val apk = record("installer.apk", "apk")
        val pdf = record("report.pdf", "pdf")
        val image = record("photo.jpg", "jpg")
        val uncertain = record("mystery.xyzunknown", "xyzunknown")

        val generated = IntentPlanGenerator.generate(
            scopeRoot = root,
            records = listOf(apk, pdf, image, uncertain),
            projectKeywords = emptyList(),
            intent = parsed.intent,
        )

        val moves = generated.plan.operations.filterIsInstance<PlannedOperation.Move>()
        assertThat(moves.map { (it.source as FileRef.Direct).absolutePath })
            .containsExactly(apk.stableRef, pdf.stableRef, image.stableRef)
        assertThat(moves.none { it.source == FileRef.Direct(uncertain.stableRef) }).isTrue()

        assertThat(
            generated.plan.operations.filterIsInstance<PlannedOperation.CreateDirectory>()
                .map { it.name },
        ).containsAtLeast("APKs", "Documents", "Images")
    }

    private fun record(name: String, extension: String) = FileRecord(
        stableRef = "${root.absolutePath}/$name",
        displayName = name,
        extension = extension,
        mimeType = null,
        absolutePathOrUri = "${root.absolutePath}/$name",
        parentRef = root.absolutePath,
        sizeBytes = 10,
        createdAt = null,
        modifiedAt = 1,
        lastScannedAt = 1,
        isDirectory = false,
        isHidden = false,
    )
}
