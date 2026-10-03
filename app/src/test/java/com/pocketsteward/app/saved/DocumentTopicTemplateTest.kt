package com.pocketsteward.app.saved

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.backup.*
import org.junit.Test

class DocumentTopicTemplateTest {
    @Test fun namedTopicsAndPortableSettingsRoundTripWithoutOperationAuthority() {
        val rules = DocumentTopicRules.parse("Household=Home/Receipts|invoice,payment due\nCafé=Reading/Café|résumé,étude")
        val named = listOf(NamedDocumentTopicTemplate("My document categories", rules))
        assertThat(DocumentTopicRules.parse(rules.encode())).isEqualTo(rules)
        val encoded = NamedDocumentTopicTemplateCodec.encode(named)
        assertThat(NamedDocumentTopicTemplateCodec.decode(encoded)).isEqualTo(named)
        val backup = PortableBackup(createdAt = 100, settings = mapOf("document_topic_rules" to rules.encode(), "named_document_topic_templates" to encoded), history = emptyList())
        assertThat(PortableBackupCodec.decode(PortableBackupCodec.encode(backup))).isEqualTo(backup)
        assertThat(String(PortableBackupCodec.encode(backup))).doesNotContain("operations")
        assertThat(DocumentTopicRules.parse("").topics).isEmpty()
    }
    @Test fun unsafeDuplicateOverlargeAndMalformedTemplatesAreRejectedBeforeRestore() {
        for (raw in listOf("Legal=../outside|contract,agreement", "Legal=/outside|contract,agreement", "Legal=Docs|invoice,INVOICE",
            "Legal=Docs|onlyone", "Legal=Docs|contract,agreement\nOther=docs|invoice,payment due")) {
            assertThat(runCatching { DocumentTopicRules.parse(raw) }.isFailure).isTrue()
            assertThat(runCatching { PortableSettingsPolicy.normalize(mapOf("document_topic_rules" to raw)) }.isFailure).isTrue()
        }
        assertThat(runCatching { DocumentTopicRules.parse((0..20).joinToString("\n") { "Topic$it=Docs$it|contract,agreement" }) }.isFailure).isTrue()
        assertThat(runCatching { PortableSettingsPolicy.normalize(mapOf("named_document_topic_templates" to "damaged")) }.isFailure).isTrue()
    }
}
