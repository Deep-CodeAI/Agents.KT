package agents_engine.core

import agents_engine.generation.Generable
import agents_engine.generation.Guide
import kotlin.reflect.KClass
import agents_engine.generation.ReflectionFallback
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.primaryConstructor

/**
 * `agents_engine/core/Skill.kt` — the unit of work an `Agent<IN, OUT>` dispatches to.
 *
 * **Two flavors.** A skill is either:
 * - **Deterministic** — declared with `implementedBy { input -> ... }`. A pure
 *   Kotlin lambda; no LLM round-trip. The agentic loop is bypassed.
 * - **Agentic** — declared with `tools(toolA, toolB, ...)` (typed) or the
 *   soft-deprecated `tools("name", ...)` (string) overload. Marks the skill
 *   LLM-driven and pins the tool allowlist. The empty `tools()` form is
 *   also valid — LLM-driven with no tools (memory + built-ins only).
 *
 * **Freeze contract.** `frozen = true` after `Agent.validate()` runs. All
 * mutator methods (`implementedBy`, `tools(...)`, `llmDescription`,
 * `knowledge`, `transformOutput`, `useMemory`) guard with [checkNotFrozen]
 * and throw `IllegalStateException` post-freeze (#668). Prevents drift via
 * a Skill reference held outside the agent.
 *
 * **Knowledge entries.** `knowledge(key, description) { provider }` attaches
 * named callable docs to the skill, surfaced into the LLM prompt via
 * [toLlmContext] and exposed as separately-invocable tools via
 * [knowledgeTools].
 *
 * **Memory opt-in (#856).** Calling `useMemory()` opts the skill into
 * `memory_read` / `memory_write` / `memory_search` tools. When ANY skill
 * on the agent opts in, only opted-in skills get memory tools — the legacy
 * "every skill gets memory if memoryBank is set" auto-inject is bypassed.
 *
 * **Output transformer.** `transformOutput { rawString -> OUT }` runs over
 * the LLM's final text to coerce it into the typed `OUT`. Used by agentic
 * skills where `OUT != String`.
 *
 * **Auto-description.** When `_llmDescription` is unset, [toLlmDescription]
 * synthesizes a markdown block from `name`, `description`, `inType`,
 * `outType`, and attached knowledge. Reflection is wrapped in
 * [agents_engine.generation.ReflectionFallback] so the framework degrades
 * gracefully when `kotlin-reflect` is missing from the classpath (#1718).
 *
 * See `src/main/resources/internals-agent/core/Skill.md` for the adjunct
 * surfaced to IDE-side LLM tools via `agents-kt-internals` (#1837 / #1839).
 */
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

    /**
     * #3863 — query-aware knowledge source (RAG seam). The [retriever] is
     * invoked with the model's query at tool-invocation time; the entry is
     * surfaced as a knowledge tool taking a `query` argument and is never
     * inlined into the prompt. Use the `:agents-kt-rag` module's
     * `ragRetriever(store, embedder) { … }` to back this with any
     * `EmbeddingStore`.
     */
    fun knowledge(key: String, description: String = "", retriever: KnowledgeRetriever) {
        checkNotFrozen()
        require(key !in _knowledge) {
            "Skill \"$name\" already has knowledge entry \"$key\". " +
                "Knowledge keys must be unique per skill."
        }
        _knowledge[key] = KnowledgeEntry(
            description = description,
            provider = {
                error("Knowledge entry \"$key\" is query-aware — call the retriever, not the static provider.")
            },
            retriever = retriever,
        )
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
                // #3863 — retriever entries have no static content to inline;
                // the model fetches them on demand via the query-arg knowledge tool.
                if (entry.retriever == null) {
                    append("\n--- $key ---\n")
                    append(entry.provider())
                } else {
                    append("\n--- $key (on-demand: call the \"$key\" tool with a query) ---")
                }
            }
        }
    }

    fun knowledgeTools(): List<KnowledgeTool> =
        _knowledge.map { (key, entry) -> KnowledgeTool(key, entry.description, entry.provider, entry.retriever) }
}

inline fun <reified IN : Any, reified OUT : Any> skill(name: String, description: String = "", block: Skill<IN, OUT>.() -> Unit = {}): Skill<IN, OUT> {
    val skill = Skill<IN, OUT>(name, description, IN::class, OUT::class)
    skill.block()
    return skill
}

private fun KClass<*>.generableDescription(): String {
    // #1718: wrap kotlin-reflect calls so consumers without kotlin-reflect
    // on the classpath get an empty description instead of LinkageError.
    // Skill auto-descriptions degrade gracefully — the agent still runs, the
    // system prompt just lacks the per-class structural detail.
    return ReflectionFallback.withReflection {
        val annotation = findAnnotation<Generable>() ?: return@withReflection ""
        buildString {
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
    } ?: ""
}
