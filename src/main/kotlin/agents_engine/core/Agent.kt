package agents_engine.core

class Agent<IN, OUT>(
    val name: String,
    val outType: kotlin.reflect.KClass<*>,
) {
    val skills = mutableListOf<Skill<*, *>>()

    fun skills(block: SkillsBuilder.() -> Unit) {
        val builder = SkillsBuilder()
        builder.block()
        skills.addAll(builder.skills)
    }

    fun validate() {
        if (skills.isNotEmpty()) {
            require(skills.any { it.outType == outType }) {
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
