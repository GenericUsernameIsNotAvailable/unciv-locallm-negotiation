package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ChatPromptBuilder
import com.unciv.logic.commitments.llm.ChatSession
import com.unciv.logic.commitments.llm.ChatSnapshotCodec
import com.unciv.logic.commitments.llm.JniLlamaCppBridge
import com.unciv.logic.commitments.llm.LeaderProfile
import com.unciv.logic.commitments.llm.LlmMessage
import com.unciv.logic.commitments.llm.LlmRequest
import com.unciv.logic.commitments.llm.ReplyCleaner
import com.unciv.logic.commitments.llm.SavedLeaderChat
import org.junit.Assert
import org.junit.Test

class Stage14Tests {

    @Test
    fun `Gemma 4 prompt uses official turn and empty thought channel tokens without start_of_turn or double bos`() {
        val req = LlmRequest(
            system = "You are Suleiman I, ruler of The Ottomans.",
            user = "hi",
            history = listOf(
                LlmMessage("user", "Greetings Sultan."),
                LlmMessage("assistant", "Speak quickly, envoy.")
            )
        )
        val prompt = JniLlamaCppBridge.buildPromptForModel(req, "/storage/llm-models/gemma-4-e2b-it-Q4_K_M.gguf")
        Assert.assertFalse("Gemma 4 must NOT use legacy <start_of_turn>", prompt.contains("<start_of_turn>"))
        Assert.assertFalse("Gemma 4 prompt must NOT prepend literal <bos>", prompt.startsWith("<bos>"))
        Assert.assertTrue(prompt.startsWith("<|turn>system\nYou are Suleiman I"))
        Assert.assertTrue(prompt.contains("<|turn>user\nGreetings Sultan.<turn|>\n"))
        Assert.assertTrue(prompt.contains("<|turn>model\nSpeak quickly, envoy.<turn|>\n"))
        Assert.assertTrue(prompt.endsWith("<|turn>user\nhi<turn|>\n<|turn>model\n<|channel>thought\n<channel|>"))
    }

    @Test
    fun `ReplyCleaner rejects leaked Thinking Process and strips Gemma 4 channel tags`() {
        val leaked = "Thinking Process: 1. Analyze the User Input: The player simply said hi. This is a greeting."
        Assert.assertNull("Leaked thinking process must return null so ChatSession retries", ReplyCleaner.clean(leaked, "Suleiman I"))

        val withChannel = "<|channel>thought\nLet me greet him proudly.<channel|>Hail, envoy of Byzantium! State your business."
        Assert.assertEquals("Hail, envoy of Byzantium! State your business.", ReplyCleaner.clean(withChannel, "Suleiman I"))
    }

    @Test
    fun `warlike AI leaders do not spam Non-Aggression Pacts in peacetime unless afraid or fighting another war`() {
        // Harald Bluetooth (DeclareWar = 8f) at peace with Favorable relations does NOT spam a Non-Aggression Pact
        val aggressivePeace = ProactiveDiplomacy.considerProposal(
            aiCiv = "Denmark",
            aiLeaderName = "Harald Bluetooth",
            humanCiv = "Byzantium",
            currentTurn = 51,
            lastProposalTurn = null,
            relationship = Relationship.Favorable,
            atWarWithHuman = false,
            commonEnemiesOfAi = emptyList(),
            aiGold = 200,
            aiGoldPerTurn = 10,
            existingOpenCommitments = emptyList(),
            willingnessToDeclareWar = 8f,
            isAtWarWithAnyone = false
        )
        Assert.assertNull("Aggressive leader at peace should not beg for a Non-Aggression Pact", aggressivePeace)

        // Same aggressive leader when Afraid DOES propose a Non-Aggression Pact
        val afraidPact = ProactiveDiplomacy.considerProposal(
            aiCiv = "Denmark",
            aiLeaderName = "Harald Bluetooth",
            humanCiv = "Byzantium",
            currentTurn = 51,
            lastProposalTurn = null,
            relationship = Relationship.Afraid,
            atWarWithHuman = false,
            commonEnemiesOfAi = emptyList(),
            aiGold = 200,
            aiGoldPerTurn = 10,
            existingOpenCommitments = emptyList(),
            willingnessToDeclareWar = 8f,
            isAtWarWithAnyone = false
        )
        Assert.assertNotNull(afraidPact)
    }

    @Test
    fun `ChatSnapshotCodec persists lastProactiveTurn across save and load`() {
        val saved = listOf(
            SavedLeaderChat(
                key = "Denmark>Byzantium",
                messages = listOf(ChatSession.Message(false, "Let us sign a pact.")),
                lastProactiveTurn = 51
            )
        )
        val encoded = ChatSnapshotCodec.encode(saved)
        val decoded = ChatSnapshotCodec.decode(encoded)
        Assert.assertEquals(1, decoded.size)
        Assert.assertEquals(51, decoded.single().lastProactiveTurn)
    }

    @Test
    fun `systemPrompt instructs leaders to stay in historical character and avoid modern chatbot clichés`() {
        val f = Fixture()
        val profile = LeaderProfile(
            leaderName = "Harald Bluetooth",
            nationName = "Denmark",
            traits = listOf("bold", "warlike"),
            relationship = "Neutral",
            atWar = false,
            giftCredit = 0,
            strength = "greater than yours"
        )
        val prompt = ChatPromptBuilder.systemPrompt(profile, NegotiationFacts.build(f.manager, "Denmark", "Byzantium"))
        Assert.assertTrue(prompt.contains("Never sound like a modern chatbot"))
    }
}
