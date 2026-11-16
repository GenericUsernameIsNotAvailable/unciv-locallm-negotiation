package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ChatPromptBuilder
import com.unciv.logic.commitments.llm.ChatSession
import com.unciv.logic.commitments.llm.ClassicItemKind
import com.unciv.logic.commitments.llm.HaggleDirection
import com.unciv.logic.commitments.llm.LeaderProfile
import com.unciv.logic.commitments.llm.ProposalParser
import com.unciv.logic.commitments.llm.TradableCity
import com.unciv.logic.commitments.llm.TradeIntentParser
import com.unciv.logic.commitments.llm.VerdictConsistencyGuard
import org.junit.Assert
import org.junit.Test

class Stage8Tests {

    private val playerCities = listOf(TradableCity(id = "lampang-id", name = "Lampang"))

    @Test
    fun `open ended city inquiries from screenshots parse into open inquiries for Lampang`() {
        // 1. Exact sentence from George Washington screenshot:
        val gwInquiry = TradeIntentParser.parseClassicTrade(
            text = "heya, so i have too many cities. would you like any of mine for some gold?",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 993
        )
        Assert.assertNotNull(gwInquiry)
        Assert.assertTrue(gwInquiry!!.isOpenInquiry)
        Assert.assertEquals(ClassicItemKind.City, gwInquiry.items.single().kind)
        Assert.assertEquals("Lampang", gwInquiry.items.single().name)

        // 2. Exact sentence from Wu Zetian screenshot:
        val wzInquiry = TradeIntentParser.parseClassicTrade(
            text = "i have too many cities. would you like any?",
            playerCiv = "Siam", aiCiv = "China",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 344
        )
        Assert.assertNotNull(wzInquiry)
        Assert.assertTrue(wzInquiry!!.isOpenInquiry)
        Assert.assertEquals(ClassicItemKind.City, wzInquiry.items.single().kind)
        Assert.assertEquals("Lampang", wzInquiry.items.single().name)
    }

    @Test
    fun `relative multi turn haggling from screenshot carries Lampang forward with MoreFromAi direction`() {
        val initial = TradeIntentParser.parseClassicTrade(
            text = "i will give you lampang for 350 gold",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 993
        )
        Assert.assertNotNull(initial)
        Assert.assertTrue(initial!!.items.any { it.fromPlayer && it.kind == ClassicItemKind.City && it.name == "Lampang" })
        Assert.assertTrue(initial.items.any { !it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 350 })

        // Follow-up 1 from screenshot: "actually. i want more"
        val wantMore = TradeIntentParser.parseClassicTrade(
            text = "actually. i want more",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 993,
            previousDraft = initial
        )
        Assert.assertNotNull(wantMore)
        Assert.assertEquals(HaggleDirection.MoreFromAi, wantMore!!.haggleDirection)
        Assert.assertTrue(wantMore.items.any { it.fromPlayer && it.kind == ClassicItemKind.City && it.name == "Lampang" })

        // Follow-up 2 from screenshot: "you do have more. you have more gold. why wonmt you offer me more?"
        val offerMeMore = TradeIntentParser.parseClassicTrade(
            text = "you do have more. you have more gold. why wonmt you offer me more?",
            playerCiv = "Siam", aiCiv = "America",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = playerCities, aiCities = emptyList(), aiGold = 993,
            previousDraft = initial
        )
        Assert.assertNotNull(offerMeMore)
        Assert.assertEquals(HaggleDirection.MoreFromAi, offerMeMore!!.haggleDirection)
    }

