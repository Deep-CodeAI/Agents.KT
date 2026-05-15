package agents_engine.runtime.events

import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * #1737 — live-LLM end-to-end exercise of `agent.session(input)` against a
 * real Ollama. Tagged `live-llm` so the default suite skips it; runs via
 * `./gradlew integrationTest`. Skips cleanly when Ollama is not reachable
 * at `localhost:11434`.
 *
 * Verifiable assertion target: π. The agent is asked to recite π to 20
 * decimal places — the canonical sequence is `3.14159265358979323846`. We
 * check the output contains the leading 15 decimal digits (`3.14159265358979`)
 * as a robust pass condition (every reasonable LLM hits 15; only very small
 * models miss it), and additionally log whether the full 20-digit sequence
 * landed for diagnostic purposes. This keeps the test stable across model
 * choices while still proving the streaming session round-tripped a useful
 * answer through the agentic loop.
 */
class AgentSessionLiveTest {

    private val ollamaModel: String = System.getenv("AGENTSKT_TEST_OLLAMA_MODEL") ?: "gpt-oss:120b-cloud"

    @Tag("live-llm")
    @Test
    fun `session against Ollama — π to 20 decimal places, events ordered, output contains canonical digits`() = runBlocking {
        assumeTrue(isOllamaReachable(), "skipping: no Ollama at localhost:11434")

        val piAgent = agent<String, String>("pi-reciter") {
            prompt(
                "You are a numeric assistant. When the user asks for π (pi), respond with the value to " +
                    "EXACTLY 20 decimal places. Output ONLY the number — no words, no equals sign, no units, " +
                    "no commentary. Example format: 3.14159265358979323846"
            )
            model {
                ollama(ollamaModel)
                host = "localhost"
                port = 11434
                temperature = 0.0  // Determinism matters here.
            }
            skills {
                skill<String, String>("recite", "Returns π to the requested precision") { tools() }
            }
        }

        val session = piAgent.session("Give me π to 20 decimal places.")
        val events = session.events.toList()
        val output = session.await()

        // ── Event-flow shape (step 2 contract) ─────────────────────────────
        // SkillStarted at index 0, SkillCompleted somewhere before the terminal,
        // and Completed as the last event. We don't pin exact size because step 3
        // will add Token / ToolCall* events; this test should stay green through
        // that rewire.
        assertTrue(events.isNotEmpty(), "session must emit at least one event")
        val started = events.first()
        assertIs<AgentEvent.SkillStarted>(started, "first event must be SkillStarted; got: $started")
        assertEquals("pi-reciter", started.agentId)
        assertEquals("recite", started.skillName)

        val terminal = events.last()
        assertIs<AgentEvent.Completed<String>>(terminal, "last event must be Completed<String>; got: $terminal")
        assertEquals("pi-reciter", terminal.agentId)
        assertEquals(output, terminal.output, "Completed.output must match session.await()")
        assertTrue(
            events.any { it is AgentEvent.SkillCompleted },
            "SkillCompleted must appear between SkillStarted and Completed; got: $events",
        )

        // ── Output content ─────────────────────────────────────────────────
        // Robust pass: 15 decimal digits. The full 20-digit sequence is the
        // ambitious target; we report on it but don't fail when a model is a
        // touch loose on the tail.
        val canonical20 = "3.14159265358979323846"
        val robust15    = "3.14159265358979"
        assertTrue(
            output.contains(robust15),
            "expected output to contain π's first 15 decimal digits ($robust15); got: \"$output\"",
        )
        val hitFull20 = output.contains(canonical20)
        println("AgentSessionLiveTest: π model=$ollamaModel; full20=$hitFull20; output=\"$output\"")
    }

    private fun isOllamaReachable(): Boolean = try {
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build()
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:11434/api/tags"))
            .timeout(Duration.ofMillis(1500))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.discarding())
        response.statusCode() in 200..299
    } catch (_: Throwable) {
        false
    }
}
