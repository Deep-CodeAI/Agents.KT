package agents_engine.core

import agents_engine.model.ToolDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #659 — `Agent.toolMap` is publicly read-only. Mutation goes through
 * internal `registerTool` / `registerBuiltInTool` / `unregisterTool`, which
 * apply the same guards (reservation, uniqueness) as the DSL — preventing
 * direct-mutation bypass of the runtime tool authorization model.
 */
class ToolMapEncapsulationTest {

    @Test
    fun `Agent toolMap is exposed as read-only Map (regression)`() {
        val a = agent<String, String>("ok") {
            tools { tool("foo", "x") { _ -> "f" } }
            skills { skill<String, String>("s", "stub") { tools("foo") } }
        }
        // Read access still works
        assertEquals("f", a.toolMap["foo"]!!.executor(emptyMap()))
        // The exposed property is NOT a MutableMap
        assertTrue(
            a.toolMap !is MutableMap<*, *> ||
                runCatching {
                    @Suppress("UNCHECKED_CAST")
                    (a.toolMap as MutableMap<String, ToolDef>)["bypass"] = ToolDef("bypass", executor = { "x" })
                }.isFailure,
            "toolMap getter must not expose a writeable MutableMap; if downcast succeeds the put must fail",
        )
    }

    @Test
    fun `internal registerTool rejects reserved memory names`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        try {
            a.registerTool(ToolDef(name = "memory_read", executor = { "x" }))
            fail("expected reservation rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory_read"))
            assertTrue(e.message!!.contains("reserved", ignoreCase = true))
        }
    }

    @Test
    fun `internal registerTool rejects duplicate names`() {
        val a = agent<String, String>("a") {
            tools { tool("first", "x") { _ -> "ok" } }
            skills { skill<String, String>("s", "stub") { tools("first") } }
        }
        try {
            a.registerTool(ToolDef(name = "first", executor = { "dup" }))
            fail("expected duplicate rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("first"))
        }
    }

    @Test
    fun `registerBuiltInTool installs reserved names (used by memory and forum_return)`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        a.registerBuiltInTool(ToolDef(name = "memory_read", executor = { "x" }))
        assertTrue("memory_read" in a.toolMap)
    }
}
