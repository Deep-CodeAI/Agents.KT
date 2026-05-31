---
description: Source-file knowledge for agents_engine/sandbox/SandboxedTools.kt — processTool(name, policy, commandFor) auto-sandboxes a subprocess tool from its declared ToolPolicy (#2914), and sandboxedEchoToFileTool(folder) is the simplest demo (#2906). Both build a ToolDef whose subprocess runs under ProcessSandbox (macOS Seatbelt). Call when reasoning about how a sandboxed subprocess tool is constructed, the auto-wiring of forPolicy, fail-closed behavior, the injection-safe argv shape, or how Layer-1 declaration and Layer-2 OS enforcement combine.
---

# `agents_engine/sandbox/SandboxedTools.kt` — sandboxed-tool factories

## API

```kotlin
fun processTool(                                    // #2914 — the general factory
    name: String,
    description: String = "",
    policy: ToolPolicy,
    commandFor: (args: Map<String, Any?>) -> List<String>,
): ToolDef
fun sandboxedEchoToFileTool(sandboxedFolder: Path): ToolDef   // #2906 — the simplest demo
```

**`processTool`** is the auto-wiring: you declare the tool's `ToolPolicy` + a command-builder,
and it applies `ProcessSandbox.forPolicy(policy)` for you (the declared `filesystem.write` globs
become the sandbox's writable roots; `network = AllowAll` opens network). Returns the command's
trimmed **stdout** on success, an `"ERROR: …"` string (exit + stderr) on failure, and **fails
closed** — if `ProcessSandbox.isSupported()` is false it refuses to run rather than executing
unsandboxed. The `policy`/`risk` are carried onto the `ToolDef`, so on an agent the Layer-1 gate
(#2890) also checks path args — both layers apply.

**`sandboxedEchoToFileTool`** is the original single-folder demo: a `ToolDef` named `echoToFile`
taking `{ "path", "text" }` that returns `"ok"`/`"ERROR: …"` (note: its `"ok"` contract differs
from `processTool`'s stdout-returning contract, which is why it is kept separate).

## What it shows

- **Declares + enforces.** The tool declares `policy { filesystem { write("<root>/**") } }`, so
  Layer-1 (`ToolPolicyEnforcer`, #2890) denies out-of-policy paths in-JVM *and* the executor
  runs the write through `ProcessSandbox`, so the **kernel** blocks an out-of-folder write even
  for a path the tool builds itself.
- **Injection-safe.** The command is `["/bin/sh", "-c", "printf '%s' \"$1\" > \"$2\"", "sh", text, path]`
  — `text` and `path` are separate argv entries, never interpolated into the script string, so
  there is no shell-injection surface. The `>` redirect executes *inside* the sandbox.
- **Canonical policy glob.** The declared write glob uses `sandboxedFolder.toRealPath()` to match
  the canonical path the OS sandbox enforces.

## Status

macOS only (delegates to `ProcessSandbox`). The richer DSL (`process { }`) and a general
`ToolPolicy` → sandbox mapping are the remaining #2891 work; this factory is the proof slice.

## Related files

- `ProcessSandbox.kt` — the Seatbelt mechanism this delegates to.
- `model/ToolDef.kt` — the tool representation constructed here.
- `core/ToolPolicy.kt` — the declared filesystem stance (also enforced in-JVM by Layer 1).
