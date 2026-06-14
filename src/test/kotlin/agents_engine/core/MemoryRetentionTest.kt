package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #4515 (PRD §8.5) — memory retention strategies. TDD: each trims its own way; MemoryBank applies
// the strategy on write; the historical maxLines maps to Sliding.

class MemoryRetentionTest {

    private val tenLines = (1..10).joinToString("\n") { "line$it" }

    @Test
    fun `Sliding keeps the last N lines`() {
        val out = MemoryRetention.Sliding(3).apply(tenLines)
        assertEquals("line8\nline9\nline10", out)
    }

    @Test
    fun `Sliding under the cap is unchanged`() {
        assertEquals("a\nb", MemoryRetention.Sliding(5).apply("a\nb"))
    }

    @Test
    fun `TokenBudget drops oldest lines until within the token budget`() {
        // 10 lines, each "lineN" ~ 5-6 chars → ~2 tokens each. Budget that fits ~the last 2 lines.
        val out = MemoryRetention.TokenBudget(maxTokens = 4).apply(tenLines)
        val kept = out.lines()
        assertTrue(kept.isNotEmpty() && kept.last() == "line10", "keeps the most recent: $out")
        assertTrue(estimateTokens(out) <= 4, "within budget: ${estimateTokens(out)} tokens")
        assertTrue(kept.size < 10, "dropped older lines")
    }

    @Test
    fun `TokenBudget within budget is unchanged`() {
        assertEquals("hi", MemoryRetention.TokenBudget(1000).apply("hi"))
    }

    @Test
    fun `Summarized collapses older lines and keeps the recent ones`() {
        val out = MemoryRetention.Summarized(keepRecentLines = 2) { older -> "[summary of ${older.size}]" }
            .apply(tenLines)
        assertEquals("[summary of 8]\nline9\nline10", out)
    }

    @Test
    fun `Summarized under the keep count is unchanged and does not summarize`() {
        var called = false
        val out = MemoryRetention.Summarized(keepRecentLines = 5) { called = true; "x" }.apply("a\nb")
        assertEquals("a\nb", out)
        assertTrue(!called, "summarizer must not run when under the keep count")
    }

    @Test
    fun `Unbounded keeps everything`() {
        assertEquals(tenLines, MemoryRetention.Unbounded.apply(tenLines))
    }

    @Test
    fun `MemoryBank applies its retention strategy on write`() {
        val bank = MemoryBank(retention = MemoryRetention.Sliding(2))
        bank.write("agent", tenLines)
        assertEquals("line9\nline10", bank.read("agent"))
    }

    @Test
    fun `maxLines constructor still behaves like Sliding (backward compatible)`() {
        val bank = MemoryBank(maxLines = 3)
        bank.write("agent", tenLines)
        assertEquals("line8\nline9\nline10", bank.read("agent"))
    }

    @Test
    fun `invalid strategy parameters are rejected`() {
        assertFailsWith<IllegalArgumentException> { MemoryRetention.Sliding(0) }
        assertFailsWith<IllegalArgumentException> { MemoryRetention.TokenBudget(0) }
        assertFailsWith<IllegalArgumentException> { MemoryRetention.Summarized(-1) { "" } }
    }
}
