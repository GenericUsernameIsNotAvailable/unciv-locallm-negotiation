package com.unciv.logic.negotiation

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.civilization.DeclareWarPlanEvaluator
import com.unciv.logic.automation.civilization.MotivationToAttackAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.commitments.CivName
import com.unciv.logic.commitments.CommitmentActions
import com.unciv.logic.commitments.CommitmentWorld
import com.unciv.logic.commitments.Relationship
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.commitments.ValuationSupport
import com.unciv.logic.commitments.llm.IntelCheck
import com.unciv.logic.commitments.llm.IntelKind
import com.unciv.logic.commitments.llm.IntelligenceInspector
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.view.GameView

/** Reads game state for the commitment engine. All civ names here are civIDs. */
internal class UncivCommitmentWorld(private val gameInfo: GameInfo) : CommitmentWorld {
    private fun civOf(id: CivName): Civilization = gameInfo.getCivilization(id)

    override val currentTurn: Int get() = gameInfo.turns

    override fun isAI(civ: CivName): Boolean = civOf(civ).isAI()

    override fun isAtWar(a: CivName, b: CivName): Boolean = civOf(a).isAtWarWith(civOf(b))

    override fun hasDefensivePact(a: CivName, b: CivName): Boolean =
        civOf(a).getDiplomacyManager(b)?.diplomaticStatus == DiplomaticStatus.DefensivePact

    /** The same measure the AI's own war evaluation uses. */
    override fun force(civ: CivName): Float = civOf(civ).getStatForRanking(RankingType.Force).toFloat()

    /** How [from] regards [toward]: a civ's DiplomacyManager holds that civ's own opinion of the other. */
    override fun relationship(from: CivName, toward: CivName): Relationship {
        val level = civOf(from).getDiplomacyManager(toward)?.relationshipLevel() ?: return Relationship.Neutral
        return Relationship.valueOf(level.name)
    }

    override fun gold(civ: CivName): Int = civOf(civ).gold

    override fun civsKnowingBoth(a: CivName, b: CivName): List<CivName> =
        civOf(a).getDiplomacyManager(b)?.getCommonKnownCivs()
            ?.filter { it.isMajorCiv && it.isAlive() }
            ?.map { it.civID }
            ?: emptyList()
}

/** Supplies live Unciv war, peace and personality evaluations to [com.unciv.logic.commitments.StandardTermValuator] and [com.unciv.logic.commitments.StandardBetrayalOracle]. */
internal class UncivValuationSupport(private val gameInfo: GameInfo) : ValuationSupport {
    private fun civOf(id: CivName): Civilization = gameInfo.getCivilization(id)

    override fun gold(civ: CivName): Int = civOf(civ).gold

    override fun goldPerTurn(civ: CivName): Int = civOf(civ).stats.statsForNextTurn.gold.toInt()

    override fun isAtWar(a: CivName, b: CivName): Boolean = civOf(a).isAtWarWith(civOf(b))

    override fun relationship(from: CivName, toward: CivName): Relationship {
        val level = civOf(from).getDiplomacyManager(toward)?.relationshipLevel() ?: return Relationship.Neutral
        return Relationship.valueOf(level.name)
    }

    override fun trait(civ: CivName, name: String): Float {
        val pv = PersonalityValue.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return 5f
        return civOf(civ).getPersonality()[pv]
    }

    override fun motivationToAttack(attacker: CivName, target: CivName): Float {
        val a = civOf(attacker)
        val t = civOf(target)
        return MotivationToAttackAutomation.hasAtLeastMotivationToAttack(a, GameView(gameInfo, a).getForeignCivView(t), -50f)
    }

    override fun warPlanMotivation(ai: CivName, target: CivName, partner: CivName): Float {
        val aiCiv = civOf(ai)
        val targetCiv = civOf(target)
        val partnerCiv = civOf(partner)
        return if (partnerCiv.isAtWarWith(targetCiv))
            DeclareWarPlanEvaluator.evaluateJoinWarPlan(aiCiv, targetCiv, partnerCiv, null)
        else
            DeclareWarPlanEvaluator.evaluateDeclareWarPlan(aiCiv, targetCiv, null)
    }

    override fun peaceCostForThem(ai: CivName, enemy: CivName): Int =
        TradeEvaluation().evaluatePeaceCostForThem(civOf(ai), civOf(enemy))
}

