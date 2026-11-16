package com.unciv.logic.commitments

/**
 * High-level strategic postures for the Hybrid Strategic AI layer.
 * Replaces blind random tech picks and flat building weights with purposeful empire-wide coordination
 * so AI civilisations play competently without relying on unfair starting handicaps.
 */
enum class StrategicPosture(val description: String) {
    WarDefense("Emergency War Defence — prioritising survival, walls, and defensive units"),
    WarOffense("Offensive War Campaign — prioritising siege and frontline military power"),
    HappinessRecovery("Internal Stability — prioritising happiness structures to end unrest"),
    EconomyRecovery("Treasury Recovery — prioritising markets, trade, and positive gold income"),
    EarlyExpansion("Frontier Expansion — prioritising settlers, workers, and core infrastructure"),
    SciencePush("Scientific Victory Push — prioritising academies, research labs, and spaceship parts"),
    CulturePush("Cultural Victory Push — prioritising monuments, temples, and cultural wonders"),
    DominationPush("Military Buildup — building a decisive force for conquest")
}

/** Plain-data snapshot of a civilization's strategic situation, testable without libGDX or Unciv classes. */
data class CivStrategicState(
    val civName: CivName,
    val atWar: Boolean,
    val ownForce: Float,
    val enemyForce: Float,
    val happiness: Int,
    val gold: Int,
    val goldPerTurn: Int,
    val cityCount: Int,
    val militaryUnitCount: Int,
    val preferredVictory: String
)

enum class ConstructionCategory {
    MilitaryUnit, DefenseBuilding, HappinessBuilding, GoldBuilding, ScienceBuilding, CultureBuilding, Settler, Worker, SpaceshipPart, Other
}

/**
 * Deterministic Hybrid Strategic AI Director.
 * Evaluates a civilization's [StrategicPosture] each turn and computes multipliers for city production
 * and technology selection.
 */
object StrategicAiDirector {

    fun evaluatePosture(state: CivStrategicState): StrategicPosture = when {
        state.atWar && state.enemyForce > state.ownForce * 1.15f -> StrategicPosture.WarDefense
        state.atWar -> StrategicPosture.WarOffense
        state.happiness < 0 -> StrategicPosture.HappinessRecovery
        state.gold < 25 && state.goldPerTurn < 0 -> StrategicPosture.EconomyRecovery
        state.cityCount < 3 && state.happiness >= 4 -> StrategicPosture.EarlyExpansion
        state.preferredVictory.contains("Cultural", ignoreCase = true) -> StrategicPosture.CulturePush
        state.preferredVictory.contains("Domination", ignoreCase = true) -> StrategicPosture.DominationPush
        else -> StrategicPosture.SciencePush
    }

    /** Multiplier (0.6..2.2) applied to a construction choice in ConstructionAutomation based on the AI's posture. */
    fun constructionMultiplier(posture: StrategicPosture, category: ConstructionCategory): Float = when (posture) {
        StrategicPosture.WarDefense -> when (category) {
            ConstructionCategory.MilitaryUnit -> 2.1f
            ConstructionCategory.DefenseBuilding -> 1.9f
            ConstructionCategory.Settler -> 0.5f
            else -> 0.85f
        }
        StrategicPosture.WarOffense -> when (category) {
            ConstructionCategory.MilitaryUnit -> 1.75f
            ConstructionCategory.DefenseBuilding -> 1.2f
            else -> 0.95f
        }
        StrategicPosture.HappinessRecovery -> when (category) {
            ConstructionCategory.HappinessBuilding -> 2.0f
            ConstructionCategory.Settler -> 0.4f
            else -> 1.0f
        }
        StrategicPosture.EconomyRecovery -> when (category) {
            ConstructionCategory.GoldBuilding -> 2.0f
            ConstructionCategory.MilitaryUnit -> 0.75f
            else -> 1.0f
        }
        StrategicPosture.EarlyExpansion -> when (category) {
            ConstructionCategory.Settler, ConstructionCategory.Worker -> 1.55f
            else -> 1.0f
        }
        StrategicPosture.SciencePush -> when (category) {
            ConstructionCategory.SpaceshipPart -> 2.2f
            ConstructionCategory.ScienceBuilding -> 1.65f
            else -> 1.0f
        }
        StrategicPosture.CulturePush -> when (category) {
            ConstructionCategory.CultureBuilding -> 1.7f
            else -> 1.0f
        }
        StrategicPosture.DominationPush -> when (category) {
            ConstructionCategory.MilitaryUnit -> 1.55f
            else -> 1.0f
        }
    }

    /** Bonus weight multiplier (1.0..2.5) applied when choosing the next technology to research. */
    fun techPriorityMultiplier(
        posture: StrategicPosture,
        unlocksMilitary: Boolean,
        unlocksScience: Boolean,
        unlocksGold: Boolean,
        unlocksHappiness: Boolean,
        unlocksCulture: Boolean
    ): Float {
        var mult = 1.0f
        when (posture) {
            StrategicPosture.WarDefense, StrategicPosture.WarOffense, StrategicPosture.DominationPush ->
                if (unlocksMilitary) mult *= 2.2f
            StrategicPosture.HappinessRecovery ->
                if (unlocksHappiness) mult *= 2.2f
            StrategicPosture.EconomyRecovery ->
                if (unlocksGold) mult *= 2.2f
            StrategicPosture.SciencePush ->
                if (unlocksScience) mult *= 2.0f
            StrategicPosture.CulturePush ->
                if (unlocksCulture) mult *= 2.0f
            StrategicPosture.EarlyExpansion ->
                if (unlocksHappiness || unlocksGold) mult *= 1.4f
        }
        return mult
    }
}
