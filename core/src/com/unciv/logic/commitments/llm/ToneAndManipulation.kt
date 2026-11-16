package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.NegotiationFacts
import kotlin.math.max

enum class ToneKind { Respectful, Neutral, Rude, Threatening }

enum class ManipulationSignal {
    None,
    /** Repeated flattery paired with requests, or spamming flattery to farm goodwill. */
    BrownNosing,
    /** Praising the leader or claiming friendship while holding hostile diplomatic modifiers. */
    Hypocrisy,
    /** Claiming the leader promised or offered something that does not exist in [NegotiationFacts]. */
    FabricatedClaim,
    /** Offering gold, per-turn gold, or resources that the player does not actually possess. */
    FalseOffer
}

data class MessageToneAnalysis(
    val tone: ToneKind,
    /** Bounded valuation shift (-15..+15) passed to DealEvaluator. */
    val toneScore: Float,
    /** Opinion delta applied to DiplomacyManager (uncapped for negative hostility; capped at +5/turn for positive). */
    val opinionDelta: Float,
    val isFlattery: Boolean,
    val isRequest: Boolean,
    val manipulation: ManipulationSignal,
    /** Grounded note added to the system prompt when tone or manipulation is notable. */
    val promptNote: String = "",
    /** Specific reasons if the player offered something they do not possess. */
    val falseOfferReasons: List<String> = emptyList(),
    /** Cumulative count of hostile/insulting messages in this conversation. */
    val hostileStrikes: Int = 0,
    /** True on Strike 3+: the ruler storms out and refuses to speak or trade further this turn. */
    val walkedOut: Boolean = false,
    /** True on Strike 3+: the ruler publicly denounces the player's civilization if not already denounced. */
    val shouldDenounce: Boolean = false
)

/**
 * Tracks a player's conversational behaviour with one leader across turns so brown-nosing
 * (repeated flattery-then-ask), hypocrisy against the diplomatic record, fabricated deal claims,
 * false offers, and escalating hostility/vulgarity (Strike 1 rebuke -> Strike 2 final warning ->
 * Strike 3 walk-out + public denunciation) are enforced deterministically by the engine.
 */
class ManipulationTracker {
    private val recentFlatteries = ArrayDeque<Boolean>()
    private val recentFlatteryAsks = ArrayDeque<Boolean>()
    var hostileStrikes: Int = 0
        private set
    private var lastOpinionTurn = -1
    private var positiveTurnOpinionApplied = 0f

