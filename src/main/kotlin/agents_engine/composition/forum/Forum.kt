package agents_engine.composition.forum

import agents_engine.core.*
import agents_engine.generation.constructFromMap
import agents_engine.generation.fromLlmOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

/**
 * `agents_engine/composition/forum/Forum.kt` — the deliberation operator.
 * `Forum<IN, OUT>` fans the same input out to N participants in
 * parallel, collects their outputs as a [ForumTranscript], and either
 * surfaces the transcript directly or hands it to a transcript-captain
 * for synthesis. `@Mention` text routing surfaces via [onMentionEmitted].
 * Used for n-of-many voting, multi-agent debate, ensemble reasoning.
 * See `src/main/resources/internals-agent/composition/forum/Forum.md`
 * (#1837 / #1867).
 */

class Forum<IN, OUT>(
    val agents: List<Agent<IN, *>>,
    internal val outType: KClass<*>,
    internal val castOut: (Any?) -> OUT,
    internal val captainTakesTranscript: Boolean = false,
) {
    private var mentionListener: ((agentName: String, output: Any?) -> Unit)? = null

    internal fun fireMentionListener(name: String, output: Any?) {
        mentionListener?.invoke(name, output)
    }

    internal fun castForumReturnInternal(raw: Any?): OUT = castForumReturn(raw)

    fun onMentionEmitted(block: (agentName: String, output: Any?) -> Unit) {
        mentionListener = block
    }

    /** The deliberating agents — everyone but the [captain] (#2802). */
    val participants: List<Agent<IN, *>> get() = agents.dropLast(1)

    /** The synthesizing agent that delivers the final verdict — the last registered agent (#2802). */
    val captain: Agent<IN, *> get() = agents.last()

    operator fun invoke(input: IN): OUT = runBlocking { invokeSuspend(input) }

    /**
     * #638: participants run concurrently inside `coroutineScope`, not
     * `runBlocking(Dispatchers.Default)`. Cancellation, timeouts, and dispatcher
     * choice all live with the caller — the framework no longer creates its own
     * scope inside the agentic loop.
     */
    suspend fun invokeSuspend(input: IN): OUT = deliberate(input) { agent, value ->
        @Suppress("UNCHECKED_CAST")
        (agent as Agent<Any?, Any?>).invokeSuspend(value)
    }

    /**
     * #3866 — emitter-aware deliberation. Same core as [invokeSuspend],
     * but every participant (and the captain) runs through
     * `runAgentInSession` so its events stream into [emitter]. Shared by
     * `forum.session(input)` and the `then` overloads that chain a Forum
     * inside a streaming Pipeline.
     */
    internal suspend fun sessionInvoke(
        input: IN,
        emitter: agents_engine.model.AgentEventEmitter,
    ): OUT = deliberate(input) { agent, value ->
        @Suppress("UNCHECKED_CAST")
        agents_engine.runtime.events.runAgentInSession(agent as Agent<Any?, Any?>, value, emitter).first
    }

    /**
     * #2802 — the single deliberation core shared by the non-streaming [invokeSuspend] and the
     * streaming `session` extension. Runs every [participant][participants] concurrently then the
     * [captain], firing [fireMentionListener] at each step, and short-circuits on `forum_return`
     * (cast through [castForumReturn]). [runAgent] abstracts *how* an agent runs: the non-streaming
     * path calls `invokeSuspend`; the streaming path routes through `runAgentInSession` so the
     * agent's events surface on the session emitter. The two paths previously re-derived this whole
     * body with subtly different shapes.
     */
    internal suspend fun deliberate(
        input: IN,
        runAgent: suspend (agent: Agent<*, *>, value: Any?) -> Any?,
    ): OUT = try {
        withContext(Dispatchers.Default) {
            coroutineScope {
                val contributions = participants.map { participant ->
                    async {
                        val output = runAgent(participant, input)
                        fireMentionListener(participant.name, output)
                        ParticipantContribution(participant.name, output)
                    }
                }.awaitAll()

                val verdict = if (captainTakesTranscript) {
                    val transcript = ForumTranscript(originalInput = input, contributions = contributions)
                    runAgent(captain, transcript)
                } else {
                    runAgent(captain, input)
                }
                fireMentionListener(captain.name, verdict)
                @Suppress("UNCHECKED_CAST")
                verdict as OUT
            }
        }
    } catch (e: ForumReturnException) {
        castForumReturn(e.value)
    }

    private fun castForumReturn(raw: Any?): OUT {
        if (outType == String::class) return castOut(raw?.toString())
        if (outType.java.isInstance(raw)) return castOut(raw)
        if (raw is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val mapped = (outType as KClass<Any>).constructFromMap(raw as Map<String, Any?>)
                ?: error("forum_return value could not be parsed as ${outType.simpleName}: $raw")
            return castOut(mapped)
        }
        if (raw is String) {
            @Suppress("UNCHECKED_CAST")
            val parsed = (outType as KClass<Any>).fromLlmOutput(raw)
                ?: error("forum_return value could not be parsed as ${outType.simpleName}: '$raw'")
            return castOut(parsed)
        }
        error("forum_return value is incompatible with ${outType.simpleName}: $raw")
    }
}
