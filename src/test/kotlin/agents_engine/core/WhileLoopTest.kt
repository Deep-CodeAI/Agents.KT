package agents_engine.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for plain Kotlin while(){} loops with agents and pipelines.
 *
 * Agents and pipelines are invokable functions — they can be called
 * repeatedly in any Kotlin control flow without restrictions.
 */
class WhileLoopTest {

    @Test
    fun `agent can be called repeatedly in a while loop`() {
        val increment = agent<Int, Int>("inc") { execute { it + 1 } }

        var result = 0
        while (result < 5) {
            result = increment(result)
        }

        assertEquals(5, result)
    }

    @Test
    fun `pipeline can be called repeatedly in a while loop`() {
        val addOne  = agent<Int, Int>("addOne")  { execute { it + 1 } }
        val doubled = agent<Int, Int>("doubled") { execute { it * 2 } }
        val pipeline = addOne then doubled

        var result = 1
        while (result < 100) {
            result = pipeline(result)
        }

        // 1 → (1+1)*2=4 → (4+1)*2=10 → (10+1)*2=22 → (22+1)*2=46 → (46+1)*2=94 → (94+1)*2=190
        assertEquals(190, result)
    }

    @Test
    fun `while loop with external condition agent`() {
        val process   = agent<Int, Int>("process")   { execute { it + 3 } }
        val isDone    = agent<Int, Boolean>("isDone") { execute { it >= 15 } }

        var result = 0
        while (!isDone(result)) {
            result = process(result)
        }

        // 0 → 3 → 6 → 9 → 12 → 15 (stop)
        assertEquals(15, result)
    }

    @Test
    fun `while loop with accumulator over pipeline`() {
        data class State(val value: Int, val log: List<Int>)

        val step = agent<State, State>("step") {
            execute { s -> State(s.value - 1, s.log + s.value) }
        }

        var state = State(5, emptyList())
        while (state.value > 0) {
            state = step(state)
        }

        assertEquals(listOf(5, 4, 3, 2, 1), state.log)
    }

    @Test
    fun `do-while equivalent — pipeline runs at least once`() {
        val process = agent<Int, Int>("process") { execute { it + 10 } }

        var result: Int
        var first = true
        result = 0
        do {
            result = process(result)
            first = false
        } while (result < 5 || first)

        // runs once: 0 → 10, condition false, stops
        assertEquals(10, result)
    }

    @Test
    fun `while loop with multi-stage pipeline body and complex exit condition`() {
        data class Document(val text: String, val revisions: Int)

        val trim    = agent<Document, Document>("trim")    { execute { Document(it.text.trim(), it.revisions) } }
        val revise  = agent<Document, Document>("revise")  { execute { Document(it.text + ".", it.revisions + 1) } }
        val pipeline = trim then revise

        var doc = Document("  hello  ", 0)
        while (doc.revisions < 3) {
            doc = pipeline(doc)
        }

        assertEquals(3, doc.revisions)
        assertEquals("hello...", doc.text)
    }
}
