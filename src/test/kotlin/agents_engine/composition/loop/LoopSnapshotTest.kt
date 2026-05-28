package agents_engine.composition.loop

import agents_engine.core.InMemoryCompositionSnapshotStore
import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #2420 — composition snapshots for Loop. Loop is the highest-value
 * operator for snapshotting: long-running iterations are exactly the
 * case where losing all progress on a crash hurts the most.
 */
class LoopSnapshotTest {

    @Test
    fun `Loop resumeOrStart skips completed iterations on resume`() {
        val sessionId = "loop1"
        val store = InMemoryCompositionSnapshotStore()

        // Counter increments each time the body executes (regardless of which
        // attempt). It must equal "useful iterations on attempt 1 + iterations
        // on attempt 2", NOT "5 + 5" — that would mean the resume restarted.
        val bodyCalls = intArrayOf(0)
        var crashed = false

        val body = agent<String, String>("Incrementer") {
            skills {
                skill<String, String>("inc", "increment") {
                    implementedBy { input ->
                        bodyCalls[0]++
                        // Crash exactly once, when entering iteration 3 (input "2").
                        if (input == "2" && !crashed) {
                            crashed = true
                            error("simulated crash on iteration 3")
                        }
                        (input.toInt() + 1).toString()
                    }
                }
            }
        }

        // Terminate when we reach "5" (so the loop runs 5 iterations total).
        val loop = body.loop { current ->
            if (current == "5") null else current
        }

        // Attempt 1: iterations 1 and 2 complete (body runs for "0" and "1");
        // iteration 3 crashes mid-body (input "2"). The composition snapshot
        // must reflect "two iterations completed, current value is '2'".
        assertFailsWith<IllegalStateException> {
            runBlocking { loop.resumeOrStart(sessionId, "0", store) }
        }
        assertEquals(3, bodyCalls[0], "body ran 3 times before crashing (2 completed + 1 crashed)")
        val saved = store.load(sessionId)
            ?: error("loop must persist a snapshot after iteration 2 completed")
        assertEquals(2, saved.stageIndex, "two iterations completed before the crash")
        assertEquals("2", saved.intermediate, "the value entering iteration 3 was '2'")

        // Attempt 2: resume picks up iteration 3 from input "2". Body runs
        // 3 more times (for "2", "3", "4"); next("5") returns null; total 6.
        val out = runBlocking { loop.resumeOrStart(sessionId, "0", store) }
        assertEquals("5", out)
        assertEquals(
            6, bodyCalls[0],
            "across both attempts the body should run 6 times (3 + 3), " +
                "NOT 8 — that would mean the resume restarted from '0'",
        )
    }

    @Test
    fun `Loop resumeOrStart with no prior snapshot runs the full loop`() {
        val sessionId = "fresh"
        val store = InMemoryCompositionSnapshotStore()
        val body = agent<String, String>("Inc") {
            skills {
                skill<String, String>("inc", "inc") {
                    implementedBy { input -> (input.toInt() + 1).toString() }
                }
            }
        }
        val loop = body.loop { c -> if (c == "3") null else c }
        val out = runBlocking { loop.resumeOrStart(sessionId, "0", store) }
        assertEquals("3", out)
        val saved = store.load(sessionId)
            ?: error("snapshot must be persisted after the last iteration")
        assertEquals(3, saved.stageIndex, "loop ran 3 iterations end-to-end")
        assertEquals("3", saved.intermediate)
    }
}
