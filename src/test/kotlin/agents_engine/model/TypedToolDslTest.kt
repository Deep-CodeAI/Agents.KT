package agents_engine.model

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

@Generable("A request to greet someone")
data class GreetArgs(
    @Guide("Name of the person to greet") val name: String,
    @Guide("Greeting language code, e.g. en, ru") val language: String = "en",
)

@Generable data class GreetResult(val message: String)

/**
 * Tests for the typed `tool<Args, Result>("name") { args -> ... }` DSL (issue #634).
 *
 * The builder wraps a typed executor in a `Map -> Any?` adapter so the rest of
 * the agentic loop is unchanged. ToolDef.argsType records the Args class for
 * downstream consumers (provider schema generation in #635, runtime validation
 * in #636).
 */
class TypedToolDslTest {

    @Test
    fun `typed tool produces a ToolDef with argsType set`() {
        val a = agent<String, String>("a") {
            lateinit var greet: Tool<GreetArgs, GreetResult>
            tools {
                greet = tool<GreetArgs, GreetResult>("greet", "Greets a person") { args ->
                    GreetResult("Hello, ${args.name}!")
                }
            }
            skills { skill<String, String>("s", "stub") { tools(greet) } }
        }
        val def = a.toolMap["greet"]
        assertNotNull(def)
        assertEquals(GreetArgs::class, def.argsType)
    }

    @Test
    fun `typed executor receives a properly constructed Args instance`() {
        var captured: GreetArgs? = null
        val a = agent<String, String>("a") {
            lateinit var greet: Tool<GreetArgs, GreetResult>
            tools {
                greet = tool<GreetArgs, GreetResult>("greet", "Greets") { args ->
                    captured = args
                    GreetResult("Hello, ${args.name}!")
                }
            }
            skills { skill<String, String>("s", "stub") { tools(greet) } }
        }
        // Invoke the wrapped executor directly with a Map (as the agentic loop would)
        val result = a.toolMap["greet"]!!.executor(mapOf("name" to "Konstantin", "language" to "en"))

        assertNotNull(captured)
        assertEquals("Konstantin", captured!!.name)
        assertEquals("en", captured!!.language)
        assertEquals("Hello, Konstantin!", (result as GreetResult).message)
    }

    @Test
    fun `typed executor uses default values from data class when fields are absent`() {
        val a = agent<String, String>("a") {
            lateinit var greet: Tool<GreetArgs, GreetResult>
            tools {
                greet = tool<GreetArgs, GreetResult>("greet", "Greets") { args ->
                    GreetResult("[${args.language}] hi ${args.name}")
                }
            }
            skills { skill<String, String>("s", "stub") { tools(greet) } }
        }
        // 'language' has a default of "en"; only pass 'name'
        val result = a.toolMap["greet"]!!.executor(mapOf("name" to "Kon")) as GreetResult
        assertEquals("[en] hi Kon", result.message)
    }

    @Test
    fun `typed executor throws on missing required field (recovery routing in 636)`() {
        val a = agent<String, String>("a") {
            lateinit var greet: Tool<GreetArgs, GreetResult>
            tools {
                greet = tool<GreetArgs, GreetResult>("greet", "Greets") { args -> GreetResult(args.name) }
            }
            skills { skill<String, String>("s", "stub") { tools(greet) } }
        }
        try {
            a.toolMap["greet"]!!.executor(emptyMap())  // missing required 'name'
            fail("expected typed builder to refuse construction from incomplete map")
        } catch (e: Throwable) {
            assertTrue(
                e.message!!.contains("GreetArgs", ignoreCase = true) ||
                    e.message!!.contains("name", ignoreCase = true),
                "error must mention the type or the missing field: ${e.message}",
            )
        }
    }

    @Test
    fun `existing untyped tool builder still works (regression)`() {
        val a = agent<String, String>("a") {
            lateinit var legacy: Tool<Map<String, Any?>, Any?>
            tools {
                legacy = tool("legacy", "untyped tool") { args -> "got: ${args["x"]}" }
            }
            skills { skill<String, String>("s", "stub") { tools(legacy) } }
        }
        assertEquals("got: 42", a.toolMap["legacy"]!!.executor(mapOf("x" to 42)))
        // Untyped tool has null argsType — discriminator for #635 schema gen
        assertEquals(null, a.toolMap["legacy"]!!.argsType)
    }

    @Test
    fun `typed tool integrates with agentic loop through allowlist`() {
        val responses = ArrayDeque<LlmResponse>()
        responses.add(LlmResponse.ToolCalls(listOf(ToolCall(name = "greet", arguments = mapOf("name" to "world", "language" to "ru")))))
        responses.add(LlmResponse.Text("done"))
        val mock = ModelClient { _ -> responses.removeFirst() }

        var capturedArgs: GreetArgs? = null
        val a = agent<String, String>("a") {
            lateinit var greet: Tool<GreetArgs, GreetResult>
            model { ollama("llama3"); client = mock }
            tools {
                greet = tool<GreetArgs, GreetResult>("greet", "Greets") { args ->
                    capturedArgs = args
                    GreetResult("ok")
                }
            }
            skills { skill<String, String>("s", "stub") { tools(greet) } }
        }
        a("hello")
        assertEquals("world", capturedArgs?.name)
        assertEquals("ru", capturedArgs?.language)
    }
}
