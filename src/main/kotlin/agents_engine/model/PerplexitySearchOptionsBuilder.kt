package agents_engine.model

import agents_engine.generation.hasGenerableAnnotation
import agents_engine.generation.jsonSchema
import kotlin.reflect.KClass

/**
 * Ergonomic DSL for [PerplexitySearchOptions] (#3677) —
 * `perplexitySearchOptions { mode = SearchMode.ACADEMIC; … }`.
 */
class PerplexitySearchOptionsBuilder {
    var model: String = "sonar"
    var mode: SearchMode? = null
    var recency: SearchRecency? = null
    var contextSize: SearchContextSize? = null
    var reasoningEffort: ReasoningEffort? = null
    var jsonSchema: JsonSchema? = null
    private val allow = mutableListOf<String>()
    private val deny = mutableListOf<String>()

    /** Restrict search to these domains (`search_domain_filter`). */
    fun allowDomains(vararg domains: String) { allow += domains }

    /** Exclude these domains (`search_domain_filter` entries prefixed with `-`). */
    fun denyDomains(vararg domains: String) { deny += domains }

    /**
     * Constrain the answer to a `@Generable` type's JSON schema via native
     * `response_format`. The answer comes back as JSON matching [type].
     */
    fun structuredOutput(type: KClass<*>) {
        require(type.hasGenerableAnnotation()) {
            "structuredOutput type ${type.simpleName} must be annotated with @Generable"
        }
        jsonSchema = JsonSchema(name = type.simpleName ?: "structured_output", schema = type.jsonSchema())
    }

    internal fun build(): PerplexitySearchOptions = PerplexitySearchOptions(
        model = model,
        mode = mode,
        recency = recency,
        domainsAllow = allow.toList(),
        domainsDeny = deny.toList(),
        contextSize = contextSize,
        reasoningEffort = reasoningEffort,
        jsonSchema = jsonSchema,
    )
}

/** Build [PerplexitySearchOptions] with the DSL builder (#3677). */
fun perplexitySearchOptions(block: PerplexitySearchOptionsBuilder.() -> Unit): PerplexitySearchOptions =
    PerplexitySearchOptionsBuilder().apply(block).build()
