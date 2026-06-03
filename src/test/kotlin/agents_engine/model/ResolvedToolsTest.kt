package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #3423 — pins the tool-set assembly extracted from `executeAgentic` into [resolveAllowedTools].
 * The authorization allowlist (#630) and the action/knowledge tool sets were only reachable through a
 * full invocation; now directly testable.
 */
class ResolvedToolsTest {

    @Test
    fun `the allowlist contains exactly the skill's declared tools`() {
        lateinit var write: Tool<Map<String, Any?>, Any?>
        lateinit var read: Tool<Map<String, Any?>, Any?>
        val agent = agent<String, String>("a") {
            tools {
                write = tool("write", "write a file") { _ -> "ok" }
                read = tool("read", "read a file") { _ -> "ok" }
            }
            skills { skill<String, String>("s", "d") { tools(write, read) } }
        }
        val resolved = resolveAllowedTools(agent, agent.skills.values.first())
        assertEquals(setOf("write", "read"), resolved.allowedToolMap.keys)
        assertEquals(2, resolved.allToolDefs.size)
        assertTrue(resolved.knowledgeToolDefs.isEmpty(), "no knowledge entries declared")
    }
}
