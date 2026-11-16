package com.unciv.logic.commitments

enum class DecisionKind {
    DealProposed, DealActivated, DealRejected,
    CommitmentActivated, CommitmentFulfilled, CommitmentBreached, CommitmentExpired, ConditionNotMet,
    BetrayalDecided, ReminderSent
}

enum class ReasonCode {
    ProposalExpired, NoLongerValid, DeclinedByParty,
    ConditionNotMet, HeldThroughWindow, CompletedAsPromised, ObligationExecuted, AlreadyAtWar,
    ViolatedByEvent, DeliberateBreach, InsufficientGold, ActionRefused, DeadlinePassed, BestEffortNotCompleted,
    BetrayalBenefitExceedsCost, ReminderLeadTime
}

data class Reason(val code: ReasonCode, val values: Map<String, Float> = emptyMap())

/**
 * One decision the engine made, with structured reasons. The leader's in-character explanation of
 * a betrayal, refusal or penalty is generated FROM these entries, so it describes what really happened.
 */
data class DecisionLogEntry(
    val turn: Int,
    val kind: DecisionKind,
    val actor: CivName,
    val other: CivName,
    val commitmentId: Int?,
    val reasons: List<Reason>
)

/** Append-only. */
class DecisionLog {
    private val items = ArrayList<DecisionLogEntry>()
    val entries: List<DecisionLogEntry> get() = items
    val size: Int get() = items.size

    fun add(entry: DecisionLogEntry) { items += entry }

    /** Used when restoring a saved state. */
    fun replaceWith(entries: List<DecisionLogEntry>) {
        items.clear()
        items.addAll(entries)
    }

    /** Entries that happened between exactly these two civs, oldest first. */
    fun between(a: CivName, b: CivName): List<DecisionLogEntry> =
        items.filter { (it.actor == a && it.other == b) || (it.actor == b && it.other == a) }
}

enum class OutboxKind {
    /** An AI reminds a human that an obligation is about to fall due. */
    ReminderToPlayer,
    /** An AI tells a human that they broke a commitment. */
    PlayerBrokeCommitment,
    /** An AI tells a human that it deliberately broke a commitment. */
    AiBetrayal,
    /** An AI tells a human that it failed a commitment through no choice of its own. */
    AiCouldNotKeepCommitment
}

/**
 * A structured "the leader has something to say" event. The chat layer renders it in character
 * using the decision log; nothing here is free text.
 */
data class OutboxMessage(
    val turn: Int,
    val from: CivName,
    val to: CivName,
    val kind: OutboxKind,
    val commitmentId: Int
)

data class TickReport(val log: List<DecisionLogEntry>, val outbox: List<OutboxMessage>)
