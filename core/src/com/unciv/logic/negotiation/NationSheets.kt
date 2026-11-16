package com.unciv.logic.negotiation

import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.commitments.llm.Fact
import com.unciv.logic.commitments.llm.NationSheet
import com.unciv.logic.commitments.llm.PlayerOfferSheet
import com.unciv.logic.commitments.llm.Topic
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.translations.tr
import kotlin.math.roundToInt

/**
 * Reads a leader's real, current state from the game into a [NationSheet], every time the player speaks, so that
 * whatever the leader says about its gold, wars, allies, cities, faith, culture, science, resources or goals is
 * true right now. To make the leader aware of one more thing, add one `add(...)` line here; the rest (choosing
 * which facts matter to the conversation, and checking replies against them) is generic.
 *
 * Everything here is information the player can also see in the game (the trade screen shows gold, the overview
 * screens show cities and wonders...), except unit counts and goals, which are the leader's own to speak of.
 * Nations the player has not met are never named.
 */
internal object NationSheets {
    private const val MAX_NAMES = 6

    fun of(ai: Civilization, human: Civilization): NationSheet {
        val facts = ArrayList<Fact>()
        fun add(topic: Topic, text: String, core: Boolean = false) { facts += Fact(topic, text, core) }
        fun names(list: List<String>) = list.take(MAX_NAMES).joinToString(", ")
        fun signed(value: Float) = (if (value >= 0f) "+" else "") + value.roundToInt()

        val stats = ai.stats.statsForNextTurn
        val cities = ai.cities
        val capital = ai.getCapital()

        // ---- Economy
        add(Topic.Economy, "You have ${ai.gold} gold, and your income is ${signed(stats.gold)} gold per turn.", core = true)

        // ---- Cities
        add(
            Topic.Cities,
            "You rule ${cities.size} ${if (cities.size == 1) "city" else "cities"}" +
                (capital?.let { ", and your capital is ${it.name.tr(hideIcons = true)}" } ?: "") + ".",
            core = true
        )
        val wonders = cities.flatMap { city -> city.cityConstructions.getBuiltBuildings().filter { it.isAnyWonder() }.map { it.name.tr(hideIcons = true) }.toList() }
        add(Topic.Cities, if (wonders.isEmpty()) "You have built no wonders." else "You have built these wonders: ${names(wonders)}.")
        val building: String? = capital?.cityConstructions?.currentConstructionName()
        if (!building.isNullOrBlank()) add(Topic.Cities, "Your capital is currently building ${building.tr(hideIcons = true)}.")

        // ---- Military
        val military = ai.units.getCivUnits().filter { it.baseUnit.isMilitary }.toList()
        add(Topic.Military, "You have ${military.size} military units.", core = true)
        add(Topic.Military, "Of them, ${military.count { it.baseUnit.isRanged() }} are ranged and ${military.count { it.baseUnit.isMelee() }} are melee.")

        // ---- Faith
        add(Topic.Faith, "You have ${ai.religionManager.storedFaith} faith, gaining ${signed(stats.faith)} per turn.")
        val religion: String? = ai.religionManager.religion?.getReligionDisplayName()
        add(Topic.Faith, if (religion != null) "Your religion is $religion." else "You have not founded a religion.")

        // ---- Culture
        add(
            Topic.Culture,
            "You have ${ai.policies.storedCulture} culture, gaining ${signed(stats.culture)} per turn, " +
                "and you have adopted ${ai.policies.adoptedPolicies.size} policies."
        )

        // ---- Science
        add(
            Topic.Science,
            "You know ${ai.tech.techsResearched.size} technologies and you are in the ${ai.tech.era.name.ifBlank { ai.gameInfo.gameParameters.startingEra }.tr(hideIcons = true)}, " +
                "gaining ${signed(stats.science)} science per turn."
        )

        // ---- Resources and happiness
        val supply = ai.getCivResourceSupply().groupBy { it.resource }.mapValues { (_, list) -> list.sumOf { it.amount } }.filterValues { it > 0 }
        val luxuries = supply.keys.filter { it.resourceType == ResourceType.Luxury }
        val strategics = supply.filterKeys { it.resourceType == ResourceType.Strategic }
        add(Topic.Resources, if (luxuries.isEmpty()) "You have no luxury resources." else "You have these luxury resources: ${names(luxuries.map { it.name.tr(hideIcons = true) })}.")
        if (strategics.isNotEmpty()) add(Topic.Resources, "Your strategic resources: ${names(strategics.map { "${it.key.name.tr(hideIcons = true)} (${it.value})" })}.")
        val lacking = ai.gameInfo.ruleset.tileResources.values.filter { it.resourceType == ResourceType.Luxury && it !in luxuries }.map { it.name.tr(hideIcons = true) }
        if (lacking.isNotEmpty()) add(Topic.Resources, "You do not have these luxury resources: ${names(lacking)}.")
        val happiness = ai.getHappiness()
        add(Topic.Resources, "Your people's happiness is ${if (happiness >= 0) "+" else ""}$happiness${if (happiness < 0) " (they are unhappy)" else ""}.")

        // ---- Diplomacy (only nations the player knows are named)
        val others = ai.diplomacy.values.filter { it.otherCiv != human && it.otherCiv.isMajorCiv && human.knows(it.otherCiv) }
        val friends = others.filter { it.hasFlag(DiplomacyFlags.DeclarationOfFriendship) }.map { it.otherCiv.civName }
        val pacts = others.filter { it.diplomaticStatus == DiplomaticStatus.DefensivePact }.map { it.otherCiv.civName }
        val withPlayer = ai.getDiplomacyManager(human)
        val friendOfPlayer = withPlayer?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true
        val pactWithPlayer = withPlayer?.diplomaticStatus == DiplomaticStatus.DefensivePact
        if (friends.isNotEmpty()) add(Topic.Diplomacy, "You have declared friendship with ${names(friends)}.")
        if (pacts.isNotEmpty()) add(Topic.Diplomacy, "You have a defensive pact with ${names(pacts)}.")
        if (friendOfPlayer) add(Topic.Diplomacy, "You have declared friendship with the player.")
        if (pactWithPlayer) add(Topic.Diplomacy, "You have a defensive pact with the player.")
        if (friends.isEmpty() && pacts.isEmpty() && !friendOfPlayer && !pactWithPlayer)
            add(Topic.Diplomacy, "You have no declared friendships or defensive pacts with nations the player knows of.")
        val cityStates = human.getKnownCivs().filter { it.isCityState }.toList()
        val alliedCityStates = cityStates.filter { it.allyCiv == ai }.map { it.civName }
        val protectedCityStates = cityStates.filter { it.getDiplomacyManager(ai)?.diplomaticStatus == DiplomaticStatus.Protector }.map { it.civName }
        if (alliedCityStates.isNotEmpty()) add(Topic.Diplomacy, "You are the ally of the city-states ${names(alliedCityStates)}.")
        if (protectedCityStates.isNotEmpty()) add(Topic.Diplomacy, "You protect the city-states ${names(protectedCityStates)}.")

        // ---- Goals: what the AI itself is working towards
        ai.getPreferredVictoryTypes().filter { it != Constants.neutralVictoryType }
            .forEach { add(Topic.Goals, "You are working towards a $it victory.", core = true) }
        val research: String? = ai.tech.currentTechnologyName()
        if (!research.isNullOrBlank()) add(Topic.Goals, "You are currently researching ${research.tr(hideIcons = true)}.", core = true)

        val allies = (friends + pacts).distinct()
        return NationSheet(
            facts = facts,
            gold = ai.gold,
            cityCount = cities.size,
            atWarWithPlayer = ai.isAtWarWith(human),
            enemies = LeaderProfiles.enemiesKnownTo(ai, human),
            allies = allies,
            player = buildPlayerSheet(human)
        )
    }

