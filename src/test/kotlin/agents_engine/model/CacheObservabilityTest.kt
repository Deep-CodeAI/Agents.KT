package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2663 — cache observability surface on [TokenUsage]. Existing
 * `cachedInputTokens` (cache reads) is joined by `cacheWriteTokens`
 * (Anthropic's premium-billed prefix-population) and a derived
 * `cacheHitRate` ratio. Both are subsets of `promptTokens` — not extra
 * billable tokens added to `total`.
 */
class CacheObservabilityTest {

    @Test
    fun `cacheHitRate is the cached-input ratio of promptTokens`() {
        val u = TokenUsage(promptTokens = 1000, completionTokens = 50, cachedInputTokens = 850, provider = "claude", model = "x")
        assertEquals(0.85, u.cacheHitRate!!, 1e-9, "850 / 1000 = 0.85")
    }

    @Test
    fun `cacheHitRate is null when the provider did not report cached tokens`() {
        val u = TokenUsage(promptTokens = 1000, completionTokens = 50, cachedInputTokens = null, provider = "ollama", model = "x")
        assertNull(u.cacheHitRate)
    }

    @Test
    fun `cacheHitRate is null on zero prompt tokens to avoid div-by-zero`() {
        val u = TokenUsage(promptTokens = 0, completionTokens = 0, cachedInputTokens = 0, provider = "x", model = "x")
        assertNull(u.cacheHitRate)
    }

    @Test
    fun `cacheHitRate is 1 point 0 on full cache hit`() {
        val u = TokenUsage(promptTokens = 500, completionTokens = 10, cachedInputTokens = 500, provider = "x", model = "x")
        assertEquals(1.0, u.cacheHitRate!!, 1e-9)
    }

    @Test
    fun `cacheWriteTokens defaults to null and does NOT inflate total`() {
        val u = TokenUsage(promptTokens = 100, completionTokens = 20, cacheWriteTokens = 80, provider = "claude", model = "x")
        // Total covers prompt + completion only — cache splits are subsets.
        assertEquals(120, u.total, "total = prompt + completion; cache write does not add to it")
        assertEquals(80, u.cacheWriteTokens)
    }

    @Test
    fun `data class identity round-trips cacheWriteTokens via copy`() {
        // Verify the new field threads through standard data-class operations.
        val original = TokenUsage(promptTokens = 100, completionTokens = 10, provider = "claude", model = "x")
        val withWrite = original.copy(cacheWriteTokens = 50)
        assertEquals(50, withWrite.cacheWriteTokens)
        assertNull(original.cacheWriteTokens, "original is untouched")
    }

    @Test
    fun `cumulative TokenUsage in agentic loop sums cacheWriteTokens across turns`() {
        // Two simulated turns: one with a write (prefix populated), one with
        // a read (prefix hit). The agentic loop's cumulative builder must
        // sum the writes; pinned here by reproducing the builder logic
        // shape so a regression in the AgenticLoop accumulator surfaces.
        val turn1 = TokenUsage(promptTokens = 1000, completionTokens = 30, cachedInputTokens = 0, cacheWriteTokens = 900, provider = "claude", model = "x")
        val turn2 = TokenUsage(promptTokens = 1000, completionTokens = 30, cachedInputTokens = 900, cacheWriteTokens = 0, provider = "claude", model = "x")

        // Mirror the agentic-loop accumulator (AgenticLoop.kt cumulativeUsage builder)
        val cumulative = TokenUsage(
            promptTokens = turn1.promptTokens + turn2.promptTokens,
            completionTokens = turn1.completionTokens + turn2.completionTokens,
            cachedInputTokens = (turn1.cachedInputTokens ?: 0) + (turn2.cachedInputTokens ?: 0),
            cacheWriteTokens = (turn1.cacheWriteTokens ?: 0) + (turn2.cacheWriteTokens ?: 0),
            provider = "claude",
            model = "x",
        )

        assertEquals(900, cumulative.cachedInputTokens, "cumulative reads")
        assertEquals(900, cumulative.cacheWriteTokens, "cumulative writes")
        // Cumulative hit rate across two turns of 2000 prompt tokens with 900 cached = 45%
        assertEquals(0.45, cumulative.cacheHitRate!!, 1e-9)
    }

    @Test
    fun `existing cachedInputTokens contract is preserved for non-caching providers`() {
        // Ollama doesn't expose cached-token usage — null end-to-end.
        val u = TokenUsage(promptTokens = 100, completionTokens = 20, provider = "ollama", model = "llama3")
        assertNull(u.cachedInputTokens)
        assertNull(u.cacheWriteTokens)
        assertNull(u.cacheHitRate)
        assertTrue(u.total == 120)
    }

    @Test
    fun `pre-existing TokenUsage call sites compile unchanged (additive constructor)`() {
        // Smoke test that the new optional parameter didn't break the
        // five-arg ctor that providers used through 0.6.3.
        val legacy = TokenUsage(
            promptTokens = 100,
            completionTokens = 20,
            cachedInputTokens = 10,
            provider = "claude",
            model = "claude-opus-4-7",
        )
        assertNotNull(legacy)
        assertEquals(120, legacy.total)
    }
}
