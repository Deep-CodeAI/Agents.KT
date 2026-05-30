package agents_engine.testing

import agents_engine.model.JsonSchema
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient

/**
 * `agents_engine/testing/DeterministicModelClient.kt` — reproducible eval
 * harness without live providers (#2492, part of the #2491 eval epic).
 *
 * A [ModelClient] that hands back a pre-scripted sequence of [LlmResponse]s
 * in order, one per `chat` call. Test code constructs an agent with this
 * client and asserts on the full agentic-loop output without any network,
 * tokeniser noise, or model nondeterminism.
 *
 * ```kotlin
 * val mock = DeterministicModelClient(
 *     LlmResponse.ToolCalls(listOf(ToolCall("lookup", mapOf("id" to "42")))),
 *     LlmResponse.Text("the answer is 42"),
 * )
 * val agent = agent<String, String>("test-agent") {
 *     model { ollama("test"); client = mock }
 *     tools { tool("lookup", "look up id") { args -> "value-${args["id"]}" } }
 *     skills { skill<String, String>("respond", "") { tools("lookup") } }
 * }
 * agent("go") // → "the answer is 42"
 * ```
 *
 * **Streaming.** Uses the default `ModelClient.chatStream` implementation,
 * which wraps `chat` into the same Started → ArgumentsDelta → Finished →
 * End chunk sequence a native streaming provider would emit. Tests that
 * assert on the streaming AgentEvent flow get the right shape automatically;
 * tests that need finer-grained chunk replay (e.g. for provider-specific
 * mid-tool-call edge cases) should write a custom flow.
 *
 * **Exhaustion.** If the agent calls `chat` more times than there are
 * scripted responses, the client throws [DeterministicScriptExhausted]
 * naming the call index — useful for debugging "why did the loop need an
 * extra turn?"
 *
 * **Thread-safety.** Calls advance an internal counter; concurrent use
 * from multiple threads is undefined (production-shape agentic loops are
 * single-flight per session, so this matches real usage).
 *
 * **Record-from-live.** Out of scope for v1. The ticket (#2492) mentions
 * "record-once/replay-many"; that needs an HTTP-fixture story we'll write
 * when there's demand. For now: hand-script the responses.
 */
class DeterministicModelClient(
    private val scripted: List<LlmResponse>,
) : ModelClient {

    constructor(vararg responses: LlmResponse) : this(responses.toList())

    private var callIndex: Int = 0
    private val recordedRequests: MutableList<List<LlmMessage>> = mutableListOf()

    /**
     * The full sequence of `messages` lists passed to `chat` so far, in
     * order. Useful for asserting on the conversation the agent built up
     * across turns. Includes ALL turns, not just the last one.
     */
    val requests: List<List<LlmMessage>>
        get() = recordedRequests.toList()

    /**
     * How many scripted responses remain unconsumed. Tests asserting "the
     * loop terminated after exactly N turns" can check `remaining() == 0`
     * after running the agent.
     */
    fun remaining(): Int = (scripted.size - callIndex).coerceAtLeast(0)

    override fun chat(messages: List<LlmMessage>): LlmResponse {
        recordedRequests += messages.toList()
        if (callIndex >= scripted.size) {
            throw DeterministicScriptExhausted(
                callIndex = callIndex,
                scriptSize = scripted.size,
                lastMessages = messages,
            )
        }
        return scripted[callIndex++]
    }

    override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse =
        chat(messages)
}

/**
 * Thrown by [DeterministicModelClient] when the agent calls `chat` more
 * times than there are scripted responses. The message names the call
 * index so test failures are easy to diagnose ("turn 4 had no scripted
 * response — did your tool unexpectedly return an error that triggered an
 * extra retry?").
 */
class DeterministicScriptExhausted(
    val callIndex: Int,
    val scriptSize: Int,
    val lastMessages: List<LlmMessage>,
) : IllegalStateException(
    "DeterministicModelClient script exhausted at call index $callIndex " +
        "(script has $scriptSize responses). The agent's loop tried to ask the model " +
        "for another turn but no response was scripted. Last message list had ${lastMessages.size} " +
        "messages; last role = ${lastMessages.lastOrNull()?.role}.",
)
