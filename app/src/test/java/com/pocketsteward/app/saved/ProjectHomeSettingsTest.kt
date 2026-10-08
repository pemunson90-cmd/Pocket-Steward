package com.pocketsteward.app.saved

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ProjectHomeSettingsTest {
    @Test fun rememberedProviderHomeDoesNotReplaceADifferentCaseSensitiveDocumentId() = runTest {
        val settings = SettingsRepository(ApplicationProvider.getApplicationContext<Application>())
        val first = "content://provider/tree/root/document/HomeA"
        val second = "content://provider/tree/root/document/homea"
        settings.setProjectHomes(listOf(ProjectHome(name = "First", path = first), ProjectHome(name = "Second", path = second)))
        assertThat(settings.projectHomes.first()).hasSize(2)
        settings.rememberProjectHome("Updated first", first, aliases = listOf("Lilith"))
        val result = settings.projectHomes.first()
        assertThat(result).hasSize(2)
        assertThat(result.single { it.path == first }.name).isEqualTo("Updated first")
        assertThat(result.single { it.path == second }.name).isEqualTo("Second")
    }
}
