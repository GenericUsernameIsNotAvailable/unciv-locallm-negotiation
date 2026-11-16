package com.unciv.logic.commitments

/**
 * Read-only view of the game that the commitment engine needs.
 * The real implementation adapts `Civilization`, `DiplomacyManager` etc.; tests use a fake.
 */
interface CommitmentWorld {
    val currentTurn: Int
    fun isAI(civ: CivName): Boolean
    fun isAtWar(a: CivName, b: CivName): Boolean
    fun hasDefensivePact(a: CivName, b: CivName): Boolean

    /** Military strength on the same scale the AI's own war evaluation uses (RankingType.Force). */
    fun force(civ: CivName): Float
    fun relationship(from: CivName, toward: CivName): Relationship
    fun gold(civ: CivName): Int

    /** Civs other than [a] and [b] that know both of them: they gossip about breaches. */
    fun civsKnowingBoth(a: CivName, b: CivName): List<CivName>
}

/** Everything the commitment engine can make happen in the game. */
interface CommitmentActions {
    /** @return true if the two civs are at war afterwards. Must go through the normal declare-war path. */
    fun declareWar(civ: CivName, target: CivName): Boolean

    /** @return false (and move nothing) if [from] cannot afford [amount]. */
    fun transferGold(from: CivName, to: CivName, amount: Int): Boolean

    /** [victim]'s opinion of [offender] changes by [delta] (negative for breaches). */
    fun applyBreachPenalty(victim: CivName, offender: CivName, delta: Float)

    /** Third-party [observer]'s opinion of [offender] changes by [delta] because word got around. */
    fun applyGossipPenalty(observer: CivName, offender: CivName, delta: Float)

    /** [beneficiary]'s opinion of [committer] improves by [delta] for a kept commitment. Adapters should diminish repeats. */
    fun applyFulfilmentBonus(beneficiary: CivName, committer: CivName, delta: Float)
}

/** Things that happen in the game that commitments care about; the adapter feeds these in. */
sealed interface WorldEvent {
    data class CitySettledNear(val settler: CivName, val nearCiv: CivName) : WorldEvent
    data class WarDeclared(val attacker: CivName, val defender: CivName) : WorldEvent
    data class PeaceMade(val a: CivName, val b: CivName) : WorldEvent
    data class ConstructionCompleted(val civ: CivName, val construction: String) : WorldEvent
    data class TradeCompleted(val a: CivName, val b: CivName) : WorldEvent
    data class BorderZoneViolated(val violator: CivName, val victim: CivName) : WorldEvent
    data class CityStateBullied(val bully: CivName, val cityState: CivName) : WorldEvent
    data class DiplomaticVoteCast(val voter: CivName, val candidate: CivName?) : WorldEvent
}

/** Things an AI is about to do, which the AI automation asks the commitment engine to approve. */
sealed interface AiAction {
    data class SettleNear(val nearCiv: CivName) : AiAction
    data class DeclareWar(val target: CivName) : AiAction
    data class MakePeace(val with: CivName) : AiAction
    data class TradeWith(val partner: CivName) : AiAction
    data class BullyCityState(val cityState: CivName) : AiAction
    data class MassTroopsNear(val nearCiv: CivName) : AiAction
}
