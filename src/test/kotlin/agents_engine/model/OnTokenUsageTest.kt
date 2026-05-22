package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
