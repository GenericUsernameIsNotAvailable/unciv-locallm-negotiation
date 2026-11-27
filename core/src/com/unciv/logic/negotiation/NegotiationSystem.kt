package com.unciv.logic.negotiation

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.automation.civilization.TradeAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.DiplomacyAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.logic.commitments.AcceptResult
import com.unciv.logic.commitments.AiAction
import com.unciv.logic.commitments.CommitmentManager
import com.unciv.logic.commitments.CommitmentSnapshotCodec
import com.unciv.logic.commitments.CommitmentStatus
import com.unciv.logic.commitments.CommitmentValidator
import com.unciv.logic.commitments.DealDraft
import com.unciv.logic.commitments.DealEvaluator
import com.unciv.logic.commitments.HagglingLimit
import com.unciv.logic.commitments.NegotiationDifficulty
import com.unciv.logic.commitments.NegotiationFacts
import com.unciv.logic.commitments.OutboxKind
import com.unciv.logic.commitments.OutboxMessage
import com.unciv.logic.commitments.CivStrategicState
import com.unciv.logic.commitments.ConstructionCategory
import com.unciv.logic.commitments.ProactiveDiplomacy
import com.unciv.logic.commitments.ProposalResult
import com.unciv.logic.commitments.StrategicAiDirector
import com.unciv.logic.commitments.StrategicPosture
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.nation.PersonalityValue
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.ui.screens.victoryscreen.RankingType
import com.unciv.logic.commitments.StandardBetrayalOracle
import com.unciv.logic.commitments.StandardTermValuator
import com.unciv.logic.commitments.Term
import com.unciv.logic.commitments.WorldEvent
import com.unciv.logic.commitments.llm.ChatSession
import com.unciv.logic.commitments.llm.ClassicItemKind
import com.unciv.logic.commitments.llm.ClassicTradeAssessment
import com.unciv.logic.commitments.llm.ClassicTradeDraft
import com.unciv.logic.commitments.llm.ClassicTradeItem
import com.unciv.logic.commitments.llm.HaggleDirection
import com.unciv.logic.commitments.llm.IntelOutcome
import com.unciv.logic.commitments.llm.IntelReport
import com.unciv.logic.commitments.llm.IntelligenceEngine
import com.unciv.logic.commitments.llm.LeaderProfile
import com.unciv.logic.commitments.llm.LlmBackend
import com.unciv.logic.commitments.llm.ManipulationSignal
import com.unciv.logic.commitments.llm.MessageToneAnalysis
import com.unciv.logic.commitments.llm.ProposalAssessment
import com.unciv.logic.commitments.llm.ProposalParser
import com.unciv.logic.commitments.llm.SemanticAnalysis
import com.unciv.logic.commitments.llm.SemanticNegotiationParser
import com.unciv.logic.commitments.llm.ToneKind
import com.unciv.logic.commitments.llm.MultiAssetBarter
import com.unciv.logic.commitments.llm.TradableCity
import com.unciv.logic.commitments.llm.TradeIntentParser
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.translations.tr
import com.unciv.utils.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Plain immutable snapshot captured on the GL thread so the LLM semantic parser can run on a background thread. */
internal data class SemanticTurnContext(
    val playerText: String,
    val humanCivId: String,
    val aiCivId: String,
    val playerCities: List<TradableCity>,
    val aiCities: List<TradableCity>,
    val playerLuxuries: List<String>,
    val aiLuxuries: List<String>,
    val playerStrategics: List<String>,
    val aiStrategics: List<String>,
    val knownThirdCivs: List<String>,
    val knownConstructions: List<String>,
    val previousDraft: ClassicTradeDraft?,
    val needsSemanticCall: Boolean
)

/** Everything prepared before a chat message is sent to the language model. */
internal data class PreparedChatTurn(
    val profile: LeaderProfile,
    val facts: NegotiationFacts,
    val tone: MessageToneAnalysis,
    val proposal: ProposalAssessment?,
    val classicTrade: ClassicTradeAssessment? = null,
    val executableTrade: Trade? = null,
    val intelReport: IntelReport? = null,
    val denouncedNow: Boolean = false
) {
    val directive: String
        get() = proposal?.toPromptDirective()
            ?: classicTrade?.toPromptDirective()
            ?: intelReport?.promptDirective.orEmpty()
    val allowsAgreement: Boolean
        get() = !tone.walkedOut && (
            proposal is ProposalAssessment.Accepted ||
                proposal is ProposalAssessment.Counteroffered ||
                classicTrade is ClassicTradeAssessment.Accepted ||
                classicTrade is ClassicTradeAssessment.Counteroffered
            )
    val confirmableCommitmentDraft: DealDraft?
        get() = if (tone.walkedOut) null else when (proposal) {
            is ProposalAssessment.Accepted -> proposal.draft
            is ProposalAssessment.Counteroffered -> proposal.counterDraft
            else -> null
        }
}

/**
 * Game-side owner of the commitment engine (see com.unciv.logic.commitments).
 * One per [GameInfo], reachable as `gameInfo.negotiation`.
 */
class NegotiationSystem(private val gameInfo: GameInfo) {
    private val world = UncivCommitmentWorld(gameInfo)
    private val valuationSupport = UncivValuationSupport(gameInfo)
    private val valuator = StandardTermValuator(valuationSupport)
    private val walkOutTurns = HashMap<String, Int>()

    val manager = CommitmentManager(
        world = world,
        actions = UncivCommitmentActions(gameInfo),
        difficulty = resolveDifficulty(),
        betrayalOracle = StandardBetrayalOracle(valuationSupport),
        noise = { Random(gameInfo.turns * 31 + gameInfo.civilizations.size).nextFloat() },
        onChanged = { save() }
    )

    init {
        load()
    }

    private fun resolveDifficulty(): NegotiationDifficulty {
        val name = gameInfo.difficulty.lowercase()
        return when {
            name in listOf("settler", "chieftain", "warlord") -> NegotiationDifficulty.Easy
            name in listOf("emperor", "immortal", "deity") -> NegotiationDifficulty.Hard
            else -> NegotiationDifficulty.Normal
        }
    }

    private fun save() {
        gameInfo.negotiationData = CommitmentSnapshotCodec.encode(manager.snapshot())
    }

    fun saveChatData() {
        gameInfo.chatData = ChatStore.encodeCurrent(gameInfo.gameId)
    }

