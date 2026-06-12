package agents_engine.runtime.events

import agents_engine.composition.parallel.div
import agents_engine.composition.pipeline.session
import agents_engine.composition.pipeline.then
import agents_engine.core.agent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #4491 (PRD §10.2) — explicit stage boundaries on composite sessions:
// StageStarted/StageCompleted pair around each direct component, operator
// legs labeled by operator, nested pipelines never double-marked.

class StageEventsTest {

    private fun echo(name: String) = agent<String, String>(name) {
        skills { skill<String, String>("run", "Runs") { implementedBy { "$name:$it" } } }
    }

    private fun length(name: String) = agent<String, Int>(name) {
        skills { skill<String, Int>("len", "Length") { implementedBy { it.length } } }
    }

    @Test
    fun `stage markers pair around each component in chain order`() = runTest {
        val events = (echo("a") then echo("b") then echo("c")).session("x").events.toList()

        val markers = events.mapNotNull {
            when (it) {
                is AgentEvent.StageStarted -> "start:${it.stageName}"
                is AgentEvent.StageCompleted -> "end:${it.stageName}"
                else -> null
            }
        }
        assertEquals(
            listOf("start:a", "end:a", "start:b", "end:b", "start:c", "end:c"),
            markers,
            "markers must pair per stage in chain order; got: $events",
        )
    }

    @Test
    fun `operator legs are labeled by operator and agent events nest inside the stage`() = runTest {
        val pipeline = echo("head") then (length("l") / length("r"))
        val events = pipeline.session("x").events.toList()

        val markers = events.filterIsInstance<AgentEvent.StageStarted>().map { it.stageName }
        assertEquals(listOf("head", "parallel"), markers)

        // Branch agents' events fall between the parallel stage's markers.
        val parallelStart = events.indexOfFirst { it is AgentEvent.StageStarted && it.stageName == "parallel" }
        val parallelEnd = events.indexOfFirst { it is AgentEvent.StageCompleted && it.stageName == "parallel" }
        val branchSkillIdx = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId in setOf("l", "r") }
        assertTrue(
            branchSkillIdx in (parallelStart + 1) until parallelEnd,
            "fan-out streams inside its stage; got: $events",
        )
    }

    @Test
    fun `nested pipelines mark their own stages exactly once — no duplication`() = runTest {
        val left = echo("a") then echo("b")
        val right = echo("c") then echo("d")
        val events = (left then right).session("x").events.toList()

        val starts = events.filterIsInstance<AgentEvent.StageStarted>().map { it.stageName }
        assertEquals(listOf("a", "b", "c", "d"), starts, "each leaf marked once, never wrapped twice; got: $starts")
    }
}
