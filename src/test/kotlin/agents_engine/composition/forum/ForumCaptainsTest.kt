package agents_engine.composition.forum

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

// #3877 — named built-in captains: consensus quorum, weighted vote,
// byzantine median, each wired through the real forum deliberation.

class ForumCaptainsTest {

    private fun voter(name: String, vote: String) = agent<String, String>(name) {
        skills { skill<String, String>("vote", "Votes") { implementedBy { vote } } }
    }

    private fun scorer(name: String, value: Double) = agent<String, Double>(name) {
        skills { skill<String, Double>("score", "Scores") { implementedBy { value } } }
    }

    @Test
    fun `consensusCaptain returns the verdict reaching quorum`() {
        val panel = forum<String, String> {
            participant(voter("a", "approve"))
            participant(voter("b", "approve"))
            participant(voter("c", "reject"))
            transcriptCaptain(consensusCaptain(quorum = 2))
        }
        assertEquals("approve", panel("case-1"))
    }

    @Test
    fun `consensusCaptain fails loud with the tally when quorum is not reached`() {
        val panel = forum<String, String> {
            participant(voter("a", "approve"))
            participant(voter("b", "reject"))
            participant(voter("c", "escalate"))
            transcriptCaptain(consensusCaptain(quorum = 2))
        }
        val e = assertFailsWith<IllegalStateException> { panel("case-2") }
        assertTrue("Tally" in (e.message ?: ""), "failure must carry the tally; got: ${e.message}")
    }

    @Test
    fun `weightedCaptain lets a heavy panelist outvote two light ones`() {
        val panel = forum<String, String> {
            participant(voter("expert", "approve"))
            participant(voter("intern1", "reject"))
            participant(voter("intern2", "reject"))
            transcriptCaptain(weightedCaptain(mapOf("expert" to 3.0)))
        }
        assertEquals("approve", panel("case-3"))
    }

    @Test
    fun `byzantineCaptain ignores an adversarial outlier via the median`() {
        val panel = forum<String, Double> {
            participant(scorer("honest1", 0.72))
            participant(scorer("honest2", 0.70))
            participant(scorer("byzantine", 99999.0))
            transcriptCaptain(byzantineCaptain())
        }
        assertEquals(0.72, panel("case-4"), "median shrugs off the outlier")
    }

    @Test
    fun `strategy name is the captain's agent name for audit`() {
        val captain = consensusCaptain<String, String>(quorum = 1)
        assertEquals("consensus-captain", captain.name)
        assertEquals("weighted-captain", weightedCaptain<String, String>(emptyMap()).name)
        assertEquals("byzantine-captain", byzantineCaptain<String>().name)
    }
}
