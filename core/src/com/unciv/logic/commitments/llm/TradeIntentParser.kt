package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.CivName
import com.unciv.logic.commitments.DealDraft

/**
 * Plain-data representation of a classic Unciv trade item (Luxuries, Strategic Resources, Open Borders,
 * Embassy, Peace Treaty, Research Agreement, Defensive Pact, Gold, Gold Per Turn, Cities) so the parser
 * and LLM intent extractor can be tested without libGDX or Unciv classes.
 */
enum class ClassicItemKind {
    Gold, GoldPerTurn, Luxury, Strategic, City, OpenBorders, Embassy, PeaceTreaty, ResearchAgreement, DefensivePact
}

enum class HaggleDirection {
    /** Player wants the AI to pay/offer more gold for the items currently on the table ("actually, I want more", "why won't you offer me more?"). */
    MoreFromAi,
    /** Player wants to pay less gold for the AI's items on the table ("that's too expensive, how about less?"). */
    LessFromPlayer
}

/** A non-capital city that can be traded in Unciv ([id] is the internal City.id, [name] is the display name). */
data class TradableCity(val id: String, val name: String)

data class ClassicTradeItem(
    val fromPlayer: Boolean,
    val kind: ClassicItemKind,
    val name: String,
    val amount: Int = 1,
    /** For [ClassicItemKind.City], holds `City.id` required by Unciv's `TradeOffer`; empty if no tradable city exists. */
    val cityId: String = ""
) {
    fun describe(playerCiv: String, aiCiv: String): String {
        val giver = if (fromPlayer) playerCiv else aiCiv
        val receiver = if (fromPlayer) aiCiv else playerCiv
        return when (kind) {
            ClassicItemKind.Gold -> "$giver gives $receiver $amount Gold"
            ClassicItemKind.GoldPerTurn -> "$giver gives $receiver $amount Gold per turn"
            ClassicItemKind.Luxury, ClassicItemKind.Strategic ->
                "$giver trades ${if (amount > 1) "$amount " else ""}$name to $receiver"
            ClassicItemKind.City -> "$giver cedes the city of $name to $receiver"
            ClassicItemKind.OpenBorders -> "$giver grants Open Borders to $receiver"
            ClassicItemKind.Embassy -> "$giver accepts an Embassy from $receiver"
            ClassicItemKind.PeaceTreaty -> "Peace Treaty between $playerCiv and $aiCiv"
            ClassicItemKind.ResearchAgreement -> "Research Agreement between $playerCiv and $aiCiv"
            ClassicItemKind.DefensivePact -> "Defensive Pact between $playerCiv and $aiCiv"
        }
    }
}

data class ClassicTradeDraft(
    val playerCiv: CivName,
    val aiCiv: CivName,
    val items: List<ClassicTradeItem>,
    /** True when the player offered or asked about an item without naming a price (so the AI should name its price). */
    val isOpenInquiry: Boolean = false,
    /** Non-null when the player asked for "more" or "less" on an active multi-turn trade without naming a specific number. */
    val haggleDirection: HaggleDirection? = null,
    /** True when the player asked what the AI would want in order to sell one of the AI's cities. */
    val isAiCityPriceInquiry: Boolean = false
) {
    fun describe(): String = items.joinToString(" AND ") { it.describe(playerCiv, aiCiv) }
}

sealed interface ClassicTradeAssessment {
    val draft: ClassicTradeDraft
    fun toPromptDirective(): String

    data class Accepted(override val draft: ClassicTradeDraft, val customDirective: String? = null) : ClassicTradeAssessment {
        override fun toPromptDirective(): String {
            if (customDirective != null) return customDirective
            val isPurePlayerGift = draft.items.isNotEmpty() && draft.items.all { it.fromPlayer }
            return if (isPurePlayerGift) {
                "ENGINE TRADE VERDICT: The player is generously gifting you ${draft.describe()} for nothing in return! " +
                    "You HAVE DECIDED TO GLADLY ACCEPT this gift. Thank them in character, state clearly that you accept, and invite them to confirm the trade card. Do NOT refuse."
            } else {
                "ENGINE TRADE VERDICT: The player proposed a trade: ${draft.describe()}. " +
                    "You HAVE DECIDED TO ACCEPT this trade. Say in character that you accept these terms and invite them to confirm the trade card."
            }
        }
    }

    data class Counteroffered(override val draft: ClassicTradeDraft, val counterDescription: String) : ClassicTradeAssessment {
        override fun toPromptDirective(): String =
            if (draft.isOpenInquiry) {
                val playerItemsDesc = draft.items.filter { it.fromPlayer }
                    .joinToString(" AND ") { it.describe(draft.playerCiv, draft.aiCiv) }
                    .ifBlank { draft.describe() }
                "ENGINE TRADE VERDICT: The player asked what you would offer for $playerItemsDesc. " +
                    "You ARE INTERESTED and put forward this offer: $counterDescription. " +
                    "State your offer in character (naming the items and exact figures in digits) and invite them to confirm the trade card."
            } else {
                "ENGINE TRADE VERDICT: The player proposed: ${draft.describe()}. " +
                    "That is not quite enough, so you offer a COUNTEROFFER: $counterDescription. " +
                    "State in character that you cannot accept their original offer, but you will agree to your counteroffer shown on the trade card."
            }
    }

