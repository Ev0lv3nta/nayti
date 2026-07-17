package app.nayti.storage

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class OcrSemanticChunkPayload(
    val ordinal: Int,
    val kind: String,
    val displayText: String,
    val contentTokenCount: Int,
    val lineOrdinals: List<Int>,
    val meanConfidenceMicros: Int,
    val reliableAlphabeticWordCount: Int,
)

data class OcrSemanticChunkSetDraft(
    val assetId: Long,
    val sourceFingerprint: String,
    val ocrPublicationToken: String,
    val chunkingVersion: String,
    val chunks: List<OcrSemanticChunkPayload>,
)

data class OcrSemanticChunkMaterialization(
    val chunkSet: OcrSemanticChunkSetEntity,
    val chunks: List<OcrSemanticChunkEntity>,
    val lines: List<OcrSemanticChunkLineEntity>,
)

/** Canonical identity for immutable semantic inputs, including the valid empty-set result. */
object OcrSemanticChunkCodec {
    fun materialize(
        draft: OcrSemanticChunkSetDraft,
        createdAtMillis: Long,
    ): OcrSemanticChunkMaterialization {
        validate(draft, createdAtMillis)
        val identifiedChunks = draft.chunks.map { payload -> payload to chunkIdentity(draft, payload) }
        val setWriter = DigestWriter()
        setWriter.bytes(SetMagic)
        setWriter.long(draft.assetId)
        setWriter.string(draft.sourceFingerprint)
        setWriter.string(draft.ocrPublicationToken)
        setWriter.string(draft.chunkingVersion)
        setWriter.int(identifiedChunks.size)
        identifiedChunks.forEach { (_, identity) -> setWriter.string(identity.sha256) }
        val setIdentity = setWriter.finish()

        val chunkSet =
            OcrSemanticChunkSetEntity(
                chunkSetId = setIdentity.sha256,
                assetId = draft.assetId,
                sourceFingerprint = draft.sourceFingerprint,
                ocrPublicationToken = draft.ocrPublicationToken,
                chunkingVersion = draft.chunkingVersion,
                chunkCount = draft.chunks.size,
                payloadSha256 = setIdentity.sha256,
                payloadByteLength = setIdentity.byteLength,
                createdAtMillis = createdAtMillis,
            )
        val chunks =
            identifiedChunks.map { (payload, identity) ->
                OcrSemanticChunkEntity(
                    chunkId = identity.sha256,
                    chunkSetId = setIdentity.sha256,
                    assetId = draft.assetId,
                    sourceFingerprint = draft.sourceFingerprint,
                    ocrPublicationToken = draft.ocrPublicationToken,
                    ordinal = payload.ordinal,
                    kind = payload.kind,
                    displayText = payload.displayText,
                    contentTokenCount = payload.contentTokenCount,
                    firstLineOrdinal = payload.lineOrdinals.first(),
                    lastLineOrdinal = payload.lineOrdinals.last(),
                    meanConfidenceMicros = payload.meanConfidenceMicros,
                    reliableAlphabeticWordCount = payload.reliableAlphabeticWordCount,
                    chunkingVersion = draft.chunkingVersion,
                    createdAtMillis = createdAtMillis,
                )
            }
        val lines =
            identifiedChunks.flatMap { (payload, identity) ->
                payload.lineOrdinals.mapIndexed { position, lineOrdinal ->
                    OcrSemanticChunkLineEntity(
                        chunkId = identity.sha256,
                        position = position,
                        assetId = draft.assetId,
                        lineOrdinal = lineOrdinal,
                    )
                }
            }
        return OcrSemanticChunkMaterialization(chunkSet, chunks, lines)
    }

    private fun chunkIdentity(
        draft: OcrSemanticChunkSetDraft,
        payload: OcrSemanticChunkPayload,
    ): OcrPayloadIdentity {
        val writer = DigestWriter()
        writer.bytes(ChunkMagic)
        writer.long(draft.assetId)
        writer.string(draft.sourceFingerprint)
        writer.string(draft.ocrPublicationToken)
        writer.string(draft.chunkingVersion)
        writer.int(payload.ordinal)
        writer.string(payload.kind)
        writer.string(payload.displayText)
        writer.int(payload.contentTokenCount)
        writer.int(payload.lineOrdinals.size)
        payload.lineOrdinals.forEach(writer::int)
        writer.int(payload.meanConfidenceMicros)
        writer.int(payload.reliableAlphabeticWordCount)
        return writer.finish()
    }

    private fun validate(draft: OcrSemanticChunkSetDraft, createdAtMillis: Long) {
        require(draft.assetId > 0)
        require(Sha256.matches(draft.sourceFingerprint))
        require(Identifier.matches(draft.ocrPublicationToken))
        require(ContractValue.matches(draft.chunkingVersion))
        require(createdAtMillis >= 0)
        require(draft.chunks.size <= MaximumChunks)
        require(draft.chunks.map(OcrSemanticChunkPayload::ordinal) == draft.chunks.indices.toList())
        draft.chunks.forEach { chunk ->
            require(chunk.kind in ChunkKinds)
            require(chunk.displayText.isNotBlank() && chunk.displayText.length <= MaximumChunkCharacters)
            require(chunk.contentTokenCount in 1..MaximumContentTokens)
            require(chunk.lineOrdinals.isNotEmpty() && chunk.lineOrdinals.size <= MaximumLinesPerChunk)
            require(chunk.lineOrdinals.all { it >= 0 })
            require(chunk.lineOrdinals.zipWithNext().all { (left, right) -> left < right })
            require(chunk.meanConfidenceMicros in 0..Micros)
            require(chunk.reliableAlphabeticWordCount in 3..MaximumWordsPerChunk)
        }
    }

    private class DigestWriter {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var length = 0L

        fun bytes(value: ByteArray) {
            digest.update(value)
            length = Math.addExact(length, value.size.toLong())
            require(length <= MaximumPayloadBytes)
        }

        fun string(value: String) {
            val encoded = value.toByteArray(StandardCharsets.UTF_8)
            int(encoded.size)
            bytes(encoded)
        }

        fun int(value: Int) {
            bytes(
                byteArrayOf(
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte(),
                ),
            )
        }

        fun long(value: Long) {
            bytes(
                byteArrayOf(
                    (value ushr 56).toByte(),
                    (value ushr 48).toByte(),
                    (value ushr 40).toByte(),
                    (value ushr 32).toByte(),
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte(),
                ),
            )
        }

        fun finish(): OcrPayloadIdentity =
            OcrPayloadIdentity(
                sha256 = digest.digest().joinToString("") { byte -> "%02x".format(byte) },
                byteLength = length,
            )
    }

    private val SetMagic = "NAYTISEMSET1".toByteArray(StandardCharsets.US_ASCII)
    private val ChunkMagic = "NAYTISEMCHK1".toByteArray(StandardCharsets.US_ASCII)
    private val Sha256 = Regex("[0-9a-f]{64}")
    private val Identifier = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,95}")
    private val ContractValue = Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}")
    private val ChunkKinds = setOf("HEADER", "BODY")
    private const val MaximumChunks = 32
    private const val MaximumContentTokens = 96
    private const val MaximumLinesPerChunk = 512
    private const val MaximumWordsPerChunk = 8_192
    private const val MaximumChunkCharacters = 16_384
    private const val MaximumPayloadBytes = 1024L * 1024L
    private const val Micros = 1_000_000
}
