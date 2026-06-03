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
import agents_engine.runtime.events.AgentEvent

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
     * Layer 1 of #1916 — when `true` (default), a tool's *declared* [ToolPolicy]
     * is enforced at the tool-call boundary: a call whose absolute filesystem-path
     * arguments fall outside the declared read/write globs is denied (surfacing as
     * a `Decision.Deny` → `onToolDenied` / `PipelineEvent.ToolDenied`) before the
     * executor runs. Tools that declare no filesystem stance are never gated —
     * enforcement is opt-in by declaration, so existing tools are unaffected.
     * Set `false` to restore the 0.6.0 declare-only (inert) behavior. OS-level
     * isolation for subprocess tools is the Layer 2 sandbox (sibling issues).
     */
    var enforceToolPolicies: Boolean = true

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
    val toolUseListener: ((name: String, args: Map<String, Any?>, result: Any?) -> Unit)?
        get() = listeners.toolUseListener
    /**
     * Fires when an `onBeforeToolCall` interceptor returns [Decision.Deny] and
     * the call is blocked before its executor runs (#2395). Parallel to
     * [toolUseListener]: blocked attempts are first-class observable so an
     * audit log catches them even on the non-streaming path. [toolUseListener]
     * deliberately does NOT fire for a denied call (no executor ran).
     */
    val toolDeniedListener: ((name: String, args: Map<String, Any?>, reason: String) -> Unit)?
        get() = listeners.toolDeniedListener
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
    val toolHallucinatedListener: ((name: String, args: Map<String, Any?>, allowedTools: List<String>) -> Unit)?
        get() = listeners.toolHallucinatedListener
    /**
     * #2489 — fires when a tool inside the agentic loop calls `humanApproval
     * { }` and the runtime is about to pause for human input. Pure
     * observability: the runtime still throws [AgentInterruptException].
     * Receives the rendered `title`, whether a `body` is attached, and the
     * advisory `timeoutMs`. Body content is omitted by design — see
     * [PipelineEvent.ApprovalRequested].
     */
    val approvalRequestedListener: ((title: String, hasBody: Boolean, timeoutMs: Long?) -> Unit)?
        get() = listeners.approvalRequestedListener
    /**
     * #2489 — fires on the resume path when `resumeWith` is a [HumanDecision].
     * Receives the variant name and whether the variant carried a payload
     * (Edited/Responded). Body content omitted by design — see
     * [PipelineEvent.ApprovalDecided].
     */
    val approvalDecidedListener: ((decision: String, hasPayload: Boolean) -> Unit)?
        get() = listeners.approvalDecidedListener
    val knowledgeUsedListener: ((name: String, content: String) -> Unit)?
        get() = listeners.knowledgeUsedListener
    val skillChosenListener: ((name: String) -> Unit)?
        get() = listeners.skillChosenListener
    /**
     * #2470 slice b — optional [agents_engine.content.BlobStore] for
     * dereferencing `Content.Image` attachments at the agent invoke
     * surface. When the caller passes `attachments = listOf(Content.Image(
     * ref, mime))`, the runtime reads the bytes from this store and builds
     * the corresponding [agents_engine.model.ImagePart] for the first user
     * LlmMessage. Null when the agent doesn't accept image attachments —
     * passing attachments to such an agent errors fast at invoke time
     * with a clear message.
     */
    var blobStore: agents_engine.content.BlobStore? = null
        private set
    var memoryBank: MemoryBank? = null
        private set
    val routerRationaleListener: ((rationale: String) -> Unit)?
        get() = listeners.routerRationaleListener
    /**
     * Fires when an infrastructure error is about to propagate out of an agentic
     * invocation — LLM transport failures, response parse failures, budget
     * exceptions, skill-routing failures, etc. Pure observability: the original
     * exception is always rethrown after the listener runs. See #962.
     *
     * Distinct from [onToolError], which is per-tool *semantic* recovery and
     * can substitute a value or repaired arguments for the failure.
     */
    val errorListener: ((Throwable) -> Unit)?
        get() = listeners.errorListener
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
    val budgetThresholdListener: ((reason: BudgetReason, usedPercent: Double) -> Unit)?
        get() = listeners.budgetThresholdListener
    /**
     * Hard-cap decision hook (#2412). When a budget cap would throw, this is
     * consulted with the reason and the current limit; returning
     * [agents_engine.model.BudgetDecision.Extend] raises the limit and continues,
     * [agents_engine.model.BudgetDecision.Stop] (or no listener) throws. Currently
     * wired for the tool-call cap. Settable post-construction like other listeners.
     */
    val budgetExceededListener: ((reason: BudgetReason, currentLimit: Int) -> agents_engine.model.BudgetDecision)?
        get() = listeners.budgetExceededListener
    var skillSelectionConfidenceThreshold: Double = 0.6
        private set
    internal var skillSelector: ((IN) -> String)? = null
        private set

    /**
     * #3088 stage 2 — skill resolution (candidate filter, manual selector, LLM router, fail-loud
     * ambiguity) lives in its own collaborator; [invokeSuspendForSession] delegates to it.
     */
    private val skillResolver = SkillResolver(this)
    /**
     * #2793 — the before-interceptor lists + decision wiring live in their own collaborator; the
     * `onBeforeX` / `onInterceptorDecision` DSL setters and `beforeXInterceptorCount` readers below
     * delegate to it.
     */
    private val interceptors = InterceptorChain()
    /**
     * #2793 — the observability listener slots + multi-subscriber `fire*` dispatch live in their own
     * collaborator. The public `agent.<slot>` reads below forward to it (no public-API change), the
     * `onX` DSL setters delegate writes here, and the runtime fires through `agent.listeners.fireX`.
     */
    internal val listeners = ListenerRegistry()
    private val toolErrorHandlers: MutableMap<String, ToolErrorHandler> = mutableMapOf()
    internal var manifestHash: String? = null
        private set
    internal var defaultToolErrorHandler: ToolErrorHandler? = null
        private set
    internal val autoToolNames: MutableSet<String> = mutableSetOf()

    val beforeSkillInterceptorCount: Int
        get() = interceptors.beforeSkillCount

    val beforeToolCallInterceptorCount: Int
        get() = interceptors.beforeToolCallCount

    val beforeTurnInterceptorCount: Int
        get() = interceptors.beforeTurnCount

    val tokenUsageListenerCount: Int
        get() = listeners.tokenUsageListenerCount

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
        listeners.toolUseListener = block
    }

    /**
     * Observe tool calls blocked by an `onBeforeToolCall` [Decision.Deny] (#2395).
     * `reason` is the denial reason returned to the model. Fires instead of
     * [onToolUse] (the executor never ran). Like the other listener slots, it
     * remains settable after construction for instrumentation.
     */
    fun onToolDenied(block: (name: String, args: Map<String, Any?>, reason: String) -> Unit) {
        listeners.toolDeniedListener = block
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
        listeners.toolHallucinatedListener = block
    }

    /**
     * #2489 — Observe `humanApproval { }` requests on this agent's loop.
     * Settable post-construction. See [PipelineEvent.ApprovalRequested].
     */
    fun onApprovalRequested(block: (title: String, hasBody: Boolean, timeoutMs: Long?) -> Unit) {
        listeners.approvalRequestedListener = block
    }

    /**
     * #2489 — Observe the [HumanDecision] when the resume path synthesises
     * a tool result from a `resumeWith` of that type. Settable
     * post-construction. See [PipelineEvent.ApprovalDecided].
     */
    fun onApprovalDecided(block: (decision: String, hasPayload: Boolean) -> Unit) {
        listeners.approvalDecidedListener = block
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
        listeners.addTokenUsageListener(block)
    }

    internal fun fireTokenUsage(usage: TokenUsage) {
        listeners.fireTokenUsage(usage)
    }

    fun onKnowledgeUsed(block: (name: String, content: String) -> Unit) {
        listeners.knowledgeUsedListener = block
    }

    fun onSkillChosen(block: (name: String) -> Unit) {
        listeners.skillChosenListener = block
    }

    fun onError(block: (Throwable) -> Unit) {
        listeners.errorListener = block
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
        listeners.budgetThresholdListener = block
    }

    /**
     * Register a hard-cap decision hook. When a budget cap would throw
     * [agents_engine.model.BudgetExceededException], [block] is called with the
     * [BudgetReason] and the current limit; return
     * [agents_engine.model.BudgetDecision.Extend] (with a larger limit) to raise
     * the cap and continue, or [agents_engine.model.BudgetDecision.Stop] to throw.
     *
     * **Reasons the handler is consulted for** (#2412 + #2750):
     * - [BudgetReason.TOOL_CALLS] — `Extend(newLimit)` raises `maxToolCalls`.
     * - [BudgetReason.TURNS] — `Extend(newLimit)` raises `maxTurns`.
     * - [BudgetReason.DURATION] — `Extend(newLimit)` raises `maxDuration` (units:
     *   milliseconds — clock budget, not turn count).
     * - [BudgetReason.TOKENS] — `Extend(newLimit)` raises `maxTokens`.
     * - [BudgetReason.CONSECUTIVE_TOOL] — `Extend(newLimit)` raises
     *   `maxConsecutiveSameTool`.
     *
     * **Reasons the handler is NOT consulted for** — only [BudgetReason.PER_TOOL_TIMEOUT].
     * It's per-call (not cumulative), so extending mid-tool would require
     * interrupting an in-flight executor, which is a different problem; that
     * cap always throws unconditionally.
     *
     * Pre-#2750 (the 0.6.4 line) the handler was wired for `TOOL_CALLS` only;
     * the other reasons threw without consulting it. Existing handlers stay
     * correct — they just see more `BudgetReason` values now and can choose
     * which to extend vs which to stop.
     */
    fun onBudgetExceeded(block: (reason: BudgetReason, currentLimit: Int) -> agents_engine.model.BudgetDecision) {
        listeners.budgetExceededListener = block
    }

    fun onBeforeSkill(block: (skillName: String) -> Decision<String>) {
        interceptors.addBeforeSkill(block)
    }

    fun onBeforeToolCall(block: (name: String, args: Map<String, Any?>) -> Decision<Map<String, Any?>>) {
        interceptors.addBeforeToolCall(block)
    }

    fun onBeforeTurn(block: (messages: List<ChatMessage>) -> Decision<List<ChatMessage>>) {
        interceptors.addBeforeTurn(block)
    }

    fun onInterceptorDecision(block: (point: InterceptorPoint, decision: Decision<*>) -> Unit) {
        interceptors.addDecisionListener(block)
    }

    fun onAgentEvent(block: (AgentEvent<*>) -> Unit) {
        listeners.addAgentEventListener(block)
    }

    internal fun fireAgentEvent(event: AgentEvent<*>) {
        listeners.fireAgentEvent(event)
    }

    internal fun decideBeforeSkill(skillName: String): Decision<String> =
        interceptors.decideBeforeSkill(skillName)

    internal fun decideBeforeToolCall(name: String, args: Map<String, Any?>): Decision<Map<String, Any?>> {
        // Layer 1 of #1916: built-in declared-policy gate runs *before* user
        // interceptors. A denial short-circuits the chain (matching "first
        // non-Proceed wins"); a non-deny gate lets the call flow through any
        // user `onBeforeToolCall`. The chain owns the fold + decision firing.
        val gate = if (enforceToolPolicies) {
            ToolPolicyEnforcer.evaluate(toolMap[name]?.policy, args) as? Decision.Deny
        } else null
        return interceptors.decideBeforeToolCall(name, args, gate)
    }

    internal fun decideBeforeTurn(messages: List<ChatMessage>): Decision<List<ChatMessage>> =
        interceptors.decideBeforeTurn(messages)

    fun skillSelection(block: (IN) -> String) {
        checkNotFrozen()
        skillSelector = block
    }

    fun routerRationale(block: (rationale: String) -> Unit) { listeners.routerRationaleListener = block }

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

    /**
     * #2470 slice b — inject a [agents_engine.content.BlobStore] so the
     * agent can dereference `Content.Image` attachments at invoke time.
     *
     * ```kotlin
     * val store = FileBlobStore(Path.of("blobs"))
     * val agent = agent<String, String>("vision") {
     *     model { ollama("qwen3-vl:8b") }
     *     blobStore(store)
     *     skills { skill<String, String>("describe", "") { tools() } }
     * }
     *
     * val ref = store.put(pngBytes, ImageMime.Png.wireMime)
     * val out = agent.invokeWithAttachments(
     *     "What is in this image?",
     *     attachments = listOf(Content.Image(ref, ImageMime.Png)),
     * )
     * ```
     *
     * The runtime reads the blob from this store, base64-encodes once,
     * and attaches it to the first user LlmMessage as
     * `images: List<ImagePart>`. Per-provider wire translation is the
     * #2470 slice-a work in `OllamaClient` / `ClaudeClient` /
     * `OpenAiClient`.
     */
    fun blobStore(store: agents_engine.content.BlobStore) {
        checkNotFrozen()
        blobStore = store
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
     * #2470 slice b — suspending entry point with image attachments. The
     * caller passes `attachments = listOf(Content.Image(ref, mime), ...)`;
     * the runtime dereferences each ref against [blobStore], base64-encodes
     * once, and attaches them to the first user LlmMessage. Per-provider
     * wire translation is the slice-a work (Ollama / Claude / OpenAI all
     * already implement the wire format for `LlmMessage.images`).
     *
     * Errors fast with a clear message when:
     * - [blobStore] is null but [attachments] are passed
     * - A ref's blob is missing from the store (purged / rewired)
     *
     * Document / Audio / Video variants in [attachments] are silently
     * skipped in v1 — they'll be wired through provider doc/audio/video
     * adapters in later slices of #2470.
     */
    suspend fun invokeSuspendWithAttachments(
        input: IN,
        attachments: List<agents_engine.content.Content>,
    ): OUT =
        withAgentRuntimeContext(newRuntimeContext()) {
            invokeSuspendForSession(
                input = input,
                emitter = null,
                request = RunRequest(attachments = attachments),
            ) { /* no-op */ }
        }

    /**
     * #2470 slice b — blocking shim over [invokeSuspendWithAttachments]
     * for callers outside coroutine scopes. Mirrors the [invoke] /
     * [invokeSuspend] split.
     */
    fun invokeWithAttachments(
        input: IN,
        attachments: List<agents_engine.content.Content>,
    ): OUT = kotlinx.coroutines.runBlocking {
        invokeSuspendWithAttachments(input, attachments)
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
        /**
         * #2488 — typed resume input for the HITL interrupt primitive. When
         * [resumeFrom] carries `pendingInterruptCallId`, the runtime synthesises
         * a tool result message from this value (rendered via
         * `toLlmInput` so typed `@Generable` replies become JSON) before the
         * loop resumes. Required when the snapshot has a pending interrupt;
         * ignored otherwise.
         */
        resumeWith: Any? = null,
        /**
         * #2754 / #2488 — opt out of the manifest-hash restore guard. False
         * (default) refuses to resume a snapshot whose manifest differs from
         * the current agent's. True lets the resume proceed (caller owns
         * migration semantics).
         */
        allowManifestMismatch: Boolean = false,
    ): OUT =
        withAgentRuntimeContext(newRuntimeContext()) {
            invokeSuspendForSession(
                input = input,
                emitter = null,
                request = RunRequest(
                    resumeFrom = resumeFrom,
                    onTurnCheckpoint = onTurnCheckpoint,
                    resumeWith = resumeWith,
                    allowManifestMismatch = allowManifestMismatch,
                ),
            ) { /* no-op onSkillStarted */ }
        }

    internal fun newRuntimeContext(sessionId: String? = null): AgentRuntimeContext =
        // #3377 — carry nested-invocation depth: a fresh top-level context is depth 0; one created
        // while another agent invocation is on the stack (a tool re-invoking an agent) is parent + 1.
        AgentRuntimeContext(
            sessionId = sessionId,
            manifestHash = manifestHash,
            depth = (AgentRuntimeContext.current()?.depth ?: -1) + 1,
        )

    /** #3377 — fail fast on over-deep nested agent invocation, before the loop runs. */
    private fun checkAgentDepth(depth: Int) {
        if (depth > budgetConfig.maxAgentDepth) {
            throw agents_engine.model.BudgetExceededException(
                "Agent \"$name\" nested invocation depth $depth exceeded " +
                    "maxAgentDepth=${budgetConfig.maxAgentDepth} — possible unbounded agent recursion.",
                BudgetReason.AGENT_DEPTH,
            )
        }
    }

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
         * #3088 — the per-invocation execution parameters (prompt override, resume/HITL state,
         * checkpoint callback, manifest-mismatch opt-out, attachments) bundled into one value
         * object. Defaults to [RunRequest] (a fresh invocation), so the non-streaming path is
         * unchanged.
         */
        request: RunRequest = RunRequest(),
        onSkillCompleted: (agents_engine.model.TokenUsage?) -> Unit = { /* no-op */ },
        onSkillStarted: (String) -> Unit,
    ): OUT {
        val runtimeContext = AgentRuntimeContext.current() ?: newRuntimeContext()
        // #3377 — bound nested agent invocation BEFORE running the loop. A tool that re-invokes an
        // agent (Swarm absorb, agent-as-tool) increments runtimeContext.depth via newRuntimeContext;
        // a self-re-entering or cyclic agent would otherwise recurse one full agentic loop per level.
        checkAgentDepth(runtimeContext.depth)
        try {
            var skill = skillResolver.resolve(input)
            when (val decision = decideBeforeSkill(skill.name)) {
                Decision.Proceed -> Unit
                is Decision.ProceedWith -> skill = skillResolver.compatible(decision.replacement, input)
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
                    request = request,
                    emitter = emitter,
                    runtimeContext = runtimeContext,
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
                request = RunRequest(promptOverride = promptOverride),
            ) { /* no-op onSkillStarted */ }
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

    // #2805 — was `BudgetConfig::class.members` reflection (kotlin-reflect)
    // which broke the module-wide reflect-optional contract (#1718). Routes
    // through BudgetConfig.describeOverrides() now; adding a new cap is a
    // compile-time reminder to extend the describe string.
    private fun describeBudget(): String = budgetConfig.describeOverrides()

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

inline fun <IN, reified OUT : Any> agent(name: String, block: Agent<IN, OUT>.() -> Unit): Agent<IN, OUT> {
    val agent = Agent<IN, OUT>(name, OUT::class) { it as OUT }
    for (tool in buildBuiltInTools()) {
        agent.registerBuiltInTool(tool)
    }
    agent.block()
    agent.validate()
    return agent
}
