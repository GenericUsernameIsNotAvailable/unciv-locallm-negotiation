package com.unciv.logic.commitments.llm

/**
 * Normalises English number words ("one gold", "twenty-five turns", "five hundred gold") into digits ("1 gold",
 * "25 turns", "500 gold") and expands common shorthand ("u" -> "you", "ur" -> "your") so natural-language parsers
 * handle spoken numbers and casual shorthand identically.
 */
object NumberWords {
    private val ones = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19
    )
    private val tens = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90
    )

    private val shorthandYou = Regex("""\bu\b""", RegexOption.IGNORE_CASE)
    private val shorthandYour = Regex("""\bur\b""", RegexOption.IGNORE_CASE)

    private val hundredPattern = Regex(
        """\b(one|two|three|four|five|six|seven|eight|nine)\s+hundred(?:\s+and)?(?:\s+(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety))?(?:[\s-](one|two|three|four|five|six|seven|eight|nine))?\b""",
        RegexOption.IGNORE_CASE
    )
    private val compoundTensPattern = Regex(
        """\b(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)[\s-](one|two|three|four|five|six|seven|eight|nine)\b""",
        RegexOption.IGNORE_CASE
    )
    // Only convert standalone "one".."ninety" when followed by a unit/trade word so "one of my cities" stays intact
    private val singleWordBeforeUnit = Regex(
        """\b(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\b(?=\s+(?:gold|gpt|turns?|cities|city|iron|horses|coal|oil|uranium|aluminum))""",
        RegexOption.IGNORE_CASE
    )

    fun normalize(text: String): String {
        var result = shorthandYou.replace(text, "you")
        result = shorthandYour.replace(result, "your")
        result = hundredPattern.replace(result) { m ->
            val h = (ones[m.groupValues[1].lowercase()] ?: 1) * 100
            val t = tens[m.groupValues[2].lowercase()] ?: 0
            val u = ones[m.groupValues[3].lowercase()] ?: 0
            (h + t + u).toString()
        }
        result = compoundTensPattern.replace(result) { m ->
            val t = tens[m.groupValues[1].lowercase()] ?: 0
            val u = ones[m.groupValues[2].lowercase()] ?: 0
            (t + u).toString()
        }
        result = singleWordBeforeUnit.replace(result) { m ->
            val word = m.groupValues[1].lowercase()
            (ones[word] ?: tens[word] ?: 0).toString()
        }
        return result
    }
}
