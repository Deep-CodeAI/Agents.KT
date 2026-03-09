package agents_engine.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

class SkillInvokeTest {

    data class Input(val v: String)
    data class Output(val v: String)

    @Test
    fun skillIsCallableViaInvokeOperator() {
        val s = skill<Input, Output>("upper") {
            implementedBy { Output(it.v.uppercase()) }
        }
        assertEquals(Output("HELLO"), s(Input("hello")))
    }

    @Test
    fun skillWithoutImplementationThrowsOnInvoke() {
        val s = skill<Input, Output>("empty") {}
        assertThrows<IllegalStateException> { s(Input("x")) }
    }

    @Test
    fun agentHasPrompt() {
        val a = agent<Input, Output>("a") {
            prompt("You transform text")
            skills {
                skill<Input, Output>("upper") { implementedBy { Output(it.v.uppercase()) } }
            }
        }
        assertEquals("You transform text", a.prompt)
    }

    @Test
    fun agentInvokesMatchingSkill() {
        val a = agent<Input, Output>("a") {
            prompt("You transform text")
            skills {
                skill<Input, Output>("upper") { implementedBy { Output(it.v.uppercase()) } }
            }
        }
        assertEquals(Output("HELLO"), a(Input("hello")))
    }

    @Test
    fun agentWithNoSkillsThrowsOnInvoke() {
        val a = agent<Input, Output>("a") {}
        assertThrows<IllegalStateException> { a(Input("x")) }
    }
}
