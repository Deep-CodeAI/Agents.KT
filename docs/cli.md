# Agents.KT CLI — permission manifest from a binary (`#1923`)

The `agents-kt` CLI is the **"externally"** half of 0.7.0 ("boundaries you can enforce
externally"). The permission manifest — the deterministic, hashed capability surface of an
agent or composition — used to be reachable only through a Gradle task. The CLI exposes the
same logic to **non-Gradle consumers**: a CI gate, an ops engineer, or a regulator can
generate, inspect, and verify the manifest from a binary, with no build tool in the loop.

It wraps the exact `agents-kt-manifest` code the Gradle plugin uses
(`ManifestEntrypointLoader` + `PermissionManifest`), so **a build and the CLI produce
byte-identical manifests** (same `manifestSha256`).

## Build / run

The CLI lives in the `:agents-kt-cli` module (Gradle `application` plugin):

```bash
./gradlew :agents-kt-cli:installDist
./agents-kt-cli/build/install/agents-kt/bin/agents-kt --help
```

`installDist` produces a self-contained launcher + jars under
`build/install/agents-kt/` that runs on any JDK 21. `distZip` / `distTar` package it for
distribution. (A jlink/GraalVM-native single-file image is a packaging follow-up; the
`generate`/`verify --entrypoint` commands reflectively load arbitrary user classes from a
provided classpath, which needs a real JVM — so a bundled-JRE jlink image is the natural
distribution, not a closed-world native image.)

## Commands

```
agents-kt <command> [options]
```

| Command | What it does |
|---|---|
| `generate` | Generate the manifest from an entrypoint class |
| `inspect`  | Print a manifest file as json or yaml |
| `verify`   | Fail if a manifest widens a boundary vs an approved baseline |
| `help` / `--help` | Usage |
| `version` / `--version` | Version |

### `generate`

```bash
agents-kt generate --entrypoint com.acme.MyAgentManifest \
  --classpath app.jar:deps.jar \
  --format json \
  --out permissions.json
```

The entrypoint class is loaded by reflection from `--classpath` (path-separator list). It
may be a Kotlin `object` implementing `PermissionManifestProvider`, or expose a no-arg
`permissionManifest()` returning a `PermissionManifest`, an `Agent`, or any composition.
Without `--out` the manifest is printed to stdout; `--format` is `json` (default) or `yaml`.

### `inspect`

```bash
agents-kt inspect permissions.json --format yaml
```

Reads and re-renders a manifest file (round-trips through `PermissionManifest.fromJson`),
useful for converting json↔yaml or confirming the `manifestSha256`.

### `verify`

```bash
# current built from a live entrypoint, compared to a checked-in baseline:
agents-kt verify --entrypoint com.acme.MyAgentManifest --classpath app.jar --baseline permissions.baseline.json

# or compare two manifest files directly:
agents-kt verify --current permissions.json --baseline permissions.baseline.json
```

Fails (exit `1`) when the current manifest **widens** a boundary vs the baseline. Tools are matched
by `agentName.toolName`, and each policy is compared as an actual **set**, not a coarse score, so a
widening is caught even when a count is unchanged:

- `tool.risk.increased` — a tool's risk rose to high/critical
- `tool.network.widened` — network mode escalated (denyAll/unspecified → hosts → allowAll) **or** a
  host was added that the baseline didn't list
- `tool.filesystem.write.widened` / `tool.filesystem.read.widened` — a path glob was added that the
  baseline's set didn't contain (gaining globs from `writeNone`/none counts)
- `tool.environment.widened` — an environment variable was added to the allow-list

Pure narrowing (only *removing* globs/hosts/vars) is not flagged. The comparison is conservative on
*added* entries — full semantic glob-**coverage** subset-checking (recognizing that a broader glob
subsumes a narrower one) is a planned refinement, so an added glob string is treated as added
authority and flagged for review. The Gradle `verifyAgentManifest` task raises the same findings.
This is the drop-in CI gate for "no PR may quietly relax the agent's capability boundary."

## Exit codes

| Code | Meaning |
|---|---|
| `0` | ok |
| `1` | verify findings — manifest widened vs baseline |
| `2` | usage error (bad/missing arguments) |
| `3` | runtime error (entrypoint load, file not found, invalid manifest) |

## Relationship to the Gradle plugin

`./gradlew agentManifest` / `verifyAgentManifest` (the `ai.deep-code.agents-kt.manifest`
plugin) remain the in-build path. Both the plugin and the CLI now call the shared,
Gradle-free `agents_engine.manifest.ManifestEntrypointLoader`, so there is a single source of
truth for how an entrypoint becomes a manifest.
