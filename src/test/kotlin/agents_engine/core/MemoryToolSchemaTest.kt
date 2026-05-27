package agents_engine.core

import agents_engine.model.BuiltInToolWireSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #2379 — built-in memory_* tools now declare typed schemas. Before
 * this issue, every memory_* tool relied on the permissive empty-
 * properties fallback in the provider clients (`additionalProperties:
 * true`), forcing the LLM to infer args from the description prose.
 * Post-fix, the wire format carries either a real `argsType` schema
 * (memory_write / memory_search) or an explicit closed empty-object
 * schema (memory_read).
 *
 * This test pins the wire format so a regression silently re-introducing
 * the untyped path is caught at unit-test time.
 */
class MemoryToolSchemaTest {

    @Test
    fun `memory_read declares closed empty-object parameters schema`() {
        val tools = buildMemoryTools(MemoryBank(), "agent")
        val read = tools.single { it.name == "memory_read" }

        val schema = read.parametersSchemaJson
        assertNotNull(schema, "memory_read must declare its own (no-args) schema, not fall back")
        assertTrue(""""additionalProperties":false""" in schema, "must be closed: $schema")
        assertTrue(""""properties":{}""" in schema, "must have no properties: $schema")
    }

    @Test
    fun `memory_write declares typed argsType backed by MemoryWriteArgs`() {
        val tools = buildMemoryTools(MemoryBank(), "agent")
        val write = tools.single { it.name == "memory_write" }

        assertEquals(MemoryWriteArgs::class, write.argsType, "memory_write must carry typed args (#2379)")
    }

    @Test
    fun `memory_search declares typed argsType backed by MemorySearchArgs`() {
        val tools = buildMemoryTools(MemoryBank(), "agent")
        val search = tools.single { it.name == "memory_search" }

        assertEquals(MemorySearchArgs::class, search.argsType, "memory_search must carry typed args (#2379)")
    }

    @Test
    fun `every built-in memory tool emits a non-permissive wire schema on every provider client`() {
        // AC (#2379): wire-format fixtures for each of the three provider
        // clients (Ollama / OpenAI / Claude) confirm no memory_* tool falls
        // through to the legacy `additionalProperties:true` fallback.
        val tools = buildMemoryTools(MemoryBank(), "agent")

        BuiltInToolWireSchema.assertNoPermissiveFallback(tools)
        // Sanity: all three tools render in every provider body.
        BuiltInToolWireSchema.assertAllContain(tools, """"name":"memory_read"""")
        BuiltInToolWireSchema.assertAllContain(tools, """"name":"memory_write"""")
        BuiltInToolWireSchema.assertAllContain(tools, """"name":"memory_search"""")
    }

    @Test
    fun `typed memory_write executor still works through Map-shaped path`() {
        val bank = MemoryBank()
        val write = buildMemoryTools(bank, "agent").single { it.name == "memory_write" }

        // The agentic loop hands Map-shaped args to the executor; the
        // typed lambda inside reconstructs MemoryWriteArgs via
        // constructFromMap and persists.
        val result = write.executor(mapOf("content" to "hello world"))

        assertEquals("ok", result)
        assertEquals("hello world", bank.read("agent"))
    }

    @Test
    fun `typed memory_search executor still works through Map-shaped path`() {
        val bank = MemoryBank()
        bank.write("agent", "alpha line\nbeta line\nalpha second")
        val search = buildMemoryTools(bank, "agent").single { it.name == "memory_search" }

        val result = search.executor(mapOf("query" to "alpha")) as String

        assertEquals("alpha line\nalpha second", result)
    }
}
