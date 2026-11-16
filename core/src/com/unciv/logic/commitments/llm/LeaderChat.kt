package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.NegotiationFacts

/** What a leader's voice should reflect. Built from game state by the game-side adapter; all plain data. */
data class LeaderProfile(
    val leaderName: String,
    val nationName: String,
    /** Adjectives from the leader's personality, e.g. "greedy", "militaristic". */
    val traits: List<String>,
    /** The leader's attitude towards the player, e.g. "Favorable". */
    val relationship: String,
    val atWar: Boolean,
    /** Goodwill the player has built through gifts, in gold-equivalent; 0 if none. */
    val giftCredit: Int,
    /** How the leader's military compares to the player's, in words. */
    val strength: String,
    /** The leader's own authored lines (greetings, threats...) that show how they sound. */
    val voiceSamples: List<String> = emptyList(),
    /** Facts about the nation the leader may draw on when asked about their history. */
    val background: String = "",
    /** How this leader behaves, from the game's personality values (see [PersonalityDescriber]); used instead of [traits] when set. */
    val temperament: String = "",
    /** Why the leader feels as it does about the player: the game's own opinion reasons, in the leader's words. */
    val reasons: List<String> = emptyList(),
    /** What is going on for the leader right now, e.g. wars it is fighting. */
    val situation: List<String> = emptyList(),
    /** The leader's real, current state across the game; see [NationSheet]. Null in tests and when unavailable. */
    val sheet: NationSheet? = null,
    /** The human player's leader name (e.g. "Augustus Caesar"), when known. */
    val playerLeaderName: String = "",
    /** The human player's nation name (e.g. "Rome"), when known. */
    val playerNationName: String = ""
) {
    companion object {
        /** Words for the ratio of two military strengths on the same scale, from the leader's point of view. */
        fun describeStrength(own: Float, theirs: Float): String {
            if (theirs <= 0f) return if (own > 0f) "far greater than yours" else "similar to yours"
            val ratio = own / theirs
            return when {
                ratio >= 2f -> "far greater than yours"
                ratio >= 1.25f -> "greater than yours"
                ratio >= 0.8f -> "about equal to yours"
                ratio >= 0.5f -> "smaller than yours"
                else -> "far smaller than yours"
            }
        }

        /**
         * How the leader should sound, taken from the game's own relationship level, so a leader is as warm or
         * as harsh as the game says it is, and no more guarded than that.
         */
        fun describeDemeanor(relationship: String, atWar: Boolean): String = when {
            atWar -> "You are at war with them and speak as an enemy would."
            relationship == "Ally" -> "You regard them as a close ally and speak warmly and openly."
            relationship == "Friend" -> "You regard them as a friend and speak warmly."
            relationship == "Favorable" -> "You like them and speak in a friendly way."
            relationship == "Neutral" -> "You have no strong feelings about them; speak politely and plainly."
            relationship == "Competitor" -> "You see them as a rival and speak coolly."
            relationship == "Afraid" -> "You are uneasy about their power and speak carefully."
            relationship == "Enemy" -> "You dislike them openly and speak harshly."
            relationship == "Unforgivable" -> "You despise them and speak with bitter contempt."
            else -> ""
        }

        /**
         * Which of a leader's authored lines make good voice examples. Long, distinctive lines are left out on purpose:
         * small models copy those into reply after reply (seen with the "fair and just relationship" introduction).
         * Short greetings and barbs show the voice without becoming a template.
         */
        fun pickVoiceSamples(lines: List<String>, maxLength: Int = 45): List<String> =
            lines.map { it.trim() }.filter { it.isNotEmpty() && it.length <= maxLength && '[' !in it }.distinct()

        /**
         * What is going on for the leader right now. Always says whether it is at war, because a model told only
         * that the leader "goes to war readily" will otherwise talk about wars that are not happening.
         */
        fun describeSituation(atWarWithPlayer: Boolean, otherEnemies: List<String>): List<String> = buildList {
            if (atWarWithPlayer) add("You are at war with the player.")
            if (otherEnemies.isNotEmpty()) add("You are at war with ${otherEnemies.joinToString(" and ")}.")
            if (!atWarWithPlayer && otherEnemies.isEmpty()) add("You are not at war with anyone you know of.")
        }

        /**
         * Nations' intro texts are written to address the player as the nation's ruler ("... is at your command!",
         * "your people"). This turns one into first-person background notes for the leader.
         */
        /**
         * Rewrites Unciv's DiplomaticModifiers text (written as the AI speaking to the player, e.g.
         * "You refused to stop settling cities near us" or "We appreciate your gifts") into clear
         * prompt-consistent second-person facts where "you" is the AI and "the player" is the counterparty.
         * This prevents 1B-2B models from inverting who settled near whom or parroting "We appreciate your gifts".
         */
        fun perspectiveReason(raw: String): String {
            val trimmed = raw.trim()
            return when {
                trimmed.equals("We appreciate your gifts", ignoreCase = true) ->
                    "The player has given you generous gifts in the past."
                trimmed.equals("Years of peace have strengthened our relations.", ignoreCase = true) ->
                    "Years of peace have strengthened relations between your two nations."
                trimmed.equals("Our mutual military struggle brings us closer together.", ignoreCase = true) ->
                    "Fighting a shared enemy brings your two nations closer together."
                trimmed.equals("Our open borders have brought us closer together.", ignoreCase = true) ->
                    "Open borders have brought your two nations closer together."
                trimmed.equals("We believe in the same religion", ignoreCase = true) ->
                    "Both of your nations share the same religion."
                else -> trimmed
                    .replace(Regex("""\bYou\b"""), "The player")
                    .replace(Regex("""\byou\b"""), "the player")
                    .replace(Regex("""\bYour\b"""), "The player's")
                    .replace(Regex("""\byour\b"""), "the player's")
                    .replace(Regex("""\bOur\b"""), "Your")
                    .replace(Regex("""\bour\b"""), "your")
                    .replace(Regex("""\bus\b"""), "you")
                    .replace(Regex("""\bWe\b"""), "You")
                    .replace(Regex("""\bwe\b"""), "you")
            }
        }

        fun backgroundFromIntro(intro: String, maxLength: Int = 450): String {
            val sentences = intro.trim().split(Regex("(?<=[.!?])\\s+")).filterNot { it.contains("your command", ignoreCase = true) }
            var text = sentences.joinToString(" ").replace(Regex("\\bYour\\b"), "Our").replace(Regex("\\byour\\b"), "our").trim()
            if (text.length > maxLength) {
                val window = text.substring(0, maxLength)
                val end = window.indexOfLast { it == '.' || it == '!' || it == '?' }
                text = if (end >= maxLength / 3) window.substring(0, end + 1) else window.trimEnd() + "\u2026"
            }
            return text
        }
    }
}

