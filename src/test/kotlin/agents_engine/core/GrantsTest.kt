package agents_engine.core

import agents_engine.model.Tool
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #4545 (PRD §9.2) — capability grants. TDD: build-time validation (skills ⊆ grants, disjoint
// allow/confirm, real tools) + the runtime confirm() gate (granting-agent authorization, fail-closed).

class GrantsTest {

    private fun denied(d: Decision<*>) = d is Decision.Deny

    @Test
    fun `allow grants a freely callable tool and confirm without a confirmer is denied (fail-closed)`() {
        lateinit var write: Tool<Map<String, Any?>, Any?>
        lateinit var deploy: Tool<Map<String, Any?>, Any?>
        val a = agent<String, String>("ops") {
            model { ollama("m") }
            tools {
                write = tool("write", "writes") { "ok" }
                deploy = tool("deploy", "deploys") { "ok" }
            }
            grants { allow(write); confirm(deploy) }
            skills { skill<String, String>("s", "does ops") { tools(write, deploy) } }
        }

        assertFalse(denied(a.decideBeforeToolCall("write", emptyMap())), "allow() tool must not be gated")
        assertTrue(denied(a.decideBeforeToolCall("deploy", emptyMap())), "confirm() with no confirmer → denied")
    }

    @Test
    fun `confirm tool is allowed when the granting agent authorizes and denied when it refuses`() {
        fun build(authorize: Boolean): Agent<String, String> {
            lateinit var deploy: Tool<Map<String, Any?>, Any?>
            return agent("ops") {
                model { ollama("m") }
                tools { deploy = tool("deploy", "deploys") { "ok" } }
                grants {
                    confirm(deploy)
                    confirmWith { _, _ -> authorize }
                }
                skills { skill<String, String>("s", "ops") { tools(deploy) } }
            }
        }

        assertFalse(denied(build(true).decideBeforeToolCall("deploy", emptyMap())), "authorized → allowed")
        assertTrue(denied(build(false).decideBeforeToolCall("deploy", emptyMap())), "refused → denied")
    }

    @Test
    fun `build fails when a skill uses a tool not covered by grants`() {
        val ex = assertFailsWith<IllegalArgumentException> {
            lateinit var write: Tool<Map<String, Any?>, Any?>
            lateinit var deploy: Tool<Map<String, Any?>, Any?>
            agent<String, String>("ops") {
                model { ollama("m") }
                tools {
                    write = tool("write", "writes") { "ok" }
                    deploy = tool("deploy", "deploys") { "ok" }
                }
                grants { allow(write) } // deploy used but not granted
                skills { skill<String, String>("s", "ops") { tools(write, deploy) } }
            }
        }
        assertTrue("not covered by grants" in ex.message!! && "deploy" in ex.message!!, ex.message!!)
    }

    @Test
    fun `build fails when a granted name is not a registered tool`() {
        // A grant referencing a tool the agent never registered is a construction error.
        val ex = assertFailsWith<IllegalArgumentException> {
            lateinit var write: Tool<Map<String, Any?>, Any?>
            lateinit var orphan: Tool<Map<String, Any?>, Any?>
            agent<String, String>("ops") {
                model { ollama("m") }
                tools {
                    write = tool("write", "writes") { "ok" }
                    orphan = tool("orphan", "not registered on this agent") { "ok" }
                }
                // unregister 'orphan' so the grant references a now-unknown tool
                unregisterTool("orphan")
                grants { allow(write, orphan) }
                skills { skill<String, String>("s", "ops") { tools(write) } }
            }
        }
        assertTrue("unknown tools" in ex.message!! && "orphan" in ex.message!!, ex.message!!)
    }

    @Test
    fun `a tool cannot be both allow and confirm`() {
        lateinit var t: Tool<Map<String, Any?>, Any?>
        val ex = assertFailsWith<IllegalArgumentException> {
            agent<String, String>("ops") {
                model { ollama("m") }
                tools { t = tool("t", "x") { "ok" } }
                grants { allow(t); confirm(t) }
                skills { skill<String, String>("s", "ops") { tools(t) } }
            }
        }
        assertTrue("cannot be both allow(...) and confirm(...)" in ex.message!!, ex.message!!)
    }

    @Test
    fun `no grants block leaves tool calls ungated (backward compatible)`() {
        lateinit var deploy: Tool<Map<String, Any?>, Any?>
        val a = agent<String, String>("ops") {
            model { ollama("m") }
            tools { deploy = tool("deploy", "deploys") { "ok" } }
            skills { skill<String, String>("s", "ops") { tools(deploy) } }
        }
        assertFalse(denied(a.decideBeforeToolCall("deploy", emptyMap())), "no grants → no grant gate")
    }
}
