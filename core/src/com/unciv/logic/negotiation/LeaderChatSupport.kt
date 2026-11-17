package com.unciv.logic.negotiation

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.commitments.DealDraft
import com.unciv.logic.commitments.llm.ChatSession
import com.unciv.logic.commitments.llm.ClassicTradeDraft
import com.unciv.logic.commitments.llm.LeaderProfile
import com.unciv.logic.commitments.llm.LlmBackend
import com.unciv.logic.commitments.llm.PersonalityDescriber
import com.unciv.logic.commitments.llm.LlmEndpoint
import com.unciv.logic.commitments.llm.ChatSnapshotCodec
import com.unciv.logic.commitments.llm.ManipulationTracker
import com.unciv.logic.commitments.llm.SavedLeaderChat
import com.unciv.logic.commitments.llm.BackgroundLlmRunner
import com.unciv.logic.commitments.llm.JniLlamaCppBridge
import com.unciv.logic.commitments.llm.OpenAiCompatibleBackend
import com.unciv.logic.commitments.llm.MobileModelSpec
import com.unciv.logic.commitments.llm.NativeLlmBridge
import com.unciv.logic.commitments.llm.OnDeviceLlmBackend
import com.unciv.logic.commitments.llm.OnDeviceModelManager
import com.unciv.logic.commitments.llm.SwitchableBackend
import java.io.File
import com.unciv.models.ruleset.nation.Nation
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.models.translations.tr
import com.unciv.ui.screens.victoryscreen.RankingType
import kotlin.math.abs

/** Turns a leader's real game state into the plain data the chat code works with. */
internal object LeaderProfiles {
    private const val STRONG_TRAIT = 7f // personality values run 0..10, 5 is neutral

    private const val MAX_REASONS = 4
    private const val MAX_ENEMIES = 3

    fun of(ai: Civilization, human: Civilization): LeaderProfile {
        val personality = ai.getPersonality()
        val traits = PersonalityValue.entries
            .filter { personality[it] >= STRONG_TRAIT }
            .map { it.description.lowercase() }
            .toMutableList()
        if (personality[PersonalityValue.Loyal] <= 3f) traits += "fickle"
        if (personality[PersonalityValue.DeclareWar] <= 2f) traits += "peaceful"

        val diplomacy = ai.getDiplomacyManager(human)
        val relationship = diplomacy?.relationshipLevel()?.name ?: "Neutral"
        val atWar = ai.isAtWarWith(human)
        return LeaderProfile(
            leaderName = ai.nation.leaderName,
            nationName = ai.civName,
            traits = traits,
            relationship = relationship,
            atWar = atWar,
            giftCredit = diplomacy?.getGoldGifts()?.coerceAtLeast(0) ?: 0,
            strength = LeaderProfile.describeStrength(
                ai.getStatForRanking(RankingType.Force).toFloat(), human.getStatForRanking(RankingType.Force).toFloat()
            ),
            voiceSamples = voiceSamples(ai.nation, hostile = atWar || relationship == "Enemy" || relationship == "Unforgivable"),
            background = LeaderProfile.backgroundFromIntro(ai.nation.startIntroPart1.tr(hideIcons = true)),
            temperament = PersonalityDescriber.describe(PersonalityValue.entries.associate { it.name to personality[it] }),
            reasons = reasons(diplomacy),
            situation = LeaderProfile.describeSituation(atWar, enemiesKnownTo(ai, human)),
            sheet = NationSheets.of(ai, human),
            playerLeaderName = human.nation.leaderName.ifBlank { human.civName }.tr(hideIcons = true),
            playerNationName = human.civName.tr(hideIcons = true)
        )
    }

    /**
     * Why the leader feels as it does: the game's own opinion modifiers, strongest first. Their texts are already
     * written in the leader's voice ("You declared war on us!", "We appreciate your gifts"), as the diplomacy screen shows.
     */
    private fun reasons(diplomacy: DiplomacyManager?): List<String> =
        diplomacy?.diplomaticModifiers?.entries.orEmpty()
            .sortedByDescending { abs(it.value) }
            .filter { abs(it.value) >= 1f }
            .mapNotNull { DiplomaticModifiers.safeValueOf(it.key)?.text }
            .filter { '[' !in it }
            .map { LeaderProfile.perspectiveReason(it.tr(hideIcons = true)) }
            .take(MAX_REASONS)

    /** The leader's other enemies. Only civs the player knows are named, so nothing hidden leaks. */
    fun enemiesKnownTo(ai: Civilization, human: Civilization): List<String> =
        ai.getCivsAtWarWith().filter { it != human && human.knows(it) }.map { it.civName }.take(MAX_ENEMIES)

    /**
     * The leader's own lines from the game's nation data, so the model hears how this particular leader speaks
     * (e.g. Nobunaga's "Oh, it's you..."). Lines with placeholders are skipped.
     */
    private fun voiceSamples(nation: Nation, hostile: Boolean): List<String> {
        val lines = if (hostile) listOf(nation.hateHello, nation.attacked) else listOf(nation.neutralHello)
        return LeaderProfile.pickVoiceSamples(lines.filter { it.isNotBlank() }.map { it.tr(hideIcons = true) })
            .filter { it.split(' ').size >= 2 } // skip bare 1-word "Hello." so small models do not copy 1-word replies
    }
}