object ChatPromptBuilder {
    /**
     * The system prompt for one reply. Kept short and concrete because it is meant for small models.
     * Tone comes from the engine (relationship, war state, personality, the leader's own authored lines), never
     * from a fixed attitude, so kind leaders can be kind and harsh ones harsh. [facts] are engine-verified (see
     * [NegotiationFacts]). The rules are only a first line of defence: [CommitmentGuard] checks the reply afterwards.
     */
    fun systemPrompt(
        profile: LeaderProfile,
        facts: NegotiationFacts,
        playerText: String = "",
        recentPlayerTexts: List<String> = emptyList(),
        toneNote: String = "",
        proposalDirective: String = ""
    ): String = buildString {
        val playerIdentity = when {
            profile.playerLeaderName.isNotBlank() && profile.playerNationName.isNotBlank() ->
                "${profile.playerLeaderName}, ruler of ${profile.playerNationName}"
            profile.sheet?.player != null ->
                "${profile.sheet.player.leaderName}, ruler of ${profile.sheet.player.nationName}"
            else -> ""
        }
        if (playerIdentity.isNotBlank()) {
            appendLine("You are ${profile.leaderName}, ruler of ${profile.nationName}, in a strategy game. You are talking with the player ($playerIdentity), the ruler of another nation.")
        } else {
            appendLine("You are ${profile.leaderName}, ruler of ${profile.nationName}, in a strategy game. You are talking with the player, the ruler of another nation.")
        }
        val character = profile.temperament.ifBlank { if (profile.traits.isEmpty()) "an ordinary ruler" else profile.traits.joinToString(", ") }
        appendLine("Your character: $character.")
        appendLine("How you feel about the player: ${profile.relationship}. ${LeaderProfile.describeDemeanor(profile.relationship, profile.atWar)}".trimEnd())
        if (profile.reasons.isNotEmpty()) {
            appendLine("Why you feel this way (true; in these lines 'You' means the player and 'us/our' means your own nation — let it colour your reply):")
            profile.reasons.forEach { appendLine("- $it") }
        }
        if (profile.giftCredit > 0) appendLine("The player has been generous to you with gifts, and you remember it.")
        if (profile.situation.isNotEmpty()) {
            appendLine("Your situation:")
            profile.situation.forEach { appendLine("- $it") }
        }
        appendLine("Your nation's military is ${profile.strength}.")
        profile.sheet?.let { sheet ->
            val lines = SheetRenderer.render(sheet, playerText, recentPlayerTexts)
            if (lines.isNotEmpty()) {
                appendLine("Facts about your nation right now. They are true and current. For anything about your nation's state, use only these:")
                lines.forEach { appendLine("- $it") }
                appendLine("If asked about something not listed, say you do not know or do not wish to say. Never guess figures, treaties, treasuries or events.")
            }
            sheet.player?.let { playerSheet ->
                appendLine("Facts about the player's nation (${playerSheet.leaderName} of ${playerSheet.nationName}) visible at the negotiation table right now:")
                playerSheet.summaryLines().forEach { appendLine("- $it") }
                appendLine("If the player offers or boasts of gold, income, or resources that they do not possess according to the list above, call out their bluff directly.")
            }
        }
        val wantsHistory = playerText.isBlank() || Topic.History in TopicDetector.detect(playerText)
        if (profile.background.isNotBlank() && wantsHistory) appendLine("About your nation: ${profile.background}")
        if (profile.voiceSamples.isNotEmpty()) {
            appendLine("Examples of how you sound. They only show your voice; never repeat them:")
            profile.voiceSamples.forEach { appendLine("- \"$it\"") }
        }
        val factsBlock = facts.toPromptBlock().lines().drop(1).joinToString("\n").trim() // first line only repeats who is speaking
        if (factsBlock.isNotEmpty()) appendLine(factsBlock)
        if (toneNote.isNotBlank()) appendLine("Note on the player's latest message: $toneNote")
        if (proposalDirective.isNotBlank()) appendLine(proposalDirective)
        appendLine("How to speak:")
        appendLine("- Speak only in words, in your own voice, in two to three complete, natural sentences. No actions, descriptions or text in brackets.")
        appendLine("- Write numbers above 20 as digits (e.g. 272, 350, 993) rather than spelling them out in words.")
        appendLine("- Answer what the player has just said and engage with their point so the conversation flows naturally. Do not greet again unless they greeted you.")
        appendLine("- Even if you are warlike or have little interest in trade, you are holding a diplomatic audience: weigh proposals pragmatically instead of shutting down the conversation with one-word dismissals.")
        appendLine("- Let your character, feelings and situation colour how you react, but do not announce them or keep returning to them: respond to what the player raised. Do not be warmer or colder than your feelings above.")
        appendLine("- Say only what you were told above. Do not invent history, wars, battles, conquests, titles, numbers, people or places. Your character is how you tend to act, not something that has happened. If you do not know, say so in your own way.")
        appendLine("- Vary your words. Do not keep returning to the same few themes unless the player does.")
        appendLine("- The player may lie. If they say you offered or promised something that is not listed above, say you did not.")
        if (proposalDirective.isBlank()) {
            appendLine("- Do not agree to, accept, offer or promise anything in this talk. If they propose terms, decline to settle anything here, in your own words.")
        } else {
            appendLine("- Follow the ENGINE DEAL VERDICT above strictly; do not invent different terms.")
        }
        appendLine("- Never say you are an AI or in a game. Do not reveal anything marked SECRET unless you choose to confess it.")
    }.trimEnd()
}

