package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface OcrSemanticDao {
    @Query("SELECT * FROM ocr_document WHERE assetId = :assetId")
    suspend fun ocrDocument(assetId: Long): OcrDocumentEntity?

    @Query("SELECT * FROM ocr_region WHERE assetId = :assetId ORDER BY ordinal")
    suspend fun ocrRegions(assetId: Long): List<OcrRegionEntity>

    @Query("SELECT COALESCE(MAX(publicationEpoch), 0) FROM ocr_document")
    suspend fun maximumOcrPublicationEpoch(): Long

    @Query("SELECT COALESCE((SELECT catalogRevision FROM catalog_watermark WHERE singletonId = 1), 0)")
    suspend fun catalogRevision(): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunkSetIfAbsent(chunkSet: OcrSemanticChunkSetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunksIfAbsent(chunks: List<OcrSemanticChunkEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertChunkLinesIfAbsent(lines: List<OcrSemanticChunkLineEntity>): List<Long>

    @Query("SELECT * FROM ocr_semantic_chunk_set WHERE chunkSetId = :chunkSetId")
    suspend fun chunkSet(chunkSetId: String): OcrSemanticChunkSetEntity?

    @Query("SELECT * FROM ocr_semantic_chunk WHERE chunkSetId = :chunkSetId ORDER BY ordinal")
    suspend fun chunks(chunkSetId: String): List<OcrSemanticChunkEntity>

    @Query("SELECT * FROM ocr_semantic_chunk WHERE chunkId = :chunkId")
    suspend fun chunk(chunkId: String): OcrSemanticChunkEntity?

    @Query("SELECT * FROM ocr_semantic_chunk_line WHERE chunkId = :chunkId ORDER BY position")
    suspend fun chunkLines(chunkId: String): List<OcrSemanticChunkLineEntity>

    @Transaction
    suspend fun publishChunkSet(materialization: OcrSemanticChunkMaterialization): OcrSemanticChunkSetEntity? {
        val expected =
            OcrSemanticChunkCodec.materialize(
                OcrSemanticChunkSetDraft(
                    assetId = materialization.chunkSet.assetId,
                    sourceFingerprint = materialization.chunkSet.sourceFingerprint,
                    ocrPublicationToken = materialization.chunkSet.ocrPublicationToken,
                    chunkingVersion = materialization.chunkSet.chunkingVersion,
                    chunks = materialization.chunks.map { chunk ->
                        val lines = materialization.lines.filter { it.chunkId == chunk.chunkId }.sortedBy { it.position }
                        OcrSemanticChunkPayload(
                            ordinal = chunk.ordinal,
                            kind = chunk.kind,
                            displayText = chunk.displayText,
                            contentTokenCount = chunk.contentTokenCount,
                            lineOrdinals = lines.map(OcrSemanticChunkLineEntity::lineOrdinal),
                            meanConfidenceMicros = chunk.meanConfidenceMicros,
                            reliableAlphabeticWordCount = chunk.reliableAlphabeticWordCount,
                        )
                    },
                ),
                createdAtMillis = materialization.chunkSet.createdAtMillis,
            )
        require(materialization == expected)

        val document = ocrDocument(materialization.chunkSet.assetId) ?: return null
        if (
            document.sourceFingerprint != materialization.chunkSet.sourceFingerprint ||
            document.publicationToken != materialization.chunkSet.ocrPublicationToken
        ) {
            return null
        }
        val regionOrdinals = ocrRegions(document.assetId).mapTo(mutableSetOf(), OcrRegionEntity::ordinal)
        if (!materialization.lines.all { line ->
            line.assetId == document.assetId && line.lineOrdinal in regionOrdinals
        }) {
            return null
        }

        insertChunkSetIfAbsent(materialization.chunkSet)
        val storedSet = checkNotNull(chunkSet(materialization.chunkSet.chunkSetId))
        check(
            storedSet.copy(createdAtMillis = materialization.chunkSet.createdAtMillis) ==
                materialization.chunkSet,
        )
        if (materialization.chunks.isNotEmpty()) insertChunksIfAbsent(materialization.chunks)
        val storedChunks = chunks(materialization.chunkSet.chunkSetId)
        check(storedChunks.size == materialization.chunks.size)
        check(
            storedChunks.mapIndexed { index, chunk ->
                chunk.copy(createdAtMillis = materialization.chunks[index].createdAtMillis)
            } == materialization.chunks,
        )
        if (materialization.lines.isNotEmpty()) insertChunkLinesIfAbsent(materialization.lines)
        materialization.chunks.forEach { chunk ->
            val expectedLines = materialization.lines.filter { it.chunkId == chunk.chunkId }.sortedBy { it.position }
            check(chunkLines(chunk.chunkId) == expectedLines)
        }
        return storedSet
    }
}
