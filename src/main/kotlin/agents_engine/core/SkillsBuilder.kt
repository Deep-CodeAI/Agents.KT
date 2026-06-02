package agents_engine.core

class SkillsBuilder {
    @PublishedApi internal data class Entry(val skill: Skill<*, *>, val exec: (Any?) -> Any)
    @PublishedApi internal val entries = mutableListOf<Entry>()

    inline operator fun <reified IN : Any, reified OUT : Any> Skill<IN, OUT>.unaryPlus() {
        val s = this
        entries.add(Entry(s) { input -> s(input as IN) })
    }

    inline fun <reified IN : Any, reified OUT : Any> skill(name: String, description: String = "", block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
        val s = Skill<IN, OUT>(name, description, IN::class, OUT::class)
        s.block()
        entries.add(Entry(s) { input -> s(input as IN) })
        return s
    }
}
