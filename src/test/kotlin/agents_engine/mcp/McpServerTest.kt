package agents_engine.mcp

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

@Generable("A person being greeted")
data class GreetRequest(
    @Guide("Name to greet") val name: String,
    @Guide("Greeting language") val language: String = "en",
)

class McpServerTest {

    private val toStop = mutableListOf<() -> Unit>()
    private val toClose = mutableListOf<AutoCloseable>()

    @AfterTest fun cleanup() {
        toClose.forEach { runCatching { it.close() } }
        toStop.forEach { runCatching { it() } }
    }

    private fun start(server: McpServer): McpServer = server.start().also { toStop.add { it.stop() } }

    @Test
    fun `tools list returns the exposed skill`() {
        val a = agent<String, String>("greeter") {
            skills {
                skill<String, String>("greet", "Greets a person by input string") {
                    implementedBy { name -> "Hello, $name!" }
                }
            }
        }

        val server = start(McpServer.from(a) { expose("greet") })
        val client = McpClient.connect(server.url).also { toClose.add(it) }

        val tools = client.toolDefs()
        assertEquals(setOf("greet"), tools.map { it.name }.toSet())
        assertTrue(
            tools.single().description.contains("Greets a person", ignoreCase = true),
            "got: ${tools.single().description}",
        )
    }

    @Test
    fun `tools call invokes the skill and returns text content`() {
        val a = agent<String, String>("greeter") {
            skills {
                skill<String, String>("greet", "Greet by name") {
                    implementedBy { name -> "Hello, $name!" }
                }
            }
        }
        val server = start(McpServer.from(a) { expose("greet") })
        val client = McpClient.connect(server.url).also { toClose.add(it) }

        val result = client.call("greet", mapOf("input" to "world"))
        assertEquals("Hello, world!", result)
    }

    @Test
    fun `Generable IN gets schema generated and args deserialized correctly`() {
        val a = agent<GreetRequest, String>("typed-greeter") {
            skills {
                skill<GreetRequest, String>("greet", "Greet typed") {
                    implementedBy { req -> "[${req.language}] Hello, ${req.name}!" }
                }
            }
        }
        val server = start(McpServer.from(a) { expose("greet") })
        val client = McpClient.connect(server.url).also { toClose.add(it) }

        val tools = client.toolDefs()
        val desc = tools.single().description
        assertTrue(desc.contains("name"), "schema field 'name' should appear in description: $desc")
        assertTrue(desc.contains("language"), "schema field 'language' should appear: $desc")

        val result = client.call("greet", mapOf("name" to "Konstantin", "language" to "ru"))
        assertEquals("[ru] Hello, Konstantin!", result)
    }

    @Test
    fun `multiple expose calls register multiple tools`() {
        val a = agent<String, String>("multi") {
            skills {
                skill<String, String>("upper", "uppercase") { implementedBy { it.uppercase() } }
                skill<String, String>("lower", "lowercase") { implementedBy { it.lowercase() } }
                skill<String, String>("hidden", "not exposed") { implementedBy { "secret" } }
            }
        }
        val server = start(McpServer.from(a) { expose("upper"); expose("lower") })
        val client = McpClient.connect(server.url).also { toClose.add(it) }

        val toolNames = client.toolDefs().map { it.name }.toSet()
        assertEquals(setOf("upper", "lower"), toolNames)
        assertEquals("HI", client.call("upper", mapOf("input" to "hi")))
        assertEquals("hi", client.call("lower", mapOf("input" to "HI")))
    }

    @Test
    fun `validation - agentic skill is rejected at start`() {
        val a = agent<String, String>("agentic-attempt") {
            model { ollama("noop"); host = "localhost"; port = 11434 }
            skills {
                skill<String, String>("agentic", "agentic skill") {
                    tools()  // marks as agentic
                }
            }
        }
        try {
            McpServer.from(a) { expose("agentic") }.start()
            fail("expected validation error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("agentic"), "got: ${e.message}")
        }
    }

    @Test
    fun `validation - unknown skill name is rejected at start`() {
        val a = agent<String, String>("known") {
            skills {
                skill<String, String>("real", "real skill") { implementedBy { "ok" } }
            }
        }
        try {
            McpServer.from(a) { expose("nope") }.start()
            fail("expected validation error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("nope"), "got: ${e.message}")
        }
    }

    @Test
    fun `validation - empty expose list is rejected at start`() {
        val a = agent<String, String>("empty") {
            skills {
                skill<String, String>("only", "only skill") { implementedBy { "ok" } }
            }
        }
        try {
            McpServer.from(a) { /* no expose() */ }.start()
            fail("expected validation error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("expose"), "got: ${e.message}")
        }
    }

    @Test
    fun `non-string non-Generable IN is rejected at start`() {
        val a = agent<Int, String>("int-input") {
            skills {
                skill<Int, String>("inc", "increment") { implementedBy { (it + 1).toString() } }
            }
        }
        try {
            McpServer.from(a) { expose("inc") }.start()
            fail("expected validation error")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Int") || e.message!!.contains("unsupported"), "got: ${e.message}")
        }
    }

    @Test
    fun `round-trip via our own McpClient end-to-end`() {
        val a = agent<String, String>("echo") {
            skills {
                skill<String, String>("echo", "echo input") { implementedBy { "echo: $it" } }
            }
        }
        val server = start(McpServer.from(a) { expose("echo") })
        val client = McpClient.connect(server.url).also { toClose.add(it) }

        repeat(3) { i ->
            assertEquals("echo: msg-$i", client.call("echo", mapOf("input" to "msg-$i")))
        }
    }
}
