package agents_engine.ksp

/**
 * `agents-kt-ksp/agents_engine/ksp/LlmDescriptionEmitter.kt` — emits the
 * markdown the framework's runtime `KClass.toLlmDescription()` produces
 * via reflection (#1703). **Contract: byte-identical to runtime.**
 * Output must match `GenerableSupport.dataClassLlmDescription()` and
 * `sealedLlmDescription()` exactly — consumers see the same prompt
 * either way; only when the work happens changes. See
 * `src/main/resources/internals-agent/ksp/LlmDescriptionEmitter.md`
 * (#1837 / #1899).
 */

/**
 * Emits the markdown that the framework's `KClass.toLlmDescription()`
 * produces today via reflection (#1703).
 *
 * **Contract: byte-identical to runtime.** Output must match
 * `GenerableSupport.dataClassLlmDescription()` and
 * `GenerableSupport.sealedLlmDescription()`. Consumers see the same prompt
 * either way; the only thing that changes is when the work happens.
 *
 * Format reference — data class:
 * ```
 * ## Person
 *
 * a person
 *
 * - **name** (String)
 * - **age** (Int): how old
 * ```
 *
 * Format reference — sealed root:
 * ```
 * ## Decision
 *
 * a description
 *
 * Choose one of the following variants:
 *
 * ### Approved
 * - **confidence** (Double)
 *
 * ### Rejected: rejection reason
 * - **reason** (String)
 * ```
 *
 * Both end with `.trimEnd()` — no trailing newline.
 */
internal object LlmDescriptionEmitter {

    /**
     * Emit the data-class markdown. Caller must ensure the class is
     * non-sealed; sealed roots go through [emitSealed].
     *
     * When the class has an `@LlmDescription(text)` override, that text
     * wins and is returned verbatim — auto-generation is suppressed.
     */
    fun emitDataClass(cls: GenerableValidator.GenerableClass): String {
        cls.llmDescriptionOverride?.let { return it }
        return buildString {
            appendLine("## ${cls.simpleName}")
            if (cls.generableDescription.isNotEmpty()) {
                appendLine()
                appendLine(cls.generableDescription)
            }
            if (cls.fields.isNotEmpty()) {
                appendLine()
                cls.fields.forEach { field ->
                    appendBullet(field)
                }
            }
        }.trimEnd()
    }

    /** Emit the sealed-root markdown with per-variant sections. */
    fun emitSealed(cls: GenerableValidator.GenerableClass): String {
        cls.llmDescriptionOverride?.let { return it }
        return buildString {
            appendLine("## ${cls.simpleName}")
            if (cls.generableDescription.isNotEmpty()) {
                appendLine()
                appendLine(cls.generableDescription)
            }
            appendLine()
            appendLine("Choose one of the following variants:")
            cls.sealedVariants.forEach { variant ->
                appendLine()
                if (variant.guideDescription != null) {
                    appendLine("### ${variant.simpleName}: ${variant.guideDescription}")
                } else {
                    appendLine("### ${variant.simpleName}")
                }
                variant.fields.forEach { field ->
                    appendBullet(field)
                }
            }
        }.trimEnd()
    }

    /** A single field bullet line. Matches the runtime format exactly. */
    private fun StringBuilder.appendBullet(field: GenerableValidator.Field) {
        val typeName = promptTypeName(field.type)
        if (field.guideDescription != null) {
            appendLine("- **${field.name}** ($typeName): ${field.guideDescription}")
        } else {
            appendLine("- **${field.name}** ($typeName)")
        }
    }

    /**
     * Match `KType.promptTypeName()` in `GenerableSupport.kt` —
     * primitive simple names, `List<T>` with recursive item naming,
     * nested `@Generable` simple class name from the qualified name.
     */
    private fun promptTypeName(type: GenerableValidator.FieldType): String = when (type) {
        is GenerableValidator.FieldType.StringT -> "String"
        is GenerableValidator.FieldType.IntT -> "Int"
        is GenerableValidator.FieldType.LongT -> "Long"
        is GenerableValidator.FieldType.DoubleT -> "Double"
        is GenerableValidator.FieldType.FloatT -> "Float"
        is GenerableValidator.FieldType.BoolT -> "Boolean"
        is GenerableValidator.FieldType.ListT ->
            if (type.itemType != null) "List<${promptTypeName(type.itemType)}>" else "List"
        is GenerableValidator.FieldType.GenerableRef ->
            type.qualifiedName.substringAfterLast('.')
        is GenerableValidator.FieldType.Unsupported ->
            // Validator should have caught this before emission. Defensive fallback
            // matches the runtime's `else -> "String"`.
            "String"
    }
}
