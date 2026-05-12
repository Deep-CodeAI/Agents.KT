package agents_engine.ksp

/**
 * Emits a JSON Schema string for a `@Generable` data class (#1701).
 *
 * **Contract: byte-identical to runtime.** The output must match the result
 * of `KClass.dataClassJsonSchema()` in `agents_engine.generation.GenerableSupport`
 * exactly — same field ordering, same separator placement, same quoting of
 * the `@Guide` description. Consumers depend on this for prompt-cache
 * determinism: identical input → identical bytes → identical Anthropic
 * cache key.
 *
 * Sealed types are out of scope for this iteration — the variant-with-
 * discriminator shape is more complex and goes through a separate emitter
 * once we tackle it.
 *
 * Pure object, no KSP types. The processor builds a [GenerableValidator.GenerableClass]
 * from `KSClassDeclaration` and passes it here.
 */
internal object SchemaEmitter {

    /**
     * Emit the JSON Schema for a data class. Caller must ensure
     * [GenerableValidator.validate] returned no errors first — this function
     * trusts its input and may produce nonsensical output for unsupported
     * shapes.
     *
     * For nested `@Generable` references, the emitter inserts a placeholder
     * `{"type":"object"}` rather than recursing into the referenced class.
     * The runtime path resolves the reference via class lookup at
     * read time, and the generated registry (when wired) does the same.
     * Keeping recursion out of the emitter keeps the generator side-effect-
     * free — one class's schema doesn't depend on whether another class's
     * generated file has landed yet.
     */
    fun emitDataClassSchema(cls: GenerableValidator.GenerableClass): String = buildString {
        append("""{"type":"object","properties":{""")
        cls.fields.forEachIndexed { i, field ->
            if (i > 0) append(",")
            append("\"").append(field.name).append("\":")
            append(emitFieldFragment(field))
        }
        append("""},"required":[""")
        // Required = not nullable AND no default. Matches
        // `!it.type.isMarkedNullable && !it.isOptional` in the runtime.
        var requiredCount = 0
        cls.fields.forEach { field ->
            if (!field.isNullable && !field.hasDefault) {
                if (requiredCount > 0) append(",")
                append("\"").append(field.name).append("\"")
                requiredCount++
            }
        }
        // Strict by default (#661 from the original runtime path).
        append("""],"additionalProperties":false}""")
    }

    /**
     * Emit the `{"type":"..."}` fragment for a single field. With a
     * `@Guide(description)`, insert the description into the object before
     * the closing `}` — matches `KParameter.jsonSchemaFragment()` exactly.
     */
    private fun emitFieldFragment(field: GenerableValidator.Field): String {
        val typeObj = emitTypeObject(field.type)
        return if (field.guideDescription == null) typeObj
        else typeObj.dropLast(1) + ""","description":"${escapeJson(field.guideDescription)}"}"""
    }

    private fun emitTypeObject(type: GenerableValidator.FieldType): String = when (type) {
        is GenerableValidator.FieldType.StringT -> """{"type":"string"}"""
        is GenerableValidator.FieldType.IntT,
        is GenerableValidator.FieldType.LongT -> """{"type":"integer"}"""
        is GenerableValidator.FieldType.DoubleT,
        is GenerableValidator.FieldType.FloatT -> """{"type":"number"}"""
        is GenerableValidator.FieldType.BoolT -> """{"type":"boolean"}"""
        is GenerableValidator.FieldType.ListT ->
            if (type.itemType != null) """{"type":"array","items":${emitTypeObject(type.itemType)}}"""
            else """{"type":"array"}"""
        is GenerableValidator.FieldType.GenerableRef ->
            // The runtime path recurses into the nested @Generable's own
            // jsonSchema(); we emit a placeholder and let the runtime resolve
            // it via the lookup mechanism. Identical to the runtime fallback
            // when the nested class has no @Generable annotation.
            """{"type":"object"}"""
        is GenerableValidator.FieldType.Unsupported ->
            // Should never happen — caller validates first. Defensive.
            """{"type":"string"}"""
    }

    /**
     * Match `String.escapeJson()` in `GenerableSupport.kt`. Defensive copy
     * here so the KSP module doesn't depend on the runtime project at
     * compile time.
     */
    private fun escapeJson(s: String): String = buildString {
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
    }
}
