package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val MODEL = "gpt-oss:120b-cloud"
private const val HOST  = "localhost"
private const val PORT  = 11434

class SignInAgentTest {

    /** Builds a JSON string from key-value pairs. */
    private fun buildRequestJson(fields: Map<String, Any?>): String {
        fun valueToJson(v: Any?): String = when (v) {
            null -> "null"
            is String -> "\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
            is Number -> v.toString()
            is Boolean -> v.toString()
            is Map<*, *> -> {
                val inner = v.entries.joinToString(",") { (k, v2) -> "\"$k\":${valueToJson(v2)}" }
                "{$inner}"
            }
            is List<*> -> v.joinToString(",", "[", "]") { valueToJson(it) }
            else -> "\"$v\""
        }
        val entries = fields.entries.joinToString(",") { (k, v) -> "\"$k\":${valueToJson(v)}" }
        return "{$entries}"
    }

    @Tag("live-llm")
    @Test
    fun `agent constructs sign-in JSON using knowledge and request generator tool`() {
        val knowledgeEvents = mutableListOf<Pair<String, String>>()
        val toolEvents = mutableListOf<Triple<String, Map<String, Any?>, Any?>>()

        val a = agent<String, String>("sign-in-agent") {
            prompt("You are a sign-in assistant. To build a sign-in request you MUST: 1) call the passwords tool to look up credentials, 2) call build_request with the login and password from that lookup. After build_request returns the JSON, reply with ONLY that JSON — no explanation.")
            model { ollama(MODEL); host = HOST; port = PORT; temperature = 0.0 }
            budget { maxTurns = 6 }
            tools {
                tool("build_request", "Build a JSON request body. Arguments: login (string), password (string). Returns a JSON string.") { args ->
                    buildRequestJson(args)
                }
            }
            skills {
                skill<String, String>("sign-in", "Build a sign-in request JSON for the given login") {
                    tools("build_request")
                    knowledge("passwords", "User credentials store. Call this to look up passwords for logins.") {
                        """
                        john@example.com : s3cretPass!
                        alice@corp.net   : Al1c3_2024
                        bob@test.org     : b0bRul3z
                        """.trimIndent()
                    }
                }
            }
            onKnowledgeUsed { name, content ->
                knowledgeEvents.add(name to content)
                println("  [knowledge] $name loaded")
            }
            onToolUse { name, args, result ->
                toolEvents.add(Triple(name, args, result))
                println("  [tool] $name($args) = $result")
            }
        }

        val result = a("Create a sign-in request for john@example.com")
        println("Result: $result")

        // Verify final output contains correct credentials
        assertTrue(result.contains("john@example.com"), "Result must contain login, got: $result")
        assertTrue(result.contains("s3cretPass!"), "Result must contain password from knowledge, got: $result")

        // Verify knowledge was accessed
        assertTrue(knowledgeEvents.isNotEmpty(), "Passwords knowledge should have been called")
        assertEquals("passwords", knowledgeEvents[0].first)

        // Verify build_request tool was called with correct args
        assertTrue(toolEvents.isNotEmpty(), "build_request should have been called")
        val buildCall = toolEvents.find { it.first == "build_request" }!!
        assertEquals("john@example.com", buildCall.second["login"], "build_request login arg")
        assertEquals("s3cretPass!", buildCall.second["password"], "build_request password arg")
    }

    @Test
    fun `request generator tool builds correct JSON from args`() {
        val result = buildRequestJson(mapOf("login" to "alice@corp.net", "password" to "Al1c3_2024"))
        assertEquals("""{"login":"alice@corp.net","password":"Al1c3_2024"}""", result)
    }
}
