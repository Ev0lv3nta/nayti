package app.nayti.storage

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object OcrPublicationCodec {
    fun identity(
        document: OcrDocumentDraft,
        regions: List<OcrRegionDraft>,
    ): OcrPayloadIdentity {
        validate(document, regions)
        val writer = DigestWriter()
        writer.bytes(Magic)
        writer.long(document.assetId)
        writer.string(document.sourceFingerprint)
        writer.long(document.accessRevision)
        writer.string(document.pipelineVersion)
        writer.string(document.componentHash)
        writer.int(document.sourceWidth)
        writer.int(document.sourceHeight)
        writer.string(document.rawText)
        writer.string(document.displayText)
        writer.string(document.canonicalText)
        writer.string(document.stemText)
        writer.string(document.identifierText)
        writer.string(document.normalizerVersion)
        writer.string(document.stemmerVersion)
        writer.string(document.identifierVersion)
        writer.int(regions.size)
        regions.forEach { region ->
            writer.string(region.rawText)
            writer.string(region.displayText)
            writer.string(region.canonicalText)
            writer.int(region.confidenceMicros)
            writer.int(region.x0Micros)
            writer.int(region.y0Micros)
            writer.int(region.x1Micros)
            writer.int(region.y1Micros)
            writer.int(region.x2Micros)
            writer.int(region.y2Micros)
            writer.int(region.x3Micros)
            writer.int(region.y3Micros)
        }
        return writer.finish()
    }

    private fun validate(document: OcrDocumentDraft, regions: List<OcrRegionDraft>) {
        require(document.assetId > 0)
        require(Sha256.matches(document.sourceFingerprint))
        require(document.accessRevision > 0)
        require(ContractValue.matches(document.pipelineVersion))
        require(Sha256.matches(document.componentHash))
        require(document.sourceWidth in 1..MaximumImageDimension)
        require(document.sourceHeight in 1..MaximumImageDimension)
        require(document.rawText.length <= MaximumDocumentCharacters)
        require(document.displayText.length <= MaximumDocumentCharacters)
        require(document.canonicalText.length <= MaximumDocumentCharacters)
        require(document.stemText.length <= MaximumDocumentCharacters)
        require(document.identifierText.length <= MaximumDocumentCharacters)
        require(Version.matches(document.normalizerVersion))
        require(Version.matches(document.stemmerVersion))
        require(Version.matches(document.identifierVersion))
        require(regions.size <= MaximumRegions)
        regions.forEach { region ->
            require(region.rawText.length <= MaximumRegionCharacters)
            require(region.displayText.length <= MaximumRegionCharacters)
            require(region.canonicalText.length <= MaximumRegionCharacters)
            require(region.confidenceMicros in 0..Micros)
            require(region.coordinates().all { coordinate -> coordinate in 0..Micros })
        }
    }

    private fun OcrRegionDraft.coordinates(): IntArray =
        intArrayOf(x0Micros, y0Micros, x1Micros, y1Micros, x2Micros, y2Micros, x3Micros, y3Micros)

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

    private val Magic = "NAYTIOCR1".toByteArray(StandardCharsets.US_ASCII)
    private val Sha256 = Regex("[0-9a-f]{64}")
    private val ContractValue = Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]{0,127}")
    private val Version = Regex("[A-Za-z0-9][A-Za-z0-9._:+/-]{0,63}")
    private const val MaximumImageDimension = 100_000
    private const val MaximumDocumentCharacters = 262_144
    private const val MaximumRegionCharacters = 8_192
    private const val MaximumRegions = 4_096
    private const val MaximumPayloadBytes = 8L * 1024 * 1024
    private const val Micros = 1_000_000
}
