package com.pocketsteward.app.data.db

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.Test

/**
 * Executes [Migrations.V3_TO_V4], the exact statements [AppDatabase.MIGRATION_3_4]
 * runs, against a real SQLite database built from the shipped version-3 schema
 * and populated with data.
 *
 * What this proves: every statement executes in order; undo journals
 * (`task_runs`, `mutation_records`) come out byte-identical; file rows keep
 * their ids and values; scope tags are carried into `file_scopes`; foreign keys
 * are intact; and every migrated table matches the version-4 schema Room
 * generated from the entities (columns, types, nullability, primary keys,
 * foreign keys, indices).
 *
 * What it does not prove: Room's own identity-hash check at open time, which
 * needs Room's runtime on a device. The structural comparison against
 * `app/schemas/.../4.json` covers the same ground Room's validation does.
 */
class MigrationV3ToV4Test {

    private val schemaDir = listOf(
        File("schemas/com.pocketsteward.app.data.db.AppDatabase"),
        File("app/schemas/com.pocketsteward.app.data.db.AppDatabase"),
    ).first { it.isDirectory }

    private fun schema(version: Int): JsonObject =
        JsonParser.parseString(File(schemaDir, "$version.json").readText())
            .asJsonObject.getAsJsonObject("database")

    private fun JsonObject.entities() = getAsJsonArray("entities").map { it.asJsonObject }

    private fun JsonObject.sql(key: String, table: String) =
        get(key).asString.replace("\${TABLE_NAME}", table)

    private fun openV3(): Connection {
        val db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
        schema(3).entities().forEach { entity ->
            val table = entity.get("tableName").asString
            db.createStatement().use { st ->
                st.execute(entity.sql("createSql", table))
                entity.getAsJsonArray("indices")?.forEach { index ->
                    st.execute(index.asJsonObject.sql("createSql", table))
                }
            }
        }
        return db
    }

    /** Inserts a row filling every NOT NULL column Room declared, with [values] taking precedence. */
    private fun Connection.insert(table: String, values: Map<String, Any?>) {
        val entity = schema(3).entities().first { it.get("tableName").asString == table }
        val row = linkedMapOf<String, Any?>()
        entity.getAsJsonArray("fields").map { it.asJsonObject }.forEach { field ->
            val column = field.get("columnName").asString
            if (column == "id" && column !in values) return@forEach
            val notNull = field.get("notNull")?.asBoolean == true
            val affinity = field.get("affinity").asString
            row[column] = when {
                column in values -> values[column]
                !notNull -> null
                affinity == "INTEGER" || affinity == "REAL" -> 0
                else -> "x"
            }
        }
        val sql = "INSERT INTO `$table` (${row.keys.joinToString { "`$it`" }}) " +
            "VALUES (${row.keys.joinToString { "?" }})"
        prepareStatement(sql).use { st ->
            row.values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    private fun Connection.rows(sql: String): List<List<Any?>> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val n = rs.metaData.columnCount
                buildList { while (rs.next()) add((1..n).map { rs.getObject(it) }) }
            }
        }

    private fun Connection.migrate() {
        autoCommit = false
        createStatement().use { st -> Migrations.V3_TO_V4.forEach(st::execute) }
        commit()
        autoCommit = true
    }

    private fun Connection.populate() {
        repeat(300) { n ->
            insert(
                "file_records",
                mapOf(
                    "stableRef" to "/storage/emulated/0/Download/f$n.txt",
                    "scopeRootRef" to if (n < 200) "/storage/emulated/0/Download" else "/storage/emulated/0/Pictures",
                    "displayName" to "f$n.txt",
                    "extension" to "txt",
                    "absolutePathOrUri" to "/storage/emulated/0/Download/f$n.txt",
                    "sizeBytes" to n.toLong(),
                    "sha256" to "hash$n",
                ),
            )
        }
        insert("task_runs", mapOf("status" to "PARTIAL", "scopeRootRef" to "/storage/emulated/0/Download"))
        repeat(4_829) { n ->
            insert(
                "mutation_records",
                mapOf(
                    "taskRunId" to 1L,
                    "sequence" to n,
                    "operationType" to "MOVE",
                    "sourceBefore" to "D:/storage/emulated/0/Download/f$n",
                    "destinationAfter" to "D:/storage/emulated/0/Download/APKs/f$n",
                    "status" to "COMMITTED",
                    "undoState" to "AVAILABLE",
                ),
            )
        }
    }

