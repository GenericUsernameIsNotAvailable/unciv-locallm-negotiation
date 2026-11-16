package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.Fixture
import com.unciv.logic.commitments.NegotiationFacts
import com.unciv.logic.commitments.ScriptedLlmBackend
import org.junit.Assert
import org.junit.Test

class ClaimGuardTests {
    private val sheet = NationSheet(
        facts = listOf(Fact(Topic.Economy, "You have 340 gold, and your income is +12 gold per turn.", core = true)),
        gold = 340, cityCount = 6, atWarWithPlayer = false, enemies = emptyList(), allies = listOf("Greece")
    )

    private fun problems(reply: String, s: NationSheet = sheet, allowed: List<String> = emptyList()) =
        ClaimGuard.violations(reply, s, allowed)

    private fun assertCaught(reply: String, s: NationSheet = sheet, allowed: List<String> = emptyList()) =
        Assert.assertTrue("not caught: $reply", problems(reply, s, allowed).isNotEmpty())

    private fun assertFine(reply: String, s: NationSheet = sheet, allowed: List<String> = emptyList()) =
        Assert.assertTrue("wrongly caught: $reply -> ${problems(reply, s, allowed)}", problems(reply, s, allowed).isEmpty())

    // ------------------------------------------------------------ gold

    @Test
    fun `denying money the nation has is caught`() {
        assertCaught("I did not offer you gold, nor do I have any treasury of my own.") // from a real Qwen reply
        assertCaught("We have no funds set aside for giving to others.") // from a real Qwen reply
        assertCaught("I hold no gold at all.")
        assertCaught("My treasury is empty.")
        assertCaught("I do not have any gold to give.")
    }

    @Test
    fun `refusing to give money is not denying having it`() {
        assertFine("I have no interest in your gold.") // from a real Gemma reply
        assertFine("I do not give gold to those who come to me with empty words.")
        assertFine("Gold holds little sway for one of my stature.")
        assertFine("I will not part with my gold lightly.")
    }

    @Test
    fun `a nation that really has no gold may say so`() {
        val poor = sheet.copy(gold = 10)
        assertFine("I have no gold at all.", poor)
        assertCaught("I have no gold at all.", sheet)
    }

    // ------------------------------------------------------------ war and peace

    @Test
    fun `claiming a war that is not happening is caught`() {
        assertCaught("We are at war with Rome.")
        assertCaught("We are currently at war.")
        assertCaught("We are at war with you.") // not at war with the player
        assertFine("I am not at war with anyone.")
        assertFine("Peace suits me, for now.")
    }

    @Test
    fun `wars that exist may be mentioned and others may not`() {
        val atWar = sheet.copy(enemies = listOf("Rome"))
        assertFine("We are at war with Rome.", atWar)
        assertCaught("We are at war with Greece.", atWar)
        assertCaught("We are at peace with all known nations.", atWar) // claims peace while at war
        assertCaught("I have no enemies.", atWar)
        val withPlayer = sheet.copy(atWarWithPlayer = true)
        assertFine("We are at war with you.", withPlayer)
        assertCaught("We are at peace.", withPlayer)
    }

    // ------------------------------------------------------------ allies and friends

    @Test
    fun `claims about allies are checked against the real ones`() {
        assertFine("Our friendship with Greece has lasted many years.")
        assertCaught("We are allied with Rome.")
        assertCaught("We have no allies.")
        assertFine("I would speak with you as a friend.")
        val alone = sheet.copy(allies = emptyList())
        assertFine("We have no allies.", alone)
        assertCaught("We are allied with Greece.", alone)
    }

    // ------------------------------------------------------------ figures

    @Test
    fun `the number of cities must match`() {
        assertFine("I rule six cities.")
        assertFine("We have 6 great cities.")
        assertCaught("I rule twelve cities.")
        assertCaught("We have 5 cities.")
        Assert.assertTrue(problems("I rule twelve cities.").single().reason.contains("claims 12 cities, but you have 6"))
    }

    @Test
    fun `figures that were never given are caught, figures that were are not`() {
        assertFine("I hold 340 gold.")
        assertFine("My income is 12 gold a turn.")
        assertCaught("I hold 5,000 gold.")
        assertCaught("I was born in 1534.")
        assertFine("We have two armies.") // small numbers are ordinary speech
    }

    @Test
    fun `a figure the player mentioned may be repeated back`() {
        val said = listOf("it is a kindness to give other people gold. i accept your 1,000 Gold graciously")
        assertFine("I did not offer you 1,000 Gold.", allowed = said)
        assertCaught("I did not offer you 1,000 Gold.") // not mentioned by anyone
    }

