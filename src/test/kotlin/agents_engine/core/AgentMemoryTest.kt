package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ToolCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgentMemoryTest {

    // --- MemoryBank standalone ---

    @Test
    fun `bank read returns empty string for unknown key`() {
        val bank = MemoryBank()
        assertEquals("", bank.read("anything"))
    }

    @Test
    fun `bank write then read returns content`() {
        val bank = MemoryBank()
        bank.write("agent-1", "hello")
        assertEquals("hello", bank.read("agent-1"))
    }

    @Test
    fun `bank write overwrites previous content`() {
        val bank = MemoryBank()
        bank.write("a", "first")
        bank.write("a", "second")
        assertEquals("second", bank.read("a"))
    }

    @Test
    fun `bank isolates keys`() {
        val bank = MemoryBank()
        bank.write("a", "data-a")
        bank.write("b", "data-b")
        assertEquals("data-a", bank.read("a"))
        assertEquals("data-b", bank.read("b"))
    }

    @Test
    fun `bank maxLines truncates keeping last lines`() {
        val bank = MemoryBank(maxLines = 3)
        bank.write("a", "line1\nline2\nline3\nline4\nline5")
        assertEquals("line3\nline4\nline5", bank.read("a"))
    }

    @Test
    fun `bank maxLines does not truncate when within limit`() {
        val bank = MemoryBank(maxLines = 10)
        bank.write("a", "one\ntwo")
        assertEquals("one\ntwo", bank.read("a"))
    }

    @Test
    fun `bank entries returns all stored keys`() {
        val bank = MemoryBank()
        bank.write("x", "1")
        bank.write("y", "2")
        assertEquals(setOf("x", "y"), bank.entries().keys)
    }

    // --- DSL: agent + memory bank ---

    @Test
    fun `memory block accepts a bank`() {
        val bank = MemoryBank()
        val a = agent<String, String>("reviewer") {
            memory(bank)
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }

        assertNotNull(a.memoryBank)
    }

    @Test
    fun `memory_read and memory_write tools are auto-injected`() {
        val bank = MemoryBank()
        val a = agent<String, String>("reviewer") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        assertTrue(a.toolMap.containsKey("memory_read"), "memory_read should be auto-injected")
        assertTrue(a.toolMap.containsKey("memory_write"), "memory_write should be auto-injected")
    }

    @Test
    fun `auto-injected tools do not overwrite user-defined tools`() {
        val bank = MemoryBank()
        val a = agent<String, String>("a") {
            tools { tool("memory_read") { _ -> "custom" } }
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools("memory_read") } }
        }

        assertEquals("custom", a.toolMap["memory_read"]!!.executor(emptyMap()))
    }

    // --- Tools read/write through the bank ---

    @Test
    fun `memory_read returns empty when bank has no data for agent`() {
        val bank = MemoryBank()
        val a = agent<String, String>("fresh") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        assertEquals("", a.toolMap["memory_read"]!!.executor(emptyMap()))
    }

    @Test
    fun `memory_write stores and memory_read retrieves via bank`() {
        val bank = MemoryBank()
        val a = agent<String, String>("writer") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        a.toolMap["memory_write"]!!.executor(mapOf("content" to "Pattern: always check nulls"))
        assertEquals("Pattern: always check nulls", a.toolMap["memory_read"]!!.executor(emptyMap()))
    }

    @Test
    fun `memory tools use agent name as bank key`() {
        val bank = MemoryBank()
        val a = agent<String, String>("my-agent") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        a.toolMap["memory_write"]!!.executor(mapOf("content" to "data"))
        assertEquals("data", bank.read("my-agent"))
    }

    @Test
    fun `bank maxLines applies through memory_write tool`() {
        val bank = MemoryBank(maxLines = 2)
        val a = agent<String, String>("a") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        a.toolMap["memory_write"]!!.executor(mapOf("content" to "a\nb\nc\nd"))
        assertEquals("c\nd", a.toolMap["memory_read"]!!.executor(emptyMap()))
    }

    // --- Two agents share a bank ---

    @Test
    fun `two agents share the same bank with isolated keys`() {
        val bank = MemoryBank()

        val a1 = agent<String, String>("agent-a") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }
        val a2 = agent<String, String>("agent-b") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        a1.toolMap["memory_write"]!!.executor(mapOf("content" to "from-a"))
        a2.toolMap["memory_write"]!!.executor(mapOf("content" to "from-b"))

        assertEquals("from-a", a1.toolMap["memory_read"]!!.executor(emptyMap()))
        assertEquals("from-b", a2.toolMap["memory_read"]!!.executor(emptyMap()))
    }

    // --- Pre-seeded bank ---

    @Test
    fun `agent reads pre-seeded bank content`() {
        val bank = MemoryBank()
        bank.write("reviewer", "Known pattern: prefer val over var")

        val a = agent<String, String>("reviewer") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        assertEquals("Known pattern: prefer val over var", a.toolMap["memory_read"]!!.executor(emptyMap()))
    }

    // --- memory_search ---

    @Test
    fun `memory_search is auto-injected`() {
        val bank = MemoryBank()
        val a = agent<String, String>("a") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        assertTrue(a.toolMap.containsKey("memory_search"), "memory_search should be auto-injected")
    }

    @Test
    fun `memory_search returns matching lines`() {
        val bank = MemoryBank()
        bank.write("a", "pattern: prefer val\nbug: NPE in parser\npattern: use data classes\nrefactor: extract method")

        val a = agent<String, String>("a") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        val result = a.toolMap["memory_search"]!!.executor(mapOf("query" to "pattern")) as String
        assertTrue(result.contains("prefer val"))
        assertTrue(result.contains("data classes"))
        assertFalse(result.contains("NPE in parser"))
        assertFalse(result.contains("extract method"))
    }

    @Test
    fun `memory_search is case-insensitive`() {
        val bank = MemoryBank()
        bank.write("a", "Error: timeout\ninfo: all good\nERROR: disk full")

        val a = agent<String, String>("a") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        val result = a.toolMap["memory_search"]!!.executor(mapOf("query" to "error")) as String
        assertTrue(result.contains("timeout"))
        assertTrue(result.contains("disk full"))
        assertFalse(result.contains("all good"))
    }

    @Test
    fun `memory_search returns empty string when no matches`() {
        val bank = MemoryBank()
        bank.write("a", "some content here")

        val a = agent<String, String>("a") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        val result = a.toolMap["memory_search"]!!.executor(mapOf("query" to "nonexistent")) as String
        assertEquals("", result)
    }

    @Test
    fun `memory_search returns empty string when memory is empty`() {
        val bank = MemoryBank()
        val a = agent<String, String>("a") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        val result = a.toolMap["memory_search"]!!.executor(mapOf("query" to "anything")) as String
        assertEquals("", result)
    }

    @Test
    fun `memory_search accepts query from any arg key`() {
        val bank = MemoryBank()
        bank.write("a", "line one\nline two\nline three")

        val a = agent<String, String>("a") {
            memory(bank)
            model { ollama("test"); client = ModelClient { _ -> LlmResponse.Text("done") } }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        val result = a.toolMap["memory_search"]!!.executor(mapOf("search" to "two")) as String
        assertTrue(result.contains("line two"))
    }

    @Test
    fun `memory_search is auto-available in agentic loop`() {
        val bank = MemoryBank()
        bank.write("searcher", "pattern: use val\nbug: NPE\npattern: data classes")

        val toolEvents = mutableListOf<String>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("memory_search", mapOf("query" to "pattern")))))
        responses.add(LlmResponse.Text("found patterns"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("searcher") {
            memory(bank)
            model { ollama("test"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
            onToolUse { name, _, _ -> toolEvents.add(name) }
        }

        val result = a("find patterns")
        assertEquals(listOf("memory_search"), toolEvents)
        assertTrue(result.contains("found patterns"))
    }

    // --- Integration: agentic loop ---

    @Test
    fun `agent reads and writes memory during agentic loop`() {
        val bank = MemoryBank()
        bank.write("memo-agent", "Known: user prefers dark mode")

        val toolEvents = mutableListOf<String>()

        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("memory_read", emptyMap()))))
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall("memory_write", mapOf("content" to "Known: user prefers dark mode\nKnown: user uses Kotlin")))))
        responses.add(LlmResponse.Text("done"))

        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("memo-agent") {
            memory(bank)
            model { ollama("test"); client = mock }
            skills { skill<String, String>("work", "do work") { tools() } }
            onToolUse { name, _, _ -> toolEvents.add(name) }
        }

        a("do something")

        assertEquals(listOf("memory_read", "memory_write"), toolEvents)
        val content = bank.read("memo-agent")
        assertTrue(content.contains("user uses Kotlin"))
        assertTrue(content.contains("user prefers dark mode"))
    }
}