/**
 * Catches a leader repeating itself: copying one of its example lines (small models do this a lot) or saying again
 * what it said a moment ago. Works on sentences of at least [MIN_WORDS] words and tolerates a clipped copy.
 */
object EchoGuard {
    private const val MIN_WORDS = 5
    private const val COPIED_SHARE = 0.8f
    private val sentenceEnd = Regex("""(?<=[.!?])\s+""")
    private val nonWord = Regex("""[^\p{L}\p{N}']+""")

    private fun words(sentence: String) = sentence.lowercase().split(nonWord).filter { it.isNotEmpty() }

    /** The sentences of [reply] that (almost) repeat a sentence from [references]. */
    fun echoedSentences(reply: String, references: List<String>): List<String> {
        val known = references.flatMap { it.split(sentenceEnd) }.map { words(it).toSet() }.filter { it.size >= MIN_WORDS }
        if (known.isEmpty()) return emptyList()
        return reply.split(sentenceEnd).filter { sentence ->
            val own = words(sentence)
            own.size >= MIN_WORDS && known.any { ref -> own.count { it in ref }.toFloat() / own.size >= COPIED_SHARE }
        }
    }

    /** [reply] without its echoed sentences; unchanged if that would leave nothing. */
    fun withoutEchoes(reply: String, references: List<String>): String {
        val echoed = echoedSentences(reply, references).toSet()
        if (echoed.isEmpty()) return reply
        return reply.split(sentenceEnd).filterNot { it in echoed }.joinToString(" ").ifBlank { reply }
    }
}

