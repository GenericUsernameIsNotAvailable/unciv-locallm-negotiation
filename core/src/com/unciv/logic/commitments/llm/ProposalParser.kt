package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.CivName
import com.unciv.logic.commitments.Commitment
import com.unciv.logic.commitments.CommitmentDraft
import com.unciv.logic.commitments.CommitmentValidator
import com.unciv.logic.commitments.Condition
import com.unciv.logic.commitments.DealBuilders
import com.unciv.logic.commitments.DealDraft
import com.unciv.logic.commitments.DealEvaluator
import com.unciv.logic.commitments.DealVerdict
import com.unciv.logic.commitments.Term
import com.unciv.logic.commitments.ValidationError
import kotlin.math.ceil

sealed interface ProposalAssessment {
    val draft: DealDraft
    fun toPromptDirective(): String

    data class Accepted(override val draft: DealDraft, val verdict: DealVerdict) : ProposalAssessment {
        override fun toPromptDirective(): String =
            "ENGINE DEAL VERDICT: The player proposed a binding deal: ${describeDraft(draft)}. " +
                "You HAVE DECIDED TO ACCEPT these terms. Say in character that these terms are acceptable and invite them to confirm the deal."
    }

    /**
     * The player's original proposal was short on value, but if the player pays [goldNeeded] lump-sum gold
     * ([counterDraft]), the AI will accept.
     */
    data class Counteroffered(
        override val draft: DealDraft,
        val counterDraft: DealDraft,
        val goldNeeded: Int,
        val verdict: DealVerdict
    ) : ProposalAssessment {
        override fun toPromptDirective(): String =
            "ENGINE DEAL VERDICT: The player proposed: ${describeDraft(draft)}. " +
                "You will NOT do that for their current offer, but you offer a COUNTEROFFER: you will agree if they pay you $goldNeeded gold (${describeDraft(counterDraft)}). " +
                "State in character that you require $goldNeeded gold for this commitment and invite them to confirm your counteroffer card."
    }

    data class Declined(override val draft: DealDraft, val verdict: DealVerdict) : ProposalAssessment {
        override fun toPromptDirective(): String {
            val shortfall = ceil(verdict.required - verdict.surplus).toInt().coerceAtLeast(10)
            return "ENGINE DEAL VERDICT: The player proposed: ${describeDraft(draft)}. " +
                "You REFUSE this deal because the value to you is insufficient (short by roughly $shortfall gold-equivalent). " +
                "Refuse in character and explain that they must offer more value (or ask less of you) before you will agree."
        }
    }

    data class Invalid(override val draft: DealDraft, val errors: List<ValidationError>) : ProposalAssessment {
        override fun toPromptDirective(): String =
            "ENGINE DEAL VERDICT: The player proposed: ${describeDraft(draft)}. " +
                "This deal CANNOT be made because: ${errors.joinToString("; ") { it.message }}. " +
                "Explain in character why this proposal is impossible right now."
    }

    companion object {
        fun describeDraft(draft: DealDraft): String =
            draft.items.joinToString(" AND ") { it.describe() }
    }
}

/**
 * Extracts structured [DealDraft] proposals from natural language so players can negotiate commitments
 * directly in chat: loans, non-aggression pacts, no-settling promises, proxy-war subsidies, conditional wars,
 * trade embargoes, construction nudges, and gold-for-promise exchanges.
 */
object ProposalParser {
    private const val DEFAULT_TURNS = 20
    private val ignoreCase = RegexOption.IGNORE_CASE

