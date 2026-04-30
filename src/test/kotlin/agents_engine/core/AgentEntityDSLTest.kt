package agents_engine.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows


class AgentEntityDSLTest {
    data class SomeAgentAsk(val v: String)
    data class SomeAgentResult(val v: String, val k: Long)
    data class SomeIntermediate(val x: Int)

    @Test
    fun agentsWork() {
        val someAgent = agent<SomeAgentAsk, SomeAgentResult>("SomeAgentAsk-to-SomeAgentResult") {
            skills {
                skill<SomeAgentAsk, SomeAgentResult>("convert") {
                    implementedBy { SomeAgentResult(it.v, 1L) }
                }
            }
        }
    }

    @Test
    fun emptyAgentFailsFast() {
        assertThrows<IllegalArgumentException> {
            agent<SomeAgentAsk, SomeAgentResult>("invalid") {
            }
        }
    }

}
