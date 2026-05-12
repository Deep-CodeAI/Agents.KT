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
     *
     * #1701 adds [fields] for the schema-generation path. Validation rules
     * fire against `fields` (each must be a supported type); the emitter
     * reads it to produce JSON Schema.
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
        /** Primary-constructor params in declaration order. Empty when not analysed. */
        val fields: List<Field> = emptyList(),
        /**
         * Simple class name (no package). Used as the `"type"` discriminator
         * const when rendering sealed variants. Derived from [qualifiedName]
         * when not given explicitly — the processor sets it directly.
         */
        val simpleName: String = qualifiedName.substringAfterLast('.'),
        /** Contents of `@Guide(description)` on the class itself (#1702 — for sealed variants). */
        val guideDescription: String? = null,
        /**
         * Sealed parent: variants discovered via KSP's `getSealedSubclasses()`.
         * Empty for non-sealed classes and for sealed parents whose subclasses
         * KSP couldn't resolve.
         */
        val sealedVariants: List<GenerableClass> = emptyList(),
        /** Contents of `@Generable(description)` — class-level introductory text (#1703). */
        val generableDescription: String = "",
        /**
         * Contents of `@LlmDescription(text)` — wins over the auto-generated
         * description. When non-null the emitter returns this text verbatim;
         * the processor bakes it into the generated constant so runtime stays
         * reflection-free for the override path too (#1703).
         */
        val llmDescriptionOverride: String? = null,
    )

    /**
     * One primary-constructor parameter. Field types map 1:1 to the runtime's
     * `KType.jsonSchemaTypeObject` set so the emitter produces byte-identical
     * schemas to the reflection path. Anything outside this set fires a
     * validator error and the class is excluded from generation.
     */
    internal data class Field(
        val name: String,
        val type: FieldType,
        val isNullable: Boolean,
        /** True when the parameter has a default value (Kotlin `= …`). */
        val hasDefault: Boolean,
        /** Contents of `@Guide(description)` if present; else null. */
        val guideDescription: String?,
    )

    /**
     * The supported-type set, matching the runtime's
     * `KType.jsonSchemaTypeObject` branching exactly. Each variant carries
     * the data the emitter needs.
     */
    internal sealed class FieldType {
        object StringT : FieldType()
        object IntT : FieldType()       // `Int` and `Long` → "integer"
        object LongT : FieldType()
        object DoubleT : FieldType()    // `Double` and `Float` → "number"
        object FloatT : FieldType()
        object BoolT : FieldType()
        /** `List<T>` where T is itself a [FieldType]. Item-less list → array of unknown. */
        data class ListT(val itemType: FieldType?) : FieldType()
        /** Another `@Generable` class — schema emitted by recursion / lookup. */
        data class GenerableRef(val qualifiedName: String) : FieldType()
        /** Anything else — fires a validator error; never emitted. */
        data class Unsupported(val rawTypeName: String) : FieldType()
    }

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

        // Rule: every primary-constructor parameter must be a supported type
        // (#1701). The runtime falls back to `{"type":"string"}` for unknown
        // types, which silently corrupts model output — better to fail at
        // compile time with a pointer at the offending field.
        if (errors.isEmpty() && cls.fields.isNotEmpty()) {
            cls.fields.forEach { field ->
                val unsupported = unsupportedTypeName(field.type)
                if (unsupported != null) {
                    errors += "@Generable class '${cls.qualifiedName}' has field '${field.name}: $unsupported' " +
                        "with an unsupported type. Supported: String, Int, Long, Double, Float, Boolean, " +
                        "List<T> of any supported type, or another @Generable class."
                }
            }
        }

        return errors
    }

    /**
     * Returns the offending type name if [type] is not representable, or null
     * if it's fine. Recurses into [FieldType.ListT] item types — a
     * `List<java.time.Instant>` is just as bad as `java.time.Instant` itself.
     */
    private fun unsupportedTypeName(type: FieldType): String? = when (type) {
        is FieldType.Unsupported -> type.rawTypeName
        is FieldType.ListT -> type.itemType?.let { unsupportedTypeName(it) }
        else -> null
    }
}
