package agents_engine.composition.pipeline

import agents_engine.core.InMemoryCompositionSnapshotStore
import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #2420 — composition snapshots for Pipeline. TDD: this is the first failing
 * test that drives the public surface. The canonical case is "a then b
 * crashes mid-B; resume must not re-run A."
 */
class PipelineSnapshotTest {

    @Test
    fun `Pipeline resumeOrStart skips a completed stage A on resume`() {
        val sessionId = "p1"
        val store = InMemoryCompositionSnapshotStore()

        // Counters survive both attempts via shared closures. Stage B fails on
        // its first call ever; the second time the pipeline is invoked it
        // succeeds. If composition snapshots work, A runs once total.
        val aRuns = intArrayOf(0)
        val bRuns = intArrayOf(0)

        val agentA = agent<String, String>("StageA") {
            skills {
                skill<String, String>("a", "stage a") {
                    implementedBy { input -> aRuns[0]++; "from-A($input)" }
                }
            }
        }
        val agentB = agent<String, String>("StageB") {
            skills {
                skill<String, String>("b", "stage b") {
                    implementedBy { input ->
                        bRuns[0]++
                        if (bRuns[0] == 1) error("simulated crash inside stage B")
                        "done($input)"
                    }
                }
            }
        }

        val pipeline = agentA then agentB

        // Run 1: A succeeds, B throws. The composition snapshot must have
        // recorded A's output so the resume doesn't lose it.
        assertFailsWith<IllegalStateException> {
            runBlocking { pipeline.resumeOrStart(sessionId, "go", store) }
        }
        assertEquals(1, aRuns[0], "A must run exactly once on the first attempt")
        assertEquals(1, bRuns[0], "B must have been entered (then crashed)")

        // Run 2: A is skipped (its output was persisted); B succeeds.
        val out = runBlocking { pipeline.resumeOrStart(sessionId, "go", store) }
        assertEquals("done(from-A(go))", out, "B must run on A's persisted output")
        assertEquals(1, aRuns[0], "A must NOT run a second time — that's the whole point")
        assertEquals(2, bRuns[0], "B re-ran (it didn't checkpoint after itself)")
    }
}