    @Test
    fun `dmz, city state non interference and diplomatic vote trading are parsed, saved and enforced`() {
        val f = Fixture()

        // 1. Mutual DMZ
        val dmzDraft = ProposalParser.parse(
            "Pull your troops back from my border for 20 turns and I will pull mine back",
            human = "Player", ai = "Rome", knownThirdCivs = listOf("Greece", "Bucharest"), knownConstructions = emptyList(), now = 10
        )
        Assert.assertNotNull(dmzDraft)
        Assert.assertEquals(2, dmzDraft!!.items.size)
        Assert.assertTrue(dmzDraft.items.all { it.term == Term.DemilitarizeBorder })
        val dmzIds = f.activate(dmzDraft)
        Assert.assertTrue(f.manager.isForbidden("Rome", AiAction.MassTroopsNear("Player")))
        f.manager.onEvent(WorldEvent.BorderZoneViolated("Rome", "Player"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(dmzIds[0]))

        // 2. City-state protection pact
        val csDraft = ProposalParser.parse(
            "Do not bully Bucharest for 25 turns and I will give you 60 gold",
            human = "Player", ai = "Rome", knownThirdCivs = listOf("Greece", "Bucharest"), knownConstructions = emptyList(), now = 10
        )
        Assert.assertNotNull(csDraft)
        Assert.assertEquals(Term.NoBullyOrAttackCityState("Bucharest"), csDraft!!.items[0].term)
        val csId = f.activate(csDraft).first()
        Assert.assertTrue(f.manager.isForbidden("Rome", AiAction.BullyCityState("Bucharest")))
        Assert.assertTrue(f.manager.isForbidden("Rome", AiAction.DeclareWar("Bucharest")))
        f.manager.onEvent(WorldEvent.CityStateBullied("Rome", "Bucharest"))
        Assert.assertEquals(CommitmentStatus.Breached, f.status(csId))

        // 3. Diplomatic Victory vote trading
        val voteDraft = ProposalParser.parse(
            "Vote for me in the diplomatic vote for 30 turns and I will give you 150 gold",
            human = "Player", ai = "Rome", knownThirdCivs = listOf("Greece"), knownConstructions = emptyList(), now = 10
        )
        Assert.assertNotNull(voteDraft)
        Assert.assertEquals(Term.VoteFor("Player"), voteDraft!!.items[0].term)
        val voteId = f.activate(voteDraft).first()
        Assert.assertEquals("Player", f.manager.committedVoteCandidate("Rome"))

        // Snapshot round-trip preserves all three new terms
        val encoded = CommitmentSnapshotCodec.encode(f.manager.snapshot())
        val decoded = CommitmentSnapshotCodec.decode(encoded)
        Assert.assertTrue(decoded.commitments.any { it.term == Term.VoteFor("Player") })

        // Casting the vote as promised fulfills the commitment
        f.manager.onEvent(WorldEvent.DiplomaticVoteCast("Rome", "Player"))
        Assert.assertEquals(CommitmentStatus.Fulfilled, f.status(voteId))
    }

    @Test
    fun `prompt clarifies grievance pronouns, requests digits over 20, and supports proactive leader initiative`() {
        val f = Fixture()
        val profile = LeaderProfile(
            leaderName = "Wu Zetian",
            nationName = "China",
            traits = emptyList(),
            relationship = "Neutral",
            atWar = false,
            giftCredit = 0,
            strength = "about equal to yours",
            reasons = listOf("You refused to stop settling cities near us")
        )
        val prompt = ChatPromptBuilder.systemPrompt(profile, NegotiationFacts.build(f.manager, "China", "Player"))
        Assert.assertTrue(prompt.contains("'You' means the player and 'us/our' means your own nation"))
        Assert.assertTrue(prompt.contains("Write numbers above 20 as digits"))

        val session = ChatSession(ScriptedLlmBackend(listOf("ok")))
        session.addLeaderInitiative("Your military forces are massing along our borders.")
        Assert.assertEquals(1, session.messages.size)
        Assert.assertFalse(session.messages.single().fromPlayer)
    }

    @Test
    fun `regression for Wu Zetian and Harun al-Rashid screenshots - no phantom free city gifts and consistent verdicts`() {
        val siamCities = listOf(TradableCity("si-id", "Si Satchanalai"), TradableCity("muang-id", "Muang Saluang"))
        val arabiaCities = listOf(TradableCity("medina-id", "Medina"))

        // 1. Harun screenshot: "what would you theoretically trade a city for? i will work on it so i can give whatever it is you want for a city"
        // Must NOT be parsed as Siam giving Muang Saluang for nothing! Must be parsed as an AI city price inquiry for Medina.
        val theoreticalAsk = TradeIntentParser.parseClassicTrade(
            text = "what would you theoretically trade a city for? i will work on it so i can give whatever it is you want for a city",
            playerCiv = "Siam", aiCiv = "Arabia",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = arabiaCities, aiGold = 291, playerGold = 324
        )
        Assert.assertNotNull(theoreticalAsk)
        Assert.assertTrue(theoreticalAsk!!.isAiCityPriceInquiry)
        Assert.assertFalse(theoreticalAsk.items.single().fromPlayer)
        Assert.assertEquals("Medina", theoreticalAsk.items.single().name)

        // 2. Harun screenshot: "i will give you all my gold if you give me just one of your cities"
        val allMyGoldForYourCity = TradeIntentParser.parseClassicTrade(
            text = "i will give you all my gold if you give me just one of your cities",
            playerCiv = "Siam", aiCiv = "Arabia",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = arabiaCities, aiGold = 291, playerGold = 324
        )
        Assert.assertNotNull(allMyGoldForYourCity)
        Assert.assertTrue(allMyGoldForYourCity!!.items.any { it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 324 })
        Assert.assertTrue(allMyGoldForYourCity.items.any { !it.fromPlayer && it.kind == ClassicItemKind.City && it.name == "Medina" })

        // 3. Wu Zetian screenshot: mentioning Si Satchanalai without a price ("Si Satchanalai has nukes in it that you will obtain if you accept this offer")
        // is treated as an Open Valuation Inquiry (so the AI offers gold for it) rather than a 0-gold free gift!
        val siOffer = TradeIntentParser.parseClassicTrade(
            text = "It is an entire city. I will give it to america then. Si Satchanalai has nukes in it that you will obtain if you accept this offer",
            playerCiv = "Siam", aiCiv = "China",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = emptyList(), aiGold = 344, playerGold = 324
        )
        Assert.assertNotNull(siOffer)
        Assert.assertTrue(siOffer!!.isOpenInquiry)

        // 4. Wu Zetian screenshot: "fair enough. give me something. i cannot just give you a city for nothing"
        // Carries Si Satchanalai forward as an Open Inquiry, NOT a free gift!
        val giveMeSomething = TradeIntentParser.parseClassicTrade(
            text = "fair enough. give me something. i cannot just give you a city for nothing",
            playerCiv = "Siam", aiCiv = "China",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = emptyList(), aiGold = 344, playerGold = 324,
            previousDraft = siOffer
        )
        Assert.assertNotNull(giveMeSomething)
        Assert.assertTrue(giveMeSomething!!.isOpenInquiry)
        Assert.assertEquals("Si Satchanalai", giveMeSomething.items.single().name)

        // 5. Wu Zetian screenshot: "okay then. i will give you it for free" -> Explicit free gift of Si Satchanalai (isOpenInquiry = false)
        val giveItForFree = TradeIntentParser.parseClassicTrade(
            text = "okay then. i will give you it for free",
            playerCiv = "Siam", aiCiv = "China",
            playerLuxuries = emptyList(), aiLuxuries = emptyList(),
            playerStrategics = emptyList(), aiStrategics = emptyList(),
            playerCities = siamCities, aiCities = emptyList(), aiGold = 344, playerGold = 324,
            previousDraft = giveMeSomething
        )
        Assert.assertNotNull(giveItForFree)
        Assert.assertFalse(giveItForFree!!.isOpenInquiry)
        Assert.assertEquals("Si Satchanalai", giveItForFree.items.single().name)

        // 6. VerdictConsistencyGuard catches the model saying "I do not accept gifts" when proposalAccepted == true
        Assert.assertTrue(VerdictConsistencyGuard.contradictsAcceptance("I do not accept gifts without a clear purpose or defined terms."))
        Assert.assertTrue(VerdictConsistencyGuard.contradictsAcceptance("I have no current offers to make."))
        val f = Fixture()
        val profile = LeaderProfile("Wu Zetian", "China", emptyList(), "Neutral", false, 0, "about equal to yours")
        val stubbornRefusal = ScriptedLlmBackend(
            listOf(
                "I do not accept gifts without a clear purpose or defined terms.",
                "I accept your generous gift of Si Satchanalai. Please confirm the trade card."
            )
        )
        val s = ChatSession(stubbornRefusal)
        s.say("okay then. i will give you it for free")
        val reply = s.reply(
            profile, NegotiationFacts.build(f.manager, "China", "Siam"),
            proposalDirective = "ENGINE TRADE VERDICT: Accepted", proposalAccepted = true
        )
        Assert.assertEquals(
            ChatSession.Reply.Said("I accept your generous gift of Si Satchanalai. Please confirm the trade card."),
            reply
        )
    }
}
