package agents_engine.core

import agents_engine.model.BudgetBuilder
import agents_engine.model.BudgetConfig
import agents_engine.model.ModelBuilder
import agents_engine.model.ModelConfig
import agents_engine.model.OnErrorBuilder
import agents_engine.model.ToolDef
import agents_engine.model.ToolErrorHandler
import agents_engine.model.ToolsBuilder
import agents_engine.model.buildBuiltInTools
import agents_engine.model.executeAgentic
import agents_engine.model.selectSkillByLlm

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
    var knowledgeUsedListener: ((name: String, content: String) -> Unit)? = null
        private set
    var skillChosenListener: ((name: String) -> Unit)? = null
        private set
    var memoryBank: MemoryBank? = null
        private set
    var routerRationaleListener: ((rationale: String) -> Unit)? = null
        private set
    var skillSelectionConfidenceThreshold: Double = 0.6
        private set
    private var skillSelector: ((IN) -> String)? = null
    private val toolErrorHandlers: MutableMap<String, ToolErrorHandler> = mutableMapOf()
    internal var defaultToolErrorHandler: ToolErrorHandler? = null
        private set
    internal val autoToolNames: MutableSet<String> = mutableSetOf()

    /**
     * Set true at end of [validate] (#697). Structural mutators (skills, tools,
     * memory, model, budget, prompt, error handlers, routing config) check this
     * and refuse post-construction mutation. Listeners (onToolUse, onKnowledgeUsed,
     * onSkillChosen, routerRationale) intentionally remain settable for
     * tracing / instrumentation use cases.
     */
    @PublishedApi internal var frozen: Boolean = false

    private fun checkNotFrozen() {
        check(!frozen) {
            "Agent \"$name\" is frozen — cannot mutate after construction. " +
                "Configure inside the agent { } block, not after."
        }
    }

    fun prompt(text: String) { checkNotFrozen(); prompt = text }

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

    fun onToolUse(block: (name: String, args: Map<String, Any?>, result: Any?) -> Unit) {
        toolUseListener = block
    }

    fun onKnowledgeUsed(block: (name: String, content: String) -> Unit) {
        knowledgeUsedListener = block
    }

    fun onSkillChosen(block: (name: String) -> Unit) {
        skillChosenListener = block
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
    suspend fun invokeSuspend(input: IN): OUT {
        val skill = resolveSkill(input)
        skillChosenListener?.invoke(skill.name)
        return if (skill.isAgentic) {
            castOut(executeAgentic(this, skill, input))
        } else {
            castOut(executors[skill.name]!!(input))
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