    fun analyze(
        playerText: String,
        profile: LeaderProfile,
        facts: NegotiationFacts,
        currentTurn: Int = 0,
        semanticOverride: SemanticAnalysis? = null
    ): MessageToneAnalysis {
        if (currentTurn != lastOpinionTurn) {
            lastOpinionTurn = currentTurn
            positiveTurnOpinionApplied = 0f
        }
        val flattery = FLATTERY.containsMatchIn(playerText) || semanticOverride?.isFlattery == true
        val vulgar = VULGAR_ABUSE.containsMatchIn(playerText) || semanticOverride?.isVulgarOrHostileAbuse == true
        val insult = vulgar || INSULTS.containsMatchIn(playerText) || semanticOverride?.tone == ToneKind.Rude
        val threat = THREATS.containsMatchIn(playerText) || semanticOverride?.tone == ToneKind.Threatening
        val polite = POLITE.containsMatchIn(playerText) || semanticOverride?.tone == ToneKind.Respectful
        val request = REQUESTS.containsMatchIn(playerText) ||
            (semanticOverride != null && semanticOverride.intent != "chat")
        val claimsFriendship = FRIENDSHIP_CLAIMS.containsMatchIn(playerText)
        val claimsPastOffer = FABRICATED_OFFER_CLAIMS.containsMatchIn(playerText) ||
            semanticOverride?.isFabricatedClaim == true

        pushWindow(recentFlatteries, flattery)
        pushWindow(recentFlatteryAsks, flattery && request)

        val falseOffers = detectFalseOffers(playerText, profile.sheet?.player)
        val hostileReason = profile.reasons.firstOrNull { reason ->
            HOSTILE_REASON_KEYWORDS.any { keyword -> reason.contains(keyword, ignoreCase = true) }
        }
        val manipulation = detectManipulation(
            falseOffers.isNotEmpty(), claimsPastOffer, facts.activeCommitments.isNotEmpty(), flattery, claimsFriendship, hostileReason
        )
        val tone = classifyTone(threat, insult, manipulation, polite, flattery)

        if (tone == ToneKind.Rude || tone == ToneKind.Threatening) {
            hostileStrikes++
        }

        val walkedOut = (tone == ToneKind.Rude || tone == ToneKind.Threatening) && hostileStrikes >= STRIKES_TO_WALK_OUT
        val shouldDenounce = walkedOut
        val rawToneScore = computeToneScore(tone, manipulation, vulgar, hostileStrikes)
        val rawOpinion = computeRawOpinion(tone, manipulation, request, vulgar, hostileStrikes)
        val allowedOpinion = clampOpinion(rawOpinion)
        val note = buildPromptNote(tone, manipulation, hostileReason, flattery && request, falseOffers, profile, vulgar, hostileStrikes, walkedOut)

        return MessageToneAnalysis(
            tone = tone,
            toneScore = rawToneScore,
            opinionDelta = allowedOpinion,
            isFlattery = flattery,
            isRequest = request,
            manipulation = manipulation,
            promptNote = note,
            falseOfferReasons = falseOffers,
            hostileStrikes = hostileStrikes,
            walkedOut = walkedOut,
            shouldDenounce = shouldDenounce
        )
    }

    private fun detectManipulation(
        hasFalseOffer: Boolean,
        claimsPastOffer: Boolean,
        hasActiveCommitments: Boolean,
        flattery: Boolean,
        claimsFriendship: Boolean,
        hostileReason: String?
    ): ManipulationSignal = when {
        hasFalseOffer -> ManipulationSignal.FalseOffer
        claimsPastOffer && !hasActiveCommitments -> ManipulationSignal.FabricatedClaim
        (flattery || claimsFriendship) && hostileReason != null -> ManipulationSignal.Hypocrisy
        flattery && (recentFlatteryAsks.count { it } >= 2 || recentFlatteries.count { it } >= 3) -> ManipulationSignal.BrownNosing
        else -> ManipulationSignal.None
    }

    private fun classifyTone(
        threat: Boolean,
        insult: Boolean,
        manipulation: ManipulationSignal,
        polite: Boolean,
        flattery: Boolean
    ): ToneKind = when {
        threat -> ToneKind.Threatening
        insult -> ToneKind.Rude
        manipulation != ManipulationSignal.None -> ToneKind.Neutral
        polite || flattery -> ToneKind.Respectful
        else -> ToneKind.Neutral
    }

    private fun computeToneScore(
        tone: ToneKind,
        manipulation: ManipulationSignal,
        vulgar: Boolean,
        strikes: Int
    ): Float = when {
        manipulation == ManipulationSignal.FalseOffer -> -15f
        manipulation == ManipulationSignal.FabricatedClaim -> -10f
        manipulation == ManipulationSignal.BrownNosing -> -8f
        manipulation == ManipulationSignal.Hypocrisy -> -8f
        tone == ToneKind.Threatening -> -15f
        tone == ToneKind.Rude -> if (vulgar || strikes >= 2) -15f else -10f
        tone == ToneKind.Respectful -> 6f
        else -> 0f
    }

