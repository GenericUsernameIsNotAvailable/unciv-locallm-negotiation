package com.unciv.logic.commitments

enum class ValidationCode {
    EmptyDeal, SameParty, NotAParty, BadTiming, HorizonTooFar, BadParameter, BadTarget,
    NotAtWar, AlreadyAtWar, DefensivePact, Contradiction, DuplicateTerm, TooManyCommitments,
    InsufficientGold, UnresolvedReference
}

data class ValidationError(val code: ValidationCode, val message: String)

/** Hard bounds on what a (possibly LLM-produced) deal may contain. Placeholders, to be tuned. */
data class ValidationLimits(
    val maxHorizonTurns: Int = 100,
    val maxOpenPerPair: Int = 5,
    val maxOpenPerCiv: Int = 12,
    val maxGoldPerTurn: Int = 1000,
    val maxGoldLump: Int = 5000,
    val minForceRatio: Float = 0.25f,
    val maxForceRatio: Float = 10f,
    val maxConstructionMultiplier: Float = 5f
)

class CommitmentValidator(
    private val world: CommitmentWorld,
    private val limits: ValidationLimits = ValidationLimits()
) {
    private data class Entry(val committer: CivName, val beneficiary: CivName, val term: Term)

    /**
     * @param existing all registered commitments (any status); only open ones count towards caps and contradictions.
     */
    fun validateDeal(deal: DealDraft, existing: Collection<Commitment>): List<ValidationError> {
        val errors = ArrayList<ValidationError>()
        fun err(code: ValidationCode, message: String) { errors += ValidationError(code, message) }

        if (deal.items.isEmpty()) {
            err(ValidationCode.EmptyDeal, "A deal needs at least one commitment")
            return errors
        }
        if (deal.proposer == deal.counterparty) err(ValidationCode.SameParty, "A civ cannot make a deal with itself")
        val parties = setOf(deal.proposer, deal.counterparty)
        val open = existing.filter { it.status.isOpen }
        val knownIds = existing.map { it.id }.toSet()
        val dealEntries = deal.items.map { Entry(it.committer, it.beneficiary, it.term) }
        val openEntries = open.map { Entry(it.committer, it.beneficiary, it.term) }

        deal.items.forEachIndexed { index, item ->
            val prefix = "Item ${index + 1}"
            fun itemErr(code: ValidationCode, message: String) = err(code, "$prefix: $message")

            if (item.committer == item.beneficiary) itemErr(ValidationCode.SameParty, "committer and beneficiary are the same civ")
            if (item.committer !in parties || item.beneficiary !in parties)
                itemErr(ValidationCode.NotAParty, "only the two parties of the deal can be committer or beneficiary")

            validateTiming(item, ::itemErr)
            validateTerm(item, ::itemErr)
            validateCondition(item, index, deal.items.size, knownIds, ::itemErr)

            val me = dealEntries[index]
            val others = openEntries + dealEntries.filterIndexed { i, _ -> i != index }
            for (other in others) {
                if (other == me) itemErr(ValidationCode.DuplicateTerm, "the same commitment already exists")
                else if (conflicts(me, other))
                    itemErr(ValidationCode.Contradiction, "contradicts another commitment by ${me.committer}")
            }
        }

        // Caps, counting the items of this deal too.
        val pairCounts = HashMap<Set<CivName>, Int>()
        for (e in openEntries + dealEntries) {
            val pairKey = setOf(e.committer, e.beneficiary)
            pairCounts[pairKey] = (pairCounts[pairKey] ?: 0) + 1
        }
        for (item in deal.items) {
            val pair = setOf(item.committer, item.beneficiary)
            if ((pairCounts[pair] ?: 0) > limits.maxOpenPerPair) {
                err(ValidationCode.TooManyCommitments, "too many open commitments between ${pair.joinToString(" and ")}")
                break
            }
        }
        for (civ in parties) {
            val count = (openEntries + dealEntries).count { it.committer == civ }
            if (count > limits.maxOpenPerCiv) err(ValidationCode.TooManyCommitments, "$civ would have too many open commitments")
        }
        return errors
    }

    private fun validateTiming(item: CommitmentDraft, err: (ValidationCode, String) -> Unit) {
        val now = world.currentTurn
        if (item.activeFrom < now || item.endTurn < item.activeFrom)
            err(ValidationCode.BadTiming, "the time window is in the past or ends before it starts")
        if (item.endTurn - now > limits.maxHorizonTurns)
            err(ValidationCode.HorizonTooFar, "commitments can run at most ${limits.maxHorizonTurns} turns ahead")
    }

    private fun validateTerm(item: CommitmentDraft, err: (ValidationCode, String) -> Unit) {
        val c = item.committer
        when (val term = item.term) {
            Term.NoSettleNear, Term.NoAttack, Term.DemilitarizeBorder -> {}
            is Term.NoDeclareWar -> validateThirdParty(item, term.target, err)
            is Term.Embargo -> validateThirdParty(item, term.target, err)
            is Term.NoBullyOrAttackCityState -> validateThirdParty(item, term.cityState, err)
            is Term.VoteFor -> if (term.candidate.isBlank() || term.candidate == c)
                err(ValidationCode.BadTarget, "vote candidate must be another civilization")
            is Term.NoPeaceWith -> {
                validateThirdParty(item, term.target, err)
                if (!world.isAtWar(c, term.target)) err(ValidationCode.NotAtWar, "$c is not at war with ${term.target}")
            }
            is Term.DeclareWar -> {
                validateThirdParty(item, term.target, err)
                if (world.isAtWar(c, term.target)) err(ValidationCode.AlreadyAtWar, "$c is already at war with ${term.target}")
                if (world.hasDefensivePact(c, term.target))
                    err(ValidationCode.DefensivePact, "$c has a defensive pact with ${term.target}")
            }
            is Term.PayGoldPerTurn ->
                if (term.amount !in 1..limits.maxGoldPerTurn) err(ValidationCode.BadParameter, "gold per turn must be 1..${limits.maxGoldPerTurn}")
            is Term.PayGoldLump -> {
                if (term.amount !in 1..limits.maxGoldLump) err(ValidationCode.BadParameter, "gold amount must be 1..${limits.maxGoldLump}")
                else if (world.gold(c) < term.amount) err(ValidationCode.InsufficientGold, "$c cannot afford ${term.amount} gold")
            }
            is Term.PrioritizeConstruction -> {
                if (term.construction.isBlank()) err(ValidationCode.BadParameter, "no construction named")
                if (term.multiplier !in 1f..limits.maxConstructionMultiplier)
                    err(ValidationCode.BadParameter, "priority multiplier must be 1..${limits.maxConstructionMultiplier}")
            }
        }
    }

    private fun validateThirdParty(item: CommitmentDraft, target: CivName, err: (ValidationCode, String) -> Unit) {
        if (target.isBlank() || target == item.committer || target == item.beneficiary)
            err(ValidationCode.BadTarget, "the target must be a third civ, not '$target'")
    }

    private fun validateCondition(
        item: CommitmentDraft, index: Int, itemCount: Int, knownIds: Set<Int>,
        err: (ValidationCode, String) -> Unit
    ) {
        for (cond in item.condition?.flatten().orEmpty()) {
            when (cond) {
                is Condition.SiblingHonored ->
                    if (cond.index !in 0 until itemCount || cond.index == index)
                        err(ValidationCode.UnresolvedReference, "refers to a deal item that does not exist")
                is Condition.CommitmentHonored ->
                    if (cond.commitmentId !in knownIds) err(ValidationCode.UnresolvedReference, "refers to unknown commitment #${cond.commitmentId}")
                is Condition.ForceRatioAtLeast -> {
                    if (cond.ratio !in limits.minForceRatio..limits.maxForceRatio)
                        err(ValidationCode.BadParameter, "force ratio must be ${limits.minForceRatio}..${limits.maxForceRatio}")
                    if (cond.civ.isBlank() || cond.versus.isBlank() || cond.civ == cond.versus)
                        err(ValidationCode.BadTarget, "force ratio needs two different civs")
                }
                is Condition.AtWar -> if (cond.a.isBlank() || cond.b.isBlank() || cond.a == cond.b)
                    err(ValidationCode.BadTarget, "war condition needs two different civs")
                is Condition.RelationshipAtLeast -> if (cond.from.isBlank() || cond.toward.isBlank() || cond.from == cond.toward)
                    err(ValidationCode.BadTarget, "relationship condition needs two different civs")
                is Condition.TurnAtLeast, is Condition.AllOf -> {}
            }
        }
    }

    private fun forbidsWarOn(e: Entry, target: CivName): Boolean = when (val t = e.term) {
        Term.NoAttack -> e.beneficiary == target
        is Term.NoDeclareWar -> t.target == target
        else -> false
    }

    /** Two commitments by the same civ conflict if one promises a war the other forbids. */
    private fun conflicts(a: Entry, b: Entry): Boolean {
        if (a.committer != b.committer) return false
        val aWar = a.term as? Term.DeclareWar
        val bWar = b.term as? Term.DeclareWar
        return (aWar != null && forbidsWarOn(b, aWar.target)) || (bWar != null && forbidsWarOn(a, bWar.target))
    }
}
