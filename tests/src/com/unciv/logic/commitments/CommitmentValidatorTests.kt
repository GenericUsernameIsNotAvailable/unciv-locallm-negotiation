package com.unciv.logic.commitments

import org.junit.Assert
import org.junit.Test

class CommitmentValidatorTests {

    private fun codes(f: Fixture, draft: DealDraft): Set<ValidationCode> =
        CommitmentValidator(f.world).validateDeal(draft, f.manager.allCommitments()).map { it.code }.toSet()

    private fun single(committer: CivName, beneficiary: CivName, term: Term, from: Int = 10, to: Int = 20, condition: Condition? = null) =
        DealDraft(beneficiary, committer, listOf(CommitmentDraft(committer, beneficiary, term, condition, from, to)))

    @Test
    fun `the standard deal shapes are valid`() {
        val f = Fixture()
        f.world.makeWar("Rome", "Persia")
        val drafts = listOf(
            DealBuilders.loan("Rome", "Player", 500, 20, 30, 10),
            DealBuilders.proxyWarSubsidy("Player", "Rome", "Persia", 50, 20, 10),
            DealBuilders.conditionalWar("Rome", "Player", "Greece", 12, 30, 1.5f),
            DealBuilders.productionNudge("Rome", "Player", "Harbor", 10, 10),
            DealBuilders.noSettleNear("Rome", "Player", 10, 10)
        )
        for (d in drafts) Assert.assertEquals(emptySet<ValidationCode>(), codes(f, d))
    }

    @Test
    fun `empty deals and deals with oneself are rejected`() {
        val f = Fixture()
        Assert.assertEquals(setOf(ValidationCode.EmptyDeal), codes(f, DealDraft("Player", "Rome", emptyList())))
        val selfDeal = DealDraft("Rome", "Rome", listOf(CommitmentDraft("Rome", "Rome", Term.NoAttack, null, 10, 20)))
        Assert.assertTrue(ValidationCode.SameParty in codes(f, selfDeal))
    }

    @Test
    fun `third civs cannot be dragged in as committer or beneficiary`() {
        val f = Fixture()
        val draft = DealDraft("Player", "Rome", listOf(CommitmentDraft("Greece", "Player", Term.NoAttack, null, 10, 20)))
        Assert.assertTrue(ValidationCode.NotAParty in codes(f, draft))
    }

    @Test
    fun `time windows must be sane and not too far ahead`() {
        val f = Fixture()
        Assert.assertTrue(ValidationCode.BadTiming in codes(f, single("Rome", "Player", Term.NoAttack, from = 9, to = 20)))
        Assert.assertTrue(ValidationCode.BadTiming in codes(f, single("Rome", "Player", Term.NoAttack, from = 15, to = 14)))
        Assert.assertTrue(ValidationCode.HorizonTooFar in codes(f, single("Rome", "Player", Term.NoAttack, from = 10, to = 111)))
        Assert.assertEquals(emptySet<ValidationCode>(), codes(f, single("Rome", "Player", Term.NoAttack, from = 10, to = 110)))
    }

    @Test
    fun `numeric parameters are bounded`() {
        val f = Fixture()
        Assert.assertTrue(ValidationCode.BadParameter in codes(f, single("Rome", "Player", Term.PayGoldPerTurn(0))))
        Assert.assertTrue(ValidationCode.BadParameter in codes(f, single("Rome", "Player", Term.PayGoldPerTurn(1001))))
        Assert.assertTrue(ValidationCode.BadParameter in codes(f, single("Rome", "Player", Term.PayGoldLump(5001))))
        Assert.assertTrue(ValidationCode.BadParameter in codes(f, single("Rome", "Player", Term.PrioritizeConstruction("", 3f))))
        Assert.assertTrue(ValidationCode.BadParameter in codes(f, single("Rome", "Player", Term.PrioritizeConstruction("Harbor", 50f))))
        val badRatio = single("Rome", "Player", Term.DeclareWar("Persia"), condition = Condition.ForceRatioAtLeast("Rome", "Persia", 99f))
        Assert.assertTrue(ValidationCode.BadParameter in codes(f, badRatio))
    }

