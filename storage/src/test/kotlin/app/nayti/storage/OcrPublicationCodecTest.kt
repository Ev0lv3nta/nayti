package app.nayti.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OcrPublicationCodecTest {
    @Test
    fun identityIsDeterministicAndBindsTextOrderAndGeometry() {
        val document = document()
        val first = region("первая", 100_000)
        val second = region("вторая", 200_000)

        val identity = OcrPublicationCodec.identity(document, listOf(first, second))

        assertEquals(identity, OcrPublicationCodec.identity(document, listOf(first, second)))
        assertNotEquals(identity, OcrPublicationCodec.identity(document.copy(displayText = "изменено"), listOf(first, second)))
        assertNotEquals(identity, OcrPublicationCodec.identity(document, listOf(second, first)))
        assertNotEquals(identity, OcrPublicationCodec.identity(document, listOf(first.copy(x0Micros = 100_001), second)))
    }

    @Test
    fun rejectsOutOfRangeGeometryAndUnboundedText() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrPublicationCodec.identity(document(), listOf(region("текст", -1)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OcrPublicationCodec.identity(document().copy(rawText = "x".repeat(262_145)), emptyList())
        }
    }

    private fun document(): OcrDocumentDraft =
        OcrDocumentDraft(
            assetId = 7,
            sourceFingerprint = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            accessRevision = 3,
            pipelineVersion = "ocr-v1",
            componentHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            sourceWidth = 1_000,
            sourceHeight = 800,
            rawText = "Первая строка",
            displayText = "Первая строка",
            canonicalText = "первая строка",
            stemText = "перв строк",
            identifierText = "",
            normalizerVersion = "normalizer-v1",
            stemmerVersion = "stemmer-v1",
            identifierVersion = "identifier-v1",
        )

    private fun region(text: String, x0: Int): OcrRegionDraft =
        OcrRegionDraft(
            rawText = text,
            displayText = text,
            canonicalText = text,
            confidenceMicros = 900_000,
            x0Micros = x0,
            y0Micros = 100_000,
            x1Micros = 900_000,
            y1Micros = 100_000,
            x2Micros = 900_000,
            y2Micros = 200_000,
            x3Micros = 100_000,
            y3Micros = 200_000,
        )
}