    private fun load() {
        val text = gameInfo.negotiationData
        if (text.isEmpty()) return
        try {
            manager.restore(CommitmentSnapshotCodec.decode(text))
        } catch (e: IllegalArgumentException) {
            Log.error("Could not restore negotiation state; starting empty", e)
        }
    }

    // ---------------------------------------------------------------- events from the game

    fun onNewTurn() {
        ChatStore.clearExpiredOnNewTurn(gameInfo.turns)
        checkDemilitarizedZones()
        val report = manager.tick()
        for (msg in report.outbox) {
            val recipient = gameInfo.civMap[msg.to] ?: continue
            val sender = gameInfo.civMap[msg.from] ?: continue
            if (!recipient.isHuman()) continue
            recipient.addNotification(
                "[${sender.civName}] has a diplomatic message: ${formatOutboxMessage(msg)}",
                DiplomacyAction(sender, showTrade = false),
                NotificationCategory.Diplomacy,
                sender.civName,
                NotificationIcon.Diplomacy
            )
        }
        checkProactiveAiMessages()
    }

    private fun checkDemilitarizedZones() {
        val activeDmzs = manager.allCommitments().filter {
            it.status == CommitmentStatus.Active && it.term == Term.DemilitarizeBorder &&
                gameInfo.turns in it.activeFrom..it.endTurn
        }
        for (dmz in activeDmzs) {
            val committer = gameInfo.civMap[dmz.committer] ?: continue
            val beneficiary = gameInfo.civMap[dmz.beneficiary] ?: continue
            val borderTiles = beneficiary.cities.asSequence()
                .flatMap { it.getTiles() }
                .flatMap { tile -> tile.neighbors + tile }
                .filter { it.getOwner() != committer }
                .toSet()
            val intrudingUnits = committer.units.getCivUnits()
                .filter { it.isMilitary() && it.currentTile in borderTiles }
                .toList()
            if (committer.isAI() && !dmz.breachLicensed) {
                intrudingUnits.forEach { it.movement.teleportToClosestMoveableTile() }
            } else if (intrudingUnits.size >= DMZ_VIOLATION_MIN_UNITS) {
                manager.onEvent(WorldEvent.BorderZoneViolated(committer.civID, beneficiary.civID))
            }
        }
    }

    private fun checkProactiveAiMessages() {
        val humans = gameInfo.civilizations.filter { it.isHuman() && !it.isDefeated() }
        for (human in humans) {
            for (ai in human.getKnownCivs().filter { it.isAI() && it.isMajorCiv && !it.isDefeated() }) {
                val aiDiplo = ai.getDiplomacyManager(human) ?: continue
                if (aiDiplo.hasFlag(DiplomacyFlags.MilitaryPresenceNearBorderOrAttackedUsDespitePromise) &&
                    !forbidsWar(human, ai) &&
                    !manager.isForbidden(human.civID, AiAction.MassTroopsNear(ai.civID))
                ) {
                    val session = chatWith(ai, human)
                    val ultimatum = "Your military forces are massing along our borders. Withdraw them or agree to a Demilitarised Zone (DMZ) or Non-Aggression Pact at once!"
                    if (session.messages.none { !it.fromPlayer && it.text == ultimatum }) {
                        session.addLeaderInitiative(ultimatum)
                        saveChatData()
                        human.addNotification(
                            "[${ai.nation.leaderName}] of [${ai.civName}] demands an explanation for your troops near their border!",
                            DiplomacyAction(ai, showTrade = false),
                            NotificationCategory.Diplomacy,
                            ai.civName,
                            NotificationIcon.Diplomacy
                        )
                        continue
                    }
                }

                if (!LlmSettings.strategicAiEnabled) continue
                // Ensure ChatStore is synced with saved chatData before checking cooldowns
                val session = chatWith(ai, human)
                if (gameInfo.turns - ChatStore.lastGlobalProactiveTurn() < ProactiveDiplomacy.GLOBAL_TURNS_BETWEEN_PROPOSALS) {
                    continue
                }
                val pairKey = "${ai.civID}>${human.civID}"
                val commonEnemies = ai.getCivsAtWarWith()
                    .filter { it != human && it.isMajorCiv && !it.isDefeated() && human.knows(it) && !human.isAtWarWith(it) }
                    .map { it.civID }
                val declareWarTrait = ai.getPersonality()[PersonalityValue.DeclareWar]
                val proposal = ProactiveDiplomacy.considerProposal(
                    aiCiv = ai.civID,
                    aiLeaderName = ai.nation.leaderName,
                    humanCiv = human.civID,
                    currentTurn = gameInfo.turns,
                    lastProposalTurn = ChatStore.lastProactiveTurnFor(pairKey),
                    relationship = world.relationship(ai.civID, human.civID),
                    atWarWithHuman = ai.isAtWarWith(human),
                    commonEnemiesOfAi = commonEnemies,
                    aiGold = ai.gold,
                    aiGoldPerTurn = ai.stats.statsForNextTurn.gold.toInt(),
                    existingOpenCommitments = manager.openCommitmentsOf(ai.civID),
                    willingnessToDeclareWar = declareWarTrait,
                    isAtWarWithAnyone = ai.isAtWar()
                )
                if (proposal != null) {
                    val alreadyRecentDuplicate = session.messages.takeLast(8).any {
                        !it.fromPlayer && (it.text == proposal.message || it.text.contains("Non-Aggression Pact", ignoreCase = true))
                    }
                    if (!alreadyRecentDuplicate) {
                        session.addLeaderInitiative(proposal.message)
                        ChatStore.setPendingProactiveDeal(pairKey, proposal.draft, gameInfo.turns)
                        ChatStore.setLastProactiveTurn(pairKey, gameInfo.turns)
                        saveChatData()
                        human.addNotification(
                            "[${ai.nation.leaderName}] of [${ai.civName}] has sent a diplomatic proposal: ${proposal.message}",
                            DiplomacyAction(ai, showTrade = false),
                            NotificationCategory.Diplomacy,
                            ai.civName,
                            NotificationIcon.Diplomacy
                        )
                    }
                }
            }
        }
    }

    fun onWarDeclared(attacker: Civilization, defender: Civilization, warType: WarType) {
        if (warType != WarType.DirectWar && warType != WarType.JoinWar && warType != WarType.TeamWar) return
        manager.onEvent(WorldEvent.WarDeclared(attacker.civID, defender.civID))
    }

    fun onPeaceMade(a: Civilization, b: Civilization) {
        manager.onEvent(WorldEvent.PeaceMade(a.civID, b.civID))
    }

