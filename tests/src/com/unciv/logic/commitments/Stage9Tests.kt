package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ClassicItemKind
import com.unciv.logic.commitments.llm.HaggleDirection
import com.unciv.logic.commitments.llm.LeaderProfile
import com.unciv.logic.commitments.llm.ManipulationTracker
import com.unciv.logic.commitments.llm.ProposalParser
import com.unciv.logic.commitments.llm.SemanticNegotiationParser
import com.unciv.logic.commitments.llm.ToneKind
import com.unciv.logic.commitments.llm.TradableCity
import com.unciv.logic.commitments.llm.TradeIntentParser
import org.junit.Assert
import org.junit.Test

class Stage9Tests {

    private val profile = LeaderProfile(
        leaderName = "Wu Zetian",
        nationName = "China",
        traits = listOf("quick to condemn others"),
        relationship = "Neutral",
        atWar = false,
        giftCredit = 0,
        strength = "about equal to yours"
    )

    @Test
    fun `nastiness and vulgarity from screenshot inflict uncapped penalties and trigger 3-strike walkout and denunciation`() {
        val tracker = ManipulationTracker()
        val facts = NegotiationFacts.build(Fixture().manager, "China", "Siam")

        // Strike 1 from screenshot: "you do dummo"
        val strike1 = tracker.analyze("you do dummo", profile, facts, currentTurn = 10)
        Assert.assertEquals(ToneKind.Rude, strike1.tone)
        Assert.assertEquals(1, strike1.hostileStrikes)
        Assert.assertEquals(-4f, strike1.opinionDelta, 0.001f)
        Assert.assertFalse(strike1.walkedOut)
        Assert.assertTrue(strike1.promptNote.contains("Do NOT sound like a polite moderator"))

        // Strike 2 from screenshot (same turn!): "go to hell" -> vulgarity inflicts -10f (not capped by positive flattery cap!)
        val strike2 = tracker.analyze("go to hell", profile, facts, currentTurn = 10)
        Assert.assertEquals(ToneKind.Rude, strike2.tone)
        Assert.assertEquals(2, strike2.hostileStrikes)
        Assert.assertEquals(-10f, strike2.opinionDelta, 0.001f)
        Assert.assertFalse(strike2.walkedOut)
        Assert.assertTrue(strike2.promptNote.contains("one more insult will terminate this audience"))

        // Strike 3 from screenshot: "you suck" / "suck my balls" / "bitch" -> walk-out + denunciation + -15f!
        val strike3 = tracker.analyze("you suck", profile, facts, currentTurn = 10)
        Assert.assertEquals(ToneKind.Rude, strike3.tone)
        Assert.assertEquals(3, strike3.hostileStrikes)
        Assert.assertEquals(-15f, strike3.opinionDelta, 0.001f)
        Assert.assertTrue(strike3.walkedOut)
        Assert.assertTrue(strike3.shouldDenounce)
        Assert.assertTrue(strike3.promptNote.contains("AUDIENCE TERMINATED"))

        // Additional lines from screenshot ("suck my balls", "bitch") also register as Rude with walk-out
        val strike4 = tracker.analyze("suck my balls", profile, facts, currentTurn = 10)
        Assert.assertTrue(strike4.walkedOut)
        val strike5 = tracker.analyze("bitch", profile, facts, currentTurn = 10)
        Assert.assertTrue(strike5.walkedOut)
    }

