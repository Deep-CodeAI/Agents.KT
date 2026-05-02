package agents_engine.composition.forum

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for #639 — captain can opt into receiving a `ForumTranscript<IN>`
 * containing the original input PLUS each participant's output. This
 * is the "deliberation" pattern. The legacy `captain(agent)` (captain on
 * original input) keeps working untouched for back-compat.
 */
class ForumTranscriptTest {

    @Test
    fun `captain receives transcript with original input and participant outputs`() {
        val voter1 = agent<String, String>("voter1") {
            skills { skill<String, String>("vote", "v") { implementedBy { "vote: yes" } } }
        }
        val voter2 = agent<String, String>("voter2") {
            skills { skill<String, String>("vote", "v") { implementedBy { "vote: no" } } }
        }
        val voter3 = agent<String, String>("voter3") {
            skills { skill<String, String>("vote", "v") { implementedBy { "vote: yes" } } }
        }

        var receivedTranscript: ForumTranscript<String>? = null
        val captain = agent<ForumTranscript<String>, String>("captain") {
            skills {
                skill<ForumTranscript<String>, String>("decide", "d") {
                    implementedBy { transcript ->
                        receivedTranscript = transcript
                        val yesVotes = transcript.contributions.count { (it.output as String).contains("yes") }
                        val noVotes = transcript.contributions.size - yesVotes
                        if (yesVotes > noVotes) "majority yes" else "majority no"
                    }
                }
            }
        }

        val f = forum<String, String> {
            participant(voter1); participant(voter2); participant(voter3)
            transcriptCaptain(captain)
        }

        val verdict = f("Should we ship?")

        assertEquals("majority yes", verdict)
        assertEquals("Should we ship?", receivedTranscript!!.originalInput)
        assertEquals(3, receivedTranscript!!.contributions.size)
        assertEquals(setOf("voter1", "voter2", "voter3"), receivedTranscript!!.contributions.map { it.agentName }.toSet())
    }

    @Test
    fun `transcript preserves participant agent names`() {
        val a = agent<String, String>("alpha") { skills { skill<String, String>("s", "") { implementedBy { "out-a" } } } }
        val b = agent<String, String>("beta")  { skills { skill<String, String>("s", "") { implementedBy { "out-b" } } } }

        var contributions: List<ParticipantContribution>? = null
        val captain = agent<ForumTranscript<String>, String>("c") {
            skills {
                skill<ForumTranscript<String>, String>("d", "") {
                    implementedBy { contributions = it.contributions; "ok" }
                }
            }
        }

        forum<String, String> { participant(a); participant(b); transcriptCaptain(captain) }("input")

        assertEquals(listOf("alpha", "beta"), contributions!!.map { it.agentName })
        assertEquals(listOf("out-a", "out-b"), contributions!!.map { it.output })
    }

    @Test
    fun `onMentionEmitted still fires per participant in transcript mode (regression)`() {
        val mentions = mutableListOf<Pair<String, Any?>>()
        val a = agent<String, String>("a") { skills { skill<String, String>("s", "") { implementedBy { "x" } } } }
        val b = agent<String, String>("b") { skills { skill<String, String>("s", "") { implementedBy { "y" } } } }
        val captain = agent<ForumTranscript<String>, String>("c") {
            skills { skill<ForumTranscript<String>, String>("d", "") { implementedBy { "ok" } } }
        }

        val f = forum<String, String> { participant(a); participant(b); transcriptCaptain(captain) }
        f.onMentionEmitted { name, output -> mentions.add(name to output) }
        f("input")

        // Participants AND captain should each fire mentionListener
        assertTrue(mentions.any { it.first == "a" && it.second == "x" })
        assertTrue(mentions.any { it.first == "b" && it.second == "y" })
        assertTrue(mentions.any { it.first == "c" && it.second == "ok" })
    }

    @Test
    fun `legacy captain(agent) still receives original input (regression)`() {
        // Verifies the existing API path is unchanged: captain typed as Agent<IN, OUT>
        // gets the original input, not a transcript. Forum's old behavior is preserved.
        var captainInput: String? = null
        val a = agent<String, String>("a") { skills { skill<String, String>("s", "") { implementedBy { "x" } } } }
        val captain = agent<String, String>("c") {
            skills { skill<String, String>("d", "") { implementedBy { captainInput = it; "ok" } } }
        }

        forum<String, String> { participant(a); captain(captain) }("hello")
        assertEquals("hello", captainInput)
    }
}
