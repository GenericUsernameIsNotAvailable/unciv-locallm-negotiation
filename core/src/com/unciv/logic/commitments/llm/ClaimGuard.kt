package com.unciv.logic.commitments.llm

/**
 * Checks what a leader says about its own nation against the real [NationSheet]. A small model that has not been
 * told something makes it up ("I have no treasury of my own", "we have no treaties", "I rule twelve cities"), and the
 * player can see the real numbers elsewhere in the game. Heuristic and deliberately narrow: it flags clear
 * contradictions and invented figures, not style. A false alarm costs one retry.
 */
object ClaimGuard {
    data class Violation(val sentence: String, val reason: String)

    private val sentenceEnd = Regex("""(?<=[.!?])\s+""")
    private val number = Regex("""\d[\d,]*""")
    private const val GOLD = "(?:gold|treasury|coffers|coin|coins|funds|money)"
    private const val MIN_GOLD_TO_CALL_IT_SOME = 25
    private const val SMALL_NUMBER = 10
    private val ignoreCase = RegexOption.IGNORE_CASE

    private val claimsNoGold = listOf(
        Regex("""\b(?:have|has|hold|holds|own|owns|possess|got)\s+(?:no|not any|nothing|zero)\s+(?:\w+\s+){0,2}$GOLD\b""", ignoreCase),
        Regex("""\bdo(?:es)?\s+not\s+(?:have|possess|hold|own)\s+(?:any\s+)?(?:\w+\s+){0,2}$GOLD\b""", ignoreCase),
        Regex("""\bnor\s+do\s+(?:i|we)\s+have\s+(?:any\s+)?(?:\w+\s+){0,2}$GOLD\b""", ignoreCase),
        Regex("""\bno\s+$GOLD\s+(?:set aside|left|available|to spare|to give|to offer)\b""", ignoreCase),
        Regex("""\b$GOLD\s+(?:is|are)\s+(?:empty|bare|exhausted)\b""", ignoreCase)
    )
    private val spareOrGiveSuffix = Regex("""\bto\s+(?:spare|give|offer|lend|loan|share|bestow)\b""", ignoreCase)
    private val playerCityPrefix = Regex(
        """(?:\byour\s+|\byou\s+(?:have|rule|govern|command|control|hold|possess|lead|own)\s+)$""",
        ignoreCase
    )
    private val claimsWar = Regex("""\b(?:we|i)\s+(?:are|am|'re)\s+(?:currently\s+|now\s+)?at\s+war\b""", ignoreCase)
    private val warWith = Regex("""\bat\s+war\s+with\s+(?:the\s+)?([A-Za-z][\w'-]*)""", ignoreCase)
    private val claimsPeace = Regex(
        """\b(?:we|i)\s+(?:are|am|'re)\s+(?:currently\s+|now\s+)?(?:not\s+at\s+war|at\s+peace)\b|\b(?:we|i)\s+(?:have|face)\s+no\s+(?:wars?|enemies)\b""",
        ignoreCase
    )
    private val allied = Regex("""\b(?:allied|alliance|alliances|friendship|friends?|pact)\s+(?:with|between us and)\s+(?:the\s+)?([A-Z][\w'-]*)""")
    private val claimsNoAllies = Regex("""\b(?:have|has)\s+no\s+(?:allies|alliances|friends|treaties|pacts)\b""", ignoreCase)
    private val cityClaim = Regex("""\b(\d+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\s+(?:\w+\s+)?cities\b""", ignoreCase)
    private val words = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12
    )
    private val notANation = setOf("you", "your", "us", "them", "the", "player", "anyone", "any", "none", "no", "all", "everyone", "our", "my")

    /**
     * @param allowedText everything the leader was legitimately told or the player said (facts, background, the
     *   conversation so far): numbers appearing there are not "invented".
     */
    fun violations(reply: String, sheet: NationSheet, allowedText: List<String>): List<Violation> {
        val allowedNumbers = HashSet<Int>()
        (allowedText + sheet.facts.map { it.text }).forEach { text -> numbersIn(text).forEach { allowedNumbers += it } }
        sheet.gold?.let { allowedNumbers += it }
        sheet.cityCount?.let { allowedNumbers += it }
        sheet.player?.let { p ->
            allowedNumbers += p.gold
            allowedNumbers += kotlin.math.abs(p.goldPerTurn)
            allowedNumbers += p.cityCount
            p.strategics.values.forEach { allowedNumbers += it }
            p.summaryLines().forEach { line -> numbersIn(line).forEach { allowedNumbers += it } }
        }

        return reply.split(sentenceEnd).mapNotNull { sentence ->
            val reasons = ArrayList<String>()

            val gold = sheet.gold
            val askedMoreGoldThanOwned = gold != null && allowedText.flatMap { numbersIn(NumberWords.normalize(it)) }.any { it > gold }
            val deniesHavingGold = claimsNoGold.any { it.containsMatchIn(sentence) } &&
                !(askedMoreGoldThanOwned && spareOrGiveSuffix.containsMatchIn(sentence))
            if (gold != null && gold >= MIN_GOLD_TO_CALL_IT_SOME && deniesHavingGold)
                reasons += "claims to have no gold, but you have $gold"

            if (!sheet.atWar && claimsWar.containsMatchIn(sentence)) reasons += "claims to be at war, but you are not"
            warWith.findAll(sentence).forEach { m ->
                val name = m.groupValues[1].lowercase()
                val toPlayer = name == "you" || name == "player"
                val known = sheet.enemies.any { it.lowercase().contains(name) || name.contains(it.lowercase()) }
                if ((toPlayer && !sheet.atWarWithPlayer) || (!toPlayer && name !in notANation && !known))
                    reasons += "claims to be at war with ${m.groupValues[1]}, but you are not"
            }
            if (sheet.atWar && claimsPeace.containsMatchIn(sentence)) reasons += "claims to be at peace, but you are at war"

            allied.findAll(sentence).forEach { m ->
                val name = m.groupValues[1].lowercase()
                if (name !in notANation && sheet.allies.none { it.lowercase().contains(name) || name.contains(it.lowercase()) })
                    reasons += "claims an alliance or friendship with ${m.groupValues[1]}, which you do not have"
            }
            if (sheet.allies.isNotEmpty() && claimsNoAllies.containsMatchIn(sentence))
                reasons += "claims to have no allies, friends or treaties, but you have some"

            val cities = sheet.cityCount
            val playerCities = sheet.player?.cityCount
            if (cities != null) cityClaim.findAll(sentence).forEach { m ->
                val said = m.groupValues[1].toIntOrNull() ?: words[m.groupValues[1].lowercase()]
                val prefix = sentence.substring(0, m.range.first)
                val mentionsPlayer = sheet.player != null && (
                    playerCityPrefix.containsMatchIn(prefix) ||
                        sentence.contains(sheet.player.nationName, ignoreCase = true) ||
                        sentence.contains(sheet.player.leaderName, ignoreCase = true)
                    )
                val aboutPlayer = playerCities != null && mentionsPlayer
                val expected = if (aboutPlayer) playerCities else cities
                if (said != null && said != expected) reasons += "claims $said cities, but ${if (aboutPlayer) "they" else "you"} have $expected"
            }

            numbersIn(sentence).filter { it >= SMALL_NUMBER && it !in allowedNumbers }.distinct().forEach {
                reasons += "states the figure $it, which is not among your facts"
            }

            if (reasons.isEmpty()) null else Violation(sentence, reasons.distinct().joinToString("; "))
        }
    }

    /** [reply] without the sentences in [violations]; "" if nothing is left. */
    fun withoutViolations(reply: String, violations: List<Violation>): String {
        val bad = violations.map { it.sentence }.toSet()
        return reply.split(sentenceEnd).filterNot { it in bad }.joinToString(" ").trim()
    }

    private fun numbersIn(text: String): List<Int> = number.findAll(text).mapNotNull { it.value.replace(",", "").toIntOrNull() }.toList()
}
