package agents_engine.model

import agents_engine.core.PipelineEvent
import agents_engine.core.agent
import agents_engine.core.observe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4490 — per-tool usage constraints: maxInvocations, onlyAfter, forbidden.
// Violations deny through the standard auditable path; the model sees the
// reason as the tool result and can self-correct; counts are per invocation.

class ToolConstraintsTest {

    private fun scripted(vararg responses: LlmResponse) = ModelClient(
        ArrayDeque(responses.toList())::removeFirst,
    )

    private fun ModelClient(next: () -> LlmResponse): ModelClient = object : ModelClient {
        override fun chat(messages: List<LlmMessage>): LlmResponse = next()
    }

    @Test
    fun `maxInvocations denies the third call and the loop self-corrects`() {
        val mock = scripted(
            LlmResponse.ToolCalls(listOf(ToolCall("ping", emptyMap()))),
            LlmResponse.ToolCalls(listOf(ToolCall("ping", emptyMap()))),
            LlmResponse.ToolCalls(listOf(ToolCall("ping", emptyMap()))), // denied — constraint
            LlmResponse.Text("done"),
        )
        var executions = 0
        val denials = mutableListOf<String>()
        val a = agent<String, String>("capped") {
            model { ollama("stub"); client = mock }
            tools {
                tool("ping") {
                    constraints { maxInvocations = 2 }
                    executor { _ -> executions++; "pong" }
                }
            }
            skills {
                skill<String, String>("work", "Works") {
                    @Suppress("DEPRECATION")
                    tools("ping")
                }
            }
            onToolDenied { name, _, reason -> denials.add("$name: $reason") }
        }

        assertEquals("done", a("go"))
        assertEquals(2, executions, "third dispatch must not reach the executor")
        assertEquals(1, denials.size, "exactly one constraint denial; got: $denials")
        assertTrue("maxInvocations" in denials.single(), denials.single())
    }

    @Test
    fun `onlyAfter denies until the prerequisite tool has completed`() {
        val mock = scripted(
            LlmResponse.ToolCalls(listOf(ToolCall("commit", emptyMap()))), // denied — fetch not yet run
            LlmResponse.ToolCalls(listOf(ToolCall("fetch", emptyMap()))),
            LlmResponse.ToolCalls(listOf(ToolCall("commit", emptyMap()))), // now allowed
            LlmResponse.Text("done"),
        )
        val order = mutableListOf<String>()
        val a = agent<String, String>("ordered") {
            model { ollama("stub"); client = mock }
            tools {
                tool("fetch") { executor { _ -> order.add("fetch"); "data" } }
                tool("commit") {
                    constraints { onlyAfter("fetch") }
                    executor { _ -> order.add("commit"); "committed" }
                }
            }
            skills {
                skill<String, String>("work", "Works") {
                    @Suppress("DEPRECATION")
                    tools("fetch", "commit")
                }
            }
        }

        assertEquals("done", a("go"))
        assertEquals(listOf("fetch", "commit"), order, "commit must only execute after fetch completed")
    }

    @Test
    fun `forbidden tools never execute and surface as ToolDenied events`() {
        val mock = scripted(
            LlmResponse.ToolCalls(listOf(ToolCall("rm_rf", emptyMap()))),
            LlmResponse.Text("backed off"),
        )
        var executed = false
        val events = mutableListOf<PipelineEvent>()
        val a = agent<String, String>("guarded") {
            model { ollama("stub"); client = mock }
            tools {
                tool("rm_rf") {
                    constraints { forbidden() }
                    executor { _ -> executed = true; "boom" }
                }
            }
            skills {
                skill<String, String>("work", "Works") {
                    @Suppress("DEPRECATION")
                    tools("rm_rf")
                }
            }
        }
        a.observe { events.add(it) }

        assertEquals("backed off", a("go"))
        assertTrue(!executed, "a forbidden tool must never reach its executor")
        val denied = events.filterIsInstance<PipelineEvent.ToolDenied>().single()
        assertEquals("rm_rf", denied.toolName)
        assertTrue("forbidden" in denied.reason, denied.reason)
    }

    @Test
    fun `constraint counts reset between invocations of the same agent`() {
        var calls = 0
        // Two invocations, one agent: each gets a fresh tracker, so a
        // maxInvocations=1 tool runs once per invocation, not once ever.
        val responses = ArrayDeque(
            listOf(
                LlmResponse.ToolCalls(listOf(ToolCall("ping", emptyMap()))), LlmResponse.Text("ok-1"),
                LlmResponse.ToolCalls(listOf(ToolCall("ping", emptyMap()))), LlmResponse.Text("ok-2"),
            ),
        )
        val a = agent<String, String>("fresh-per-run") {
            model { ollama("stub"); client = ModelClient(responses::removeFirst) }
            tools {
                tool("ping") {
                    constraints { maxInvocations = 1 }
                    executor { _ -> calls++; "pong" }
                }
            }
            skills {
                skill<String, String>("work", "Works") {
                    @Suppress("DEPRECATION")
                    tools("ping")
                }
            }
        }

        assertEquals("ok-1", a("first"))
        assertEquals("ok-2", a("second"))
        assertEquals(2, calls, "per-invocation counts must not leak across runs")
    }
}
