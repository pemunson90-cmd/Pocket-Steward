package com.pocketsteward.app.data.db

/**
 * Room schema migrations as plain SQL strings with no Android imports.
 *
 * They live here rather than inline in [AppDatabase] so a JVM unit test can
 * execute the exact statements the app runs against a real SQLite database
 * holding version-3 data (see `MigrationV3ToV4Test`). The app has no
 * instrumented migration test, so without this the only proof a migration
 * preserves undo journals would be installing it on a phone that holds them.
 *
 * Statements run in list order inside the transaction Room wraps a migration
 * in. Moved here verbatim from the original inline `execSQL` calls.
 */
object Migrations {

    /**
     * Version 3 to 4: `FileRecord.scopeRootRef` becomes the `file_scopes`
     * join table so a file can belong to more than one scanned scope. The
     * old one-scope mapping is preserved through a holding table while
     * `file_records` is rebuilt without the column. `task_runs` and
     * `mutation_records` are never named, so the undo journals cannot be
     * touched by it.
     */
    val V3_TO_V4: List<String> = listOf(
        """
        CREATE TABLE `file_scopes_legacy` (
            `fileRef` TEXT NOT NULL,
            `scopeRoot` TEXT NOT NULL,
            PRIMARY KEY(`fileRef`, `scopeRoot`)
        )
        """.trimIndent(),
        """
        INSERT OR IGNORE INTO `file_scopes_legacy` (`fileRef`, `scopeRoot`)
        SELECT `stableRef`, `scopeRootRef` FROM `file_records`
        """.trimIndent(),
        """
        CREATE TABLE `file_records_new` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `stableRef` TEXT NOT NULL,
            `displayName` TEXT NOT NULL,
            `extension` TEXT NOT NULL,
            `mimeType` TEXT,
            `absolutePathOrUri` TEXT NOT NULL,
            `parentRef` TEXT,
            `sizeBytes` INTEGER NOT NULL,
            `createdAt` INTEGER,
            `modifiedAt` INTEGER,
            `lastScannedAt` INTEGER NOT NULL,
            `isDirectory` INTEGER NOT NULL,
            `isHidden` INTEGER NOT NULL,
            `mediaType` TEXT,
            `width` INTEGER,
            `height` INTEGER,
            `durationMs` INTEGER,
            `apkPackageName` TEXT,
            `apkVersionName` TEXT,
            `sha256` TEXT,
            `quickFingerprint` TEXT,
            `textPreview` TEXT,
            `classification` TEXT,
            `classificationConfidence` REAL
        )
        """.trimIndent(),
        """
        INSERT INTO `file_records_new` (
            `id`, `stableRef`, `displayName`, `extension`, `mimeType`,
            `absolutePathOrUri`, `parentRef`, `sizeBytes`, `createdAt`,
            `modifiedAt`, `lastScannedAt`, `isDirectory`, `isHidden`,
            `mediaType`, `width`, `height`, `durationMs`, `apkPackageName`,
            `apkVersionName`, `sha256`, `quickFingerprint`, `textPreview`,
            `classification`, `classificationConfidence`
        )
        SELECT
            `id`, `stableRef`, `displayName`, `extension`, `mimeType`,
            `absolutePathOrUri`, `parentRef`, `sizeBytes`, `createdAt`,
            `modifiedAt`, `lastScannedAt`, `isDirectory`, `isHidden`,
            `mediaType`, `width`, `height`, `durationMs`, `apkPackageName`,
            `apkVersionName`, `sha256`, `quickFingerprint`, `textPreview`,
            `classification`, `classificationConfidence`
        FROM `file_records`
        """.trimIndent(),
        "DROP TABLE `file_records`",
        "ALTER TABLE `file_records_new` RENAME TO `file_records`",
        "CREATE UNIQUE INDEX `index_file_records_stableRef` ON `file_records` (`stableRef`)",
        """
        CREATE TABLE `file_scopes` (
            `fileRef` TEXT NOT NULL,
            `scopeRoot` TEXT NOT NULL,
            PRIMARY KEY(`fileRef`, `scopeRoot`),
            FOREIGN KEY(`fileRef`) REFERENCES `file_records`(`stableRef`)
                ON UPDATE NO ACTION ON DELETE CASCADE
        )
        """.trimIndent(),
        "CREATE INDEX `index_file_scopes_fileRef` ON `file_scopes` (`fileRef`)",
        """
        INSERT INTO `file_scopes` (`fileRef`, `scopeRoot`)
        SELECT `fileRef`, `scopeRoot` FROM `file_scopes_legacy`
        """.trimIndent(),
        "DROP TABLE `file_scopes_legacy`",
    )
}
