package agents_engine.model

import agents_engine.generation.fromLlmOutput
import kotlin.reflect.KClass

/**
 * Coerces an LLM's raw text (or an interceptor's substitute value) into the agent's declared output
 * type (#3376 — extracted from `AgenticLoop`'s private helpers so the contracts are unit-testable).
 */
internal object OutputCoercion {

    fun parseOutput(text: String, outType: KClass<*>): Any? = when {
        outType == String::class -> text
        else -> @Suppress("UNCHECKED_CAST") (outType as KClass<Any>).fromLlmOutput(text)
    }

    fun coerceSubstituteOutput(result: Any?, outType: KClass<*>): Any {
        if (result != null && outType.java.isInstance(result)) return result
        return parseOutput(result?.toString() ?: "null", outType)
            ?: error("Could not parse interceptor substitute result as ${outType.simpleName}: '$result'")
    }
}
