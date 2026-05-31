---
description: Source-file knowledge for agents_engine/sandbox/ProcessSandbox.kt — Layer 2 of #1916, first slice (#2906). macOS Seatbelt (sandbox-exec) OS-level write-confinement for a subprocess: generates a deny-default profile allowing file writes only under one canonical folder, runs a command, returns SandboxResult. Call when reasoning about OS sandboxing of tool subprocesses, the Seatbelt profile format, the /tmp→/private/tmp canonicalization gotcha, or the Layer-1 vs Layer-2 enforcement boundary.
---

# `agents_engine/sandbox/ProcessSandbox.kt` — OS sandbox: macOS Seatbelt + Linux bwrap (Layer 2)

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
        fun seatbeltProfile(writeRoots: List<Path>, allowNetwork: Boolean = false): String        // pure, macOS
        fun seatbeltProfile(realRoot: Path): String              // single-root convenience
        fun bwrapArgs(writeRoots: List<Path>, allowNetwork: Boolean = false): List<String>         // pure, Linux #2892
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
- **Launch (dispatched by OS in `run`)**: macOS → `sandbox-exec -p <profile> <command>`; Linux → `bwrap <bwrapArgs> <command>`. `bwrapArgs` (pure, #2892) binds the whole fs `--ro-bind / /`, a fresh `--proc /proc --dev /dev`, re-binds each write root `--bind <root> <root>`, and `--unshare-net` unless network is opened. stdout/stderr are drained on daemon threads (full-pipe-deadlock guard — same pattern as `mcp/StdioMcpTransport.forProcess`); optional `stdin` written then closed; `waitFor(timeout)` → `destroy()` → `destroyForcibly()` on overrun (exit `-1` + timeout note).
- **Support gate**: `isSupported()` = macOS-with-`sandbox-exec` OR Linux-with-`bwrap` (resolved from PATH); `run` throws on platforms with neither (firejail fallback + Wasm/Docker are follow-ups).

## The canonicalization gotcha (load-bearing)

macOS `/tmp` is a symlink to `/private/tmp`, and Seatbelt `subpath` matches the **canonical**
path. A logical root (`/tmp/x`) would match nothing, silently blocking *every* write. The
constructor resolves `writableRoot.toRealPath()` once and the profile uses that. Callers that
compare a write target against the root must also canonicalize.

## Scope / limitations (remain in #2891)

- macOS (Seatbelt) + Linux (bwrap). Writes confined to one-or-many roots, derived from
  `ToolPolicy.filesystem.write` via `forPolicy` (#2909). Network opens only for `AllowAll`;
  `Hosts` filtering needs the proxy (#2893). **Reads stay broad** — read-confinement needs
  system-path allowlisting (deferred).
- Auto-wiring via `processTool(...)` (#2914) builds `forPolicy` for you. Remaining follow-ups:
  the firejail fallback + plain-`ProcessBuilder` fallback, the network proxy (#2893),
  read-confinement, and the `process { }` DSL (Wasm/Docker = #2894/#2895).

## Related files

- `SandboxedTools.kt` — `sandboxedEchoToFileTool(folder)`, the simplest tool built on this.
- `core/ToolPolicyEnforcer.kt` — Layer 1 (in-JVM arg gate); this is its OS-level sibling.
- `mcp/StdioMcpTransport.kt` — the `ProcessBuilder` lifecycle pattern reused here.
- `docs/tool-policy-enforcement.md` — the user-facing two-layer guide.
