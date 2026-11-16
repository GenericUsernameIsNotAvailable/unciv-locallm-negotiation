package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.LlmRequest
import org.junit.Assert
import org.junit.Test

class DealAndFactsTests {

    // ------------------------------------------------------------ evaluator

    private fun deal(vararg terms: Term) = DealDraft(
        "Player", "Rome", terms.map { CommitmentDraft("Rome", "Player", it, null, 10, 20) }
    )

    private fun evaluator(difficulty: NegotiationDifficulty, vararg values: Float): DealEvaluator {
        val queue = values.toList()
        var i = 0
        return DealEvaluator({ _, _ -> queue[i++ % queue.size] }, difficulty)
    }

    private val twoItems = deal(Term.NoAttack, Term.NoSettleNear)

    @Test
    fun `AI accepts when surplus reaches the difficulty margin`() {
        val verdict = evaluator(NegotiationDifficulty.Normal, 10f, -4f).evaluate(twoItems, "Rome")
        Assert.assertEquals(6f, verdict.surplus, 0.001f)
        Assert.assertTrue(verdict.accepted)
        Assert.assertEquals(listOf(10f, -4f), verdict.perItem)
    }

    @Test
    fun `hard AI demands a surplus that normal AI does not`() {
        Assert.assertFalse(evaluator(NegotiationDifficulty.Hard, 10f, -4f).evaluate(twoItems, "Rome").accepted)
        Assert.assertTrue(evaluator(NegotiationDifficulty.Easy, 2f, -6f).evaluate(twoItems, "Rome").accepted)
    }

    @Test
    fun `tone helps and hurts but is clamped so it cannot buy a bad deal`() {
        val polite = evaluator(NegotiationDifficulty.Hard, 10f, -4f).evaluate(twoItems, "Rome", tone = 100f)
        Assert.assertEquals(15f, polite.toneApplied, 0.001f)
        Assert.assertTrue(polite.accepted) // 6 + 15 = 21 >= 10
        val rude = evaluator(NegotiationDifficulty.Normal, 10f, -4f).evaluate(twoItems, "Rome", tone = -100f)
        Assert.assertEquals(-15f, rude.toneApplied, 0.001f)
        Assert.assertFalse(rude.accepted) // 6 - 15 = -9 < 0
        val hopeless = evaluator(NegotiationDifficulty.Normal, -50f, -50f).evaluate(twoItems, "Rome", tone = 100f)
        Assert.assertFalse(hopeless.accepted)
    }

    // ------------------------------------------------------------ builders

    @Test
    fun `no attack deal restrains the committer towards the beneficiary`() {
        val f = Fixture()
        val id = f.activate(DealBuilders.noAttack("Rome", "Player", turns = 8, now = 10)).single()
        Assert.assertTrue(f.manager.isForbidden("Rome", AiAction.DeclareWar("Player")))
        f.manager.onEvent(WorldEvent.WarDeclared("Rome", "Player"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(id))
    }

    @Test
    fun `loan quote reports the effective interest`() {
        val quote = DealBuilders.loanQuote(principal = 1000, repayPerTurn = 40, turns = 30)
        Assert.assertEquals(1200, quote.totalRepaid)
        Assert.assertEquals(20f, quote.interestPercent, 0.001f)
    }

    @Test
    fun `loan repayments depend on the lump sum having been paid`() {
        val draft = DealBuilders.loan("Rome", "Player", 500, 20, 30, now = 10)
        Assert.assertEquals(Term.PayGoldLump(500), draft.items[0].term)
        Assert.assertEquals(Condition.SiblingHonored(0), draft.items[1].condition)
        Assert.assertEquals(11, draft.items[1].activeFrom)
        Assert.assertEquals(40, draft.items[1].endTurn)
    }

    @Test
    fun `sibling references are resolved to real commitment ids when registered`() {
        val f = Fixture()
        val ids = f.activate(DealBuilders.loan("Rome", "Player", 500, 20, 30, now = 10))
        Assert.assertEquals(Condition.CommitmentHonored(ids[0]), f.manager.commitment(ids[1])!!.condition)
    }

    // ------------------------------------------------------------ facts for the language model

    @Test
    fun `facts list active commitments and recent true events`() {
        val f = Fixture()
        f.activate(DealBuilders.noSettleNear("Rome", "Player", 5, 10))
        f.manager.onEvent(WorldEvent.CitySettledNear("Rome", "Player"))
        val facts = NegotiationFacts.build(f.manager, speaker = "Rome", listener = "Player")
        Assert.assertTrue(facts.activeCommitments.isEmpty()) // breached, so no longer open
        Assert.assertTrue(facts.recentEvents.any { it.contains("CommitmentBreached") && it.contains("ViolatedByEvent") })

        val g = Fixture()
        g.activate(DealBuilders.noSettleNear("Rome", "Player", 5, 10))
        val open = NegotiationFacts.build(g.manager, "Rome", "Player")
        Assert.assertEquals(1, open.activeCommitments.size)
        Assert.assertTrue(open.activeCommitments.single().contains("Rome will not found cities near Player"))
    }

    @Test
    fun `the betrayer's facts contain the secret and the listener's do not`() {
        val f = Fixture(NegotiationDifficulty.Hard, { BetrayalAssessment(100f, 0f) })
        f.activate(DealBuilders.noSettleNear("Rome", "Player", 20, 10))
        f.advance(1)

        val romeFacts = NegotiationFacts.build(f.manager, "Rome", "Player")
        Assert.assertEquals(1, romeFacts.secrets.size)
        Assert.assertTrue(romeFacts.toPromptBlock().contains("SECRET"))
        Assert.assertTrue(romeFacts.recentEvents.any { it.contains("BetrayalDecided") && it.contains("benefit=100.0") })

        val playerFacts = NegotiationFacts.build(f.manager, "Player", "Rome")
        Assert.assertTrue(playerFacts.secrets.isEmpty())
        Assert.assertFalse(playerFacts.toPromptBlock().contains("SECRET"))
        Assert.assertTrue(playerFacts.recentEvents.none { it.contains("BetrayalDecided") }) // must not leak Rome's decision
    }

    @Test
    fun `after the betrayal the log explains it truthfully`() {
        val f = Fixture(NegotiationDifficulty.Hard, { BetrayalAssessment(100f, 0f) })
        f.activate(DealBuilders.noSettleNear("Rome", "Player", 20, 10))
        f.advance(1)
        f.manager.onEvent(WorldEvent.CitySettledNear("Rome", "Player"))
        val facts = NegotiationFacts.build(f.manager, "Rome", "Player")
        Assert.assertTrue(facts.secrets.isEmpty()) // nothing left to hide
        Assert.assertTrue(facts.recentEvents.any { it.contains("DeliberateBreach") })
        Assert.assertTrue(facts.recentEvents.any { it.contains("reputationCost=30.0") })
    }

    @Test
    fun `empty sections are omitted from the prompt block`() {
        val f = Fixture()
        val block = NegotiationFacts.build(f.manager, "Rome", "Player").toPromptBlock()
        Assert.assertEquals("You are Rome, speaking with Player.", block)
    }

    @Test
    fun `scripted backend replays replies and records requests`() {
        val backend = ScriptedLlmBackend(listOf("one", "two"))
        Assert.assertEquals("one", backend.generate(LlmRequest("sys", "hi")).text)
        Assert.assertEquals("two", backend.generate(LlmRequest("sys", "again", grammar = "root ::= x")).text)
        Assert.assertEquals("root ::= x", backend.requests[1].grammar)
    }
}
