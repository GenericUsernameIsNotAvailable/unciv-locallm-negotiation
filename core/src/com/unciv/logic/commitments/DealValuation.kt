package com.unciv.logic.commitments

import kotlin.math.max

/**
 * Game state needed to value commitments and assess betrayal temptation.
 * Kept free of libGDX and Unciv classes so valuations and betrayals can be unit-tested with fakes.
 */
interface ValuationSupport {
    fun gold(civ: CivName): Int
    fun goldPerTurn(civ: CivName): Int
    fun isAtWar(a: CivName, b: CivName): Boolean
    fun relationship(from: CivName, toward: CivName): Relationship
    /** 0..10 personality scale (5 is neutral). */
    fun trait(civ: CivName, name: String): Float
    /** Positive when [attacker] wants to attack [target] on its own. */
    fun motivationToAttack(attacker: CivName, target: CivName): Float
    /** How willing [ai] is to declare war on [target] alongside [partner]; > 0 means willing without payment. */
    fun warPlanMotivation(ai: CivName, target: CivName, partner: CivName): Float
    /** Positive when [ai] is winning against [enemy] and reluctant to make peace; negative when losing. */
    fun peaceCostForThem(ai: CivName, enemy: CivName): Int
}

/**
 * Values each [CommitmentDraft] from [ai]'s perspective in gold-equivalent units:
 * positive when [ai] is the beneficiary, negative when [ai] is the committer.
 */
class StandardTermValuator(private val support: ValuationSupport) : TermValuator {

    override fun valueToAi(item: CommitmentDraft, ai: CivName): Float {
        val duration = max(1, item.endTurn - item.activeFrom + 1)
        val aiCommits = item.committer == ai
        val aiBenefits = item.beneficiary == ai
        if (!aiCommits && !aiBenefits) return 0f

        return when (val term = item.term) {
            is Term.PayGoldLump -> valueLump(ai, term.amount, aiCommits)
            is Term.PayGoldPerTurn -> valuePerTurn(ai, term.amount, duration, aiCommits)
            Term.NoSettleNear -> valueNoSettle(ai, duration, aiCommits)
            Term.NoAttack -> valueNoAttack(ai, if (aiCommits) item.beneficiary else item.committer, duration, aiCommits)
            is Term.NoDeclareWar -> valueNoDeclareWar(ai, term.target, duration, aiCommits)
            is Term.NoPeaceWith -> valueNoPeace(ai, term.target, duration, aiCommits)
            is Term.Embargo -> valueEmbargo(ai, term.target, duration, aiCommits)
            Term.DemilitarizeBorder -> valueDemilitarize(ai, if (aiCommits) item.beneficiary else item.committer, duration, aiCommits)
            is Term.NoBullyOrAttackCityState -> valueCityStatePact(ai, duration, aiCommits)
            is Term.DeclareWar -> valueDeclareWar(ai, term.target, if (aiCommits) item.beneficiary else item.committer, item.condition, aiCommits)
            is Term.PrioritizeConstruction -> valueConstruction(term.multiplier, aiCommits)
            is Term.VoteFor -> valueVoteFor(ai, term.candidate, aiCommits)
        }
    }

    private fun valueLump(ai: CivName, amount: Int, aiCommits: Boolean): Float {
        if (!aiCommits) return amount.toFloat()
        val treasury = support.gold(ai)
        if (treasury < amount) return -UNACCEPTABLE_COST
        val reserveShare = (treasury - amount).toFloat() / max(1, treasury)
        val strain = if (reserveShare < 0.25f) 1.35f else 1.05f
        return -amount * strain
    }

    private fun valuePerTurn(ai: CivName, amount: Int, duration: Int, aiCommits: Boolean): Float {
        val discounted = amount * duration * 0.8f
        if (!aiCommits) return discounted
        val gpt = support.goldPerTurn(ai)
        val strain = if (amount > gpt) 1.4f else 1f
        return -discounted * strain
    }

    private fun valueNoSettle(ai: CivName, duration: Int, aiCommits: Boolean): Float {
        val scale = (duration.coerceAtMost(30) / 15f).coerceAtLeast(0.5f)
        if (!aiCommits) return 35f * scale
        val expansion = support.trait(ai, "Expansion")
        return -(15f + max(0f, expansion - 4f) * 8f) * scale
    }

    private fun valueNoAttack(ai: CivName, other: CivName, duration: Int, aiCommits: Boolean): Float {
        val scale = (duration.coerceAtMost(30) / 15f).coerceAtLeast(0.5f)
        if (!aiCommits) return 45f * scale
        val warDesire = max(0f, support.motivationToAttack(ai, other))
        return -(20f * scale + warDesire * 8f)
    }

    private fun valueNoDeclareWar(ai: CivName, target: CivName, duration: Int, aiCommits: Boolean): Float {
        val scale = (duration.coerceAtMost(30) / 15f).coerceAtLeast(0.5f)
        if (!aiCommits) {
            val friendlyToTarget = support.relationship(ai, target) >= Relationship.Friend
            return if (friendlyToTarget) 40f * scale else 15f * scale
        }
        val warDesire = max(0f, support.motivationToAttack(ai, target))
        return -(15f * scale + warDesire * 8f)
    }

