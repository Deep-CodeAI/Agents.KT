package agents_engine.runtime.events

import agents_engine.core.agent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue

// #1738 — step-2 closeout: prove that cancelling a session's events
// collector terminates the collect promptly, even when the underlying
// `implementedBy` skill is mid-execution.
//
// **Step-2 honesty note.** Coroutine cancellation can only fire at
// suspension points. An `implementedBy` lambda is `(IN) -> OUT` — pure
// synchronous code with no suspension points — so the *invocation*
// itself may run to completion in the background after a cancel. What
// we CAN verify in step 2:
//   - The collector job's `cancelAndJoin()` returns promptly (well under
//     the skill's synthetic sleep duration).
//   - Subsequent `collect`s on the cancelled session don't deliver
//     additional events to the cancelled job.
//
// What step 3 will add (and what this test will be extended to cover):
// once `executeAgentic` is rewired onto a `FlowCollector<AgentEvent>`,
// the HTTP call inside the loop becomes a `chatStream(...)` suspend
// function — cancellation propagates through that suspend boundary and
// the actual invocation stops. Today, only the *Flow surface* respects
// cancellation; the agentic loop's body doesn't yet.

class AgentSessionCancellationTest {

    @Test
    fun `cancelling the events collect terminates promptly even while a slow skill is mid-execution`() = runBlocking {
        val skillEntered = CompletableDeferred<Unit>()

        // 2-second synthetic delay — large enough that "cancel returns
        // before the skill finishes" is unambiguously measurable.
        val slowAgent = agent<String, String>("slow") {
            skills {
                skill<String, String>("work", "Synthetic slow work to exercise cancellation") {
                    implementedBy {
                        skillEntered.complete(Unit)
                        Thread.sleep(2000)
                        "done"
                    }
                }
            }
        }

        val session = slowAgent.session("input")
        val collectJob = launch {
            session.events.collect { /* receive but don't act */ }
        }

        // Wait for the skill to enter — this proves the invocation
        // actually started before we cancel.
        withTimeout(1000) { skillEntered.await() }

        val cancelStartNs = System.nanoTime()
        collectJob.cancelAndJoin()
        val cancelMs = (System.nanoTime() - cancelStartNs) / 1_000_000

        // The skill's Thread.sleep continues to run in the background
        // (step-2 gap, documented above). What we assert: the cancel
        // returned promptly — under half a second is generous slack
        // for CI noise; the skill's sleep is 2 seconds. If cancel were
        // waiting on the skill, this would never hold.
        assertTrue(
            cancelMs < 500,
            "cancel should return well under the skill's 2-second sleep; took ${cancelMs}ms",
        )
    }
}
