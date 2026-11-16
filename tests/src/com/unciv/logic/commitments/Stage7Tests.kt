package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ClaimGuard
import com.unciv.logic.commitments.llm.ClassicItemKind
import com.unciv.logic.commitments.llm.Fact
import com.unciv.logic.commitments.llm.IntelCheck
import com.unciv.logic.commitments.llm.IntelKind
import com.unciv.logic.commitments.llm.IntelOutcome
import com.unciv.logic.commitments.llm.IntelligenceEngine
import com.unciv.logic.commitments.llm.NationSheet
import com.unciv.logic.commitments.llm.NumberWords
import com.unciv.logic.commitments.llm.ProposalAssessment
import com.unciv.logic.commitments.llm.ProposalParser
import com.unciv.logic.commitments.llm.Topic
import com.unciv.logic.commitments.llm.TradableCity
import com.unciv.logic.commitments.llm.TradeIntentParser
import org.junit.Assert
import org.junit.Test

class Stage7Tests {

    private val playerCities = listOf(TradableCity(id = "samoa-id", name = "Samoa"))

    @Test
    fun `number words and screenshot bargaining sequences parse accurately`() {
        Assert.assertEquals("i give you one of my cities if you give me 1 gold", NumberWords.normalize("i give you one of my cities if you give me one gold"))
        Assert.assertEquals("Pay 525 gold over 25 turns", NumberWords.normalize("Pay five hundred and twenty-five gold over twenty-five turns"))

        // 1. "I have a city Samoa. would you be interested?" -> Open valuation inquiry for Samoa
        val inquiry = TradeIntentParser.parseClassicTrade(
            text = "I have a city Samoa. would you be interested?",
            playerCiv = "Polynesia", aiCiv = "Japan",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 503
        )
        Assert.assertNotNull(inquiry)
        Assert.assertTrue(inquiry!!.isOpenInquiry)
        Assert.assertEquals(ClassicItemKind.City, inquiry.items.single().kind)
        Assert.assertEquals("Samoa", inquiry.items.single().name)

        // 2. "Samoa for 1 gold" -> Polynesia gives Samoa, Japan gives 1 Gold
        val samoaFor1 = TradeIntentParser.parseClassicTrade(
            text = "Samoa for 1 gold",
            playerCiv = "Polynesia", aiCiv = "Japan",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 503
        )
        Assert.assertNotNull(samoaFor1)
        Assert.assertTrue(samoaFor1!!.items.any { it.fromPlayer && it.kind == ClassicItemKind.City && it.name == "Samoa" })
        Assert.assertTrue(samoaFor1.items.any { !it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 1 })

        // 3. "i give you one of my cities if you give me one gold" -> Polynesia gives Samoa, Japan gives 1 Gold
        val oneGoldWord = TradeIntentParser.parseClassicTrade(
            text = "i give you one of my cities if you give me one gold",
            playerCiv = "Polynesia", aiCiv = "Japan",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 503
        )
        Assert.assertNotNull(oneGoldWord)
        Assert.assertTrue(oneGoldWord!!.items.any { it.fromPlayer && it.kind == ClassicItemKind.City && it.name == "Samoa" })
        Assert.assertTrue(oneGoldWord.items.any { !it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 1 })

        // 4. Multi-turn haggling: "then i will give you my city if you give me 5 gold" -> "actually you give me 500 gold"
        val firstOffer = TradeIntentParser.parseClassicTrade(
            text = "then i will give you my city if you give me 5 gold",
            playerCiv = "Polynesia", aiCiv = "Japan",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 503
        )
        Assert.assertNotNull(firstOffer)

        val followUp500 = TradeIntentParser.parseClassicTrade(
            text = "actually you give me 500 gold",
            playerCiv = "Polynesia", aiCiv = "Japan",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 503,
            previousDraft = firstOffer
        )
        Assert.assertNotNull(followUp500)
        Assert.assertTrue(followUp500!!.items.any { it.fromPlayer && it.kind == ClassicItemKind.City && it.name == "Samoa" })
        Assert.assertTrue(followUp500.items.any { !it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 500 })
    }

    @Test
    fun `claim guard catches do not possess any gold when player only asked for one gold`() {
        val sheet = NationSheet(
            facts = listOf(Fact(Topic.Economy, "You have 503 gold, and your income is +3 gold per turn.", core = true)),
            gold = 503, cityCount = 1
        )
        val violations = ClaimGuard.violations(
            "I do not possess any gold to offer you.",
            sheet,
            allowedText = listOf("i give you one of my cities if you give me one gold")
        )
        Assert.assertTrue("Should catch denying gold when only 1 gold was asked", violations.isNotEmpty())
    }

