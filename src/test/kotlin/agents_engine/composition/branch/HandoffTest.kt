package agents_engine.composition.branch

import agents_engine.core.PipelineEvent
import agents_engine.core.agent
import agents_engine.core.observe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #3871 — handoff: branch semantics + an audit contract. The target gets
// only its declared input type (never the source's history), and route
// selection fires onHandoff / PipelineEvent.HandoffPerformed.

class HandoffTest {

    sealed interface Task {
        data class Billing(val amount: Int) : Task
        data class Tech(val component: String) : Task
    }

    private fun triage() = agent<String, Task>("triage") {
        skills {
            skill<String, Task>("classify", "Classifies the request") {
                implementedBy { input ->
                    if ("charge" in input) Task.Billing(42) else Task.Tech(input)
                }
            }
        }
    }

    private fun billing() = agent<Task.Billing, String>("billing") {
        skills {
            skill<Task.Billing, String>("resolve", "Resolves billing") {
                implementedBy { "refunded ${it.amount}" }
            }
        }
    }

    private fun tech() = agent<Task.Tech, String>("tech") {
        skills {
            skill<Task.Tech, String>("resolve", "Resolves tech") {
                implementedBy { "restarted ${it.component}" }
            }
        }
    }

    @Test
    fun `handoff routes on the typed output and fires the audit listener`() {
        val source = triage()
        val handoffs = mutableListOf<Pair<String, String>>()
        source.onHandoff { toAgent, inputType -> handoffs.add(toAgent to inputType) }

        val flow = source handoff {
            on<Task.Billing>() then billing()
            on<Task.Tech>() then tech()
        }

        assertEquals("refunded 42", flow("double charge on my card"))
        assertEquals(listOf("billing" to "Billing"), handoffs, "audit listener carries target + decision type")
    }

    @Test
    fun `handoff surfaces as HandoffPerformed via observe`() {
        val source = triage()
        val events = mutableListOf<PipelineEvent>()
        source.observe { events.add(it) }

        val flow = source handoff {
            on<Task.Billing>() then billing()
            on<Task.Tech>() then tech()
        }
        flow("the search component is down")

        val handoff = events.filterIsInstance<PipelineEvent.HandoffPerformed>().single()
        assertEquals("tech", handoff.toAgent)
        assertEquals("Tech", handoff.decisionInputType)
        assertEquals("triage", handoff.agentName)
    }

    @Test
    fun `plain branch fires no handoff events`() {
        val source = triage()
        val events = mutableListOf<PipelineEvent>()
        source.observe { events.add(it) }

        val routed = source.branch<String, Task, String> {
            on<Task.Billing>() then billing()
            on<Task.Tech>() then tech()
        }
        routed("double charge again")

        assertTrue(
            events.filterIsInstance<PipelineEvent.HandoffPerformed>().isEmpty(),
            "branch must stay silent on the handoff channel; got: $events",
        )
    }

    @Test
    fun `sealed exhaustiveness validation applies to handoff like branch`() {
        val source = triage()
        assertFailsWith<IllegalArgumentException> {
            source handoff {
                on<Task.Billing>() then billing()
                // Task.Tech missing, no onElse — construction must fail.
            }
        }
    }
}
