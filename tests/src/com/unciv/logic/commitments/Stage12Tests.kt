package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ChatSession
import com.unciv.logic.commitments.llm.ClassicItemKind
import com.unciv.logic.commitments.llm.LeaderProfile
import com.unciv.logic.commitments.llm.NumberWords
import com.unciv.logic.commitments.llm.SemanticAnalysis
import com.unciv.logic.commitments.llm.SemanticNegotiationParser
import com.unciv.logic.commitments.llm.TradableCity
import com.unciv.logic.commitments.llm.TradeIntentParser
import com.unciv.logic.commitments.llm.VerdictConsistencyGuard
import org.junit.Assert
import org.junit.Test

class Stage12Tests {

    private val siamCities = listOf(TradableCity("muang-id", "Muang Saluang"))

    @Test
    fun `regression for George Washington screenshot - i will give u one of my cities for nothing is a pure free gift`() {
        // 1. "u" and "ur" are expanded cleanly by NumberWords.normalize
        Assert.assertEquals(
            "i will give you one of my cities for nothing",
            NumberWords.normalize("i will give u one of my cities for nothing")
        )
        Assert.assertTrue(TradeIntentParser.isExplicitFreeGift("i will give u one of my cities for nothing"))

        // 2. Fast parser parses "i will give u one of my cities for nothing" as a 1-sided free gift (isOpenInquiry = false)
        val draft = TradeIntentParser.parseClassicTrade(
            text = "i will give u one of my cities for nothing",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = listOf("Dyes", "Sugar"), aiLuxuries = listOf("Cotton", "Ivory"),
            playerStrategics = listOf("Horses"), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = emptyList(), aiGold = 1027, playerGold = 5152
        )
        Assert.assertNotNull(draft)
        Assert.assertFalse("Explicit 'for nothing' must NOT be treated as an open inquiry!", draft!!.isOpenInquiry)
        Assert.assertEquals(1, draft.items.size)
        Assert.assertTrue(draft.items.single().fromPlayer)
        Assert.assertEquals(ClassicItemKind.City, draft.items.single().kind)
        Assert.assertEquals("Muang Saluang", draft.items.single().name)

        // 3. Semantic parser fallback also respects "for nothing" (isOpenInquiry = false)
        val sem = SemanticAnalysis(intent = "propose_trade", playerGivesCity = "Muang Saluang")
        val semDraft = SemanticNegotiationParser.toClassicTradeDraft(
            sem, "Siam", "America", siamCities, emptyList(), listOf("Dyes"), listOf("Cotton"), emptyList(), emptyList(),
            previousDraft = null, playerText = "i will give u one of my cities for nothing"
        )
        Assert.assertNotNull(semDraft)
        Assert.assertFalse(semDraft!!.isOpenInquiry)
    }

    @Test
    fun `regression for George Washington screenshot - VerdictConsistencyGuard catches not something I am prepared to accept`() {
        val gwRefusal = "That proposal is not something I am prepared to accept. We have established a good peace between our nations, and I prefer to maintain that stability."
        Assert.assertTrue(VerdictConsistencyGuard.contradictsAcceptance(gwRefusal))
        Assert.assertTrue(VerdictConsistencyGuard.contradictsAcceptance("I am not interested in specific resources from your nation."))
        Assert.assertTrue(VerdictConsistencyGuard.contradictsAcceptance("I do not wish to offer any of our cities for exchange at this time."))

        // Verify ChatSession retries and replaces the refusal when proposalAccepted == true
        val f = Fixture()
        val profile = LeaderProfile("George Washington", "America", emptyList(), "Neutral", false, 0, "about equal to yours")
        val backend = ScriptedLlmBackend(
            listOf(
                gwRefusal,
                "Good day. I gladly accept your generous gift of Muang Saluang. Please confirm the trade card."
            )
        )
        val session = ChatSession(backend)
        session.say("i will give u one of my cities for nothing")
        val reply = session.reply(
            profile, NegotiationFacts.build(f.manager, "America", "Siam"),
            proposalDirective = "ENGINE TRADE VERDICT: Accepted", proposalAccepted = true
        )
        Assert.assertEquals(
            ChatSession.Reply.Said("Good day. I gladly accept your generous gift of Muang Saluang. Please confirm the trade card."),
            reply
        )
    }

    @Test
    fun `regression for George Washington screenshot - what would you like of mine and gold for cities either or`() {
        // 1. "okay, what would you like of mine?" -> picks a luxury the player has that the AI lacks (Dyes) as an open inquiry
        val whatYouLike = TradeIntentParser.parseClassicTrade(
            text = "okay, what would you like of mine?",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = listOf("Dyes", "Sugar"), aiLuxuries = listOf("Cotton", "Ivory"),
            playerStrategics = listOf("Horses"), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = emptyList(), aiGold = 1027, playerGold = 5152
        )
        Assert.assertNotNull(whatYouLike)
        Assert.assertTrue(whatYouLike!!.isOpenInquiry)
        Assert.assertEquals(ClassicItemKind.Luxury, whatYouLike.items.single().kind)
        Assert.assertEquals("Dyes", whatYouLike.items.single().name)

        // 2. "gold for cities either or" -> open inquiry for player's tradable city
        val goldForCities = TradeIntentParser.parseClassicTrade(
            text = "gold for cities either or",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = listOf("Dyes"), aiLuxuries = listOf("Cotton"),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = emptyList(), aiGold = 1027, playerGold = 5152
        )
        Assert.assertNotNull(goldForCities)
        Assert.assertTrue(goldForCities!!.isOpenInquiry)
        Assert.assertEquals(ClassicItemKind.City, goldForCities.items.single().kind)
    }
}
