package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.CivName
import com.unciv.logic.commitments.DealDraft

/**
 * Structured output from the LLM semantic interpreter so players can speak in completely natural,
 * unrestricted language (slang, idioms, indirect offers, creative insults) without memorizing keyphrases.
 */
data class SemanticAnalysis(
    val tone: ToneKind = ToneKind.Neutral,
    val isVulgarOrHostileAbuse: Boolean = false,
    val isFlattery: Boolean = false,
    val isFabricatedClaim: Boolean = false,
    val intent: String = "chat",
    val playerGivesCity: String = "",
    val aiGivesCity: String = "",
    val playerGivesLuxury: String = "",
    val aiGivesLuxury: String = "",
    val playerGivesStrategic: String = "",
    val aiGivesStrategic: String = "",
    val playerGivesGold: Int = 0,
    val aiGivesGold: Int = 0,
    val playerGivesGpt: Int = 0,
    val aiGivesGpt: Int = 0,
    val treaty: String = "none",
    val commitment: String = "none",
    val target: String = "",
    val turns: Int = 20
)

/**
 * Translates free-form player dialogue into structured [SemanticAnalysis], [DealDraft], or [ClassicTradeDraft]
 * using the active [LlmBackend], plus fuzzy name matching so partial city/resource names work effortlessly.
 */
object SemanticNegotiationParser {

    /**
     * Matches a city or resource name in [candidates] against [queryOrText] even when the player only types
     * a partial name (e.g. "Satchanalai" for "Si Satchanalai" or "Saluang" for "Muang Saluang").
     */
    fun fuzzyFindName(text: String, candidates: List<String>): String? {
        if (candidates.isEmpty() || text.isBlank()) return null
        val ignoreCase = RegexOption.IGNORE_CASE
        // 1. Exact whole-word match
        candidates.firstOrNull { name ->
            Regex("""\b${Regex.escape(name)}\b""", ignoreCase).containsMatchIn(text)
        }?.let { return it }

        // 2. Distinctive token match (tokens >= 4 chars, e.g. "Satchanalai" in "Si Satchanalai")
        for (candidate in candidates) {
            val tokens = candidate.split(Regex("""\s+""")).filter { it.length >= 4 }
            if (tokens.any { token -> Regex("""\b${Regex.escape(token)}\b""", ignoreCase).containsMatchIn(text) }) {
                return candidate
            }
        }
        return null
    }

    fun analyzeWithLlm(
        backend: LlmBackend,
        playerText: String,
        human: CivName,
        ai: CivName,
        playerCities: List<TradableCity>,
        aiCities: List<TradableCity>,
        playerLuxuries: List<String>,
        aiLuxuries: List<String>,
        playerStrategics: List<String>,
        aiStrategics: List<String>,
        knownThirdCivs: List<CivName>,
        previousDraft: ClassicTradeDraft? = null,
        reasoningEffort: String? = "none"
    ): SemanticAnalysis? {
        val system = buildString {
            appendLine("Classify the player's diplomatic message to ruler $ai into a single JSON object. Output ONLY valid JSON.")
            appendLine("Player ($human) cities: ${playerCities.map { it.name }.ifEmpty { listOf("none") }.joinToString(", ")}; luxuries: ${playerLuxuries.ifEmpty { listOf("none") }.joinToString(", ")}; strategics: ${playerStrategics.ifEmpty { listOf("none") }.joinToString(", ")}.")
            appendLine("AI ($ai) cities: ${aiCities.map { it.name }.ifEmpty { listOf("none") }.joinToString(", ")}; luxuries: ${aiLuxuries.ifEmpty { listOf("none") }.joinToString(", ")}; strategics: ${aiStrategics.ifEmpty { listOf("none") }.joinToString(", ")}.")
            if (knownThirdCivs.isNotEmpty()) appendLine("Other nations/city-states: ${knownThirdCivs.joinToString(", ")}.")
            if (previousDraft != null) appendLine("Current deal on table: ${previousDraft.describe()}.")
            appendLine("Allowed tone: \"respectful\", \"neutral\", \"rude\", \"hostile_abuse\", \"threatening\".")
            appendLine("Allowed intent: \"chat\", \"propose_trade\", \"open_inquiry\", \"ask_ai_city_price\", \"haggle_more\", \"haggle_less\", \"propose_commitment\", \"share_intel\".")
            appendLine("Allowed commitment: \"none\", \"no_settle\", \"no_attack\", \"mutual_no_attack\", \"embargo\", \"dmz\", \"mutual_dmz\", \"protect_cs\", \"vote_for_me\", \"loan_to_player\", \"loan_to_ai\", \"war_on_third\", \"subsidy_war\", \"build\".")
            appendLine("Schema: {\"tone\":string,\"flattery\":boolean,\"fabricated_claim\":boolean,\"intent\":string,\"player_city\":string,\"ai_city\":string,\"player_lux\":string,\"ai_lux\":string,\"player_strat\":string,\"ai_strat\":string,\"player_gold\":number,\"ai_gold\":number,\"player_gpt\":number,\"ai_gpt\":number,\"treaty\":string,\"commitment\":string,\"target\":string,\"turns\":number}")
        }.trimEnd()

        return try {
            val raw = backend.generate(
                LlmRequest(system = system, user = playerText, maxTokens = 160, temperature = 0.0f, reasoningEffort = reasoningEffort)
            ).text
            parseSemanticJson(raw)
        } catch (_: Exception) {
            null
        }
    }

