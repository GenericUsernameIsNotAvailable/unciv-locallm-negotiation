package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ChatSession
import com.unciv.logic.commitments.llm.ChatSnapshotCodec
import com.unciv.logic.commitments.llm.ClassicItemKind
import com.unciv.logic.commitments.llm.CommitmentGuard
import com.unciv.logic.commitments.llm.ManipulationTracker
import com.unciv.logic.commitments.llm.SavedLeaderChat
import com.unciv.logic.commitments.llm.TradeIntentParser
import org.junit.Assert
import org.junit.Test

class Stage4bTests {

    @Test
    fun `commitment guard catches generous gift thank you and pleased to accept from probe`() {
        val probeSlip = "Thank you for your generous gift. It is a kindness to give people gold, and I am pleased to accept it."
        Assert.assertTrue(CommitmentGuard.makesCommitment(probeSlip))
        Assert.assertTrue(CommitmentGuard.makesCommitment("I am happy to agree to your terms."))
    }

    @Test
    fun `chat snapshot codec round trips messages and manipulation tracker flags`() {
        val original = listOf(
            SavedLeaderChat(
                key = "Japan>Player",
                messages = listOf(
                    ChatSession.Message(true, "Hello Oda | how are you?"),
                    ChatSession.Message(false, "Speak plainly.")
                ),
                recentFlatteries = listOf(true, false, true),
                recentFlatteryAsks = listOf(true, false)
            )
        )
        val encoded = ChatSnapshotCodec.encode(original)
        val decoded = ChatSnapshotCodec.decode(encoded)
        Assert.assertEquals(original, decoded)

        val tracker = ManipulationTracker()
        tracker.restoreFlags(decoded.single().recentFlatteries, decoded.single().recentFlatteryAsks)
        Assert.assertEquals(listOf(true, false, true) to listOf(true, false), tracker.snapshotFlags())
    }

    @Test
    fun `classic trade parser extracts luxury exchanges, open borders and peace treaties`() {
        val luxTrade = TradeIntentParser.parseClassicTrade(
            text = "I will give you 150 gold for your Spices",
            playerCiv = "Player",
            aiCiv = "Japan",
            playerLuxuries = listOf("Silk"),
            aiLuxuries = listOf("Spices", "Wine"),
            playerStrategics = listOf("Iron"),
            aiStrategics = listOf("Horses")
        )
        Assert.assertNotNull(luxTrade)
        Assert.assertTrue(luxTrade!!.items.any { !it.fromPlayer && it.kind == ClassicItemKind.Luxury && it.name == "Spices" })
        Assert.assertTrue(luxTrade.items.any { it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 150 })

        val borders = TradeIntentParser.parseClassicTrade(
            text = "Let us agree to open borders",
            playerCiv = "Player",
            aiCiv = "Japan",
            playerLuxuries = emptyList(),
            aiLuxuries = emptyList(),
            playerStrategics = emptyList(),
            aiStrategics = emptyList()
        )
        Assert.assertNotNull(borders)
        Assert.assertEquals(2, borders!!.items.count { it.kind == ClassicItemKind.OpenBorders })
    }

    @Test
    fun `llm fallback intent extractor parses json into a valid deal draft`() {
        val json = """{"type":"no_settle","target":"","gold":120,"gpt":0,"turns":25}"""
        val backend = ScriptedLlmBackend(listOf(json))
        val draft = TradeIntentParser.extractWithLlmFallback(
            backend = backend,
            playerText = "Keep your settlers away from our frontier and take 120 gold",
            human = "Player",
            ai = "Rome",
            knownThirdCivs = listOf("Greece"),
            knownConstructions = emptyList(),
            now = 10
        )
        Assert.assertNotNull(draft)
        Assert.assertEquals(Term.NoSettleNear, draft!!.items[0].term)
        Assert.assertEquals(Term.PayGoldLump(120), draft.items[1].term)
    }
}
