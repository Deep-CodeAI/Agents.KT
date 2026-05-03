package agents_engine.model

import agents_engine.core.MemoryBank
import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Tests for #856 — per-skill memory-tool opt-in.
//
// When ANY skill on an agent calls `useMemory()`, the agentic loop respects
// that opt-in: skills that did NOT opt in must NOT receive memory_* tools in
// their authorized set. When no skill opts in, the legacy default-on behavior
// is preserved for single-skill agents that already work today.
class MemoryToolPerSkillOptInTest {

    private fun snoopAuthorizedTools(captureLatest: MutableList<List<String>>): ModelClient {
        // Use the system message that the agentic loop builds — it lists every tool
        // available to the current skill. We can read it back from the captured
        // messages of the first chat() call.
        return ModelClient { msgs ->
            val sys = msgs.firstOrNull { it.role == "system" }
            val tools = sys?.content
                ?.lineSequence()
                ?.filter { it.startsWith("- ") }
                ?.map { it.removePrefix("- ").substringBefore(":") }
                ?.toList()
                ?: emptyList()
            captureLatest.add(tools)
            LlmResponse.Text("done")
        }
    }

    @Test
    fun `legacy single-skill agent with memoryBank still auto-gets memory tools (backward compat)`() {
        val captured = mutableListOf<List<String>>()
        val a = agent<String, String>("legacy") {
            model { ollama("test"); client = snoopAuthorizedTools(captured) }
            memory(MemoryBank())
            skills { skill<String, String>("only", "stub") { tools() } }
        }
        a("hi")
        assertTrue(captured.single().contains("memory_read"), "legacy auto-inject must still work; got: ${captured.single()}")
        assertTrue(captured.single().contains("memory_write"))
        assertTrue(captured.single().contains("memory_search"))
    }

    @Test
    fun `when one skill opts in, a non-opted-in skill on the same agent loses memory access`() {
        // The core security guarantee — auto-inject across ALL skills was the bug.
        // With two skills on one agent (one opts in, one doesn't), the non-opted
        // skill's authorized tool set must NOT include memory_*.
        // The mock client first answers the skill-router LLM call (returning a
        // SkillRoute pointing at "read-only"), then snoops the second call's
        // system prompt for the "read-only" skill's authorized tools.
        val captured = mutableListOf<List<String>>()
        var callIdx = 0
        val client = ModelClient { msgs ->
            callIdx++
            if (callIdx == 1) {
                // Skill router call — return a JSON SkillRoute.
                LlmResponse.Text("""{"skillName":"read-only","confidence":1.0,"rationale":"x"}""")
            } else {
                // Skill execution call — capture the system prompt's tool listing.
                val sys = msgs.firstOrNull { it.role == "system" }
                captured.add(
                    sys?.content?.lineSequence()
                        ?.filter { it.startsWith("- ") }
                        ?.map { it.removePrefix("- ").substringBefore(":") }
                        ?.toList()
                        ?: emptyList(),
                )
                LlmResponse.Text("done")
            }
        }
        val a = agent<String, String>("two-skill") {
            model { ollama("test"); this.client = client }
            memory(MemoryBank())
            skills {
                skill<String, String>("read-only", "answers questions") { tools() }
                skill<String, String>("memo-writer", "writes notes") { tools(); useMemory() }
            }
        }
        a("input")
        val tools = captured.single()
        assertFalse(tools.contains("memory_read"), "non-opted-in skill must NOT receive memory_read; got: $tools")
        assertFalse(tools.contains("memory_write"), "non-opted-in skill must NOT receive memory_write; got: $tools")
        assertFalse(tools.contains("memory_search"), "non-opted-in skill must NOT receive memory_search; got: $tools")
    }

    @Test
    fun `opted-in skill DOES receive memory tools`() {
        val captured = mutableListOf<List<String>>()
        val a = agent<String, String>("writer") {
            model { ollama("test"); client = snoopAuthorizedTools(captured) }
            memory(MemoryBank())
            skills {
                skill<String, String>("memo-writer", "uses memory") { tools(); useMemory() }
            }
        }
        a("hi")
        val tools = captured.single()
        assertTrue(tools.contains("memory_read"), "opted-in skill must receive memory_read; got: $tools")
        assertTrue(tools.contains("memory_write"))
        assertTrue(tools.contains("memory_search"))
    }

    @Test
    fun `agent without memoryBank never injects memory tools regardless of useMemory`() {
        val captured = mutableListOf<List<String>>()
        val a = agent<String, String>("no-memory") {
            model { ollama("test"); client = snoopAuthorizedTools(captured) }
            // no memory(...) call
            skills {
                skill<String, String>("s", "stub") { tools(); useMemory() }
            }
        }
        a("hi")
        val tools = captured.single()
        assertEquals(emptyList(), tools.filter { it.startsWith("memory_") }, "no memoryBank means no memory tools regardless of opt-in")
    }
}