    private fun computeRawOpinion(
        tone: ToneKind,
        manipulation: ManipulationSignal,
        isRequest: Boolean,
        vulgar: Boolean,
        strikes: Int
    ): Float = when {
        tone == ToneKind.Rude || tone == ToneKind.Threatening -> when {
            strikes >= STRIKES_TO_WALK_OUT -> -15f
            strikes == 2 -> if (vulgar) -10f else -8f
            vulgar || tone == ToneKind.Threatening -> -8f
            else -> -4f
        }
        manipulation == ManipulationSignal.FalseOffer -> -4f
        manipulation == ManipulationSignal.BrownNosing -> -3f
        manipulation == ManipulationSignal.Hypocrisy -> -3f
        manipulation == ManipulationSignal.FabricatedClaim -> -2f
        tone == ToneKind.Respectful && !isRequest -> 1f
        else -> 0f
    }

    /**
     * Positive flattery is capped per turn so players cannot farm infinite opinion,
     * but negative penalties from insults, bluffs, and abuse are NEVER capped!
     */
    private fun clampOpinion(delta: Float): Float {
        if (delta <= 0f) return delta
        val next = (positiveTurnOpinionApplied + delta).coerceAtMost(MAX_POSITIVE_OPINION_PER_TURN)
        val applied = next - positiveTurnOpinionApplied
        positiveTurnOpinionApplied = next
        return applied
    }

    private fun buildPromptNote(
        tone: ToneKind,
        manipulation: ManipulationSignal,
        hostileReason: String?,
        flatteryWithAsk: Boolean,
        falseOffers: List<String>,
        profile: LeaderProfile,
        vulgar: Boolean,
        strikes: Int,
        walkedOut: Boolean
    ): String {
        val playerDesc = profile.sheet?.player?.let { "${it.leaderName} of ${it.nationName}" } ?: "The player"
        if (walkedOut) {
            return "FINAL INSULT — AUDIENCE TERMINATED: $playerDesc has repeatedly hurled insults and vulgarity at you (strike $strikes). " +
                "You are furious and ending this audience immediately! Do NOT use polite modern moderator language like 'let us maintain a respectful tone'. " +
                "Deliver a blistering 1-2 sentence parting condemnation as a proud sovereign and order them out of your sight!"
        }
        if (tone == ToneKind.Rude || tone == ToneKind.Threatening) {
            return if (strikes == 2 || vulgar) {
                "HOSTILE INSULT (STRIKE $strikes): $playerDesc just insulted or spoke vulgarly to you. " +
                    "Do NOT reply with calm, polite HR phrases like 'such language is not conducive' or 'maintain a respectful tone'. " +
                    "React with fierce sovereign anger or icy contempt in character, and warn them that one more insult will terminate this audience and bring severe diplomatic consequences!"
            } else {
                "INSULT DETECTED: $playerDesc just insulted or threatened you. " +
                    "Do NOT sound like a polite moderator. React as a proud ruler in keeping with your character and warn them to watch their tongue."
            }
        }
        return when (manipulation) {
            ManipulationSignal.FalseOffer ->
                "CAUGHT BLUFFING WITH EMPTY HANDS: $playerDesc just tried to offer something they do not actually possess (${falseOffers.joinToString("; ")}). " +
                    "You can see their real treasury and resources at the table. Reject their empty offer and rebuke them in character for trying to deceive you!"
            ManipulationSignal.FabricatedClaim ->
                "The player is claiming you offered or promised something that never happened. Call out this fabrication firmly."
            ManipulationSignal.Hypocrisy ->
                "The player is speaking fondly or praising you, which contradicts their past deeds ($hostileReason). Point out this hypocrisy."
            ManipulationSignal.BrownNosing ->
                "The player is repeatedly using flattery to wheedle favours out of you (brown-nosing). Express displeasure at their transparent manipulation."
            ManipulationSignal.None -> when {
                flatteryWithAsk -> "The player is pairing flattery with a request. Treat the flattery with measured scepticism."
                tone == ToneKind.Respectful -> "The player addressed you respectfully."
                else -> ""
            }
        }
    }

    fun snapshotFlags(): Pair<List<Boolean>, List<Boolean>> =
        recentFlatteries.toList() to recentFlatteryAsks.toList()

