package agents_engine.manifest

import agents_engine.core.AgentRuntimeContext
import agents_engine.core.agent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * #2395 — corrects field-postmortem finding 4.4, which claimed the whole-agent
 * `manifestHash` is "always null" / the generator "not built".
 *
 * Reality: the generator exists (`agent.permissionManifest()` in this module),
 * computes a SHA-256, and calls `Agent.attachManifestHash(sha256)`, after which
 * the hash rides the runtime context on every invocation. The "always null"
 * symptom is simply that no manifest had been generated — not a library defect.
 *
 * This pins the before/after contrast (the existing
 * `PermissionManifestTest` only checks the populated state).
 */
class ManifestHashPropagationTest {

    @Test
    fun `manifestHash is null until permissionManifest generates it, then propagates`() {
        val observed = mutableListOf<String?>()
        val a = agent<String, String>("hash-agent") {
            skills {
                skill<String, String>("echo", "Echo input") {
                    implementedBy {
                        observed += AgentRuntimeContext.currentOrNew().manifestHash
                        it
                    }
                }
            }
        }

        // Before generating a manifest: nothing is stamped onto the runtime context.
        a("first")
        assertNull(observed.last(), "manifestHash must be null until a manifest is generated")

        // Generating the manifest computes a hash and attaches it to the agent.
        val manifest = a.permissionManifest()
        assertTrue(manifest.sha256.matches(Regex("[a-f0-9]{64}")), "manifest hash should be a SHA-256 hex")

        // After generation: the same hash propagates on every invocation.
        a("second")
        assertEquals(
            manifest.sha256,
            observed.last(),
            "manifestHash must propagate once the manifest is generated (postmortem 4.4 was a usage gap)",
        )
    }
}
