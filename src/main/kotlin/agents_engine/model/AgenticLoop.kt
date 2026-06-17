package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.InterceptorDeniedException
import agents_engine.core.Skill
import agents_engine.runtime.events.AgentEvent

/**
 * `agents_engine/model/AgenticLoop.kt` — the multi-turn LLM-tool dispatch
 * loop ([executeAgentic]) at the heart of every agentic-skill invocation.
 *
 * **Responsibilities.** Builds the per-skill tool allowlist (skill tools +
 * agent-capability tools + per-skill memory tools per #856 + knowledge-on-
 * demand tools), runs `chat ↔ tool` turns until the LLM produces a final
 * answer or a budget cap fires, coerces the final text into the typed
 * `OUT` via the skill's transformer or [agents_engine.generation]
 * structured-output decoder, and returns an [AgenticResult] carrying both
 * the output and the cumulative [TokenUsage] (#1740).
 * For `@Generable` outputs, the loop passes a provider-neutral [JsonSchema]
 * to clients that support constrained decoding (#1949), then still validates
 * the returned text locally.
 *
 * **Streaming-aware (#1739).** When [executeAgentic]'s `emitter` is
 * non-null, the loop switches to `client.chatStream(...)` and surfaces
 * `Token` / `ToolCallStarted` / `ToolCallArgumentsDelta` /
 * `ToolCallFinished` `AgentEvent`s. When null, behaves byte-for-byte as
 * the non-streaming `chat(...)` path — `Agent.invoke` / `invokeSuspend`
 * pay no overhead.
 *
 * **Budget enforcement.** Honors `maxTurns`, `maxToolCalls`, `maxDuration`,
 * `perToolTimeout`, `maxTokens`, `maxConsecutiveSameTool`. Pre-cap warnings
 * fire via the agent's `budgetThresholdListener` before the hard throw.
 *
 * **Before-interceptors (#1907).** Runs `onBeforeTurn` before every outbound
 * model call and `onBeforeToolCall` after the static allowlist check but before
 * dispatch. The tool hook covers both regular and session-aware executors.
 *
 * **Argument repair.** Up to [MAX_ARGUMENT_REPAIR_STEPS] retries (8) when
 * the LLM produces a tool call whose JSON arguments fail to parse or
 * deserialize — the loop reflects the parser error back to the LLM and
 * asks for corrected arguments.
 *
 * **Wrap-friendly effective prompt.** [executeAgentic]'s `effectivePrompt`
 * defaults to `agent.prompt` but the `wrap` operator passes the teacher's
 * output instead — avoids the race where the wrap operator would have to
 * mutate `agent.prompt` on shared pipeline invocations (#1707).
 *
 * See `src/main/resources/internals-agent/model/AgenticLoop.md` for the
 * adjunct surfaced to IDE-side LLM tools via `agents-kt-internals`
 * (#1837 / #1844).
 */


// #2804 — first N hex chars of the manifest SHA-256 used in OpenAI
// `prompt_cache_key` routing. 12 = 48 bits, ample to disambiguate agent
// revisions in the route hint without flooding cache-key cardinality.
private const val MANIFEST_HASH_PREFIX_LEN = 12

/**
 * #1740 — return shape from [executeAgentic]. Carries the parsed output
 * alongside cumulative [TokenUsage] summed across all LLM turns of the
 * invocation. [tokenUsage] is null when the provider never reported
 * usage for any turn.
 */
internal data class AgenticResult(val output: Any?, val tokenUsage: TokenUsage?)

/**
 * #3508 — run a model round-trip, tagging ANY failure with the internal [LlmCallFailure] marker so
 * the single chokepoint in `Agent.invokeSuspendForSession` can recognize "the LLM call failed" and
 * distinguish it from a tool error, a budget cap, or cancellation. A *down* server surfaces as a raw
 * `java.net.ConnectException`; a 5xx / malformed-stream as something else again — the marker makes
 * model failures recognizable WITHOUT changing the identity of the underlying error (the chokepoint
 * unwraps it). Lives in the loop core (its primary caller via [chatOrStream]); also used by the skill
 * router (`selectSkillByLlm`).
 *
 * [kotlinx.coroutines.CancellationException] (including `TimeoutCancellationException` from budget /
 * `withTimeout`) propagates untouched — structured concurrency and budget handling own those, and
 * recovering them would break cooperative cancellation. An already-marked [LlmCallFailure] passes
 * through (no double-wrap).
 */
