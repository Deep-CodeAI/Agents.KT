package agents_engine.composition.forum

import agents_engine.core.*

/**
 * #2379 — `forum_return` accepts a value of the captain agent's `OUT`
 * type, which is heterogeneous and not statically known at the point
 * `buildForumReturnTool()` runs. Rather than build a typed `argsType`
 * we declare a `parametersSchemaJson` with a single `value` property
 * whose own schema is open (`{}`, accepts any JSON), so the LLM gets
 * a real `parameters` field (with property name + the implicit
 * "value is optional, anything else is rejected" contract) instead of
 * the empty-object permissive fallback.
 */
private const val FORUM_RETURN_SCHEMA = """{
    "type": "object",
    "properties": {
        "value": {"description": "The final value to return from the forum. Type matches the captain's OUT."}
    },
    "additionalProperties": false
}"""

private fun buildForumReturnTool(): agents_engine.model.ToolDef =
    agents_engine.model.ToolDef(
        name = "forum_return",
        description = "Finalize the forum immediately. Args: value (optional) or a JSON object matching the forum result type.",
        parametersSchemaJson = FORUM_RETURN_SCHEMA,
        executor = { args ->
            val value = when {
                "value" in args -> args["value"]
                args.isEmpty() -> ""
                args.size == 1 -> args.values.first()
                else -> args
            }
            throw ForumReturnException(value)
        },
    )

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
