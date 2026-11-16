package com.unciv.logic.commitments.llm

/** One earlier turn of a conversation. [role] is "user" or "assistant". */
data class LlmMessage(val role: String, val content: String)

/**
 * A single model call. [history] holds earlier turns, oldest first, placed between [system] and [user].
 * [grammar] is a GBNF grammar (llama.cpp) that constrains the output; the understanding step will use it so
 * even tiny models must produce valid structured intents.
 */
data class LlmRequest(
    val system: String,
    val user: String,
    val grammar: String? = null,
    val maxTokens: Int = 128,
    val temperature: Float = 0.7f,
    val history: List<LlmMessage> = emptyList(),
    /** Asks servers that support it to limit hidden thinking, e.g. "none". Left out of the request when null. */
    val reasoningEffort: String? = null
)

data class LlmResponse(val text: String)

/** The model could not be reached or gave an unusable answer. The message is meant for the player. */
class LlmException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Seam between the negotiation pipeline and whatever runs the model: an on-device llama.cpp binding on
 * Android, a local server during desktop development, or a scripted fake in tests.
 * Calls block, so callers must run them off the render thread (Unciv uses Concurrency.run).
 */
interface LlmBackend {
    val isAvailable: Boolean

    /** @throws LlmException if the model cannot be reached or answers with something unusable. */
    fun generate(request: LlmRequest): LlmResponse
}
