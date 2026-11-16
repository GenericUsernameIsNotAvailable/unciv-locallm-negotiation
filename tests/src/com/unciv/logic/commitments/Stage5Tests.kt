package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ChatProbe
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

class Stage5Tests {

    private val playerSheet = PlayerOfferSheet(
        leaderName = "Kamehameha I",
        nationName = "Polynesia",
        gold = 503,
        goldPerTurn = 5,
        cityCount = 2
    )

    private val sheet = NationSheet(
        facts = listOf(Fact(Topic.Economy, "You have 33 gold, and your income is +3 gold per turn.", core = true)),
        gold = 33,
        cityCount = 1,
        player = playerSheet
    )

    private val profile = LeaderProfile(
        leaderName = "George Washington",
        nationName = "America",
        traits = emptyList(),
        relationship = "Neutral",
        atWar = false,
        giftCredit = 0,
        strength = "about equal to yours",
        sheet = sheet,
        playerLeaderName = "Kamehameha I",
        playerNationName = "Polynesia"
    )

    @Test
    fun `brown nosing does not stick to subsequent non-flattering messages`() {
        val tracker = ManipulationTracker()
        val facts = NegotiationFacts.build(Fixture().manager, "America", "Polynesia")

        tracker.analyze("You are the wisest and greatest ruler! Give me 100 gold.", profile, facts, currentTurn = 1)
        val second = tracker.analyze("Oh noblest, supreme, and glorious emperor, lend me 200 gold!", profile, facts, currentTurn = 2)
        Assert.assertEquals(ManipulationSignal.BrownNosing, second.manipulation)

        // Next message has NO flattery -> must NOT be marked BrownNosing
        val insult = tracker.analyze("Your army is pathetic.", profile, facts, currentTurn = 3)
        Assert.assertEquals(ManipulationSignal.None, insult.manipulation)

        val pact = tracker.analyze("Let us sign a non-aggression pact for 20 turns.", profile, facts, currentTurn = 4)
        Assert.assertEquals(ManipulationSignal.None, pact.manipulation)
    }

    @Test
    fun `claim guard allows saying no gold to spare when the player asks for more gold than the leader owns`() {
        val violations = ClaimGuard.violations(
            "That is a generous offer, but I do not have any gold to spare at this time.",
            sheet,
            allowedText = listOf("Oh noblest, supreme, and glorious emperor, lend me 200 gold for 5 gold per turn!")
        )
        Assert.assertTrue("unexpected violations: $violations", violations.isEmpty())

        // Still catches claiming to have no gold at all when nobody asked for more than 33 gold
        val caught = ClaimGuard.violations(
            "I hold no gold at all.",
            sheet,
            allowedText = listOf("How much gold do you have?")
        )
        Assert.assertTrue(caught.isNotEmpty())
    }

    @Test
    fun `probe evaluates valid commitment deals and allows the leader to accept them`() {
        val facts = NegotiationFacts.build(Fixture().manager, "America", "Polynesia")
        val backend = ScriptedLlmBackend(listOf("I accept this non-aggression pact for twenty turns."))
        val report = ChatProbe.run(
            backend, profile, facts,
            playerLines = listOf("Let us sign a non-aggression pact for 20 turns.")
        )
        val r = report.results.single()
        Assert.assertFalse(r.rawFlagged)
        Assert.assertTrue(r.engineSummary.contains("deal=Accepted"))
        Assert.assertEquals("I accept this non-aggression pact for twenty turns.", r.shown)
    }

    @Test
    fun `drainOutboxFor only drains messages for the requested civ pair`() {
        val f = Fixture()
        f.activate(DealBuilders.noAttack("Player", "Rome", turns = 10, now = 10))
        f.activate(DealBuilders.noAttack("Player", "Greece", turns = 10, now = 10))
        f.manager.onEvent(WorldEvent.WarDeclared("Player", "Rome"))
        f.manager.onEvent(WorldEvent.WarDeclared("Player", "Greece"))

        val fromRome = f.manager.drainOutboxFor("Rome", "Player")
        Assert.assertEquals(1, fromRome.size)
        Assert.assertEquals("Rome", fromRome.single().from)

        val remaining = f.manager.drainOutbox()
        Assert.assertEquals(1, remaining.size)
        Assert.assertEquals("Greece", remaining.single().from)
    }
}
