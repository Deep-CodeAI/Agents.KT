package agents_engine.runtime

import agents_engine.core.agent
import agents_engine.model.OllamaClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #2379 — the tool minted by [Agent.absorb] now carries a typed
 * `argsType` ([SwarmDelegateArgs]) instead of relying on the permissive
 * empty-properties fallback. v1 absorb only supports `Agent<String, *>`,
 * so the delegate input shape is always `{query: String}`.
 *
 * Sibling of `MemoryToolSchemaTest` / `ForumReturnToolSchemaTest`: pins
 * the wire format so a regression silently re-introducing the untyped
 * path is caught at unit-test time.
 */
class SwarmDelegateToolSchemaTest {

    private fun namedAgent(name: String) =
        agent<String, String>(name) {
            skills { skill<String, String>("op", "op") { implementedBy { "OUT:$it" } } }
        }

    // absorb registers the delegate tool (named after the sibling) into the
    // captain's toolMap.
    private fun captainDelegateTool(siblingName: String) = run {
        val captain = agent<String, String>("captain") {
            skills { skill<String, String>("op", "op") { implementedBy { "x" } } }
        }
        captain.absorb(namedAgent(siblingName))
        captain.toolMap[siblingName]
            ?: error("absorb should have registered the delegate tool")
    }

    @Test
    fun `swarm delegate declares typed argsType backed by SwarmDelegateArgs`() {
        val tool = captainDelegateTool("helper")
        assertEquals(SwarmDelegateArgs::class, tool.argsType, "delegate must carry typed args (#2379)")
    }

    @Test
    fun `swarm delegate emits a non-permissive wire schema with a query property on Ollama`() {
        val body = object : OllamaClient(model = "test", tools = listOf(captainDelegateTool("helper"))) {}
            .buildRequestJson(emptyList())
        val compact = body.filterNot { it.isWhitespace() }
        assertFalse(
            "\"additionalProperties\":true" in compact,
            "delegate must not emit the permissive fallback: $body",
        )
        assertTrue("\"query\"" in compact, "argsType schema should expose the query property: $body")
        assertTrue("\"name\":\"helper\"" in compact)
    }
}