/**
 * Where the language model lives. Defaults suit a local Ollama server. Each setting can come from, in order:
 * the console (`negotiation model <name>`), an environment variable set before the game starts, or the default.
 *   UNCIV_LLM_URL   (default http://localhost:11434/v1)
 *   UNCIV_LLM_MODEL (default qwen2.5:1.5b)
 *   UNCIV_LLM_KEY   (optional)
 */
/**
 * Holds the optional on-device native bridge (registered by AndroidLauncher / iOS launcher) and
 * switches seamlessly between On-Device execution and OpenAI-compatible HTTP server execution (Ollama).
 */
object PlatformLlm {
    /** Registered by the mobile launcher when a native JNI/Metal runtime (llama.cpp / LiteRT-LM) is linked. */
    @Volatile var nativeBridge: NativeLlmBridge? = if (JniLlamaCppBridge.isNativeLibraryAvailable) JniLlamaCppBridge else null

    /** Directory where on-device `.gguf` models are stored; set by AndroidLauncher or resolved via Gdx.files. */
    @Volatile var modelsDirectory: File? = null

    fun resolveModelsDirectory(): File {
        modelsDirectory?.let { dir -> if (dir.exists() || dir.mkdirs()) return dir }
        try {
            val gdxFiles = com.badlogic.gdx.Gdx.files
            if (gdxFiles != null && gdxFiles.localStoragePath != null) {
                val dir = gdxFiles.local("llm-models").file()
                if (dir.exists() || dir.mkdirs()) return dir
            }
        } catch (_: Throwable) {}
        val localDir = File("llm-models")
        if (localDir.exists() || localDir.mkdirs()) return localDir
        return File(System.getProperty("java.io.tmpdir", "."), "unciv-llm-models").apply { mkdirs() }
    }
}

internal object LlmSettings {
    const val DEFAULT_URL = "http://localhost:11434/v1"
    const val DEFAULT_MODEL = "gemma4:e2b"

    /** Set from the console or the in-game Model popup; lasts until the game is closed. */
    @Volatile var modelOverride: String? = null

    /** Set from the console or the in-game Model popup so mobile/iPad devices can point to a PC on Wi-Fi. */
    @Volatile var urlOverride: String? = null

    /** Default to "none" so thinking models (like Gemma 4) answer immediately on both desktop and mobile. */
    @Volatile var reasoningEffort: String? = "none"

    /** When true, free-form messages that do not match a fast regex are interpreted semantically by the LLM. */
    @Volatile var semanticParsingEnabled: Boolean = true

    /** When true, StrategicAiDirector guides AI tech choices, city production, and proactive diplomacy. */
    @Volatile var strategicAiEnabled: Boolean = true

    /** When true and a [PlatformLlm.nativeBridge] is available (or forced for testing), uses [OnDeviceLlmBackend]. */
    @Volatile var preferOnDevice: Boolean = true

    val modelManager = OnDeviceModelManager { PlatformLlm.resolveModelsDirectory() }

    val onDeviceBackend = OnDeviceLlmBackend(
        bridgeProvider = { PlatformLlm.nativeBridge },
        modelManager = modelManager,
        selectedSpec = { MobileModelSpec.byIdOrDefault(current().model) }
    )

    fun current() = LlmEndpoint(
        baseUrl = urlOverride ?: System.getenv("UNCIV_LLM_URL") ?: DEFAULT_URL,
        model = modelOverride ?: System.getenv("UNCIV_LLM_MODEL") ?: DEFAULT_MODEL,
        apiKey = System.getenv("UNCIV_LLM_KEY")
    )

    fun isUsingOnDevice(): Boolean = preferOnDevice && PlatformLlm.nativeBridge != null

    /** Starts or verifies the local background neural language model when ChatTable opens. */
    fun warmUpBackgroundModel(): String {
        if (isUsingOnDevice()) {
            try { onDeviceBackend.warmUp() } catch (_: Throwable) {}
            return statusDescription()
        }
        val cfg = current()
        BackgroundLlmRunner.ensureRunning(
            configuredBaseUrl = cfg.baseUrl,
            spec = MobileModelSpec.byIdOrDefault(cfg.model),
            modelManager = modelManager
        )
        return statusDescription()
    }

    fun statusDescription(): String {
        val spec = MobileModelSpec.byIdOrDefault(current().model)
        val downloaded = modelManager.isDownloaded(spec)
        val bridge = PlatformLlm.nativeBridge
        return when {
            preferOnDevice && bridge != null ->
                "On-Device (${bridge.runtimeName}, ${spec.id}, ${if (downloaded) "ready" else "download required"})"
            BackgroundLlmRunner.managedStatusLabel != null ->
                BackgroundLlmRunner.managedStatusLabel!!
            downloaded ->
                "Local GGUF (${spec.id} — auto-starts in background)"
            else ->
                "Local Model (${current().model} — auto-managed in background)"
        }
    }

