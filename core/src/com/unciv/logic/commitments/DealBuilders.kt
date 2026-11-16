package com.unciv.logic.commitments

/**
 * Ready-made deal shapes. These are what the LLM front end (and the plain buttons) ask for;
 * each is just a composition of whitelisted [Term]s and [Condition]s.
 *
 * Timing convention: restraints are in force from [now] through now+turns; payments run on
 * the turns now+1..now+turns; lump sums change hands the moment the deal is confirmed.
 */
object DealBuilders {

    data class LoanQuote(val principal: Int, val totalRepaid: Int, val interestPercent: Float)

    fun loanQuote(principal: Int, repayPerTurn: Int, turns: Int): LoanQuote {
        val total = repayPerTurn * turns
        return LoanQuote(principal, total, (total - principal) * 100f / principal)
    }

    /**
     * [lender] hands over [principal] now; [borrower] repays [repayPerTurn] each turn for [turns] turns.
     * Repayments are owed only while the lump sum was really paid.
     */
    fun loan(lender: CivName, borrower: CivName, principal: Int, repayPerTurn: Int, turns: Int, now: Int) = DealDraft(
        proposer = borrower, counterparty = lender,
        items = listOf(
            CommitmentDraft(lender, borrower, Term.PayGoldLump(principal), activeFrom = now, endTurn = now),
            CommitmentDraft(
                borrower, lender, Term.PayGoldPerTurn(repayPerTurn),
                condition = Condition.SiblingHonored(0), activeFrom = now + 1, endTurn = now + turns
            )
        )
    )

    /**
     * [payer] pays [fighter] [goldPerTurn] per turn for as long as [fighter] stays at war with [target].
     * Each side's part depends on the other honouring theirs: if the payer stops paying, the fighter
     * is free to make peace without penalty, and vice versa.
     */
    fun proxyWarSubsidy(payer: CivName, fighter: CivName, target: CivName, goldPerTurn: Int, turns: Int, now: Int) = DealDraft(
        proposer = payer, counterparty = fighter,
        items = listOf(
            CommitmentDraft(
                fighter, payer, Term.NoPeaceWith(target),
                condition = Condition.SiblingHonored(1), activeFrom = now, endTurn = now + turns
            ),
            CommitmentDraft(
                payer, fighter, Term.PayGoldPerTurn(goldPerTurn),
                condition = Condition.SiblingHonored(0), activeFrom = now + 1, endTurn = now + turns
            )
        )
    )

    /** [committer] declares war on [target] at some point in [notBefore]..[byTurn], once it is strong enough (if a ratio is given). */
    fun conditionalWar(
        committer: CivName, partner: CivName, target: CivName, notBefore: Int, byTurn: Int, minForceRatio: Float? = null
    ) = DealDraft(
        proposer = partner, counterparty = committer,
        items = listOf(
            CommitmentDraft(
                committer, partner, Term.DeclareWar(target),
                condition = minForceRatio?.let { Condition.ForceRatioAtLeast(committer, target, it) },
                activeFrom = notBefore, endTurn = byTurn
            )
        )
    )

    fun productionNudge(committer: CivName, beneficiary: CivName, construction: String, turns: Int, now: Int, multiplier: Float = 3f) =
        DealDraft(
            proposer = beneficiary, counterparty = committer,
            items = listOf(
                CommitmentDraft(committer, beneficiary, Term.PrioritizeConstruction(construction, multiplier), activeFrom = now, endTurn = now + turns)
            )
        )

    fun noAttack(committer: CivName, beneficiary: CivName, turns: Int, now: Int) = DealDraft(
        proposer = beneficiary, counterparty = committer,
        items = listOf(CommitmentDraft(committer, beneficiary, Term.NoAttack, activeFrom = now, endTurn = now + turns))
    )

    fun noSettleNear(committer: CivName, beneficiary: CivName, turns: Int, now: Int) = DealDraft(
        proposer = beneficiary, counterparty = committer,
        items = listOf(CommitmentDraft(committer, beneficiary, Term.NoSettleNear, activeFrom = now, endTurn = now + turns))
    )

    fun embargo(committer: CivName, beneficiary: CivName, target: CivName, turns: Int, now: Int) = DealDraft(
        proposer = beneficiary, counterparty = committer,
        items = listOf(CommitmentDraft(committer, beneficiary, Term.Embargo(target), activeFrom = now, endTurn = now + turns))
    )

    fun demilitarizeBorder(a: CivName, b: CivName, turns: Int, now: Int, mutual: Boolean = true) = DealDraft(
        proposer = a, counterparty = b,
        items = buildList {
            add(CommitmentDraft(b, a, Term.DemilitarizeBorder, activeFrom = now, endTurn = now + turns))
            if (mutual) add(CommitmentDraft(a, b, Term.DemilitarizeBorder, activeFrom = now, endTurn = now + turns))
        }
    )

    fun protectCityState(committer: CivName, beneficiary: CivName, cityState: CivName, turns: Int, now: Int) = DealDraft(
        proposer = beneficiary, counterparty = committer,
        items = listOf(CommitmentDraft(committer, beneficiary, Term.NoBullyOrAttackCityState(cityState), activeFrom = now, endTurn = now + turns))
    )

    fun voteFor(voter: CivName, candidate: CivName, turns: Int, now: Int) = DealDraft(
        proposer = candidate, counterparty = voter,
        items = listOf(CommitmentDraft(voter, candidate, Term.VoteFor(candidate), activeFrom = now, endTurn = now + turns))
    )
}

/**
 * How much a single commitment is worth to [ai], in the same units as TradeEvaluation / war motivation:
 * positive if it benefits the AI, negative if it costs it. The game adapter implements this on top of
 * the existing evaluators (e.g. DeclareWarPlanEvaluator for war obligations, TradeEvaluation for gold).
 */
fun interface TermValuator {
    fun valueToAi(item: CommitmentDraft, ai: CivName): Float
}

data class DealVerdict(
    val accepted: Boolean,
    /** Sum of item values plus the (clamped) tone effect. */
    val surplus: Float,
    /** Surplus the AI demands, from the difficulty. */
    val required: Float,
    val perItem: List<Float>,
    val toneApplied: Float
)

/**
 * The engine, not the language model, decides whether the AI accepts. The model's reading of the player's
 * tone enters only as [tone], which is clamped so politeness helps and rudeness hurts but neither can buy a bad deal.
 */
class DealEvaluator(
    private val valuator: TermValuator,
    private val difficulty: NegotiationDifficulty,
    private val policy: BreachPolicy = BreachPolicy()
) {
    fun evaluate(deal: DealDraft, ai: CivName, tone: Float = 0f): DealVerdict {
        val perItem = deal.items.map { valuator.valueToAi(it, ai) }
        val toneApplied = tone.coerceIn(-policy.maxToneInfluence, policy.maxToneInfluence)
        val surplus = perItem.sum() + toneApplied
        return DealVerdict(surplus >= difficulty.bargainingMargin, surplus, difficulty.bargainingMargin, perItem, toneApplied)
    }
}
