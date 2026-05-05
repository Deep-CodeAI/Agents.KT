package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #645 — duplicate tool names across the per-skill allowed set
 * (skill tools / auto tools / knowledge tools) must throw at agentic
 * invocation, not silently pick a winner via `distinctBy`.
 */
class DuplicateToolNameTest {

    @Test
    fun `tool name colliding with knowledge entry name throws at agentic invocation`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("collide") {
            lateinit var read: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools { read = tool("read", "an action tool") { _ -> "from-action" } }
            skills {
                skill<String, String>("s", "stub") {
                    tools(read)
                    knowledge("read", "knowledge with the same name") { "from-knowledge" }
                }
            }
        }

        try {
            a("input")
            fail("expected duplicate-name rejection at invocation")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("read"), "error must name the duplicate: ${e.message}")
            assertTrue(
                e.message!!.contains("duplicate", ignoreCase = true) ||
                    e.message!!.contains("collid", ignoreCase = true),
                "error must mention duplication: ${e.message}",
            )
            assertTrue(e.message!!.contains("s"), "error must name the offending skill: ${e.message}")
        }
    }

    @Test
    fun `non-colliding tools and knowledge work fine (regression)`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("ok") {
            lateinit var doSomething: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools { doSomething = tool("doSomething", "x") { _ -> "ok" } }
            skills {
                skill<String, String>("s", "stub") {
                    tools(doSomething)
                    knowledge("doc", "guide") { "rules" }
                }
            }
        }

        // Should not throw on invocation
        a("input")
    }

    @Test
    fun `skill that lists same tool twice still works (distinct in the input list)`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        // tools("foo", "foo") is a different shape — same source, same tool, listed twice
        // After lookup, it produces one ToolDef, not two — so no duplicate appears in allToolDefs.
        // Verify that doesn't trip the new check.
        val a = agent<String, String>("dup-listing") {
            lateinit var foo: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools { foo = tool("foo", "x") { _ -> "ok" } }
            skills { skill<String, String>("s", "stub") { tools(foo, foo) } }
        }
        a("input")
    }

    @Test
    fun `error message does not enumerate the full allowlist`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            lateinit var collide: Tool<Map<String, Any?>, Any?>
            lateinit var secretA: Tool<Map<String, Any?>, Any?>
            lateinit var secretB: Tool<Map<String, Any?>, Any?>
            model { ollama("llama3"); client = mock }
            tools {
                collide = tool("collide", "x") { _ -> "x" }
                secretA = tool("secret_a", "x") { _ -> "x" }
                secretB = tool("secret_b", "x") { _ -> "x" }
            }
            skills {
                skill<String, String>("s", "stub") {
                    tools(collide, secretA, secretB)
                    knowledge("collide", "k") { "kdata" }
                }
            }
        }

        try {
            a("input")
            fail("expected duplicate-name rejection")
        } catch (e: IllegalStateException) {
            val msg = e.message!!
            assertTrue(msg.contains("collide"), "must name the duplicate")
            assertTrue(
                !msg.contains("secret_a") && !msg.contains("secret_b"),
                "error must NOT enumerate other allowed tools (allowlist topology leak): $msg",
            )
        }
    }
}
