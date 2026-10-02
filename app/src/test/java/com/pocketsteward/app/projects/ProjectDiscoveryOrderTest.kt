package com.pocketsteward.app.projects

import com.google.common.truth.Truth.assertThat
import java.sql.DriverManager
import org.junit.Test

class ProjectDiscoveryOrderTest {
    @Test fun pageOrderMatchesSQLiteKeysetsForEmojiBmpAndAccentedProjectNames() {
        val names = listOf("/Projects/📚Stories", "/Projects/\uE000Research", "/Projects/Lilith", "/Projects/Écriture", "/Projects/𐀀Archive")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE homes (path TEXT PRIMARY KEY)") }
            db.prepareStatement("INSERT INTO homes(path) VALUES (?)").use { insert -> names.forEach { insert.setString(1, it); insert.executeUpdate() } }
            val ordered = db.createStatement().use { statement -> statement.executeQuery("SELECT path FROM homes ORDER BY path").use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } } }
            assertThat(names.sortedWith(ProjectDiscoveryOrder)).containsExactlyElementsIn(ordered).inOrder()
        }
    }
}
