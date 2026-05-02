package agents_engine.model

import agents_engine.core.MemoryBank
import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for #644 — `memory_read`, `memory_write`, `memory_search` are reserved
 * names. Any user attempt to register them via the tools DSL must throw at
 * construction. Only `memory(bank)` may install these tools.
 *
 * Closes the post-PR-1 attack surface where a user-defined `memory_read`
 * shadowing the built-in could be auto-allowed by the agentic loop's
 * memory-tool path.
 */
class ReservedMemoryToolNamesTest {

    @Test
    fun `tool(name, desc, executor) blocks memory_read`() {
        try {
            agent<String, String>("a") {
                tools { tool("memory_read", "x") { _ -> "x" } }
                skills { skill<String, String>("s", "stub") { tools("memory_read") } }
            }
            fail("expected reserved-name rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory_read"))
            assertTrue(e.message!!.contains("reserved", ignoreCase = true))
        }
    }

    @Test
    fun `tool blocks memory_write`() {
        try {
            agent<String, String>("a") {
                tools { tool("memory_write", "x") { _ -> "x" } }
                skills { skill<String, String>("s", "stub") { tools("memory_write") } }
            }
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory_write"))
        }
    }

    @Test
    fun `tool blocks memory_search`() {
        try {
            agent<String, String>("a") {
                tools { tool("memory_search", "x") { _ -> "x" } }
                skills { skill<String, String>("s", "stub") { tools("memory_search") } }
            }
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory_search"))
        }
    }

    @Test
    fun `tool block-builder blocks reserved names`() {
        try {
            agent<String, String>("a") {
                tools {
                    tool("memory_read") {
                        description("x")
                        executor { _ -> "x" }
                    }
                }
                skills { skill<String, String>("s", "stub") { tools("memory_read") } }
            }
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory_read"))
        }
    }

    @Test
    fun `tool with onError overload blocks reserved names`() {
        try {
            agent<String, String>("a") {
                tools {
                    tool("memory_read", "x", onError = {
                        invalidArgs { _, _ -> null }
                    }) { _ -> "x" }
                }
                skills { skill<String, String>("s", "stub") { tools("memory_read") } }
            }
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory_read"))
        }
    }

    @Test
    fun `unaryPlus blocks externally-built reserved-name ToolDef`() {
        try {
            val def = ToolDef(name = "memory_read", description = "x", executor = { _ -> "x" })
            agent<String, String>("a") {
                tools { +def }
                skills { skill<String, String>("s", "stub") { tools("memory_read") } }
            }
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory_read"))
        }
    }

    @Test
    fun `typed tool builder blocks reserved names`() {
        try {
            agent<String, String>("a") {
                tools { tool<GreetArgs, GreetResult>("memory_read", "x") { GreetResult("y") } }
                skills { skill<String, String>("s", "stub") { tools("memory_read") } }
            }
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("memory_read"))
        }
    }

    @Test
    fun `memory(bank) succeeds and registers the three reserved names (regression)`() {
        val a = agent<String, String>("memOk") {
            memory(MemoryBank())
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        assertTrue("memory_read" in a.toolMap)
        assertTrue("memory_write" in a.toolMap)
        assertTrue("memory_search" in a.toolMap)
    }

    @Test
    fun `non-reserved custom tool names work fine (regression)`() {
        val a = agent<String, String>("ok") {
            tools {
                tool("foo", "x") { _ -> "f" }
                tool("memory_zzz", "x") { _ -> "z" }   // not in reserved set
            }
            skills { skill<String, String>("s", "stub") { tools("foo", "memory_zzz") } }
        }
        assertEquals("f", a.toolMap["foo"]!!.executor(emptyMap()))
        assertEquals("z", a.toolMap["memory_zzz"]!!.executor(emptyMap()))
    }
}