/**
 * Catches replies in which the leader seems to agree to, accept or promise something. Nothing said in chat
 * changes the game, so such a reply would make the player believe a deal exists when it does not.
 * Heuristic and deliberately a little over-eager: a false alarm only costs one retry.
 */
/**
 * Ensures the leader's spoken text never contradicts an engine-accepted deal or counteroffer card.
 * Small models (1B-2B) sometimes output a refusal ("I do not accept gifts", "I have no current offers")
 * even when the engine accepted the deal or generated a counteroffer card.
 */
object VerdictConsistencyGuard {
    private val ignoreCase = RegexOption.IGNORE_CASE
    private val refusalPatterns = listOf(
        Regex("""\b(?:i|we)\s+(?:do\s+not|don't|cannot|can't|will\s+not|won't|must\s+not)\s+(?:[a-z]+\s+){0,3}(?:accept|agree|entertain|consider|consent|make\s+any|wish\s+to)\b""", ignoreCase),
        Regex("""\bnot\s+(?:something|a\s+proposal|an\s+offer|an\s+arrangement|terms)\b.*?\b(?:accept|agree|entertain|consider)\b""", ignoreCase),
        Regex("""\bnot\s+(?:prepared|willing|inclined|able|ready|disposed|about)\s+to\s+(?:accept|agree|entertain|consider|make|part\s+with)\b""", ignoreCase),
        Regex("""\b(?:refuse|decline|reject|unacceptable|out\s+of\s+the\s+question)\b""", ignoreCase),
        Regex("""\b(?:not\s+interested|have\s+no\s+interest|have\s+no\s+(?:current\s+)?(?:offers?|desire|intention))\b""", ignoreCase),
        Regex("""\bno\s+specific\s+city\s+in\s+mind\b""", ignoreCase)
    )

    fun contradictsAcceptance(reply: String): Boolean =
        refusalPatterns.any { it.containsMatchIn(reply) }
}

object CommitmentGuard {
    private val negation = Regex("""\b(?:not|never|cannot|no|nothing|none|neither|nor|refuse|decline|won't|can't|don't|shan't)\b|n't\b""", RegexOption.IGNORE_CASE)
    private val sentenceEnd = Regex("""(?<=[.!?])\s+""")
    private val patterns = listOf(
        Regex("""\b(?:i|we)\s+(?:hereby\s+|gladly\s+|happily\s+|readily\s+|do\s+)?(?:accept|agree|consent)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:pleased|happy|glad|willing|delighted)\s+to\s+(?:accept|agree|consent)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:it'?s|that'?s|this is|we have)\s+a\s+deal\b""", RegexOption.IGNORE_CASE),
        Regex("""^\W*agreed\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:i|we)\s+(?:hereby\s+)?(?:promise|swear|vow|pledge|guarantee)\b""", RegexOption.IGNORE_CASE),
        Regex("""\byou have (?:my|our) (?:word|pledge|promise|oath|blessing)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:i|we)(?:\s+|'ll\s*)(?:will\s+|shall\s+)?(?:gladly\s+|happily\s+|surely\s+)?(?:give|send|pay|grant|provide|lend|cede|transfer|deliver)\b""", RegexOption.IGNORE_CASE),
        Regex("""\bour\s+(?:alliance|pact|treaty|agreement|friendship)\s+is\b""", RegexOption.IGNORE_CASE),
        Regex("""\bthank you for (?:your|the)\s+(?:\w+\s+){0,2}(?:gift|generosity|gold|offer|payment)\b""", RegexOption.IGNORE_CASE)
    )

