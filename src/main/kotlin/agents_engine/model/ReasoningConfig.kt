package agents_engine.model

/**
 * Opt-in reasoning/thinking configuration (#2406). Off unless set. When
 * enabled, providers that expose reasoning surface it via
 * `AgentEvent.Reasoning` / `LlmResponse.reasoning`:
 * - Claude: `thinking` with [budgetTokens] as the token budget.
 * - Ollama: `think: true`.
 * - DeepSeek: `reasoning_content` (stops force-disabling thinking).
 * - OpenAI: `reasoning_effort` from [effort]; surfaces reasoning *token counts*
 *   only — Chat Completions returns no reasoning text.
 */
data class ReasoningConfig(
    val enabled: Boolean = true,
    val budgetTokens: Int? = null,
    val effort: ReasoningEffort? = null,
)