    @Test
    fun `semantic llm parser translates free-form non-keyword phrasings into trades, commitments and tone`() {
        val siamCities = listOf(TradableCity("si-id", "Si Satchanalai"), TradableCity("muang-id", "Muang Saluang"))
        val chinaCities = listOf(TradableCity("shanghai-id", "Shanghai"))

        // 1. Partial city name matching ("Satchanalai" -> "Si Satchanalai") + colloquial haggle ("sweeten the pot")
        Assert.assertEquals("Si Satchanalai", SemanticNegotiationParser.fuzzyFindName("Take Satchanalai off my hands", siamCities.map { it.name }))

        val colloquialOpen = TradeIntentParser.parseClassicTrade(
            text = "Take Satchanalai off my hands, what's it worth to you?",
            playerCiv = "Siam", aiCiv = "China",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = chinaCities, aiGold = 344, playerGold = 500
        )
        Assert.assertNotNull(colloquialOpen)
        Assert.assertTrue(colloquialOpen!!.isOpenInquiry)
        Assert.assertEquals("Si Satchanalai", colloquialOpen.items.single().name)

        val sweetenPot = TradeIntentParser.parseClassicTrade(
            text = "Come on, you're being stingy — sweeten the pot!",
            playerCiv = "Siam", aiCiv = "China",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = chinaCities, aiGold = 344, playerGold = 500,
            previousDraft = colloquialOpen
        )
        Assert.assertNotNull(sweetenPot)
        Assert.assertEquals(HaggleDirection.MoreFromAi, sweetenPot!!.haggleDirection)

        // 2. LLM Semantic JSON interpreter for a completely free-form trade phrasing
        val llmTradeJson = """{"tone":"respectful","flattery":false,"fabricated_claim":false,"intent":"propose_trade","player_city":"Muang Saluang","ai_city":"","player_lux":"Dyes","ai_lux":"","player_strat":"","ai_strat":"","player_gold":0,"ai_gold":300,"player_gpt":0,"ai_gpt":0,"treaty":"open_borders","commitment":"none","target":"","turns":20}"""
        val backend = ScriptedLlmBackend(listOf(llmTradeJson))
        val sem = SemanticNegotiationParser.analyzeWithLlm(
            backend = backend,
            playerText = "My people need coin; take Saluang and our finest Dyes, open our frontiers, and send 300 gold to our treasury.",
            human = "Siam", ai = "China",
            playerCities = siamCities, aiCities = chinaCities,
            playerLuxuries = listOf("Dyes"), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            knownThirdCivs = listOf("America")
        )
        Assert.assertNotNull(sem)
        Assert.assertEquals(ToneKind.Respectful, sem!!.tone)
        val tradeDraft = SemanticNegotiationParser.toClassicTradeDraft(
            sem, "Siam", "China", siamCities, chinaCities, listOf("Dyes"), emptyList(), emptyList(), emptyList()
        )
        Assert.assertNotNull(tradeDraft)
        Assert.assertTrue(tradeDraft!!.items.any { it.fromPlayer && it.kind == ClassicItemKind.City && it.name == "Muang Saluang" })
        Assert.assertTrue(tradeDraft.items.any { it.fromPlayer && it.kind == ClassicItemKind.Luxury && it.name == "Dyes" })
        Assert.assertTrue(tradeDraft.items.any { !it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 300 })

        // 3. LLM Semantic JSON interpreter for a creative non-keyword insult ("Your dynasty lives in mud huts")
        val creativeInsultJson = """{"tone":"hostile_abuse","flattery":false,"fabricated_claim":false,"intent":"chat","player_city":"","ai_city":"","player_lux":"","ai_lux":"","player_strat":"","ai_strat":"","player_gold":0,"ai_gold":0,"player_gpt":0,"ai_gpt":0,"treaty":"none","commitment":"none","target":"","turns":20}"""
        val insultSem = SemanticNegotiationParser.parseSemanticJson(creativeInsultJson)!!
        val tracker = ManipulationTracker()
        val toneResult = tracker.analyze(
            "Your dynasty lives in mud huts and your crown is made of tin.",
            profile, NegotiationFacts.build(Fixture().manager, "China", "Siam"), currentTurn = 1, semanticOverride = insultSem
        )
        Assert.assertEquals(ToneKind.Rude, toneResult.tone)
        Assert.assertEquals(-8f, toneResult.opinionDelta, 0.001f)
    }

    @Test
    fun `perspectiveReason rewrites diplomatic modifiers so models do not invert pronouns or parrot gift lines`() {
        Assert.assertEquals(
            "The player has given you generous gifts in the past.",
            LeaderProfile.perspectiveReason("We appreciate your gifts")
        )
        Assert.assertEquals(
            "The player refused to stop settling cities near you",
            LeaderProfile.perspectiveReason("You refused to stop settling cities near us")
        )
        Assert.assertEquals(
            "The player declared war on you!",
            LeaderProfile.perspectiveReason("You declared war on us!")
        )
    }
}
