package agents_engine.generation

/**
 * Overrides the auto-generated [KClass.toLlmDescription] for a [@Generable] class.
 *
 * When present, [text] is returned verbatim — no auto-generation happens.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class LlmDescription(val text: String)