    fun onTradeCompleted(a: Civilization, b: Civilization) {
        manager.onEvent(WorldEvent.TradeCompleted(a.civID, b.civID))
    }

    fun onCityStateBullied(bully: Civilization, cityState: Civilization) {
        manager.onEvent(WorldEvent.CityStateBullied(bully.civID, cityState.civID))
    }

    fun onDiplomaticVoteCast(voter: Civilization, candidate: Civilization?) {
        manager.onEvent(WorldEvent.DiplomaticVoteCast(voter.civID, candidate?.civID))
    }

    fun onCityFounded(city: City) {
        val founder = city.civ
        val location = city.getCenterTile()
        for (other in founder.getKnownCivs()) {
            if (!other.isMajorCiv) continue
            if (other.cities.any { it.getCenterTile().aerialDistanceTo(location) <= SETTLE_PROMISE_DISTANCE })
                manager.onEvent(WorldEvent.CitySettledNear(founder.civID, other.civID))
        }
    }

    fun onConstructionCompleted(civ: Civilization, constructionName: String) {
        manager.onEvent(WorldEvent.ConstructionCompleted(civ.civID, constructionName))
    }

    // ---------------------------------------------------------------- questions from AI automation

    fun forbidsWar(attacker: Civilization, target: Civilization): Boolean =
        manager.isForbidden(attacker.civID, AiAction.DeclareWar(target.civID))

    fun forbidsPeace(civ: Civilization, enemy: Civilization): Boolean =
        manager.isForbidden(civ.civID, AiAction.MakePeace(enemy.civID))

    fun forbidsSettlingNear(settler: Civilization, near: Civilization): Boolean =
        manager.isForbidden(settler.civID, AiAction.SettleNear(near.civID))

    fun forbidsTrade(actor: Civilization, partner: Civilization): Boolean =
        manager.isForbidden(actor.civID, AiAction.TradeWith(partner.civID))

    fun forbidsBullyingCityState(actor: Civilization, cityState: Civilization): Boolean =
        manager.isForbidden(actor.civID, AiAction.BullyCityState(cityState.civID))

    fun committedDiplomaticVote(voter: Civilization): Civilization? {
        val candidateId = manager.committedVoteCandidate(voter.civID) ?: return null
        return gameInfo.civMap[candidateId]?.takeIf { !it.isDefeated() }
    }

    fun constructionBias(civ: Civilization): Map<String, Float> = manager.constructionBias(civ.civID)

    fun evaluateStrategicPosture(civ: Civilization): StrategicPosture {
        val ownForce = civ.getStatForRanking(RankingType.Force).toFloat()
        val enemyForce = civ.getCivsAtWarWith().sumOf { it.getStatForRanking(RankingType.Force) }.toFloat()
        val preferred = civ.getPreferredVictoryTypes().firstOrNull() ?: "Scientific"
        val militaryCount = civ.units.getCivUnits().count { it.baseUnit.isMilitary }
        val state = CivStrategicState(
            civName = civ.civID,
            atWar = civ.isAtWar(),
            ownForce = ownForce,
            enemyForce = enemyForce,
            happiness = civ.getHappiness(),
            gold = civ.gold,
            goldPerTurn = civ.stats.statsForNextTurn.gold.toInt(),
            cityCount = civ.cities.size,
            militaryUnitCount = militaryCount,
            preferredVictory = preferred
        )
        return StrategicAiDirector.evaluatePosture(state)
    }

    fun strategicConstructionMultiplier(civ: Civilization, choice: IConstruction): Float {
        val commitmentMult = manager.constructionBias(civ.civID)[choice.name] ?: 1f
        if (!civ.isAI() || !civ.isMajorCiv || !LlmSettings.strategicAiEnabled) return commitmentMult
        val posture = evaluateStrategicPosture(civ)
        val category = classifyConstruction(civ, choice)
        return commitmentMult * StrategicAiDirector.constructionMultiplier(posture, category)
    }

    private fun classifyConstruction(civ: Civilization, choice: IConstruction): ConstructionCategory {
        val name = choice.name
        if (name in civ.gameInfo.spaceResources) return ConstructionCategory.SpaceshipPart
        if (choice is BaseUnit) {
            return when {
                choice.isMilitary -> ConstructionCategory.MilitaryUnit
                name.contains("Settler", ignoreCase = true) -> ConstructionCategory.Settler
                name.contains("Worker", ignoreCase = true) || name.contains("Work Boat", ignoreCase = true) -> ConstructionCategory.Worker
                else -> ConstructionCategory.Other
            }
        }
        if (choice is Building) {
            return when {
                choice.cityStrength > 0 || choice.cityHealth > 0 -> ConstructionCategory.DefenseBuilding
                choice.happiness > 0 -> ConstructionCategory.HappinessBuilding
                choice.gold > 0 -> ConstructionCategory.GoldBuilding
                choice.science > 0 -> ConstructionCategory.ScienceBuilding
                choice.culture > 0 -> ConstructionCategory.CultureBuilding
                else -> ConstructionCategory.Other
            }
        }
        return ConstructionCategory.Other
    }

    fun strategicTechMultiplier(civ: Civilization, tech: Technology): Float {
        if (!civ.isAI() || !civ.isMajorCiv || !LlmSettings.strategicAiEnabled) return 1f
        val posture = evaluateStrategicPosture(civ)
        val ruleset = civ.gameInfo.ruleset
        val unlockedUnits = ruleset.units.values.filter { it.requiredTech == tech.name }
        val unlockedBuildings = ruleset.buildings.values.filter { it.requiredTech == tech.name }
        return StrategicAiDirector.techPriorityMultiplier(
            posture = posture,
            unlocksMilitary = unlockedUnits.any { it.isMilitary } || unlockedBuildings.any { it.cityStrength > 0 },
            unlocksScience = unlockedBuildings.any { it.science > 0 },
            unlocksGold = unlockedBuildings.any { it.gold > 0 },
            unlocksHappiness = unlockedBuildings.any { it.happiness > 0 },
            unlocksCulture = unlockedBuildings.any { it.culture > 0 }
        )
    }

    // ---------------------------------------------------------------- immediate answers to human offers

    private val hagglingLimit = HagglingLimit(MAX_REFUSALS_PER_TURN)

    fun isAudienceEndedForTurn(ai: Civilization, human: Civilization): Boolean =
        walkOutTurns["${ai.civID}>${human.civID}"] == gameInfo.turns