    private val turnsRegex = Regex("""(\d+)\s+turns?""", ignoreCase)
    private val ratioRegex = Regex("""(\d+(?:\.\d+)?)\s*(?:x|times)""", ignoreCase)
    private val loanAskRegex = Regex(
        """\b(?:lend|loan|spot|front|advance)\s+me\s+(\d[\d,]*)\s*gold.*?\b(\d[\d,]*)\s*(?:gold\s*)?(?:per\s+turn|gpt|a\s+turn)""",
        ignoreCase
    )
    private val loanOfferRegex = Regex(
        """\bi(?:'ll|\s+will)?\s+(?:lend|loan|spot|front|advance)\s+you\s+(\d[\d,]*)\s*gold.*?\b(\d[\d,]*)\s*(?:gold\s*)?(?:per\s+turn|gpt|a\s+turn)""",
        ignoreCase
    )
    private val noSettleRegex = Regex(
        """\b(?:(?:do\s+not|don't|stop|no|never|refrain\s+from)\s+(?:settle|settling|founding\s+cities|expanding)\s+(?:near|close\s+to|by)|keep\s+your\s+settlers\s+(?:off|away\s+from))\b""",
        ignoreCase
    )
    private val mutualNoAttackRegex = Regex(
        """\b(?:non-aggression|neither\s+of\s+us\s+attack|not\s+attack\s+each\s+other|peace\s+pact)\b""",
        ignoreCase
    )
    private val noAttackRegex = Regex(
        """\b(?:do\s+not|don't|promise\s+not\s+to|never)\s+attack\s+(?:me|us)\b""",
        ignoreCase
    )
    private val embargoRegex = Regex(
        """\b(?:embargo|stop\s+trading\s+(?:luxury\s+goods\s+|resources\s+)?with|do\s+not\s+trade\s+with|don't\s+trade\s+with|cut\s+off\s+trade\s+with)\b""",
        ignoreCase
    )
    private val dmzRegex = Regex(
        """\b(?:demilitari[sz]ed\s+zone|dmz|(?:pull|withdraw|keep|move|draw)\s+(?:your\s+|our\s+)?(?:troops|army|armies|forces|units|cavalry|soldiers)\s+(?:back|away)\s+from\s+(?:my|our|the)\s+borders?)\b""",
        ignoreCase
    )
    private val mutualDmzRegex = Regex(
        """\b(?:mutual\s+(?:dmz|demilitari[sz]ed\s+zone)|establish\s+a\s+(?:dmz|demilitari[sz]ed\s+zone)|and\s+i\s+will\s+(?:pull|withdraw|draw|keep)\s+(?:my|mine|our))\b""",
        ignoreCase
    )
    private val protectCityStateRegex = Regex(
        """\b(?:do\s+not\s+(?:bully|attack|tribute|harm)|don't\s+(?:bully|attack|tribute|harm)|stop\s+(?:bullying|attacking)|leave\s+[A-Za-z][\w'-]*\s+alone|hands\s+off|protect\s+the\s+city[-\s]?state)\b""",
        ignoreCase
    )
    private val voteForMeRegex = Regex(
        """\b(?:vote\s+for\s+(?:me|us)|(?:support|back)\s+(?:me|us)\s+in\s+the\s+(?:(?:diplomatic|world|united\s+nations)\s+)?vote|cast\s+your\s+vote\s+for\s+(?:me|us))\b""",
        ignoreCase
    )
    private val stayAtWarRegex = Regex(
        """\b(?:stay\s+at\s+war\s+with|keep\s+fighting|no\s+peace\s+with)\b""",
        ignoreCase
    )
    private val declareWarRegex = Regex(
        """\b(?:declare\s+war\s+on|go\s+to\s+war\s+with|make\s+war\s+on|wage\s+war\s+on|attack|invade|join\s+my\s+war\s+against)\b""",
        ignoreCase
    )
    private val buildRegex = Regex(
        """\b(?:build|construct|prioritise|prioritize|focus\s+on\s+building)\s+(?:a\s+|an\s+|the\s+)?([A-Za-z][A-Za-z ]{2,24})""",
        ignoreCase
    )
    private val playerGivesGoldLump = Regex(
        """\b(?:i(?:'ll|\s+will)?\s+(?:give|pay|offer|send)\s+(?:you\s+)?(\d[\d,]*)\s*gold(?!\s*(?:per\s+turn|gpt|a\s+turn))|for\s+(\d[\d,]*)\s*gold(?!\s*(?:per\s+turn|gpt|a\s+turn)))""",
        ignoreCase
    )
    private val playerGivesGpt = Regex(
        """\b(?:i(?:'ll|\s+will)?\s+(?:give|pay|offer)\s+(?:you\s+)?(\d[\d,]*)\s*(?:gold\s*)?(?:per\s+turn|gpt|a\s+turn)|for\s+(\d[\d,]*)\s*(?:gold\s*)?(?:per\s+turn|gpt|a\s+turn))""",
        ignoreCase
    )