    fun parseSemanticJson(raw: String): SemanticAnalysis? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val map = try {
            MiniJson.parse(raw.substring(start, end + 1)) as? Map<*, *>
        } catch (_: Exception) {
            null
        } ?: return null

        val rawTone = (map["tone"] as? String)?.lowercase()?.trim().orEmpty()
        val tone = when (rawTone) {
            "respectful" -> ToneKind.Respectful
            "rude" -> ToneKind.Rude
            "hostile_abuse", "hostile", "abusive", "vulgar" -> ToneKind.Rude
            "threatening", "threat" -> ToneKind.Threatening
            else -> ToneKind.Neutral
        }
        val isVulgar = rawTone in setOf("hostile_abuse", "hostile", "abusive", "vulgar")
        return SemanticAnalysis(
            tone = tone,
            isVulgarOrHostileAbuse = isVulgar,
            isFlattery = map["flattery"] == true,
            isFabricatedClaim = map["fabricated_claim"] == true,
            intent = (map["intent"] as? String)?.lowercase()?.trim() ?: "chat",
            playerGivesCity = (map["player_city"] as? String)?.trim().orEmpty(),
            aiGivesCity = (map["ai_city"] as? String)?.trim().orEmpty(),
            playerGivesLuxury = (map["player_lux"] as? String)?.trim().orEmpty(),
            aiGivesLuxury = (map["ai_lux"] as? String)?.trim().orEmpty(),
            playerGivesStrategic = (map["player_strat"] as? String)?.trim().orEmpty(),
            aiGivesStrategic = (map["ai_strat"] as? String)?.trim().orEmpty(),
            playerGivesGold = (map["player_gold"] as? Number)?.toInt() ?: 0,
            aiGivesGold = (map["ai_gold"] as? Number)?.toInt() ?: 0,
            playerGivesGpt = (map["player_gpt"] as? Number)?.toInt() ?: 0,
            aiGivesGpt = (map["ai_gpt"] as? Number)?.toInt() ?: 0,
            treaty = (map["treaty"] as? String)?.lowercase()?.trim() ?: "none",
            commitment = (map["commitment"] as? String)?.lowercase()?.trim() ?: "none",
            target = (map["target"] as? String)?.trim().orEmpty(),
            turns = ((map["turns"] as? Number)?.toInt() ?: 20).coerceIn(5, 60)
        )
    }

    fun toDealDraft(
        sem: SemanticAnalysis,
        human: CivName,
        ai: CivName,
        knownThirdCivs: List<CivName>,
        knownConstructions: List<String>,
        now: Int
    ): DealDraft? {
        if (sem.commitment == "none" && sem.intent != "propose_commitment") return null
        val target = fuzzyFindName(sem.target, knownThirdCivs) ?: sem.target
        val gold = sem.playerGivesGold.takeIf { it > 0 } ?: sem.aiGivesGold
        val gpt = sem.playerGivesGpt.takeIf { it > 0 } ?: sem.aiGivesGpt
        val turns = sem.turns

        val canonical = when (sem.commitment) {
            "no_settle" -> "Do not settle near my cities for $turns turns" + (if (sem.playerGivesGold > 0) " and I will give you ${sem.playerGivesGold} gold" else "")
            "no_attack" -> "Do not attack me for $turns turns" + (if (sem.playerGivesGold > 0) " and I will give you ${sem.playerGivesGold} gold" else "")
            "mutual_no_attack" -> "Let us sign a non-aggression pact for $turns turns"
            "embargo" -> if (target.isNotEmpty()) "Embargo $target for $turns turns" + (if (sem.playerGivesGold > 0) " and I will give you ${sem.playerGivesGold} gold" else "") else return null
            "dmz" -> "Pull your troops back from my border for $turns turns" + (if (sem.playerGivesGold > 0) " and I will give you ${sem.playerGivesGold} gold" else "")
            "mutual_dmz" -> "Pull your troops back from my border for $turns turns and I will pull mine back"
            "protect_cs" -> if (target.isNotEmpty()) "Do not bully $target for $turns turns" + (if (sem.playerGivesGold > 0) " and I will give you ${sem.playerGivesGold} gold" else "") else return null
            "vote_for_me" -> "Vote for me in the diplomatic vote for $turns turns" + (if (sem.playerGivesGold > 0) " and I will give you ${sem.playerGivesGold} gold" else "")
            "loan_to_player" -> if (gold > 0 && gpt > 0) "Lend me $gold gold and I will pay $gpt gold per turn for $turns turns" else return null
            "loan_to_ai" -> if (gold > 0 && gpt > 0) "I will lend you $gold gold for $gpt gold per turn for $turns turns" else return null
            "war_on_third" -> if (target.isNotEmpty()) "Declare war on $target in $turns turns" + (if (sem.playerGivesGold > 0) " for ${sem.playerGivesGold} gold" else "") else return null
            "subsidy_war" -> if (target.isNotEmpty() && gpt > 0) "Stay at war with $target for $gpt gold per turn for $turns turns" else return null
            "build" -> if (target.isNotEmpty()) "Build a $target for $turns turns" + (if (sem.playerGivesGold > 0) " and I will give you ${sem.playerGivesGold} gold" else "") else return null
            else -> return null
        }
        return ProposalParser.parse(canonical, human, ai, knownThirdCivs, knownConstructions, now)
    }

    fun toClassicTradeDraft(
        sem: SemanticAnalysis,
        playerCiv: CivName,
        aiCiv: CivName,
        playerCities: List<TradableCity>,
        aiCities: List<TradableCity>,
        playerLuxuries: List<String>,
        aiLuxuries: List<String>,
        playerStrategics: List<String>,
        aiStrategics: List<String>,
        previousDraft: ClassicTradeDraft? = null,
        playerText: String = ""
    ): ClassicTradeDraft? {
        if (sem.intent == "haggle_more" && previousDraft != null) {
            return previousDraft.copy(isOpenInquiry = false, haggleDirection = HaggleDirection.MoreFromAi)
        }
        if (sem.intent == "haggle_less" && previousDraft != null) {
            return previousDraft.copy(isOpenInquiry = false, haggleDirection = HaggleDirection.LessFromPlayer)
        }
        if (sem.intent == "ask_ai_city_price") {
            val matchedAiCity = aiCities.firstOrNull { it.name.equals(sem.aiGivesCity, ignoreCase = true) } ?: aiCities.firstOrNull()
            val item = if (matchedAiCity != null) ClassicTradeItem(false, ClassicItemKind.City, matchedAiCity.name, 1, matchedAiCity.id)
            else ClassicTradeItem(false, ClassicItemKind.City, "Capital", 1, "")
            return ClassicTradeDraft(playerCiv, aiCiv, listOf(item), isAiCityPriceInquiry = true)
        }

        val items = ArrayList<ClassicTradeItem>()
        if (sem.playerGivesCity.isNotBlank() && sem.playerGivesCity != "none") {
            val match = playerCities.firstOrNull { it.name.equals(sem.playerGivesCity, ignoreCase = true) }
                ?: fuzzyFindName(sem.playerGivesCity, playerCities.map { it.name })?.let { n -> playerCities.first { it.name == n } }
                ?: playerCities.firstOrNull()
            items += if (match != null) ClassicTradeItem(true, ClassicItemKind.City, match.name, 1, match.id)
            else ClassicTradeItem(true, ClassicItemKind.City, "Capital", 1, "")
        }
        if (sem.aiGivesCity.isNotBlank() && sem.aiGivesCity != "none") {
            val match = aiCities.firstOrNull { it.name.equals(sem.aiGivesCity, ignoreCase = true) }
                ?: fuzzyFindName(sem.aiGivesCity, aiCities.map { it.name })?.let { n -> aiCities.first { it.name == n } }
                ?: aiCities.firstOrNull()
            items += if (match != null) ClassicTradeItem(false, ClassicItemKind.City, match.name, 1, match.id)
            else ClassicTradeItem(false, ClassicItemKind.City, "Capital", 1, "")
        }
        fuzzyFindName(sem.playerGivesLuxury, playerLuxuries)?.let { items += ClassicTradeItem(true, ClassicItemKind.Luxury, it, 1) }
        fuzzyFindName(sem.aiGivesLuxury, aiLuxuries)?.let { items += ClassicTradeItem(false, ClassicItemKind.Luxury, it, 1) }
        fuzzyFindName(sem.playerGivesStrategic, playerStrategics)?.let { items += ClassicTradeItem(true, ClassicItemKind.Strategic, it, 1) }
        fuzzyFindName(sem.aiGivesStrategic, aiStrategics)?.let { items += ClassicTradeItem(false, ClassicItemKind.Strategic, it, 1) }

        when (sem.treaty) {
            "open_borders" -> {
                items += ClassicTradeItem(true, ClassicItemKind.OpenBorders, "Open Borders")
                items += ClassicTradeItem(false, ClassicItemKind.OpenBorders, "Open Borders")
            }
            "embassy" -> items += ClassicTradeItem(false, ClassicItemKind.Embassy, "Accept Embassy")
            "peace", "peace_treaty" -> items += ClassicTradeItem(true, ClassicItemKind.PeaceTreaty, "Peace Treaty")
            "research_agreement" -> items += ClassicTradeItem(true, ClassicItemKind.ResearchAgreement, "Research Agreement")
            "defensive_pact" -> items += ClassicTradeItem(true, ClassicItemKind.DefensivePact, "Defensive Pact")
        }

        if (sem.playerGivesGold > 0) items += ClassicTradeItem(true, ClassicItemKind.Gold, "Gold", sem.playerGivesGold)
        if (sem.playerGivesGpt > 0) items += ClassicTradeItem(true, ClassicItemKind.GoldPerTurn, "Gold per turn", sem.playerGivesGpt)
        if (sem.aiGivesGold > 0) items += ClassicTradeItem(false, ClassicItemKind.Gold, "Gold", sem.aiGivesGold)
        if (sem.aiGivesGpt > 0) items += ClassicTradeItem(false, ClassicItemKind.GoldPerTurn, "Gold per turn", sem.aiGivesGpt)

        if (items.isEmpty()) return null
        val explicitFree = playerText.isNotBlank() && TradeIntentParser.isExplicitFreeGift(playerText)
        val isOpen = !explicitFree && (sem.intent == "open_inquiry" || (items.all { it.fromPlayer && it.kind != ClassicItemKind.Gold && it.kind != ClassicItemKind.GoldPerTurn } && sem.aiGivesGold == 0))
        return ClassicTradeDraft(playerCiv, aiCiv, items, isOpenInquiry = isOpen)
    }
}
