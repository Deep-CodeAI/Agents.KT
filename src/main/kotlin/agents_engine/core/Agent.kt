package agents_engine.core

import agents_engine.model.BudgetBuilder
import agents_engine.model.BudgetConfig
import agents_engine.model.ModelBuilder
import agents_engine.model.ModelConfig
import agents_engine.model.ToolDef
import agents_engine.model.ToolsBuilder
import agents_engine.model.executeAgentic

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

    fun tools(block: ToolsBuilder.() -> Unit) {
        val builder = ToolsBuilder()
        builder.block()
        builder.defs.forEach { toolMap[it.name] = it }
    }

    fun markPlaced(context: String) {
        require(placedIn == null) {
            "Agent \"$name\" is already placed in $placedIn. " +
                "Each agent instance can only participate once. Create a new instance for \"$context\"."
        }
        placedIn = context
    }

    operator fun invoke(input: IN): OUT {
        val skill = skills.values.find {
            it.inType.java.isInstance(input) && it.outType == outType
        } ?: error(
            "Agent \"$name\" has no skill for ${outType.simpleName}. " +
                "Add a skill with implementedBy { } block."
        )
        skillChosenListener?.invoke(skill.name)
        return if (skill.isAgentic) {
            castOut(executeAgentic(this, skill, input))
        } else {
            castOut(executors[skill.name]!!(input))
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
