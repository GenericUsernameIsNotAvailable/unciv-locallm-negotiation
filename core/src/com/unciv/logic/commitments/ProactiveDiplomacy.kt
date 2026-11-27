package com.unciv.logic.commitments

import kotlin.math.abs

/**
 * Proactive AI-initiated diplomatic proposals.
 * Generates structured [DealDraft] proposals and in-character opening messages when an AI leader
 * has a genuine strategic reason to contact the human player on its own turn.
 */
data class ProactiveProposal(
    val aiCiv: CivName,
    val humanCiv: CivName,
    val message: String,
    val draft: DealDraft
)

object ProactiveDiplomacy {
    const val MIN_TURNS_BETWEEN_PROPOSALS = 20
    const val MIN_TURNS_AFTER_REFUSAL = 35
    const val GLOBAL_TURNS_BETWEEN_PROPOSALS = 8

    fun considerProposal(
        aiCiv: CivName,
        aiLeaderName: String,
        humanCiv: CivName,
        currentTurn: Int,
        lastProposalTurn: Int?,
        relationship: Relationship,
        atWarWithHuman: Boolean,
        commonEnemiesOfAi: List<CivName>,
        aiGold: Int,
        aiGoldPerTurn: Int,
        existingOpenCommitments: List<Commitment>,
        willingnessToDeclareWar: Float = 5f,
        isAtWarWithAnyone: Boolean = commonEnemiesOfAi.isNotEmpty()
    ): ProactiveProposal? {
        if (atWarWithHuman) return null
        if (lastProposalTurn != null && currentTurn - lastProposalTurn < MIN_TURNS_BETWEEN_PROPOSALS) return null
        if (relationship <= Relationship.Unforgivable) return null

        val betweenPair = existingOpenCommitments.filter {
            (it.committer == aiCiv && it.beneficiary == humanCiv) ||
                (it.committer == humanCiv && it.beneficiary == aiCiv)
        }
        if (betweenPair.size >= 2) return null

        val salt = abs(aiCiv.hashCode() * 31 + currentTurn)

        // 1. If the AI is fighting a third major civ and has no war pact with the player, ask for help or a war loan
        val enemy = commonEnemiesOfAi.firstOrNull()
        if (enemy != null && relationship >= Relationship.Neutral) {
            if (aiGold >= 100) {
                val bounty = (aiGold / 3).coerceIn(50, 250)
                val draft = DealDraft(
                    proposer = aiCiv,
                    counterparty = humanCiv,
                    items = listOf(
                        CommitmentDraft(humanCiv, aiCiv, Term.DeclareWar(enemy), activeFrom = currentTurn + 1, endTurn = currentTurn + 10),
                        CommitmentDraft(aiCiv, humanCiv, Term.PayGoldLump(bounty), activeFrom = currentTurn, endTurn = currentTurn)
                    )
                )
                return ProactiveProposal(
                    aiCiv = aiCiv,
                    humanCiv = humanCiv,
                    message = "We are locked in war with $enemy. Strike at $enemy within 10 turns and $aiCiv will pay you $bounty gold upfront.",
                    draft = draft
                )
            } else if (aiGoldPerTurn >= 8) {
                val repayGpt = (aiGoldPerTurn / 2).coerceIn(5, 25)
                val principal = repayGpt * 15
                val draft = DealBuilders.loan(
                    lender = humanCiv, borrower = aiCiv, principal = principal, repayPerTurn = repayGpt, turns = 20, now = currentTurn
                )
                return ProactiveProposal(
                    aiCiv = aiCiv,
                    humanCiv = humanCiv,
                    message = "Our war against $enemy strains our coffers. Lend $aiCiv $principal gold now and we will repay you $repayGpt gold per turn for 20 turns.",
                    draft = draft
                )
            }
        }

        // 2. Non-Aggression Pact: ONLY proposed when strategically justified —
        //    either the AI is Afraid of the player, busy fighting another war and securing its flank,
        //    or has a genuinely peaceful/diplomatic temperament (DeclareWar <= 5) and Favorable+ relations.
        //    Aggressive warlords (DeclareWar > 5) do not randomly beg for non-aggression pacts in peacetime.
        val hasNoAttack = betweenPair.any { it.term == Term.NoAttack || it.term == Term.DemilitarizeBorder }
        val wantsPeacePact = !hasNoAttack && (
            relationship == Relationship.Afraid ||
                (relationship >= Relationship.Favorable && (willingnessToDeclareWar <= 5f || isAtWarWithAnyone))
            )
        if (wantsPeacePact) {
            val draft = DealDraft(
                proposer = aiCiv,
                counterparty = humanCiv,
                items = listOf(
                    CommitmentDraft(aiCiv, humanCiv, Term.NoAttack, activeFrom = currentTurn, endTurn = currentTurn + 20),
                    CommitmentDraft(humanCiv, aiCiv, Term.NoAttack, activeFrom = currentTurn, endTurn = currentTurn + 20)
                )
            )
            val lines = if (relationship == Relationship.Afraid) {
                listOf(
                    "Your armies cast a long shadow over $aiCiv. Let us pledge a 20-turn Non-Aggression Pact so our borders stay quiet.",
                    "$aiLeaderName of $aiCiv proposes a 20-turn Non-Aggression Pact — better that our swords remain sheathed."
                )
            } else if (isAtWarWithAnyone) {
                listOf(
                    "$aiCiv has enemies enough already. Sign a 20-turn Non-Aggression Pact with us so neither of us needs to watch our flank.",
                    "While $aiCiv fights elsewhere, let us seal a 20-turn Non-Aggression Pact between our realms."
                )
            } else {
                listOf(
                    "Our people value steady borders. Let $aiCiv and $humanCiv swear a mutual Non-Aggression Pact for 20 turns.",
                    "A 20-turn Non-Aggression Pact would let both $aiCiv and $humanCiv build without fear of sudden war. Do you agree?",
                    "Let us put our goodwill into writing: I propose a 20-turn mutual Non-Aggression Pact between $aiCiv and $humanCiv."
                )
            }
            return ProactiveProposal(
                aiCiv = aiCiv,
                humanCiv = humanCiv,
                message = lines[salt % lines.size],
                draft = draft
            )
        }

        return null
    }
}
