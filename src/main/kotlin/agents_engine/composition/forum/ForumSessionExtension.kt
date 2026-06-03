package agents_engine.composition.forum

import agents_engine.core.Agent
import agents_engine.runtime.events.AgentSession
import agents_engine.runtime.events.agentSessionScope
import agents_engine.runtime.events.runAgentInSession

/**
 * `agents_engine/composition/forum/ForumSessionExtension.kt` — the
 * `forum.session(input)` extension. All participants run concurrently
 * via `runAgentInSession`, their events interleave on the shared
 * channel demultiplexable by `agentId`. The optional transcript-captain
 * runs after the deliberation completes; its events also flow through.
 * Terminal `Completed` carries the forum's effective output. The
 * deliberation core is shared with `Forum.invokeSuspend` (#2802); the
 * channel / scope / context / terminal-event lifecycle lives in the
 * shared [agentSessionScope] (#2797). See
 * `src/main/resources/internals-agent/composition/forum/ForumSessionExtension.md`
 * (#1837 / #1868).
 */

/**
 * #1751 — start a streaming session against [this] forum.
 *
 * Participants run concurrently — their events stream into the shared
 * emitter and interleave by arrival order (like Parallel). After every
 * participant completes, the captain runs sequentially; the captain's
 * events stream next. Terminal `Completed(agentId=captain.name, output)`.
 *
 * Preserves the `ForumReturnException` short-circuit: if a participant
 * (or, less commonly, the captain) calls `forum_return`, the captain
 * doesn't run; terminal `Completed` carries the captured value cast
 * through `castForumReturnInternal` — all handled inside [Forum.deliberate].
 *
 * Mention listener still fires per-agent (forum.onMentionEmitted) — the
 * streaming session is purely additive to the existing observability.
 */
fun <IN, OUT> Forum<IN, OUT>.session(input: IN): AgentSession<OUT> {
    val forum = this
    return agentSessionScope({ forum.captain.name }) { emit ->
        // #2802 — same deliberation core as Forum.invokeSuspend; the streaming difference is only that
        // each agent runs through runAgentInSession so its events surface on the emitter.
        forum.deliberate(input) { agent, value ->
            @Suppress("UNCHECKED_CAST")
            runAgentInSession(agent as Agent<Any?, Any?>, value, emit).first
        }
    }
}
