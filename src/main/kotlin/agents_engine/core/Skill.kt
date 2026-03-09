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
        val impl = checkNotNull(implementation) {
            "Skill \"$name\" has no implementation. Add implementedBy { } block."
        }
        return impl(input)
    }

    operator fun invoke(input: IN): OUT = execute(input)
}

inline fun <reified IN : Any, reified OUT : Any> skill(name: String, block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
    val skill = Skill<IN, OUT>(name, IN::class, OUT::class)
    skill.block()
    return skill
}

class SkillsBuilder {
    @PublishedApi internal data class Entry(val skill: Skill<*, *>, val exec: (Any?) -> Any)
    @PublishedApi internal val entries = mutableListOf<Entry>()

    inline operator fun <reified IN : Any, reified OUT : Any> Skill<IN, OUT>.unaryPlus() {
        val s = this
        entries.add(Entry(s) { input -> s(input as IN) })
    }

    inline fun <reified IN : Any, reified OUT : Any> skill(name: String, block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
        val s = Skill<IN, OUT>(name, IN::class, OUT::class)
        s.block()
        entries.add(Entry(s) { input -> s(input as IN) })
        return s
    }
}
