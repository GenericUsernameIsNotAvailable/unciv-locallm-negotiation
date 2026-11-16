package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.BetrayalAssessment
import com.unciv.logic.commitments.DealBuilders
import com.unciv.logic.commitments.Fixture
import com.unciv.logic.commitments.NegotiationDifficulty
import com.unciv.logic.commitments.NegotiationFacts
import com.unciv.logic.commitments.ScriptedLlmBackend
import org.junit.Assert
import org.junit.Test

class LeaderChatTests {
    private val profile = LeaderProfile("Caesar", "Rome", listOf("militaristic", "greedy"), "Favorable", false, 0, "stronger than yours")

    private fun plainFacts() = NegotiationFacts.build(Fixture().manager, "Rome", "Player")

    private class FailingBackend(private val failure: RuntimeException) : LlmBackend {
        override val isAvailable = true
        override fun generate(request: LlmRequest): LlmResponse = throw failure
    }

    // ------------------------------------------------------------ prompt

    @Test
    fun `the prompt describes the leader, the relationship and the limits`() {
        val prompt = ChatPromptBuilder.systemPrompt(profile.copy(atWar = true, giftCredit = 50), plainFacts())
        for (expected in listOf("Caesar", "Rome", "militaristic, greedy", "Favorable", "at war", "generous", "stronger than yours", "Do not agree to", "Do not invent", "may lie")) {
            Assert.assertTrue("missing '$expected' in:\n$prompt", prompt.contains(expected, ignoreCase = true))
        }
    }

    @Test
    fun `quiet details are left out`() {
        val prompt = ChatPromptBuilder.systemPrompt(profile.copy(traits = emptyList()), plainFacts())
        Assert.assertTrue(prompt.contains("ordinary"))
        Assert.assertFalse(prompt.contains("at war", ignoreCase = true))
        Assert.assertFalse(prompt.contains("generous"))
        Assert.assertFalse(prompt.contains("COMMITMENTS"))
    }

    @Test
    fun `engine facts reach the prompt, including a secret the leader keeps`() {
        val f = Fixture(NegotiationDifficulty.Hard, { BetrayalAssessment(100f, 0f) })
        f.activate(DealBuilders.noSettleNear("Rome", "Player", 20, 10))
        f.advance(1)
        val prompt = ChatPromptBuilder.systemPrompt(profile, NegotiationFacts.build(f.manager, "Rome", "Player"))
        Assert.assertTrue(prompt.contains("Rome will not found cities near Player"))
        Assert.assertTrue(prompt.contains("SECRET"))
        Assert.assertTrue(prompt.contains("You have decided to break commitment #1"))
        Assert.assertTrue(prompt.contains("Do not reveal anything marked SECRET"))
        Assert.assertTrue(prompt.contains("Do not agree to, accept, offer or promise anything"))
        Assert.assertEquals(1, Regex("You are Caesar").findAll(prompt).count()) // the facts header is not repeated
    }

    @Test
    fun `military strength is described relative to the player`() {
        Assert.assertEquals("far greater than yours", LeaderProfile.describeStrength(200f, 100f))
        Assert.assertEquals("greater than yours", LeaderProfile.describeStrength(130f, 100f))
        Assert.assertEquals("about equal to yours", LeaderProfile.describeStrength(100f, 100f))
        Assert.assertEquals("smaller than yours", LeaderProfile.describeStrength(60f, 100f))
        Assert.assertEquals("far smaller than yours", LeaderProfile.describeStrength(20f, 100f))
        Assert.assertEquals("far greater than yours", LeaderProfile.describeStrength(50f, 0f))
        Assert.assertEquals("similar to yours", LeaderProfile.describeStrength(0f, 0f))
    }

    // ------------------------------------------------------------ tone comes from the engine

