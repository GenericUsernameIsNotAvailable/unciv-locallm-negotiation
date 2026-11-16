package com.unciv.logic.commitments

/**
 * All numbers here are PLACEHOLDERS chosen to mirror existing game values where one exists
 * (e.g. the -20 / -10 betrayal penalties in NextTurnAutomation.onDemandViolation). They have not been
 * playtested and are expected to change.
 */
data class BreachPolicy(
    /** Victim's opinion change for a breach. Mirrors addDiminishingModifier(BetrayedPromise..., -20f). */
    val relationshipPenalty: Float = 20f,
    /** Opinion change for each third party that knows both. Mirrors BetrayedPromiseToNotAttackOtherCiv (-10). */
    val gossipPenalty: Float = 10f,
    val fulfilmentBonus: Float = 5f,
    /** Breaches the committer could not help (could not afford a payment, action refused) hurt less. */
    val involuntaryFactor: Float = 0.5f,
    /** An AI reminds a human this many turns before an obligation falls due. */
    val reminderLeadTurns: Int = 2,
    /** Unconfirmed proposals lapse after this many turns. */
    val proposalTtlTurns: Int = 3,
    /** Tone can shift a deal's valuation by at most this much either way. */
    val maxToneInfluence: Float = 15f,
    /** Only this many of the newest decision-log entries are kept when the state is saved. */
    val maxSavedLogEntries: Int = 200
)

/**
 * Negotiation skill as the difficulty axis, instead of production or tech bonuses.
 * - [loyaltyFloor]: minimum loyalty (0..1) an AI leader acts with. HIGH floor = reliable AI = easier for the player.
 * - [bargainingMargin]: surplus (in valuation units) a deal must give the AI before it accepts. HIGH = stingier AI.
 */
data class NegotiationDifficulty(val loyaltyFloor: Float, val bargainingMargin: Float) {
    companion object {
        val Easy = NegotiationDifficulty(loyaltyFloor = 0.8f, bargainingMargin = -5f)
        val Normal = NegotiationDifficulty(loyaltyFloor = 0.4f, bargainingMargin = 0f)
        val Hard = NegotiationDifficulty(loyaltyFloor = 0f, bargainingMargin = 10f)
    }
}

/**
 * The engine's estimate of how tempting breaking a commitment is for an AI right now.
 * [benefit] is on the same scale as war motivation / trade valuation; [loyalty] is the leader's
 * trait normalised to 0..1 (Personality values run 0..10, so divide by 10).
 */
data class BetrayalAssessment(val benefit: Float, val loyalty: Float)

/** Supplied by the game adapter; null means no opportunity to betray this commitment this turn. */
fun interface BetrayalOracle {
    fun assess(commitment: Commitment): BetrayalAssessment?
}

data class BetrayalDecision(val betray: Boolean, val margin: Float, val threshold: Float, val loyalty: Float)

class BetrayalEvaluator(
    private val baseThreshold: Float = 10f,
    private val loyaltyWeight: Float = 40f,
    private val noiseSpan: Float = 10f
) {
    /**
     * Betray when (benefit - reputationCost) clears a threshold that grows with loyalty.
     * [noise] is a value in 0..1 from a seeded game RNG, so saves stay reproducible.
     */
    fun decide(
        assessment: BetrayalAssessment,
        reputationCost: Float,
        difficulty: NegotiationDifficulty,
        noise: Float
    ): BetrayalDecision {
        val loyalty = maxOf(assessment.loyalty.coerceIn(0f, 1f), difficulty.loyaltyFloor)
        val threshold = baseThreshold + loyalty * loyaltyWeight + (noise - 0.5f) * noiseSpan
        val margin = assessment.benefit - reputationCost
        return BetrayalDecision(margin > threshold, margin, threshold, loyalty)
    }
}
