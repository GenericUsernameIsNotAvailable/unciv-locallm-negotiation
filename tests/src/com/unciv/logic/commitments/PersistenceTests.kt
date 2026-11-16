package com.unciv.logic.commitments

import org.junit.Assert
import org.junit.Test

class PersistenceTests {

    private fun roundTrip(s: CommitmentSnapshot) = CommitmentSnapshotCodec.decode(CommitmentSnapshotCodec.encode(s))

    /** A fixture whose state touches every kind of term, condition, log entry and outbox message. */
    private fun richFixture(): Fixture {
        val f = Fixture(NegotiationDifficulty.Hard, { BetrayalAssessment(100f, 0f) })
        f.world.makeWar("Rome", "Persia")
        f.world.golds["Player"] = 0
        f.activate(DealBuilders.loan("Rome", "Player", 500, 20, 30, 10))
        f.activate(DealBuilders.proxyWarSubsidy("Greece", "Rome", "Persia", 5, 20, 10))
        f.activate(DealBuilders.productionNudge("Rome", "Player", "Harbor", 10, 10, 2.5f))
        f.activate(DealBuilders.conditionalWar("Greece", "Player", "Persia", 12, 30, 1.5f))
        f.activate(
            DealDraft(
                "Player", "Rome",
                listOf(
                    CommitmentDraft(
                        "Rome", "Player", Term.NoDeclareWar("Greece"),
                        Condition.AllOf(
                            listOf(
                                Condition.TurnAtLeast(12), Condition.AtWar("Rome", "Persia", true),
                                Condition.RelationshipAtLeast("Rome", "Player", Relationship.Favorable),
                                Condition.ForceRatioAtLeast("Rome", "Greece", 0.5f)
                            )
                        ),
                        10, 40
                    )
                )
            )
        )
        f.activate(DealBuilders.noSettleNear("Player", "Rome", 8, 10))
        // A deal still waiting for confirmation, and some history
        f.manager.propose(DealBuilders.noSettleNear("Greece", "Player", 6, 10))
        f.advance(2)
        f.manager.onEvent(WorldEvent.PeaceMade("Rome", "Persia")) // breaks the subsidy restraint, knowingly
        f.manager.onEvent(WorldEvent.CitySettledNear("Player", "Rome")) // the human breaks a promise: an outbox message
        return f
    }

    @Test
    fun `an empty snapshot round trips`() {
        Assert.assertEquals(CommitmentSnapshot.EMPTY, roundTrip(CommitmentSnapshot.EMPTY))
    }

    @Test
    fun `a rich state round trips exactly`() {
        val s = richFixture().manager.snapshot()
        Assert.assertTrue(s.commitments.size >= 7)
        Assert.assertTrue(s.deals.any { it.status == DealStatus.Proposed })
        Assert.assertTrue(s.log.any { it.reasons.any { r -> r.values.isNotEmpty() } })
        Assert.assertTrue(s.outbox.isNotEmpty())
        Assert.assertEquals(s, roundTrip(s))
    }

    @Test
    fun `the encoded form is stable`() {
        val text = CommitmentSnapshotCodec.encode(richFixture().manager.snapshot())
        Assert.assertEquals(text, CommitmentSnapshotCodec.encode(CommitmentSnapshotCodec.decode(text)))
        Assert.assertTrue(text.startsWith("V1\n"))
    }

    @Test
    fun `hostile characters in civ names survive`() {
        val f = Fixture()
        val weirdCommitter = "We|ird;Na,me\\n\\"
        val weirdBeneficiary = "Pla\nyer\r!"
        val draft = DealBuilders.noAttack(weirdCommitter, weirdBeneficiary, 5, 10)
        val id = (f.manager.propose(draft) as ProposalResult.Proposed).dealId
        f.manager.accept(id, weirdCommitter)
        val s = f.manager.snapshot()
        val back = roundTrip(s)
        Assert.assertEquals(s, back)
        Assert.assertEquals(weirdCommitter, back.commitments.single().committer)
        Assert.assertEquals(weirdBeneficiary, back.commitments.single().beneficiary)
    }