    data class Declined(override val draft: ClassicTradeDraft, val reason: String) : ClassicTradeAssessment {
        override fun toPromptDirective(): String =
            "ENGINE TRADE VERDICT: The player proposed: ${draft.describe()}. " +
                "You REFUSE this trade ($reason). Refuse in character and explain why."
    }
}

/**
 * Parses natural-language proposals for classic Unciv trade items (cities, pure gold gifts, resources,
 * open borders, embassies, treaties, gold per turn), handles open-ended inquiries ("I have too many cities, would you like any?"),
 * pronoun continuity ("give me something", "i will give you it for free"), and relative multi-turn haggling ("actually, I want more").
 */
object TradeIntentParser {
    private val ignoreCase = RegexOption.IGNORE_CASE

    /** Questions about names/facts ("may you name one of my cities?", "do you know what gold I have?") are never trade proposals. */
    private val informationalQuestionRegex = Regex(
        """\b(?:may\s+you\s+name|can\s+you\s+name|name\s+one\s+of|do\s+you\s+(?:even\s+)?know|what\s+(?:gold|resources|cities|luxuries)\s+(?:do\s+)?(?:i|you)\s+(?:have|bring|possess)|which\s+(?:luxury|strategic|resources|cities)\s+(?:do\s+)?you\s+have|how\s+(?:much|many)\s+(?:gold|cities|units)\s+do\s+you\s+have|tell\s+me\s+(?:about|plainly|what))\b""",
        ignoreCase
    )

    /** Open-ended valuation inquiries ("I have a city Samoa. would you be interested?", "i have too many cities. would you like any?"). */
    private val valuationInquiryRegex = Regex(
        """\b(?:would\s+you\s+(?:be\s+interested|like|want|care\s+for|accept)|are\s+you\s+interested|do\s+you\s+want\s+(?:any|one|my|a\s+city|it)|what\s+(?:would|will)\s+you\s+(?:give|pay|offer)|what(?:'s|\s+is)\s+it\s+worth|off\s+my\s+hands|how\s+much\s+(?:would\s+you|will\s+you|gold\s+for|for\s+my)|give\s+me\s+something|offer\s+me\s+something)\b""",
        ignoreCase
    )

    /** Asking what the AI wants in order to sell one of the AI's cities ("what would you theoretically trade a city for?"). */
    private val aiCityPriceInquiryRegex = Regex(
        """\b(?:what\s+would\s+you\s+(?:[\w]+\s+)?(?:trade|sell|give|cede)\s+(?:a\s+city|(?:one|1)\s+of\s+your\s+cities|your\s+city)\s+for|what\s+do\s+you\s+want\s+for\s+(?:a\s+city|(?:one|1)\s+of\s+your\s+cities|your\s+city))\b""",
        ignoreCase
    )

    /** Explicit free-gift intent ("for free", "for nothing", "as a gift", "do not have to give me anything"). */
    private val explicitFreeGiftRegex = Regex(
        """\b(?:for\s+free|for\s+nothing|free\s+of\s+charge|no\s+charge|as\s+a\s+gift|without\s+asking\s+anything|(?:do\s+not|don't)\s+(?:have\s+to|need\s+to)\s+(?:give|pay|offer)\s+me\s+anything|nothing\s+in\s+return)\b""",
        ignoreCase
    )
    private val whatAiWantsFromPlayerRegex = Regex(
        """\b(?:what\s+(?:would|do)\s+you\s+(?:like|want|desire|need)\s+(?:of\s+mine|from\s+me|from\s+my)|anything\s+of\s+mine\s+you\s+(?:like|want)|what\s+can\s+i\s+(?:give|offer|trade)\s+you)\b""",
        ignoreCase
    )
    private val goldForCitiesEitherOrRegex = Regex(
        """\b(?:gold\s+for\s+cities|cities\s+for\s+gold)\b""",
        ignoreCase
    )

    fun isExplicitFreeGift(text: String): Boolean {
        val norm = NumberWords.normalize(text)
        return explicitFreeGiftRegex.containsMatchIn(norm) && !negatedFreeRegex.containsMatchIn(norm)
    }
    private val negatedFreeRegex = Regex(
        """\b(?:cannot|can't|will\s+not|won't|not|never)\s+(?:just\s+)?(?:give|offer|hand)\s+(?:you\s+)?(?:a\s+city|my\s+city|it)\s+for\s+(?:nothing|free)\b""",
        ignoreCase
    )

    /** Resource and city trades require clear transfer/exchange verbs OR "for <amount> gold" OR a valuation inquiry. */
    private val tradeActionRegex = Regex(
        """\b(?:give|offer|trade|sell|buy|cede|hand\s+over|exchange|swap|pay|send|supply|gift|obtain|for\s+your|for\s+my|for\s+(?:some\s+)?gold|for\s+free|for\s+\d|in\s+exchange)\b""",
        ignoreCase
    )

