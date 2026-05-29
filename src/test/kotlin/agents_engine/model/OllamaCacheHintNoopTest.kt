package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * #2662 — Ollama / self-hosted prefix caching is **engine-level** (Ollama
 * context reuse, vLLM APC, SGLang RadixAttention). No wire-level cache
 * control exists, so `CacheHint`s degrade to a no-op at the adapter
 * boundary. Prefix stability (#2657) is what makes the engine cache hit.
 *
 * This test pins the no-op contract: an `LlmMessage` carrying a
 * [CacheHint] does NOT leak any vendor-specific cache field into the
 * Ollama request body. Future Ollama API additions (a `cache_prompt`
 * boolean, an engine cache-stats response) would still be backward-
 * compatible additions, but the contract today is "hints are silent."
 */
class OllamaCacheHintNoopTest {

    private class StubClient(
        canned: String = """{"message":{"role":"assistant","content":"ok"},"done":true}""",
    ) : OllamaClient(
        host = "localhost",
        port = 11434,
        model = "llama3",
        temperature = 0.0,
    ) {
        val sentBodies: MutableList<String> = mutableListOf()
        private val cannedBody = canned
        override fun sendChat(body: String): String {
            sentBodies.add(body)
            return cannedBody
        }
    }

    @Test
    fun `cache hints do not introduce any cache_control field in the Ollama body`() {
        val client = StubClient()
        client.chat(listOf(
            LlmMessage(
                "system",
                "You are helpful.",
                cacheHint = CacheHint(segment = CacheSegment.SystemPrompt),
            ),
            LlmMessage(
                "system",
                "Big retrieved doc.",
                cacheHint = CacheHint(segment = CacheSegment.Custom("doc-1")),
            ),
            LlmMessage("user", "hi"),
        ))

        val body = client.sentBodies.single()
        assertFalse(body.contains("cache_control"), "Ollama doesn't accept cache_control; must not leak")
        assertFalse(body.contains("prompt_cache_key"), "no OpenAI-routing fields leak into Ollama either")
        // Sanity: the body still parses and carries the messages — the
        // hints were silently dropped, not the content.
        val root = LenientJsonParser.parse(body) as Map<*, *>
        val msgs = root["messages"] as List<*>
        // Ollama treats the multiple system messages as multiple system
        // entries; the content is preserved verbatim.
        val texts = msgs.map { (it as Map<*, *>)["content"] as String }
        // The Custom segment content survives somewhere in the messages.
        assert(texts.any { it.contains("Big retrieved doc") }) {
            "custom segment content must reach Ollama; got: $texts"
        }
    }
}