internal suspend fun <T> guardLlmCall(block: suspend () -> T): T = try {
    block()
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (marked: LlmCallFailure) {
    throw marked
} catch (t: Throwable) {
    throw LlmCallFailure(t)
}

/**
 * Runs the agentic loop for [skill] on [agent] with [input].
 * Returns the parsed output paired with cumulative token usage;
 * the caller casts the output via the agent's castOut.
 */
internal suspend fun <IN> executeAgentic(
    agent: Agent<IN, *>,
    skill: Skill<*, *>,
    input: IN,
    /**
     * #3376 batch 5 — the per-invocation execution parameters (prompt override, resume/HITL state,
     * checkpoint callback, manifest-mismatch opt-out, attachments) bundled into one [RunRequest],
     * shared with [Agent.invokeSuspendForSession]. Defaults to a fresh invocation.
     */
    request: agents_engine.core.RunRequest = agents_engine.core.RunRequest(),
    /**
     * #1739: optional AgentEvent emitter. When non-null, the loop streams
     * via `client.chatStream(...)`, surfaces `Token` / `ToolCallStarted` /
     * `ToolCallArgumentsDelta` events from chunks, and emits
     * `ToolCallFinished` after each tool executor runs. When null, the
     * loop uses `client.chat(...)` byte-for-byte as before — non-streaming
     * callers (`Agent.invoke`, `Agent.invokeSuspend`) pay no overhead.
     */
    emitter: AgentEventEmitter? = null,
    runtimeContext: AgentRuntimeContext = AgentRuntimeContext.currentOrNew(),
): AgenticResult {
    val config = requireNotNull(agent.modelConfig) {
        "Agent '${agent.name}' has no model configured. Add a model { } block."
    }
    val budget = agent.budgetConfig

    // #3376 batch 5 — unpack the bundled request into the locals the loop body uses, so the body is
    // byte-for-byte unchanged. `effectivePrompt` defaults to the agent's baked-in prompt (the `wrap`
    // operator passes a per-invocation override via request.promptOverride).
    val effectivePrompt = request.promptOverride ?: agent.prompt
    val resumeFrom = request.resumeFrom
    val onTurnCheckpoint = request.onTurnCheckpoint
    val allowManifestMismatch = request.allowManifestMismatch
    val resumeWith = request.resumeWith
    val attachments = request.attachments

    val messages = mutableListOf<LlmMessage>()

    // #3423 — tool-set assembly (skill + agent-capability + per-skill memory tools per #856, lazy
    // knowledge tools, dedup fail-fast per #645, and the #630 authorization allowlist) extracted to
    // resolveAllowedTools; unpacked into the locals the loop body below consumes.
    val resolvedTools = resolveAllowedTools(agent, skill)
    val allToolDefs = resolvedTools.allToolDefs
    val knowledgeToolDefs = resolvedTools.knowledgeToolDefs
    val knowledgeToolMap = resolvedTools.knowledgeToolMap
    val allowedToolMap = resolvedTools.allowedToolMap

    // #2659 — `prompt_cache_key` for OpenAI routing. Derived from agent
    // identity so same-shape requests land on the same OpenAI cache shard.
    // Null when caching is disabled or the agent has no manifest hash —
    // both correctness-neutral, just no routing hint to send.
    val cacheRoutingKey: String? = if (agent.cacheConfig.enabled) {
        // #2804 — MANIFEST_HASH_PREFIX_LEN named so the OpenAI routing key's
        // shape doesn't drift on a typo. 12 hex chars = 48 bits of manifest
        // identity, ample to disambiguate agent revisions in the route hint
        // without flooding cache-key cardinality.
        agent.manifestHash?.let { "agents-kt:${agent.name}:${it.take(MANIFEST_HASH_PREFIX_LEN)}" }
            ?: "agents-kt:${agent.name}"
    } else null
    val client = config.client
        ?: ModelClientFactory.defaultClientFor(config, allToolDefs, cacheRoutingKey, agent.toolChoice)
    val constrainedOutputSchema = ModelClientFactory.constrainedOutputSchemaFor(agent.outType, skill, client)

    val systemContent = buildSystemPrompt(effectivePrompt, skill, allToolDefs, knowledgeToolDefs)
    // #2416 — resume seeds messages + memory from a prior snapshot (the saved history already
    // contains the system + user messages); a fresh run builds them via #2791 seedMessages.
    if (resumeFrom != null) {
        restoreFromSnapshot(agent, resumeFrom, allowManifestMismatch, resumeWith, runtimeContext, messages)
    } else {
        seedMessages(agent, input, attachments, systemContent, messages)
    }

    // #1740: cumulative usage across all turns, summed via TokenUsage.plus (TokenUsage.total derived).
    var cumulativeUsage: TokenUsage? = resumeFrom?.tokensUsed
    // #4490 — per-invocation tool-constraint state (counts + completion set).
    val constraintTracker = ToolConstraintTracker()
    val invocationStartNanos = System.nanoTime()

    // #2791 — budget counters, pre-cap threshold firing (#966), the Stop/Extend/Checkpoint cap
    // dispatch (#2412/#2750), and the one SessionSnapshot builder (#2755/#2764) all live in
    // BudgetTracker; the snapshot reads the live message list + cumulative usage through providers.
    val tracker = BudgetTracker(
        agent = agent,
        budget = budget,
        resumeFrom = resumeFrom,
        runtimeContext = runtimeContext,
        onTurnCheckpoint = onTurnCheckpoint,
        messagesSnapshot = { messages.toList() },
        cumulativeUsage = { cumulativeUsage },
    )

    while (true) {
        val elapsedNanos = System.nanoTime() - invocationStartNanos
        if (elapsedNanos >= tracker.durationLimitNanos) {
            // #2750 — DURATION is extendable via onBudgetExceeded(): the handler returns
            // Extend(newLimit) in MILLISECONDS; convert back to nanos. Stop / no handler / Extend ≤
            // current still throws (back-compat with #2412).
            val currentMillis = (tracker.durationLimitNanos / 1_000_000L).toInt().coerceAtLeast(1)
            tracker.resolveCapDecision(
                BudgetReason.DURATION,
                currentMillis,
                "Agent '${agent.name}' exceeded duration budget of ${budget.maxDuration}",
            ) { newMillis -> tracker.durationLimitNanos = newMillis.toLong() * 1_000_000L }
        }
        if (tracker.turns >= tracker.turnLimit) {
            tracker.resolveCapDecision(
                BudgetReason.TURNS,
                tracker.turnLimit,
                "Agent '${agent.name}' exceeded budget of ${tracker.turnLimit} turns",
            ) { newLimit -> tracker.turnLimit = newLimit }
        }

        // Threshold check before the next chat — DURATION is wall-clock, so it can cross the
        // threshold purely by waiting (e.g., on a slow tool). TURNS / TOOL_CALLS / TOKENS thresholds
        // get checked just after their accumulator updates below.
        tracker.maybeFireThreshold(BudgetReason.DURATION, elapsedNanos.toDouble() / tracker.durationLimitNanos)

        when (val decision = agent.decideBeforeTurn(messages.toList())) {
            Decision.Proceed -> Unit
            is Decision.ProceedWith -> {
                messages.clear()
                messages.addAll(decision.replacement)
            }
            is Decision.Deny -> throw InterceptorDeniedException(
                "Turn denied by interceptor: ${decision.reason}"
            )
            is Decision.Substitute<*> -> return AgenticResult(
                OutputCoercion.coerceSubstituteOutput(decision.result, agent.outType),
                cumulativeUsage,
            )
        }

        val turnIndex = tracker.turns + 1
        emitter?.invoke(
            AgentEvent.ModelTurnStarted(
                agentId = agent.name,
                skillName = skill.name,
                turnIndex = turnIndex,
                provider = ModelClientFactory.semconvProviderName(config.provider),
                model = config.name,
                temperature = config.temperature,
            )
        )
        // #3508/#4495 — on model-call failure (down server, provider 5xx, malformed response) the
        // onLLMError policy is consulted per attempt: Retry re-runs the call after exponential
        // backoff (attempt budget is per turn); RespondWith short-circuits the loop with a fallback;
        // Rethrow / no handler / exhausted retries rethrow the ORIGINAL error (fail fast and loud,
        // identity preserved). The LlmCallFailure marker never escapes.
        var llmAttempt = 0
        var attemptResponse: LlmResponse? = null
        while (attemptResponse == null) {
            attemptResponse = try {
                chatOrStream(
                    client = client,
                    messages = messages,
                    agentId = agent.name,
                    skillName = skill.name,
                    emitter = emitter,
                    jsonSchema = constrainedOutputSchema,
                )
            } catch (failure: LlmCallFailure) {
                llmAttempt++
                when (val decision = agent.llmErrorHandler?.invoke(failure.original)) {
                    is LlmErrorDecision.RespondWith -> return AgenticResult(decision.output, null)
                    is LlmErrorDecision.Retry -> {
                        if (llmAttempt >= decision.maxAttempts) throw failure.original
                        kotlinx.coroutines.delay(decision.backoffBeforeRetry(llmAttempt))
                        null
                    }
                    LlmErrorDecision.Rethrow, null -> throw failure.original
                }
            }
        }
        val response = attemptResponse
        tracker.turns++
        val responseUsage = response.tokenUsage
        emitter?.invoke(
            AgentEvent.ModelTurnCompleted(
                agentId = agent.name,
                skillName = skill.name,
                turnIndex = turnIndex,
                provider = responseUsage?.provider ?: ModelClientFactory.semconvProviderName(config.provider),
                model = responseUsage?.model ?: config.name,
                responseType = when (response) {
                    is LlmResponse.Text -> "text"
                    is LlmResponse.ToolCalls -> "tool_calls"
                },
                tokensUsed = responseUsage,
            )
        )
        tracker.maybeFireThreshold(BudgetReason.TURNS, tracker.turns.toDouble() / tracker.turnLimit)

        // #963: accumulate tokens only when the provider reported usage — a missing `tokenUsage`
        // does NOT count as zero toward the cap. Check after the round-trip so the LAST turn's
        // tokens are counted even if it tips us over: the throw still surfaces the breach.
        responseUsage?.let { usage ->
            agent.fireTokenUsage(usage)
            tracker.totalTokens += usage.total
            cumulativeUsage = accumulateUsage(cumulativeUsage, usage)
            val cap = tracker.tokenLimit
            if (cap != null) {
                tracker.maybeFireThreshold(BudgetReason.TOKENS, tracker.totalTokens.toDouble() / cap)
                if (tracker.totalTokens > cap) {
                    tracker.resolveCapDecision(
                        BudgetReason.TOKENS,
                        cap,
                        "Agent '${agent.name}' exceeded token budget of $cap (used ${tracker.totalTokens})",
                    ) { newLimit -> tracker.tokenLimit = newLimit }
                }
            }
        }

        when (response) {
            is LlmResponse.Text -> {
                val parsed = skill.outputTransformer?.invoke(response.content)
                    ?: OutputCoercion.parseOutput(response.content, agent.outType)
                    ?: error("Could not parse LLM output as ${agent.outType.simpleName}: '${response.content}'")
                return AgenticResult(parsed, cumulativeUsage)
            }
            // #2791 — the tool-call arm (budget accounting + per-call dispatch) lives in
            // ToolCallHandling.kt so this loop stays orchestration.
            is LlmResponse.ToolCalls -> handleToolCalls(
                response, agent, skill, budget, emitter, runtimeContext, messages,
                allowedToolMap, knowledgeToolMap, constraintTracker, tracker, onTurnCheckpoint,
            )
        }
        // #2416 — turn-boundary checkpoint. Text responses return above; only tool-turns reach here,
        // with messages settled and no half-run tool.
        onTurnCheckpoint?.invoke(tracker.snapshot())
    }
}


internal suspend fun <IN> executeToolWithBudgetHandlingEvents(
    agent: Agent<IN, *>,
    tool: ToolDef,
    call: ToolCall,
    budget: BudgetConfig,
    emitter: AgentEventEmitter?,
): Any? = try {
    executeToolWithBudget(agent, tool, call, budget, emitter)
} catch (signal: agents_engine.core.PendingInterruptSignal) {
    // #2488 — HITL interrupt is NOT an error. Rethrow without emitting
    // ToolCallFinished(isError=true); the outer try/catch in
    // executeAgentic owns the snapshot capture + AgentInterruptException
    // throw. Emitting an error event here would misleadingly mark a
    // legitimate pause as a failure in streaming consumers and audit logs.
    throw signal
} catch (t: Throwable) {
    // #1739: tool executor threw and onError didn't recover.
    // Surface a ToolCallFinished event with isError=true so consumers see
    // the failure, then rethrow — the outer error path emits session Failed.
    emitToolFinished(emitter, agent, call, t.message, isError = true)
    throw t
}
