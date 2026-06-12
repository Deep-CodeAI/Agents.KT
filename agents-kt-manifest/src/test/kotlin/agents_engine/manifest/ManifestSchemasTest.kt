package agents_engine.manifest

import agents_engine.core.agent
import agents_engine.generation.Generable
import agents_engine.generation.Guide
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// #3875 — manifests carry JSON Schema for every @Generable IN/OUT type,
// hash-stable across builds; v1-vs-v2 verification stays ok with an info finding.

class ManifestSchemasTest {

    @Generable("A support ticket")
    data class Ticket(
        @Guide("Short subject") val subject: String,
        @Guide("1-5") val priority: Int,
    )

    @Generable("A triage outcome")
    data class Outcome(@Guide("Routing decision") val route: String)

    private fun triageAgent() = agent<Ticket, Outcome>("triage") {
        skills {
            skill<Ticket, Outcome>("route", "Routes tickets") {
                implementedBy { Outcome("billing:${it.subject}") }
            }
        }
    }

    @Test
    fun `manifest lists schemas for every Generable IN and OUT type with stable hashes`() {
        val manifest = triageAgent().permissionManifest()
        @Suppress("UNCHECKED_CAST")
        val schemas = manifest.toMap()["schemas"] as Map<String, Map<String, Any?>>

        val keys = schemas.keys.map { it.substringAfterLast('$').substringAfterLast('.') }.toSet()
        assertTrue("Ticket" in keys && "Outcome" in keys, "IN and OUT schemas expected; got: ${schemas.keys}")
        schemas.values.forEach { entry ->
            assertEquals(64, entry["sha256"].toString().length, "per-schema sha256")
            assertTrue(entry["schema"].toString().contains("subject") || entry["schema"].toString().contains("route"))
        }

        // Reproducible: a second build of the same graph yields the identical manifest hash.
        assertEquals(manifest.sha256, triageAgent().permissionManifest().sha256, "manifestHash must be schema-stable")
    }

    @Test
    fun `schema change bumps the manifest hash`() {
        val withSchemas = triageAgent().permissionManifest()
        val stringAgent = agent<String, String>("triage") {
            skills { skill<String, String>("route", "Routes tickets") { implementedBy { it } } }
        }
        assertTrue(withSchemas.sha256 != stringAgent.permissionManifest().sha256)
    }

    @Test
    fun `version difference is an info finding and does not fail verification`() {
        val current = triageAgent().permissionManifest()
        // Simulate a v1 baseline: same content, version field forced to 1.
        val v1Json = current.toJson().replace("\"agentsKtManifestVersion\":2", "\"agentsKtManifestVersion\":1")
        val baseline = PermissionManifest.fromJson(v1Json)

        val result = current.verifyAgainst(baseline)
        assertTrue(result.findings.any { it.code == "manifest.version.changed" && it.severity == "info" },
            result.findings.toString())
        assertTrue(result.ok, "info findings must not fail verification; got: ${result.findings}")
    }
}