    fun parse(
        text: String,
        human: CivName,
        ai: CivName,
        knownThirdCivs: List<CivName>,
        knownConstructions: List<String>,
        now: Int
    ): DealDraft? {
        val normalized = NumberWords.normalize(text)
        val turns = turnsRegex.find(normalized)?.groupValues?.get(1)?.toIntOrNull() ?: DEFAULT_TURNS

        parseLoan(normalized, human, ai, turns, now)?.let { return it }

        val targetCiv = SemanticNegotiationParser.fuzzyFindName(normalized, knownThirdCivs)
        val lumpOffered = extractAmount(playerGivesGoldLump, normalized)
        val gptOffered = extractAmount(playerGivesGpt, normalized)

        if (stayAtWarRegex.containsMatchIn(normalized) && targetCiv != null && gptOffered != null) {
            return DealBuilders.proxyWarSubsidy(payer = human, fighter = ai, target = targetCiv, goldPerTurn = gptOffered, turns = turns, now = now)
        }

        val items = collectCoreTerms(normalized, human, ai, targetCiv, knownConstructions, turns, now)
        if (items.isEmpty()) return null

        if (lumpOffered != null && lumpOffered > 0) {
            items += CommitmentDraft(human, ai, Term.PayGoldLump(lumpOffered), activeFrom = now, endTurn = now)
        }
        if (gptOffered != null && gptOffered > 0) {
            items += CommitmentDraft(
                human, ai, Term.PayGoldPerTurn(gptOffered),
                condition = Condition.SiblingHonored(0), activeFrom = now + 1, endTurn = now + turns
            )
        }
        return DealDraft(proposer = human, counterparty = ai, items = items)
    }

    private fun parseLoan(text: String, human: CivName, ai: CivName, turns: Int, now: Int): DealDraft? {
        loanAskRegex.find(text)?.let { m ->
            return DealBuilders.loan(lender = ai, borrower = human, principal = num(m.groupValues[1]), repayPerTurn = num(m.groupValues[2]), turns = turns, now = now)
        }
        loanOfferRegex.find(text)?.let { m ->
            return DealBuilders.loan(lender = human, borrower = ai, principal = num(m.groupValues[1]), repayPerTurn = num(m.groupValues[2]), turns = turns, now = now)
        }
        return null
    }

    private fun collectCoreTerms(
        text: String,
        human: CivName,
        ai: CivName,
        targetCiv: CivName?,
        knownConstructions: List<String>,
        turns: Int,
        now: Int
    ): ArrayList<CommitmentDraft> {
        val items = ArrayList<CommitmentDraft>()
        if (mutualNoAttackRegex.containsMatchIn(text)) {
            items += CommitmentDraft(ai, human, Term.NoAttack, activeFrom = now, endTurn = now + turns)
            items += CommitmentDraft(human, ai, Term.NoAttack, activeFrom = now, endTurn = now + turns)
        } else if (noAttackRegex.containsMatchIn(text)) {
            items += CommitmentDraft(ai, human, Term.NoAttack, activeFrom = now, endTurn = now + turns)
        }
        if (noSettleRegex.containsMatchIn(text)) {
            items += CommitmentDraft(ai, human, Term.NoSettleNear, activeFrom = now, endTurn = now + turns)
        }
        if (embargoRegex.containsMatchIn(text) && targetCiv != null) {
            items += CommitmentDraft(ai, human, Term.Embargo(targetCiv), activeFrom = now, endTurn = now + turns)
        }
        if (dmzRegex.containsMatchIn(text)) {
            items += CommitmentDraft(ai, human, Term.DemilitarizeBorder, activeFrom = now, endTurn = now + turns)
            if (mutualDmzRegex.containsMatchIn(text)) {
                items += CommitmentDraft(human, ai, Term.DemilitarizeBorder, activeFrom = now, endTurn = now + turns)
            }
        }
        if (protectCityStateRegex.containsMatchIn(text) && targetCiv != null) {
            items += CommitmentDraft(ai, human, Term.NoBullyOrAttackCityState(targetCiv), activeFrom = now, endTurn = now + turns)
        }
        if (voteForMeRegex.containsMatchIn(text)) {
            items += CommitmentDraft(ai, human, Term.VoteFor(human), activeFrom = now, endTurn = now + turns)
        }
        if (declareWarRegex.containsMatchIn(text) && targetCiv != null) {
            val ratio = ratioRegex.find(text)?.groupValues?.get(1)?.toFloatOrNull()
            val cond = ratio?.let { Condition.ForceRatioAtLeast(ai, targetCiv, it) }
            items += CommitmentDraft(ai, human, Term.DeclareWar(targetCiv), condition = cond, activeFrom = now + 1, endTurn = now + turns)
        }
        val matchedConstruction = findConstruction(text, knownConstructions)
        if (matchedConstruction != null && hasBuildIntent(text)) {
            items += CommitmentDraft(ai, human, Term.PrioritizeConstruction(matchedConstruction), activeFrom = now, endTurn = now + turns)
        }
        return items
    }

