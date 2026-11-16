package com.unciv.logic.commitments

import com.unciv.logic.commitments.llm.ClaimGuard
import com.unciv.logic.commitments.llm.ClassicItemKind
import com.unciv.logic.commitments.llm.Fact
import com.unciv.logic.commitments.llm.LlmException
import com.unciv.logic.commitments.llm.LlmRequest
import com.unciv.logic.commitments.llm.LlmResponse
import com.unciv.logic.commitments.llm.MobileModelSpec
import com.unciv.logic.commitments.llm.NationSheet
import com.unciv.logic.commitments.llm.NativeLlmBridge
import com.unciv.logic.commitments.llm.OnDeviceLlmBackend
import com.unciv.logic.commitments.llm.OnDeviceModelManager
import com.unciv.logic.commitments.llm.PlayerOfferSheet
import com.unciv.logic.commitments.llm.Topic
import com.unciv.logic.commitments.llm.TradableCity
import com.unciv.logic.commitments.llm.TradeIntentParser
import org.junit.Assert
import org.junit.Test
import java.nio.file.Files

class Stage6Tests {

    private val playerSheet = PlayerOfferSheet(
        leaderName = "Kamehameha I",
        nationName = "Polynesia",
        gold = 503,
        goldPerTurn = 5,
        cityCount = 2
    )

    private val sheet = NationSheet(
        facts = listOf(Fact(Topic.Economy, "You have 500 gold, and your income is +0 gold per turn.", core = true)),
        gold = 500,
        cityCount = 0,
        player = playerSheet
    )

    @Test
    fun `regression for probe runs - govern or command two cities and no gold to give when asked for more`() {
        // From Haile Selassie Line 1 (gemma4:e4b)
        val selassie = "I know that you are Kamehameha I, the ruler of Polynesia. I can see that your treasury holds a measure of gold and that you govern two cities."
        Assert.assertTrue(ClaimGuard.violations(selassie, sheet, emptyList()).isEmpty())

        // From George Washington Line 1 (gemma4:e4b)
        val washingtonSheet = sheet.copy(cityCount = 1)
        val washington = "I know you as Kamehameha I, the ruler of Polynesia. I observe that your treasury holds five hundred and three gold, and you command two cities."
        Assert.assertTrue(ClaimGuard.violations(washington, washingtonSheet, emptyList()).isEmpty())

        // When player asks for more gold than the leader owns (e.g. 200 > 33), saying "no gold to give/spare" is allowed
        val washingtonPoor = sheet.copy(gold = 33)
        val noGoldToGive = "I do not have any gold to give you at this time."
        Assert.assertTrue(ClaimGuard.violations(noGoldToGive, washingtonPoor, listOf("give me 100 gold")).isEmpty())
    }

