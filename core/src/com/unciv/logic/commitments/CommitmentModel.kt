package com.unciv.logic.commitments

import java.util.Locale

/** Civilizations are identified by their civID (the key of Civilization.diplomacy and GameInfo.civMap). */
typealias CivName = String

/**
 * Mirrors `com.unciv.logic.civilization.diplomacy.RelationshipLevel` (same names, same order)
 * so this package has no dependency on game or libGDX classes. Adapters map by name.
 */
enum class Relationship { Unforgivable, Enemy, Afraid, Competitor, Neutral, Favorable, Friend, Ally }

private fun Float.fmt(): String = String.format(Locale.ROOT, "%.1f", this)

/**
 * What a civ (the committer) promises to a beneficiary. This is a closed, whitelisted set:
 * the LLM front end can only choose from these and fill their parameters, and every entry must be
 * something the engine can detect, enforce and explain.
 */
sealed interface Term {
    /** Restraints are things the committer will NOT do; obligations are things it WILL do. */
    val isRestraint: Boolean

    /** Best-effort terms that are not completed by the end of their window expire without penalty. */
    val bestEffort: Boolean get() = false

    /** Plain-English summary for the confirmation screen: the structure, not the chat text, is the contract. */
    fun describe(committer: CivName, beneficiary: CivName): String

    // ---- Restraints ----

    data object NoSettleNear : Term {
        override val isRestraint: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will not found cities near $beneficiary"
    }

    data object NoAttack : Term {
        override val isRestraint: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will not attack $beneficiary"
    }

    data class NoDeclareWar(val target: CivName) : Term {
        override val isRestraint: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will not declare war on $target"
    }

    /** "Stay at war with [target]" - the basis of proxy-war subsidies. */
    data class NoPeaceWith(val target: CivName) : Term {
        override val isRestraint: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will stay at war with $target (no peace treaty)"
    }

    /** "Do not trade with [target]" - dynamic trade embargo against a third civ. */
    data class Embargo(val target: CivName) : Term {
        override val isRestraint: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will maintain a trade embargo against $target"
    }

    /** Demilitarised zone (DMZ): keep military units back from [beneficiary]'s borders. */
    data object DemilitarizeBorder : Term {
        override val isRestraint: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will keep military forces back from $beneficiary's borders (DMZ)"
    }

    /** City-state non-interference pact: do not bully/tribute or attack [cityState]. */
    data class NoBullyOrAttackCityState(val cityState: CivName) : Term {
        override val isRestraint: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will not bully or attack the city-state of $cityState"
    }

    // ---- Obligations ----

    data class DeclareWar(val target: CivName) : Term {
        override val isRestraint: Boolean get() = false
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will declare war on $target"
    }

    data class PayGoldPerTurn(val amount: Int) : Term {
        override val isRestraint: Boolean get() = false
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will pay $beneficiary $amount gold per turn"
    }

    data class PayGoldLump(val amount: Int) : Term {
        override val isRestraint: Boolean get() = false
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will pay $beneficiary $amount gold immediately"
    }

    /** A nudge, not an order: the AI still needs the prerequisites and production capacity. */
    data class PrioritizeConstruction(val construction: String, val multiplier: Float = 3f) : Term {
        override val isRestraint: Boolean get() = false
        override val bestEffort: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will prioritise building $construction (best effort)"
    }

    /** Diplomatic Victory vote trading: vote for [candidate] when the Diplomatic Victory vote is held. */
    data class VoteFor(val candidate: CivName) : Term {
        override val isRestraint: Boolean get() = false
        override val bestEffort: Boolean get() = true
        override fun describe(committer: CivName, beneficiary: CivName) =
            "$committer will vote for $candidate in the Diplomatic Victory vote"
    }
}

/** A predicate over game state that the engine can evaluate every turn. */
sealed interface Condition {
    fun describe(): String

    /** Replaces [SiblingHonored] references by real commitment ids once a deal is registered. */
    fun resolve(siblingIds: List<Int>): Condition = this

