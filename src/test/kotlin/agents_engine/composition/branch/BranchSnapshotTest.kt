package agents_engine.composition.branch

import agents_engine.core.InMemoryCompositionSnapshotStore
import agents_engine.core.agent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * #2420 — composition snapshots for Branch. The source agent runs once
 * to produce the routing key; the matched route's executor runs once
 * with that key. The composition snapshot captures the source's output
 * so a crash inside the route doesn't force the source to re-run on
 * resume.
 */
class BranchSnapshotTest {

    @Test
    fun `Branch resumeOrStart skips the source on resume`() {
        val sessionId = "br1"
        val store = InMemoryCompositionSnapshotStore()
        val sourceRuns = intArrayOf(0)
        val routeRuns = intArrayOf(0)
        var routeCrashed = false

        val source = agent<String, String>("Classifier") {
            skills {
                skill<String, String>("classify", "classify") {
                    implementedBy { input ->
                        sourceRuns[0]++
                        "tag-$input"
                    }
                }
            }
        }
        val routed = agent<String, String>("Worker") {
            skills {
                skill<String, String>("work", "work") {
                    implementedBy { input ->
                        routeRuns[0]++
                        if (!routeCrashed) {
                            routeCrashed = true
                            error("simulated crash inside route")
                        }
                        "handled($input)"
                    }
                }
            }
        }

        // onElse routes anything the source produces into `routed`.
        val branch = source.branch<String, String, String> {
            onElse then routed
        }

        // First attempt: source succeeds, route crashes.
        assertFailsWith<IllegalStateException> {
            runBlocking { branch.resumeOrStart(sessionId, "input", store) }
        }
        assertEquals(1, sourceRuns[0], "source must run exactly once")
        val saved = store.load(sessionId)
            ?: error("Branch must persist the source output before invoking the route")
        assertEquals(1, saved.stageIndex, "stageIndex 1 means source done, route pending")
        assertEquals("tag-input", saved.intermediate)

        // Second attempt: source is skipped; route runs with the persisted value.
        val out = runBlocking { branch.resumeOrStart(sessionId, "input", store) }
        assertEquals("handled(tag-input)", out)
        assertEquals(1, sourceRuns[0], "source must NOT run again — that's the point")
        assertEquals(2, routeRuns[0], "route re-ran (it crashed last time)")
    }
}