    @Test
    fun `city for gold and pure gold gift sentences from in-game chat are parsed into classic trades`() {
        val playerCities = listOf(TradableCity(id = "city-samoa-id", name = "Samoa"))

        // 1. Exact sentence from screenshot: "you give me 15 gold and i will give you one of my cities. deal?"
        val cityFor15 = TradeIntentParser.parseClassicTrade(
            text = "i say its an awesome deal, and you should take it. tell you what. you give me 15 gold and i will give you one of my cities. deal?",
            playerCiv = "Polynesia",
            aiCiv = "America",
            playerLuxuries = emptyList(),
            aiLuxuries = emptyList(),
            playerStrategics = emptyList(),
            aiStrategics = emptyList(),
            playerCities = playerCities,
            aiCities = emptyList(),
            aiGold = 33
        )
        Assert.assertNotNull(cityFor15)
        Assert.assertTrue(cityFor15!!.items.any { it.fromPlayer && it.kind == ClassicItemKind.City && it.cityId == "city-samoa-id" && it.name == "Samoa" })
        Assert.assertTrue(cityFor15.items.any { !it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 15 })

        // 2. Offering an entire city for 33 gold (or "all your gold")
        val cityFor33 = TradeIntentParser.parseClassicTrade(
            text = "I will give you an entire city for 33 gold",
            playerCiv = "Polynesia",
            aiCiv = "America",
            playerLuxuries = emptyList(),
            aiLuxuries = emptyList(),
            playerStrategics = emptyList(),
            aiStrategics = emptyList(),
            playerCities = playerCities,
            aiCities = emptyList(),
            aiGold = 33
        )
        Assert.assertNotNull(cityFor33)
        Assert.assertTrue(cityFor33!!.items.any { it.fromPlayer && it.kind == ClassicItemKind.City && it.cityId == "city-samoa-id" })
        Assert.assertTrue(cityFor33.items.any { !it.fromPlayer && it.kind == ClassicItemKind.Gold && it.amount == 33 })

        // 3. Exact sentence from screenshot: "okay. how about i give you 50 gold and you do not have to give me anything"
        val pureGift50 = TradeIntentParser.parseClassicTrade(
            text = "okay. how about i give you 50 gold and you do not have to give me anything",
            playerCiv = "Polynesia",
            aiCiv = "America",
            playerLuxuries = emptyList(),
            aiLuxuries = emptyList(),
            playerStrategics = emptyList(),
            aiStrategics = emptyList(),
            playerCities = playerCities,
            aiCities = emptyList(),
            aiGold = 33
        )
        Assert.assertNotNull(pureGift50)
        Assert.assertEquals(1, pureGift50!!.items.size)
        Assert.assertEquals(ClassicItemKind.Gold, pureGift50.items.single().kind)
        Assert.assertTrue(pureGift50.items.single().fromPlayer)
        Assert.assertEquals(50, pureGift50.items.single().amount)

        // 4. Exact sentence from Haile Selassie screenshot: asking to name one of my cities must NOT trigger a trade!
        val questionAboutCities = TradeIntentParser.parseClassicTrade(
            text = "hello! i hope we can become friends one day. this may sound silly to ask, but please may you name one of my cities?",
            playerCiv = "Polynesia",
            aiCiv = "Ethiopia",
            playerLuxuries = emptyList(),
            aiLuxuries = emptyList(),
            playerStrategics = emptyList(),
            aiStrategics = emptyList(),
            playerCities = playerCities,
            aiCities = emptyList(),
            aiGold = 500
        )
        Assert.assertNull(questionAboutCities)

        // 5. PlayerOfferSheet lists all city names when provided
        val withCityNames = playerSheet.copy(capitalName = "Honolulu", cityNames = listOf("Honolulu", "Samoa"))
        val summary = withCityNames.summaryLines().joinToString(" ")
        Assert.assertTrue(summary.contains("Honolulu (capital), Samoa"))
    }

    @Test
    fun `on device backend loads model lazily on chat and unloads when diplomacy closes`() {
        val tempDir = Files.createTempDirectory("unciv-llm-test").toFile()
        try {
            val manager = OnDeviceModelManager { tempDir }
            val spec = MobileModelSpec.byIdOrDefault("gemma4:e2b")
            Assert.assertFalse(manager.isDownloaded(spec))

            var loadedPath: String? = null
            var loadCount = 0
            var unloadCount = 0
            val fakeBridge = object : NativeLlmBridge {
                override val runtimeName = "FakeLlamaCppJni"
                override val isLoaded: Boolean get() = loadedPath != null
                override fun loadModel(modelPath: String, contextSize: Int, gpuLayers: Int) {
                    loadedPath = modelPath
                    loadCount++
                }
                override fun unloadModel() {
                    loadedPath = null
                    unloadCount++
                }
                override fun generate(request: LlmRequest): LlmResponse =
                    LlmResponse("On-device reply to: ${request.user}")
            }

            val backend = OnDeviceLlmBackend({ fakeBridge }, manager, { spec })
            Assert.assertFalse(backend.isAvailable)

            try {
                backend.generate(LlmRequest("sys", "hi"))
                Assert.fail("Expected LlmException when model is not downloaded")
            } catch (e: LlmException) {
                Assert.assertTrue(e.message!!.contains("not downloaded"))
            }

            val modelFile = manager.modelFile(spec)
            modelFile.writeBytes(ByteArray(1024 * 1024 + 64))
            Assert.assertTrue(manager.isDownloaded(spec))
            Assert.assertTrue(backend.isAvailable)

            val first = backend.generate(LlmRequest("sys", "Hello"))
            Assert.assertEquals("On-device reply to: Hello", first.text)
            Assert.assertEquals(1, loadCount)
            Assert.assertTrue(fakeBridge.isLoaded)

            backend.generate(LlmRequest("sys", "Second message"))
            Assert.assertEquals(1, loadCount)

            backend.unload()
            Assert.assertFalse(fakeBridge.isLoaded)
            Assert.assertEquals(1, unloadCount)

            Assert.assertTrue(manager.deleteModel(spec))
            Assert.assertFalse(manager.isDownloaded(spec))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
