package com.unciv.logic.commitments.llm

import org.junit.Assert
import org.junit.Test

class MiniJsonTests {
    @Test
    fun `parses nested structures`() {
        val v = MiniJson.parse(""" { "a": 1, "b": [true, false, null, "x"], "c": { "d": -1.5e2 } } """) as Map<*, *>
        Assert.assertEquals(1.0, v["a"])
        Assert.assertEquals(listOf(true, false, null, "x"), v["b"])
        Assert.assertEquals(mapOf("d" to -150.0), v["c"])
        Assert.assertEquals(listOf("a", "b", "c"), v.keys.toList())
    }

    @Test
    fun `parses empty containers and scalars`() {
        Assert.assertEquals(emptyMap<String, Any?>(), MiniJson.parse("{}"))
        Assert.assertEquals(emptyList<Any?>(), MiniJson.parse(" [ ] "))
        Assert.assertEquals("hi", MiniJson.parse("\"hi\""))
        Assert.assertEquals(true, MiniJson.parse("true"))
        Assert.assertNull(MiniJson.parse("null"))
    }

    @Test
    fun `understands string escapes`() {
        val text = MiniJson.parse("\"q\\\" b\\\\ s\\/ n\\n t\\t u\\u00e9 \\ud83d\\ude00\"") as String
        Assert.assertEquals("q\" b\\ s/ n\n t\t u\u00e9 \uD83D\uDE00", text)
    }

    @Test
    fun `quote and parse are inverses`() {
        for (s in listOf("", "plain", "say \"hi\"", "back\\slash", "line\nbreak\r\ttab", "ctrl\u0001\u001f", "caf\u00e9 \uD83D\uDE00", "{[,:]}")) {
            Assert.assertEquals(s, MiniJson.parse(MiniJson.quote(s)))
        }
        Assert.assertEquals("\"a\\nb\"", MiniJson.quote("a\nb"))
    }

    @Test
    fun `rejects malformed input`() {
        val bad = listOf("", "{", "[1,", "{\"a\" 1}", "{'a':1}", "tru", "1 2", "\"abc", "[1 2]", "{\"a\":}", "\"\\x\"", "\"\\u12\"", "-", "nul")
        for (text in bad) {
            try {
                MiniJson.parse(text)
                Assert.fail("accepted: $text")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun `refuses absurdly deep nesting instead of overflowing the stack`() {
        val deep = "[".repeat(500) + "]".repeat(500)
        try {
            MiniJson.parse(deep)
            Assert.fail("accepted")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
