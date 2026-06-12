package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #4492 — compaction strategies over the #3865 compressor: sliding window
// (drop + marker, zero summarizer cost), custom replacement, degrade on
// custom failure, and back-compat (default = Summarize).

class CompactionStrategyTest {

    private fun messages(n: Int) = List(n) { ChatMessage(role = "user", content = "m$it") }

    private fun config(strategy: CompactionStrategy, preserve: Int = 2) =
        HistoryCompressionBuilder().apply {
            triggerMessages = 5
            preserveRecent = preserve
            this.strategy = strategy
        }.build()

    @Test
    fun `sliding window drops the middle with an elision marker and keepRecent wins`() {
        var result: HistoryCompressionResult? = null
        val decision = compressHistory(
            messages(10),
            config(CompactionStrategy.SlidingWindow(keepRecent = 3)),
        ) { result = it }

        val replaced = assertIs<Decision.ProceedWith<List<ChatMessage>>>(decision).replacement
        assertEquals(4, replaced.size, "marker + 3 kept (keepRecent overrides preserveRecent); got: $replaced")
        assertTrue(replaced.first().content.contains("elided"), replaced.first().content)
        assertEquals(listOf("m7", "m8", "m9"), replaced.drop(1).map { it.content })
        assertEquals(7, result?.replacedCount)
    }

    @Test
    fun `custom strategy controls the replacement middle`() {
        val decision = compressHistory(
            messages(10),
            config(CompactionStrategy.Custom { middle ->
                listOf(ChatMessage(role = "user", content = "custom:${middle.size}"))
            }),
        ) { }

        val replaced = assertIs<Decision.ProceedWith<List<ChatMessage>>>(decision).replacement
        assertEquals("custom:8", replaced.first().content)
    }

    @Test
    fun `custom strategy failure degrades to an uncompressed turn`() {
        val decision = compressHistory(
            messages(10),
            config(CompactionStrategy.Custom { error("custom blew up") }),
        ) { error("must not report success") }
        assertIs<Decision.Proceed>(decision)
    }

    @Test
    fun `default stays Summarize — existing behavior unchanged`() {
        val decision = compressHistory(messages(10), config(CompactionStrategy.Summarize())) { }
        val replaced = assertIs<Decision.ProceedWith<List<ChatMessage>>>(decision).replacement
        assertTrue(replaced.first { it.content.startsWith("[History summary") }.content.contains("8 earlier"))
    }
}