    /** Follow-up haggling cues with a number ("actually you give me 500 gold", "make it 200 gold"). */
    private val followUpPriceChangeRegex = Regex(
        """\b(?:actually|instead|make\s+it|change\s+it\s+to|how\s+about|what\s+about|then\s+give\s+me|you\s+give\s+me)\b""",
        ignoreCase
    )

    /** Follow-up relative haggling cues WITHOUT a number ("actually. i want more", "why won't you offer me more?", "give me more gold"). */
    private val haggleMoreFromAiRegex = Regex(
        """\b(?:i\s+want\s+more|offer\s+(?:me\s+)?more|give\s+(?:me\s+)?more|pay\s+(?:me\s+)?more|more\s+gold|higher\s+offer|raise\s+your\s+offer|up\s+the\s+(?:ante|offer)|sweeten\s+the\s+(?:pot|deal)|cough\s+up\s+more|stingy|too\s+low|not\s+enough\s+gold|you\s+have\s+more|throw\s+in\s+(?:some|a|your)|add\s+(?:some\s+|a\s+)?(?:luxury|luxuries|resources|gold)|what\s+else\s+(?:can|will|do)\s+you\s+(?:offer|give|have))\b""",
        ignoreCase
    )
    private val haggleLessFromPlayerRegex = Regex(
        """\b(?:too\s+expensive|too\s+much\s+gold|too\s+steep|lower\s+the\s+price|pay\s+less|how\s+about\s+less)\b""",
        ignoreCase
    )

    private val openBordersRegex = Regex("""\bopen\s+borders\b""", ignoreCase)
    private val embassyRegex = Regex("""\b(?:exchange\s+embassies|accept\s+(?:an\s+|my\s+)?embassy|establish\s+(?:an\s+)?embassy)\b""", ignoreCase)
    private val peaceTreatyRegex = Regex("""\b(?:peace\s+treaty|make\s+peace|sign\s+peace|end\s+(?:this|our)\s+war)\b""", ignoreCase)
    private val researchAgreementRegex = Regex("""\bresearch\s+agreement\b""", ignoreCase)
    private val defensivePactRegex = Regex("""\bdefensive\s+pact\b""", ignoreCase)

    // Matches player offering their own city (requires "my city", "one of my cities", "an entire city", or "I will give you a city" - NOT negated and NOT "what would you trade a city for")
    private val playerGenericCityRegex = Regex(
        """\b(?:(?<!cannot\s)(?<!can't\s)(?<!not\s)(?<!just\s)(?:give|offer|trade|sell|cede|hand\s+over)\s+(?:you\s+)?(?:(?:one|1)\s+of\s+my\s+cities|a\s+city\s+of\s+mine|my\s+city|an\s+entire\s+city)|(?:(?:one|1)\s+of\s+my\s+cities|a\s+city\s+of\s+mine|my\s+city|an\s+entire\s+city)\s+(?:for|in\s+exchange|if\s+you)|(?:i\s+have\s+(?:too\s+)?many\s+cities|my\s+cities).*?\b(?:would\s+you\s+(?:like|want|be\s+interested)|do\s+you\s+want|for\s+(?:some\s+)?gold))""",
        setOf(ignoreCase, RegexOption.DOT_MATCHES_ALL)
    )
    private val aiGenericCityRegex = Regex(
        """\b(?:(?:give|offer|trade|sell|cede|hand\s+over)\s+(?:me|us)\s+(?:just\s+|at\s+least\s+|even\s+)?(?:(?:one|1)\s+of\s+your\s+cities|a\s+city\s+of\s+yours|your\s+city|a\s+city|an\s+entire\s+city)|(?:(?:one|1)\s+of\s+your\s+cities|a\s+city\s+of\s+yours|your\s+city)\s+(?:for|in\s+exchange|if\s+i))""",
        ignoreCase
    )