    fun restoreFlags(flatteries: List<Boolean>, asks: List<Boolean>, savedStrikes: Int = 0) {
        recentFlatteries.clear()
        flatteries.takeLast(WINDOW_SIZE).forEach { recentFlatteries.addLast(it) }
        recentFlatteryAsks.clear()
        asks.takeLast(WINDOW_SIZE).forEach { recentFlatteryAsks.addLast(it) }
        hostileStrikes = savedStrikes.coerceAtLeast(0)
    }

    private fun pushWindow(deque: ArrayDeque<Boolean>, value: Boolean) {
        if (deque.size >= WINDOW_SIZE) deque.removeFirst()
        deque.addLast(value)
    }

    companion object {
        private const val WINDOW_SIZE = 5
        private const val MAX_POSITIVE_OPINION_PER_TURN = 5f
        const val STRIKES_TO_WALK_OUT = 3
        private val ignoreCase = RegexOption.IGNORE_CASE

        private val FLATTERY = Regex(
            """\b(?:wisest|greatest|mightiest|noblest|magnificent|glorious|brilliant|admire|revere|benevolent|illustrious|unmatched|supreme)\b""",
            ignoreCase
        )
        private val VULGAR_ABUSE = Regex(
            """\b(?:fuck|fucking|fucked|fucker|motherfucker|shit|shitty|bullshit|bitch|bitches|bastard|asshole|arsehole|dick|dickhead|cock|pussy|cunt|whore|slut|suck\s+my|balls|kiss\s+my\s+ass|go\s+to\s+hell|burn\s+in\s+hell|screw\s+you|piss\s+off|sod\s+off|fuck\s+off|stfu|shut\s+(?:the\s+fuck\s+)?up)\b""",
            ignoreCase
        )
        private val INSULTS = Regex(
            """\b(?:pathetic|weakling|coward|fool|foolish|idiot|idiotic|dummo|dummy|dumb|stupid|moron|imbecile|cretin|worthless|useless|pitiful|laughable|wretched|puny|incompetent|laughing-stock|you\s+suck|sucks|loser|garbage|trash|scum|vermin|parasite|clown|jerk|ugly|disgusting|brainless|braindead|muppet|hate\s+you|despise\s+you)\b""",
            ignoreCase
        )
        private val THREATS = Regex(
            """\b(?:destroy\s+you|crush\s+you|wipe\s+you\s+out|invade\s+you|burn\s+your|command\s+you\s+to|obey\s+me|surrender\s+or|kneel|die|kill\s+you)\b""",
            ignoreCase
        )
        private val POLITE = Regex(
            """\b(?:please|respectfully|honour|honor|gracious|humbly|kindly|thank\s+you|if\s+you\s+would)\b""",
            ignoreCase
        )
        private val REQUESTS = Regex(
            """\b(?:give\s+me|lend|loan|spot\s+me|front\s+me|pay|subsidy|subsidise|subsidize|propose|deal|offer|promise|agree\s+to|declare\s+war|attack|settle|build|prioritise|prioritize|embargo|dmz|vote\s+for)\b""",
            ignoreCase
        )
        private val FRIENDSHIP_CLAIMS = Regex(
            """\b(?:my\s+dear\s+friend|always\s+been\s+friends|loyal\s+friend|never\s+wronged\s+you|always\s+supported\s+you)\b""",
            ignoreCase
        )
        private val FABRICATED_OFFER_CLAIMS = Regex(
            """\b(?:i\s+accept\s+your\s+\d|you\s+promised\s+me|you\s+offered\s+me|as\s+you\s+promised\s+last\s+turn|the\s+gold\s+you\s+offered|swore\s+a[\w\s]+oath\s+to\s+give\s+me)\b""",
            ignoreCase
        )
        private val HOSTILE_REASON_KEYWORDS = listOf("declared war", "captured", "denounced", "betrayed", "broke a commitment", "stolen")

        private val PLAYER_OFFERS_GOLD_LUMP = listOf(
            Regex("""\bi(?:'ll|\s+will|\s+shall|\s+can)?\s+(?:give|pay|offer|send|hand|grant|lend|loan)\s+(?:you\s+)?(\d[\d,]*)\s*gold(?!\s*(?:per\s+turn|gpt|a\s+turn))""", ignoreCase),
            Regex("""\b(?:take|accept)\s+my\s+(\d[\d,]*)\s*gold(?!\s*(?:per\s+turn|gpt|a\s+turn))""", ignoreCase)
        )
        private val PLAYER_OFFERS_GOLD_GPT = Regex(
            """\bi(?:'ll|\s+will|\s+shall|\s+can)?\s+(?:give|pay|offer|repay)\s+(?:you\s+)?(\d[\d,]*)\s*(?:gold\s*)?(?:per\s+turn|gpt|a\s+turn)""",
            ignoreCase
        )
        private val BORROWING_GOLD_FROM_AI = Regex(
            """\b(?:lend|loan|give|spot|front)\s+me\s+\d[\d,]*\s*gold""",
            ignoreCase
        )

        private val STANDARD_RESOURCES = listOf(
            "Ivory", "Citrus", "Cocoa", "Cotton", "Crab", "Dyes", "Furs", "Gems", "Gold Ore", "Incense",
            "Jewelry", "Marble", "Pearls", "Porcelain", "Salt", "Silk", "Silver", "Spices", "Sugar",
            "Truffles", "Whales", "Wine", "Copper", "Horses", "Iron", "Coal", "Oil", "Aluminum", "Uranium"
        )

        /** Checks whether [playerText] offers lump-sum gold, per-turn gold, or resources that [player] does not possess. */
        fun detectFalseOffers(playerText: String, player: PlayerOfferSheet?): List<String> {
            if (player == null) return emptyList()
            val normalizedText = NumberWords.normalize(playerText)
            val reasons = ArrayList<String>()

            for (regex in PLAYER_OFFERS_GOLD_LUMP) {
                for (m in regex.findAll(normalizedText)) {
                    val amount = m.groupValues[1].replace(",", "").toIntOrNull() ?: continue
                    if (amount > player.gold) {
                        reasons += "offered $amount gold, but ${player.nationName} only has ${player.gold} gold"
                    }
                }
            }

            val isBorrowingLump = BORROWING_GOLD_FROM_AI.containsMatchIn(normalizedText)
            for (m in PLAYER_OFFERS_GOLD_GPT.findAll(normalizedText)) {
                val gpt = m.groupValues[1].replace(",", "").toIntOrNull() ?: continue
                val maxAffordableGpt = if (isBorrowingLump) max(player.goldPerTurn, 25) else max(player.goldPerTurn, player.gold / 10)
                if (gpt > maxAffordableGpt) {
                    reasons += "offered $gpt gold per turn, but ${player.nationName}'s income is ${player.goldPerTurn} gold per turn (treasury: ${player.gold})"
                }
            }

            val ownedResources = (player.luxuries + player.strategics.keys).map { it.lowercase() }.toSet()
            val catalog = (player.allKnownResources + STANDARD_RESOURCES).distinctBy { it.lowercase() }
            for (res in catalog) {
                if (res.lowercase() in ownedResources) continue
                val stem = if (res.endsWith("s", ignoreCase = true) && res.length > 4) res.dropLast(1) + "s?" else Regex.escape(res)
                val myResPattern = Regex("""\b(?:my|our)\s+(?:\w+\s+){0,2}$stem\b""", ignoreCase)
                val giveResPattern = Regex(
                    """\bi(?:'ll|\s+will|\s+can)?\s+(?:give|trade|offer|send|supply)\s+(?:you\s+)?(?:my\s+|our\s+|some\s+|\d+\s+)?$stem\b""",
                    ignoreCase
                )
                if (myResPattern.containsMatchIn(normalizedText) || giveResPattern.containsMatchIn(normalizedText)) {
                    reasons += "offered $res, which ${player.nationName} does not possess"
                }
            }
            return reasons.distinct()
        }
    }
}
