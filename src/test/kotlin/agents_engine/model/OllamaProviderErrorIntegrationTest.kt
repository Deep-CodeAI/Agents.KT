package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Live end-to-end test of the #706 inline-tool fallback against real Ollama.
 *
 * Runs the original postmortem scenario verbatim: agent configured with
 * `gemma3:4b` (no native tool support) plus a tool. Without any fallback
 * this would surface as `LlmProviderException` from #702. With the #706
 * fallback, OllamaClient transparently retries with the tool catalog
 * embedded in a system message, and the model emits inline JSON tool
 * calls that the framework executes — agent completes normally.
 *
 * Tagged `live-llm` — excluded from `./gradlew test`, run via
 * `./gradlew integrationTest`. Requires Ollama on localhost:11434 with
 * `gemma3:4b` pulled.
 */
class OllamaProviderErrorIntegrationTest {

    @Tag("live-llm")
    @Test
    fun `gemma3 4b plus tools triggers inline fallback and tool gets executed`() {
        // The framework guarantee under test: gemma3:4b (no native tool support)
        // no longer surfaces as LlmProviderException — the inline-prompt fallback
        // drives the model into the inline JSON tool-call format and the tool
        // actually executes. The model's final-turn text quality is out of scope.
        var toolCalled = false
        var greetedName: String? = null

        val a = agent<String, String>("repro") {
            prompt(
                "You are a tool-calling agent. To greet someone, call the greet tool. " +
                    "After getting the tool result, repeat it verbatim as your final answer."
            )
            model { ollama("gemma3:4b"); host = "localhost"; port = 11434; temperature = 0.0 }
            tools {
                tool("greet", "Greet a person by name. Arguments: {name: string}") { args ->
                    toolCalled = true
                    greetedName = args["name"] as? String
                    "Hello, ${args["name"]}!"
                }
            }
            skills {
                skill<String, String>("s", "Greet someone using the greet tool") {
                    tools("greet")
                }
            }
        }

        // Must not throw LlmProviderException — that's the regression #706 prevents.
        a("Greet Alice.")

        assertTrue(toolCalled, "Inline-tool fallback should have driven greet to be called")
        assertTrue(
            greetedName.equals("Alice", ignoreCase = true),
            "Tool should have been called with name=Alice, got: $greetedName",
        )
    }
}
