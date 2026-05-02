package agents_engine.model

import agents_engine.generation.LenientJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for #635 — typed `ToolDef.argsType` produces a real JSON Schema in
 * the Ollama provider envelope, not the legacy `properties: {}, additionalProperties: true`.
 */
class OllamaSchemaGenerationTest {

    private fun captureRequestBody(tools: List<ToolDef>): Map<String, Any?> {
        val client = OllamaClient(host = "localhost", port = 11434, model = "test-model", tools = tools)
        val body = client.buildRequestJson(listOf(LlmMessage("user", "hi")))
        @Suppress("UNCHECKED_CAST")
        return LenientJsonParser.parse(body) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun firstToolParameters(body: Map<String, Any?>): Map<String, Any?> {
        val toolsArr = body["tools"] as? List<Map<String, Any?>>
        assertNotNull(toolsArr, "request body must include tools array; got: $body")
        val function = (toolsArr.first()["function"] as? Map<String, Any?>) ?: error("no function in tool: ${toolsArr.first()}")
        return function["parameters"] as? Map<String, Any?> ?: error("no parameters in function: $function")
    }

    @Test
    fun `typed tool produces a parameters block with the Args fields`() {
        // Use the existing GreetArgs from TypedToolDslTest
        val typedDef = ToolDef(
            name = "greet",
            description = "Greets",
            argsType = GreetArgs::class,
            executor = { _ -> "ok" },
        )

        val body = captureRequestBody(listOf(typedDef))
        val parameters = firstToolParameters(body)

        assertEquals("object", parameters["type"])
        @Suppress("UNCHECKED_CAST")
        val properties = parameters["properties"] as? Map<String, Any?>
        assertNotNull(properties, "typed tool must produce properties; got: $parameters")
        assertTrue("name" in properties, "Args field 'name' must appear in properties: $properties")
        assertTrue("language" in properties, "Args field 'language' must appear in properties: $properties")
    }

    @Test
    fun `typed tool exposes Guide descriptions per field`() {
        val typedDef = ToolDef(
            name = "greet",
            description = "Greets",
            argsType = GreetArgs::class,
            executor = { _ -> "ok" },
        )

        val parameters = firstToolParameters(captureRequestBody(listOf(typedDef)))
        @Suppress("UNCHECKED_CAST")
        val properties = parameters["properties"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val nameField = properties["name"] as Map<String, Any?>
        val description = nameField["description"] as? String
        assertNotNull(description)
        assertTrue(
            description.contains("Name", ignoreCase = true) || description.contains("greet", ignoreCase = true),
            "Guide description must surface in the schema; got: $description",
        )
    }

    @Test
    fun `typed tool declares required fields based on Args primary constructor`() {
        val typedDef = ToolDef(
            name = "greet",
            description = "Greets",
            argsType = GreetArgs::class,
            executor = { _ -> "ok" },
        )

        val parameters = firstToolParameters(captureRequestBody(listOf(typedDef)))
        @Suppress("UNCHECKED_CAST")
        val required = parameters["required"] as? List<String>
        assertNotNull(required, "typed tool must declare required fields; got: $parameters")
        assertTrue("name" in required, "'name' (no default) must be required: $required")
        assertTrue("language" !in required, "'language' (has default) must NOT be required: $required")
    }

    @Test
    fun `untyped tool keeps the legacy generic schema (regression)`() {
        val untypedDef = ToolDef(
            name = "legacy",
            description = "old style",
            executor = { _ -> "ok" },  // no argsType
        )

        val parameters = firstToolParameters(captureRequestBody(listOf(untypedDef)))
        assertEquals("object", parameters["type"])
        @Suppress("UNCHECKED_CAST")
        val properties = parameters["properties"] as? Map<String, Any?>
        assertNotNull(properties)
        assertTrue(properties.isEmpty(), "untyped tool must have empty properties: $properties")
        assertEquals(true, parameters["additionalProperties"], "untyped tool must allow additional properties")
    }
}
