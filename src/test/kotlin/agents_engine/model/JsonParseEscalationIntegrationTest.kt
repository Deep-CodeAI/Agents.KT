package agents_engine.model

import agents_engine.core.agent
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests: agent parses malformed JSON via a tool, fixer agent escalates,
 * the LLM sees the error and retries with corrected data.
 *
 * Flow:
 *   1. LLM calls calculateNumberOfKeys(json = malformed)
 *   2. Tool throws (can't parse)
 *   3. Fixer agent sees the error, calls escalate() tool
 *   4. Escalation error is fed back to the main LLM
 *   5. Main LLM retries calculateNumberOfKeys(json = corrected JSON)
 *   6. Tool succeeds → LLM returns the answer
 *
 * Run with: ./gradlew integrationTest --tests "agents_engine.model.JsonParseEscalationIntegrationTest"
 */
class JsonParseEscalationIntegrationTest {

    private fun buildCalculateNumberOfKeysTool(): (Map<String, Any?>) -> Any? = { args ->
        val json = args["json"]?.toString()
            ?: throw IllegalArgumentException("Missing 'json' argument. Pass the JSON as: json = '{\"key\":\"value\"}'")
        val trimmed = json.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            throw IllegalArgumentException("Invalid JSON: must be an object starting with { and ending with }")
        }
        val keyPattern = Regex(""""([^"]+)"\s*:""")
        val keys = keyPattern.findAll(trimmed).map { it.groupValues[1] }.toList()
        if (keys.isEmpty()) {
            throw IllegalArgumentException("No valid keys found — JSON may have unquoted keys. All keys must be double-quoted.")
        }
        keys.size
    }

    /**
     * Unit test with mock LLM: deterministic end-to-end escalation flow.
     * Fixer agent (mock) calls escalate tool → error fed back → LLM retries with corrected JSON.
     */
    @Test
    fun `escalation feeds error to LLM which retries with corrected JSON`() {
        // Fixer agent: mock LLM calls escalate
        val fixerResponses = ArrayDeque<LlmResponse>()
        fixerResponses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("escalate", mapOf(
                "reason" to """Malformed JSON: unquoted keys. Corrected: {"name":"world","age":30,"active":true}""",
                "severity" to "MEDIUM"
            ))
        )))
        val fixerMock = ModelClient { _ -> fixerResponses.removeFirst() }

        val fixer = agent<String, String>("json-fixer") {
            prompt("Analyze the malformed JSON error. Call escalate with the reason and corrected JSON.")
            model { ollama("test"); client = fixerMock }
            skills { skill<String, String>("fix", "Fix JSON") { tools("escalate") } }
        }

        // Main agent LLM: first calls with malformed JSON, then retries with corrected
        data class ToolUse(val name: String, val args: Map<String, Any?>, val result: Any?)
        val toolUses = mutableListOf<ToolUse>()

        val mainResponses = ArrayDeque<LlmResponse>()
        // Turn 1: LLM calls with malformed JSON (unquoted keys)
        mainResponses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("calculateNumberOfKeys", mapOf("json" to "{name: world, age: 30, active: true}"))
        )))
        // Turn 2: LLM sees escalation error, retries with corrected JSON from error message
        mainResponses.add(LlmResponse.ToolCalls(listOf(
            ToolCall("calculateNumberOfKeys", mapOf("json" to """{"name":"world","age":30,"active":true}"""))
        )))
        // Turn 3: LLM returns the answer
        mainResponses.add(LlmResponse.Text("3"))

        val captured = mutableListOf<List<LlmMessage>>()
        val mainMock = ModelClient { msgs -> captured.add(msgs.toList()); mainResponses.removeFirst() }

        val a = agent<String, String>("json-agent") {
            model { ollama("test"); client = mainMock }
            tools {
                tool("calculateNumberOfKeys",
                    "Count top-level keys in a JSON object. Args: json (valid JSON string)",
                    buildCalculateNumberOfKeysTool()
                )
            }
            onToolError("calculateNumberOfKeys") {
                executionError { _ -> fix(agent = fixer, retries = 1) }
            }
            skills { skill<String, String>("solve", "Analyze JSON") {
                tools("calculateNumberOfKeys")
            }}
            onToolUse { name, args, result ->
                toolUses.add(ToolUse(name, args, result))
            }
        }

        val result = a("How many keys in {name: world, age: 30, active: true}?")
        assertEquals("3", result)

        // Turn 1: malformed JSON → tool threw → fixer escalated → error fed back to LLM
        val turn2Msgs = captured[1]
        val errorToolMsg = turn2Msgs.last { it.role == "tool" }
        assertTrue(errorToolMsg.content.contains("ERROR"), "Escalation error should be in tool message")
        assertTrue(errorToolMsg.content.contains("Malformed JSON"), "Reason should be in error: ${errorToolMsg.content}")

        // Turn 1 result is the escalation error string, Turn 2 result is 3
        assertEquals(2, toolUses.size, "Both calls should fire onToolUse")
        assertTrue(toolUses[0].result.toString().contains("ERROR"), "First call should be escalation error")
        assertEquals(3, toolUses[1].result, "Second call with corrected JSON has 3 keys")
    }

    /**
     * Live LLM integration test: fixer agent uses real LLM to analyze the error
     * and call escalate(). Main agent sees the error and retries.
     */
    @Tag("live-llm")
    @Test
    fun `live LLM agent retries with corrected JSON after fixer escalates`() {
        val fixer = agent<String, String>("json-fixer") {
            prompt(
                "You receive a string that was supposed to be valid JSON but failed to parse. " +
                "Analyze the error. Call the escalate tool with a reason that includes the corrected valid JSON. " +
                "Severity should be MEDIUM for fixable formatting issues."
            )
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            budget { maxTurns = 3 }
            skills { skill<String, String>("fix", "Analyze and escalate JSON errors") {
                tools("escalate")
            }}
        }

        data class ToolUse(val name: String, val args: Map<String, Any?>, val result: Any?)
        val toolUses = mutableListOf<ToolUse>()

        val a = agent<String, String>("json-agent") {
            prompt(
                "You are a JSON analysis assistant. Use the calculateNumberOfKeys tool to count keys in JSON objects. " +
                "The tool takes ONE argument: json (a valid JSON string with double-quoted keys). " +
                "If a tool returns an ERROR, read the error carefully — it contains the corrected JSON. " +
                "Extract the corrected JSON from the error and retry. Reply with ONLY the final number."
            )
            model { ollama("gpt-oss:120b-cloud"); host = "localhost"; port = 11434; temperature = 0.0 }
            budget { maxTurns = 10 }
            tools {
                tool("calculateNumberOfKeys",
                    "Count top-level keys in a JSON object. Args: json (string — valid JSON with double-quoted keys, e.g. '{\"name\":\"world\"}')",
                    buildCalculateNumberOfKeysTool()
                )
            }
            onToolError("calculateNumberOfKeys") {
                executionError { _ -> fix(agent = fixer, retries = 2) }
            }
            skills { skill<String, String>("solve", "Analyze JSON using tools") {
                tools("calculateNumberOfKeys")
            }}
            onToolUse { name, args, result ->
                toolUses.add(ToolUse(name, args, result))
                println("  $name(json=${args["json"].toString().take(80)}) = $result")
            }
        }

        val result = a("How many keys are in this JSON? {name: world, age: 30, active: true}")
        println("Result: $result")

        assertTrue(toolUses.isNotEmpty(), "Should have at least one successful tool call")
        val lastSuccess = toolUses.last()
        assertEquals(3, lastSuccess.result, "Corrected JSON should have 3 keys")
        assertTrue(result.contains("3"), "Expected 3 in result, got: $result")
    }
}
