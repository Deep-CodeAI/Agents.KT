package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ToolCall
import agents_engine.testing.DeterministicModelClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #3865 Phase 1 — before-turn history compression: trigger boundary,
// pinned system head, preserved tail with the orphan-tool-result rule,
// deterministic digests, degrade-don't-fail summarizer policy, and the
// end-to-end agentic-loop integration.

class HistoryCompressionTest {

    private fun message(role: String, content: String) = ChatMessage(role = role, content = content)

    private fun config(
        triggerMessages: Int = 5,
        preserveRecent: Int = 2,
        summarizer: ((List<ChatMessage>) -> String)? = null,
    ): HistoryCompressionConfig = HistoryCompressionBuilder().apply {
        this.triggerMessages = triggerMessages
        this.preserveRecent = preserveRecent
        summarizer?.let { summarizer(it) }
    }.build()

    @Test
    fun `below the trigger nothing happens`() {
        val messages = List(5) { message("user", "m$it") }
        val decision = compressHistory(messages, config(triggerMessages = 5)) { error("must not fire") }
        assertIs<Decision.Proceed>(decision)
    }

    @Test
    fun `above the trigger the middle collapses to one summary, system head and recent tail preserved`() {
        val messages = listOf(message("system", "prompt")) +
            List(8) { message(if (it % 2 == 0) "user" else "assistant", "turn-$it") }
        var fired: HistoryCompressionResult? = null

        val decision = compressHistory(messages, config(triggerMessages = 5, preserveRecent = 2)) { fired = it }

        val replaced = assertIs<Decision.ProceedWith<List<ChatMessage>>>(decision).replacement
        assertEquals("system", replaced.first().role, "system head must be pinned")
        assertTrue(replaced[1].content.startsWith("[History summary"), "summary message follows the head")
        assertEquals(listOf("turn-6", "turn-7"), replaced.takeLast(2).map { it.content }, "recent tail preserved")
        assertEquals(4, replaced.size, "head + summary + 2 preserved")
        assertEquals(6, fired?.replacedCount)
    }

    @Test
    fun `preserved window extends backward past an orphaned tool result`() {
        val messages = List(6) { message("user", "m$it") } +
            message("assistant", "calling tool") +
            message("tool", "tool result") +
            message("assistant", "final")
        // preserveRecent = 2 would start the tail at the tool result — the rule
        // must pull the assistant tool_call message in too.
        val decision = compressHistory(messages, config(triggerMessages = 5, preserveRecent = 2)) { }

        val replaced = assertIs<Decision.ProceedWith<List<ChatMessage>>>(decision).replacement
        val tailRoles = replaced.dropWhile { !it.content.startsWith("[History summary") }.drop(1).map { it.role }
        assertEquals(listOf("assistant", "tool", "assistant"), tailRoles, "tool result must not be orphaned")
    }

    @Test
    fun `digest is deterministic for identical input`() {
        val messages = List(10) { message("user", "stable content $it") }
        var first: String? = null
        var second: String? = null
        compressHistory(messages, config()) { first = it.digest }
        compressHistory(messages, config()) { second = it.digest }
        assertEquals(first, second, "same input must produce the identical digest")
    }

    @Test
    fun `summarizer failure degrades to an uncompressed turn instead of failing the run`() {
        val messages = List(10) { message("user", "m$it") }
        val failing = config(summarizer = { error("summarizer blew up") })
        val decision = compressHistory(messages, failing) { error("must not report success") }
        assertIs<Decision.Proceed>(decision)
    }

    @Test
    fun `agentic loop compresses mid-run and still completes`() {
        // 12 tool-call rounds then a final answer; each round adds an assistant
        // tool_call + tool result to history, so the trigger fires mid-run.
        val script = buildList {
            repeat(12) { add(LlmResponse.ToolCalls(listOf(ToolCall("note", mapOf("i" to "$it"))))) }
            add(LlmResponse.Text("done"))
        }
        var compressions = 0
        var lastResult: HistoryCompressionResult? = null
        val a = agent<String, String>("long-runner") {
            model { ollama("scripted"); client = DeterministicModelClient(*script.toTypedArray()) }
            budget { maxTurns = 50; maxToolCalls = 50 }
            tools { tool("note", "Notes a value") { args -> "noted-${args["i"]}" } }
            skills { skill<String, String>("work", "Works long") { tools("note") } }
            historyCompression {
                triggerMessages = 10
                preserveRecent = 2
            }
            onHistoryCompressed { result -> compressions++; lastResult = result }
        }

        val out = a("start")

        assertEquals("done", out, "compression must not derail the loop")
        assertTrue(compressions > 0, "compression must have fired at least once mid-run")
        assertTrue((lastResult?.replacedCount ?: 0) > 1, "a real chunk of history was replaced")
    }
}
