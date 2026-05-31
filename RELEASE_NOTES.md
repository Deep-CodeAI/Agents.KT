# Agents.KT v0.7.0 — Boundaries you can enforce externally

**Release date:** 2026-05-31

The 0.6 line made tool policies **declarative** and **auditable**. 0.7.0 makes them **enforced** — at runtime, by the OS — and reachable **outside the build**.

> A declared `ToolPolicy` is no longer just documentation and a manifest entry. It now constrains what a tool can actually do, and a standalone binary lets a CI gate or auditor verify the capability boundary without Gradle.

```kotlin
implementation("ai.deep-code:agents-kt:0.7.0")
implementation("ai.deep-code:agents-kt-ksp:0.7.0")           // optional but recommended
implementation("ai.deep-code:agents-kt-manifest:0.7.0")      // permission manifests
implementation("ai.deep-code:agents-kt-observability:0.7.0") // JSONL audit + ObservabilityBridge
implementation("ai.deep-code:agents-kt-otel:0.7.0")
implementation("ai.deep-code:agents-kt-langsmith:0.7.0")
implementation("ai.deep-code:agents-kt-langfuse:0.7.0")
```

## What ships in 0.7.0

### Enforcement — declared policies now constrain tools at runtime

- **Layer 1 — in-JVM filesystem gate (#2890).** A tool call whose absolute filesystem-path argument falls outside the declared `read`/`write` globs is denied before the executor runs (paths normalized first, so `..` can't escape), surfacing through `onToolDenied` / `PipelineEvent.ToolDenied`. Opt-in by declaration; `enforceToolPolicies = false` restores 0.6 behavior.
- **Layer 2 — OS sandbox for subprocess-shaped tools (#1916).** `ProcessSandbox` confines a subprocess at the kernel level:
  - **macOS Seatbelt** (`sandbox-exec`), **Linux bubblewrap** (`bwrap`), a **firejail setuid fallback** (confines even where unprivileged user namespaces are restricted, e.g. Ubuntu 24.04), and a plain `ProcessBuilder` + loud **`UNCONFINED`** warning where no sandbox tool is present (`isSupported()` is false, so a caller that requires enforcement can refuse).
  - `forPolicy(policy)` derives **write roots**, an **environment allow-list**, and a **working directory** from the declared `ToolPolicy`; the **network is default-deny** (only `network { allowAll() }` opens it). Auto-wired via `processTool(...)`.
  - Verified on CI on a native Linux runner (bwrap + firejail kernel-level confinement) and on macOS (Seatbelt).

### "Externally" — the manifest from a binary (#1923)

- New standalone **`agents-kt` CLI** (`:agents-kt-cli`): `generate` / `inspect` / `verify` the deterministic permission manifest with no build tool in the loop. `verify` fails (exit `1`) when a change widens a boundary (risk increased, network opened, write scope broadened) — a drop-in CI gate.
- The reflective entrypoint→manifest loader is now Gradle-free and **shared** with the `agentManifest` Gradle plugin, so a build and the CLI emit byte-identical manifests. See [`docs/cli.md`](docs/cli.md).

## Deferred to 0.8 (tracked, not in 0.7.0)

- `WasmSandbox` (Chicory, #2894) and `DockerSandbox` (opt-in extras, #2895) backends.
- The network **hostname-allowlist proxy** (#2893) — default-deny ships; selective per-host allow needs the proxy.
- The `grants { }` hierarchical structure DSL (root/delegates, parent-⊇-child).
- A jlink / GraalVM single-file CLI image (packaging).

## Compatibility

Additive and opt-in by declaration. A tool that declares no policy is never gated; existing 0.6.x callers compile and run unchanged. `enforceToolPolicies = false` restores the inert 0.6 behavior if needed.
