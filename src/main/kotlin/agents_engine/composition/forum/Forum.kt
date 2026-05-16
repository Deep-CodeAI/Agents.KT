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

/**
 * One participant's contribution to a forum deliberation.
 * `output` is `Any?` because participants are heterogeneously typed (`Agent<IN, *>`).
 */
data class ParticipantContribution(
    val agentName: String,
    val output: Any?,
)

/**
 * The collected state a `transcriptCaptain` receives: the original forum input
 * plus each participant's output, in registration order. The deliberation pattern.
 */
data class ForumTranscript<IN>(
    val originalInput: IN,
    val contributions: List<ParticipantContribution>,
)

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

internal class ForumReturnException(val value: Any?) : RuntimeException()

private fun buildForumReturnTool(): agents_engine.model.ToolDef =
    agents_engine.model.ToolDef(
        "forum_return",
        "Finalize the forum immediately. Args: value (optional) or a JSON object matching the forum result type."
    ) { args ->
        val value = when {
            "value" in args -> args["value"]
            args.isEmpty() -> ""
            args.size == 1 -> args.values.first()
            else -> args
        }
        throw ForumReturnException(value)
    }

private fun Agent<*, *>.reserveForumReturnName() {
    require("forum_return" !in toolMap) {
        "Agent \"$name\" already has a tool named \"forum_return\". " +
            "That name is reserved for forum control."
    }
}

private fun Agent<*, *>.setForumReturnPermission(allowed: Boolean) {
    if (allowed) {
        registerBuiltInTool(buildForumReturnTool())
        enableAutoTool("forum_return")
    } else {
        unregisterTool("forum_return")
        disableAutoTool("forum_return")
    }
}

private fun <IN, OUT> Agent<IN, OUT>.prepareForForum(allowForumReturn: Boolean) {
    reserveForumReturnName()
    setForumReturnPermission(allowForumReturn)
    markPlaced("forum")
}

class ForumBuilder<IN, OUT> {
    private val participants = mutableListOf<Agent<IN, *>>()
    private var captain: Agent<*, OUT>? = null
    private var captainTakesTranscript = false
    private val forumReturnAllowed = mutableSetOf<Agent<*, *>>()

    fun <T> participant(agent: Agent<IN, T>) {
        require(agent !in participants && agent !== captain) {
            "Agent \"${agent.name}\" is already registered in this forum."
        }
        participants += agent
    }

    /** Legacy captain — receives the original forum input. Use [transcriptCaptain] for the deliberation pattern. */
    fun captain(agent: Agent<IN, OUT>) {
        require(captain == null) { "Forum already has a captain." }
        require(agent !in participants) {
            "Agent \"${agent.name}\" is already registered as a participant in this forum."
        }
        captain = agent
        captainTakesTranscript = false
    }

    /**
     * Captain that receives a [ForumTranscript] containing the original input AND each
     * participant's output. Use this when the captain needs to deliberate on what the
     * participants said (the "deliberation" pattern). Issue #639.
     */
    fun transcriptCaptain(agent: Agent<ForumTranscript<IN>, OUT>) {
        require(captain == null) { "Forum already has a captain." }
        require(participants.none { it === agent }) {
            "Agent \"${agent.name}\" is already registered as a participant in this forum."
        }
        captain = agent
        captainTakesTranscript = true
    }

    fun <T> allowForumReturn(agent: Agent<*, T>) {
        forumReturnAllowed += agent
    }

    internal fun build(): Forum<IN, OUT> {
        val captainAgent = requireNotNull(captain) { "Forum must declare a captain." }
        val allAgents: List<Agent<IN, *>> = participants + (@Suppress("UNCHECKED_CAST") (captainAgent as Agent<IN, *>))
        require(forumReturnAllowed.all { it in allAgents }) {
            "allowForumReturn can only be used with agents registered in this forum."
        }

        participants.forEach { participant ->
            participant.prepareForForum(participant in forumReturnAllowed)
        }
        captainAgent.prepareForForum(true)

        return Forum(allAgents, captainAgent.outType, { it as OUT }, captainTakesTranscript)
    }
}

fun <IN, OUT> forum(block: ForumBuilder<IN, OUT>.() -> Unit): Forum<IN, OUT> {
    val builder = ForumBuilder<IN, OUT>()
    builder.block()
    return builder.build()
}

operator fun <A, B, C> Agent<A, B>.times(other: Agent<A, C>): Forum<A, C> {
    this.prepareForForum(allowForumReturn = false)
    other.prepareForForum(allowForumReturn = true)
    return Forum(agents = listOf(this, other), outType = other.outType, castOut = { it as C })
}

operator fun <A, B, C> Forum<A, B>.times(other: Agent<A, C>): Forum<A, C> {
    agents.last().setForumReturnPermission(false)
    other.prepareForForum(allowForumReturn = true)
    return Forum(agents = agents + other, outType = other.outType, castOut = { it as C })
}
