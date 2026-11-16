package com.unciv.logic.commitments

import org.junit.Assert
import org.junit.Test

class CommitmentManagerTests {

    private fun restraint(committer: CivName, beneficiary: CivName, term: Term, from: Int, to: Int, condition: Condition? = null) =
        DealDraft(beneficiary, committer, listOf(CommitmentDraft(committer, beneficiary, term, condition, from, to)))

    // ------------------------------------------------------------ deal lifecycle

    @Test
    fun `deal activates only after the counterparty confirms`() {
        val f = Fixture()
        val draft = DealBuilders.noSettleNear("Rome", "Player", 5, 10)
        val id = (f.manager.propose(draft) as ProposalResult.Proposed).dealId
        Assert.assertEquals(CommitmentStatus.Proposed, f.manager.allCommitments().single().status)
        Assert.assertEquals(AcceptResult.NotFound, f.manager.accept(id, "Greece"))
        Assert.assertEquals(AcceptResult.Activated, f.manager.accept(id, "Rome"))
        Assert.assertEquals(CommitmentStatus.Active, f.manager.allCommitments().single().status)
    }

    @Test
    fun `rejected deal cancels its commitments`() {
        val f = Fixture()
        val id = (f.manager.propose(DealBuilders.noSettleNear("Rome", "Player", 5, 10)) as ProposalResult.Proposed).dealId
        Assert.assertTrue(f.manager.reject(id, "Rome"))
        Assert.assertEquals(CommitmentStatus.Cancelled, f.manager.allCommitments().single().status)
        Assert.assertEquals(DealStatus.Rejected, f.manager.deal(id)!!.status)
    }

    @Test
    fun `unconfirmed proposal lapses after its time to live`() {
        val f = Fixture()
        val id = (f.manager.propose(DealBuilders.noSettleNear("Rome", "Player", 20, 10)) as ProposalResult.Proposed).dealId
        f.advance(4)
        Assert.assertEquals(DealStatus.Rejected, f.manager.deal(id)!!.status)
        Assert.assertEquals(AcceptResult.NotFound, f.manager.accept(id, "Rome"))
    }

    @Test
    fun `deal that became unaffordable is invalid on confirmation`() {
        val f = Fixture()
        val draft = DealBuilders.loan(lender = "Rome", borrower = "Player", principal = 500, repayPerTurn = 20, turns = 30, now = 10)
        val id = (f.manager.propose(draft) as ProposalResult.Proposed).dealId
        f.world.golds["Rome"] = 0
        val result = f.manager.accept(id, "Rome")
        Assert.assertTrue(result is AcceptResult.Invalid)
        Assert.assertEquals(ValidationCode.InsufficientGold, (result as AcceptResult.Invalid).errors.first().code)
        Assert.assertEquals(DealStatus.Rejected, f.manager.deal(id)!!.status)
    }

    // ------------------------------------------------------------ restraints

