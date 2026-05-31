[← Back to README](../README.md)

# Tool-policy enforcement

A tool can **declare** a `ToolPolicy` — the risk class plus filesystem, network, and
environment sub-policies (see [permission-manifest.md](permission-manifest.md) for the
declaration DSL and the manifest it feeds). This page is about what the runtime does to
**enforce** that declaration.

Enforcement is delivered in two layers:

| Layer | What it protects | Applies to | Status |
|-------|------------------|------------|--------|
| **Layer 1 — in-JVM policy gate** | Filesystem-path *arguments* | every tool (in-process lambdas included) | **shipped** (#2890) |
| **Layer 2 — OS sandbox** | The process itself (fs + network + env) | subprocess-shaped tools | macOS Seatbelt + Linux bwrap (#2906 / #2892); firejail fallback, Wasm/Docker planned (#1916) |

---

## Layer 1 — the in-JVM filesystem policy gate

When a tool declares a filesystem stance, the framework checks every pending tool call
against it **before the executor runs**. No hand-written interceptor is needed.

```kotlin
val uploads = tool("writeReport") {
    description("Write a report file")
    policy {
        risk = ToolRisk.Medium
        filesystem { write("/srv/uploads/**") }   // declared write surface
    }
    executor { args ->
        File(args["path"].toString()).writeText(render(args))
        "ok"
    }
}
```

With this declaration:

- `writeReport(path = "/srv/uploads/2026/r.txt")` → **runs** (inside the declared glob).
- `writeReport(path = "/etc/passwd")` → **denied** before the executor runs. The denial
  surfaces exactly like any other blocked call:
  - `onToolDenied { name, args, reason -> … }`
  - `PipelineEvent.ToolDenied` (carries `toolPolicyRisk` and `usedDeclaredCapability`)
  - the executed-call hooks (`onToolUse` / `PipelineEvent.ToolCalled`) do **not** fire for it.

### Exactly what the gate checks

1. **Opt-in by declaration.** If a tool's filesystem stance is `Unspecified` for both
   `read` and `write` (i.e. it declared no filesystem policy), the gate does nothing.
   Existing tools are unaffected — enforcement only engages once you declare a stance.
2. **Absolute path arguments only.** Each string-valued argument that *is an absolute
   path* is a candidate. The allowed set is the **union of the declared `read` + `write`
   globs**; a candidate that matches none of them denies the call.
3. **Normalization.** Candidate paths are normalized before matching, so
   `/srv/uploads/../../etc/passwd` resolves to `/etc/passwd` and is denied — `..`
   traversal cannot escape a declared glob.
4. **`None` means none.** A `filesystem { writeNone() }` (or `readNone()`) stance is a
   *declared* stance with an empty allow-set, so any absolute path argument is denied.

### Turning it off

Enforcement is on by default. To restore the 0.6.0 declare-only (inert) behavior — for
example if you prefer to enforce with your own `onBeforeToolCall` interceptor:

```kotlin
agent("myAgent") {
    enforceToolPolicies = false
    // … your own onBeforeToolCall { … } enforcement, if any
}
```

The built-in gate runs *before* user `onBeforeToolCall` interceptors and short-circuits on
denial (matching the "first non-`Proceed` wins" chain semantics). An executed call still
flows through your interceptors.

### Deliberate Layer-1 limitations

Layer 1 inspects **arguments**, not the running process. Two things it intentionally does
**not** do — both covered by the Layer-2 OS sandbox:

- **Relative paths are not gated.** The JVM has no reliable, side-effect-free way to bind a
  lambda's working directory, and treating every slash-bearing string as a path would
  false-deny ordinary content. Only absolute paths are checked. Pass absolute paths, or run
  the tool under the Layer-2 sandbox, when you need relative-path coverage.
- **`network` and `environment` are not enforced in-JVM.** A plain in-process Kotlin lambda
  can open a socket or read an environment variable with no interception point (modern JDKs
  have no `SecurityManager`). Declaring `network { denyAll() }` documents intent and feeds
  the manifest, but the actual block requires the Layer-2 OS sandbox.

These boundaries are the reason for Layer 2: real process/network/env isolation for tools
whose executor shells out to a subprocess. See the roadmap entry for #1916.

---

## Layer 2 — OS sandbox (macOS Seatbelt + Linux bwrap)

Layer 2 isolates the **process**, not just the arguments — so it holds even for paths a tool
constructs itself. The first slice (#2906) is macOS write-confinement via Seatbelt:

```kotlin
val sandbox = ProcessSandbox(sandboxedFolder)        // folder is canonicalized (toRealPath)
val result = sandbox.run(listOf("/bin/sh", "-c", "…")) // runs under sandbox-exec
// writes outside sandboxedFolder are blocked by the kernel; result.ok == false
```

`agents_engine.sandbox.ProcessSandbox` generates a Seatbelt profile that denies by default and
allows file **writes** only under one canonical folder (reads + process exec stay allowed so the
command can load). The convenience `sandboxedEchoToFileTool(folder)` is the simplest end-to-end
example — a tool that echoes text into a path, OS-confined to `folder`.

The ergonomic way to build a sandboxed subprocess tool is **`processTool`**, which auto-applies
`ProcessSandbox.forPolicy` from the tool's declared policy — you never wire the sandbox by hand:

```kotlin
val grep = processTool("grep", policy = toolPolicy {
    risk = ToolRisk.Medium
    filesystem { read("/data/**"); write("/out/**") }
    network { denyAll() }
}) { args ->
    listOf("rg", args["pattern"].toString(), "/data")   // the command to run
}
// writes confined to /out, network blocked, stdout returned; ERROR (no run) if the
// platform has no OS sandbox (fail-closed).
```

Caveats / status:
- **Three backends, picked by OS** (#2892): macOS **Seatbelt** (`sandbox-exec`), Linux
  **bubblewrap** (`bwrap` — binds the root fs read-only, re-binds the write roots read-write,
  `--unshare-net` unless network is opened), then Linux **firejail** (the **setuid** fallback —
  `--read-only=/` + `--read-write` carve-outs + `--net=none`, so it still confines where
  unprivileged user namespaces are restricted and `bwrap` can't start). `isSupported()` is true
  when any is present. On a host with **none**, `run` does **not** throw — it runs the command via a
  plain `ProcessBuilder` and prints a loud `UNCONFINED` warning (`isSupported()` is false, so a
  caller that requires enforcement can detect and refuse). Wasm (#2894) and Docker (#2895) are follow-ups.
- **Write-confinement, derived from policy.** `ProcessSandbox.forPolicy(policy)` builds the
  profile from a tool's declared `filesystem.write` globs (one-or-many roots) and opens network
  only for `network = AllowAll` — the bridge from Layer-1 declaration to Layer-2 enforcement.
  `forWritableRoots(roots)` confines to several folders directly. **Reads stay broad**, and
  `network { hosts(...) }` filtering needs the proxy (#2893); read-confinement and the
  `process { }` DSL are the remaining #2891 work.
- macOS's `/tmp` is a symlink to `/private/tmp`, and Seatbelt matches the **canonical** path —
  `ProcessSandbox` resolves the folder with `toRealPath()` before building the profile.
- OS-gated integration tests are tagged `mac_os_only` / `linux_only` (+ `@EnabledOnOs`). The pure
  arg/profile generation is unit-tested on every platform; the kernel-level Linux tests auto-skip on
  macOS and run on CI's native Ubuntu runner. See [testing.md](testing.md#linux-sandbox-tests-bwrap--firejail).

---

## Relationship to the permission manifest

Declaration and enforcement are two sides of the same `ToolPolicy`:

- the **manifest** ([permission-manifest.md](permission-manifest.md)) is the build-time,
  reviewable view of what every tool *may* touch;
- **Layer 1** makes the filesystem part of that declaration *bite* at runtime;
- **Layer 2** (#1916) will extend enforcement to the process boundary.
