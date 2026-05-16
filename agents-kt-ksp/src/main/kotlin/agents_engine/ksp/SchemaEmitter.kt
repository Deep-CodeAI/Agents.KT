package agents_engine.ksp

/**
 * `agents-kt-ksp/agents_engine/ksp/SchemaEmitter.kt` — emits a JSON
 * Schema string for a `@Generable` data class (#1701). **Contract:
 * byte-identical to runtime.** Output must match
 * `KClass.dataClassJsonSchema()` in `GenerableSupport` exactly —
 * same field ordering, same separator placement, same `@Guide`
 * quoting. Consumers depend on this for prompt-cache determinism
 * (identical input → identical bytes → identical Anthropic cache
 * key). Sealed types out of scope this iteration (variant-with-
 * discriminator shape goes through a separate emitter). #1705
 * defensive emission gate skips when sealed parent's variants list
 * is empty (incremental-compile race). See
 * `src/main/resources/internals-agent/ksp/SchemaEmitter.md`
 * (#1837 / #1900).
 */

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
     * #1705 defensive emission gate. Returns true when the processor should
     * emit a `__GeneratedSchema.kt` file for this class; false when it
     * should skip and let the runtime reflection path handle it.
     *
     * Today the only "skip" case is: a sealed parent whose
     * [GenerableValidator.GenerableClass.sealedVariants] list is empty.
     * That usually means KSP saw the parent before all variant files were
     * processed (incremental-compile race). Emitting `{"oneOf":[]}` for a
     * type that actually has variants at JVM runtime would silently produce
     * a wrong schema. Reflection-based `KClass.sealedSubclasses` always sees
     * the full hierarchy at runtime, so the fallback is correct.
     *
     * Non-sealed data classes are always emit-eligible — they don't
     * depend on cross-file discovery.
     */
    fun canEmit(cls: GenerableValidator.GenerableClass): Boolean {
        if (cls.isSealed && cls.sealedVariants.isEmpty()) return false
        return true
    }

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
     * Emit the JSON Schema for a sealed `@Generable` root (#1702).
     *
     * Format: `{"oneOf":[variant1,variant2,...]}` where each variant is a
     * data-class-shaped object with a `"type"` discriminator at the head
     * (`{"type":"string","const":"<SimpleName>"}`), then the variant's own
     * primary-ctor params, then `additionalProperties:false`, with the
     * variant-class `@Guide(description)` (if any) tacked on at the end
     * as `"description"`.
     *
     * Mirrors `GenerableSupport.sealedJsonSchema()` + `variantJsonSchema()`
     * byte-for-byte.
     */
    fun emitSealedSchema(parent: GenerableValidator.GenerableClass): String = buildString {
        append("""{"oneOf":[""")
        parent.sealedVariants.forEachIndexed { i, variant ->
            if (i > 0) append(",")
            append(emitVariantSchema(variant))
        }
        append("]}")
    }

    private fun emitVariantSchema(variant: GenerableValidator.GenerableClass): String = buildString {
        append("""{"type":"object","properties":{""")
        // Discriminator always first — matches the runtime's
        // `"type":{"type":"string","const":"$simpleName"}` placement.
        append(""""type":{"type":"string","const":"""")
        append(variant.simpleName)
        append("\"}")
        variant.fields.forEach { field ->
            append(",")
            append("\"").append(field.name).append("\":")
            append(emitFieldFragment(field))
        }
        // Required: "type" is always required; then non-nullable, non-defaulted params.
        append("""},"required":["type"""")
        variant.fields.forEach { field ->
            if (!field.isNullable && !field.hasDefault) {
                append(""","""")
                append(field.name)
                append('"')
            }
        }
        append("""],"additionalProperties":false""")
        // Variant-class @Guide description (#1702) — appears AFTER
        // additionalProperties, matching the runtime's variantJsonSchema().
        if (variant.guideDescription != null) {
            append(""","description":"""")
            append(escapeJson(variant.guideDescription))
            append('"')
        }
        append('}')
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
