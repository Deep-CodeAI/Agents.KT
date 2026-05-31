---
description: Source-file knowledge for agents_engine/sandbox/SandboxedTools.kt — sandboxedEchoToFileTool(folder), the simplest Layer-2 demonstration tool (#2906). Builds a ToolDef that echoes text into a path, OS-confined to one folder via ProcessSandbox (macOS Seatbelt). Call when reasoning about how a sandboxed subprocess tool is constructed, the injection-safe argv shape, or how Layer-1 declaration and Layer-2 OS enforcement combine on one tool.
---

# `agents_engine/sandbox/SandboxedTools.kt` — the simplest sandboxed tool

A single factory that demonstrates Layer 2 end-to-end.

## API

```kotlin
fun sandboxedEchoToFileTool(sandboxedFolder: Path): ToolDef
```

Returns a `ToolDef` named `echoToFile` taking `{ "path": String, "text": String }`. It writes
`text` to `path`, with the OS sandbox confining every write to `sandboxedFolder`. Returns
`"ok"` on success or an `"ERROR: …"` string when the write is blocked/failed.

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