    fun offerToAi(ai: Civilization, human: Civilization, humanSideTrade: Trade): ImmediateTradeResult {
        val turn = gameInfo.turns
        val key = "${ai.civID}>${human.civID}"
        if (walkOutTurns[key] == turn || hagglingLimit.isExhausted(turn, key)) return ImmediateTradeResult.OutOfPatience
        val result = ImmediateTrade.respond(ai, human, humanSideTrade)
        if (result == ImmediateTradeResult.Declined) hagglingLimit.noteRefusal(turn, key)
        return result
    }

    // ---------------------------------------------------------------- chatting with leaders & deal proposals

    private val backend: LlmBackend by lazy { LlmSettings.createBackend() }

    private fun newSession(): ChatSession =
        ChatSession(backend, reasoningEffort = { LlmSettings.reasoningEffort })

    fun chatWith(ai: Civilization, human: Civilization): ChatSession =
        ChatStore.sessionFor(gameInfo.gameId, gameInfo.chatData, "${ai.civID}>${human.civID}", ::newSession)

    fun chatContext(ai: Civilization, human: Civilization): Pair<LeaderProfile, NegotiationFacts> =
        LeaderProfiles.of(ai, human) to NegotiationFacts.build(manager, ai.civID, human.civID)

    /** Captures plain data on the GL thread so [runSemanticParserIfNeeded] can run safely on a background thread. */
    internal fun captureSemanticContext(ai: Civilization, human: Civilization, playerText: String): SemanticTurnContext {
        val pairKey = "${ai.civID}>${human.civID}"
        val ruleset = ai.gameInfo.ruleset
        val luxuries = ruleset.tileResources.values.filter { it.resourceType == ResourceType.Luxury }.map { it.name }
        val strategics = ruleset.tileResources.values.filter { it.resourceType == ResourceType.Strategic }.map { it.name }
        val playerLux = luxuries.filter { human.hasResource(it) }
        val aiLux = luxuries.filter { ai.hasResource(it) }
        val playerStrat = strategics.filter { human.hasResource(it) }
        val aiStrat = strategics.filter { ai.hasResource(it) }
        val playerCities = human.cities.filterNot { it.isCapital() || it.isInResistance() }
            .map { TradableCity(it.id, it.name.tr(hideIcons = true)) }
        val aiCities = ai.cities.filterNot { it.isCapital() || it.isInResistance() }
            .map { TradableCity(it.id, it.name.tr(hideIcons = true)) }
        val knownThirdCivs = human.getKnownCivs().filter { it != ai && !it.isDefeated() }.map { it.civID }.toList()
        val knownConstructions = (ruleset.buildings.keys + ruleset.units.keys).toList()
        val previousDraft = ChatStore.lastTradeDraftFor(pairKey)

        // Check if fast regex parsers already match a clear commitment or trade
        val fastCommitment = ProposalParser.parse(playerText, human.civID, ai.civID, knownThirdCivs, knownConstructions, gameInfo.turns)
        val fastTrade = TradeIntentParser.parseClassicTrade(
            playerText, human.civID, ai.civID, playerLux, aiLux, playerStrat, aiStrat,
            playerCities = playerCities, aiCities = aiCities, aiGold = ai.gold, playerGold = human.gold,
            previousDraft = previousDraft
        )
        val wordCount = playerText.trim().split(Regex("""\s+""")).size
        val hasBargainingCue = Regex(
            """\b(?:offer|trade|give|pay|gold|gpt|per\s+turn|city|cities|luxury|luxuries|resource|pact|alliance|peace|war|embargo|loan|more|less|sweeten|deal|throw\s+in|chuck)\b|\d+""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(playerText)
        val isQuestionOrGreeting = playerText.trim().endsWith("?") || Regex(
            """^(?:hi|hello|hey|greetings|how\s+are|what|who|why|where|when|do\s+you|tell\s+me)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(playerText.trim())
        val needsSemantic = LlmSettings.semanticParsingEnabled && fastCommitment == null && fastTrade == null && wordCount >= 4 && hasBargainingCue && !isQuestionOrGreeting

        return SemanticTurnContext(
            playerText = playerText,
            humanCivId = human.civID,
            aiCivId = ai.civID,
            playerCities = playerCities,
            aiCities = aiCities,
            playerLuxuries = playerLux,
            aiLuxuries = aiLux,
            playerStrategics = playerStrat,
            aiStrategics = aiStrat,
            knownThirdCivs = knownThirdCivs,
            knownConstructions = knownConstructions,
            previousDraft = previousDraft,
            needsSemanticCall = needsSemantic
        )
    }

    /** Runs on the background thread before [prepareChatTurn] so the GL thread never blocks. */
    internal fun runSemanticParserIfNeeded(ctx: SemanticTurnContext): SemanticAnalysis? {
        if (!ctx.needsSemanticCall) return null
        return SemanticNegotiationParser.analyzeWithLlm(
            backend = backend,
            playerText = ctx.playerText,
            human = ctx.humanCivId,
            ai = ctx.aiCivId,
            playerCities = ctx.playerCities,
            aiCities = ctx.aiCities,
            playerLuxuries = ctx.playerLuxuries,
            aiLuxuries = ctx.aiLuxuries,
            playerStrategics = ctx.playerStrategics,
            aiStrategics = ctx.aiStrategics,
            knownThirdCivs = ctx.knownThirdCivs,
            previousDraft = ctx.previousDraft,
            reasoningEffort = LlmSettings.reasoningEffort ?: "none"
        )
    }

    internal fun prepareChatTurn(
        ai: Civilization,
        human: Civilization,
        playerText: String,
        semanticHint: SemanticAnalysis? = null
    ): PreparedChatTurn {
        val pairKey = "${ai.civID}>${human.civID}"
        val facts = NegotiationFacts.build(manager, ai.civID, human.civID)
        val initialProfile = LeaderProfiles.of(ai, human)
        val tracker = ChatStore.trackerFor(gameInfo.gameId, gameInfo.chatData, pairKey, ::newSession)
        val tone = tracker.analyze(playerText, initialProfile, facts, gameInfo.turns, semanticHint)
        val denouncedNow = applyToneOpinionAndConsequences(ai, human, pairKey, tone)

        val knownThirdCivs = human.getKnownCivs()
            .filter { it != ai && !it.isDefeated() }
            .map { it.civID }
            .toList()
        val knownConstructions = (ai.gameInfo.ruleset.buildings.keys + ai.gameInfo.ruleset.units.keys).toList()

        val blockDeals = tone.walkedOut ||
            tone.manipulation == ManipulationSignal.FalseOffer ||
            tone.manipulation == ManipulationSignal.FabricatedClaim

        val draft = if (blockDeals) null else (
            ProposalParser.parse(playerText, human.civID, ai.civID, knownThirdCivs, knownConstructions, gameInfo.turns)
                ?: semanticHint?.let { SemanticNegotiationParser.toDealDraft(it, human.civID, ai.civID, knownThirdCivs, knownConstructions, gameInfo.turns) }
            )
        val proposal = draft?.let { parsed ->
            val evaluator = DealEvaluator(valuator, resolveDifficulty())
            val validator = CommitmentValidator(world)
            ProposalParser.evaluateProposal(
                parsed, ai.civID, tone.toneScore, validator, manager.allCommitments(), evaluator,
                playerTreasury = human.gold, playerGoldPerTurn = human.stats.statsForNextTurn.gold.toInt(), now = gameInfo.turns
            )
        }

        val (classicAssessment, execTrade) = if (!blockDeals && proposal == null) {
            evaluateClassicTradeProposal(ai, human, pairKey, playerText, tone.toneScore, semanticHint)
        } else null to null

        val intelReport = if (!blockDeals && proposal == null && classicAssessment == null) {
            val majorThirdCivs = human.getKnownCivs().filter { it != ai && it.isMajorCiv && !it.isDefeated() }.map { it.civID }.toList()
            evaluateIntelligence(ai, human, playerText, majorThirdCivs)
        } else null

        val updatedProfile = LeaderProfiles.of(ai, human)
        saveChatData()
        return PreparedChatTurn(updatedProfile, facts, tone, proposal, classicAssessment, execTrade, intelReport, denouncedNow)
    }

    private fun evaluateIntelligence(
        ai: Civilization,
        human: Civilization,
        playerText: String,
        knownThirdCivs: List<String>
    ): IntelReport? {
        if (knownThirdCivs.isEmpty()) return null
        val inspector = UncivIntelligenceInspector(gameInfo, human)
        val report = IntelligenceEngine.evaluate(playerText, ai.civID, knownThirdCivs, inspector) ?: return null
        val diplo = ai.getDiplomacyManager(human)
        when (report.outcome) {
            IntelOutcome.VerifiedTrue, IntelOutcome.BluffBelieved -> {
                diplo?.addDiminishingModifier(DiplomaticModifiers.SharedIntelligence, report.opinionDelta)
                val targetCiv = gameInfo.getCivilization(report.target)
                val aiToTarget = ai.getDiplomacyManager(targetCiv)
                if (aiToTarget != null && !aiToTarget.hasFlag(DiplomacyFlags.WaryOf)) {
                    aiToTarget.setFlag(DiplomacyFlags.WaryOf, -4)
                }
            }
            IntelOutcome.BluffCalledOut -> {
                diplo?.addDiminishingModifier(DiplomaticModifiers.CaughtLying, report.opinionDelta)
            }
        }
        return report
    }

    private fun evaluateClassicTradeProposal(
        ai: Civilization,
        human: Civilization,
        pairKey: String,
        playerText: String,
        toneScore: Float,
        semanticHint: SemanticAnalysis? = null
    ): Pair<ClassicTradeAssessment?, Trade?> {
        val ruleset = ai.gameInfo.ruleset
        val luxuries = ruleset.tileResources.values.filter { it.resourceType == ResourceType.Luxury }.map { it.name }
        val strategics = ruleset.tileResources.values.filter { it.resourceType == ResourceType.Strategic }.map { it.name }
        val playerLux = luxuries.filter { human.hasResource(it) }
        val aiLux = luxuries.filter { ai.hasResource(it) }
        val playerStrat = strategics.filter { human.hasResource(it) }
        val aiStrat = strategics.filter { ai.hasResource(it) }
        val playerCities = human.cities.filterNot { it.isCapital() || it.isInResistance() }
            .map { TradableCity(it.id, it.name.tr(hideIcons = true)) }
        val aiCities = ai.cities.filterNot { it.isCapital() || it.isInResistance() }
            .map { TradableCity(it.id, it.name.tr(hideIcons = true)) }

        val previousDraft = ChatStore.lastTradeDraftFor(pairKey)
        val draft = TradeIntentParser.parseClassicTrade(
            playerText, human.civID, ai.civID, playerLux, aiLux, playerStrat, aiStrat,
            playerCities = playerCities, aiCities = aiCities, aiGold = ai.gold, playerGold = human.gold,
            previousDraft = previousDraft
        ) ?: semanticHint?.let {
            SemanticNegotiationParser.toClassicTradeDraft(
                it, human.civID, ai.civID, playerCities, aiCities, playerLux, aiLux, playerStrat, aiStrat, previousDraft, playerText
            )
        } ?: return null to null

        if (draft.items.any { it.kind == ClassicItemKind.City && it.cityId.isEmpty() }) {
            val who = if (draft.items.first { it.kind == ClassicItemKind.City }.fromPlayer) human.civName else ai.civName
            return ClassicTradeAssessment.Declined(
                draft, "$who only has a capital city (or cities in resistance), and capital cities can never be traded"
            ) to null
        }

        val playerLuxWantedByAi = playerLux.filter { !ai.hasResource(it) }
        val aiSpareLuxForPlayer = aiLux.filter { !human.hasResource(it) }
        val aiCivResources = ai.getCivResourcesByName()
        val humanCivResources = human.getCivResourcesByName()
        val aiStratMap = aiStrat.associateWith { (aiCivResources[it] ?: 0) }.filterValues { it >= 2 }
        val playerStratWantedMap = playerStrat.associateWith { (humanCivResources[it] ?: 0) }.filterValues { it >= 2 }

        if (draft.isAiCityPriceInquiry) {
            val singleCityTrade = toUncivTrade(draft, human)
            val evaluation = TradeEvaluation()
            val priceInGold = -evaluation.getTradeAcceptability(singleCityTrade.reverse(), ai, human, includeDiplomaticGifts = false)
            val cityName = draft.items.first().name
            val multiDemand = MultiAssetBarter.buildAiDemandFromPlayer(
                requiredValue = priceInGold,
                playerGold = human.gold,
                playerGoldPerTurn = human.stats.statsForNextTurn.gold.toInt(),
                playerLuxuriesWantedByAi = playerLuxWantedByAi,
                playerStrategicsWantedByAi = playerStratWantedMap
            )
            if (multiDemand != null) {
                val counterDraft = draft.copy(items = multiDemand + draft.items, isAiCityPriceInquiry = false)
                ChatStore.setLastTradeDraft(pairKey, counterDraft)
                val execTrade = toUncivTrade(counterDraft, human)
                val desc = describeHumanSideTrade(execTrade, human.civName, ai.civName)
                return ClassicTradeAssessment.Counteroffered(counterDraft, desc) to execTrade
            }
            val wantedAssetsHint = (playerLuxWantedByAi + playerStratWantedMap.keys).take(3)
                .let { if (it.isEmpty()) "gold and resources" else "${it.joinToString(", ")} plus gold" }
            return ClassicTradeAssessment.Declined(
                draft,
                "to part with the city of $cityName, you would require at least $priceInGold gold-equivalent in value (such as $wantedAssetsHint)"
            ) to null
        }

        if (draft.haggleDirection != null) {
            return evaluateRelativeHaggle(ai, human, pairKey, playerText, draft, aiSpareLuxForPlayer, aiStratMap)
        }

        if (draft.isOpenInquiry) {
            return evaluateOpenInquiry(ai, human, pairKey, draft, aiSpareLuxForPlayer, aiStratMap)
        }

        ChatStore.setLastTradeDraft(pairKey, draft)

        val humanSideTrade = toUncivTrade(draft, human)
        val aiSideTrade = humanSideTrade.reverse()
        val evaluation = TradeEvaluation()
        if (!evaluation.isTradeValid(aiSideTrade, ai, human)) {
            return ClassicTradeAssessment.Declined(draft, "one of us does not have those items available to trade right now") to null
        }
        if (ImmediateTrade.violatesActiveCommitment(ai, human, aiSideTrade)) {
            return ClassicTradeAssessment.Declined(draft, "an active commitment or embargo forbids those terms") to null
        }

        val acceptability = evaluation.getTradeAcceptability(aiSideTrade, ai, human, includeDiplomaticGifts = true)
        val toneBonusGold = (toneScore * 5f).toInt()
        if (acceptability + toneBonusGold >= 0) {
            return ClassicTradeAssessment.Accepted(draft) to humanSideTrade
        }
        val counter = TradeAutomation.getCounteroffer(ai, TradeRequest(human.civID, aiSideTrade))
        if (counter != null) {
            val counterDesc = describeHumanSideTrade(counter.trade, human.civName, ai.civName)
            return ClassicTradeAssessment.Counteroffered(draft, counterDesc) to counter.trade
        }
        // Fallback to MultiAssetBarter if TradeAutomation.getCounteroffer returned null
        val shortfall = -(acceptability + toneBonusGold)
        val aiItemsAsked = draft.items.filter { !it.fromPlayer }
        if (aiItemsAsked.isNotEmpty()) {
            val extraDemand = MultiAssetBarter.buildAiDemandFromPlayer(
                requiredValue = shortfall,
                playerGold = human.gold,
                playerGoldPerTurn = human.stats.statsForNextTurn.gold.toInt(),
                playerLuxuriesWantedByAi = playerLuxWantedByAi.filter { lux -> draft.items.none { it.fromPlayer && it.name == lux } },
                playerStrategicsWantedByAi = playerStratWantedMap.filterKeys { s -> draft.items.none { it.fromPlayer && it.name == s } }
            )
            if (extraDemand != null) {
                val mergedItems = draft.items.filterNot { it.fromPlayer && (it.kind == ClassicItemKind.Gold || it.kind == ClassicItemKind.GoldPerTurn) } + extraDemand
                val counterDraft = draft.copy(items = mergedItems)
                val execTrade = toUncivTrade(counterDraft, human)
                if (evaluation.isTradeValid(execTrade.reverse(), ai, human)) {
                    ChatStore.setLastTradeDraft(pairKey, counterDraft)
                    val desc = describeHumanSideTrade(execTrade, human.civName, ai.civName)
                    return ClassicTradeAssessment.Counteroffered(counterDraft, desc) to execTrade
                }
            }
        }
        return ClassicTradeAssessment.Declined(
            draft, "the offer falls short by roughly $shortfall gold-equivalent in resources or gold compared to what you ask"
        ) to null
    }

    private fun evaluateOpenInquiry(
        ai: Civilization,
        human: Civilization,
        pairKey: String,
        draft: ClassicTradeDraft,
        aiSpareLuxForPlayer: List<String>,
        aiStratMap: Map<String, Int>
    ): Pair<ClassicTradeAssessment, Trade?> {
        val baseHumanTrade = toUncivTrade(draft, human)
        val evaluation = TradeEvaluation()
        val maxValToAi = evaluation.getTradeAcceptability(baseHumanTrade.reverse(), ai, human, includeDiplomaticGifts = false)
        val openingTarget = (maxValToAi * 0.68f).toInt()
        val aiOfferItems = MultiAssetBarter.buildAiOfferForPlayerItems(
            targetValue = openingTarget,
            aiGold = ai.gold,
            aiGoldPerTurn = ai.stats.statsForNextTurn.gold.toInt(),
            aiLuxuriesForPlayer = aiSpareLuxForPlayer,
            aiStrategicsForPlayer = aiStratMap,
            preferResourcesFirst = false
        )
        if (aiOfferItems.isEmpty()) {
            return ClassicTradeAssessment.Declined(draft, "we do not have spare gold or resources to purchase that right now") to null
        }
        val withOffer = draft.copy(
            items = draft.items + aiOfferItems,
            isOpenInquiry = true
        )
        ChatStore.setLastTradeDraft(pairKey, withOffer.copy(isOpenInquiry = false))
        val execTrade = toUncivTrade(withOffer, human)
        val desc = describeHumanSideTrade(execTrade, human.civName, ai.civName)
        return ClassicTradeAssessment.Counteroffered(withOffer, desc) to execTrade
    }

    private fun evaluateRelativeHaggle(
        ai: Civilization,
        human: Civilization,
        pairKey: String,
        playerText: String,
        draft: ClassicTradeDraft,
        aiSpareLuxForPlayer: List<String>,
        aiStratMap: Map<String, Int>
    ): Pair<ClassicTradeAssessment, Trade?> {
        val playerSideItems = draft.items.filter { it.fromPlayer }
        val nonGoldDraft = draft.copy(items = playerSideItems, haggleDirection = null)
        val baseTrade = toUncivTrade(nonGoldDraft, human)
        val evaluation = TradeEvaluation()
        val maxValToAi = evaluation.getTradeAcceptability(baseTrade.reverse(), ai, human, includeDiplomaticGifts = false)
        val maxSpendableGold = (ai.gold * 0.90f).toInt().coerceAtMost(max(0, ai.gold - 1))
        val maxGoldAiWillPay = min(maxValToAi, maxSpendableGold)
        val currentAiGold = draft.items.firstOrNull { !it.fromPlayer && it.kind == ClassicItemKind.Gold }?.amount ?: 0
        val currentAiNonGold = draft.items.filter { !it.fromPlayer && it.kind != ClassicItemKind.Gold && it.kind != ClassicItemKind.GoldPerTurn }
        val currentAiGpt = draft.items.firstOrNull { !it.fromPlayer && it.kind == ClassicItemKind.GoldPerTurn }?.amount ?: 0
        val asksSpecificallyForResources = Regex("""\b(?:luxury|luxuries|resources?|goods|items?)\b""", RegexOption.IGNORE_CASE).containsMatchIn(playerText)

        if (draft.haggleDirection == HaggleDirection.MoreFromAi) {
            if (!asksSpecificallyForResources && currentAiGold < maxGoldAiWillPay) {
                val step = max(25, (maxGoldAiWillPay - currentAiGold + 1) / 2)
                val newGold = min(maxGoldAiWillPay, currentAiGold + step)
                val updatedItems = ArrayList<ClassicTradeItem>()
                updatedItems.addAll(playerSideItems)
                updatedItems.addAll(currentAiNonGold)
                updatedItems += ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.Gold, name = "Gold", amount = newGold)
                if (currentAiGpt > 0) updatedItems += ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.GoldPerTurn, name = "Gold per turn", amount = currentAiGpt)
                val updatedDraft = nonGoldDraft.copy(items = updatedItems)
                ChatStore.setLastTradeDraft(pairKey, updatedDraft)
                val execTrade = toUncivTrade(updatedDraft, human)
                val directive = "ENGINE HAGGLE VERDICT: The player asked for more than $currentAiGold gold for ${nonGoldDraft.describe()}. " +
                    "You agree to raise your offer to ${describeHumanSideTrade(execTrade, human.civName, ai.civName)}. " +
                    "State your raised offer (using digits for numbers) in character and invite them to confirm the updated trade card."
                return ClassicTradeAssessment.Accepted(updatedDraft, directive) to execTrade
            }

            // Either the AI is at its lump-sum gold limit OR the player asked to throw in resources: sweeten the deal with a Luxury, Strategic, or GPT!
            val usedAiItemNames = currentAiNonGold.map { it.name }.toSet()
            val unusedLux = aiSpareLuxForPlayer.firstOrNull { it !in usedAiItemNames }
            val unusedStrat = aiStratMap.entries.firstOrNull { it.key !in usedAiItemNames && it.value >= 2 }
            val aiGpt = ai.stats.statsForNextTurn.gold.toInt()
            val maxGpt = (aiGpt * 2 / 3).coerceAtLeast(0)
            val currentEstimatedAiOfferValue = currentAiGold + currentAiNonGold.size * MultiAssetBarter.LUXURY_VALUATION + currentAiGpt * MultiAssetBarter.GPT_TURN_FACTOR

            if (currentEstimatedAiOfferValue + 80 <= maxValToAi) {
                val addedItem: ClassicTradeItem? = when {
                    unusedLux != null -> ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.Luxury, name = unusedLux, amount = 1)
                    unusedStrat != null -> ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.Strategic, name = unusedStrat.key, amount = min(3, unusedStrat.value))
                    currentAiGpt < maxGpt -> ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.GoldPerTurn, name = "Gold per turn", amount = min(maxGpt, currentAiGpt + max(3, maxGpt / 3)))
                    else -> null
                }
                if (addedItem != null) {
                    val baseAiItems = draft.items.filter { !it.fromPlayer && !(addedItem.kind == ClassicItemKind.GoldPerTurn && it.kind == ClassicItemKind.GoldPerTurn) }
                    val updatedDraft = nonGoldDraft.copy(items = playerSideItems + baseAiItems + addedItem)
                    val execTrade = toUncivTrade(updatedDraft, human)
                    if (evaluation.isTradeValid(execTrade.reverse(), ai, human)) {
                        ChatStore.setLastTradeDraft(pairKey, updatedDraft)
                        val desc = describeHumanSideTrade(execTrade, human.civName, ai.civName)
                        val directive = "ENGINE HAGGLE VERDICT: The player asked for more for ${nonGoldDraft.describe()}. " +
                            "To sweeten the deal beyond flat gold, you add ${addedItem.name} to your offer ($desc). " +
                            "State your upgraded multi-asset offer in character and invite them to confirm the updated trade card."
                        return ClassicTradeAssessment.Accepted(updatedDraft, directive) to execTrade
                    }
                }
            }

            val cappedGold = max(currentAiGold, maxGoldAiWillPay)
            val keptItems = playerSideItems + currentAiNonGold +
                (if (cappedGold > 0) listOf(ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.Gold, name = "Gold", amount = cappedGold)) else emptyList()) +
                (if (currentAiGpt > 0) listOf(ClassicTradeItem(fromPlayer = false, kind = ClassicItemKind.GoldPerTurn, name = "Gold per turn", amount = currentAiGpt)) else emptyList())
            val keptDraft = nonGoldDraft.copy(items = keptItems)
            ChatStore.setLastTradeDraft(pairKey, keptDraft)
            val execTrade = toUncivTrade(keptDraft, human)
            val directive = "ENGINE HAGGLE VERDICT: The player asked for more for ${nonGoldDraft.describe()}, " +
                "but ${describeHumanSideTrade(execTrade, human.civName, ai.civName)} is the maximum you can offer given your treasury (${ai.gold} gold) and resources. " +
                "State firmly in character that this is your final offer, and they may confirm the trade card or leave it."
            return ClassicTradeAssessment.Accepted(keptDraft, directive) to execTrade
        }
        val execTrade = toUncivTrade(draft.copy(haggleDirection = null), human)
        return ClassicTradeAssessment.Accepted(draft.copy(haggleDirection = null)) to execTrade
    }

    private fun toUncivTrade(draft: ClassicTradeDraft, human: Civilization): Trade {
        val speed = human.gameInfo.speed
        val trade = Trade()
        for (item in draft.items) {
            val offer = when (item.kind) {
                ClassicItemKind.Gold -> TradeOffer(Constants.flatGold, TradeOfferType.Gold, item.amount, speed)
                ClassicItemKind.GoldPerTurn -> TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, item.amount, speed)
                ClassicItemKind.Luxury -> TradeOffer(item.name, TradeOfferType.Luxury_Resource, item.amount, speed)
                ClassicItemKind.Strategic -> TradeOffer(item.name, TradeOfferType.Strategic_Resource, item.amount, speed)
                ClassicItemKind.City -> TradeOffer(item.cityId, TradeOfferType.City, 1, speed)
                ClassicItemKind.OpenBorders -> TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, speed)
                ClassicItemKind.Embassy -> TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, 1, speed)
                ClassicItemKind.PeaceTreaty -> TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, speed)
                ClassicItemKind.ResearchAgreement -> TradeOffer(
                    Constants.researchAgreement, TradeOfferType.Treaty,
                    human.diplomacyFunctions.getResearchAgreementCost(human.gameInfo.getCivilization(draft.aiCiv)), speed
                )
                ClassicItemKind.DefensivePact -> TradeOffer(Constants.defensivePact, TradeOfferType.Treaty, 1, speed)
            }
            if (offer.type == TradeOfferType.Treaty) {
                trade.ourOffers.add(offer.copy())
                trade.theirOffers.add(offer.copy())
            } else if (item.fromPlayer) {
                trade.ourOffers.add(offer)
            } else {
                trade.theirOffers.add(offer)
            }
        }
        return trade
    }

    internal fun describeHumanSideTrade(humanSideTrade: Trade, humanName: String, aiName: String): String {
        val weGive = humanSideTrade.ourOffers.joinToString(", ") { it.getOfferText().replace("\n", " ") }.ifBlank { "nothing" }
        val theyGive = humanSideTrade.theirOffers.joinToString(", ") { it.getOfferText().replace("\n", " ") }.ifBlank { "nothing" }
        return "$humanName gives [$weGive]; $aiName gives [$theyGive]"
    }

    fun executeAcceptedTrade(ai: Civilization, human: Civilization, humanSideTrade: Trade): Boolean {
        val aiSide = humanSideTrade.reverse()
        if (!TradeEvaluation().isTradeValid(aiSide, ai, human)) return false
        val tradeLogic = TradeLogic(ai, human)
        tradeLogic.currentTrade.set(aiSide)
        tradeLogic.acceptTrade()
        ChatStore.setLastTradeDraft("${ai.civID}>${human.civID}", null)
        onTradeCompleted(ai, human)
        return true
    }

    private fun applyToneOpinionAndConsequences(
        ai: Civilization,
        human: Civilization,
        pairKey: String,
        tone: MessageToneAnalysis
    ): Boolean {
        val diplo = ai.getDiplomacyManager(human) ?: return false
        if (tone.opinionDelta != 0f) {
            when {
                tone.manipulation == ManipulationSignal.FalseOffer ->
                    diplo.addDiminishingModifier(DiplomaticModifiers.DishonestOffer, tone.opinionDelta)
                tone.manipulation == ManipulationSignal.BrownNosing ||
                    tone.manipulation == ManipulationSignal.Hypocrisy ||
                    tone.manipulation == ManipulationSignal.FabricatedClaim ->
                    diplo.addDiminishingModifier(DiplomaticModifiers.ManipulativeFlattery, tone.opinionDelta)
                tone.opinionDelta < 0f ->
                    // Hostile insults and threats do NOT diminish: every insult inflicts the full opinion hit
                    diplo.addModifier(DiplomaticModifiers.DisrespectfulTone, tone.opinionDelta, limit = -100f)
                else ->
                    diplo.addDiminishingModifier(DiplomaticModifiers.RespectfulTone, tone.opinionDelta)
            }
        }
        if (tone.walkedOut) {
            walkOutTurns[pairKey] = gameInfo.turns
            ChatStore.setLastTradeDraft(pairKey, null)
        }
        var denounced = false
        if (tone.shouldDenounce && !ai.isAtWarWith(human) && !diplo.hasFlag(DiplomacyFlags.Denunciation) && !diplo.hasFlag(DiplomacyFlags.DeclarationOfFriendship)) {
            diplo.denounce()
            denounced = true
        }
        return denounced
    }

    fun confirmChatDeal(draft: DealDraft): Boolean {
        val proposed = manager.propose(draft) as? ProposalResult.Proposed ?: return false
        return manager.accept(proposed.dealId, draft.counterparty) == AcceptResult.Activated
    }

    /** Records an explicit refusal of an AI's deal proposal so it does not spam the player again soon. */
    fun refuseChatDeal(ai: Civilization, human: Civilization) {
        val pairKey = "${ai.civID}>${human.civID}"
        ChatStore.setPendingProactiveDeal(pairKey, null)
        // Push cooldown well past MIN_TURNS_BETWEEN_PROPOSALS so refused leaders wait ~35 turns
        val extraDelay = ProactiveDiplomacy.MIN_TURNS_AFTER_REFUSAL - ProactiveDiplomacy.MIN_TURNS_BETWEEN_PROPOSALS
        ChatStore.setLastProactiveTurn(pairKey, gameInfo.turns + extraDelay.coerceAtLeast(0))
        val session = chatWith(ai, human)
        session.say("I decline that proposal.")
        saveChatData()
    }

    fun drainOutboxLinesFor(ai: Civilization, human: Civilization): List<String> {
        return manager.drainOutboxFor(ai.civID, human.civID).map { msg -> formatOutboxMessage(msg) }
    }

    private fun formatOutboxMessage(msg: OutboxMessage): String {
        val desc = manager.commitment(msg.commitmentId)?.describe() ?: "commitment #${msg.commitmentId}"
        return when (msg.kind) {
            OutboxKind.ReminderToPlayer -> "Remember your obligation ($desc) — the deadline is nearly upon us."
            OutboxKind.PlayerBrokeCommitment -> "You broke your word ($desc)! Do not expect us to forget this betrayal."
            OutboxKind.AiBetrayal -> "Circumstances have changed. I am no longer bound by my earlier promise ($desc)."
            OutboxKind.AiCouldNotKeepCommitment -> "Through no choice of my own, I could not fulfil our arrangement ($desc)."
        }
    }

    companion object {
        const val SETTLE_PROMISE_DISTANCE = 6
        const val MAX_REFUSALS_PER_TURN = 3
        const val DMZ_VIOLATION_MIN_UNITS = 2
    }
}