    private fun findConstruction(text: String, knownConstructions: List<String>): String? {
        val known = knownConstructions.firstOrNull { name ->
            Regex("""\b${Regex.escape(name)}\b""", ignoreCase).containsMatchIn(text)
        }
        if (known != null) return known
        val raw = buildRegex.find(text)?.groupValues?.get(1)?.trim() ?: return null
        return raw.split(Regex("""\s+(?:for|in|within|and|if)\b""", ignoreCase)).firstOrNull()?.trim()
    }

    private fun extractAmount(regex: Regex, text: String): Int? =
        regex.find(text)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.let { num(it) }

    fun evaluateProposal(
        draft: DealDraft,
        ai: CivName,
        toneScore: Float,
        validator: CommitmentValidator,
        existingCommitments: Collection<Commitment>,
        evaluator: DealEvaluator,
        playerTreasury: Int = 0,
        playerGoldPerTurn: Int = 0,
        now: Int = draft.items.minOfOrNull { it.activeFrom } ?: 0
    ): ProposalAssessment {
        val errors = validator.validateDeal(draft, existingCommitments)
        if (errors.isNotEmpty()) return ProposalAssessment.Invalid(draft, errors)
        val verdict = evaluator.evaluate(draft, ai, tone = toneScore)
        if (verdict.accepted) return ProposalAssessment.Accepted(draft, verdict)

        // Check if a lump-sum gold payment, per-turn gold (GPT), or a hybrid of both from the player can cover the shortfall
        val isLoan = draft.items.any { it.committer == ai && it.term is Term.PayGoldLump }
        val shortfall = ceil(verdict.required - verdict.surplus).toInt().coerceAtLeast(10)
        if (!isLoan && shortfall in 1..2000) {
            val existingLump = draft.items.firstOrNull { it.committer == draft.proposer && it.term is Term.PayGoldLump }
                ?.let { (it.term as Term.PayGoldLump).amount } ?: 0
            val totalGoldNeeded = ((existingLump + shortfall + 4) / 5) * 5 // round up to nearest 5 gold
            val nonPaymentItems = draft.items.filterNot {
                it.committer == draft.proposer && (it.term is Term.PayGoldLump || it.term is Term.PayGoldPerTurn)
            }
            val duration = (nonPaymentItems.maxOfOrNull { it.endTurn - now } ?: DEFAULT_TURNS).coerceIn(10, 30)

            if (playerTreasury >= totalGoldNeeded) {
                val counterItems = nonPaymentItems + CommitmentDraft(
                    draft.proposer, ai, Term.PayGoldLump(totalGoldNeeded), activeFrom = now, endTurn = now
                )
                val counterDraft = draft.copy(items = counterItems)
                if (validator.validateDeal(counterDraft, existingCommitments).isEmpty()) {
                    return ProposalAssessment.Counteroffered(draft, counterDraft, totalGoldNeeded, verdict)
                }
            } else if (playerGoldPerTurn > 0) {
                // Player does not have enough lump-sum gold, so build a hybrid Lump Gold + Gold Per Turn counteroffer
                val lumpPart = ((playerTreasury * 3 / 4) / 5) * 5
                val remainingValue = (totalGoldNeeded - lumpPart).coerceAtLeast(10)
                val gptNeeded = ceil(remainingValue.toDouble() / (duration * 0.7)).toInt().coerceAtLeast(1)
                if (gptNeeded <= playerGoldPerTurn) {
                    val counterItems = ArrayList(nonPaymentItems)
                    if (lumpPart > 0) {
                        counterItems += CommitmentDraft(draft.proposer, ai, Term.PayGoldLump(lumpPart), activeFrom = now, endTurn = now)
                    }
                    counterItems += CommitmentDraft(
                        draft.proposer, ai, Term.PayGoldPerTurn(gptNeeded),
                        condition = Condition.SiblingHonored(0), activeFrom = now + 1, endTurn = now + duration
                    )
                    val counterDraft = draft.copy(items = counterItems)
                    if (validator.validateDeal(counterDraft, existingCommitments).isEmpty()) {
                        return ProposalAssessment.Counteroffered(draft, counterDraft, totalGoldNeeded, verdict)
                    }
                }
            }
        }
        return ProposalAssessment.Declined(draft, verdict)
    }

    private fun hasBuildIntent(text: String): Boolean =
        Regex("""\b(?:build|construct|prioritise|prioritize)\b""", ignoreCase).containsMatchIn(text)

    private fun num(s: String): Int = s.replace(",", "").toIntOrNull() ?: 0
}
