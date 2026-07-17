package app.nayti.ml.runtime.pack

import java.io.ByteArrayOutputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets

internal sealed interface JsonValue {
    data class ObjectValue(val entries: Map<String, JsonValue>) : JsonValue
    data class ArrayValue(val values: List<JsonValue>) : JsonValue
    data class StringValue(val value: String) : JsonValue
    data class IntegerValue(val value: Long) : JsonValue
    data class BooleanValue(val value: Boolean) : JsonValue
    data object NullValue : JsonValue
}

internal object CanonicalJson {
    fun parseCanonical(raw: ByteArray): JsonValue.ObjectValue {
        val text =
            try {
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(raw))
                    .toString()
            } catch (failure: CharacterCodingException) {
                throw ModelPackException("Manifest is not strict UTF-8", failure)
            }
        val value = Parser(if (text.endsWith('\n')) text.dropLast(1) else text).parse()
        val objectValue = value as? JsonValue.ObjectValue
            ?: throw ModelPackException("Manifest root must be an object")
        if (!raw.contentEquals(encode(objectValue))) {
            throw ModelPackException("Manifest is not canonical JSON")
        }
        return objectValue
    }

    fun encode(value: JsonValue): ByteArray {
        val output = ByteArrayOutputStream()
        write(value, output)
        output.write('\n'.code)
        return output.toByteArray()
    }

    private fun write(value: JsonValue, output: ByteArrayOutputStream) {
        when (value) {
            is JsonValue.ObjectValue -> {
                output.write('{'.code)
                value.entries.keys.sortedWith(CodePointComparator).forEachIndexed { index, key ->
                    if (index > 0) output.write(','.code)
                    writeString(key, output)
                    output.write(':'.code)
                    write(checkNotNull(value.entries[key]), output)
                }
                output.write('}'.code)
            }
            is JsonValue.ArrayValue -> {
                output.write('['.code)
                value.values.forEachIndexed { index, item ->
                    if (index > 0) output.write(','.code)
                    write(item, output)
                }
                output.write(']'.code)
            }
            is JsonValue.StringValue -> writeString(value.value, output)
            is JsonValue.IntegerValue -> output.write(value.value.toString().toByteArray(StandardCharsets.US_ASCII))
            is JsonValue.BooleanValue -> output.write(if (value.value) TRUE else FALSE)
            JsonValue.NullValue -> output.write(NULL)
        }
    }

    private fun writeString(value: String, output: ByteArrayOutputStream) {
        output.write('"'.code)
        var index = 0
        while (index < value.length) {
            val character = value[index]
            when (character) {
                '"' -> output.write(QUOTE)
                '\\' -> output.write(BACKSLASH)
                '\b' -> output.write(BACKSPACE)
                '\u000c' -> output.write(FORM_FEED)
                '\n' -> output.write(NEWLINE)
                '\r' -> output.write(CARRIAGE_RETURN)
                '\t' -> output.write(TAB)
                else -> {
                    when {
                        character.code < 0x20 -> {
                            output.write("\\u%04x".format(character.code).toByteArray(StandardCharsets.US_ASCII))
                        }
                        character.isHighSurrogate() -> {
                            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                                throw ModelPackException("Manifest contains an unpaired surrogate")
                            }
                            output.write(value.substring(index, index + 2).toByteArray(StandardCharsets.UTF_8))
                            index++
                        }
                        character.isLowSurrogate() -> throw ModelPackException("Manifest contains an unpaired surrogate")
                        else -> output.write(character.toString().toByteArray(StandardCharsets.UTF_8))
                    }
                }
            }
            index++
        }
        output.write('"'.code)
    }

    private object CodePointComparator : Comparator<String> {
        override fun compare(left: String, right: String): Int {
            val leftPoints = left.codePoints().toArray()
            val rightPoints = right.codePoints().toArray()
            for (index in 0 until minOf(leftPoints.size, rightPoints.size)) {
                if (leftPoints[index] != rightPoints[index]) {
                    return leftPoints[index].compareTo(rightPoints[index])
                }
            }
            return leftPoints.size.compareTo(rightPoints.size)
        }
    }

    private class Parser(private val text: String) {
        private var offset = 0

        fun parse(): JsonValue {
            val value = parseValue()
            if (offset != text.length) fail("Trailing JSON data")
            return value
        }

        private fun parseValue(): JsonValue {
            if (offset >= text.length) fail("Unexpected end of JSON")
            return when (text[offset]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.StringValue(parseString())
                't' -> parseLiteral("true", JsonValue.BooleanValue(true))
                'f' -> parseLiteral("false", JsonValue.BooleanValue(false))
                'n' -> parseLiteral("null", JsonValue.NullValue)
                '-', in '0'..'9' -> parseInteger()
                else -> fail("Unexpected JSON token")
            }
        }

        private fun parseObject(): JsonValue.ObjectValue {
            offset++
            val entries = linkedMapOf<String, JsonValue>()
            if (consume('}')) return JsonValue.ObjectValue(entries)
            while (true) {
                if (offset >= text.length || text[offset] != '"') fail("Object key must be a string")
                val key = parseString()
                if (entries.containsKey(key)) fail("Duplicate JSON key: $key")
                requireCharacter(':')
                entries[key] = parseValue()
                if (consume('}')) break
                requireCharacter(',')
            }
            return JsonValue.ObjectValue(entries)
        }

        private fun parseArray(): JsonValue.ArrayValue {
            offset++
            val values = mutableListOf<JsonValue>()
            if (consume(']')) return JsonValue.ArrayValue(values)
            while (true) {
                values += parseValue()
                if (consume(']')) break
                requireCharacter(',')
            }
            return JsonValue.ArrayValue(values)
        }

        private fun parseString(): String {
            requireCharacter('"')
            val result = StringBuilder()
            while (offset < text.length) {
                val character = text[offset++]
                when {
                    character == '"' -> return validateSurrogates(result.toString())
                    character == '\\' -> result.append(parseEscape())
                    character.code < 0x20 -> fail("Unescaped control character")
                    else -> result.append(character)
                }
            }
            fail("Unterminated JSON string")
        }

        private fun parseEscape(): Char =
            when (val escaped = nextCharacter("Unterminated JSON escape")) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (offset + 4 > text.length) fail("Truncated Unicode escape")
                    val digits = text.substring(offset, offset + 4)
                    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                        fail("Invalid Unicode escape")
                    }
                    offset += 4
                    digits.toInt(16).toChar()
                }
                else -> fail("Invalid JSON escape")
            }

        private fun parseInteger(): JsonValue.IntegerValue {
            val start = offset
            if (consume('-') && offset >= text.length) fail("Truncated integer")
            when {
                consume('0') -> {
                    if (offset < text.length && text[offset].isDigit()) fail("Leading zero in integer")
                }
                offset < text.length && text[offset] in '1'..'9' -> {
                    offset++
                    while (offset < text.length && text[offset].isDigit()) offset++
                }
                else -> fail("Invalid integer")
            }
            if (offset < text.length && text[offset] in charArrayOf('.', 'e', 'E')) {
                fail("Floating-point values are forbidden")
            }
            val raw = text.substring(start, offset)
            return JsonValue.IntegerValue(
                raw.toLongOrNull() ?: fail("Integer is outside signed 64-bit range"),
            )
        }

        private fun <T : JsonValue> parseLiteral(expected: String, value: T): T {
            if (!text.startsWith(expected, offset)) fail("Invalid JSON literal")
            offset += expected.length
            return value
        }

        private fun consume(expected: Char): Boolean {
            if (offset < text.length && text[offset] == expected) {
                offset++
                return true
            }
            return false
        }

        private fun requireCharacter(expected: Char) {
            if (!consume(expected)) fail("Expected '$expected'")
        }

        private fun nextCharacter(message: String): Char {
            if (offset >= text.length) fail(message)
            return text[offset++]
        }

        private fun validateSurrogates(value: String): String {
            var index = 0
            while (index < value.length) {
                val character = value[index]
                if (character.isHighSurrogate()) {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        fail("Unpaired Unicode surrogate")
                    }
                    index++
                } else if (character.isLowSurrogate()) {
                    fail("Unpaired Unicode surrogate")
                }
                index++
            }
            return value
        }

        private fun fail(message: String): Nothing = throw ModelPackException("$message at offset $offset")
    }

    private val TRUE = "true".toByteArray(StandardCharsets.US_ASCII)
    private val FALSE = "false".toByteArray(StandardCharsets.US_ASCII)
    private val NULL = "null".toByteArray(StandardCharsets.US_ASCII)
    private val QUOTE = "\\\"".toByteArray(StandardCharsets.US_ASCII)
    private val BACKSLASH = "\\\\".toByteArray(StandardCharsets.US_ASCII)
    private val BACKSPACE = "\\b".toByteArray(StandardCharsets.US_ASCII)
    private val FORM_FEED = "\\f".toByteArray(StandardCharsets.US_ASCII)
    private val NEWLINE = "\\n".toByteArray(StandardCharsets.US_ASCII)
    private val CARRIAGE_RETURN = "\\r".toByteArray(StandardCharsets.US_ASCII)
    private val TAB = "\\t".toByteArray(StandardCharsets.US_ASCII)
}