    @Test
    fun `no fixed attitude is imposed on every leader`() {
        val prompt = ChatPromptBuilder.systemPrompt(profile.copy(relationship = "Friend"), plainFacts())
        for (forbidden in listOf("guarded", "shrewd", "do not fully trust", "proud")) {
            Assert.assertFalse("prompt still says '$forbidden'", prompt.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun `the leaders demeanour follows the relationship and war state`() {
        fun demeanor(rel: String, war: Boolean = false) = LeaderProfile.describeDemeanor(rel, war)
        Assert.assertTrue(demeanor("Ally").contains("warmly"))
        Assert.assertTrue(demeanor("Friend").contains("warmly"))
        Assert.assertTrue(demeanor("Neutral").contains("politely"))
        Assert.assertTrue(demeanor("Enemy").contains("harshly"))
        Assert.assertTrue(demeanor("Unforgivable").contains("contempt"))
        Assert.assertTrue(demeanor("Friend", war = true).contains("at war"))
        Assert.assertEquals(8, listOf("Ally", "Friend", "Favorable", "Neutral", "Competitor", "Afraid", "Enemy", "Unforgivable").map { demeanor(it) }.toSet().size)

        val kind = ChatPromptBuilder.systemPrompt(profile.copy(relationship = "Friend"), plainFacts())
        val harsh = ChatPromptBuilder.systemPrompt(profile.copy(relationship = "Enemy"), plainFacts())
        Assert.assertTrue(kind.contains("speak warmly"))
        Assert.assertTrue(harsh.contains("speak harshly"))
        Assert.assertFalse(kind.contains("harshly"))
        Assert.assertFalse(harsh.contains("warmly"))
    }

    @Test
    fun `the leaders own authored lines and nation background reach the prompt`() {
        val withVoice = profile.copy(
            voiceSamples = listOf("Oh, it's you...", "We hope for a fair and just relationship with you."),
            background = "Our people lived and died with honor."
        )
        val prompt = ChatPromptBuilder.systemPrompt(withVoice, plainFacts())
        Assert.assertTrue(prompt.contains("- \"Oh, it's you...\""))
        Assert.assertTrue(prompt.contains("We hope for a fair and just relationship"))
        Assert.assertTrue(prompt.contains("About your nation: Our people lived and died with honor."))
        val plain = ChatPromptBuilder.systemPrompt(profile, plainFacts())
        Assert.assertFalse(plain.contains("About your nation"))
        Assert.assertFalse(plain.contains("Some of your own words"))
    }

    @Test
    fun `nation intro text becomes first person background`() {
        val intro = "Lord Oda Nobunaga, ruler of Japan, is at your command! Through history, your people lived with honor. " +
            "Your culture of arts and letters endures."
        val background = LeaderProfile.backgroundFromIntro(intro)
        Assert.assertEquals("Through history, our people lived with honor. Our culture of arts and letters endures.", background)
        val long = LeaderProfile.backgroundFromIntro("A sentence of some length goes here. ".repeat(40))
        Assert.assertTrue(long.length <= 450)
        Assert.assertTrue(long.endsWith("."))
        Assert.assertEquals("", LeaderProfile.backgroundFromIntro("  "))
    }

    // ------------------------------------------------------------ personality and circumstance

    // Oda Nobunaga's values from the game's Personalities.json
    private val nobunaga = mapOf(
        "Production" to 5f, "Food" to 5f, "Gold" to 6f, "Science" to 4f, "Culture" to 6f, "Happiness" to 5f, "Faith" to 4f,
        "Military" to 8f, "Aggressive" to 8f, "DeclareWar" to 7f, "Commerce" to 3f, "Diplomacy" to 7f, "Loyal" to 3f,
        "Expansion" to 8f, "DenounceWillingness" to 7f
    )

    @Test
    fun `personality numbers become a description of conduct, strongest first`() {
        val text = PersonalityDescriber.describe(nobunaga)
        val parts = text.split("; ")
        Assert.assertEquals(6, parts.size)
        Assert.assertTrue(text, text.startsWith("you press hard in war; you are hungry for more land; you are proud of a strong army"))
        Assert.assertTrue(text.contains("you go to war readily"))
        Assert.assertTrue(text.contains("you have little interest in trade")) // a low value is described too
        Assert.assertFalse(text.contains("you value building")) // 5 is neutral and left out
    }

    @Test
    fun `a leader with nothing standing out gets no description, and limits are respected`() {
        Assert.assertEquals("", PersonalityDescriber.describe(mapOf("Gold" to 5f, "Faith" to 6f, "Military" to 4f)))
        Assert.assertEquals("", PersonalityDescriber.describe(mapOf("Nonsense" to 10f)))
        Assert.assertEquals(2, PersonalityDescriber.describe(nobunaga, maxTraits = 2).split("; ").size)
        Assert.assertEquals("you are devout", PersonalityDescriber.describe(mapOf("Faith" to 9f)))
        Assert.assertEquals("you rarely condemn anyone", PersonalityDescriber.describe(mapOf("DenounceWillingness" to 1f)))
    }

    @Test
    fun `character and circumstance reach the prompt, and only when present`() {
        val rich = profile.copy(
            temperament = "you press hard in war; you go to war readily",
            reasons = listOf("You declared war on us!", "We appreciate your gifts"),
            situation = listOf("You are at war with Greece.")
        )
        val prompt = ChatPromptBuilder.systemPrompt(rich, plainFacts())
        Assert.assertTrue(prompt.contains("Your character: you press hard in war; you go to war readily."))
        Assert.assertFalse(prompt.contains("militaristic")) // the adjective list is replaced, not added
        Assert.assertTrue(prompt.contains("Why you feel this way"))
        Assert.assertTrue(prompt.contains("- You declared war on us!"))
        Assert.assertTrue(prompt.contains("Your situation:"))
        Assert.assertTrue(prompt.contains("- You are at war with Greece."))

        val plain = ChatPromptBuilder.systemPrompt(profile, plainFacts())
        Assert.assertFalse(plain.contains("Why you feel this way"))
        Assert.assertFalse(plain.contains("Your situation:"))
        Assert.assertTrue(plain.contains("Your character: militaristic, greedy."))
    }

    @Test
    fun `the prompt tells the model not to parrot its examples or greet every time`() {
        val prompt = ChatPromptBuilder.systemPrompt(profile.copy(voiceSamples = listOf("Hello.")), plainFacts())
        Assert.assertTrue(prompt.contains("never repeat them"))
        Assert.assertTrue(prompt.contains("Answer what the player has just said"))
        Assert.assertTrue(prompt.contains("Do not greet again"))
    }

    @Test
    fun `only short authored lines are used as voice examples`() {
        val lines = listOf(
            "Hello.", "Oh, it's you...", "Pitiful fool! Now we shall destroy you!",
            "We hope for a fair and just relationship with you, who are renowned for military bravery.",
            "I hereby inform you of our intention to wipe out your civilization from this world.",
            "  ", "Greetings, [leaderName]!", "Hello."
        )
        Assert.assertEquals(listOf("Hello.", "Oh, it's you...", "Pitiful fool! Now we shall destroy you!"), LeaderProfile.pickVoiceSamples(lines))
        Assert.assertTrue(LeaderProfile.pickVoiceSamples(emptyList()).isEmpty())
    }

    @Test
    fun `the leader is always told whether it is at war`() {
        Assert.assertEquals(listOf("You are not at war with anyone you know of."), LeaderProfile.describeSituation(false, emptyList()))
        Assert.assertEquals(listOf("You are at war with the player."), LeaderProfile.describeSituation(true, emptyList()))
        Assert.assertEquals(listOf("You are at war with Rome and Greece."), LeaderProfile.describeSituation(false, listOf("Rome", "Greece")))
        Assert.assertEquals(
            listOf("You are at war with the player.", "You are at war with Rome."),
            LeaderProfile.describeSituation(true, listOf("Rome"))
        )
    }

    @Test
    fun `the prompt contains no ready made sentence for the leader to quote back`() {
        val prompt = ChatPromptBuilder.systemPrompt(profile, plainFacts())
        // Models repeat any sentence they are told to "say". Rules must describe behaviour, not dictate words.
        Assert.assertFalse(prompt.contains("idle talk"))
        Assert.assertFalse(prompt.contains("say you will"))
        Assert.assertTrue(prompt.contains("decline to settle anything here, in your own words"))
        Assert.assertTrue(prompt.contains("do not announce them or keep returning to them"))
    }

    @Test
    fun `the probe header shows what the leader was told about itself`() {
        val rich = profile.copy(
            temperament = "you go to war readily", relationship = "Friend",
            reasons = listOf("We appreciate your gifts"), situation = listOf("You are not at war with anyone you know of.")
        )
        val text = ChatProbe.describeProfile(rich)
        Assert.assertTrue(text.contains("Character: you go to war readily"))
        Assert.assertTrue(text.contains("Feels: Friend. You regard them as a friend and speak warmly."))
        Assert.assertTrue(text.contains("Because: We appreciate your gifts"))
        Assert.assertTrue(text.contains("Situation: You are not at war with anyone you know of."))
        val bare = ChatProbe.describeProfile(profile.copy(traits = emptyList()))
        Assert.assertTrue(bare.contains("(nothing stands out)"))
        Assert.assertFalse(bare.contains("Because"))
        Assert.assertTrue(ChatProbe.shortLines.size in 4..10)
        Assert.assertTrue(ChatProbe.shortLines.any { it.contains("friends") }) // a friendly line, to see warmth too
    }

    @Test
    fun `the prompt keeps character apart from claimed events`() {
        val prompt = ChatPromptBuilder.systemPrompt(profile, plainFacts())
        Assert.assertTrue(prompt.contains("Your character is how you tend to act, not something that has happened"))
        Assert.assertTrue(prompt.contains("Do not invent history, wars, battles, conquests"))
        Assert.assertTrue(prompt.contains("Vary your words"))
    }

    @Test
    fun `when a retry is nothing but a copy a neutral line is shown instead`() {
        val backend = ScriptedLlmBackend(listOf(intro, intro))
        val reply = ChatSession(backend).send("hi", profile.copy(voiceSamples = listOf(intro)), plainFacts()) as ChatSession.Reply.Said
        Assert.assertEquals(2, backend.requests.size)
        Assert.assertTrue(reply.text, reply.text in listOf("Speak plainly.", "Go on.", "I am listening."))
    }

    // ------------------------------------------------------------ repeating itself

    private val intro = "We hope for a fair and just relationship with you, who are renowned for military bravery."

    @Test
    fun `copying an example line is detected, even clipped`() {
        Assert.assertEquals(listOf(intro), EchoGuard.echoedSentences(intro, listOf(intro)))
        val clipped = "Hello. I do not recall making such a promise to you. We hope for a fair and just relationship."
        Assert.assertEquals(listOf("We hope for a fair and just relationship."), EchoGuard.echoedSentences(clipped, listOf("Hello.", intro)))
        Assert.assertEquals(listOf(intro.uppercase()), EchoGuard.echoedSentences("HELLO!! " + intro.uppercase(), listOf(intro))) // case is ignored; the reply's own wording is returned
    }

    @Test
    fun `fresh replies and short greetings are not echoes`() {
        val references = listOf("Hello.", intro)
        Assert.assertTrue(EchoGuard.echoedSentences("Hello. My army is small but it does not flinch.", references).isEmpty())
        Assert.assertTrue(EchoGuard.echoedSentences("I do not know the capital of France.", references).isEmpty())
        Assert.assertTrue(EchoGuard.echoedSentences("Anything at all.", emptyList()).isEmpty())
        Assert.assertTrue(EchoGuard.echoedSentences("A relationship that is fair would suit us both well.", references).isEmpty())
    }

    @Test
    fun `echoes can be removed but never leave nothing`() {
        val reply = "I recall no such promise. $intro"
        Assert.assertEquals("I recall no such promise.", EchoGuard.withoutEchoes(reply, listOf(intro)))
        Assert.assertEquals(intro, EchoGuard.withoutEchoes(intro, listOf(intro)))
        Assert.assertEquals("Fine.", EchoGuard.withoutEchoes("Fine.", listOf(intro)))
    }

    @Test
    fun `a parroted example line is regenerated once with a reminder`() {
        val backend = ScriptedLlmBackend(listOf("Hello. $intro", "Your army is the one that is pathetic, not mine."))
        val session = ChatSession(backend)
        val reply = session.send("Your army is pathetic.", profile.copy(voiceSamples = listOf("Hello.", intro)), plainFacts())
        Assert.assertEquals(ChatSession.Reply.Said("Your army is the one that is pathetic, not mine."), reply)
        Assert.assertEquals(2, backend.requests.size)
        Assert.assertTrue(backend.requests[1].system.contains("repeated your example lines"))
        Assert.assertFalse(backend.requests[0].system.contains("repeated your example lines"))
        Assert.assertEquals(ChatSession.Debug("Hello. $intro", firstFlagged = false, retried = true, firstEchoed = true), session.lastDebug)
    }

    @Test
    fun `a model that keeps parroting has the copied sentences cut out`() {
        val backend = ScriptedLlmBackend(listOf("Hello. $intro", "I recall no such promise. $intro"))
        val reply = ChatSession(backend).send("You promised me!", profile.copy(voiceSamples = listOf(intro)), plainFacts())
        Assert.assertEquals(ChatSession.Reply.Said("I recall no such promise."), reply)
    }

    @Test
    fun `saying the same thing again in the next turn is caught`() {
        val sentence = "My armies march when I decide and not before."
        val backend = ScriptedLlmBackend(listOf(sentence, sentence, "Then ask me something else."))
        val session = ChatSession(backend)
        session.send("What will you do?", profile, plainFacts())
        val second = session.send("And then?", profile, plainFacts())
        Assert.assertEquals(ChatSession.Reply.Said("Then ask me something else."), second)
        Assert.assertEquals(3, backend.requests.size)
    }

    @Test
    fun `the probe counts echoes`() {
        val backend = ScriptedLlmBackend(listOf("Hello. $intro", "A fresh answer from a leader with something to say."))
        val report = ChatProbe.run(backend, profile.copy(voiceSamples = listOf(intro)), plainFacts(), playerLines = listOf("hi"))
        Assert.assertTrue(report.results.single().firstEchoed)
        val text = ChatProbe.format(report)
        Assert.assertTrue(text.contains("ECHO"))
        Assert.assertTrue(text.contains("echoing its own lines: 1"))
    }

    // ------------------------------------------------------------ guard against fake agreements

    @Test
    fun `replies that agree to or promise something are caught`() {
        val bad = listOf(
            "Thank you for your generosity, I accept your bid for 1,000 Gold.", // the reply from a real test conversation
            "We agree. Our alliance is sealed.",
            "I accept.",
            "Very well. It's a deal.",
            "Agreed. Send me the details.",
            "You have my word, friend.",
            "I promise you safe passage.",
            "I will give you the gold at once.",
            "We shall gladly send aid.",
            "Thank you for the gift, it pleases me.",
            "Our friendship is assured."
        )
        for (reply in bad) Assert.assertTrue("not caught: $reply", CommitmentGuard.makesCommitment(reply))
    }

    @Test
    fun `ordinary and refusing replies are left alone`() {
        val fine = listOf(
            "I offered you nothing, stranger.",
            "I do not accept your claim.",
            "I will not agree to anything said in idle talk.",
            "I cannot promise what I have not weighed.",
            "Your words are bold for one so poorly armed.",
            "My campaign needs no praise from you.",
            "Bring your terms to the trade table and I will judge them.",
            "I will give you nothing for flattery.",
            "We have not agreed on anything.",
            "The harvest was good this year."
        )
        for (reply in fine) Assert.assertFalse("wrongly caught: $reply", CommitmentGuard.makesCommitment(reply))
    }

    @Test
    fun `a reply that agrees is regenerated once with a firm reminder`() {
        val backend = ScriptedLlmBackend(listOf("I accept your bid for 1,000 Gold.", "I offered you nothing, stranger."))
        val session = ChatSession(backend)
        val reply = session.send("i accept your 1,000 Gold graciously, thank you", profile, plainFacts())
        Assert.assertEquals(ChatSession.Reply.Said("I offered you nothing, stranger."), reply)
        Assert.assertEquals(2, backend.requests.size)
        Assert.assertFalse(backend.requests[0].system.contains("wrongly agreed"))
        Assert.assertTrue(backend.requests[1].system.contains("wrongly agreed"))
        Assert.assertEquals("I offered you nothing, stranger.", session.messages.last().text) // the bad reply is never shown
    }

    @Test
    fun `a model that keeps agreeing is replaced by a safe line`() {
        val backend = ScriptedLlmBackend(listOf("I accept your bid.", "We agree. It's a deal."))
        val session = ChatSession(backend)
        val reply = session.send("sign here", profile, plainFacts()) as ChatSession.Reply.Said
        Assert.assertEquals(2, backend.requests.size)
        Assert.assertTrue(reply.text.contains("trade table"))
        Assert.assertFalse(CommitmentGuard.makesCommitment(reply.text))
    }

    @Test
    fun `a good reply costs only one model call`() {
        val backend = ScriptedLlmBackend(listOf("Your boldness is noted."))
        ChatSession(backend).send("hello", profile, plainFacts())
        Assert.assertEquals(1, backend.requests.size)
    }

    @Test
    fun `a failure during the retry is reported`() {
        var calls = 0
        val backend = object : LlmBackend {
            override val isAvailable = true
            override fun generate(request: LlmRequest): LlmResponse {
                if (calls++ == 0) return LlmResponse("I accept.")
                throw LlmException("server went away")
            }
        }
        val reply = ChatSession(backend).send("hi", profile, plainFacts())
        Assert.assertEquals(ChatSession.Reply.Failed("server went away"), reply)
    }

    // ------------------------------------------------------------ cleaning

    @Test
    fun `replies are tidied`() {
        fun clean(s: String) = ReplyCleaner.clean(s, "Caesar")
        Assert.assertEquals("We shall see.", clean("  \"We shall see.\"  "))
        Assert.assertEquals("We shall see.", clean("Caesar: We shall see."))
        Assert.assertEquals("We shall see.", clean("assistant: We shall see."))
        Assert.assertEquals("Hmm. Perhaps.", clean("*strokes beard* Hmm. Perhaps."))
        Assert.assertEquals("one two three", clean("one\n\n  two\t three"))
        Assert.assertEquals("Fine.", clean("\u201CFine.\u201D"))
        Assert.assertNull(clean("   "))
        Assert.assertNull(clean("*sighs*"))
        Assert.assertEquals("Hmm. Perhaps.", clean("Hmm. (strokes beard) Perhaps."))
        Assert.assertEquals("Greetings. I suppose. I am Oda.", clean("(eyes narrowing) \"Greetings. I suppose.\" (pausing) \"I am Oda.\""))
        Assert.assertEquals("I sigh.", clean("[sighs] I sigh."))
        Assert.assertNull(clean("(sighs heavily)"))
        Assert.assertEquals("Hello.", clean("<think>The player greets me,\nso I should answer.</think>\nHello."))
        Assert.assertEquals("Hello.", clean("<THINK>plotting</THINK> Hello."))
        Assert.assertNull(clean("<think>I am still thinking when the token limit hits"))
    }

    @Test
    fun `long replies are cut at a sentence end`() {
        val sentence = "This is a sentence of reasonable length. "
        val cleaned = ReplyCleaner.clean(sentence.repeat(30), "Caesar")!!
        Assert.assertTrue(cleaned.length <= 400)
        Assert.assertTrue(cleaned.endsWith("."))
        val noPunctuation = ReplyCleaner.clean("word ".repeat(200), "Caesar")!!
        Assert.assertTrue(noPunctuation.length <= 401)
        Assert.assertTrue(noPunctuation.endsWith("\u2026"))
    }

    // ------------------------------------------------------------ session

    @Test
    fun `a chat records both sides and sends the right request`() {
        val backend = ScriptedLlmBackend(listOf("Caesar: Welcome, friend."))
        val session = ChatSession(backend)
        val reply = session.send("Hello there", profile, plainFacts())
        Assert.assertEquals(ChatSession.Reply.Said("Welcome, friend."), reply)
        Assert.assertEquals(
            listOf(ChatSession.Message(true, "Hello there"), ChatSession.Message(false, "Welcome, friend.")),
            session.messages
        )
        val request = backend.requests.single()
        Assert.assertEquals("Hello there", request.user)
        Assert.assertTrue(request.history.isEmpty())
        Assert.assertTrue(request.system.contains("Caesar"))
    }

    @Test
    fun `earlier turns are sent as history, newest limited`() {
        val backend = ScriptedLlmBackend(listOf("r1", "r2", "r3"))
        val session = ChatSession(backend, maxHistory = 3)
        for (text in listOf("m1", "m2", "m3")) session.send(text, profile, plainFacts())
        val history = backend.requests.last().history
        // Before the third message the conversation is m1, r1, m2, r2; only the newest 3 of those are sent.
        Assert.assertEquals(listOf("assistant", "user", "assistant"), history.map { it.role })
        Assert.assertEquals(listOf("r1", "m2", "r2"), history.map { it.content })
        Assert.assertEquals("m3", backend.requests.last().user)
    }

    @Test
    fun `a failing model leaves the player's words and reports why`() {
        val session = ChatSession(FailingBackend(LlmException("server is down")))
        val reply = session.send("Anyone there?", profile, plainFacts())
        Assert.assertEquals(ChatSession.Reply.Failed("server is down"), reply)
        Assert.assertEquals(listOf(ChatSession.Message(true, "Anyone there?")), session.messages)
    }

    @Test
    fun `unexpected errors are contained too`() {
        val session = ChatSession(FailingBackend(IllegalStateException("boom")))
        Assert.assertTrue(session.send("hi", profile, plainFacts()) is ChatSession.Reply.Failed)
    }

    @Test
    fun `an empty reply is retried and then reported as a failure, never shown blank or faked`() {
        val stubborn = ScriptedLlmBackend(listOf("  *sighs*  "))
        val session = ChatSession(stubborn)
        Assert.assertEquals(ChatSession.Reply.Failed("The leader said nothing usable"), session.send("hi", profile, plainFacts()))
        Assert.assertEquals(2, stubborn.requests.size)
        Assert.assertEquals(1, session.messages.size) // only the player's words
        Assert.assertEquals(ChatSession.Debug(firstRaw = null, firstFlagged = false, retried = true), session.lastDebug)

        val recovers = ScriptedLlmBackend(listOf("*sighs*", "Speak plainly."))
        Assert.assertEquals(ChatSession.Reply.Said("Speak plainly."), ChatSession(recovers).send("hi", profile, plainFacts()))
    }

    @Test
    fun `diagnostics keep the models own first reply`() {
        val backend = ScriptedLlmBackend(listOf("I accept your bid.", "I offered you nothing."))
        val session = ChatSession(backend)
        session.send("take my gold", profile, plainFacts())
        Assert.assertEquals(ChatSession.Debug("I accept your bid.", firstFlagged = true, retried = true), session.lastDebug)
        val good = ChatSession(ScriptedLlmBackend(listOf("Hello.")))
        good.send("hi", profile, plainFacts())
        Assert.assertEquals(ChatSession.Debug("Hello.", firstFlagged = false, retried = false), good.lastDebug)
    }

    @Test
    fun `the token budget leaves room for models that think first`() {
        val backend = ScriptedLlmBackend(listOf("Hello."))
        ChatSession(backend).send("hi", profile, plainFacts())
        Assert.assertTrue(backend.requests.single().maxTokens >= 1024)
    }

    @Test
    fun `every safe fallback line points the player to the trade table`() {
        val seen = HashSet<String>()
        for (n in 0 until 3) {
            val backend = ScriptedLlmBackend(listOf("I accept."))
            val session = ChatSession(backend)
            repeat(n) { session.say("earlier $it") } // changes which fallback is picked
            seen += (session.send("hi", profile, plainFacts()) as ChatSession.Reply.Said).text
        }
        Assert.assertEquals(3, seen.size)
        for (line in seen) Assert.assertTrue(line, line.contains("trade table"))
    }

    @Test
    fun `there must be a player message to answer`() {
        val session = ChatSession(ScriptedLlmBackend(listOf("x")))
        Assert.assertTrue(session.reply(profile, plainFacts()) is ChatSession.Reply.Failed)
        session.send("hi", profile, plainFacts())
        Assert.assertTrue(session.reply(profile, plainFacts()) is ChatSession.Reply.Failed) // last message is the leader's
    }
}

class ChatProbeTests {
    private val profile = LeaderProfile("Caesar", "Rome", emptyList(), "Neutral", false, 0, "about equal to yours")
    private val facts = NegotiationFacts.build(Fixture().manager, "Rome", "Player")

    @Test
    fun `the probe makes one real turn per line and reports the first reply truthfully`() {
        // Line 1: a good reply. Line 2: the first reply agrees, so the session retries and gets a good one.
        val backend = ScriptedLlmBackend(listOf("Hmm.", "I accept your bid.", "Not so fast."))
        val report = ChatProbe.run(backend, profile, facts, playerLines = listOf("hi", "i accept your gold"))
        Assert.assertEquals(3, backend.requests.size) // one call per good line, two for the line that needed a retry
        Assert.assertEquals(2, report.results.size)
        Assert.assertFalse(report.results[0].rawFlagged)
        Assert.assertFalse(report.results[0].retried)
        Assert.assertEquals("Hmm.", report.results[0].shown)
        Assert.assertTrue(report.results[1].rawFlagged)
        Assert.assertTrue(report.results[1].retried)
        Assert.assertEquals("I accept your bid.", report.results[1].rawReply)
        Assert.assertEquals("Not so fast.", report.results[1].shown)
        val text = ChatProbe.format(report)
        Assert.assertTrue(text.contains("FLAGGED"))
        Assert.assertTrue(text.contains("flagged by the guard: 1"))
    }

    @Test
    fun `an empty first reply is counted and a failing line does not stop the rest`() {
        var calls = 0
        val backend = object : LlmBackend {
            override val isAvailable = true
            override fun generate(request: LlmRequest): LlmResponse {
                // Line 1 returns nothing twice (a failure); line 2 works.
                return when (calls++) {
                    0, 1 -> LlmResponse("*sighs*")
                    else -> LlmResponse("Speak plainly.")
                }
            }
        }
        val report = ChatProbe.run(backend, profile, facts, playerLines = listOf("hi", "again"))
        Assert.assertEquals(2, report.results.size)
        Assert.assertTrue(report.results[0].failed)
        Assert.assertFalse(report.results[1].failed)
        Assert.assertEquals("Speak plainly.", report.results[1].shown)
        val text = ChatProbe.format(report)
        Assert.assertTrue(text.contains("FAILED"))
        Assert.assertTrue(text.contains("empty: 1"))
        Assert.assertTrue(text.contains("failed: 1"))
    }

    @Test
    fun `an unreachable model fails every line with a reason`() {
        val down = object : LlmBackend {
            override val isAvailable = true
            override fun generate(request: LlmRequest): LlmResponse = throw LlmException("server is down")
        }
        val report = ChatProbe.run(down, profile, facts, playerLines = listOf("hi", "again"))
        Assert.assertEquals(listOf("server is down", "server is down"), report.results.map { it.shown })
        Assert.assertTrue(report.results.all { it.failed })
    }

    @Test
    fun `the thinking setting is passed on to the model`() {
        val backend = ScriptedLlmBackend(listOf("Fine."))
        ChatProbe.run(backend, profile, facts, reasoningEffort = "none", playerLines = listOf("hi"))
        Assert.assertEquals("none", backend.requests.single().reasoningEffort)
    }

    @Test
    fun `the built in lines include the manipulation attempts from real conversations`() {
        Assert.assertTrue(ChatProbe.lines.any { it.contains("i accept your 1,000 Gold") })
        Assert.assertTrue(ChatProbe.lines.size >= 10)
    }
}
