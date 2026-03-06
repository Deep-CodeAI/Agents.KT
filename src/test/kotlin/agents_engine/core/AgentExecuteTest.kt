package agents_engine.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class AgentExecuteTest {

    data class Input(val v: String)
    data class Output(val v: String)

    @Test
    fun agentWithExecuteBlockCanBeCreated() {
        agent<Input, Output>("a") {
            execute { input -> Output(input.v.uppercase()) }
        }
    }

    @Test
    fun agentWithExecuteBlockIsInvokable() {
        val a = agent<Input, Output>("a") {
            execute { input -> Output(input.v.uppercase()) }
        }
        assertEquals(Output("HELLO"), a(Input("hello")))
    }

    @Test
    fun agentWithExecuteAndSkillsThrows() {
        assertThrows<IllegalArgumentException> {
            agent<Input, Output>("a") {
                execute { input -> Output(input.v) }
                skills {
                    skill<Input, Output>("s") {}
                }
            }
        }
    }

    @Test
    fun agentWithNoExecuteAndNoSkillsThrowsOnInvoke() {
        val a = agent<Input, Output>("a") {}
        assertThrows<IllegalStateException> {
            a(Input("hello"))
        }
    }
}
