package com.pocketsteward.app.inbox

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.rules.ProjectKeyword
import com.pocketsteward.app.saved.ProjectHierarchy
import com.pocketsteward.app.saved.ProjectHome
import org.junit.Test

class InboxFilingEngineTest {
    private val storage = "/storage/emulated/0"
    private val lilith = ProjectHome(
        id = "lilith",
        name = "Lilith Companion App",
        path = "$storage/Lilith Companion App",
        aliases = listOf("LilithCompanion", "Lilith Companion", "Lilith"),
        packageIds = listOf("com.example.lilithcompanion"),
        hierarchy = ProjectHierarchy.VERSIONED,
    )

    @Test
    fun apkMetadataFindsExistingProjectAndReleaseStrongly() {
        val apk = artifact(
            "LilithCompanion-0.3.105.apk",
            "apk",
            apkLabel = "Lilith Companion",
            apkPackageName = "com.example.lilithcompanion",
            apkVersionName = "0.3.105",
        )

        val result = InboxFilingEngine.analyze(
            artifacts = listOf(apk),
            projectHomes = listOf(lilith),
            storageRootPath = storage,
        )

        val decision = result.decisions.single()
        assertThat(decision.projectHome.path).isEqualTo(lilith.path)
        assertThat(decision.release).isEqualTo("0.3.105")
        assertThat(decision.confidence).isEqualTo(FilingConfidence.STRONG)
        assertThat(decision.evidence.map { it.type }).contains(FilingEvidenceType.APK_PACKAGE)
    }

    @Test
    fun versionedSourceArchiveJoinsExistingProjectWithoutExtensionGrouping() {
        val source = artifact(
            "LilithCompanion-0.3.105-SOURCE.zip",
            "zip",
            archiveSample = listOf("LilithCompanion/app/src/main/AndroidManifest.xml"),
        )

        val result = InboxFilingEngine.analyze(
            artifacts = listOf(source),
            projectHomes = listOf(lilith),
            storageRootPath = storage,
        )

        val decision = result.decisions.single()
        assertThat(decision.projectHome.name).isEqualTo("Lilith Companion App")
        assertThat(decision.release).isEqualTo("0.3.105")
        assertThat(decision.confidence).isEqualTo(FilingConfidence.STRONG)
    }

    @Test
    fun unrelatedDescriptiveDownloadDoesNotInventTopLevelProject() {
        val random = artifact("some-other-random-file.pdf", "pdf")

        val result = InboxFilingEngine.analyze(
            artifacts = listOf(random),
            projectHomes = listOf(lilith),
            storageRootPath = storage,
        )

        assertThat(result.decisions).isEmpty()
        assertThat(result.unresolved.single().artifact.displayName).isEqualTo(random.displayName)
    }

    @Test
    fun supportingImageCanJoinExistingProjectFromDistinctiveAlias() {
        val now = 10_000_000L
        val apk = artifact(
            "LilithCompanion-0.3.105.apk",
            "apk",
            modifiedAt = now,
            apkPackageName = "com.example.lilithcompanion",
            apkVersionName = "0.3.105",
        )
        val image = artifact(
            "lilith-back-turnaround.png",
            "png",
            modifiedAt = now + 30_000,
        )

        val result = InboxFilingEngine.analyze(
            artifacts = listOf(apk, image),
            projectHomes = listOf(lilith),
            storageRootPath = storage,
        )

        val imageDecision = result.decisions.single { it.artifact.displayName == image.displayName }
        assertThat(imageDecision.projectHome.path).isEqualTo(lilith.path)
        assertThat(imageDecision.confidence).isEqualTo(FilingConfidence.STRONG)
        assertThat(imageDecision.evidence.map { it.type }).contains(FilingEvidenceType.KNOWN_PROJECT_HOME)
    }

    @Test
    fun versionOnlyNoteStaysUnresolvedWhenTwoProjectsShareSameDownloadWindow() {
        val now = 20_000_000L
        val mothership = ProjectHome(
            id = "mothership",
            name = "Mothership",
            path = "$storage/Mothership",
            packageIds = listOf("com.example.mothership"),
        )
        val lilithApk = artifact(
            "LilithCompanion-0.3.105.apk", "apk", modifiedAt = now,
            apkPackageName = "com.example.lilithcompanion", apkVersionName = "0.3.105",
        )
        val motherApk = artifact(
            "Mothership-0.3.105.apk", "apk", modifiedAt = now + 1_000,
            apkPackageName = "com.example.mothership", apkVersionName = "0.3.105",
        )
        val note = artifact("0.3.105-build-notes.md", "md", modifiedAt = now + 2_000)

        val result = InboxFilingEngine.analyze(
            artifacts = listOf(lilithApk, motherApk, note),
            projectHomes = listOf(lilith, mothership),
            storageRootPath = storage,
        )

        assertThat(result.unresolved.map { it.artifact.displayName }).contains(note.displayName)
    }

    @Test
    fun projectKeywordCanCreateNewVersionedHome() {
        val apk = artifact("PocketSteward-1.4.0-dev1.apk", "apk", apkVersionName = "1.4.0-dev1")

        val result = InboxFilingEngine.analyze(
            artifacts = listOf(apk),
            projectHomes = emptyList(),
            projectKeywords = listOf(ProjectKeyword("PocketSteward", "Pocket Steward")),
            storageRootPath = storage,
        )

        val decision = result.decisions.single()
        assertThat(decision.projectHome.path).isEqualTo("$storage/Pocket Steward")
        assertThat(decision.release).isEqualTo("1.4.0-dev1")
        assertThat(decision.confidence).isEqualTo(FilingConfidence.STRONG)
    }

    @Test
    fun releaseParserHandlesDevAndHbSuffixes() {
        assertThat(InboxFilingEngine.inferRelease(artifact("PocketSteward-1.3.2-dev1.apk", "apk")))
            .isEqualTo("1.3.2-dev1")
        assertThat(InboxFilingEngine.inferRelease(artifact("Mothership-0.3.0-HB07.apk", "apk")))
            .isEqualTo("0.3.0-HB07")
    }

    private fun artifact(
        name: String,
        extension: String,
        modifiedAt: Long? = 1_000L,
        apkLabel: String? = null,
        apkPackageName: String? = null,
        apkVersionName: String? = null,
        archiveSample: List<String> = emptyList(),
    ) = ArtifactSignals(
        stableRef = "$storage/Download/$name",
        displayName = name,
        extension = extension,
        sizeBytes = 1234,
        modifiedAt = modifiedAt,
        apkLabel = apkLabel,
        apkPackageName = apkPackageName,
        apkVersionName = apkVersionName,
        archiveSample = archiveSample,
    )
}
