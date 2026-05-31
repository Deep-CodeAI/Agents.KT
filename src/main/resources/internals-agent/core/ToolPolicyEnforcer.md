---
description: Source-file knowledge for agents_engine/core/ToolPolicyEnforcer.kt — Layer 1 of #1916, the in-JVM filesystem policy gate (#2890). Pure function that denies a tool call whose absolute path arguments fall outside the declared read/write globs, with `..` normalization and opt-in-by-declaration semantics. Call when the IDE LLM needs to reason about runtime tool-policy enforcement, why a tool call was denied, or the boundary between Layer 1 and the Layer-2 OS sandbox.
---

# `agents_engine/core/ToolPolicyEnforcer.kt` — in-JVM filesystem policy gate (Layer 1)

The runtime half of `ToolPolicy`. 0.6.0 let a tool *declare* a policy; #2890 makes the
**filesystem** part of that declaration enforce at the tool-call boundary, in-process, with
no hand-written interceptor. This is Layer 1 of #1916; Layer 2 is the OS sandbox.

## API

```kotlin
internal object ToolPolicyEnforcer {
    fun evaluate(policy: ToolPolicy?, args: Map<String, Any?>): Decision<Map<String, Any?>>
}
```

Pure and side-effect-free: given a tool's declared `ToolPolicy` and the arguments of a
pending call, returns `Decision.Proceed` or `Decision.Deny(reason)`. It never touches the
filesystem and never throws into the agent loop.

## Where it runs

`Agent.decideBeforeToolCall(name, args)` invokes it **before** any user `onBeforeToolCall`
interceptor, gated on the agent-level `enforceToolPolicies` flag (default `true`). A `Deny`
short-circuits the chain (matching "first non-`Proceed` wins") and flows through the existing
dispatch in `AgenticLoop` → `onToolDenied` / `PipelineEvent.ToolDenied`, carrying
`toolPolicyRisk` and `usedDeclaredCapability`. The executed call still passes through user
interceptors below.

## Decision rules

1. **Opt-in by declaration.** If `policy?.filesystem` has both `read` and `write` as
   `ToolFilesystemAccess.Unspecified`, the call proceeds — undeclared tools are never gated,
   so existing tools are unaffected. (`null` policy ⇒ proceed.)
2. **Candidate args = absolute paths only.** Each string-valued arg is a candidate iff
   `Path.of(value).isAbsolute`. Relative strings and ordinary content are ignored — treating
   every slash-bearing string as a path would false-deny content, and the JVM can't bind a
   lambda's CWD reliably.
3. **Allow-set = union of `read.globs` + `write.globs`.** A candidate that matches none of
   them denies the call: `Decision.Deny("path '<p>' outside declared filesystem policy <globs>")`.
   `ToolFilesystemAccess.None` is a declared stance with an empty allow-set ⇒ any path arg denies.
4. **Normalization before matching.** `Path.of(value).normalize()` resolves `..`/`.`, so
   `/uploads/../../etc/passwd` becomes `/etc/passwd` and is denied — traversal cannot escape a glob.
   Matching reuses `FileSystems.getDefault().getPathMatcher("glob:<glob>")`.
5. **Robust to junk.** Both path parsing and glob matching are wrapped in `runCatching`; an
   `InvalidPathException` (e.g. a NUL byte) makes the arg a non-candidate rather than crashing.

## Deliberate Layer-1 limitations (covered by Layer 2 / #1916)

- **Relative paths** are not gated (see rule 2).
- **`network` and `environment`** sub-policies are not enforced in-JVM — a plain lambda can
  open a socket or read an env var with no interception point (no `SecurityManager` in modern
  JDKs). Declaring them documents intent and feeds the manifest; the actual block needs the
  Layer-2 OS sandbox (`ProcessSandbox` / `WasmSandbox` / `DockerSandbox`).

## Escape hatch

`agent { enforceToolPolicies = false }` restores the 0.6.0 declare-only (inert) behavior —
useful when a consumer prefers custom `onBeforeToolCall` enforcement.

## Related files

- `ToolPolicy.kt` — the declaration this enforces (filesystem / network / environment shapes).
- `Agent.kt` — `decideBeforeToolCall` wiring + the `enforceToolPolicies` flag.
- `Decision.kt` — the `Proceed` / `Deny` sealed return type.
- `model/AgenticLoop.kt` — turns a `Decision.Deny` into the `ToolDenied` audit event.
- `docs/tool-policy-enforcement.md` — the user-facing two-layer guide.
