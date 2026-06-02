package agents_engine.mcp

import agents_engine.core.Skill
import agents_engine.generation.LenientJsonParser
import agents_engine.generation.constructFromMap
import agents_engine.generation.hasGenerableAnnotation
import agents_engine.generation.jsonSchema
import kotlin.reflect.KClass

/**
 * Internal wrapper over a [Skill] that [McpServer] exposes as an MCP tool: builds the tool's input
 * JSON Schema and deserializes the call-time argument map into the skill's typed input (String, or
 * a `@Generable` class via the KSP-aware cache-then-reflection probe).
 */
internal class ExposedSkill private constructor(
    val skill: Skill<*, *>,
    private val inputBuilder: (Map<String, Any?>) -> Any?,
    private val schema: Map<String, Any?>,
) {
    fun toMcpDescriptor(): Map<String, Any?> = buildMap {
        put("name", skill.name)
        put("description", skill.description)
        put("inputSchema", schema)
    }

    fun toMcpToolInfo(): McpToolInfo =
        McpToolInfo(name = skill.name, description = skill.description, inputSchema = schema)

    fun deserializeInput(args: Map<String, Any?>): Any? = inputBuilder(args)

    companion object {
        fun of(skill: Skill<*, *>): ExposedSkill {
            val inType = skill.inType
            return when {
                inType == String::class -> ExposedSkill(
                    skill = skill,
                    inputBuilder = { args ->
                        args["input"] as? String
                            ?: error("tool '${skill.name}' expects {\"input\": string}; got: $args")
                    },
                    schema = mapOf(
                        "type" to "object",
                        "properties" to mapOf("input" to mapOf("type" to "string")),
                        "required" to listOf("input"),
                    ),
                )
                // #1718: cache-aware probe, reflection-free when KSP generated
                // the companion. Falls through to wrapped reflection otherwise.
                inType.hasGenerableAnnotation() -> ExposedSkill(
                    skill = skill,
                    inputBuilder = { args ->
                        @Suppress("UNCHECKED_CAST")
                        (inType as KClass<Any>).constructFromMap(args)
                            ?: error("tool '${skill.name}' could not deserialize @Generable ${inType.simpleName} from: $args")
                    },
                    schema = parseSchema(inType.jsonSchema()),
                )
                else -> throw IllegalArgumentException(
                    "Skill \"${skill.name}\" has unsupported IN type ${inType.simpleName}. " +
                        "McpServer only exposes skills whose IN is String or a @Generable class."
                )
            }
        }

        @Suppress("UNCHECKED_CAST")
        private fun parseSchema(json: String): Map<String, Any?> =
            (LenientJsonParser.parse(json) as? Map<String, Any?>)
                ?: mapOf("type" to "object")
    }
}
