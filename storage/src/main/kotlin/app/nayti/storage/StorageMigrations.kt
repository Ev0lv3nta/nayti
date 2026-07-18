package app.nayti.storage

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

object StorageMigrations {
    val From1To2: Migration =
        object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ocr_semantic_chunk_set` (" +
                        "`chunkSetId` TEXT NOT NULL, `assetId` INTEGER NOT NULL, " +
                        "`sourceFingerprint` TEXT NOT NULL, `ocrPublicationToken` TEXT NOT NULL, " +
                        "`chunkingVersion` TEXT NOT NULL, `chunkCount` INTEGER NOT NULL, " +
                        "`payloadSha256` TEXT NOT NULL, `payloadByteLength` INTEGER NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`chunkSetId`))",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_semantic_chunk_set_ocrPublicationToken_chunkingVersion` " +
                        "ON `ocr_semantic_chunk_set` (`ocrPublicationToken`, `chunkingVersion`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_ocr_semantic_chunk_set_assetId_sourceFingerprint` " +
                        "ON `ocr_semantic_chunk_set` (`assetId`, `sourceFingerprint`)",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ocr_semantic_chunk` (" +
                        "`chunkId` TEXT NOT NULL, `chunkSetId` TEXT NOT NULL, `assetId` INTEGER NOT NULL, " +
                        "`sourceFingerprint` TEXT NOT NULL, `ocrPublicationToken` TEXT NOT NULL, " +
                        "`ordinal` INTEGER NOT NULL, `kind` TEXT NOT NULL, `displayText` TEXT NOT NULL, " +
                        "`contentTokenCount` INTEGER NOT NULL, `firstLineOrdinal` INTEGER NOT NULL, " +
                        "`lastLineOrdinal` INTEGER NOT NULL, `meanConfidenceMicros` INTEGER NOT NULL, " +
                        "`reliableAlphabeticWordCount` INTEGER NOT NULL, `chunkingVersion` TEXT NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, PRIMARY KEY(`chunkId`))",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_ocr_semantic_chunk_chunkSetId_ordinal` " +
                        "ON `ocr_semantic_chunk` (`chunkSetId`, `ordinal`)",
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

    val From2To3: Migration =
        object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `vector_segment_artifact` " +
                        "ADD COLUMN `compactionLevel` INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

    val From3To4: Migration =
        object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `perceptual_hash_result` (" +
                        "`assetId` INTEGER NOT NULL, `sourceFingerprint` TEXT NOT NULL, " +
                        "`accessRevision` INTEGER NOT NULL, `pipelineVersion` TEXT NOT NULL, " +
                        "`componentHash` TEXT NOT NULL, `hashBits` INTEGER NOT NULL, " +
                        "`publicationEpoch` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`assetId`, `publicationEpoch`))",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_perceptual_hash_result_assetId_sourceFingerprint` " +
                        "ON `perceptual_hash_result` (`assetId`, `sourceFingerprint`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_perceptual_hash_result_pipelineVersion_componentHash_publicationEpoch` " +
                        "ON `perceptual_hash_result` (`pipelineVersion`, `componentHash`, `publicationEpoch`)",
                )
            }
        }

    val From4To5: Migration =
        object : Migration(4, 5) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE `activation_snapshot` " +
                        "ADD COLUMN `formatVersion` INTEGER NOT NULL DEFAULT 1",
                )
                connection.execSQL(
                    "ALTER TABLE `activation_snapshot` " +
                        "ADD COLUMN `capturedAccessRevision` INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE `active_snapshot_pointer` " +
                        "ADD COLUMN `rollbackSnapshotId` TEXT",
                )
                connection.execSQL(
                    "ALTER TABLE `active_snapshot_pointer` " +
                        "ADD COLUMN `activationSequence` INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE `active_snapshot_pointer` " +
                        "ADD COLUMN `updatedAtMillis` INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `activation_candidate` (" +
                        "`candidateId` TEXT NOT NULL, `snapshotId` TEXT NOT NULL, " +
                        "`parentSnapshotId` TEXT, `packId` TEXT NOT NULL, `packVersion` TEXT NOT NULL, " +
                        "`packManifestSha256` TEXT NOT NULL, `capturedAccessRevision` INTEGER NOT NULL, " +
                        "`capturedCatalogWatermark` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
                        "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                        "`failureCode` TEXT, PRIMARY KEY(`candidateId`))",
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_activation_candidate_snapshotId` " +
                        "ON `activation_candidate` (`snapshotId`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_activation_candidate_state_updatedAtMillis` " +
                        "ON `activation_candidate` (`state`, `updatedAtMillis`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_activation_candidate_packId_packVersion` " +
                        "ON `activation_candidate` (`packId`, `packVersion`)",
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_activation_candidate_parentSnapshotId` " +
                        "ON `activation_candidate` (`parentSnapshotId`)",
                )
            }
        }

    val All: Array<Migration> = arrayOf(From1To2, From2To3, From3To4, From4To5)
}
