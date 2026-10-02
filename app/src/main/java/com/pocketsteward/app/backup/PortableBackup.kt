package com.pocketsteward.app.backup

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.pocketsteward.app.filing.InboxFilingEngine
import com.pocketsteward.app.saved.*
import com.pocketsteward.app.scheduled.ScheduledCleanupCodec

/** Deliberately contains neither executable plans nor grants, journals, keys or file contents. */
data class ArchivedTaskSummary(val request: String, val status: String, val startedAt: Long, val completedAt: Long?, val summary: String?)
data class PortableBackup(val format: String = "PocketStewardSettings", val version: Int = 1, val createdAt: Long, val settings: Map<String, String>, val history: List<ArchivedTaskSummary>)

object PortableSettingsPolicy {
    val booleanKeys = setOf("metadata_indexing_enabled", "content_inspection_enabled", "image_analysis_enabled", "on_device_ai_enabled", "advanced_mode_enabled", "wallpaper_colors_enabled", "thumbnails_enabled", "library_content_while_charging", "library_evidence_while_charging")
    val stringKeys = setOf("project_keywords", "hierarchy_template", "named_hierarchy_templates", "saved_workflows", "saved_searches", "favorite_destinations", "correction_rules", "project_homes", "inbox_roots", "scheduled_cleanup")
    val allowedKeys = booleanKeys + stringKeys

    fun normalize(values: Map<String, String>): Map<String, String> {
        require(values.keys.all { it in allowedKeys }) { "Backup includes unsupported or private settings." }
        return values.mapValues { (key, value) ->
            require(value.length <= 2_000_000) { "Backup setting is too large." }
            if (key in booleanKeys) {
                require(value == "true" || value == "false") { "Invalid boolean setting." }
                value
            } else when (key) {
                "hierarchy_template" -> HierarchyTemplate.parse(value).encode()
                "named_hierarchy_templates" -> checkedList(value, NamedHierarchyTemplateCodec.decode(value), 20, NamedHierarchyTemplateCodec::encode)
                "saved_workflows" -> checkedList(value, SavedWorkflowCodec.decode(value), 20, SavedWorkflowCodec::encode)
                "saved_searches" -> checkedList(value, SavedSearchCodec.decode(value), 20, SavedSearchCodec::encode)
                "favorite_destinations" -> checkedList(value, OrganizationPreferenceCodec.decodeDestinations(value), 100, OrganizationPreferenceCodec::encodeDestinations)
                "correction_rules" -> checkedList(value, OrganizationPreferenceCodec.decodeCorrections(value), 100, OrganizationPreferenceCodec::encodeCorrections)
                "project_homes" -> checkedList(value, OrganizationPreferenceCodec.decodeProjectHomes(value), 100, OrganizationPreferenceCodec::encodeProjectHomes)
                "inbox_roots" -> checkedList(value, OrganizationPreferenceCodec.decodeInboxRoots(value), 100, OrganizationPreferenceCodec::encodeInboxRoots)
                "scheduled_cleanup" -> {
                    val fields = value.split('|', limit = 3)
                    require(fields.size == 3 && fields[0] in setOf("0", "1") && fields[1].toLongOrNull()?.let { it in 1L..720L } == true) { "Invalid schedule setting." }
                    ScheduledCleanupCodec.encode(ScheduledCleanupCodec.decode(value).copy(enabled = false))
                }
                "project_keywords" -> {
                    val lines = value.lineSequence().filter { it.isNotBlank() }.toList()
                    require(lines.size <= 1000)
                    lines.forEach { line ->
                        val fields = line.split('=', limit = 2)
                        require(fields.size == 2 && fields[0].isNotBlank() && fields[0].length <= 200 && InboxFilingEngine.sanitizeSegment(fields[1]) == fields[1]) { "Invalid project keyword." }
                    }
                    lines.joinToString("\n")
                }
                else -> error("Unsupported setting")
            }
        }
    }

    private fun <T> checkedList(raw: String, decoded: List<T>, maximum: Int, encode: (List<T>) -> String): String {
        require(decoded.size <= maximum && raw.lineSequence().count { it.isNotBlank() } == decoded.size) { "Backup list has invalid or duplicate entries." }
        return encode(decoded)
    }
}

object PortableBackupCodec {
    const val MAX_BYTES = 8 * 1024 * 1024
    private val gson = Gson()
    fun encode(backup: PortableBackup): ByteArray {
        validate(backup)
        val bytes = gson.toJson(backup).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES)
        return bytes
    }
    fun decode(bytes: ByteArray): PortableBackup {
        require(bytes.size <= MAX_BYTES) { "Backup exceeds the 8 MiB limit." }
        val text = String(bytes, Charsets.UTF_8)
        validateJsonBounds(text)
        val json = JsonParser.parseString(text).asJsonObject
        require(json.keySet().all { it in setOf("format", "version", "createdAt", "settings", "history") }) { "Unsupported backup fields." }
        val backup = gson.fromJson(json, PortableBackup::class.java)
        validate(backup)
        return backup.copy(settings = PortableSettingsPolicy.normalize(backup.settings))
    }
    private fun validateJsonBounds(text: String) {
        com.google.gson.stream.JsonReader(java.io.StringReader(text)).use { reader ->
            reader.strictness = com.google.gson.Strictness.STRICT
            var depth = 0
            var tokens = 0
            while (reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
                require(++tokens <= 100_000) { "Backup has too many entries." }
                when (reader.peek()) {
                    com.google.gson.stream.JsonToken.BEGIN_OBJECT -> { reader.beginObject(); require(++depth <= 8) { "Backup is nested too deeply." } }
                    com.google.gson.stream.JsonToken.BEGIN_ARRAY -> { reader.beginArray(); require(++depth <= 8) { "Backup is nested too deeply." } }
                    com.google.gson.stream.JsonToken.END_OBJECT -> { reader.endObject(); depth-- }
                    com.google.gson.stream.JsonToken.END_ARRAY -> { reader.endArray(); depth-- }
                    com.google.gson.stream.JsonToken.NAME -> reader.nextName()
                    com.google.gson.stream.JsonToken.STRING, com.google.gson.stream.JsonToken.NUMBER -> reader.nextString()
                    com.google.gson.stream.JsonToken.BOOLEAN -> reader.nextBoolean()
                    com.google.gson.stream.JsonToken.NULL -> reader.nextNull()
                    else -> error("Invalid backup JSON")
                }
            }
        }
    }
    private fun validate(backup: PortableBackup) {
        require(backup.format == "PocketStewardSettings" && backup.version == 1) { "Unsupported backup format or version." }
        require(backup.createdAt >= 0)
        PortableSettingsPolicy.normalize(backup.settings)
        require(backup.history.size <= 1000) { "Backup history is too large." }
        backup.history.forEach { task ->
            require(task.request.length <= 20_000 && task.status.length <= 100 && task.summary.orEmpty().length <= 20_000 && task.startedAt >= 0)
        }
    }
}
