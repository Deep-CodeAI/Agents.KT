package agents_engine.model

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolErrorAgentRepairTest {

    @Test
    fun `fix with agent calls the repair agent and returns its output`() {
        val fixer = agent<String, String>("json-fixer") {
            skills {
                skill<String, String>("cleanup", "Fixes JSON") {
                    implementedBy { input ->
                        // Deterministic agent: just fix trailing commas
                        input.replace(",}", "}").replace(",]", "]")
                    }
                }
            }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = fixer) }
        }.build()

        val result = handler.handleInvalidArgs("""{"name":"world",}""", "trailing comma")
        assertIs<RepairResult.Fixed>(result)
        assertEquals("""{"name":"world"}""", result.value)
    }

    @Test
    fun `fix with agent retries up to specified count`() {
        var attempts = 0
        val flakyFixer = agent<String, String>("flaky-fixer") {
            skills {
                skill<String, String>("fix", "Fixes JSON") {
                    implementedBy { input ->
                        attempts++
                        if (attempts < 3) error("Still broken")
                        input.replace(",}", "}")
                    }
                }
            }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = flakyFixer, retries = 3) }
        }.build()

        val result = handler.handleInvalidArgs("""{"a":1,}""", "trailing comma")
        assertIs<RepairResult.Fixed>(result)
        assertEquals(3, attempts)
    }

    @Test
    fun `fix with agent returns unrecoverable when all retries exhausted`() {
        val alwaysFails = agent<String, String>("broken-fixer") {
            skills {
                skill<String, String>("fix", "Fixes JSON") {
                    implementedBy { _ -> error("Cannot fix") }
                }
            }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = alwaysFails, retries = 2) }
        }.build()

        val result = handler.handleInvalidArgs("garbage", "parse error")
        assertIs<RepairResult.Unrecoverable>(result)
    }

    @Test
    fun `hybrid fix - deterministic first, fallback to agent`() {
        val agentFixer = agent<String, String>("json-fixer") {
            skills {
                skill<String, String>("fix", "Fixes JSON") {
                    implementedBy { _ -> """{"fixed":true}""" }
                }
            }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { raw, _ ->
                // Deterministic attempt returns null (can't fix)
                fix { null } ?: fix(agent = agentFixer)
            }
        }.build()

        val result = handler.handleInvalidArgs("total-garbage", "not JSON")
        assertIs<RepairResult.Fixed>(result)
        assertEquals("""{"fixed":true}""", result.value)
    }

    @Test
    fun `escalation produces EscalationError with context`() {
        val escalatingFixer = agent<String, String>("esc-fixer") {
            skills {
                skill<String, String>("fix", "Tries to fix") {
                    implementedBy { _ ->
                        throw EscalationException("Schema mismatch", Severity.HIGH)
                    }
                }
            }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = escalatingFixer, retries = 1) }
        }.build()

        val result = handler.handleInvalidArgs("bad", "error")
        assertIs<RepairResult.Escalated>(result)
        assertEquals("Schema mismatch", result.reason)
        assertEquals(Severity.HIGH, result.severity)
    }

    @Test
    fun `ToolExecutionException from throwException propagates`() {
        val hardFailFixer = agent<String, String>("hard-fail") {
            skills {
                skill<String, String>("fix", "Tries to fix") {
                    implementedBy { _ ->
                        throw ToolExecutionException("Fundamentally broken")
                    }
                }
            }
        }

        val handler = OnErrorBuilder().apply {
            invalidArgs { _, _ -> fix(agent = hardFailFixer, retries = 1) }
        }.build()

        var caught = false
        try {
            handler.handleInvalidArgs("bad", "error")
        } catch (e: ToolExecutionException) {
            caught = true
            assertEquals("Fundamentally broken", e.message)
        }
        assertTrue(caught, "ToolExecutionException should propagate")
    }
}
