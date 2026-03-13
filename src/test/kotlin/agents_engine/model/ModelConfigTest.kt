package agents_engine.model

import agents_engine.core.agent
import agents_engine.core.skill
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
}
