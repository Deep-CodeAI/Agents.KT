package agents_engine.manifest

internal object ManifestJsonParser {
    fun parse(text: String): Any? = Parser(text).parse()

    private class Parser(private val text: String) {
        private var index: Int = 0

        fun parse(): Any? {
            val value = parseValue()
            skipWhitespace()
            require(index == text.length) { "Unexpected trailing JSON at offset $index" }
            return value
        }

        private fun parseValue(): Any? {
            skipWhitespace()
            require(index < text.length) { "Unexpected end of JSON" }
            return when (val ch = text[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> consumeLiteral("true", true)
                'f' -> consumeLiteral("false", false)
                'n' -> consumeLiteral("null", null)
                '-', in '0'..'9' -> parseNumber()
                else -> error("Unexpected JSON character '$ch' at offset $index")
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            skipWhitespace()
            val out = linkedMapOf<String, Any?>()
            if (peek('}')) {
                expect('}')
                return out
            }
            while (true) {
                val key = parseString()
                skipWhitespace()
                expect(':')
                out[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> expect(',')
                    peek('}') -> {
                        expect('}')
                        return out
                    }
                    else -> error("Expected ',' or '}' at offset $index")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            skipWhitespace()
            val out = mutableListOf<Any?>()
            if (peek(']')) {
                expect(']')
                return out
            }
            while (true) {
                out += parseValue()
                skipWhitespace()
                when {
                    peek(',') -> expect(',')
                    peek(']') -> {
                        expect(']')
                        return out
                    }
                    else -> error("Expected ',' or ']' at offset $index")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val out = StringBuilder()
            while (index < text.length) {
                val ch = text[index++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        require(index < text.length) { "Unterminated JSON escape" }
                        out.append(
                            when (val escaped = text[index++]) {
                                '"' -> '"'
                                '\\' -> '\\'
                                '/' -> '/'
                                'b' -> '\b'
                                'f' -> '\u000C'
                                'n' -> '\n'
                                'r' -> '\r'
                                't' -> '\t'
                                'u' -> parseUnicodeEscape()
                                else -> error("Invalid JSON escape '\\$escaped' at offset ${index - 1}")
                            },
                        )
                    }
                    else -> out.append(ch)
                }
            }
            error("Unterminated JSON string")
        }

        private fun parseUnicodeEscape(): Char {
            require(index + 4 <= text.length) { "Incomplete unicode escape at offset $index" }
            val hex = text.substring(index, index + 4)
            index += 4
            return hex.toInt(16).toChar()
        }

        private fun parseNumber(): Number {
            val start = index
            if (peek('-')) index++
            while (index < text.length && text[index].isDigit()) index++
            if (peek('.')) {
                index++
                while (index < text.length && text[index].isDigit()) index++
            }
            if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
                index++
                if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
                while (index < text.length && text[index].isDigit()) index++
            }
            val raw = text.substring(start, index)
            return if (raw.any { it == '.' || it == 'e' || it == 'E' }) raw.toDouble() else raw.toLong()
        }

        private fun consumeLiteral(literal: String, value: Any?): Any? {
            require(text.startsWith(literal, index)) { "Expected $literal at offset $index" }
            index += literal.length
            return value
        }

        private fun skipWhitespace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }

        private fun expect(ch: Char) {
            skipWhitespace()
            require(index < text.length && text[index] == ch) { "Expected '$ch' at offset $index" }
            index++
        }

        private fun peek(ch: Char): Boolean = index < text.length && text[index] == ch
    }
}
