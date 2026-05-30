package agents_engine.runtime.events

import agents_engine.core.agent
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.ModelProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * #2863 — regression coverage for the session-extension cancellation contract.
 *
 * Pre-fix every outer `catch (t: Throwable)` block treated `CancellationException`
 * identically to a real failure, emitting an `AgentEvent.Failed` event and
 * swallowing the cancellation from the surrounding scope. Field report (SSE
 * bridge in backend #2861) rendered the cancellation to users as "FlowSubscription
 * was cancelled", clobbering already-streamed partial output.
 *
 * The fix:
 *   - Bare `CancellationException` propagates per structured-concurrency
 *     contract; the consumer's flow surfaces a cancellation, not a synthetic
 *     `Failed` event.
 *   - `TimeoutCancellationException` keeps surfacing as `Failed` — a budget /
 *     `withTimeout` failure is a genuine error consumers must hear about.
 *   - Real failures (executor throw, LLM error, etc.) still emit `Failed`.
 */
class SessionCancellationTest {

    /**
     * Bare cancellation must propagate and must NOT manifest as Failed.
     * We launch the session collector in an inner job, cancel it mid-stream
     * (the implementedBy body blocks via runBlocking + delay), and assert
     * no Failed event lands in the collected list.
     */
    @Test
    fun `bare cancellation propagates to the collector — no Failed event`() {
        // `implementedBy` is non-suspending; runBlocking + delay gives us a
        // cancellation-friendly hang inside the agent's body. The outer
        // coroutine's cancel propagates through runBlocking's job.
        val slowAgent = agent<String, String>("slow") {
            skills {
                skill<String, String>("noop", "noop") {
                    implementedBy { _ ->
                        runBlocking { delay(10_000) }
                        "should not reach here"
                    }
                }
            }
        }

        val collected = mutableListOf<AgentEvent<String>>()
        runBlocking {
            coroutineScope {
                val session = slowAgent.session("hello")
                val collectorJob = launch {
                    try {
                        session.events.toList(collected)
                    } catch (_: CancellationException) {
                        // Expected — the cancellation reaches the collector.
                    }
                }
                delay(80.milliseconds)
                collectorJob.cancel()
            }
        }

        // The point of the fix: no synthetic Failed event in the collected
        // list. Pre-fix the catch would have produced one.
        assertTrue(
            collected.none { it is AgentEvent.Failed },
            "bare cancellation must not emit AgentEvent.Failed, got: $collected",
        )
    }

    /**
     * A real failure (the executor throws a non-cancellation) MUST emit
     * AgentEvent.Failed. This is the "the Failed path still works for real
     * failures" half of the contract.
     */
    @Test
    fun `executor failure still emits AgentEvent Failed`() {
        val boom = IllegalStateException("kaboom")
        val failingAgent = agent<String, String>("failing") {
            skills {
                skill<String, String>("boom", "throws") {
                    implementedBy { _ -> throw boom }
                }
            }
        }

        val collected = mutableListOf<AgentEvent<String>>()
        runBlocking {
            try {
                failingAgent.session("hello").events.toList(collected)
            } catch (e: IllegalStateException) {
                // The flow surfaces the failure after the Failed event lands.
                assertTrue(e.message?.contains("kaboom") == true, "expected kaboom: ${e.message}")
            }
        }

        val failed = collected.filterIsInstance<AgentEvent.Failed>()
        assertTrue(
            failed.isNotEmpty(),
            "real failure must emit AgentEvent.Failed; got: $collected",
        )
        val cause = failed.single().cause
        assertTrue(
            cause is IllegalStateException && cause.message == "kaboom",
            "Failed.cause must carry the original throwable: $cause",
        )
    }

    // ─── Per-vendor coverage (#2863) ────────────────────────────────────
    //
    // The fix lives in the session-extension's catch block, which is
    // vendor-agnostic — every provider routes through the same launch
    // {} + catch {} skeleton. These tests configure an agent against
    // each shipped ModelClient adapter via the `client = ...` DSL
    // injection seam, with a stub client that hangs cancellably. We
    // cancel the collector and assert no synthetic Failed event lands.
    //
    // Why all four: the fix is a code-level change, but each vendor
    // also has its own agentic-loop entry shape (different request-
    // assembly paths, retry wrappers, etc). A per-vendor test means a
    // future regression specific to one adapter's loop integration
    // can't slip past CI.

    @Test
    fun `Ollama session — bare cancellation does not emit Failed`() =
        assertNoFailedOnCancel(provider = ModelProvider.OLLAMA, modelName = "any-model")

    @Test
    fun `Claude session — bare cancellation does not emit Failed`() =
        assertNoFailedOnCancel(provider = ModelProvider.ANTHROPIC, modelName = "claude-opus-4-7", apiKey = "fake")

    @Test
    fun `OpenAI session — bare cancellation does not emit Failed`() =
        assertNoFailedOnCancel(provider = ModelProvider.OPENAI, modelName = "gpt-4o-mini", apiKey = "fake")

    @Test
    fun `DeepSeek session — bare cancellation does not emit Failed`() =
        assertNoFailedOnCancel(provider = ModelProvider.DEEPSEEK, modelName = "deepseek-chat", apiKey = "fake")

    /**
     * Shared per-vendor harness. Builds an agent of the requested
     * provider with a stub [ModelClient] whose `chat()` blocks
     * indefinitely (interruptible — `Thread.sleep` plays nice with
     * cancellation when the dispatcher uses a backing pool). Cancels
     * the collector and asserts no Failed event surfaces.
     */
    private fun assertNoFailedOnCancel(
        provider: ModelProvider,
        modelName: String,
        apiKey: String? = null,
    ) {
        val hangingClient = ModelClient { _: List<LlmMessage> ->
            // Hang on the calling thread; coroutine cancellation will
            // unwind through the launch scope (Dispatchers.Unconfined
            // resumes on the cancelling thread).
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { /* unwind */ }
            LlmResponse.Text("never")
        }

        val hangingAgent = agent<String, String>("hang-$provider") {
            model {
                when (provider) {
                    ModelProvider.OLLAMA -> ollama(modelName)
                    ModelProvider.ANTHROPIC -> claude(modelName)
                    ModelProvider.OPENAI -> openai(modelName)
                    ModelProvider.DEEPSEEK -> deepseek(modelName)
                }
                apiKey?.let { this.apiKey = it }
                client = hangingClient
            }
            skills {
                skill<String, String>("answer", "calls the LLM once") {
                    tools()  // empty list — pure single-shot chat
                }
            }
        }

        val collected = mutableListOf<AgentEvent<String>>()
        runBlocking {
            coroutineScope {
                val session = hangingAgent.session("hello")
                val collectorJob = launch {
                    try {
                        session.events.toList(collected)
                    } catch (_: CancellationException) {
                        // Expected.
                    }
                }
                delay(80.milliseconds)
                collectorJob.cancel()
            }
        }

        assertTrue(
            collected.none { it is AgentEvent.Failed },
            "$provider session must not synthesize Failed on bare cancellation; got: $collected",
        )
    }
}
