package agents_engine.composition.forum

import agents_engine.core.*
import agents_engine.generation.constructFromMap
import agents_engine.generation.fromLlmOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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

    operator fun invoke(input: IN): OUT = runBlocking { invokeSuspend(input) }

    /**
     * #638: participants run concurrently inside `coroutineScope`, not
     * `runBlocking(Dispatchers.Default)`. Cancellation, timeouts, and dispatcher
     * choice all live with the caller — the framework no longer creates its own
     * scope inside the agentic loop.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun invokeSuspend(input: IN): OUT = try {
        withContext(Dispatchers.Default) {
            coroutineScope {
            val participants = agents.dropLast(1)
            val captain = agents.last()

            // All participants process the input concurrently
            val contributions = participants.map { agent ->
                async {
                    val output = (agent as Agent<IN, Any?>).invokeSuspend(input)
                    mentionListener?.invoke(agent.name, output)
                    ParticipantContribution(agent.name, output)
                }
            }.map { it.await() }

            // Captain delivers the final verdict
            val verdict: OUT = if (captainTakesTranscript) {
                val transcript = ForumTranscript(originalInput = input, contributions = contributions)
                (captain as Agent<ForumTranscript<IN>, OUT>).invokeSuspend(transcript)
            } else {
                (captain as Agent<IN, OUT>).invokeSuspend(input)
            }
            mentionListener?.invoke(captain.name, verdict)
            verdict
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
