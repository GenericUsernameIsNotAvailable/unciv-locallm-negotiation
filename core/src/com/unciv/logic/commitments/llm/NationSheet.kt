package com.unciv.logic.commitments.llm

/** What a fact is about. The player's words decide which topics' details the leader is given for a reply. */
enum class Topic { Economy, Military, War, Diplomacy, Cities, Faith, Culture, Science, Resources, Goals, History }

/**
 * One true statement about the leader's nation right now, written in the leader's second person
 * ("You have 340 gold ..."). [core] facts go into every reply; the others only when their [topic] comes up.
 * Adding a new stat to the game's picture of a nation is adding one of these; nothing else needs to change.
 */
data class Fact(val topic: Topic, val text: String, val core: Boolean = false)

/**
 * What the human player visibly brings to the negotiation table: who they are, their treasury and income,
 * their cities, and the luxury and strategic resources they actually own. This lets the AI leader know who it
 * is speaking to, see what the player can genuinely offer, and catch the player bluffing with empty hands.
 */
data class PlayerOfferSheet(
    val leaderName: String,
    val nationName: String,
    val gold: Int,
    val goldPerTurn: Int,
    val cityCount: Int,
    val capitalName: String? = null,
    val cityNames: List<String> = emptyList(),
    val luxuries: List<String> = emptyList(),
    val strategics: Map<String, Int> = emptyMap(),
    /** All luxury and strategic resource names in the ruleset so false resource offers can be caught. */
    val allKnownResources: List<String> = emptyList()
) {
    fun summaryLines(): List<String> {
        val signedGpt = (if (goldPerTurn >= 0) "+" else "") + goldPerTurn
        val cityDetail = when {
            cityNames.isNotEmpty() -> {
                val formatted = cityNames.joinToString(", ") { if (it == capitalName) "$it (capital)" else it }
                ": $formatted"
            }
            capitalName != null -> " (capital: $capitalName)"
            else -> ""
        }
        val luxText = if (luxuries.isEmpty()) "none" else luxuries.joinToString(", ")
        val stratText = if (strategics.isEmpty()) "none" else strategics.entries.joinToString(", ") { "${it.key} (${it.value})" }
        return listOf(
            "They are $leaderName, ruler of $nationName.",
            "Their treasury has $gold gold ($signedGpt gold per turn), and they rule $cityCount ${if (cityCount == 1) "city" else "cities"}$cityDetail.",
            "Their luxury resources: $luxText.",
            "Their strategic resources: $stratText."
        )
    }
}

/**
 * The leader's real, current situation, read from the game when the player speaks. [facts] is what the leader may
 * say about its nation; [player] is what the leader can see of the player's nation; the other fields let [ClaimGuard]
 * check what it did say.
 */
data class NationSheet(
    val facts: List<Fact>,
    val gold: Int? = null,
    val cityCount: Int? = null,
    val atWarWithPlayer: Boolean = false,
    /** Other nations at war with the leader, as far as the player knows them. */
    val enemies: List<String> = emptyList(),
    /** Nations the leader has a declared friendship or defensive pact with, as far as the player knows them. */
    val allies: List<String> = emptyList(),
    /** What the human player visibly has at the table; null in isolated tests. */
    val player: PlayerOfferSheet? = null
) {
    val atWar: Boolean get() = atWarWithPlayer || enemies.isNotEmpty()
}

