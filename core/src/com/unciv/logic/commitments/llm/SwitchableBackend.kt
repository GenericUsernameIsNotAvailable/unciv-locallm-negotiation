package com.unciv.logic.commitments.llm

/** Where and what to talk to. */
data class LlmEndpoint(val baseUrl: String, val model: String, val apiKey: String? = null)

/**
 * Looks up the endpoint again on every call, so the model can be changed while the game is running
 * (the chats already open pick the change up at their next reply).
 */
class SwitchableBackend(private val endpoint: () -> LlmEndpoint) : LlmBackend {
    override val isAvailable: Boolean get() = true

    override fun generate(request: LlmRequest): LlmResponse {
        val current = endpoint()
        return OpenAiCompatibleBackend(current.baseUrl, current.model, current.apiKey).generate(request)
    }
}
