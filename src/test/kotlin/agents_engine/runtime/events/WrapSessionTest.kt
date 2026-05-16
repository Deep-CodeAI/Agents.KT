package agents_engine.runtime.events

import agents_engine.composition.pipeline.session
import agents_engine.composition.wrap.wrap
import agents_engine.core.agent
import agents_engine.model.LlmMessage
import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// #1747 — wrap (teacher wrap student) returns a Pipeline that runs
// teacher first to produce a system-prompt String, then runs student
// under that override. Both agents' events stream through to the
// session's events Flow with their own agentIds.

class WrapSessionTest {

    @Test
    fun `wrap session emits ordered events from teacher then student with student-prompt-override active`() = runTest {
        // Teacher emits a system-prompt string for the student.
        // Student is agentic — uses a stub ModelClient so the prompt-override
        // path through executeAgentic is exercised under streaming.
        val capturedSystemPrompt = mutableListOf<String>()
        val stub = ModelClient { messages: List<LlmMessage> ->
            messages.firstOrNull { it.role == "system" }?.content?.let { capturedSystemPrompt += it }
            LlmResponse.Text("student-output")
        }

        val teacher = agent<String, String>("teacher") {
            skills {
                skill<String, String>("write-prompt", "Emits a system prompt for the student") {
                    implementedBy { input -> "Teacher emitted prompt for: $input" }
                }
            }
        }
        val student = agent<String, String>("student") {
            prompt("Baked-in student prompt that should NOT be used during wrap.")
            model { ollama("llama3"); client = stub }
            skills {
                skill<String, String>("do-task", "Does the actual task via LLM") { tools() }
            }
        }
        val pipeline = teacher wrap student

        val session = pipeline.session("hello")
        val events = session.events.toList()
        val output = session.await()

        assertEquals("student-output", output, "wrap pipeline output is student's output")

        // Teacher's events come first.
        val firstStarted = events.filterIsInstance<AgentEvent.SkillStarted>().first()
        assertEquals("teacher", firstStarted.agentId)
        assertEquals("write-prompt", firstStarted.skillName)

        // Student's events follow.
        val studentStarted = events.filterIsInstance<AgentEvent.SkillStarted>()
            .firstOrNull { it.agentId == "student" }
            ?: error("expected SkillStarted(student); got: $events")
        assertEquals("do-task", studentStarted.skillName)

        // Order: teacher.SkillStarted < teacher.SkillCompleted < student.SkillStarted
        val teacherStarted = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId == "teacher" }
        val teacherCompleted = events.indexOfFirst { it is AgentEvent.SkillCompleted && it.agentId == "teacher" }
        val studentStartedIdx = events.indexOfFirst { it is AgentEvent.SkillStarted && it.agentId == "student" }
        assertTrue(teacherStarted < teacherCompleted, "teacher.SkillStarted < teacher.SkillCompleted")
        assertTrue(teacherCompleted < studentStartedIdx, "teacher.SkillCompleted < student.SkillStarted")

        // Terminal Completed uses the student's name.
        val terminal = events.last()
        assertIs<AgentEvent.Completed<String>>(terminal)
        assertEquals("student", terminal.agentId, "Completed.agentId = student's name (last agent in chain)")
        assertEquals("student-output", terminal.output)

        // Prompt override was active: the system prompt the student's LLM saw must contain
        // the teacher's emitted text, NOT the student's baked-in prompt.
        assertTrue(capturedSystemPrompt.isNotEmpty(), "student's stub ModelClient must have received a system message")
        val systemPrompt = capturedSystemPrompt.single()
        assertTrue(
            "Teacher emitted prompt for: hello" in systemPrompt,
            "student's system prompt must carry the teacher's output verbatim; got: \"$systemPrompt\"",
        )
        assertTrue(
            "Baked-in student prompt" !in systemPrompt,
            "student's baked-in prompt must NOT appear when wrap is active; got: \"$systemPrompt\"",
        )
    }
}
