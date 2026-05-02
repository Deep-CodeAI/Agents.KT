package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Live end-to-end reproduction of #702 against a real Ollama instance.
 *
 * Reproduces the original postmortem scenario verbatim: agent configured with
 * `gemma3:4b` (no native tool support) plus a tool. Without the fix, Ollama's
 * `{"error":"... does not support tools"}` envelope flowed into the user's
 * `transformOutput` as opaque JSON, surfacing as `IllegalStateException:
 * Could not parse ...`. With the fix, callers see a clean
 * `LlmProviderException` and `transformOutput` is never invoked.
 *
 * Tagged `live-llm` — excluded from `./gradlew test`, run with
 * `./gradlew integrationTest`. Requires Ollama on localhost:11434 with
 * `gemma3:4b` pulled.
 */
class OllamaProviderErrorIntegrationTest {

    @Tag("live-llm")
    @Test
    fun `gemma3 4b plus tools surfaces LlmProviderException not transformOutput parse error`() {
        var transformOutputCalled = false
        val a = agent<String, String>("repro") {
            prompt("Use the greet tool to greet the user by name.")
            model { ollama("gemma3:4b"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools { tool("greet", "Greet a person by name. Arguments: {name: string}") { args ->
                "Hello, ${args["name"]}!"
            }}
            skills {
                skill<String, String>("s", "Greet someone using the greet tool") {
                    tools("greet")
                    transformOutput { text ->
                        transformOutputCalled = true
                        text
                    }
                }
            }
        }

        try {
            a("Greet Alice.")
            fail("expected LlmProviderException — gemma3:4b does not support tools")
        } catch (e: LlmProviderException) {
            assertTrue(
                e.message!!.contains("does not support tools", ignoreCase = true),
                "exception must surface Ollama's verbatim error: ${e.message}",
            )
            assertTrue(
                e.message!!.contains("Ollama", ignoreCase = true),
                "exception must identify the provider: ${e.message}",
            )
        }

        assertEquals(
            false, transformOutputCalled,
            "transformOutput must NOT see the provider error envelope as model output",
        )
    }
}