    private val explicitPlayerGoldLump = Regex(
        """\b(?:i(?:'ll|\s+will|\s+can|\s+shall)?\s+(?:give|pay|offer|send|gift|hand|grant)\s+(?:you\s+)?(\d[\d,]*)\s*gold(?!\s*(?:per\s+turn|gpt|a\s+turn))|(?:take|accept)\s+my\s+(\d[\d,]*)\s*gold(?!\s*(?:per\s+turn|gpt|a\s+turn)))""",
        ignoreCase
    )
    private val explicitPlayerGoldGpt = Regex(
        """\b(?:i(?:'ll|\s+will|\s+can|\s+shall)?\s+(?:give|pay|offer|send)\s+(?:you\s+)?(\d[\d,]*)\s*(?:gold\s*)?(?:per\s+turn|gpt|a\s+turn))""",
        ignoreCase
    )
    private val explicitAiGoldLump = Regex(
        """\b(?:(?:you\s+(?:will\s+|can\s+)?(?:give|pay|offer|send|hand)\s+(?:me|us)\s+|(?<!i\s)(?<!i\swill\s)(?<!i'll\s)(?:give|pay|send|hand)\s+(?:me|us)\s+|ask\s+for\s+)(\d[\d,]*)\s*gold(?!\s*(?:per\s+turn|gpt|a\s+turn)))""",
        ignoreCase
    )
    private val explicitAiGoldGpt = Regex(
        """\b(?:(?:you\s+(?:will\s+|can\s+)?(?:give|pay|offer|send)\s+(?:me|us)\s+|(?<!i\s)(?<!i\swill\s)(?<!i'll\s)(?:give|pay|send)\s+(?:me|us)\s+|ask\s+for\s+)(\d[\d,]*)\s*(?:gold\s*)?(?:per\s+turn|gpt|a\s+turn))""",
        ignoreCase
    )
    private val forGoldLump = Regex(
        """\b(?:for|in\s+exchange\s+for)\s+(\d[\d,]*)\s*gold(?!\s*(?:per\s+turn|gpt|a\s+turn))""",
        ignoreCase
    )
    private val forGoldGpt = Regex(
        """\b(?:for|in\s+exchange\s+for)\s+(\d[\d,]*)\s*(?:gold\s*)?(?:per\s+turn|gpt|a\s+turn)""",
        ignoreCase
    )
    private val allPlayerGoldRegex = Regex(
        """\b(?:all\s+(?:of\s+)?my\s+gold|all\s+the\s+gold\s+i\s+have|my\s+entire\s+treasury)""",
        ignoreCase
    )
    private val allAiGoldRegex = Regex(
        """\b(?:all\s+(?:of\s+)?your\s+gold|all\s+the\s+gold\s+you\s+have|your\s+entire\s+treasury)""",
        ignoreCase
    )

    fun parseClassicTrade(
        text: String,
        playerCiv: CivName,
        aiCiv: CivName,
        playerLuxuries: List<String>,
        aiLuxuries: List<String>,
        playerStrategics: List<String>,
        aiStrategics: List<String>,
        playerCities: List<TradableCity> = emptyList(),
        aiCities: List<TradableCity> = emptyList(),
        aiGold: Int = 0,
        playerGold: Int = 0,
        previousDraft: ClassicTradeDraft? = null
    ): ClassicTradeDraft? {
        val normalized = NumberWords.normalize(text)

        // 0. Check if the player is asking what the AI wants in exchange for one of the AI's cities
        if (aiCityPriceInquiryRegex.containsMatchIn(normalized)) {
            val firstAiCity = aiCities.firstOrNull()
            val item = if (firstAiCity != null) {
                ClassicTradeItem(false, ClassicItemKind.City, firstAiCity.name, 1, cityId = firstAiCity.id)
            } else {
                ClassicTradeItem(false, ClassicItemKind.City, "Capital", 1, cityId = "")
            }
            return ClassicTradeDraft(playerCiv, aiCiv, listOf(item), isAiCityPriceInquiry = true)
        }

        // 0b. Check if the player asked "what would you like of mine?" or "gold for cities either or"
        if (whatAiWantsFromPlayerRegex.containsMatchIn(normalized)) {
            val wantedLux = playerLuxuries.firstOrNull { it !in aiLuxuries } ?: playerLuxuries.firstOrNull()
            val wantedStrat = playerStrategics.firstOrNull { it !in aiStrategics } ?: playerStrategics.firstOrNull()
            val wantedCity = playerCities.firstOrNull()
            val item = when {
                wantedLux != null -> ClassicTradeItem(true, ClassicItemKind.Luxury, wantedLux, 1)
                wantedStrat != null -> ClassicTradeItem(true, ClassicItemKind.Strategic, wantedStrat, 1)
                wantedCity != null -> ClassicTradeItem(true, ClassicItemKind.City, wantedCity.name, 1, wantedCity.id)
                else -> return null
            }
            return ClassicTradeDraft(playerCiv, aiCiv, listOf(item), isOpenInquiry = true)
        }
        if (goldForCitiesEitherOrRegex.containsMatchIn(normalized)) {
            val firstPlayerCity = playerCities.firstOrNull()
            if (firstPlayerCity != null) {
                return ClassicTradeDraft(playerCiv, aiCiv, listOf(ClassicTradeItem(true, ClassicItemKind.City, firstPlayerCity.name, 1, firstPlayerCity.id)), isOpenInquiry = true)
            }
            val firstAiCity = aiCities.firstOrNull()
            if (firstAiCity != null) {
                return ClassicTradeDraft(playerCiv, aiCiv, listOf(ClassicTradeItem(false, ClassicItemKind.City, firstAiCity.name, 1, firstAiCity.id)), isAiCityPriceInquiry = true)
            }
        }

        val isInquiry = valuationInquiryRegex.containsMatchIn(normalized)
        val hasTradeAction = tradeActionRegex.containsMatchIn(normalized)
        if (informationalQuestionRegex.containsMatchIn(normalized) && !hasTradeAction && !isInquiry) {
            return null
        }

        val items = ArrayList<ClassicTradeItem>()
        addTreatyItems(normalized, items)
        if (hasTradeAction || isInquiry) {
            addResourceItems(normalized, playerLuxuries, aiLuxuries, ClassicItemKind.Luxury, items)
            addResourceItems(normalized, playerStrategics, aiStrategics, ClassicItemKind.Strategic, items)
            addCityItems(normalized, playerCities, aiCities, items)
        }
        addGoldItems(normalized, aiGold, playerGold, items)

        val prevNonGold = previousDraft?.items?.filter { it.kind != ClassicItemKind.Gold && it.kind != ClassicItemKind.GoldPerTurn }.orEmpty()

        // 1. Multi-turn haggling WITH a new explicit gold number ("actually you give me 500 gold")
        val onlyGoldParsed = items.isNotEmpty() && items.all { it.kind == ClassicItemKind.Gold || it.kind == ClassicItemKind.GoldPerTurn }
        if (onlyGoldParsed && prevNonGold.isNotEmpty() && followUpPriceChangeRegex.containsMatchIn(normalized)) {
            val merged = ArrayList<ClassicTradeItem>()
            merged.addAll(prevNonGold)
            merged.addAll(items)
            return ClassicTradeDraft(playerCiv, aiCiv, merged, isOpenInquiry = false)
        }

        // 2. Multi-turn continuity when items is empty ("actually. i want more", "give me something", "i will give you it for free")
        if (items.isEmpty() && previousDraft != null && prevNonGold.isNotEmpty()) {
            val wantsExplicitFreeGift = explicitFreeGiftRegex.containsMatchIn(normalized) && !negatedFreeRegex.containsMatchIn(normalized)
            when {
                wantsExplicitFreeGift ->
                    return ClassicTradeDraft(playerCiv, aiCiv, prevNonGold, isOpenInquiry = false)
                haggleMoreFromAiRegex.containsMatchIn(normalized) ->
                    return previousDraft.copy(isOpenInquiry = false, haggleDirection = HaggleDirection.MoreFromAi)
                haggleLessFromPlayerRegex.containsMatchIn(normalized) ->
                    return previousDraft.copy(isOpenInquiry = false, haggleDirection = HaggleDirection.LessFromPlayer)
                isInquiry || negatedFreeRegex.containsMatchIn(normalized) ->
                    return ClassicTradeDraft(playerCiv, aiCiv, prevNonGold, isOpenInquiry = true)
            }
        }

        if (items.isEmpty()) return null

        // 3. Safeguard: if the parsed trade is ONLY the player giving a City or Resource with 0 AI items in return,
        //    treat it as an Open Valuation Inquiry UNLESS the player explicitly said "for free" / "as a gift"!
        val onlyPlayerNonGold = items.all { it.fromPlayer && it.kind != ClassicItemKind.Gold && it.kind != ClassicItemKind.GoldPerTurn && it.kind != ClassicItemKind.PeaceTreaty && it.kind != ClassicItemKind.ResearchAgreement && it.kind != ClassicItemKind.DefensivePact && it.kind != ClassicItemKind.OpenBorders }
        val explicitFree = explicitFreeGiftRegex.containsMatchIn(normalized) && !negatedFreeRegex.containsMatchIn(normalized)
        val openInquiry = onlyPlayerNonGold && !explicitFree
        return ClassicTradeDraft(playerCiv, aiCiv, items, isOpenInquiry = openInquiry)
    }

