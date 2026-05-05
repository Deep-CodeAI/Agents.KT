package agents_engine.mcp

import agents_engine.core.agent
import org.junit.jupiter.api.Tag
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentFibonacciMcpTest {

    private var server: MockMcpServer? = null
    @AfterTest fun stop() { server?.stop() }

    @Tag("live-llm")
    @Test
    fun `agent uses fib_next via MCP to generate the canonical sequence`() {
        val s = FibonacciMcpServer.start().also { server = it }
        val mcp = McpClient.connect(s.url)
        val mcpTools = mcp.toolDefs()

        val fib = agent<String, Int>("fibonacci-mcp") {
            prompt(
                """You generate Fibonacci numbers by calling exactly one tool.
                |
                |PROCEDURE — every time, no exceptions:
                |1. Call fib_next with empty arguments {}.
                |2. Reply with ONLY the number returned, nothing else.
                """.trimMargin()
            )
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools { mcpTools.forEach { +it } }
            budget { maxTurns = 5 }
            skills {
                skill<String, Int>("fib", "Generate next Fibonacci number via fib_next") {
                    @Suppress("DEPRECATION") // MCP tools discovered at runtime — names aren't compile-time refs
                    tools(*mcpTools.map { it.name }.toTypedArray())
                    transformOutput { it.trim().toIntOrNull() ?: Regex("\\d+").find(it)?.value?.toInt() ?: error("No int in: $it") }
                }
            }
        }

        assertEquals(1, fib("go"))
        assertEquals(1, fib("go"))
        assertEquals(2, fib("go"))
        assertEquals(3, fib("go"))
        assertEquals(5, fib("go"))
    }
}
