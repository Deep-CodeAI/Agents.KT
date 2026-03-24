package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

data class KnowledgeTool(
    val name: String,
    val description: String,
    val call: () -> String,
)

private data class KnowledgeEntry(val description: String, val provider: () -> String)

class Skill<IN, OUT>(
    val name: String,
    val description: String,
    val inType: kotlin.reflect.KClass<*>,
    val outType: kotlin.reflect.KClass<*>,
) {
    var implementation: ((IN) -> OUT)? = null
    var isAgentic: Boolean = false
        private set
    var toolNames: List<String>? = null
        private set
    private var _llmDescription: String? = null
    private val _knowledge = mutableMapOf<String, KnowledgeEntry>()

    // backward-compat: callable by key — skill.knowledge["key"]!!()
    val knowledge: Map<String, () -> String>
        get() = _knowledge.mapValues { it.value.provider }

    fun llmDescription(text: String) { _llmDescription = text }

    fun knowledge(key: String, description: String = "", provider: () -> String) {
        _knowledge[key] = KnowledgeEntry(description, provider)
    }

    fun implementedBy(block: (IN) -> OUT) {
        implementation = block
        isAgentic = false
    }

    /** Marks this skill as LLM-driven; [names] are the tools the LLM may call. */
    fun tools(vararg names: String) {
        isAgentic = true
        toolNames = names.toList()
        implementation = null
    }

    var outputTransformer: ((String) -> OUT)? = null
        private set

    fun transformOutput(block: (String) -> OUT) {
        outputTransformer = block
    }

    fun execute(input: IN): OUT {
        val impl = checkNotNull(implementation) {
            "Skill \"$name\" has no implementation. Add implementedBy { } block."
        }
        return impl(input)
    }

    operator fun invoke(input: IN): OUT = execute(input)

    fun toLlmDescription(): String {
        _llmDescription?.let { return it }
        return buildString {
            appendLine("## Skill: $name")
            appendLine()
            appendLine("**Input:** ${inType.simpleName}${inType.generableDescription()}")
            appendLine("**Output:** ${outType.simpleName}${outType.generableDescription()}")
            appendLine()
            appendLine(description)
            if (_knowledge.isNotEmpty()) {
                appendLine()
                appendLine("**Knowledge:**")
                _knowledge.entries.forEach { (key, entry) ->
                    if (entry.description.isNotEmpty())
                        appendLine("- $key — ${entry.description}")
                    else
                        appendLine("- $key")
                }
            }
        }.trimEnd()
    }

    fun toLlmContext(): String = buildString {
        append(toLlmDescription())
        if (_knowledge.isNotEmpty()) {
            append("\n\nKnowledge:")
            _knowledge.forEach { (key, entry) ->
                append("\n--- $key ---\n")
                append(entry.provider())
            }
        }
    }

    fun knowledgeTools(): List<KnowledgeTool> =
        _knowledge.map { (key, entry) -> KnowledgeTool(key, entry.description, entry.provider) }
}

inline fun <reified IN : Any, reified OUT : Any> skill(name: String, description: String = "", block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
    val skill = Skill<IN, OUT>(name, description, IN::class, OUT::class)
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

    inline fun <reified IN : Any, reified OUT : Any> skill(name: String, description: String = "", block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
        val s = Skill<IN, OUT>(name, description, IN::class, OUT::class)
        s.block()
        entries.add(Entry(s) { input -> s(input as IN) })
        return s
    }
}

private fun KClass<*>.generableDescription(): String {
    val annotation = findAnnotation<Generable>() ?: return ""
    return buildString {
        val desc = annotation.description
        if (desc.isNotEmpty()) append(" — $desc")
        val ctor = primaryConstructor
        if (ctor != null && ctor.parameters.isNotEmpty()) {
            ctor.parameters.forEach { param ->
                val typeName = (param.type.classifier as? KClass<*>)?.simpleName ?: "Any"
                val guide = param.findAnnotation<Guide>()
                append("\n  - ${param.name} ($typeName)")
                if (guide != null) append(": ${guide.description}")
            }
        }
    }
}
