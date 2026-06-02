package agents_engine.testing

import agents_engine.model.LlmMessage

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
