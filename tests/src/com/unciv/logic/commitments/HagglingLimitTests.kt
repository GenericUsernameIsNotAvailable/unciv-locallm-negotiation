package com.unciv.logic.commitments

import org.junit.Assert
import org.junit.Test

class HagglingLimitTests {
    @Test
    fun `patience runs out after the allowed number of refusals`() {
        val limit = HagglingLimit(maxRefusalsPerTurn = 3)
        repeat(2) { limit.noteRefusal(5, "Rome>Player") }
        Assert.assertFalse(limit.isExhausted(5, "Rome>Player"))
        limit.noteRefusal(5, "Rome>Player")
        Assert.assertTrue(limit.isExhausted(5, "Rome>Player"))
    }

    @Test
    fun `each leader has their own patience`() {
        val limit = HagglingLimit(maxRefusalsPerTurn = 1)
        limit.noteRefusal(5, "Rome>Player")
        Assert.assertTrue(limit.isExhausted(5, "Rome>Player"))
        Assert.assertFalse(limit.isExhausted(5, "Greece>Player"))
    }

    @Test
    fun `patience is restored on a new turn`() {
        val limit = HagglingLimit(maxRefusalsPerTurn = 1)
        limit.noteRefusal(5, "Rome>Player")
        Assert.assertTrue(limit.isExhausted(5, "Rome>Player"))
        Assert.assertFalse(limit.isExhausted(6, "Rome>Player"))
        limit.noteRefusal(6, "Rome>Player")
        Assert.assertTrue(limit.isExhausted(6, "Rome>Player"))
    }
}
