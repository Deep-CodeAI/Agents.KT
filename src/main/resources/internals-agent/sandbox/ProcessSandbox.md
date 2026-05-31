---
description: Source-file knowledge for agents_engine/sandbox/ProcessSandbox.kt — Layer 2 of #1916. OS-level write-confinement for a subprocess, dispatched by backend at run time: macOS Seatbelt (sandbox-exec), Linux bubblewrap (bwrap), Linux firejail (setuid fallback), and a plain-ProcessBuilder no-backend fallback that runs unconfined with a loud warning (#2892). Allows file writes only under canonical roots, runs a command, returns SandboxResult. Call when reasoning about OS sandboxing of tool subprocesses, the Seatbelt/bwrap/firejail argv shapes, the /tmp→/private/tmp canonicalization gotcha, the unprivileged-userns restriction that motivates the firejail fallback, or the Layer-1 vs Layer-2 enforcement boundary.
---

# `agents_engine/sandbox/ProcessSandbox.kt` — OS sandbox: macOS Seatbelt + Linux bwrap/firejail (Layer 2)

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
        fun firejailArgs(writeRoots: List<Path>, allowNetwork: Boolean = false): List<String>      // pure, Linux #2892 setuid fallback
        fun globToWriteRoot(glob: String): String                // pure: glob -> dir prefix
    }
    internal enum class Backend { SEATBELT, BWRAP, FIREJAIL, NONE }                                  // run-time dispatch
    internal fun runWithBackend(b: Backend, command, stdin?, timeout): SandboxResult                 // forced backend (tests)
}
data class SandboxResult(val exitCode: Int, val stdout: String, val stderr: String) { val ok: Boolean }
```

`forPolicy` (#2909) is the Layer-1→Layer-2 bridge: writable roots come from the tool's
`filesystem.write` globs (`globToWriteRoot` takes each glob's literal directory prefix), and
network is opened only for `network = AllowAll`. `forWritableRoots` confines writes to several
folders at once. Empty write roots ⇒ a deny-all-writes profile.

## How it works

- **Profile** (`seatbeltProfile`, pure): `(version 1)(deny default)(allow process*)(allow file-read*)(allow file-write* (subpath "<root>"))`. Deny-by-default; reads + process exec stay allowed so `/bin/sh` can load and run; writes allowed only under the root. The root is wrapped as an SBPL string literal (backslash + double-quote escaped).
- **Launch (dispatched by backend in `run` → `runWithBackend`)**: macOS Seatbelt → `sandbox-exec -p <profile> <command>`; Linux bwrap → `bwrap <bwrapArgs> <command>`; Linux firejail → `firejail <firejailArgs> <command>`; no backend → plain `ProcessBuilder` running the command **unconfined** after a loud `UNCONFINED` warning. `bwrapArgs` (pure) binds the whole fs `--ro-bind / /`, a fresh `--proc /proc --dev /dev`, re-binds each write root `--bind <root> <root>`, `--unshare-net` unless opened. `firejailArgs` (pure, the setuid fallback) does `--quiet --noprofile --read-only=/ --read-write=<root>… --net=none` — same write-confinement, but firejail's setuid means it works where unprivileged userns is blocked. stdout/stderr drained on daemon threads (full-pipe-deadlock guard — same pattern as `mcp/StdioMcpTransport.forProcess`); optional `stdin` written then closed; `waitFor(timeout)` → `destroy()` → `destroyForcibly()` on overrun (exit `-1` + timeout note).
- **Support / dispatch gate**: `detectBackend()` picks Seatbelt → bwrap → firejail → NONE (each resolved from PATH). `isSupported()` = a real backend exists (≠ NONE). `run` **no longer throws** when none is present — it runs unconfined + warns, so a caller that requires enforcement checks `isSupported()` and refuses. The internal `Backend` enum + `runWithBackend(backend, …)` seam let tests force a specific backend (e.g. firejail even where bwrap is also installed and would win). Wasm/Docker are #2894/#2895.

## The canonicalization gotcha (load-bearing)

macOS `/tmp` is a symlink to `/private/tmp`, and Seatbelt `subpath` matches the **canonical**
path. A logical root (`/tmp/x`) would match nothing, silently blocking *every* write. The
constructor resolves `writableRoot.toRealPath()` once and the profile uses that. Callers that
compare a write target against the root must also canonicalize.

## Scope / limitations (remain in #2891)

- macOS (Seatbelt) + Linux (bwrap primary, firejail setuid fallback) + a plain-`ProcessBuilder`
  no-backend fallback (warns, runs unconfined). Writes confined to one-or-many roots, derived from
  `ToolPolicy.filesystem.write` via `forPolicy` (#2909). Network opens only for `AllowAll`; `Hosts`
  filtering needs the proxy (#2893). **Reads stay broad** — read-confinement needs system-path
  allowlisting (deferred).
- Auto-wiring via `processTool(...)` (#2914) builds `forPolicy` for you. Remaining #2891 follow-ups:
  the network proxy (#2893), read-confinement, and the `process { }` DSL (Wasm/Docker = #2894/#2895).

## Related files

- `SandboxedTools.kt` — `sandboxedEchoToFileTool(folder)`, the simplest tool built on this.
- `core/ToolPolicyEnforcer.kt` — Layer 1 (in-JVM arg gate); this is its OS-level sibling.
- `mcp/StdioMcpTransport.kt` — the `ProcessBuilder` lifecycle pattern reused here.
- `docs/tool-policy-enforcement.md` — the user-facing two-layer guide.
