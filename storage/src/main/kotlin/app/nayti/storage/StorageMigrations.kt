package app.nayti.storage

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object StorageMigrations {
    val From1To2: Migration =
        object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ocr_semantic_chunk` (" +
                        "`chunkId` TEXT NOT NULL, `assetId` INTEGER NOT NULL, " +
                        "`sourceFingerprint` TEXT NOT NULL, `ocrPublicationToken` TEXT NOT NULL, " +
                        "`ordinal` INTEGER NOT NULL, `kind` TEXT NOT NULL, `displayText` TEXT NOT NULL, " +
                        "`contentTokenCount` INTEGER NOT NULL, `firstLineOrdinal` INTEGER NOT NULL, " +
                        "`lastLineOrdinal` INTEGER NOT NULL, `meanConfidenceMicros` INTEGER NOT NULL, " +
                        "`reliableAlphabeticWordCount` INTEGER NOT NULL, `chunkingVersion` TEXT NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`chunkId`))",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_semantic_chunk_ocrPublicationToken_chunkingVersion_ordinal` " +
                        "ON `ocr_semantic_chunk` (`ocrPublicationToken`, `chunkingVersion`, `ordinal`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ocr_semantic_chunk_assetId_sourceFingerprint` " +
                        "ON `ocr_semantic_chunk` (`assetId`, `sourceFingerprint`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ocr_semantic_chunk_ocrPublicationToken` " +
                        "ON `ocr_semantic_chunk` (`ocrPublicationToken`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ocr_semantic_chunk_line` (" +
                        "`chunkId` TEXT NOT NULL, `position` INTEGER NOT NULL, `assetId` INTEGER NOT NULL, " +
                        "`lineOrdinal` INTEGER NOT NULL, PRIMARY KEY(`chunkId`, `position`))",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_semantic_chunk_line_chunkId_lineOrdinal` " +
                        "ON `ocr_semantic_chunk_line` (`chunkId`, `lineOrdinal`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ocr_semantic_chunk_line_assetId_lineOrdinal` " +
                        "ON `ocr_semantic_chunk_line` (`assetId`, `lineOrdinal`)",
                )
                connection.execSQL(
                    "ALTER TABLE `vector_segment_record` ADD COLUMN `semanticChunkId` TEXT",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_vector_segment_record_semanticChunkId` " +
                        "ON `vector_segment_record` (`semanticChunkId`)",
                )
            }
        }

    val All: Array<Migration> = arrayOf(From1To2)
}
