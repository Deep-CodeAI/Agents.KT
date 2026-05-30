package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ModelConfigTest {

    @Test
    fun `ollama model name stored on agent`() {
        val a = agent<String, String>("a") {
            model { ollama("qwen3:14b") }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }
        assertNotNull(a.modelConfig)
        assertEquals("qwen3:14b", a.modelConfig!!.name)
        assertEquals(ModelProvider.OLLAMA, a.modelConfig!!.provider)
    }

    @Test
    fun `temperature defaults to 0_7`() {
        val a = agent<String, String>("a") {
            model { ollama("llama3") }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }
        assertEquals(0.7, a.modelConfig!!.temperature)
    }

    @Test
    fun `temperature can be overridden`() {
        val a = agent<String, String>("a") {
            model { ollama("llama3"); temperature = 0.1 }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }
        assertEquals(0.1, a.modelConfig!!.temperature)
    }

    @Test
    fun `host defaults to localhost and port to 11434`() {
        val a = agent<String, String>("a") {
            model { ollama("llama3") }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }
        assertEquals("localhost", a.modelConfig!!.host)
        assertEquals(11434, a.modelConfig!!.port)
        assertEquals("http://localhost:11434", a.modelConfig!!.baseUrl)
    }

    @Test
    fun `host and port can be overridden`() {
        val a = agent<String, String>("a") {
            model { ollama("llama3"); host = "myserver"; port = 9999 }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }
        assertEquals("myserver", a.modelConfig!!.host)
        assertEquals(9999, a.modelConfig!!.port)
        assertEquals("http://myserver:9999", a.modelConfig!!.baseUrl)
    }

    @Test
    fun `agent without model has null modelConfig`() {
        val a = agent<String, String>("a") {
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }
        assertNull(a.modelConfig)
    }

    // Security regression — ModelConfig.toString() must NEVER include the
    // raw apiKey value. The default Kotlin data-class toString does, which
    // makes any `log.info("config = $modelConfig")` or stack trace that
    // captures a config a credential leak.
    @Test
    fun `ModelConfig toString masks apiKey value`() {
        val cfg = ModelConfig(
            name = "claude-opus-4-7",
            provider = ModelProvider.ANTHROPIC,
            apiKey = "sk-ant-secret-DO-NOT-LEAK-XYZ",
        )
        val s = cfg.toString()
        assertFalse(
            s.contains("sk-ant-secret-DO-NOT-LEAK-XYZ"),
            "raw apiKey leaked into toString(): $s",
        )
        assertTrue(
            s.contains("apiKey="),
            "expected the apiKey field to still appear (masked) so callers can see it's set: $s",
        )
    }

    @Test
    fun `ModelConfig toString shows apiKey as null when unset`() {
        val cfg = ModelConfig(name = "x", provider = ModelProvider.OLLAMA)
        val s = cfg.toString()
        assertTrue(s.contains("apiKey=null"), "unset apiKey should render as null: $s")
    }

    // #2850 — timeout overrides flow from the DSL into ModelConfig. The default
    // is null (= adapter falls back to its DEFAULT_REQUEST_TIMEOUT of 300s).
    @Test
    fun `requestTimeout and connectTimeout default to null on the DSL`() {
        val a = agent<String, String>("a") {
            model { ollama("llama3") }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }
        assertNull(a.modelConfig!!.requestTimeout)
        assertNull(a.modelConfig!!.connectTimeout)
    }

    @Test
    fun `requestTimeout and connectTimeout flow from DSL to ModelConfig`() {
        val a = agent<String, String>("a") {
            model {
                ollama("llama3")
                requestTimeout = 10.minutes
                connectTimeout = 5.seconds
            }
            skills { skill<String, String>("s", "s") { implementedBy { it } } }
        }
        assertEquals(10.minutes, a.modelConfig!!.requestTimeout)
        assertEquals(5.seconds, a.modelConfig!!.connectTimeout)
    }

    @Test
    fun `built-in adapter DEFAULT_REQUEST_TIMEOUT is 5 minutes on every adapter`() {
        // Hotfix floor — 0.6.4 shipped a hardcoded 60s on Claude that killed
        // long Sonnet turns in production (#2850). Every built-in adapter now
        // ships with a 300s default so the out-of-the-box experience covers
        // long agentic turns; users tune via the DSL when needed.
        assertEquals(300.seconds, ClaudeClient.DEFAULT_REQUEST_TIMEOUT)
        assertEquals(300.seconds, OpenAiClient.DEFAULT_REQUEST_TIMEOUT)
        assertEquals(300.seconds, OllamaClient.DEFAULT_REQUEST_TIMEOUT)
        assertEquals(10.seconds, ClaudeClient.DEFAULT_CONNECT_TIMEOUT)
        assertEquals(10.seconds, OpenAiClient.DEFAULT_CONNECT_TIMEOUT)
        assertEquals(10.seconds, OllamaClient.DEFAULT_CONNECT_TIMEOUT)
    }
}
