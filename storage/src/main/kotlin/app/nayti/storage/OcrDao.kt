package app.nayti.storage

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

@Dao
interface OcrDao {
    @Query("SELECT * FROM ocr_document WHERE assetId = :assetId")
    suspend fun document(assetId: Long): OcrDocumentEntity?

    @Query("SELECT * FROM ocr_region WHERE assetId = :assetId ORDER BY ordinal")
    suspend fun regions(assetId: Long): List<OcrRegionEntity>

    @Query("SELECT * FROM index_channel_work WHERE leaseToken = :leaseToken")
    suspend fun workByLease(leaseToken: String): IndexChannelWorkEntity?

    @Query("SELECT * FROM catalog_asset WHERE assetId = :assetId")
    suspend fun catalogAsset(assetId: Long): CatalogAssetEntity?

    @Query("SELECT * FROM catalog_access_observation WHERE singletonId = 1")
    suspend fun accessObservation(): CatalogAccessObservationEntity?

    @Query("SELECT * FROM index_channel_publication WHERE publicationToken = :publicationToken")
    suspend fun publicationByToken(publicationToken: String): IndexChannelPublicationEntity?

    @Query("SELECT * FROM index_publication_clock WHERE singletonId = 1")
    suspend fun publicationClock(): IndexPublicationClockEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePublication(publication: IndexChannelPublicationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replacePublicationClock(clock: IndexPublicationClockEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocument(document: OcrDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRegions(regions: List<OcrRegionEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLexical(row: OcrLexicalFtsEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertTrigram(row: OcrTrigramFtsEntity)

    @Query("DELETE FROM ocr_lexical_fts WHERE rowid = :assetId")
    suspend fun deleteLexical(assetId: Long): Int

    @Query("DELETE FROM ocr_trigram_fts WHERE rowid = :assetId")
    suspend fun deleteTrigram(assetId: Long): Int

    @Query("DELETE FROM ocr_region WHERE assetId = :assetId")
    suspend fun deleteRegions(assetId: Long): Int

    @Query("DELETE FROM ocr_document WHERE assetId = :assetId")
    suspend fun deleteDocument(assetId: Long): Int

    @Query(
        "UPDATE index_channel_work SET state = 'DONE', leaseToken = NULL, leaseExpiresAtMillis = NULL, " +
            "executionWindowId = NULL, publicationToken = :publicationToken, stagedArtifactPath = NULL, " +
            "stagedArtifactLength = NULL, stagedArtifactSha256 = NULL, nextEligibleAtMillis = NULL, " +
            "errorCode = NULL, updatedAtMillis = :nowMillis " +
            "WHERE assetId = :assetId AND channel = 'OCR' AND state = 'RUNNING' " +
            "AND leaseToken = :leaseToken AND leaseExpiresAtMillis > :nowMillis " +
            "AND sourceFingerprint = :sourceFingerprint AND accessRevision = :accessRevision " +
            "AND pipelineVersion = :pipelineVersion AND componentHash = :componentHash",
    )
    suspend fun completeOcrWork(
        assetId: Long,
        leaseToken: String,
        publicationToken: String,
        sourceFingerprint: String,
        accessRevision: Long,
        pipelineVersion: String,
        componentHash: String,
        nowMillis: Long,
    ): Int

    @Query(
        "SELECT ocr_document.assetId AS assetId, " +
            "bm25(ocr_lexical_fts, 1.0, 0.8, 1.2) AS score " +
            "FROM ocr_lexical_fts " +
            "INNER JOIN ocr_document ON ocr_document.assetId = ocr_lexical_fts.rowid " +
            "INNER JOIN catalog_asset ON catalog_asset.assetId = ocr_document.assetId " +
            "INNER JOIN index_channel_work ON index_channel_work.assetId = ocr_document.assetId " +
            "AND index_channel_work.channel = 'OCR' " +
            "INNER JOIN catalog_access_observation ON catalog_access_observation.singletonId = 1 " +
            "WHERE ocr_lexical_fts MATCH :matchQuery " +
            "AND catalog_asset.availability = 'AVAILABLE' " +
            "AND catalog_asset.sourceFingerprint = ocr_document.sourceFingerprint " +
            "AND index_channel_work.state = 'DONE' " +
            "AND index_channel_work.sourceFingerprint = ocr_document.sourceFingerprint " +
            "AND index_channel_work.accessRevision = ocr_document.accessRevision " +
            "AND index_channel_work.pipelineVersion = :pipelineVersion " +
            "AND index_channel_work.componentHash = :componentHash " +
            "AND ocr_document.pipelineVersion = :pipelineVersion " +
            "AND ocr_document.componentHash = :componentHash " +
            "AND ocr_document.accessRevision = catalog_access_observation.processAccessRevision " +
            "AND catalog_access_observation.accessScope != 'None' " +
            "AND ocr_document.publicationEpoch <= :maximumPublicationEpoch " +
            "ORDER BY score, ocr_document.assetId LIMIT :limit",
    )
    suspend fun lexicalCandidatesRow(
        matchQuery: String,
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
        limit: Int,
    ): List<OcrLexicalCandidate>

    @Query(
        "SELECT ocr_document.assetId AS assetId, rank AS score " +
            "FROM ocr_trigram_fts " +
            "INNER JOIN ocr_document ON ocr_document.assetId = ocr_trigram_fts.rowid " +
            "INNER JOIN catalog_asset ON catalog_asset.assetId = ocr_document.assetId " +
            "INNER JOIN index_channel_work ON index_channel_work.assetId = ocr_document.assetId " +
            "AND index_channel_work.channel = 'OCR' " +
            "INNER JOIN catalog_access_observation ON catalog_access_observation.singletonId = 1 " +
            "WHERE ocr_trigram_fts MATCH :matchQuery " +
            "AND catalog_asset.availability = 'AVAILABLE' " +
            "AND catalog_asset.sourceFingerprint = ocr_document.sourceFingerprint " +
            "AND index_channel_work.state = 'DONE' " +
            "AND index_channel_work.sourceFingerprint = ocr_document.sourceFingerprint " +
            "AND index_channel_work.accessRevision = ocr_document.accessRevision " +
            "AND index_channel_work.pipelineVersion = :pipelineVersion " +
            "AND index_channel_work.componentHash = :componentHash " +
            "AND ocr_document.pipelineVersion = :pipelineVersion " +
            "AND ocr_document.componentHash = :componentHash " +
            "AND ocr_document.accessRevision = catalog_access_observation.processAccessRevision " +
            "AND catalog_access_observation.accessScope != 'None' " +
            "AND ocr_document.publicationEpoch <= :maximumPublicationEpoch " +
            "ORDER BY score, ocr_document.assetId LIMIT :limit",
    )
    suspend fun trigramCandidatesRow(
        matchQuery: String,
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
        limit: Int,
    ): List<OcrLexicalCandidate>

    @Transaction
    suspend fun commitOcrPublication(
        leaseToken: String,
        publicationToken: String,
        expectedIdentity: OcrPayloadIdentity,
        document: OcrDocumentDraft,
        regions: List<OcrRegionDraft>,
        nowMillis: Long,
    ): IndexChannelPublicationEntity? {
        require(PublicationToken.matches(publicationToken))
        require(Sha256.matches(expectedIdentity.sha256) && expectedIdentity.byteLength >= 0)
        val actualIdentity = OcrPublicationCodec.identity(document, regions)
        require(actualIdentity == expectedIdentity)
        val work = workByLease(leaseToken) ?: return null
        if (
            work.channel != IndexChannel.OCR ||
            work.state != IndexWorkState.RUNNING ||
            (work.leaseExpiresAtMillis ?: 0) <= nowMillis
        ) {
            return null
        }
        val asset = catalogAsset(work.assetId) ?: return null
        val access = accessObservation() ?: return null
        if (
            document.assetId != work.assetId ||
            document.sourceFingerprint != work.sourceFingerprint ||
            document.accessRevision != work.accessRevision ||
            document.pipelineVersion != work.pipelineVersion ||
            document.componentHash != work.componentHash ||
            asset.availability != CatalogAvailability.AVAILABLE ||
            asset.sourceFingerprint != work.sourceFingerprint ||
            access.processAccessRevision != work.accessRevision ||
            access.accessScope == "None"
        ) {
            return null
        }
        if (publicationByToken(publicationToken) != null) return null
        val epoch = Math.addExact(publicationClock()?.lastEpoch ?: 0, 1)
        val publication =
            IndexChannelPublicationEntity(
                assetId = work.assetId,
                channel = IndexChannel.OCR,
                publicationToken = publicationToken,
                sourceFingerprint = work.sourceFingerprint,
                accessRevision = work.accessRevision,
                pipelineVersion = work.pipelineVersion,
                componentHash = work.componentHash,
                resultSha256 = actualIdentity.sha256,
                resultLength = actualIdentity.byteLength,
                publicationEpoch = epoch,
                publishedAtMillis = nowMillis,
            )
        deleteLexical(work.assetId)
        deleteTrigram(work.assetId)
        deleteRegions(work.assetId)
        deleteDocument(work.assetId)
        insertDocument(document.toEntity(publication, regions.size))
        if (regions.isNotEmpty()) {
            insertRegions(regions.mapIndexed { ordinal, region -> region.toEntity(document.assetId, ordinal) })
        }
        insertLexical(
            OcrLexicalFtsEntity(
                assetId = document.assetId,
                canonical = document.canonicalText,
                stems = document.stemText,
                identifiers = document.identifierText,
            ),
        )
        insertTrigram(OcrTrigramFtsEntity(document.assetId, document.canonicalText))
        replacePublication(publication)
        check(
            completeOcrWork(
                assetId = work.assetId,
                leaseToken = leaseToken,
                publicationToken = publicationToken,
                sourceFingerprint = work.sourceFingerprint,
                accessRevision = work.accessRevision,
                pipelineVersion = work.pipelineVersion,
                componentHash = work.componentHash,
                nowMillis = nowMillis,
            ) == 1,
        )
        replacePublicationClock(IndexPublicationClockEntity(lastEpoch = epoch))
        return publication
    }

    @Transaction
    suspend fun lexicalCandidates(
        matchQuery: String,
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
        limit: Int,
    ): List<OcrLexicalCandidate> {
        validateSearch(matchQuery, pipelineVersion, componentHash, maximumPublicationEpoch, limit)
        return lexicalCandidatesRow(matchQuery, pipelineVersion, componentHash, maximumPublicationEpoch, limit)
    }

    @Transaction
    suspend fun trigramCandidates(
        matchQuery: String,
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
        limit: Int,
    ): List<OcrLexicalCandidate> {
        validateSearch(matchQuery, pipelineVersion, componentHash, maximumPublicationEpoch, limit)
        return trigramCandidatesRow(matchQuery, pipelineVersion, componentHash, maximumPublicationEpoch, limit)
    }

    private fun validateSearch(
        matchQuery: String,
        pipelineVersion: String,
        componentHash: String,
        maximumPublicationEpoch: Long,
        limit: Int,
    ) {
        require(matchQuery.isNotBlank() && matchQuery.length <= MaximumMatchCharacters)
        require(ContractValue.matches(pipelineVersion))
        require(Sha256.matches(componentHash))
        require(maximumPublicationEpoch >= 0)
        require(limit in 1..MaximumCandidates)
    }

    private fun OcrDocumentDraft.toEntity(
        publication: IndexChannelPublicationEntity,
        regions: Int,
    ): OcrDocumentEntity =
        OcrDocumentEntity(
            assetId = assetId,
            sourceFingerprint = sourceFingerprint,
            accessRevision = accessRevision,
            pipelineVersion = pipelineVersion,
            componentHash = componentHash,
            publicationToken = publication.publicationToken,
            publicationEpoch = publication.publicationEpoch,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            rawText = rawText,
            displayText = displayText,
            canonicalText = canonicalText,
            stemText = stemText,
            identifierText = identifierText,
            hasRecognizedText = canonicalText.isNotBlank(),
            regionCount = regions,
            normalizerVersion = normalizerVersion,
            stemmerVersion = stemmerVersion,
            identifierVersion = identifierVersion,
            publishedAtMillis = publication.publishedAtMillis,
        )

    private fun OcrRegionDraft.toEntity(assetId: Long, ordinal: Int): OcrRegionEntity =
        OcrRegionEntity(
            assetId = assetId,
            ordinal = ordinal,
            rawText = rawText,
            displayText = displayText,
            canonicalText = canonicalText,
            confidenceMicros = confidenceMicros,
            x0Micros = x0Micros,
            y0Micros = y0Micros,
            x1Micros = x1Micros,
            y1Micros = y1Micros,
            x2Micros = x2Micros,
            y2Micros = y2Micros,
            x3Micros = x3Micros,
            y3Micros = y3Micros,
        )

    companion object {
        private val PublicationToken = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")
        private val ContractValue = Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}")
        private val Sha256 = Regex("[0-9a-f]{64}")
        const val MaximumMatchCharacters = 1_024
        const val MaximumCandidates = 256
    }
}