    private fun addTreatyItems(text: String, items: MutableList<ClassicTradeItem>) {
        if (peaceTreatyRegex.containsMatchIn(text)) {
            items += ClassicTradeItem(true, ClassicItemKind.PeaceTreaty, "Peace Treaty")
        }
        if (researchAgreementRegex.containsMatchIn(text)) {
            items += ClassicTradeItem(true, ClassicItemKind.ResearchAgreement, "Research Agreement")
        }
        if (defensivePactRegex.containsMatchIn(text)) {
            items += ClassicTradeItem(true, ClassicItemKind.DefensivePact, "Defensive Pact")
        }
        if (openBordersRegex.containsMatchIn(text)) {
            items += ClassicTradeItem(true, ClassicItemKind.OpenBorders, "Open Borders")
            items += ClassicTradeItem(false, ClassicItemKind.OpenBorders, "Open Borders")
        }
        if (embassyRegex.containsMatchIn(text)) {
            items += ClassicTradeItem(false, ClassicItemKind.Embassy, "Accept Embassy")
        }
    }

    private fun addResourceItems(
        text: String,
        playerOwned: List<String>,
        aiOwned: List<String>,
        kind: ClassicItemKind,
        items: MutableList<ClassicTradeItem>
    ) {
        for (res in aiOwned) {
            if (Regex("""\b${Regex.escape(res)}\b""", ignoreCase).containsMatchIn(text)) {
                val playerGivesIt = res in playerOwned && Regex("""\bmy\s+${Regex.escape(res)}""", ignoreCase).containsMatchIn(text)
                if (!playerGivesIt) items += ClassicTradeItem(false, kind, res, 1)
            }
        }
        for (res in playerOwned) {
            if (Regex("""\b${Regex.escape(res)}\b""", ignoreCase).containsMatchIn(text)) {
                val alreadyAi = items.any { !it.fromPlayer && it.name.equals(res, ignoreCase = true) }
                if (!alreadyAi) items += ClassicTradeItem(true, kind, res, 1)
            }
        }
    }

