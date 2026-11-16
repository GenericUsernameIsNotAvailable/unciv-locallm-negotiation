package com.unciv.logic.commitments

/**
 * Instant answers would let a player binary-search an AI's exact price ("100? 90? 95?"), which the old
 * wait-a-turn rule happened to prevent. This limits flat refusals per leader per turn, the way a person
 * eventually loses patience. Counteroffers do not count: they move towards a deal and reveal nothing the
 * AI would not already say. Not saved: it only matters within one turn.
 */
class HagglingLimit(private val maxRefusalsPerTurn: Int = 3) {
    private var turn = -1
    private val refusals = HashMap<String, Int>()

    private fun syncTurn(currentTurn: Int) {
        if (currentTurn == turn) return
        turn = currentTurn
        refusals.clear()
    }

    /** [key] identifies who is haggling with whom, e.g. "Rome>Player". */
    fun isExhausted(currentTurn: Int, key: String): Boolean {
        syncTurn(currentTurn)
        return (refusals[key] ?: 0) >= maxRefusalsPerTurn
    }

    fun noteRefusal(currentTurn: Int, key: String) {
        syncTurn(currentTurn)
        refusals[key] = (refusals[key] ?: 0) + 1
    }
}