    @Test
    fun `declined commitment proposal generates a concrete counteroffer when player can afford the shortfall`() {
        val f = Fixture()
        val evaluator = DealEvaluator({ item, _ ->
            when (item.term) {
                Term.NoSettleNear -> -45f
                is Term.PayGoldLump -> (item.term as Term.PayGoldLump).amount.toFloat()
                else -> 0f
            }
        }, NegotiationDifficulty.Normal)

        val draft = DealBuilders.noSettleNear("Rome", "Player", turns = 20, now = 10)
        val assessment = ProposalParser.evaluateProposal(
            draft, "Rome", toneScore = 0f, CommitmentValidator(f.world), f.manager.allCommitments(), evaluator,
            playerTreasury = 500, now = 10
        )
        Assert.assertTrue("Expected Counteroffered but got $assessment", assessment is ProposalAssessment.Counteroffered)
        val counter = assessment as ProposalAssessment.Counteroffered
        Assert.assertEquals(45, counter.goldNeeded)
        Assert.assertTrue(counter.counterDraft.items.any { it.term == Term.PayGoldLump(45) })
    }

    @Test
    fun `trade embargo commitment is parsed, encoded, enforced and breached on trade`() {
        val f = Fixture()
        val draft = ProposalParser.parse(
            "Stop trading with Greece for 20 turns and I will give you 50 gold",
            human = "Player", ai = "Rome", knownThirdCivs = listOf("Greece"), knownConstructions = emptyList(), now = 10
        )
        Assert.assertNotNull(draft)
        Assert.assertEquals(Term.Embargo("Greece"), draft!!.items[0].term)

        val id = f.activate(draft).first()
        Assert.assertTrue(f.manager.isForbidden("Rome", AiAction.TradeWith("Greece")))
        Assert.assertFalse(f.manager.isForbidden("Rome", AiAction.TradeWith("Persia")))

        // Round-trip through snapshot codec
        val encoded = CommitmentSnapshotCodec.encode(f.manager.snapshot())
        val decoded = CommitmentSnapshotCodec.decode(encoded)
        Assert.assertEquals(Term.Embargo("Greece"), decoded.commitments.first().term)

        // Trading with Greece violates the embargo
        f.manager.onEvent(WorldEvent.TradeCompleted("Rome", "Greece"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(id))
    }

    @Test
    fun `intelligence engine verifies true intel, believes trusted bluffs under fog of war, and calls out lies`() {
        // 1. True military warning
        val trueReport = IntelligenceEngine.evaluate(
            "Be careful, Greece is massing troops and planning to attack you!",
            ai = "Rome", knownThirdCivs = listOf("Greece")
        ) { _, _, _ -> IntelCheck(isActuallyTrue = true, aiCanVerifyDirectly = true, aiTrustsPlayer = false, truthSummary = "Greece has a large army") }
        Assert.assertNotNull(trueReport)
        Assert.assertEquals(IntelOutcome.VerifiedTrue, trueReport!!.outcome)
        Assert.assertEquals(3f, trueReport.opinionDelta, 0.001f)

        // 2. False warning, but hidden by fog of war and AI trusts the player -> BluffBelieved
        val believedBluff = IntelligenceEngine.evaluate(
            "Greece is preparing for war and massing troops near your border!",
            ai = "Rome", knownThirdCivs = listOf("Greece")
        ) { _, _, _ -> IntelCheck(isActuallyTrue = false, aiCanVerifyDirectly = false, aiTrustsPlayer = true, truthSummary = "Greece has 0 units") }
        Assert.assertNotNull(believedBluff)
        Assert.assertEquals(IntelOutcome.BluffBelieved, believedBluff!!.outcome)
        Assert.assertEquals(2f, believedBluff.opinionDelta, 0.001f)

        // 3. False warning and AI can see Greece (or does not trust player) -> BluffCalledOut
        val caughtLie = IntelligenceEngine.evaluate(
            "Greece is close to a science victory and about to win!",
            ai = "Rome", knownThirdCivs = listOf("Greece")
        ) { _, _, _ -> IntelCheck(isActuallyTrue = false, aiCanVerifyDirectly = true, aiTrustsPlayer = false, truthSummary = "Greece trails in science") }
        Assert.assertNotNull(caughtLie)
        Assert.assertEquals(IntelOutcome.BluffCalledOut, caughtLie!!.outcome)
        Assert.assertEquals(-6f, caughtLie.opinionDelta, 0.001f)
    }
}
