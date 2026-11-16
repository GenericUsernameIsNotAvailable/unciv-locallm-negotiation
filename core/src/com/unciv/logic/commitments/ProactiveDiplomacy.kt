package com.unciv.logic.commitments

/**
 * Proactive AI-initiated diplomatic proposals.
 * Generates structured [DealDraft] proposals and in-character opening messages when an AI leader
 * has a strategic reason to contact the human player on its own turn.
 */
data class ProactiveProposal(
    val aiCiv: CivName,
    val humanCiv: CivName,
    val message: String,
    val draft: DealDraft
)

object ProactiveDiplomacy {
    const val MIN_TURNS_BETWEEN_PROPOSALS = 12

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
        existingOpenCommitments: List<Commitment>
    ): ProactiveProposal? {
        if (atWarWithHuman) return null
        if (lastProposalTurn != null && currentTurn - lastProposalTurn < MIN_TURNS_BETWEEN_PROPOSALS) return null
        if (relationship <= Relationship.Unforgivable) return null

        val betweenPair = existingOpenCommitments.filter {
            (it.committer == aiCiv && it.beneficiary == humanCiv) ||
                (it.committer == humanCiv && it.beneficiary == aiCiv)
        }
        if (betweenPair.size >= 2) return null

        // 1. If the AI is fighting a third major civ and has no war pact with the player, ask the player to join the war (paying gold if possible) or ask for a war loan
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
                    message = "We are at war with $enemy. Declare war on $enemy within 10 turns and $aiCiv will pay you $bounty gold immediately!",
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
                    message = "Our war against $enemy strains our treasury. Lend us $principal gold now and we will repay you $repayGpt gold per turn for 20 turns.",
                    draft = draft
                )
            }
        }

        // 2. If the AI is friendly/favorable or afraid and has no Non-Aggression Pact with the player, propose a 20-turn Non-Aggression Pact
        val hasNoAttack = betweenPair.any { it.term == Term.NoAttack || it.term == Term.DemilitarizeBorder }
        if (!hasNoAttack && (relationship >= Relationship.Favorable || relationship == Relationship.Afraid)) {
            val draft = DealDraft(
                proposer = aiCiv,
                counterparty = humanCiv,
                items = listOf(
                    CommitmentDraft(aiCiv, humanCiv, Term.NoAttack, activeFrom = currentTurn, endTurn = currentTurn + 20),
                    CommitmentDraft(humanCiv, aiCiv, Term.NoAttack, activeFrom = currentTurn, endTurn = currentTurn + 20)
                )
            )
            return ProactiveProposal(
                aiCiv = aiCiv,
                humanCiv = humanCiv,
                message = "Peace between $aiCiv and $humanCiv benefits us both. Let us sign a mutual Non-Aggression Pact for 20 turns.",
                draft = draft
            )
        }

        return null
    }
}
