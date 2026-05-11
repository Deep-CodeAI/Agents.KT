package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live integration tests for #1675 — proves the typed `tool<Args, Result>`
 * path works end-to-end on each provider.
 *
 * The OpenAI live tests (#1656) revealed that an untyped `ToolDef("...")`
 * advertises `properties: {}` to the provider, and `gpt-4o-mini` rightly
 * called the tool with empty args. The framework's answer is to use the
 * typed builder; this file exercises that answer against real models.
 *
 * Each test:
 *   - registers `tool<GreetArgs, GreetResult>("greet", "...")` where
 *     `GreetArgs(name: String, language: String = "en")` is `@Generable`,
 *   - drives the agent with "Greet Alice in Russian",
 *   - asserts the typed executor saw `name=Alice` (and best-effort that
 *     `language` reflects the request).
 *
 * Together, these exercise: `@Generable` schema → provider envelope
 * (Ollama `parameters`, Anthropic `input_schema`, OpenAI `parameters`)
 * → wire serialization → response parsing → `KClass.constructFromMap`
 * → typed executor invocation.
 *
 * Gating: tagged `live-llm` (excluded from default `./gradlew test`);
 * each test skips via JUnit `Assumptions` when its provider isn't
 * reachable (no key file / no env var / no local Ollama).
 */
class TypedArgsLiveIntegrationTest {

    private val anthropicKey: String? = loadKey("anthropic-key", "ANTHROPIC_API_KEY")
    private val openaiKey: String? = loadKey("openai-key", "OPENAI_API_KEY")

    private val claudeModel: String = System.getenv("CLAUDE_TEST_MODEL") ?: "claude-haiku-4-5-20251001"
    private val openaiModel: String = System.getenv("OPENAI_TEST_MODEL") ?: "gpt-4o-mini"
    private val ollamaModel: String = System.getenv("OLLAMA_TEST_MODEL") ?: "gpt-oss:20b-cloud"

    private val systemPrompt =
        "You are a greeting assistant. ALWAYS call the greet tool — never reply with plain text. " +
            "Extract the person's name and the language they want to be greeted in from the user message."

    @Tag("live-llm")
    @Test
    fun `Claude — typed GreetArgs round-trip with name=Alice`() {
        assumeTrue(anthropicKey != null, "skipping: no Anthropic key at .secrets/anthropic-key or ANTHROPIC_API_KEY")
        val key = anthropicKey!!
        val captured = mutableListOf<GreetArgs>()
        val a = agent<String, String>("typed-greet-claude") {
            lateinit var greet: Tool<GreetArgs, GreetResult>
            prompt(systemPrompt)
            model {
                claude(claudeModel)
                apiKey = key
                temperature = 0.0
                maxTokens = 512
            }
            tools {
                greet = tool<GreetArgs, GreetResult>(
                    "greet",
                    "Greet a person by name in their preferred language.",
                ) { args ->
                    captured += args
                    GreetResult("Hello, ${args.name}! (${args.language})")
                }
            }
            skills { skill<String, String>("greet-skill", "Greet someone using the greet tool") { tools(greet) } }
        }
        val out = runBlocking { a.invokeSuspend("Greet Alice in Russian") }
        assertCapturedName(captured, "Alice", out)
    }

    @Tag("live-llm")
    @Test
    fun `OpenAI — typed GreetArgs round-trip with name=Alice`() {
        assumeTrue(openaiKey != null, "skipping: no OpenAI key at .secrets/openai-key or OPENAI_API_KEY")
        val key = openaiKey!!
        val captured = mutableListOf<GreetArgs>()
        val a = agent<String, String>("typed-greet-openai") {
            lateinit var greet: Tool<GreetArgs, GreetResult>
            prompt(systemPrompt)
            model {
                openai(openaiModel)
                apiKey = key
                temperature = 0.0
                maxTokens = 512
            }
            tools {
                greet = tool<GreetArgs, GreetResult>(
                    "greet",
                    "Greet a person by name in their preferred language.",
                ) { args ->
                    captured += args
                    GreetResult("Hello, ${args.name}! (${args.language})")
                }
            }
            skills { skill<String, String>("greet-skill", "Greet someone using the greet tool") { tools(greet) } }
        }
        val out = runBlocking { a.invokeSuspend("Greet Alice in Russian") }
        assertCapturedName(captured, "Alice", out)
    }

    @Tag("live-llm")
    @Test
    fun `Ollama — typed GreetArgs round-trip with name=Alice`() {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")
        val captured = mutableListOf<GreetArgs>()
        val a = agent<String, String>("typed-greet-ollama") {
            lateinit var greet: Tool<GreetArgs, GreetResult>
            prompt(systemPrompt)
            model {
                ollama(ollamaModel)
                host = "localhost"
                port = 11434
                temperature = 0.0
            }
            tools {
                greet = tool<GreetArgs, GreetResult>(
                    "greet",
                    "Greet a person by name in their preferred language.",
                ) { args ->
                    captured += args
                    GreetResult("Hello, ${args.name}! (${args.language})")
                }
            }
            skills { skill<String, String>("greet-skill", "Greet someone using the greet tool") { tools(greet) } }
        }
        val out = runBlocking { a.invokeSuspend("Greet Alice in Russian") }
        assertCapturedName(captured, "Alice", out)
    }

    /**
     * Captures the shared assertion shape. The typed executor must have been
     * invoked at least once with `name~=Alice`. The `language` field is
     * observed but not asserted — small/cheap models map "Russian" to "ru",
     * "russian", or sometimes default to "en"; that's a model-precision
     * concern, not a `constructFromMap` correctness concern.
     */
    private fun assertCapturedName(
        captured: List<GreetArgs>,
        expectedName: String,
        agentOutput: String,
    ) {
        assertTrue(
            captured.isNotEmpty(),
            "typed executor must be invoked at least once; agent output was: $agentOutput",
        )
        val first = captured.first()
        assertTrue(
            first.name.contains(expectedName, ignoreCase = true),
            "expected name~=$expectedName in typed args, got name='${first.name}' language='${first.language}'",
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

    /** Fast preflight against Ollama — fail-closed on any IO error → test skips. */
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
