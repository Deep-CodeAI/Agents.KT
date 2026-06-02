package agents_engine.model

/**
 * Token consumption for one LLM round-trip — null on the response when the
 * provider doesn't report it. Sum of prompt + completion is what counts toward
 * [BudgetConfig.maxTokens]. Cached input tokens are a provider-visible subset
 * of prompt tokens, not extra billable tokens to add to [total]. See #963/#2355.
 */
data class TokenUsage(
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedInputTokens: Int? = null,
    val provider: String = "unknown",
    val model: String = "unknown",
    /**
     * Reasoning tokens the provider billed inside [completionTokens] when a
     * reasoning model was used (#2411). A subset of completion tokens, not extra
     * — surfaced for cost/observability. Null when the provider doesn't report it.
     */
    val reasoningTokens: Int? = null,
    /**
     * Cache-write tokens billed at premium rate (#2663 — Anthropic prompt-caching
     * split). Anthropic charges 25% more for the tokens that *populated* the
     * cache and ~10% for [cachedInputTokens] that *hit* it; null on providers
     * that don't expose the write side (OpenAI / DeepSeek / Ollama report
     * cache reads only). A subset of [promptTokens] semantically — not extra
     * billable tokens to add to [total].
     */
    val cacheWriteTokens: Int? = null,
) {
    val total: Int get() = promptTokens + completionTokens

    /**
     * Cache hit ratio for this round-trip (#2663): cached input tokens as a
     * fraction of total prompt tokens. Null when the provider didn't report
     * cached-token usage, or when [promptTokens] is zero (no prompt to cache).
     * Range `[0.0, 1.0]`; `1.0` means the entire prompt hit the cache.
     */
    val cacheHitRate: Double?
        get() {
            val cached = cachedInputTokens ?: return null
            if (promptTokens <= 0) return null
            return cached.toDouble() / promptTokens.toDouble()
        }

    /**
     * #2867 — cumulative accumulator. Sum every field; nullable fields
     * collapse to `null` only when BOTH operands are null (so a turn that
     * happens to report `cacheWriteTokens = null` doesn't erase the
     * running total from earlier turns that did report it). Provider /
     * model come from [other] — the latest turn wins, matching how the
     * AgenticLoop wrote cumulative usage pre-#2867.
     *
     * Before this helper landed, the AgenticLoop's inline merge dropped
     * `reasoningTokens` entirely (audited at 0.6.5 by the 8.0/10 review),
     * and the snapshot encoder dropped `cacheWriteTokens`. Routing both
     * paths through `+` makes a missed field a compile-time error, not
     * a silent loss.
     */
    operator fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        promptTokens = promptTokens + other.promptTokens,
        completionTokens = completionTokens + other.completionTokens,
        cachedInputTokens = sumNullable(cachedInputTokens, other.cachedInputTokens),
        cacheWriteTokens = sumNullable(cacheWriteTokens, other.cacheWriteTokens),
        reasoningTokens = sumNullable(reasoningTokens, other.reasoningTokens),
        provider = other.provider,
        model = other.model,
    )

    private fun sumNullable(a: Int?, b: Int?): Int? = when {
        a == null && b == null -> null
        else -> (a ?: 0) + (b ?: 0)
    }
}