    @Test
    fun `migration runs on an empty version 3 database`() {
        openV3().use { db ->
            db.migrate()
            assertThat(db.rows("SELECT COUNT(*) FROM file_scopes").single().single()).isEqualTo(0)
        }
    }

    @Test
    fun `undo journals come through byte identical`() {
        openV3().use { db ->
            db.populate()
            val runs = db.rows("SELECT * FROM task_runs ORDER BY id")
            val journal = db.rows("SELECT * FROM mutation_records ORDER BY id")
            db.migrate()
            assertThat(db.rows("SELECT * FROM task_runs ORDER BY id")).isEqualTo(runs)
            assertThat(db.rows("SELECT * FROM mutation_records ORDER BY id")).isEqualTo(journal)
            assertThat(journal).hasSize(4_829)
        }
    }

    @Test
    fun `file rows keep their ids and values`() {
        openV3().use { db ->
            db.populate()
            val sql = "SELECT id, stableRef, displayName, sizeBytes, sha256 FROM file_records ORDER BY id"
            val before = db.rows(sql)
            db.migrate()
            assertThat(db.rows(sql)).isEqualTo(before)
        }
    }

    @Test
    fun `scope tags are carried into file_scopes`() {
        openV3().use { db ->
            db.populate()
            db.migrate()
            assertThat(db.rows("SELECT scopeRoot, COUNT(*) FROM file_scopes GROUP BY scopeRoot ORDER BY scopeRoot"))
                .containsExactly(
                    listOf("/storage/emulated/0/Download", 200),
                    listOf("/storage/emulated/0/Pictures", 100),
                ).inOrder()
        }
    }

    @Test
    fun `foreign keys are intact and the holding table is gone`() {
        openV3().use { db ->
            db.populate()
            db.migrate()
            assertThat(db.rows("PRAGMA foreign_key_check")).isEmpty()
            assertThat(db.rows("SELECT name FROM sqlite_master WHERE name = 'file_scopes_legacy'")).isEmpty()
        }
    }

    @Test
    fun `every migrated table matches the schema Room expects at version 4`() {
        openV3().use { db ->
            db.populate()
            db.migrate()
            schema(4).entities().forEach { entity ->
                val table = entity.get("tableName").asString
                val columns = db.rows("PRAGMA table_info(`$table`)")
                    .associate { it[1] as String to it }
                val expected = entity.getAsJsonArray("fields").map { it.asJsonObject }
                assertWithMessage("columns of $table").that(columns.keys)
                    .containsExactlyElementsIn(expected.map { it.get("columnName").asString })
                expected.forEach { field ->
                    val name = field.get("columnName").asString
                    val row = columns.getValue(name)
                    assertWithMessage("$table.$name type").that((row[2] as String).uppercase())
                        .isEqualTo(field.get("affinity").asString.uppercase())
                    assertWithMessage("$table.$name notNull").that((row[3] as Int) == 1)
                        .isEqualTo(field.get("notNull")?.asBoolean == true)
                }
                val primaryKey = columns.values.filter { (it[5] as Int) > 0 }
                    .sortedBy { it[5] as Int }.map { it[1] as String }
                assertWithMessage("primary key of $table").that(primaryKey).isEqualTo(
                    entity.getAsJsonObject("primaryKey").getAsJsonArray("columnNames").map { it.asString },
                )
                val foreignKeys = db.rows("PRAGMA foreign_key_list(`$table`)")
                    .map { listOf(it[2], it[3], it[4], it[6]) }
                val expectedForeignKeys = entity.getAsJsonArray("foreignKeys")?.map {
                    val fk = it.asJsonObject
                    listOf(
                        fk.get("table").asString,
                        fk.getAsJsonArray("columns").single().asString,
                        fk.getAsJsonArray("referencedColumns").single().asString,
                        fk.get("onDelete").asString,
                    )
                }.orEmpty()
                assertWithMessage("foreign keys of $table").that(foreignKeys)
                    .containsExactlyElementsIn(expectedForeignKeys)
                val indices = db.rows("PRAGMA index_list(`$table`)").map { it[1] as String }
                    .filterNot { it.startsWith("sqlite_autoindex") }
                val expectedIndices = entity.getAsJsonArray("indices")
                    ?.map { it.asJsonObject.get("name").asString }.orEmpty()
                assertWithMessage("indices of $table").that(indices).containsExactlyElementsIn(expectedIndices)
            }
        }
    }
}
