package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.LlmBackend
import com.unciv.logic.commitments.llm.LlmRequest
import com.unciv.logic.commitments.llm.LlmResponse

class FakeWorld : CommitmentWorld {
    override var currentTurn = 10
    val aiCivs = mutableSetOf<CivName>()
    val wars = mutableSetOf<Set<CivName>>()
    val pacts = mutableSetOf<Set<CivName>>()
    val forces = mutableMapOf<CivName, Float>()
    val golds = mutableMapOf<CivName, Int>()
    val relationships = mutableMapOf<Pair<CivName, CivName>, Relationship>()
    val observers = mutableMapOf<Set<CivName>, List<CivName>>()

    override fun isAI(civ: CivName) = civ in aiCivs
    override fun isAtWar(a: CivName, b: CivName) = setOf(a, b) in wars
    override fun hasDefensivePact(a: CivName, b: CivName) = setOf(a, b) in pacts
    override fun force(civ: CivName) = forces[civ] ?: 100f
    override fun relationship(from: CivName, toward: CivName) = relationships[from to toward] ?: Relationship.Neutral
    override fun gold(civ: CivName) = golds[civ] ?: 0
    override fun civsKnowingBoth(a: CivName, b: CivName) = observers[setOf(a, b)] ?: emptyList()

    fun makeWar(a: CivName, b: CivName) { wars += setOf(a, b) }
}

class FakeActions(private val world: FakeWorld) : CommitmentActions {
    data class Opinion(val who: CivName, val about: CivName, val delta: Float)

    var declareWarSucceeds = true
    val declaredWars = mutableListOf<Pair<CivName, CivName>>()
    val breachPenalties = mutableListOf<Opinion>()
    val gossipPenalties = mutableListOf<Opinion>()
    val bonuses = mutableListOf<Opinion>()

    override fun declareWar(civ: CivName, target: CivName): Boolean {
        if (!declareWarSucceeds) return false
        world.makeWar(civ, target)
        declaredWars += civ to target
        return true
    }

    override fun transferGold(from: CivName, to: CivName, amount: Int): Boolean {
        if (world.gold(from) < amount) return false
        world.golds[from] = world.gold(from) - amount
        world.golds[to] = world.gold(to) + amount
        return true
    }

    override fun applyBreachPenalty(victim: CivName, offender: CivName, delta: Float) { breachPenalties += Opinion(victim, offender, delta) }
    override fun applyGossipPenalty(observer: CivName, offender: CivName, delta: Float) { gossipPenalties += Opinion(observer, offender, delta) }
    override fun applyFulfilmentBonus(beneficiary: CivName, committer: CivName, delta: Float) { bonuses += Opinion(beneficiary, committer, delta) }
}

/** Returns canned replies in order and records what it was asked. */
class ScriptedLlmBackend(private val replies: List<String>) : LlmBackend {
    val requests = mutableListOf<LlmRequest>()
    private var next = 0
    override val isAvailable = true
    override fun generate(request: LlmRequest): LlmResponse {
        requests += request
        return LlmResponse(replies[next++ % replies.size])
    }
}

/** Rome, Greece and Persia are AI civs; "Player" is the human. Turn 10; everyone has 1000 gold. */
class Fixture(
    difficulty: NegotiationDifficulty = NegotiationDifficulty.Normal,
    oracle: BetrayalOracle? = null,
    noise: () -> Float = { 0.5f }
) {
    val world = FakeWorld().apply {
        aiCivs += listOf("Rome", "Greece", "Persia")
        for (civ in listOf("Player", "Rome", "Greece", "Persia")) golds[civ] = 1000
        observers[setOf("Rome", "Player")] = listOf("Greece")
    }
    val actions = FakeActions(world)
    val manager = CommitmentManager(world, actions, difficulty = difficulty, betrayalOracle = oracle, noise = noise)

    /** Proposes [draft] and has the counterparty confirm it; returns the ids of its commitments. */
    fun activate(draft: DealDraft): List<Int> {
        val result = manager.propose(draft)
        check(result is ProposalResult.Proposed) { "proposal rejected: $result" }
        check(manager.accept(result.dealId, draft.counterparty) == AcceptResult.Activated)
        return manager.deal(result.dealId)!!.commitmentIds
    }

    /** Advances the world one turn at a time, ticking the manager each turn. */
    fun advance(turns: Int = 1) = repeat(turns) {
        world.currentTurn++
        manager.tick()
    }

    fun status(id: Int) = manager.commitment(id)!!.status
}
