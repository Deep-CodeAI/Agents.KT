package agents_engine.detekt

import io.gitlab.arturbosch.detekt.test.lint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #2887 — body ⊆ policy: an executor body using a capability the declared
 * policy doesn't grant fails; matching and over-declared policies pass;
 * tools without a policy block are out of scope.
 */
class ToolPolicyCapabilityComparatorTest {

    private val rule = ToolPolicyCapabilityComparator()

    @Test fun `body writes a file but policy declares writeNone — flagged`() {
        val findings = rule.lint(
            """
            fun build() {
                tool("writer") {
                    policy { filesystem { read("/data/**"); writeNone() } }
                    executor { args ->
                        java.io.File("/out/x").writeText("x")
                        "ok"
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(1, findings.size, "undeclared FS_WRITE must be flagged; got: $findings")
        assertTrue("FS_WRITE" in findings.single().message)
    }

    @Test fun `body matches the declared policy — passes`() {
        val findings = rule.lint(
            """
            fun build() {
                tool("writer") {
                    policy { filesystem { write("/out/**") } }
                    executor { args ->
                        java.io.File("/out/x").writeText("x")
                        "ok"
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), findings.toList(), "declared write must pass")
    }

    @Test fun `over-declared policy passes — declaring more than the body uses is fine`() {
        val findings = rule.lint(
            """
            fun build() {
                tool("reader") {
                    policy {
                        filesystem { read("/data/**"); write("/out/**") }
                        network { allowAll() }
                        exec { allow() }
                    }
                    executor { args -> java.io.File("/data/x").readText() }
                }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), findings.toList(), "over-declaration is a manifest concern, not a violation")
    }

    @Test fun `network and exec used but not granted — both flagged`() {
        val findings = rule.lint(
            """
            fun build() {
                tool("fetcher") {
                    policy { network { denyAll() } }
                    executor { args ->
                        val c = java.net.URL("https://x").openConnection()
                        ProcessBuilder("ls").start()
                        c.toString()
                    }
                }
            }
            """.trimIndent(),
        )
        assertEquals(2, findings.size, "NETWORK and EXEC must both be flagged; got: $findings")
    }

    @Test fun `no policy block — out of scope, nothing flagged`() {
        val findings = rule.lint(
            """
            fun build() {
                tool("legacy") {
                    executor { args -> java.io.File("/x").writeText("y") }
                }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), findings.toList(), "un-policied tools are ToolBodyForbiddenApis' business")
    }

    @Test fun `env access granted via environment allow — passes`() {
        val findings = rule.lint(
            """
            fun build() {
                tool("env-reader") {
                    policy { environment { allow("HOME") } }
                    executor { args -> System.getenv("HOME") ?: "" }
                }
            }
            """.trimIndent(),
        )
        assertEquals(emptyList(), findings.toList())
    }
}
