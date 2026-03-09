package agents_engine.generation

/**
 * Marks a data class or sealed interface as an LLM generation target.
 *
 * The framework uses this annotation at runtime to generate:
 * - JSON Schema via [KClass.jsonSchema]
 * - Prompt fragment via [KClass.promptFragment]
 * - Lenient deserializer via [KClass.fromLlmOutput]
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Generable(val description: String = "")

/**
 * Overrides the auto-generated [KClass.toLlmDescription] for a [@Generable] class.
 *
 * When present, [text] is returned verbatim — no auto-generation happens.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class LlmDescription(val text: String)

/**
 * Per-field (or per-variant) guidance for the LLM.
 *
 * On a constructor parameter: tells the LLM what to put in this field —
 * its range, format, or constraints.
 *
 * On a sealed subclass: tells the LLM when to choose this variant.
 */
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Guide(val description: String)
