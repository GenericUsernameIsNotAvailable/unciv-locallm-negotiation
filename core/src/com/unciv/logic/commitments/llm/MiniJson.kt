package com.unciv.logic.commitments.llm

/**
 * A small JSON reader and string quoter, so the language-model code needs no library and can be tested
 * on its own. Parsed values are Map<String, Any?>, List<Any?>, String, Double, Boolean or null.
 */
object MiniJson {
    private const val MAX_DEPTH = 64

    /** @throws IllegalArgumentException if [text] is not valid JSON. */
    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.readValue(0)
        parser.skipWhitespace()
        require(parser.atEnd()) { "Unexpected text after JSON value at position ${parser.position}" }
        return value
    }

    /** [s] as a JSON string literal, including the surrounding quotes. */
    fun quote(s: String): String = buildString {
        append('"')
        for (ch in s) when {
            ch == '"' -> append("\\\"")
            ch == '\\' -> append("\\\\")
            ch == '\n' -> append("\\n")
            ch == '\r' -> append("\\r")
            ch == '\t' -> append("\\t")
            ch < ' ' -> append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> append(ch)
        }
        append('"')
    }

    private class Parser(private val s: String) {
        var position = 0
            private set

        fun atEnd() = position >= s.length

        fun skipWhitespace() {
            while (position < s.length && s[position].isWhitespace()) position++
        }

        fun readValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON nested too deeply" }
            skipWhitespace()
            require(!atEnd()) { "Unexpected end of JSON" }
            return when (val c = s[position]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw error("Unexpected '$c'")
            }
        }

        private fun error(message: String) = IllegalArgumentException("$message at position $position")

        private fun readLiteral(word: String, value: Any?): Any? {
            if (!s.startsWith(word, position)) throw error("Expected '$word'")
            position += word.length
            return value
        }

        private fun readNumber(): Double {
            val start = position
            while (position < s.length && s[position] in "+-0123456789.eE") position++
            return s.substring(start, position).toDoubleOrNull() ?: throw error("Bad number")
        }

        private fun readString(): String {
            position++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw error("Unterminated string")
                val c = s[position++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw error("Unterminated escape")
                        when (val e = s[position++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (position + 4 > s.length) throw error("Bad unicode escape")
                                sb.append(s.substring(position, position + 4).toIntOrNull(16)?.toChar() ?: throw error("Bad unicode escape"))
                                position += 4
                            }
                            else -> throw error("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            position++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && s[position] == ']') { position++; return result }
            while (true) {
                result += readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) throw error("Unterminated array")
                when (s[position++]) {
                    ',' -> continue
                    ']' -> return result
                    else -> { position--; throw error("Expected ',' or ']'") }
                }
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            position++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && s[position] == '}') { position++; return result }
            while (true) {
                skipWhitespace()
                if (atEnd() || s[position] != '"') throw error("Expected a string key")
                val key = readString()
                skipWhitespace()
                if (atEnd() || s[position++] != ':') { position--; throw error("Expected ':'") }
                result[key] = readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) throw error("Unterminated object")
                when (s[position++]) {
                    ',' -> continue
                    '}' -> return result
                    else -> { position--; throw error("Expected ',' or '}'") }
                }
            }
        }
    }
}