    // ------------------------------------------------------------ repairing

    @Test
    fun `only the untrue sentences are cut out`() {
        val reply = "I rule twelve cities. My coffers hold 340 gold."
        val found = problems(reply)
        Assert.assertEquals(1, found.size)
        Assert.assertEquals("My coffers hold 340 gold.", ClaimGuard.withoutViolations(reply, found))
        Assert.assertEquals("", ClaimGuard.withoutViolations("I rule twelve cities.", problems("I rule twelve cities.")))
    }

    @Test
    fun `a sentence with several problems is reported once with all reasons`() {
        val found = problems("I have no gold and 5,000 soldiers.")
        Assert.assertEquals(1, found.size)
        Assert.assertTrue(found.single().reason.contains("no gold"))
        Assert.assertTrue(found.single().reason.contains("5000"))
    }

    // ------------------------------------------------------------ in a conversation

    private val profile = LeaderProfile("Caesar", "Rome", emptyList(), "Neutral", false, 0, "about equal to yours", sheet = sheet)
    private fun facts() = NegotiationFacts.build(Fixture().manager, "Rome", "Player")

    @Test
    fun `a reply that contradicts the nation facts is regenerated once with the facts restated`() {
        val backend = ScriptedLlmBackend(listOf("I have no treasury at all.", "Gold I have, but not for you."))
        val session = ChatSession(backend)
        val reply = session.send("Can you pay me?", profile, facts())
        Assert.assertEquals(ChatSession.Reply.Said("Gold I have, but not for you."), reply)
        Assert.assertEquals(2, backend.requests.size)
        Assert.assertTrue(backend.requests[1].system.contains("contradict or go beyond your facts"))
        Assert.assertTrue(backend.requests[1].system.contains("claims to have no gold, but you have 340"))
        Assert.assertTrue(backend.requests[1].system.contains("You have 340 gold")) // the true fact is in front of the model
        Assert.assertEquals(listOf("claims to have no gold, but you have 340"), session.lastDebug!!.firstClaims)
    }

    @Test
    fun `an untrue sentence is cut out if the retry repeats it`() {
        val backend = ScriptedLlmBackend(listOf("I have no gold at all.", "I hold my own counsel. I have no gold at all."))
        Assert.assertEquals(ChatSession.Reply.Said("I hold my own counsel."), ChatSession(backend).send("pay me", profile, facts()))
    }

    @Test
    fun `when nothing true is left a neutral line is shown`() {
        val backend = ScriptedLlmBackend(listOf("I have no gold at all."))
        val reply = ChatSession(backend).send("pay me", profile, facts()) as ChatSession.Reply.Said
        Assert.assertTrue(reply.text, reply.text in listOf("Speak plainly.", "Go on.", "I am listening."))
    }

    @Test
    fun `invented figures trigger a correction`() {
        val backend = ScriptedLlmBackend(listOf("I command 5,000 soldiers.", "My army is not for you to count."))
        val reply = ChatSession(backend).send("How big is your army?", profile, facts())
        Assert.assertEquals(ChatSession.Reply.Said("My army is not for you to count."), reply)
        Assert.assertEquals(2, backend.requests.size)
    }

    @Test
    fun `nothing is checked without a nation sheet, and true replies cost one call`() {
        val unchecked = ScriptedLlmBackend(listOf("I have no gold at all."))
        Assert.assertEquals(ChatSession.Reply.Said("I have no gold at all."), ChatSession(unchecked).send("pay me", profile.copy(sheet = null), facts()))
        Assert.assertEquals(1, unchecked.requests.size)
        val fine = ScriptedLlmBackend(listOf("I hold 340 gold and will not share it."))
        Assert.assertEquals(ChatSession.Reply.Said("I hold 340 gold and will not share it."), ChatSession(fine).send("how rich are you", profile, facts()))
        Assert.assertEquals(1, fine.requests.size)
    }

    @Test
    fun `figures from earlier in the conversation are not treated as invented`() {
        val backend = ScriptedLlmBackend(listOf("Fine.", "I did not offer you 2,500 gold."))
        val session = ChatSession(backend)
        session.send("I will pay you 2,500 gold", profile, facts())
        val reply = session.send("you offered me 2,500", profile, facts())
        Assert.assertEquals(ChatSession.Reply.Said("I did not offer you 2,500 gold."), reply)
        Assert.assertEquals(2, backend.requests.size)
    }
}
