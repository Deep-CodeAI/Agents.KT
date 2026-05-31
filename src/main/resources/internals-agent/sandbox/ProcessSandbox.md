---
description: Source-file knowledge for agents_engine/sandbox/ProcessSandbox.kt — Layer 2 of #1916, first slice (#2906). macOS Seatbelt (sandbox-exec) OS-level write-confinement for a subprocess: generates a deny-default profile allowing file writes only under one canonical folder, runs a command, returns SandboxResult. Call when reasoning about OS sandboxing of tool subprocesses, the Seatbelt profile format, the /tmp→/private/tmp canonicalization gotcha, or the Layer-1 vs Layer-2 enforcement boundary.
---

# `agents_engine/sandbox/ProcessSandbox.kt` — macOS Seatbelt write-confinement (Layer 2)

Where Layer 1 (`ToolPolicyEnforcer`) gates tool *arguments* in-JVM, Layer 2 isolates the
**process**. This file is the first slice (#2906) of #2891: confine a subprocess's file writes
to a single folder using macOS Seatbelt, so even a path the tool builds itself can't escape.

## API

```kotlin
class ProcessSandbox /* private(List<Path>, Boolean) */ {
    constructor(writableRoot: Path)                              // single-folder (#2906)
    fun run(command: List<String>, stdin: String? = null, timeout: Duration = 10.seconds): SandboxResult
    companion object {
        fun isSupported(): Boolean
        fun forWritableRoots(roots: List<Path>, allowNetwork: Boolean = false): ProcessSandbox   // #2909
        fun forPolicy(policy: ToolPolicy): ProcessSandbox                                          // #2909
        fun seatbeltProfile(writeRoots: List<Path>, allowNetwork: Boolean = false): String        // pure
        fun seatbeltProfile(realRoot: Path): String              // single-root convenience
        fun globToWriteRoot(glob: String): String                // pure: glob -> dir prefix
    }
}
data class SandboxResult(val exitCode: Int, val stdout: String, val stderr: String) { val ok: Boolean }
```

`forPolicy` (#2909) is the Layer-1→Layer-2 bridge: writable roots come from the tool's
`filesystem.write` globs (`globToWriteRoot` takes each glob's literal directory prefix), and
network is opened only for `network = AllowAll`. `forWritableRoots` confines writes to several
folders at once. Empty write roots ⇒ a deny-all-writes profile.

## How it works

- **Profile** (`seatbeltProfile`, pure): `(version 1)(deny default)(allow process*)(allow file-read*)(allow file-write* (subpath "<root>"))`. Deny-by-default; reads + process exec stay allowed so `/bin/sh` can load and run; writes allowed only under the root. The root is wrapped as an SBPL string literal (backslash + double-quote escaped).
- **Launch**: `ProcessBuilder(listOf("/usr/bin/sandbox-exec", "-p", profile) + command)`. stdout/stderr are drained on daemon threads (avoids a full-pipe deadlock — same pattern as `mcp/StdioMcpTransport.forProcess`); optional `stdin` is written then closed; `waitFor(timeout)` → `destroy()` → `destroyForcibly()` on overrun (returns exit `-1` with a timeout note).
- **Support gate**: `isSupported()` = macOS + `/usr/bin/sandbox-exec` executable. `run` `check()`s this and throws a loud `IllegalStateException` elsewhere (Linux backend is #2892).

## The canonicalization gotcha (load-bearing)

macOS `/tmp` is a symlink to `/private/tmp`, and Seatbelt `subpath` matches the **canonical**
path. A logical root (`/tmp/x`) would match nothing, silently blocking *every* write. The
constructor resolves `writableRoot.toRealPath()` once and the profile uses that. Callers that
compare a write target against the root must also canonicalize.

## Scope / limitations (remain in #2891)

- macOS only. Writes confined to one-or-many roots, derived from `ToolPolicy.filesystem.write`
  via `forPolicy` (#2909). Network opens only for `AllowAll`; `Hosts` filtering needs the proxy
  (#2893). **Reads stay broad** — read-confinement needs system-path allowlisting (deferred).
- Not yet auto-wired into the agent executor (a tool with a policy isn't auto-sandboxed; the
  caller builds `ProcessSandbox.forPolicy`). The `process { }` DSL, plain-`ProcessBuilder`
  fallback, and the Linux bwrap/firejail backend (#2892) remain in #2891 (Wasm/Docker = #2894/#2895).

## Related files

- `SandboxedTools.kt` — `sandboxedEchoToFileTool(folder)`, the simplest tool built on this.
- `core/ToolPolicyEnforcer.kt` — Layer 1 (in-JVM arg gate); this is its OS-level sibling.
- `mcp/StdioMcpTransport.kt` — the `ProcessBuilder` lifecycle pattern reused here.
- `docs/tool-policy-enforcement.md` — the user-facing two-layer guide.
