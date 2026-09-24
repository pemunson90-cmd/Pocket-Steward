package com.pocketsteward.app.executor

import com.google.common.truth.Truth.assertThat
import com.pocketsteward.app.storage.FileRef
import org.junit.Test

class ScopeMatchingTest {

    private fun direct(path: String) = FileRef.Direct(path)

    @Test
    fun `a file inside a scope matches it`() {
        assertThat(matchingScopeRoots(direct("/0/Download/a.txt"), listOf("/0/Download")))
            .containsExactly("/0/Download")
    }

    @Test
    fun `a file matches every nested scope that contains it`() {
        assertThat(
            matchingScopeRoots(
                direct("/0/Download/Receipts/r.pdf"),
                listOf("/0/Download", "/0/Download/Receipts", "/0/Pictures"),
            ),
        ).containsExactly("/0/Download", "/0/Download/Receipts")
    }

    @Test
    fun `a sibling whose name starts the same is not a match`() {
        assertThat(matchingScopeRoots(direct("/0/Download2/a.txt"), listOf("/0/Download")))
            .isEmpty()
    }

    @Test
    fun `the scope root itself matches`() {
        assertThat(matchingScopeRoots(direct("/0/Download"), listOf("/0/Download")))
            .containsExactly("/0/Download")
    }

    @Test
    fun `trailing slashes on either side are ignored`() {
        assertThat(matchingScopeRoots(direct("/0/Download/a.txt/"), listOf("/0/Download/")))
            .containsExactly("/0/Download/")
    }

    @Test
    fun `duplicate scopes collapse`() {
        assertThat(matchingScopeRoots(direct("/0/Download/a.txt"), listOf("/0/Download", "/0/Download")))
            .containsExactly("/0/Download")
    }

    @Test
    fun `a file moved out of a scope no longer matches it`() {
        // The case move and undo both depend on: after Download/a.txt moves to
        // Documents, re-tagging must drop Download.
        assertThat(
            matchingScopeRoots(direct("/0/Documents/a.txt"), listOf("/0/Download", "/0/Documents")),
        ).containsExactly("/0/Documents")
    }
}