/** Finds out what the player is talking about, from their words. Deliberately simple, fast and predictable. */
object TopicDetector {
    private val keywords: Map<Topic, Set<String>> = mapOf(
        Topic.Economy to setOf(
            "gold", "money", "coin", "coins", "treasury", "wealth", "wealthy", "rich", "poor", "income", "pay", "paid",
            "price", "cost", "costs", "trade", "trades", "trading", "tax", "taxes", "afford", "gift", "gifts", "bribe",
            "loan", "debt", "buy", "sell", "salary", "payment", "funds", "fund"
        ),
        Topic.Military to setOf(
            "army", "armies", "troops", "soldiers", "soldier", "military", "units", "unit", "weapons", "strong", "weak",
            "strength", "defend", "defence", "defense", "fleet", "navy", "warriors", "force", "forces", "pathetic", "archers",
            "swordsmen", "tanks", "ships", "cannons"
        ),
        Topic.War to setOf(
            "war", "wars", "attack", "attacks", "invade", "invasion", "conquer", "conquest", "fight", "fighting", "battle",
            "battles", "peace", "enemy", "enemies", "declare", "hostile", "ceasefire", "surrender", "destroy", "kill", "crush"
        ),
        Topic.Diplomacy to setOf(
            "ally", "allies", "alliance", "alliances", "friend", "friends", "friendship", "pact", "pacts", "treaty",
            "treaties", "protect", "protection", "protector", "denounce", "denounced", "embassy", "borders", "neighbour",
            "neighbor", "neighbours", "neighbors", "citystate", "agreement", "promise", "promised", "relations", "diplomacy"
        ),
        Topic.Cities to setOf(
            "city", "cities", "capital", "land", "lands", "settle", "settlers", "expand", "expansion", "territory",
            "wonder", "wonders", "build", "building", "buildings", "construct", "construction", "population", "citizens", "empire"
        ),
        Topic.Faith to setOf(
            "faith", "religion", "religions", "god", "gods", "pray", "prayer", "temple", "church", "belief", "beliefs", "holy",
            "pantheon", "prophet", "devout", "worship", "missionary"
        ),
        Topic.Culture to setOf(
            "culture", "art", "arts", "policy", "policies", "tradition", "traditions", "artists", "music", "heritage", "ideology"
        ),
        Topic.Science to setOf(
            "science", "tech", "technology", "technologies", "research", "researching", "learn", "scholars", "knowledge",
            "invention", "inventions", "era", "discoveries", "discover"
        ),
        Topic.Resources to setOf(
            "luxury", "luxuries", "resource", "resources", "happiness", "happy", "unhappy", "iron", "horses", "oil", "coal",
            "silk", "spices", "wine", "stability", "content", "uranium", "aluminum", "gems", "ivory", "incense", "whales",
            "whale", "furs", "cotton", "dyes", "silver", "sugar", "pearls", "citrus", "crab", "truffles", "cocoa", "copper", "salt"
        ),
        Topic.Goals to setOf(
            "goal", "goals", "plan", "plans", "intend", "ambition", "ambitions", "aim", "aims", "want", "wants", "wish", "next",
            "victory", "win", "winning", "future", "strategy", "working", "pursue", "hope"
        ),
        Topic.History to setOf(
            "history", "past", "born", "ancestors", "ancestry", "origin", "origins", "founded", "story", "legacy", "ancient"
        )
    )

    private val nonWord = Regex("""[^\p{L}]+""")

    fun detect(text: String): Set<Topic> {
        val words = text.lowercase().split(nonWord).filter { it.isNotEmpty() }.toSet()
        return keywords.filterValues { it.any { keyword -> keyword in words } }.keys
    }
}

object SheetRenderer {
    const val MAX_PER_TOPIC = 6

    /**
     * The facts to put in front of the model for this reply: the core facts, plus the details for every topic the
     * player's latest words (and their message before it) touch on. Core facts come first, then the rest in sheet order.
     */
    fun render(sheet: NationSheet, playerText: String, recentPlayerTexts: List<String> = emptyList()): List<String> {
        val topics = (listOf(playerText) + recentPlayerTexts).flatMap { TopicDetector.detect(it) }.toSet()
        val core = sheet.facts.filter { it.core }
        val details = sheet.facts.filter { !it.core && it.topic in topics }
            .groupBy { it.topic }.values.flatMap { it.take(MAX_PER_TOPIC) }
        return (core + details).map { it.text }
    }
}