    @Test
    fun `a lump sum must be affordable`() {
        val f = Fixture()
        f.world.golds["Rome"] = 100
        Assert.assertTrue(ValidationCode.InsufficientGold in codes(f, single("Rome", "Player", Term.PayGoldLump(500))))
    }

    @Test
    fun `war terms are checked against the diplomatic situation`() {
        val f = Fixture()
        Assert.assertTrue(ValidationCode.NotAtWar in codes(f, single("Rome", "Player", Term.NoPeaceWith("Persia"))))
        Assert.assertTrue(ValidationCode.BadTarget in codes(f, single("Rome", "Player", Term.DeclareWar("Player"))))
        Assert.assertTrue(ValidationCode.BadTarget in codes(f, single("Rome", "Player", Term.DeclareWar("Rome"))))
        f.world.pacts += setOf("Rome", "Persia")
        Assert.assertTrue(ValidationCode.DefensivePact in codes(f, single("Rome", "Player", Term.DeclareWar("Persia"))))
        f.world.pacts.clear()
        f.world.makeWar("Rome", "Persia")
        Assert.assertTrue(ValidationCode.AlreadyAtWar in codes(f, single("Rome", "Player", Term.DeclareWar("Persia"))))
    }

    @Test
    fun `promising a war the same civ has promised not to start is a contradiction`() {
        val f = Fixture()
        f.activate(single("Rome", "Player", Term.NoDeclareWar("Persia")))
        Assert.assertTrue(ValidationCode.Contradiction in codes(f, single("Rome", "Player", Term.DeclareWar("Persia"))))
        // Other direction, and via NoAttack on the beneficiary
        val g = Fixture()
        g.activate(single("Rome", "Player", Term.DeclareWar("Greece")))
        Assert.assertTrue(ValidationCode.Contradiction in codes(g, single("Rome", "Player", Term.NoDeclareWar("Greece"))))
        Assert.assertTrue(ValidationCode.Contradiction in codes(g, single("Rome", "Greece", Term.NoAttack)))
    }

    @Test
    fun `contradictions inside one deal are caught too`() {
        val f = Fixture()
        val draft = DealDraft(
            "Player", "Rome",
            listOf(
                CommitmentDraft("Rome", "Player", Term.DeclareWar("Persia"), null, 10, 20),
                CommitmentDraft("Rome", "Player", Term.NoDeclareWar("Persia"), null, 10, 20)
            )
        )
        Assert.assertTrue(ValidationCode.Contradiction in codes(f, draft))
    }

    @Test
    fun `an identical open commitment is a duplicate`() {
        val f = Fixture()
        f.activate(single("Rome", "Player", Term.NoSettleNear))
        Assert.assertTrue(ValidationCode.DuplicateTerm in codes(f, single("Rome", "Player", Term.NoSettleNear)))
    }

    @Test
    fun `open commitments between a pair are capped`() {
        val f = Fixture()
        for (amount in 1..5) f.activate(single("Rome", "Player", Term.PayGoldPerTurn(amount)))
        Assert.assertTrue(ValidationCode.TooManyCommitments in codes(f, single("Rome", "Player", Term.PayGoldPerTurn(6))))
        val result = f.manager.propose(single("Rome", "Player", Term.PayGoldPerTurn(6)))
        Assert.assertTrue(result is ProposalResult.Rejected)
    }

    @Test
    fun `references to other deal items and commitments must resolve`() {
        val f = Fixture()
        val dangling = single("Rome", "Player", Term.NoSettleNear, condition = Condition.SiblingHonored(3))
        Assert.assertTrue(ValidationCode.UnresolvedReference in codes(f, dangling))
        val selfRef = single("Rome", "Player", Term.NoSettleNear, condition = Condition.SiblingHonored(0))
        Assert.assertTrue(ValidationCode.UnresolvedReference in codes(f, selfRef))
        val unknown = single("Rome", "Player", Term.NoSettleNear, condition = Condition.CommitmentHonored(42))
        Assert.assertTrue(ValidationCode.UnresolvedReference in codes(f, unknown))
        val nested = single("Rome", "Player", Term.NoSettleNear, condition = Condition.AllOf(listOf(Condition.TurnAtLeast(5), Condition.SiblingHonored(9))))
        Assert.assertTrue(ValidationCode.UnresolvedReference in codes(f, nested))
    }
}
