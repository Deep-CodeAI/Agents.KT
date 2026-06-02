package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #3376 batch 1 — pins the tool-result/error rendering contracts extracted out of `AgenticLoop`'s
 * private helpers into [ToolResultRendering]. These were unreachable for unit testing while private
 * to the loop; the extraction makes them directly testable. Behavior must match the prior inline fns.
 */
class ToolResultRenderingTest {

    @Test
    fun `formatDeniedToolError renders the policy denial`() {
        assertEquals(
            "ERROR: Tool 'writeFile' denied by policy: outside sandbox",
            ToolResultRendering.formatDeniedToolError("writeFile", "outside sandbox"),
        )
    }

    @Test
    fun `formatEscalatedToolError includes reason and severity`() {
        val msg = ToolResultRendering.formatEscalatedToolError(
            "fetch",
            RepairResult.Escalated("rate limited", Severity.HIGH),
        )
        assertEquals(
            "ERROR: Tool 'fetch' failed: rate limited (severity: HIGH). Please retry with corrected arguments.",
            msg,
        )
    }

    @Test
    fun `wrapUntrustedToolResult produces a trusted-false JSON envelope`() {
        assertEquals(
            """{"tool":"web","trusted":false,"value":"hello"}""",
            ToolResultRendering.wrapUntrustedToolResult("web", "hello"),
        )
    }

    @Test
    fun `wrapUntrustedToolResult escapes quotes and control chars (valid JSON, #2756)`() {
        val json = ToolResultRendering.wrapUntrustedToolResult("x", "a\"b\nc")
        assertTrue("\\\"" in json, "must escape the quote: $json")
        assertTrue("\\n" in json, "must escape the newline: $json")
    }

    @Test
    fun `renderToolResultForLlm uses toString, and 'null' for null`() {
        assertEquals("42", ToolResultRendering.renderToolResultForLlm(42))
        assertEquals("null", ToolResultRendering.renderToolResultForLlm(null))
    }
}
