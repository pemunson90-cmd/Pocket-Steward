package com.pocketsteward.app.saved

import java.util.Base64

data class NamedHierarchyTemplate(val name: String, val template: HierarchyTemplate)
object NamedHierarchyTemplateCodec {
    fun encode(values: List<NamedHierarchyTemplate>): String = values.joinToString("\n") { "${enc(it.name)};${enc(it.template.encode())}" }
    fun decode(text: String?): List<NamedHierarchyTemplate> = text.orEmpty().lineSequence().take(20).mapNotNull { line ->
        runCatching {
            require(line.length <= 10_000)
            val parts = line.split(';', limit = 2)
            require(parts.size == 2)
            val name = dec(parts[0])
            require(name.isNotBlank() && name.length <= 60 && name.none(Char::isISOControl))
            NamedHierarchyTemplate(name, HierarchyTemplate.parse(dec(parts[1])))
        }.getOrNull()
    }.distinctBy { it.name.lowercase(java.util.Locale.ROOT) }.toList()
    private fun enc(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
    private fun dec(value: String) = String(Base64.getUrlDecoder().decode(value), Charsets.UTF_8)
}
