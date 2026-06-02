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
