package com.unciv.logic.commitments.llm

import com.unciv.logic.commitments.CivName

enum class IntelKind {
    /** Warn that [target] is massing troops, plotting war, or has a dangerous military. */
    MilitaryThreat,
    /** Warn that [target] is pulling ahead in score, science, culture, or close to winning. */
    VictoryLead
}

enum class IntelOutcome {
    /** The claim matches reality; the AI appreciates the warning and becomes wary of [target]. */
    VerifiedTrue,
    /** The claim is false, but fog of war hides the truth and the AI trusts the player enough to believe the bluff. */
    BluffBelieved,
    /** The claim is false and the AI either sees the truth directly or does not trust the player; penalty applied. */
    BluffCalledOut
}

data class IntelCheck(
    val isActuallyTrue: Boolean,
    val aiCanVerifyDirectly: Boolean,
    val aiTrustsPlayer: Boolean,
    val truthSummary: String
)

fun interface IntelligenceInspector {
    fun inspect(ai: CivName, target: CivName, kind: IntelKind): IntelCheck
}

data class IntelReport(
    val target: CivName,
    val kind: IntelKind,
    val outcome: IntelOutcome,
    val opinionDelta: Float,
    val promptDirective: String,
    val uiNotice: String
)

/**
 * Evaluates strategic intelligence and deception claims shared by the player about a third civilization.
 */
object IntelligenceEngine {
    private val ignoreCase = RegexOption.IGNORE_CASE

    private val militaryWarningRegex = Regex(
        """\b(?:massing\s+troops|plotting\s+(?:war|an\s+attack|to\s+attack)|planning\s+to\s+(?:attack|invade)|preparing\s+(?:for\s+war|to\s+attack|an\s+invasion)|building\s+a\s+(?:huge|massive)\s+army|marching\s+(?:on|toward|towards)\s+your)\b""",
        ignoreCase
    )
    private val victoryWarningRegex = Regex(
        """\b(?:close\s+to\s+(?:a\s+)?(?:science|cultural|domination|diplomatic\s+)?victory|about\s+to\s+win|pulling\s+ahead\s+in\s+(?:science|technology|culture|score)|winning\s+the\s+game|leading\s+in\s+(?:science|culture|score))\b""",
        ignoreCase
    )

    fun evaluate(
        playerText: String,
        ai: CivName,
        knownThirdCivs: List<CivName>,
        inspector: IntelligenceInspector
    ): IntelReport? {
        val target = knownThirdCivs.firstOrNull { civ ->
            Regex("""\b${Regex.escape(civ)}\b""", ignoreCase).containsMatchIn(playerText)
        } ?: return null

        val kind = when {
            militaryWarningRegex.containsMatchIn(playerText) -> IntelKind.MilitaryThreat
            victoryWarningRegex.containsMatchIn(playerText) -> IntelKind.VictoryLead
            else -> return null
        }

        val check = inspector.inspect(ai, target, kind)
        return when {
            check.isActuallyTrue -> IntelReport(
                target = target,
                kind = kind,
                outcome = IntelOutcome.VerifiedTrue,
                opinionDelta = 3f,
                promptDirective = "STRATEGIC INTELLIGENCE VERIFIED: The player warned you about $target (${kind.name}), and your own intelligence confirms it (${check.truthSummary}). " +
                    "Thank them in character for the valuable warning and note that you will watch $target closely.",
                uiNotice = "[Diplomatic bonus (+3 opinion): Shared true strategic intelligence about $target]"
            )
            !check.aiCanVerifyDirectly && check.aiTrustsPlayer -> IntelReport(
                target = target,
                kind = kind,
                outcome = IntelOutcome.BluffBelieved,
                opinionDelta = 2f,
                promptDirective = "STRATEGIC INTELLIGENCE BELIEVED: The player warned you about $target (${kind.name}). Because you trust them and cannot see all of $target's territory, you believe their warning. " +
                    "Thank them in character and state that you will grow wary of $target.",
                uiNotice = "[Deception succeeded (+2 opinion): Leader believed your intelligence bluff about $target]"
            )
            else -> IntelReport(
                target = target,
                kind = kind,
                outcome = IntelOutcome.BluffCalledOut,
                opinionDelta = -6f,
                promptDirective = "CAUGHT LYING ABOUT $target: The player claimed $target is a ${kind.name} threat, which contradicts what you know (${check.truthSummary}). " +
                    "Call out their false intelligence in character and warn them not to manipulate you against $target!",
                uiNotice = "[Diplomatic penalty (-6 opinion): Caught spreading false intelligence about $target (${check.truthSummary})]"
            )
        }
    }
}
