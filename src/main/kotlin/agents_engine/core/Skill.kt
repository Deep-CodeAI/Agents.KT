package agents_engine.core

class Skill<IN, OUT>(
    val name: String,
    val inType: kotlin.reflect.KClass<*>,
    val outType: kotlin.reflect.KClass<*>,
) {
    var implementation: ((IN) -> OUT)? = null

    fun implementedBy(block: (IN) -> OUT) {
        implementation = block
    }

    fun execute(input: IN): OUT {
        val impl = requireNotNull(implementation) {
            "Skill \"$name\" has no implementation. Add implementedBy { } block."
        }
        return impl(input)
    }
}

inline fun <reified IN : Any, reified OUT : Any> skill(name: String, block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
    val skill = Skill<IN, OUT>(name, IN::class, OUT::class)
    skill.block()
    return skill
}

class SkillsBuilder {
    val skills = mutableListOf<Skill<*, *>>()

    operator fun <IN, OUT> Skill<IN, OUT>.unaryPlus() {
        skills.add(this)
    }

    inline fun <reified IN : Any, reified OUT : Any> skill(name: String, block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
        val s = Skill<IN, OUT>(name, IN::class, OUT::class)
        s.block()
        skills.add(s)
        return s
    }
}
