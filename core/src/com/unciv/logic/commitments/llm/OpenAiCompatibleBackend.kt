package com.unciv.logic.commitments.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Talks to any server offering the OpenAI-style `POST {baseUrl}/chat/completions`: Ollama
 * (`http://localhost:11434/v1`), llama.cpp's server, LM Studio and so on. Used for desktop development;
 * nothing here needs the internet when the server runs on the same machine.
 */
class OpenAiCompatibleBackend(
    baseUrl: String,
    private val model: String,
    private val apiKey: String? = null,
    private val readTimeoutMillis: Int = 120_000
) : LlmBackend {
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"

    /** Whether the server is reachable is only known when it is asked, so failures surface from [generate]. */
    override val isAvailable: Boolean get() = true

    override fun generate(request: LlmRequest): LlmResponse {
        return try {
            generateOnce(request)
        } catch (e: LlmException) {
            val msg = e.message.orEmpty()
            if (msg.contains("llama-server", ignoreCase = true) || msg.contains("process has terminated", ignoreCase = true)) {
                try { Thread.sleep(600) } catch (_: InterruptedException) {}
                generateOnce(request)
            } else throw e
        }
    }

    private fun generateOnce(request: LlmRequest): LlmResponse {
        val connection = try {
            URI(endpoint).toURL().openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw LlmException("The language model address '$endpoint' is not valid", e)
        }
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 5_000
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            apiKey?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            connection.outputStream.use { it.write(buildBody(request).toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) throw LlmException("The language model server answered HTTP $status: ${text.take(200)}")
            return LlmResponse(extractContent(text))
        } catch (e: IOException) {
            throw LlmException("Could not reach the language model server at $endpoint (${e.message})", e)
        } finally {
            connection.disconnect()
        }
    }

    internal fun buildBody(request: LlmRequest): String {
        fun message(role: String, content: String) = """{"role":${MiniJson.quote(role)},"content":${MiniJson.quote(content)}}"""
        val messages = buildList {
            add(message("system", request.system))
            request.history.forEach { add(message(it.role, it.content)) }
            add(message("user", request.user))
        }
        return buildString {
            append("""{"model":${MiniJson.quote(model)},"messages":[${messages.joinToString(",")}]""")
            append(""","max_tokens":${request.maxTokens},"temperature":${request.temperature},"stream":false""")
            request.grammar?.let { append(""","grammar":${MiniJson.quote(it)}""") }
            request.reasoningEffort?.let { append(""","reasoning_effort":${MiniJson.quote(it)}""") }
            append("}")
        }
    }

    internal fun extractContent(responseText: String): String {
        val root = try {
            MiniJson.parse(responseText) as? Map<*, *>
        } catch (e: IllegalArgumentException) {
            throw LlmException("The language model server sent an unreadable answer", e)
        }
        val choice = (root?.get("choices") as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: throw LlmException("The language model server's answer had no message")
        val message = choice["message"] as? Map<*, *> ?: throw LlmException("The language model server's answer had no message")
        val content = message["content"] as? String ?: throw LlmException("The language model server's answer had no message")
        if (content.isBlank()) {
            val hidden = (message["reasoning"] ?: message["reasoning_content"] ?: message["thinking"]) as? String
            throw LlmException(
                "The model returned no text (finish_reason=${choice["finish_reason"]}" +
                    (if (!hidden.isNullOrBlank()) ", ${hidden.length} characters of hidden thinking" else "") +
                    "). It may be using its whole token budget on thinking: try 'negotiation thinking off' or a model that does not think."
            )
        }
        return content
    }
}
