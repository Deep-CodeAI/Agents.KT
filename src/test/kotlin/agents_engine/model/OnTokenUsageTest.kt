package agents_engine.model

import agents_engine.core.agent
import agents_engine.runtime.events.session
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OnTokenUsageTest {

    @Test
    fun `TokenUsage exposes provider model and cached input tokens`() {
        val usage = TokenUsage(
            promptTokens = 11,
            completionTokens = 5,
            cachedInputTokens = 3,
            provider = "openai",
            model = "gpt-4o-mini",
        )

        assertEquals(11, usage.promptTokens)
        assertEquals(5, usage.completionTokens)
        assertEquals(3, usage.cachedInputTokens)
        assertEquals("openai", usage.provider)
        assertEquals("gpt-4o-mini", usage.model)
        assertEquals(16, usage.total)
    }

    @Test
    fun `onTokenUsage composes callbacks in registration order and swallows listener failures`() {
        val usage = TokenUsage(
            promptTokens = 7,
            completionTokens = 4,
            cachedInputTokens = 2,
            provider = "test",
            model = "fixture-model",
        )
        val events = mutableListOf<String>()

        val a = agent<String, String>("usage-agent") {
            model {
                ollama("fixture-model")
                client = ModelClient { LlmResponse.Text("done", usage) }
            }
            skills {
                skill<String, String>("s", "s") { tools() }
            }
            onTokenUsage { events += "first:${it.total}" }
            onTokenUsage {
                events += "boom"
                error("listener should be swallowed")
            }
            onTokenUsage { events += "third:${it.provider}:${it.model}:${it.cachedInputTokens}" }
        }

        assertEquals("done", a("input"))
        assertEquals(
            listOf("first:11", "boom", "third:test:fixture-model:2"),
            events,
        )
    }

    @Test
    fun `onTokenUsage does not fire when successful response omits usage`() {
        val usages = mutableListOf<TokenUsage>()
        val a = agent<String, String>("usage-agent") {
            model {
                ollama("fixture-model")
                client = ModelClient { LlmResponse.Text("done") }
            }
            skills {
                skill<String, String>("s", "s") { tools() }
            }
            onTokenUsage { usages += it }
        }

        assertEquals("done", a("input"))
        assertTrue(usages.isEmpty(), "missing tokenUsage must not fire the listener")
    }

    @Test
    fun `multi-turn tool-use cycle fires token usage per round trip before toolUse`() {
        val first = TokenUsage(10, 2, provider = "test", model = "turn-1")
        val second = TokenUsage(30, 4, provider = "test", model = "turn-2")
        val responses = ArrayDeque<LlmResponse>()
        responses += LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap())), first)
        responses += LlmResponse.Text("done", second)
        val events = mutableListOf<String>()
        val usages = mutableListOf<TokenUsage>()

        val a = agent<String, String>("usage-agent") {
            lateinit var noop: Tool<Map<String, Any?>, Any?>
            model {
                ollama("fixture-model")
                client = ModelClient { responses.removeFirst() }
            }
            tools {
                noop = tool("noop", "No-op tool") { _ -> "ok" }
            }
            skills {
                skill<String, String>("s", "s") { tools(noop) }
            }
            onTokenUsage {
                usages += it
                events += "usage:${it.promptTokens}"
            }
            onToolUse { name, _, _ -> events += "tool:$name" }
        }

        assertEquals("done", a("input"))
        assertEquals(listOf(first, second), usages)
        assertEquals(listOf("usage:10", "tool:noop", "usage:30"), events)
    }

    @Test
    fun `onTokenUsage does not fire when model call throws but onError does`() {
        val boom = RuntimeException("simulated 429")
        val usages = mutableListOf<TokenUsage>()
        val errors = mutableListOf<Throwable>()
        val a = agent<String, String>("usage-agent") {
            model {
                ollama("fixture-model")
                client = ModelClient { throw boom }
            }
            skills {
                skill<String, String>("s", "s") { tools() }
            }
            onTokenUsage { usages += it }
            onError { errors += it }
        }

        assertFailsWith<RuntimeException> { a("input") }
        assertTrue(usages.isEmpty(), "failed model calls must not fire token usage")
        assertEquals(1, errors.size)
        assertEquals(boom.message, errors.single().message)
    }

    @Test
    fun `streaming session fires onTokenUsage once at end of stream`() = runTest {
        val usage = TokenUsage(
            promptTokens = 12,
            completionTokens = 6,
            cachedInputTokens = 4,
            provider = "stream-test",
            model = "fixture-model",
        )
        val usages = mutableListOf<TokenUsage>()
        val streamingClient = object : ModelClient {
            override fun chat(messages: List<LlmMessage>): LlmResponse =
                error("session path should use chatStream")

            override suspend fun chatStream(messages: List<LlmMessage>) = flow {
                emit(LlmChunk.TextDelta("do"))
                emit(LlmChunk.TextDelta("ne"))
                emit(LlmChunk.End(usage))
            }
        }

        val a = agent<String, String>("usage-agent") {
            model {
                ollama("fixture-model")
                client = streamingClient
            }
            skills {
                skill<String, String>("s", "s") { tools() }
            }
            onTokenUsage { usages += it }
        }

        val session = a.session("input")
        session.events.toList()

        assertEquals("done", session.await())
        assertEquals(listOf(usage), usages)
    }
}