    /**
     * Routes to the in-process native JNI bridge when linked, or automatically ensures the local neural
     * language model is running in the background (`llama-server` on `.gguf` or `ollama serve`) before generating.
     */
    fun createBackend(): LlmBackend = object : LlmBackend {
        override val isAvailable: Boolean
            get() = if (isUsingOnDevice()) onDeviceBackend.isAvailable else true

        override fun generate(request: com.unciv.logic.commitments.llm.LlmRequest): com.unciv.logic.commitments.llm.LlmResponse {
            if (isUsingOnDevice()) return onDeviceBackend.generate(request)
            val cfg = current()
            val activeUrl = BackgroundLlmRunner.ensureRunning(
                configuredBaseUrl = cfg.baseUrl,
                spec = MobileModelSpec.byIdOrDefault(cfg.model),
                modelManager = modelManager
            )
            return OpenAiCompatibleBackend(activeUrl, cfg.model, cfg.apiKey).generate(request)
        }
    }

    /** Called when DiplomacyScreen closes so background models release their RAM/VRAM back to the game. */
    fun onDiplomacyClosed() {
        onDeviceBackend.unload()
        val cfg = current()
        BackgroundLlmRunner.stopManagedModelAsync(cfg.baseUrl, cfg.model)
    }
}

/**
 * Conversations must outlive GameInfo clones (the game plays each turn on a clone), so they are kept
 * here, per running game, instead of in the NegotiationSystem. Not saved: they are lost when the game is closed.
 */
internal object ChatStore {
    private var gameId = ""
    private var loadedData = ""
    private val sessions = HashMap<String, ChatSession>()
    private val trackers = HashMap<String, ManipulationTracker>()
    private val lastTradeDrafts = HashMap<String, ClassicTradeDraft>()
    private val pendingProactiveDeals = HashMap<String, DealDraft>()
    private val lastProactiveTurns = HashMap<String, Int>()

    @Synchronized
    private fun syncGame(gameId: String, chatData: String, createSession: () -> ChatSession) {
        if (gameId != this.gameId) {
            this.gameId = gameId
            this.loadedData = ""
            sessions.clear()
            trackers.clear()
            lastTradeDrafts.clear()
            pendingProactiveDeals.clear()
            lastProactiveTurns.clear()
        }
        if (chatData.isNotEmpty() && chatData != loadedData && sessions.isEmpty()) {
            loadedData = chatData
            for (saved in ChatSnapshotCodec.decode(chatData)) {
                val s = createSession()
                s.restoreMessages(saved.messages)
                sessions[saved.key] = s
                val t = ManipulationTracker()
                t.restoreFlags(saved.recentFlatteries, saved.recentFlatteryAsks, saved.hostileStrikes)
                trackers[saved.key] = t
            }
        }
    }

    @Synchronized
    fun sessionFor(gameId: String, chatData: String, key: String, create: () -> ChatSession): ChatSession {
        syncGame(gameId, chatData, create)
        return sessions.getOrPut(key, create)
    }

    @Synchronized
    fun trackerFor(gameId: String, chatData: String, key: String, createSession: () -> ChatSession): ManipulationTracker {
        syncGame(gameId, chatData, createSession)
        return trackers.getOrPut(key) { ManipulationTracker() }
    }

    @Synchronized
    fun lastTradeDraftFor(key: String): ClassicTradeDraft? = lastTradeDrafts[key]

    @Synchronized
    fun setLastTradeDraft(key: String, draft: ClassicTradeDraft?) {
        if (draft == null) lastTradeDrafts.remove(key) else lastTradeDrafts[key] = draft
    }

    @Synchronized
    fun pendingProactiveDealFor(key: String): DealDraft? = pendingProactiveDeals[key]

    @Synchronized
    fun setPendingProactiveDeal(key: String, draft: DealDraft?) {
        if (draft == null) pendingProactiveDeals.remove(key) else pendingProactiveDeals[key] = draft
    }

    @Synchronized
    fun lastProactiveTurnFor(key: String): Int? = lastProactiveTurns[key]

    @Synchronized
    fun setLastProactiveTurn(key: String, turn: Int) {
        lastProactiveTurns[key] = turn
    }

    @Synchronized
    fun encodeCurrent(gameId: String): String {
        if (gameId != this.gameId) return ""
        val keys = (sessions.keys + trackers.keys).distinct()
        val list = keys.mapNotNull { key ->
            val msgs = sessions[key]?.messages.orEmpty()
            val tracker = trackers[key]
            val (flat, asks) = tracker?.snapshotFlags() ?: (emptyList<Boolean>() to emptyList())
            val strikes = tracker?.hostileStrikes ?: 0
            if (msgs.isEmpty() && flat.isEmpty() && asks.isEmpty() && strikes == 0) null
            else SavedLeaderChat(key, msgs, flat, asks, strikes)
        }
        val encoded = ChatSnapshotCodec.encode(list)
        loadedData = encoded
        return encoded
    }
}
