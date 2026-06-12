package agents_engine.composition.firstof

import agents_engine.runtime.events.AgentSession
import agents_engine.runtime.events.agentSessionScope

/**
 * `agents_engine/composition/firstof/FirstOfSessionExtension.kt` — #3869.
 * Streaming session over a speculative race. Every racer's events stream
 * into the shared emitter (interleaved — demultiplex by `agentId`; note
 * self-speculation racers share the agent's id); losers' streams stop
 * when the winner settles and they are cancelled. Terminal `Completed`
 * carries the winning branch's output under the winner's name.
 */
fun <IN, OUT> FirstOf<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val race = this
    var terminalId = race.agents.firstOrNull()?.name ?: "firstOf"
    return agentSessionScope({ terminalId }) { emit ->
        race.sessionInvoke(input, emit) { winner -> terminalId = winner }
    }
}
