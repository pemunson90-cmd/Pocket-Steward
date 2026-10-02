package com.pocketsteward.app.saved

import com.pocketsteward.app.data.settings.PrivacySettings
import java.util.Base64

enum class WorkflowSelection(val label: String) { DEFAULT("Use normal safe defaults"), MANUAL("Start with all actions unchecked") }
enum class WorkflowDestination(val label: String) {
    DEFAULT("Use normal destinations"), INBOX_LOCAL("Create new groups in the source folder"),
    DOCUMENTS("Create new groups in Documents"), CHOSEN_FOLDER("Create new groups in a chosen folder")
}

/** Preferences cannot turn on privacy switches or store execution/grant authority. */
data class WorkflowPreferences(
    val metadata: Boolean = true, val content: Boolean = true, val images: Boolean = true,
    val selection: WorkflowSelection = WorkflowSelection.DEFAULT,
    val destination: WorkflowDestination = WorkflowDestination.DEFAULT,
    val destinationFolder: String? = null,
) {
    fun validate() {
        require(selection in WorkflowSelection.entries && destination in WorkflowDestination.entries)
        if (destination == WorkflowDestination.CHOSEN_FOLDER) {
            val ref = requireNotNull(destinationFolder)
            require(ref.length in 1..4096 && ref.none { it.isISOControl() }) { "Choose a valid folder." }
            require(ref.startsWith('/') || ref.startsWith("content://")) { "Choose an absolute storage folder or granted document URI." }
            if (ref.startsWith('/')) require(ref.split('/').none { it in setOf(".", "..") }) { "Folder must not contain relative path segments." }
        } else require(destinationFolder == null)
    }
    fun permittedBy(privacy: PrivacySettings): PrivacySettings = privacy.copy(
        metadataIndexingEnabled = privacy.metadataIndexingEnabled && metadata,
        contentInspectionEnabled = privacy.contentInspectionEnabled && content,
        imageAnalysisEnabled = privacy.imageAnalysisEnabled && images,
    )
    fun initialSelection(defaults: Set<Int>, preservingChoices: Boolean = false): Set<Int> =
        if (selection == WorkflowSelection.MANUAL && !preservingChoices) emptySet() else defaults
    val summary: String get() = "${selection.label} · ${destination.label}" +
        (if (destinationFolder == null) "" else " · $destinationFolder") + " · evidence: " +
        listOfNotNull("metadata".takeIf { metadata }, "documents".takeIf { content }, "images".takeIf { images }).joinToString().ifEmpty { "names only" }
}

object WorkflowPreferencesCodec {
    fun encode(value: WorkflowPreferences): String {
        value.validate()
        val flags = listOf(value.metadata, value.content, value.images).joinToString("") { if (it) "1" else "0" }
        return listOf("1", flags, value.selection.name, value.destination.name,
            value.destinationFolder?.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }.orEmpty()).joinToString("|")
    }
    fun decode(raw: String): WorkflowPreferences {
        require(raw.length <= 6000)
        val fields = raw.split('|'); require(fields.size == 5 && fields[0] == "1" && fields[1].matches(Regex("[01]{3}")))
        return WorkflowPreferences(fields[1][0] == '1', fields[1][1] == '1', fields[1][2] == '1',
            WorkflowSelection.valueOf(fields[2]), WorkflowDestination.valueOf(fields[3]),
            fields[4].takeIf { it.isNotEmpty() }?.let { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }).also { it.validate() }
    }
}
