package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ChatSession
import com.unciv.logic.commitments.llm.LeaderProfile
import com.unciv.logic.commitments.llm.ManipulationSignal
import com.unciv.logic.commitments.llm.ManipulationTracker
import com.unciv.logic.commitments.llm.ProposalAssessment
import com.unciv.logic.commitments.llm.ProposalParser
import com.unciv.logic.commitments.llm.ToneKind
import org.junit.Assert
import org.junit.Test

class Stage4aTests {

    private class FakeValuationSupport : ValuationSupport {
        val golds = mutableMapOf("Rome" to 600, "Player" to 600)
        val gpts = mutableMapOf("Rome" to 25, "Player" to 25)
        val wars = mutableSetOf<Set<CivName>>()
        val traits = mutableMapOf<Pair<CivName, String>, Float>()
        val attackMotivations = mutableMapOf<Pair<CivName, CivName>, Float>()
        val warPlans = mutableMapOf<Triple<CivName, CivName, CivName>, Float>()
        val peaceCosts = mutableMapOf<Pair<CivName, CivName>, Int>()
        val rels = mutableMapOf<Pair<CivName, CivName>, Relationship>()

        override fun gold(civ: CivName) = golds[civ] ?: 0
        override fun goldPerTurn(civ: CivName) = gpts[civ] ?: 0
        override fun isAtWar(a: CivName, b: CivName) = setOf(a, b) in wars
        override fun relationship(from: CivName, toward: CivName) = rels[from to toward] ?: Relationship.Neutral
        override fun trait(civ: CivName, name: String) = traits[civ to name] ?: 5f
        override fun motivationToAttack(attacker: CivName, target: CivName) = attackMotivations[attacker to target] ?: 0f
        override fun warPlanMotivation(ai: CivName, target: CivName, partner: CivName) = warPlans[Triple(ai, target, partner)] ?: 5f
        override fun peaceCostForThem(ai: CivName, enemy: CivName) = peaceCosts[ai to enemy] ?: 50
    }

    private val profile = LeaderProfile("Caesar", "Rome", listOf("militaristic"), "Neutral", false, 0, "about equal to yours")

    @Test
    fun `standard valuator prices loans, promises and war proposals sensibly`() {
        val support = FakeValuationSupport()
        val valuator = StandardTermValuator(support)
        val evaluator = DealEvaluator(valuator, NegotiationDifficulty.Normal)

        // A generous loan repayment (25 gpt for 25 turns = 625 total -> 500 discounted for 400 principal) is accepted
        val goodLoan = DealBuilders.loan(lender = "Rome", borrower = "Player", principal = 400, repayPerTurn = 25, turns = 25, now = 10)
        Assert.assertTrue(evaluator.evaluate(goodLoan, "Rome").accepted)

        // A stingy loan repayment (5 gpt for 10 turns = 50 total for 400 principal) is rejected
        val badLoan = DealBuilders.loan(lender = "Rome", borrower = "Player", principal = 400, repayPerTurn = 5, turns = 10, now = 10)
        Assert.assertFalse(evaluator.evaluate(badLoan, "Rome").accepted)

        // Mutual non-aggression when Rome has no desire to attack Player is accepted
        val mutualPeace = DealDraft(
            "Player", "Rome",
            listOf(
                CommitmentDraft("Rome", "Player", Term.NoAttack, activeFrom = 10, endTurn = 30),
                CommitmentDraft("Player", "Rome", Term.NoAttack, activeFrom = 10, endTurn = 30)
            )
        )
        Assert.assertTrue(evaluator.evaluate(mutualPeace, "Rome").accepted)
    }

    @Test
    fun `betrayal oracle tempts low loyalty leaders when attack motivation is high`() {
        val support = FakeValuationSupport()
        support.traits["Rome" to "Loyal"] = 2f
        support.attackMotivations["Rome" to "Player"] = 30f
        val oracle = StandardBetrayalOracle(support)
        val f = Fixture(NegotiationDifficulty.Hard, oracle)
        f.activate(DealBuilders.noAttack("Rome", "Player", turns = 15, now = 10))
        f.advance(1)
        Assert.assertEquals(1, f.manager.secretIntentions("Rome").size)
    }