    private fun valueEmbargo(ai: CivName, target: CivName, duration: Int, aiCommits: Boolean): Float {
        val scale = (duration.coerceAtMost(30) / 15f).coerceAtLeast(0.5f)
        val rel = support.relationship(ai, target)
        if (!aiCommits) {
            return if (rel <= Relationship.Enemy) 40f * scale else 15f * scale
        }
        val commerce = support.trait(ai, "Commerce")
        val friendPenalty = if (rel >= Relationship.Friend) 50f else 0f
        return -(20f + max(0f, commerce - 4f) * 6f + friendPenalty) * scale
    }

    private fun valueNoPeace(ai: CivName, target: CivName, duration: Int, aiCommits: Boolean): Float {
        val scale = (duration.coerceAtMost(30) / 15f).coerceAtLeast(0.5f)
        if (!aiCommits) {
            return if (support.isAtWar(ai, target) || support.relationship(ai, target) <= Relationship.Enemy) 50f * scale else 10f
        }
        val peaceAdvantage = support.peaceCostForThem(ai, target)
        return if (peaceAdvantage > 0) -30f * scale else -(100f + (-peaceAdvantage).coerceAtMost(400) * 0.5f) * scale
    }

    private fun valueDeclareWar(
        ai: CivName,
        target: CivName,
        partner: CivName,
        condition: Condition?,
        aiCommits: Boolean
    ): Float {
        if (!aiCommits) {
            return when {
                support.isAtWar(ai, target) -> 180f
                support.relationship(ai, target) <= Relationship.Enemy -> 90f
                else -> 0f
            }
        }
        val motivation = support.warPlanMotivation(ai, target, partner)
        if (motivation < -10f) return -UNACCEPTABLE_COST
        val hasForceSafeguard = condition?.flatten().orEmpty().any { cond ->
            cond is Condition.ForceRatioAtLeast && cond.civ == ai && cond.ratio >= 1.2f
        }
        val discount = if (hasForceSafeguard) 0.7f else 1f
        return if (motivation < 0f) (-motivation * 80f * discount).coerceIn(80f, 800f) * -1f else -40f * discount
    }

    private fun valueDemilitarize(ai: CivName, other: CivName, duration: Int, aiCommits: Boolean): Float {
        val scale = (duration.coerceAtMost(30) / 15f).coerceAtLeast(0.5f)
        if (!aiCommits) return 35f * scale
        val warDesire = max(0f, support.motivationToAttack(ai, other))
        return -(20f * scale + warDesire * 6f)
    }

    private fun valueCityStatePact(ai: CivName, duration: Int, aiCommits: Boolean): Float {
        val scale = (duration.coerceAtMost(30) / 15f).coerceAtLeast(0.5f)
        if (!aiCommits) return 25f * scale
        val aggressive = support.trait(ai, "Aggressive")
        return -(15f + max(0f, aggressive - 5f) * 5f) * scale
    }

    private fun valueVoteFor(ai: CivName, candidate: CivName, aiCommits: Boolean): Float {
        if (!aiCommits) return if (candidate == ai) 120f else 20f
        val rel = support.relationship(ai, candidate)
        return when {
            rel >= Relationship.Ally -> -30f
            rel >= Relationship.Friend -> -60f
            rel >= Relationship.Neutral -> -110f
            else -> -UNACCEPTABLE_COST
        }
    }

    private fun valueConstruction(multiplier: Float, aiCommits: Boolean): Float {
        if (!aiCommits) return 15f
        val extra = max(0.5f, multiplier - 1f)
        return -15f * extra
    }

    companion object {
        const val UNACCEPTABLE_COST = 100_000f
    }
}

/**
 * Assesses whether an AI committer is tempted to break an active restraint this turn.
 */
class StandardBetrayalOracle(private val support: ValuationSupport) : BetrayalOracle {
    override fun assess(commitment: Commitment): BetrayalAssessment? {
        val ai = commitment.committer
        val loyalty = (support.trait(ai, "Loyal") / 10f).coerceIn(0f, 1f)
        val benefit = when (val term = commitment.term) {
            Term.NoAttack -> support.motivationToAttack(ai, commitment.beneficiary) * 2f
            is Term.NoDeclareWar -> support.motivationToAttack(ai, term.target) * 2f
            is Term.NoPeaceWith -> {
                val peaceCost = support.peaceCostForThem(ai, term.target)
                if (peaceCost < 0) (-peaceCost / 20f).coerceAtMost(80f) else 0f
            }
            Term.NoSettleNear -> {
                val expansion = support.trait(ai, "Expansion")
                if (expansion >= 8f && support.relationship(ai, commitment.beneficiary) <= Relationship.Competitor) 35f else 0f
            }
            Term.DemilitarizeBorder -> support.motivationToAttack(ai, commitment.beneficiary) * 1.8f
            else -> 0f
        }
        return if (benefit <= 5f) null else BetrayalAssessment(benefit, loyalty)
    }
}
