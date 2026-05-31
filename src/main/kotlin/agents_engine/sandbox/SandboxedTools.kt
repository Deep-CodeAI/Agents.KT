package agents_engine.sandbox

import agents_engine.core.ToolPolicy
import agents_engine.core.ToolRisk
import agents_engine.core.toolPolicy
import agents_engine.model.ToolDef
import java.nio.file.Path

/**
 * Build a subprocess tool that is **automatically OS-sandboxed from its declared
 * [policy]** (#2914, under #2891). You supply the [policy] and a [commandFor] that
 * turns the call arguments into a command line; the framework launches it via
 * [ProcessSandbox.forPolicy] — no hand-wiring of the sandbox.
 *
 * On success the tool returns the command's trimmed stdout; on a non-zero exit it
 * returns an `"ERROR: …"` string (exit code + stderr). The declared filesystem write
 * globs become the sandbox's writable roots, and `network = AllowAll` opens network.
 *
 * **Fail-closed:** if no OS sandbox is available ([ProcessSandbox.isSupported] is
 * false — currently any non-macOS host), the tool refuses to run rather than
 * executing the subprocess unsandboxed. The plain-`ProcessBuilder` fallback and the
 * Linux backend are #2892.
 *
 * The tool also *declares* [policy], so when used on an agent the in-JVM Layer-1
 * gate ([agents_engine.core.ToolPolicyEnforcer], #2890) checks path arguments too —
 * both enforcement layers apply.
 */
fun processTool(
    name: String,
    description: String = "",
    policy: ToolPolicy,
    commandFor: (args: Map<String, Any?>) -> List<String>,
): ToolDef = ToolDef(
    name = name,
    description = description,
    risk = policy.risk,
    policy = policy,
) { args ->
    if (!ProcessSandbox.isSupported()) {
        "ERROR: OS sandbox unavailable on this platform (macOS only for now; Linux is #2892) — " +
            "refusing to run '$name' unsandboxed"
    } else {
        val result = ProcessSandbox.forPolicy(policy).run(commandFor(args))
        if (result.ok) {
            result.stdout.trimEnd()
        } else {
            "ERROR: '$name' failed (exit ${result.exitCode}): ${result.stderr.trim()}"
        }
    }
}

/**
 * The simplest Layer-2 demonstration tool (#2906, under #2891): echo `text` into
 * the file at `path`, with the **OS sandbox** confining every write to
 * [sandboxedFolder]. A path outside the folder is blocked by the kernel
 * (macOS Seatbelt), not just the in-JVM Layer-1 gate (#2890).
 *
 * Arguments: `{ "path": String, "text": String }`. Returns `"ok"` on success or an
 * `"ERROR: …"` string when the write is blocked/failed.
 *
 * The tool also *declares* a matching filesystem policy, so Layer-1 enforcement
 * (#2890) denies out-of-policy paths in-JVM as well — but the kernel-level block
 * here is what makes the tool safe even for paths it constructs itself.
 *
 * macOS only for now (see [ProcessSandbox.isSupported]); the Linux backend is #2892.
 */
fun sandboxedEchoToFileTool(sandboxedFolder: Path): ToolDef {
    val root = sandboxedFolder.toRealPath()
    return ToolDef(
        name = "echoToFile",
        description = "Write text to a file path, confined by the OS sandbox to $root",
        risk = ToolRisk.MEDIUM,
        policy = toolPolicy {
            risk = ToolRisk.MEDIUM
            filesystem { write("$root/**") }
        },
    ) { args ->
        val path = args["path"]?.toString()
        if (path.isNullOrBlank()) {
            "ERROR: missing 'path'"
        } else {
            val text = args["text"]?.toString() ?: ""
            // argv-separated -> no shell injection; the redirect runs *inside* the sandbox.
            val result = ProcessSandbox(sandboxedFolder).run(
                listOf("/bin/sh", "-c", "printf '%s' \"\$1\" > \"\$2\"", "sh", text, path),
            )
            if (result.ok) {
                "ok"
            } else {
                "ERROR: write blocked or failed (exit ${result.exitCode}): ${result.stderr.trim()}"
            }
        }
    }
}