    @Test
    fun `restraint is fulfilled once its window passes without a violation`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.noSettleNear("Rome", "Player", 5, 10)).single()
        f.advance(5) // turn 15, the last turn of the window
        Assert.assertEquals(CommitmentStatus.Active, f.status(id))
        f.advance(1)
        Assert.assertEquals(CommitmentStatus.Fulfilled, f.status(id))
        Assert.assertEquals(1, f.actions.bonuses.size)
        Assert.assertTrue(f.actions.breachPenalties.isEmpty())
    }

    @Test
    fun `violating a restraint breaches it with penalty and gossip`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.noSettleNear("Rome", "Player", 5, 10)).single()
        f.advance(2)
        f.manager.onEvent(WorldEvent.CitySettledNear("Rome", "Player"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(id))
        Assert.assertEquals(FakeActions.Opinion("Player", "Rome", -20f), f.actions.breachPenalties.single())
        Assert.assertEquals(FakeActions.Opinion("Greece", "Rome", -10f), f.actions.gossipPenalties.single())
    }

    @Test
    fun `a human breaking a promise to an AI makes the AI complain`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.noSettleNear("Player", "Rome", 5, 10)).single()
        f.manager.onEvent(WorldEvent.CitySettledNear("Player", "Rome"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(id))
        val message = f.manager.drainOutbox().single()
        Assert.assertEquals(OutboxKind.PlayerBrokeCommitment, message.kind)
        Assert.assertEquals("Rome", message.from)
        Assert.assertEquals("Player", message.to)
        Assert.assertTrue(f.manager.drainOutbox().isEmpty())
    }

    @Test
    fun `a restraint whose condition is false is not in force`() {
        val f = Fixture()
        val draft = restraint("Rome", "Player", Term.NoSettleNear, 10, 20, Condition.TurnAtLeast(14))
        val id = f.activate(draft).single()
        f.advance(2) // turn 12
        f.manager.onEvent(WorldEvent.CitySettledNear("Rome", "Player"))
        Assert.assertEquals(CommitmentStatus.Active, f.status(id))
        Assert.assertFalse(f.manager.isForbidden("Rome", AiAction.SettleNear("Player")))
        f.advance(2) // turn 14
        Assert.assertTrue(f.manager.isForbidden("Rome", AiAction.SettleNear("Player")))
        f.manager.onEvent(WorldEvent.CitySettledNear("Rome", "Player"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(id))
    }

    @Test
    fun `isForbidden reflects exactly what active restraints promise`() {
        val f = Fixture()
        f.activate(restraint("Rome", "Player", Term.NoAttack, 10, 15))
        Assert.assertTrue(f.manager.isForbidden("Rome", AiAction.DeclareWar("Player")))
        Assert.assertFalse(f.manager.isForbidden("Rome", AiAction.DeclareWar("Greece")))
        Assert.assertFalse(f.manager.isForbidden("Greece", AiAction.DeclareWar("Player")))
        Assert.assertFalse(f.manager.isForbidden("Rome", AiAction.MakePeace("Player")))
        f.advance(6)
        Assert.assertFalse(f.manager.isForbidden("Rome", AiAction.DeclareWar("Player")))
    }

    // ------------------------------------------------------------ obligations

    @Test
    fun `conditional war is declared once the AI is strong enough`() {
        val f = Fixture()
        f.world.forces["Rome"] = 100f
        f.world.forces["Persia"] = 100f
        val id = f.activate(DealBuilders.conditionalWar("Rome", "Player", "Persia", notBefore = 11, byTurn = 20, minForceRatio = 1.5f)).single()
        f.advance(4)
        Assert.assertTrue(f.actions.declaredWars.isEmpty())
        Assert.assertEquals(CommitmentStatus.Active, f.status(id))
        f.world.forces["Rome"] = 200f
        f.advance(1)
        Assert.assertEquals(listOf("Rome" to "Persia"), f.actions.declaredWars)
        Assert.assertEquals(CommitmentStatus.Fulfilled, f.status(id))
    }

    @Test
    fun `conditional war that never becomes viable expires without penalty`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.conditionalWar("Rome", "Player", "Persia", 11, 20, minForceRatio = 1.5f)).single()
        f.advance(11)
        Assert.assertEquals(CommitmentStatus.ConditionNotMet, f.status(id))
        Assert.assertTrue(f.actions.declaredWars.isEmpty())
        Assert.assertTrue(f.actions.breachPenalties.isEmpty())
    }

    @Test
    fun `war obligation is fulfilled if the war already exists`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.conditionalWar("Rome", "Player", "Persia", notBefore = 13, byTurn = 20)).single()
        f.advance(2)
        f.world.makeWar("Rome", "Persia")
        f.advance(1)
        Assert.assertEquals(CommitmentStatus.Fulfilled, f.status(id))
        Assert.assertTrue(f.actions.declaredWars.isEmpty())
    }

    @Test
    fun `AI that cannot declare war breaches involuntarily at the deadline for half the penalty`() {
        val f = Fixture()
        f.actions.declareWarSucceeds = false
        val id = f.activate(DealBuilders.conditionalWar("Rome", "Player", "Persia", 11, 13)).single()
        f.advance(4)
        Assert.assertEquals(CommitmentStatus.Breached, f.status(id))
        Assert.assertEquals(-10f, f.actions.breachPenalties.single().delta, 0.001f)
        Assert.assertEquals(OutboxKind.AiCouldNotKeepCommitment, f.manager.drainOutbox().single().kind)
    }

    @Test
    fun `human is reminded and then breaches if they never declare the promised war`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.conditionalWar("Player", "Rome", "Persia", 11, 14)).single()
        f.advance(2) // turn 12: endTurn - turn == 2
        Assert.assertEquals(OutboxKind.ReminderToPlayer, f.manager.drainOutbox().single().kind)
        f.advance(3) // turn 15: window closed
        Assert.assertEquals(CommitmentStatus.Breached, f.status(id))
        Assert.assertEquals(-20f, f.actions.breachPenalties.single().delta, 0.001f)
        Assert.assertEquals(OutboxKind.PlayerBrokeCommitment, f.manager.drainOutbox().single().kind)
    }

    @Test
    fun `human who declares the promised war in time fulfils the obligation`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.conditionalWar("Player", "Rome", "Persia", 11, 14)).single()
        f.advance(3)
        f.world.makeWar("Player", "Persia")
        f.manager.onEvent(WorldEvent.WarDeclared("Player", "Persia"))
        Assert.assertEquals(CommitmentStatus.Fulfilled, f.status(id))
        f.advance(3)
        Assert.assertTrue(f.actions.breachPenalties.isEmpty())
    }

    // ------------------------------------------------------------ money

    @Test
    fun `loan pays the principal at once and is repaid turn by turn`() {
        val f = Fixture()
        f.world.golds["Player"] = 0
        val ids = f.activate(DealBuilders.loan("Rome", "Player", principal = 500, repayPerTurn = 20, turns = 30, now = 10))
        Assert.assertEquals(500, f.world.gold("Player"))
        Assert.assertEquals(500, f.world.gold("Rome"))
        Assert.assertEquals(CommitmentStatus.Fulfilled, f.status(ids[0]))
        f.advance(3)
        Assert.assertEquals(440, f.world.gold("Player"))
        Assert.assertEquals(560, f.world.gold("Rome"))
    }

    @Test
    fun `fully repaid loan returns principal plus interest to the lender`() {
        val f = Fixture()
        val ids = f.activate(DealBuilders.loan("Rome", "Player", principal = 500, repayPerTurn = 20, turns = 30, now = 10))
        f.advance(31) // 30 repayment turns, then the window closes
        Assert.assertEquals(1000 + 100, f.world.gold("Rome")) // -500 +600
        Assert.assertEquals(1000 - 100, f.world.gold("Player"))
        Assert.assertEquals(CommitmentStatus.Fulfilled, f.status(ids[1]))
        Assert.assertTrue(f.actions.breachPenalties.isEmpty())
        val quote = DealBuilders.loanQuote(500, 20, 30)
        Assert.assertEquals(20f, quote.interestPercent, 0.001f)
    }

    @Test
    fun `borrower who cannot repay breaches involuntarily`() {
        val f = Fixture()
        f.world.golds["Player"] = 0
        val ids = f.activate(DealBuilders.loan("Rome", "Player", principal = 500, repayPerTurn = 300, turns = 10, now = 10))
        f.advance(2) // first payment succeeds (500 -> 200), second fails
        Assert.assertEquals(CommitmentStatus.Breached, f.status(ids[1]))
        val penalty = f.actions.breachPenalties.single()
        Assert.assertEquals("Rome", penalty.who)
        Assert.assertEquals("Player", penalty.about)
        Assert.assertEquals(-10f, penalty.delta, 0.001f)
    }

    @Test
    fun `proxy war subsidy stops when the fighter makes peace`() {
        val f = Fixture()
        f.world.makeWar("Rome", "Persia")
        val ids = f.activate(DealBuilders.proxyWarSubsidy("Player", "Rome", "Persia", goldPerTurn = 50, turns = 20, now = 10))
        f.advance(3)
        Assert.assertEquals(850, f.world.gold("Player"))
        f.manager.onEvent(WorldEvent.PeaceMade("Rome", "Persia"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(ids[0]))
        f.advance(2)
        Assert.assertEquals(850, f.world.gold("Player")) // payments paused: the other side broke its part
        f.advance(20)
        Assert.assertEquals(CommitmentStatus.ConditionNotMet, f.status(ids[1]))
        Assert.assertEquals(850, f.world.gold("Player"))
    }

    @Test
    fun `fighter is released from the subsidy deal if the payer stops paying`() {
        val f = Fixture()
        f.world.makeWar("Rome", "Persia")
        f.world.golds["Player"] = 100
        val ids = f.activate(DealBuilders.proxyWarSubsidy("Player", "Rome", "Persia", goldPerTurn = 60, turns = 20, now = 10))
        f.advance(2) // pays 60, then cannot pay again
        Assert.assertEquals(CommitmentStatus.Breached, f.status(ids[1]))
        f.manager.onEvent(WorldEvent.PeaceMade("Rome", "Persia"))
        Assert.assertEquals(CommitmentStatus.Active, f.status(ids[0])) // not in force, so no breach
        Assert.assertFalse(f.manager.isForbidden("Rome", AiAction.MakePeace("Persia")))
    }

    // ------------------------------------------------------------ production nudges

    @Test
    fun `production nudge biases the committer until the construction completes`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.productionNudge("Rome", "Player", "Harbor", turns = 10, now = 10)).single()
        Assert.assertEquals(mapOf("Harbor" to 3f), f.manager.constructionBias("Rome"))
        Assert.assertTrue(f.manager.constructionBias("Greece").isEmpty())
        f.advance(2)
        f.manager.onEvent(WorldEvent.ConstructionCompleted("Rome", "Harbor"))
        Assert.assertEquals(CommitmentStatus.Fulfilled, f.status(id))
        Assert.assertTrue(f.manager.constructionBias("Rome").isEmpty())
    }

    @Test
    fun `production nudge that is not completed expires without penalty`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.productionNudge("Rome", "Player", "Library", turns = 10, now = 10)).single()
        f.advance(11)
        Assert.assertEquals(CommitmentStatus.Expired, f.status(id))
        Assert.assertTrue(f.actions.breachPenalties.isEmpty())
    }

    // ------------------------------------------------------------ betrayal

    private val tempting = BetrayalOracle { BetrayalAssessment(benefit = 100f, loyalty = 0f) }

    @Test
    fun `AI decides to betray, then breaks the promise knowingly and says so`() {
        val f = Fixture(NegotiationDifficulty.Hard, tempting)
        val id = f.activate(restraint("Rome", "Player", Term.NoAttack, 10, 30)).single()
        Assert.assertTrue(f.manager.isForbidden("Rome", AiAction.DeclareWar("Player")))
        Assert.assertTrue(f.manager.secretIntentions("Rome").isEmpty())

        f.advance(1)
        val decision = f.manager.log.entries.single { it.kind == DecisionKind.BetrayalDecided }
        Assert.assertEquals(ReasonCode.BetrayalBenefitExceedsCost, decision.reasons.single().code)
        Assert.assertEquals(30f, decision.reasons.single().values.getValue("reputationCost"), 0.001f)
        Assert.assertEquals(1, f.manager.secretIntentions("Rome").size)
        Assert.assertFalse(f.manager.isForbidden("Rome", AiAction.DeclareWar("Player")))
        Assert.assertEquals(CommitmentStatus.Active, f.status(id)) // decided, not yet done

        f.manager.onEvent(WorldEvent.WarDeclared("Rome", "Player"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(id))
        Assert.assertEquals(OutboxKind.AiBetrayal, f.manager.drainOutbox().single().kind)
        Assert.assertEquals(-20f, f.actions.breachPenalties.single().delta, 0.001f)
        Assert.assertTrue(f.manager.secretIntentions("Rome").isEmpty())
    }

    @Test
    fun `easy difficulty raises the loyalty floor so the same temptation is resisted`() {
        val marginal = BetrayalOracle { BetrayalAssessment(benefit = 55f, loyalty = 0f) }
        val hard = Fixture(NegotiationDifficulty.Hard, marginal)
        val easy = Fixture(NegotiationDifficulty.Easy, marginal)
        for (f in listOf(hard, easy)) {
            f.activate(restraint("Rome", "Player", Term.NoAttack, 10, 30))
            f.advance(1)
        }
        Assert.assertEquals(1, hard.manager.secretIntentions("Rome").size)
        Assert.assertTrue(easy.manager.secretIntentions("Rome").isEmpty())
    }

    @Test
    fun `humans never get a betrayal licence`() {
        val f = Fixture(NegotiationDifficulty.Hard, tempting)
        f.activate(restraint("Player", "Rome", Term.NoAttack, 10, 30))
        f.advance(3)
        Assert.assertTrue(f.manager.log.entries.none { it.kind == DecisionKind.BetrayalDecided })
    }

    @Test
    fun `betrayal evaluator scales its threshold with loyalty and noise`() {
        val e = BetrayalEvaluator()
        val loyal = e.decide(BetrayalAssessment(60f, 1f), 0f, NegotiationDifficulty.Hard, 0.5f)
        Assert.assertEquals(50f, loyal.threshold, 0.001f)
        Assert.assertTrue(loyal.betray)
        val loyalLowerBenefit = e.decide(BetrayalAssessment(45f, 1f), 0f, NegotiationDifficulty.Hard, 0.5f)
        Assert.assertFalse(loyalLowerBenefit.betray)
        val lucky = e.decide(BetrayalAssessment(45f, 1f), 0f, NegotiationDifficulty.Hard, 0f)
        Assert.assertEquals(45f, lucky.threshold, 0.001f)
        Assert.assertFalse(lucky.betray) // margin must strictly exceed the threshold
    }
}