    private fun addCityItems(
        text: String,
        playerCities: List<TradableCity>,
        aiCities: List<TradableCity>,
        items: MutableList<ClassicTradeItem>
    ) {
        var matchedAiCity = false
        val fuzzyAiCityName = SemanticNegotiationParser.fuzzyFindName(text, aiCities.map { it.name })
        if (fuzzyAiCityName != null) {
            val city = aiCities.first { it.name == fuzzyAiCityName }
            items += ClassicTradeItem(false, ClassicItemKind.City, city.name, 1, cityId = city.id)
            matchedAiCity = true
        }
        if (!matchedAiCity && aiGenericCityRegex.containsMatchIn(text)) {
            val first = aiCities.firstOrNull()
            if (first != null) {
                items += ClassicTradeItem(false, ClassicItemKind.City, first.name, 1, cityId = first.id)
            } else {
                items += ClassicTradeItem(false, ClassicItemKind.City, "Capital", 1, cityId = "")
            }
            matchedAiCity = true
        }

        var matchedPlayerCity = false
        val fuzzyPlayerCityName = SemanticNegotiationParser.fuzzyFindName(text, playerCities.map { it.name })
        if (fuzzyPlayerCityName != null) {
            val city = playerCities.first { it.name == fuzzyPlayerCityName }
            items += ClassicTradeItem(true, ClassicItemKind.City, city.name, 1, cityId = city.id)
            matchedPlayerCity = true
        }
        if (!matchedPlayerCity && !matchedAiCity && !negatedFreeRegex.containsMatchIn(text) && playerGenericCityRegex.containsMatchIn(text)) {
            val first = playerCities.firstOrNull()
            if (first != null) {
                items += ClassicTradeItem(true, ClassicItemKind.City, first.name, 1, cityId = first.id)
            } else {
                items += ClassicTradeItem(true, ClassicItemKind.City, "Capital", 1, cityId = "")
            }
        }
    }

    private fun addGoldItems(text: String, aiGold: Int, playerGold: Int, items: MutableList<ClassicTradeItem>) {
        val playerGivesNonGold = items.any { it.fromPlayer }
        val aiGivesNonGold = items.any { !it.fromPlayer }

        val pLump = extractNum(explicitPlayerGoldLump, text)
            ?: if (allPlayerGoldRegex.containsMatchIn(text) && playerGold > 0) playerGold else null
        val pGpt = extractNum(explicitPlayerGoldGpt, text)
        val aLump = extractNum(explicitAiGoldLump, text)
            ?: if (allAiGoldRegex.containsMatchIn(text) && aiGold > 0) aiGold else null
        val aGpt = extractNum(explicitAiGoldGpt, text)

        if (pLump != null && pLump > 0) items += ClassicTradeItem(true, ClassicItemKind.Gold, "Gold", pLump)
        if (pGpt != null && pGpt > 0) items += ClassicTradeItem(true, ClassicItemKind.GoldPerTurn, "Gold per turn", pGpt)
        if (aLump != null && aLump > 0) items += ClassicTradeItem(false, ClassicItemKind.Gold, "Gold", aLump)
        if (aGpt != null && aGpt > 0) items += ClassicTradeItem(false, ClassicItemKind.GoldPerTurn, "Gold per turn", aGpt)

        val forLump = extractNum(forGoldLump, text)
        if (forLump != null && forLump > 0 && forLump != pLump && forLump != aLump) {
            val fromPlayer = !(playerGivesNonGold && !aiGivesNonGold)
            items += ClassicTradeItem(fromPlayer, ClassicItemKind.Gold, "Gold", forLump)
        }
        val forGpt = extractNum(forGoldGpt, text)
        if (forGpt != null && forGpt > 0 && forGpt != pGpt && forGpt != aGpt) {
            val fromPlayer = !(playerGivesNonGold && !aiGivesNonGold)
            items += ClassicTradeItem(fromPlayer, ClassicItemKind.Gold, "Gold", forGpt)
        }
    }

