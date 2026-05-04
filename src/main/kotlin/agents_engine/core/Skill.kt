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
        private set
    var isAgentic: Boolean = false
        private set
    var toolNames: List<String>? = null
        private set
    private var _llmDescription: String? = null
    private val _knowledge = mutableMapOf<String, KnowledgeEntry>()

    /**
     * Set to true by Agent.validate() at the end of construction. Mutators throw
     * once frozen so the agent's allowlist composition + agentic/deterministic
     * dispatch can't drift via a held Skill reference. See #668.
     */
    @PublishedApi internal var frozen: Boolean = false

    private fun checkNotFrozen() {
        check(!frozen) {
            "Skill \"$name\" is frozen — cannot mutate after agent construction."
        }
    }

    // backward-compat: callable by key — skill.knowledge["key"]!!()
    val knowledge: Map<String, () -> String>
        get() = _knowledge.mapValues { it.value.provider }

    fun llmDescription(text: String) {
        checkNotFrozen()
        _llmDescription = text
    }

    fun knowledge(key: String, description: String = "", provider: () -> String) {
        checkNotFrozen()
        require(key !in _knowledge) {
            "Skill \"$name\" already has knowledge entry \"$key\". " +
                "Knowledge keys must be unique per skill."
        }
        _knowledge[key] = KnowledgeEntry(description, provider)
    }

    fun implementedBy(block: (IN) -> OUT) {
        checkNotFrozen()
        implementation = block
        isAgentic = false
    }

    /**
     * No-arg form — marks the skill agentic with no allowlisted tools (the LLM
     * is restricted to memory and built-in tools only). Not deprecated; useful
     * for skills that derive output via `transformOutput { }` or pure prompting.
     */
    fun tools() {
        checkNotFrozen()
        isAgentic = true
        toolNames = emptyList()
        implementation = null
    }

    /**
     * Marks this skill as LLM-driven; [names] are the tools the LLM may call.
     *
     * Soft-deprecated in favor of the typed `tools(first: Tool<*, *>, vararg rest)`
     * overload (#1016) — the typed form catches typos at compile time. The string
     * form remains for built-in tools (`escalate`, `throwException`, `memory_*`)
     * that have no user-declared `Tool<*, *>` handle to capture.
     */
    @Deprecated(
        message = "Use the typed `tools(first, vararg rest)` overload that takes Tool<*, *> handles returned by `tool(...)` builders. The string form is kept for built-in tools (escalate, throwException, memory_*) and negative tests of validate().",
        level = DeprecationLevel.WARNING,
    )
    fun tools(vararg names: String) {
        checkNotFrozen()
        isAgentic = true
        toolNames = names.toList()
        implementation = null
    }

    /**
     * Typed overload accepting `Tool<*, *>` handles returned by `tool(...)` builders
     * (#1015). Catches typos and stale references at compile time instead of at agent
     * `validate()` (`Agent.kt:404`). See `docs/ksp-design.md` for the broader plan.
     *
     * Requires at least one ref to disambiguate from `tools()` (empty), which resolves
     * to the legacy string-vararg form.
     */
    fun tools(first: agents_engine.model.Tool<*, *>, vararg rest: agents_engine.model.Tool<*, *>) {
        checkNotFrozen()
        isAgentic = true
        toolNames = listOf(first.name) + rest.map { it.name }
        implementation = null
    }

    var outputTransformer: ((String) -> OUT)? = null
        private set

    fun transformOutput(block: (String) -> OUT) {
        checkNotFrozen()
        outputTransformer = block
    }

    /**
     * #856 — opt-in for memory tools. When ANY skill on an agent calls `useMemory()`,
     * the agentic loop respects the opt-in: only skills that called this get
     * `memory_read` / `memory_write` / `memory_search` in their allowlist. When NO
     * skill opts in, the legacy auto-inject (every skill gets memory if memoryBank
     * is set) is preserved for backward compatibility.
     */
    var useMemory: Boolean = false
        private set

    fun useMemory() {
        checkNotFrozen()
        useMemory = true
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
