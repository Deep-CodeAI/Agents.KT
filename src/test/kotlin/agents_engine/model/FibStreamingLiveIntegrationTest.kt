package agents_engine.model

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Live `chatStream(...)` integration tests across the three shipped providers
 * (Ollama / Anthropic / OpenAI), driven through the v0.4.6 streaming
 * foundation (#1722).
 *
 * As of v0.4.6 no adapter overrides `chatStream` — they inherit the default
 * `chat()`-wrapping implementation on [ModelClient]. So this file pins the
 * contract that downstream native-streaming overrides must continue to
 * satisfy:
 *
 *   - terminal chunk is [LlmChunk.End]
 *   - everything before End is one or more [LlmChunk.TextDelta] for a plain
 *     text answer (no tool calls)
 *   - concatenating the TextDeltas reproduces the model's answer
 *
 * When per-adapter native streaming lands (Anthropic SSE / OpenAI SSE / Ollama
 * `stream: true`), the "exactly one TextDelta" behaviour relaxes — providers
 * will emit many partials — but the ordered-sequence + assembled-text +
 * terminal-End shape stays.
 *
 * Gating: tagged `live-llm` (excluded from default `./gradlew test`); each
 * test skips via JUnit `Assumptions` when its provider isn't reachable
 * (no key file / no env var / no local Ollama).
 */
class FibStreamingLiveIntegrationTest {

    private val anthropicKey: String? = loadKey("anthropic-key", "ANTHROPIC_API_KEY")
    private val openaiKey: String? = loadKey("openai-key", "OPENAI_API_KEY")

    private val claudeModel: String = System.getenv("CLAUDE_TEST_MODEL") ?: "claude-haiku-4-5-20251001"
    private val openaiModel: String = System.getenv("OPENAI_TEST_MODEL") ?: "gpt-4o-mini"
    private val ollamaModel: String = System.getenv("OLLAMA_TEST_MODEL") ?: "gpt-oss:20b-cloud"

    // fib(10) = 55. A bare-integer system prompt keeps the assertion robust
    // across providers — the model has nowhere to hide a sentence around it.
    private val systemPrompt =
        "You are a number generator. Reply with ONLY the decimal integer answer — " +
            "no words, no punctuation, no explanation."
    private val userPrompt = "What is the 10th Fibonacci number? (fib(0)=0, fib(1)=1, ..., fib(10)=?)"
    private val expectedAnswer = "55"

    @Tag("live-llm")
    @Test
    fun `Claude — chatStream emits ordered TextDelta(s) + End containing fib(10)=55`() = runBlocking {
        assumeTrue(anthropicKey != null, "skipping: no Anthropic key at .secrets/anthropic-key or ANTHROPIC_API_KEY")
        val client = ClaudeClient(
            apiKey = anthropicKey!!,
            model = claudeModel,
            temperature = 0.0,
            maxTokens = 32,
        )
        val chunks = client.chatStream(fibMessages()).toList()
        assertStreamingShapeAndAnswer("Claude", chunks)
    }

    @Tag("live-llm")
    @Test
    fun `OpenAI — chatStream emits ordered TextDelta(s) + End containing fib(10)=55`() = runBlocking {
        assumeTrue(openaiKey != null, "skipping: no OpenAI key at .secrets/openai-key or OPENAI_API_KEY")
        val client = OpenAiClient(
            apiKey = openaiKey!!,
            model = openaiModel,
            temperature = 0.0,
            maxTokens = 32,
        )
        val chunks = client.chatStream(fibMessages()).toList()
        assertStreamingShapeAndAnswer("OpenAI", chunks)
    }

    @Tag("live-llm")
    @Test
    fun `Ollama — chatStream emits ordered TextDelta(s) + End containing fib(10)=55`() = runBlocking {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")
        val client = OllamaClient(
            host = "localhost",
            port = 11434,
            model = ollamaModel,
            temperature = 0.0,
        )
        val chunks = client.chatStream(fibMessages()).toList()
        assertStreamingShapeAndAnswer("Ollama", chunks)
    }

    private fun fibMessages(): List<LlmMessage> = listOf(
        LlmMessage(role = "system", content = systemPrompt),
        LlmMessage(role = "user", content = userPrompt),
    )

    private fun assertStreamingShapeAndAnswer(provider: String, chunks: List<LlmChunk>) {
        assertTrue(chunks.isNotEmpty(), "[$provider] no chunks received")
        val last = chunks.last()
        assertIs<LlmChunk.End>(last, "[$provider] terminal chunk must be End; got $last")
        val preTerminal = chunks.dropLast(1)
        assertTrue(
            preTerminal.isNotEmpty() && preTerminal.all { it is LlmChunk.TextDelta },
            "[$provider] expected one-or-more TextDeltas before End for a plain-text answer; got: $chunks",
        )
        val assembled = preTerminal
            .filterIsInstance<LlmChunk.TextDelta>()
            .joinToString("") { it.text }
        assertTrue(
            expectedAnswer in assembled,
            "[$provider] expected '$expectedAnswer' in assembled output, got: '$assembled'",
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
        client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
    } catch (_: Throwable) {
        false
    }
}
