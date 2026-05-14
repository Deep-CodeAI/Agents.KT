package agents_engine.ksp

/**
 * Emits the source body of `constructFromMap(fields: Map<*, Any?>): Foo?`
 * for `@Generable data class` and `@Generable sealed` types (#1704).
 *
 * The generated function reproduces the runtime contract from
 * `GenerableSupport.constructFromMap` — strict extras rejection (#665),
 * sealed-variant discriminator check (#699), per-field coercion (using the
 * framework's @PublishedApi `coerceString` / `coerceInt` / `coerceList`
 * helpers so overflow / type rejection matches reflection byte-for-byte),
 * non-nullable-required short-circuit (return null on coercion miss).
 *
 * Scope: only emits for classes whose primary-ctor params have NO default
 * values. Optional defaults need the Kotlin compiler's synthetic
 * constructor-with-mask which isn't callable from generated source; those
 * fall through to the runtime reflection path.
 *
 * Pure object; no KSP types. Returns the Kotlin source body that goes
 * inside `constructFromMap(fields: Map<*, Any?>): <Qualified>?`.
 */
internal object ConstructFromMapEmitter {

    /**
     * Returns true if this class is generation-eligible. The caller skips
     * codegen otherwise and lets the runtime reflection path handle it.
     */
    fun canGenerate(cls: GenerableValidator.GenerableClass): Boolean {
        if (cls.isSealed) return true  // sealed dispatch doesn't read fields
        if (!cls.hasPrimaryConstructor || cls.fields.isEmpty()) return false
        if (cls.fields.any { it.hasDefault }) return false   // see scope note
        if (cls.fields.any { it.type is GenerableValidator.FieldType.Unsupported }) return false
        return true
    }

    /**
     * Emit the body of `constructFromMap` for a non-sealed data class.
     * Body is wrapped by the caller in:
     *
     *     @JvmStatic fun constructFromMap(fields: Map<*, Any?>): <Qualified>? { ... }
     */
    fun emitDataClassBody(cls: GenerableValidator.GenerableClass, isSealedVariant: Boolean): String =
        buildString {
            // 1. Reject extras (strict, #665).
            // 2. For sealed variants only, the "type" key is allowed AND
            //    the discriminator value must equal this variant's simple
            //    name (#699).
            val allowedNames = cls.fields.map { it.name } + if (isSealedVariant) listOf("type") else emptyList()
            appendLine("val allowed = setOf(${allowedNames.joinToString(", ") { "\"$it\"" }})")
            appendLine("for (k in fields.keys) {")
            appendLine("    val kStr = k?.toString() ?: continue")
            appendLine("    if (kStr !in allowed) return null")
            appendLine("}")
            if (isSealedVariant) {
                appendLine("val discriminator = fields[\"type\"] as? String")
                appendLine("if (discriminator != null && discriminator != \"${cls.simpleName}\") return null")
            }

            // 3. Coerce each field. For required (non-nullable) fields,
            //    return null on coercion miss; nullable fields accept null.
            cls.fields.forEach { field ->
                val coerceExpr = coerceExpression(field.type, """fields["${field.name}"]""")
                if (field.isNullable) {
                    // Nullable field: just bind the coerced value (may be null).
                    appendLine("val ${field.name} = $coerceExpr")
                } else {
                    // Required non-nullable: short-circuit on coercion miss.
                    appendLine("val ${field.name} = $coerceExpr ?: return null")
                }
            }

            // 4. Invoke constructor with named args.
            appendLine("return try {")
            appendLine("    ${cls.qualifiedName}(")
            cls.fields.forEachIndexed { i, field ->
                val sep = if (i < cls.fields.size - 1) "," else ""
                appendLine("        ${field.name} = ${field.name}$sep")
            }
            appendLine("    )")
            appendLine("} catch (_: Exception) { null }")
        }.trimEnd()

    /**
     * Emit the body of `constructFromMap` for a sealed root — dispatches
     * to each variant's generated `constructFromMap` by `"type"`. Each
     * variant must itself be `@Generable` (and KSP-generated) for this to
     * resolve; if a variant is missing the generated companion, the JVM
     * call from runtime fails harmlessly and the reflection fallback
     * kicks in.
     */
    fun emitSealedDispatchBody(cls: GenerableValidator.GenerableClass): String = buildString {
        appendLine("val typeName = fields[\"type\"] as? String ?: return null")
        appendLine("return when (typeName) {")
        cls.sealedVariants.forEach { variant ->
            appendLine("    \"${variant.simpleName}\" -> ${variant.qualifiedName}__GeneratedSchema.constructFromMap(fields)")
        }
        appendLine("    else -> null")
        appendLine("}")
    }.trimEnd()

    /**
     * Emit the Kotlin expression that coerces `<source>` (an `Any?`) to
     * the typed value. Returns an expression of type `T?` for primitive
     * leaves, recursive for List, calls the nested @Generable's generated
     * `constructFromMap` for refs.
     */
    private fun coerceExpression(type: GenerableValidator.FieldType, source: String): String = when (type) {
        is GenerableValidator.FieldType.StringT ->
            "agents_engine.generation.coerceString($source)"
        is GenerableValidator.FieldType.IntT ->
            "agents_engine.generation.coerceInt($source)"
        is GenerableValidator.FieldType.LongT ->
            "agents_engine.generation.coerceLong($source)"
        is GenerableValidator.FieldType.DoubleT ->
            "agents_engine.generation.coerceDouble($source)"
        is GenerableValidator.FieldType.FloatT ->
            "agents_engine.generation.coerceFloat($source)"
        is GenerableValidator.FieldType.BoolT ->
            "agents_engine.generation.coerceBoolean($source)"
        is GenerableValidator.FieldType.ListT -> {
            // Recursive per-item coercion. Empty/missing item type: List<Any> from raw List.
            val itemExpr = type.itemType?.let { coerceExpression(it, "it") } ?: "it"
            "agents_engine.generation.coerceList($source) { $itemExpr }"
        }
        is GenerableValidator.FieldType.GenerableRef ->
            // #1707/#2: route nested refs through the runtime extension on
            // KClass. The receiver `::class` is a Kotlin class literal that
            // compiles regardless of whether the nested class has a
            // generated companion — `agents_engine.generation.constructFromMap`
            // is `@PublishedApi internal` and consults the cache (generated
            // companion present → fast path; absent → reflection fallback,
            // or graceful null if kotlin-reflect is unavailable).
            //
            // Previously we hard-coded `${type.qualifiedName}__GeneratedSchema.constructFromMap(it)`,
            // which was an unresolved reference at compile time when the
            // nested class had default-valued params (canGenerate skipped
            // its constructFromMap emission).
            "(${source} as? Map<*, *>)?.let { ${type.qualifiedName}::class.constructFromMap(it) }"
        is GenerableValidator.FieldType.Unsupported ->
            // Validator should have caught this. Defensive — make compile fail loudly.
            error("Cannot emit coercion for unsupported type ${type.rawTypeName}")
    }
}
