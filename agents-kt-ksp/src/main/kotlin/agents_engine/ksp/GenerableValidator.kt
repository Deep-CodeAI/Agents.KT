package agents_engine.ksp

/**
 * Compile-time shape rules for `@Generable` classes (#1700). Pure data over
 * pure data — KSP-free so the rules can be unit-tested without the KSP test
 * harness (kctfork lags Kotlin metadata versions, and we run on Kotlin 2.3.x).
 *
 * The runtime checks in `GenerableSupport` / `ToolDef` still fire when KSP
 * isn't applied; this just lifts the diagnosis to the IDE / compile step
 * when KSP is on the classpath.
 */
internal object GenerableValidator {

    /**
     * Minimal description of a class as seen by KSP. Just the fields the
     * validator needs — by keeping it small, the tests are direct.
     */
    internal data class GenerableClass(
        val qualifiedName: String,
        val isSealed: Boolean,
        val isAbstract: Boolean,
        val isInterface: Boolean,
        val isEnum: Boolean,
        val isAnnotation: Boolean,
        val hasPrimaryConstructor: Boolean,
        val primaryConstructorParamCount: Int,
    )

    /**
     * Run all shape rules against [cls] and return a list of error messages
     * (one per violation). Empty list = clean.
     *
     * The framework's runtime (`GenerableSupport`) branches on `isSealed` —
     * sealed types take the polymorphic / `type` discriminator path; the
     * non-sealed branch needs a constructable concrete class. The rules
     * below mirror that branching:
     *
     * - **Sealed class or interface** → always valid (variants are
     *   validated independently when they're also `@Generable`).
     * - **Non-sealed** → must be a concrete class with a primary
     *   constructor of ≥ 1 parameter. Annotation classes and enums never
     *   apply.
     *
     * Each error message names the offending class, names the rule, and
     * explains why it matters — so the IDE popup is actionable.
     */
    fun validate(cls: GenerableClass): List<String> {
        // Sealed is a first-class supported root — short-circuit before the
        // concrete-class rules. (Variants are processed separately when
        // they're also @Generable.)
        if (cls.isSealed) return emptyList()

        val errors = mutableListOf<String>()

        // Rule: kind-specific rejections. Phrase per-kind so the message
        // tells the user what to do, not just what they can't do.
        when {
            cls.isAnnotation -> errors += "@Generable class '${cls.qualifiedName}' must not be an annotation class. " +
                "Annotation classes are metadata containers, not data classes — use a `data class` instead."

            cls.isEnum -> errors += "@Generable class '${cls.qualifiedName}' must not be an enum class. " +
                "Use a String field with @Guide listing valid values, or a sealed interface " +
                "with one @Generable data class per variant."

            cls.isInterface -> errors += "@Generable class '${cls.qualifiedName}' must not be a non-sealed interface. " +
                "Mark the interface `sealed` (so the framework can route via `type` discriminator), " +
                "or replace it with a `data class`."

            cls.isAbstract -> errors += "@Generable class '${cls.qualifiedName}' must not be abstract. " +
                "The framework constructs instances via the primary constructor; abstract " +
                "classes cannot be instantiated. Use a `data class`, or `sealed class` with " +
                "@Generable variants."
        }

        // Rule: at least one primary-constructor parameter. Without
        // parameters there's nothing to populate from JSON — the model can't
        // produce a meaningful instance. (Doesn't apply to sealed; those
        // short-circuited above.) Skip when we've already rejected the
        // class on kind grounds; the kind error is the more actionable one.
        if (errors.isEmpty()) {
            if (!cls.hasPrimaryConstructor) {
                errors += "@Generable class '${cls.qualifiedName}' must have a primary constructor. " +
                    "The framework constructs instances by mapping JSON fields to constructor parameters."
            } else if (cls.primaryConstructorParamCount == 0) {
                errors += "@Generable class '${cls.qualifiedName}' must have at least one " +
                    "primary-constructor parameter. A class with no parameters has nothing to " +
                    "deserialize and produces only `${cls.qualifiedName}()` from any JSON input."
            }
        }

        return errors
    }
}
