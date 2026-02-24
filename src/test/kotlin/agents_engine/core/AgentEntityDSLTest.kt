package agents_engine.core

import org.junit.jupiter.api.Test


class AgentEntityDSLTest {
    data class SomeAgentAsk(val v: String)
    data class SomeAgentResult(val v: String, val k: Long)
    data class SomeIntermediate(val x: Int)

    @Test
    fun agentsWork() {
        val someAgent = agent<SomeAgentAsk, SomeAgentResult>("SomeAgentAsk-to-SomeAgentResult") {
        }
    }

}
