package com.unciv.logic.commitments.llm

import com.sun.net.httpserver.HttpServer
import org.junit.Assert
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class OpenAiCompatibleBackendTests {

    /** A real HTTP server on the loopback interface that records the last request and replies with a canned answer. */
    private class FakeServer(private val status: Int, private val response: String) {
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        @Volatile var lastBody = ""
        @Volatile var lastAuth: String? = null
        @Volatile var lastPath = ""
        val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

        init {
            server.createContext("/") { exchange ->
                lastBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                lastAuth = exchange.requestHeaders.getFirst("Authorization")
                lastPath = exchange.requestURI.path
                val bytes = response.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
        }

        fun stop() = server.stop(0)
    }

    private fun answer(content: String) = """{"choices":[{"index":0,"message":{"role":"assistant","content":${MiniJson.quote(content)}}}]}"""

    @Test
    fun `sends an OpenAI style chat request and returns the reply`() {
        val server = FakeServer(200, answer("Greetings, traveller."))
        try {
            val backend = OpenAiCompatibleBackend(server.baseUrl, "test-model")
            val request = LlmRequest(
                system = "You are Caesar.", user = "Hello \"Caesar\"\nhow are you?", maxTokens = 50, temperature = 0.5f,
                history = listOf(LlmMessage("user", "earlier"), LlmMessage("assistant", "reply"))
            )
            Assert.assertEquals("Greetings, traveller.", backend.generate(request).text)

            Assert.assertEquals("/v1/chat/completions", server.lastPath)
            val body = MiniJson.parse(server.lastBody) as Map<*, *>
            Assert.assertEquals("test-model", body["model"])
            Assert.assertEquals(50.0, body["max_tokens"])
            Assert.assertEquals(0.5, body["temperature"])
            Assert.assertEquals(false, body["stream"])
            Assert.assertFalse(body.containsKey("grammar"))
            val messages = (body["messages"] as List<*>).map { it as Map<*, *> }
            Assert.assertEquals(listOf("system", "user", "assistant", "user"), messages.map { it["role"] })
            Assert.assertEquals("You are Caesar.", messages[0]["content"])
            Assert.assertEquals("Hello \"Caesar\"\nhow are you?", messages[3]["content"]) // special characters survive
            Assert.assertNull(server.lastAuth)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `passes a grammar and an api key when given`() {
        val server = FakeServer(200, answer("{}"))
        try {
            OpenAiCompatibleBackend(server.baseUrl, "m", apiKey = "secret").generate(LlmRequest("s", "u", grammar = "root ::= \"x\""))
            Assert.assertEquals("Bearer secret", server.lastAuth)
            Assert.assertEquals("root ::= \"x\"", (MiniJson.parse(server.lastBody) as Map<*, *>)["grammar"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `the model can be switched between calls`() {
        val server = FakeServer(200, answer("ok"))
        try {
            var model = "first-model"
            val backend = SwitchableBackend { LlmEndpoint(server.baseUrl, model) }
            backend.generate(LlmRequest("s", "u"))
            Assert.assertEquals("first-model", (MiniJson.parse(server.lastBody) as Map<*, *>)["model"])
            model = "second-model"
            backend.generate(LlmRequest("s", "u"))
            Assert.assertEquals("second-model", (MiniJson.parse(server.lastBody) as Map<*, *>)["model"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `a trailing slash on the address is harmless`() {
        val server = FakeServer(200, answer("ok"))
        try {
            Assert.assertEquals("ok", OpenAiCompatibleBackend(server.baseUrl + "/", "m").generate(LlmRequest("s", "u")).text)
            Assert.assertEquals("/v1/chat/completions", server.lastPath)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `server errors become readable exceptions`() {
        val server = FakeServer(500, "model not found")
        try {
            val e = failure { OpenAiCompatibleBackend(server.baseUrl, "m").generate(LlmRequest("s", "u")) }
            Assert.assertTrue(e.message!!.contains("500"))
            Assert.assertTrue(e.message!!.contains("model not found"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unusable answers become exceptions`() {
        for (response in listOf("not json", "{}", """{"choices":[]}""", """{"choices":[{"message":{}}]}""")) {
            val server = FakeServer(200, response)
            try {
                failure { OpenAiCompatibleBackend(server.baseUrl, "m").generate(LlmRequest("s", "u")) }
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `an empty reply explains that the model may be thinking`() {
        val response = """{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"","reasoning":"Let me think about this for a while"}}]}"""
        val server = FakeServer(200, response)
        try {
            val e = failure { OpenAiCompatibleBackend(server.baseUrl, "m").generate(LlmRequest("s", "u")) }
            Assert.assertTrue(e.message!!, e.message!!.contains("finish_reason=length"))
            Assert.assertTrue(e.message!!.contains("hidden thinking"))
            Assert.assertTrue(e.message!!.contains("negotiation thinking off"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `reasoning effort is sent only when asked for`() {
        val server = FakeServer(200, answer("ok"))
        try {
            val backend = OpenAiCompatibleBackend(server.baseUrl, "m")
            backend.generate(LlmRequest("s", "u"))
            Assert.assertFalse((MiniJson.parse(server.lastBody) as Map<*, *>).containsKey("reasoning_effort"))
            backend.generate(LlmRequest("s", "u", reasoningEffort = "none"))
            Assert.assertEquals("none", (MiniJson.parse(server.lastBody) as Map<*, *>)["reasoning_effort"])
        } finally {
            server.stop()
        }
    }

    @Test
    fun `an unreachable server becomes an exception that says where`() {
        val server = FakeServer(200, answer("x"))
        val url = server.baseUrl
        server.stop()
        val e = failure { OpenAiCompatibleBackend(url, "m").generate(LlmRequest("s", "u")) }
        Assert.assertTrue(e.message!!.contains("/v1/chat/completions"))
    }

    @Test
    fun `a malformed address becomes an exception`() {
        failure { OpenAiCompatibleBackend("not a url", "m").generate(LlmRequest("s", "u")) }
    }

    private fun failure(block: () -> Unit): LlmException {
        try {
            block()
        } catch (e: LlmException) {
            return e
        }
        throw AssertionError("expected an LlmException")
    }
}
