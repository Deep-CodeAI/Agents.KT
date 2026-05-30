package agents_engine.core

import agents_engine.model.BudgetBuilder
import agents_engine.model.BudgetConfig
import agents_engine.model.BudgetReason
import agents_engine.model.CacheBuilder
import agents_engine.model.CacheConfig
import agents_engine.model.ToolChoice
import agents_engine.model.ModelBuilder
import agents_engine.model.ModelConfig
import agents_engine.model.OnErrorBuilder
import agents_engine.model.TokenUsage
import agents_engine.model.ToolDef
import agents_engine.model.ToolErrorHandler
import agents_engine.model.ToolsBuilder
import agents_engine.model.buildBuiltInTools
import agents_engine.model.executeAgentic
import agents_engine.model.selectSkillByLlm
import agents_engine.runtime.events.AgentEvent
import java.util.logging.Level
import java.util.logging.Logger

/**
 * `agents_engine/core/Agent.kt` — the typed-agent class. One input type,
 * one output type, one job. Type mismatches at composition boundaries
 * are caught by the compiler; structural misuses (duplicate placements,
 * mutation after freeze) fail fast at construction time.
 *
 * **Construction.** Built through the `agent { }` DSL, never via direct
 * constructor calls. After construction `validate()` runs and the agent
 * is frozen — skills, tools, knowledge, and observability hooks are
 * read-only. Mutation attempts throw `IllegalStateException`.
 *
 * **Invocation surfaces.** Three entry points, all routing through the
 * same skill-resolution + agentic loop:
 * - [invoke] / `agent(input)` — blocking, returns `OUT`.
 * - [invokeSuspend] — suspending, returns `OUT`. Use from coroutines so
 *   parent-scope cancellation + `withTimeout` propagate cleanly.
 * - `agent.session(input)` (extension in `agents_engine.runtime.events`) —
 *   returns `AgentSession<OUT>` with cold `events: Flow<AgentEvent<OUT>>`
 *   and `suspend fun await()`. The v0.5.0+ streaming surface.
 *
 * **Single-placement rule.** A given `Agent` instance may be wired into
 * AT MOST one structure (`then`, `/`, `forum`, `Branch`, `Loop`,
 * `wrap`, `Swarm`). A second placement throws `IllegalArgumentException`.
 *
 * **Observability hooks (post-hoc PipelineEvent).** Separate from
 * `AgentEvent` (the streaming session surface): `onSkillChosen`,
 * `onToolUse`, `onKnowledgeUsed`, `onError`, `onBudgetThreshold`,
 * `onTokenUsage`, and the unified `observe { event -> }` sealed-event view.
 * Before-interceptor hooks (`onBeforeSkill`, `onBeforeTurn`,
 * `onBeforeToolCall`) return [Decision] to deny, mutate, or substitute before
 * the selected operation runs (#1907).
 *
 * **Internal session entry point.** [invokeSuspendForSession] is the
 * streaming-aware variant called only by `Agent.session(input)` and
 * composition operators. Existing [invokeSuspend] delegates to it
 * with a no-op emitter — byte-for-byte unchanged non-streaming behavior.
 *
 * See `src/main/resources/internals-agent/core/Agent.md` for the
 * extended adjunct surfaced to IDE-side LLM tools via the
 * `agents-kt-internals` MCP server (#1837 / #1838).
 */
