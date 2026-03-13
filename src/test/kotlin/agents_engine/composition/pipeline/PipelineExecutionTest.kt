package agents_engine.composition.pipeline

import agents_engine.core.*
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class PipelineExecutionTest {

    data class Input(val v: String)
    data class Middle(val v: String)
    data class Output(val v: String)

    @Test
    fun pipelineOfTwoCodeAgentsExecutes() {
        val upper = agent<Input, Middle>("upper") {
            skills { skill<Input, Middle>("upper") { implementedBy { Middle(it.v.uppercase()) } } }
        }
        val exclaim = agent<Middle, Output>("exclaim") {
            skills { skill<Middle, Output>("exclaim") { implementedBy { Output("${it.v}!") } } }
        }

        val pipeline = upper then exclaim
        assertEquals(Output("HELLO!"), pipeline(Input("hello")))
    }

    @Test
    fun pipelineExecutesAgentsInOrder() {
        val log = mutableListOf<String>()

        val first = agent<Input, Middle>("first") {
            skills { skill<Input, Middle>("first") { implementedBy { log.add("first"); Middle(it.v) } } }
        }
        val second = agent<Middle, Output>("second") {
            skills { skill<Middle, Output>("second") { implementedBy { log.add("second"); Output(it.v) } } }
        }

        (first then second)(Input("x"))

        assertEquals(listOf("first", "second"), log)
    }

    @Test
    fun longPipelineExecutes() {
        data class A(val v: Int)
        data class B(val v: Int)
        data class C(val v: Int)
        data class D(val v: Int)

        val a = agent<A, B>("a") { skills { skill<A, B>("a") { implementedBy { B(it.v + 1) } } } }
        val b = agent<B, C>("b") { skills { skill<B, C>("b") { implementedBy { C(it.v * 2) } } } }
        val c = agent<C, D>("c") { skills { skill<C, D>("c") { implementedBy { D(it.v - 3) } } } }

        val pipeline = a then b then c
        assertEquals(D((1 + 1) * 2 - 3), pipeline(A(1)))
    }
}
