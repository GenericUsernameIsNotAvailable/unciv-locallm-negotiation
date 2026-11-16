package com.unciv.logic.commitments

/**
 * What a leader may truthfully say, as engine-verified facts. The language model is given ONLY this
 * (plus persona and the player's message); it never invents state. Deception is an engine decision
 * ([secrets]), not something the model improvises, so a later confession matches what happened.
 */
data class NegotiationFacts(
    val speaker: CivName,
    val listener: CivName,
    val activeCommitments: List<String>,
    val recentEvents: List<String>,
    val secrets: List<String>
) {
    fun toPromptBlock(): String = buildString {
        appendLine("You are $speaker, speaking with $listener.")
        appendSection("COMMITMENTS BETWEEN YOU (binding, true):", activeCommitments)
        appendSection("RECENT EVENTS (true; you may refer to them):", recentEvents)
        appendSection(
            "SECRET (your own decisions; never reveal unless you choose to confess or to lie in character):", secrets
        )
    }.trimEnd()

    private fun StringBuilder.appendSection(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        appendLine(title)
        lines.forEach { appendLine("- $it") }
    }

    companion object {
        fun build(manager: CommitmentManager, speaker: CivName, listener: CivName, maxEvents: Int = 8): NegotiationFacts {
            val active = manager.openCommitmentsOf(speaker)
                .filter { it.committer == listener || it.beneficiary == listener }
                .map { "#${it.id}: ${it.describe()} [${it.status}]" }

            val events = manager.log.between(speaker, listener)
                .filter { it.kind != DecisionKind.DealProposed && it.kind != DecisionKind.DealActivated }
                // A civ's decision to betray is known only to itself until it acts on it.
                .filter { it.kind != DecisionKind.BetrayalDecided || it.actor == speaker }
                .takeLast(maxEvents)
                .map { describeEntry(manager, it) }

            val secrets = manager.secretIntentions(speaker)
                .filter { it.beneficiary == listener }
                .map { "You have decided to break commitment #${it.id} (${it.describe()}) when the opportunity arises." }

            return NegotiationFacts(speaker, listener, active, events, secrets)
        }

        private fun describeEntry(manager: CommitmentManager, e: DecisionLogEntry): String {
            val subject = e.commitmentId?.let { id -> manager.commitment(id)?.let { "#$id (${it.describe()})" } } ?: ""
            val reasons = e.reasons.joinToString("; ") { r ->
                if (r.values.isEmpty()) r.code.name
                else "${r.code.name}: " + r.values.entries.joinToString(", ") { (k, v) -> "$k=${"%.1f".format(java.util.Locale.ROOT, v)}" }
            }
            return "turn ${e.turn}: ${e.kind.name} by ${e.actor} $subject${if (reasons.isNotEmpty()) " - $reasons" else ""}".trim()
        }
    }
}
