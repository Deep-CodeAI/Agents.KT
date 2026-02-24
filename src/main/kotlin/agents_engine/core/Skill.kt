package agents_engine.core

class Skill<IN, OUT>(
    val name: String,
    val inType: kotlin.reflect.KClass<*>,
    val outType: kotlin.reflect.KClass<*>,
)

class SkillsBuilder {
    val skills = mutableListOf<Skill<*, *>>()

    inline fun <reified IN : Any, reified OUT : Any> skill(name: String, block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
        val skill = Skill<IN, OUT>(name, IN::class, OUT::class)
        skill.block()
        skills.add(skill)
        return skill
    }
}
