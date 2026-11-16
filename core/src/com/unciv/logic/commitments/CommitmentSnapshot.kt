package com.unciv.logic.commitments

/** Everything the engine needs to continue after a save/load or a GameInfo clone. */
data class CommitmentSnapshot(
    val nextCommitmentId: Int,
    val nextDealId: Int,
    val commitments: List<Commitment>,
    val deals: List<Deal>,
    val log: List<DecisionLogEntry>,
    val outbox: List<OutboxMessage>
) {
    companion object {
        val EMPTY = CommitmentSnapshot(1, 1, emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * Compact, versioned, line-based text form of a [CommitmentSnapshot], stored as one String in the save game.
 * A plain string keeps it clear of the game's save-format rules for nested classes.
 *
 * Layout (one record per line, fields separated by '|'):
 *   V1
 *   N|nextCommitmentId|nextDealId
 *   C|id|dealId|committer|beneficiary|term|conditions|activeFrom|endTurn|status|licensed|everHeld|failedAttempts
 *   D|id|proposer|counterparty|proposedTurn|status|confirmedBy|commitmentIds
 *   I|dealId|committer|beneficiary|term|conditions|activeFrom|endTurn          (items of a deal, in order)
 *   L|turn|kind|actor|other|commitmentId|reasons
 *   O|turn|from|to|kind|commitmentId
 * Inside a field, ',' separates arguments and ';' separates list elements; all of '|' ';' ',' '\' and line
 * breaks are escaped, so civ names may contain anything.
 */
object CommitmentSnapshotCodec {
    private const val VERSION = "V1"

    fun encode(s: CommitmentSnapshot): String = buildString {
        appendLine(VERSION)
        appendLine(row("N", s.nextCommitmentId, s.nextDealId))
        for (c in s.commitments) appendLine(
            row(
                "C", c.id, c.dealId, esc(c.committer), esc(c.beneficiary), encodeTerm(c.term), encodeCondition(c.condition),
                c.activeFrom, c.endTurn, c.status.name, c.breachLicensed, c.conditionEverHeld, c.failedAttempts
            )
        )
        for (d in s.deals) {
            appendLine(
                row(
                    "D", d.id, esc(d.proposer), esc(d.counterparty), d.proposedTurn, d.status.name,
                    d.confirmedBy.joinToString(";") { esc(it) }, d.commitmentIds.joinToString(";")
                )
            )
            for (i in d.draft.items) appendLine(
                row("I", d.id, esc(i.committer), esc(i.beneficiary), encodeTerm(i.term), encodeCondition(i.condition), i.activeFrom, i.endTurn)
            )
        }
        for (e in s.log) appendLine(
            row("L", e.turn, e.kind.name, esc(e.actor), esc(e.other), e.commitmentId ?: "", encodeReasons(e.reasons))
        )
        for (m in s.outbox) appendLine(row("O", m.turn, esc(m.from), esc(m.to), m.kind.name, m.commitmentId))
    }

    /** @throws IllegalArgumentException if [text] is not a valid snapshot. */
    fun decode(text: String): CommitmentSnapshot {
        try {
            val lines = text.lines().filter { it.isNotBlank() }
            require(lines.firstOrNull() == VERSION) { "Unknown negotiation state version" }

            var nextCommitmentId = 1
            var nextDealId = 1
            val commitments = ArrayList<Commitment>()
            val dealRows = ArrayList<List<String>>()
            val itemsByDeal = HashMap<Int, MutableList<CommitmentDraft>>()
            val log = ArrayList<DecisionLogEntry>()
            val outbox = ArrayList<OutboxMessage>()

            for (line in lines.drop(1)) {
                val f = line.split('|')
                when (f[0]) {
                    "N" -> { nextCommitmentId = f[1].toInt(); nextDealId = f[2].toInt() }
                    "C" -> commitments += Commitment(
                        id = f[1].toInt(), dealId = f[2].toInt(),
                        draft = CommitmentDraft(unesc(f[3]), unesc(f[4]), decodeTerm(f[5]), decodeCondition(f[6]), f[7].toInt(), f[8].toInt()),
                        status = CommitmentStatus.valueOf(f[9]), breachLicensed = f[10].toBooleanStrict(),
                        conditionEverHeld = f[11].toBooleanStrict(), failedAttempts = f[12].toInt()
                    )
                    "D" -> dealRows += f
                    "I" -> itemsByDeal.getOrPut(f[1].toInt()) { ArrayList() }.add(
                        CommitmentDraft(unesc(f[2]), unesc(f[3]), decodeTerm(f[4]), decodeCondition(f[5]), f[6].toInt(), f[7].toInt())
                    )
                    "L" -> log += DecisionLogEntry(
                        f[1].toInt(), DecisionKind.valueOf(f[2]), unesc(f[3]), unesc(f[4]), f[5].toIntOrNull(), decodeReasons(f[6])
                    )
                    "O" -> outbox += OutboxMessage(f[1].toInt(), unesc(f[2]), unesc(f[3]), OutboxKind.valueOf(f[4]), f[5].toInt())
                    else -> {} // unknown record types from a newer version are ignored
                }
            }
            val deals = dealRows.map { f ->
                val id = f[1].toInt()
                Deal(
                    id = id,
                    draft = DealDraft(unesc(f[2]), unesc(f[3]), itemsByDeal[id].orEmpty()),
                    commitmentIds = list(f[7]).map { it.toInt() },
                    proposedTurn = f[4].toInt(),
                    confirmedBy = list(f[6]).map { unesc(it) }.toSet(),
                    status = DealStatus.valueOf(f[5])
                )
            }
            return CommitmentSnapshot(nextCommitmentId, nextDealId, commitments, deals, log, outbox)
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("Malformed negotiation state: ${e.message}", e)
        }
    }

    // ---------------------------------------------------------------- terms, conditions, reasons

    private fun encodeTerm(term: Term): String = when (term) {
        Term.NoSettleNear -> args("NoSettleNear")
        Term.NoAttack -> args("NoAttack")
        is Term.NoDeclareWar -> args("NoDeclareWar", term.target)
        is Term.NoPeaceWith -> args("NoPeaceWith", term.target)
        is Term.Embargo -> args("Embargo", term.target)
        Term.DemilitarizeBorder -> args("DemilitarizeBorder")
        is Term.NoBullyOrAttackCityState -> args("NoBullyOrAttackCityState", term.cityState)
        is Term.DeclareWar -> args("DeclareWar", term.target)
        is Term.PayGoldPerTurn -> args("PayGoldPerTurn", term.amount)
        is Term.PayGoldLump -> args("PayGoldLump", term.amount)
        is Term.PrioritizeConstruction -> args("PrioritizeConstruction", term.construction, term.multiplier)
        is Term.VoteFor -> args("VoteFor", term.candidate)
    }

    private fun decodeTerm(text: String): Term {
        val a = parts(text)
        return when (a[0]) {
            "NoSettleNear" -> Term.NoSettleNear
            "NoAttack" -> Term.NoAttack
            "NoDeclareWar" -> Term.NoDeclareWar(a[1])
            "NoPeaceWith" -> Term.NoPeaceWith(a[1])
            "Embargo" -> Term.Embargo(a[1])
            "DemilitarizeBorder" -> Term.DemilitarizeBorder
            "NoBullyOrAttackCityState" -> Term.NoBullyOrAttackCityState(a[1])
            "DeclareWar" -> Term.DeclareWar(a[1])
            "PayGoldPerTurn" -> Term.PayGoldPerTurn(a[1].toInt())
            "PayGoldLump" -> Term.PayGoldLump(a[1].toInt())
            "PrioritizeConstruction" -> Term.PrioritizeConstruction(a[1], a[2].toFloat())
            "VoteFor" -> Term.VoteFor(a[1])
            else -> throw IllegalArgumentException("Unknown term '${a[0]}'")
        }
    }

    /** Nested [Condition.AllOf]s are flattened: all-of-all-of is the same as one all-of. */
    private fun leaves(c: Condition): List<Condition> =
        if (c is Condition.AllOf) c.conditions.flatMap { leaves(it) } else listOf(c)

    private fun encodeCondition(condition: Condition?): String =
        if (condition == null) "" else leaves(condition).joinToString(";") { c ->
            when (c) {
                is Condition.ForceRatioAtLeast -> args("Force", c.civ, c.versus, c.ratio)
                is Condition.AtWar -> args("AtWar", c.a, c.b, c.expected)
                is Condition.RelationshipAtLeast -> args("Relationship", c.from, c.toward, c.level.name)
                is Condition.TurnAtLeast -> args("Turn", c.turn)
                is Condition.CommitmentHonored -> args("Honored", c.commitmentId)
                is Condition.SiblingHonored -> args("Sibling", c.index)
                is Condition.AllOf -> "" // unreachable: flattened above
            }
        }

    private fun decodeCondition(text: String): Condition? {
        val found = list(text).map { item ->
            val a = parts(item)
            when (a[0]) {
                "Force" -> Condition.ForceRatioAtLeast(a[1], a[2], a[3].toFloat())
                "AtWar" -> Condition.AtWar(a[1], a[2], a[3].toBooleanStrict())
                "Relationship" -> Condition.RelationshipAtLeast(a[1], a[2], Relationship.valueOf(a[3]))
                "Turn" -> Condition.TurnAtLeast(a[1].toInt())
                "Honored" -> Condition.CommitmentHonored(a[1].toInt())
                "Sibling" -> Condition.SiblingHonored(a[1].toInt())
                else -> throw IllegalArgumentException("Unknown condition '${a[0]}'")
            }
        }
        return when (found.size) {
            0 -> null
            1 -> found.single()
            else -> Condition.AllOf(found)
        }
    }

    private fun encodeReasons(reasons: List<Reason>): String = reasons.joinToString(";") { r ->
        (listOf(r.code.name) + r.values.flatMap { (k, v) -> listOf(k, v.toString()) }).joinToString(",") { esc(it) }
    }

    private fun decodeReasons(text: String): List<Reason> = list(text).map { item ->
        val a = parts(item)
        Reason(ReasonCode.valueOf(a[0]), a.drop(1).chunked(2).associate { (k, v) -> k to v.toFloat() })
    }

    // ---------------------------------------------------------------- low-level text helpers

    private fun row(vararg fields: Any): String = fields.joinToString("|")

    /** Joins escaped arguments with ','. */
    private fun args(vararg values: Any): String = values.joinToString(",") { esc(it.toString()) }

    /** Splits an argument list and unescapes each argument. */
    private fun parts(text: String): List<String> = text.split(',').map { unesc(it) }

    /** Splits a ';'-separated list; an empty field is an empty list. */
    private fun list(text: String): List<String> = if (text.isEmpty()) emptyList() else text.split(';')

    internal fun esc(s: String): String = buildString {
        for (ch in s) when (ch) {
            '\\' -> append("\\\\")
            '|' -> append("\\p")
            ';' -> append("\\s")
            ',' -> append("\\c")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            else -> append(ch)
        }
    }

    internal fun unesc(s: String): String = buildString {
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\') { append(ch); i++; continue }
            require(i + 1 < s.length) { "Dangling escape" }
            append(
                when (val code = s[i + 1]) {
                    '\\' -> '\\'
                    'p' -> '|'
                    's' -> ';'
                    'c' -> ','
                    'n' -> '\n'
                    'r' -> '\r'
                    else -> throw IllegalArgumentException("Bad escape '\\$code'")
                }
            )
            i += 2
        }
    }
}
