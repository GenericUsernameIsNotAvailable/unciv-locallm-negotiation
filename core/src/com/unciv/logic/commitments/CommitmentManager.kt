package com.unciv.logic.commitments

sealed interface ProposalResult {
    data class Proposed(val dealId: Int) : ProposalResult
    data class Rejected(val errors: List<ValidationError>) : ProposalResult
}

sealed interface AcceptResult {
    /** Confirmed by this party; waiting for the other. */
    data object Pending : AcceptResult
    data object Activated : AcceptResult
    /** Unknown deal, not open, or [by] is not a party to it. */
    data object NotFound : AcceptResult
    /** The world changed since the proposal and the deal is no longer valid; it has been cancelled. */
    data class Invalid(val errors: List<ValidationError>) : AcceptResult
}

private enum class BreachCause { Deliberate, Violation, Involuntary }

/**
 * Owns all deals and commitments, enforces them each turn and records why it did what it did.
 * It never talks to an LLM and has no libGDX dependencies; the game is reached only through
 * [CommitmentWorld] and [CommitmentActions]. Windows are inclusive: a commitment applies on
 * turns activeFrom..endTurn and is resolved on the first tick after endTurn.
 */
class CommitmentManager(
    private val world: CommitmentWorld,
    private val actions: CommitmentActions,
    private val policy: BreachPolicy = BreachPolicy(),
    private val difficulty: NegotiationDifficulty = NegotiationDifficulty.Normal,
    private val validator: CommitmentValidator = CommitmentValidator(world),
    private val betrayalEvaluator: BetrayalEvaluator = BetrayalEvaluator(),
    private val betrayalOracle: BetrayalOracle? = null,
    private val noise: () -> Float = { 0.5f },
    /** Called after any public operation that changed state, so the owner can persist it. */
    private val onChanged: () -> Unit = {}
) {
    private val commitments = LinkedHashMap<Int, Commitment>()
    private val deals = LinkedHashMap<Int, Deal>()
    private val outbox = ArrayList<OutboxMessage>()
    private var nextCommitmentId = 1
    private var nextDealId = 1
    private var dirty = false

    private fun store(c: Commitment) { commitments[c.id] = c; dirty = true }
    private fun storeDeal(d: Deal) { deals[d.id] = d; dirty = true }
    private fun queue(m: OutboxMessage) { outbox += m; dirty = true }
    private fun flush() {
        if (!dirty) return
        dirty = false
        onChanged()
    }

    val log = DecisionLog()

    // ---------------------------------------------------------------- queries

    fun commitment(id: Int): Commitment? = commitments[id]
    fun deal(id: Int): Deal? = deals[id]
    fun allCommitments(): List<Commitment> = commitments.values.toList()

    /** Open (proposed or active) commitments in which [civ] is committer or beneficiary. */
    fun openCommitmentsOf(civ: CivName): List<Commitment> =
        commitments.values.filter { it.status.isOpen && (it.committer == civ || it.beneficiary == civ) }

    /** Active commitments by which [civ] has secretly decided to break a restraint. */
    fun secretIntentions(civ: CivName): List<Commitment> =
        commitments.values.filter { it.status == CommitmentStatus.Active && it.breachLicensed && it.committer == civ }

    /** AI automation asks this before settling, declaring war or making peace. */
    fun isForbidden(actor: CivName, action: AiAction): Boolean = commitments.values.any {
        it.committer == actor && it.status == CommitmentStatus.Active && !it.breachLicensed &&
            inForce(it) && forbids(it, action)
    }

    /** The candidate [civ] has committed to vote for in the Diplomatic Victory vote, if any. */
    fun committedVoteCandidate(civ: CivName): CivName? = commitments.values.firstOrNull {
        it.committer == civ && it.status == CommitmentStatus.Active && inForce(it) && it.term is Term.VoteFor
    }?.let { (it.term as Term.VoteFor).candidate }

    /** Production priority multipliers requested of [civ], by construction name, for ConstructionAutomation. */
    fun constructionBias(civ: CivName): Map<String, Float> {
        val result = HashMap<String, Float>()
        for (c in commitments.values) {
            val term = c.term as? Term.PrioritizeConstruction ?: continue
            if (c.status == CommitmentStatus.Active && c.committer == civ && inForce(c)) {
                val prev = result[term.construction]
                result[term.construction] = if (prev == null) term.multiplier else maxOf(prev, term.multiplier)
            }
        }
        return result
    }

    fun drainOutbox(): List<OutboxMessage> {
        val messages = outbox.toList()
        if (messages.isNotEmpty()) {
            outbox.clear()
            dirty = true
            flush()
        }
        return messages
    }

    /** Drains only the outbox messages from [from] to [to], leaving messages for other civ pairs intact. */
    fun drainOutboxFor(from: CivName, to: CivName): List<OutboxMessage> {
        val matching = outbox.filter { it.from == from && it.to == to }
        if (matching.isNotEmpty()) {
            outbox.removeAll { it.from == from && it.to == to }
            dirty = true
            flush()
        }
        return matching
    }

    // ---------------------------------------------------------------- saving and restoring

    /** The full state, for storing in the save game. */
    fun snapshot(): CommitmentSnapshot = CommitmentSnapshot(
        nextCommitmentId, nextDealId, commitments.values.toList(), deals.values.toList(),
        log.entries.takeLast(policy.maxSavedLogEntries), outbox.toList()
    )

    /** Replaces all state with [snapshot]. Does not trigger [onChanged]. */
    fun restore(snapshot: CommitmentSnapshot) {
        commitments.clear()
        for (c in snapshot.commitments) commitments[c.id] = c
        deals.clear()
        for (d in snapshot.deals) deals[d.id] = d
        outbox.clear()
        outbox.addAll(snapshot.outbox)
        log.replaceWith(snapshot.log)
        nextCommitmentId = snapshot.nextCommitmentId
        nextDealId = snapshot.nextDealId
        dirty = false
    }

    // ---------------------------------------------------------------- deal lifecycle

    /** Validates and registers a deal. The proposer's confirmation is implied. */
    fun propose(draft: DealDraft): ProposalResult {
        val errors = validator.validateDeal(draft, commitments.values)
        if (errors.isNotEmpty()) return ProposalResult.Rejected(errors)

        val dealId = nextDealId++
        dirty = true
        val ids = draft.items.indices.map { nextCommitmentId + it }
        nextCommitmentId += ids.size
        dirty = true
        draft.items.forEachIndexed { i, item ->
            val resolved = item.copy(condition = item.condition?.resolve(ids))
            store(Commitment(ids[i], dealId, resolved))
        }
        storeDeal(Deal(dealId, draft, ids, world.currentTurn, setOf(draft.proposer), DealStatus.Proposed))
        record(DecisionKind.DealProposed, draft.proposer, draft.counterparty, null)
        flush()
        return ProposalResult.Proposed(dealId)
    }

    fun accept(dealId: Int, by: CivName): AcceptResult {
        val deal = deals[dealId] ?: return AcceptResult.NotFound
        if (deal.status != DealStatus.Proposed || by != deal.proposer && by != deal.counterparty) return AcceptResult.NotFound

        val confirmed = deal.confirmedBy + by
        if (confirmed.size < 2) {
            storeDeal(deal.copy(confirmedBy = confirmed))
            flush()
            return AcceptResult.Pending
        }
        // The world may have changed since the proposal: validate again, ignoring this deal's own items.
        val others = commitments.values.filter { it.dealId != dealId }
        val errors = validator.validateDeal(deal.draft, others)
        if (errors.isNotEmpty()) {
            cancelDeal(deal, ReasonCode.NoLongerValid)
            flush()
            return AcceptResult.Invalid(errors)
        }
        activate(deal.copy(confirmedBy = confirmed, status = DealStatus.Active))
        flush()
        return AcceptResult.Activated
    }

    fun reject(dealId: Int, by: CivName): Boolean {
        val deal = deals[dealId] ?: return false
        if (deal.status != DealStatus.Proposed || by != deal.proposer && by != deal.counterparty) return false
        cancelDeal(deal, ReasonCode.DeclinedByParty, actor = by)
        flush()
        return true
    }

    private fun cancelDeal(deal: Deal, reason: ReasonCode, actor: CivName = deal.proposer) {
        storeDeal(deal.copy(status = DealStatus.Rejected))
        for (id in deal.commitmentIds) store(commitments.getValue(id).copy(status = CommitmentStatus.Cancelled))
        val other = if (actor == deal.proposer) deal.counterparty else deal.proposer
        record(DecisionKind.DealRejected, actor, other, null, Reason(reason))
    }

    private fun activate(deal: Deal) {
        storeDeal(deal)
        for (id in deal.commitmentIds) {
            val c = commitments.getValue(id)
            store(c.copy(status = CommitmentStatus.Active))
            record(DecisionKind.CommitmentActivated, c.committer, c.beneficiary, id)
        }
        // Lump sums that are already due change hands immediately, so a loan is tangible at once.
        for (id in deal.commitmentIds) {
            val c = commitments.getValue(id)
            if (c.term is Term.PayGoldLump && world.currentTurn >= c.activeFrom) processLump(c)
        }
        record(DecisionKind.DealActivated, deal.proposer, deal.counterparty, null)
    }

    // ---------------------------------------------------------------- events from the game

    fun onEvent(event: WorldEvent) {
        for (snapshot in commitments.values.toList()) {
            val c = commitments.getValue(snapshot.id)
            if (c.status != CommitmentStatus.Active) continue
            val inWindow = world.currentTurn in c.activeFrom..c.endTurn
            when (event) {
                is WorldEvent.CitySettledNear ->
                    if (c.term == Term.NoSettleNear && inForce(c) && c.committer == event.settler && c.beneficiary == event.nearCiv)
                        violated(c)
                is WorldEvent.WarDeclared -> when (val term = c.term) {
                    Term.NoAttack ->
                        if (inForce(c) && c.committer == event.attacker && c.beneficiary == event.defender) violated(c)
                    is Term.NoDeclareWar ->
                        if (inForce(c) && c.committer == event.attacker && term.target == event.defender) violated(c)
                    is Term.NoBullyOrAttackCityState ->
                        if (inForce(c) && c.committer == event.attacker && term.cityState == event.defender) violated(c)
                    is Term.DeclareWar ->
                        if (inWindow && c.committer == event.attacker && term.target == event.defender)
                            fulfill(c, ReasonCode.ObligationExecuted)
                    else -> {}
                }
                is WorldEvent.PeaceMade -> {
                    val term = c.term
                    if (term is Term.NoPeaceWith && inForce(c) &&
                        (c.committer == event.a && term.target == event.b || c.committer == event.b && term.target == event.a)
                    ) violated(c)
                }
                is WorldEvent.ConstructionCompleted -> {
                    val term = c.term
                    if (term is Term.PrioritizeConstruction && inWindow && c.committer == event.civ && term.construction == event.construction)
                        fulfill(c, ReasonCode.CompletedAsPromised)
                }
                is WorldEvent.TradeCompleted -> {
                    val term = c.term
                    if (term is Term.Embargo && inForce(c) &&
                        (c.committer == event.a && term.target == event.b || c.committer == event.b && term.target == event.a)
                    ) violated(c)
                }
                is WorldEvent.BorderZoneViolated -> {
                    if (c.term == Term.DemilitarizeBorder && inForce(c) && c.committer == event.violator && c.beneficiary == event.victim)
                        violated(c)
                }
                is WorldEvent.CityStateBullied -> {
                    val term = c.term
                    if (term is Term.NoBullyOrAttackCityState && inForce(c) && c.committer == event.bully && term.cityState == event.cityState)
                        violated(c)
                }
                is WorldEvent.DiplomaticVoteCast -> {
                    val term = c.term
                    if (term is Term.VoteFor && inWindow && c.committer == event.voter) {
                        if (event.candidate == term.candidate) fulfill(c, ReasonCode.ObligationExecuted)
                        else violated(c)
                    }
                }
            }
        }
        flush()
    }

    private fun violated(c: Commitment) {
        val cause = if (c.breachLicensed) BreachCause.Deliberate else BreachCause.Violation
        val reason = if (cause == BreachCause.Deliberate) ReasonCode.DeliberateBreach else ReasonCode.ViolatedByEvent
        breach(c, cause, reason)
    }

    // ---------------------------------------------------------------- per-turn processing

    /** Call once at the start of each turn, after the world's current turn has advanced. */
    fun tick(): TickReport {
        val logStart = log.size
        val outStart = outbox.size
        val t = world.currentTurn

        for (deal in deals.values.toList()) {
            if (deal.status == DealStatus.Proposed && t - deal.proposedTurn > policy.proposalTtlTurns)
                cancelDeal(deal, ReasonCode.ProposalExpired)
        }
        for (id in commitments.keys.toList()) {
            var c = commitments.getValue(id)
            if (c.status != CommitmentStatus.Active) continue
            if (t > c.endTurn) {
                closeWindow(c)
                continue
            }
            if (t < c.activeFrom) continue
            if (!c.conditionEverHeld && conditionHolds(c.condition)) {
                c = c.copy(conditionEverHeld = true)
                store(c)
            }
            when (c.term) {
                Term.NoSettleNear, Term.NoAttack, is Term.NoDeclareWar, is Term.NoPeaceWith, is Term.Embargo,
                Term.DemilitarizeBorder, is Term.NoBullyOrAttackCityState -> considerBetrayal(c)
                is Term.DeclareWar -> processDeclareWar(c, c.term as Term.DeclareWar)
                is Term.PayGoldPerTurn -> processPayment(c, c.term as Term.PayGoldPerTurn)
                is Term.PayGoldLump -> processLump(c)
                is Term.PrioritizeConstruction, is Term.VoteFor -> {}
            }
            commitments[id]?.let { remindIfDue(it) }
        }
        flush()
        return TickReport(log.entries.drop(logStart), outbox.drop(outStart))
    }

    private fun closeWindow(c: Commitment) {
        when (c.term) {
            Term.NoSettleNear, Term.NoAttack, is Term.NoDeclareWar, is Term.NoPeaceWith, is Term.Embargo,
            Term.DemilitarizeBorder, is Term.NoBullyOrAttackCityState ->
                if (c.conditionEverHeld) fulfill(c, ReasonCode.HeldThroughWindow) else conditionNotMet(c)
            is Term.PayGoldPerTurn ->
                // If the other side broke their part we stopped paying; nothing was owed at the end.
                if (conditionHolds(c.condition)) fulfill(c, ReasonCode.HeldThroughWindow) else conditionNotMet(c)
            is Term.PayGoldLump -> processLump(c)
            is Term.DeclareWar ->
                if (!c.conditionEverHeld) conditionNotMet(c)
                else breach(
                    c,
                    if (world.isAI(c.committer)) BreachCause.Involuntary else BreachCause.Violation,
                    if (world.isAI(c.committer)) ReasonCode.ActionRefused else ReasonCode.DeadlinePassed
                )
            is Term.PrioritizeConstruction, is Term.VoteFor -> {
                store(c.copy(status = CommitmentStatus.Expired))
                record(DecisionKind.CommitmentExpired, c.committer, c.beneficiary, c.id, Reason(ReasonCode.BestEffortNotCompleted))
            }
        }
    }

    private fun processDeclareWar(c: Commitment, term: Term.DeclareWar) {
        if (!commitments.getValue(c.id).conditionEverHeld) return
        if (world.isAtWar(c.committer, term.target)) {
            fulfill(c, ReasonCode.AlreadyAtWar)
        } else if (world.isAI(c.committer)) {
            // The engine performs the AI's obligation. A human's war declaration is only monitored.
            if (actions.declareWar(c.committer, term.target)) fulfill(c, ReasonCode.ObligationExecuted)
            else store(c.copy(failedAttempts = c.failedAttempts + 1))
        }
    }

    private fun processPayment(c: Commitment, term: Term.PayGoldPerTurn) {
        if (!conditionHolds(c.condition)) return // paused while the other side is not honouring their part
        if (!actions.transferGold(c.committer, c.beneficiary, term.amount))
            breach(c, BreachCause.Involuntary, ReasonCode.InsufficientGold, mapOf("amount" to term.amount.toFloat()))
    }

    private fun processLump(c: Commitment) {
        val amount = (c.term as Term.PayGoldLump).amount
        if (actions.transferGold(c.committer, c.beneficiary, amount)) fulfill(c, ReasonCode.ObligationExecuted)
        else breach(c, BreachCause.Involuntary, ReasonCode.InsufficientGold, mapOf("amount" to amount.toFloat()))
    }

    private fun remindIfDue(c: Commitment) {
        if (c.status != CommitmentStatus.Active || c.term !is Term.DeclareWar) return
        if (world.isAI(c.beneficiary) && !world.isAI(c.committer) && c.endTurn - world.currentTurn == policy.reminderLeadTurns) {
            record(DecisionKind.ReminderSent, c.beneficiary, c.committer, c.id, Reason(ReasonCode.ReminderLeadTime))
            queue(OutboxMessage(world.currentTurn, c.beneficiary, c.committer, OutboxKind.ReminderToPlayer, c.id))
        }
    }

    // ---------------------------------------------------------------- betrayal

    /**
     * An AI committer may decide to break a restraint. The decision is made here, by the engine, and logged
     * with its numbers; the commitment is then no longer "forbidden" for AI automation. The actual breach
     * is recorded when the forbidden act really happens, so a betrayal is always a knowing one.
     */
    private fun considerBetrayal(c: Commitment) {
        val oracle = betrayalOracle ?: return
        if (c.breachLicensed || !world.isAI(c.committer) || !inForce(c)) return
        val assessment = oracle.assess(c) ?: return
        val observers = world.civsKnowingBoth(c.committer, c.beneficiary).size
        val reputationCost = policy.relationshipPenalty + observers * policy.gossipPenalty
        val decision = betrayalEvaluator.decide(assessment, reputationCost, difficulty, noise())
        if (!decision.betray) return
        store(c.copy(breachLicensed = true))
        record(
            DecisionKind.BetrayalDecided, c.committer, c.beneficiary, c.id,
            Reason(
                ReasonCode.BetrayalBenefitExceedsCost,
                mapOf(
                    "benefit" to assessment.benefit, "reputationCost" to reputationCost,
                    "loyalty" to decision.loyalty, "threshold" to decision.threshold
                )
            )
        )
    }

    // ---------------------------------------------------------------- resolution

    private fun fulfill(c: Commitment, reason: ReasonCode) {
        store(c.copy(status = CommitmentStatus.Fulfilled))
        record(DecisionKind.CommitmentFulfilled, c.committer, c.beneficiary, c.id, Reason(reason))
        actions.applyFulfilmentBonus(c.beneficiary, c.committer, policy.fulfilmentBonus)
    }

    private fun conditionNotMet(c: Commitment) {
        store(c.copy(status = CommitmentStatus.ConditionNotMet))
        record(DecisionKind.ConditionNotMet, c.committer, c.beneficiary, c.id, Reason(ReasonCode.ConditionNotMet))
    }

    private fun breach(c: Commitment, cause: BreachCause, reason: ReasonCode, values: Map<String, Float> = emptyMap()) {
        store(c.copy(status = CommitmentStatus.Breached))
        record(DecisionKind.CommitmentBreached, c.committer, c.beneficiary, c.id, Reason(reason, values))

        val factor = if (cause == BreachCause.Involuntary) policy.involuntaryFactor else 1f
        actions.applyBreachPenalty(c.beneficiary, c.committer, -policy.relationshipPenalty * factor)
        for (observer in world.civsKnowingBoth(c.committer, c.beneficiary))
            actions.applyGossipPenalty(observer, c.committer, -policy.gossipPenalty * factor)

        val turn = world.currentTurn
        val aiCommitter = world.isAI(c.committer)
        val aiBeneficiary = world.isAI(c.beneficiary)
        when {
            aiBeneficiary && !aiCommitter ->
                queue(OutboxMessage(turn, c.beneficiary, c.committer, OutboxKind.PlayerBrokeCommitment, c.id))
            aiCommitter && !aiBeneficiary && cause == BreachCause.Deliberate ->
                queue(OutboxMessage(turn, c.committer, c.beneficiary, OutboxKind.AiBetrayal, c.id))
            aiCommitter && !aiBeneficiary ->
                queue(OutboxMessage(turn, c.committer, c.beneficiary, OutboxKind.AiCouldNotKeepCommitment, c.id))
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun record(kind: DecisionKind, actor: CivName, other: CivName, id: Int?, vararg reasons: Reason) {
        log.add(DecisionLogEntry(world.currentTurn, kind, actor, other, id, reasons.toList()))
        dirty = true
    }

    /** Restraints bind only inside their window and while their condition (if any) holds. */
    private fun inForce(c: Commitment): Boolean =
        world.currentTurn in c.activeFrom..c.endTurn && conditionHolds(c.condition)

    private fun forbids(c: Commitment, action: AiAction): Boolean = when (val term = c.term) {
        Term.NoSettleNear -> action is AiAction.SettleNear && action.nearCiv == c.beneficiary
        Term.NoAttack -> action is AiAction.DeclareWar && action.target == c.beneficiary
        is Term.NoDeclareWar -> action is AiAction.DeclareWar && action.target == term.target
        is Term.NoPeaceWith -> action is AiAction.MakePeace && action.with == term.target
        is Term.Embargo -> action is AiAction.TradeWith && action.partner == term.target
        Term.DemilitarizeBorder -> action is AiAction.MassTroopsNear && action.nearCiv == c.beneficiary
        is Term.NoBullyOrAttackCityState ->
            (action is AiAction.BullyCityState && action.cityState == term.cityState) ||
                (action is AiAction.DeclareWar && action.target == term.cityState)
        else -> false
    }

    private fun conditionHolds(condition: Condition?): Boolean = when (condition) {
        null -> true
        is Condition.ForceRatioAtLeast -> {
            val denominator = world.force(condition.versus)
            val ratio = if (denominator <= 0f) Float.MAX_VALUE else world.force(condition.civ) / denominator
            ratio >= condition.ratio
        }
        is Condition.AtWar -> world.isAtWar(condition.a, condition.b) == condition.expected
        is Condition.RelationshipAtLeast -> world.relationship(condition.from, condition.toward) >= condition.level
        is Condition.TurnAtLeast -> world.currentTurn >= condition.turn
        is Condition.CommitmentHonored -> commitments[condition.commitmentId]?.status.let {
            it == CommitmentStatus.Active || it == CommitmentStatus.Fulfilled
        }
        is Condition.SiblingHonored -> false // unresolved reference; the validator rejects these
        is Condition.AllOf -> condition.conditions.all { conditionHolds(it) }
    }
}
