package agents_engine.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// #2887 — the exec capability: DSL, manifest round-trip, declaresAnyCapability.

class ToolExecPolicyTest {

    @Test
    fun `exec allow round-trips through the manifest JSON`() {
        val policy = toolPolicy {
            filesystem { write("/out/**") }
            exec { allow() }
        }
        assertEquals(ToolExecPolicy.Allow, policy.exec)
        assertTrue(policy.declaresAnyCapability)

        val roundTripped = ToolPolicy.fromManifestJson(policy.toManifestJson())
        assertEquals(ToolExecPolicy.Allow, roundTripped.exec)
    }

    @Test
    fun `exec deny round-trips and does not count as a declared capability`() {
        val policy = toolPolicy { exec { deny() } }
        assertEquals(ToolExecPolicy.Deny, policy.exec)
        assertFalse(policy.declaresAnyCapability, "deny grants nothing")

        assertEquals(ToolExecPolicy.Deny, ToolPolicy.fromManifestJson(policy.toManifestJson()).exec)
    }

    @Test
    fun `legacy manifests without an exec section parse as Unspecified`() {
        val legacy = toolPolicy { filesystem { read("/data/**") } }.toManifestJson()
            .replace(Regex(""","exec":\{[^}]*}"""), "")
        assertEquals(ToolExecPolicy.Unspecified, ToolPolicy.fromManifestJson(legacy).exec)
    }
}