    /**
     * Two-stage LLM fallback: when a message looks like a proposal/request but did not match the fast
     * regexes, asks the LLM to emit a compact JSON object describing the commitment or trade, and parses it.
     */
    fun extractWithLlmFallback(
        backend: LlmBackend,
        playerText: String,
        human: CivName,
        ai: CivName,
        knownThirdCivs: List<CivName>,
        knownConstructions: List<String>,
        now: Int,
        reasoningEffort: String? = null
    ): DealDraft? {
        val system = buildString {
            appendLine("Convert the player's diplomatic proposal into a single JSON object. Output ONLY valid JSON.")
            appendLine("Allowed types: \"no_settle\", \"no_attack\", \"mutual_no_attack\", \"embargo\", \"dmz\", \"protect_cs\", \"vote_for_me\", \"loan_to_player\", \"war_on_third\", \"subsidy_war\", \"build\", \"none\".")
            if (knownThirdCivs.isNotEmpty()) appendLine("Known third nations: ${knownThirdCivs.joinToString(", ")}.")
            appendLine("Schema: {\"type\":string, \"target\":string, \"gold\":number, \"gpt\":number, \"turns\":number}")
        }.trimEnd()
        return try {
            val raw = backend.generate(
                LlmRequest(system = system, user = playerText, maxTokens = 160, temperature = 0.1f, reasoningEffort = reasoningEffort)
            ).text
            val jsonStart = raw.indexOf('{')
            val jsonEnd = raw.lastIndexOf('}')
            if (jsonStart < 0 || jsonEnd <= jsonStart) return null
            val map = MiniJson.parse(raw.substring(jsonStart, jsonEnd + 1)) as? Map<*, *> ?: return null
            draftFromJsonMap(map, human, ai, knownThirdCivs, knownConstructions, now)
        } catch (_: Exception) {
            null
        }
    }

    private fun draftFromJsonMap(
        map: Map<*, *>,
        human: CivName,
        ai: CivName,
        knownThirdCivs: List<CivName>,
        knownConstructions: List<String>,
        now: Int
    ): DealDraft? {
        val type = (map["type"] as? String)?.lowercase()?.trim() ?: return null
        val target = (map["target"] as? String)?.trim().orEmpty()
        val gold = (map["gold"] as? Number)?.toInt() ?: 0
        val gpt = (map["gpt"] as? Number)?.toInt() ?: 0
        val turns = ((map["turns"] as? Number)?.toInt() ?: 20).coerceIn(5, 60)

        val canonicalSentence = when (type) {
            "no_settle" -> "Do not settle near my cities for $turns turns" + (if (gold > 0) " and I will give you $gold gold" else "")
            "no_attack" -> "Do not attack me for $turns turns" + (if (gold > 0) " and I will give you $gold gold" else "")
            "mutual_no_attack" -> "Let us sign a non-aggression pact for $turns turns"
            "embargo" -> if (target.isNotEmpty()) "Embargo $target for $turns turns" + (if (gold > 0) " and I will give you $gold gold" else "") else return null
            "dmz" -> "Let us establish a demilitarised zone for $turns turns"
            "protect_cs" -> if (target.isNotEmpty()) "Do not bully $target for $turns turns" + (if (gold > 0) " and I will give you $gold gold" else "") else return null
            "vote_for_me" -> "Vote for me in the diplomatic vote for $turns turns" + (if (gold > 0) " and I will give you $gold gold" else "")
            "loan_to_player" -> if (gold > 0 && gpt > 0) "Lend me $gold gold and I will pay $gpt gold per turn for $turns turns" else return null
            "war_on_third" -> if (target.isNotEmpty()) "Declare war on $target in $turns turns" + (if (gold > 0) " for $gold gold" else "") else return null
            "subsidy_war" -> if (target.isNotEmpty() && gpt > 0) "Stay at war with $target for $gpt gold per turn for $turns turns" else return null
            "build" -> if (target.isNotEmpty()) "Build a $target for $turns turns" + (if (gold > 0) " and I will give you $gold gold" else "") else return null
            else -> return null
        }
        return ProposalParser.parse(canonicalSentence, human, ai, knownThirdCivs, knownConstructions, now)
    }

    private fun extractNum(regex: Regex, text: String): Int? =
        regex.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.replace(",", "")?.toIntOrNull()
}


/**
 * Builds multi-asset barter packages (combining Luxury Resources, Strategic Resources, Gold Per Turn,
 * and Lump-Sum Gold) so AI civilisations do not rely solely on flat gold when making or countering offers.
 */
object MultiAssetBarter {
    const val LUXURY_VALUATION = 240
    const val STRATEGIC_UNIT_VALUATION = 45
    const val GPT_TURN_FACTOR = 20

    /**
     * Constructs an AI offer (items with `fromPlayer = false`) worth up to [targetValue] using the AI's
     * spare luxuries, strategic resources, lump-sum gold, and gold per turn.
     */
    fun buildAiOfferForPlayerItems(
        targetValue: Int,
        aiGold: Int,
        aiGoldPerTurn: Int,
        aiLuxuriesForPlayer: List<String>,
        aiStrategicsForPlayer: Map<String, Int>,
        preferResourcesFirst: Boolean = false
    ): List<ClassicTradeItem> {
        if (targetValue <= 0) return emptyList()
        var remaining = targetValue
        val offered = ArrayList<ClassicTradeItem>()
        val maxLumpGold = (aiGold * 0.88f).toInt().coerceAtLeast(0)

        // 1. If the AI cannot cover the target value with lump gold alone (or if resources are preferred), include spare luxuries
        if (preferResourcesFirst || targetValue > maxLumpGold) {
            for (lux in aiLuxuriesForPlayer.take(2)) {
                if (remaining >= LUXURY_VALUATION / 2) {
                    offered += ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.Luxury, name = lux, amount = 1)
                    remaining = (remaining - LUXURY_VALUATION).coerceAtLeast(0)
                }
            }
        }

