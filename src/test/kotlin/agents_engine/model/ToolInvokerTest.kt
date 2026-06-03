package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * #3376 batch 3 — pins contracts of the tool-execution subsystem extracted from `AgenticLoop` into
 * `ToolInvoker.kt`. These functions were `private` to the loop (only reachable through a full agentic
 * invocation); the extraction makes the budget gate + recovery ladder directly unit-testable.
 */
class ToolInvokerTest {

    @Test
    fun `toolArgsByteSize measures the wire argument form`() {
        assertEquals(9L, toolArgsByteSize(ToolCall(name = "t", rawArguments = """{"a":"b"}""")))
    }

    @Test
    fun `validateTypedArgsOrNull passes an untyped tool`() {
        val tool = ToolDef("t", "desc") { _ -> "ok" }
        assertNull(validateTypedArgsOrNull(tool, mapOf("x" to 1)))
    }

    @Test
    fun `executeToolWithExecutionRecovery rethrows when the tool throws and there's no handler`() {
        val agent = agents_engine.core.agent<String, String>("a") {
            skills { skill<String, String>("s", "d") { implementedBy { it } } }
        }
        val tool = ToolDef("boom", "throws") { _ -> error("kaboom") }
        val ex = assertFailsWith<IllegalStateException> {
            executeToolWithExecutionRecovery(agent, tool, "boom", emptyMap(), handler = null)
        }
        assertEquals("kaboom", ex.message)
    }
}
