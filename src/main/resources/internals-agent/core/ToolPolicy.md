---
description: Source-file knowledge for agents_engine/core/ToolPolicy.kt — declarative tool sandbox policy model and DSL (#1915). Defines ToolPolicy risk/filesystem/network/environment sub-policies, toolPolicy { } builder, manifest map/JSON/YAML helpers, and the declarative-only 0.6.0 contract. Call when reasoning about tool risk metadata, policy serialization, audit fields, or future sandbox enforcement inputs.
---

# `agents_engine/core/ToolPolicy.kt` — declarative tool policy

`ToolPolicy` records what a tool is expected to touch:

```kotlin
tool("readUploadedDocument") {
    description("Read an uploaded KYC document")
    policy {
        risk = ToolRisk.Medium
        filesystem {
            read("/uploads/kyc/**")
            writeNone()
        }
        network { denyAll() }
        environment { allow("OCR_REGION") }
    }
    executor { args -> /* ... */ }
}
```

This is **declarative only in 0.6.0**. It feeds manifest/audit evidence; it does not sandbox the executor. Runtime enforcement is the sibling #1916 track.

## Model

```kotlin
data class ToolPolicy(
    val risk: ToolRisk = ToolRisk.LOW,
    val filesystem: ToolFilesystemPolicy = ToolFilesystemPolicy(),
    val network: ToolNetworkPolicy = ToolNetworkPolicy.Unspecified,
    val environment: ToolEnvironmentPolicy = ToolEnvironmentPolicy.Unspecified,
)
```

Sub-policies:

- Filesystem: `read(glob)`, `write(glob)`, `readNone()`, `writeNone()`.
- Network: `allow(host)`, `denyAll()`, `allowAll()`.
- Environment: `allow(varName)`, `denyAll()`.

`network { allowAll() }` logs a warning during policy construction so broad egress appears loudly in review.

## Manifest Helpers

`ToolPolicy` exposes:

- `toManifestMap()` / `fromManifestMap(...)`
- `toManifestJson()` / `fromManifestJson(...)`
- `toManifestYaml()` / `fromManifestYaml(...)`

These helpers are deterministic and zero-dependency so `:agents-kt-manifest` can capture tool policies verbatim without pulling a YAML/JSON library into the core runtime.

## Audit

`PipelineEvent.ToolCalled` includes:

- `toolPolicyRisk`
- `usedDeclaredCapability`

The JSONL audit exporter writes both fields. `usedDeclaredCapability` is true when the executed tool declares at least one filesystem/network/environment capability; it is not OS-level proof that the capability was used.

## Related Files

- `core/Tool.kt` — common `Tool<IN, OUT>` contract exposes `risk` and `policy`.
- `model/ToolDef.kt` — local tool builders attach `ToolPolicy`.
- `core/PipelineEvent.kt` — `ToolCalled` carries risk/capability metadata.
- `agents-kt-observability/.../JsonlAuditExporter.kt` — exports policy fields to JSONL.
