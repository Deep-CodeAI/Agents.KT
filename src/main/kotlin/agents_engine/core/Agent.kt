package agents_engine.core

class Agent<IN, OUT>(
    val name: String,
    val outType: kotlin.reflect.KClass<*>,
) {
    val skills = mutableMapOf<String, Skill<*, *>>()
    private var placedIn: String? = null

    fun markPlaced(context: String) {
        require(placedIn == null) {
            "Agent \"$name\" is already placed in $placedIn. " +
                "Each agent instance can only participate once. Create a new instance for \"$context\"."
        }
        placedIn = context
    }

    fun skills(block: SkillsBuilder.() -> Unit) {
        val builder = SkillsBuilder()
        builder.block()
        builder.skills.forEach { skills[it.name] = it }
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
    val agent = Agent<IN, OUT>(name, OUT::class)
    agent.block()
    agent.validate()
    return agent
}
