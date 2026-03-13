package agents_engine.model

import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.assertThrows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AgenticLoopTest {

    @Test
    fun `model client is called when agentic skill is invoked`() {
        var callCount = 0
        val mock = ModelClient { _ -> callCount++; LlmResponse.Text("result") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        a("input")
        assertEquals(1, callCount)
    }

    @Test
    fun `model text response becomes agent output`() {
        val mock = ModelClient { _ -> LlmResponse.Text("hello world") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { tools() } }
        }

        assertEquals("hello world", a("input"))
    }

    @Test
    fun `initial messages include system prompt and user input`() {
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); LlmResponse.Text("done") }

        val a = agent<String, String>("a") {
            prompt("You are a helpful assistant.")
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "desc") { tools() } }
        }

        a("user task")

        val msgs = captured.single()
        assertEquals("system", msgs[0].role)
        assertTrue(msgs[0].content.contains("You are a helpful assistant."))
        assertEquals("user", msgs[1].role)
        assertEquals("user task", msgs[1].content)
    }

    @Test
    fun `tool call from model is executed`() {
        var toolExecuted = false
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = mapOf("name" to "world")))))
        responses.add(LlmResponse.Text("done"))

        val mock = ModelClient { _ -> responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools { tool("greet") { _ -> toolExecuted = true; "Hi!" } }
            skills { skill<String, String>("s", "s") { tools("greet") } }
        }

        a("input")
        assertTrue(toolExecuted)
    }

    @Test
    fun `tool result is injected as tool message in next turn`() {
        val allMessages = mutableListOf<List<LlmMessage>>()
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "reverse", arguments = mapOf("text" to "hello")))))
        responses.add(LlmResponse.Text("olleh"))
        val mock = ModelClient { msgs -> allMessages.add(msgs.toList()); responses.removeFirst() }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools { tool("reverse") { args -> args["text"].toString().reversed() } }
            skills { skill<String, String>("s", "s") { tools("reverse") } }
        }

        val result = a("hello")
        assertEquals("olleh", result)
        val secondCallMsgs = allMessages[1]
        val toolMsg = secondCallMsgs.find { it.role == "tool" }
        assertNotNull(toolMsg)
        assertEquals("olleh", toolMsg!!.content)
    }

    @Test
    fun `budget maxTurns stops loop with exception`() {
        val mock = ModelClient { _ ->
            LlmResponse.ToolCalls(listOf(ToolCall(name = "noop", arguments = emptyMap())))
        }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            budget { maxTurns = 3 }
            tools { tool("noop") { _ -> "ok" } }
            skills { skill<String, String>("s", "s") { tools("noop") } }
        }

        assertThrows<BudgetExceededException> { a("input") }
    }

    @Test
    fun `system prompt includes tool descriptions when skill has tools`() {
        val captured = mutableListOf<List<LlmMessage>>()
        val mock = ModelClient { msgs -> captured.add(msgs.toList()); LlmResponse.Text("done") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            tools { tool("greet", "Greet someone by name") { _ -> "Hi!" } }
            skills { skill<String, String>("s", "s") { tools("greet") } }
        }

        a("task")

        val systemMsg = captured.single().first { it.role == "system" }
        assertTrue(systemMsg.content.contains("greet"))
        assertTrue(systemMsg.content.contains("Greet someone by name"))
    }

    @Test
    fun `calculator agent solves nested arithmetic via tool chaining`() {
        fun num(args: Map<String, Any?>, key: String) = args[key].toString().toDouble()

        data class ToolUse(val name: String, val args: Map<String, Any?>, val result: Any?)
        val toolUses = mutableListOf<ToolUse>()

        val a = agent<String, String>("calculator") {
            prompt("You are a calculator. Use the provided tools to evaluate expressions step by step.")
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                tool("add",      "Add two numbers. Args: a, b")             { args -> num(args, "a") + num(args, "b") }
                tool("subtract", "Subtract b from a. Args: a, b")           { args -> num(args, "a") - num(args, "b") }
                tool("multiply", "Multiply two numbers. Args: a, b")        { args -> num(args, "a") * num(args, "b") }
                tool("divide",   "Divide a by b. Args: a, b")               { args -> num(args, "a") / num(args, "b") }
                tool("power",    "Raise base to exponent. Args: base, exp") { args -> Math.pow(num(args, "base"), num(args, "exp")) }
            }
            skills { skill<String, String>("solve", "Evaluate arithmetic expressions using tools") {
                tools("add", "subtract", "multiply", "divide", "power")
            }}
            onToolUse { name, args, result ->
                toolUses.add(ToolUse(name, args, result))
                println("  $name(${args.values.joinToString(", ")}) = $result")
            }
        }

        // ((15 + 35) / 2)^2  =  (50 / 2)^2  =  25^2  =  625
        val result = a("Calculate ((15 + 35) / 2)^2")
        println(result)
        assertTrue(result.contains("625"), "Expected 625 in result, got: $result")

        assertEquals(3, toolUses.size, "Expected exactly 3 tool calls")

        val add = toolUses[0]
        assertEquals("add", add.name)
        assertEquals(15.0, add.args["a"].toString().toDouble())
        assertEquals(35.0, add.args["b"].toString().toDouble())
        assertEquals(50.0, add.result)

        val divide = toolUses[1]
        assertEquals("divide", divide.name)
        assertEquals(50.0, divide.args["a"].toString().toDouble())
        assertEquals(2.0,  divide.args["b"].toString().toDouble())
        assertEquals(25.0, divide.result)

        val power = toolUses[2]
        assertEquals("power", power.name)
        assertEquals(25.0, power.args["base"].toString().toDouble())
        assertEquals(2.0,  power.args["exp"].toString().toDouble())
        assertEquals(625.0, power.result)
    }

    @Test
    fun `agent returns Int via skill output parser`() {
        fun num(args: Map<String, Any?>, key: String) = args[key].toString().toDouble()

        data class ToolUse(val name: String, val args: Map<String, Any?>, val result: Any?)
        val toolUses = mutableListOf<ToolUse>()

        val compute = agent<String, Int>("calculator") {
            prompt("You are a calculator. You MUST use the provided tools for every arithmetic operation — never compute mentally. After all tool calls, reply with ONLY the final number.")
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                tool("add",    "Add two numbers. Args: a, b")             { args -> num(args, "a") + num(args, "b") }
                tool("divide", "Divide a by b. Args: a, b")               { args -> num(args, "a") / num(args, "b") }
                tool("power",  "Raise base to exponent. Args: base, exp") { args -> Math.pow(num(args, "base"), num(args, "exp")) }
            }
            skills { skill<String, Int>("solve", "Evaluate arithmetic expressions using tools") {
                tools("add", "divide", "power")
                parseOutput { it.trim().toIntOrNull() ?: Regex("-?\\d+").find(it)?.value?.toInt() ?: error("No integer in: $it") }
            }}
            onToolUse { name, args, result -> toolUses.add(ToolUse(name, args, result)) }
        }

        // ((15 + 35) / 2)^2 = 625
        val result: Int = compute("Calculate ((15 + 35) / 2)^2")
        assertEquals(625, result)

        assertTrue(toolUses.isNotEmpty(), "Expected at least one tool call")
        val power = toolUses.last()
        assertEquals("power", power.name)
        assertEquals(625.0, power.result)
    }

    @Test
    fun `agent pipeline returns Int result`() {
        fun num(args: Map<String, Any?>, key: String) = args[key].toString().toDouble()

        val compute = agent<String, String>("calculator") {
            prompt("You are a calculator. Use the provided tools to evaluate the expression, then reply with ONLY the final number — no explanation.")
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                tool("add",    "Add two numbers. Args: a, b")          { args -> num(args, "a") + num(args, "b") }
                tool("divide", "Divide a by b. Args: a, b")            { args -> num(args, "a") / num(args, "b") }
                tool("power",  "Raise base to exponent. Args: base, exp") { args -> Math.pow(num(args, "base"), num(args, "exp")) }
            }
            skills { skill<String, String>("solve", "Evaluate arithmetic expressions using tools") {
                tools("add", "divide", "power")
            }}
        }

        val asInt = agent<String, Int>("as-int") {
            skills { skill<String, Int>("parse", "Parse integer from text") {
                implementedBy { it.trim().toIntOrNull() ?: Regex("-?\\d+").find(it)!!.value.toInt() }
            }}
        }

        // ((15 + 35) / 2)^2 = 625
        val result: Int = (compute then asInt)("Calculate ((15 + 35) / 2)^2")
        assertEquals(625, result)
    }

    @Test
    fun `lambda skill still works when agent also has model config`() {
        val mock = ModelClient { _ -> error("Should not be called") }

        val a = agent<String, String>("a") {
            model { ollama("llama3"); client = mock }
            skills { skill<String, String>("s", "s") { implementedBy { "lambda: $it" } } }
        }

        assertEquals("lambda: hello", a("hello"))
    }
}