    private fun buildPlayerSheet(human: Civilization): PlayerOfferSheet {
        val supply = human.getCivResourceSupply()
            .groupBy { it.resource }
            .mapValues { (_, list) -> list.sumOf { it.amount } }
            .filterValues { it > 0 }
        val luxuries = supply.keys
            .filter { it.resourceType == ResourceType.Luxury }
            .map { it.name.tr(hideIcons = true) }
        val strategics = supply
            .filterKeys { it.resourceType == ResourceType.Strategic }
            .mapKeys { (res, _) -> res.name.tr(hideIcons = true) }
        val allResources = human.gameInfo.ruleset.tileResources.values
            .filter { it.resourceType == ResourceType.Luxury || it.resourceType == ResourceType.Strategic }
            .map { it.name.tr(hideIcons = true) }
        return PlayerOfferSheet(
            leaderName = human.nation.leaderName.ifBlank { human.civName }.tr(hideIcons = true),
            nationName = human.civName.tr(hideIcons = true),
            gold = human.gold,
            goldPerTurn = human.stats.statsForNextTurn.gold.roundToInt(),
            cityCount = human.cities.size,
            capitalName = human.getCapital()?.name?.tr(hideIcons = true),
            cityNames = human.cities.map { it.name.tr(hideIcons = true) },
            luxuries = luxuries,
            strategics = strategics,
            allKnownResources = allResources
        )
    }
}
