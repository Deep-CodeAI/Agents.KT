package agents_engine.generation

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
fun KClass<*>.jsonSchema(): String =
    if (isSealed) sealedJsonSchema() else dataClassJsonSchema()

private fun KClass<*>.dataClassJsonSchema(): String {
    val ctor = primaryConstructor ?: return """{"type":"object"}"""
    return buildString {
        append("""{"type":"object","properties":{""")
        ctor.parameters.forEachIndexed { i, param ->
            if (i > 0) append(",")
            append(""""${param.name}":${param.jsonSchemaFragment()}""")
        }
        append("""},"required":[""")
        ctor.parameters
            .filter { !it.type.isMarkedNullable }
            .forEachIndexed { i, param ->
                if (i > 0) append(",")
                append(""""${param.name}"""")
            }
        append("]}")
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
        ctor?.parameters?.filter { !it.type.isMarkedNullable }?.forEach { param ->
            append(""","${param.name}"""")
        }
        append("]")
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

internal fun <T : Any> KClass<T>.constructFromMap(fields: Map<*, Any?>): T? {
    val ctor = primaryConstructor ?: return null
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
        Int::class -> (value as? Number)?.toInt()
        Long::class -> (value as? Number)?.toLong()
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
