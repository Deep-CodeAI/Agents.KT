package agents_engine.model

import agents_engine.core.Agent
import agents_engine.core.AgentRuntimeContext
import agents_engine.core.Decision
import agents_engine.core.InterceptorDeniedException
import agents_engine.core.Skill
import agents_engine.core.withAgentRuntimeContext
import agents_engine.generation.jsonSchema
import agents_engine.generation.toLlmInput
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

// #2804 — first N hex chars of a content-addressed blob hash used when
// rendering multipart tool-result placeholders into the LLM message. Same
// 12-char convention as MANIFEST_HASH_PREFIX_LEN.
private const val BLOB_HASH_PREFIX_LEN = 12

/**
 * #1740 — return shape from [executeAgentic]. Carries the parsed output
 * alongside cumulative [TokenUsage] summed across all LLM turns of the
 * invocation. [tokenUsage] is null when the provider never reported
 * usage for any turn.
 */
internal data class AgenticResult(val output: Any, val tokenUsage: TokenUsage?)

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

    // Action tools: tools the skill explicitly lists + agent capabilities + memory tools
    val skillToolDefs = skill.toolNames?.mapNotNull { agent.toolMap[it] } ?: emptyList()
    val autoToolDefs = agent.autoToolNames.mapNotNull { agent.toolMap[it] }
    // #856 — memory-tool authorization is per-skill when ANY skill opts in via
    // `useMemory()`. If none opt in, fall back to the legacy default-on behavior
    // (every skill gets memory tools when memoryBank is set) so existing
    // single-skill agents don't break.
    val anySkillOptedIntoMemory = agent.skills.values.any { it.useMemory }
    val memoryToolDefs = when {
        agent.memoryBank == null -> emptyList()
        anySkillOptedIntoMemory && !skill.useMemory -> emptyList()
        // #2804 — reuse RESERVED_MEMORY_TOOL_NAMES (ToolDef.kt) so adding a
        // 4th memory tool (e.g. memory_delete) only updates one set, not two.
        else -> agent.toolMap.values.filter { it.name in RESERVED_MEMORY_TOOL_NAMES }
    }
    val actionToolDefs = (skillToolDefs + autoToolDefs + memoryToolDefs).distinctBy { it.name }

    // Knowledge tools: exposed lazily — LLM calls them to load context on demand
    val knowledgeToolDefs = skill.knowledgeTools().map { kt ->
        ToolDef(kt.name, kt.description) { _ -> kt.call() }
    }
    val knowledgeToolMap = knowledgeToolDefs.associateBy { it.name }

    val allToolDefs = actionToolDefs + knowledgeToolDefs

    // Fail-fast on duplicate tool names across the allowed sources (skill tools,
    // auto tools, memory tools, knowledge entries). `distinctBy` would silently
    // pick a winner; we want this surfaced as a configuration error. See #645.
    val duplicateNames = allToolDefs.groupBy { it.name }.filterValues { it.size > 1 }.keys
    check(duplicateNames.isEmpty()) {
        "Duplicate tool names in allowed tool set for skill '${skill.name}': $duplicateNames. " +
            "A name appears in more than one source (skill tools, auto tools, memory tools, " +
            "knowledge entries) — pick one source per name."
    }

    // Authorization boundary: execution looks up against THIS allowlist only,
    // not the wider agent.toolMap. A model emitting any tool name not in this
    // map will be refused — even if the agent has that tool registered for a
    // different skill. This is the runtime enforcement the prompt does NOT do.
    val allowedToolMap = allToolDefs.associateBy { it.name }

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
    // #2416 — resume seeds messages + memory from a prior snapshot; the saved
    // history already contains the system + user messages, so we don't re-add
    // them. A fresh run builds them as usual.
    if (resumeFrom != null) {
        restoreFromSnapshot(agent, resumeFrom, allowManifestMismatch, resumeWith, runtimeContext, messages)
    } else {
        // #2656 — vendor-neutral cache hints derived from agent.cacheConfig.
        // The hint marks an LlmMessage as the end of a cacheable group;
        // adapters translate to their provider's mechanism (Anthropic
        // cache_control breakpoint, Gemini handle boundary, OpenAI / DeepSeek /
        // Ollama automatic prefix caching, …). Adapters that don't support
        // caching ignore the hint — no correctness impact.
        val cache = agent.cacheConfig
        val systemHint = if (cache.enabled && (cache.cacheSystemPrompt || cache.cacheToolDefs)) {
            CacheHint(segment = CacheSegment.SystemPrompt, ttl = cache.ttl)
        } else null

        if (systemContent.isNotBlank()) {
            val systemMsg = LlmMessage("system", systemContent, cacheHint = systemHint)
            messages.add(systemMsg)
            // #2657 — prefix-stability guard. Warns the deployer when a
            // cacheable segment's content changed between invocations of the
            // same agent (timestamps, UUIDs, non-deterministic ordering),
            // since the vendor cache silently misses without any signal.
            // No-op when systemHint is null (caching disabled).
            PrefixStabilityGuard.observe(agent, systemMsg)
        }
        // Custom cacheable segments — large retrieved docs / instruction sets
        // declared via `caching { cacheable("id") { content } }`. Emitted as
        // their own "system"-role messages so each carries its own Custom hint.
        // Content is preserved even when caching is disabled (the DSL declared
        // prompt content, not just a cache directive); only the hint drops.
        for (seg in cache.customSegments) {
            val hint = if (cache.enabled) {
                CacheHint(segment = CacheSegment.Custom(seg.id), ttl = seg.ttl ?: cache.ttl)
            } else null
            val customMsg = LlmMessage("system", seg.content, cacheHint = hint)
            messages.add(customMsg)
            PrefixStabilityGuard.observe(agent, customMsg)
        }
        // User: serialized input. Typed @Generable inputs become JSON; primitives
        // and Strings render literally; non-Generable types fall back to toString.
        // See #937 / GenerableSupport.toLlmInput.
        //
        // #2470 slice b — when the caller passes `attachments`, dereference
        // each `Content.Image` against the agent's BlobStore, base64-encode
        // once, and ride along on this first user message as `images: List<
        // ImagePart>`. The slice-a per-provider adapters translate that to
        // the right wire shape (Ollama `images: [...]`, Claude image blocks,
        // OpenAI image_url blocks). Non-image content variants (Document /
        // Audio / Video) skipped — provider doc/audio/video paths land in
        // later slices. Image-less attachments lists are a fast-path no-op.
        val attachedImages: List<ImagePart>? = if (attachments.isNullOrEmpty()) {
            null
        } else {
            val store = agent.blobStore
            require(store != null) {
                "Agent '${agent.name}' has attachments but no blobStore — call `blobStore(store)` " +
                    "inside the agent { } block so Content.Image refs can be dereferenced."
            }
            attachments.mapNotNull { content ->
                when (content) {
                    is agents_engine.content.Content.Image -> {
                        val bytes = store.get(content.ref)
                            ?: error(
                                "BlobStore on agent '${agent.name}' has no entry for ContentRef(" +
                                    "hash=${content.ref.hash.take(BLOB_HASH_PREFIX_LEN)}…, size=${content.ref.sizeBytes}); " +
                                    "did the store get rewired or the blob purged?",
                            )
                        ImagePart(
                            base64 = java.util.Base64.getEncoder().encodeToString(bytes),
                            wireMime = when (content.mime) {
                                agents_engine.content.ImageMime.Png -> ImagePart.WireMime.Png
                                agents_engine.content.ImageMime.Jpeg -> ImagePart.WireMime.Jpeg
                                agents_engine.content.ImageMime.Gif -> ImagePart.WireMime.Gif
                                agents_engine.content.ImageMime.Webp -> ImagePart.WireMime.Webp
                            },
                        )
                    }
                    is agents_engine.content.Content.Text,
                    is agents_engine.content.Content.Audio,
                    is agents_engine.content.Content.Video,
                    is agents_engine.content.Content.Document -> {
                        // Not an image — skip in v1. Slice c (provider doc/
                        // audio/video paths) covers the rest.
                        null
                    }
                }
            }.takeIf { it.isNotEmpty() }
        }
        messages.add(LlmMessage("user", toLlmInput(input), images = attachedImages))
    }

    var turns = resumeFrom?.turns ?: 0
    var toolCalls = resumeFrom?.toolCalls ?: 0
    // #2412 / #2750 — effective caps; start at the configured budget and can
    // be raised mid-run by an onBudgetExceeded handler so the loop continues.
    // #2412 wired TOOL_CALLS only; #2750 broadens to TURNS / DURATION /
    // TOKENS / CONSECUTIVE_TOOL using the same Stop/Extend pattern. Units
    // when handlers return Extend(newLimit):
    //   - TOOL_CALLS / TURNS / TOKENS / CONSECUTIVE_TOOL → integer count
    //   - DURATION → milliseconds (clock budget, not turn count)
    // PER_TOOL_TIMEOUT is per-call, not cumulative, so it stays unconditionally
    // throwing — extending it mid-tool would require interrupting an in-flight
    // executor, which is a different ticket.
    //
    // #2749 — when resuming, honor whichever TOOL_CALLS limit is HIGHER
    // between the snapshot's saved limit and the current agent's budget. That
    // lets the "raise the cap and resume" UX work — the agent author rebuilds
    // with a higher maxToolCalls, calls invokeSuspendResuming(resumeFrom = …),
    // and the loop honors the new ceiling. Falls back to the saved snapshot
    // value when the agent's budget hasn't been raised (preserves the #2416
    // contract that the snapshot is authoritative for the loop counters
    // including the running limit raised via Extend mid-flight).
    var toolCallLimit = if (resumeFrom != null) {
        maxOf(resumeFrom.toolCallLimit, budget.maxToolCalls)
    } else {
        budget.maxToolCalls
    }
    var turnLimit = budget.maxTurns
    var durationLimitNanos = budget.maxDuration.inWholeNanoseconds
    var tokenLimit: Int? = budget.maxTokens
    var consecutiveSameToolLimit: Int? = budget.maxConsecutiveSameTool
    var totalTokens = 0
    // #1740: cumulative usage across all turns. Provider reports per-turn;
    // we sum prompt and completion independently (TokenUsage.total is derived).
    var cumulativeUsage: TokenUsage? = resumeFrom?.tokensUsed
    var lastToolName: String? = null
    var consecutiveSameTool = 0
    val invocationStartNanos = System.nanoTime()

    // #966: pre-cap warning hook. Tracks which reasons already crossed the
    // threshold this invocation so we fire at most once per reason.
    val firedThresholds = mutableSetOf<BudgetReason>()
    fun maybeFireThreshold(reason: BudgetReason, usedPercent: Double) {
        val listener = agent.budgetThresholdListener ?: return
        if (reason in firedThresholds) return
        if (usedPercent < agent.budgetThreshold) return
        firedThresholds += reason
        listener(reason, usedPercent)
    }

    // #2764 — extracted Checkpoint capture-and-throw helper. Mirrors the
    // existing TOOL_CALLS Checkpoint behavior (#2749) at every cap site
    // touched by #2750's broadened Extend coverage. Captures the in-flight
    // SessionSnapshot at the turn boundary BEFORE the would-be breach,
    // delivers it via `onTurnCheckpoint` (if registered), and throws
    // [BudgetCheckpointException] so the caller can resume later via
    // `invokeSuspendResuming(input, resumeFrom = exception.snapshot)`.
    //
    // #2755 — the memory slice in the snapshot is per-agent (not the whole
    // bank), so a shared-workspace topology resume doesn't disturb other
    // agents' slots. Same contract as the turn-boundary checkpoint at the
    // end of each loop iteration.
    fun checkpointAndThrow(reason: BudgetReason, currentLimit: Int): Nothing {
        if (onTurnCheckpoint == null) {
            // No place to deliver the snapshot — Stop semantics. Matches the
            // pre-#2764 TOOL_CALLS Checkpoint fallback.
            throw BudgetExceededException(
                "Agent '${agent.name}' exceeded $reason cap ($currentLimit)",
                reason,
            )
        }
        val snapshot = agents_engine.core.SessionSnapshot(
            messages = messages.toList(),
            turns = turns,
            toolCalls = toolCalls,
            toolCallLimit = toolCallLimit,
            tokensUsed = cumulativeUsage,
            memory = agent.memoryBank?.let { b ->
                b.snapshotForAgent(agent.name)?.let { v -> mapOf(agent.name to v) }
            } ?: emptyMap(),
            requestId = runtimeContext.requestId,
            sessionId = runtimeContext.sessionId,
            manifestHash = agent.manifestHash,
        )
        onTurnCheckpoint.invoke(snapshot)
        throw BudgetCheckpointException(
            snapshot = snapshot,
            reason = reason,
            currentLimit = currentLimit,
        )
    }

    while (true) {
        val elapsedNanos = System.nanoTime() - invocationStartNanos
        if (elapsedNanos >= durationLimitNanos) {
            // #2750 — DURATION is now extendable via onBudgetExceeded(). The
            // handler returns Extend(newLimit) in MILLISECONDS; we convert
            // back to nanos for the loop counter. Stop / no handler / Extend
            // with a value ≤ current still throws (back-compat with #2412).
            val currentMillis = (durationLimitNanos / 1_000_000L).toInt().coerceAtLeast(1)
            val decision = agent.budgetExceededListener?.invoke(BudgetReason.DURATION, currentMillis)
            val newMillis = (decision as? BudgetDecision.Extend)?.newLimit
            if (newMillis != null && newMillis > currentMillis) {
                durationLimitNanos = newMillis.toLong() * 1_000_000L
                firedThresholds.remove(BudgetReason.DURATION)
            } else if (decision == BudgetDecision.Checkpoint) {
                // #2764 — DURATION Checkpoint mirrors TOOL_CALLS Checkpoint.
                checkpointAndThrow(BudgetReason.DURATION, currentMillis)
            } else {
                throw BudgetExceededException(
                    "Agent '${agent.name}' exceeded duration budget of ${budget.maxDuration}",
                    BudgetReason.DURATION,
                )
            }
        }
        if (turns >= turnLimit) {
            // #2750 — TURNS is now extendable. Same Stop/Extend semantics.
            val decision = agent.budgetExceededListener?.invoke(BudgetReason.TURNS, turnLimit)
            val newLimit = (decision as? BudgetDecision.Extend)?.newLimit
            if (newLimit != null && newLimit > turnLimit) {
                turnLimit = newLimit
                firedThresholds.remove(BudgetReason.TURNS)
            } else if (decision == BudgetDecision.Checkpoint) {
                // #2764 — TURNS Checkpoint mirrors TOOL_CALLS Checkpoint.
                checkpointAndThrow(BudgetReason.TURNS, turnLimit)
            } else {
                throw BudgetExceededException(
                    "Agent '${agent.name}' exceeded budget of $turnLimit turns",
                    BudgetReason.TURNS,
                )
            }
        }

        // Threshold check before the next chat — DURATION is wall-clock, so
        // it can cross the threshold purely by waiting (e.g., on a slow tool).
        // TURNS / TOOL_CALLS / TOKENS thresholds get checked just after their
        // accumulator updates below.
        maybeFireThreshold(
            BudgetReason.DURATION,
            elapsedNanos.toDouble() / durationLimitNanos,
        )

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

        val turnIndex = turns + 1
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
        val response = chatOrStream(
            client = client,
            messages = messages,
            agentId = agent.name,
            skillName = skill.name,
            emitter = emitter,
            jsonSchema = constrainedOutputSchema,
        )
        turns++
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
        maybeFireThreshold(BudgetReason.TURNS, turns.toDouble() / turnLimit)

        // #963: accumulate tokens only when the provider reported usage —
        // a missing `tokenUsage` does NOT count as zero toward the cap.
        // Check after the round-trip so the LAST turn's tokens are counted
        // even if it tips us over: the throw still surfaces the breach.
        responseUsage?.let { usage ->
            agent.fireTokenUsage(usage)
            totalTokens += usage.total
            // #1740 / #2867: build cumulative TokenUsage for the event surface.
            // Routes through TokenUsage.plus so reasoningTokens (audited drop)
            // and any future field are picked up automatically.
            cumulativeUsage = cumulativeUsage?.plus(usage) ?: usage
            val cap = tokenLimit
            if (cap != null) {
                maybeFireThreshold(BudgetReason.TOKENS, totalTokens.toDouble() / cap)
                if (totalTokens > cap) {
                    // #2750 — TOKENS is now extendable. Same Stop/Extend semantics.
                    val decision = agent.budgetExceededListener?.invoke(BudgetReason.TOKENS, cap)
                    val newLimit = (decision as? BudgetDecision.Extend)?.newLimit
                    if (newLimit != null && newLimit > cap) {
                        tokenLimit = newLimit
                        firedThresholds.remove(BudgetReason.TOKENS)
                    } else if (decision == BudgetDecision.Checkpoint) {
                        // #2764 — TOKENS Checkpoint mirrors TOOL_CALLS Checkpoint.
                        checkpointAndThrow(BudgetReason.TOKENS, cap)
                    } else {
                        throw BudgetExceededException(
                            "Agent '${agent.name}' exceeded token budget of $cap (used $totalTokens)",
                            BudgetReason.TOKENS,
                        )
                    }
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
            is LlmResponse.ToolCalls -> {
                // #2656 — Rolling conversation: anchor a cache breakpoint at
                // each turn boundary so the growing prefix keeps hitting.
                // Off by default; opt-in via `caching { cacheConversation = Rolling }`.
                val convHint = if (
                    agent.cacheConfig.enabled &&
                    agent.cacheConfig.cacheConversation == CacheConversation.Rolling
                ) {
                    CacheHint(segment = CacheSegment.Conversation, ttl = agent.cacheConfig.ttl)
                } else null
                messages.add(LlmMessage("assistant", "", response.calls, cacheHint = convHint))
                for (call in response.calls) {
                    if (toolCalls >= toolCallLimit) {
                        // #2412 — give an onBudgetExceeded handler the chance to raise
                        // the cap and continue instead of throwing.
                        // #2749 — also accept BudgetDecision.Checkpoint: capture
                        // the current SessionSnapshot, deliver it via
                        // onTurnCheckpoint (if registered), and throw
                        // BudgetCheckpointException so the caller can resume
                        // later with a larger budget via invokeSuspendResuming.
                        val decision = agent.budgetExceededListener
                            ?.invoke(BudgetReason.TOOL_CALLS, toolCallLimit)
                        val newLimit = (decision as? agents_engine.model.BudgetDecision.Extend)?.newLimit
                        if (newLimit != null && newLimit > toolCallLimit) {
                            toolCallLimit = newLimit
                            // Re-arm the pre-cap warning so it fires again toward the new cap.
                            firedThresholds.remove(BudgetReason.TOOL_CALLS)
                        } else if (decision == agents_engine.model.BudgetDecision.Checkpoint) {
                            // #2749 / #2764 — capture and throw via the shared
                            // helper. Falls back to BudgetExceededException
                            // when onTurnCheckpoint is null (Stop semantics).
                            checkpointAndThrow(BudgetReason.TOOL_CALLS, toolCallLimit)
                        } else {
                            // No handler, Stop, or Extend that didn't raise the limit.
                            throw BudgetExceededException(
                                "Agent '${agent.name}' exceeded tool-call budget of $toolCallLimit",
                                BudgetReason.TOOL_CALLS,
                            )
                        }
                    }
                    toolCalls++
                    maybeFireThreshold(
                        BudgetReason.TOOL_CALLS,
                        toolCalls.toDouble() / toolCallLimit,
                    )
                    // #969: trip on repeated invocation of the same tool. Counter
                    // tracks consecutive calls regardless of turn boundary — what
                    // matters is "no other tool came between," not "in the same turn."
                    if (call.name == lastToolName) consecutiveSameTool++
                    else { lastToolName = call.name; consecutiveSameTool = 1 }
                    consecutiveSameToolLimit?.let { cap ->
                        if (consecutiveSameTool > cap) {
                            // #2750 — CONSECUTIVE_TOOL is now extendable.
                            val decision = agent.budgetExceededListener?.invoke(BudgetReason.CONSECUTIVE_TOOL, cap)
                            val newLimit = (decision as? BudgetDecision.Extend)?.newLimit
                            if (newLimit != null && newLimit > cap) {
                                consecutiveSameToolLimit = newLimit
                            } else if (decision == BudgetDecision.Checkpoint) {
                                // #2764 — CONSECUTIVE_TOOL Checkpoint mirrors TOOL_CALLS.
                                checkpointAndThrow(BudgetReason.CONSECUTIVE_TOOL, cap)
                            } else {
                                throw BudgetExceededException(
                                    "Agent '${agent.name}' invoked tool '${call.name}' $consecutiveSameTool times in a row (cap: $cap)",
                                    BudgetReason.CONSECUTIVE_TOOL,
                                )
                            }
                        }
                    }
                    val isKnowledge = call.name in knowledgeToolMap
                    val tool = allowedToolMap[call.name]
                    if (tool == null) {
                        // #2476 — the LLM emitted a tool name that isn't in the
                        // skill's allowed set (hallucinated, or a tool that
                        // belongs to a different skill on the same agent). Don't
                        // throw — that kills the loop and the model never gets
                        // to retry. Append a tool-result message naming the
                        // unknown call and listing the allowed tools, and let
                        // the loop continue. The model can now self-correct on
                        // the next turn.
                        val allowedList = allowedToolMap.keys.toList()
                        val unknownToolMessage =
                            "ERROR: Tool '${call.name}' is unknown for skill '${skill.name}'. " +
                                "Allowed tools: ${allowedList.joinToString(", ")}. " +
                                "Pick one of the allowed tools or return a final text answer."
                        // #2757 — first-class audit signal: hallucinated tool is
                        // a different event from policy-denied or execution error.
                        // Fires before the recovery message goes into context, so
                        // an auditor sees the rejection on the same wall-clock
                        // ordering as the streaming ToolCallFinished(isError=true).
                        // Allowed list deliberately bounded by the skill (not the
                        // wider agent.toolMap) — same boundary as the message.
                        agent.toolHallucinatedListener?.invoke(call.name, call.arguments, allowedList)
                        emitToolFinished(emitter, agent, call, unknownToolMessage, isError = true)
                        messages.add(LlmMessage("tool", unknownToolMessage))
                        continue
                    }
                    var effectiveCall = call
                    var denied = false
                    var deniedReason: String? = null
                    val result = try {
                        when (val decision = agent.decideBeforeToolCall(call.name, call.arguments)) {
                            Decision.Proceed -> executeToolWithBudgetHandlingEvents(
                                agent, tool, effectiveCall, budget, emitter
                            )
                            is Decision.ProceedWith -> {
                                effectiveCall = call.copy(
                                    arguments = decision.replacement,
                                    rawArguments = null,
                                    invalidArgumentsError = null,
                                )
                                executeToolWithBudgetHandlingEvents(agent, tool, effectiveCall, budget, emitter)
                            }
                            is Decision.Deny -> {
                                denied = true
                                deniedReason = decision.reason
                                ToolResultRendering.formatDeniedToolError(call.name, decision.reason)
                            }
                            is Decision.Substitute<*> -> decision.result
                        }
                    } catch (signal: agents_engine.core.PendingInterruptSignal) {
                        // #2488 — HITL interrupt. Build the snapshot at the
                        // pre-tool-result boundary (messages contain the
                        // assistant tool-calls turn that emitted this call but
                        // NOT a tool result for it yet — the result will be
                        // synthesised on resume from `resumeWith`). Fire
                        // onTurnCheckpoint with the snapshot before throwing
                        // so the caller can persist via the same wire path
                        // as a budget Checkpoint.
                        // #2489 — if the payload is an ApprovalRequest (from
                        // humanApproval { }), fire the dedicated audit event.
                        val payload = signal.payload
                        if (payload is agents_engine.core.ApprovalRequest) {
                            withAgentRuntimeContext(runtimeContext) {
                                agent.approvalRequestedListener?.invoke(
                                    payload.title,
                                    payload.body != null,
                                    payload.timeout?.inWholeMilliseconds,
                                )
                            }
                        }
                        val snapshot = agents_engine.core.SessionSnapshot(
                            messages = messages.toList(),
                            turns = turns,
                            toolCalls = toolCalls,
                            toolCallLimit = toolCallLimit,
                            tokensUsed = cumulativeUsage,
                            memory = agent.memoryBank?.let { b ->
                                b.snapshotForAgent(agent.name)?.let { v -> mapOf(agent.name to v) }
                            } ?: emptyMap(),
                            requestId = runtimeContext.requestId,
                            sessionId = runtimeContext.sessionId,
                            manifestHash = agent.manifestHash,
                            pendingInterruptCallId = effectiveCall.callId ?: "interrupt-${turns}-${toolCalls}",
                        )
                        onTurnCheckpoint?.invoke(snapshot)
                        throw agents_engine.core.AgentInterruptException(
                            snapshot = snapshot,
                            payload = signal.payload,
                            pendingToolCallId = snapshot.pendingInterruptCallId,
                        )
                    }

                    if (denied) {
                        // #2395 — a blocked call never reaches onToolUse, so fire the
                        // first-class onToolDenied hook here (under the runtime context
                        // so requestId/sessionId/manifestHash correlate). This is what
                        // observe{} turns into PipelineEvent.ToolDenied; without it,
                        // audit logs built on observe{}/onToolUse silently drop denials.
                        withAgentRuntimeContext(runtimeContext) {
                            agent.toolDeniedListener?.invoke(
                                effectiveCall.name,
                                effectiveCall.arguments,
                                deniedReason ?: "",
                            )
                        }
                        emitToolFinished(emitter, agent, effectiveCall, result, isError = true)
                    } else {
                        if (isKnowledge) {
                            withAgentRuntimeContext(runtimeContext) {
                                agent.knowledgeUsedListener?.invoke(call.name, result?.toString() ?: "")
                            }
                        } else {
                            withAgentRuntimeContext(runtimeContext) {
                                agent.toolUseListener?.invoke(call.name, effectiveCall.arguments, result)
                            }
                        }
                        // #1739: emit ToolCallFinished on the success path with the
                        // executor's return value. callId is the one the streaming
                        // aggregator stamped on this ToolCall — null only when the
                        // emitter is null (no event work needed) or the non-streaming
                        // path produced a ToolCall without one.
                        emitToolFinished(emitter, agent, effectiveCall, result, isError = false)
                    }
                    val rendered = ToolResultRendering.renderToolResultForLlm(result)
                    val toolMessage = if (!denied && tool.untrustedOutput) {
                        ToolResultRendering.wrapUntrustedToolResult(tool.name, rendered)
                    } else {
                        rendered
                    }
                    messages.add(LlmMessage("tool", toolMessage))
                }
            }
        }
        // #2416 — turn-boundary checkpoint. Text responses return above; only
        // tool-turns reach here, with messages settled and no half-run tool.
        onTurnCheckpoint?.invoke(
            agents_engine.core.SessionSnapshot(
                messages = messages.toList(),
                turns = turns,
                toolCalls = toolCalls,
                toolCallLimit = toolCallLimit,
                tokensUsed = cumulativeUsage,
                // #2755 — snapshot only THIS agent's slot in a (possibly shared)
                // bank. The pre-#2755 `bank.entries()` dump included every other
                // agent's slot — leaking unrelated data into the snapshot file
                // and breaking the namespaced-restore guarantee.
                memory = agent.memoryBank?.let { b ->
                    b.snapshotForAgent(agent.name)?.let { v -> mapOf(agent.name to v) }
                } ?: emptyMap(),
                requestId = runtimeContext.requestId,
                sessionId = runtimeContext.sessionId,
                manifestHash = agent.manifestHash,
            ),
        )
    }
}


private suspend fun <IN> executeToolWithBudgetHandlingEvents(
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
