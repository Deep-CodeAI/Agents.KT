package agents_engine.manifest

import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #2887 — exec widening is a verifier finding, like network/filesystem.

class ExecWideningTest {

    private fun manifestWith(policy: agents_engine.core.ToolPolicyBuilder.() -> Unit) =
        agent<String, String>("ops") {
            tools {
                tool("runner") {
                    policy(policy)
                    executor { "ok" }
                }
            }
            skills {
                skill<String, String>("run") {
                    @Suppress("DEPRECATION")
                    tools("runner")
                    implementedBy { it }
                }
            }
        }.permissionManifest()

    @Test fun `unspecified to allow is a widening`() {
        val baseline = manifestWith { filesystem { write("/out/**") } }
        val current = manifestWith { filesystem { write("/out/**") }; exec { allow() } }
        val result = current.verifyAgainst(baseline)
        assertFalse(result.ok, "exec unspecified -> allow must be flagged")
        assertTrue(result.findings.any { it.code == "tool.exec.widened" }, result.findings.toString())
    }

    @Test fun `allow to deny is a narrowing and passes`() {
        val baseline = manifestWith { exec { allow() } }
        val current = manifestWith { exec { deny() } }
        assertTrue(current.verifyAgainst(baseline).ok, "narrowing exec must pass")
    }
}