    fun makesCommitment(reply: String): Boolean = reply.split(sentenceEnd).any { sentence ->
        !negation.containsMatchIn(sentence) && patterns.any { it.containsMatchIn(sentence) }
    }
}

object ReplyCleaner {
    private val stageDirections = Regex("""\*[^*]*\*""")
    private val bracketed = Regex("""\([^)]*\)|\[[^\]]*]""") // "(eyes narrowing slightly)", "[sighs]"
    private val quoteMarks = Regex("""["\u201C\u201D\u201E]""")
    // Some models (e.g. Qwen3, Gemma 4) may emit their reasoning in <think> tags: closed blocks, or one cut off by the token limit.
    private val thinking = Regex("""<think>.*?(?:</think>|$)""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private const val MAX_LENGTH = 400

    /** Tidies a raw model reply into plain spoken words; returns null if nothing usable is left. */
    fun clean(raw: String, leaderName: String): String? {
        var text = raw.replace(thinking, " ").replace(stageDirections, " ").replace(bracketed, " ").trim()
        // "Caesar: Hello" / "Assistant: Hello" / "Leader: Hello"
        val prefix = Regex("""^(?:${Regex.escape(leaderName)}|assistant|leader|ai)\s*:\s*""", RegexOption.IGNORE_CASE)
        text = text.replace(prefix, "")
        text = text.replace(quoteMarks, "")
        text = text.replace(Regex("""\s+"""), " ").trim()
        if (text.length > MAX_LENGTH) text = shorten(text)
        return text.ifBlank { null }
    }

    /** Cuts at the last sentence end that fits, or hard-cuts with an ellipsis if there is none. */
    private fun shorten(text: String): String {
        val window = text.substring(0, MAX_LENGTH)
        val end = window.indexOfLast { it == '.' || it == '!' || it == '?' }
        return if (end >= MAX_LENGTH / 4) window.substring(0, end + 1) else window.trimEnd() + "\u2026"
    }
}

/**
 * One conversation between the player and an AI leader. Thread-safe, because the model answers on a
 * background thread while the UI reads the transcript.
 * [reasoningEffort] is asked for on every call, so it can be changed while the game runs.
 */
class ChatSession(
    private val backend: LlmBackend,
    private val maxHistory: Int = 8,
    private val reasoningEffort: () -> String? = { null }
) {
    data class Message(val fromPlayer: Boolean, val text: String)

    sealed interface Reply {
        data class Said(val text: String) : Reply
        data class Failed(val reason: String) : Reply
    }

    /** What happened inside the latest [reply], for diagnostics: the model's own first attempt, before any correction. */
    data class Debug(
        val firstRaw: String?,
        val firstFlagged: Boolean,
        val retried: Boolean,
        val firstEchoed: Boolean = false,
        /** Why the first reply was thought to contradict the nation sheet, if it was. */
        val firstClaims: List<String> = emptyList()
    )

    private val lock = Any()
    private val transcript = ArrayList<Message>()

    @Volatile
    var lastDebug: Debug? = null
        private set

    /** A copy of the conversation so far, oldest first. */
    val messages: List<Message> get() = synchronized(lock) { transcript.toList() }

    /** Records what the player said. Call on the UI thread, then [reply] on a background thread. */
    fun say(playerText: String) {
        synchronized(lock) { transcript += Message(true, playerText) }
    }

    /** Appends a proactive leader message (e.g. a border ultimatum or AI-initiated proposal) to the transcript. */
    fun addLeaderInitiative(leaderText: String) {
        synchronized(lock) { transcript += Message(false, leaderText) }
    }

    /** Restores saved messages into this session. */
    fun restoreMessages(saved: List<Message>) {
        synchronized(lock) {
            transcript.clear()
            transcript.addAll(saved)
        }
    }

    /** Asks the model to answer the player's latest message, and records the answer. Blocks. */
    fun reply(
        profile: LeaderProfile,
        facts: NegotiationFacts,
        toneNote: String = "",
        proposalDirective: String = "",
        proposalAccepted: Boolean = false
    ): Reply {
        val snapshot = messages
        val last = snapshot.lastOrNull()
        if (last == null || !last.fromPlayer) return Reply.Failed("There is nothing to answer")

        val history = snapshot.dropLast(1).takeLast(maxHistory)
            .map { LlmMessage(if (it.fromPlayer) "user" else "assistant", it.text) }
        val recentPlayerTexts = snapshot.dropLast(1).filter { it.fromPlayer }.takeLast(1).map { it.text }
        val request = LlmRequest(
            system = ChatPromptBuilder.systemPrompt(profile, facts, last.text, recentPlayerTexts, toneNote, proposalDirective),
            user = last.text,
            maxTokens = REPLY_TOKEN_BUDGET,
            temperature = 0.8f,
            history = history,
            reasoningEffort = reasoningEffort()
        )

        // Lines the leader must not simply repeat: its own example lines and what it said a moment ago.
        val references = profile.voiceSamples + snapshot.filter { !it.fromPlayer }.takeLast(3).map { it.text }
        // Everything the leader was legitimately told or the player said: numbers there are not "invented".
        val allowedText = buildList {
            add(facts.toPromptBlock())
            snapshot.forEach { add(it.text) }
            add(profile.background)
            addAll(profile.reasons)
            addAll(profile.situation)
            addAll(profile.voiceSamples)
        }
        val checks = Checks(references, profile.sheet, allowedText, allowAgreement = proposalAccepted)

        try {
            val first = generate(request, profile)
            val problem = checks.of(first)
            var text: String? = first
            var retried = false
            if (problem != Problem.None) {
                // Nothing usable, a fake agreement, a repeat, or a claim about its nation that is not true. Ask once more.
                retried = true
                val retryRequest = when (problem) {
                    Problem.Agreement -> request.copy(system = request.system + "\n" + FIRM_REMINDER, temperature = 0.5f)
                    Problem.ContradictsAcceptance -> request.copy(system = request.system + "\n" + ACCEPTANCE_REMINDER, temperature = 0.4f)
                    Problem.Echo -> request.copy(system = request.system + "\n" + ECHO_REMINDER, temperature = 0.9f)
                    is Problem.Claims -> request.copy(system = request.system + "\n" + claimReminder(problem.violations), temperature = 0.5f)
                    else -> request
                }
                val second = generate(retryRequest, profile)
                // If the retry said nothing, a first reply that only needs repairing (not a fake agreement) is repaired instead.
                val candidate = second ?: if (problem is Problem.Echo || problem is Problem.Claims) first else null
                text = settle(candidate, problem, checks, snapshot.size)
            }
            lastDebug = Debug(
                first, problem == Problem.Agreement, retried, firstEchoed = problem == Problem.Echo,
                firstClaims = (problem as? Problem.Claims)?.violations?.map { it.reason }.orEmpty()
            )
            val said = text ?: return Reply.Failed("The leader said nothing usable")
            synchronized(lock) { transcript += Message(false, said) }
            return Reply.Said(said)
        } catch (e: LlmException) {
            return Reply.Failed(e.message ?: "The language model failed")
        } catch (e: RuntimeException) {
            return Reply.Failed("The language model failed: ${e.message}")
        }
    }

    private sealed interface Problem {
        data object None : Problem
        data object Empty : Problem
        data object Agreement : Problem
        data object ContradictsAcceptance : Problem
        data object Echo : Problem
        data class Claims(val violations: List<ClaimGuard.Violation>) : Problem
    }

    private class Checks(
        val references: List<String>,
        private val sheet: NationSheet?,
        private val allowedText: List<String>,
        val allowAgreement: Boolean = false
    ) {
        fun claims(text: String): List<ClaimGuard.Violation> = sheet?.let { ClaimGuard.violations(text, it, allowedText) }.orEmpty()
        fun echoes(text: String): Boolean = EchoGuard.echoedSentences(text, references).isNotEmpty()

        fun of(text: String?): Problem = when {
            text == null -> Problem.Empty
            !allowAgreement && CommitmentGuard.makesCommitment(text) -> Problem.Agreement
            allowAgreement && VerdictConsistencyGuard.contradictsAcceptance(text) -> Problem.ContradictsAcceptance
            echoes(text) -> Problem.Echo
            else -> claims(text).takeIf { it.isNotEmpty() }?.let { Problem.Claims(it) } ?: Problem.None
        }
    }

    /**
     * The reply to show once the one retry is done: the candidate if it is clean, otherwise repaired by cutting out
     * what is wrong, and a neutral line if nothing is left. Null means there was nothing usable at all.
     */
    private fun settle(candidate: String?, firstProblem: Problem, checks: Checks, salt: Int): String? {
        if (candidate == null) {
            return when (firstProblem) {
                Problem.Agreement -> FALLBACKS[salt % FALLBACKS.size]
                Problem.ContradictsAcceptance -> ACCEPTED_FALLBACKS[salt % ACCEPTED_FALLBACKS.size]
                else -> null
            }
        }
        if (!checks.allowAgreement && CommitmentGuard.makesCommitment(candidate)) return FALLBACKS[salt % FALLBACKS.size]
        if (checks.allowAgreement && VerdictConsistencyGuard.contradictsAcceptance(candidate)) return ACCEPTED_FALLBACKS[salt % ACCEPTED_FALLBACKS.size]
        var text = candidate
        if (checks.echoes(text)) {
            text = EchoGuard.withoutEchoes(text, checks.references)
            if (checks.echoes(text)) return PLAIN_REPLIES[salt % PLAIN_REPLIES.size] // all that was left was a copy
        }
        val untrue = checks.claims(text)
        if (untrue.isNotEmpty()) {
            text = ClaimGuard.withoutViolations(text, untrue)
            if (text.isBlank()) return PLAIN_REPLIES[salt % PLAIN_REPLIES.size]
        }
        return text
    }

    private fun generate(request: LlmRequest, profile: LeaderProfile): String? =
        ReplyCleaner.clean(backend.generate(request).text, profile.leaderName)

    /** [say] followed by [reply]; convenient for tests. */
    fun send(playerText: String, profile: LeaderProfile, facts: NegotiationFacts): Reply {
        say(playerText)
        return reply(profile, facts)
    }

    private companion object {
        /**
         * Generous on purpose: models that think before answering spend tokens on that first, and an exhausted
         * budget means an empty reply. Models that do not think stop by themselves after a sentence or two.
         */
        const val REPLY_TOKEN_BUDGET = 1024

        const val FIRM_REMINDER = "Your previous reply wrongly agreed to, accepted or promised something, or thanked them for something. " +
            "Reply again: accept nothing, agree to nothing, promise nothing. Answer their words in keeping with your character."

        const val ACCEPTANCE_REMINDER = "Your previous reply wrongly refused the offer, even though the game engine ACCEPTED it (or put forward a counteroffer card). " +
            "Reply again: state in character that these terms are acceptable and invite the player to confirm the trade card."

        val ACCEPTED_FALLBACKS = listOf(
            "Very well. Those terms are acceptable to me — confirm the agreement when you are ready.",
            "We have an accord on these terms. Confirm the deal card so we may proceed.",
            "I find this arrangement acceptable. Confirm the terms before us."
        )

        fun claimReminder(violations: List<ClaimGuard.Violation>) =
            "Your previous reply said things about your nation that contradict or go beyond your facts: " +
                violations.joinToString("; ") { "\"${it.sentence}\" (${it.reason})" } +
                ". Reply again. About your nation's state (wealth, wars, allies, cities, figures) say only what your facts state; if unsure, do not mention it."

        const val ECHO_REMINDER = "Your previous reply repeated your example lines or your own earlier words. " +
            "Reply again in new words, answering exactly what the player just said."

        /** Neutral lines for when a model can only produce copies of its own words: better silence than a parrot. */
        val PLAIN_REPLIES = listOf("Speak plainly.", "Go on.", "I am listening.")

        /** In character enough, and safe: used only when the model twice fails to stay within its limits. */
        val FALLBACKS = listOf(
            "Words are cheap. If you have terms, bring them to the trade table.",
            "I make no promises in conversation. Put your proposal to me at the trade table and I will judge it.",
            "Talk is pleasant, but agreements are made at the trade table, not here."
        )
    }
}