    @Test
    fun `a restored manager carries on exactly where the original stopped`() {
        // Control: one manager runs the whole loan without interruption.
        val control = Fixture()
        control.world.golds["Player"] = 200 // 500 borrowed + 200 own covers the 600 owed
        control.activate(DealBuilders.loan("Rome", "Player", 500, 20, 30, 10))
        control.advance(31)

        // Interrupted: save after 5 turns, continue with a brand new manager restored from the text.
        val first = Fixture()
        first.world.golds["Player"] = 200
        first.activate(DealBuilders.loan("Rome", "Player", 500, 20, 30, 10))
        first.advance(5)
        val saved = CommitmentSnapshotCodec.encode(first.manager.snapshot())

        val second = CommitmentManager(first.world, first.actions)
        second.restore(CommitmentSnapshotCodec.decode(saved))
        repeat(26) {
            first.world.currentTurn++
            second.tick()
        }
        Assert.assertEquals(control.world.gold("Player"), first.world.gold("Player"))
        Assert.assertEquals(control.world.gold("Rome"), first.world.gold("Rome"))
        Assert.assertTrue(second.allCommitments().all { it.status == CommitmentStatus.Fulfilled })
        Assert.assertEquals(control.manager.allCommitments().map { it.status }, second.allCommitments().map { it.status })
    }

    @Test
    fun `restoring keeps id counters so new deals do not collide`() {
        val f = Fixture()
        f.activate(DealBuilders.noSettleNear("Rome", "Player", 5, 10))
        val restored = CommitmentManager(f.world, f.actions)
        restored.restore(CommitmentSnapshotCodec.decode(CommitmentSnapshotCodec.encode(f.manager.snapshot())))
        val result = restored.propose(DealBuilders.noAttack("Rome", "Player", 5, 10)) as ProposalResult.Proposed
        Assert.assertEquals(2, result.dealId)
        Assert.assertEquals(listOf(1, 2), restored.allCommitments().map { it.id })
    }

    @Test
    fun `restoring replaces earlier state and does not announce a change`() {
        var changes = 0
        val f = Fixture()
        val manager = CommitmentManager(f.world, f.actions, onChanged = { changes++ })
        manager.propose(DealBuilders.noSettleNear("Rome", "Player", 5, 10))
        Assert.assertEquals(1, changes)
        manager.restore(CommitmentSnapshot.EMPTY)
        Assert.assertEquals(1, changes)
        Assert.assertTrue(manager.allCommitments().isEmpty())
        Assert.assertTrue(manager.log.entries.isEmpty())
    }

    @Test
    fun `listeners hear about real changes only`() {
        var changes = 0
        val f = Fixture()
        val manager = CommitmentManager(f.world, f.actions, onChanged = { changes++ })
        Assert.assertTrue(manager.propose(DealBuilders.noSettleNear("Rome", "Player", 5, 10)) is ProposalResult.Proposed)
        Assert.assertEquals(1, changes)
        manager.accept(1, "Rome")
        Assert.assertEquals(2, changes)

        manager.onEvent(WorldEvent.CitySettledNear("Greece", "Player")) // irrelevant to every commitment
        manager.accept(99, "Rome") // unknown deal
        Assert.assertEquals(2, changes)

        manager.onEvent(WorldEvent.CitySettledNear("Rome", "Player")) // breach
        Assert.assertEquals(3, changes)
        Assert.assertEquals(1, manager.drainOutbox().size)
        Assert.assertEquals(4, changes)
        Assert.assertTrue(manager.drainOutbox().isEmpty())
        Assert.assertEquals(4, changes)
    }

    @Test
    fun `only the newest log entries are saved`() {
        val f = Fixture()
        val manager = CommitmentManager(f.world, f.actions, policy = BreachPolicy(maxSavedLogEntries = 3))
        repeat(4) { i ->
            manager.propose(DealBuilders.noSettleNear("Rome", "Player", 5, 10))
            manager.reject(i + 1, "Rome")
        }
        Assert.assertTrue(manager.log.entries.size > 3)
        Assert.assertEquals(3, manager.snapshot().log.size)
        Assert.assertEquals(manager.log.entries.takeLast(3), manager.snapshot().log)
    }

    @Test
    fun `corrupt or foreign text is rejected, not half-loaded`() {
        for (bad in listOf("", "hello", "V9\nN|1|1", "V1\nN|x|1", "V1\nC|1|1|a", "V1\nL|1|NoSuchKind|a|b||")) {
            try {
                CommitmentSnapshotCodec.decode(bad)
                Assert.fail("accepted: $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `record types from a newer version are ignored`() {
        val text = CommitmentSnapshotCodec.encode(CommitmentSnapshot.EMPTY) + "Z|something|new\n"
        Assert.assertEquals(CommitmentSnapshot.EMPTY, CommitmentSnapshotCodec.decode(text))
    }
}