/** Changes game state on behalf of the commitment engine. All civ names here are civIDs. */
internal class UncivCommitmentActions(private val gameInfo: GameInfo) : CommitmentActions {
    private fun civOf(id: CivName): Civilization = gameInfo.getCivilization(id)

    override fun declareWar(civ: CivName, target: CivName): Boolean {
        val attacker = civOf(civ)
        val defender = civOf(target)
        if (attacker.isAtWarWith(defender)) return true
        val diplomacy = attacker.getDiplomacyManager(defender) ?: return false
        if (!diplomacy.canDeclareWar()) return false
        diplomacy.declareWar()
        return attacker.isAtWarWith(defender)
    }

    override fun transferGold(from: CivName, to: CivName, amount: Int): Boolean {
        val payer = civOf(from)
        if (payer.gold < amount) return false
        payer.addGold(-amount)
        civOf(to).addGold(amount)
        return true
    }

    // Diminishing, so repeated breaches (or repeated small kept promises) cannot run away.
    // The opinion that matters is the AI civ's own, held in its DiplomacyManager towards the other civ.

    override fun applyBreachPenalty(victim: CivName, offender: CivName, delta: Float) {
        civOf(victim).getDiplomacyManager(offender)?.addDiminishingModifier(DiplomaticModifiers.BrokeCommitment, delta)
    }

    override fun applyGossipPenalty(observer: CivName, offender: CivName, delta: Float) {
        civOf(observer).getDiplomacyManager(offender)?.addDiminishingModifier(DiplomaticModifiers.HeardAboutBrokenCommitment, delta)
    }

    override fun applyFulfilmentBonus(beneficiary: CivName, committer: CivName, delta: Float) {
        civOf(beneficiary).getDiplomacyManager(committer)?.addDiminishingModifier(DiplomaticModifiers.KeptCommitment, delta)
    }
}

/** Cross-references player intelligence claims against the game state and fog of war. */
internal class UncivIntelligenceInspector(
    private val gameInfo: GameInfo,
    private val human: Civilization
) : IntelligenceInspector {
    private fun civOf(id: CivName): Civilization = gameInfo.getCivilization(id)

    override fun inspect(ai: CivName, target: CivName, kind: IntelKind): IntelCheck {
        val aiCiv = civOf(ai)
        val targetCiv = civOf(target)
        val aiDiploWithHuman = aiCiv.getDiplomacyManager(human)
        val aiTrustsPlayer = aiDiploWithHuman?.isRelationshipLevelGE(RelationshipLevel.Friend) == true
        val aiCanSeeTargetCities = targetCiv.cities.isNotEmpty() && targetCiv.cities.all { aiCiv.hasExplored(it.getCenterTile()) }

        return when (kind) {
            IntelKind.MilitaryThreat -> {
                val aiForce = aiCiv.getStatForRanking(RankingType.Force).coerceAtLeast(1)
                val targetForce = targetCiv.getStatForRanking(RankingType.Force)
                val targetWaryOrAtWar = targetCiv.isAtWarWith(aiCiv) ||
                    targetCiv.getDiplomacyManager(aiCiv)?.let { it.hasFlag(DiplomacyFlags.WaryOf) && it.getFlag(DiplomacyFlags.WaryOf) < 0 } == true
                val isTrue = targetWaryOrAtWar || targetForce >= aiForce * 0.85f
                val summary = if (isTrue) "$target has significant military force ($targetForce vs your $aiForce)"
                else "$target's military force ($targetForce) is smaller than yours ($aiForce) and shows no invasion preparation"
                IntelCheck(isTrue, aiCanSeeTargetCities, aiTrustsPlayer, summary)
            }
            IntelKind.VictoryLead -> {
                val aiScore = aiCiv.getStatForRanking(RankingType.Score).coerceAtLeast(1)
                val targetScore = targetCiv.getStatForRanking(RankingType.Score)
                val isTrue = targetScore >= aiScore * 1.1f || targetCiv.tech.techsResearched.size > aiCiv.tech.techsResearched.size + 2
                val summary = if (isTrue) "$target leads in score or technology ($targetScore vs your $aiScore)"
                else "$target does not hold a commanding lead ($targetScore vs your $aiScore)"
                IntelCheck(isTrue, aiCanVerifyDirectly = true, aiTrustsPlayer = aiTrustsPlayer, truthSummary = summary)
            }
        }
    }
}
