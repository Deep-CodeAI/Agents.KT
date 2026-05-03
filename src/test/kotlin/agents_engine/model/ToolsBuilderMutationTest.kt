package agents_engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Mutation-killer tests for ToolsBuilder.tool / unaryPlus — see #843.
//
// Existing reserved-name + duplicate-name tests build a full agent — but
// `Agent.registerTool` has its OWN reserved-name check, so L82/L93/L105
// mutations in `ToolsBuilder` were equivalent through that path. These
// tests call `ToolsBuilder` directly so the in-builder guards are the
// only thing standing between the call and success.
class ToolsBuilderMutationTest {

    // Basic 3-arg overload (L67-74) — tool(name, description, executor)

    @Test
    fun `3-arg overload directly rejects reserved memory_search name`() {
        // Existing ReservedMemoryToolNamesTest goes through agent {} which has its
        // own RESERVED_MEMORY_TOOL_NAMES check in Agent.registerTool — that path
        // makes the in-builder check equivalent. Direct call surfaces the diff.
        val builder = ToolsBuilder()
        var threw = false
        try {
            builder.tool("memory_search", "x") { _ -> "x" }
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("memory_search"))
            assertTrue(e.message!!.contains("reserved", ignoreCase = true))
        }
        assertTrue(threw, "ToolsBuilder.tool (3-arg) must reject reserved name in-builder")
    }

    // 4-arg overload (L76-90) — tool(name, description, onError, executor)

    @Test
    fun `4-arg overload directly rejects reserved memory_read name`() {
        val builder = ToolsBuilder()
        var threw = false
        try {
            builder.tool(
                name = "memory_read",
                description = "x",
                onError = { },
                executor = { _ -> "x" },
            )
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("memory_read"))
            assertTrue(e.message!!.contains("reserved", ignoreCase = true))
        }
        assertTrue(threw, "ToolsBuilder.tool (4-arg) must reject reserved name in-builder, not delegate to Agent")
    }

    @Test
    fun `4-arg overload directly rejects duplicate name`() {
        val builder = ToolsBuilder()
        builder.tool("dup", "first") { _ -> "1" }
        var threw = false
        try {
            builder.tool(
                name = "dup",
                description = "second",
                onError = { },
                executor = { _ -> "2" },
            )
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("dup"))
            assertTrue(e.message!!.contains("already defined", ignoreCase = true))
        }
        assertTrue(threw, "duplicate name must be rejected in-builder")
    }

    // Block-form overload (L92-102) — tool(name) { ... }

    @Test
    fun `block-form overload directly rejects reserved memory_write name`() {
        val builder = ToolsBuilder()
        var threw = false
        try {
            builder.tool("memory_write") {
                description("x")
                executor { _ -> "x" }
            }
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("memory_write"))
            assertTrue(e.message!!.contains("reserved", ignoreCase = true))
        }
        assertTrue(threw, "block-form tool() must reject reserved name in-builder")
    }

    @Test
    fun `block-form overload directly rejects duplicate name`() {
        val builder = ToolsBuilder()
        builder.tool("dup", "first") { _ -> "1" }
        var threw = false
        try {
            builder.tool("dup") {
                description("second")
                executor { _ -> "2" }
            }
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("dup"))
            assertTrue(e.message!!.contains("already defined", ignoreCase = true))
        }
        assertTrue(threw, "duplicate name must be rejected in-builder (block form)")
    }

    // unaryPlus operator (L104-111) — `+toolDef`

    @Test
    fun `unaryPlus directly rejects reserved name`() {
        val builder = ToolsBuilder()
        var threw = false
        try {
            with(builder) {
                +ToolDef(name = "memory_read", description = "x", executor = { _ -> "x" })
            }
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("memory_read"))
            assertTrue(e.message!!.contains("reserved", ignoreCase = true))
        }
        assertTrue(threw, "unaryPlus must reject reserved name in-builder")
    }

    @Test
    fun `unaryPlus directly rejects duplicate name`() {
        val builder = ToolsBuilder()
        builder.tool("dup", "first") { _ -> "1" }
        var threw = false
        try {
            with(builder) {
                +ToolDef(name = "dup", description = "second", executor = { _ -> "2" })
            }
        } catch (e: IllegalArgumentException) {
            threw = true
            assertTrue(e.message!!.contains("dup"))
            assertTrue(e.message!!.contains("already defined", ignoreCase = true))
        }
        assertTrue(threw, "duplicate name must be rejected in-builder (unaryPlus)")
    }

    // Sanity: positive paths keep working (regression guards for above mutations).

    @Test
    fun `4-arg overload accepts a non-reserved unique name`() {
        val builder = ToolsBuilder()
        builder.tool(
            name = "doStuff",
            description = "ok",
            onError = { },
            executor = { _ -> "ok" },
        )
        assertEquals(1, builder.defs.size)
        assertEquals("doStuff", builder.defs.single().name)
    }

    @Test
    fun `block-form overload accepts a non-reserved unique name`() {
        val builder = ToolsBuilder()
        builder.tool("doStuff") {
            description("ok")
            executor { _ -> "ok" }
        }
        assertEquals(1, builder.defs.size)
    }

    @Test
    fun `unaryPlus accepts a non-reserved unique ToolDef`() {
        val builder = ToolsBuilder()
        with(builder) {
            +ToolDef(name = "doStuff", description = "ok", executor = { _ -> "ok" })
        }
        assertEquals(1, builder.defs.size)
    }
}
