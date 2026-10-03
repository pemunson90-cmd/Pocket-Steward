package com.pocketsteward.app.saved

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = android.app.Application::class)
class DocumentTopicSettingsTest {
    @Test fun editableRulesNamedTemplatesAndActualSettingsRestoreStayConsistent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = SettingsRepository(context)
        repository.setDocumentTopicRules("")
        repository.saveDocumentTopicRule(null, "Household", "Home/Receipts", "invoice, payment due")
        repository.saveNamedDocumentTopicTemplate("Household documents")
        repository.saveDocumentTopicRule("Household", "Receipts", "Finance/Receipts", "invoice, account balance")
        assertThat(repository.documentTopicRules.first().topics.single().name).isEqualTo("Receipts")
        val saved = repository.namedDocumentTopicTemplates.first().single { it.name == "Household documents" }
        assertThat(saved.rules.topics.single().name).isEqualTo("Household")
        val before = repository.exportPortableSettings()
        repository.removeDocumentTopicRule("Receipts")
        repository.removeNamedDocumentTopicTemplate(saved.name)
        assertThat(repository.documentTopicRules.first().topics).isEmpty()
        repository.restorePortableSettings(before)
        assertThat(repository.documentTopicRules.first().topics.single().name).isEqualTo("Receipts")
        assertThat(repository.namedDocumentTopicTemplates.first()).contains(saved)
        val unchanged = repository.documentTopicRules.first()
        val invalid = runCatching { repository.restorePortableSettings(before + ("document_topic_rules" to "Bad=../outside|invoice,payment due")) }
        assertThat(invalid.isFailure).isTrue()
        assertThat(repository.documentTopicRules.first()).isEqualTo(unchanged)
        repository.setDocumentTopicRules(saved.rules.encode())
        assertThat(repository.documentTopicRules.first()).isEqualTo(saved.rules)
    }
}
