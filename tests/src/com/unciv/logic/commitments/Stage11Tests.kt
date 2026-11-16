package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ClassicItemKind
import com.unciv.logic.commitments.llm.HaggleDirection
import com.unciv.logic.commitments.llm.MultiAssetBarter
import com.unciv.logic.commitments.llm.ProposalAssessment
import com.unciv.logic.commitments.llm.ProposalParser
import com.unciv.logic.commitments.llm.TradableCity
import com.unciv.logic.commitments.llm.TradeIntentParser
import org.junit.Assert
import org.junit.Test

class Stage11Tests {

    @Test
    fun `cash-poor AI builds a multi-asset barter offer combining luxuries, strategics, gold and GPT for a player city`() {
        val offer = MultiAssetBarter.buildAiOfferForPlayerItems(
            targetValue = 850,
            aiGold = 76,
            aiGoldPerTurn = 57,
            aiLuxuriesForPlayer = listOf("Wine", "Cotton"),
            aiStrategicsForPlayer = mapOf("Horses" to 4)
        )
        Assert.assertTrue(offer.any { !it.fromPlayer && it.kind == ClassicItemKind.Luxury && it.name == "Wine" })
        Assert.assertTrue(offer.any { !it.fromPlayer && it.kind == ClassicItemKind.Luxury && it.name == "Cotton" })
        Assert.assertTrue(offer.any { !it.fromPlayer && it.kind == ClassicItemKind.Strategic && it.name == "Horses" })
        Assert.assertTrue(offer.any { !it.fromPlayer && it.kind == ClassicItemKind.Gold })
        Assert.assertTrue(offer.any { !it.fromPlayer && it.kind == ClassicItemKind.GoldPerTurn })
    }

    @Test
    fun `AI builds a multi-asset demand package from player luxuries, strategics, gold and GPT when asked for an AI city`() {
        val demand = MultiAssetBarter.buildAiDemandFromPlayer(
            requiredValue = 1100,
            playerGold = 400,
            playerGoldPerTurn = 30,
            playerLuxuriesWantedByAi = listOf("Dyes", "Sugar", "Porcelain"),
            playerStrategicsWantedByAi = mapOf("Horses" to 3)
        )
        Assert.assertNotNull(demand)
        Assert.assertTrue(demand!!.any { it.fromPlayer && it.kind == ClassicItemKind.Luxury && it.name == "Dyes" })
        Assert.assertTrue(demand.any { it.fromPlayer && it.kind == ClassicItemKind.Luxury && it.name == "Sugar" })
        Assert.assertTrue(demand.any { it.fromPlayer && it.kind == ClassicItemKind.Strategic && it.name == "Horses" })
        Assert.assertTrue(demand.any { it.fromPlayer && it.kind == ClassicItemKind.Gold })
    }

    @Test
    fun `commitment counteroffer uses hybrid lump gold plus gold per turn when player lump treasury is low`() {
        val f = Fixture()
        val evaluator = DealEvaluator({ item, _ ->
            when (item.term) {
                is Term.DeclareWar -> -180f
                is Term.PayGoldLump -> (item.term as Term.PayGoldLump).amount.toFloat()
                is Term.PayGoldPerTurn -> (item.term as Term.PayGoldPerTurn).amount * 14f
                else -> 0f
            }
        }, NegotiationDifficulty.Normal)

        val draft = ProposalParser.parse(
            text = "id like you to go to war with china. what can i do to make this idea good for you?",
            human = "Player", ai = "Arabia", knownThirdCivs = listOf("China"), knownConstructions = emptyList(), now = 10
        )!!

        val assessment = ProposalParser.evaluateProposal(
            draft, "Arabia", toneScore = 0f, CommitmentValidator(f.world), f.manager.allCommitments(), evaluator,
            playerTreasury = 60, playerGoldPerTurn = 25, now = 10
        )
        Assert.assertTrue("Expected Counteroffered but got $assessment", assessment is ProposalAssessment.Counteroffered)
        val counter = assessment as ProposalAssessment.Counteroffered
        Assert.assertTrue(counter.counterDraft.items.any { it.term is Term.PayGoldLump })
        Assert.assertTrue(counter.counterDraft.items.any { it.term is Term.PayGoldPerTurn })
    }

    @Test
    fun `haggling for resources or more triggers MoreFromAi direction`() {
        val playerCities = listOf(TradableCity("lampang-id", "Lampang"))
        val initial = TradeIntentParser.parseClassicTrade(
            text = "I will give you Lampang for 300 gold",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = emptyList(), aiLuxuries = listOf("Cotton"),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 350, playerGold = 500
        )!!

        val throwInResources = TradeIntentParser.parseClassicTrade(
            text = "Throw in some resources or a luxury too!",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = emptyList(), aiLuxuries = listOf("Cotton"),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 350, playerGold = 500,
            previousDraft = initial
        )
        Assert.assertNotNull(throwInResources)
        Assert.assertEquals(HaggleDirection.MoreFromAi, throwInResources!!.haggleDirection)
    }
}
