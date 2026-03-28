package agents_engine.composition.forum

import agents_engine.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

class Forum<IN, OUT>(
    val agents: List<Agent<*, *>>,
) {
    private var mentionListener: ((agentName: String, output: Any?) -> Unit)? = null

    fun onMentionEmitted(block: (agentName: String, output: Any?) -> Unit) {
        mentionListener = block
    }

    @Suppress("UNCHECKED_CAST")
    operator fun invoke(input: IN): OUT = runBlocking(Dispatchers.Default) {
        val forumInput = input as Any?
        val participants = agents.dropLast(1)
        val captain = agents.last() as Agent<Any?, OUT>

        // All participants process the input concurrently
        participants.map { agent ->
            async {
                val output = (agent as Agent<Any?, Any?>)(forumInput)
                mentionListener?.invoke(agent.name, output)
                output
            }
        }.map { it.await() }

        // Captain delivers the final verdict
        val verdict = captain(forumInput)
        mentionListener?.invoke(captain.name, verdict)
        verdict
    }
}

operator fun <A, B, C> Agent<A, B>.times(other: Agent<*, C>): Forum<A, C> {
    this.markPlaced("forum")
    other.markPlaced("forum")
    return Forum(listOf(this, other))
}

operator fun <A, B, C> Forum<A, B>.times(other: Agent<*, C>): Forum<A, C> {
    other.markPlaced("forum")
    return Forum(agents + other)
}
