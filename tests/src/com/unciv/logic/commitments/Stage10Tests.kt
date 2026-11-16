package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ProposalAssessment
import com.unciv.logic.commitments.llm.ProposalParser
import org.junit.Assert
import org.junit.Test

class Stage10Tests {

    @Test
    fun `strategic ai director evaluates postures and adjusts construction and tech weights`() {
        val base = CivStrategicState(
            civName = "China",
            atWar = false,
            ownForce = 200f,
            enemyForce = 0f,
            happiness = 10,
            gold = 300,
            goldPerTurn = 15,
            cityCount = 4,
            militaryUnitCount = 5,
            preferredVictory = "Scientific"
        )

        // 1. Peaceful scientific empire -> SciencePush
        Assert.assertEquals(StrategicPosture.SciencePush, StrategicAiDirector.evaluatePosture(base))
        Assert.assertTrue(StrategicAiDirector.constructionMultiplier(StrategicPosture.SciencePush, ConstructionCategory.ScienceBuilding) > 1.5f)
        Assert.assertTrue(StrategicAiDirector.techPriorityMultiplier(StrategicPosture.SciencePush, false, unlocksScience = true, false, false, false) >= 2.0f)

        // 2. Outnumbered at war -> WarDefense
        val warDef = base.copy(atWar = true, ownForce = 100f, enemyForce = 250f)
        Assert.assertEquals(StrategicPosture.WarDefense, StrategicAiDirector.evaluatePosture(warDef))
        Assert.assertTrue(StrategicAiDirector.constructionMultiplier(StrategicPosture.WarDefense, ConstructionCategory.MilitaryUnit) >= 2.0f)
        Assert.assertTrue(StrategicAiDirector.constructionMultiplier(StrategicPosture.WarDefense, ConstructionCategory.DefenseBuilding) >= 1.8f)

        // 3. Unhappy empire -> HappinessRecovery
        val unhappy = base.copy(happiness = -3)
        Assert.assertEquals(StrategicPosture.HappinessRecovery, StrategicAiDirector.evaluatePosture(unhappy))
        Assert.assertTrue(StrategicAiDirector.constructionMultiplier(StrategicPosture.HappinessRecovery, ConstructionCategory.HappinessBuilding) >= 1.9f)

        // 4. Broke empire -> EconomyRecovery
        val broke = base.copy(gold = 10, goldPerTurn = -4)
        Assert.assertEquals(StrategicPosture.EconomyRecovery, StrategicAiDirector.evaluatePosture(broke))
        Assert.assertTrue(StrategicAiDirector.constructionMultiplier(StrategicPosture.EconomyRecovery, ConstructionCategory.GoldBuilding) >= 1.9f)
    }

    @Test
    fun `proactive diplomacy proposes war coalitions, war loans, and non aggression pacts with cooldown`() {
        // 1. AI at war with Persia and has 300 gold -> offers player a 100-gold bounty to join the war
        val warBounty = ProactiveDiplomacy.considerProposal(
            aiCiv = "China",
            aiLeaderName = "Wu Zetian",
            humanCiv = "Siam",
            currentTurn = 20,
            lastProposalTurn = null,
            relationship = Relationship.Neutral,
            atWarWithHuman = false,
            commonEnemiesOfAi = listOf("Persia"),
            aiGold = 300,
            aiGoldPerTurn = 12,
            existingOpenCommitments = emptyList()
        )
        Assert.assertNotNull(warBounty)
        Assert.assertTrue(warBounty!!.draft.items.any { it.term == Term.DeclareWar("Persia") })
        Assert.assertTrue(warBounty.draft.items.any { it.term == Term.PayGoldLump(100) })

        // 2. Cooldown prevents spamming another proposal on the next turn
        val blockedByCooldown = ProactiveDiplomacy.considerProposal(
            aiCiv = "China",
            aiLeaderName = "Wu Zetian",
            humanCiv = "Siam",
            currentTurn = 22,
            lastProposalTurn = 20,
            relationship = Relationship.Friend,
            atWarWithHuman = false,
            commonEnemiesOfAi = emptyList(),
            aiGold = 300,
            aiGoldPerTurn = 12,
            existingOpenCommitments = emptyList()
        )
        Assert.assertNull(blockedByCooldown)

        // 3. Friendly AI at peace proposes a 20-turn Non-Aggression Pact
        val peacePact = ProactiveDiplomacy.considerProposal(
            aiCiv = "China",
            aiLeaderName = "Wu Zetian",
            humanCiv = "Siam",
            currentTurn = 40,
            lastProposalTurn = 20,
            relationship = Relationship.Favorable,
            atWarWithHuman = false,
            commonEnemiesOfAi = emptyList(),
            aiGold = 300,
            aiGoldPerTurn = 12,
            existingOpenCommitments = emptyList()
        )
        Assert.assertNotNull(peacePact)
        Assert.assertEquals(2, peacePact!!.draft.items.count { it.term == Term.NoAttack })

        // 4. "id like you to go to war with china. what can i do to make this idea good for you?" -> Counteroffered war deal
        val warAsk = ProposalParser.parse(
            text = "id like you to go to war with china. what can i do to make this idea good for you?",
            human = "Siam", ai = "Arabia", knownThirdCivs = listOf("China"), knownConstructions = emptyList(), now = 10
        )
        Assert.assertNotNull(warAsk)
        Assert.assertEquals(Term.DeclareWar("China"), warAsk!!.items.single().term)
    }
}
