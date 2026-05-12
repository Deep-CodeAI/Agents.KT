package agents_engine.composition.wrap

import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.model.Tool
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-provider live integration test for the `wrap` operator (#1698).
 *
 * Teacher = real Claude, student = real Ollama. The teacher emits a system
 * prompt that instructs the student to use a `fib` tool to compute
 * Fibonacci; the student is configured against Ollama with that single
 * tool. The pipeline `(claudeTeacher wrap ollamaStudent)("10")` exercises
 * the wrap operator across two different LLM adapters in one run.
 *
 * Gating: tagged `live-llm` — excluded from default `./gradlew test`. Runs
 * via `./gradlew integrationTest`. Skips cleanly when either provider is
 * unreachable:
 *   - Anthropic: `.secrets/anthropic-key` or `ANTHROPIC_API_KEY`
 *   - Ollama:    a fast preflight to `http://localhost:11434/api/tags`
 *
 * Real LLMs aren't fully deterministic; assertions focus on shape:
 *   1. The `fib` tool was invoked at least once with an integer `n` argument.
 *   2. The final pipeline output contains the right Fibonacci number (55 for n=10).
 *
 * Both are robust to wording variation (e.g., "The answer is 55" vs "55").
 */
class WrapLiveIntegrationTest {

    private val anthropicKey: String? = loadKey("anthropic-key", "ANTHROPIC_API_KEY")
    private val claudeModel: String = System.getenv("CLAUDE_TEST_MODEL") ?: "claude-haiku-4-5-20251001"
    private val ollamaModel: String = System.getenv("OLLAMA_TEST_MODEL") ?: "gpt-oss:20b-cloud"

    private fun fib(n: Int): Long {
        require(n >= 0)
        var a = 0L; var b = 1L
        repeat(n) { val t = a + b; a = b; b = t }
        return a
    }

    @Tag("live-llm")
    @Test
    fun `wrap — Claude teacher instructs Ollama student to compute fibonacci(10)`() {
        assumeTrue(anthropicKey != null, "skipping: no Anthropic key at .secrets/anthropic-key or ANTHROPIC_API_KEY")
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")
        val key = anthropicKey!!

        // Teacher (Claude): given a user message containing an integer,
        // emit a system prompt that tells another LLM agent to call its
        // `fib` tool with that integer. The teacher knows the student's
        // tool surface — that's part of the curriculum.
        val teacher = agent<String, String>("claude-teacher") {
            prompt(
                "You write system prompts for another LLM agent. That agent has exactly one tool: " +
                    "`fib(n: integer)` — computes the nth Fibonacci number. " +
                    "Given a user message containing an integer, your output must be a complete system prompt " +
                    "telling the agent to call the `fib` tool with that integer and return ONLY the resulting " +
                    "number. Output the system prompt text directly, no preamble, no quotes, no markdown."
            )
            model {
                claude(claudeModel)
                apiKey = key
                temperature = 0.0
                maxTokens = 512
            }
            skills {
                skill<String, String>("emit-prompt", "Emit a system prompt that drives the fib tool") {
                    tools()  // text-only, no tool calls from the teacher
                }
            }
        }

        // Student (Ollama): agentic, single `fib` tool. The teacher's
        // emitted prompt will become this agent's system message for the call.
        val toolInvocations = mutableListOf<Map<String, Any?>>()
        val student = agent<String, String>("ollama-student") {
            lateinit var fibTool: Tool<Map<String, Any?>, Any?>
            prompt(
                "DEFAULT BAKED-IN PROMPT — should be overridden by the teacher during the wrap call."
            )
            model {
                ollama(ollamaModel)
                host = "localhost"
                port = 11434
                temperature = 0.0
            }
            tools {
                fibTool = tool("fib", "Compute the nth Fibonacci number. Argument: n (integer ≥ 0).") { args ->
                    toolInvocations += args
                    val n = (args["n"] as Number).toInt()
                    fib(n).toString()
                }
            }
            skills {
                skill<String, String>("compute", "Compute fib via the fib tool") { tools(fibTool) }
            }
        }

        val pipeline = teacher wrap student
        val output = pipeline("10")

        // 1. The tool was invoked at least once with an integer n. Robust to
        //    the model occasionally retrying or echoing the call.
        assertTrue(
            toolInvocations.isNotEmpty(),
            "expected the `fib` tool to be invoked; output was: $output",
        )
        val firstCall = toolInvocations.first()
        val nArg = firstCall["n"] as? Number
            ?: error("first fib invocation had no integer `n` argument: $firstCall (output: $output)")
        assertEquals(
            10,
            nArg.toInt(),
            "first fib invocation should use n=10 (the user-supplied integer); got n=$nArg, output=$output",
        )

        // 2. Final pipeline output carries the right answer. Loose match so
        //    "55", "55.", "The answer is 55", etc. all pass.
        assertTrue(
            "55" in output,
            "expected pipeline output to contain fib(10)=55; got: $output",
        )

        // 3. Student's baked-in default is restored after the call — verifies
        //    the wrap override is truly per-call against real LLM machinery.
        assertTrue(
            "DEFAULT BAKED-IN PROMPT" in student.prompt,
            "student.prompt should be restored after the call; got: ${student.prompt}",
        )
    }

    private fun loadKey(fileName: String, envVar: String): String? {
        val path = Paths.get(".secrets", fileName)
        if (Files.isReadable(path)) {
            val raw = Files.readString(path).trim()
            if (raw.isNotEmpty()) return raw
        }
        return System.getenv(envVar)?.takeIf { it.isNotBlank() }
    }

    private fun isOllamaReachable(): Boolean = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:11434/api/tags"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.statusCode() in 200..299
    } catch (_: Throwable) {
        false
    }
}
