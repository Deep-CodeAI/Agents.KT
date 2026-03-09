package agents_engine.core

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

    fun prompt(text: String) { prompt = text }

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
        return castOut(executors[skill.name]!!(input))
    }

    fun skills(block: SkillsBuilder.() -> Unit) {
        val builder = SkillsBuilder()
        builder.block()
        builder.entries.forEach { (skill, exec) ->
            skills[skill.name] = skill
            if (skill.outType == outType) executors[skill.name] = exec
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
