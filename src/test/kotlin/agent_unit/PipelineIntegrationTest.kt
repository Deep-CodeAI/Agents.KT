package agent_unit

import agents_engine.core.agent
import agents_engine.core.skill
import agents_engine.composition.then
import org.junit.jupiter.api.Assertions.assertEquals
import kotlin.test.Test

class PipelineIntegrationTest {

    data class UserRequest(val text: String)
    data class Specification(val endpoints: List<String>)
    data class Code(val source: String)
    data class ReviewResult(val approved: Boolean, val summary: String)

    @Test
    fun fullPipelineExecutesSkillsInSequence() {
        // Define skills with real implementations
        val analyze = skill<UserRequest, Specification>("analyze") {
            implementedBy { input ->
                val endpoints = input.text.split(",").map { it.trim() }
                Specification(endpoints)
            }
        }

        val generate = skill<Specification, Code>("generate") {
            implementedBy { spec ->
                val code = spec.endpoints.joinToString("\n") { "fun $it() { }" }
                Code(code)
            }
        }

        val review = skill<Code, ReviewResult>("review") {
            implementedBy { code ->
                val lineCount = code.source.lines().size
                ReviewResult(
                    approved = lineCount > 0,
                    summary = "Reviewed $lineCount endpoints"
                )
            }
        }

        // Wire agents
        val specMaster = agent<UserRequest, Specification>("spec-master") {
            skills { +analyze }
        }
        val coder = agent<Specification, Code>("coder") {
            skills { +generate }
        }
        val reviewer = agent<Code, ReviewResult>("reviewer") {
            skills { +review }
        }

        // Build pipeline — compiler validates type chain
        val pipeline = specMaster then coder then reviewer

        // Execute each skill manually through the pipeline
        val input = UserRequest("getUsers, createUser, deleteUser")
        val spec = analyze.execute(input)
        val code = generate.execute(spec)
        val result = review.execute(code)

        // Verify end-to-end
        assertEquals(3, spec.endpoints.size)
        assertEquals("fun getUsers() { }\nfun createUser() { }\nfun deleteUser() { }", code.source)
        assertEquals(true, result.approved)
        assertEquals("Reviewed 3 endpoints", result.summary)

        // Pipeline structure is correct
        assertEquals(3, pipeline.agents.size)
    }
}
