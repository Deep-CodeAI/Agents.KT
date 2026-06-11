# Permission Manifest

The permission manifest (shipped in 0.6.0) is the audit artifact for Agents.KT. It turns an
agent or composition into deterministic JSON/YAML that can be reviewed in CI,
checked into an evidence pack, and correlated with runtime audit events through
`manifestHash`.

The manifest captures:

- agents, input/output types, skills, knowledge keys, and per-skill tool allowlists
- tool risk plus declared filesystem, network, and environment policy
- memory enablement and memory-tool opt-in
- model provider, model name, base URL, and masked API-key evidence
- budgets and guardrail hook counts
- MCP client snapshots and MCP server exposure snapshots
- composition structure for `then`, `/`, `forum`, `loop`, and `branch`

Secrets are not emitted raw. Provider API keys become:

```json
{"apiKey":"masked","apiKeyPresent":true}
```

## Runtime API

Add the manifest module:

```kotlin
dependencies {
    // published on Maven Central — use the latest released version (see the README quickstart)
    implementation("ai.deep-code:agents-kt:0.7.23")
    // in-repo module — build from this repository (not yet published to Central;
    // only agents-kt and agents-kt-ksp are)
    implementation(project(":agents-kt-manifest"))
}
```

Generate a manifest from an agent:

```kotlin
import agents_engine.manifest.permissionManifest

val manifest = reviewer.permissionManifest {
    includeProviderConfig = true
    includeBudgets = true
    includeMcp = true
    includeMemory = true
    includePolicy = true
    includeComposition = true
}

manifest.writeJson(file("build/agents/permissions.json"))
manifest.writeYaml(file("build/agents/permissions.yaml"))
```

The same extension exists on composition objects:

```kotlin
val pipeline = parse then review
val manifest = pipeline.permissionManifest()
```

Generating the manifest attaches `manifest.sha256` to every agent in the graph.
Subsequent runtime events carry that value as `manifestHash`, so JSONL audit rows
can be tied back to the reviewed capability graph.

## CI Verification

`verifyAgainst` compares a current manifest to an approved baseline and reports
high-risk widening:

```kotlin
val current = pipeline.permissionManifest()
val baseline = PermissionManifest.fromJson(file("agents/permissions.baseline.json").readText())

val result = current.verifyAgainst(baseline)
check(result.ok) {
    result.findings.joinToString("\n") { "${it.code}: ${it.message}" }
}
```

Today the verifier flags:

- new high-risk tools
- tool risk increases into `high` or `critical`
- network access widening, including `denyAll` to `allowAll`
- filesystem read/write access widening

## Gradle Plugin

The manifest module also provides a Gradle plugin (built from this repository — not yet on the Gradle Plugin Portal; consume it via an included build or your own plugin repository):

```kotlin
plugins {
    id("ai.deep-code.agents-kt.manifest")
}

agentsKtManifest {
    entrypointClass.set("com.example.AgentManifestEntrypoint")
    outputJson.set(layout.buildDirectory.file("agents/permissions.json"))
    outputYaml.set(layout.buildDirectory.file("agents/permissions.yaml"))
    baselineJson.set(layout.projectDirectory.file("agents/permissions.baseline.json"))
}
```

The entrypoint can implement `PermissionManifestProvider`:

```kotlin
import agents_engine.manifest.PermissionManifestProvider
import agents_engine.manifest.permissionManifest

class AgentManifestEntrypoint : PermissionManifestProvider {
    override fun permissionManifest() = buildPipeline().permissionManifest()
}
```

Or expose a no-arg `permissionManifest()` method. The method may return a
`PermissionManifest`, an `Agent`, or a supported composition; the task will coerce
agents/compositions into manifests.

Tasks:

- `agentManifest` writes deterministic JSON and YAML.
- `verifyAgentManifest` loads `baselineJson`, generates the current manifest, and
  fails when high-risk boundary widening is detected.

## Sample Shape

```yaml
agentsKtManifestVersion: 1
manifestSha256: "..."
format: "agents-kt.permission-manifest"
subject:
  agents:
    - "reviewer"
  type: "agent"
agents:
  -
    name: "reviewer"
    provider:
      provider: "openai"
      model: "gpt-4o-mini"
      apiKey: "masked"
      apiKeyPresent: true
    skills:
      -
        name: "review"
        toolAllowlist:
          - "readUploadedDocument"
    tools:
      -
        name: "readUploadedDocument"
        risk: "medium"
        policy:
          filesystem:
            read:
              globs:
                - "/uploads/**"
              mode: "globs"
            write:
              globs: []
              mode: "none"
          network:
            hosts: []
            mode: "denyAll"
composition:
  nodes:
    - "reviewer"
  type: "agent"
```