    /** This condition and all nested conditions, for validation. */
    fun flatten(): List<Condition> = listOf(this)

    data class ForceRatioAtLeast(val civ: CivName, val versus: CivName, val ratio: Float) : Condition {
        override fun describe() = "$civ's military strength is at least ${ratio.fmt()}x that of $versus"
    }

    data class AtWar(val a: CivName, val b: CivName, val expected: Boolean = true) : Condition {
        override fun describe() = if (expected) "$a is at war with $b" else "$a is not at war with $b"
    }

    data class RelationshipAtLeast(val from: CivName, val toward: CivName, val level: Relationship) : Condition {
        override fun describe() = "$from regards $toward as at least $level"
    }

    data class TurnAtLeast(val turn: Int) : Condition {
        override fun describe() = "turn $turn has been reached"
    }

    /** True while the referenced commitment is Active or Fulfilled (i.e. not broken or cancelled). */
    data class CommitmentHonored(val commitmentId: Int) : Condition {
        override fun describe() = "commitment #$commitmentId is being honoured"
    }

    /** Only valid inside a [DealDraft]: refers to another item of the same deal by position. */
    data class SiblingHonored(val index: Int) : Condition {
        override fun describe() = "the other side keeps its part of this deal (item ${index + 1})"
        override fun resolve(siblingIds: List<Int>): Condition = CommitmentHonored(siblingIds[index])
    }

    data class AllOf(val conditions: List<Condition>) : Condition {
        override fun describe() = conditions.joinToString(" and ") { it.describe() }
        override fun resolve(siblingIds: List<Int>): Condition = AllOf(conditions.map { it.resolve(siblingIds) })
        override fun flatten(): List<Condition> = listOf(this) + conditions.flatMap { it.flatten() }
    }
}

enum class CommitmentStatus {
    Proposed, Active, Fulfilled, Breached, Expired, ConditionNotMet, Cancelled;

    val isOpen: Boolean get() = this == Proposed || this == Active
}

/**
 * A commitment before it is registered. The window is inclusive: the commitment applies on turns
 * [activeFrom]..[endTurn]. Restraints are in force in that window; obligations are executed (or, for
 * human committers, monitored) in it.
 */
data class CommitmentDraft(
    val committer: CivName,
    val beneficiary: CivName,
    val term: Term,
    val condition: Condition? = null,
    val activeFrom: Int,
    val endTurn: Int
) {
    fun describe(): String {
        val core = term.describe(committer, beneficiary)
        val cond = condition?.let { " if ${it.describe()}" } ?: ""
        return "$core$cond (turns $activeFrom-$endTurn)"
    }
}

/** A registered commitment. Immutable; the manager replaces it with a modified copy on change. */
data class Commitment(
    val id: Int,
    val dealId: Int,
    val draft: CommitmentDraft,
    val status: CommitmentStatus = CommitmentStatus.Proposed,
    /** The committer (an AI) has decided to break this restraint; AI automation no longer treats it as forbidden. */
    val breachLicensed: Boolean = false,
    /** The condition has been true on at least one tick inside the window. */
    val conditionEverHeld: Boolean = false,
    val failedAttempts: Int = 0
) {
    val committer: CivName get() = draft.committer
    val beneficiary: CivName get() = draft.beneficiary
    val term: Term get() = draft.term
    val condition: Condition? get() = draft.condition
    val activeFrom: Int get() = draft.activeFrom
    val endTurn: Int get() = draft.endTurn

    fun describe(): String = draft.describe()
}

/** The items of a deal. Both parties must confirm before any item becomes active. */
data class DealDraft(
    val proposer: CivName,
    val counterparty: CivName,
    val items: List<CommitmentDraft>
)

enum class DealStatus { Proposed, Active, Rejected }

data class Deal(
    val id: Int,
    val draft: DealDraft,
    val commitmentIds: List<Int>,
    val proposedTurn: Int,
    val confirmedBy: Set<CivName>,
    val status: DealStatus
) {
    val proposer: CivName get() = draft.proposer
    val counterparty: CivName get() = draft.counterparty
}
