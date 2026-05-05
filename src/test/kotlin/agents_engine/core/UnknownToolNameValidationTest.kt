@file:Suppress("DEPRECATION")

package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tests for fail-fast validation of unknown tool names at agent construction (issue #631).
 *
 * The bug: `Skill.tools(...)` references that don't match any registered tool were
 * silently dropped via `mapNotNull` in AgenticLoop, leaving the developer with a
 * silently-broken agent. Now: agent construction throws if any skill references a
 * tool that doesn't exist on the agent.
 */
class UnknownToolNameValidationTest {

    @Test
    fun `single unknown tool name in skill throws at agent construction`() {
        try {
            agent<String, String>("typo-agent") {
                tools { tool("realTool", "real") { _ -> "ok" } }
                skills { skill<String, String>("s", "stub") { tools("missingTool") } }
            }
            fail("expected IllegalArgumentException for unknown tool name")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("missingTool"), "message must name the missing tool: ${e.message}")
            assertTrue(e.message!!.contains("s") || e.message!!.contains("typo-agent"),
                "message should mention skill or agent: ${e.message}")
        }
    }

    @Test
    fun `multiple tool names with one typo - error names only the typo`() {
        try {
            agent<String, String>("a") {
                tools {
                    tool("realToolA", "") { _ -> "a" }
                    tool("realToolB", "") { _ -> "b" }
                }
                skills { skill<String, String>("s", "stub") { tools("realToolA", "typo_tool", "realToolB") } }
            }
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("typo_tool"), "message must name typo_tool: ${e.message}")
            // Real tools should not be in the error list
            assertTrue(!e.message!!.contains("references unknown tools: [realToolA"),
                "real tool names must not be flagged as unknown: ${e.message}")
        }
    }

    @Test
    fun `all known tool names - construction succeeds`() {
        // Regression: must not throw when everything is wired correctly
        val a = agent<String, String>("ok") {
            tools {
                tool("foo", "") { _ -> "f" }
                tool("bar", "") { _ -> "b" }
            }
            skills { skill<String, String>("s", "stub") { tools("foo", "bar") } }
        }
        // No assertion needed — the lack of an exception is the success condition.
        // Sanity: the agent should still be usable for non-LLM construction.
        @Suppress("UNUSED_VARIABLE")
        val isReady = a.skills.isNotEmpty()
    }

    @Test
    fun `skill with no tools list - no validation noise`() {
        // tools() with no args produces empty toolNames; should not trip validation.
        val a = agent<String, String>("no-tools") {
            tools { tool("anyTool", "") { _ -> "x" } }
            skills { skill<String, String>("s", "stub") { tools() } }
        }
        @Suppress("UNUSED_VARIABLE")
        val isReady = a.skills.isNotEmpty()
    }

    @Test
    fun `skill with implementedBy - no validation against tool names`() {
        // Pure-Kotlin skills don't reference tools; validation must skip them entirely.
        val a = agent<String, String>("pure") {
            skills { skill<String, String>("s", "stub") { implementedBy { it } } }
        }
        @Suppress("UNUSED_VARIABLE")
        val isReady = a.skills.isNotEmpty()
    }
}
