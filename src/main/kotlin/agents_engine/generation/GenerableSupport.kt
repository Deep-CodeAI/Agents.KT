package agents_engine.generation

import agents_engine.internal.toJsonString
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KType
import kotlin.reflect.full.*

/**
 * `agents_engine/generation/GenerableSupport.kt` — the runtime support
 * for `@Generable` types. Three public surfaces: `jsonSchema`,
 * `toLlmDescription`, and `constructFromMap` (the reverse — JSON-ish
 * Map back into a typed instance). Each tries the KSP-generated path
 * first (#1701 / #1702 / #1703 / #1704 via the
 * `:agents-kt-ksp` processor, looked up as `<ClassName>__GeneratedSchema`
 * objects) and falls back to reflection via [ReflectionFallback]. With
 * KSP applied, consumers can run without `kotlin-reflect` on the
 * classpath entirely (#1718). See
 * `src/main/resources/internals-agent/generation/GenerableSupport.md`
 * (#1837 / #1860).
 */

// ─── Internal helpers ─────────────────────────────────────────────────────────

// #2799 — was a local 5-char `\ " \n \r \t` replace chain that produced
// invalid JSON for U+0000-U+001F control chars (`\b`, `\f`, NUL, ESC). Now
// delegates to the central [toJsonString] (RFC 8259 §7-conformant) and
// strips the surrounding quotes that [toJsonString] adds — every call site
// in this file wraps the result in its own `"..."`, so the central
// quote-adding contract would over-quote.
private fun String.escapeJson(): String {
    val quoted = this.toJsonString()
    return quoted.substring(1, quoted.length - 1)
}

// ─── KSP-generated metadata lookup (#1701 / #1702 / #1703) ────────────────────
//
// The `:agents-kt-ksp` processor emits `<ClassName>__GeneratedSchema.kt` per
// `@Generable` class. Each generated object holds named `const val` strings
// (`JSON_SCHEMA`, `LLM_DESCRIPTION`, …) byte-identical to what the runtime
// reflection paths produce. Looking them up via `Class.forName` lets the
// runtime skip reflection on consumers that apply KSP, and falls through to
// the reflection paths transparently when no generated object exists.

private object GeneratedMetaCache {
    // Per-class entry: constants map + optional generated constructor method.
    // ConcurrentHashMap for the typical multi-thread agentic-loop access.
    private data class Entry(
        val constants: Map<String, String>,
        val constructor: java.lang.reflect.Method?,
    )
    private val MISS = Entry(emptyMap(), null)
    private val cache = ConcurrentHashMap<KClass<*>, Entry>()

    /** Lookup the `JSON_SCHEMA` constant emitted by the schema-gen pass (#1701, #1702). */
    fun lookupJsonSchema(kClass: KClass<*>): String? = load(kClass).constants["JSON_SCHEMA"]

    /** Lookup the `LLM_DESCRIPTION` constant emitted by the description-gen pass (#1703). */
    fun lookupLlmDescription(kClass: KClass<*>): String? = load(kClass).constants["LLM_DESCRIPTION"]

    /**
     * Lookup the generated `constructFromMap(Map<*, Any?>): T?` method (#1704).
     * Returns a typed invocation lambda when present; null when the class
     * has no generated companion or no `constructFromMap` method (e.g. the
     * class had default-valued params and KSP skipped that emission).
     */
    fun lookupConstructor(kClass: KClass<*>): ((Map<*, Any?>) -> Any?)? {
        val method = load(kClass).constructor ?: return null
        return { fields -> method.invoke(null, fields) }
    }

    private fun load(kClass: KClass<*>): Entry {
        cache[kClass]?.let { return it }
        val loaded = tryLoad(kClass) ?: MISS
        cache[kClass] = loaded
        return loaded
    }

