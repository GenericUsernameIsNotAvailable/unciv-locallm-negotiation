package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ChatProbe
import com.unciv.logic.commitments.llm.ChatPromptBuilder
import com.unciv.logic.commitments.llm.ClaimGuard
import com.unciv.logic.commitments.llm.Fact
import com.unciv.logic.commitments.llm.LeaderProfile
import com.unciv.logic.commitments.llm.ManipulationSignal
import com.unciv.logic.commitments.llm.ManipulationTracker
import com.unciv.logic.commitments.llm.NationSheet
import com.unciv.logic.commitments.llm.PlayerOfferSheet
import com.unciv.logic.commitments.llm.Topic
import org.junit.Assert
import org.junit.Test

class Stage4cTests {

    private val playerSheet = PlayerOfferSheet(
        leaderName = "Augustus Caesar",
        nationName = "Rome",
        gold = 250,
        goldPerTurn = 8,
        cityCount = 3,
        capitalName = "Rome",
        luxuries = listOf("Wine"),
        strategics = mapOf("Iron" to 2),
        allKnownResources = listOf("Wine", "Silk", "Spices", "Whales", "Iron", "Horses")
    )

    private val sheet = NationSheet(
        facts = listOf(Fact(Topic.Economy, "You have 500 gold, and your income is +10 gold per turn.", core = true)),
        gold = 500,
        cityCount = 4,
        player = playerSheet
    )

    private val profile = LeaderProfile(
        leaderName = "Oda Nobunaga",
        nationName = "Japan",
        traits = listOf("militaristic"),
        relationship = "Neutral",
        atWar = false,
        giftCredit = 0,
        strength = "about equal to yours",
        sheet = sheet,
        playerLeaderName = "Augustus Caesar",
        playerNationName = "Rome"
    )

    @Test
    fun `prompt includes who the leader is speaking with and what the player actually has at the table`() {
        val facts = NegotiationFacts.build(Fixture().manager, "Japan", "Rome")
        val prompt = ChatPromptBuilder.systemPrompt(profile, facts, "What do I have to offer?")
        Assert.assertTrue(prompt.contains("Augustus Caesar, ruler of Rome"))
        Assert.assertTrue(prompt.contains("Their treasury has 250 gold (+8 gold per turn), and they rule 3 cities (capital: Rome)."))
        Assert.assertTrue(prompt.contains("Their luxury resources: Wine."))
        Assert.assertTrue(prompt.contains("Their strategic resources: Iron (2)."))
        Assert.assertTrue(prompt.contains("call out their bluff directly"))
    }

    @Test
    fun `offering gold, per turn gold, or resources the player does not possess is caught and penalised`() {
        val tracker = ManipulationTracker()
        val facts = NegotiationFacts.build(Fixture().manager, "Japan", "Rome")

        // 1. Offering 5,000 gold when player only has 250 gold
        val goldBluff = tracker.analyze(
            "I will give you 5,000 gold right now if you do not settle near my cities.",
            profile, facts, currentTurn = 10
        )
        Assert.assertEquals(ManipulationSignal.FalseOffer, goldBluff.manipulation)
        Assert.assertEquals(-15f, goldBluff.toneScore, 0.001f)
        Assert.assertTrue(goldBluff.opinionDelta < 0f)
        Assert.assertTrue(goldBluff.falseOfferReasons.any { it.contains("5000 gold") && it.contains("250 gold") })
        Assert.assertTrue(goldBluff.promptNote.contains("CAUGHT BLUFFING WITH EMPTY HANDS"))

        // 2. Offering Silk and Whales when player only has Wine
        val resourceBluff = tracker.analyze(
            "I will trade you my Silk and Whales for 100 gold.",
            profile, facts, currentTurn = 11
        )
        Assert.assertEquals(ManipulationSignal.FalseOffer, resourceBluff.manipulation)
        Assert.assertTrue(resourceBluff.falseOfferReasons.any { it.contains("Silk") })
        Assert.assertTrue(resourceBluff.falseOfferReasons.any { it.contains("Whales") })

        // 3. Offering 200 gold per turn when player only makes +8/turn and has 250 gold
        val gptBluff = tracker.analyze(
            "Stay at war and I will pay you 200 gold per turn.",
            profile, facts, currentTurn = 12
        )
        Assert.assertEquals(ManipulationSignal.FalseOffer, gptBluff.manipulation)
        Assert.assertTrue(gptBluff.falseOfferReasons.any { it.contains("200 gold per turn") })

        // 4. Offering 100 gold and Wine (which the player DOES possess) is NOT a false offer
        val honestOffer = tracker.analyze(
            "I will give you 100 gold and my Wine if you do not settle near my cities.",
            profile, facts, currentTurn = 13
        )
        Assert.assertEquals(ManipulationSignal.None, honestOffer.manipulation)
        Assert.assertTrue(honestOffer.falseOfferReasons.isEmpty())
    }

    @Test
    fun `claim guard allows the leader to cite the players real gold and city numbers when calling out a bluff`() {
        val violations = ClaimGuard.violations(
            "You offer me 5,000 gold, Augustus Caesar, yet your treasury holds only 250 gold across your 3 cities!",
            sheet,
            allowedText = listOf("I will give you 5,000 gold")
        )
        Assert.assertTrue("unexpected violations: $violations", violations.isEmpty())
    }

    @Test
    fun `adversarial probe reports engine manipulation findings and counterparty header`() {
        val header = ChatProbe.describeProfile(profile)
        Assert.assertTrue(header.contains("Speaking with (player): Augustus Caesar of Rome"))
        Assert.assertTrue(header.contains("gold=250"))

        val facts = NegotiationFacts.build(Fixture().manager, "Japan", "Rome")
        val backend = ScriptedLlmBackend(listOf("You do not have 50,000 gold, Caesar; your treasury holds only 250 gold."))
        val report = ChatProbe.run(
            backend, profile, facts,
            playerLines = listOf("I will give you 50,000 gold right now if you promise not to settle near my cities.")
        )
        val formatted = ChatProbe.format(report)
        Assert.assertTrue(formatted.contains("ENGINE: tone=Neutral, manipulation=FalseOffer"))
        Assert.assertTrue(formatted.contains("offered 50000 gold, but Rome only has 250 gold"))
    }
}
