package agents_engine.generation

import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.*

// ─── Internal helpers ─────────────────────────────────────────────────────────

private fun String.escapeJson(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

// ─── KSP-generated schema lookup (#1701) ──────────────────────────────────────
//
// The `:agents-kt-ksp` processor emits `<ClassName>__GeneratedSchema.kt` files
// in the same package as each `@Generable` data class. Each generated object
// holds a `JSON_SCHEMA` constant that's byte-identical to what
// `dataClassJsonSchema()` produces below. Looking it up via `Class.forName`
// lets `KClass.jsonSchema()` skip the reflection walk entirely on consumers
// that apply KSP — and consumers who don't still fall through to reflection
// transparently.

private object GeneratedSchemaCache {
    // Both hits (the schema string) and misses (a sentinel) cache so we
    // never re-attempt Class.forName on the same KClass twice. Concurrent
    // map for the typical multi-thread agentic-loop access pattern.
    private val MISS = Any()
    private val cache = ConcurrentHashMap<KClass<*>, Any>()

    fun lookup(kClass: KClass<*>): String? {
        cache[kClass]?.let { return if (it === MISS) null else it as String }
        val resolved = tryLoad(kClass) ?: run {
            cache[kClass] = MISS
            return null
        }
        cache[kClass] = resolved
        return resolved
    }

    private fun tryLoad(kClass: KClass<*>): String? {
        val fqn = kClass.qualifiedName ?: return null
        return try {
            val generatedClassName = "${fqn}__GeneratedSchema"
            // Kotlin `object` declarations live as `<Name>` in the JVM class
            // namespace (their methods live on an `INSTANCE` field) but the
            // `const val` lands directly as a static field on the class.
            val cls = Class.forName(generatedClassName, /* initialize = */ true, kClass.java.classLoader)
            val field = cls.getDeclaredField("JSON_SCHEMA")
            field.isAccessible = true
            field.get(null) as? String
        } catch (_: ClassNotFoundException) {
            null   // expected when KSP isn't applied or the class is sealed
        } catch (_: Throwable) {
            // Defensive — never let lookup errors poison the reflection
            // fallback path. The generated schema must be a clean win or it
            // doesn't apply.
            null
        }
    }
}

// ─── LLM Description ─────────────────────────────────────────────────────────

/**
 * Returns a markdown description of this [@Generable] class for use in LLM prompts.
 *
 * Auto-generated from the class name, field names, types, and [@Guide] descriptions.
 * Sealed interfaces list all variants with their [@Guide] text.
 *
 * Override with [@LlmDescription] on the class when the generated text doesn't fit.
 */
fun KClass<*>.toLlmDescription(): String {
    findAnnotation<LlmDescription>()?.let { return it.text }
    return if (isSealed) sealedLlmDescription() else dataClassLlmDescription()
}

private fun KClass<*>.dataClassLlmDescription(): String {
    val ctor = primaryConstructor
    val genDescription = findAnnotation<Generable>()?.description.orEmpty()
    return buildString {
        appendLine("## $simpleName")
        if (genDescription.isNotEmpty()) {
            appendLine()
            appendLine(genDescription)
        }
        if (ctor != null && ctor.parameters.isNotEmpty()) {
            appendLine()
            ctor.parameters.forEach { param ->
                val guide = param.findAnnotation<Guide>()
                val typeName = param.type.promptTypeName()
                if (guide != null)
                    appendLine("- **${param.name}** ($typeName): ${guide.description}")
                else
                    appendLine("- **${param.name}** ($typeName)")
            }
        }
    }.trimEnd()
}

private fun KClass<*>.sealedLlmDescription(): String = buildString {
    val genDescription = findAnnotation<Generable>()?.description.orEmpty()
    appendLine("## $simpleName")
    if (genDescription.isNotEmpty()) {
        appendLine()
        appendLine(genDescription)
    }
    appendLine()
    appendLine("Choose one of the following variants:")
    sealedSubclasses.forEach { sub ->
        val guide = sub.findAnnotation<Guide>()
        appendLine()
        if (guide != null)
            appendLine("### ${sub.simpleName}: ${guide.description}")
        else
            appendLine("### ${sub.simpleName}")
        val ctor = sub.primaryConstructor
        if (ctor != null && ctor.parameters.isNotEmpty()) {
            ctor.parameters.forEach { param ->
                val paramGuide = param.findAnnotation<Guide>()
                val typeName = param.type.promptTypeName()
                if (paramGuide != null)
                    appendLine("- **${param.name}** ($typeName): ${paramGuide.description}")
                else
                    appendLine("- **${param.name}** ($typeName)")
            }
        }
    }
}.trimEnd()

// ─── JSON Schema ─────────────────────────────────────────────────────────────

/**
 * Generates a JSON Schema string for this [@Generable] class.
 *
 * - Data classes produce `{"type":"object","properties":{...},"required":[...]}`.
 * - Sealed interfaces produce `{"oneOf":[...]}` with a `"type"` discriminator per variant.
 */
fun KClass<*>.jsonSchema(): String {
    // #1701: prefer the KSP-generated schema when present. Byte-identical
    // to the reflection path, but no reflection walk, no kotlin-reflect
    // work, and zero allocation past the first call (cached). #1702
    // extended generation to sealed roots — the cache mechanism is
    // shape-agnostic (reads a JSON_SCHEMA constant regardless of whether
    // its content is data-class or `oneOf`-shaped).
    GeneratedSchemaCache.lookup(this)?.let { return it }
    return if (isSealed) sealedJsonSchema() else dataClassJsonSchema()
}

private fun KClass<*>.dataClassJsonSchema(): String {
    val ctor = primaryConstructor ?: return """{"type":"object","additionalProperties":false}"""
    return buildString {
        append("""{"type":"object","properties":{""")
        ctor.parameters.forEachIndexed { i, param ->
            if (i > 0) append(",")
            append(""""${param.name}":${param.jsonSchemaFragment()}""")
        }
        append("""},"required":[""")
        ctor.parameters
            .filter { !it.type.isMarkedNullable && !it.isOptional }
            .forEachIndexed { i, param ->
                if (i > 0) append(",")
                append(""""${param.name}"""")
            }
        // Strict by default (#661): the contract IS the type. Extras the model invents
        // would otherwise be silently dropped; better to fail constraint-decoding upfront.
        append("""],"additionalProperties":false}""")
    }
}

private fun KClass<*>.sealedJsonSchema(): String = buildString {
    append("""{"oneOf":[""")
    sealedSubclasses.forEachIndexed { i, sub ->
        if (i > 0) append(",")
        append(sub.variantJsonSchema())
    }
    append("]}")
}

private fun KClass<*>.variantJsonSchema(): String {
    val guide = findAnnotation<Guide>()
    val ctor = primaryConstructor
    return buildString {
        append("""{"type":"object","properties":{""")
        append(""""type":{"type":"string","const":"$simpleName"}""")
        ctor?.parameters?.forEach { param ->
            append(",")
            append(""""${param.name}":${param.jsonSchemaFragment()}""")
        }
        append("""},"required":["type"""")
        ctor?.parameters?.filter { !it.type.isMarkedNullable && !it.isOptional }?.forEach { param ->
            append(""","${param.name}"""")
        }
        append("""],"additionalProperties":false""")
        if (guide != null) append(""","description":"${guide.description.escapeJson()}"""")
        append("}")
    }
}

private fun KParameter.jsonSchemaFragment(): String {
    val typeObj = type.jsonSchemaTypeObject()
    val guide = findAnnotation<Guide>() ?: return typeObj
    // Insert description into the type object before the closing }
    return typeObj.dropLast(1) + ""","description":"${guide.description.escapeJson()}"}"""
}

private fun KType.jsonSchemaTypeObject(): String = when (val cls = classifier) {
    String::class -> """{"type":"string"}"""
    Int::class, Long::class -> """{"type":"integer"}"""
    Double::class, Float::class -> """{"type":"number"}"""
    Boolean::class -> """{"type":"boolean"}"""
    List::class -> {
        val itemType = arguments.firstOrNull()?.type
        if (itemType != null) """{"type":"array","items":${itemType.jsonSchemaTypeObject()}}"""
        else """{"type":"array"}"""
    }
    is KClass<*> -> if (cls.hasAnnotation<Generable>()) cls.jsonSchema() else """{"type":"string"}"""
    else -> """{"type":"string"}"""
}

// ─── Prompt Fragment ─────────────────────────────────────────────────────────

/**
 * Generates a natural-language prompt fragment instructing the LLM how to format its output.
 *
 * - Data classes produce a JSON template with field names, types, and [@Guide] descriptions.
 * - Sealed interfaces describe each variant and when to use it.
 *
 * This is injected into the skill's system prompt before the LLM runs.
 */
fun KClass<*>.promptFragment(): String =
    if (isSealed) sealedPromptFragment() else dataClassPromptFragment()

private fun KClass<*>.dataClassPromptFragment(): String {
    val ctor = primaryConstructor ?: return ""
    return buildString {
        appendLine("Respond with a JSON object matching this structure:")
        appendLine("{")
        ctor.parameters.forEachIndexed { i, param ->
            val guide = param.findAnnotation<Guide>()
            val typeName = param.type.promptTypeName()
            val comma = if (i < ctor.parameters.size - 1) "," else ""
            val content = if (guide != null) "$typeName: ${guide.description}" else typeName
            appendLine("""  "${param.name}": <$content>$comma""")
        }
        append("}")
    }
}

private fun KClass<*>.sealedPromptFragment(): String = buildString {
    appendLine("Respond with a JSON object for one of the following variants.")
    appendLine("""Set "type" to the variant name.""")
    sealedSubclasses.forEachIndexed { i, sub ->
        appendLine()
        val guide = sub.findAnnotation<Guide>()
        if (guide != null) appendLine("${sub.simpleName}: ${guide.description}")
        else appendLine("${sub.simpleName}:")
        appendLine(sub.dataClassPromptFragment())
    }
}

private fun KType.promptTypeName(): String = when (val cls = classifier) {
    String::class -> "String"
    Int::class -> "Int"
    Long::class -> "Long"
    Double::class -> "Double"
    Float::class -> "Float"
    Boolean::class -> "Boolean"
    List::class -> {
        val item = arguments.firstOrNull()?.type
        if (item != null) "List<${item.promptTypeName()}>" else "List"
    }
    is KClass<*> -> cls.simpleName ?: "Object"
    else -> "String"
}

// ─── LLM Input Serialization (typed → wire format) ──────────────────────────

/**
 * Serializes an agent input value to the form the LLM should see in a user message.
 *
 * Symmetric with [fromLlmOutput]: that takes JSON the model produced and reconstructs
 * a typed instance; this takes a typed instance and emits the wire format the model
 * should see.
 *
 * Rules:
 * - `null` → `"null"`
 * - `String` → the string as-is (no JSON quoting; matches the current behavior of
 *   passing a free-form question/instruction to a model).
 * - `Boolean` / `Number` → JSON literal.
 * - `List<*>` → JSON array, recursing on each element.
 * - `Map<*, *>` → JSON object, recursing on each value.
 * - `@Generable` data class → JSON object with each constructor param as a field.
 *   Sealed-class variants get a `"type":"VariantName"` discriminator (matches
 *   [fromLlmOutput]'s expected shape).
 * - Anything else → `value.toString()` (backward-compatible fallback for plain
 *   classes that don't opt into `@Generable`).
 *
 * See #937.
 */
fun toLlmInput(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is Boolean -> value.toString()
    is Number -> value.toString()
    is List<*> -> value.joinToString(",", "[", "]") { jsonSerialize(it) }
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"${k.toString().escapeJson()}\":${jsonSerialize(v)}"
    }
    else -> {
        val cls = value::class
        if (cls.findAnnotation<Generable>() != null) {
            generableToJson(value, cls)
        } else {
            value.toString()
        }
    }
}

/**
 * Internal recursive serializer — used by collections and Generable field
 * walking. Differs from [toLlmInput] in that strings get JSON-quoted (since
 * they're nested inside a JSON value).
 */
private fun jsonSerialize(value: Any?): String = when (value) {
    null -> "null"
    is String -> "\"${value.escapeJson()}\""
    is Boolean -> value.toString()
    is Number -> value.toString()
    is List<*> -> value.joinToString(",", "[", "]") { jsonSerialize(it) }
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { (k, v) ->
        "\"${k.toString().escapeJson()}\":${jsonSerialize(v)}"
    }
    else -> {
        val cls = value::class
        if (cls.findAnnotation<Generable>() != null) {
            generableToJson(value, cls)
        } else {
            // Non-Generable, non-primitive nested value — render via toString
            // and JSON-quote it. Lossy but consistent with falling back to
            // toString at the top level.
            "\"${value.toString().escapeJson()}\""
        }
    }
}

private fun generableToJson(value: Any, cls: KClass<*>): String {
    val ctor = cls.primaryConstructor
        ?: return "\"${value.toString().escapeJson()}\""
    val isSealedVariant = cls.allSuperclasses.any { it.isSealed }
    return buildString {
        append("{")
        var first = true
        if (isSealedVariant) {
            append("\"type\":\"${cls.simpleName}\"")
            first = false
        }
        ctor.parameters.forEach { param ->
            val name = param.name ?: return@forEach
            val prop = cls.memberProperties.find { it.name == name } ?: return@forEach
            if (!first) append(",")
            first = false
            @Suppress("UNCHECKED_CAST")
            val fieldValue = (prop as kotlin.reflect.KProperty1<Any, *>).get(value)
            append("\"${name.escapeJson()}\":${jsonSerialize(fieldValue)}")
        }
        append("}")
    }
}

// ─── Lenient Deserialization ──────────────────────────────────────────────────

/**
 * Parses [json] leniently into a [T] instance using reflection.
 *
 * Handles markdown fences, trailing commas, and surrounding explanation text.
 * For sealed interfaces, routes to the correct subclass via the `"type"` discriminator.
 * Returns null on unrecoverable input or construction failure.
 */
inline fun <reified T : Any> fromLlmOutput(json: String): T? = T::class.fromLlmOutput(json)

/**
 * Parses [json] leniently into a [T] instance.
 * See [fromLlmOutput] for the inline reified variant.
 */
fun <T : Any> KClass<T>.fromLlmOutput(json: String): T? {
    val parsed = try {
        LenientJsonParser.parse(json)
    } catch (e: Exception) {
        return null
    }

    if (isSealed) {
        val obj = parsed as? Map<*, *> ?: return null
        val typeName = obj["type"] as? String ?: return null
        val variant = sealedSubclasses.find { it.simpleName == typeName } ?: return null
        @Suppress("UNCHECKED_CAST")
        return variant.constructFromMap(obj as Map<String, Any?>) as? T
    }

    val obj = parsed as? Map<*, *> ?: return null
    @Suppress("UNCHECKED_CAST")
    return constructFromMap(obj as Map<String, Any?>)
}

@PublishedApi
internal fun <T : Any> KClass<T>.constructFromMap(fields: Map<*, Any?>): T? {
    val ctor = primaryConstructor ?: return null
    // Strict args (#665): refuse extras so additionalProperties:false is enforced
    // at the Kotlin layer regardless of provider behavior. The "type" discriminator
    // is allowed ONLY for sealed-variant construction (#669) — for plain data classes
    // an extra "type" key is a real extra and must be rejected.
    val allowedKeys = ctor.parameters.mapNotNull { it.name }.toMutableSet()
    val isSealedVariant = this.allSuperclasses.any { it.isSealed }
    if (isSealedVariant) allowedKeys.add("type")
    val incomingKeys = fields.keys.mapNotNull { it?.toString() }
    val extraKeys = incomingKeys.filter { it !in allowedKeys }
    if (extraKeys.isNotEmpty()) return null
    // #699: when "type" is present on a sealed variant, verify the discriminator
    // value matches this variant. Prevents constructing CircleStrict from a JSON
    // shaped {"type":"SquareStrict", ...}.
    if (isSealedVariant) {
        val discriminator = fields["type"] as? String
        if (discriminator != null && discriminator != simpleName) return null
    }
    return try {
        val args = mutableMapOf<KParameter, Any?>()
        for (param in ctor.parameters) {
            val raw = fields[param.name]
            val coerced = coerceValue(raw, param.type)
            when {
                coerced != null -> args[param] = coerced
                param.isOptional -> { /* omit — use default value */ }
                else -> args[param] = null  // non-optional null → callBy will throw for non-nullable
            }
        }
        ctor.callBy(args)
    } catch (e: Exception) {
        null
    }
}

@Suppress("UNCHECKED_CAST")
private fun coerceValue(value: Any?, type: KType): Any? {
    if (value == null) return null
    return when (type.classifier) {
        String::class -> value.toString()
        // #855 — `Number.toInt()` and `Number.toLong()` truncate silently on overflow.
        // Reject out-of-range values so the LLM can't slip a 99_999_999_999 into an
        // `Int` field and end up with garbage. Returning null routes through
        // `constructFromMap` → `onToolError.invalidArgs`, which is the right
        // recovery path for "this value didn't match the type contract".
        Int::class -> coerceToInt(value)
        Long::class -> coerceToLong(value)
        Double::class -> (value as? Number)?.toDouble()
        Float::class -> (value as? Number)?.toFloat()
        Boolean::class -> value as? Boolean
        List::class -> {
            val items = value as? List<*> ?: return null
            val elementType = type.arguments.firstOrNull()?.type ?: return items
            items.map { coerceValue(it, elementType) }
        }
        else -> {
            val cls = type.classifier as? KClass<*>
            if (cls != null && cls.hasAnnotation<Generable>()) {
                (cls as KClass<Any>).constructFromMap(value as? Map<*, *> ?: return null)
            } else {
                value
            }
        }
    }
}

/**
 * Coerce a JSON-decoded value to `Int` without silent truncation. Returns null when
 * the input is not a `Number`, when a fractional part would be lost, or when the
 * value is outside `Int.MIN_VALUE..Int.MAX_VALUE`. See #855.
 */
private fun coerceToInt(value: Any): Int? {
    val n = value as? Number ?: return null
    val asLong = when (n) {
        is Long, is Int, is Short, is Byte -> n.toLong()
        is Double -> {
            if (n.isNaN() || n.isInfinite() || n != Math.floor(n)) return null
            if (n < Int.MIN_VALUE.toDouble() || n > Int.MAX_VALUE.toDouble()) return null
            n.toLong()
        }
        is Float -> {
            val d = n.toDouble()
            if (d.isNaN() || d.isInfinite() || d != Math.floor(d)) return null
            if (d < Int.MIN_VALUE.toDouble() || d > Int.MAX_VALUE.toDouble()) return null
            d.toLong()
        }
        else -> n.toLong()
    }
    if (asLong !in Int.MIN_VALUE..Int.MAX_VALUE) return null
    return asLong.toInt()
}

/**
 * Coerce a JSON-decoded value to `Long` without silent truncation. Returns null on
 * non-numeric input, fractional input, or out-of-range floating-point input. See #855.
 */
private fun coerceToLong(value: Any): Long? {
    val n = value as? Number ?: return null
    return when (n) {
        is Long -> n
        is Int, is Short, is Byte -> n.toLong()
        is Double -> {
            if (n.isNaN() || n.isInfinite() || n != Math.floor(n)) return null
            if (n < Long.MIN_VALUE.toDouble() || n > Long.MAX_VALUE.toDouble()) return null
            n.toLong()
        }
        is Float -> {
            val d = n.toDouble()
            if (d.isNaN() || d.isInfinite() || d != Math.floor(d)) return null
            if (d < Long.MIN_VALUE.toDouble() || d > Long.MAX_VALUE.toDouble()) return null
            d.toLong()
        }
        else -> n.toLong()
    }
}
