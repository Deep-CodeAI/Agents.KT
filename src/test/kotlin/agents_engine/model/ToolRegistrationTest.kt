package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ToolRegistrationTest {

    @Test
    fun `duplicate tool names in same tools block throw`() {
        assertFailsWith<IllegalArgumentException> {
            agent<String, String>("duplicate-tools") {
                tools {
                    tool("fetch", "Fetch") { _ -> "ok" }
                    tool("fetch", "Fetch again") { _ -> "still ok" }
                }
                skills { skill<String, String>("s") { implementedBy { it } } }
            }
        }
    }

    @Test
    fun `duplicate tool names across tools blocks throw`() {
        assertFailsWith<IllegalArgumentException> {
            agent<String, String>("duplicate-tools-across-blocks") {
                tools {
                    tool("fetch", "Fetch") { _ -> "ok" }
                }
                tools {
                    tool("fetch", "Fetch again") { _ -> "still ok" }
                }
                skills { skill<String, String>("s") { implementedBy { it } } }
            }
        }
    }
}
