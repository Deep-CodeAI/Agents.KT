package agents_engine.generation

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
