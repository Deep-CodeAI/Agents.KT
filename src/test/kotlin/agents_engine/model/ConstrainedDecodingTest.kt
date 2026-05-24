package agents_engine.model

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import agents_engine.generation.LenientJsonParser
import agents_engine.generation.jsonSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConstrainedDecodingTest {

    @Generable("Structured answer for constrained decoding tests")
    data class StructuredAnswer(
        @Guide("Final answer text") val answer: String,
        @Guide("Confidence from 0 to 1") val confidence: Double,
    )

    @Test
    fun `OpenAI request carries response_format json_schema when schema is supplied`() {
        val schema = StructuredAnswer::class.toJsonSchema()
        val json = OpenAiClient(apiKey = "test", model = "gpt-4o")
            .buildRequestJson(
                messages = listOf(LlmMessage("user", "answer")),
                jsonSchema = schema,
            )
        val root = json.asMap()

        val responseFormat = root["response_format"] as? Map<*, *>
        assertNotNull(responseFormat, "OpenAI request must include response_format: $json")
        assertEquals("json_schema", responseFormat["type"])
        val payload = responseFormat["json_schema"] as? Map<*, *>
        assertNotNull(payload)
        assertEquals("StructuredAnswer", payload["name"])
        assertEquals(true, payload["strict"])
        val rawSchema = payload["schema"] as? Map<*, *>
        assertNotNull(rawSchema)
        assertEquals("object", rawSchema["type"])
        assertTrue("answer" in ((rawSchema["properties"] as Map<*, *>).keys))
    }

    @Test
    fun `OpenAI request omits response_format by default`() {
        val json = OpenAiClient(apiKey = "test", model = "gpt-4o")
            .buildRequestJson(listOf(LlmMessage("user", "answer")))

        assertNull(json.asMap()["response_format"])
    }

    @Test
    fun `DeepSeek reports constrained decoding unsupported`() {
        val client = DeepSeekClient(apiKey = "test", model = "deepseek-v4-flash")

        assertTrue(!client.supportsConstrainedDecoding())
    }

    @Test
    fun `Ollama request carries inline format schema when schema is supplied`() {
        val schema = StructuredAnswer::class.toJsonSchema()
        val json = OllamaClient(model = "llama3")
            .buildRequestJson(
                messages = listOf(LlmMessage("user", "answer")),
                jsonSchema = schema,
            )
        val root = json.asMap()

        val format = root["format"] as? Map<*, *>
        assertNotNull(format, "Ollama request must include format schema: $json")
        assertEquals("object", format["type"])
        assertTrue("confidence" in ((format["properties"] as Map<*, *>).keys))
    }

    @Test
    fun `Claude request carries forced structured output tool when schema is supplied`() {
        val schema = StructuredAnswer::class.toJsonSchema()
        val json = ClaudeClient(apiKey = "test", model = "claude-opus-4-7")
            .buildRequestJson(
                messages = listOf(LlmMessage("user", "answer")),
                jsonSchema = schema,
            )
        val root = json.asMap()

        val tools = root["tools"] as? List<*>
        assertNotNull(tools, "Claude request must include structured-output tool: $json")
        val tool = tools.single() as Map<*, *>
        assertEquals("structured_output", tool["name"])
        assertEquals("object", (tool["input_schema"] as Map<*, *>)["type"])
        val choice = root["tool_choice"] as? Map<*, *>
        assertNotNull(choice)
        assertEquals("tool", choice["type"])
        assertEquals("structured_output", choice["name"])
    }

    @Test
    fun `Claude structured output tool result is parsed as final JSON text`() {
        val schema = StructuredAnswer::class.toJsonSchema()
        val response = ClaudeClient(apiKey = "test", model = "claude-opus-4-7").parseResponse(
            body = """
                {
                  "content": [{
                    "type": "tool_use",
                    "id": "toolu_1",
                    "name": "structured_output",
                    "input": {"answer": "yes", "confidence": 0.9}
                  }]
                }
            """.trimIndent(),
            jsonSchema = schema,
        )

        val text = response as? LlmResponse.Text
        assertNotNull(text)
        assertEquals("yes", (text.content.asMap()["answer"]))
        assertEquals(0.9, (text.content.asMap()["confidence"] as Number).toDouble())
    }

    @Test
    fun `AgenticLoop passes generable output schema to supporting clients`() {
        val client = CapturingSchemaClient(supports = true)
        val parser = agent<String, StructuredAnswer>("parser") {
            model { ollama("stub"); this.client = client }
            skills {
                skill<String, StructuredAnswer>("parse", "Parse structured answer") {
                    tools()
                }
            }
        }

        val result = parser("extract")

        assertEquals(StructuredAnswer("ok", 1.0), result)
        assertNotNull(client.capturedSchema, "Generable output schema should be passed to the model client")
        assertEquals("StructuredAnswer", client.capturedSchema?.name)
        assertTrue(client.capturedSchema?.schema.orEmpty().contains("confidence"))
    }

    @Test
    fun `AgenticLoop does not pass generable output schema to unsupported clients`() {
        val client = CapturingSchemaClient(supports = false)
        val parser = agent<String, StructuredAnswer>("parser") {
            model { ollama("stub"); this.client = client }
            skills {
                skill<String, StructuredAnswer>("parse", "Parse structured answer") {
                    tools()
                }
            }
        }

        val result = parser("extract")

        assertEquals(StructuredAnswer("ok", 1.0), result)
        assertNull(client.capturedSchema)
    }

    private class CapturingSchemaClient(
        private val supports: Boolean,
    ) : ModelClient {
        var capturedSchema: JsonSchema? = null

        override fun supportsConstrainedDecoding(): Boolean = supports

        override fun chat(messages: List<LlmMessage>): LlmResponse =
            LlmResponse.Text("""{"answer":"ok","confidence":1.0}""")

        override fun chat(messages: List<LlmMessage>, jsonSchema: JsonSchema?): LlmResponse {
            capturedSchema = jsonSchema
            return LlmResponse.Text("""{"answer":"ok","confidence":1.0}""")
        }
    }

    private fun kotlin.reflect.KClass<*>.toJsonSchema(): JsonSchema =
        JsonSchema(simpleName ?: "structured_output", jsonSchema())

    private fun String.asMap(): Map<*, *> =
        LenientJsonParser.parse(this) as? Map<*, *>
            ?: error("not a JSON object: $this")
}
