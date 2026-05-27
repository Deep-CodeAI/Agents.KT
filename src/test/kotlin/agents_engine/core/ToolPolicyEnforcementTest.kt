package agents_engine.core

import agents_engine.model.LlmResponse
import agents_engine.model.ModelClient
import agents_engine.model.Tool
import agents_engine.model.ToolCall
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #2395 — reference use case from the 0.6.0 tool-policy/manifest field
 * postmortem. Drives a real `agent { }` with a stub `ModelClient` that scripts
 * one turn of tool calls (a write inside the declared policy + a write outside
 * it) against a `@TempDir` workspace.
 *
 * Covers three of the postmortem's cases (the 4th, manifestHash propagation,
 * lives in the `:agents-kt-manifest` module where `permissionManifest()` is):
 *
 *  - **Tripwire (4.1):** a declared `ToolPolicy` does NOT enforce on its own —
 *    with no interceptor the agent writes into the restricted folder. This
 *    assertion intentionally pins the *gap*; it flips when runtime enforcement
 *    (#1916) lands, forcing a revisit.
 *  - **Enforcement (4.2):** an `onBeforeToolCall` interceptor that reads the
 *    tool's declared write globs + returns `Decision.Deny` blocks the restricted
 *    write while allowing the in-policy one.
 *  - **Observability (4.3, fixed here):** a blocked call surfaces through the new
 *    `onToolDenied` hook / `PipelineEvent.ToolDenied` while `onToolUse` /
 *    `PipelineEvent.ToolCalled` fire only for the executed call.
 */
class ToolPolicyEnforcementTest {

    @TempDir
    lateinit var workspace: File

    private val allowedPath: String get() = File(workspace, "allowed/notes/note.txt").absolutePath
    private val restrictedPath: String get() = File(workspace, "secret/vault/secret.txt").absolutePath
    private val writeGlob: String get() = "${workspace.absolutePath}/allowed/**"

    private fun matchesGlob(glob: String, path: String): Boolean =
        FileSystems.getDefault().getPathMatcher("glob:$glob").matches(Path.of(path))

    /**
     * An agent whose `writeFile` tool declares write access to the allowed
     * subtree and whose stub model scripts a write to the allowed path followed
     * by a write to the restricted path, then ends the turn.
     */
    private fun policyAgent(): Agent<String, String> =
        agent("policy-agent") {
            var turn = 0
            model {
                ollama("test")
                client = ModelClient { _ ->
                    turn++
                    if (turn == 1) {
                        LlmResponse.ToolCalls(
                            listOf(
                                ToolCall("writeFile", mapOf("path" to allowedPath)),
                                ToolCall("writeFile", mapOf("path" to restrictedPath)),
                            ),
                        )
                    } else {
                        LlmResponse.Text("done")
                    }
                }
            }
            lateinit var writeFile: Tool<Map<String, Any?>, Any?>
            tools {
                writeFile = tool("writeFile") {
                    description("Write text to a file path")
                    policy {
                        risk = ToolRisk.MEDIUM
                        filesystem { write(writeGlob) }
                        network { denyAll() }
                        environment { denyAll() }
                    }
                    executor { args ->
                        val path = args["path"]?.toString() ?: error("writeFile needs a path")
                        File(path).apply { parentFile?.mkdirs() }.writeText("data")
                        "ok"
                    }
                }
            }
            skills {
                skill<String, String>("act", "Write files on request") {
                    tools(writeFile)
                }
            }
        }

    /** The supported enforcement path: read the tool's declared write globs and deny out-of-policy paths. */
    private fun Agent<String, String>.enforceDeclaredWritePolicy() {
        onBeforeToolCall { name, args ->
            val globs = toolMap[name]?.policy?.filesystem?.write?.globs.orEmpty()
            val path = args["path"]?.toString()
            if (path != null && globs.isNotEmpty() && globs.none { matchesGlob(it, path) }) {
                Decision.Deny("path '$path' outside declared write policy $globs")
            } else {
                Decision.Proceed
            }
        }
    }

    @Test
    fun `declarative policy alone does NOT enforce restricted folders (0_6_0 gap tripwire)`() {
        // No interceptor: the manifest declaration is inert. Both writes run.
        policyAgent().invoke("go")

        assertTrue(File(allowedPath).exists(), "in-policy write should happen")
        // TRIPWIRE: declaring write access to allowed/** does NOT stop a write to
        // secret/vault/. When runtime enforcement (#1916) lands, this flips to
        // assertFalse and forces a revisit of this test.
        assertTrue(
            File(restrictedPath).exists(),
            "0.6.0: declarative policy does not sandbox — restricted write still happens",
        )
    }

    @Test
    fun `onBeforeToolCall interceptor enforces the declared write policy`() {
        val agent = policyAgent().also { it.enforceDeclaredWritePolicy() }

        agent.invoke("go")

        assertTrue(File(allowedPath).exists(), "in-policy write must be allowed")
        assertFalse(File(restrictedPath).exists(), "out-of-policy write must be blocked by the interceptor")
    }

    @Test
    fun `denied calls surface via onToolDenied and PipelineEvent_ToolDenied but never onToolUse`() {
        val agent = policyAgent().also { it.enforceDeclaredWritePolicy() }

        val used = mutableListOf<Pair<String, Map<String, Any?>>>()
        val denied = mutableListOf<Triple<String, Map<String, Any?>, String>>()
        agent.onToolUse { name, args, _ -> used += name to args }
        agent.onToolDenied { name, args, reason -> denied += Triple(name, args, reason) }

        val events = mutableListOf<PipelineEvent>()
        agent.observe { events += it }

        agent.invoke("go")

        // onToolUse sees only the executed (in-policy) call.
        assertEquals(1, used.size, "onToolUse must fire only for the executed call")
        assertEquals(allowedPath, used.single().second["path"])

        // onToolDenied sees the blocked call, with name + args + reason.
        assertEquals(1, denied.size, "onToolDenied must fire for the blocked call")
        val (deniedName, deniedArgs, reason) = denied.single()
        assertEquals("writeFile", deniedName)
        assertEquals(restrictedPath, deniedArgs["path"])
        assertTrue("outside declared write policy" in reason, "reason should explain the denial: $reason")

        // observe{} mirrors both into the unified PipelineEvent stream.
        val calledPaths = events.filterIsInstance<PipelineEvent.ToolCalled>().map { it.arguments["path"] }
        val deniedPaths = events.filterIsInstance<PipelineEvent.ToolDenied>().map { it.arguments["path"] }
        assertEquals(listOf(allowedPath), calledPaths, "ToolCalled only for the executed call")
        assertEquals(listOf(restrictedPath), deniedPaths, "ToolDenied for the blocked call")

        // The ToolDenied event carries the declared-policy audit metadata.
        val deniedEvent = events.filterIsInstance<PipelineEvent.ToolDenied>().single()
        assertEquals(ToolRisk.MEDIUM, deniedEvent.toolPolicyRisk)
        assertTrue(deniedEvent.usedDeclaredCapability, "blocked tool declared a capability")
    }
}