class Agent<IN, OUT>(
    val name: String,
    val outType: kotlin.reflect.KClass<*>,
    private val castOut: (Any?) -> OUT,
) {
    private val _skills = mutableMapOf<String, Skill<*, *>>()
    private val _skillsView: Map<String, Skill<*, *>> = java.util.Collections.unmodifiableMap(_skills)
    /**
     * Read-only view of all skills registered on this agent. Mutation goes through
     * [skills] { } DSL block; direct map mutation (or downcast-then-mutate) would
     * bypass uniqueness checks and the construction-time invariants — see #667.
     */
    val skills: Map<String, Skill<*, *>> get() = _skillsView
    private val executors = mutableMapOf<String, (Any?) -> Any>()
    private var placedIn: String? = null
    var prompt: String = ""
        private set

    var modelConfig: ModelConfig? = null
        private set
    var budgetConfig: BudgetConfig = BudgetConfig()
        private set
    /**
     * Vendor-neutral prompt-caching configuration (#2656). Default is the
     * production-friendly profile in [CacheConfig]: system prompt + tool
     * defs cached, conversation rolling off. Override via `caching { }`.
     */
    var cacheConfig: CacheConfig = CacheConfig()
        private set

    /**
     * #2479 part 2 — provider-neutral tool-choice control. Default
     * [ToolChoice.Auto] preserves pre-#2479-pt2 behaviour (the field is
     * omitted from the request body). Set via `agent { toolChoice =
     * ToolChoice.Required }` or `toolChoice = ToolChoice.Specific("write")`.
     * Adapters translate per provider; see [ToolChoice] for the wire table.
     */
    var toolChoice: ToolChoice = ToolChoice.Auto
        private set
    private val _toolMap: MutableMap<String, ToolDef> = mutableMapOf()
    private val _toolMapView: Map<String, ToolDef> = java.util.Collections.unmodifiableMap(_toolMap)
    /**
     * Read-only view of all tools registered on this agent. Mutation goes through
     * [registerTool] / [registerBuiltInTool] / [unregisterTool] (internal API)
     * so the framework's guards (reserved names, uniqueness) are always applied.
     * Direct map mutation (or downcast-then-mutate) would bypass the runtime
     * authorization model — see #659.
     */
    val toolMap: Map<String, ToolDef> get() = _toolMapView

    /**
     * Internal API for tools that pass through user DSL guards (reservation +
     * uniqueness). MCP DSL, ToolsBuilder result merging, and other code paths
     * that surface tools the user named call this. Freeze-checked: closes the
     * post-construction `agent.mcp { server(...) }` bypass that #697 missed
     * (see #708). `registerBuiltInTool` and `unregisterTool` remain unguarded
     * because Forum needs them for runtime captain rotation.
     */
    internal fun registerTool(def: ToolDef) {
        checkNotFrozen()
        require(def.name !in agents_engine.model.RESERVED_MEMORY_TOOL_NAMES) {
            "Tool name \"${def.name}\" is reserved for built-in memory tools (registered via memory(bank))."
        }
        require(def.name !in _toolMap) {
            "Agent \"$name\" already has a tool named \"${def.name}\". Tool names must be unique."
        }
        _toolMap[def.name] = def
    }

    /**
     * Internal API for built-ins allowed to use reserved names (memory_*, forum_return,
     * escalate, throwException). Idempotent via putIfAbsent — repeated registration of
     * the same built-in is a no-op.
     */
    @PublishedApi
    internal fun registerBuiltInTool(def: ToolDef) {
        _toolMap.putIfAbsent(def.name, def)
    }

    /** Internal API to remove a tool (e.g., Forum cleaning up forum_return on captain change). */
    internal fun unregisterTool(name: String) { _toolMap.remove(name) }
    var toolUseListener: ((name: String, args: Map<String, Any?>, result: Any?) -> Unit)? = null
        private set
    /**
     * Fires when an `onBeforeToolCall` interceptor returns [Decision.Deny] and
     * the call is blocked before its executor runs (#2395). Parallel to
     * [toolUseListener]: blocked attempts are first-class observable so an
     * audit log catches them even on the non-streaming path. [toolUseListener]
     * deliberately does NOT fire for a denied call (no executor ran).
     */
    var toolDeniedListener: ((name: String, args: Map<String, Any?>, reason: String) -> Unit)? = null
        private set
    /**
     * #2757 — fires when the model emits a tool name that is NOT in the
     * current skill's allowlist (hallucinated, or a tool that belongs to a
     * different skill on the same agent). The runtime appends a tool-result
     * error to context and continues (per #2476), but this listener is the
     * first-class audit signal: hallucinated tools are distinct evidence
     * from policy-denied tools or execution errors, and auditors want to
     * grep by reason rather than parsing error message bodies.
     *
     * `allowedTools` is the skill's allowlist for this turn — same set the
     * recovery message exposes to the model. Does NOT leak the wider
     * `agent.toolMap`.
     */
    var toolHallucinatedListener: ((name: String, args: Map<String, Any?>, allowedTools: List<String>) -> Unit)? = null
        private set
    private val tokenUsageListeners = mutableListOf<(TokenUsage) -> Unit>()
    var knowledgeUsedListener: ((name: String, content: String) -> Unit)? = null
        private set
    var skillChosenListener: ((name: String) -> Unit)? = null
        private set
    var memoryBank: MemoryBank? = null
        private set
    var routerRationaleListener: ((rationale: String) -> Unit)? = null
        private set
    /**
     * Fires when an infrastructure error is about to propagate out of an agentic
     * invocation — LLM transport failures, response parse failures, budget
     * exceptions, skill-routing failures, etc. Pure observability: the original
     * exception is always rethrown after the listener runs. See #962.
     *
     * Distinct from [onToolError], which is per-tool *semantic* recovery and
     * can substitute a value or repaired arguments for the failure.
     */
    var errorListener: ((Throwable) -> Unit)? = null
        private set
    /**
     * Pre-cap warning hook (#966). Fires once per [BudgetReason] when cumulative
     * usage of that cap crosses [budgetThreshold]. Lets the user wrap up
     * gracefully before [agents_engine.model.BudgetExceededException] gets thrown.
     *
     * Cumulative reasons only — TURNS, TOOL_CALLS, DURATION, TOKENS.
     * `PER_TOOL_TIMEOUT` is per-call so a percentage doesn't apply.
     * TOKENS only fires when both `budget.maxTokens` is set AND the provider
     * reports `tokenUsage` on the response.
     */
    var budgetThreshold: Double = 0.8
        private set
    var budgetThresholdListener: ((reason: BudgetReason, usedPercent: Double) -> Unit)? = null
        private set
    /**
     * Hard-cap decision hook (#2412). When a budget cap would throw, this is
     * consulted with the reason and the current limit; returning
     * [agents_engine.model.BudgetDecision.Extend] raises the limit and continues,
     * [agents_engine.model.BudgetDecision.Stop] (or no listener) throws. Currently
     * wired for the tool-call cap. Settable post-construction like other listeners.
     */
    var budgetExceededListener: ((reason: BudgetReason, currentLimit: Int) -> agents_engine.model.BudgetDecision)? = null
        private set
    var skillSelectionConfidenceThreshold: Double = 0.6
        private set
    private var skillSelector: ((IN) -> String)? = null
    private val beforeSkillInterceptors = mutableListOf<(String) -> Decision<String>>()
    private val beforeToolCallInterceptors =
        mutableListOf<(name: String, args: Map<String, Any?>) -> Decision<Map<String, Any?>>>()
    private val beforeTurnInterceptors = mutableListOf<(List<ChatMessage>) -> Decision<List<ChatMessage>>>()
    private val interceptorDecisionListeners = mutableListOf<(InterceptorPoint, Decision<*>) -> Unit>()
    private val agentEventListeners = mutableListOf<(AgentEvent<*>) -> Unit>()
    private val toolErrorHandlers: MutableMap<String, ToolErrorHandler> = mutableMapOf()
    internal var manifestHash: String? = null
        private set
    internal var defaultToolErrorHandler: ToolErrorHandler? = null
        private set
    internal val autoToolNames: MutableSet<String> = mutableSetOf()

    val beforeSkillInterceptorCount: Int
        get() = beforeSkillInterceptors.size

    val beforeToolCallInterceptorCount: Int
        get() = beforeToolCallInterceptors.size

    val beforeTurnInterceptorCount: Int
        get() = beforeTurnInterceptors.size

    val tokenUsageListenerCount: Int
        get() = tokenUsageListeners.size

    /**
     * Set true at end of [validate] (#697). Structural mutators (skills, tools,
     * memory, model, budget, prompt, error handlers, routing config) check this
     * and refuse post-construction mutation. Listeners (onToolUse, onTokenUsage,
     * onKnowledgeUsed, onSkillChosen, routerRationale) intentionally remain settable for
     * tracing / instrumentation use cases. Before-interceptors follow the same
     * listener-shaped post-freeze rule because they are runtime policy, not
     * structural graph mutation (#1907).
     */
    @PublishedApi internal var frozen: Boolean = false

    private fun checkNotFrozen() {
        check(!frozen) {
            "Agent \"$name\" is frozen — cannot mutate after construction. " +
                "Configure inside the agent { } block, not after."
        }
    }

    fun prompt(text: String) { checkNotFrozen(); prompt = text }

    fun attachManifestHash(hash: String?) {
        manifestHash = hash
    }

    fun model(block: ModelBuilder.() -> Unit) {
        checkNotFrozen()
        val builder = ModelBuilder()
        builder.block()
        modelConfig = builder.build()
    }

    fun budget(block: BudgetBuilder.() -> Unit) {
        checkNotFrozen()
        val builder = BudgetBuilder()
        builder.block()
        budgetConfig = builder.build()
    }

    /**
     * `caching { }` DSL slot — declarative, vendor-neutral prompt-caching
     * control (#2656). Adapters that can't honour a hint (e.g. providers
     * with no caching surface) silently no-op. See [CacheConfig] for the
     * knobs and defaults.
     */
    fun caching(block: CacheBuilder.() -> Unit) {
        checkNotFrozen()
        val builder = CacheBuilder()
        builder.block()
        cacheConfig = builder.build()
    }

    /**
     * #2479 part 2 — DSL slot for vendor-neutral [ToolChoice]. Used as
     * `agent { toolChoice(ToolChoice.Required) }` or `toolChoice(ToolChoice
     * .Specific("write_file"))`. Default is [ToolChoice.Auto] which preserves
     * pre-#2479-pt2 wire behaviour (field omitted from the request body).
     *
     * [ToolChoice.Specific.name] validation is deferred to `validate()` so
     * the tool registry is fully populated before we check membership.
     */
    fun toolChoice(choice: ToolChoice) {
        checkNotFrozen()
        toolChoice = choice
    }

    fun onToolUse(block: (name: String, args: Map<String, Any?>, result: Any?) -> Unit) {
        toolUseListener = block
    }

    /**
     * Observe tool calls blocked by an `onBeforeToolCall` [Decision.Deny] (#2395).
     * `reason` is the denial reason returned to the model. Fires instead of
     * [onToolUse] (the executor never ran). Like the other listener slots, it
     * remains settable after construction for instrumentation.
     */
    fun onToolDenied(block: (name: String, args: Map<String, Any?>, reason: String) -> Unit) {
        toolDeniedListener = block
    }

    /**
     * #2757 — Observe tool-name hallucinations: the LLM emitted a tool name
     * not in the active skill's allowlist. Fires once per rejected call,
     * before the recovery message is appended. The runtime still recovers
     * (per #2476) — this listener is pure observability for auditors who
     * need to distinguish hallucinations from policy denials. Settable
     * after construction like the other listener slots.
     */
    fun onToolHallucinated(block: (name: String, args: Map<String, Any?>, allowedTools: List<String>) -> Unit) {
        toolHallucinatedListener = block
    }

    /**
     * Observe provider-reported token usage for each successful LLM round-trip.
     *
     * Semantics:
     * - Fires once per LLM response carrying usage, not once per agent invocation.
     *   Tool-use cycles can therefore fire more than once.
     * - Fires after the provider response is parsed and before tool callbacks for
     *   that same turn.
     * - Does not fire when the LLM call throws; pair with [onError] for failures.
     * - Streaming providers fire once at end-of-stream with their final usage.
     * - Listener failures are logged and swallowed so user telemetry cannot break
     *   the agent run.
     * - Multiple registrations are invoked in registration order.
     *
     * Provider adapters normalize usage into [TokenUsage]. Providers that do not
     * report cache reads set `cachedInputTokens = null`; successful responses
     * with no usage payload do not fire.
     *
     * Provider mapping:
     * - Anthropic: `usage.input_tokens`, `usage.output_tokens`,
     *   `usage.cache_read_input_tokens` → `provider = "claude"`.
     * - OpenAI: `usage.prompt_tokens`, `usage.completion_tokens`,
     *   `usage.prompt_tokens_details.cached_tokens` → `provider = "openai"`.
     * - Ollama: `prompt_eval_count`, `eval_count`, no cache field
     *   → `provider = "ollama"`.
     */
    fun onTokenUsage(block: (TokenUsage) -> Unit) {
        tokenUsageListeners += block
    }

    internal fun fireTokenUsage(usage: TokenUsage) {
        tokenUsageListeners.toList().forEach { listener ->
            try {
                listener(usage)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "onTokenUsage listener failed; swallowing", t)
            }
        }
    }

    fun onKnowledgeUsed(block: (name: String, content: String) -> Unit) {
        knowledgeUsedListener = block
    }

    fun onSkillChosen(block: (name: String) -> Unit) {
        skillChosenListener = block
    }

    fun onError(block: (Throwable) -> Unit) {
        errorListener = block
    }

    /**
     * Register a pre-cap budget warning. Fires once per [BudgetReason] when
     * cumulative usage crosses [threshold] (a fraction in `[0.0, 1.0]`).
     * Default threshold is 0.8. See #966.
     */
    fun onBudgetThreshold(threshold: Double = 0.8, block: (reason: BudgetReason, usedPercent: Double) -> Unit) {
        require(threshold in 0.0..1.0) {
            "onBudgetThreshold threshold must be in [0.0, 1.0]; got $threshold"
        }
        budgetThreshold = threshold
        budgetThresholdListener = block
    }

    /**
     * Register a hard-cap decision hook (#2412). When a budget cap would throw
     * [agents_engine.model.BudgetExceededException], [block] is called with the
     * [BudgetReason] and the current limit; return
     * [agents_engine.model.BudgetDecision.Extend] (with a larger limit) to raise
     * the cap and continue, or [agents_engine.model.BudgetDecision.Stop] to throw.
     * Currently consulted for the tool-call cap.
     */
    fun onBudgetExceeded(block: (reason: BudgetReason, currentLimit: Int) -> agents_engine.model.BudgetDecision) {
        budgetExceededListener = block
    }

    fun onBeforeSkill(block: (skillName: String) -> Decision<String>) {
        beforeSkillInterceptors += block
    }

    fun onBeforeToolCall(block: (name: String, args: Map<String, Any?>) -> Decision<Map<String, Any?>>) {
        beforeToolCallInterceptors += block
    }

    fun onBeforeTurn(block: (messages: List<ChatMessage>) -> Decision<List<ChatMessage>>) {
        beforeTurnInterceptors += block
    }

    fun onInterceptorDecision(block: (point: InterceptorPoint, decision: Decision<*>) -> Unit) {
        interceptorDecisionListeners += block
    }

    fun onAgentEvent(block: (AgentEvent<*>) -> Unit) {
        agentEventListeners += block
    }

    internal fun fireAgentEvent(event: AgentEvent<*>) {
        agentEventListeners.toList().forEach { listener ->
            try {
                listener(event)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "onAgentEvent listener failed; swallowing", t)
            }
        }
    }

    internal fun decideBeforeSkill(skillName: String): Decision<String> {
        val interceptors = beforeSkillInterceptors.toList()
        val decision = runDecisionChain(skillName, interceptors)
        fireInterceptorDecision(InterceptorPoint.BeforeSkill, decision, interceptors.isNotEmpty())
        return decision
    }

    internal fun decideBeforeToolCall(name: String, args: Map<String, Any?>): Decision<Map<String, Any?>> {
        val interceptors = beforeToolCallInterceptors.toList()
        var current = args
        var effective: Decision<Map<String, Any?>> = Decision.Proceed

        interceptors.forEach { interceptor ->
            val decision = try {
                interceptor(name, current)
            } catch (t: Throwable) {
                Decision.Deny(t.message ?: t.toString())
            }

            if (effective is Decision.Proceed) {
                effective = decision
                if (decision is Decision.ProceedWith<*>) {
                    @Suppress("UNCHECKED_CAST")
                    current = decision.replacement as Map<String, Any?>
                }
            }
        }

        fireInterceptorDecision(InterceptorPoint.BeforeToolCall, effective, interceptors.isNotEmpty())
        return effective
    }

    internal fun decideBeforeTurn(messages: List<ChatMessage>): Decision<List<ChatMessage>> {
        val interceptors = beforeTurnInterceptors.toList()
        val decision = runDecisionChain(messages, interceptors)
        fireInterceptorDecision(InterceptorPoint.BeforeTurn, decision, interceptors.isNotEmpty())
        return decision
    }

    private fun fireInterceptorDecision(
        point: InterceptorPoint,
        decision: Decision<*>,
        hasInterceptors: Boolean,
    ) {
        if (!hasInterceptors) return
        interceptorDecisionListeners.toList().forEach { listener ->
            try {
                listener(point, decision)
            } catch (t: Throwable) {
                LOGGER.log(Level.WARNING, "onInterceptorDecision listener failed; swallowing", t)
            }
        }
    }

    fun skillSelection(block: (IN) -> String) {
        checkNotFrozen()
        skillSelector = block
    }

    fun routerRationale(block: (rationale: String) -> Unit) { routerRationaleListener = block }

    fun skillSelectionConfidenceThreshold(threshold: Double) {
        checkNotFrozen()
        require(threshold in 0.0..1.0) { "Threshold must be in [0.0, 1.0]; got $threshold" }
        skillSelectionConfidenceThreshold = threshold
    }

    fun memory(bank: MemoryBank) {
        checkNotFrozen()
        memoryBank = bank
        for (tool in buildMemoryTools(bank, name)) {
            registerBuiltInTool(tool)
        }
    }

    fun tools(block: ToolsBuilder.() -> Unit) {
        checkNotFrozen()
        val builder = ToolsBuilder()
        builder.block()
        builder.defs.forEach { registerTool(it) }
        builder.defaultErrorHandler?.let { defaultToolErrorHandler = it }
    }

    fun onToolError(toolName: String, block: OnErrorBuilder.() -> Unit) {
        checkNotFrozen()
        val builder = OnErrorBuilder()
        builder.block()
        toolErrorHandlers[toolName] = builder.build()
    }

    fun getToolErrorHandler(toolName: String): ToolErrorHandler? =
        toolMap[toolName]?.errorHandler
            ?: toolErrorHandlers[toolName]
            ?: defaultToolErrorHandler

    internal fun enableAutoTool(name: String) {
        autoToolNames += name
    }

    internal fun disableAutoTool(name: String) {
        autoToolNames -= name
    }

    fun markPlaced(context: String) {
        require(placedIn == null) {
            "Agent \"$name\" is already placed in $placedIn. " +
                "Each agent instance can only participate once. Create a new instance for \"$context\"."
        }
        placedIn = context
    }

    /**
     * Blocking entry point — preserved for back-compat. Routes through [invokeSuspend]
     * via a single `runBlocking` at the user-facing boundary. See #638: internal
     * composition (Pipeline / Forum / Parallel / Loop / Branch) calls [invokeSuspend]
     * directly so the framework never wraps `runBlocking` around itself.
     */
    operator fun invoke(input: IN): OUT = kotlinx.coroutines.runBlocking { invokeSuspend(input) }

    /**
     * Suspending entry point (#638). Callers in coroutine scopes — including the
     * suspending invokeSuspend on every composition operator — call this directly,
     * which lets parent-scope cancellation and `withTimeout` propagate cleanly into
     * the agentic loop. The blocking [invoke] is a thin shim over this.
     */
    suspend fun invokeSuspend(input: IN): OUT =
        withAgentRuntimeContext(newRuntimeContext()) {
            invokeSuspendForSession(input, emitter = null) { /* no-op */ }
        }

    /**
     * #2749 — public snapshot/resume seam.
     *
     * Runs the agentic loop the same way [invokeSuspend] does, but also:
     * - When [resumeFrom] is non-null, seeds the loop with the saved
     *   conversation + counters from a prior [agents_engine.core.SessionSnapshot]
     *   instead of starting fresh. Does NOT replay dialog history — the
     *   conversation continues at exactly the turn where the snapshot was
     *   captured.
     * - When [onTurnCheckpoint] is non-null, fires at every turn boundary
     *   with the current resumable state. The caller persists it (e.g.
     *   `FileSnapshotStore(...).save(dialogId, snapshot)`) so a later
     *   process restart, budget bump, or human-in-the-loop decision can
     *   resume from the latest checkpoint.
     *
     * Composes with [agents_engine.model.BudgetDecision.Checkpoint]: when
     * an `onBudgetExceeded` handler returns `Checkpoint`, this method's
     * `onTurnCheckpoint` hook receives the in-flight snapshot AND the
     * loop throws [agents_engine.model.BudgetCheckpointException] carrying
     * the same snapshot on its own field — letting the caller surface a
     * "raise the cap?" UX without losing the conversation history.
     *
     * With both parameters at their defaults (null), behavior is
     * byte-for-byte identical to [invokeSuspend] — opt-in throughout.
     *
     * @param input the user-side input for this invocation.
     * @param resumeFrom optional seed snapshot. Null = fresh run.
     * @param onTurnCheckpoint optional per-turn callback. Null = no
     *   checkpoints captured.
     */
    suspend fun invokeSuspendResuming(
        input: IN,
        resumeFrom: agents_engine.core.SessionSnapshot? = null,
        onTurnCheckpoint: ((agents_engine.core.SessionSnapshot) -> Unit)? = null,
    ): OUT =
        withAgentRuntimeContext(newRuntimeContext()) {
            invokeSuspendForSession(
                input = input,
                emitter = null,
                resumeFrom = resumeFrom,
                onTurnCheckpoint = onTurnCheckpoint,
            ) { /* no-op onSkillStarted */ }
        }

    internal fun newRuntimeContext(sessionId: String? = null): AgentRuntimeContext =
        AgentRuntimeContext(sessionId = sessionId, manifestHash = manifestHash)

    /**
     * #1736 — session-aware sibling of [invokeSuspend]. Same logic, plus an
     * extra [onSkillStarted] callback fired after skill resolution and before
     * execution. Existing `invokeSuspend` delegates with a no-op callback, so
     * backward-compat is byte-for-byte; this entry point is only called by
     * `Agent.session(input)` to surface the skill name into the event flow.
     *
     * #1739 — when [emitter] is non-null, the agentic loop streams via
     * `chatStream` and surfaces `Token` / `ToolCall*` events through it.
     * Non-agentic skills ignore the emitter (they have no LLM round-trip).
     */
    internal suspend fun invokeSuspendForSession(
        input: IN,
        emitter: agents_engine.model.AgentEventEmitter? = null,
        /**
         * #1747 — optional system-prompt override (used by the `wrap` operator).
         * When non-null, replaces `this.prompt` as the effective system prompt
         * for this invocation only. Consolidates the previous separate
         * `invokeSuspendWithPromptOverride` entry point — that one now
         * delegates here with `emitter = null`.
         */
        promptOverride: String? = null,
        /**
         * #2749 — optional seed for snapshot/resume. When non-null, the agentic
         * loop starts from this snapshot's messages + counters (and restores
         * memory) instead of from a fresh conversation. The `executeAgentic`
         * loop has carried this parameter as internal since #2416; this layer
         * is now the public seam.
         */
        resumeFrom: agents_engine.core.SessionSnapshot? = null,
        /**
         * #2749 — optional per-turn checkpoint callback. Fires at each turn
         * boundary (after the tool round completes, before the next model
         * call) with the current resumable state. Also fires when an
         * `onBudgetExceeded` handler returns [agents_engine.model.BudgetDecision.Checkpoint]
         * — that path then throws [agents_engine.model.BudgetCheckpointException]
         * carrying the same snapshot.
         */
        onTurnCheckpoint: ((agents_engine.core.SessionSnapshot) -> Unit)? = null,
        onSkillCompleted: (agents_engine.model.TokenUsage?) -> Unit = { /* no-op */ },
        onSkillStarted: (String) -> Unit,
    ): OUT {
        val runtimeContext = AgentRuntimeContext.current() ?: newRuntimeContext()
        try {
            var skill = resolveSkill(input)
            when (val decision = decideBeforeSkill(skill.name)) {
                Decision.Proceed -> Unit
                is Decision.ProceedWith -> skill = compatibleSkill(decision.replacement, input)
                is Decision.Deny -> throw InterceptorDeniedException(
                    "Skill '${skill.name}' denied by interceptor: ${decision.reason}"
                )
                is Decision.Substitute<*> -> return castOut(decision.result)
            }
            withAgentRuntimeContext(runtimeContext) {
                skillChosenListener?.invoke(skill.name)
            }
            onSkillStarted(skill.name)
            return if (skill.isAgentic) {
                val result = executeAgentic(
                    this, skill, input,
                    effectivePrompt = promptOverride ?: this.prompt,
                    emitter = emitter,
                    runtimeContext = runtimeContext,
                    resumeFrom = resumeFrom,
                    onTurnCheckpoint = onTurnCheckpoint,
                )
                // #1740: surface cumulative usage on the way out. Non-agentic
                // skills don't go through executeAgentic, so onSkillCompleted
                // stays at its default null for the implementedBy path below.
                onSkillCompleted(result.tokenUsage)
                castOut(result.output)
            } else {
                castOut(executors[skill.name]!!(input))
            }
        } catch (t: Throwable) {
            // #962: observability hook for infrastructure errors. Fires on
            // *anything* that escapes the agentic invocation — LLM transport
            // failures, response parse failures, budget exceptions, skill
            // routing errors. Listener exceptions are attached as suppressed
            // so they can never swallow the original error.
            errorListener?.let { listener ->
                try {
                    withAgentRuntimeContext(runtimeContext) {
                        listener(t)
                    }
                } catch (callbackError: Throwable) {
                    t.addSuppressed(callbackError)
                }
            }
            throw t
        }
    }

    private fun compatibleSkill(skillName: String, input: IN): Skill<*, *> {
        val selected = skills[skillName] ?: error(
            "before-skill interceptor returned unknown skill name \"$skillName\". " +
                "Available: ${skills.keys}"
        )
        check(selected.inType.java.isInstance(input) && selected.outType == outType) {
            "before-skill interceptor returned incompatible skill \"$skillName\". " +
                "Compatible skills for agent \"$name\" must accept the invocation input " +
                "and produce ${outType.simpleName}."
        }
        return selected
    }

    /**
     * #1698: Run the agentic loop with [promptOverride] in effect as the
     * system prompt, *without* mutating the agent's baked-in [prompt].
     * Used by the `wrap` operator (`teacher wrap student`).
     *
     * #1707/#3: v0.4.4 implemented this by swapping `this.prompt` and
     * restoring in a `finally` block. That's race-unsafe when the same
     * pipeline is launched from multiple coroutines — one lane's prompt
     * could land in another lane's system message. The fix routes the
     * override through `executeAgentic`'s `effectivePrompt` parameter,
     * which threads through the call stack as a local rather than via
     * the shared field.
     *
     * Falls back through the same skill-resolution / error-hook flow as
     * the normal [invokeSuspend]; only the system-message construction
     * differs (reads the override instead of `agent.prompt`).
     */
    internal suspend fun invokeSuspendWithPromptOverride(input: IN, promptOverride: String): OUT {
        // #1747 — consolidated into invokeSuspendForSession. emitter = null
        // preserves the non-streaming behavior the wrap operator used pre-
        // step 4; the streaming variant goes through runAgentInSession with
        // the same promptOverride parameter.
        return withAgentRuntimeContext(newRuntimeContext()) {
            invokeSuspendForSession(
                input = input,
                emitter = null,
                promptOverride = promptOverride,
            ) { /* no-op onSkillStarted */ }
        }
    }

    private suspend fun resolveSkill(input: IN): Skill<*, *> {
        val candidates = skills.values.filter {
            it.inType.java.isInstance(input) && it.outType == outType
        }

        skillSelector?.let { selector ->
            val selectedName = selector(input)
            val selected = skills[selectedName] ?: error(
                "skillSelection returned unknown skill name \"$selectedName\". " +
                    "Available: ${skills.keys}"
            )
            check(selected in candidates) {
                "skillSelection returned incompatible skill \"$selectedName\". " +
                    "Compatible skills for agent \"$name\": ${candidates.map { it.name }}"
            }
            return selected
        }

        return when {
            candidates.isEmpty() -> error(
                "Agent \"$name\" has no skill for ${outType.simpleName}. " +
                    "Add a skill with implementedBy { } block."
            )
            candidates.size == 1 -> candidates.single()
            modelConfig != null -> {
                val route = selectSkillByLlm(this, candidates, input)
                if (route.confidence < skillSelectionConfidenceThreshold) {
                    throw SkillRoutingException(
                        "Router uncertain (confidence=${route.confidence}, threshold=$skillSelectionConfidenceThreshold). " +
                            "Rationale: ${route.rationale}"
                    )
                }
                val selected = candidates.find { it.name == route.skillName }
                    ?: throw SkillRoutingException(
                        "LLM router selected unknown skill \"${route.skillName}\". " +
                            "Available: ${candidates.map { it.name }}. Rationale: ${route.rationale}"
                    )
                if (route.rationale.isNotEmpty()) routerRationaleListener?.invoke(route.rationale)
                selected
            }
            else -> candidates.first()
        }
    }

    fun skills(block: SkillsBuilder.() -> Unit) {
        checkNotFrozen()
        val builder = SkillsBuilder()
        builder.block()
        builder.entries.forEach { (skill, exec) ->
            require(skill.name !in _skills) {
                "Agent \"$name\" already has a skill named \"${skill.name}\". " +
                    "Skill names must be unique per agent."
            }
            _skills[skill.name] = skill
            if (skill.outType == outType && !skill.isAgentic) executors[skill.name] = exec
        }
    }

    /**
     * Single-line identifier used in logs and stack traces. See #970.
     * Replaces the default JVM `Object#toString` (`Agent@5e91993f`) with
     * something a reader can parse at a glance.
     */
    override fun toString(): String = "Agent<$name>"

    /**
     * Multi-line human-readable summary of this agent's configuration.
     * Useful for log dumps when an agent misbehaves and you want to see its
     * full state in one shot. Format is intentionally informal — read it,
     * don't parse it. See #970.
     */
    fun describe(): String = buildString {
        appendLine("Agent<$name> : ${outType.simpleName ?: "?"}")
        appendLine("  prompt: ${describePrompt()}")
        appendLine("  model: ${describeModel()}")
        appendLine("  budget: ${describeBudget()}")
        appendLine("  skills (${skills.size}): ${skills.keys.sorted().joinToString(", ")}")
        appendLine("  tools (${toolMap.size}): ${toolMap.keys.sorted().joinToString(", ")}")
        append("  memory: ${if (memoryBank != null) "configured" else "(none)"}")
    }

    private fun describePrompt(): String = when {
        prompt.isBlank() -> "(none)"
        prompt.length <= 80 -> prompt
        else -> prompt.take(77) + "..."
    }

    private fun describeModel(): String {
        val cfg = modelConfig ?: return "(none)"
        return "${cfg.provider.name.lowercase()} (${cfg.host}:${cfg.port}, ${cfg.name}, T=${cfg.temperature})"
    }

    private fun describeBudget(): String {
        val b = budgetConfig
        // Show only fields that diverge from BudgetConfig() defaults so the
        // user sees what they actually overrode. Empty list means "all defaults."
        // (Iterates the data class component fields generically so future
        // additions to BudgetConfig pick up automatically — keeps describe() in
        // sync with new caps without a manual list update each time.)
        val defaults = BudgetConfig()
        val defaultValues = defaults::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<BudgetConfig, *>>()
            .associate { it.name to it.get(defaults) }
        val overrides = b::class.members
            .filterIsInstance<kotlin.reflect.KProperty1<BudgetConfig, *>>()
            .mapNotNull { prop ->
                val current = prop.get(b)
                if (current != defaultValues[prop.name]) "${prop.name}=$current" else null
            }
        return if (overrides.isEmpty()) "(defaults)" else overrides.joinToString(", ")
    }

    fun validate() {
        require(skills.isNotEmpty()) {
            "Agent \"$name\" must declare at least one skill."
        }
        require(skills.values.any { it.outType == outType }) {
            "Agent \"$name\" has no skill producing ${outType.simpleName}. " +
                "At least one skill must return the agent's OUT type."
        }
        // Fail-fast: tool-name typos in `tools(...)` should not silently disappear.
        for (skill in skills.values) {
            val unknown = skill.toolNames.orEmpty().filterNot { it in toolMap }
            require(unknown.isEmpty()) {
                "Skill \"${skill.name}\" on agent \"$name\" references unknown tools: $unknown. " +
                    "Available: ${toolMap.keys}"
            }
        }
        val unknownAuto = autoToolNames.filterNot { it in toolMap }
        require(unknownAuto.isEmpty()) {
            "Agent \"$name\" auto-tools reference unknown tools: $unknownAuto. " +
                "Available: ${toolMap.keys}"
        }
        // #2479 part 2 — fail-fast on ToolChoice.Specific naming a tool that
        // isn't registered on the agent. Same philosophy as the skill / auto
        // tool name checks above: typos surface at construction, not as a
        // runtime API error from the provider after the first turn.
        val specific = (toolChoice as? ToolChoice.Specific)?.name
        if (specific != null) {
            require(specific in toolMap) {
                "Agent \"$name\" toolChoice is Specific(\"$specific\") but that tool is not registered. " +
                    "Available: ${toolMap.keys}"
            }
        }
        // Freeze skills so the agent's contract (allowlist composition, dispatch)
        // can't drift after construction via a held Skill reference. See #668.
        skills.values.forEach { it.frozen = true }
        // Freeze the agent itself so structural mutators (skills, tools, memory,
        // model, budget, prompt, error handlers, routing config) can't be
        // re-invoked post-construction via the agent reference. See #697.
        frozen = true
    }
}

private val LOGGER: Logger = Logger.getLogger(Agent::class.java.name)

inline fun <IN, reified OUT : Any> agent(name: String, block: Agent<IN, OUT>.() -> Unit): Agent<IN, OUT> {
    val agent = Agent<IN, OUT>(name, OUT::class) { it as OUT }
    for (tool in buildBuiltInTools()) {
        agent.registerBuiltInTool(tool)
    }
    agent.block()
    agent.validate()
    return agent
}
