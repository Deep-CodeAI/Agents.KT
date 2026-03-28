package agents_engine.core

import agents_engine.model.BudgetBuilder
import agents_engine.model.BudgetConfig
import agents_engine.model.ModelBuilder
import agents_engine.model.ModelConfig
import agents_engine.model.OnErrorBuilder
import agents_engine.model.ToolDef
import agents_engine.model.ToolErrorHandler
import agents_engine.model.ToolsBuilder
import agents_engine.model.executeAgentic
import agents_engine.model.selectSkillByLlm

class Agent<IN, OUT>(
    val name: String,
    val outType: kotlin.reflect.KClass<*>,
    private val castOut: (Any?) -> OUT,
) {
    val skills = mutableMapOf<String, Skill<*, *>>()
    private val executors = mutableMapOf<String, (Any?) -> Any>()
    private var placedIn: String? = null
    var prompt: String = ""
        private set

    var modelConfig: ModelConfig? = null
        private set
    var budgetConfig: BudgetConfig = BudgetConfig()
        private set
    val toolMap: MutableMap<String, ToolDef> = mutableMapOf()
    var toolUseListener: ((name: String, args: Map<String, Any?>, result: Any?) -> Unit)? = null
        private set
    var knowledgeUsedListener: ((name: String, content: String) -> Unit)? = null
        private set
    var skillChosenListener: ((name: String) -> Unit)? = null
        private set
    var memoryBank: MemoryBank? = null
        private set
    private var skillSelector: ((IN) -> String)? = null
    private val toolErrorHandlers: MutableMap<String, ToolErrorHandler> = mutableMapOf()
    internal var defaultToolErrorHandler: ToolErrorHandler? = null
        private set

    fun prompt(text: String) { prompt = text }

    fun model(block: ModelBuilder.() -> Unit) {
        val builder = ModelBuilder()
        builder.block()
        modelConfig = builder.build()
    }

    fun budget(block: BudgetBuilder.() -> Unit) {
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
        skillSelector = block
    }

    fun memory(bank: MemoryBank) {
        memoryBank = bank
        for (tool in buildMemoryTools(bank, name)) {
            toolMap.putIfAbsent(tool.name, tool)
        }
    }

    fun tools(block: ToolsBuilder.() -> Unit) {
        val builder = ToolsBuilder()
        builder.block()
        builder.defs.forEach { toolMap[it.name] = it }
        builder.defaultErrorHandler?.let { defaultToolErrorHandler = it }
    }

    fun onToolError(toolName: String, block: OnErrorBuilder.() -> Unit) {
        val builder = OnErrorBuilder()
        builder.block()
        toolErrorHandlers[toolName] = builder.build()
    }

    fun getToolErrorHandler(toolName: String): ToolErrorHandler? =
        toolErrorHandlers[toolName] ?: defaultToolErrorHandler

    fun markPlaced(context: String) {
        require(placedIn == null) {
            "Agent \"$name\" is already placed in $placedIn. " +
                "Each agent instance can only participate once. Create a new instance for \"$context\"."
        }
        placedIn = context
    }

    operator fun invoke(input: IN): OUT {
        val skill = resolveSkill(input)
        skillChosenListener?.invoke(skill.name)
        return if (skill.isAgentic) {
            castOut(executeAgentic(this, skill, input))
        } else {
            castOut(executors[skill.name]!!(input))
        }
    }

    private fun resolveSkill(input: IN): Skill<*, *> {
        skillSelector?.let { selector ->
            val selectedName = selector(input)
            return skills[selectedName] ?: error(
                "skillSelection returned unknown skill name \"$selectedName\". " +
                    "Available: ${skills.keys}"
            )
        }

        val candidates = skills.values.filter {
            it.inType.java.isInstance(input) && it.outType == outType
        }

        return when {
            candidates.isEmpty() -> error(
                "Agent \"$name\" has no skill for ${outType.simpleName}. " +
                    "Add a skill with implementedBy { } block."
            )
            candidates.size == 1 -> candidates.single()
            modelConfig != null -> {
                val chosenName = selectSkillByLlm(this, candidates, input)
                candidates.find { it.name == chosenName }
                    ?: error(
                        "LLM selected unknown skill \"$chosenName\". " +
                            "Available: ${candidates.map { it.name }}"
                    )
            }
            else -> candidates.first()
        }
    }

    fun skills(block: SkillsBuilder.() -> Unit) {
        val builder = SkillsBuilder()
        builder.block()
        builder.entries.forEach { (skill, exec) ->
            skills[skill.name] = skill
            if (skill.outType == outType && !skill.isAgentic) executors[skill.name] = exec
        }
    }

    fun validate() {
        if (skills.isNotEmpty()) {
            require(skills.values.any { it.outType == outType }) {
                "Agent \"$name\" has no skill producing ${outType.simpleName}. " +
                    "At least one skill must return the agent's OUT type."
            }
        }
    }
}

inline fun <IN, reified OUT : Any> agent(name: String, block: Agent<IN, OUT>.() -> Unit): Agent<IN, OUT> {
    val agent = Agent<IN, OUT>(name, OUT::class) { it as OUT }
    agent.block()
    agent.validate()
    return agent
}