        // 2. Include spare strategic resources if there is still a gap beyond lump gold
        if (preferResourcesFirst || remaining > maxLumpGold) {
            for ((strat, count) in aiStrategicsForPlayer) {
                val giveCount = count.coerceAtMost(5)
                if (giveCount > 0 && remaining >= STRATEGIC_UNIT_VALUATION) {
                    val neededUnits = ((remaining + STRATEGIC_UNIT_VALUATION - 1) / STRATEGIC_UNIT_VALUATION).coerceIn(1, giveCount)
                    offered += ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.Strategic, name = strat, amount = neededUnits)
                    remaining = (remaining - neededUnits * STRATEGIC_UNIT_VALUATION).coerceAtLeast(0)
                    break
                }
            }
        }

        // 3. Add lump-sum gold up to maxLumpGold
        if (remaining > 0 && maxLumpGold > 0) {
            val lump = ((remaining.coerceAtMost(maxLumpGold) / 5) * 5).coerceAtLeast(minOf(10, maxLumpGold))
            if (lump > 0) {
                offered += ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.Gold, name = "Gold", amount = lump)
                remaining = (remaining - lump).coerceAtLeast(0)
            }
        }

        // 4. Bridge any remaining value gap with Gold Per Turn (GPT)
        val maxGpt = (aiGoldPerTurn * 2 / 3).coerceAtLeast(0)
        if (remaining >= GPT_TURN_FACTOR && maxGpt > 0) {
            val gpt = (remaining / GPT_TURN_FACTOR).coerceIn(1, maxGpt)
            offered += ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.GoldPerTurn, name = "Gold per turn", amount = gpt)
        }

        return offered
    }

    /**
     * Constructs a multi-asset counteroffer demand from the player (`fromPlayer = true`) worth at least
     * [requiredValue] using the player's luxuries wanted by the AI, strategic resources, lump gold, and GPT.
     * Returns null if the player's total combinable assets cannot reach at least 85% of [requiredValue].
     */
    fun buildAiDemandFromPlayer(
        requiredValue: Int,
        playerGold: Int,
        playerGoldPerTurn: Int,
        playerLuxuriesWantedByAi: List<String>,
        playerStrategicsWantedByAi: Map<String, Int>
    ): List<ClassicTradeItem>? {
        if (requiredValue <= 0) return emptyList()
        var remaining = requiredValue
        val demanded = ArrayList<ClassicTradeItem>()

        // 1. Ask for luxuries the player has that the AI lacks
        for (lux in playerLuxuriesWantedByAi.take(3)) {
            if (remaining > 0) {
                demanded += ClassicTradeItem(fromPlayer = true, kind = ClassicItemKind.Luxury, name = lux, amount = 1)
                remaining = (remaining - LUXURY_VALUATION).coerceAtLeast(0)
            }
        }

        // 2. Ask for strategic resources the player has that the AI wants
        for ((strat, count) in playerStrategicsWantedByAi) {
            val takeUnits = count.coerceAtMost(5)
            if (takeUnits > 0 && remaining >= STRATEGIC_UNIT_VALUATION) {
                val units = ((remaining + STRATEGIC_UNIT_VALUATION - 1) / STRATEGIC_UNIT_VALUATION).coerceIn(1, takeUnits)
                demanded += ClassicTradeItem(fromPlayer = true, kind = ClassicItemKind.Strategic, name = strat, amount = units)
                remaining = (remaining - units * STRATEGIC_UNIT_VALUATION).coerceAtLeast(0)
                break
            }
        }

        // 3. Ask for lump-sum gold up to 90% of player's treasury
        val maxPlayerLump = (playerGold * 0.9f).toInt().coerceAtLeast(0)
        if (remaining > 0 && maxPlayerLump > 0) {
            val lump = (((remaining.coerceAtMost(maxPlayerLump) + 4) / 5) * 5).coerceAtMost(maxPlayerLump)
            if (lump > 0) {
                demanded += ClassicTradeItem(fromPlayer = true, kind = ClassicItemKind.Gold, name = "Gold", amount = lump)
                remaining = (remaining - lump).coerceAtLeast(0)
            }
        }

        // 4. Ask for Gold Per Turn if lump gold + resources did not cover the full price
        val maxPlayerGpt = (playerGoldPerTurn * 3 / 4).coerceAtLeast(0)
        if (remaining > 0 && maxPlayerGpt > 0) {
            val gpt = ((remaining + GPT_TURN_FACTOR - 1) / GPT_TURN_FACTOR).coerceIn(1, maxPlayerGpt)
            demanded += ClassicTradeItem(fromPlayer = true, kind = ClassicItemKind.GoldPerTurn, name = "Gold per turn", amount = gpt)
            remaining = (remaining - gpt * GPT_TURN_FACTOR).coerceAtLeast(0)
        }

        // Check if we covered at least 85% of the required value
        return if (remaining <= requiredValue * 0.15f && demanded.isNotEmpty()) demanded else null
    }
}