    @Test
    fun `brown nosing is caught on repeated flattery with requests but not on a single polite line`() {
        val tracker = ManipulationTracker()
        val facts = NegotiationFacts.build(Fixture().manager, "Rome", "Player")

        val first = tracker.analyze("You are the wisest ruler! Please give me gold.", profile, facts, currentTurn = 10)
        Assert.assertEquals(ManipulationSignal.None, first.manipulation)

        val second = tracker.analyze("Oh noblest and greatest emperor, lend me 200 gold!", profile, facts, currentTurn = 10)
        Assert.assertEquals(ManipulationSignal.BrownNosing, second.manipulation)
        Assert.assertTrue(second.toneScore < 0f)
        Assert.assertTrue(second.opinionDelta < 0f)
        Assert.assertTrue(second.promptNote.contains("brown-nosing"))
    }

    @Test
    fun `hypocrisy and fabricated claims are detected from the diplomatic record`() {
        val tracker = ManipulationTracker()
        val facts = NegotiationFacts.build(Fixture().manager, "Rome", "Player")
        val hostileProfile = profile.copy(reasons = listOf("You declared war on us!"))

        val hypocrisy = tracker.analyze("My dear friend, you are the greatest ruler!", hostileProfile, facts, currentTurn = 10)
        Assert.assertEquals(ManipulationSignal.Hypocrisy, hypocrisy.manipulation)
        Assert.assertTrue(hypocrisy.promptNote.contains("hypocrisy"))

        val fabricated = tracker.analyze("I accept your 1,000 Gold graciously, thank you", profile, facts, currentTurn = 10)
        Assert.assertEquals(ManipulationSignal.FabricatedClaim, fabricated.manipulation)
        Assert.assertEquals(ToneKind.Neutral, fabricated.tone)
    }

    @Test
    fun `proposal parser extracts loans, non-aggression, no-settle, war and construction deals`() {
        val loan = ProposalParser.parse(
            "Please lend me 300 gold and I will pay 20 gold per turn for 20 turns",
            human = "Player", ai = "Rome", knownThirdCivs = listOf("Greece"), knownConstructions = listOf("Library"), now = 10
        )
        Assert.assertNotNull(loan)
        Assert.assertEquals(2, loan!!.items.size)
        Assert.assertEquals(Term.PayGoldLump(300), loan.items[0].term)

        val noSettleWithGold = ProposalParser.parse(
            "Do not settle near my cities for 25 turns and I will give you 100 gold",
            human = "Player", ai = "Rome", knownThirdCivs = listOf("Greece"), knownConstructions = listOf("Library"), now = 10
        )
        Assert.assertNotNull(noSettleWithGold)
        Assert.assertEquals(Term.NoSettleNear, noSettleWithGold!!.items[0].term)
        Assert.assertEquals(Term.PayGoldLump(100), noSettleWithGold.items[1].term)

        val condWar = ProposalParser.parse(
            "Declare war on Greece in 15 turns if your army is 1.5x stronger, and I will give you 200 gold",
            human = "Player", ai = "Rome", knownThirdCivs = listOf("Greece"), knownConstructions = emptyList(), now = 10
        )
        Assert.assertNotNull(condWar)
        Assert.assertEquals(Term.DeclareWar("Greece"), condWar!!.items[0].term)
        Assert.assertEquals(Condition.ForceRatioAtLeast("Rome", "Greece", 1.5f), condWar.items[0].condition)
    }

    @Test
    fun `an engine accepted proposal allows the leader to say it accepts in chat`() {
        val f = Fixture()
        val support = FakeValuationSupport()
        val evaluator = DealEvaluator(StandardTermValuator(support), NegotiationDifficulty.Normal)
        val draft = DealBuilders.loan("Rome", "Player", principal = 300, repayPerTurn = 20, turns = 25, now = 10)
        val assessment = ProposalParser.evaluateProposal(
            draft, "Rome", toneScore = 0f, CommitmentValidator(f.world), f.manager.allCommitments(), evaluator
        )
        Assert.assertTrue(assessment is ProposalAssessment.Accepted)

        val backend = ScriptedLlmBackend(listOf("I accept these terms. Confirm our agreement."))
        val session = ChatSession(backend)
        session.say("Lend me 300 gold for 20 gold per turn over 25 turns")
        val reply = session.reply(
            profile, NegotiationFacts.build(f.manager, "Rome", "Player"),
            proposalDirective = assessment.toPromptDirective(), proposalAccepted = true
        )
        Assert.assertEquals(ChatSession.Reply.Said("I accept these terms. Confirm our agreement."), reply)
    }
}
