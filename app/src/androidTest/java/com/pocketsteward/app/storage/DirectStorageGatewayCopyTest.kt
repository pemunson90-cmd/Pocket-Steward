package com.pocketsteward.app.storage

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DirectStorageGatewayCopyTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val root = File(context.cacheDir, "copy-test")
    private lateinit var gateway: DirectStorageGateway

    @Before
    fun setUp() {
        root.deleteRecursively()
        check(root.mkdirs())
        gateway = DirectStorageGateway(context)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun copyPreservesSourceAndWritesExactDestinationBytes() = runTest {
        val source = File(root, "source.bin").apply {
            writeBytes(ByteArray(32 * 1024) { index -> (index % 251).toByte() })
        }
        val destination = File(root, "nested/copied.bin")

        val result = gateway.copy(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(destination.absolutePath),
        )

        assertThat(result).isInstanceOf(MutationResult.Success::class.java)
        assertThat(source.exists()).isTrue()
        assertThat(destination.exists()).isTrue()
        assertThat(destination.readBytes()).isEqualTo(source.readBytes())
    }

    @Test
    fun copyRefusesOverwriteAndLeavesBothFilesUntouched() = runTest {
        val source = File(root, "source.txt").apply { writeText("source") }
        val destination = File(root, "destination.txt").apply { writeText("existing") }

        val result = gateway.copy(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(destination.absolutePath),
        )

        assertThat(result).isInstanceOf(MutationResult.Failure::class.java)
        assertThat(source.readText()).isEqualTo("source")
        assertThat(destination.readText()).isEqualTo("existing")
    }

    @Test
    fun copyRefusesDirectorySources() = runTest {
        val source = File(root, "folder").apply { mkdirs() }
        val destination = File(root, "folder-copy")

        val result = gateway.copy(
            FileRef.Direct(source.absolutePath),
            FileRef.Direct(destination.absolutePath),
        )

        assertThat(result).isInstanceOf(MutationResult.Failure::class.java)
        assertThat(destination.exists()).isFalse()
    }
}