    private fun tryLoad(kClass: KClass<*>): Entry? {
        val fqn = kClass.qualifiedName ?: return null
        return try {
            val generatedClassName = "${fqn}__GeneratedSchema"
            // Kotlin `object` declarations carry their `const val` fields as
            // public static finals on the JVM class — easy to read without
            // touching the INSTANCE.
            val cls = Class.forName(generatedClassName, /* initialize = */ true, kClass.java.classLoader)
            val constants = HashMap<String, String>()
            for (field in cls.declaredFields) {
                val mods = field.modifiers
                if (java.lang.reflect.Modifier.isStatic(mods) &&
                    java.lang.reflect.Modifier.isFinal(mods) &&
                    field.type == String::class.java
                ) {
                    field.isAccessible = true
                    val value = field.get(null) as? String
                    if (value != null) constants[field.name] = value
                }
            }
            // #1704: find the @JvmStatic constructFromMap(Map) method on
            // the generated object. May be null when codegen skipped it
            // (defaults-valued params; reflection still handles those).
            val constructor = try {
                cls.getMethod("constructFromMap", Map::class.java).also { it.isAccessible = true }
            } catch (_: NoSuchMethodException) {
                null
            }
            if (constants.isEmpty() && constructor == null) null
            else Entry(constants, constructor)
        } catch (_: ClassNotFoundException) {
            null   // expected when KSP isn't applied to this class
        } catch (_: Throwable) {
            // Defensive — never let lookup errors poison the reflection
            // fallback. The generated metadata must be a clean win or it
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
    // #1703: prefer the KSP-generated description constant when present.
    // The generator already bakes the `@LlmDescription` override into the
    // constant, so a single cache lookup covers both auto-generated and
    // overridden paths. The reflection fallback below still handles the
    // override for non-KSP consumers.
    GeneratedMetaCache.lookupLlmDescription(this)?.let { return it }
    // #1705: wrap reflection paths so a missing kotlin-reflect degrades
    // to the simple class-name fallback instead of crashing.
    return ReflectionFallback.withReflection {
        findAnnotation<LlmDescription>()?.let { return@withReflection it.text }
        if (isSealed) sealedLlmDescription() else dataClassLlmDescription()
    } ?: "## ${simpleName ?: "Unknown"}"
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
    // extended to sealed roots; #1703 generalised the cache to load all
    // string constants from the generated object at once.
    GeneratedMetaCache.lookupJsonSchema(this)?.let { return it }
    // #1705: reflection fallback. kotlin-reflect is now compileOnly; if
    // it's absent from the consumer's runtime classpath the body throws
    // NoClassDefFoundError, which we degrade to the "no schema available"
    // string. Consumers see the same empty-object schema they'd see for
    // an unsupported type — the agentic loop's invalidArgs path takes over.
    return ReflectionFallback.withReflection {
        if (isSealed) sealedJsonSchema() else dataClassJsonSchema()
    } ?: """{"type":"object","additionalProperties":false}"""
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
    is KClass<*> -> when {
        // #2479 — enum fields render as a typed string with an `enum` value
        // list so the LLM (and the constrained-decoding provider path) knows
        // exactly which values are legal. Constant names are emitted verbatim
        // from `Enum.name` — no case mutation (Koog regression: their
        // Anthropic client lowercased these and broke `@SerialName`).
        cls.java.isEnum -> {
            val constants = cls.java.enumConstants
                ?.mapNotNull { (it as? Enum<*>)?.name }
                ?: emptyList()
            val values = constants.joinToString(",") { "\"${it.escapeJson()}\"" }
            """{"type":"string","enum":[$values]}"""
        }
        cls.hasAnnotation<Generable>() -> cls.jsonSchema()
        else -> """{"type":"string"}"""
    }
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
        // #1718: cache-first @Generable detection. Reflection-free for
        // KSP-applied consumers; wrapped reflection fallback otherwise.
        if (cls.hasGenerableAnnotation()) {
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
        // #1718: cache-first @Generable detection. Reflection-free for
        // KSP-applied consumers; wrapped reflection fallback otherwise.
        if (cls.hasGenerableAnnotation()) {
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
    // #1718: this function walks ctor params + member properties — pure
    // kotlin-reflect territory. Wrap so a missing kotlin-reflect degrades
    // to `toString()` rendering (lossy but doesn't crash).
    return ReflectionFallback.withReflection {
        val ctor = cls.primaryConstructor
            ?: return@withReflection "\"${value.toString().escapeJson()}\""
        val isSealedVariant = cls.allSuperclasses.any { it.isSealed }
        buildString {
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
    } ?: "\"${value.toString().escapeJson()}\""
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

    // #1718: `isSealed` is itself a kotlin-reflect call. Wrap it so
    // consumers without reflect fall through to the data-class path
    // rather than crashing on the check. Sealed-root dispatch without
    // reflection isn't yet supported — that's a KSP-generated dispatcher
    // for a follow-up. For now: with reflect missing AND the type is
    // sealed, we lose the dispatcher entirely and return null (caller's
    // null-handling path takes over).
    val isSealedRoot = ReflectionFallback.withReflection { isSealed } == true
    if (isSealedRoot) {
        return ReflectionFallback.withReflection {
            val obj = parsed as? Map<*, *> ?: return@withReflection null
            val typeName = obj["type"] as? String ?: return@withReflection null
            val variant = sealedSubclasses.find { it.simpleName == typeName } ?: return@withReflection null
            @Suppress("UNCHECKED_CAST")
            variant.constructFromMap(obj as Map<String, Any?>) as? T
        }
    }

    val obj = parsed as? Map<*, *> ?: return null
    @Suppress("UNCHECKED_CAST")
    return constructFromMap(obj as Map<String, Any?>)
}

@Suppress("UNCHECKED_CAST")
@PublishedApi
internal fun <T : Any> KClass<T>.constructFromMap(fields: Map<*, Any?>): T? {
    // #1704: prefer the KSP-generated constructFromMap when present. The
    // generated method is byte-for-byte equivalent to the reflection path
    // for classes without default-valued params; for everything else, the
    // cache returns null and we fall through to reflection below.
    GeneratedMetaCache.lookupConstructor(this)?.let { invoke ->
        return invoke(fields) as T?
    }
    // #1705: wrap reflection — typed-tool deserialization returns null on
    // missing kotlin-reflect, which routes through onError.invalidArgs.
    return ReflectionFallback.withReflection {
        constructFromMapReflective(fields)
    }
}

@Suppress("UNCHECKED_CAST")
private fun <T : Any> KClass<T>.constructFromMapReflective(fields: Map<*, Any?>): T? {
    // #2482a — sealed parent dispatch. When the called class is a sealed
    // PARENT (not a variant), `primaryConstructor` is null and we'd return
    // null below — meaning a sealed @Generable input type (e.g. a McpServer
    // skill IN type) is unusable. Look up the variant by the `type`
    // discriminator and recurse into it. data-object variants resolve via
    // `objectInstance` (no constructor to call).
    if (this.isSealed) {
        val typeName = fields["type"] as? String ?: return null
        val variant = sealedSubclasses.firstOrNull { it.simpleName == typeName } ?: return null
        // data-object variants: no fields, the singleton IS the value.
        variant.objectInstance?.let {
            @Suppress("UNCHECKED_CAST")
            return it as? T
        }
        @Suppress("UNCHECKED_CAST")
        return (variant as KClass<Any>).constructFromMap(fields) as? T
    }

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
            // #2482b — accept a stringified JSON array when the value
            // arrives as a String (some providers / LLMs send list-typed
            // args wrapped as JSON text). String FIELDS stay strings
            // because the `String::class` branch above runs first.
            val items = (value as? List<*>)
                ?: (value as? String)?.let { LenientJsonParser.parse(it) as? List<*> }
                ?: return null
            val elementType = type.arguments.firstOrNull()?.type ?: return items
            items.map { coerceValue(it, elementType) }
        }
        else -> {
            val cls = type.classifier as? KClass<*>
            if (cls != null && cls.hasAnnotation<Generable>()) {
                // #2482b — accept a stringified JSON object when the value
                // arrives as a String. Same guard as the List branch —
                // String FIELDS already returned above; only fields typed
                // `Generable` (object or sealed) reach here.
                val map: Map<*, *> = (value as? Map<*, *>)
                    ?: (value as? String)?.let { LenientJsonParser.parse(it) as? Map<*, *> }
                    ?: return null
                (cls as KClass<Any>).constructFromMap(map)
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

// ─── @Generable annotation probe (#1718, v0.4.6) ─────────────────────────────
//
// Replaces direct `findAnnotation<Generable>()` calls at consumer-facing sites
// (ToolDef typed-tool validation, McpServer @Generable detection, toLlmInput
// branch). Routes through the KSP-generated cache first — fast, reflection-
// free, works even without kotlin-reflect on the classpath. Falls through to
// reflection (wrapped) for non-KSP consumers; returns false cleanly when both
// are unavailable.

@PublishedApi
internal fun KClass<*>.hasGenerableAnnotation(): Boolean {
    // Cache check is the load-bearing reflection-free path. If the KSP
    // processor generated a `<FQN>__GeneratedSchema` for this class, it has
    // `@Generable` by definition (the processor only walks annotated classes).
    if (GeneratedMetaCache.lookupJsonSchema(this) != null) return true
    if (GeneratedMetaCache.lookupLlmDescription(this) != null) return true
    // Reflection fallback for consumers without KSP applied. Wrapped so a
    // missing kotlin-reflect degrades to false rather than crashing.
    return ReflectionFallback.withReflection {
        findAnnotation<Generable>() != null
    } == true
}

// ─── @PublishedApi coercion helpers for generated code (#1704) ────────────────
//
// Generated `constructFromMap` lives in the consumer's module and needs to
// call into the framework's strict coercion. Expose the helpers under
// @PublishedApi internal so they're callable from generated code without
// landing in the public API. Each is a typed wrapper over the existing
// private coerceValue path — same semantics, same overflow rejection.

@PublishedApi internal fun coerceString(value: Any?): String? = value?.toString()

@PublishedApi internal fun coerceInt(value: Any?): Int? =
    if (value == null) null else coerceToInt(value)

@PublishedApi internal fun coerceLong(value: Any?): Long? =
    if (value == null) null else coerceToLong(value)

@PublishedApi internal fun coerceDouble(value: Any?): Double? =
    (value as? Number)?.toDouble()

@PublishedApi internal fun coerceFloat(value: Any?): Float? =
    (value as? Number)?.toFloat()

@PublishedApi internal fun coerceBoolean(value: Any?): Boolean? = value as? Boolean

/**
 * Coerce a JSON-decoded list-shaped value with per-item coercion. Returns
 * null if the value isn't a List, or if any item's coercion returns null.
 */
@PublishedApi internal fun <T : Any> coerceList(value: Any?, perItem: (Any?) -> T?): List<T>? {
    val items = value as? List<*> ?: return null
    val result = ArrayList<T>(items.size)
    for (item in items) {
        val coerced = perItem(item) ?: return null
        result += coerced
    }
    return result
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
