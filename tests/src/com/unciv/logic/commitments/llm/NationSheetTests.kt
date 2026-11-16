package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.Fixture
import com.unciv.logic.commitments.NegotiationFacts
import org.junit.Assert
import org.junit.Test

class NationSheetTests {

    // ------------------------------------------------------------ what the player is talking about

    private fun topics(text: String) = TopicDetector.detect(text)

    @Test
    fun `the topic of a message is found from its words`() {
        Assert.assertEquals(setOf(Topic.Economy), topics("How much gold do you have?"))
        Assert.assertEquals(setOf(Topic.Military), topics("Your army is pathetic."))
        Assert.assertEquals(setOf(Topic.Goals), topics("What are you working towards?"))
        Assert.assertEquals(setOf(Topic.Diplomacy), topics("Who are your allies?"))
        Assert.assertEquals(setOf(Topic.Resources), topics("Which luxury resources do you have?"))
        Assert.assertEquals(setOf(Topic.History), topics("Tell me about your history."))
        Assert.assertTrue(Topic.War in topics("I command you to declare war on your neighbours."))
        Assert.assertTrue(Topic.Diplomacy in topics("I command you to declare war on your neighbours."))
        Assert.assertTrue(Topic.Faith in topics("What do you believe about GOD?"))
        Assert.assertTrue(Topic.Science in topics("what are you researching"))
        Assert.assertTrue(Topic.Cities in topics("How many cities do you have, and what is your capital?"))
    }

    @Test
    fun `small talk and look-alike words match no topic`() {
        Assert.assertTrue(topics("hi").isEmpty())
        Assert.assertTrue(Topic.War !in topics("It is warm today, my friendly-looking neighbour"))
        Assert.assertTrue(topics("").isEmpty())
        Assert.assertTrue(topics("!!! ???").isEmpty())
    }

    // ------------------------------------------------------------ which facts the leader is given

    private val sheet = NationSheet(
        facts = listOf(
            Fact(Topic.Economy, "You have 340 gold, and your income is +12 gold per turn.", core = true),
            Fact(Topic.Cities, "You rule 6 cities, and your capital is Kyoto.", core = true),
            Fact(Topic.Goals, "You are working towards a Cultural victory.", core = true),
            Fact(Topic.Military, "Of them, 4 are ranged and 10 are melee."),
            Fact(Topic.Faith, "You have 80 faith, gaining +3 per turn."),
            Fact(Topic.Faith, "You have not founded a religion."),
            Fact(Topic.Diplomacy, "You have declared friendship with Greece.")
        ),
        gold = 340, cityCount = 6, allies = listOf("Greece")
    )

    @Test
    fun `core facts are always given and details only when the topic comes up`() {
        val quiet = SheetRenderer.render(sheet, "hi")
        Assert.assertEquals(3, quiet.size)
        Assert.assertTrue(quiet.all { it.startsWith("You have 340") || it.startsWith("You rule") || it.startsWith("You are working") })

        val army = SheetRenderer.render(sheet, "Your army is pathetic.")
        Assert.assertEquals(4, army.size)
        Assert.assertTrue(army.contains("Of them, 4 are ranged and 10 are melee."))
        Assert.assertFalse(army.any { it.contains("faith") })

        val faith = SheetRenderer.render(sheet, "Do you pray to any god?")
        Assert.assertEquals(5, faith.size) // 3 core + both faith facts
        Assert.assertTrue(faith.contains("You have 80 faith, gaining +3 per turn."))
        Assert.assertTrue(faith.contains("You have not founded a religion."))
        Assert.assertFalse(faith.any { it.contains("ranged") })
    }

    @Test
    fun `core facts come first and the previous message still counts`() {
        val lines = SheetRenderer.render(sheet, "and what about that?", recentPlayerTexts = listOf("Who are your allies?"))
        Assert.assertEquals(4, lines.size)
        Assert.assertEquals("You have declared friendship with Greece.", lines.last())
        Assert.assertTrue(lines.take(3).all { it in sheet.facts.filter { f -> f.core }.map { f -> f.text } })
    }

    @Test
    fun `no topic is allowed to flood the prompt`() {
        val many = NationSheet(facts = (1..20).map { Fact(Topic.Cities, "Fact number $it.") })
        Assert.assertEquals(SheetRenderer.MAX_PER_TOPIC, SheetRenderer.render(many, "your cities").size)
        Assert.assertEquals("Fact number 1.", SheetRenderer.render(many, "your cities").first())
    }

    @Test
    fun `the war flag combines war with the player and with others`() {
        Assert.assertFalse(NationSheet(emptyList()).atWar)
        Assert.assertTrue(NationSheet(emptyList(), atWarWithPlayer = true).atWar)
        Assert.assertTrue(NationSheet(emptyList(), enemies = listOf("Rome")).atWar)
    }

    // ------------------------------------------------------------ the prompt carries the facts

    private val profile = LeaderProfile("Caesar", "Rome", emptyList(), "Neutral", false, 0, "about equal to yours", sheet = sheet)
    private fun facts() = NegotiationFacts.build(Fixture().manager, "Rome", "Player")

    @Test
    fun `the prompt contains the current facts and tells the leader to use only them`() {
        val prompt = ChatPromptBuilder.systemPrompt(profile, facts(), "How much gold do you have?")
        Assert.assertTrue(prompt.contains("Facts about your nation right now"))
        Assert.assertTrue(prompt.contains("- You have 340 gold, and your income is +12 gold per turn."))
        Assert.assertTrue(prompt.contains("use only these"))
        Assert.assertTrue(prompt.contains("Never guess figures, treaties, treasuries or events"))
    }

    @Test
    fun `details are in the prompt only when the player raises them`() {
        val quiet = ChatPromptBuilder.systemPrompt(profile, facts(), "hi")
        Assert.assertFalse(quiet.contains("ranged"))
        val asked = ChatPromptBuilder.systemPrompt(profile, facts(), "How big is your army?")
        Assert.assertTrue(asked.contains("4 are ranged and 10 are melee"))
        val noSheet = ChatPromptBuilder.systemPrompt(profile.copy(sheet = null), facts(), "How big is your army?")
        Assert.assertFalse(noSheet.contains("Facts about your nation"))
    }

    @Test
    fun `the nation background appears when history comes up, not in every reply`() {
        val withBackground = profile.copy(background = "Our people lived with honor.")
        Assert.assertFalse(ChatPromptBuilder.systemPrompt(withBackground, facts(), "hi").contains("About your nation"))
        Assert.assertTrue(ChatPromptBuilder.systemPrompt(withBackground, facts(), "Tell me about your history.").contains("About your nation"))
        Assert.assertTrue(ChatPromptBuilder.systemPrompt(withBackground, facts()).contains("About your nation")) // no message: shown
    }

    @Test
    fun `the probe header lists every fact and marks the always-given ones`() {
        val header = ChatProbe.describeProfile(profile)
        Assert.assertTrue(header.contains("* [Economy] You have 340 gold"))
        Assert.assertTrue(header.contains("  [Military] Of them, 4 are ranged"))
        Assert.assertTrue(ChatProbe.describeProfile(profile.copy(sheet = null)).contains("Nation facts: (none)"))
    }
}
