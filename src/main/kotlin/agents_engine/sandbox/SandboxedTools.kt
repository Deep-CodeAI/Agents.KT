package agents_engine.sandbox

import agents_engine.core.ToolRisk
import agents_engine.core.toolPolicy
import agents_engine.model.ToolDef
import java.nio.file.Path

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
