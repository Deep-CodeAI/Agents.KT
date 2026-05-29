package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Verifies AgenticLoop attaches [CacheHint]s to LlmMessages according to
 * [CacheConfig] at message-assembly time (#2656).
 *
 * Strategy: a capturing [ModelClient] records the message list of every
 * `chat(...)` call so we can assert which messages carry which hint.
 * Adapter consumption is out of scope here (#2658-#2662); these tests
 * only check that the framework emits the right hints.
 */
class AgenticLoopCacheHintTest {

    private fun capturingClient(
        vararg responses: LlmResponse,
    ): Pair<ModelClient, MutableList<List<LlmMessage>>> {
        val sink = mutableListOf<List<LlmMessage>>()
        val queue = ArrayDeque(responses.toList())
        val client = ModelClient { msgs ->
            sink.add(msgs.toList())
            if (queue.isEmpty()) LlmResponse.Text("done") else queue.removeFirst()
        }
        return client to sink
    }

    /**
     * Tool-less skills throw "no implementation" — give every test agent a
     * trivial tool so the agentic loop runs. The mock returns Text("done")
     * before the tool is ever called.
     */
    private fun agentWith(
        client: ModelClient,
        configure: Agent<String, String>.() -> Unit,
    ): Agent<String, String> = agent<String, String>("c") {
        lateinit var noop: Tool<Map<String, Any?>, Any?>
        prompt("you are helpful")
        model { ollama("llama3"); this.client = client }
        tools { noop = tool("noop", "") { _ -> "ok" } }
        configure()
        skills { skill<String, String>("s", "stub") { tools(noop) } }
    }

    @Test
    fun `default caching — system message gets SystemPrompt hint, no Rolling on assistant`() {
        val (client, sink) = capturingClient(LlmResponse.Text("done"))
        val a = agentWith(client) { /* no caching{} block — defaults apply */ }

        a("hi")

        val first = sink.first()
        val system = first.first { it.role == "system" }
        assertEquals(
            CacheHint(segment = CacheSegment.SystemPrompt),
            system.cacheHint,
            "system message carries SystemPrompt hint with null ttl by default",
        )
        val user = first.first { it.role == "user" }
        assertNull(user.cacheHint, "user input is never a cache anchor")
    }

    @Test
    fun `caching disabled — no hints emitted on any message`() {
        val (client, sink) = capturingClient(LlmResponse.Text("done"))
        val a = agentWith(client) { caching { enabled = false } }

        a("hi")

        assertTrue(sink.first().all { it.cacheHint == null }, "enabled=false → zero hints")
    }

    @Test
    fun `both knobs off — system message has no hint even when caching is enabled`() {
        val (client, sink) = capturingClient(LlmResponse.Text("done"))
        val a = agentWith(client) {
            caching {
                cacheSystemPrompt = false
                cacheToolDefs = false
            }
        }

        a("hi")

        assertNull(
            sink.first().first { it.role == "system" }.cacheHint,
            "both system-side knobs off → no hint on system message",
        )
    }

    @Test
    fun `Rolling conversation — assistant tool-call message gets a Conversation hint`() {
        val (client, sink) = capturingClient(
            LlmResponse.ToolCalls(listOf(ToolCall("noop", emptyMap()))),
            LlmResponse.Text("done"),
        )
        val a = agentWith(client) {
            caching {
                cacheConversation = CacheConversation.Rolling
                ttl = 5.minutes
            }
        }

        a("hi")

        // Second chat call's message list contains the assistant turn-boundary message;
        // that's the one that should carry the Conversation hint under Rolling mode.
        val secondCall = sink[1]
        val assistantTurn = secondCall.first { it.role == "assistant" }
        assertEquals(
            CacheHint(segment = CacheSegment.Conversation, ttl = 5.minutes),
            assistantTurn.cacheHint,
            "Rolling → assistant turn message anchors a conversation cache breakpoint",
        )
    }

    @Test
    fun `custom segments appended after system with Custom() hint and per-segment ttl`() {
        val (client, sink) = capturingClient(LlmResponse.Text("done"))
        val a = agentWith(client) {
            caching {
                ttl = 5.minutes
                cacheable("docs", ttl = 30.minutes) { "big knowledge doc" }
                cacheable("rules") { "house rules" }
            }
        }

        a("hi")

        val msgs = sink.first()
        val systems = msgs.filter { it.role == "system" }
        // Layout: [main system, docs custom, rules custom]
        assertEquals(3, systems.size, "main system message + two custom segments")
        assertEquals(CacheSegment.SystemPrompt, systems[0].cacheHint?.segment)
        assertEquals(
            CacheHint(CacheSegment.Custom("docs"), ttl = 30.minutes),
            systems[1].cacheHint,
            "per-segment ttl wins over config-level",
        )
        assertEquals("big knowledge doc", systems[1].content)
        assertEquals(
            CacheHint(CacheSegment.Custom("rules"), ttl = 5.minutes),
            systems[2].cacheHint,
            "segment with no ttl falls back to CacheConfig.ttl",
        )
    }
}
