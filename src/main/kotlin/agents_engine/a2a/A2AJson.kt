package agents_engine.a2a

import agents_engine.mcp.McpJson
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties

/**
 * #3864 — JSON helpers for the A2A wire. Encoding reuses [McpJson] for
 * scalars/maps/lists; typed payloads are flattened to a property map via
 * Kotlin reflection (flat `@Generable`-style data classes — the same shape
 * the lenient decoder reconstructs on the other side). Not a general
 * object mapper; nested custom types render via their `toString()`.
 */
internal object A2AJson {
    fun encode(value: Any?): String = McpJson.encode(value)

    /** Render a typed output as a JSON object of its public properties. */
    fun encodeTyped(value: Any): String {
        val map = value::class.memberProperties.associate { prop ->
            prop.name to prop.getter.call(value)
        }
        return McpJson.encode(map)
    }

    fun isSimple(klass: KClass<*>): Boolean =
        klass == String::class || klass == Int::class || klass == Long::class ||
            klass == Double::class || klass == Float::class || klass == Boolean::class
}
