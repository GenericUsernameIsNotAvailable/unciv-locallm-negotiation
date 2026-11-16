package com.unciv.logic.commitments.llm

import kotlin.math.abs

/**
 * Turns a leader's numeric personality (the game's own 0..10 values, 5 being neutral) into a short description of
 * how that person behaves. A bare list of adjectives ("militaristic, pushy") is not something a small model
 * embodies; sentences about conduct are. Only traits that clearly differ from neutral are mentioned, strongest first.
 */
object PersonalityDescriber {
    private data class Trait(val high: String, val low: String)

    // Keyed by the names of the game's PersonalityValue entries.
    private val traits = mapOf(
        "Production" to Trait("you value building and industry", "you care little for industry"),
        "Food" to Trait("you prize growth and a well-fed people", "you do not care about growth"),
        "Gold" to Trait("you love wealth and drive hard bargains", "you are careless with wealth"),
        "Science" to Trait("you prize learning and reason", "you have little patience for scholars"),
        "Culture" to Trait("you treasure art and tradition", "you have little time for art"),
        "Happiness" to Trait("you like comfort and pleasure", "you are austere and stern"),
        "Faith" to Trait("you are devout", "you are not religious"),
        "Military" to Trait("you are proud of a strong army", "you keep only a small army"),
        "Aggressive" to Trait("you press hard in war", "you are cautious in war"),
        "DeclareWar" to Trait("you go to war readily", "you avoid starting wars"),
        "Commerce" to Trait("you love trade", "you have little interest in trade"),
        "Diplomacy" to Trait("you are a skilled negotiator who likes treaties", "you are blunt and distrust diplomacy"),
        "Loyal" to Trait("you are steadfast to allies", "you are fickle and drop partners when it suits you"),
        "Expansion" to Trait("you are hungry for more land", "you are content with what you hold"),
        "DenounceWillingness" to Trait("you are quick to condemn others", "you rarely condemn anyone")
    )

    /** @param values trait name to value; unknown names are ignored. Returns "" when nothing stands out. */
    fun describe(values: Map<String, Float>, maxTraits: Int = 6, minDeviation: Float = 2f): String =
        values.mapNotNull { (name, value) ->
            val trait = traits[name] ?: return@mapNotNull null
            val deviation = value - 5f
            if (abs(deviation) < minDeviation) null else Triple(abs(deviation), name, if (deviation > 0) trait.high else trait.low)
        }
            .sortedWith(compareByDescending<Triple<Float, String, String>> { it.first }.thenBy { it.second })
            .take(maxTraits)
            .joinToString("; ") { it.third }
}
