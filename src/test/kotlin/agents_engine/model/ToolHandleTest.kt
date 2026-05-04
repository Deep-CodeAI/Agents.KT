package agents_engine.model

import agents_engine.core.agent
import agents_engine.generation.Generable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * #1015 — `tool(...)` builders return typed `Tool<Args, Result>` handles.
 *
 * The handle is the basis for typed `Skill.tools(...)` / `+autoTool(...)` overloads
 * landing in #1016, but it must already work as a standalone return value in 0.2.x:
 * existing call sites that ignore the return value continue to compile, and new
 * call sites that bind the handle to a `val` see the type parameters propagate.
 */
class ToolHandleTest {

    @Generable("a typed tool's args")
    data class FetchArgs(val url: String, val timeoutMs: Int = 5_000)

    @Test
    fun `untyped tool builder returns Tool with Map Args`() {
        var captured: Tool<Map<String, Any?>, Any?>? = null
        agent<String, String>("untyped-tool-handle") {
            tools {
                captured = tool("fetch", "Fetch a URL") { args -> args["url"]?.toString() ?: "missing" }
            }
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        val handle = checkNotNull(captured)
        assertEquals("fetch", handle.name)
        assertEquals("Fetch a URL", handle.description)
        assertEquals("Tool<fetch>", handle.toString())
    }

    @Test
    fun `typed tool builder returns Tool with reified Args`() {
        var captured: Tool<FetchArgs, String>? = null
        agent<String, String>("typed-tool-handle") {
            tools {
                captured = tool<FetchArgs, String>("fetch_typed", "Fetch typed") { args ->
                    "GET ${args.url} (${args.timeoutMs}ms)"
                }
            }
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        val handle = checkNotNull(captured)
        assertEquals("fetch_typed", handle.name)
        assertEquals("Tool<fetch_typed>", handle.toString())
    }

    @Test
    fun `block-builder tool returns Tool handle`() {
        var captured: Tool<Map<String, Any?>, Any?>? = null
        agent<String, String>("block-tool-handle") {
            tools {
                captured = tool("audit") {
                    description("Audit log writer")
                    executor { _ -> "logged" }
                }
            }
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        val handle = checkNotNull(captured)
        assertEquals("audit", handle.name)
        assertEquals("Audit log writer", handle.description)
    }

    @Test
    fun `Tool def points at the same ToolDef registered with the agent`() {
        var captured: Tool<Map<String, Any?>, Any?>? = null
        val a = agent<String, String>("ref-identity") {
            tools {
                captured = tool("ping", "ping") { _ -> "pong" }
            }
            skills { skill<String, String>("s") { implementedBy { it } } }
        }
        val handle = checkNotNull(captured)
        assertSame(a.toolMap["ping"], handle.def)
    }
}
