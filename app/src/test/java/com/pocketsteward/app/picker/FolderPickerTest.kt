package com.pocketsteward.app.picker

import com.google.common.truth.Truth.assertThat
import org.junit.Test

private fun folder(
    name: String,
    fileCount: Int = 1,
    totalBytes: Long = 1,
    modifiedAt: Long? = 1,
    isProtected: Boolean = false,
    parent: String = "/sd/Download",
) = PickerFolder(
    path = "$parent/$name",
    displayName = name,
    fileCount = fileCount,
    totalBytes = totalBytes,
    modifiedAt = modifiedAt,
    isProtected = isProtected,
)

class FolderSearchTest {

    @Test
    fun `a blank query matches everything`() {
        val folders = listOf(folder("Alpha"), folder("Beta"))

        assertThat(FolderPicker.apply(folders, query = "   ")).hasSize(2)
    }

    @Test
    fun `search is a case-insensitive substring match`() {
        val folders = listOf(folder("Screenshots"), folder("Documents"), folder("SHOTS"))

        val names = FolderPicker.apply(folders, query = "shot").map { it.displayName }

        assertThat(names).containsExactly("Screenshots", "SHOTS")
    }

    @Test
    fun `a query matching nothing returns nothing rather than everything`() {
        assertThat(FolderPicker.apply(listOf(folder("Alpha")), query = "zzz")).isEmpty()
    }
}

class FolderSortTest {

    @Test
    fun `name is ascending and case-insensitive`() {
        val folders = listOf(folder("zeta"), folder("Alpha"), folder("beta"))

        val names = FolderPicker.apply(folders, sort = FolderSort.NAME).map { it.displayName }

        assertThat(names).containsExactly("Alpha", "beta", "zeta").inOrder()
    }

    @Test
    fun `file count size and modified are all descending`() {
        val folders = listOf(
            folder("small", fileCount = 1, totalBytes = 10, modifiedAt = 100),
            folder("big", fileCount = 900, totalBytes = 9000, modifiedAt = 900),
        )

        assertThat(FolderPicker.apply(folders, sort = FolderSort.FILE_COUNT).first().displayName).isEqualTo("big")
        assertThat(FolderPicker.apply(folders, sort = FolderSort.SIZE).first().displayName).isEqualTo("big")
        assertThat(FolderPicker.apply(folders, sort = FolderSort.MODIFIED).first().displayName).isEqualTo("big")
    }

    @Test
    fun `a folder with no timestamp sorts last, not first`() {
        val folders = listOf(
            folder("unknown", modifiedAt = null),
            folder("known", modifiedAt = 1),
        )

        val names = FolderPicker.apply(folders, sort = FolderSort.MODIFIED).map { it.displayName }

        assertThat(names).containsExactly("known", "unknown").inOrder()
    }

    @Test
    fun `ties fall back to name so the order is stable`() {
        val folders = listOf(
            folder("zeta", fileCount = 5),
            folder("alpha", fileCount = 5),
            folder("mid", fileCount = 5),
        )

        val names = FolderPicker.apply(folders, sort = FolderSort.FILE_COUNT).map { it.displayName }

        assertThat(names).containsExactly("alpha", "mid", "zeta").inOrder()
    }
}

class FolderFilterTest {

    @Test
    fun `all filters are off by default`() {
        val folders = listOf(
            folder("Empty", fileCount = 0),
            folder(".thumbnails"),
            folder("Android"),
            folder("Normal"),
        )

        assertThat(FolderPicker.apply(folders)).hasSize(4)
    }

    @Test
    fun `hide empty removes only zero-file folders`() {
        val folders = listOf(folder("Empty", fileCount = 0), folder("Full", fileCount = 1))

        val names = FolderPicker.apply(folders, filters = FolderFilters(hideEmpty = true)).map { it.displayName }

        assertThat(names).containsExactly("Full")
    }

    @Test
    fun `only protected keeps just the marked ones`() {
        val folders = listOf(folder("Open"), folder("Locked", isProtected = true))

        val names = FolderPicker.apply(folders, filters = FolderFilters(onlyProtected = true)).map { it.displayName }

        assertThat(names).containsExactly("Locked")
    }

    @Test
    fun `hide system covers dotfolders and Android in any case`() {
        val folders = listOf(folder(".thumbnails"), folder("Android"), folder("android"), folder("Music"))

        val names = FolderPicker.apply(folders, filters = FolderFilters(hideSystem = true)).map { it.displayName }

        assertThat(names).containsExactly("Music")
    }

    @Test
    fun `a folder hidden by a filter cannot be surfaced by a matching query`() {
        val folders = listOf(folder("Android"), folder("AndroidStudio"))

        val names = FolderPicker.apply(
            folders,
            query = "android",
            filters = FolderFilters(hideSystem = true),
        ).map { it.displayName }

        assertThat(names).containsExactly("AndroidStudio")
    }
}

class RecentFoldersTest {

    @Test
    fun `the newest path goes to the front`() {
        val recents = RecentFolders.add(listOf("/sd/A", "/sd/B"), "/sd/C")

        assertThat(recents).containsExactly("/sd/C", "/sd/A", "/sd/B").inOrder()
    }

    @Test
    fun `re-adding an existing path moves it rather than duplicating it`() {
        val recents = RecentFolders.add(listOf("/sd/A", "/sd/B"), "/sd/B")

        assertThat(recents).containsExactly("/sd/B", "/sd/A").inOrder()
    }

    @Test
    fun `a trailing slash is the same folder`() {
        val recents = RecentFolders.add(listOf("/sd/A"), "/sd/A/")

        assertThat(recents).containsExactly("/sd/A")
    }

    @Test
    fun `the list is capped, dropping the oldest`() {
        var recents = emptyList<String>()
        for (i in 1..8) recents = RecentFolders.add(recents, "/sd/$i")

        assertThat(recents).hasSize(RecentFolders.MAX)
        assertThat(recents.first()).isEqualTo("/sd/8")
        assertThat(recents).doesNotContain("/sd/1")
    }

    @Test
    fun `a blank path is ignored`() {
        assertThat(RecentFolders.add(listOf("/sd/A"), "   ")).containsExactly("/sd/A")
    }

    @Test
    fun `encode and decode round trip, and blank lines are dropped`() {
        val paths = listOf("/sd/A", "/sd/B B", "/sd/C")

        assertThat(RecentFolders.decode(RecentFolders.encode(paths))).isEqualTo(paths)
        assertThat(RecentFolders.decode(null)).isEmpty()
        assertThat(RecentFolders.decode("\n\n /sd/A \n\n")).containsExactly("/sd/A")
    }
}
